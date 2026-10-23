package ir.electricconnect.nativefree;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;

/**
 * Explicitly user-started, visible dataSync foreground session for PUBLIC business
 * web search only. Never starts on BOOT_COMPLETED, sends messages, or uses TDLib.
 * Android 15 enforces a shared dataSync quota of 6h / 24h. We stop earlier and
 * also handle the system's onTimeout callback.
 */
public final class ActiveWebSearchService extends Service {
    static final String ACTION_START="ir.electricconnect.nativefree.START_ACTIVE_WEB";
    static final String ACTION_STOP="ir.electricconnect.nativefree.STOP_ACTIVE_WEB";
    static final String PREF="active_web_search";
    private static final String CHANNEL="tellegoli_active_public_web";
    private static final int NOTIFICATION_ID=31512;
    // Safety ceiling per user-started session; system's total 6h allowance wins.
    static final long SESSION_LIMIT_MS=5L*60*60*1000;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private long startedAt;
    private boolean foregroundStarted;
    private final Runnable tick=new Runnable() {
        @Override public void run() {
            if(!foregroundStarted)return;
            if(System.currentTimeMillis()-startedAt>=SESSION_LIMIT_MS) {
                shutdown("جلسهٔ جستجوی فعال به سقف زمانی ایمن رسید.");
                return;
            }
            FastWebDiscovery.maybeStart(ActiveWebSearchService.this,true);
            handler.postDelayed(this,FastWebDiscovery.INTERVAL_MS);
        }
    };

    static boolean enabled(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF,MODE_PRIVATE)
            .getBoolean("enabled",false);
    }
    static String status(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF,MODE_PRIVATE)
            .getString("status","غیرفعال");
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel(
            CHANNEL,"تلگولی — جستجوی فعال",NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        String action=intent==null?"":intent.getAction();
        if(ACTION_STOP.equals(action)) {
            shutdown("با درخواست کاربر متوقف شد.");
            return START_NOT_STICKY;
        }
        if(!ACTION_START.equals(action)) {
            shutdown("جلسهٔ قبلی خودکار راه‌اندازی نمی‌شود.");
            return START_NOT_STICKY;
        }
        if(foregroundStarted)return START_NOT_STICKY;
        try {
            Notification notification=new Notification.Builder(this,CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("تلگولی — جستجوی فعال سایت‌های عمومی")
                .setContentText("هر ۳ دقیقه؛ برای توقف روی «توقف جستجو» بزنید")
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this,2,
                    new Intent(this,MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE))
                .addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_media_pause,"توقف جستجو",
                    PendingIntent.getService(this,3,
                        new Intent(this,ActiveWebSearchService.class).setAction(ACTION_STOP),
                        PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE)).build())
                .build();
            if(Build.VERSION.SDK_INT>=29)
                startForeground(NOTIFICATION_ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTIFICATION_ID,notification);
            foregroundStarted=true;
            startedAt=System.currentTimeMillis();
            getSharedPreferences(PREF,MODE_PRIVATE).edit()
                .putBoolean("enabled",true)
                .putLong("started_at",startedAt)
                .putString("status","فعال؛ جستجوی وب هر ۳ دقیقه تا حداکثر ۵ ساعت")
                .apply();
            handler.post(tick);
        } catch(RuntimeException e) {
            // Android may disallow a new FGS due to background launch or exhausted quota.
            getSharedPreferences(PREF,MODE_PRIVATE).edit()
                .putBoolean("enabled",false)
                .putString("status","اندروید اجازهٔ شروع سرویس را نداد؛ محدودیت زمان یا باتری را بررسی کنید.")
                .apply();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override public void onTimeout(int startId,int fgsType) {
        shutdown("سقف مجاز اجرای سرویس در Android 15 تمام شد.");
    }

    private void shutdown(String reason) {
        handler.removeCallbacks(tick);
        boolean wasRunning=foregroundStarted;
        foregroundStarted=false;
        getSharedPreferences(PREF,MODE_PRIVATE).edit()
            .putBoolean("enabled",false).putString("status",reason).apply();
        if(wasRunning) {
            DiscoveryCoordinator.webBackground(this).stop();
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
        stopSelf();
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(tick);
        if(foregroundStarted) {
            DiscoveryCoordinator.webBackground(this).stop();
            getSharedPreferences(PREF,MODE_PRIVATE).edit().putBoolean("enabled",false)
                .putString("status","سرویس توسط اندروید متوقف شد.").apply();
        }
        foregroundStarted=false;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent){return null;}
}
