package ir.electricconnect.nativefree;

import static org.junit.Assert.*;
import android.content.Context;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class ScheduledQueueTest {
    private Context context;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("approved_scheduled_queue_v095",Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test public void validatesChatAndText() {
        assertNotNull(ScheduledQueue.add(context,0,"Group","Message"));
        assertNotNull(ScheduledQueue.add(context,123,"Group"," "));
        assertNotNull(ScheduledQueue.add(context,123,"Group","x".repeat(3001)));
        assertTrue(ScheduledQueue.snapshot(context).isEmpty());
    }

    @Test public void persistsDistinctApprovedItemsAndRejectsDuplicates() {
        assertNull(ScheduledQueue.add(context,111L,"فروشندگان برق","تبلیغ مجاز"));
        assertNotNull(ScheduledQueue.add(context,111L,"فروشندگان برق","تبلیغ مجاز"));
        assertNull(ScheduledQueue.add(context,222L,"پیمانکاران برق","تبلیغ مجاز"));
        List<JSONObject> items=ScheduledQueue.snapshot(context);
        assertEquals(2,items.size());
        assertEquals("PENDING",items.get(0).optString("status"));
        assertEquals(222L,items.get(1).optLong("chat_id"));
    }

    @Test public void enforcesFiniteQueueBound() {
        for(int i=1;i<=ScheduledQueue.MAX_PENDING;i++) {
            assertNull(ScheduledQueue.add(context,i,"Group "+i,"Message "+i));
        }
        assertNotNull(ScheduledQueue.add(context,500,"More","Message"));
        assertEquals(ScheduledQueue.MAX_PENDING,ScheduledQueue.snapshot(context).size());
    }

    @Test public void earlyTelegramReceiptIsMatchedOnlyAfterPendingIdIsKnown() throws Exception {
        final long chat=-1001234567890L;
        final long temporaryMessageId=9223372036854775806L;
        assertNull(ScheduledQueue.add(context,chat,"گروه مجاز","متن تایید شده"));
        String id=ScheduledQueue.snapshot(context).get(0).optString("id");
        java.lang.reflect.Method change=ScheduledQueue.class.getDeclaredMethod("change",
            Context.class,String.class,String.class,String.class,String.class,long.class,boolean.class);
        change.setAccessible(true);
        assertTrue((Boolean)change.invoke(null,context,id,"PENDING","SENDING","ارسال",0L,true));
        ScheduledQueue.recordDelivery(context,chat,temporaryMessageId,true);
        assertEquals("SENDING",ScheduledQueue.snapshot(context).get(0).optString("status"));
        assertTrue((Boolean)change.invoke(null,context,id,"SENDING","SUBMITTED","TDLib پذیرفت",temporaryMessageId,false));
        assertEquals("DELIVERED",ScheduledQueue.snapshot(context).get(0).optString("status"));
        ScheduledQueue.recordDelivery(context,chat,temporaryMessageId,false);
        assertEquals("DELIVERED",ScheduledQueue.snapshot(context).get(0).optString("status"));
    }

    @Test public void unrelatedEarlyReceiptMustNotConfirmApprovedMessage() throws Exception {
        final long chat=-100987654321L;
        assertNull(ScheduledQueue.add(context,chat,"گروه مجاز","اطلاع‌رسانی"));
        String id=ScheduledQueue.snapshot(context).get(0).optString("id");
        java.lang.reflect.Method change=ScheduledQueue.class.getDeclaredMethod("change",
            Context.class,String.class,String.class,String.class,String.class,long.class,boolean.class);
        change.setAccessible(true);
        assertTrue((Boolean)change.invoke(null,context,id,"PENDING","SENDING","ارسال",0L,true));
        ScheduledQueue.recordDelivery(context,chat,1234L,true);
        assertTrue((Boolean)change.invoke(null,context,id,"SENDING","SUBMITTED","TDLib پذیرفت",5678L,false));
        assertEquals("SUBMITTED",ScheduledQueue.snapshot(context).get(0).optString("status"));
        ScheduledQueue.recordDelivery(context,chat,5678L,true);
        assertEquals("DELIVERED",ScheduledQueue.snapshot(context).get(0).optString("status"));
    }

    @Test public void removePendingOnlyAndPersist() {
        assertNull(ScheduledQueue.add(context,123,"Group","Message"));
        String id=ScheduledQueue.snapshot(context).get(0).optString("id");
        assertFalse(ScheduledQueue.removePending(context,"bad-id"));
        assertTrue(ScheduledQueue.removePending(context,id));
        assertFalse(ScheduledQueue.removePending(context,id));
        assertTrue(ScheduledQueue.snapshot(context).isEmpty());
    }
}
