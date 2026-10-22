package ir.electricconnect.nativefree;

import android.content.Context;
import androidx.work.*;
import java.util.concurrent.TimeUnit;

final class DiscoverySchedule {
    static final String WORK="tellegoli-public-web-discovery";
    static final int WEB_INTERVAL_MINUTES=15;
    /**
     * Tellegoli web discovery every 15 minutes by default
     * is enabled on first launch, with a visible switch to disable it at any time.
     * Never schedules Telegram account searches or message sends headlessly.
     */
    static void bootstrap(Context app) {
        android.content.SharedPreferences settings=
            app.getSharedPreferences("discovery",Context.MODE_PRIVATE);
        if(!settings.getBoolean("simple_automation_initialized",false)) {
            // Honor a previous explicit OFF choice when upgrading an installation.
            boolean periodic=settings.contains("periodic")
                ?settings.getBoolean("periodic",false)
                :settings.getBoolean("sales_auto_enabled",true);
            settings.edit()
                .putBoolean("simple_automation_initialized",true)
                .putBoolean("sales_auto_enabled",periodic)
                .putBoolean("periodic",periodic)
                .putBoolean("periodic_15_minutes",true)
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
    static int intervalMinutes(Context app) {
        return app.getSharedPreferences("discovery",Context.MODE_PRIVATE)
            .getBoolean("periodic_15_minutes",true)?WEB_INTERVAL_MINUTES:1440;
    }
    static PeriodicWorkRequest request(Context app) {
        int minutes=intervalMinutes(app);
        return new PeriodicWorkRequest.Builder(DiscoveryWorker.class,minutes,TimeUnit.MINUTES)
            // The first unattended scan is due after the first interval;
            // foreground one-tap discovery remains immediately available.
            .setInitialDelay(minutes,TimeUnit.MINUTES)
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
