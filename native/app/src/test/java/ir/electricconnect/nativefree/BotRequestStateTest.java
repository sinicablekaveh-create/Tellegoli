package ir.electricconnect.nativefree;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, manifest=Config.NONE)
public class BotRequestStateTest {
    private SharedPreferences prefs;
    private AtomicLong clock;
    private BotRequestState screen;
    @Before public void setup() {
        prefs=RuntimeEnvironment.getApplication().getSharedPreferences("bot_test",Context.MODE_PRIVATE);
        prefs.edit().clear().commit();clock=new AtomicLong(1000000L);
        screen=new BotRequestState(prefs,clock::get);
    }
    @Test public void replacementScreenCannotOverlapRequestOrBypassSuccessfulSendCooldown() {
        assertNull(screen.begin(true));
        BotRequestState replacement=new BotRequestState(prefs,clock::get);
        try {assertNotNull(replacement.begin(true));} finally {screen.finish(true,true,0);}
        assertNotNull(replacement.begin(true));
        // Verifying the bot is permitted during the send cooldown.
        assertNull(replacement.begin(false));replacement.finish(false,true,0);
        clock.addAndGet(480000L);
        assertNull(replacement.begin(true));replacement.finish(true,false,0);
    }
    @Test public void telegramRetryAfterSurvivesScreenReplacementAndBlocksAllMethods() {
        assertNull(screen.begin(true));screen.finish(true,false,90);
        BotRequestState replacement=new BotRequestState(prefs,clock::get);
        assertNotNull(replacement.begin(false));
        assertNotNull(replacement.begin(true));
        clock.addAndGet(90000L);
        assertNull(replacement.begin(true));replacement.finish(true,false,0);
    }
    @Test public void failedSendReleasesOwnershipWithoutRecordingSuccess() {
        assertNull(screen.begin(true));screen.finish(true,false,0);
        assertFalse(prefs.contains("last_success"));
        assertNull(screen.begin(true));screen.finish(true,false,0);
    }
}
