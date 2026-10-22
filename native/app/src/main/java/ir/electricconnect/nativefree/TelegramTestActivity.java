package ir.electricconnect.nativefree;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;

/**
 * Permission-based Telegram BOT test mode.
 * Does NOT authenticate a personal user via TDLib, discover private chats,
 * or schedule unattended/bulk messages. All sends require explicit confirmation.
 */
public final class TelegramTestActivity extends Activity {
    private static final String PREFS = "telegram_test_settings";
    private final int teal = Color.rgb(8, 103, 115);
    private BotRequestState requests;
    private TextView status;
    private EditText tokenField, chatField, messageField;
    private CheckBox allowed;

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        ElectricUi.styleWindow(this);
        requests = new BotRequestState(prefs(), System::currentTimeMillis);
        setTitle("Electric Connect | Telegram Test");
        ScrollView scroller = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(18), dp(18), dp(18), dp(22));
        root.setBackgroundColor(ElectricUi.BG);
        scroller.addView(root);
        setContentView(scroller);
        LinearLayout hero=ElectricUi.hero(this);
        hero.addView(ElectricUi.text(this,"آزمایش ربات تلگرام",23,Color.WHITE,true));
        hero.addView(ElectricUi.text(this,
            "محیط آزمایشی Bot API؛ فقط یک پیام مجاز با تأیید دستی و بدون ارسال گروهی خودکار.",
            14,Color.rgb(227,245,244),false));
        ElectricUi.addBlock(this,root,hero,1);
        ElectricUi.section(this,root,"۱. مشخصات ربات",
            "توکن فقط در حافظهٔ رمزگذاری‌شدهٔ گوشی نگهداری می‌شود");
        tokenField = entry("توکن را فقط در گوشی وارد کنید",true);
        root.addView(tokenField);
        root.addView(button("ذخیرهٔ رمزگذاری‌شدهٔ توکن", () -> {
            String raw = tokenField.getText().toString().trim();
            if (!raw.matches("[0-9]{5,15}:[A-Za-z0-9_-]{20,}")) {
                setStatus("قالب توکن Bot API معتبر نیست."); return;
            }
            try {
                Vault.save(this,raw);
                tokenField.setText("");
                setStatus("توکن در Android Keystore این گوشی ذخیره شد.");
            } catch (Exception ex) { setStatus("ذخیرهٔ امن انجام نشد."); }
        }));
        root.addView(ElectricUi.button(this,"حذف توکن ذخیره‌شده", () -> new AlertDialog.Builder(this)
            .setMessage("توکن ربات از حافظهٔ این گوشی حذف شود؟")
            .setNegativeButton("انصراف",null)
            .setPositiveButton("حذف",(d,w)-> {
                Vault.delete(this);
                tokenField.setText("");
                setStatus("توکن حذف شد.");
            }).show(),true,true));
        root.addView(button("بررسی اتصال ربات (getMe)",this::verifyBot));
        ElectricUi.section(this,root,"۲. مقصد آزمایشی","شناسهٔ عددی گفت‌وگوی دارای مجوز");
        chatField=entry("مثال: -1001234567890",false);
        root.addView(chatField);
        ElectricUi.section(this,root,"۳. متن و تأیید ارسال","متن را قبل از ارسال دوباره بررسی کن");
        messageField=entry("سلام، این یک پیام تست مجاز است.",false);
        messageField.setSingleLine(false);
        messageField.setMinLines(3);
        root.addView(messageField);
        allowed=new CheckBox(this);
        allowed.setText("اجازهٔ ارسال در این چت را دارم و ارسال را تأیید می‌کنم.");
        allowed.setTextDirection(View.TEXT_DIRECTION_RTL);
        root.addView(allowed);
        root.addView(button("ارسال فقط یک پیام آزمایشی با تأیید",this::confirmSend));
        root.addView(text("برای هر ارسال، تأیید جدید لازم است. حداقل ۸ دقیقه میان ارسال‌های موفق حفظ می‌شود. خطای ۴۲۹ و محدودیت‌های تلگرام موجب توقف می‌شوند.",13,false));
        ElectricUi.section(this,root,"حساب شخصی تلگرام","این قسمت از Bot API مستقل است");
        root.addView(text("ورود به حساب شخصی در صفحهٔ جداگانهٔ TDLib با api_id و api_hash انجام می‌شود.",13,false));
        root.addView(button("اتصال حساب شخصی TDLib", () ->
            startActivity(new Intent(this,TdlibAccountActivity.class))));
        root.addView(button("بازکردن صفحهٔ رسمی ساخت API تلگرام", () -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://my.telegram.org"))); }
            catch(Exception ignored){setStatus("مرورگر قابل‌دسترسی نیست.");}
        }));
        status=text(Vault.exists(this) ? "توکن آزمایشی قبلاً ذخیره شده است." : "ابتدا توکن ربات آزمایشی را ذخیره کنید.",14,false);
        status.setBackground(ElectricUi.outlined(this,ElectricUi.WHITE,14));
        status.setPadding(dp(14),dp(14),dp(14),dp(14));
        ElectricUi.addBlock(this,root,status,11);
    }

    private int dp(int n){return ElectricUi.dp(this,n);}
    private TextView text(String value,int size,boolean bold){
        return ElectricUi.text(this,value,size,bold?ElectricUi.INK:ElectricUi.MUTED,bold);
    }
    private EditText entry(String hint,boolean password){
        EditText f=ElectricUi.field(this,hint,false);
        if(password){
            f.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
            f.setSaveEnabled(false);
        }
        return f;
    }
    private Button button(String label,Runnable action){
        return ElectricUi.button(this,label,action);
    }
    private void setStatus(String s) { if(status!=null)status.setText(s); }
    private SharedPreferences prefs(){return getSharedPreferences(PREFS,MODE_PRIVATE);}

    private void verifyBot(){
        request("getMe",null, false);
    }
    private void confirmSend(){
        final String chat=SearchText.digits(chatField.getText().toString().trim());
        final String message=messageField.getText().toString().trim();
        if(!chat.matches("-?[0-9]{4,20}")){setStatus("شناسهٔ عددی چت آزمایشی را وارد کنید.");return;}
        if(message.isEmpty()||message.length()>4096){setStatus("متن باید بین ۱ تا ۴۰۹۶ نویسه باشد.");return;}
        if(!allowed.isChecked()){setStatus("ابتدا مجوز ارسال به این چت را تأیید کنید.");return;}
        new AlertDialog.Builder(this)
            .setTitle("تأیید ارسال به یک چت")
            .setMessage("آیا یک پیام را فقط به چت آزمایشی "+chat+" ارسال کنم؟\n\n"+message)
            .setNegativeButton("انصراف",null)
            .setPositiveButton("ارسال یک پیام",(d,w)-> {
                JSONObject params=new JSONObject();
                try{
                    params.put("chat_id",Long.parseLong(chat));
                    params.put("text",message);
                    params.put("disable_notification",true);
                }catch(Exception ignored){setStatus("شناسهٔ چت نامعتبر است.");return;}
                request("sendMessage",params,true);
            }).show();
    }

    private void request(String method,JSONObject body,boolean sending){
        final String token;
        try{
            token=Vault.read(this);
        }catch(Exception ignored){
            setStatus("توکن قابل بازیابی نیست؛ آن را حذف و دوباره ذخیره کنید.");
            return;
        }
        if(token==null||token.isEmpty()){setStatus("توکن ربات ذخیره نشده است.");return;}
        String blocked=requests.begin(sending);
        if(blocked!=null){setStatus(blocked);return;}
        if(sending)allowed.setChecked(false);
        setStatus("در حال ارتباط امن با Telegram Bot API ...");
        new Thread(()->{
            String message;
            boolean success=false;
            int retry=0;
            try {
                JSONObject result=call(method,token,body);
                if(!result.optBoolean("ok",false)) {
                    retry=result.optJSONObject("parameters")==null?0:
                        result.optJSONObject("parameters").optInt("retry_after",0);
                    message= retry>0 ? "محدودیت تلگرام؛ "+retry+" ثانیه توقف و عدم ارسال مجدد خودکار."
                        : "تلگرام درخواست را نپذیرفت (کد "+result.optInt("error_code",0)+").";
                } else {
                    success=true;
                    message=sending?"ارسال یک پیام آزمایشی با موفقیت تأیید شد.":
                        "توکن ربات معتبر است: @"+result.optJSONObject("result").optString("username","");
                }
            }catch(Exception ex){message="ارتباط موفق نبود؛ اتصال اینترنت و دسترسی ربات را بررسی کنید.";}
            requests.finish(sending,success,retry);
            final String outcome=message;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed())return;
                setStatus(outcome);
            });
        },"electric-telegram-test").start();
    }

    private static JSONObject call(String method,String token,JSONObject body)throws Exception{
        HttpsURLConnection conn=(HttpsURLConnection)new URL("https://api.telegram.org/bot"+token+"/"+method).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(20000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type","application/json; charset=utf-8");
        byte[] data=(body==null?"{}":body.toString()).getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(data.length);
        try{
            try(OutputStream os=conn.getOutputStream()){os.write(data);}
            int status=conn.getResponseCode();
            InputStream source=status>=200&&status<300?conn.getInputStream():conn.getErrorStream();
            if(source==null)throw new Exception("no response");
            try(InputStream in=source;ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] chunk=new byte[4096];int n;
                while((n=in.read(chunk))!=-1){
                    if(out.size()+n>131072)throw new Exception("oversize response");
                    out.write(chunk,0,n);
                }
                // Never expose raw server text: it may include user data.
                JSONObject result=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
                if(status==429)return result;
                return result;
            }
        }finally{conn.disconnect();}
    }

    private static final class Vault{
        private static final String ALIAS="electric_connect_telegram_test_token_v1";
        private static final String CIPHER="c",NONCE="n";
        private static SharedPreferences settings(Context ctx){
            return ctx.getSharedPreferences("telegram_bot_secret",Context.MODE_PRIVATE);
        }
        private static SecretKey key()throws Exception{
            KeyStore store=KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            SecretKey stored=(SecretKey)store.getKey(ALIAS,null);
            if(stored!=null)return stored;
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
            return generator.generateKey();
        }
        static void save(Context ctx,String token)throws Exception{
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE,key());
            byte[] encoded=c.doFinal(token.getBytes(StandardCharsets.UTF_8));
            settings(ctx).edit()
                .putString(CIPHER,Base64.encodeToString(encoded,Base64.NO_WRAP))
                .putString(NONCE,Base64.encodeToString(c.getIV(),Base64.NO_WRAP)).apply();
        }
        static String read(Context ctx)throws Exception{
            String c=settings(ctx).getString(CIPHER,null);
            String n=settings(ctx).getString(NONCE,null);
            if(c==null||n==null)return null;
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key(),
                new GCMParameterSpec(128,Base64.decode(n,Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(c,Base64.NO_WRAP)),StandardCharsets.UTF_8);
        }
        static boolean exists(Context ctx){return settings(ctx).contains(CIPHER);}
        static void delete(Context ctx){
            settings(ctx).edit().clear().apply();
            try{
                KeyStore k=KeyStore.getInstance("AndroidKeyStore");
                k.load(null);
                k.deleteEntry(ALIAS);
            }catch(Exception ignored){}
        }
    }
}
