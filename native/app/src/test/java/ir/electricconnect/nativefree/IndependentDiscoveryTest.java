package ir.electricconnect.nativefree;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class IndependentDiscoveryTest {
    private Context app;private MainActivity.Store store;private DiscoveryData data;
    @Before public void setup() {
        ReflectionHelpers.setStaticField(DiscoveryCoordinator.class,"shared",null);
        app=RuntimeEnvironment.getApplication();app.deleteDatabase("electric_connect_native_free.db");
        app.getSharedPreferences("exa_ui_settings",0).edit().clear().commit();
        store=new MainActivity.Store(app);data=new DiscoveryData(store);
    }
    @After public void close(){store.close();}
    private DiscoveryCoordinator.Run run() {DiscoveryCoordinator.Run r=new DiscoveryCoordinator.Run(data,new AtomicBoolean(),null);r.maxHosts=1;r.maxPages=4;r.seeds.add("https://cable.example/");return r;}
    private static String html(String phone) {return "<html><head><title>شرکت پیمانکار برق صنعتی نمونه</title></head><body><p>دفتر فروش؛ تلفن تماس: "+phone+"</p></body></html>";}
    private static PublicHttp.Page page(int status,String text,String type) {return new PublicHttp.Page(status,text,type,"");}

    @Test public void noExaKeyFindsNewPhoneThroughContactLinkFromRealLocalServerAndSavesOffline()throws Exception {
        assertFalse(app.getSharedPreferences("exa_ui_settings",0).getBoolean("online_enabled",false));
        try(MockWebServer server=new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("User-agent: *\nDisallow: /private"));
            server.enqueue(new MockResponse().setBody("<title>شرکت کابل</title><a href='/contact'>تماس با ما</a>"));
            server.enqueue(new MockResponse().setBody(html("۰۲۱-۵۶۵۱۹۹۳۱")));
            server.start();OkHttpClient local=new OkHttpClient();
            PublicWebsiteContactsProvider provider=new PublicWebsiteContactsProvider((url,hosts)->{
                // Only tests substitute an isolated local HTTP server for an approved public site.
                PublicHttp.validate(url,hosts);
                try(Response response=local.newCall(new Request.Builder().url(server.url(URI.create(url).getPath())).build()).execute()) {
                    return page(response.code(),response.body().string(),url.endsWith("robots.txt")?"text/plain":"text/html");
                }
            });
            DiscoveryCoordinator.Run r=run();DiscoveryCoordinator.execute(r,Collections.singletonList(provider));
            assertEquals(1,r.saved);assertEquals(2,r.pages);assertEquals("finished",r.state);assertEquals(3,server.getRequestCount());
            assertEquals("/robots.txt",server.takeRequest(1,TimeUnit.SECONDS).getPath());
            assertEquals("/",server.takeRequest(1,TimeUnit.SECONDS).getPath());
            assertEquals("/contact",server.takeRequest(1,TimeUnit.SECONDS).getPath());
            assertEquals(1,store.count("records"));
            MainActivity.Record saved=store.find("56519931",0,100).get(0);
            assertEquals("02156519931",saved.phone);assertEquals("https://cable.example/contact",saved.source);assertEquals("وب‌سایت عمومی",saved.provider);assertTrue(saved.retrieved>0);
            try(MainActivity.Store restarted=new MainActivity.Store(app)){assertEquals(1,restarted.find("شرکت 021",0,100).size());}
        }
    }
    @Test public void liveDiscoveredBuyerSitesTakePrecedenceOverFallbackSeeds()throws Exception {
        // An Exa/SearXNG/user-discovered website may be the only site reached
        // under the normal per-run host limit. Bundled sites must not starve it.
        DiscoveryCoordinator.Run r=run();
        r.seeds.clear();
        r.seeds.add("https://freshbuyer.example/");
        r.maxHosts=1;
        List<String> requested=new ArrayList<>();
        new PublicWebsiteContactsProvider((url,hosts)->{
            requested.add(url);
            return page(200,url.endsWith("robots.txt")?"User-agent: *\\nAllow: /":html("02156519931"),
                url.endsWith("robots.txt")?"text/plain":"text/html");
        }).discover(r);
        assertEquals(1,r.saved);
        assertEquals("https://freshbuyer.example/",store.find("",0,10).get(0).source);
        assertFalse(requested.isEmpty());
        for(String url:requested)assertTrue(url,url.startsWith("https://freshbuyer.example/"));
    }


    @Test public void completedDefaultSiteNeverConsumesNewExcelSiteHostSlot()throws Exception {
        DiscoveryCoordinator.Run r=run();
        r.seeds.clear();
        r.maxHosts=1;
        r.maxPages=1;
        String done="https://finished-buyer.example/";
        String fresh="https://fresh-from-excel.example/";
        r.seeds.add(done);
        r.seeds.add(fresh);
        assertTrue(data.queue(done));
        data.finishUrl(done,"done");
        List<String> calls=new ArrayList<>();
        new PublicWebsiteContactsProvider((url,hosts)->{
            calls.add(url);
            return page(200,url.endsWith("robots.txt")?"User-agent: *\\nAllow: /":
                html("02156519931"),url.endsWith("robots.txt")?"text/plain":"text/html");
        }).discover(r);
        assertEquals(1,r.saved);
        assertEquals(1,r.pages);
        assertFalse(calls.isEmpty());
        for(String url:calls)assertTrue("Must scan fresh buyer, not completed seed: "+url,
            url.startsWith(fresh));
    }

    @Test public void selectedExcelHostCanScanPastAnOldHundredUrlBacklog()throws Exception {
        for(int i=0;i<140;i++)assertTrue(data.queue(
            "https://previous"+i+".example/contact"));
        String fresh="https://fresh-excel.example/";
        DiscoveryCoordinator.Run r=run();
        r.seeds.clear();r.seeds.add(fresh);r.maxHosts=1;r.maxPages=1;
        new PublicWebsiteContactsProvider((url,hosts)->page(200,
            url.endsWith("robots.txt")?"User-agent: *\\nAllow: /":html("02156519931"),
            url.endsWith("robots.txt")?"text/plain":"text/html")).discover(r);
        assertEquals(1,r.saved);
        assertEquals(fresh,store.find("02156519931",0,10).get(0).source);
        assertEquals(1,r.pages);
        assertTrue(data.total("discovery_urls")>=141); // Bundled fallback sites may be queued too.
    }


    @Test public void anExcelWebsiteContactLinkStillExpandsWithOldPendingBacklog()throws Exception {
        for(int i=0;i<120;i++)data.queue("https://old-site"+i+".example/contact");
        DiscoveryCoordinator.Run r=run();
        r.seeds.clear();
        r.seeds.add("https://fresh-link.example/");
        r.maxHosts=1;r.maxPages=2;
        new PublicWebsiteContactsProvider((url,hosts)->{
            if(url.endsWith("robots.txt"))
                return page(200,"User-agent: *\\nAllow: /","text/plain");
            if(url.endsWith("/contact"))
                return page(200,html("02156519931"),"text/html");
            return page(200,"<title>شرکت پیمانکار برق صنعتی</title>"
                +"<a href='/contact'>تماس با ما</a>","text/html");
        }).discover(r);
        assertEquals(2,r.pages);
        assertEquals(1,r.saved);
        assertEquals("https://fresh-link.example/contact",
            store.find("02156519931",0,10).get(0).source);
    }


    @Test public void importedExcelWebsitesReceiveHostSlotsDespiteLiveSearchResults()throws Exception {
        UnifiedExcelImport.Parsed input=new UnifiedExcelImport.Parsed();
        input.sources.add(new UnifiedExcelImport.WebSource(
            "https://buyer-imported.example/contact","مجری برق آزمون"));
        assertEquals(1,UnifiedExcelImport.apply(store,input).sources);
        DiscoveryCoordinator.Run pass=run();
        pass.seeds.clear();
        pass.seeds.add("https://new-search-result.example/");
        pass.maxHosts=1;pass.maxPages=1;
        List<String> fetched=new ArrayList<>();
        new PublicWebsiteContactsProvider((url,hosts)->{
            fetched.add(url);
            return page(200,url.endsWith("robots.txt")?"User-agent: *\\nAllow: /":
                    html("02156519931"),
                url.endsWith("robots.txt")?"text/plain":"text/html");
        }).discover(pass);
        assertEquals(1,pass.saved);
        assertEquals("https://buyer-imported.example/contact",
            store.find("02156519931",0,10).get(0).source);
        for(String url:fetched)assertTrue("User Excel source is selected: "+url,
            url.startsWith("https://buyer-imported.example/"));
    }

    @Test public void exa403FallsBackToIndependentSourceWithoutRepeatingPaidRequest()throws Exception {
        AtomicInteger exa=new AtomicInteger();
        DiscoveryCoordinator.Run r=run();
        ExaProvider optional=new ExaProvider(()->{exa.incrementAndGet();throw new IOException(ExaHttpError.describe(403,""));});
        PublicWebsiteContactsProvider independent=new PublicWebsiteContactsProvider((url,hosts)->page(200,url.endsWith("robots.txt")?"User-agent: *\nAllow: /":html("02156519931"),url.endsWith("robots.txt")?"text/plain":"text/html"));
        DiscoveryCoordinator.execute(r,Arrays.asList(optional,independent));
        assertEquals(1,exa.get());assertEquals(1,r.saved);assertEquals(1,r.errors);assertTrue(r.note.contains("403"));
    }
    @Test public void robotsDenialAndAllowlistPreventFetchingPageOrOtherHosts()throws Exception {
        List<String> fetched=new ArrayList<>();DiscoveryCoordinator.Run r=run();
        new PublicWebsiteContactsProvider((url,hosts)->{fetched.add(url);return page(200,"User-agent: *\nDisallow: /","text/plain");}).discover(r);
        assertEquals(Collections.singletonList("https://cable.example/robots.txt"),fetched);assertEquals(0,store.count("records"));assertEquals(1,r.errors);
        assertDenied("https://other.example/contact",Collections.singleton("cable.example"));
    }
    private void assertDenied(String url,Set<String> hosts) {try{PublicHttp.validate(url,hosts);fail(url);}catch(PublicHttp.AccessDenied expected){}}
    @Test public void rejectsSsrfCredentialsCleartextQueriesAndPrivateDns()throws Exception {
        for(String url:Arrays.asList("file:///data/local","http://cable.example/","https://localhost/","https://127.0.0.1/","https://169.254.169.254/","https://2130706433/","https://[::1]/","https://user:pass@cable.example/","https://cable.example:8443/","https://cable.example/login","https://cable.example/contact?token=secret","https://t.me/person"))assertDenied(url,null);
        try(PublicHttp http=new PublicHttp(new AtomicBoolean())) {
            try {http.client.dns().lookup("localhost");fail();}catch(UnknownHostException expected){}
        }
        for(String ip:Arrays.asList("127.0.0.1","10.0.0.1","172.16.0.1","192.168.1.1","100.64.0.1","169.254.169.254","0.0.0.0","198.18.0.1","192.0.2.1","::ffff:127.0.0.1","2001:db8::1"))assertFalse(ip,PublicHttp.isPublic(InetAddress.getByName(ip)));
        assertTrue(PublicHttp.isPublic(InetAddress.getByName("8.8.8.8")));
    }
    @Test public void redirectsCannotEscapeHostAndSameHostSourcesRecordFinalUrl()throws Exception {
        DiscoveryCoordinator.Run r=run();AtomicInteger calls=new AtomicInteger();
        new PublicWebsiteContactsProvider((url,hosts)->{
            calls.incrementAndGet();
            if(url.endsWith("robots.txt"))return page(200,"User-agent: *\nAllow: /","text/plain");
            if(url.endsWith("/"))return new PublicHttp.Page(302,"","text/html","/contact");
            return page(200,html("02156519931"),"text/html");
        }).discover(r);
        assertEquals(1,r.saved);assertEquals("https://cable.example/contact",store.find("",0,100).get(0).source);assertEquals(3,calls.get());
        DiscoveryCoordinator.Run other=run();other.seeds.clear();other.seeds.add("https://other.example/");
        new PublicWebsiteContactsProvider((url,hosts)->url.endsWith("robots.txt")?page(200,"","text/plain"):new PublicHttp.Page(302,"","text/html","https://127.0.0.1/private")).discover(other);
        assertEquals(1,other.errors);assertEquals(0,other.saved);
    }
    @Test public void cancellationPreventsNewRequestsAndResumeRetainsSavedContact()throws Exception {
        DiscoveryCoordinator.Run first=run();
        try {
            new PublicWebsiteContactsProvider((url,hosts)->{
                if(!url.endsWith("robots.txt")) {first.cancelled.set(true);throw new InterruptedIOException();}
                return page(200,"","text/plain");
            }).discover(first);fail();
        }catch(InterruptedIOException expected){}
        assertEquals(1,data.pending(10).size());
        DiscoveryCoordinator.Run resumed=run();
        new PublicWebsiteContactsProvider((url,hosts)->page(200,url.endsWith("robots.txt")?"":html("02156519931"),url.endsWith("robots.txt")?"text/plain":"text/html")).discover(resumed);
        assertEquals(1,resumed.saved);assertEquals(1,store.count("records"));
    }
    @Test public void stoppedRunDoesNotTouchNetworkOrDatabase() {
        DiscoveryCoordinator.Run r=run();r.cancelled.set(true);AtomicInteger calls=new AtomicInteger();
        DiscoveryCoordinator.execute(r,Collections.singletonList(new PublicWebsiteContactsProvider((url,hosts)->{calls.incrementAndGet();return page(200,"","text/plain");})));
        assertEquals(0,calls.get());assertEquals("stopped",r.state);assertEquals(0,store.count("records"));
    }
    @Test public void rateLimitsHaveFiniteRetryCapAndPersistentRetryCheckpoint()throws Exception {
        DiscoveryCoordinator.Run r=run();AtomicInteger calls=new AtomicInteger();
        new PublicWebsiteContactsProvider((url,hosts)->{calls.incrementAndGet();return page(429,"","text/plain");}).discover(r);
        assertEquals(3,calls.get());assertEquals(1,r.errors);assertEquals(1,data.pending(20).size());
        JSONObject state=data.get("run");assertEquals(1,state.optInt("errors"));
    }
    @Test public void timeoutDoesNotWipeOldContactsAndCanResumeWithoutRefetchingDoneUrls()throws Exception {
        store.insertRecord("قبلی","02156519930","برق","دستی");DiscoveryCoordinator.Run first=run();
        new PublicWebsiteContactsProvider((url,hosts)->{throw new java.net.SocketTimeoutException();}).discover(first);
        assertEquals(1,store.count("records"));assertEquals(1,data.pending(20).size());
        DiscoveryCoordinator.Run retry=run();new PublicWebsiteContactsProvider((url,hosts)->page(200,url.endsWith("robots.txt")?"":html("02156519931"),url.endsWith("robots.txt")?"text/plain":"text/html")).discover(retry);
        assertEquals(2,store.count("records"));assertTrue(data.pending(20).isEmpty());
        AtomicInteger calls=new AtomicInteger();new PublicWebsiteContactsProvider((url,hosts)->{calls.incrementAndGet();throw new IOException();}).discover(run());assertEquals(0,calls.get());
    }
    @Test public void queueGapRefreshesOldPermittedPagesButNeverDeniedOnes() {
        String allowed="https://cable.example/contact";
        String forbidden="https://cable.example/blocked";
        assertTrue(data.queue(allowed));
        assertTrue(data.queue(forbidden));
        data.finishUrl(allowed,"done");
        data.finishUrl(forbidden,"denied");
        long past=System.currentTimeMillis()-TimeUnit.MINUTES.toMillis(9);
        store.getWritableDatabase().execSQL("UPDATE discovery_urls SET updated=?",
            new Object[]{past});
        data.renewOldPages(TimeUnit.MINUTES.toMillis(8));
        assertEquals(Collections.singletonList(allowed),data.pending(10));
        try(android.database.Cursor c=store.getReadableDatabase().rawQuery(
                "SELECT state FROM discovery_urls WHERE url=?",new String[]{forbidden})) {
            assertTrue(c.moveToFirst());
            assertEquals("denied",c.getString(0));
        }
    }

    @Test public void queueIntervalDiscoveryNeedsAnApprovedActiveQueue() {
        // No queue means no background web or account access, even with the option on.
        assertTrue(QueueGapDiscovery.enabled(app));
        assertFalse(ScheduledQueue.enabled(app));
        QueueGapDiscovery.maybeSearchGroups(app);
        assertEquals(0,data.groups().size());
    }

    @Test public void robotsMostSpecificRulesAndSpecificAgentOverrideGeneric() {
        RobotsRules r=new RobotsRules("User-agent: *\nDisallow: /\nUser-agent: ElectricConnect\nDisallow: /secret*\nAllow: /secret/contact$\nSitemap: https://cable.example/sitemap.xml");
        assertTrue(r.allows("/contact"));assertFalse(r.allows("/secret/file"));assertTrue(r.allows("/secret/contact"));assertFalse(r.allows("/secret/contact/private"));assertEquals(1,r.sitemaps.size());
    }
    @Test public void backupRoundTripMergesWithoutSendingOrSessionCredentialsAndRollsBackInvalidInput()throws Exception {
        store.insertRecord("شرکت کابل","۰۲۱۵۶۵۱۹۹۳۱","برق","https://cable.example/contact");store.addDraft("گروه مجاز","پیش‌نویس");
        data.saveGroup(new TdlibEngine.ChatOverview(-1001234567890123L,"گروه برق","گروه عمومی",false),"برق صنعتی");
        ByteArrayOutputStream backup=new ByteArrayOutputStream();LocalBackup.write(store,backup);
        String json=backup.toString("UTF-8");assertFalse(json.contains("api_hash"));assertFalse(json.contains("session"));
        store.clearAll();assertEquals(0,store.count("records"));
        assertEquals(1,LocalBackup.restore(store,new ByteArrayInputStream(backup.toByteArray())));assertEquals(1,store.count("records"));assertEquals(1,store.count("drafts"));
        assertEquals(0,LocalBackup.restore(store,new ByteArrayInputStream(backup.toByteArray())));assertEquals(1,store.count("drafts"));
        JSONObject invalid=new JSONObject(json);invalid.getJSONArray("records").put(new JSONObject().put("title","invalid"));
        try{LocalBackup.restore(store,new ByteArrayInputStream(invalid.toString().getBytes("UTF-8")));fail();}catch(JSONException expected){}
        assertEquals(1,store.count("records"));
    }
    @Test public void fullReportWebSourceSnapshotRetainsStatesAndAvoidsDuplicateUrls() {
        assertTrue(data.queue("https://example.org/businesses"));
        assertFalse(data.queue("https://example.org/businesses"));
        assertTrue(data.queue("https://example.org/blocked"));
        data.finishUrl("https://example.org/businesses","done");
        data.finishUrl("https://example.org/blocked","denied");
        assertEquals(2,data.total("discovery_urls"));
        assertEquals(1,data.finishedWebPages());
        java.util.List<String[]> result=data.webExport();
        assertEquals(2,result.size());
        java.util.Set<String> states=new java.util.HashSet<>();
        for(String[] source:result)states.add(source[1]);
        assertTrue(states.contains("denied"));
        assertTrue(states.contains("done"));
    }

    @Test public void periodicScopeIsHonoredAndDeferredTelegramOnlyClearsAfterSuccess() {
        android.content.SharedPreferences options=app.getSharedPreferences("discovery",0);
        options.edit().putInt("scope",0).putBoolean("telegram_pending",true).commit();
        assertEquals(0,DiscoveryWorker.selectedScope(app));
        options.edit().putInt("scope",1).commit();
        assertEquals(1,DiscoveryWorker.selectedScope(app));
        options.edit().putInt("scope",2).commit();
        assertEquals(2,DiscoveryWorker.selectedScope(app));
        options.edit().putInt("scope",99).commit();
        assertEquals(2,DiscoveryWorker.selectedScope(app));

        DiscoveryCoordinator.Run deferred=run();
        deferred.scope=1;deferred.state="finished";
        DiscoveryCoordinator.finishDeferredFlag(app,deferred);
        assertTrue("Merely starting a scan is not completion",options.getBoolean("telegram_pending",false));
        deferred.telegramCompleted=true;deferred.state="interrupted";
        DiscoveryCoordinator.finishDeferredFlag(app,deferred);
        assertTrue("Interrupted scan must resume later",options.getBoolean("telegram_pending",false));
        deferred.state="finished";deferred.background=true;
        DiscoveryCoordinator.finishDeferredFlag(app,deferred);
        assertTrue("Headless run cannot acknowledge personal Telegram",options.getBoolean("telegram_pending",false));
        deferred.background=false;deferred.cancelled.set(true);
        DiscoveryCoordinator.finishDeferredFlag(app,deferred);
        assertTrue("Stopped run must not acknowledge Telegram",options.getBoolean("telegram_pending",false));
        deferred.cancelled.set(false);
        DiscoveryCoordinator.finishDeferredFlag(app,deferred);
        assertFalse("Only successful completed scan can clear pending",options.getBoolean("telegram_pending",true));
    }

    @Test public void periodicWebWorkIsQuarterHourlyConstrainedAndOptOutCancelsUniqueWork()throws Exception {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app);
        app.getSharedPreferences("discovery",0).edit().remove("periodic_15_minutes").commit();
        androidx.work.PeriodicWorkRequest request=DiscoverySchedule.request(app);
        assertEquals(TimeUnit.MINUTES.toMillis(15),request.getWorkSpec().intervalDuration);
        assertEquals(TimeUnit.MINUTES.toMillis(15),request.getWorkSpec().initialDelay);
        app.getSharedPreferences("discovery",0).edit().putBoolean("periodic_15_minutes",false).commit();
        androidx.work.PeriodicWorkRequest daily=DiscoverySchedule.request(app);
        assertEquals(TimeUnit.HOURS.toMillis(24),daily.getWorkSpec().intervalDuration);
        app.getSharedPreferences("discovery",0).edit().putBoolean("periodic_15_minutes",true).commit();
        assertEquals(androidx.work.NetworkType.CONNECTED,request.getWorkSpec().constraints.getRequiredNetworkType());assertTrue(request.getWorkSpec().constraints.requiresBatteryNotLow());
        DiscoverySchedule.enable(app);DiscoverySchedule.enable(app);
        assertEquals(1,androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWork(DiscoverySchedule.WORK).get().size());
        DiscoverySchedule.disable(app);
        assertFalse(app.getSharedPreferences("discovery",0).getBoolean("periodic",true));
        assertEquals(androidx.work.WorkInfo.State.CANCELLED,androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWork(DiscoverySchedule.WORK).get().get(0).getState());
    }

    @Test public void telegramBackgroundScheduleIsOptInIndependentOfWebAndPersistent()throws Exception {
        app.getSharedPreferences(TelegramBackgroundSchedule.PREF,0).edit().clear().commit();
        assertFalse(TelegramBackgroundSchedule.enabled(app));
        androidx.work.PeriodicWorkRequest periodic=TelegramBackgroundSchedule.request();
        assertEquals(TimeUnit.MINUTES.toMillis(15),periodic.getWorkSpec().intervalDuration);
        assertEquals(TimeUnit.MINUTES.toMillis(15),periodic.getWorkSpec().initialDelay);
        assertEquals(androidx.work.NetworkType.CONNECTED,
            periodic.getWorkSpec().constraints.getRequiredNetworkType());
        assertTrue(periodic.getWorkSpec().constraints.requiresBatteryNotLow());
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app);
        app.getSharedPreferences("discovery",0).edit().putBoolean("periodic",false).commit();
        TelegramBackgroundSchedule.setEnabled(app,true);
        TelegramBackgroundSchedule.bootstrap(app);
        assertTrue(TelegramBackgroundSchedule.enabled(app));
        assertEquals(1,androidx.work.WorkManager.getInstance(app)
            .getWorkInfosForUniqueWork(TelegramBackgroundSchedule.WORK).get().size());
        assertFalse("Telegram background opt-in must not force on public web",
            app.getSharedPreferences("discovery",0).getBoolean("periodic",true));
        TelegramBackgroundSchedule.setEnabled(app,false);
        assertFalse(TelegramBackgroundSchedule.enabled(app));
        assertEquals(androidx.work.WorkInfo.State.CANCELLED,
            androidx.work.WorkManager.getInstance(app)
                .getWorkInfosForUniqueWork(TelegramBackgroundSchedule.WORK)
                .get().get(0).getState());
        assertFalse("Background Telegram must never run without opt-in",
            DiscoveryCoordinator.get(app).start(1,true));
    }

    @Test public void backgroundTelegramSearchesExactlyOnePublicTermAndDoesNotSendMessages()throws Exception {
        TdlibEngine engine=new TdlibEngine(app);
        ReflectionHelpers.setField(engine,"loggedIn",true);
        AtomicInteger searches=new AtomicInteger();
        AtomicInteger groupDetails=new AtomicInteger();
        TdlibEngine.Session session=(function,handler)->{
            if(function instanceof org.drinkless.tdlib.TdApi.SearchPublicChats) {
                searches.incrementAndGet();
                org.drinkless.tdlib.TdApi.Chats chats=new org.drinkless.tdlib.TdApi.Chats();
                chats.chatIds=new long[]{9001};
                handler.onResult(chats);
            } else if(function instanceof org.drinkless.tdlib.TdApi.GetChat) {
                groupDetails.incrementAndGet();
                org.drinkless.tdlib.TdApi.Chat chat=new org.drinkless.tdlib.TdApi.Chat();
                chat.id=9001;chat.title="گروه پیمانکاران برق صنعتی";
                chat.type=new org.drinkless.tdlib.TdApi.ChatTypeBasicGroup();
                handler.onResult(chat);
            } else fail("Background search must never send, join or read members: "+
                function.getClass().getSimpleName());
        };
        ReflectionHelpers.setField(engine,"client",session);
        DiscoveryCoordinator.Run pass=run();
        pass.scope=1;pass.background=true;pass.telegramBackground=true;
        ExecutorService worker=Executors.newSingleThreadExecutor();
        Future<?> job=worker.submit(()->DiscoveryCoordinator.execute(pass,
            Collections.singletonList(new TelegramPublicGroupProvider(engine))));
        try {
            long deadline=System.currentTimeMillis()+9000;
            while(!job.isDone()&&System.currentTimeMillis()<deadline) {
                shadowOf(Looper.getMainLooper()).idle();Thread.sleep(20);
            }
            job.get(1,TimeUnit.SECONDS);
            assertEquals("One background search must use just one query",1,searches.get());
            assertEquals(1,groupDetails.get());
            assertEquals(1,pass.groups);
            assertEquals("finished",pass.state);
            assertEquals(1,data.groups().size());
            assertEquals(1,data.get("background_telegram_topic_cycle").optInt("next"));
            assertFalse("Background discovery cannot request foreground login",pass.telegramPending);
        } finally {pass.cancelled.set(true);worker.shutdownNow();}
    }

    @Test public void backgroundTelegramSkipsRecentlyCompletedTopicAndAdvancesCursor()throws Exception {
        String topic=GroupKeywords.all(data).get(0);
        data.put("telegram:"+topic,new JSONObject().put("success",System.currentTimeMillis()));
        TdlibEngine engine=new TdlibEngine(app);
        ReflectionHelpers.setField(engine,"loggedIn",true);
        ReflectionHelpers.setField(engine,"client",(TdlibEngine.Session)(function,handler)->
            fail("Freshly checked topic must not trigger Telegram request"));
        DiscoveryCoordinator.Run pass=run();
        pass.scope=1;pass.background=true;pass.telegramBackground=true;
        DiscoveryCoordinator.execute(pass,Collections.singletonList(
            new TelegramPublicGroupProvider(engine)));
        assertEquals("finished",pass.state);
        assertEquals(0,pass.topic);
        assertEquals(0,pass.groups);
        assertEquals(1,data.get("background_telegram_topic_cycle").optInt("next"));
        assertFalse(pass.telegramPending);
    }

    @Test public void telegramProviderUsesOfficialSearchDeduplicatesPersistsAndCancels()throws Exception {
        TdlibEngine engine=new TdlibEngine(app);AtomicInteger searches=new AtomicInteger();
        ReflectionHelpers.setField(engine,"loggedIn",true);
        TdlibEngine.Session session=(function,handler)->{
            if(function instanceof org.drinkless.tdlib.TdApi.SearchPublicChats) {
                searches.incrementAndGet();org.drinkless.tdlib.TdApi.Chats chats=new org.drinkless.tdlib.TdApi.Chats();chats.chatIds=new long[]{9,9,10};handler.onResult(chats);
            }else if(function instanceof org.drinkless.tdlib.TdApi.GetChat) {
                org.drinkless.tdlib.TdApi.Chat chat=new org.drinkless.tdlib.TdApi.Chat();chat.id=((org.drinkless.tdlib.TdApi.GetChat)function).chatId;chat.title="گروه پیمانکار برق صنعتی";
                if(chat.id==9)chat.type=new org.drinkless.tdlib.TdApi.ChatTypeBasicGroup();
                else {org.drinkless.tdlib.TdApi.ChatTypeSupergroup type=new org.drinkless.tdlib.TdApi.ChatTypeSupergroup();type.isChannel=true;chat.type=type;}
                handler.onResult(chat);
            } else fail("Unexpected Telegram request: "+function.getClass().getSimpleName());
        };
        ReflectionHelpers.setField(engine,"client",session);
        DiscoveryCoordinator.Run r=run();r.changed=()->{data.put("run",r.json());if(r.duplicates>=1)r.cancelled.set(true);};
        ExecutorService worker=Executors.newSingleThreadExecutor();
        Future<?> job=worker.submit(()->DiscoveryCoordinator.execute(r,Collections.singletonList(new TelegramPublicGroupProvider(engine))));
        try {
            long until=System.currentTimeMillis()+8000;
            while(!job.isDone()&&System.currentTimeMillis()<until) {shadowOf(Looper.getMainLooper()).idle();Thread.sleep(20);}
            job.get(1,TimeUnit.SECONDS);
            assertEquals(2,searches.get());assertEquals(1,r.groups);assertEquals(1,r.duplicates);assertEquals("stopped",r.state);
            assertEquals(1,data.groups().size());assertEquals("9",data.groupExport().get(0)[1]);
            assertTrue(data.groupExport().get(0)[2].contains("عضویت تأیید نشده"));assertTrue(data.get("telegram:"+GroupKeywords.all(data).get(0)).optLong("success")>0);
        }finally {r.cancelled.set(true);worker.shutdownNow();}
    }
    @Test public void missingTelegramSessionStaysWaitingAndDoesNotFabricateCompletion() {
        DiscoveryCoordinator.Run r=run();DiscoveryCoordinator.execute(r,Collections.singletonList(new TelegramPublicGroupProvider(new TdlibEngine(app))));
        assertEquals("waiting",r.state);assertEquals(0,r.groups);assertTrue(r.note.contains("متصل"));
    }
    @Test public void zeroResultsAndMalformedSearxEndpointAreTruthfulAndDoNotCreateContacts()throws Exception {
        DiscoveryCoordinator.Run r=run();new PublicWebsiteContactsProvider((url,hosts)->page(200,url.endsWith("robots.txt")?"":"<title>وبلاگ شخصی</title><p>کد: 09121234567</p>",url.endsWith("robots.txt")?"text/plain":"text/html")).discover(r);
        assertEquals(1,r.pages);assertEquals(0,r.saved);assertEquals(0,store.count("records"));
        try(PublicHttp http=new PublicHttp(new AtomicBoolean())) {
            try {new SearXngSeedProvider("http://localhost:8888/search",http).discover(run());fail();}catch(PublicHttp.AccessDenied expected){}
        }
    }
    @Test public void homeKeepsScopeProgressAndExactlyFiveTabsAcrossRecreation()throws Exception {
        data.put("run",new JSONObject().put("state","running").put("scope",2).put("pages",3).put("saved",2).put("groups",4));
        // This checks restore of a saved foreground run, not automatic fresh-install discovery.
        app.getSharedPreferences("discovery",0).edit().putInt("scope",1)
            .putBoolean("sales_auto_enabled",false).commit();
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();
            // The sales dashboard's exact presentation text is covered by
            // TelegramConnectSalesDashboardTest. This lifecycle regression
            // focuses on real navigation and durable discovery state instead.
            assertEquals(0,((Integer)ReflectionHelpers.getField(activity,"section")).intValue());
            for(String tab:new String[]{"خانه","جستجو","مخاطبان","صف پیام","تنظیمات"})
                assertNotNull("Missing navigation tab: "+tab,
                    OfflineExcelTest.find(activity.getWindow().getDecorView(),tab));
            controller.recreate();
            assertEquals(0,((Integer)ReflectionHelpers.getField(activity,"section")).intValue());
            assertEquals(3,DiscoveryCoordinator.get(app).snapshot().optInt("pages"));
            assertEquals("interrupted",DiscoveryCoordinator.get(app).snapshot().optString("state"));
            assertEquals(1,app.getSharedPreferences("discovery",0).getInt("scope",2));
        }
    }

    @Test public void newPublicContactsAndSavedGroupsExportAsRealXmlWithAllProvenance()throws Exception {
        new PublicWebsiteContactsProvider((url,hosts)->page(200,url.endsWith("robots.txt")?"":html("۰۲۱۵۶۵۱۹۹۳۱"),url.endsWith("robots.txt")?"text/plain":"text/html")).discover(run());
        data.saveGroup(new TdlibEngine.ChatOverview(-1001234567890123L,"=گروه کابل & برق\u0001","گروه عمومی",false),"سینی کابل");
        List<String[]> rows=new ArrayList<>();rows.add(new String[]{"نوع","نام","شماره / شناسه","منبع"});
        for(MainActivity.Record r:store.find("",0,100))rows.add(new String[]{"مخاطب",r.title,r.phone,r.source});
        for(String[] g:data.groupExport())rows.add(new String[]{"گروه",g[0],g[1],g[3]});
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();ExcelWorkbook.write(bytes,"کشف عمومی",rows);
        javax.xml.parsers.DocumentBuilderFactory parser=javax.xml.parsers.DocumentBuilderFactory.newInstance();parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        try(java.util.zip.ZipInputStream zip=new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            java.util.zip.ZipEntry entry;int xml=0;while((entry=zip.getNextEntry())!=null) {
                byte[] contents=zip.readAllBytes();parser.newDocumentBuilder().parse(new ByteArrayInputStream(contents));xml++;
                if(entry.getName().endsWith("sheet1.xml")){String sheet=new String(contents,"UTF-8");assertTrue(sheet.contains("02156519931"));assertTrue(sheet.contains("-1001234567890123"));assertTrue(sheet.contains("rightToLeft=\"1\""));assertFalse(sheet.contains("<f>"));}
            }assertEquals(6,xml);
        }
        java.io.File dir=new java.io.File("build/verification");assertTrue(dir.isDirectory()||dir.mkdirs());
        try(OutputStream file=new FileOutputStream(new java.io.File(dir,"discovery.xlsx"))){file.write(bytes.toByteArray());}
    }
    @Test public void searxRequiresActualJsonAndPublicSeedUrls()throws Exception {
        DiscoveryCoordinator.Run r=run();
        new SearXngSeedProvider("https://search.example/search",url->{assertTrue(url.contains("format=json"));return page(200,"{\"results\":[{\"url\":\"https://new.example/contact\"},{\"url\":\"http://127.0.0.1/private\"}]}","application/json");}).discover(r);
        assertEquals(2,r.seeds.size());assertTrue(r.seeds.contains("https://new.example/contact"));
        try{new SearXngSeedProvider("https://search.example/search",url->page(200,"<html>blocked</html>","text/html")).discover(r);fail();}catch(IllegalStateException expected){}
    }

    @Test public void encodedDotSegmentsCannotBypassRobotsAndDoubleEncodingIsDenied()throws Exception {
        assertEquals("https://cable.example/private",PublicHttp.validate("https://cable.example/public/%2e%2e/private",null));
        assertDenied("https://cable.example/%252e%252e/private",null);
        assertDenied("https://cable.example/public%5cprivate",null);
        RobotsRules rules=new RobotsRules("User-agent: *\nDisallow: /%D8%AE%D8%B5%D9%88%D8%B5%DB%8C");
        assertFalse(rules.allows("/خصوصی"));
    }

}
