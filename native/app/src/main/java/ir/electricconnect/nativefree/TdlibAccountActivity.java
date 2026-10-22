package ir.electricconnect.nativefree;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
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
import java.util.List;
import java.util.ArrayList;

/** Android UI for authorized personal account TDLib session. */
public final class TdlibAccountActivity extends Activity implements TdlibEngine.Listener {
    private static final int TEAL=Color.rgb(8,103,115);
    private TdlibEngine engine;
    private TextView connectionState;
    private TextView actionResult;
    private LinearLayout groupResults, contactsResults;
    private EditText appId, appHash, phone, code, email, emailCode, password, firstName, lastName, message;
    private CheckBox permission;
    private AlertDialog sendConfirmation;
    private RadioGroup chatPicker;
    private EditText groupFilter, contactFilter;
    private List<TdlibEngine.ChatInfo> storedChats = new ArrayList<>();
    private List<String> storedContacts = new ArrayList<>();
    private long selectedChatId;
    private int chatLimit=100, contactLimit=100;

    @Override public void onCreate(Bundle savedInstance) {
        super.onCreate(savedInstance);
        ElectricUi.styleWindow(this);
        setTitle("تلگولی | Telegram TDLib");
        engine=TdlibEngine.get(this);
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(18),dp(18),dp(18),dp(40));
        root.setBackgroundColor(ElectricUi.BG);
        scroll.addView(root);
        setContentView(scroll);

