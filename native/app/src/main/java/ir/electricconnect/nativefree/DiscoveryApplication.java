package ir.electricconnect.nativefree;

import android.app.*;
import android.os.*;
import android.content.*;

/** Foreground discovery may survive rotation, but never silently runs after app exit. */
public final class DiscoveryApplication extends Application {
    private int started;
    private final Handler queueHandler=new Handler(Looper.getMainLooper());
    private final Runnable queueDiscoveryCheck=new Runnable() {
        @Override public void run() {
            if(started<=0)return;
            // Foreground only, never background polling of an account.
            maybeAutoDiscover();
            QueueGapDiscovery.maybeSearchGroups(DiscoveryApplication.this);
            queueHandler.postDelayed(this,15000L);
        }
    };
    /** Independent opted-in web discovery and foreground READY resumption.
     * Neither discovery path sends or joins anything. */
    void maybeAutoDiscover() {
        SharedPreferences prefs=getSharedPreferences("discovery",MODE_PRIVATE);
        TdlibEngine engine=TdlibEngine.get(this);
        DiscoveryCoordinator discovery=DiscoveryCoordinator.get(this);
        if(discovery.isRunning())return;
        long now=System.currentTimeMillis();
        // Reopen a saved session after process death so an explicit pending
        // request can reach READY even when daily discovery is disabled.
        boolean telegramConfigured=engine.hasCredentials();
        if(prefs.getBoolean("telegram_pending",false) && !engine.isReady() && telegramConfigured)
            engine.connect();
        // An explicit pending search also resumes when daily automation is off.
        if(engine.isReady() && prefs.getBoolean("telegram_pending",false)) {
            long previous=prefs.getLong("telegram_auto_started",0);
            if(now<prefs.getLong("telegram_retry_after",0)
                    || now>=previous && now-previous<30L*60*1000)return;
            if(discovery.start(1,false))prefs.edit()
                .putLong("telegram_auto_started",now)
                .putLong("telegram_retry_after",now+30L*60*1000).apply();
            return;
        }
        // A public buyer/phone scan is its own engine. Opening the app can
        // start one bounded pass immediately; WorkManager persists the schedule.
        // Neither TDLib readiness nor queue state is required for this scan.
        if(!prefs.getBoolean("periodic",false))return;
        long previous=prefs.getLong("sales_auto_started",0);
        if(now>=previous && now-previous<24L*60*60*1000)return;
        if(DiscoveryCoordinator.webBackground(this).start(0,true))
            prefs.edit().putLong("sales_auto_started",now).apply();
    }
    void telegramReady() {
        // If opt-in ran before login, give the first group scan another safe
        // chance after READY. This never sends or joins anything.
        TelegramBackgroundSchedule.onAccountReady(this);
        if(started>0) {queueHandler.removeCallbacks(queueDiscoveryCheck);queueHandler.post(queueDiscoveryCheck);}
    }

    @Override public void onCreate() {
        super.onCreate();
        // Restore independent, user-enabled group discovery after Android
        // restarts the process; no UI or messaging consent is inferred.
        TelegramBackgroundSchedule.bootstrap(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) {
                if(started++==0) {
                    queueHandler.removeCallbacks(queueDiscoveryCheck);
                    queueHandler.post(queueDiscoveryCheck);
                }
            }
            @Override public void onActivityStopped(Activity a) {
                started=Math.max(0,started-1);
                if(started==0)queueHandler.removeCallbacks(queueDiscoveryCheck);
                if(a.isChangingConfigurations())return;
                new Handler(Looper.getMainLooper()).postDelayed(()->{if(started==0)DiscoveryCoordinator.get(DiscoveryApplication.this).stopForeground();},500);
            }
            @Override public void onActivityCreated(Activity a,Bundle b){}
            @Override public void onActivityResumed(Activity a){}
            @Override public void onActivityPaused(Activity a){}
            @Override public void onActivitySaveInstanceState(Activity a,Bundle b){}
            @Override public void onActivityDestroyed(Activity a){}
        });
    }
}
