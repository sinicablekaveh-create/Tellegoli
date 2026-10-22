package ir.electricconnect.nativefree;

import android.content.Context;
import androidx.work.*;
import java.util.concurrent.TimeUnit;

final class DiscoverySchedule {
    static final String WORK="electric-public-discovery-daily";
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
