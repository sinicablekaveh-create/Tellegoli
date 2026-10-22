package ir.electricconnect.nativefree;

import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class TelegramProxyUiTest {
    @Test public void proxyButtonOpensDialogInTelegramBrowser() {
        try (var controller=Robolectric.buildActivity(TelegramBrowserActivity.class).create()) {
            TextView button=find(controller.get().getWindow().getDecorView(),"پروکسی تلگرام");
            assertNotNull("Proxy button should be visible in the browser",button);
            assertTrue(button.performClick());
            AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull("Proxy dialog should be shown",dialog);
            assertTrue(dialog.isShowing());
            dialog.dismiss();
        }
    }

    @Test public void proxyButtonOpensDialogInAccountScreen() {
        try (var controller=Robolectric.buildActivity(TdlibAccountActivity.class).create()) {
            TextView button=find(controller.get().getWindow().getDecorView(),"پروکسی تلگرام");
            assertNotNull("Proxy action should be available even before login",button);
            assertTrue(button.performClick());
            AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);
            assertTrue(dialog.isShowing());
            dialog.dismiss();
        }
    }

    @Test public void proxyParserAcceptsTelegramLinksAndRejectsUntrustedUrls() {
        Object mt=ReflectionHelpers.callStaticMethod(TdlibEngine.class,"parseTelegramProxy",
            ReflectionHelpers.ClassParameter.from(String.class,
                "tg://proxy?server=proxy.example.com&port=443&secret=0123456789abcdef0123456789abcdef"));
        assertNotNull(mt);
        assertEquals("proxy.example.com",ReflectionHelpers.getField(mt,"server"));
        assertEquals(443,(int)ReflectionHelpers.getField(mt,"port"));
        assertEquals(true,(boolean)ReflectionHelpers.getField(mt,"mtproto"));

        Object socks=ReflectionHelpers.callStaticMethod(TdlibEngine.class,"parseTelegramProxy",
            ReflectionHelpers.ClassParameter.from(String.class,
                "https://t.me/socks?server=proxy.example.com&port=1080&user=test&pass=test"));
        assertNotNull(socks);
        assertEquals(false,(boolean)ReflectionHelpers.getField(socks,"mtproto"));

        Object invalid=ReflectionHelpers.callStaticMethod(TdlibEngine.class,"parseTelegramProxy",
            ReflectionHelpers.ClassParameter.from(String.class,
                "https://example.com/proxy?server=proxy.example.com&port=443&secret=abcd"));
        assertNull(invalid);
    }

    @Test public void base64UrlProxySecretIsNormalizedWithoutChangingServerOrPort() {
        // Synthetic, nonfunctional fixture deliberately beginning with the
        // textual letters "ee"; that does NOT imply hexadecimal 0xee.
        Object cfg=ReflectionHelpers.callStaticMethod(TdlibEngine.class,
            "parseTelegramProxy",ReflectionHelpers.ClassParameter.from(String.class,
                "https://t.me/proxy?server=proxy.example.com&port=8880&secret=eeAAAAAAAAAAAAAAAAAAAA"));
        assertNotNull(cfg);
        assertEquals("proxy.example.com",ReflectionHelpers.getField(cfg,"server"));
        assertEquals(8880,(int)ReflectionHelpers.getField(cfg,"port"));
        assertEquals("79e00000000000000000000000000000",
            ReflectionHelpers.getField(cfg,"secret"));

        // The Telegram-native scheme and canonical padded Base64URL work too.
        Object tg=ReflectionHelpers.callStaticMethod(TdlibEngine.class,
            "parseTelegramProxy",ReflectionHelpers.ClassParameter.from(String.class,
                "tg://proxy?server=proxy.example.com&port=443&secret=eeAAAAAAAAAAAAAAAAAAAA=="));
        assertNotNull(tg);
        assertEquals("79e00000000000000000000000000000",
            ReflectionHelpers.getField(tg,"secret"));
    }

    @Test public void malformedAndUnsupportedMtprotoEncodingsAreRejected() {
        for(String secret:new String[]{
            "bad","eeAAAAAAAAAAAAAAAAAAA*", "eeAAAAAAAAAAAAAAAAAAA%20",
            "AAAAAAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAAAAAAAAAAAA"
        }) {
            Object invalid=ReflectionHelpers.callStaticMethod(TdlibEngine.class,
                "parseTelegramProxy",ReflectionHelpers.ClassParameter.from(String.class,
                    "https://t.me/proxy?server=proxy.example.com&port=443&secret="+secret));
            assertNull("Must not accept malformed encoded proxy secret",invalid);
        }
    }

    private static TextView find(View view,String label){
        if(view instanceof TextView && label.contentEquals(((TextView)view).getText()))
            return (TextView)view;
        if(view instanceof ViewGroup){
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++){
                TextView item=find(group.getChildAt(i),label);
                if(item!=null)return item;
            }
        }
        return null;
    }
}
