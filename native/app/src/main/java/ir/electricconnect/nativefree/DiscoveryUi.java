package ir.electricconnect.nativefree;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.widget.*;
import org.json.JSONObject;

/** One consistent start/scope/stop panel; no network work is owned by an Activity. */
final class DiscoveryUi implements DiscoveryCoordinator.Listener {
    private final Activity activity;
    private final DiscoveryCoordinator coordinator;
    private final TextView status;
    private final Button start,stop;
    private final Runnable updated;
    private final android.os.Handler refresh=new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean attached=true;
    private final Runnable tick=new Runnable(){public void run(){
        if(!attached)return;
        renderStatus(coordinator.snapshot());refresh.postDelayed(this,2000L);
    }};
    DiscoveryUi(Activity activity,LinearLayout parent,Runnable updated) {
        this(activity,parent,updated,false);
    }
    DiscoveryUi(Activity activity,LinearLayout parent,Runnable updated,boolean compact) {
        this.activity=activity;this.updated=updated;coordinator=DiscoveryCoordinator.get(activity);
        if(!compact)ElectricUi.section(activity,parent,"کشف خودکار صنعت برق","ذخیرهٔ خودکار از منابع عمومی؛ کشف جدید به اینترنت نیاز دارد. کلید پولی لازم نیست.");
        android.content.SharedPreferences prefs=activity.getSharedPreferences("discovery",Context.MODE_PRIVATE);
        Spinner scope=new Spinner(activity);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{"مخاطبان تجاری","گروه‌های عمومی","هر دو"});
        scope.setAdapter(adapter);scope.setSelection(prefs.getInt("scope",2));
        scope.setContentDescription("محدودهٔ کشف خودکار");scope.setMinimumHeight(ElectricUi.dp(activity,48));
        if(!compact)ElectricUi.addBlock(activity,parent,scope,8);
        start=ElectricUi.button(activity,"شروع کشف خودکار",()->{
            int choice=scope.getSelectedItemPosition();prefs.edit().putInt("scope",choice).apply();coordinator.start(choice,false);changed(coordinator.snapshot());
        });
        if(!compact)ElectricUi.addBlock(activity,parent,start,8);
        status=ElectricUi.text(activity,"",13,ElectricUi.INK,false);
        stop=ElectricUi.button(activity,"توقف",()->{coordinator.stopByUser();status.setText("توقف درخواست شد؛ اطلاعات ذخیره‌شده محفوظ است.");},true,true);
        ElectricUi.addBlock(activity,parent,stop,6);
        ElectricUi.addBlock(activity,parent,status,8);
        coordinator.attach(this);
        refresh.postDelayed(tick,2000L);
    }
    @Override public void changed(JSONObject state) {
        if(activity.isFinishing()||activity.isDestroyed())return;
        renderStatus(state);
        start.setEnabled(!coordinator.isRunning());
        updateStop();
        if(updated!=null)updated.run();
    }
    private void renderStatus(JSONObject state){
        if(activity.isFinishing()||activity.isDestroyed())return;
        TdlibEngine engine=TdlibEngine.get(activity);
        status.setText("تلگرام: "+engine.connectionState()+"\n"+DiscoveryCoordinator.describe(state));
        updateStop();
    }
    private void updateStop(){
        boolean canStop=coordinator.isRunning()||activity.getSharedPreferences("discovery",Context.MODE_PRIVATE).getBoolean("telegram_pending",false);
        stop.setEnabled(canStop);stop.setVisibility(canStop?android.view.View.VISIBLE:android.view.View.GONE);
    }
    void detach(){attached=false;refresh.removeCallbacks(tick);coordinator.detach(this);}
    static void sources(Activity activity) {
        android.content.SharedPreferences prefs=activity.getSharedPreferences("discovery",Context.MODE_PRIVATE);
        LinearLayout form=ElectricUi.column(activity);form.setPadding(24,8,24,8);
        TextView explanation=ElectricUi.text(activity,"اختیاری: فقط نشانی امن و عمومی کسب‌وکارهایی که اجازهٔ استفاده از آن‌ها را دارید. هر نشانی در یک خط. منابع داخلی برنامه همیشه فعال‌اند.",13,ElectricUi.INK,false);form.addView(explanation);
        EditText seeds=ElectricUi.field(activity,"نشانی صفحات عمومی کسب‌وکار",true);seeds.setText(prefs.getString("seeds",""));form.addView(seeds);
        EditText searx=ElectricUi.field(activity,"نشانی جست‌وجوگر شخصی (اختیاری)",false);searx.setText(prefs.getString("searxng",""));form.addView(searx);
        EditText pages=ElectricUi.field(activity,"سقف صفحه برای هر سایت: ۱ تا ۶",false);pages.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);pages.setText(""+prefs.getInt("pages_per_host",4));form.addView(pages);
        EditText hosts=ElectricUi.field(activity,"سقف سایت در هر اجرا: ۱ تا ۸",false);hosts.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);hosts.setText(""+prefs.getInt("hosts_per_run",4));form.addView(hosts);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("منابع عمومی اختیاری").setView(form).setNegativeButton("انصراف",null).setPositiveButton("ذخیره",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try {
                String raw=seeds.getText().toString().trim();if(raw.length()>12000)throw new IllegalArgumentException();
                for(String url:raw.split("\\s+"))if(!url.isEmpty())PublicHttp.validate(url,null);
                String endpoint=searx.getText().toString().trim();if(!endpoint.isEmpty())PublicHttp.validate(endpoint,null);
                int pageLimit=Integer.parseInt(SearchText.digits(pages.getText().toString()));
                int hostLimit=Integer.parseInt(SearchText.digits(hosts.getText().toString()));
                if(pageLimit<1||pageLimit>6||hostLimit<1||hostLimit>8)throw new IllegalArgumentException();
                prefs.edit().putString("seeds",raw).putString("searxng",endpoint).putInt("pages_per_host",pageLimit).putInt("hosts_per_run",hostLimit).apply();dialog.dismiss();
            }catch(Exception e){Toast.makeText(activity,"نشانی امن بدون ورود و پارامتر، و سقف‌های معتبر وارد کنید.",Toast.LENGTH_LONG).show();}
        }));dialog.show();
    }
}
