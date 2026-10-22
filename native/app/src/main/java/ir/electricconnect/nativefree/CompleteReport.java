package ir.electricconnect.nativefree;

import android.content.Context;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Offline snapshot of currently stored, user-accessible records. Nothing is sent online. */
final class CompleteReport {
    private CompleteReport(){}

    static List<String[]> rows(MainActivity.Store store,Context context) {
        List<String[]> out=new ArrayList<>();
        out.add(new String[]{"نوع داده","نام کسب‌وکار یا گروه","شماره تماس یا شناسه",
            "دسته‌بندی یا وضعیت","منبع یا لینک","متن پیام","وضعیت ارسال",
            "زمان / آخرین مشاهده","شناسه صف","جزئیات"});
        int offset=0;
        while(true) {
            List<MainActivity.Record> page=store.find("",offset,500);
            if(page.isEmpty())break;
            for(MainActivity.Record r:page)
                out.add(new String[]{"مخاطب تجاری",r.title,r.phone,r.category,
                    r.source,"","",Long.toString(r.retrieved),"",r.provider});
            offset+=page.size();
            if(page.size()<500)break;
        }
        DiscoveryData data=new DiscoveryData(store);
        for(String[] group:data.groupExport()) {
            out.add(new String[]{"گروه عمومی کشف‌شده",group[0],group[1],
                group[2],group[3],"","",group[5],"",
                "کشف: "+group[4]+"؛ لینک عمومی تأیید نشده"});
        }
        // Only account groups loaded in the current authorized TDLib session;
        // no invented invite links, member lists or private numbers.
        for(TdlibEngine.ChatInfo chat:TdlibEngine.get(context).accountGroups()) {
            out.add(new String[]{"گروه عضو حساب",chat.name,Long.toString(chat.id),
                "گروه قابل‌دسترسی حساب (عمومی/خصوصی)",
                "لینک در دسترس نیست","","","","",
                "فقط فهرست گروه‌ها؛ انتشار هر پیام مجوز مستقل می‌خواهد"});
        }
        for(String[] web:data.webExport()) {
            out.add(new String[]{"منبع وب",web[0],"",web[1],web[0],"","",
                web[3],"","تعداد بررسی: "+web[2]});
        }
        List<String[]> queue=QueueDeliveryReport.rows(ScheduledQueue.snapshot(context));
        for(int i=1;i<queue.size();i++){
            String[] q=queue.get(i);
            out.add(new String[]{"گزارش صف",q[1],q[2],q[5],"",q[3],q[4],
                q[8],q[0],q[6]+"؛ ثبت: "+q[7]});
        }
        return out;
    }

    static String live(MainActivity.Store store,Context context){
        DiscoveryData data=new DiscoveryData(store);
        int pending=0,submitted=0,delivered=0,failed=0,unknown=0;
        for(JSONObject item:ScheduledQueue.snapshot(context)){
            switch(item.optString("status")){
                case "PENDING":pending++;break;
                case "SENDING":case "SUBMITTED":submitted++;break;
                case "DELIVERED":delivered++;break;
                case "FAILED":failed++;break;
                case "UNKNOWN":unknown++;break;
            }
        }
        JSONObject last=DiscoveryCoordinator.get(context).snapshot();
        return "مخاطبان تجاری: "+store.count("records")+
            " | گروه‌های عمومی: "+data.total("public_groups")+
            " | گروه‌های حساب: "+TdlibEngine.get(context).accountGroups().size()+
            "\nنشانی‌های وب: "+data.total("discovery_urls")+
            " | صفحات بررسی‌شده: "+data.finishedWebPages()+
            "\nصف — منتظر: "+pending+" | درخواست ثبت‌شده: "+submitted+
            " | تأیید ارسال تلگرام: "+delivered+
            " | ناموفق: "+failed+" | نامشخص: "+unknown+
            "\nآخرین کاوش — شماره جدید: "+last.optInt("saved")+
            " | گروه جدید: "+last.optInt("groups")+
            " | صفحات: "+last.optInt("pages")+
            " | تکراری: "+last.optInt("duplicates")+
            " | خطا: "+last.optInt("errors")+
            "\nوضعیت کاوش: "+last.optString("state","آماده");
    }

    static void writeTxt(OutputStream output,List<String[]> rows)throws IOException{
        // Flush but do not close the caller's stream; close is owned by the Activity.
        BufferedWriter writer=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8));
        for(String[] row:rows){
            for(int i=0;i<row.length;i++){
                if(i>0)writer.write('\t');
                String value=row[i]==null?"":row[i];
                writer.write(value.replace("\t"," ").replace("\r"," ").replace("\n"," "));
            }
            writer.newLine();
        }
        writer.flush();
    }
}
