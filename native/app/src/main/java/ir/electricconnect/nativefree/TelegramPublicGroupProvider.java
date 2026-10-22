package ir.electricconnect.nativefree;

import android.os.Handler;
import android.os.Looper;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Searches only in a foreground READY session; never joins or reads participants. */
final class TelegramPublicGroupProvider implements DiscoveryProvider {
    private final TdlibEngine engine;
    TelegramPublicGroupProvider(TdlibEngine engine){this.engine=engine;}
    @Override public String name(){return "گروه‌های عمومی تلگرام";}
    @Override public void discover(DiscoveryCoordinator.Run run)throws Exception {
        if(!engine.isReady()) {run.telegramPending=true;run.note="برای گروه‌ها حساب تلگرام را متصل کنید، سپس شروع را بزنید.";return;}
        // The queue mode rotates through one industry keyword each quiet interval.
        // Normal explicit discovery still runs the complete list with daily checkpoints.
        int first=run.queuePass
            ? Math.floorMod(run.data.get("queue_topic_cycle").optInt("next"),run.topics.size())
            : 0;
        int end=run.queuePass?first+1:run.topics.size();
        boolean incomplete=false;
        for(int i=first;i<end && run.active();i++) {
            String topic=run.topics.get(i);JSONObject checkpoint=run.data.get("telegram:"+topic);
            // Successful topics are not replayed in the same day; interrupted/limited ones resume.
            if(!run.queuePass && checkpoint.optLong("success")>System.currentTimeMillis()-TimeUnit.HOURS.toMillis(24))continue;
            if(checkpoint.optInt("attempts")>=3&&checkpoint.optLong("attempted")>System.currentTimeMillis()-TimeUnit.HOURS.toMillis(1)) {
                incomplete=true;
                continue;
            }
            run.current=name()+": "+topic;run.topic=i+1;run.total=run.topics.size();run.emit();
            java.util.concurrent.atomic.AtomicBoolean queryActive=new java.util.concurrent.atomic.AtomicBoolean(true);
            CountDownLatch done=new CountDownLatch(1);AtomicReference<List<TdlibEngine.ChatOverview>> result=new AtomicReference<>();AtomicReference<String> error=new AtomicReference<>();
            new Handler(Looper.getMainLooper()).post(()->{
                if(!run.active()){done.countDown();return;}
                engine.searchPublicGroups(topic,(rows,detail)->{result.set(rows);error.set(detail);done.countDown();},()->run.active()&&queryActive.get());
            });
            long until=System.currentTimeMillis()+20000;
            while(run.active()&&System.currentTimeMillis()<until&&!done.await(100,TimeUnit.MILLISECONDS)) { /* cancellable wait */ }
            queryActive.set(false);
            if(!run.active())return;
            if(result.get()!=null && error.get()==null) {
                for(TdlibEngine.ChatOverview group:result.get()) {
                    if(!run.active())return;
                    if(GroupRelevance.accepts(group.name) && run.data.saveGroup(group,topic))run.groups++;
                    else if(GroupRelevance.accepts(group.name))run.duplicates++;
                }
                checkpoint.put("success",System.currentTimeMillis()).put("attempts",0);
            } else {
                incomplete=true;
                run.error("جست‌وجوی عمومی تلگرام قطع یا محدود شد؛ بعداً دوباره تلاش کنید.");
                checkpoint.put("attempts",checkpoint.optInt("attempts")+1);
            }
            checkpoint.put("attempted",System.currentTimeMillis());run.data.put("telegram:"+topic,checkpoint);
            if(run.queuePass)run.data.put("queue_topic_cycle",
                new JSONObject().put("next",(i+1)%run.topics.size()));
            run.emit();run.pause(1500);
            if(!engine.isReady()){run.telegramPending=true;return;}
        }
        // Only a fully completed manual/deferred full sweep may acknowledge
        // telegram_pending. Rate limits, cancellation, expiration or logout
        // leave the deferred request intact for a later authorized READY run.
        if(!run.queuePass && run.active()) {
            if(incomplete) {
                run.telegramPending=true;
                run.note="بعضی موضوعات تلگرام محدود شدند؛ جست‌وجو بعداً ادامه می‌یابد.";
            } else run.telegramCompleted=true;
        }
    }
}
