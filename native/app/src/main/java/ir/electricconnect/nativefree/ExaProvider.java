package ir.electricconnect.nativefree;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit opt-in enhancement: only industry topics leave the device. */
final class ExaProvider implements DiscoveryProvider {
    interface Search {JSONObject search() throws Exception;}
    private final Search search;
    private final Context app;
    ExaProvider(Context app) {this.app=app;this.search=null;}
    ExaProvider(Search search){this.app=null;this.search=search;}
    @Override public String name(){return "Exa اختیاری";}
    @Override public void discover(DiscoveryCoordinator.Run run) throws Exception {
        if(!run.active())return;
        JSONObject response;
        if(app==null)response=search.search();
        else {
            if(!app.getSharedPreferences("exa_ui_settings",Context.MODE_PRIVATE).getBoolean("online_enabled",false))return;
            String key=ExaResearchActivity.KeyVault.load(app);
            if(key==null||key.isEmpty()) {run.note="کلید شخصی Exa ثبت نشده؛ منابع رایگان ادامه دارند.";return;}
            String topic=run.webTopic("exa");run.current=name()+": "+topic;run.emit();
            response=ExaResearchActivity.callExa(key,topic+" شماره تماس دفتر شرکت ایران", "auto",10,false);
            run.advanceWebTopic("exa");
        }
        JSONArray results=response.optJSONArray("results");
        if(results!=null)for(int i=0;i<Math.min(10,results.length())&&run.active();i++) {
            JSONObject row=results.optJSONObject(i);if(row==null)continue;
            try {run.seeds.add(PublicHttp.validate(row.optString("url"),null));}catch(Exception ignored){}
        }
    }
}
