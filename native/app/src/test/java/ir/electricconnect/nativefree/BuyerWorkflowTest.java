package ir.electricconnect.nativefree;

import android.content.Context;
import android.os.Looper;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.robolectric.util.ReflectionHelpers;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class BuyerWorkflowTest {
    Context app;MainActivity.Store store;DiscoveryData data;
    @Before public void setup(){
        app=RuntimeEnvironment.getApplication();app.deleteDatabase("electric_connect_native_free.db");
        app.getSharedPreferences("discovery",0).edit().clear().putBoolean("sales_auto_enabled",false).commit();
        app.getSharedPreferences("exa_ui_settings",0).edit().clear().commit();
        ReflectionHelpers.setStaticField(DiscoveryCoordinator.class,"shared",null);
        ReflectionHelpers.setStaticField(TdlibEngine.class,"shared",null);
        store=new MainActivity.Store(app);data=new DiscoveryData(store);
    }
    @After public void close()throws Exception{
        DiscoveryCoordinator coordinator=ReflectionHelpers.getStaticField(DiscoveryCoordinator.class,"shared");
        if(coordinator!=null){
            coordinator.stop();
            ExecutorService executor=ReflectionHelpers.getField(coordinator,"executor");
            executor.shutdown();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));
            shadowOf(Looper.getMainLooper()).idle();
            coordinator.data.store.close();
            ReflectionHelpers.setStaticField(DiscoveryCoordinator.class,"shared",null);
        }
        store.close();
    }
    private byte[] xlsx(List<String[]> rows)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();ExcelWorkbook.write(bytes,"کلیدواژه‌ها",rows);return bytes.toByteArray();
    }
    @Test public void directXlsxImportUsesNamedColumnAcrossSheetsAndPreservesOldData()throws Exception{
        store.insertRecord("مخاطب قبلی","02112345678","قدیمی","دستی");store.addDraft("گروه مجاز","پیش‌نویس قبلی");
        KeywordWorkbook.Result result=KeywordWorkbook.read(new ByteArrayInputStream(xlsx(Arrays.asList(
            new String[]{"شماره","کلیدواژه"},new String[]{"۱","پیمانکار برق تبریز"},
            new String[]{"۲","پیمانكار برق تبريز"},new String[]{"۳","سینی کابل"},
            new String[]{"۴","تولیدکننده سینی کابل"},new String[]{"۵","مجری تأسیسات الکتریکی"}))));
        assertEquals(2,result.keywords.size());assertEquals(2,result.rejected);assertEquals(1,result.duplicates);
        assertEquals(2,LeadKeywords.merge(data,result.keywords));assertEquals(0,LeadKeywords.merge(data,result.keywords));
        assertEquals("پیمانکار برق تبریز",LeadKeywords.all(data).get(0));
        assertEquals(1,store.count("records"));assertEquals(1,store.count("drafts"));
        try(MainActivity.Store restarted=new MainActivity.Store(app)){
            assertEquals(result.keywords,LeadKeywords.imported(new DiscoveryData(restarted)));
            assertEquals(1,restarted.count("records"));
        }
    }
    @Test public void sharedStringsRichTextAndFormulaCellsAreHandledWithoutEvaluation()throws Exception{
        Map<String,String> entries=new LinkedHashMap<>();
        entries.put("[Content_Types].xml","<Types/>");entries.put("xl/workbook.xml","<workbook/>");
        entries.put("xl/sharedStrings.xml","<sst><si><t>کلیدواژه</t></si><si><r><t>مجری برق </t></r><r><t>سوله</t></r></si></sst>");
        entries.put("xl/worksheets/sheet1.xml","<worksheet><sheetData>"
            +"<row><c r='A1' t='s'><v>0</v></c></row>"
            +"<row><c r='A2' t='s'><v>1</v></c></row>"
            +"<row><c r='A3' t='str'><f>HYPERLINK()</f><v>پیمانکار برق جعلی</v></c></row>"
            +"</sheetData></worksheet>");
        entries.put("xl/worksheets/sheet2.xml","<worksheet><sheetData><row><c r='A1' t='inlineStr'><is><t>پیمانکار برق پروژه</t></is></c></row></sheetData></worksheet>");
        KeywordWorkbook.Result result=KeywordWorkbook.read(new ByteArrayInputStream(zip(entries)));
        assertEquals(Arrays.asList("مجری برق سوله","پیمانکار برق پروژه"),result.keywords);
    }
    private byte[] zip(Map<String,String> entries)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)){
            for(Map.Entry<String,String> e:entries.entrySet()){
                zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            }
        }return bytes.toByteArray();
    }
    @Test public void invalidOversizedOrProductOnlyImportsDoNotReplaceKeywords()throws Exception{
        LeadKeywords.merge(data,Collections.singletonList("پیمانکار برق تبریز"));
        for(byte[] invalid:Arrays.asList("not an xlsx".getBytes(StandardCharsets.UTF_8),
                xlsx(Collections.singletonList(new String[]{"نردبان کابل"})),
                zip(Collections.singletonMap("bomb.xml",String.join("",Collections.nCopies(21*1024,"x".repeat(1024))))))) {
            try{KeywordWorkbook.read(new ByteArrayInputStream(invalid));fail();}catch(IOException expected){}
        }
        List<String> tooMany=new ArrayList<>();for(int i=0;i<51;i++)tooMany.add("پیمانکار برق پروژه "+i);
        try{LeadKeywords.merge(data,tooMany);fail();}catch(IllegalArgumentException expected){}
        assertEquals(Collections.singletonList("پیمانکار برق تبریز"),LeadKeywords.imported(data));
        assertFalse(LeadKeywords.allowed("Cable Tray Supplier"));assertFalse(LeadKeywords.allowed("تولید کننده سینی کابل"));
    }
    @Test public void automaticPhoneCaptureRejectsManufacturersButKeepsPreviousContacts()throws Exception{
        store.insertRecord("تولیدکننده قدیمی","02112345678","قدیمی","دستی");
        JSONArray results=new JSONArray()
            .put(new JSONObject().put("title","شرکت تولید سینی کابل").put("url","https://supplier.example/contact").put("text","مشتریان ما پیمانکار برق صنعتی هستند؛ تلفن تماس: 02155551111"))
            .put(new JSONObject().put("title","شرکت پیمانکار برق صنعتی").put("url","https://buyer.example/contact").put("text","تلفن تماس: ۰۲۱۵۵۵۵۲۲۲۲"));
        PublicPhoneDiscovery.Summary summary=PublicPhoneDiscovery.saveBuyers(store,results,null,()->true);
        assertEquals(1,summary.saved);assertEquals(1,summary.rejected);assertEquals(2,store.count("records"));
        assertEquals(0,PublicPhoneDiscovery.saveBuyers(store,results,null,()->true).saved);
        assertFalse(BuyerRelevance.accepts("شرکت فروش تجهیزات","خدمات به پیمانکار برق صنعتی و فروش سینی کابل"));
        assertTrue(BuyerRelevance.accepts("شرکت اجرایی","اجرای تأسیسات الکتریکی و کابل‌کشی پروژه"));
    }
    @Test public void importedKeywordsReachOptionalWebQueryAndReturnedSitesTakePriority()throws Exception{
        LeadKeywords.merge(data,Collections.singletonList("پیمانکار برق تبریز"));
        DiscoveryCoordinator.Run run=new DiscoveryCoordinator.Run(data,new AtomicBoolean(),null);run.topics=LeadKeywords.all(data);
        run.seeds.add("https://custom-buyer.example/");
        List<String> requests=new ArrayList<>();
        SearXngSeedProvider provider=new SearXngSeedProvider("https://search.example/search",url->{
            requests.add(java.net.URLDecoder.decode(url,"UTF-8"));
            return new PublicHttp.Page(200,"{\"results\":[{\"url\":\"https://buyer.example/\"}]}","application/json","");
        });
        provider.discover(run);assertTrue(requests.get(0).contains("پیمانکار برق تبریز"));
        assertEquals("https://custom-buyer.example/",run.seeds.get(0));
        assertEquals("https://buyer.example/",run.seeds.get(1));assertEquals(1,data.get("searxng_topic_cycle").optInt("next"));
    }
    @Test public void optionalCaptchaWidgetDoesNotHidePublicContactsButChallengeWallsAreDenied(){
        assertFalse(PublicWebsiteContactsProvider.isAccessChallenge(org.jsoup.Jsoup.parse(
            "<title>شرکت پیمانکار برق صنعتی</title><p>تلفن تماس: 021.55552222</p><form><div class='g-recaptcha'></div></form><script src='https://www.google.com/recaptcha/api.js'></script>")));
        assertTrue(PublicWebsiteContactsProvider.isAccessChallenge(org.jsoup.Jsoup.parse("<title>Just a moment...</title><form id='challenge-form'></form>")));
        assertTrue(PublicWebsiteContactsProvider.isAccessChallenge(org.jsoup.Jsoup.parse("<title>Verification</title><p>Please verify you are human</p>")));
    }
    @Test public void websiteCaptureKeepsLabeledDottedBusinessPhoneAndExcludesComments()throws Exception{
        DiscoveryCoordinator.Run run=new DiscoveryCoordinator.Run(data,new AtomicBoolean(),null);
        run.seeds.add("https://buyer.example/");run.maxHosts=1;run.maxPages=1;
        new PublicWebsiteContactsProvider((url,hosts)->new PublicHttp.Page(200,
            url.endsWith("robots.txt")?"User-agent: *\nAllow: /":
            "<title>شرکت پیمانکار برق صنعتی</title><p>تلفن تماس: 021.55552222</p>"
            +"<div id='comments'>کاربر: شماره تماس: 09123334444</div>"
            +"<script src='https://www.google.com/recaptcha/api.js'></script>",
            url.endsWith("robots.txt")?"text/plain":"text/html","")).discover(run);
        assertEquals(1,run.saved);assertEquals(1,store.count("records"));
        assertEquals("02155552222",store.find("",0,10).get(0).phone);
    }
    @Test public void stopClearsDeferredAutomaticRestartAndMaintainsContacts(){
        store.insertRecord("قبلی","02112345678","قدیمی","دستی");
        app.getSharedPreferences("discovery",0).edit().putBoolean("telegram_pending",true).commit();
        DiscoveryCoordinator.get(app).stopByUser();
        assertFalse(app.getSharedPreferences("discovery",0).getBoolean("telegram_pending",true));
        assertTrue(app.getSharedPreferences("discovery",0).getLong("sales_auto_started",0)>0);
        assertEquals(1,store.count("records"));
    }
    @Test public void missingReadyDoesNotSendAnyTelegramRequest()throws Exception{
        TdlibEngine engine=new TdlibEngine(app);
        ReflectionHelpers.setField(engine,"client",(TdlibEngine.Session)(f,h)->fail("Not READY"));
        DiscoveryCoordinator.Run run=new DiscoveryCoordinator.Run(data,new AtomicBoolean(),null);
        DiscoveryCoordinator.execute(run,Collections.singletonList(new TelegramPublicGroupProvider(engine)));
        assertEquals("waiting",run.state);assertEquals(0,run.groups);
    }
    @Test public void pendingExplicitDiscoveryResumesAfterReadyWithDailyAutomationOff()throws Exception{
        app.getSharedPreferences("discovery",0).edit().putBoolean("telegram_pending",true).commit();
        TdlibEngine engine=TdlibEngine.get(app);
        ReflectionHelpers.setField(engine,"client",(TdlibEngine.Session)(f,h)->fail("Daily completed topics must not be replayed"));
        DiscoveryApplication application=(DiscoveryApplication)app;
        application.maybeAutoDiscover();assertFalse(DiscoveryCoordinator.get(app).isRunning());
        // Public-group discovery now has its own keyword family, separate from buyer leads.
        for(String topic:GroupKeywords.all(data))data.put("telegram:"+topic,new JSONObject().put("success",System.currentTimeMillis()));
        ReflectionHelpers.setField(engine,"loggedIn",true);
        application.maybeAutoDiscover();
        DiscoveryCoordinator coordinator=DiscoveryCoordinator.get(app);
        long until=System.currentTimeMillis()+5000;
        while(coordinator.isRunning()&&System.currentTimeMillis()<until){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}
        assertFalse(coordinator.isRunning());assertEquals(1,coordinator.snapshot().optInt("scope"));
        assertEquals("finished",coordinator.snapshot().optString("state"));
        assertFalse(app.getSharedPreferences("discovery",0).getBoolean("telegram_pending",true));
    }
    @Implements(value=TdlibEngine.class,isInAndroidSdk=false)
    public static class SavedSessionShadow {
        static int connectCalls;
        @Implementation protected boolean hasCredentials(){return true;}
        @Implementation protected void connect(){connectCalls++;}
    }
    @Test @Config(shadows=SavedSessionShadow.class)
    public void coldProcessReconnectsPendingSavedSessionWithDailyDiscoveryOff(){
        SavedSessionShadow.connectCalls=0;
        DiscoveryApplication application=(DiscoveryApplication)app;
        application.maybeAutoDiscover();
        assertEquals("No daily or pending request must stay quiet",0,SavedSessionShadow.connectCalls);
        app.getSharedPreferences("discovery",0).edit().putBoolean("telegram_pending",true).commit();
        application.maybeAutoDiscover();
        assertEquals("Saved session must reconnect before READY can arrive",1,SavedSessionShadow.connectCalls);
        assertFalse("Group discovery must wait for READY",DiscoveryCoordinator.get(app).isRunning());
        assertTrue(app.getSharedPreferences("discovery",0).getBoolean("telegram_pending",false));
    }
    @Test public void searchScreenOpensNativeExcelPickerWithoutLaunchingDiscovery(){
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=controller.get();
            OfflineExcelTest.find(activity.getWindow().getDecorView(),"جستجو").performClick();
            OfflineExcelTest.find(activity.getWindow().getDecorView(),"ورودی یکجای اکسل").performClick();
            android.content.Intent intent=shadowOf(activity).getNextStartedActivityForResult().intent;
            assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT,intent.getAction());
            assertEquals(ExcelWorkbook.MIME,intent.getType());
            assertFalse(DiscoveryCoordinator.get(app).isRunning());
        }
    }
    @Test public void searchPagePublicDiscoveryStartsJobInsteadOfGoingHome(){
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=controller.get();
            OfflineExcelTest.find(activity.getWindow().getDecorView(),"جستجو").performClick();
            OfflineExcelTest.find(activity.getWindow().getDecorView(),"شروع جستجوی مشتریان و گروه‌ها").performClick();
            DiscoveryCoordinator coordinator=DiscoveryCoordinator.get(app);
            assertEquals(2,app.getSharedPreferences("discovery",0).getInt("scope",-1));
            assertTrue("The button must start discovery",coordinator.isRunning());
            // Staying on the search screen lets users import keywords and inspect local results.
            assertNotNull(OfflineExcelTest.find(activity.getWindow().getDecorView(),"ورودی یکجای اکسل"));
            coordinator.stop();
        }
    }
    @Test public void contactsCombinedDiscoveryStartsJobInsteadOfGoingHome(){
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=controller.get();
            OfflineExcelTest.find(activity.getWindow().getDecorView(),"مخاطبان").performClick();
            android.view.View action=(android.view.View)((android.view.View)OfflineExcelTest.find(
                activity.getWindow().getDecorView(),"کاوش هم‌زمان مخاطبان و گروه‌ها").getParent()).getParent();
            assertTrue(action.performClick());
            DiscoveryCoordinator coordinator=DiscoveryCoordinator.get(app);
            assertEquals(2,app.getSharedPreferences("discovery",0).getInt("scope",-1));
            assertTrue("Combined discovery must start",coordinator.isRunning());
            assertNotNull(OfflineExcelTest.find(activity.getWindow().getDecorView(),"جست‌وجوی آفلاین در شماره‌های ذخیره‌شده"));
            coordinator.stop();
        }
    }
    @Test public void deferredTelegramBackoffCannotFallThroughToDailyWebAutomation(){
        TdlibEngine engine=TdlibEngine.get(app);
        ReflectionHelpers.setField(engine,"client",(TdlibEngine.Session)(f,h)->fail("Backoff must prevent account work"));
        ReflectionHelpers.setField(engine,"loggedIn",true);
        app.getSharedPreferences("discovery",0).edit().putBoolean("sales_auto_enabled",true)
            .putBoolean("telegram_pending",true).putLong("telegram_auto_started",System.currentTimeMillis()).commit();
        app.getSharedPreferences("exa_ui_settings",0).edit().putBoolean("online_enabled",true).commit();
        ((DiscoveryApplication)app).maybeAutoDiscover();
        assertFalse(DiscoveryCoordinator.get(app).isRunning());
        assertTrue(app.getSharedPreferences("discovery",0).getBoolean("telegram_pending",false));
    }
}
