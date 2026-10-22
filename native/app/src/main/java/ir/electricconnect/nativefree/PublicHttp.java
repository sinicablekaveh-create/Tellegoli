package ir.electricconnect.nativefree;

import okhttp3.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** No cookies, credentials, automatic redirects, private IPs, or insecure TLS. */
final class PublicHttp implements AutoCloseable {
    interface Transport { Page get(String url, Set<String> hosts) throws IOException; }
    static final class Page {
        final int code;final String body,type,location;
        Page(int code,String body,String type,String location){this.code=code;this.body=body;this.type=type;this.location=location;}
    }
    static final class AccessDenied extends IOException { AccessDenied(){super("دسترسی این منبع مجاز نیست");} }
    static final class Limited extends IOException { Limited(){super("محدودیت موقت منبع؛ تلاش محدود پایان یافت");} }
    final AtomicBoolean cancelled;
    final OkHttpClient client;
    PublicHttp(AtomicBoolean cancelled) {
        this.cancelled=cancelled;
        client=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(8,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS).callTimeout(12,TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).dns(host->{
                List<InetAddress> addresses=Dns.SYSTEM.lookup(host);
                if(addresses.isEmpty())throw new UnknownHostException();
                List<InetAddress> ipv4=new ArrayList<>();
                for(InetAddress address:addresses) {
                    if(address.getAddress().length==16) {
                        // Ignore public IPv6 on dual-stack hosts; never fall back to private IPv6.
                        if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress()||(address.getAddress()[0]&0xe0)!=0x20)throw new UnknownHostException("نشانی عمومی نیست");
                    } else {if(!isPublic(address))throw new UnknownHostException("نشانی عمومی نیست");ipv4.add(address);}
                }
                if(ipv4.isEmpty())throw new UnknownHostException("نشانی عمومی پشتیبانی‌شده یافت نشد");
                return ipv4;
            }).build();
    }
    static boolean isPublic(InetAddress ip) {
        if(ip.isAnyLocalAddress()||ip.isLoopbackAddress()||ip.isLinkLocalAddress()||ip.isSiteLocalAddress()||ip.isMulticastAddress())return false;
        byte[] b=ip.getAddress();
        // IPv4 only: reject IPv6 translation, mapped-address and routing ambiguity.
        if(b.length!=4)return false;
        int a=b[0]&255,c=b[1]&255,d=b[2]&255;
        return !(a==0||a==10||a==127||a>=224||a==169&&c==254||a==172&&c>=16&&c<=31||a==192&&(c==168||c==0||c==2)||a==100&&c>=64&&c<=127||a==198&&(c==18||c==19||c==51&&d==100)||a==203&&c==0&&d==113);
    }
    static String validate(String raw,Set<String> hosts) throws AccessDenied {
        try {
            URI uri=new URI(raw).normalize();String host=uri.getHost();
            if(host==null||!"https".equalsIgnoreCase(uri.getScheme())||uri.getRawUserInfo()!=null||uri.getPort()!=-1&&uri.getPort()!=443||raw.length()>1600)throw new AccessDenied();
            host=host.toLowerCase(Locale.ROOT);
            if(!host.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")||!host.contains(".")||host.matches("[0-9.]+")||host.endsWith(".local")||host.endsWith(".internal")||host.endsWith(".localhost")||host.endsWith(".test")||hosts!=null&&!hosts.contains(host))throw new AccessDenied();
            if(PublicPhoneDiscovery.canonicalSource(raw)==null)throw new AccessDenied();
            String path=URLDecoder.decode(uri.getRawPath()==null?"/":uri.getRawPath(),"UTF-8").toLowerCase(Locale.ROOT);
            if(path.contains("%")||path.contains("\\"))throw new AccessDenied();
            if(path.matches(".*(?:login|sign-in|my-account|wp-admin|checkout|privacy|terms|cart|account|شرایط|قوانین|حریم).*")||uri.getRawQuery()!=null)throw new AccessDenied();
            return new URI("https",null,host,-1,uri.getPath()==null||uri.getPath().isEmpty()?"/":uri.getPath(),null,null).normalize().toASCIIString();
        } catch(Exception e) {throw new AccessDenied();}
    }
    Page get(String url,Set<String> hosts) throws IOException {
        String safe=validate(url,hosts);if(cancelled.get())throw new InterruptedIOException();
        Request request=new Request.Builder().url(safe).header("User-Agent","ElectricConnect/0.10 (+public business contacts; no login)").header("Accept","text/html,application/xml,text/plain,application/json").build();
        return execute(request);
    }
    Page json(String url) throws IOException {
        URI uri=URI.create(url);
        // Query parameters are constructed locally; endpoint itself uses the same public policy.
        validate(new URIBuilder(uri).withoutQuery(),Collections.singleton(uri.getHost()));
        if(cancelled.get())throw new InterruptedIOException();
        return execute(new Request.Builder().url(url).header("Accept","application/json").build());
    }
    private Page execute(Request request) throws IOException {
        Call call=client.newCall(request);
        if(cancelled.get())throw new InterruptedIOException();
        try(Response response=call.execute()) {
            ResponseBody body=response.body();
            String type=response.header("Content-Type","");
            if(body==null)return new Page(response.code(),"",type,response.header("Location",""));
            if(body.contentLength()>1_048_576)throw new IOException("حجم صفحه بیش از حد مجاز است");
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=body.byteStream()) {
                byte[] buffer=new byte[4096];int n;
                while((n=in.read(buffer))!=-1) {
                    if(cancelled.get())throw new InterruptedIOException();
                    if(out.size()+n>1_048_576)throw new IOException("حجم صفحه بیش از حد مجاز است");
                    out.write(buffer,0,n);
                }
            }
            java.nio.charset.Charset charset=body.contentType()==null?java.nio.charset.StandardCharsets.UTF_8:body.contentType().charset(java.nio.charset.StandardCharsets.UTF_8);
            return new Page(response.code(),new String(out.toByteArray(),charset),type,response.header("Location",""));
        }
    }
    void cancel(){cancelled.set(true);client.dispatcher().cancelAll();}
    @Override public void close(){client.connectionPool().evictAll();}
    private static final class URIBuilder {
        final URI uri;URIBuilder(URI u){uri=u;}
        String withoutQuery() {try{return new URI(uri.getScheme(),uri.getRawAuthority(),uri.getPath(),null,null).toString();}catch(Exception e){return "";}}
    }
}
