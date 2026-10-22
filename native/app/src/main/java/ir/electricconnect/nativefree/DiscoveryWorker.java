package ir.electricconnect.nativefree;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Only opted-in public web work. Never opens or logs into a headless personal session. */
public final class DiscoveryWorker extends Worker {
    public DiscoveryWorker(@NonNull Context app,@NonNull WorkerParameters params){super(app,params);}
    /** Keep background discovery aligned with the last foreground choice. */
    static int selectedScope(Context app) {
        int value=app.getSharedPreferences("discovery",Context.MODE_PRIVATE).getInt("scope",2);
        return value>=0 && value<=2 ? value : 2;
    }

    @NonNull @Override public Result doWork() {
        Context app=getApplicationContext();
        android.content.SharedPreferences preferences=app.getSharedPreferences("discovery",Context.MODE_PRIVATE);
        if(!preferences.getBoolean("periodic",false))return Result.success();
        int selected=selectedScope(app);
        preferences.edit().putLong("last_attempt",System.currentTimeMillis()).apply();
        // User selected groups only: defer TDLib access until an authorized
        // account is READY in the foreground. Never crawl business sites instead.
        if(selected==1) {
            preferences.edit().putBoolean("telegram_pending",true).apply();
            return Result.success();
        }
        DiscoveryCoordinator coordinator=DiscoveryCoordinator.get(app);
        if(!coordinator.start(selected,true))return Result.success();
        try {
            while(coordinator.isRunning()&&!isStopped())Thread.sleep(200);
            if(isStopped()) {coordinator.stop();return Result.success();}
            org.json.JSONObject state=coordinator.snapshot();
            if(state.optInt("pages")>0)app.getSharedPreferences("discovery",Context.MODE_PRIVATE).edit().putLong("last_success",System.currentTimeMillis()).putInt("last_new",state.optInt("saved")).apply();
        } catch(InterruptedException e) {Thread.currentThread().interrupt();coordinator.stop();}
        return Result.success();
    }
}
