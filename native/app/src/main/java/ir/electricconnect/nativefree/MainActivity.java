package ir.electricconnect.nativefree;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.io.OutputStream;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Persian-native, accessible dashboard; messaging behaviour lives in existing services. */
public final class MainActivity extends Activity {
  private Store store;
  private LinearLayout page;
  private LinearLayout recordResults;
  private int section = 0;
  private static final int REQUEST_EXPORT_CONTACTS=7341;
  private static final int REQUEST_EXPORT_ALL=7345;
  private static final int REQUEST_EXPORT_TXT=7347;
  private static final int REQUEST_IMPORT_KEYWORDS=7346;
  private static final int REQUEST_IMPORT_ALL=7348;
  private TextView offlineStatus;
  private String offlineMessage="بانک مخاطبان برای استفادهٔ آفلاین آماده است.";
  private final ExecutorService contactExecutor=Executors.newSingleThreadExecutor();
  private DiscoveryUi discoveryUi;
  private TextView liveStatus;
  private TextView syncStatus;
  private static final String[] SYNC_PARTS={
      "جستجو و همگام‌سازی شماره‌های تجاری",
      "جستجو و ذخیرهٔ گروه‌های عمومی تلگرام",
      "همگام‌سازی گروه‌های حساب TDLib",
      "تکمیل صفحات و منابع وب",
      "همگام‌سازی وضعیت صف بدون ارسال پیام",
      "آماده‌سازی خروجی کامل Excel و TXT"};
  private final android.os.Handler liveHandler=new android.os.Handler(android.os.Looper.getMainLooper());
  private final Runnable liveTick=new Runnable() {
    @Override public void run() {
      if(section!=0 || liveStatus==null || isFinishing() || isDestroyed())return;
      liveStatus.setText(liveSummary());
      if(syncStatus!=null)syncStatus.setText(syncProgress());
      liveHandler.postDelayed(this,5000L);
    }
  };
  private final String[] tabs = {"خانه", "جستجو", "مخاطبان", "صف پیام", "تنظیمات"};
  private final String[] symbols = {"⌂", "⌕", "▣", "▤", "⚙"};
  private final String[] profiles = {"پیمانکار برق صنعتی", "پیمانکار تأسیسات الکتریکی",
          "مجری کابل‌کشی", "مجری برق پروژه", "شرکت EPC", "برق سوله و کارخانه"};

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    ElectricUi.styleWindow(this);
    store = new Store(this);
    DiscoverySchedule.bootstrap(this);
    setTitle("تلگولی");
    int previous = state == null ? 0 : state.getInt("tab", 0);
    draw(previous >= 0 && previous < tabs.length ? previous : 0);
  }
  @Override public void onSaveInstanceState(Bundle bundle) {
    bundle.putInt("tab", section);
    super.onSaveInstanceState(bundle);
  }
  @Override protected void onResume() {
    super.onResume();
    // Returning from Exa/Telegram refreshes the dashboard's offline/online badge.
    if (page != null && (section == 0 || section == 2)) draw(section);
  }
  @Override protected void onPause() {
    liveHandler.removeCallbacks(liveTick);
    super.onPause();
  }
  @Override protected void onDestroy() {
    liveHandler.removeCallbacks(liveTick);
    if(discoveryUi!=null)discoveryUi.detach();
    contactExecutor.execute(()->{if(store!=null)store.close();});
    contactExecutor.shutdown();
    super.onDestroy();
  }

  private int dp(int n) { return ElectricUi.dp(this,n); }
  private LinearLayout vertical() { return ElectricUi.column(this); }
  private TextView text(String s, int size, boolean bold) {
    return ElectricUi.text(this,s,size,bold ? ElectricUi.INK : ElectricUi.MUTED,bold);
  }
  private void line(String title) { ElectricUi.section(this,page,title,null); }
  private void note(String s) { ElectricUi.addBlock(this,page,text(s,13,false),4); }
  private void toast(String msg) { Toast.makeText(this,msg,Toast.LENGTH_LONG).show(); }
  private Button button(String label, Runnable action) {
    Button b=ElectricUi.button(this,label,action);
    ElectricUi.addBlock(this,page,b,10);
    return b;
  }
  private EditText input(String hint) {
    EditText e=ElectricUi.field(this,hint,false);
    ElectricUi.addBlock(this,page,e,10);
    return e;
  }
  private void card(LinearLayout content) { ElectricUi.addBlock(this,page,content,12); }
  private LinearLayout newCard() { return ElectricUi.card(this); }

  private void quickAction(String glyph, String title, String subtitle, Runnable action) {
    LinearLayout item=ElectricUi.row(this);
    item.setPadding(dp(14),dp(13),dp(14),dp(13));
    item.setBackground(ElectricUi.outlined(this,ElectricUi.WHITE,16));
    item.setMinimumHeight(dp(76));
    TextView icon=ElectricUi.text(this,glyph,22,ElectricUi.TEAL,true);
    icon.setGravity(Gravity.CENTER);
    icon.setBackground(ElectricUi.shape(this,ElectricUi.LIGHT_TEAL,12));
    item.addView(icon,new LinearLayout.LayoutParams(dp(48),dp(48)));
    LinearLayout middle=vertical();
    middle.addView(ElectricUi.text(this,title,16,ElectricUi.INK,true));
    middle.addView(ElectricUi.text(this,subtitle,12,ElectricUi.MUTED,false));
    LinearLayout.LayoutParams mlp=new LinearLayout.LayoutParams(0,-2,1f);
    mlp.setMargins(dp(10),0,dp(8),0);
    item.addView(middle,mlp);
    item.addView(ElectricUi.text(this,"‹",24,ElectricUi.TEAL,true));
    item.setContentDescription(title+"؛ "+subtitle);
    item.setFocusable(true);
    item.setOnClickListener(v->action.run());
    ElectricUi.addBlock(this,page,item,9);
  }

  private void draw(int tab) {
    if(discoveryUi!=null) {discoveryUi.detach();discoveryUi=null;}
    section=tab;
    liveStatus=null;syncStatus=null;
    liveHandler.removeCallbacks(liveTick);
    LinearLayout root=vertical();
    root.setBackgroundColor(ElectricUi.BG);
    String[] subtitle={"مرکز ارتباطات صنعت برق","جست‌وجوی محلی و اینترنتی",
        "بانک اطلاعات کسب‌وکارهای شما","پیش‌نویس و صف تأییدشده","تنظیمات و حریم خصوصی"};
    root.addView(ElectricUi.header(this,"⚡ تلگولی",subtitle[tab]));
    ScrollView scroller=new ScrollView(this);
    scroller.setFillViewport(true);
    page=vertical();
    page.setPadding(dp(18),dp(4),dp(18),dp(26));
    scroller.addView(page);
    root.addView(scroller,new LinearLayout.LayoutParams(-1,0,1f));
    LinearLayout bottom=ElectricUi.row(this);
    bottom.setBackground(ElectricUi.shape(this,ElectricUi.WHITE,0));
    bottom.setPadding(dp(6),dp(6),dp(6),dp(7));
    bottom.setElevation(dp(8));
    for(int i=0;i<tabs.length;i++) {
      final int target=i;
      LinearLayout link=vertical();
      link.setGravity(Gravity.CENTER);
      link.setMinimumHeight(dp(60));
      link.setContentDescription("بخش "+tabs[i]);
      link.setFocusable(true);
      if(tab==i)link.setBackground(ElectricUi.shape(this,ElectricUi.LIGHT_TEAL,12));
      TextView icon=ElectricUi.text(this,symbols[i],21,
          tab==i?ElectricUi.TEAL:ElectricUi.MUTED,tab==i);
      icon.setGravity(Gravity.CENTER);
      link.addView(icon);
      TextView label=ElectricUi.text(this,tabs[i],12,
          tab==i?ElectricUi.TEAL:ElectricUi.MUTED,tab==i);
      label.setGravity(Gravity.CENTER);
      link.addView(label);
      link.setOnClickListener(v->draw(target));
      // Both the full hit target and its visible text/icon navigate.
      // Robolectric and accessibility action targeting may hit a child directly.
      icon.setOnClickListener(v->draw(target));
      label.setOnClickListener(v->draw(target));
      bottom.addView(link,new LinearLayout.LayoutParams(0,dp(64),1f));
    }
    root.addView(bottom);
    setContentView(root);
    if(tab==0)home();
    else if(tab==1)searchPage();
    else if(tab==2)contactsPage();
    else if(tab==3)queuePage();
    else settingsPage();
    if(tab==0)liveHandler.post(liveTick);
  }
  /** Ask Android where to save; no storage permissions, cloud upload or Exa needed. */
  private void requestContactsXlsxExport() {
    Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType(ExcelWorkbook.MIME);
    intent.putExtra(Intent.EXTRA_TITLE,"Tellegoli_Contacts.xlsx");
    try {startActivityForResult(intent,REQUEST_EXPORT_CONTACTS);}
    catch(Exception error){toast("برنامه‌ای برای ذخیرهٔ فایل اکسل در دسترس نیست.");}
  }

  @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
    super.onActivityResult(requestCode,resultCode,data);
    if(requestCode==REQUEST_IMPORT_ALL && resultCode==RESULT_OK && data!=null && data.getData()!=null) {
      Uri file=data.getData();
      toast("در حال خواندن منابع وب، مخاطبان و گروه‌ها از اکسل...");
      contactExecutor.execute(()->{
        try(java.io.InputStream in=getContentResolver().openInputStream(file)){
          UnifiedExcelImport.Parsed parsed=UnifiedExcelImport.read(in);
          UnifiedExcelImport.Report imported=UnifiedExcelImport.apply(store,parsed);
          runOnUiThread(()->{if(!isDestroyed()){
            toast(imported.describe());
            if(imported.sources>0 && getSharedPreferences("discovery",MODE_PRIVATE)
                .getBoolean("periodic",false))DiscoveryCoordinator.get(this).start(0,false);
            draw(0);
          }});
        }catch(Exception error){
          runOnUiThread(()->{if(!isDestroyed())toast("ورود اکسل انجام نشد: "+error.getMessage()+"؛ اطلاعات قبلی محفوظ است.");});
        }
      });return;
    }
    if(requestCode==REQUEST_IMPORT_KEYWORDS && resultCode==RESULT_OK && data!=null && data.getData()!=null) {
      Uri file=data.getData();
      toast("در حال خواندن کلیدواژه‌های اکسل...");
      contactExecutor.execute(()->{
        try(java.io.InputStream in=getContentResolver().openInputStream(file)) {
          if(in==null)throw new java.io.IOException();
          KeywordWorkbook.Result parsed=KeywordWorkbook.read(in);
          int added=LeadKeywords.merge(new DiscoveryData(store),parsed.keywords);
          String message=added+" کلیدواژهٔ جدید ذخیره شد؛ "+parsed.rejected+" عبارت نامعتبر یا محصول و "+parsed.duplicates+" تکراری در فایل کنار گذاشته شد.";
          runOnUiThread(()->{if(!isDestroyed()){toast(message);draw(1);}});
        } catch(Exception e) {
          runOnUiThread(()->{if(!isDestroyed())toast("ورود اکسل انجام نشد. فایل XLSX با حداکثر ۵۰ کلیدواژهٔ مشتری‌یابی انتخاب کنید؛ داده‌های قبلی محفوظ است.");});
        }
      });return;
    }
    if((requestCode==7343||requestCode==7344)&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null) {
      Uri file=data.getData();
      contactExecutor.execute(()->{
        try {
          String message;
          if(requestCode==7343) {try(OutputStream out=getContentResolver().openOutputStream(file,"w")){if(out==null)throw new java.io.IOException();LocalBackup.write(store,out);}message="پشتیبان مخاطبان، گروه‌های عمومی و پیش‌نویس‌ها ذخیره شد؛ نشست تلگرام در آن نیست.";}
          else {int count;try(java.io.InputStream in=getContentResolver().openInputStream(file)){if(in==null)throw new java.io.IOException();count=LocalBackup.restore(store,in);}message="بازیابی انجام شد؛ "+count+" مخاطب اضافه شد. داده‌های قبلی حفظ شدند.";}
          runOnUiThread(()->{if(!isDestroyed()){toast(message);draw(section);}});
        } catch(Exception e){runOnUiThread(()->{if(!isDestroyed())toast("فایل معتبر یا قابل‌دسترسی نیست؛ داده‌های قبلی محفوظ است.");});}
      });return;
    }
    if(requestCode==REQUEST_EXPORT_ALL && resultCode==RESULT_OK &&
        data!=null && data.getData()!=null) {
      writeCombinedExcel(data.getData());return;
    }
    if(requestCode==REQUEST_EXPORT_TXT && resultCode==RESULT_OK &&
        data!=null && data.getData()!=null) {
      writeCombinedText(data.getData());return;
    }
    if(requestCode!=REQUEST_EXPORT_CONTACTS || resultCode!=RESULT_OK ||
        data==null || data.getData()==null)return;
    Uri destination=data.getData();
    offlineMessage="در حال تهیهٔ خروجی اکسل از اطلاعات ذخیره‌شده...";
    if(offlineStatus!=null)offlineStatus.setText(offlineMessage);
    contactExecutor.execute(()->{
      try{
        List<String[]> records=new ArrayList<>();
        records.add(new String[]{"نام کسب‌وکار","شماره تماس","دسته‌بندی","منبع / یادداشت","نوع منبع","زمان دریافت"});
        int offset=0;
        while(true){
          List<Record> page=store.find("",offset,500);
          if(page.isEmpty())break;
          for(Record contact:page)
            records.add(new String[]{contact.title,contact.phone,contact.category,contact.source,contact.provider,Long.toString(contact.retrieved)});
          offset+=page.size();
          if(page.size()<500)break;
        }
        try(OutputStream output=getContentResolver().openOutputStream(destination,"w")){
          if(output==null)throw new java.io.IOException("File cannot be opened");
          ExcelWorkbook.write(output,"مخاطبان",records);
        }
        final int total=records.size()-1;
        runOnUiThread(()->{
          if(isDestroyed())return;
          offlineMessage="خروجی اکسل با "+total+" مخاطب در محل انتخاب‌شده ذخیره شد.";
          if(offlineStatus!=null)offlineStatus.setText(offlineMessage);
          toast(offlineMessage);
        });
      }catch(Exception e){
        runOnUiThread(()->{
          if(isDestroyed())return;
          offlineMessage="خروجی اکسل ناموفق بود؛ محل ذخیره را بررسی کن.";
          if(offlineStatus!=null)offlineStatus.setText(offlineMessage);
          toast(offlineMessage);
        });
      }
    });
  }

  /** Single export: business phones, public Telegram group metadata and
   * approved queue log. One offline XLSX; no personal chat messages exported. */
  private void requestCombinedXlsxExport() {
    Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType(ExcelWorkbook.MIME);
    intent.putExtra(Intent.EXTRA_TITLE,"Tellegoli_Contacts_Groups_Queue.xlsx");
    try {startActivityForResult(intent,REQUEST_EXPORT_ALL);}
    catch(Exception error){toast("انتخاب محل خروجی اکسل ممکن نشد.");}
  }

  /** One auditable snapshot for BOTH Excel and UTF-8 TXT, including pending/unknown statuses. */
  private List<String[]> combinedReportRows() {
    List<String[]> rows=new ArrayList<>();
    rows.add(new String[]{"نوع داده","عنوان","شماره / شناسه / آدرس",
        "دسته‌بندی","منبع","متن پیام","وضعیت واقعی","زمان / آخرین مشاهده",
        "شناسه صف","جزئیات","لینک عمومی گروه"});
    int offset=0;
    while(true) {
      List<Record> contacts=store.find("",offset,500);
      if(contacts.isEmpty())break;
      for(Record r:contacts)rows.add(new String[]{"مخاطب تجاری",
          r.title==null||r.title.trim().isEmpty()?"بی‌نام":r.title,r.phone,
          r.category,r.source,"","",formatTime(r.retrieved),"",r.provider,""});
      offset+=contacts.size();
      if(contacts.size()<500)break;
    }
    java.util.Set<String> groupIds=new java.util.HashSet<>();
    for(TdlibEngine.ChatInfo g:TdlibEngine.get(this).snapshotJoinedGroups()) {
      String id=Long.toString(g.id);
      if(groupIds.add(id))rows.add(new String[]{"گروه حساب تلگرام",g.name,id,
          "موجود در فهرست حساب", "TDLib - فهرست حساب","","", "", "",
          "مجوز ارسال هنگام هر تلاش جداگانه بررسی می‌شود",
          TdlibEngine.get(this).publicGroupLink(g.id)});
    }
    for(String[] g:new DiscoveryData(store).groupExport()) {
      if(groupIds.add(g[1]))rows.add(new String[]{"گروه عمومی کشف‌شده",g[0],g[1],
          g[2],g[3],"","",formatTime(Long.parseLong(g[5])),"",
          "کشف: "+formatTime(Long.parseLong(g[4])),
          TdlibEngine.get(this).publicGroupLink(Long.parseLong(g[1]))});
    }
    for(String[] g:new DiscoveryData(store).importedGroupExport()) {
      if(!g[1].isEmpty() && !groupIds.add(g[1]))continue;
      rows.add(new String[]{"گروه ورودی اکسل",g[0],g[1],
          "واردشده؛ عضویت تأیید نشده",g[3],"","",g[4],"",
          "لینک و مشخصات از فایل کاربر؛ مجوز انتشار تأیید نشده",g[2]});
    }
    List<String[]> queue=QueueDeliveryReport.rows(ScheduledQueue.snapshot(getApplicationContext()));
    for(int i=1;i<queue.size();i++) {
      String[] q=queue.get(i);
      rows.add(new String[]{"گزارش صف",q[1],q[2],q[5],"TDLib",q[3],q[4],
          q[8],q[0],q[6]+"؛ ثبت: "+q[7]+"؛ شناسه پیام: "+q[9],""});
    }
    // Export imported website sources in the same XLSX shape that the importer
    // accepts; this also makes source lists portable between devices.
    for(String[] site:new DiscoveryData(store).importedSourceExport()) {
      rows.add(new String[]{"منبع وب",site[1],site[0],
          "سایت عمومی مشتری‌یابی","ورودی اکسل","","",formatTime(Long.parseLong(site[2])),
          "","در جستجوی دوره‌ای استفاده می‌شود",""});
    }
    android.content.SharedPreferences web=getSharedPreferences("discovery",MODE_PRIVATE);
    android.content.SharedPreferences telegram=TelegramBackgroundSchedule.prefs(this);
    rows.add(new String[]{"وضعیت برنامه","جستجوی وب ۱۵ دقیقه‌ای",
        web.getBoolean("periodic",false)?"فعال":"خاموش","موتور وب عمومی",
        "WorkManager","","",formatTime(web.getLong("last_attempt",0)),
        "","آخرین شماره‌های جدید: "+web.getInt("last_new",0),""});
    rows.add(new String[]{"وضعیت برنامه","جستجوی گروه‌های تلگرام در پس‌زمینه",
        TelegramBackgroundSchedule.enabled(this)?"فعال":"خاموش","TDLib عمومی",
        "حساب شخصیِ ازقبل‌واردشده","","",formatTime(telegram.getLong("last_attempt",0)),
        "",TelegramBackgroundSchedule.describe(this),""});
    rows.add(new String[]{"وضعیت برنامه","جستجوی شماره‌های تماس",
        Long.toString(store.count("records")),"شماره‌های تجاری ذخیره‌شده",
        "SQLite محلی","","","","","شمارهٔ اعضای تلگرام استخراج نمی‌شود",""});
    rows.add(new String[]{"وضعیت برنامه","صف پیام‌های تأییدشده",
        ScheduledQueue.enabled(this)?"فعال":"متوقف","حداقل فاصله ۸ دقیقه",
        "TDLib","","","","","فقط پیام‌های انتخاب‌شده؛ وضعیت هر پیام در ردیف‌های گزارش صف",""});
    try(Cursor pages=store.getReadableDatabase().rawQuery(
        "SELECT url,state,attempts,updated FROM discovery_urls ORDER BY updated DESC",null)) {
      while(pages.moveToNext())rows.add(new String[]{"نتیجه وب","صفحه عمومی",
          pages.getString(0),"وضعیت صفحه", "کشف وب","",""+pages.getString(1),
          formatTime(pages.getLong(3)),"","تلاش‌ها: "+pages.getInt(2),""});
    }
    return rows;
  }

  private void requestCombinedTxtExport() {
    Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("text/plain");
    intent.putExtra(Intent.EXTRA_TITLE,"Tellegoli_Full_Report.txt");
    try {startActivityForResult(intent,REQUEST_EXPORT_TXT);}
    catch(Exception error){toast("انتخاب محل خروجی TXT ممکن نشد.");}
  }

  private void writeCombinedExcel(Uri destination) {
    contactExecutor.execute(()->{
      try {
        List<String[]> rows=combinedReportRows();
        try(OutputStream out=getContentResolver().openOutputStream(destination,"w")) {
          if(out==null)throw new java.io.IOException("No export stream");
          ExcelWorkbook.write(out,"تلگولی",rows);
        }
        runOnUiThread(()->{if(!isDestroyed())toast("اکسل کامل با "+(rows.size()-1)+" ردیف ذخیره شد.");});
      }catch(Exception e){
        runOnUiThread(()->{if(!isDestroyed())toast("خروجی اکسل ناموفق بود؛ محل ذخیره را بررسی کنید.");});
      }
    });
  }

  private void writeCombinedText(Uri destination) {
    contactExecutor.execute(()->{
      try {
        List<String[]> rows=combinedReportRows();
        try(OutputStream out=getContentResolver().openOutputStream(destination,"w")) {
          if(out==null)throw new java.io.IOException("No export stream");
          out.write(new byte[]{(byte)0xEF,(byte)0xBB,(byte)0xBF});
          try(java.io.BufferedWriter writer=new java.io.BufferedWriter(
                  new java.io.OutputStreamWriter(out,java.nio.charset.StandardCharsets.UTF_8))) {
            for(String[] row:rows) {
              for(int i=0;i<row.length;i++) {
                if(i>0)writer.write("\t");
                writer.write(row[i]==null?"":row[i].replace('\t',' ').replace('\r',' ').replace('\n',' '));
              }
              writer.newLine();
            }
          }
        }
        runOnUiThread(()->{if(!isDestroyed())toast("TXT کامل با "+(rows.size()-1)+" ردیف ذخیره شد.");});
      }catch(Exception e){
        runOnUiThread(()->{if(!isDestroyed())toast("خروجی TXT ناموفق بود؛ محل ذخیره را بررسی کنید.");});
      }
    });
  }

  /** Counts are refreshed while Home is visible; they are never treated as send receipts. */
  private String liveSummary() {
    long publicGroups=0,donePages=0;
    try(Cursor cursor=store.getReadableDatabase().rawQuery(
        "SELECT COUNT(*) FROM public_groups",null)){
      if(cursor.moveToFirst())publicGroups=cursor.getLong(0);
    }
    try(Cursor cursor=store.getReadableDatabase().rawQuery(
        "SELECT COUNT(*) FROM discovery_urls WHERE state='done'",null)){
      if(cursor.moveToFirst())donePages=cursor.getLong(0);
    }
    int waiting=0,sending=0,submitted=0,confirmed=0,failed=0,unknown=0;
    for(JSONObject item:ScheduledQueue.snapshot(this)) {
      switch(item.optString("status")) {
        case "PENDING":waiting++;break;
        case "SENDING":sending++;break;
        case "SUBMITTED":submitted++;break;
        case "DELIVERED":confirmed++;break;
        case "FAILED":failed++;break;
        case "UNKNOWN":unknown++;break;
      }
    }
    JSONObject run=DiscoveryCoordinator.get(this).snapshot();
    JSONObject webRun=DiscoveryCoordinator.webBackground(this).snapshot();
    JSONObject groupRun=DiscoveryCoordinator.telegramBackground(this).snapshot();
    android.content.SharedPreferences web=getSharedPreferences("discovery",MODE_PRIVATE);
    String webMode=web.getBoolean("periodic",false)?"فعال، برنامه‌ریزی تقریبی ۱۵ دقیقه":"خاموش";
    return "۱. جستجوی وب: "+webMode+
        " | آخرین تلاش: "+formatTime(web.getLong("last_attempt",0))+
        "\n   سایت‌های واردشده: "+new DiscoveryData(store).importedSourceCount()+
        " | صفحات بررسی‌شده: "+donePages+
        "\n   اجرای مستقل وب: "+webRun.optString("state","آماده")+
        " | صفحات آخرین نوبت: "+webRun.optInt("pages")+
        " | خطا: "+webRun.optInt("errors")+
        "\n۲. جستجوی گروه‌های عمومی تلگرام در پس‌زمینه: "+
            TelegramBackgroundSchedule.describe(this)+
        "\n   گروه‌های کشف‌شده: "+publicGroups+
        " | گروه‌های اکسل: "+new DiscoveryData(store).importedGroupCount()+
        "\n   اجرای مستقل تلگرام: "+groupRun.optString("state","آماده")+
        " | گروه‌های جدید آخرین نوبت: "+groupRun.optInt("groups")+
        "\n۳. جستجوی شماره تماس شرکت‌ها: "+store.count("records")+" شماره ذخیره‌شده"+
        " | شمارهٔ جدید در آخرین کاوش وب مستقل: "+webRun.optInt("saved")+
        "\n۴. صف پیام‌های تأییدشده: "+(ScheduledQueue.enabled(this)?"فعال":"متوقف")+
        " | منتظر: "+waiting+" | در حال ارسال: "+sending+
        "\n   پذیرفته‌شده: "+submitted+" | تأییدشده: "+confirmed+
        " | ناموفق: "+failed+" | نامشخص: "+unknown+
        "\nآخرین اجرای موتور کاوش: "+formatTime(run.optLong("updated",0))+
        " | خطاها: "+run.optInt("errors")+
        "\nوضعیت‌ها از بانک خود گوشی خوانده و هنگام بازبودن صفحه هر ۵ ثانیه تازه می‌شوند.";
  }

  /** Explicit on-device synchronisation; never starts or resumes the message sender. */
  private String syncProgress() {
    JSONObject run=DiscoveryCoordinator.get(this).snapshot();
    String state=run.optString("state","آماده");
    String what=run.optString("current","");
    return "وضعیت کاوش: "+state+(what.isEmpty()?"":" — "+what)+
        "\nصفحات: "+run.optInt("pages")+" | شماره‌های جدید: "+run.optInt("saved")+
        " | گروه‌های جدید: "+run.optInt("groups")+" | خطا: "+run.optInt("errors")+
        "\nگروه‌های قابل‌دسترسی TDLib: "+TdlibEngine.get(this).snapshotJoinedGroups().size()+
        " | شماره‌های تجاری ذخیره‌شده: "+store.count("records");
  }

  private void fullSyncPanel() {
    LinearLayout panel=newCard();
    panel.addView(ElectricUi.text(this,"همگام‌سازی کامل",17,ElectricUi.INK,true));
    panel.addView(ElectricUi.text(this,
        "□ انتخاب بخش‌ها؛ همگام‌سازی فقط داده و وضعیت است و هیچ پیامی ارسال نمی‌کند.",
        12,ElectricUi.MUTED,false));
    CheckBox all=new CheckBox(this);
    all.setText("■ انتخاب همهٔ بخش‌ها");
    all.setChecked(true);
    all.setMinimumHeight(dp(48));
    panel.addView(all);
    CheckBox[] checks=new CheckBox[SYNC_PARTS.length];
    final boolean[] fromItem={false};
    for(int i=0;i<SYNC_PARTS.length;i++) {
      final int index=i;
      CheckBox check=new CheckBox(this);
      check.setText(SYNC_PARTS[i]);check.setChecked(true);
      check.setMinimumHeight(dp(44));
      checks[i]=check;panel.addView(check);
      check.setOnCheckedChangeListener((b,checked)->{
        boolean allSelected=true;
        for(CheckBox item:checks)if(item!=null&&!item.isChecked())allSelected=false;
        if(all.isChecked()!=allSelected) {
          fromItem[0]=true;all.setChecked(allSelected);fromItem[0]=false;
        }
      });
    }
    all.setOnCheckedChangeListener((b,checked)->{
      if(fromItem[0])return;
      for(CheckBox check:checks)if(check!=null && check.isChecked()!=checked)
        check.setChecked(checked);
    });
    ElectricUi.addBlock(this,panel,ElectricUi.button(this,
        "اجرای همگام‌سازی بخش‌های انتخاب‌شده",()->runFullSync(checks),true,false),10);
    syncStatus=ElectricUi.text(this,syncProgress(),12,ElectricUi.MUTED,false);
    ElectricUi.addBlock(this,panel,syncStatus,8);
    ElectricUi.addBlock(this,page,panel,10);
  }

  private void runFullSync(CheckBox[] checks) {
    boolean any=false;
    for(CheckBox check:checks)any|=check.isChecked();
    if(!any){toast("حداقل یک بخش را انتخاب کنید.");return;}
    boolean contacts=checks[0].isChecked();
    boolean publicGroups=checks[1].isChecked();
    boolean account=checks[2].isChecked();
    boolean web=checks[3].isChecked();
    boolean queue=checks[4].isChecked();
    boolean export=checks[5].isChecked();
    if(account) {
      TdlibEngine engine=TdlibEngine.get(this);
      if(engine.isReady())engine.refresh();
      else toast("همگام‌سازی حساب به ورود READY تلگرام نیاز دارد؛ سایر بخش‌ها مستقل اجرا می‌شوند.");
    }
    if(contacts||publicGroups||web) {
      // Contacts and web share the public-buyer discovery provider.
      int scope=publicGroups?(contacts||web?2:1):0;
      boolean started=DiscoveryCoordinator.get(this).start(scope,false);
      if(!started)toast("یک کاوش در حال اجراست؛ پیشرفت آن را بررسی کنید.");
    }
    if(queue)ScheduledQueue.snapshot(getApplicationContext()); // Read-only status; no send.
    if(syncStatus!=null)syncStatus.setText(syncProgress()+
        "\nبخش‌های انتخابی درخواست شدند؛ خروجی وضعیت ذخیره‌شدهٔ فعلی را دارد.");
    if(liveStatus!=null)liveStatus.setText(liveSummary());
    if(export) {
      new AlertDialog.Builder(this)
          .setTitle("خروجی کامل داده‌های فعلی")
          .setMessage("خروجی گرفتن اختیاری است؛ برای فایل‌های نهایی پس از اتمام کاوش دوباره خروجی بگیرید. هیچ پیامی ارسال نمی‌شود.")
          .setPositiveButton("خروجی Excel",(d,w)->requestCombinedXlsxExport())
          .setNeutralButton("خروجی TXT",(d,w)->requestCombinedTxtExport())
          .setNegativeButton("بعداً",null).show();
    } else toast("همگام‌سازی بخش‌های انتخاب‌شده شروع یا تازه‌سازی شد؛ وضعیت زنده را ببینید.");
  }

  private void startSalesDiscovery() {
    getSharedPreferences("discovery",MODE_PRIVATE).edit().putInt("scope",2).apply();
    boolean started=DiscoveryCoordinator.get(this).start(2,false);
    toast(started?"کاوش مشتریان بالقوه و گروه‌های عمومی شروع شد؛ نتایج خودکار ذخیره می‌شوند.":
        "جست‌وجو در حال اجراست؛ وضعیت را پایین صفحه ببین.");
  }

  /** Phone discovery is a real contacts-only job, not just navigation back home. */
  private void startContactsDiscovery() {
    getSharedPreferences("discovery",MODE_PRIVATE).edit().putInt("scope",0).apply();
    boolean started=DiscoveryCoordinator.get(this).start(0,false);
    toast(started?"جست‌وجوی شماره‌های عمومی شرکت‌های مجری برق شروع شد؛ نتیجه‌ها ذخیره می‌شوند.":
        "جست‌وجو از قبل در حال اجراست؛ وضعیت را در همین صفحه ببین.");
  }

  private void requestUnifiedImport() {
    Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT);
    picker.addCategory(Intent.CATEGORY_OPENABLE);
    picker.setType(ExcelWorkbook.MIME);
    try {startActivityForResult(picker,REQUEST_IMPORT_ALL);}
    catch(Exception e){toast("انتخاب فایل اکسل ممکن نشد.");}
  }

  private void requestKeywordImport() {
    Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT);
    picker.addCategory(Intent.CATEGORY_OPENABLE);picker.setType(ExcelWorkbook.MIME);
    try {startActivityForResult(picker,REQUEST_IMPORT_KEYWORDS);}
    catch(Exception e){toast("انتخاب فایل در این دستگاه در دسترس نیست.");}
  }

  private void keywordPanel() {
    line("کلیدواژه‌های مشتری‌یابی از Excel");
    note("فایل XLSX را مستقیم انتخاب کنید: ستون «کلیدواژه» یا ستون اول، هر ردیف یک عبارت؛ حداکثر ۵۰ عبارت. متن پیمانکار، مجری و پروژه وارد کنید. عبارت‌های محصول و تولیدکننده پذیرفته نمی‌شوند.");
    button("ورود مستقیم کلیدواژه‌ها از Excel",this::requestKeywordImport);
    button("ورود منابع سایت از اکسل",this::requestUnifiedImport);
    note("تعداد منابع واردشده: "+new DiscoveryData(store).importedSourceCount()+" | فایل XLSX با ستون «آدرس سایت» یا URL.");
    List<String> imported=LeadKeywords.imported(new DiscoveryData(store));
    note(imported.isEmpty()?"۱۰ موضوع آمادهٔ خریداران فعال است؛ کلیدواژه‌های اکسل به آن‌ها اضافه می‌شوند.":
        "کلیدواژه‌های واردشده (در جست‌وجوی بعدی فعال):\n"+android.text.TextUtils.join("، ",imported));
  }

  private void requestBackup(boolean restore) {
    Intent picker=new Intent(restore?Intent.ACTION_OPEN_DOCUMENT:Intent.ACTION_CREATE_DOCUMENT);
    picker.addCategory(Intent.CATEGORY_OPENABLE);picker.setType("application/json");
    if(!restore)picker.putExtra(Intent.EXTRA_TITLE,"Tellegoli_Backup.json");
    try{startActivityForResult(picker,restore?7344:7343);}catch(Exception e){toast("انتخاب فایل در این دستگاه ممکن نشد.");}
  }

  private void openExa(){startActivity(new Intent(this,ExaResearchActivity.class));}
  private void openTelegramTest(){startActivity(new Intent(this,TelegramTestActivity.class));}
  private void openTdlib(){startActivity(new Intent(this,TdlibAccountActivity.class));}
  private void openTelegramBrowser(){
    startActivity(new Intent(this,TelegramBrowserActivity.class));
  }
  private void openScheduledQueue(){startActivity(new Intent(this,ScheduledQueueActivity.class));}


  /** A focused sales dashboard: one discovery action, clear automatic mode, real counts. */
  private void home(){
    LinearLayout hero=ElectricUi.hero(this);
    hero.addView(ElectricUi.text(this,"تلگولی  •  "+BuildConfig.VERSION_NAME,
        13,Color.rgb(210,245,245),true));
    ElectricUi.addBlock(this,hero,ElectricUi.text(this,
        "مشتری‌یابی خودکار",27,Color.WHITE,true),6);
    ElectricUi.addBlock(this,hero,ElectricUi.text(this,
        "شمارهٔ شرکت‌های برق و گروه‌های عمومی تلگرام؛ یکجا و بدون نیاز به کلید پولی.",
        14,Color.rgb(234,247,245),false),4);
    card(hero);

    button("شروع جستجوی مشتریان و گروه‌ها",this::startSalesDiscovery);
    button("ورود منابع سایت از اکسل",this::requestUnifiedImport);
    button("خروجی کامل Excel: شماره‌ها، گروه‌ها، منابع و وضعیت",this::requestCombinedXlsxExport);

    LinearLayout automaticCard=newCard();
    automaticCard.addView(text("جستجوی خودکار وب هر ۱۵ دقیقه",17,true));
    CheckBox webToggle=new CheckBox(this);
    webToggle.setText("جستجوی وب در پس‌زمینه فعال باشد");
    webToggle.setMinHeight(dp(52));
    webToggle.setChecked(getSharedPreferences("discovery",MODE_PRIVATE)
        .getBoolean("periodic",true));
    webToggle.setOnCheckedChangeListener((v,enabled)->setAutomaticDiscovery(enabled));
    automaticCard.addView(webToggle);
    automaticCard.addView(text("پس از فعال‌سازی یک جستجوی فوری درخواست می‌شود؛ سپس تقریباً هر ۱۵ دقیقه. حداکثر ۳ سایت مستقل هم‌زمان بررسی می‌شوند و صفحات تماس اولویت دارند. منابع Excel هم چرخشی پردازش می‌شوند. زمان دقیق به اندروید بستگی دارد.",12,false));
    card(automaticCard);

    LinearLayout telegramCard=newCard();
    telegramCard.addView(text("جستجوی خودکار گروه‌های تلگرام",17,true));
    CheckBox telegramToggle=new CheckBox(this);
    telegramToggle.setText("در پس‌زمینه حتی وقتی برنامه باز نیست");
    telegramToggle.setMinHeight(dp(52));
    telegramToggle.setChecked(TelegramBackgroundSchedule.enabled(this));
    telegramToggle.setOnCheckedChangeListener((v,enabled)->setBackgroundTelegram(enabled));
    telegramCard.addView(telegramToggle);
    telegramCard.addView(text(
        "با اجازهٔ شما و حساب آمادهٔ TDLib، اولین جستجوی گروه عمومی فوراً در صف قرار می‌گیرد؛ سپس تقریباً هر ۱۵ دقیقه یک عبارت بررسی می‌شود. زمان دقیق به اندروید بستگی دارد. عضویت و ارسال خودکار انجام نمی‌شود.",
        12,false));
    card(telegramCard);

    LinearLayout stats=ElectricUi.row(this);
    LinearLayout contactStat=newCard();
    contactStat.addView(ElectricUi.text(this,
        String.valueOf(store.count("records")),27,ElectricUi.TEAL,true));
    contactStat.addView(text("شمارهٔ تجاری ذخیره‌شده",12,false));
    LinearLayout groupStat=newCard();
    long groupCount=new DiscoveryData(store).total("public_groups");
    groupStat.addView(ElectricUi.text(this,String.valueOf(groupCount),27,ElectricUi.TEAL,true));
    groupStat.addView(text("گروه عمومی ذخیره‌شده",12,false));
    stats.addView(contactStat,new LinearLayout.LayoutParams(0,-2,1f));
    LinearLayout.LayoutParams groupWidth=new LinearLayout.LayoutParams(0,-2,1f);
    groupWidth.setMargins(dp(10),0,0,0);
    stats.addView(groupStat,groupWidth);
    ElectricUi.addBlock(this,page,stats,12);

    note("منابع وب واردشده با اکسل: "+new DiscoveryData(store).importedSourceCount()+" سایت");
    ElectricUi.section(this,page,"جستجوی مستقل وب و تلگرام، جدا از صف پیام",null);
    int waitingMessages=0;
    for(JSONObject item:ScheduledQueue.snapshot(getApplicationContext()))
      if("PENDING".equals(item.optString("status")))waitingMessages++;
    note("صف پیام: "+(ScheduledQueue.enabled(this)?"فعال":"متوقف")+" | پیام تأییدشدهٔ منتظر: "+waitingMessages+" | هر پیام فقط یک نوبت. شماره یا گروهِ کشف‌شده خودکار به گیرندگان اضافه نمی‌شود.");
    ElectricUi.section(this,page,"دسترسی سریع",null);
    quickAction("▣","مخاطبان و شماره‌های تماس",
        "دیدن نتایج و جستجو در شماره‌های ذخیره‌شده",()->draw(2));
    quickAction("◈","گروه‌های تلگرام",
        "کشف و مرور گروه‌های عمومی؛ بدون عضویت خودکار",this::openTelegramBrowser);
    quickAction("⇩","خروجی اکسل",
        "دریافت شماره‌ها، گروه‌ها و گزارش صف",this::requestCombinedXlsxExport);
    quickAction("◷","صف و ارسال به گروه‌ها",
        "فقط پیام‌هایی که خودت تأیید کرده‌ای",this::openScheduledQueue);

    LinearLayout statusCard=newCard();
    statusCard.addView(text("وضعیت چهار بخش",17,true));
    liveStatus=ElectricUi.text(this,liveSummary(),12,ElectricUi.INK,false);
    ElectricUi.addBlock(this,statusCard,liveStatus,6);
    statusCard.addView(text("گزارش جستجو",17,true));
    discoveryUi=new DiscoveryUi(this,statusCard,null,true);
    card(statusCard);
    note("موتور وب و شماره‌های تجاری، موتور گروه‌های عمومی تلگرام و صف پیام سه بخش جدا هستند؛ کشف اطلاعات هیچ پیام یا عضویتی ایجاد نمی‌کند.");
  }

  private void setBackgroundTelegram(boolean enabled) {
    TelegramBackgroundSchedule.setEnabled(this,enabled);
    if(enabled && !TdlibEngine.get(this).hasCredentials())
      toast("زمان‌بندی روشن شد؛ برای جستجوی تلگرام ابتدا در بخش اتصال تلگرام وارد حساب شوید.");
    else toast(enabled?"اولین کاوش گروه‌ها در صف فوری قرار گرفت؛ سپس بررسی دوره‌ای انجام می‌شود. اجرای دقیق به اندروید بستگی دارد.":
        "جستجوی پس‌زمینهٔ تلگرام خاموش شد؛ گروه‌های ذخیره‌شده محفوظ‌اند.");
    if(liveStatus!=null)liveStatus.setText(liveSummary());
  }

  private void setAutomaticDiscovery(boolean enabled) {
    android.content.SharedPreferences preferences=getSharedPreferences("discovery",MODE_PRIVATE);
    preferences.edit().putBoolean("sales_auto_enabled",enabled).apply();
    if(enabled) {
      preferences.edit().putLong("sales_auto_started",0).apply();
      DiscoverySchedule.enable(this);
      ((DiscoveryApplication)getApplication()).maybeAutoDiscover();
      if(ScheduledQueue.enabled(this))QueueGapDiscovery.scheduleWeb(this);
      toast("جستجوی فوری وب درخواست شد؛ سپس حدود هر ۱۵ دقیقه منابع بررسی می‌شوند و صفحات تماس اولویت دارند.");
    } else {
      DiscoverySchedule.disable(this);
      // Switching off WEB must not cancel unrelated Telegram group discovery.
      DiscoveryCoordinator.webBackground(this).stop();
      toast("جستجوی خودکار وب خاموش شد. جستجوی تلگرام جداگانه کنترل می‌شود و اطلاعات قبلی محفوظ‌اند.");
    }
  }


  private void searchPage() {
    ElectricUi.section(this,page,"جستجو و نتیجه‌ها", "شماره‌های ذخیره‌شده همین‌جا جستجو می‌شوند.");
    EditText query=input("عبارت جست‌وجو، عنوان، شماره یا منبع");
    query.setFilters(new android.text.InputFilter[]{
        new android.text.InputFilter.LengthFilter(250)});
    button("جست‌وجو در اطلاعات ذخیره‌شده",
        ()->showRecords(query.getText().toString(),false));
    recordResults=vertical();
    page.addView(recordResults);
    showRecords("",false);
    line("یافتن موارد تازه");
    button("شروع جستجوی مشتریان و گروه‌ها",this::startSalesDiscovery);
    quickAction("◈","گروه‌های تلگرام",
        "جستجوی گروه‌های عمومی و مرور حساب",this::openTelegramBrowser);
    button("ورودی یکجای اکسل",this::requestUnifiedImport);
    button("ورود منابع سایت از اکسل",this::requestUnifiedImport);
    note("برای منابع وب: ستون «آدرس سایت» یا URL و در صورت تمایل «نام شرکت». هر ردیف یک نشانی عمومی https:// بدون رمز، لینک تلگرام یا پارامتر. منابع در جستجوی خودکار اضافه می‌شوند.");
    note("کشف فقط از منابع عمومی انجام می‌شود؛ نتایج بدون منبع معتبر وارد بانک نمی‌شوند. هیچ پیامی خودکار ارسال نمی‌شود.");
  }

  private void contactsPage(){
    LinearLayout hero=ElectricUi.hero(this);
    hero.addView(ElectricUi.text(this,"شماره‌یاب خودکار صنعت برق",23,Color.WHITE,true));
    hero.addView(ElectricUi.text(this,
        "جست‌وجوی شماره‌های تماس عمومی شرکت‌ها؛ ذخیرهٔ خودکار بدون فرم ورود دستی.",
        14,Color.rgb(228,246,245),false));
    card(hero);
    button("شروع جست‌وجوی خودکار شماره‌های جدید",this::startContactsDiscovery);
    button("ورودی یکجای اکسل",this::requestUnifiedImport);
    quickAction("⌕","کاوش هم‌زمان مخاطبان و گروه‌ها","شروع مستقیم جست‌وجوی ترکیبی",this::startSalesDiscovery);
    quickAction("◈","گروه‌های عمومی کشف‌شده","مشاهده و خروجی اطلاعات گروه‌های ذخیره‌شده",this::openTelegramBrowser);
    long discoveredGroups=0;
    try(android.database.Cursor groups=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM public_groups",null)){
      if(groups.moveToFirst())discoveredGroups=groups.getLong(0);
    }
    note("ورودی ذخیره‌شده: "+store.count("records")+" مخاطب تجاری و "+discoveredGroups+
         " گروه عمومی. کاوش خودکار بین پیام‌های مجاز نیز این فهرست‌ها را تکمیل می‌کند.");
    line("بانک مخاطبان آفلاین");
    note("مشاهده، جست‌وجو، حذف و خروجی اکسل مخاطبان ذخیره‌شده کاملاً بدون Exa یا اینترنت انجام می‌شود.");
    EditText offlineQuery=input("جست‌وجوی آفلاین نام، شماره یا دسته‌بندی");
    button("جست‌وجوی آفلاین در شماره‌های ذخیره‌شده",()->{
      showRecords(offlineQuery.getText().toString(),true);
      offlineMessage="نتایج آفلاین از حافظهٔ همین گوشی خوانده شدند.";
      if(offlineStatus!=null)offlineStatus.setText(offlineMessage);
    });
    button("نمایش همهٔ شماره‌های ذخیره‌شده",()->{
      offlineQuery.setText("");
      showRecords("",true);
      offlineMessage="همهٔ رکوردهای ذخیره‌شده نمایش داده شدند.";
      if(offlineStatus!=null)offlineStatus.setText(offlineMessage);
    });
    button("خروجی اکسل",this::requestContactsXlsxExport);
    offlineStatus=text("مجموع ذخیره‌شده: "+store.count("records")+"\n"+offlineMessage,13,false);
    page.addView(offlineStatus);
    line("شماره‌های ذخیره‌شده");
    note("هر شماره همراه نام کسب‌وکار و لینک منبع ذخیره می‌شود. رکوردهای قبلی حذف نمی‌شوند.");
    recordResults=vertical();
    page.addView(recordResults);
    showRecords("",true);
    ElectricUi.section(this,page,"وضعیت زندهٔ جست‌وجوی مخاطبان",null);
    discoveryUi=new DiscoveryUi(this,page,()->{
      if(section==2 && recordResults!=null)showRecords(offlineQuery.getText().toString(),true);
    },true);
  }

  private String normalizeDigits(String input) {
    String fa="۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩";
    String en="01234567890123456789";
    StringBuilder out=new StringBuilder();
    for(int i=0;i<input.length();i++){
      char c=input.charAt(i);int at=fa.indexOf(c);
      out.append(at>=0?en.charAt(at):c);
    }
    return out.toString().replace(" ","").replace("-","");
  }

  private void showRecords(String query,boolean allowDelete) {
    recordResults.removeAllViews();
    showRecordPage(query,allowDelete,0);
  }
  private void showRecordPage(String query,boolean allowDelete,int offset){
    List<Record> records=store.find(query,offset,51);
    if(records.isEmpty() && offset==0){
      LinearLayout empty=newCard();
      empty.addView(text("هیچ نتیجه‌ای در حافظه پیدا نشد.",15,true));
      empty.addView(text("برای دریافت شمارهٔ جدید، جست‌وجوی آنلاین را اجرا کن؛ بانک محلی آفلاین است.",13,false));
      // Preserve existing explanatory wording for old tests and user familiarity.
      empty.addView(text("نتیجه‌ای در حافظه ثبت نشده است.",13,false));
      ElectricUi.addBlock(this,recordResults,empty,9);
      return;
    }
    boolean more=records.size()>50;
    if(more)records=records.subList(0,50);
    for(Record record:records){
      LinearLayout item=newCard();
      item.addView(ElectricUi.text(this,"◈ "+record.title,16,ElectricUi.INK,true));
      TextView number=ElectricUi.text(this,record.phone.isEmpty()?"شماره ثبت نشده":
          record.phone,18,ElectricUi.TEAL,true);
      number.setTextIsSelectable(true);
      ElectricUi.addBlock(this,item,number,3);
      TextView details=ElectricUi.text(this,
          "دسته‌بندی: "+record.category+"\nمنبع: "+record.source+
          "\nنوع منبع: "+record.provider+" | دریافت: "+formatTime(record.retrieved),
          12,ElectricUi.MUTED,false);
      details.setVisibility(View.GONE);
      Button detailsToggle=ElectricUi.button(this,"مشاهده منبع و جزئیات",()->{
          details.setVisibility(details.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);
      },true,false);
      ElectricUi.addBlock(this,item,detailsToggle,5);
      ElectricUi.addBlock(this,item,details,3);
      if(allowDelete)ElectricUi.addBlock(this,item,
            ElectricUi.button(this,"حذف «"+record.title+"»",()->
              new AlertDialog.Builder(this).setMessage("این مورد حذف شود؟")
                .setNegativeButton("انصراف",null).setPositiveButton("حذف",(d,w)->{
                  store.deleteRecord(record.id);draw(2);
                }).show(),true,true),9);
      ElectricUi.addBlock(this,recordResults,item,9);
    }
    if(more) {
      Button moreBtn=ElectricUi.button(this,"نمایش ۵۰ نتیجهٔ بعدی",()->{},true,false);
      moreBtn.setOnClickListener(v->{
        recordResults.removeView(moreBtn);
        showRecordPage(query,allowDelete,offset+50);
      });
      ElectricUi.addBlock(this,recordResults,moreBtn,9);
    }
  }

  private void queuePage(){
    LinearLayout intro=ElectricUi.hero(this);
    intro.addView(ElectricUi.text(this,"پیام‌ها تحت کنترل شما",22,Color.WHITE,true));
    intro.addView(ElectricUi.text(this,
        "پیش‌نویس با ارسال خودکار متفاوت است؛ هر پیام زمان‌بندی‌شده تأیید مستقل می‌خواهد.",
        14,Color.rgb(228,246,245),false));
    card(intro);
    quickAction("◷","صف زمان‌بندی‌شده گروه‌های مجاز","مشاهده، شروع و توقف صف",this::openScheduledQueue);
    quickAction("◈","حساب شخصی TDLib","اتصال و ارسال آزمایشی",this::openTdlib);
    quickAction("⌕","مرور همهٔ چت‌ها","مشاهدهٔ گروه‌ها، کانال‌ها و گفتگوهای قابل‌دسترسی",this::openTelegramBrowser);
    quickAction("♙","آزمایش Bot API","ارسال آزمایشی با تأیید دستی",this::openTelegramTest);
    line("پیش‌نویس جدید");
    LinearLayout form=newCard();
    EditText dest=ElectricUi.field(this,"نام مقصد مجاز (برای ثبت پیش‌نویس)",false);
    EditText message=ElectricUi.field(this,"متن پیش‌نویس",true);
    ElectricUi.addBlock(this,form,dest,7);
    ElectricUi.addBlock(this,form,message,9);
    ElectricUi.addBlock(this,form,ElectricUi.button(this,"ذخیره پیش‌نویس",()->{
      if(dest.getText().toString().trim().isEmpty()||message.getText().toString().trim().isEmpty()){
        toast("مقصد و متن را وارد کن");return;
      }
      store.addDraft(dest.getText().toString().trim(),message.getText().toString().trim());
      toast("پیش‌نویس ذخیره شد؛ هیچ پیامی ارسال نشده است");draw(3);
    }),10);
    card(form);
    line("پیش‌نویس‌های ذخیره‌شده");
    for(Draft d:store.drafts()){
      LinearLayout item=newCard();
      item.addView(text(d.destination,16,true));
      item.addView(text(d.message+"\nوضعیت: پیش‌نویس — ارسال غیرفعال",13,false));
      ElectricUi.addBlock(this,item,ElectricUi.button(this,"حذف پیش‌نویس",()->
          new AlertDialog.Builder(this).setMessage("این پیش‌نویس حذف شود؟")
            .setNegativeButton("انصراف",null)
            .setPositiveButton("حذف",(dialog,which)->{store.deleteDraft(d.id);draw(3);})
            .show(),true,true),9);
      card(item);
    }
    if(store.count("drafts")==0)note("هیچ پیش‌نویسی ذخیره نشده است.");
  }

  private void settingsPage(){
    ElectricUi.section(this,page,"وضعیت و تنظیمات","اطلاعات برنامه و انتخاب‌های شما");
    LinearLayout info=newCard();
    info.addView(text("تلگولی  •  نسخه "+BuildConfig.VERSION_NAME,16,true));
    info.addView(text("برنامهٔ بومی اندروید؛ بدون Termux، WebView یا سرور محلی",13,false));
    info.addView(ElectricUi.badge(this,
          getSharedPreferences("exa_ui_settings",MODE_PRIVATE)
              .getBoolean("online_enabled",false)
            ? "Exa روشن (دستی)" : "Exa خاموش (آفلاین)",
          ElectricUi.TEAL,ElectricUi.LIGHT_TEAL));
    card(info);
    quickAction("◎","تحقیق تکمیلی اختیاری","تنظیم کلید شخصی Exa؛ برای کشف پایه لازم نیست",this::openExa);
    button("منابع عمومی اختیاری",()->DiscoveryUi.sources(this));
    button("ورود منابع سایت از اکسل",this::requestUnifiedImport);
    button("خروجی کامل Excel: شماره‌ها، گروه‌ها، منابع و وضعیت",this::requestCombinedXlsxExport);
    CheckBox telegramBackground=new CheckBox(this);
    telegramBackground.setText("جستجوی خودکار گروه‌های تلگرام در پس‌زمینه");
    telegramBackground.setMinHeight(dp(52));
    telegramBackground.setChecked(TelegramBackgroundSchedule.enabled(this));
    telegramBackground.setOnCheckedChangeListener((v,on)->setBackgroundTelegram(on));
    page.addView(telegramBackground);
    note("مستقل از جستجوی وب است. نیاز به یک‌بار ورود به حساب تلگرام دارد؛ هر بار یک عبارت گروه عمومی بدون ارسال پیام بررسی می‌شود.");
    button("ورود مستقیم کلیدواژه‌ها از Excel",this::requestKeywordImport);

    CheckBox automatic=new CheckBox(this);
    automatic.setText("جستجوی خودکار وب هر ۱۵ دقیقه");
    automatic.setMinHeight(dp(52));
    automatic.setChecked(getSharedPreferences("discovery",MODE_PRIVATE)
        .getBoolean("periodic",true));
    automatic.setOnCheckedChangeListener((v,on)->setAutomaticDiscovery(on));
    page.addView(automatic);
    note("موتور مستقل وب و شماره‌یاب در پس‌زمینه با WorkManager اجرا می‌شود؛ اجرای دقیق چرخهٔ ۱۵ دقیقه‌ای تضمین نمی‌شود. موتور گروه‌های عمومی تلگرام نیز مستقل است و به حساب TDLib قبلاً واردشده نیاز دارد.");
    button("تنظیمات منابع و محدودیت جستجو",()->DiscoveryUi.sources(this));
    android.content.SharedPreferences schedule=getSharedPreferences("discovery",MODE_PRIVATE);
    note("آخرین تلاش: "+formatTime(schedule.getLong("last_attempt",0))+" | آخرین دریافت موفق: "+formatTime(schedule.getLong("last_success",0))+" | شمارهٔ جدید: "+schedule.getInt("last_new",0));
    button("پشتیبان‌گیری مخاطبان و پیش‌نویس‌ها",()->requestBackup(false));
    button("بازیابی پشتیبان بدون حذف داده‌ها",()->requestBackup(true));
    quickAction("◈","اتصال تلگرام","ورود امن، مدیریت حساب و گروه‌ها",this::openTdlib);
    quickAction("⌕","مرور همهٔ چت‌های تلگرام","جست‌وجوی عمومی و مشاهدهٔ پیام‌های حساب",this::openTelegramBrowser);
    note("صف WorkManager محدود به پیام‌های تأییدشده است و اجرای دقیق هر ۸ دقیقه تضمین نمی‌شود.");
    note("اطلاعات مخاطبان در پایگاه SQLite خصوصی همین دستگاه ذخیره می‌شوند.");
    line("مدیریت داده‌های محلی");
    ElectricUi.addBlock(this,page,ElectricUi.button(this,"پاک‌کردن همهٔ داده‌های محلی",()->
      new AlertDialog.Builder(this).setTitle("حذف دائمی داده‌ها")
        .setMessage("تمام مخاطبان و پیش‌نویس‌های ذخیره‌شده حذف می‌شوند. ادامه می‌دهی؟")
        .setNegativeButton("انصراف",null)
        .setPositiveButton("حذف",(d,w)->{DiscoveryCoordinator.get(this).stop();DiscoverySchedule.disable(this);store.clearAll();draw(4);toast("داده‌ها حذف شدند");})
        .show(),true,true),10);
  }


  private String formatTime(long time) {return time==0?"هنوز اجرا نشده":android.text.format.DateFormat.format("yyyy/MM/dd HH:mm",time).toString();}

  static final class Record {
    long id; String title, phone, category, source, provider="قدیمی / دستی";long retrieved;
    Record(long id,String title,String phone,String category,String source) {
      this.id=id;this.title=title;this.phone=phone;this.category=category;this.source=source;
    }
  }
  static final class Draft {
    long id; String destination, message;
    Draft(long id,String destination,String message) {
      this.id=id;this.destination=destination;this.message=message;
    }
  }
  static final class Store extends SQLiteOpenHelper {
    Store(Context context) {super(context, "electric_connect_native_free.db", null, 6);}
    @Override public void onCreate(SQLiteDatabase db) {
      db.execSQL("CREATE TABLE records (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, phone TEXT NOT NULL DEFAULT '', category TEXT NOT NULL DEFAULT '', source TEXT NOT NULL DEFAULT '', search_text TEXT NOT NULL DEFAULT '', canonical_phone TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL, UNIQUE(title, phone))");
      db.execSQL("CREATE TABLE drafts (id INTEGER PRIMARY KEY AUTOINCREMENT, destination TEXT NOT NULL, message TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'DRAFT', created INTEGER NOT NULL)");
      db.execSQL("CREATE INDEX idx_record_title ON records(title)");
      db.execSQL("CREATE INDEX idx_record_category ON records(category)");
      db.execSQL("CREATE INDEX idx_record_canonical_phone ON records(canonical_phone)");
      db.execSQL("CREATE INDEX idx_drafts_created ON drafts(created)");
      addDiscoveryColumns(db);
      DiscoveryData.create(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
      if (oldVersion < 2) {
        db.execSQL("ALTER TABLE records ADD COLUMN search_text TEXT NOT NULL DEFAULT ''");
        try (Cursor rows = db.rawQuery("SELECT id,title,phone,category,source FROM records", null)) {
          while (rows.moveToNext()) {
            ContentValues values = new ContentValues();
            values.put("search_text", SearchText.normalize(rows.getString(1) + " " + rows.getString(2)
                + " " + rows.getString(3) + " " + rows.getString(4)));
            db.update("records", values, "id=?", new String[]{rows.getString(0)});
          }
        }
      }
      if(oldVersion<3) {addDiscoveryColumns(db);DiscoveryData.create(db);}
      if(oldVersion<4) DiscoveryData.create(db);
      if(oldVersion<5) DiscoveryData.create(db);
      if(oldVersion<6) {
        // Preserve every legacy row, including differently formatted duplicate phones.
        // Only add a non-unique index, so migrations never discard user data.
        db.execSQL("ALTER TABLE records ADD COLUMN canonical_phone TEXT NOT NULL DEFAULT ''");
        try(Cursor cursor=db.rawQuery("SELECT id,phone FROM records WHERE phone<>''",null)) {
          while(cursor.moveToNext()) {
            String canonical=PublicPhoneDiscovery.normalizeIranPhone(cursor.getString(1));
            if(canonical==null)continue;
            ContentValues values=new ContentValues();
            values.put("canonical_phone",canonical);
            db.update("records",values,"id=?",new String[]{Long.toString(cursor.getLong(0))});
          }
        }
        db.execSQL("CREATE INDEX idx_record_canonical_phone ON records(canonical_phone)");
      }
    }
    private static void addDiscoveryColumns(SQLiteDatabase db) {
      db.execSQL("ALTER TABLE records ADD COLUMN provider TEXT NOT NULL DEFAULT 'قدیمی / دستی'");
      db.execSQL("ALTER TABLE records ADD COLUMN retrieved INTEGER NOT NULL DEFAULT 0");
    }
    long count(String table) {
      if (!"records".equals(table) && !"drafts".equals(table)) return 0;
      Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + table,null);
      try { return cursor.moveToFirst()?cursor.getLong(0):0; } finally {cursor.close();}
    }
    /**
     * Preserve old records but deduplicate new public business contacts by
     * canonical phone, including older manual records with Persian digits.
     * Contacts with no phone are never created by automatic discovery.
     */
    synchronized boolean insertPublicBusinessPhone(String title,String phone,String source){
      return insertPublicBusinessPhone(title,phone,source,"وب‌سایت عمومی");
    }
    synchronized boolean insertPublicBusinessPhone(String title,String phone,String source,String provider){
      String canonical=PublicPhoneDiscovery.normalizeIranPhone(phone);
      if(canonical==null || title==null || title.trim().length()<2
          || PublicPhoneDiscovery.canonicalSource(source)==null)return false;
      SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
      try {
      // Indexed lookup rather than re-normalizing the entire historic database
      // for every newly discovered business number.
      try(Cursor cursor=db.rawQuery(
          "SELECT 1 FROM records WHERE canonical_phone=? LIMIT 1",
          new String[]{canonical})) {
        if(cursor.moveToFirst())return false;
      }
      boolean inserted=insertRecord(title.trim(),canonical,"تلفن عمومی کسب‌وکار صنعت برق",source);
      if(inserted) {
        ContentValues provenance=new ContentValues();provenance.put("provider",provider);
        provenance.put("retrieved",System.currentTimeMillis());
        getWritableDatabase().update("records",provenance,"title=? AND phone=?",new String[]{title.trim(),canonical});
      }
      db.setTransactionSuccessful();return inserted;
      }finally {db.endTransaction();}
    }
    boolean insertRecord(String title,String phone,String category,String source) {
      ContentValues values = new ContentValues();
      values.put("title",title);values.put("phone",phone);
      String canonical=PublicPhoneDiscovery.normalizeIranPhone(phone);
      values.put("canonical_phone",canonical==null?"":canonical);
      values.put("category",category);values.put("source",source);
      values.put("search_text", SearchText.normalize(title + " " + phone + " " + category + " " + source));
      values.put("created",System.currentTimeMillis());
      return getWritableDatabase().insertWithOnConflict("records",null,values,SQLiteDatabase.CONFLICT_IGNORE)!=-1;
    }
    List<Record> find(String input, int offset, int limit) {
      StringBuilder where = new StringBuilder("1=1");
      ArrayList<String> args = new ArrayList<>();
      for (String term : SearchText.terms(input)) {
        where.append(" AND search_text LIKE ? ESCAPE '\\'");
        args.add(SearchText.likePattern(term));
      }
      args.add(Integer.toString(limit)); args.add(Integer.toString(offset));
      Cursor cursor = getReadableDatabase().rawQuery(
        "SELECT id,title,phone,category,source,provider,retrieved FROM records WHERE " + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
        args.toArray(new String[0]));
      List<Record> result = new ArrayList<>();
      try {while(cursor.moveToNext()) {
        Record record=new Record(cursor.getLong(0),cursor.getString(1),cursor.getString(2),cursor.getString(3),cursor.getString(4));
        record.provider=cursor.getString(5);record.retrieved=cursor.getLong(6);result.add(record);
      }} finally {cursor.close();}
      return result;
    }
    void deleteRecord(long id) {getWritableDatabase().delete("records","id=?",new String[]{Long.toString(id)});}
    void addDraft(String destination,String message) {
      ContentValues values=new ContentValues();
      values.put("destination",destination); values.put("message",message);
      values.put("created",System.currentTimeMillis());
      getWritableDatabase().insertOrThrow("drafts",null,values);
    }
    List<Draft> drafts() {
      Cursor c=getReadableDatabase().rawQuery("SELECT id,destination,message FROM drafts ORDER BY id DESC LIMIT 100",null);
      List<Draft> rows=new ArrayList<>();
      try { while(c.moveToNext()) rows.add(new Draft(c.getLong(0),c.getString(1),c.getString(2))); }
      finally {c.close();} return rows;
    }
    void deleteDraft(long id) {getWritableDatabase().delete("drafts","id=?",new String[]{Long.toString(id)});}
    void clearAll() {
      SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
      try {db.delete("records",null,null);db.delete("drafts",null,null);db.delete("public_groups",null,null);db.delete("imported_groups",null,null);db.delete("imported_web_sources",null,null);db.delete("discovery_urls",null,null);db.delete("discovery_state",null,null);db.setTransactionSuccessful();}
      finally {db.endTransaction();}
    }
  }
}
