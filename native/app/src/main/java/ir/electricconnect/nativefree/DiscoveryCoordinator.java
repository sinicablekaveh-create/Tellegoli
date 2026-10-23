package ir.electricconnect.nativefree;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.InterruptedIOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-owned foreground job. SQLite checkpoints survive activities and process death. */
final class DiscoveryCoordinator {
    static final String[] TOPICS={"پیمانکار برق صنعتی","پیمانکار تاسیسات الکتریکی","مجری کابل کشی","برقکار پروژه صنعتی","پیمانکار برق ساختمان","مجری برق سوله","شرکت EPC صنعتی","پیمانکار برق کارخانه","مجری تاسیسات تجاری","پیمانکار برق پروژه"};
    interface Listener {void changed(JSONObject state);}
    private static DiscoveryCoordinator shared;
    static synchronized DiscoveryCoordinator get(Context context) {
        if(shared==null)shared=new DiscoveryCoordinator(context.getApplicationContext());return shared;
    }
    final Context app;
    final DiscoveryData data;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Set<Listener> listeners=new HashSet<>();
    private volatile Run running;
    DiscoveryCoordinator(Context app) {
        this.app=app;data=new DiscoveryData(new MainActivity.Store(app));
    }
    synchronized void attach(Listener listener){listeners.add(listener);listener.changed(snapshot());}
    synchronized void detach(Listener listener){listeners.remove(listener);}
    JSONObject snapshot(){
        JSONObject saved=data.get("run");
        if(running==null&&"running".equals(saved.optString("state"))) {
            try {saved.put("state","interrupted").put("note","اجرا قطع شد؛ برای ادامه شروع را بزنید. نتایج ذخیره شده‌اند.");}catch(Exception ignored){}
        }
        return saved;
    }
    boolean isRunning(){return running!=null;}
    synchronized boolean start(int scope,boolean background) {
        if(running!=null)return false;
        if(background && scope==1 && !TelegramBackgroundSchedule.enabled(app))return false;
        Run run=new Run(data,new AtomicBoolean(),null);
        run.topics=LeadKeywords.all(data);
        run.scope=scope;run.background=background;
        run.queuePass=scope==3;
        run.telegramBackground=background && scope==1;
        JSONObject previous=snapshot();
        if(!run.telegramBackground && previous.optInt("scope",-1)==scope && (previous.optString("state").equals("interrupted")||previous.optString("state").equals("stopped")||previous.optString("state").equals("waiting"))) {
            run.pages=previous.optInt("pages");run.saved=previous.optInt("saved");run.groups=previous.optInt("groups");run.duplicates=previous.optInt("duplicates");run.errors=previous.optInt("errors");
        }
        running=run;
        // Record the request before a web provider can consume the time budget.
        // READY resumption must also survive web timeouts and process interruption.
        if((scope==1||scope==2) && !run.telegramBackground)
            app.getSharedPreferences("discovery",Context.MODE_PRIVATE)
            .edit().putBoolean("telegram_pending",true).apply();
        run.changed=()->publish(run);
        executor.execute(()->{
            try(PublicHttp http=new PublicHttp(run.cancelled)) {
                run.http=http;run.scope=scope;run.background=background;
                // Source rotation favors NEW pending websites. Previously checked
                // business pages cool down for 24h even while a message queue is
                // active; an 8-minute queue gap is for new research, not hammering sites.
                data.renewOldPages(TimeUnit.HOURS.toMillis(24));
                List<DiscoveryProvider> providers=new ArrayList<>();
                android.content.SharedPreferences settings=app.getSharedPreferences("discovery",Context.MODE_PRIVATE);
                run.maxPages=Math.max(1,Math.min(6,settings.getInt("pages_per_host",4)));
                run.maxHosts=Math.max(1,Math.min(8,settings.getInt("hosts_per_run",4)));
                // Scope 3 is the approved-queue foreground group-only pass.
                run.queuePass=scope==3;
                if(scope==0 || scope==2) {
                    // User-supplied public buyer sources take priority. With default
                    // four hosts per run, rotate the bundled contractor site order.
                    for(String url:settings.getString("seeds","").split("\\s+"))
                        if(!url.trim().isEmpty())run.seeds.add(url.trim());
                    int seedCount=PublicWebsiteContactsProvider.DEFAULT_SEEDS.size();
                    int cursor=Math.floorMod(settings.getInt("buyer_seed_cursor",0),seedCount);
                    run.buyerSeedCursor=cursor;
                    int importedCount=(int)data.importedSourceCount();
                    int sourceCursor=importedCount==0?0:Math.floorMod(
                        settings.getInt("imported_source_cursor",0),importedCount);
                    run.importedSourceCursor=sourceCursor;
                    settings.edit()
                        .putInt("buyer_seed_cursor",(cursor+run.maxHosts)%seedCount)
                        .putInt("imported_source_cursor",importedCount==0?0:
                            (sourceCursor+importedCursorStep(run.maxHosts))%importedCount).apply();
                    if(app.getSharedPreferences("exa_ui_settings",Context.MODE_PRIVATE).getBoolean("online_enabled",false))
                        providers.add(new ExaProvider(app));
                    String endpoint=settings.getString("searxng","");
                    if(!endpoint.isEmpty())providers.add(new SearXngSeedProvider(endpoint,http));
                    providers.add(new PublicWebsiteContactsProvider(http::get));
                }
                if(scope!=0) {
                    if(run.telegramBackground) {
                        // Only after explicit opt-in + saved READY TDLib session.
                        // One public group keyword per scheduled pass.
                        providers.add(new TelegramPublicGroupProvider(TdlibEngine.get(app)));
                    } else if(background) {
                        settings.edit().putBoolean("telegram_pending",true).apply();
                        run.note="وب دوره‌ای اجرا شد؛ بخش تلگرام در ورود بعدی به حساب آماده اجرا می‌شود.";
                    } else providers.add(new TelegramPublicGroupProvider(TdlibEngine.get(app)));
                }
                execute(run,providers);
                // A READY-triggered, visible follow-up must survive a web pass
                // that finished before Telegram authorization was available.
                if(run.telegramPending && !run.cancelled.get() && !run.background && scope!=0)
                    settings.edit().putBoolean("telegram_pending",true).apply();
            } catch(Exception e) {run.errors++;run.note="اجرا قطع شد؛ اتصال و منابع را بررسی کنید.";run.state="interrupted";}
            finally {
                if(run.cancelled.get())run.state="stopped";
                finishDeferredFlag(app,run);
                run.emit();running=null;
                main.post(()->{synchronized(this){for(Listener l:new ArrayList<>(listeners))l.changed(snapshot());}});
            }
        });
        return true;
    }
    /** Match source rotation stride to reserved Excel host slots.
     * Advancing by the total host cap would permanently skip every other
     * imported source when the Excel list size is divisible by that cap. */
    static int importedCursorStep(int hostLimit) {
        return Math.max(1,hostLimit/2);
    }
    // Shared by deterministic tests; a failing optional provider cannot abort independent ones.
    static void execute(Run run,List<DiscoveryProvider> providers) {
        for(DiscoveryProvider provider:providers) {
            if(!run.active())break;
            run.current=provider.name();run.topic=0;run.total=0;run.emit();
            try {
                // Group discovery and web buyer discovery use separate keyword lists.
                run.topics=provider instanceof TelegramPublicGroupProvider
                    ?GroupKeywords.all(run.data):LeadKeywords.all(run.data);
                provider.discover(run);
            }
            catch(InterruptedIOException e) {run.interrupted=true;break;}
            catch(Exception e) {run.error(provider instanceof ExaProvider && e.getMessage()!=null?e.getMessage():"منبع اختیاری در دسترس نیست؛ منابع مستقل ادامه دارند.");}
        }
        run.state=run.cancelled.get()?"stopped":run.interrupted||System.currentTimeMillis()>=run.deadline?"interrupted":run.telegramPending?"waiting":"finished";
        if(run.note.isEmpty())run.note=run.state.equals("finished")?"بررسی محدود منابع تمام شد؛ شماره‌ها از نظر قالب و منبع بررسی شدند، فعال‌بودن خط تأیید نشده است.":"نتایج ذخیره شده‌اند؛ ادامه با دکمهٔ شروع.";
        run.emit();
    }
    /** A READY event starting a scan is not acknowledgement of its completion. */
    static void finishDeferredFlag(Context context,Run run) {
        if(run.telegramCompleted && !run.background && !run.cancelled.get() && "finished".equals(run.state))
            context.getSharedPreferences("discovery",Context.MODE_PRIVATE).edit()
                .putBoolean("telegram_pending",false).putLong("telegram_retry_after",0).apply();
    }
    synchronized void stop(){Run run=running;if(run!=null) {run.cancelled.set(true);if(run.http!=null)run.http.cancel();}}
    synchronized void stopByUser(){
        app.getSharedPreferences("discovery",Context.MODE_PRIVATE).edit()
            .putBoolean("telegram_pending",false).putLong("sales_auto_started",System.currentTimeMillis()).apply();
        stop();
        if(running==null){
            JSONObject saved=snapshot();
            try{saved.put("state","stopped").put("note","جست‌وجو و انتظار برای تلگرام متوقف شد؛ نتایج قبلی محفوظ است.");}catch(Exception ignored){}
            data.put("run",saved);
        }
    }
    void stopForeground(){Run run=running;if(run!=null&&!run.background)stop();}
    void stopQueuePass(){Run run=running;if(run!=null&&run.queuePass)stop();}
    void stopBackgroundTelegram(){Run run=running;if(run!=null&&run.telegramBackground)stop();}
    private void publish(Run run) {
        JSONObject value=run.json();data.put("run",value);
        main.post(()->{synchronized(this){for(Listener l:new ArrayList<>(listeners))l.changed(value);}});
    }
    static String describe(JSONObject s) {
        String state=s.optString("state","");
        String label=state.equals("running")?"در حال بررسی":state.equals("stopped")?"متوقف":state.equals("waiting")?"منتظر حساب تلگرام":state.equals("interrupted")?"قابل ادامه":state.equals("finished")?"پایان بررسی منابع":"آمادهٔ کشف بدون کلید پولی";
        return label+"\nمنبع فعلی: "+s.optString("current","—")+
            " | موضوع "+s.optInt("topic")+" / "+s.optInt("total")+
            "\nصفحات بررسی‌شده: "+s.optInt("pages")+" | گروه‌های جدید: "+s.optInt("groups")+
            "\nشماره‌های عمومی ذخیره‌شدهٔ جدید: "+s.optInt("saved")+" | تکراری: "+s.optInt("duplicates")+
            "\nخطا / محدودیت دسترسی: "+s.optInt("errors")+
            (s.optString("note").isEmpty()?"":"\n"+s.optString("note"));
    }
    static final class Run {
        final DiscoveryData data;final AtomicBoolean cancelled;
        final long deadline=System.currentTimeMillis()+TimeUnit.MINUTES.toMillis(6);
        final List<String> seeds=new ArrayList<>();
        List<String> topics=Arrays.asList(TOPICS);
        PublicHttp http;Runnable changed;
        int pages,saved,groups,duplicates,errors,topic,total,scope,maxPages=4,maxHosts=4,buyerSeedCursor,importedSourceCursor;
        boolean background,telegramPending,queuePass,telegramBackground,telegramCompleted,interrupted;
        String state="running",current="",note="";
        Run(DiscoveryData data,AtomicBoolean cancelled,Runnable changed){this.data=data;this.cancelled=cancelled;this.changed=changed;}
        boolean active(){return !cancelled.get()&&!Thread.currentThread().isInterrupted()&&System.currentTimeMillis()<deadline;}
        void pause(long millis) throws InterruptedIOException {
            long until=System.currentTimeMillis()+millis;
            while(System.currentTimeMillis()<until) {if(!active())throw new InterruptedIOException();try{Thread.sleep(Math.min(100,Math.max(1,until-System.currentTimeMillis())));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}}
            if(!active())throw new InterruptedIOException();
        }
        synchronized void error(String message){errors++;note=message;emit();}
        String webTopic(String provider) {
            int index=Math.floorMod(data.get(provider+"_topic_cycle").optInt("next"),topics.size());
            return topics.get(index);
        }
        void advanceWebTopic(String provider)throws Exception {
            int index=Math.floorMod(data.get(provider+"_topic_cycle").optInt("next"),topics.size());
            data.put(provider+"_topic_cycle",new JSONObject().put("next",(index+1)%topics.size()));
        }
        synchronized JSONObject json(){JSONObject o=new JSONObject();try {o.put("scope",scope).put("state",state).put("current",current).put("topic",topic).put("total",total).put("pages",pages).put("saved",saved).put("groups",groups).put("duplicates",duplicates).put("errors",errors).put("note",note).put("updated",System.currentTimeMillis());}catch(Exception ignored){}return o;}
        synchronized void emit(){if(changed!=null)changed.run();else data.put("run",json());}
    }
}
