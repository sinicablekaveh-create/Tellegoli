package ir.electricconnect.nativefree;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, manifest=Config.NONE)
public class StoreTest {
    private Context context;

    @Before public void resetDatabase() {
        context=RuntimeEnvironment.getApplication();
        context.deleteDatabase("electric_connect_native_free.db");
    }

    @Test public void searchesAcrossFieldsWithNormalizedMultipleTermsAndLiteralWildcards() {
        try(MainActivity.Store store=new MainActivity.Store(context)) {
            store.insertRecord("صنايع كيان", "0912", "كابل", "https://example.com/تهران 50%_\\");
            store.insertRecord("Other", "0913", "", "تهران 500");
            assertEquals(1,store.find("کیان ۰۹۱۲ تهران",0,51).size());
            assertEquals(1,store.find("50%_\\",0,51).size());
            assertEquals(0,store.find("کیان اصفهان",0,51).size());
        }
    }

    @Test public void canBrowseBeyondTheFormerHundredRecordLimit() {
        try(MainActivity.Store store=new MainActivity.Store(context)) {
            for(int n=0;n<130;n++)store.insertRecord("Business "+n,"","","source");
            assertEquals(51,store.find("",0,51).size());
            assertEquals(51,store.find("",50,51).size());
            assertEquals(30,store.find("",100,51).size());
            assertEquals("Business 29",store.find("",100,51).get(0).title);
        }
    }

