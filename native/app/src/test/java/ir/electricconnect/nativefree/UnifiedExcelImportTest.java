package ir.electricconnect.nativefree;

import java.io.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Checks round-trip of combined XLSX plus preservation and deduplication. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
public class UnifiedExcelImportTest {
    private byte[] workbook(String[][] rows)throws Exception{
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        ExcelWorkbook.write(output,"ورودی",Arrays.asList(rows));
        return output.toByteArray();
    }
    @Test public void importsPhonesAndGroupMetadataOnceWithoutJoining()throws Exception{
        String[][] rows={
            {"نوع داده","عنوان","شماره / شناسه / آدرس","دسته‌بندی","منبع",
             "متن پیام","وضعیت واقعی","زمان / آخرین مشاهده","شناسه صف","جزئیات","لینک عمومی گروه"},
            {"مخاطب تجاری","شرکت پیمانکار برق نمونه","02199994444","برق صنعتی","Excel","","","","","",""},
            {"مخاطب تجاری","همان شماره","۰۲۱۹۹۹۹۴۴۴۴","برق صنعتی","Excel","","","","","",""},
            {"گروه ورودی اکسل","بازار برق تهران","","","Excel","","","","","","https://t.me/bargh_tehran"},
            {"گزارش صف","ارسال نشده","1","","","","","","","",""}
        };
        byte[] excel=workbook(rows);
        UnifiedExcelImport.Parsed parsed=UnifiedExcelImport.read(new ByteArrayInputStream(excel));
        assertEquals(2,parsed.contacts.size());
        assertEquals(1,parsed.groups.size());
        MainActivity.Store store=new MainActivity.Store(RuntimeEnvironment.getApplication());
        try{
            UnifiedExcelImport.Report first=UnifiedExcelImport.apply(store,parsed);
            assertEquals(1,first.contacts);
            assertEquals(1,first.groups);
            UnifiedExcelImport.Report second=UnifiedExcelImport.apply(store,parsed);
            assertEquals(0,second.contacts);
            assertEquals(0,second.groups);
            assertTrue(new DiscoveryData(store).importedGroupCount()>=1);
            assertTrue(store.find("02199994444",0,50).size()>=1);
        }finally{store.close();}
    }
    @Test public void separatesBuyerAndGroupSearchKeywords()throws Exception{
        byte[] excel=workbook(new String[][]{
            {"حوزه","کلیدواژه"},
            {"مخاطبان","مجری برق صنعتی"},
            {"گروه‌ها","سینی کابل"}
        });
        UnifiedExcelImport.Parsed parsed=UnifiedExcelImport.read(new ByteArrayInputStream(excel));
        assertEquals(1,parsed.buyers.size());
        assertEquals(1,parsed.groupTerms.size());
        assertEquals("سینی کابل",parsed.groupTerms.get(0));
    }
    @Test public void backupPreservesNewExcelGroupsAndKeywords()throws Exception{
        MainActivity.Store store=new MainActivity.Store(RuntimeEnvironment.getApplication());
        try{
            UnifiedExcelImport.Parsed parsed=new UnifiedExcelImport.Parsed();
            parsed.groups.add(new UnifiedExcelImport.Group("بازار کابل تست",
                "https://t.me/cabletest_example","",
                "ورودی آزمون"));
            parsed.groupTerms.add("برق نیروگاهی");
            UnifiedExcelImport.apply(store,parsed);
            ByteArrayOutputStream backup=new ByteArrayOutputStream();
            LocalBackup.write(store,backup);
            String json=backup.toString("UTF-8");
            assertTrue(json.contains("imported_groups"));
            assertTrue(json.contains("search_keywords"));
            assertTrue(json.contains("برق نیروگاهی"));
        }finally{store.close();}
    }
    @Test public void rejectsUnknownFileBeforeDatabaseWrite()throws Exception{
        try{
            UnifiedExcelImport.read(new ByteArrayInputStream("not an excel file".getBytes("UTF-8")));
            fail("An invalid Excel must be rejected.");
        }catch(IOException expected){assertNotNull(expected.getMessage());}
    }
}
