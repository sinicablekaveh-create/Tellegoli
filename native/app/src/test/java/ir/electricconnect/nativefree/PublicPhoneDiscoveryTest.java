package ir.electricconnect.nativefree;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
public final class PublicPhoneDiscoveryTest {
    private Context ctx;
    @Before public void setup(){
        ctx=RuntimeEnvironment.getApplication();
        ctx.deleteDatabase("electric_connect_native_free.db");
    }
    private JSONObject business(String title,String url,String text)throws Exception{
        return new JSONObject().put("title",title).put("url",url).put("text",text);
    }

    @Test public void savesOnlyLabeledPublicBusinessNumbersAndDeduplicates(){
        JSONArray data=new JSONArray();
        try {
            data.put(business("شرکت تولید سینی کابل کاوه","https://cable.example/contact",
                "دفتر مرکزی - شماره تماس: 021 5651 9931\nواحد فروش موبایل: ۰۹۱۲ ۱۲۳ ۴۵۶۷"));
            data.put(business("صنایع کابل کاوه","https://cable.example/about",
                "تلفن تماس: +98 (21) 5651-9931"));
            data.put(business("تولید تجهیزات برقی","https://example.org/product",
                "دستگاه 4000 وات سال 1402، بدون شماره تماس واقعی"));
            data.put(business("شرکت سیم و کابل","https://example.org/contact",
                "شماره تماس: ۰۹۳۵۶۷۸۹۰۱۲"));
            data.put(business("حساب شخصی","https://t.me/name",
                "موبایل: 09121111111"));
        }catch(Exception e){throw new AssertionError(e);}
        List<Integer> progress=new ArrayList<>();
        try(MainActivity.Store db=new MainActivity.Store(ctx)){
            PublicPhoneDiscovery.Summary s=PublicPhoneDiscovery.save(db,data,
                (done,total,saved,dups,rejected)->progress.add(done));
            assertEquals(5,progress.size());
            assertEquals(3,s.saved);
            assertEquals(1,s.duplicates);
            assertEquals(3,db.count("records"));
            assertEquals(1,db.find("02156519931",0,10).size());
            assertEquals(1,db.find("09121234567",0,10).size());
            assertEquals(1,db.find("09356789012",0,10).size());
            PublicPhoneDiscovery.Summary again=PublicPhoneDiscovery.save(db,data,null);
            assertEquals(0,again.saved);
            assertEquals(3,db.count("records"));
        }
    }
    @Test public void oldManualNumbersArePreservedAndNotDuplicated()throws Exception{
        try(MainActivity.Store db=new MainActivity.Store(ctx)){
            assertTrue(db.insertRecord("قبلی","۰۲۱-۵۶۵۱۹۹۳۱","برق","دستی"));
            JSONArray rows=new JSONArray().put(
                business("شرکت کابل صنعتی","https://cable.example/contact",
                    "تلفن: +98 21 5651 9931"));
            assertEquals(0,PublicPhoneDiscovery.save(db,rows,null).saved);
            assertEquals(1,db.count("records"));
        }
    }
    @Test public void rejectsUnlabeledNumbersAndNonBusinessOrSocialSources()throws Exception{
        JSONArray rows=new JSONArray()
            .put(business("شرکت صنعتی","https://example.org/product",
                "کد کالا: 09121234567"))
            .put(business("صفحهٔ شخصی","https://example.org/about-me",
                "موبایل: 09121234567"))
            .put(business("تجهیزات صنعتی","https://t.me/publicgroup",
                "شماره تماس: 09121234567"))
            .put(business("تابلو برق","https://example.org/contact",
                "تلفن: 02112345678"));
        try(MainActivity.Store db=new MainActivity.Store(ctx)){
            PublicPhoneDiscovery.Summary result=PublicPhoneDiscovery.save(db,rows,null);
            assertEquals(1,result.saved);
            assertEquals(1,db.count("records"));
        }
        assertNull(PublicPhoneDiscovery.canonicalSource("file:///data/user/0"));
        assertNull(PublicPhoneDiscovery.canonicalSource("http://localhost/private"));
        assertNull(PublicPhoneDiscovery.canonicalSource("https://u:p@example.org/contact"));
        assertNull(PublicPhoneDiscovery.normalizeIranPhone("1234"));
        assertEquals("09121234567",PublicPhoneDiscovery.normalizeIranPhone("+98 (912) 1234567"));
        assertEquals("02156519931",PublicPhoneDiscovery.normalizeIranPhone("۰۲۱۵۶۵۱۹۹۳۱"));
    }
}
