package ir.electricconnect.nativefree;

import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** No network, accounts, Telegram actions or messages needed for schedule checks. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
public final class FastWebDiscoveryTest {
    private Context app;
    @Before public void reset() {
        app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences("discovery",Context.MODE_PRIVATE).edit()
            .putBoolean("periodic",false).remove("fast_web_last_started").commit();
        app.getSharedPreferences(ActiveWebSearchService.PREF,Context.MODE_PRIVATE)
            .edit().clear().commit();
    }

    @Test public void visibleScanIsThrottledToThreeMinutes() {
        long now=TimeUnit.MINUTES.toMillis(100);
        assertEquals(TimeUnit.MINUTES.toMillis(3),FastWebDiscovery.INTERVAL_MS);
        assertTrue(FastWebDiscovery.isDue(now,0));
        assertFalse(FastWebDiscovery.isDue(now,now));
        assertFalse(FastWebDiscovery.isDue(now,now-TimeUnit.MINUTES.toMillis(2)));
        assertFalse(FastWebDiscovery.isDue(now,now-FastWebDiscovery.INTERVAL_MS+1));
        assertTrue(FastWebDiscovery.isDue(now,now-FastWebDiscovery.INTERVAL_MS));
        assertTrue(FastWebDiscovery.isDue(now,now+1)); // reset after clock moves back
    }

    @Test public void optOutCannotSilentlyStartBackgroundWebOrTelegram() {
        assertFalse(ActiveWebSearchService.enabled(app));
        assertFalse(FastWebDiscovery.maybeStart(app,true));
        assertFalse("Visible fast mode honors the OFF switch",
            FastWebDiscovery.maybeStart(app,false));
        assertEquals("Periodic jobs remain 15 minutes, not 3",
            15,DiscoverySchedule.WEB_INTERVAL_MINUTES);
    }

    @Test public void android15ActiveDataSyncSessionHasConservativeSafetyLimit() {
        assertTrue(ActiveWebSearchService.SESSION_LIMIT_MS>FastWebDiscovery.INTERVAL_MS);
        assertTrue(ActiveWebSearchService.SESSION_LIMIT_MS<
            TimeUnit.HOURS.toMillis(6));
    }
}
