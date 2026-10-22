package ir.electricconnect.nativefree;

import android.content.Context;
import androidx.work.*;
import java.util.concurrent.TimeUnit;

final class DiscoverySchedule {
    static final String WORK="electric-public-discovery-daily";
    /**
     * New simple-mode default requested for Tellegoli: daily public-web discovery
     * is enabled on first launch, with a visible switch to disable it at any time.
     * Never schedules Telegram account searches or message sends headlessly.
     */
    static void bootstrap(Context app) {
        android.content.SharedPreferences settings=
            app.getSharedPreferences("discovery",Context.MODE_PRIVATE);
        if(!settings.getBoolean("simple_automation_initialized",false)) {
            settings.edit()
                .putBoolean("simple_automation_initialized",true)
                .putBoolean("sales_auto_enabled",true)
                .putBoolean("periodic",true)
                .putBoolean("periodic_15_minutes",false)
                .putInt("scope",2).apply();
        }
        if(settings.getBoolean("periodic",false)) {
            try {
                WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                    WORK,ExistingPeriodicWorkPolicy.KEEP,request(app));
            } catch(IllegalStateException ignored) {
                // WorkManager may not yet be available during test bootstrap;
                // the daily preference remains visible and can be retried.
            }
        }
    }
    static PeriodicWorkRequest request(Context app) {
        int minutes=app.getSharedPreferences("discovery",Context.MODE_PRIVATE).getBoolean("periodic_15_minutes",false)?15:1440;
        return new PeriodicWorkRequest.Builder(DiscoveryWorker.class,minutes,TimeUnit.MINUTES)
            .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.MINUTES).build();
    }
    static void enable(Context app) {
        app.getSharedPreferences("discovery",Context.MODE_PRIVATE).edit().putBoolean("periodic",true).apply();
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(WORK,ExistingPeriodicWorkPolicy.UPDATE,request(app));
    }
    static void setFifteenMinutes(Context app,boolean enabled) {
        app.getSharedPreferences("discovery",Context.MODE_PRIVATE).edit().putBoolean("periodic_15_minutes",enabled).apply();
        if(app.getSharedPreferences("discovery",Context.MODE_PRIVATE).getBoolean("periodic",false))enable(app);
    }
    static void disable(Context app) {
        app.getSharedPreferences("discovery",Context.MODE_PRIVATE).edit().putBoolean("periodic",false).putBoolean("telegram_pending",false).apply();
        try {WorkManager.getInstance(app).cancelUniqueWork(WORK);}catch(IllegalStateException ignored) { /* Before WorkManager initialization the disabled preference still gates the worker. */ }
    }
}
