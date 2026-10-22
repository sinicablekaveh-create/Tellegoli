package ir.electricconnect.nativefree;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.CheckBox;
import android.widget.EditText;
import org.drinkless.tdlib.TdApi;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class QueueSafetyTest {
    private Context app;
    @Before public void setup() {
        app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences("approved_scheduled_queue_v095",0).edit().clear().putBoolean("enabled",true).commit();
        app.getSharedPreferences("tdlib_controls",0).edit().clear().commit();
    }
    private TdlibEngine engine(TdlibEngine.Session session) {
        TdlibEngine engine=new TdlibEngine(app);ReflectionHelpers.setField(engine,"client",session);ReflectionHelpers.setField(engine,"loggedIn",true);return engine;
    }
    private TdApi.Chat chat(boolean permission) {TdApi.Chat c=new TdApi.Chat();c.id=123;c.title="گروه مجاز";c.type=new TdApi.ChatTypeBasicGroup();c.permissions=new TdApi.ChatPermissions();c.permissions.canSendBasicMessages=permission;return c;}
    @Test public void permissionDenialAndStoppedQueueNeverSendMessage() {
        AtomicInteger sent=new AtomicInteger();AtomicBoolean accepted=new AtomicBoolean(true);
        TdlibEngine engine=engine((function,handler)->{
            if(function instanceof TdApi.GetChat)handler.onResult(chat(false));
            else if(function instanceof TdApi.GetBasicGroup) {
                TdApi.BasicGroup group=new TdApi.BasicGroup();
                group.status=new TdApi.ChatMemberStatusMember();handler.onResult(group);
            }
            else if(function instanceof TdApi.SendMessage)sent.incrementAndGet();
        });
        engine.sendQueuedOnce(123,"پیام مجاز",(ok,id,detail)->accepted.set(ok));assertFalse(accepted.get());assertEquals(0,sent.get());
        app.getSharedPreferences("approved_scheduled_queue_v095",0).edit().putBoolean("enabled",false).commit();
        engine.sendQueuedOnce(123,"پیام مجاز",(ok,id,detail)->accepted.set(ok));assertFalse(accepted.get());assertEquals(0,sent.get());
    }
    @Test public void floodWaitPersistsAcrossEngineRestartAndDoesNotResendUnknownOutcome() {
        AtomicInteger sends=new AtomicInteger();
        TdlibEngine.Session session=(function,handler)->{
            if(function instanceof TdApi.GetChat)handler.onResult(chat(true));
            if(function instanceof TdApi.GetBasicGroup){TdApi.BasicGroup group=new TdApi.BasicGroup();group.status=new TdApi.ChatMemberStatusMember();handler.onResult(group);}
            if(function instanceof TdApi.SendMessage){sends.incrementAndGet();handler.onResult(new TdApi.Error(429,"FLOOD_WAIT_7200"));}
        };
        TdlibEngine engine=engine(session);
        engine.sendQueuedOnce(123,"پیام تأییدشده",(ok,id,detail)->assertFalse(ok));
        assertEquals(1,sends.get());
        assertTrue(app.getSharedPreferences("tdlib_controls",0).getLong("retry_until",0)>System.currentTimeMillis()+TimeUnitHelper.HOUR);
        assertTrue(engine(session).reserveScheduledAttempt().startsWith("WAIT:"));assertEquals(1,sends.get());
    }
    @Test public void eachQueueItemRequiresPermissionThenExactMessageDialogAndResetsConsent() {
        TdlibEngine engine=engine((function,handler)->{});ReflectionHelpers.setStaticField(TdlibEngine.class,"shared",engine);
        try(var controller=Robolectric.buildActivity(ScheduledQueueActivity.class).create()) {
            ScheduledQueueActivity activity=controller.get();activity.onState("READY","");activity.onChats(Collections.singletonList(new TdlibEngine.ChatInfo(123,"گروه مجاز")));
            android.widget.LinearLayout picker=ReflectionHelpers.getField(activity,"picker");
            assertTrue(picker.getChildAt(0) instanceof CheckBox);
            ((CheckBox)picker.getChildAt(0)).setChecked(true);
            EditText message=ReflectionHelpers.getField(activity,"message");message.setText("نمونهٔ تأییدشده");
            CheckBox consent=ReflectionHelpers.getField(activity,"permission");
            OfflineExcelTest.find(activity.getWindow().getDecorView(),"افزودن این پیام به صف").performClick();assertTrue(ScheduledQueue.snapshot(app).isEmpty());
            consent.setChecked(true);OfflineExcelTest.find(activity.getWindow().getDecorView(),"افزودن این پیام به صف").performClick();
            AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);assertTrue(dialog.isShowing());assertTrue(ScheduledQueue.snapshot(app).isEmpty());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(1,ScheduledQueue.snapshot(app).size());assertFalse(consent.isChecked());assertEquals("",message.getText().toString());
            message.setText("پیام بعدی");OfflineExcelTest.find(activity.getWindow().getDecorView(),"افزودن این پیام به صف").performClick();assertEquals(1,ScheduledQueue.snapshot(app).size());
        }finally{ReflectionHelpers.setStaticField(TdlibEngine.class,"shared",null);}
    }
    @Test public void departedMemberAndExpiredAttemptCannotDispatch() {
        for(boolean expired:new boolean[]{false,true}) {
            AtomicInteger sends=new AtomicInteger();AtomicBoolean active=new AtomicBoolean(true);
            AtomicBoolean completed=new AtomicBoolean();
            TdlibEngine engine=engine((function,handler)->{
                if(function instanceof TdApi.GetChat)handler.onResult(chat(true));
                else if(function instanceof TdApi.GetBasicGroup) {
                    TdApi.BasicGroup group=new TdApi.BasicGroup();
                    group.status=expired?new TdApi.ChatMemberStatusMember():new TdApi.ChatMemberStatusLeft();
                    if(expired)active.set(false);
                    handler.onResult(group);
                } else if(function instanceof TdApi.SendMessage)sends.incrementAndGet();
                else fail("Unexpected request: "+function.getClass().getSimpleName());
            });
            engine.sendQueuedOnce(123,"پیام مجاز",(ok,id,detail)->{assertFalse(ok);completed.set(true);},active::get);
            assertTrue(completed.get());assertEquals(0,sends.get());
        }
    }
    private static final class TimeUnitHelper {static final long HOUR=3600000L;}
}
