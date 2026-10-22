package ir.electricconnect.nativefree;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import android.os.Bundle;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * On-device TDLib browser for ALL available chats in an authenticated account.
 * Lists private chats, bots, groups, channels and archived conversations;
 * message text is fetched only after a user search or opening a chat.
 * No automatic participant discovery, phone collection or export of messages.
 */
public final class TelegramBrowserActivity extends Activity
        implements TdlibEngine.BrowserCallback {
    private TdlibEngine engine;
    private TextView status, indexCount, historyTitle;
    private EditText chatFilter, publicQuery, messageQuery;
    private LinearLayout chatList, publicGroups, importedGroupList, globalMessages, history;
    private List<TdlibEngine.ChatOverview> chats=new ArrayList<>();
    private int displayed=80;
    private int chatTypeFilter=0;
    private static final String[] CHAT_FILTERS={"همهٔ چت‌ها","فقط گروه‌ها","فقط کانال‌ها","فقط اشخاص","فقط ربات‌ها"};
    private long chosenChat=0;
    private long historyOffset=0;
    private static final int REQUEST_EXPORT_GROUPS=7342;
    private TextView exportStatus;
    private Button olderMessagesButton,moreSearchResultsButton;
    private final Set<Long> historySeen=new HashSet<>();
    private final Set<String> searchSeen=new HashSet<>();
    private String nextSearchOffset="";
    private int totalHistoryMessages;
    private boolean refreshRequested;
    private String currentPublicQuery="";
    private String currentMessageQuery="";
    private final Map<Long,TdlibEngine.ChatOverview> discovered=new LinkedHashMap<>();
    private DiscoveryUi discoveryUi;
    private DiscoveryData discoveryData;
    private final java.util.concurrent.ExecutorService discoveryIo=java.util.concurrent.Executors.newSingleThreadExecutor();
    // Serialize every database read/write against Activity teardown. SQLiteOpenHelper.close()
    // does not permanently disable the helper, so tasks must never reopen it after onDestroy.
    private final Object discoveryDatabaseLock=new Object();
    private volatile boolean discoveryClosed;
    private final android.os.Handler groupsRefreshHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshSavedGroups=new Runnable() {
        @Override public void run() {
            if(discoveryClosed || isFinishing() || isDestroyed())return;
            loadSavedGroups();
            groupsRefreshHandler.postDelayed(this,30000L);
        }
    };

    @Override public void onCreate(Bundle savedInstance) {
        super.onCreate(savedInstance);
        ElectricUi.styleWindow(this);
        setTitle("تلگولی | جست‌وجوی همهٔ چت‌ها");
        engine=TdlibEngine.get(this);
        discoveryData=new DiscoveryData(new MainActivity.Store(getApplicationContext()));
        ScrollView scroller=new ScrollView(this);
        LinearLayout root=ElectricUi.column(this);
        root.setBackgroundColor(ElectricUi.BG);
        root.setPadding(dp(16),dp(12),dp(16),dp(28));
        scroller.addView(root);
        setContentView(scroller);

        LinearLayout hero=ElectricUi.hero(this);
        hero.addView(ElectricUi.text(this,"همهٔ چت‌های تلگرام",23,Color.WHITE,true));
        hero.addView(ElectricUi.text(this,
            "شخصی، گروه‌ها، کانال‌ها و آرشیو؛ جست‌وجوی عمومی گروه‌های صنعت برق",
            14,Color.rgb(227,245,244),false));
        ElectricUi.addBlock(this,root,hero,2);
        status=ElectricUi.text(this,"در انتظار اتصال حساب تلگرام...",14,ElectricUi.INK,true);
        status.setPadding(dp(14),dp(12),dp(14),dp(12));
        status.setBackground(ElectricUi.outlined(this,ElectricUi.WHITE,13));
        ElectricUi.addBlock(this,root,status,12);
        ElectricUi.addBlock(this,root,ElectricUi.button(this,"پروکسی تلگرام",()->TelegramProxyUi.show(this,engine,status),true,false),8);
        ElectricUi.addBlock(this,root,
            ElectricUi.button(this,"بازخوانی همهٔ چت‌های حساب",()->{
                refreshRequested=true;
                status.setText("در حال بازخوانی فهرست تلگرام...");
                engine.refresh();
            }),10);

        ElectricUi.section(this,root,"۱. چت‌های حساب من",
            "فهرست گروه‌ها، چت‌های شخصی، ربات‌ها، کانال‌ها و آرشیو از TDLib بارگذاری می‌شود.");
        chatFilter=ElectricUi.field(this,"جست‌وجوی نام چت، گروه، کانال یا شناسه",false);
        ElectricUi.addBlock(this,root,chatFilter,9);
        chatFilter.addTextChangedListener(watch(this::renderChats));
        Spinner typeSelector=new Spinner(this);
        ArrayAdapter<String> typeAdapter=new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,CHAT_FILTERS);
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        typeSelector.setAdapter(typeAdapter);
        typeSelector.setContentDescription("فیلتر نوع چت");
        typeSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                chatTypeFilter=position;displayed=80;renderChats();
            }
            @Override public void onNothingSelected(AdapterView<?> parent){}
        });
        ElectricUi.addBlock(this,root,typeSelector,6);
        indexCount=ElectricUi.text(this,"هنوز فهرستی بارگذاری نشده است.",13,ElectricUi.MUTED,false);
        ElectricUi.addBlock(this,root,indexCount,6);
        chatList=ElectricUi.column(this);
        ElectricUi.addBlock(this,root,chatList,5);

        ElectricUi.section(this,root,"۲. گروه‌های عمومی صنعت برق","نتایج ذخیره‌شده پس از بستن برنامه هم باقی می‌مانند؛ عضویت خودکار انجام نمی‌شود.");
        android.widget.CheckBox backgroundGroups=new android.widget.CheckBox(this);
        backgroundGroups.setText("جستجوی خودکار گروه‌های عمومی حتی وقتی برنامه بسته است");
        backgroundGroups.setMinHeight(dp(48));
        backgroundGroups.setChecked(TelegramBackgroundSchedule.enabled(this));
        backgroundGroups.setOnCheckedChangeListener((button,enabled)->{
            TelegramBackgroundSchedule.setEnabled(this,enabled);
            status.setText(enabled?"جستجوی دوره‌ای در پس‌زمینه ثبت شد؛ اتصال آمادهٔ TDLib لازم است.":
                "جستجوی گروه‌های تلگرام در پس‌زمینه خاموش شد.");
        });
        ElectricUi.addBlock(this,root,backgroundGroups,7);
        ElectricUi.addBlock(this,root,ElectricUi.text(this,
            "هر حدود ۱۵ دقیقه یک عبارت عمومی با حساب قبلاً واردشده بررسی می‌شود؛ اندروید ممکن است اجرا را عقب بیندازد. "+
            TelegramBackgroundSchedule.describe(this),12,ElectricUi.MUTED,false),7);
        ElectricUi.addBlock(this,root,
            ElectricUi.button(this,"شروع جست‌وجوی خودکار چندعبارتی گروه‌ها",()->{
                if(!engine.isReady()) {
                    status.setText("ابتدا حساب تلگرام را به حالت READY برسانید.");
                    return;
                }
                boolean started=DiscoveryCoordinator.get(this).start(1,false);
                status.setText(started?"کشف خودکار با چندین کلیدواژه آغاز شد؛ نتایج ذخیره می‌شوند.":
                    "جست‌وجوی خودکار در حال اجراست؛ نتایج را در خانه ببینید.");
            }),9);
        publicGroups=ElectricUi.column(this);
        ElectricUi.addBlock(this,root,publicGroups,6);
        ElectricUi.section(this,root,"گروه‌های ورودی اکسل","فقط از فایل شما ثبت شده‌اند؛ عضویت و مجوز ارسال تأیید نشده است.");
        importedGroupList=ElectricUi.column(this);
        ElectricUi.addBlock(this,root,importedGroupList,7);
        ElectricUi.section(this,root,"خروجی اکسل گروه‌ها",
            "گروه‌های عضو حساب و گروه‌های عمومی کشف‌شده؛ بدون پیام و شمارهٔ اعضا.");
        ElectricUi.addBlock(this,root,ElectricUi.button(this,
            "خروجی اکسل",this::requestGroupsXlsxExport),9);
        exportStatus=ElectricUi.text(this,
            "برای خروجی، گروه‌ها را بارگذاری کن؛ فایل بدون اینترنت ساخته می‌شود.",
            13,ElectricUi.MUTED,false);
        ElectricUi.addBlock(this,root,exportStatus,8);

        ElectricUi.section(this,root,"۳. جست‌وجو در پیام‌های همهٔ چت‌ها",
            "جست‌وجوی دستی در پیام‌های قابل‌دسترسی حساب؛ هر مرحله حداکثر ۳۰ نتیجه؛ با امکان نمایش بیشتر. چت محرمانه مستثناست.");
        messageQuery=ElectricUi.field(this,"موضوع پیام، نام کالا یا عبارت موردنظر",false);
        ElectricUi.addBlock(this,root,messageQuery,8);
        ElectricUi.addBlock(this,root,
            ElectricUi.button(this,"جست‌وجو در پیام‌های همهٔ چت‌ها",()->{
                currentMessageQuery=messageQuery.getText().toString().trim();
                nextSearchOffset="";
                searchSeen.clear();
                globalMessages.removeAllViews();
                moreSearchResultsButton.setEnabled(false);
                status.setText("در حال جست‌وجوی پیام‌های تلگرام...");
                engine.searchAllMessages(currentMessageQuery,this);
            }),9);
        globalMessages=ElectricUi.column(this);
        ElectricUi.addBlock(this,root,globalMessages,6);
        moreSearchResultsButton=ElectricUi.button(this,"نمایش پیام‌های بیشتر",()->{
            if(nextSearchOffset==null||nextSearchOffset.isEmpty())return;
            moreSearchResultsButton.setEnabled(false);
            status.setText("در حال دریافت صفحهٔ بعدی پیام‌ها...");
            engine.searchAllMessages(currentMessageQuery,nextSearchOffset,this);
        },true,false);
        moreSearchResultsButton.setEnabled(false);
        ElectricUi.addBlock(this,root,moreSearchResultsButton,8);

        ElectricUi.section(this,root,"۴. مشاهدهٔ تاریخچهٔ چت انتخاب‌شده",
            "با انتخاب یک چت از فهرست بالا، جدیدترین پیام‌های مجاز آن نمایش داده می‌شود.");
        historyTitle=ElectricUi.text(this,"هنوز چتی انتخاب نشده است.",14,ElectricUi.MUTED,true);
        ElectricUi.addBlock(this,root,historyTitle,7);
        history=ElectricUi.column(this);
        ElectricUi.addBlock(this,root,history,7);
        olderMessagesButton=ElectricUi.button(this,"نمایش پیام‌های قدیمی‌تر",()->{
            if(chosenChat==0||historyOffset==0)return;
            olderMessagesButton.setEnabled(false);
            status.setText("در حال دریافت پیام‌های قدیمی‌تر...");
            engine.readChatHistory(chosenChat,historyOffset,this);
        },true,false);
        olderMessagesButton.setEnabled(false);
        ElectricUi.addBlock(this,root,olderMessagesButton,8);
        ElectricUi.addBlock(this,root,ElectricUi.text(this,
            "این صفحه فقط برای مشاهده و جست‌وجو است. پیام‌ها به Exa فرستاده نمی‌شوند و شمارهٔ اعضای گروه‌ها جمع‌آوری یا ذخیره نمی‌شود.",
            13,ElectricUi.MUTED,false),18);
    }

    /** Enqueue database work only while the Activity owns its SQLite helper. */
    private void enqueueDiscoveryDatabase(Runnable work) {
        if(discoveryClosed)return;
        try {
            discoveryIo.execute(()->{
                synchronized(discoveryDatabaseLock) {
                    if(discoveryClosed)return;
                    try {work.run();}
                    catch(android.database.sqlite.SQLiteException error) {
                        // A missing/full/inaccessible database must not kill the worker thread.
                        runOnUiThread(()->{
                            if(!discoveryClosed && !isFinishing() && !isDestroyed() && status!=null)
                                status.setText("بانک اطلاعات گروه‌ها باز نشد؛ فضای ذخیره‌سازی و دسترسی برنامه را بررسی کنید. اطلاعات قبلی حذف نشده‌اند.");
                        });
                    }
                }
            });
        } catch(java.util.concurrent.RejectedExecutionException ignored) {
            // Teardown has already started. Do not reopen a closed helper.
        }
    }

    private void loadSavedGroups() {
        enqueueDiscoveryDatabase(()->{
            List<TdlibEngine.ChatOverview> saved=discoveryData.groups();
            List<String[]> imported=discoveryData.importedGroupExport();
            runOnUiThread(()->{
                if(!discoveryClosed && !isFinishing() && !isDestroyed()) {
                    for(TdlibEngine.ChatOverview c:saved)discovered.put(c.id,c);
                    renderPublicGroups();
                    renderImportedGroups(imported);
                }
            });
        });
    }

    @Override protected void onDestroy() {
        discoveryClosed=true;
        groupsRefreshHandler.removeCallbacks(refreshSavedGroups);
        if(discoveryUi!=null)discoveryUi.detach();
        // Drop queued reads; wait only for an already-running short SQLite operation.
        // This prevents Robolectric test-sandbox teardown (and real Activity teardown)
        // from racing with a background getReadableDatabase() call.
        discoveryIo.shutdownNow();
        synchronized(discoveryDatabaseLock) {discoveryData.store.close();}
        super.onDestroy();
    }

    private void requestGroupsXlsxExport(){
        int count=discovered.size();
        for(TdlibEngine.ChatOverview c:chats)if("گروه".equals(c.category))count++;
        Intent picker=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType(ExcelWorkbook.MIME);
        picker.putExtra(Intent.EXTRA_TITLE,"Electric_Connect_Groups.xlsx");
        try{startActivityForResult(picker,REQUEST_EXPORT_GROUPS);}
        catch(Exception e){exportStatus.setText("انتخاب محل ذخیرهٔ فایل ممکن نشد.");}
    }

    @Override protected void onActivityResult(int code,int result,Intent data){
        super.onActivityResult(code,result,data);
        if(code!=REQUEST_EXPORT_GROUPS||result!=RESULT_OK||
           data==null||data.getData()==null)return;
        Uri destination=data.getData();
        Map<Long,String[]> groups=new LinkedHashMap<>();
        for(TdlibEngine.ChatOverview chat:chats)if("گروه".equals(chat.category))
            groups.put(chat.id,new String[]{chat.name.isEmpty()?"نام دریافت نشده":chat.name,Long.toString(chat.id),chat.archived?"در فهرست حساب (آرشیو)":"در فهرست حساب","TDLib",engine.publicGroupLink(chat.id),"", ""});
        for(TdlibEngine.ChatOverview chat:discovered.values())groups.putIfAbsent(chat.id,new String[]{chat.name,Long.toString(chat.id),"عمومی کشف‌شده؛ عضویت تأیید نشده","جست‌وجوی عمومی تلگرام",engine.publicGroupLink(chat.id),"",""});
        exportStatus.setText("در حال ذخیرهٔ "+groups.size()+" گروه...");
        new Thread(()->{
            try{
                List<String[]> storedGroups, importedRows;
                try(MainActivity.Store exportStore=new MainActivity.Store(getApplicationContext())) {
                    DiscoveryData exportData=new DiscoveryData(exportStore);
                    storedGroups=exportData.groupExport();
                    importedRows=exportData.importedGroupExport();
                }
                for(String[] saved:storedGroups) {
                    long id=Long.parseLong(saved[1]);
                    if(!groups.containsKey(id)||!groups.get(id)[2].startsWith("در فهرست حساب"))
                        groups.put(id,new String[]{saved[0],saved[1],saved[2],saved[3],engine.publicGroupLink(id),saved[4],saved[5]});
                }
                List<String[]> rows=new ArrayList<>();
                rows.add(new String[]{"نام گروه","شناسهٔ گروه","نوع / وضعیت","منبع","لینک عمومی معتبر","زمان کشف","آخرین مشاهده"});
                rows.addAll(groups.values());
                for(String[] imported:importedRows)
                    rows.add(new String[]{imported[0],imported[1],
                        "ورودی اکسل؛ عضویت تأیید نشده",imported[3],
                        imported[4],"لینک گروه: "+imported[2]});
                try(OutputStream output=getContentResolver().openOutputStream(destination,"w")){
                    if(output==null)throw new java.io.IOException("No output");
                    ExcelWorkbook.write(output,"گروه‌ها",rows);
                }
                runOnUiThread(()->{
                    if(!isFinishing()&&!isDestroyed())
                        exportStatus.setText(groups.size()+" گروه به اکسل صادر شدند.");
                });
            }catch(Exception e){
                runOnUiThread(()->{
                    if(!isFinishing()&&!isDestroyed())
                        exportStatus.setText("خروجی گروه‌ها ناموفق بود؛ محل ذخیره را بررسی کن.");
                });
            }
        },"electric-groups-export").start();
    }

    private int dp(int n){return ElectricUi.dp(this,n);}
    private TextWatcher watch(Runnable action) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){
                displayed=80;action.run();
            }
            @Override public void afterTextChanged(Editable e){}
        };
    }
    @Override protected void onResume(){
        super.onResume();
        loadSavedGroups();
        groupsRefreshHandler.removeCallbacks(refreshSavedGroups);
        groupsRefreshHandler.postDelayed(refreshSavedGroups,30000L);
        engine.attachBrowser(this);
        engine.connect();
    }
    @Override protected void onPause(){
        groupsRefreshHandler.removeCallbacks(refreshSavedGroups);
        engine.detachBrowser(this);
        super.onPause();
    }

    private void renderImportedGroups(List<String[]> items){
        if(importedGroupList==null)return;
        importedGroupList.removeAllViews();
        importedGroupList.addView(ElectricUi.text(this,
            items.size()+" گروه ورودی اکسل (اطلاعات واردشده، نه عضویت)",13,ElectricUi.TEAL,true));
        int shown=0;
        for(String[] g:items){
            if(shown++>=80)break;
            LinearLayout card=ElectricUi.card(this);
            card.addView(ElectricUi.text(this,g[0],15,ElectricUi.INK,true));
            String caption=!g[2].isEmpty()?g[2]:g[1];
            if(!caption.isEmpty())card.addView(ElectricUi.text(this,caption,12,ElectricUi.MUTED,false));
            ElectricUi.addBlock(this,importedGroupList,card,6);
        }
        if(items.size()>80)importedGroupList.addView(ElectricUi.text(this,
            "۸۰ گروه اول نمایش داده شده‌اند؛ همهٔ گروه‌ها در خروجی اکسل موجودند.",
            12,ElectricUi.MUTED,false));
    }

    private String latestConnectionState="DISCONNECTED";
    private void renderPublicGroups(){
        publicGroups.removeAllViews();
        if(discovered.isEmpty()){
            publicGroups.addView(ElectricUi.text(this,"تا این لحظه گروه عمومی جدیدی پیدا نشده است.",13,ElectricUi.MUTED,false));
            return;
        }
        publicGroups.addView(ElectricUi.text(this,
            discovered.size()+" گروه عمومی یکتا (نتایج جست‌وجو، نه عضویت حساب)",
            14,ElectricUi.TEAL,true));
        for(TdlibEngine.ChatOverview c:discovered.values()){
            LinearLayout card=ElectricUi.card(this);
            card.addView(ElectricUi.text(this,c.name,15,ElectricUi.INK,true));
            card.addView(ElectricUi.text(this,"گروه عمومی  •  "+c.id,12,ElectricUi.MUTED,false));
            ElectricUi.addBlock(this,publicGroups,card,8);
        }
    }

    static boolean matchesChatType(String category,int filter){
        switch(filter){
            case 1:return "گروه".equals(category);
            case 2:return "کانال".equals(category);
            case 3:return "شخصی".equals(category);
            case 4:return "ربات".equals(category);
            default:return true;
        }
    }

    private void renderChats(){
        if(chatList==null)return;
        chatList.removeAllViews();
        String query=chatFilter.getText().toString();
        int count=0,shown=0;
        for(TdlibEngine.ChatOverview c:chats){
            if(!matchesChatType(c.category,chatTypeFilter))continue;
            if(!SearchText.matches(c.name+" "+c.category+" "+c.id,query))continue;
            count++;
            if(shown++>=displayed)continue;
            String name=c.name==null||c.name.trim().isEmpty()?"نام دریافت نشده":c.name;
            String caption=(c.archived?"آرشیو | ":"")+c.category;
            LinearLayout card=ElectricUi.card(this);
            card.addView(ElectricUi.text(this,name,15,ElectricUi.INK,true));
            card.addView(ElectricUi.text(this,caption+"  •  "+c.id,12,ElectricUi.MUTED,false));
            card.setContentDescription("مشاهدهٔ "+name);
            card.setMinimumHeight(dp(64));
            card.setOnClickListener(v->selectChat(c));
            ElectricUi.addBlock(this,chatList,card,8);
        }
        indexCount.setText("نمایش "+Math.min(count,displayed)+" از "+count+
            " چت — "+CHAT_FILTERS[chatTypeFilter]+"؛ شناسهٔ چت شمارهٔ تلفن نیست.");
        if(count==0)chatList.addView(ElectricUi.text(this,"چتی مطابق جست‌وجو پیدا نشد.",13,ElectricUi.MUTED,false));
        if(count>displayed)ElectricUi.addBlock(this,chatList,
            ElectricUi.button(this,"نمایش چت‌های بیشتر",()->{displayed+=80;renderChats();},true,false),8);
    }

    private void selectChat(TdlibEngine.ChatOverview chat){
        chosenChat=chat.id;
        historyOffset=0;
        historySeen.clear();
        totalHistoryMessages=0;
        olderMessagesButton.setEnabled(false);
        historyTitle.setText("پیام‌های چت: "+chat.name);
        history.removeAllViews();
        history.addView(ElectricUi.text(this,"در حال خواندن پیام‌های اخیر...",13,ElectricUi.MUTED,false));
        engine.readChatHistory(chat.id,0,this);
    }
    private void showMessage(LinearLayout target,TdlibEngine.MessagePreview m,boolean includeTitle){
        LinearLayout card=ElectricUi.card(this);
        if(includeTitle)card.addView(ElectricUi.text(this,m.chatTitle,14,ElectricUi.TEAL,true));
        card.addView(ElectricUi.text(this,m.body.isEmpty()?"(متن ندارد)":m.body,14,ElectricUi.INK,false));
        card.addView(ElectricUi.text(this,"شناسهٔ پیام: "+m.messageId,11,ElectricUi.MUTED,false));
        ElectricUi.addBlock(this,target,card,8);
    }

    @Override public void onBrowserStatus(String state,String detail){
        if(status==null)return;
        status.setText(("READY".equals(state)?"حساب آماده":"وضعیت اتصال تلگرام")+"\n"+detail);
        latestConnectionState=state;
        if("READY".equals(state) && !refreshRequested) {
            refreshRequested=true;
            engine.refresh();
        } else if(!"READY".equals(state)){
            // Never show results of an old signed-out account.
            refreshRequested=false;
            if("LOGGING_OUT".equals(state)||"CLOSED".equals(state)||"CLOSING".equals(state)){
                chats.clear();renderChats();
                chosenChat=0;history.removeAllViews();
                historySeen.clear();historyOffset=0;
                olderMessagesButton.setEnabled(false);moreSearchResultsButton.setEnabled(false);
                publicGroups.removeAllViews();globalMessages.removeAllViews();
            }
        }
    }
    @Override public void onChatLoading(int loaded,boolean mainFinished,boolean archiveFinished,String error) {
        status.setText("چت‌های بارگذاری‌شده: "+loaded+" | اصلی: "+(mainFinished?"تمام شد":"در حال دریافت")+" | آرشیو: "+(archiveFinished?"تمام شد":"در حال دریافت")+(error.isEmpty()?"":"\n"+error));
    }
    @Override public void onChatIndex(List<TdlibEngine.ChatOverview> rows){
        chats=new ArrayList<>(rows);
        renderChats();
    }
    @Override public void onPublicGroups(String query,List<TdlibEngine.ChatOverview> rows,String error){
        if(!query.equals(currentPublicQuery))return;
        publicGroups.removeAllViews();
        if(error!=null){publicGroups.addView(ElectricUi.text(this,error,13,ElectricUi.RED,false));return;}
        if(rows.isEmpty())publicGroups.addView(ElectricUi.text(this,"گروه عمومی مطابق عبارت پیدا نشد.",13,ElectricUi.MUTED,false));
        for(TdlibEngine.ChatOverview c:rows){
            LinearLayout card=ElectricUi.card(this);
            card.addView(ElectricUi.text(this,c.name,15,ElectricUi.INK,true));
            card.addView(ElectricUi.text(this,"گروه عمومی  •  "+c.id,12,ElectricUi.MUTED,false));
            ElectricUi.addBlock(this,publicGroups,card,8);
        }
        for(TdlibEngine.ChatOverview c:rows)discovered.put(c.id,c);
        List<TdlibEngine.ChatOverview> savedGroups=new ArrayList<>(rows);
        enqueueDiscoveryDatabase(()->{for(TdlibEngine.ChatOverview c:savedGroups)discoveryData.saveGroup(c,query);});
        status.setText(rows.size()+" گروه عمومی مطابق عبارت دلخواه پیدا شد.");
    }
    @Override public void onGlobalMessages(String query,List<TdlibEngine.MessagePreview> rows,String error){
        onGlobalMessagesPage(query,rows,error,"");
    }
    @Override public void onGlobalMessagesPage(String query,List<TdlibEngine.MessagePreview> rows,
                                               String error,String nextOffset){
        if(!query.equals(currentMessageQuery))return;
        if(error!=null){
            status.setText(error);
            moreSearchResultsButton.setEnabled(false);
            return;
        }
        int added=0;
        for(TdlibEngine.MessagePreview m:rows)if(searchSeen.add(m.chatId+":"+m.messageId)){
            showMessage(globalMessages,m,true);added++;
        }
        nextSearchOffset=nextOffset==null?"":nextOffset;
        moreSearchResultsButton.setEnabled(!nextSearchOffset.isEmpty());
        status.setText(searchSeen.size()+" پیام بارگذاری شد"+
            (nextSearchOffset.isEmpty()?"؛ نتیجهٔ دیگری وجود ندارد.":"؛ برای ادامه دکمهٔ «نمایش پیام‌های بیشتر» را بزن."));
        if(searchSeen.isEmpty())
            globalMessages.addView(ElectricUi.text(this,
                "پیامی مطابق عبارت پیدا نشد.",13,ElectricUi.MUTED,false));
    }
    @Override public void onChatHistory(long chatId,List<TdlibEngine.MessagePreview> rows,String error){
        if(chatId!=chosenChat)return;
        if(error!=null){status.setText(error);olderMessagesButton.setEnabled(false);return;}
        if(totalHistoryMessages==0)history.removeAllViews();
        int added=0;
        for(TdlibEngine.MessagePreview m:rows)if(historySeen.add(m.messageId)){
            showMessage(history,m,false);
            totalHistoryMessages++;
            added++;
        }
        if(totalHistoryMessages==0)
            history.addView(ElectricUi.text(this,
                "پیامی برای نمایش پیدا نشد.",13,ElectricUi.MUTED,false));
        long next=rows.isEmpty()?0:rows.get(rows.size()-1).messageId;
        boolean hasMore=added>0 && next!=0 && next!=historyOffset;
        if(hasMore)historyOffset=next;
        olderMessagesButton.setEnabled(hasMore);
        historyTitle.setText("پیام‌های بارگذاری‌شده: "+totalHistoryMessages);
        status.setText(totalHistoryMessages+" پیام نمایش داده شد"+(hasMore?
            "؛ برای خواندن بیشتر دکمهٔ پیام‌های قدیمی‌تر را بزن.":"؛ صفحهٔ دیگری دریافت نشد."));
    }

}
