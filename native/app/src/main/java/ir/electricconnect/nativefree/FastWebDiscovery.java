package ir.electricconnect.nativefree;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.concurrent.TimeUnit;

/** A single three-minute gate shared by visible-screen and opt-in active-service scans. */
final class FastWebDiscovery {
    static final long INTERVAL_MS=TimeUnit.MINUTES.toMillis(3);
    private static final String LAST="fast_web_last_started";
    private FastWebDiscovery(){}

    static boolean isDue(long now,long last) {
        return last<=0 || now<last || now-last>=INTERVAL_MS;
    }

    /** Only PUBLIC business websites. Existing coordinator also guards overlapping searches. */
    static synchronized boolean maybeStart(Context context,boolean activeService) {
        Context app=context.getApplicationContext();
        SharedPreferences prefs=app.getSharedPreferences("discovery",Context.MODE_PRIVATE);
        if(activeService) {
            if(!ActiveWebSearchService.enabled(app))return false;
        } else if(!prefs.getBoolean("periodic",false))return false;
        long now=System.currentTimeMillis();
        if(!isDue(now,prefs.getLong(LAST,0)))return false;
        DiscoveryCoordinator coordinator=DiscoveryCoordinator.webBackground(app);
        if(coordinator.isRunning() || DiscoveryCoordinator.get(app).isRunning())return false;
        if(!coordinator.start(0,true))return false;
        prefs.edit().putLong(LAST,now).apply();
        return true;
    }
}
