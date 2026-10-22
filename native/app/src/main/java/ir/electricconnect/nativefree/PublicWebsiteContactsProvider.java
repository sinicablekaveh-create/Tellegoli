package ir.electricconnect.nativefree;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.URI;
import java.util.*;

/** Bounded, robots-aware public website import; HTML only, no logged-in browser. */
final class PublicWebsiteContactsProvider implements DiscoveryProvider {
    // Public websites of electrical installers and EPC contractors: potential buyers,
    // not cable-tray manufacturers. Only published business contacts are extracted.
    static final List<String> DEFAULT_SEEDS=Arrays.asList(
        "https://nicsaco.com/industrial-power-system-design/",
        "https://www.rastak-eng.com/",
        "https://fidarmandegar.ir/projects/",
        "https://www.falatco.com/fa/contact",
        "https://saha.co.ir/",
        "https://powertechno.ir/پیمانکاری-برق-صنعتی/"
    );
    /** Change the first websites between runs so a four-host cap does not starve later buyers. */
    static List<String> rotatedSeeds(int start) {
        List<String> order=new ArrayList<>();
        for(int i=0;i<DEFAULT_SEEDS.size();i++)
            order.add(DEFAULT_SEEDS.get(Math.floorMod(start+i,DEFAULT_SEEDS.size())));
        return order;
    }
    // These were legacy supplier seeds; never silently reuse them as buyer leads.
    private static boolean oldSupplierSeed(String host) {
        return "cableiran.com".equals(host) || "www.cableiran.com".equals(host)
            || "mashadcable.ir".equals(host) || "www.mashadcable.ir".equals(host)
            || "fa.yazdcable.com".equals(host) || "yazdcable.com".equals(host);
    }
    private final PublicHttp.Transport transport;
    private final Map<String,RobotsRules> robots=new HashMap<>();
    private final Map<String,Integer> requests=new HashMap<>(),pages=new HashMap<>();
    private final Map<String,Long> lastRequest=new HashMap<>();
    PublicWebsiteContactsProvider(PublicHttp.Transport transport){this.transport=transport;}
    @Override public String name(){return "وب‌سایت‌های عمومی";}
    @Override public void discover(DiscoveryCoordinator.Run run) throws Exception {
        Set<String> hosts=new LinkedHashSet<>();
        // Search providers run before this website importer: their discovered
        // URLs must be checked before the built-in fallback sites, otherwise
        // maxHosts can silently discard every Exa/SearXNG result.
        List<String> sources=new ArrayList<>(run.seeds);
        sources.addAll(rotatedSeeds(run.buyerSeedCursor));
        for(String seed:sources) {
            try {String safe=PublicHttp.validate(seed,null);String host=URI.create(safe).getHost();
                if(!oldSupplierSeed(host) && (hosts.contains(host)||hosts.size()<run.maxHosts)) {hosts.add(host);run.data.queue(safe);}
            }catch(IOException e){run.error("نشانی منبع مجاز نیست؛ فقط وب‌سایت عمومی امن");}
        }
        // Restore pending same-host contact links without accepting arbitrary persisted destinations.
        for(String url:run.data.pending(100))if(hosts.size()<run.maxHosts) {
            try {String safe=PublicHttp.validate(url,null);String host=URI.create(safe).getHost();if(!oldSupplierSeed(host))hosts.add(host);}catch(IOException ignored){}
        }
        boolean worked=false;Set<String> attempted=new HashSet<>();
        for(int round=0;round<run.maxHosts*run.maxPages+8 && run.active();round++) {
            List<String> pending=run.data.pending(100);String url=null;
            for(String candidate:pending) {
                String host=URI.create(candidate).getHost();
                if(hosts.contains(host) && !attempted.contains(candidate) && pages.getOrDefault(host,0)<run.maxPages) {url=candidate;break;}
            }
            if(url==null)break;
            attempted.add(url);
            String host=URI.create(url).getHost();
            pages.put(host,pages.getOrDefault(host,0)+1);
            run.current=name()+": "+host;run.topic=1;run.total=1;run.emit();
            try {
                RobotsRules rules=robots.get(host);
                if(rules==null) {
                    PublicHttp.Page policy=request("https://"+host+"/robots.txt",hosts,run,1500);
                    if(policy.code==404||policy.code==410)rules=new RobotsRules("");
                    else if(policy.code==200 && (policy.type.contains("text")||policy.type.isEmpty()))rules=new RobotsRules(policy.body);
                    else throw new PublicHttp.AccessDenied();
                    robots.put(host,rules);
                }
                PublicHttp.Page page=fetch(url,hosts,run,rules,0);
                if(!run.active())throw new InterruptedIOException();
                if(page.code>=300&&page.code<400) {run.data.finishUrl(url,"done");run.emit();continue;}
                if(page.code<200||page.code>=300)throw new PublicHttp.AccessDenied();
                run.pages++;worked=true;
                if(page.type.contains("html")) {
                    Document doc=Jsoup.parse(page.body,url);
                    if(isAccessChallenge(doc))throw new PublicHttp.AccessDenied();
                    if(doc.select("meta[name=robots]").stream().anyMatch(e->e.attr("content").toLowerCase(Locale.ROOT).matches(".*(noindex|nofollow|noarchive|none).*$")))throw new PublicHttp.AccessDenied();
                    doc.select("script,style,noscript,form,nav,#comments,.comments-area,.comment-list,#respond").remove();
                    String title=doc.title();
                    String business=doc.select("meta[property=og:site_name]").attr("content");
                    if(!business.isEmpty())title=business+" — "+title;
                    StringBuilder text=new StringBuilder(doc.body()==null?"":doc.body().text());
                    // tel links explicitly on a business page are contact context, never account members.
                    for(Element a:doc.select("a[href^=tel:]"))text.append("\nتلفن تماس: ").append(a.attr("href").substring(4));
                    PublicPhoneDiscovery.Summary summary=PublicPhoneDiscovery.saveBuyers(run.data.store,new JSONArray().put(
                        new JSONObject().put("title",title).put("url",url).put("text",text.toString()).put("provider","وب‌سایت عمومی")),null,run::active);
                    run.saved+=summary.saved;run.duplicates+=summary.duplicates;
                    for(Element a:doc.select("a[href]")) {
                        String label=SearchText.normalize(a.text()+" "+a.attr("href"));
                        if(label.matches(".*(contact|about|تماس|درباره|ارتباط|sitemap|feed).*$"))queueLink(url,a.attr("href"),hosts,run);
                    }
                    for(String sitemap:rules.sitemaps)queueLink(url,sitemap,hosts,run);
                } else if(page.type.contains("xml")) {
                    Document doc=Jsoup.parse(page.body,url,org.jsoup.parser.Parser.xmlParser());
                    for(Element e:doc.select("loc, link")) {
                        String link=e.hasAttr("href")?e.attr("href"):e.text();
                        String decoded=java.net.URLDecoder.decode(link,"UTF-8").toLowerCase(Locale.ROOT);
                        if(decoded.matches(".*(contact|about|تماس|درباره|sitemap|feed).*$"))queueLink(url,link,hosts,run);
                    }
                }
                // Save result and checkpoint before the next network operation.
                run.data.finishUrl(url,"done");run.emit();
            } catch(InterruptedIOException e) {
                if(!run.active()) {run.data.finishUrl(url,"interrupted");throw e;}
                run.data.finishUrl(url,"retry");run.error("زمان دریافت منبع پایان یافت؛ در اجرای بعد می‌توان ادامه داد.");
            }
            catch(Exception e) {
                run.data.finishUrl(url,e instanceof PublicHttp.AccessDenied?"denied":"retry");
                run.error(e instanceof PublicHttp.AccessDenied?"منبع اجازهٔ دسترسی نداد؛ صفحات قبلی محفوظ است":"خطا یا محدودیت اتصال منبع؛ تلاش محدود و ادامهٔ منابع دیگر");
            }
        }
        if(!worked)run.note="صفحهٔ تازه‌ای دریافت نشد؛ منابع ذخیره‌شده یا تنظیمات منابع را بررسی کنید.";
    }
    /** Optional contact-form captcha scripts do not make the public page a challenge.
     * No challenge is solved, submitted, or bypassed. */
    static boolean isAccessChallenge(Document doc) {
        String title=SearchText.normalize(doc.title());
        if(title.matches(".*(just a moment|attention required|access denied|captcha|verify.*human|بررسی امنیت|کپچا).*$"))return true;
        if(!doc.select("#challenge-form,#challenge-running,#cf-challenge-running").isEmpty())return true;
        Document visible=doc.clone();visible.select("script,style,noscript,form").remove();
        String text=visible.body()==null?"":SearchText.normalize(visible.body().text());
        return text.length()<1500 && text.matches(".*(verify you are human|checking your browser|complete the captcha|تایید کنید.*انسان).*$");
    }
    private void queueLink(String base,String link,Set<String> hosts,DiscoveryCoordinator.Run run) {
        if(!run.active())return;
        try {String url=PublicHttp.validate(URI.create(base).resolve(link).toString(),hosts);if(run.data.pending(100).size()<80)run.data.queue(url);}catch(Exception ignored){}
    }
    private PublicHttp.Page fetch(String url,Set<String> hosts,DiscoveryCoordinator.Run run,RobotsRules rules,int redirects) throws Exception {
        String safe=PublicHttp.validate(url,hosts);
        URI uri=URI.create(safe);
        if(!rules.allows(uri.getRawPath())||!rules.allows(java.net.URLDecoder.decode(uri.getRawPath(),"UTF-8"))||rules.delayMillis>30000)throw new PublicHttp.AccessDenied();
        PublicHttp.Page page=request(safe,hosts,run,rules.delayMillis);
        if(page.code>=300&&page.code<400) {
            if(redirects>=3)throw new PublicHttp.AccessDenied();
            String next=PublicHttp.validate(uri.resolve(page.location).toString(),Collections.singleton(uri.getHost()));
            run.data.queue(next);
            return page;
        }
        return page;
    }
    private PublicHttp.Page request(String url,Set<String> hosts,DiscoveryCoordinator.Run run,long delay) throws Exception {
        String host=URI.create(url).getHost();
        for(int attempt=0;attempt<3;attempt++) {
            if(!run.active())throw new InterruptedIOException();
            int count=requests.getOrDefault(host,0);if(count>=12)throw new PublicHttp.Limited();
            long wait=Math.max(0,lastRequest.getOrDefault(host,0L)+delay-System.currentTimeMillis());run.pause(wait);
            requests.put(host,count+1);lastRequest.put(host,System.currentTimeMillis());
            PublicHttp.Page response=transport.get(url,hosts);
            if(response.code!=429 && response.code<500)return response;
            if(attempt==2)throw new PublicHttp.Limited();
            run.pause(1500L<<attempt);
        }
        throw new PublicHttp.Limited();
    }
}
