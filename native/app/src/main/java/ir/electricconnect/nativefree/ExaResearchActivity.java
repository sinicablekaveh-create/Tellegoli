package ir.electricconnect.nativefree;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
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
import android.widget.Spinner;
import android.widget.ArrayAdapter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;

/**
 * User-initiated online search. There is no embedded API key, automatic
 * background querying, web scraping or automatic messaging.
 */
public final class ExaResearchActivity extends Activity {
    private static final String EXA_SEARCH_URL = "https://api.exa.ai/search";
    private static final String EXA_PREFS = "exa_ui_settings";
    private static final String EXA_ONLINE_ENABLED = "online_enabled";
    private static final String[] TOPICS = {
        "پیمانکاران اجرایی برق صنعتی ایران", "مجریان تأسیسات الکتریکی ساختمان",
        "پیمانکاران کابل کشی پروژه‌های صنعتی", "شرکت‌های EPC و برق کارخانجات",
        "مجریان برق سوله و مراکز تجاری", "گروه‌های عمومی پیمانکاران برق",
        "گروه‌های عمومی تلگرام تاسیسات برق صنعتی و پیمانکاران پروژه site:t.me"
    };

    private final int navy = Color.rgb(10, 42, 54);
    private final int teal = Color.rgb(8, 103, 115);
    private LinearLayout root;
    private LinearLayout results;
    private TextView status;
    private EditText queryInput;
    private EditText keyInput;
    private RadioGroup searchMode;
    private RadioButton normalMode;
    private RadioButton deepMode;
    private CheckBox exaToggle;
    private int deepId;
    // Incremented whenever the mode or online search changes, so an in-flight
    // response cannot overwrite the offline screen after Exa was turned off.
    private int searchGeneration;
    private Button searchButton;
    private MainActivity.Store store;
    private Spinner resultCount;
    private EditText resultFilter;
    private JSONObject lastResponse;
    private String lastMode;
    private final AtomicBoolean busy = new AtomicBoolean();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ElectricUi.styleWindow(this);
        store = new MainActivity.Store(this);
        setTitle("Electric Connect | Exa");
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        root.setBackgroundColor(ElectricUi.BG);
        scroller.addView(root);
        setContentView(scroller);

        LinearLayout hero = ElectricUi.hero(this);
        hero.addView(ElectricUi.text(this,"جست‌وجوگر هوشمند Exa",23,Color.WHITE,true));
        hero.addView(ElectricUi.text(this,
            "دو حالت مستقل: بانک اطلاعات آفلاین و جست‌وجوی اینترنتی اختیاری.",
            14,Color.rgb(228,246,245),false));
        ElectricUi.addBlock(this,root,hero,2);
        LinearLayout modeCard = ElectricUi.card(this);
        modeCard.addView(text("دسترسی اینترنتی",17,true));
        ElectricUi.addBlock(this,root,modeCard,14);
        exaToggle = new CheckBox(this);
        exaToggle.setText("Exa روشن — جست‌وجوی اینترنتی با کلید API");
        exaToggle.setTextDirection(View.TEXT_DIRECTION_RTL);
        exaToggle.setChecked(getSharedPreferences(EXA_PREFS, Context.MODE_PRIVATE)
                .getBoolean(EXA_ONLINE_ENABLED, false));
        exaToggle.setMinHeight(dp(48));
        modeCard.addView(exaToggle);
        modeCard.addView(text("با خاموش بودن Exa، هیچ درخواست اینترنتی جدیدی ارسال نمی‌شود؛ فقط داده‌های محلی گوشی جست‌وجو می‌شوند.",13,false));
        LinearLayout keyCard=ElectricUi.card(this);
        keyCard.addView(text("کلید API مخصوص Exa",17,true));
        keyCard.addView(text("اختیاری؛ فقط در حالت روشن استفاده می‌شود و رمزگذاری‌شده می‌ماند.",13,false));
        ElectricUi.addBlock(this,root,keyCard,12);
        keyInput = input("کلید Exa را اینجا وارد کنید");
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setSaveEnabled(false);
        ElectricUi.addBlock(this,keyCard,keyInput,9);
        ElectricUi.addBlock(this,keyCard,button("ذخیرهٔ رمزگذاری‌شدهٔ کلید در گوشی", () -> {
            String value = keyInput.getText().toString().trim();
            if (value.length() < 12) { setStatus("کلید API معتبر وارد کنید."); return; }
            try {
                KeyVault.save(this, value);
                keyInput.setText("");
                setStatus("کلید در حافظهٔ رمزگذاری‌شدهٔ همین دستگاه ذخیره شد.");
            } catch (Exception ignored) { setStatus("خطا در ذخیرهٔ امن کلید؛ تغییری اعمال نشد."); }
        }),10);
        ElectricUi.addBlock(this,keyCard,ElectricUi.button(this,"حذف کلید ذخیره‌شده",() -> new AlertDialog.Builder(this)
            .setMessage("کلید API ذخیره‌شده از برنامه حذف شود؟")
            .setNegativeButton("انصراف", null)
            .setPositiveButton("حذف", (dialog, which) -> {
                KeyVault.clear(this);
                keyInput.setText("");
                setStatus("کلید حذف شد.");
            }).show(),true,true),8);

