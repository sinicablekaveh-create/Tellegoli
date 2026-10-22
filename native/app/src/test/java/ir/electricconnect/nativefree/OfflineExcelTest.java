package ir.electricconnect.nativefree;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import android.os.Looper;
import org.robolectric.util.ReflectionHelpers;
import static org.robolectric.Shadows.shadowOf;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class OfflineExcelTest {
    @Test public void genuineXlsxEscapesTextAndPreservesLeadingZeros()throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        List<String[]> data=Arrays.asList(
            new String[]{"نام شرکت","تلفن","منبع"},
            new String[]{"صنایع برق و کابل","02133980000","https://company.example/contact?a=1&b=2"},
            new String[]{"=HYPERLINK(\"evil\")","09121234567","متن <امن>"});
        ExcelWorkbook.write(bytes,"مخاطبان",data);
        Map<String,String> xml=new LinkedHashMap<>();
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()),
                StandardCharsets.UTF_8)){
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){
                ByteArrayOutputStream body=new ByteArrayOutputStream();
                byte[] chunk=new byte[2048];int n;
                while((n=zip.read(chunk))>=0)body.write(chunk,0,n);
                xml.put(entry.getName(),body.toString("UTF-8"));
            }
        }
        assertTrue(xml.containsKey("[Content_Types].xml"));
        assertTrue(xml.containsKey("xl/workbook.xml"));
        assertTrue(xml.containsKey("xl/worksheets/sheet1.xml"));
        assertTrue(xml.containsKey("xl/styles.xml"));
        String sheet=xml.get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("rightToLeft=\"1\""));
        assertTrue(sheet.contains("02133980000"));
        assertTrue(sheet.contains("https://company.example/contact?a=1&amp;b=2"));
        assertTrue(sheet.contains("متن &lt;امن&gt;"));
        assertTrue(sheet.contains("=HYPERLINK(&quot;evil&quot;)"));
        assertFalse(sheet.contains("<f>"));
        assertTrue(sheet.contains("t=\"inlineStr\""));
        assertTrue(sheet.contains("<autoFilter"));
    }

    @Test public void offlineContactsAndTelegramGroupsExposeWorkingExportActions(){
        Context context=RuntimeEnvironment.getApplication();
        context.deleteDatabase("electric_connect_native_free.db");
        try(MainActivity.Store store=new MainActivity.Store(context)){
            assertTrue(store.insertRecord("تجهیزات برق کاوه","02133980000","برق","وب‌سایت"));
        }
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity app=controller.get();
            TextView contacts=find(app.getWindow().getDecorView(),"مخاطبان");
            assertNotNull(contacts);contacts.performClick();
            assertNotNull(find(app.getWindow().getDecorView(),"خروجی اکسل"));
            assertNotNull(find(app.getWindow().getDecorView(),"جست‌وجوی آفلاین در شماره‌های ذخیره‌شده"));
            assertNotNull(find(app.getWindow().getDecorView(),"◈ تجهیزات برق کاوه"));
        }
        try(var controller=Robolectric.buildActivity(TelegramBrowserActivity.class).create()){
            assertNotNull(find(controller.get().getWindow().getDecorView(),
                "خروجی اکسل"));
            assertNotNull(find(controller.get().getWindow().getDecorView(),
                "نمایش پیام‌های قدیمی‌تر"));
            assertNotNull(find(controller.get().getWindow().getDecorView(),
                "نمایش پیام‌های بیشتر"));
        }
    }
    @Test public void telegramBrowserClosesDatabaseWorkerBeforeActivityTeardownAndPreservesGroups()throws Exception{
        Context context=RuntimeEnvironment.getApplication();
        context.deleteDatabase("electric_connect_native_free.db");
        long savedId=-1001234567001L;
        try(MainActivity.Store store=new MainActivity.Store(context)){
            assertTrue(new DiscoveryData(store).saveGroup(
                new TdlibEngine.ChatOverview(savedId,"گروه آزمایشی برق","گروه عمومی",false),"سینی کابل"));
        }
        var controller=Robolectric.buildActivity(TelegramBrowserActivity.class).setup();
        TelegramBrowserActivity activity=controller.get();
        ExecutorService io=ReflectionHelpers.getField(activity,"discoveryIo");
        try {
            // A queue barrier ensures the real background SQLite read has completed.
            io.submit(()->{}).get(5,TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(find(activity.getWindow().getDecorView(),"گروه آزمایشی برق"));
        } finally {
            controller.pause().stop().destroy();
        }
        assertTrue("Teardown must stop further SQLite jobs",io.isShutdown());
        // A callback arriving late after destruction cannot reopen the helper.
        ReflectionHelpers.callInstanceMethod(activity,"loadSavedGroups");
        try(MainActivity.Store check=new MainActivity.Store(context)){
            assertEquals(savedId,new DiscoveryData(check).groups().get(0).id);
        }
    }

    @Test public void createOnlyBrowserDoesNotLeaveDatabaseReadsRunningAfterClose() {
        var controller=Robolectric.buildActivity(TelegramBrowserActivity.class).create();
        TelegramBrowserActivity activity=controller.get();
        ExecutorService io=ReflectionHelpers.getField(activity,"discoveryIo");
        controller.destroy();
        assertTrue(io.isShutdown());
        ReflectionHelpers.callInstanceMethod(activity,"loadSavedGroups");
    }

    static TextView find(View v,String text){
        if(v instanceof TextView && text.contentEquals(((TextView)v).getText()))return (TextView)v;
        if(v instanceof ViewGroup){
            ViewGroup group=(ViewGroup)v;
            for(int i=0;i<group.getChildCount();i++){
                TextView result=find(group.getChildAt(i),text);
                if(result!=null)return result;
            }
        }
        return null;
    }
}
