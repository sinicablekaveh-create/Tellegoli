package ir.electricconnect.nativefree;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Foreground consent UI for a FINITE background queue; never discovers group members.
 * A message is approved for one specific group at enqueue time, never at dispatch.
 */
public final class ScheduledQueueActivity extends Activity implements TdlibEngine.Listener {
    private final int teal = Color.rgb(8,103,115);
    private TdlibEngine engine;
    private TextView stateText, resultText, queueHeader;
    private LinearLayout picker;
    private final java.util.LinkedHashMap<Long,String> chosenGroups=new java.util.LinkedHashMap<>();
    private LinearLayout queueItems;
    private EditText filter, message;
    private CheckBox permission;
    private TextView discoveryStatus;
    private List<TdlibEngine.ChatInfo> chats = new ArrayList<>();
    private long selectedChat;
    private String selectedTitle;
    private boolean ready;
    private AlertDialog pendingDialog;
    private static final int REQUEST_EXPORT_QUEUE=7343;
    private final ExecutorService reportExecutor=Executors.newSingleThreadExecutor();
    private final Handler refreshHandler=new Handler(Looper.getMainLooper());
    private boolean screenVisible;
    private String lastQueueSignature="";
    private long lastGroupRefreshAt=0L;
    private final Runnable refreshQueue=new Runnable() {
        @Override public void run() {
            if(!screenVisible)return;
            drawQueue();
            if(ready && System.currentTimeMillis()-lastGroupRefreshAt>=300000L) {
                lastGroupRefreshAt=System.currentTimeMillis();
                engine.refresh();
            }
            refreshHandler.postDelayed(this,5000L);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ElectricUi.styleWindow(this);
        setTitle("Electric Connect | صف زمان‌بندی‌شده");
        engine = TdlibEngine.get(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundColor(ElectricUi.BG);
        root.setPadding(dp(18),dp(18),dp(18),dp(32));
        scroll.addView(root);
        setContentView(scroll);

        LinearLayout hero=ElectricUi.hero(this);
        hero.addView(ElectricUi.text(this,"صف زمان‌بندی‌شده",23,Color.WHITE,true));
        hero.addView(ElectricUi.text(this,"هر پیام، یک گروه و یک تأیید مستقل؛ حداکثر ۲۰ پیام در انتظار.",
                14,Color.rgb(229,244,246),false));
        hero.addView(ElectricUi.text(this,"حداقل ۸ دقیقه بین تلاش‌ها؛ زمان دقیق تضمین نمی‌شود.",
                13,Color.rgb(229,244,246),false));
        ElectricUi.addBlock(this,root,hero,1);
        LinearLayout connection=ElectricUi.card(this);
        connection.addView(text("وضعیت اتصال حساب تلگرام",16,true));
        stateText=text("حساب TDLib را متصل کنید.",15,true);
        connection.addView(stateText);
        ElectricUi.addBlock(this,root,connection,13);
        ElectricUi.addBlock(this,connection,button("اتصال / بارگذاری دوبارهٔ گروه‌ها",()->{
            engine.connect();
            if(ready)engine.refresh();
        }),10);
        resultText=text("",13,false);
        resultText.setBackground(ElectricUi.outlined(this,ElectricUi.WHITE,12));
        resultText.setPadding(dp(13),dp(11),dp(13),dp(11));
        ElectricUi.addBlock(this,root,resultText,12);

        ElectricUi.section(this,root,"۱. انتخاب گروه‌های مجاز","چند گروه عضو را انتخاب کنید؛ فقط با اجازهٔ انتشار");
        LinearLayout groupsCard=ElectricUi.card(this);
        filter=entry("جست‌وجوی نام گروه یا شناسه",false);
        ElectricUi.addBlock(this,groupsCard,filter,8);
        ElectricUi.addBlock(this,root,groupsCard,9);
        filter.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) { drawGroups(); }
            public void afterTextChanged(android.text.Editable s) {}
        });
        picker=new LinearLayout(this);
        picker.setOrientation(LinearLayout.VERTICAL);
        picker.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        ElectricUi.addBlock(this,groupsCard,picker,10);

        ElectricUi.section(this,root,"۲. نوشتن پیام","نام مقصد و متن در تأیید نهایی نمایش داده می‌شوند");
        message=entry("متن تبلیغ یا اطلاع‌رسانی تأییدشده",true);
        message.setSingleLine(false);
        message.setMinLines(3);
        ElectricUi.addBlock(this,root,message,9);
        permission=new CheckBox(this);
        permission.setText("مجوز صریح انتشار این متن را برای تمام گروه‌های انتخاب‌شده دارم؛ هر مقصد فقط یک‌بار ثبت شود.");
        permission.setTextDirection(View.TEXT_DIRECTION_RTL);
        permission.setMinHeight(dp(48));
        ElectricUi.addBlock(this,root,permission,8);
        ElectricUi.addBlock(this,root,button("افزودن این پیام به صف",this::approveItem),10);

