package ir.electricconnect.nativefree;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

/**
 * A separate, bounded discovery pass during approved messages' eight-minute
 * quiet intervals. Web contact data may be fetched by WorkManager; Telegram
 * public-group discovery only occurs with a READY account AND visible UI.
 * Discoveries never become recipients or send approvals automatically.
 */
final class QueueGapDiscovery {
    static final String WORK="electric-approved-queue-public-web-gap";
    private static final String PREF="queue_gap_discovery";
    private static final long GROUP_INTERVAL=ScheduledQueue.MIN_GAP;
    private static final Object LOCK=new Object();

    private QueueGapDiscovery(){}

    static boolean enabled(Context context){
        return context.getSharedPreferences(PREF,Context.MODE_PRIVATE).getBoolean("enabled",true);
    }

    static void setEnabled(Context context,boolean allowed) {
        context.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putBoolean("enabled",allowed).apply();
        if(!allowed) cancel(context);
        else if(ScheduledQueue.enabled(context)) scheduleWeb(context);
    }

    static void scheduleWeb(Context context){
        if(!enabled(context) || !ScheduledQueue.enabled(context))return;
        OneTimeWorkRequest work=new OneTimeWorkRequest.Builder(WebWorker.class)
            .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true).build()).build();
        WorkManager.getInstance(context).enqueueUniqueWork(WORK,ExistingWorkPolicy.KEEP,work);
    }

    static void cancel(Context context){
        WorkManager.getInstance(context).cancelUniqueWork(WORK);
        DiscoveryCoordinator.get(context).stopQueuePass();
    }

    /** Called by Application only while an Activity is started and account READY. */
    static void maybeSearchGroups(Context context) {
        if(!enabled(context) || !ScheduledQueue.enabled(context)
                || !TdlibEngine.get(context).isReady())return;
        synchronized(LOCK) {
            SharedPreferences p=context.getSharedPreferences(PREF,Context.MODE_PRIVATE);
            long now=System.currentTimeMillis();
            long previous=p.getLong("telegram_started",0);
            if(now-previous<GROUP_INTERVAL)return;
            // Queue-only scope: one keyword per pass, with a persisted round-robin cursor.
            if(DiscoveryCoordinator.get(context).start(3,false))
                p.edit().putLong("telegram_started",now).apply();
        }
    }

    public static final class WebWorker extends Worker {
        public WebWorker(@NonNull Context context,@NonNull WorkerParameters params){
            super(context,params);
        }

        @NonNull @Override public Result doWork(){
            Context app=getApplicationContext();
            if(!enabled(app) || !ScheduledQueue.enabled(app))return Result.success();
            DiscoveryCoordinator coordinator=DiscoveryCoordinator.get(app);
            // If an interactive search already runs, do not interrupt or replace it.
            if(!coordinator.start(0,true))return Result.success();
            try {
                while(coordinator.isRunning()){
                    if(isStopped() || !enabled(app) || !ScheduledQueue.enabled(app)){
                        coordinator.stop();break;
                    }
                    Thread.sleep(250);
                }
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                coordinator.stop();
            }
            return Result.success();
        }
    }
}
