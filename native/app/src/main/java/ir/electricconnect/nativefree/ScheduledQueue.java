package ir.electricconnect.nativefree;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.WorkManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Finite, user-approved queue. Each item is an explicitly reviewed message
 * addressed to one chosen group; no member scraping or repeating campaigns.
 * WorkManager is inexact under Doze; 8 minutes is a minimum, never a promise.
 */
final class ScheduledQueue {
    static final String UNIQUE_WORK = "electric-connect-approved-queue-v095";
    static final long MIN_GAP = 8L * 60 * 1000;
    static final int MAX_PENDING = 20;
    private static final Object LOCK = new Object();
    private static final String PREF = "approved_scheduled_queue_v095";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_LAST = "last_attempt";
    private static final String KEY_EARLY = "early_delivery_receipts";

    private ScheduledQueue() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static JSONArray read(SharedPreferences prefs) {
        try { return new JSONArray(prefs.getString(KEY_ITEMS, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private static boolean write(SharedPreferences prefs, JSONArray list) {
        return prefs.edit().putString(KEY_ITEMS, list.toString()).commit();
    }

    static List<JSONObject> snapshot(Context context) {
        synchronized (LOCK) {
            JSONArray items = read(prefs(context));
            ArrayList<JSONObject> copy = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) {
                    try { copy.add(new JSONObject(item.toString())); }
                    catch (Exception ignored) {}
                }
            }
            return copy;
        }
    }

    static boolean enabled(Context context) { return prefs(context).getBoolean(KEY_ENABLED, false); }

    private static JSONArray earlyReceipts(SharedPreferences p) {
        try {return new JSONArray(p.getString(KEY_EARLY,"[]"));}
        catch(Exception ignored) {return new JSONArray();}
    }

    /** Called only after explicit consent and group selection in the Activity. */
    static String add(Context context, long chatId, String chatName, String body) {
        if (chatId == 0 || chatName == null || chatName.trim().isEmpty()
                || body == null || body.trim().isEmpty() || body.length() > 3000) {
            return "گروه یا متن پیام معتبر نیست.";
        }
        synchronized (LOCK) {
            SharedPreferences p = prefs(context);
            JSONArray items = read(p);
            int pending = 0;
            for (int i = 0; i < items.length(); i++) {
                JSONObject v = items.optJSONObject(i);
                if (v == null) continue;
                if ("PENDING".equals(v.optString("status"))) {
                    pending++;
                    if (v.optLong("chat_id") == chatId && body.trim().equals(v.optString("text"))) {
                        return "این متن برای همین گروه از قبل در صف قرار دارد.";
                    }
                }
            }
            if (pending >= MAX_PENDING) return "حداکثر ۲۰ پیام تأییدشده می‌توانند منتظر باشند.";
            JSONObject item = new JSONObject();
            try {
                item.put("id", UUID.randomUUID().toString());
                item.put("chat_id", chatId);
                item.put("chat_name", chatName.trim());
                item.put("text", body.trim());
                item.put("status", "PENDING");
                item.put("detail", "منتظر شروع یا نوبت ارسال");
                item.put("created", System.currentTimeMillis());
                items.put(item);
            } catch (Exception e) { return "خطا در آماده‌سازی پیام."; }
            return write(p, items) ? null : "ثبت صف در حافظهٔ دستگاه ناموفق بود.";
        }
    }

    private static JSONObject nextPending(Context context) {
        synchronized (LOCK) {
            JSONArray items = read(prefs(context));
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null && "PENDING".equals(item.optString("status"))) {
                    try { return new JSONObject(item.toString()); }
                    catch (Exception ignored) { return null; }
                }
            }
            return null;
        }
    }

    static boolean start(Context context) {
        synchronized (LOCK) {
            if (nextPending(context) == null) return false;
            if (!prefs(context).edit().putBoolean(KEY_ENABLED, true).commit()) return false;
        }
        schedule(context, ExistingWorkPolicy.REPLACE);
        QueueGapDiscovery.scheduleWeb(context);
        return true;
    }