    @Test public void versionTwoMigrationKeepsOriginalDuplicateRowsDraftsAndSessionFiles()throws Exception {
        java.io.File session=new java.io.File(context.getFilesDir(),"tdlib-account-db");assertTrue(session.isDirectory()||session.mkdirs());
        java.io.File marker=new java.io.File(session,"existing-session");java.nio.file.Files.write(marker.toPath(),"local session sentinel".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try(SQLiteDatabase old=context.openOrCreateDatabase("electric_connect_native_free.db",0,null)) {
            old.execSQL("CREATE TABLE records (id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,phone TEXT NOT NULL,category TEXT NOT NULL,source TEXT NOT NULL,search_text TEXT NOT NULL,created INTEGER NOT NULL,UNIQUE(title,phone))");
            old.execSQL("CREATE TABLE drafts (id INTEGER PRIMARY KEY AUTOINCREMENT,destination TEXT NOT NULL,message TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'DRAFT',created INTEGER NOT NULL)");
            old.execSQL("INSERT INTO records VALUES(1,'کابل قبلی','۰۲۱۵۶۵۱۹۹۳۱','دستی','یادداشت اصلی','کابل قبلی',42)");
            old.execSQL("INSERT INTO records VALUES(2,'کابل تکراری قبلی','02156519931','دستی','یادداشت دوم','کابل تکراری قبلی',43)");
            old.execSQL("INSERT INTO drafts VALUES(1,'گروه','پیش‌نویس','DRAFT',1)");old.setVersion(2);
        }
        try(MainActivity.Store upgraded=new MainActivity.Store(context)) {
            assertEquals(2,upgraded.count("records"));assertEquals(1,upgraded.count("drafts"));assertEquals(6,upgraded.getReadableDatabase().getVersion());
            assertEquals("Migrated v2 database must contain the new Excel group table",0,new DiscoveryData(upgraded).importedGroupCount());
            assertEquals("یادداشت اصلی",upgraded.find("قبلی",0,10).get(1).source);
            assertFalse(upgraded.insertPublicBusinessPhone("جدید","+98 21 56519931","https://cable.example/contact"));assertEquals(2,upgraded.count("records"));
        }
        assertEquals("local session sentinel",new String(java.nio.file.Files.readAllBytes(marker.toPath()),java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test public void versionFiveMigrationKeepsBothLegacyDuplicateContacts()throws Exception {
        try(SQLiteDatabase old=context.openOrCreateDatabase("electric_connect_native_free.db",0,null)) {
            old.execSQL("CREATE TABLE records (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                +" title TEXT NOT NULL, phone TEXT NOT NULL DEFAULT '', category TEXT NOT NULL DEFAULT '',"
                +" source TEXT NOT NULL DEFAULT '', search_text TEXT NOT NULL DEFAULT '',"
                +" created INTEGER NOT NULL, provider TEXT NOT NULL DEFAULT 'قدیمی / دستی',"
                +" retrieved INTEGER NOT NULL DEFAULT 0, UNIQUE(title,phone))");
            old.execSQL("CREATE TABLE drafts (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                +" destination TEXT NOT NULL, message TEXT NOT NULL,"
                +" status TEXT NOT NULL DEFAULT 'DRAFT', created INTEGER NOT NULL)");
            old.execSQL("INSERT INTO records(title,phone,category,source,search_text,created)"
                +" VALUES('الف','۰۲۱۵۶۵۱۹۹۳۱','تجاری','دستی','الف',1)");
            old.execSQL("INSERT INTO records(title,phone,category,source,search_text,created)"
                +" VALUES('ب','02156519931','تجاری','دستی','ب',2)");
            old.setVersion(5);
        }
        try(MainActivity.Store store=new MainActivity.Store(context)) {
            assertEquals(6,store.getReadableDatabase().getVersion());
            assertEquals("Migration must not silently delete existing duplicates",2,store.count("records"));
            try(android.database.Cursor indexed=store.getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM records WHERE canonical_phone=?",new String[]{"02156519931"})) {
                assertTrue(indexed.moveToFirst());
                assertEquals(2,indexed.getInt(0));
            }
            assertFalse(store.insertPublicBusinessPhone("ج","02156519931",
                "https://buyer.example/contact"));
        }
    }

    @Test public void indexedCanonicalPhoneLookupPreservesLegacyFormattingAndDeletion()throws Exception {
        try(MainActivity.Store store=new MainActivity.Store(context)) {
            assertTrue(store.insertRecord("شرکت قبلی","۰۲۱-۵۶۵۱۹۹۳۱","برق","دستی"));
            assertFalse("An equivalent public number must be rejected without scanning all records",
                store.insertPublicBusinessPhone("شرکت جدید","+98 21 56519931","https://buyer.example/contact"));
            assertEquals(1,store.count("records"));
            try(android.database.Cursor index=store.getReadableDatabase().rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_record_canonical_phone'",null)) {
                assertTrue("Canonical phone lookup should have its own SQLite index",index.moveToFirst());
            }
            long original=store.find("",0,5).get(0).id;
            store.deleteRecord(original);
            assertTrue("Deleted manual number must not leave a stale deduplication key",
                store.insertPublicBusinessPhone("شرکت جدید","02156519931","https://buyer.example/contact"));
            assertEquals(1,store.count("records"));
        }
    }

    @Test public void excelImportsUseTheSameCanonicalIndexAsLiveDiscovery()throws Exception {
        try(MainActivity.Store store=new MainActivity.Store(context)) {
            assertTrue(store.insertRecord("رکورد اصلی","۰۲۱-۵۶۵۱۹۹۳۱","دستی","دفتر"));
            UnifiedExcelImport.Parsed imported=new UnifiedExcelImport.Parsed();
            imported.contacts.add(new UnifiedExcelImport.Contact(
                "شرکت تکراری","02156519931","تجاری","https://buyer.example/contact"));
            imported.contacts.add(new UnifiedExcelImport.Contact(
                "شرکت تازه","۰۹۱۲۱۲۳۴۵۶۷","تجاری","https://buyer.example/contact"));
            UnifiedExcelImport.Report result=UnifiedExcelImport.apply(store,imported);
            assertEquals(1,result.contacts);
            assertEquals(1,result.rejected);
            assertEquals(2,store.count("records"));
            assertFalse(store.insertPublicBusinessPhone("شرکت سوم","09121234567",
                "https://buyer.example/contact"));
        }
    }

    @Test public void upgradesExistingRecordsAndPreservesDrafts() {
        try(SQLiteDatabase old=context.openOrCreateDatabase("electric_connect_native_free.db",0,null)) {
            old.execSQL("CREATE TABLE records (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, phone TEXT NOT NULL DEFAULT '', category TEXT NOT NULL DEFAULT '', source TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL, UNIQUE(title,phone))");
            old.execSQL("CREATE TABLE drafts (id INTEGER PRIMARY KEY AUTOINCREMENT, destination TEXT NOT NULL, message TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'DRAFT', created INTEGER NOT NULL)");
            old.execSQL("INSERT INTO records(title,phone,category,source,created) VALUES('كِيان','0912','كابل','تهران',1)");
            old.execSQL("INSERT INTO drafts(destination,message,created) VALUES('Test','Draft text',1)");
            old.setVersion(1);
        }
        try(MainActivity.Store upgraded=new MainActivity.Store(context)) {
            assertEquals(1,upgraded.find("کیان تهران ۰۹۱۲",0,51).size());
            assertEquals(1,upgraded.count("records"));
            assertEquals("Draft text",upgraded.drafts().get(0).message);
        }
    }
}