        ElectricUi.section(this,root,"۳. مدیریت صف","شروع فقط با تأیید مستقل شما انجام می‌شود");
        CheckBox gapDiscovery=new CheckBox(this);
        gapDiscovery.setText("جستجوی بیشترِ وب بین دو ارسال تأییدشده");
        gapDiscovery.setTextDirection(View.TEXT_DIRECTION_RTL);
        gapDiscovery.setMinHeight(dp(48));
        gapDiscovery.setChecked(QueueGapDiscovery.enabled(this));
        gapDiscovery.setOnCheckedChangeListener((v,on)->QueueGapDiscovery.setEnabled(this,on));
        ElectricUi.addBlock(this,root,gapDiscovery,8);
        ElectricUi.addBlock(this,root,text(
            "پس از هر تلاش ارسال، یک نوبت جستجوی محدود وب در فاصلهٔ حداقل ۸ دقیقه‌ای آغاز می‌شود؛ زمان اجرای پس‌زمینه دقیق نیست. جستجوی وب هر ۱۵ دقیقه هم مستقل ادامه دارد. گروه‌های تلگرام فقط با حساب آماده و برنامهٔ باز بررسی می‌شوند. هیچ گیرندهٔ جدیدی بدون تأیید به صف اضافه نمی‌شود.",
            13,false),8);
        discoveryStatus=text("",13,false);
        ElectricUi.addBlock(this,root,discoveryStatus,8);
        root.addView(button("شروع صف زمان‌بندی‌شده",this::confirmStart));
        ElectricUi.addBlock(this,root,ElectricUi.button(this,"توقف فوری صف",()->{
            ScheduledQueue.stop(this);
            show("صف متوقف شد؛ اگر ارسال شبکه‌ای از قبل آغاز شده باشد ممکن است قابل لغو نباشد.");
            drawQueue();
        },true,true),8);
        ElectricUi.addBlock(this,root,ElectricUi.button(this,
                "بازخوانی وضعیت صف",this::drawQueue,true,false),8);
        ElectricUi.addBlock(this,root,ElectricUi.button(this,
                "ذخیره گزارش تحویل پیام‌ها (Excel)",this::requestQueueExport,true,false),8);
        queueHeader=text("",15,true);
        ElectricUi.addBlock(this,root,queueHeader,12);
        queueItems=new LinearLayout(this);
        queueItems.setOrientation(LinearLayout.VERTICAL);
        ElectricUi.addBlock(this,root,queueItems,5);

