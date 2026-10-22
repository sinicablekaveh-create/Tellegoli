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
public class QueueDeliveryReportTest {
    private Context context;
    @Before public void setup(){
        context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("approved_scheduled_queue_v095",Context.MODE_PRIVATE)
            .edit().clear().commit();
    }

    @Test public void exportsPersistedQueueWithoutSendingOrLosingIdentifiers(){
        assertNull(ScheduledQueue.add(context,-1001234567890L,"گروه برق","پیام تأییدشده"));
        List<JSONObject> items=ScheduledQueue.snapshot(context);
        List<String[]> rows=QueueDeliveryReport.rows(items);
        assertEquals(2,rows.size());
        assertEquals(10,rows.get(0).length);
        assertEquals(10,rows.get(1).length);
        assertEquals("-1001234567890",rows.get(1)[2]);
        assertEquals("پیام تأییدشده",rows.get(1)[3]);
        assertEquals("PENDING",rows.get(1)[4]);
        assertEquals("منتظر ارسال",rows.get(1)[5]);
        assertEquals("",rows.get(1)[8]);
        assertEquals("",rows.get(1)[9]);
        assertFalse(ScheduledQueue.enabled(context));
        assertEquals("PENDING",ScheduledQueue.snapshot(context).get(0).optString("status"));
    }

    @Test public void distinguishesTelegramSendSuccessFromReadReceipt()throws Exception{
        JSONObject row=new JSONObject()
            .put("id","queue-1").put("chat_name","Group")
            .put("chat_id",-100123L).put("text","test")
            .put("status","DELIVERED").put("detail","تلگرام ارسال را تأیید کرد")
            .put("created",1000L).put("attempt_at",2000L)
            .put("pending_id",9223372036854775806L);
        String[] cells=QueueDeliveryReport.rows(java.util.Collections.singletonList(row)).get(1);
        assertTrue(cells[5].contains("خواندن پیام تأیید نشده"));
        assertEquals("9223372036854775806",cells[9]);
        assertTrue(cells[7].matches("\\d{4}-\\d{2}-\\d{2} .*"));
        assertTrue(cells[8].matches("\\d{4}-\\d{2}-\\d{2} .*"));
    }

    @Test public void emptyQueueHasHeaderAndUnknownStatusIsExplicit(){
        assertEquals(1,QueueDeliveryReport.rows(ScheduledQueue.snapshot(context)).size());
        assertTrue(QueueDeliveryReport.label("UNKNOWN").contains("نامشخص"));
    }
}