    static void stop(Context context) {
        prefs(context).edit().putBoolean(KEY_ENABLED, false).commit();
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK);
        QueueGapDiscovery.cancel(context);
    }

    static void pause(Context context, String reason) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(context);
            p.edit().putBoolean(KEY_ENABLED, false).commit();
            JSONArray list = read(p);
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item != null && "PENDING".equals(item.optString("status"))) {
                    try { item.put("detail", reason); } catch (Exception ignored) {}
                }
            }
            write(p, list);
        }
        QueueGapDiscovery.cancel(context);
    }

    static boolean removePending(Context context, String id) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(context);
            JSONArray list = read(p), updated = new JSONArray();
            boolean found = false;
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                if (id.equals(item.optString("id")) && "PENDING".equals(item.optString("status"))) {
                    found = true;
                } else updated.put(item);
            }
            return found && write(p, updated);
        }
    }

    private static boolean change(Context context, String id, String expected, String status,
                                  String detail, long messageId, boolean attempt) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(context);
            JSONArray list = read(p);
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null || !id.equals(item.optString("id"))
                        || !expected.equals(item.optString("status"))) continue;
                JSONArray remaining=null;
                try {
                    item.put("status", status);
                    item.put("detail", detail);
                    if (attempt) item.put("attempt_at", System.currentTimeMillis());
                    if (messageId != 0L) item.put("pending_id", messageId);
                    // A TDLib send-result can race ahead of the send request callback.
                    // Compare both the group and the temporary message ID before applying.
                    if ("SUBMITTED".equals(status) && messageId!=0L) {
                        remaining=new JSONArray();
                        JSONArray previous=earlyReceipts(p);
                        long now=System.currentTimeMillis();
                        for(int j=0;j<previous.length();j++) {
                            JSONObject receipt=previous.optJSONObject(j);
                            if(receipt==null || now-receipt.optLong("at")>TimeUnit.MINUTES.toMillis(10))continue;
                            if(receipt.optLong("chat_id")==item.optLong("chat_id")
                                    && receipt.optLong("old_id")==messageId) {
                                boolean success=receipt.optBoolean("ok");
                                item.put("status",success?"DELIVERED":"FAILED");
                                item.put("detail",success?"تلگرام ارسال را تأیید کرد":"تلگرام ارسال را رد کرد");
                            } else remaining.put(receipt);
                        }
                    }
                } catch (Exception ignored) { return false; }
                SharedPreferences.Editor update=p.edit().putString(KEY_ITEMS,list.toString());
                if (remaining!=null) update.putString(KEY_EARLY,remaining.toString());
                if (attempt) update.putLong(KEY_LAST,System.currentTimeMillis());
                return update.commit();
            }
            return false;
        }
    }

    static void recordDelivery(Context context, long chatId, long oldId, boolean ok) {
        if(oldId==0L)return;
        synchronized (LOCK) {
            SharedPreferences p = prefs(context);
            JSONArray list = read(p);
            boolean changed = false, sendingToChat=false;
            long now=System.currentTimeMillis();
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if(item==null || item.optLong("chat_id")!=chatId)continue;
                String current=item.optString("status");
                if ("SENDING".equals(current) && item.optLong("pending_id")==0
                        && now-item.optLong("attempt_at")<TimeUnit.MINUTES.toMillis(2))
                    sendingToChat=true;
                if(item.optLong("pending_id")!=oldId
                        || !("SUBMITTED".equals(current)||"SENDING".equals(current)||"UNKNOWN".equals(current)))
                    continue;
                try {
                    item.put("status", ok ? "DELIVERED" : "FAILED");
                    item.put("detail", ok ? "تلگرام ارسال را تأیید کرد" : "تلگرام ارسال را رد کرد");
                    changed = true;
                } catch (Exception ignored) {}
            }
            if(changed)write(p,list);
            else if(sendingToChat) {
                // Store only bounded ID metadata while an approved attempt is in flight.
                // Unrelated messages are never attributed to the queue without an ID match.
                JSONArray previous=earlyReceipts(p), bounded=new JSONArray();
                for(int i=Math.max(0,previous.length()-19);i<previous.length();i++) {
                    JSONObject entry=previous.optJSONObject(i);
                    if(entry!=null && now-entry.optLong("at")<TimeUnit.MINUTES.toMillis(10))
                        bounded.put(entry);
                }
                try {
                    bounded.put(new JSONObject().put("chat_id",chatId).put("old_id",oldId)
                        .put("ok",ok).put("at",now));
                    p.edit().putString(KEY_EARLY,bounded.toString()).commit();
                } catch(Exception ignored){}
            }
        }
    }

    private static void recover(Context context) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(context);
            JSONArray list = read(p);
            boolean changed = false;
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item != null && "SENDING".equals(item.optString("status"))) {
                    try {
                        item.put("status", "UNKNOWN");
                        item.put("detail", "اجرای قبلی قطع شد؛ برای جلوگیری از تکرار خودکار ارسال نمی‌شود");
                        changed = true;
                    } catch (Exception ignored) {}
                }
            }
            if (changed) write(p, list);
        }
    }

    static void schedule(Context context, ExistingWorkPolicy policy) {
        if (!enabled(context)) return;
        if (nextPending(context) == null) {
            prefs(context).edit().putBoolean(KEY_ENABLED, false).commit();
            return;
        }
        long last = prefs(context).getLong(KEY_LAST, 0);
        long delay = Math.max(0L, last + MIN_GAP - System.currentTimeMillis());
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SendWorker.class)
                .setConstraints(constraints).setInitialDelay(delay, TimeUnit.MILLISECONDS).build();
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, policy, request);
    }

    public static final class SendWorker extends Worker {
        public SendWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
            super(context, parameters);
        }

        @NonNull @Override public Result doWork() {
            Context context = getApplicationContext();
            recover(context);
            if (!enabled(context) || isStopped()) return Result.success();
            JSONObject item = nextPending(context);
            if (item == null) {
                pause(context, "صف به پایان رسید.");
                return Result.success();
            }
            // Recheck on execution: WorkManager may run earlier/later than requested.
            long previous = prefs(context).getLong(KEY_LAST, 0);
            if (System.currentTimeMillis() - previous < MIN_GAP) {
                schedule(context, ExistingWorkPolicy.APPEND_OR_REPLACE);
                return Result.success();
            }
            TdlibEngine engine = TdlibEngine.get(context);
            engine.connect();
            try {
                if (!engine.awaitReadyForQueue(55000L)) {
                    pause(context, "حساب TDLib آماده نیست؛ برنامه را باز کنید و دوباره شروع کنید");
                    return Result.success();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pause(context, "اجرای پس‌زمینه قطع شد؛ نیازمند شروع دوباره");
                return Result.success();
            }
            if (isStopped() || !enabled(context)) return Result.success();
            String id = item.optString("id");
            long chatId = item.optLong("chat_id");
            String message = item.optString("text");
            String reserved = engine.reserveScheduledAttempt();
            if (reserved != null) {
                if (reserved.startsWith("WAIT:")) {
                    long remaining;
                    try { remaining = Long.parseLong(reserved.substring(5)); }
                    catch (Exception ignored) { remaining = MIN_GAP; }
                    prefs(context).edit().putLong(KEY_LAST, System.currentTimeMillis() - MIN_GAP
                            + Math.max(1000L, remaining)).commit();
                    schedule(context, ExistingWorkPolicy.APPEND_OR_REPLACE);
                } else pause(context, reserved);
                return Result.success();
            }
            if (!change(context,id,"PENDING","SENDING","در حال تحویل درخواست به TDLib",0,true)) {
                pause(context,"صف در زمان ارسال تغییر کرده است؛ دوباره بررسی کنید");
                return Result.success();
            }
            if (isStopped() || !enabled(context)) {
                change(context,id,"SENDING","UNKNOWN","ارسال لغو شد؛ وضعیت دریافت نامعلوم است",0,false);
                return Result.success();
            }
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean accepted = new AtomicBoolean(false);
            AtomicLong temporaryMessageId = new AtomicLong();
            AtomicReference<String> detail = new AtomicReference<>("پاسخی از TDLib دریافت نشد");
            AtomicBoolean attemptActive=new AtomicBoolean(true);
            long attemptDeadline=android.os.SystemClock.elapsedRealtime()+55000L;
            engine.sendQueuedOnce(chatId,message,(ok,pendingId,text)->{
                accepted.set(ok);
                temporaryMessageId.set(pendingId);
                detail.set(text);
                latch.countDown();
            },()->attemptActive.get()&&!isStopped()&&android.os.SystemClock.elapsedRealtime()<attemptDeadline);
            try {
                if (latch.await(55,TimeUnit.SECONDS)) {
                    change(context,id,"SENDING",accepted.get()?"SUBMITTED":"FAILED",
                            detail.get(),temporaryMessageId.get(),false);
                } else {
                    change(context,id,"SENDING","UNKNOWN",
                            "مهلت دریافت پاسخ تمام شد؛ ارسال خودکار تکرار نمی‌شود",0,false);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                change(context,id,"SENDING","UNKNOWN",
                        "درخواست قطع شد؛ وضعیت ارسال نیازمند بررسی است",0,false);
            } finally {attemptActive.set(false);}
            if (!isStopped() && enabled(context)) {
                schedule(context, ExistingWorkPolicy.APPEND_OR_REPLACE);
                QueueGapDiscovery.scheduleWeb(context);
            }
            return Result.success();
        }
    }
}