        ElectricUi.section(this,root,"موضوع تحقیق","جست‌وجوی عمومی یا داده‌های ذخیره‌شده");
        queryInput = input("مثلاً: تولیدکنندگان تابلو برق صنعتی در تهران");
        ElectricUi.addBlock(this,root,queryInput,8);
        LinearLayout topics=ElectricUi.card(this);
        topics.addView(text("جست‌وجوهای پیشنهادی صنعت برق",15,true));
        for(String topic:TOPICS) {
            ElectricUi.addBlock(this,topics,
                ElectricUi.button(this,topic,()->queryInput.setText(topic),true,false),7);
        }
        ElectricUi.addBlock(this,root,topics,12);
        searchMode = new RadioGroup(this);
        searchMode.setOrientation(RadioGroup.VERTICAL);
        RadioButton normal = new RadioButton(this);
        normal.setId(View.generateViewId());
        normal.setText("جست‌وجوی آنلاین (auto)");
        normalMode = normal;
        searchMode.addView(normal);
        RadioButton deep = new RadioButton(this);
        deepId = View.generateViewId();
        deep.setId(deepId);
        deep.setText("تحقیق عمیق (deep - هزینهٔ بالاتر)");
        deepMode = deep;
        searchMode.addView(deep);
        searchMode.check(normal.getId());
        LinearLayout settings=ElectricUi.card(this);
        settings.addView(text("روش جست‌وجوی آنلاین",16,true));
        settings.addView(searchMode);
        settings.addView(text("تعداد نتایج (تعداد بیشتر ممکن است هزینهٔ بیشتری داشته باشد)",14,false));
        resultCount = new Spinner(this);
        resultCount.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"10", "25", "50"}));
        resultCount.setSelection(0);
        settings.addView(resultCount);
        ElectricUi.addBlock(this,root,settings,12);
        searchButton = button("جست‌وجوی واقعی در Exa", () -> {
            String term = queryInput.getText().toString().trim();
            boolean online = exaToggle.isChecked();
            if (term.length() < (online ? 3 : 1) || term.length() > 250) {
                setStatus(online ? "موضوع آنلاین باید بین ۳ تا ۲۵۰ نویسه باشد."
                                 : "موضوع آفلاین باید بین ۱ تا ۲۵۰ نویسه باشد.");
                return;
            }
            if (!online) {
                showOfflineResults(term, 0);
                return;
            }
            if (searchMode.getCheckedRadioButtonId() == deepId) {
                new AlertDialog.Builder(this)
                    .setTitle("تأیید هزینهٔ جست‌وجوی عمیق")
                    .setMessage("حالت Deep ممکن است اعتبار بیشتری از حساب Exa مصرف کند. جست‌وجو انجام شود؟")
                    .setNegativeButton("انصراف", null)
                    .setPositiveButton("اجرا", (dialog, which) -> executeSearch(term, "deep"))
                    .show();
            } else {
                executeSearch(term, "auto");
            }
        });
        root.addView(searchButton);
        status = text("", 14, false);
        status.setBackground(ElectricUi.outlined(this,ElectricUi.WHITE,14));
        status.setPadding(dp(15),dp(15),dp(15),dp(15));
        ElectricUi.addBlock(this,root,status,13);
        resultFilter = input("جست‌وجو در عنوان، منبع و متن نتایج دریافت‌شده");
        root.addView(resultFilter);
        resultFilter.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) {
                if(lastResponse!=null)showResults(lastResponse,lastMode);
            }
            public void afterTextChanged(Editable s) {}
        });
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.addView(results);
        ElectricUi.addBlock(this,root,text("فقط منابع عمومی بررسی می‌شوند. ذخیرهٔ مخاطب یا ارسال پیام، خودکار نیست.",13,false),14);
        exaToggle.setOnCheckedChangeListener((view, enabled) -> {
            getSharedPreferences(EXA_PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(EXA_ONLINE_ENABLED, enabled).apply();
            searchGeneration++;
            applyExaMode(enabled);
            lastResponse = null;
            results.removeAllViews();
            resultFilter.setText("");
            setStatus(enabled
                    ? "Exa روشن است؛ فقط پس از زدن دکمهٔ جست‌وجو درخواست اینترنتی ارسال می‌شود."
                    : "Exa خاموش شد. درخواست آنلاین جدیدی ارسال نمی‌شود؛ جست‌وجو فقط در حافظهٔ گوشی است.");
        });
        applyExaMode(exaToggle.isChecked());
        setStatus(exaToggle.isChecked()
            ? (KeyVault.hasSaved(this)
                ? "Exa روشن است و کلید قبلاً ذخیره شده است."
                : "Exa روشن است؛ ابتدا کلید API را ذخیره کنید.")
            : "Exa خاموش است؛ جست‌وجوی آفلاین بدون کلید API آماده است.");
    }

    private int dp(int n) {
        return (int)(getResources().getDisplayMetrics().density * n + .5f);
    }

    private TextView text(String s,int size,boolean bold) {
        return ElectricUi.text(this,s,size,bold?ElectricUi.INK:ElectricUi.MUTED,bold);
    }

    private EditText input(String hint) {
        return ElectricUi.field(this,hint,false);
    }

    private Button button(String title,Runnable action) {
        return ElectricUi.button(this,title,action);
    }

    private void setStatus(String message) {
        if (status != null) status.setText(message);
    }

    private void applyExaMode(boolean enabled) {
        normalMode.setEnabled(enabled);
        deepMode.setEnabled(enabled);
        resultCount.setEnabled(enabled);
        resultFilter.setEnabled(enabled);
        searchButton.setEnabled(!enabled || !busy.get());
        searchButton.setText(enabled ? "جست‌وجوی واقعی در Exa"
                                     : "جست‌وجوی آفلاین (Exa خاموش)");
    }

    private void showOfflineResults(String query, int offset) {
        if (exaToggle.isChecked()) return;
        lastResponse = null;
        results.removeAllViews();
        try {
            java.util.List<MainActivity.Record> records = store.find(query, offset, 51);
            int count = Math.min(50, records.size());
            if (count == 0 && offset == 0) {
                setStatus("Exa خاموش است. نتیجه‌ای در اطلاعات ذخیره‌شده پیدا نشد.");
                results.addView(text("برای جست‌وجوی آفلاین باید ابتدا مخاطب یا کسب‌وکار در برنامه ذخیره شده باشد.", 14, false));
                return;
            }
            for (int i = 0; i < count; i++) {
                MainActivity.Record record = records.get(i);
                results.addView(text((offset+i+1) + ". " + cut(record.title, 240), 17, true));
                if (!record.phone.isEmpty()) results.addView(text("شماره: " + cut(record.phone, 100), 14, false));
                if (!record.category.isEmpty()) results.addView(text("دسته‌بندی: " + cut(record.category, 150), 13, false));
                if (!record.source.isEmpty()) results.addView(text("منبع / یادداشت: " + cut(record.source, 300), 13, false));
            }
            if (records.size() > 50) {
                results.addView(button("نمایش ۵۰ نتیجهٔ بعدی (آفلاین)",
                        () -> showOfflineResults(query, offset + 50)));
            }
            setStatus("Exa خاموش | نمایش " + count + " نتیجهٔ محلی"
                    + (offset > 0 ? " (از شمارهٔ " + (offset + 1) + ")" : ""));
        } catch (Exception e) {
            setStatus("خطای جست‌وجوی محلی؛ اطلاعات ذخیره‌شدهٔ برنامه را بررسی کنید.");
        }
    }

    private void executeSearch(String query, String mode) {
        if (!exaToggle.isChecked()) {
            setStatus("Exa خاموش است؛ برای جست‌وجوی محلی از دکمهٔ آفلاین استفاده کنید.");
            return;
        }
        if(busy.get()) {setStatus("جست‌وجوی دیگری در حال اجراست.");return;}
        final String key;
        try {
            key = KeyVault.load(this);
        } catch (Exception ignored) {
            setStatus("کلید رمزگذاری‌شده قابل خواندن نیست. آن را حذف و دوباره ذخیره کنید.");
            return;
        }
        if (key == null || key.isEmpty()) {
            setStatus("ابتدا کلید اختصاصی Exa را وارد و ذخیره کنید.");
            return;
        }
        if(!busy.compareAndSet(false,true))return;
        final int generation = ++searchGeneration;
        final int requestedCount=Integer.parseInt(resultCount.getSelectedItem().toString());
        lastResponse=null;
        resultFilter.setText("");
        searchButton.setEnabled(false);
        results.removeAllViews();
        setStatus("در حال جست‌وجو با Exa ...");
        new Thread(() -> {
            String error = null;
            JSONObject response = null;
            try {
                response = callExa(key, query, mode, requestedCount);
            } catch (Exception e) {
                error = e instanceof SearchFailure ? e.getMessage() : "خطای اتصال؛ اینترنت و دسترسی Exa را بررسی کنید.";
            }
            final String finalError = error;
            final JSONObject finalResponse = response;
            runOnUiThread(() -> {
                busy.set(false);
                if (isFinishing() || isDestroyed()) return;
                applyExaMode(exaToggle.isChecked());
                // A disabled Exa mode must not display stale results from an
                // online request that began before the user switched it off.
                if (!exaToggle.isChecked() || generation != searchGeneration) return;
                if (finalError != null) {
                    setStatus("جست‌وجو ناموفق: " + finalError);
                    return;
                }
                lastResponse=finalResponse;
                lastMode=mode;
                showResults(finalResponse, mode);
            });
        }, "electric-exa-search").start();
    }

    private static final class SearchFailure extends Exception {
        SearchFailure(String message) {super(message);}
    }

    private static JSONObject callExa(String key, String query, String mode, int count) throws Exception {
        return callExa(key,query,mode,count,false);
    }

    /** Full-text opt-in for verified public business-contact extraction.
     * Only search terms are sent to Exa; local contacts/Telegram remain private.
     * Exa text extraction can incur additional charges.
     */
    static JSONObject callExa(String key, String query, String mode, int count,
                              boolean includePublicPageText) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(EXA_SEARCH_URL).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout("deep".equals(mode) ? 65000 : 25000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("x-api-key", key);
        JSONObject body = new JSONObject();
        body.put("query", query);
        body.put("type", mode);
        body.put("numResults", count);
        JSONObject contents = new JSONObject();
        if(includePublicPageText){
            JSONObject textSettings=new JSONObject();
            textSettings.put("maxCharacters",6000);
            contents.put("text",textSettings);
        } else {
            contents.put("highlights",true);
        }
        body.put("contents", contents);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(payload.length);
        try {
            try (OutputStream stream = connection.getOutputStream()) {
                stream.write(payload);
            }
            int http = connection.getResponseCode();
            if (http != 200) {
                // Read only the bounded Exa error payload. Never display arbitrary
                // server text, API keys or the full request on the screen.
                String responseBody = "";
                try (InputStream errorStream = connection.getErrorStream()) {
                    if (errorStream != null) {
                        ByteArrayOutputStream errorBuffer = new ByteArrayOutputStream();
                        byte[] chunk = new byte[1024];
                        int read;
                        while (errorBuffer.size() < 4096
                                && (read = errorStream.read(chunk)) != -1) {
                            errorBuffer.write(chunk, 0, Math.min(read, 4096 - errorBuffer.size()));
                        }
                        responseBody = new String(errorBuffer.toByteArray(), StandardCharsets.UTF_8);
                    }
                } catch (Exception ignored) {
                    // HTTP status still gives useful troubleshooting information.
                }
                throw new SearchFailure(ExaHttpError.describe(http, responseBody));
            }
            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] data = new byte[8192];
                int n;
                while ((n = in.read(data)) != -1) {
                    if (buffer.size() + n > 4 * 1024 * 1024) throw new SearchFailure("پاسخ Exa بیش از اندازه بزرگ است");
                    buffer.write(data, 0, n);
                }
                return new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    private void showResults(JSONObject response, String mode) {
        results.removeAllViews();
        if (response == null) { setStatus("پاسخی از Exa دریافت نشد."); return; }
        JSONArray array = response.optJSONArray("results");
        if (array == null || array.length() == 0) {
            setStatus("Exa نتیجه‌ای برای این جست‌وجو برنگرداند.");
            return;
        }
        JSONObject synthesis = response.optJSONObject("output");
        if (synthesis != null) {
            String summary = synthesis.optString("content", "").trim();
            if (!summary.isEmpty()) {
                results.addView(text("خلاصهٔ تحقیق Exa", 17, true));
                results.addView(text(cut(summary, 6000), 14, false));
            }
        }
        int count = 0;
        Set<String> seen=new HashSet<>();
        String filter=resultFilter.getText().toString();
        for (int i = 0; i < array.length(); i++) {
            JSONObject result = array.optJSONObject(i);
            if (result == null) continue;
            String title = result.optString("title", "بدون عنوان").trim();
            String url = result.optString("url", "").trim();
            if (!(url.startsWith("https://") || url.startsWith("http://"))) continue;
            JSONArray highlights = result.optJSONArray("highlights");
            StringBuilder searchable=new StringBuilder(title).append(' ').append(url);
            if(highlights!=null)for(int n=0;n<highlights.length();n++)searchable.append(' ').append(highlights.optString(n,""));
            if(!SearchText.matches(searchable.toString(),filter) || !seen.add(url))continue;
            count++;
            results.addView(text(count + ". " + cut(title, 240), 17, true));
            if (highlights != null && highlights.length() > 0) {
                String excerpt = highlights.optString(0, "");
                if (!excerpt.isEmpty()) results.addView(text(cut(excerpt, 750), 13, false));
            }
            results.addView(text(cut(url, 320), 12, false));
            results.addView(button("مشاهدهٔ منبع", () -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) { setStatus("مرورگر قابل‌دسترسی نیست."); }
            }));
            results.addView(button("ذخیرهٔ دستی در مخاطبان", () -> {
                try {
                    boolean ok = store.insertRecord(title, "", "نتیجهٔ جست‌وجوی Exa", url);
                    setStatus(ok ? "نتیجه در مخاطبان محلی ذخیره شد." : "این عنوان قبلاً ثبت شده است.");
                } catch (Exception ignored) { setStatus("خطا در ذخیرهٔ مخاطب."); }
            }));
        }
        setStatus(count + " نتیجهٔ نمایش‌داده‌شده از " + array.length() + " نتیجهٔ دریافتی (" + mode + ").");
        if(count==0)results.addView(text("نتیجه‌ای مطابق فیلتر پیدا نشد.",14,false));
    }

    private static String cut(String text, int max) {
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }

    @Override public void onDestroy() {
        if (store != null) store.close();
        super.onDestroy();
    }

    /** Device-local encrypted API key; never included in the APK. */
    static final class KeyVault {
        private static final String PREFS = "exa_secure_settings";
        private static final String ALIAS = "electric_connect_exa_aes_gcm_v1";
        private static final String CIPHERTEXT = "encrypted_exa_key";
        private static final String IV = "encrypted_exa_iv";

        private static SharedPreferences prefs(Context c) {
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }

        private static SecretKey key() throws Exception {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            SecretKey existing = (SecretKey) store.getKey(ALIAS, null);
            if (existing != null) return existing;
            KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
            return generator.generateKey();
        }

        static void save(Context c, String value) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            prefs(c).edit()
                .putString(CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .apply();
        }

        static String load(Context c) throws Exception {
            String data = prefs(c).getString(CIPHERTEXT, null);
            String nonce = prefs(c).getString(IV, null);
            if (data == null || nonce == null) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                new GCMParameterSpec(128, Base64.decode(nonce, Base64.NO_WRAP)));
            byte[] clear = cipher.doFinal(Base64.decode(data, Base64.NO_WRAP));
            return new String(clear, StandardCharsets.UTF_8);
        }

        static boolean hasSaved(Context c) {
            return prefs(c).contains(CIPHERTEXT);
        }

        static void clear(Context c) {
            prefs(c).edit().clear().apply();
            try {
                KeyStore store = KeyStore.getInstance("AndroidKeyStore");
                store.load(null);
                store.deleteEntry(ALIAS);
            } catch (Exception ignored) { }
        }
    }
}