        LinearLayout hero=ElectricUi.hero(this);
        hero.addView(ElectricUi.text(this,"تلگرام شخصی | TDLib",23,Color.WHITE,true));
        hero.addView(ElectricUi.text(this,
            "ورود امن، مشاهدهٔ گروه‌ها و مخاطبان؛ بدون استخراج خصوصی یا ارسال انبوه.",
            14,Color.rgb(228,245,245),false));
        ElectricUi.addBlock(this,root,hero,1);
        LinearLayout statusCard=ElectricUi.card(this);
        statusCard.addView(text("وضعیت اتصال",17,true));
        connectionState=text("در انتظار راه‌اندازی...",15,true);
        statusCard.addView(connectionState);
        ElectricUi.addBlock(this,root,statusCard,12);
        ElectricUi.section(this,root,"۱. کلید توسعه‌دهنده","از my.telegram.org دریافت می‌شود");
        appId=entry("api_id عددی",InputType.TYPE_CLASS_NUMBER);
        root.addView(appId);
        appHash=entry("api_hash اختصاصی ۳۲ نویسه",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(appHash);
        root.addView(button("ذخیرهٔ امن API و اتصال TDLib",()->{
            String id=SearchText.digits(appId.getText().toString().trim());
            if(!id.matches("[1-9][0-9]{0,9}")){setFeedback("api_id معتبر وارد کنید.");return;}
            long value=Long.parseLong(id);
            if(value>Integer.MAX_VALUE){setFeedback("api_id خارج از محدوده است.");return;}
            engine.saveAppCredentials((int)value,appHash.getText().toString().trim());
            appId.setText("");appHash.setText("");
        }));
        root.addView(button("دریافت api_id و api_hash از تلگرام",()->openOfficial("https://my.telegram.org")));
        root.addView(button("اتصال / بازیابی نشست موجود",()->engine.connect()));
        root.addView(button("پروکسی تلگرام",()->TelegramProxyUi.show(this,engine,connectionState)));

        ElectricUi.section(this,root,"۲. ورود به حساب شخصی",
            "هر کد، ایمیل یا رمز را فقط وقتی تلگرام درخواست کرد وارد کن");
        phone=entry("+989121234567",InputType.TYPE_CLASS_PHONE);
        root.addView(phone);
        root.addView(button("ثبت شمارهٔ تلفن و دریافت کد",()->{
            engine.enterPhone(SearchText.digits(phone.getText().toString().trim()));phone.setText("");
        }));
        code=entry("کد ورود دریافت‌شده در تلگرام",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        root.addView(code);
        root.addView(button("تأیید کد ورود",()->{
            engine.enterCode(SearchText.digits(code.getText().toString().trim()));code.setText("");
        }));
        password=entry("رمز دومرحله‌ای تلگرام",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(password);
        root.addView(button("تأیید رمز دومرحله‌ای",()->{
            engine.enterPassword(password.getText().toString());password.setText("");
        }));
        ElectricUi.section(this,root,"مراحل اختیاری ورود","فقط در صورت درخواست مستقیم تلگرام");
        email=entry("ایمیل برای احراز هویت",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        root.addView(email);
        root.addView(button("ثبت ایمیل",()->{
            engine.enterEmail(email.getText().toString().trim());email.setText("");
        }));
        emailCode=entry("کد تأیید ایمیل",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(emailCode);
        root.addView(button("تأیید کد ایمیل",()->{
            engine.enterEmailCode(emailCode.getText().toString().trim());emailCode.setText("");
        }));
        firstName=entry("نام برای ثبت‌نام حساب جدید",InputType.TYPE_CLASS_TEXT);
        lastName=entry("نام خانوادگی (اختیاری)",InputType.TYPE_CLASS_TEXT);
        root.addView(firstName);root.addView(lastName);
        root.addView(button("ثبت‌نام فقط در صورت درخواست تلگرام",()->{
            engine.register(firstName.getText().toString(),lastName.getText().toString());
            firstName.setText("");lastName.setText("");
        }));
        ElectricUi.section(this,root,"۳. گروه‌های حساب","جست‌وجو و انتخاب فقط از گروه‌های قابل‌دسترسی");
        ElectricUi.addBlock(this,root,ElectricUi.button(this,
            "مشاهده و جست‌وجوی همهٔ چت‌ها و گروه‌های عمومی",
            ()->startActivity(new Intent(this,TelegramBrowserActivity.class))),10);
        groupFilter=entry("جست‌وجوی گروه در حساب تلگرام...", InputType.TYPE_CLASS_TEXT);
        root.addView(groupFilter);
        groupFilter.addTextChangedListener(watcher(()->{chatLimit=100;showFilteredChats();}));
        root.addView(button("بارگذاری گروه‌ها و مخاطبان مجاز",()->engine.refresh()));
        root.addView(text("فقط گفتگوهای گروهی حساب شما فهرست می‌شوند؛ بعضی موارد ممکن است کانال باشند و اجازهٔ ارسال ندهند.",13,false));
        groupResults=new LinearLayout(this);
        groupResults.setOrientation(LinearLayout.VERTICAL);
        groupResults.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        chatPicker=new RadioGroup(this);
        chatPicker.setOrientation(RadioGroup.VERTICAL);
        chatPicker.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        groupResults.addView(chatPicker);
        root.addView(groupResults);

        ElectricUi.section(this,root,"۴. آزمایش ارسال یک پیام","با تأیید جداگانه و رعایت مجوز گروه");
        message=entry("متن پیام آزمایشی",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        message.setSingleLine(false);
        message.setMinLines(2);
        root.addView(message);
        permission=new CheckBox(this);
        permission.setText("مدیریت گروه اجازهٔ انتشار این پیام را داده است و فقط همین پیام را تأیید می‌کنم.");
        permission.setTextDirection(View.TEXT_DIRECTION_RTL);
        root.addView(permission);
        root.addView(button("بررسی و تأیید ارسال یک پیام",this::confirmManualTest));
        root.addView(text("هیچ ارسال گروهی زمان‌بندی‌شده‌ای انجام نمی‌شود. حداقل ۸ دقیقه فاصله بین تلاش‌های دستی این برنامه رعایت می‌شود؛ مجوز ارسال باید مستقل بررسی شود.",13,false));
        ElectricUi.section(this,root,"۵. مخاطبان حساب شخصی","نمایش محلی؛ اطلاعات به Exa فرستاده نمی‌شود");
        contactFilter=entry("جست‌وجوی نام مخاطب تلگرام...",InputType.TYPE_CLASS_TEXT);
        root.addView(contactFilter);
        contactFilter.addTextChangedListener(watcher(()->{contactLimit=100;showFilteredContacts();}));
        root.addView(text("این فهرست صرفاً داخل گوشی نمایش داده می‌شود و برای Exa ارسال نمی‌شود.",13,false));
        contactsResults=new LinearLayout(this);
        contactsResults.setOrientation(LinearLayout.VERTICAL);
        contactsResults.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.addView(contactsResults);
        root.addView(ElectricUi.button(this,"خروج کامل از نشست حساب شخصی",()->new AlertDialog.Builder(this)
            .setTitle("خروج از تلگرام")
            .setMessage("نشست TDLib این برنامه از حساب تلگرام شما خارج شود؟")
            .setNegativeButton("انصراف",null)
            .setPositiveButton("خروج", (d,w)->engine.logout())
            .show(),true,true));
        actionResult=text("",14,false);
        root.addView(actionResult);
    }

    @Override protected void onResume() {
        super.onResume();
        engine.attach(this);
        engine.connect();
    }
    @Override protected void onPause() {
        dismissSendConfirmation();
        engine.detach(this);
        super.onPause();
    }

    private void confirmManualTest() {
        if(selectedChatId==0){setFeedback("ابتدا یکی از گروه‌های بارگذاری‌شده را انتخاب کنید.");return;}
        String text=message.getText().toString().trim();
        if(text.isEmpty() || text.length()>3000){setFeedback("متن باید بین ۱ تا ۳۰۰۰ نویسه باشد.");return;}
        if(!permission.isChecked()){setFeedback("مجوز انتشار را تأیید کنید.");return;}
        final long id=selectedChatId;
        dismissSendConfirmation();
        sendConfirmation=new AlertDialog.Builder(this)
            .setTitle("تأیید قطعی یک پیام از حساب شخصی")
            .setMessage("این متن فقط به گروه منتخب با شناسه "+id+" ارسال شود؟\n\n"+text)
            .setNegativeButton("انصراف",null)
            .setPositiveButton("فقط همین پیام",(d,w)->{
                engine.sendApprovedSingleMessage(id,text,true);
                permission.setChecked(false);
            }).show();
    }

    private int dp(int n){return ElectricUi.dp(this,n);}
    private TextView text(String s,int size,boolean bold){
        return ElectricUi.text(this,s,size,bold?ElectricUi.INK:ElectricUi.MUTED,bold);
    }
    private EditText entry(String hint,int type){
        EditText t=ElectricUi.field(this,hint,
            (type & InputType.TYPE_TEXT_FLAG_MULTI_LINE)!=0);
        t.setInputType(type);
        t.setSaveEnabled(false);
        return t;
    }
    private Button button(String label,Runnable action){
        return ElectricUi.button(this,label,action);
    }
    private void openOfficial(String url) {
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}
        catch(Exception e){setFeedback("مرورگر پیدا نشد.");}
    }
    private void setFeedback(String s){if(actionResult!=null)actionResult.setText(s);}

    @Override public void onState(String state,String detail) {
        connectionState.setText("وضعیت: "+state+"\n"+detail);
        if(!"READY".equals(state)) {
            selectedChatId=0;
            permission.setChecked(false);
            chatPicker.clearCheck();
            dismissSendConfirmation();
        }
    }
    private void dismissSendConfirmation() {
        if(sendConfirmation!=null){sendConfirmation.dismiss();sendConfirmation=null;}
    }
    private TextWatcher watcher(Runnable onChange) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) {onChange.run();}
            @Override public void afterTextChanged(Editable s) {}
        };
    }

    @Override public void onChats(List<TdlibEngine.ChatInfo> chats) {
        storedChats = new ArrayList<>(chats);
        showFilteredChats();
    }

    private void showFilteredChats() {
        if(chatPicker==null)return;
        String keyword = groupFilter==null?"":groupFilter.getText().toString();
        chatPicker.clearCheck();
        chatPicker.removeAllViews();
        boolean found=false;
        int shown=0;
        int matches=0;
        for(TdlibEngine.ChatInfo chat:storedChats){
            if(!SearchText.matches(chat.name+" "+chat.id,keyword))continue;
            matches++;
            if(shown>=chatLimit)continue;
            shown++;
            RadioButton option=new RadioButton(this);
            option.setText(chat.name+" ("+chat.id+")");
            option.setTextDirection(View.TEXT_DIRECTION_RTL);
            option.setId(View.generateViewId());
            option.setOnClickListener(v->{
                selectedChatId=chat.id;
                if(permission!=null)permission.setChecked(false);
            });
            chatPicker.addView(option);
            if(selectedChatId==chat.id){option.setChecked(true);found=true;}
        }
        if(!found) {
            selectedChatId=0;
            if(permission!=null)permission.setChecked(false);
            dismissSendConfirmation();
        }
        if(shown==0)chatPicker.addView(text("گروهی مطابق جست‌وجو پیدا نشد.",13,false));
        chatPicker.addView(text(shown+" / "+matches,12,false));
        if(matches>shown)chatPicker.addView(button("نمایش گروه‌های بیشتر",()->{chatLimit+=100;showFilteredChats();}));
    }

    @Override public void onContacts(List<String> names) {
        storedContacts = new ArrayList<>(names);
        showFilteredContacts();
    }

    private void showFilteredContacts() {
        if(contactsResults==null)return;
        String keyword=contactFilter==null?"":contactFilter.getText().toString();
        contactsResults.removeAllViews();
        int count=0;
        int matches=0;
        for(String name:storedContacts){
            if(!SearchText.matches(name,keyword))continue;
            matches++;
            if(count>=contactLimit)continue;
            count++;
            contactsResults.addView(text("• "+name,13,false));
        }
        if(count==0)contactsResults.addView(text("مخاطبی مطابق جست‌وجو پیدا نشد.",13,false));
        contactsResults.addView(text(count+" / "+matches,12,false));
        if(matches>count)contactsResults.addView(button("نمایش مخاطبان بیشتر",()->{contactLimit+=100;showFilteredContacts();}));
    }
    @Override public void onResult(String task,boolean success,String detail) {
        setFeedback((success?"✓ ":"! ")+task+": "+detail);
    }
}
