package ir.electricconnect.nativefree;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLEncoder;

/** User-owned supplementary JSON endpoint. No default third-party endpoint. */
final class SearXngSeedProvider implements DiscoveryProvider {
    interface JsonFetch {PublicHttp.Page get(String url)throws Exception;}
    private final String endpoint;private final JsonFetch fetch;
    SearXngSeedProvider(String endpoint,PublicHttp http){this(endpoint,http::json);}
    SearXngSeedProvider(String endpoint,JsonFetch fetch){this.endpoint=endpoint;this.fetch=fetch;}
    @Override public String name(){return "جست‌وجوگر اختیاری";}
    @Override public void discover(DiscoveryCoordinator.Run run)throws Exception {
        String safe=PublicHttp.validate(endpoint,null);
        if(!run.active())return;
        String topic=run.webTopic("searxng");run.current=name()+": "+topic;run.emit();
        PublicHttp.Page page=fetch.get(safe+"?format=json&language=fa&q="+URLEncoder.encode(topic+" تماس دفتر شرکت","UTF-8"));
        if(page.code!=200||!page.type.contains("json"))throw new IllegalStateException();
        JSONArray results=new JSONObject(page.body).getJSONArray("results");
        run.advanceWebTopic("searxng");
        for(int i=0;i<Math.min(10,results.length())&&run.active();i++) {
            try {run.seeds.add(PublicHttp.validate(results.getJSONObject(i).optString("url"),null));}catch(Exception ignored){}
        }
    }
}
