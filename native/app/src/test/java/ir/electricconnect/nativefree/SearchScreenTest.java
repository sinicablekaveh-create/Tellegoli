package ir.electricconnect.nativefree;

import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import org.json.JSONArray;
import org.json.JSONObject;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class SearchScreenTest {
    @Test public void webResultsRenderBeyondTwentyFiveAndFilterAllReturnedHighlights() throws Exception {
        JSONArray rows=new JSONArray();
        for(int n=0;n<50;n++) {
            rows.put(new JSONObject().put("title","كيان "+n).put("url","https://example.com/"+n)
                .put("highlights",new JSONArray().put("تهران")));
        }
        JSONObject response=new JSONObject().put("results",rows);
        try(var controller=Robolectric.buildActivity(ExaResearchActivity.class).setup()) {
            ExaResearchActivity activity=controller.get();
            ReflectionHelpers.setField(activity,"lastResponse",response);
            ReflectionHelpers.setField(activity,"lastMode","auto");
            ReflectionHelpers.callInstanceMethod(activity,"showResults",
                ClassParameter.from(JSONObject.class,response),ClassParameter.from(String.class,"auto"));
            View root=activity.getWindow().getDecorView();
            assertNotNull(text(root,"50. كيان 49"));
            EditText filter=input(root,"جست‌وجو در عنوان، منبع و متن نتایج دریافت‌شده");
            filter.setText("کیان ۴۹ تهران");
            assertNotNull(text(root,"1. كيان 49"));
            assertNull(text(root,"1. كيان 0"));
            filter.setText("");
            assertNotNull(text(root,"50. كيان 49"));
        }
    }

    @Test public void webFilterUsesUnescapedHighlightsAndMatchingDuplicateSources() throws Exception {
        JSONArray rows=new JSONArray()
            .put(new JSONObject().put("title","First").put("url","https://example.com/same")
                .put("highlights",new JSONArray().put("Other")))
            .put(new JSONObject().put("title","Second").put("url","https://example.com/same")
                .put("highlights",new JSONArray().put("C:\\panel/cable\nتهران")));
        JSONObject response=new JSONObject().put("results",rows);
        try(var controller=Robolectric.buildActivity(ExaResearchActivity.class).setup()) {
            ExaResearchActivity activity=controller.get();
            ReflectionHelpers.setField(activity,"lastResponse",response);
            ReflectionHelpers.setField(activity,"lastMode","auto");
            View root=activity.getWindow().getDecorView();
            input(root,"جست‌وجو در عنوان، منبع و متن نتایج دریافت‌شده").setText("C:\\panel/cable تهران");
            assertNotNull(text(root,"1. Second"));
            assertNull(text(root,"1. First"));
        }
    }

    @Test public void telegramGroupsPaginateAndFilteringClearsHiddenSelectionAndPermission() {
        try(var controller=Robolectric.buildActivity(TdlibAccountActivity.class).create()) {
            TdlibAccountActivity activity=controller.get();
            java.util.List<TdlibEngine.ChatInfo> chats=new java.util.ArrayList<>();
            for(int n=0;n<230;n++)chats.add(new TdlibEngine.ChatInfo(n+1,"گروه "+n));
            activity.onChats(chats);
            View root=activity.getWindow().getDecorView();
            assertNotNull(text(root,"100 / 230"));
            text(root,"نمایش گروه‌های بیشتر").performClick();
            assertNotNull(text(root,"200 / 230"));
            text(root,"نمایش گروه‌های بیشتر").performClick();
            assertNotNull(text(root,"230 / 230"));
            text(root,"گروه 229 (230)").performClick();
            android.widget.CheckBox permission=ReflectionHelpers.getField(activity,"permission");
            permission.setChecked(true);
            input(root,"جست‌وجوی گروه در حساب تلگرام...").setText("گروه ۱");
            assertEquals(0L,(long)ReflectionHelpers.getField(activity,"selectedChatId"));
            assertFalse(permission.isChecked());
            input(root,"جست‌وجوی گروه در حساب تلگرام...").setText("");
            text(root,"گروه 0 (1)").performClick();permission.setChecked(true);
            activity.onState("LOGGING_OUT","Test");
            assertEquals(0L,(long)ReflectionHelpers.getField(activity,"selectedChatId"));
            assertFalse(permission.isChecked());
        }
    }

    @Test public void telegramLogoutDismissesPendingSendConfirmation() {
        try(var controller=Robolectric.buildActivity(TdlibAccountActivity.class).create()) {
            TdlibAccountActivity activity=controller.get();
            activity.onChats(java.util.List.of(new TdlibEngine.ChatInfo(17,"Group")));
            View root=activity.getWindow().getDecorView();
            text(root,"Group (17)").performClick();
            input(root,"متن پیام آزمایشی").setText("Test message");
            android.widget.CheckBox permission=ReflectionHelpers.getField(activity,"permission");
            permission.setChecked(true);
            text(root,"بررسی و تأیید ارسال یک پیام").performClick();
            android.app.AlertDialog confirmation=ReflectionHelpers.getField(activity,"sendConfirmation");
            assertTrue(confirmation.isShowing());
            activity.onState("LOGGING_OUT","Test");
            assertFalse(confirmation.isShowing());
        }
    }

    @Test public void repeatedLocalSearchReplacesCardsAndEmptySearchClearsThem() {
        RuntimeEnvironment.getApplication().deleteDatabase("electric_connect_native_free.db");
        try(MainActivity.Store store=new MainActivity.Store(RuntimeEnvironment.getApplication())) {
            store.insertRecord("کیان","0912","کابل","تهران");
        }
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            View root=controller.get().getWindow().getDecorView();
            text(root,"جستجو").performClick();
            root=controller.get().getWindow().getDecorView();
            EditText query=input(root,"عبارت جست‌وجو، عنوان، شماره یا منبع");
            View search=text(root,"جست‌وجو در اطلاعات ذخیره‌شده");
            query.setText("کیان"); search.performClick(); search.performClick();
            assertEquals(1,count(root,"◈ کیان"));
            query.setText("اصفهان"); search.performClick();
            assertEquals(0,count(root,"◈ کیان"));
            assertNotNull(text(root,"نتیجه‌ای در حافظه ثبت نشده است."));
        }
    }

    private static TextView text(View view,String value) {
        if(view instanceof TextView && ((TextView)view).getText().toString().equals(value))return (TextView)view;
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int n=0;n<group.getChildCount();n++) {
                TextView match=text(group.getChildAt(n),value);
                if(match!=null)return match;
            }
        }
        return null;
    }
    private static EditText input(View view,String hint) {
        if(view instanceof EditText && hint.contentEquals(((EditText)view).getHint()))return (EditText)view;
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int n=0;n<group.getChildCount();n++) {
                EditText match=input(group.getChildAt(n),hint);
                if(match!=null)return match;
            }
        }
        return null;
    }
    private static int count(View view,String value) {
        int total=view instanceof TextView && value.contentEquals(((TextView)view).getText())?1:0;
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int n=0;n<group.getChildCount();n++)total+=count(group.getChildAt(n),value);
        }
        return total;
    }
}
