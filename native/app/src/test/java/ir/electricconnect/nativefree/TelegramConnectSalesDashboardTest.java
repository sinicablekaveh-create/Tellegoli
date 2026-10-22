package ir.electricconnect.nativefree;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Regression guards for the user's four-task sales workflow. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class TelegramConnectSalesDashboardTest {
    private static TextView find(View root,String expected) {
        if(root instanceof TextView && expected.contentEquals(((TextView)root).getText()))
            return (TextView)root;
        if(root instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)root;
            for(int i=0;i<group.getChildCount();i++) {
                TextView result=find(group.getChildAt(i),expected);
                if(result!=null)return result;
            }
        }
        return null;
    }

    @Test public void homeExposesFourPrimarySalesActions() {
        Context app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences("discovery",Context.MODE_PRIVATE)
            .edit().putBoolean("sales_auto_enabled",false).commit();
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            View root=controller.get().getWindow().getDecorView();
            assertNotNull(find(root,"مشتری‌یابی خودکار"));
            assertNotNull(find(root,"شروع جستجوی مشتریان و گروه‌ها"));
            assertNotNull(find(root,"جستجوی خودکار وب هر ۱۵ دقیقه"));
            assertNotNull(find(root,"جستجوی وب ۱۵ دقیقه‌ای + صف ۸ دقیقه‌ای"));
            assertNotNull(find(root,"صف و ارسال به گروه‌ها"));
            assertNotNull(find(root,"مخاطبان و شماره‌های تماس"));
            assertNotNull(find(root,"گروه‌های تلگرام"));
            assertNotNull(find(root,"خروجی اکسل"));
        }
    }

    private static View action(View root,String title) {
        TextView text=find(root,title);
        assertNotNull("Missing action: "+title,text);
        ViewGroup labelColumn=(ViewGroup)text.getParent();
        View target=(View)labelColumn.getParent();
        assertTrue("No real click handler for: "+title,target.hasOnClickListeners());
        return target;
    }

    @Test public void contactsActionOpensActualSavedContactsScreen() {
        Context app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences("discovery",Context.MODE_PRIVATE)
            .edit().putBoolean("sales_auto_enabled",false).commit();
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();
            action(activity.getWindow().getDecorView(),"مخاطبان و شماره‌های تماس").performClick();
            assertNotNull(find(activity.getWindow().getDecorView(),"شماره‌یاب خودکار صنعت برق"));
            assertNotNull(find(activity.getWindow().getDecorView(),"شروع جست‌وجوی خودکار شماره‌های جدید"));
            assertNotNull(find(activity.getWindow().getDecorView(),"وضعیت زندهٔ جست‌وجوی مخاطبان"));
            assertNotNull(find(activity.getWindow().getDecorView(),"خروجی اکسل"));
        }
    }

    @Test public void allFourMainToolsHaveWiredClickableActions() {
        Context app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences("discovery",Context.MODE_PRIVATE)
            .edit().putBoolean("sales_auto_enabled",false).commit();
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            View root=controller.get().getWindow().getDecorView();
            for(String title:new String[]{
                "گروه‌های تلگرام", "صف و ارسال به گروه‌ها",
                "مخاطبان و شماره‌های تماس", "خروجی اکسل"
            })assertNotNull(action(root,title));
        }
    }


    @Test public void webDiscoveryDefaultsToFifteenMinutesAndQueueGapIsEightMinutes() {
        Context app=RuntimeEnvironment.getApplication();
        android.content.SharedPreferences settings=app.getSharedPreferences("discovery",Context.MODE_PRIVATE);
        app.getSharedPreferences("queue_gap_discovery",Context.MODE_PRIVATE)
            .edit().putBoolean("enabled",true).commit();
        settings.edit().putBoolean("periodic_15_minutes",true).putBoolean("periodic",true).commit();
        assertEquals(15,DiscoverySchedule.intervalMinutes(app));
        assertEquals(8L*60*1000,ScheduledQueue.MIN_GAP);
        assertTrue(QueueGapDiscovery.webSearchAllowed(app));
        settings.edit().putBoolean("periodic",false).commit();
        assertFalse(QueueGapDiscovery.webSearchAllowed(app));
        settings.edit().putBoolean("periodic",true).putBoolean("periodic_15_minutes",false).commit();
        assertEquals(1440,DiscoverySchedule.intervalMinutes(app));
        settings.edit().putBoolean("periodic_15_minutes",true).putBoolean("periodic",false).commit();
    }

    @Test public void automaticQueriesTargetBuyersNotOurCableTrayProduct() {
        for(String topic:DiscoveryCoordinator.TOPICS) {
            assertFalse(topic,topic.contains("سینی کابل"));
            assertTrue(topic,topic.contains("پیمانکار")||topic.contains("مجری")
                ||topic.contains("EPC")||topic.contains("برقکار"));
        }
        assertEquals(10,DiscoveryCoordinator.TOPICS.length);
        assertFalse(PublicWebsiteContactsProvider.DEFAULT_SEEDS.contains("https://cableiran.com/contact-us"));
        assertFalse(PublicWebsiteContactsProvider.DEFAULT_SEEDS.contains("https://mashadcable.ir/"));
        assertFalse(PublicWebsiteContactsProvider.DEFAULT_SEEDS.contains("https://fa.yazdcable.com/"));
    }

    @Test public void verifiedPublicBuyerSitesRotateAcrossLimitedRuns() {
        java.util.List<String> first=PublicWebsiteContactsProvider.rotatedSeeds(0);
        java.util.List<String> next=PublicWebsiteContactsProvider.rotatedSeeds(4);
        assertEquals(PublicWebsiteContactsProvider.DEFAULT_SEEDS.size(),new java.util.HashSet<>(first).size());
        assertTrue(first.size()>4);
        assertEquals(first.get(4),next.get(0));
        for(int i=0;i<first.size();i++)
            assertEquals(first.get((4+i)%first.size()),next.get(i));
        for(String url:first) {
            assertTrue(url.startsWith("https://"));
            assertFalse(url.contains("cableiran.com"));
            assertFalse(url.contains("mashadcable.ir"));
        }
    }
}
