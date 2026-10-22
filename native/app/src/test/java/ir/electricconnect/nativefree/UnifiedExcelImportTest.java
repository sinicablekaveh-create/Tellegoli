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

    @Test public void importsWebSourcesFromExcelWithCanonicalDeduplicationAndRotation()throws Exception {
        var context=RuntimeEnvironment.getApplication();
        context.deleteDatabase("electric_connect_native_free.db");
        byte[] excel=workbook(new String[][]{
            {"آدرس سایت","نام شرکت"},
            {"https://buyer-a.example/","پیمانکار برق الف"},
            {"https://buyer-b.example/","مجری پروژه ب"},
            {"https://buyer-a.example/","همان نشانی دوباره"},
            {"https://t.me/publicgroup","کانال تلگرام"},
            {"http://buyer-c.example/","ناامن"},
            {"https://localhost/","نشانی خصوصی"}
        });
        UnifiedExcelImport.Parsed parsed=UnifiedExcelImport.read(new ByteArrayInputStream(excel));
        assertEquals(3,parsed.sources.size());
        assertEquals(3,parsed.rejected);
        try(MainActivity.Store store=new MainActivity.Store(context)) {
            UnifiedExcelImport.Report report=UnifiedExcelImport.apply(store,parsed);
            assertEquals(2,report.sources);
            assertEquals(2,new DiscoveryData(store).importedSourceCount());
            assertEquals(parsed.rejected+1,report.rejected); // One duplicate was rejected at apply
            List<String> rotated=new DiscoveryData(store).rotatedImportedSources(1,2);
            assertEquals(Arrays.asList("https://buyer-b.example/","https://buyer-a.example/"),rotated);
            UnifiedExcelImport.Report again=UnifiedExcelImport.apply(store,parsed);
            assertEquals(0,again.sources);
            assertEquals(2,new DiscoveryData(store).importedSourceCount());
        }
    }

    @Test public void acceptsSingleColumnAndUnifiedReportWebSourcesWithoutTreatingThemAsKeywords()throws Exception {
        byte[] simple=workbook(new String[][]{
            {"https://buyer-01.example/contact"},
            {"https://buyer-02.example/"}
        });
        UnifiedExcelImport.Parsed raw=UnifiedExcelImport.read(new ByteArrayInputStream(simple));
        assertEquals(2,raw.sources.size());
        assertEquals(0,raw.buyers.size());
        byte[] report=workbook(new String[][]{
            {"نوع داده","عنوان","شماره / شناسه / آدرس","منبع"},
            {"منبع وب","شرکت مجری","https://buyer-03.example/contact","ورودی اکسل"},
            {"گزارش صف","صف ارسال","0001","TDLib"}
        });
        UnifiedExcelImport.Parsed fromReport=UnifiedExcelImport.read(new ByteArrayInputStream(report));
        assertEquals(1,fromReport.sources.size());
        assertEquals(0,fromReport.rejected);
        assertEquals("https://buyer-03.example/contact",fromReport.sources.get(0).url);
        byte[] common=workbook(new String[][]{
            {"منبع"},
            {"https://buyer-04.example/"},
            {"https://buyer-05.example/projects/"}
        });
        assertEquals(2,UnifiedExcelImport.read(new ByteArrayInputStream(common)).sources.size());
    }

    @Test public void excelSourceBackupRestoreKeepsPreviouslySavedNumbers()throws Exception {
        var context=RuntimeEnvironment.getApplication();
        context.deleteDatabase("electric_connect_native_free.db");
        byte[] backup;
        try(MainActivity.Store store=new MainActivity.Store(context)){
            assertTrue(store.insertRecord("مخاطب قبلی","02156519931","برق","دستی"));
            UnifiedExcelImport.Parsed imported=new UnifiedExcelImport.Parsed();
            imported.sources.add(new UnifiedExcelImport.WebSource(
                "https://buyer-storage.example/contact","شرکت مجری"));
            assertEquals(1,UnifiedExcelImport.apply(store,imported).sources);
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            LocalBackup.write(store,output);
            backup=output.toByteArray();
            assertTrue(new String(backup,"UTF-8").contains("imported_web_sources"));
        }
        context.deleteDatabase("electric_connect_native_free.db");
        try(MainActivity.Store restored=new MainActivity.Store(context)){
            assertEquals(1,LocalBackup.restore(restored,new ByteArrayInputStream(backup)));
            assertEquals(1,restored.count("records"));
            assertEquals(1,new DiscoveryData(restored).importedSourceCount());
            assertEquals(0,LocalBackup.restore(restored,new ByteArrayInputStream(backup)));
            assertEquals(1,new DiscoveryData(restored).importedSourceCount());
        }
    }

    @Test public void rejectsUnknownFileBeforeDatabaseWrite()throws Exception{
        try{
            UnifiedExcelImport.read(new ByteArrayInputStream("not an excel file".getBytes("UTF-8")));
            fail("An invalid Excel must be rejected.");
        }catch(IOException expected){assertNotNull(expected.getMessage());}
    }
}
