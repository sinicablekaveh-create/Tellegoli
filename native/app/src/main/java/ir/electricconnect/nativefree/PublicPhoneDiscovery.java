package ir.electricconnect.nativefree;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * On-device capture of phone numbers explicitly published as BUSINESS contacts
 * in public web search results. No Telegram conversations, people/contacts API,
 * private messages, or account member lists are inspected.
 *
 * Discovery never sends messages or syncs saved contacts to Exa.
 */
final class PublicPhoneDiscovery {
    interface Progress {
        void changed(int processed,int total,int saved,int duplicates,int rejected);
    }
    static final class Summary {
        final int processed,saved,duplicates,rejected;
        Summary(int p,int s,int d,int r){
            processed=p;saved=s;duplicates=d;rejected=r;
        }
    }

    // Iran national fixed-line and mobile numbers, with optional +98/0098.
    // No eight-digit local number or ambiguous sequence is silently stored.
    private static final Pattern IRAN_PHONE=Pattern.compile(
        "(?<![0-9])(?:\\+98|0098|0)(?:[\\s().\\-]*[0-9]){10}(?![0-9])");
    private static final Pattern BUSINESS_CONTACT_LABEL=Pattern.compile(
        "(?:شماره\\s*تماس|تلفن(?:\\s*تماس)?|موبایل\\s*(?:شرکت|فروش|دفتر|تماس)?|"
        +"تماس\\s*با\\s*ما|واحد\\s*فروش|دفتر\\s*مرکزی|واحد\\s*بازرگانی|"
        +"پشتیبانی|سفارش(?:ات)?|contact|telephone|phone|tel\\s*[:.]|sales\\s*office)",
        Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
    // A business page may list telephone and fax numbers side by side.
    // An earlier "phone" heading must never authorize the later fax value.
    private static final Pattern FAX_LABEL=Pattern.compile(
        "(?:فکس|فاکس|فاكس|دورنگار|fax|facsimile)",
        Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
    static boolean hasDialableContactLabel(String beforeNumber) {
        Matcher contact=BUSINESS_CONTACT_LABEL.matcher(beforeNumber);
        int lastContact=-1;
        while(contact.find())lastContact=contact.start();
        if(lastContact<0)return false;
        Matcher fax=FAX_LABEL.matcher(beforeNumber);
        int lastFax=-1;
        while(fax.find())lastFax=fax.start();
        return lastFax<lastContact;
    }

    private static final Pattern BUSINESS_TITLE=Pattern.compile(
        "(?:شرکت|تولید|کارخانه|صنایع|صنعت|فروش|برق|کابل|تجهیزات|سینی|"
        +"تابلو|لوازم|پیمانکار|فروشگاه|تامین|تأمین|الکتریک|مهندسی|"
        +"company|industr|cable|electri|manufactur|supplier)",
        Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);

    private PublicPhoneDiscovery(){}

    static String normalizeIranPhone(String raw){
        if(raw==null)return null;
        String digits=asciiDigits(raw).replaceAll("[^0-9+]","");
        if(digits.startsWith("+98"))digits="0"+digits.substring(3);
        else if(digits.startsWith("0098"))digits="0"+digits.substring(4);
        if(!digits.matches("0[0-9]{10}"))return null;
        if(!digits.matches("(?:09[0-9]{9}|0(?:11|13|17|21|23|24|25|26|28|31|34|35|38|41|44|45|51|54|56|58|61|66|71|74|76|77|81|83|84|86|87|88)[0-9]{8})"))return null;
        if(digits.matches("(\\d)\\1{10}"))return null;
        return digits;
    }

    private static String asciiDigits(String input) {
        StringBuilder out=new StringBuilder(input.length());
        for(int i=0;i<input.length();i++){
            char c=input.charAt(i);
            if(c>='۰'&&c<='۹')c=(char)('0'+c-'۰');
            else if(c>='٠'&&c<='٩')c=(char)('0'+c-'٠');
            out.append(c);
        }
        return out.toString();
    }

    static String canonicalSource(String source){
        if(source==null || source.length()<10 || source.length()>1600)return null;
        try {
            URI u=new URI(source.trim());
            String scheme=u.getScheme(),host=u.getHost();
            if(scheme==null||host==null||u.getUserInfo()!=null)return null;
            scheme=scheme.toLowerCase(Locale.ROOT);
            host=host.toLowerCase(Locale.ROOT);
            if(!scheme.equals("https")&&!scheme.equals("http"))return null;
            if(!host.contains(".")||host.equals("localhost")||host.endsWith(".local")
                    ||host.endsWith(".internal")||host.equals("127.0.0.1"))return null;
            // A social/chat profile or personal directory is not a verified
            // business contact page. Never infer a person's phone from it.
            if(host.equals("t.me")||host.endsWith(".t.me")
                ||host.endsWith("telegram.me")||host.endsWith("instagram.com")
                ||host.endsWith("facebook.com")||host.endsWith("linkedin.com")
                ||host.endsWith("wa.me")||host.endsWith("whatsapp.com"))return null;
            return new URI(scheme,null,host,u.getPort(),
                u.getPath()==null||u.getPath().isEmpty()?"/":u.getPath(),
                u.getQuery(),null).toASCIIString();
        }catch(Exception ignored){return null;}
    }

    static Summary save(MainActivity.Store store,JSONArray results,Progress progress){
        return save(store,results,progress,()->!Thread.currentThread().isInterrupted());
    }
    static Summary save(MainActivity.Store store,JSONArray results,Progress progress,java.util.function.BooleanSupplier active) {
        return save(store,results,progress,active,false);
    }
    static Summary saveBuyers(MainActivity.Store store,JSONArray results,Progress progress,java.util.function.BooleanSupplier active) {
        return save(store,results,progress,active,true);
    }
    private static Summary save(MainActivity.Store store,JSONArray results,Progress progress,java.util.function.BooleanSupplier active,boolean buyersOnly) {
        int total=results==null?0:results.length();
        int processed=0,saved=0,duplicates=0,rejected=0;
        Set<String> seen=new HashSet<>();
        for(int i=0;i<total;i++){
            if(!active.getAsBoolean())break;
            JSONObject result=results.optJSONObject(i);
            String title=result==null?"":result.optString("title","").trim();
            String url=result==null?"":canonicalSource(result.optString("url",""));
            String fullText=result==null?"":result.optString("text","");
            if(fullText.length()>200000)fullText=fullText.substring(0,200000);
            if(fullText.isEmpty() && result!=null) {
                JSONArray highlights=result.optJSONArray("highlights");
                if(highlights!=null){
                    StringBuilder builder=new StringBuilder();
                    for(int j=0;j<Math.min(12,highlights.length());j++)
                        builder.append(highlights.optString(j,"")).append('\n');
                    fullText=builder.toString();
                }
            }
            // Never save a result without a public source, business context,
            // and an explicit phone/contact label near the number.
            if(title.length()<2 || url==null ||
                    !BUSINESS_TITLE.matcher(title).find() || fullText.isEmpty()
                    || buyersOnly&&!BuyerRelevance.accepts(title,fullText)){
                rejected++;
            }else{
                String body=asciiDigits(fullText);
                Matcher phones=IRAN_PHONE.matcher(body);
                int valid=0;
                while(active.getAsBoolean()&&phones.find()){
                    int start=Math.max(0,phones.start()-110);
                    String around=body.substring(start,phones.start());
                    if(!hasDialableContactLabel(around))continue;
                    String phone=normalizeIranPhone(phones.group());
                    if(phone==null)continue;
                    valid++;
                    if(!seen.add(phone)){duplicates++;continue;}
                    if(store.insertPublicBusinessPhone(
                            title.substring(0,Math.min(title.length(),240)),phone,url,result.optString("provider","Exa اختیاری")))saved++;
                    else duplicates++;
                }
                if(valid==0)rejected++;
            }
            processed++;
            if(progress!=null)progress.changed(processed,total,saved,duplicates,rejected);
        }
        return new Summary(processed,saved,duplicates,rejected);
    }
}
