package ir.electricconnect.nativefree;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;

/**
 * Explicit opt-in background search of PUBLIC Telegram group titles/usernames.
 * Runs a single industry keyword on each Android-scheduled pass. No message
 * sending, joining, scraping participants or phone discovery from Telegram.
 * Android may delay/skip background executions during Doze or force-stop.
 */
final class TelegramBackgroundSchedule {
    static final String WORK="tellegoli-public-telegram-group-discovery";
    static final String PREF="telegram_background_discovery";
    static final long INTERVAL_MINUTES=15L;
    private TelegramBackgroundSchedule(){}

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);
    }
    static boolean enabled(Context context){return prefs(context).getBoolean("enabled",false);}
    static PeriodicWorkRequest request() {
        return new PeriodicWorkRequest.Builder(GroupWorker.class,INTERVAL_MINUTES,TimeUnit.MINUTES)
            .setInitialDelay(INTERVAL_MINUTES,TimeUnit.MINUTES)
            .setConstraints(new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true).build())
            .build();
    }
    static void bootstrap(Context context) {
        if(!enabled(context))return;
        try {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK,ExistingPeriodicWorkPolicy.KEEP,request());
        }catch(IllegalStateException ignored){/* WorkManager initializes later. */}
    }
    static void setEnabled(Context context,boolean allowed) {
        prefs(context).edit().putBoolean("enabled",allowed)
            .putString("state",allowed?"scheduled":"off").apply();
        try {
            WorkManager work=WorkManager.getInstance(context);
            if(allowed)work.enqueueUniquePeriodicWork(
                WORK,ExistingPeriodicWorkPolicy.UPDATE,request());
            else work.cancelUniqueWork(WORK);
        }catch(IllegalStateException ignored){/* Preference still guards every worker. */}
        if(!allowed)DiscoveryCoordinator.get(context).stopBackgroundTelegram();
    }
    static String describe(Context context) {
        SharedPreferences p=prefs(context);
        if(!enabled(context))return "خاموش — برای شروع، جستجوی گروه‌های تلگرام در پس‌زمینه را روشن کنید.";
        String state=p.getString("state","scheduled");
        String label=state.equals("ready")?"آخرین جستجو انجام شد":
            state.equals("running")?"در حال جستجوی گروه‌های عمومی":
            state.equals("account")?"حساب یا اتصال TDLib آماده نیست؛ تنظیمات تلگرام را بررسی کنید":
            state.equals("busy")?"نوبت قبلی با جستجوی دیگر هم‌زمان شد":
            state.equals("throttled")?"در انتظار پایان محدودیت موقت تلگرام":
            state.equals("cooldown")?"این عبارت به‌تازگی بررسی شده؛ در نوبت بعدی عبارت دیگری انتخاب می‌شود":
            state.equals("error")?"جستجوی اخیر ناموفق بود؛ محدودیت یا اتصال را بررسی کنید":
            "برنامه‌ریزی شده — منتظر نوبت اندروید";
        long last=p.getLong("last_attempt",0);
        String date=last==0?"هنوز اجرا نشده":
            android.text.format.DateFormat.format("yyyy/MM/dd HH:mm",last).toString();
        return label+"\nآخرین تلاش: "+date+
            " | عبارت: "+p.getString("last_topic","—")+
            " | گروه تازهٔ آخرین نوبت: "+p.getInt("last_new",0);
    }
    private static void status(Context context,String state,String topic,int newlyFound) {
        prefs(context).edit().putString("state",state)
            .putString("last_topic",topic)
            .putInt("last_new",newlyFound).apply();
    }

    public static final class GroupWorker extends Worker {
        public GroupWorker(@NonNull Context context,@NonNull WorkerParameters params){
            super(context,params);
        }
        @NonNull @Override public Result doWork() {
            Context app=getApplicationContext();
            if(!enabled(app)||isStopped())return Result.success();
            prefs(app).edit().putLong("last_attempt",System.currentTimeMillis())
                .putString("state","running").apply();
            long retryUntil=app.getSharedPreferences("tdlib_controls",Context.MODE_PRIVATE)
                .getLong("retry_until",0);
            if(System.currentTimeMillis()<retryUntil) {
                status(app,"throttled","—",0);
                return Result.success();
            }
            TdlibEngine engine=TdlibEngine.get(app);
            if(!engine.hasCredentials()) {
                status(app,"account","—",0);
                return Result.success();
            }
            try {
                // Reconnect an EXISTING saved TDLib session. Never request login
                // codes, phone numbers or passwords from a headless worker.
                engine.connect();
                if(!engine.awaitReadyForQueue(TimeUnit.SECONDS.toMillis(45))) {
                    if(enabled(app))status(app,"account","—",0);
                    return Result.success();
                }
                if(isStopped()||!enabled(app))return Result.success();
                DiscoveryCoordinator coordinator=DiscoveryCoordinator.get(app);
                if(!coordinator.start(1,true)) {
                    status(app,"busy","—",0);
                    return Result.success();
                }
                while(coordinator.isRunning()&&!isStopped()&&enabled(app))
                    Thread.sleep(200);
                if(isStopped()||!enabled(app)) {
                    coordinator.stopBackgroundTelegram();
                    return Result.success();
                }
                JSONObject run=coordinator.snapshot();
                String current=run.optString("current","—");
                String topic=current.startsWith("گروه‌های عمومی تلگرام: ")
                    ? current.substring("گروه‌های عمومی تلگرام: ".length()) : "—";
                boolean success="finished".equals(run.optString("state"))
                    && run.optInt("errors")==0;
                boolean queried=run.optInt("topic")>0;
                status(app,success?(queried?"ready":"cooldown"):"error",
                    topic,run.optInt("groups"));
                if(success && queried) prefs(app).edit()
                    .putLong("last_success",System.currentTimeMillis()).apply();
            } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                DiscoveryCoordinator.get(app).stopBackgroundTelegram();
                if(enabled(app))status(app,"error","—",0);
            } catch(Exception error) {
                if(enabled(app))status(app,"error","—",0);
            }
            return Result.success();
        }
    }
}