        ElectricUi.addBlock(this,root,text("تحویل به TDLib الزاماً تأیید دریافت در گروه نیست. خطاهای نامشخص تکرار نمی‌شوند؛ در تلگرام رسمی نتیجه را بررسی کنید.",13,false),13);
        drawQueue();
        drawGroups();
    }

    @Override protected void onResume() {
        super.onResume();
        engine.attach(this);
        engine.connect();
        screenVisible=true;
        drawQueue();
        refreshHandler.removeCallbacks(refreshQueue);
        refreshHandler.postDelayed(refreshQueue,5000L);
    }
    @Override protected void onPause() {
        screenVisible=false;
        refreshHandler.removeCallbacks(refreshQueue);
        engine.detach(this);
        if(pendingDialog!=null){pendingDialog.dismiss();pendingDialog=null;}
        super.onPause();
    }
    @Override protected void onDestroy() {
        screenVisible=false;
        refreshHandler.removeCallbacks(refreshQueue);
        reportExecutor.shutdown();
        super.onDestroy();
    }

    private void requestQueueExport() {
        Intent choose=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        choose.addCategory(Intent.CATEGORY_OPENABLE);
        choose.setType(ExcelWorkbook.MIME);
        choose.putExtra(Intent.EXTRA_TITLE,"electric-connect-message-report.xlsx");
        try { startActivityForResult(choose,REQUEST_EXPORT_QUEUE); }
        catch(Exception e) { show("انتخاب محل ذخیرهٔ گزارش در این دستگاه در دسترس نیست."); }
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=REQUEST_EXPORT_QUEUE || resultCode!=RESULT_OK || data==null || data.getData()==null)return;
        final Uri destination=data.getData();
        show("در حال ذخیرهٔ گزارش آفلاین پیام‌ها...");
        reportExecutor.execute(()->{
            try {
                List<String[]> rows=QueueDeliveryReport.rows(ScheduledQueue.snapshot(getApplicationContext()));
                try(OutputStream output=getContentResolver().openOutputStream(destination,"w")){
                    if(output==null)throw new java.io.IOException("Cannot open destination");
                    ExcelWorkbook.write(output,"گزارش پیام‌ها",rows);
                }
                int exported=rows.size()-1;
                runOnUiThread(()->{if(!isDestroyed())show("گزارش "+exported+" پیام در محل انتخاب‌شده ذخیره شد.");});
            } catch(Exception e) {
                runOnUiThread(()->{if(!isDestroyed())show("ذخیرهٔ گزارش ناموفق بود؛ محل ذخیره و فضای دستگاه را بررسی کنید.");});
            }
        });
    }

    private void approveItem() {
        if(!ready){show("ابتدا وارد حساب تلگرام شوید تا وضعیت READY شود.");return;}
        if(chosenGroups.isEmpty()){show("حداقل یک گروه را از فهرست حساب انتخاب کنید.");return;}
        if(!permission.isChecked()){show("داشتن مجوز انتشار را برای همین پیام تأیید کنید.");return;}
        String body=message.getText().toString().trim();
        if(body.isEmpty()||body.length()>3000){show("متن باید بین ۱ تا ۳۰۰۰ نویسه باشد.");return;}
        final java.util.LinkedHashMap<Long,String> targets=new java.util.LinkedHashMap<>(chosenGroups);
        final String groupNames=android.text.TextUtils.join("، ",targets.values());
        pendingDialog=new AlertDialog.Builder(this)
            .setTitle("تأیید افزودن "+targets.size()+" پیام")
            .setMessage("این متن یک‌بار برای هر گروه انتخاب‌شده و بدون تکرار در صف قرار گیرد؟\n\n"+
                "گروه‌ها: "+shortText(groupNames,800)+"\n\n"+body)
            .setNegativeButton("انصراف",null)
            .setPositiveButton("افزودن به صف",(d,w)->{
                if(isFinishing()||isDestroyed()||!ready||!chosenGroups.equals(targets)
                        ||!permission.isChecked()||!body.equals(message.getText().toString().trim())){
                    show("انتخاب‌ها تغییر کرده؛ دوباره بررسی کنید.");return;
                }
                int added=0;String firstError=null;
                for(java.util.Map.Entry<Long,String> target:targets.entrySet()){
                    String error=ScheduledQueue.add(this,target.getKey(),target.getValue(),body);
                    if(error==null)added++;else if(firstError==null)firstError=error;
                }
                if(added>0) {
                    message.setText("");
                    permission.setChecked(false);
                    chosenGroups.clear();
                    drawGroups();
                }
                show(added+" پیام برای گروه‌های تأییدشده در صف ثبت شد؛ هنوز ارسال نشده است."+
                    (firstError==null?"":"\nبعضی مقصدها ثبت نشدند: "+firstError));
                drawQueue();
            }).show();
    }

    private void confirmStart() {
        int pending=0;
        for(JSONObject item:ScheduledQueue.snapshot(this)) {
            if("PENDING".equals(item.optString("status")))pending++;
        }
        if(pending==0){show("صف پیام در انتظار ندارد.");return;}
        if(!ready){show("حساب تلگرام READY نیست؛ نخست وارد حساب شوید.");return;}
        final int total=pending;
        pendingDialog=new AlertDialog.Builder(this)
            .setTitle("شروع ارسال مجاز پس‌زمینه")
            .setMessage(total+" پیام تأییدشده، هر کدام فقط یک‌بار و با فاصلهٔ حداقل ۸ دقیقه بین تلاش‌ها در پس‌زمینه اجرا شوند؟\n\n"
              +"اجرای دقیق در اندروید تضمین نمی‌شود. برای توقف از همین صفحه استفاده کنید.")
            .setNegativeButton("انصراف",null)
            .setPositiveButton("شروع صف",(d,w)->{
                if(!ready||isFinishing()||isDestroyed()){show("حساب آماده نیست؛ پس از اتصال دوباره تأیید کنید.");return;}
                boolean started=ScheduledQueue.start(this);
                show(started?"صف در WorkManager ثبت شد.": "شروع ناموفق بود یا صف خالی است.");
                drawQueue();
            }).show();
    }

    private void drawQueue() {
        if(queueItems==null)return;
        List<JSONObject> entries=ScheduledQueue.snapshot(this);
        boolean active=ScheduledQueue.enabled(this);
        String signature=active+"|"+entries.toString();
        if(discoveryStatus!=null)discoveryStatus.setText(
                "آخرین وضعیت کاوش:\n"+DiscoveryCoordinator.describe(DiscoveryCoordinator.get(this).snapshot()));
        if(signature.equals(lastQueueSignature))return;
        lastQueueSignature=signature;
        queueItems.removeAllViews();
        int pending=0,delivered=0,failed=0,uncertain=0;
        for(JSONObject entry:entries) {
            switch(entry.optString("status")) {
                case "PENDING":pending++;break;
                case "DELIVERED":delivered++;break;
                case "FAILED":failed++;break;
                case "UNKNOWN":uncertain++;break;
            }
        }
        queueHeader.setText("وضعیت: "+(active?"فعال":"متوقف")+
                " | کل: "+entries.size()+" | منتظر: "+pending+
                " | تأیید ارسال تلگرام: "+delivered+" | ناموفق: "+failed+
                " | نامشخص: "+uncertain+"\nبه‌روزرسانی خودکار هر ۵ ثانیه هنگام باز بودن صفحه");
        if(entries.isEmpty())queueItems.addView(text("صف خالی است.",13,false));
        for(JSONObject item:entries) {
            String id=item.optString("id");
            String status=item.optString("status");
            String title=item.optString("chat_name");
            String info=item.optString("detail");
            TextView row=text("• "+title+"\n"+QueueDeliveryReport.label(status)+" | "+info+"\n"+
                    shortText(item.optString("text"),180),14,false);
            LinearLayout itemCard=ElectricUi.card(this);
            itemCard.addView(row);
            ElectricUi.addBlock(this,queueItems,itemCard,9);
            if("PENDING".equals(status)) {
                ElectricUi.addBlock(this,itemCard,ElectricUi.button(this,
                    "حذف این پیام از صف",()->new AlertDialog.Builder(this)
                    .setMessage("این پیام ارسال‌نشده از صف حذف شود؟")
                    .setNegativeButton("انصراف",null)
                    .setPositiveButton("حذف",(d,w)->{
                        ScheduledQueue.removePending(this,id);
                        drawQueue();
                    }).show(),true,true),8);
            }
        }
    }

    private void drawGroups() {
        if(picker==null)return;
        picker.removeAllViews();
        String query=filter.getText().toString();
        int count=0,matched=0;
        for(TdlibEngine.ChatInfo chat:chats) {
            if(!SearchText.matches(chat.name+" "+chat.id,query))continue;
            matched++;
            if(count>=100)continue;
            count++;
            CheckBox b=new CheckBox(this);
            b.setId(View.generateViewId());
            b.setText(chat.name+" ("+chat.id+")");
            b.setTextDirection(View.TEXT_DIRECTION_RTL);
            b.setChecked(chosenGroups.containsKey(chat.id));
            b.setOnCheckedChangeListener((view,checked)->{
                if(checked)chosenGroups.put(chat.id,chat.name);
                else chosenGroups.remove(chat.id);
                permission.setChecked(false);
            });
            picker.addView(b);
        }
        picker.addView(text(chosenGroups.size()+" گروه انتخاب‌شده؛ نمایش "+count+" از "+matched+
            " گروه مطابق جست‌وجو. انتخاب‌ها هنگام فیلترکردن حفظ می‌شوند.",12,false));
    }

    private void show(String info) { if(resultText!=null)resultText.setText(info); }
    private String shortText(String text,int limit) {return text.length()>limit?text.substring(0,limit)+"…":text;}
    private int dp(int n){return ElectricUi.dp(this,n);}
    private TextView text(String text,int size,boolean bold){
        return ElectricUi.text(this,text,size,bold?ElectricUi.INK:ElectricUi.MUTED,bold);
    }
    private EditText entry(String hint,boolean multi){
        return ElectricUi.field(this,hint,multi);
    }
    private Button button(String title,Runnable action){
        return ElectricUi.button(this,title,action);
    }

    @Override public void onState(String state,String detail) {
        ready="READY".equals(state);
        if(stateText!=null)stateText.setText("TDLib: "+state+"\n"+detail);
        if(!ready){chosenGroups.clear();if(permission!=null)permission.setChecked(false);}
        if(ready && chats.isEmpty() && System.currentTimeMillis()-lastGroupRefreshAt>=300000L) {
            lastGroupRefreshAt=System.currentTimeMillis();
            engine.refresh();
        }
    }
    @Override public void onChats(List<TdlibEngine.ChatInfo> updated) {
        chats=new ArrayList<>(updated);
        drawGroups();
    }
    @Override public void onContacts(List<String> ignored) {}
    @Override public void onResult(String task,boolean ok,String detail) {
        show((ok?"✓ ":"! ")+task+": "+detail);
    }
}
