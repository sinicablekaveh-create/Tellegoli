package ir.electricconnect.nativefree;

import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Offline, user-requested audit export. Telegram send confirmation is not a read receipt. */
final class QueueDeliveryReport {
    private QueueDeliveryReport() {}

    static List<String[]> rows(List<JSONObject> entries) {
        List<String[]> result=new ArrayList<>();
        result.add(new String[]{
            "شناسهٔ صف", "گروه انتخاب‌شده", "شناسهٔ گروه", "متن پیام",
            "کد وضعیت", "وضعیت فارسی", "شرح نتیجه", "زمان ثبت (محلی)",
            "زمان تلاش (محلی)", "شناسهٔ موقت تلگرام"
        });
        for(JSONObject item:entries) {
            String status=item.optString("status");
            result.add(new String[]{
                item.optString("id"), item.optString("chat_name"),
                item.optString("chat_id"), item.optString("text"),
                status, label(status), item.optString("detail"),
                time(item.optLong("created")), time(item.optLong("attempt_at")),
                item.has("pending_id")?item.optString("pending_id"):""
            });
        }
        return result;
    }

    static String label(String status) {
        switch(status) {
            case "PENDING":return "منتظر ارسال";
            case "SENDING":return "در حال ارسال";
            case "SUBMITTED":return "درخواست پذیرفته شد؛ تأیید تلگرام نامشخص";
            case "DELIVERED":return "ارسال توسط تلگرام تأیید شد؛ خواندن پیام تأیید نشده";
            case "FAILED":return "ارسال ناموفق";
            case "UNKNOWN":return "نامشخص؛ ارسال خودکار دوباره انجام نمی‌شود";
            default:return "وضعیت ناشناخته";
        }
    }

    static String time(long millis) {
        if(millis<=0)return "";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.ROOT).format(new Date(millis));
    }
}
