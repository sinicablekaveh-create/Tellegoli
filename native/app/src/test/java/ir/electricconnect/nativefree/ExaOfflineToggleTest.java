package ir.electricconnect.nativefree;

import static org.junit.Assert.*;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class ExaOfflineToggleTest {
    @Before public void reset() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("electric_connect_native_free.db");
        context.getSharedPreferences("exa_ui_settings", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void offlineIsDefaultAndSearchWorksWithoutApiKeyOrInternet() {
        try (MainActivity.Store store = new MainActivity.Store(RuntimeEnvironment.getApplication())) {
            store.insertRecord("صنايع كيان", "0912", "سینی کابل", "تهران");
        }
        try (var controller = Robolectric.buildActivity(ExaResearchActivity.class).setup()) {
            ExaResearchActivity activity = controller.get();
            View screen = activity.getWindow().getDecorView();
            CheckBox toggle = checkbox(screen, "Exa روشن — جست‌وجوی اینترنتی با کلید API");
            assertNotNull(toggle);
            assertFalse(toggle.isChecked());
            assertNotNull(text(screen, "جست‌وجوی آفلاین (Exa خاموش)"));
            input(screen, "مثلاً: تولیدکنندگان تابلو برق صنعتی در تهران").setText("کیان");
            text(screen, "جست‌وجوی آفلاین (Exa خاموش)").performClick();
            assertNotNull(text(screen, "1. صنايع كيان"));
            assertNotNull(text(screen, "شماره: 0912"));
            assertTrue(((TextView) org.robolectric.util.ReflectionHelpers.getField(activity,"status"))
                    .getText().toString().contains("Exa خاموش"));
        }
    }

    @Test public void userCanTurnExaOnAndOffAndPreferencePersists() {
        try (var controller = Robolectric.buildActivity(ExaResearchActivity.class).setup()) {
            View screen = controller.get().getWindow().getDecorView();
            CheckBox toggle = checkbox(screen, "Exa روشن — جست‌وجوی اینترنتی با کلید API");
            toggle.setChecked(true);
            assertNotNull(text(screen, "جست‌وجوی واقعی در Exa"));
            assertTrue(RuntimeEnvironment.getApplication().getSharedPreferences(
                    "exa_ui_settings", Context.MODE_PRIVATE).getBoolean("online_enabled",false));
            toggle.setChecked(false);
            assertNotNull(text(screen, "جست‌وجوی آفلاین (Exa خاموش)"));
            assertFalse(RuntimeEnvironment.getApplication().getSharedPreferences(
                    "exa_ui_settings", Context.MODE_PRIVATE).getBoolean("online_enabled",true));
        }
        try (var controller = Robolectric.buildActivity(ExaResearchActivity.class).setup()) {
            View screen = controller.get().getWindow().getDecorView();
            assertFalse(checkbox(screen, "Exa روشن — جست‌وجوی اینترنتی با کلید API").isChecked());
        }
    }

    @Test public void offlineResultsArePaginatedAtFifty() {
        try (MainActivity.Store store = new MainActivity.Store(RuntimeEnvironment.getApplication())) {
            for(int i=0;i<52;i++)store.insertRecord("کابل "+i,"","تجهیزات برق","تهران");
        }
        try (var controller=Robolectric.buildActivity(ExaResearchActivity.class).setup()) {
            View screen=controller.get().getWindow().getDecorView();
            input(screen,"مثلاً: تولیدکنندگان تابلو برق صنعتی در تهران").setText("کابل");
            text(screen,"جست‌وجوی آفلاین (Exa خاموش)").performClick();
            assertNotNull(text(screen,"نمایش ۵۰ نتیجهٔ بعدی (آفلاین)"));
            assertNotNull(text(screen,"50. کابل 2"));
            text(screen,"نمایش ۵۰ نتیجهٔ بعدی (آفلاین)").performClick();
            assertNotNull(text(screen,"51. کابل 1"));
            assertNotNull(text(screen,"52. کابل 0"));
            assertNull(text(screen,"50. کابل 2"));
        }
    }

    private static TextView text(View view,String value) {
        if (view instanceof TextView && value.contentEquals(((TextView)view).getText())) return (TextView)view;
        if (view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) {
                TextView found=text(group.getChildAt(i),value);
                if(found!=null)return found;
            }
        }
        return null;
    }

    private static EditText input(View view,String hint) {
        if(view instanceof EditText && hint.contentEquals(((EditText)view).getHint())) return (EditText)view;
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) {
                EditText found=input(group.getChildAt(i),hint);
                if(found!=null)return found;
            }
        }
        return null;
    }

    private static CheckBox checkbox(View view,String caption) {
        if(view instanceof CheckBox && caption.contentEquals(((CheckBox)view).getText()))return (CheckBox)view;
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) {
                CheckBox found=checkbox(group.getChildAt(i),caption);
                if(found!=null)return found;
            }
        }
        return null;
    }
}
