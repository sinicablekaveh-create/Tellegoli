package ir.electricconnect.nativefree;

import static org.junit.Assert.*;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
public class ElectricUiRedesignTest {
    @Before public void reset(){
        RuntimeEnvironment.getApplication().deleteDatabase("electric_connect_native_free.db");
        RuntimeEnvironment.getApplication().getSharedPreferences(
              "exa_ui_settings",Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void allFiveBottomDestinationsWorkWithPersianLabels(){
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity screen=controller.get();
            assertNotNull(find(screen.getWindow().getDecorView(),"مرکز ارتباطات صنعت برق"));
            String[] tabs={"خانه","جستجو","مخاطبان","صف پیام","تنظیمات"};
            for(String label:tabs)assertNotNull(find(screen.getWindow().getDecorView(),label));
            find(screen.getWindow().getDecorView(),"جستجو").performClick();
            assertNotNull(find(screen.getWindow().getDecorView(),"جست‌وجو در اطلاعات ذخیره‌شده"));
            find(screen.getWindow().getDecorView(),"مخاطبان").performClick();
            assertNotNull(find(screen.getWindow().getDecorView(),"شماره‌یاب خودکار صنعت برق"));
            assertNotNull(find(screen.getWindow().getDecorView(),"شروع جست‌وجوی خودکار شماره‌های جدید"));
            find(screen.getWindow().getDecorView(),"صف پیام").performClick();
            assertNotNull(find(screen.getWindow().getDecorView(),"پیام‌ها تحت کنترل شما"));
            find(screen.getWindow().getDecorView(),"تنظیمات").performClick();
            assertNotNull(find(screen.getWindow().getDecorView(),"مدیریت داده‌های محلی"));
        }
    }

    @Test public void savedBusinessSurvivesNavigationAndRemainsSearchable(){
        Context context=RuntimeEnvironment.getApplication();
        try(MainActivity.Store store=new MainActivity.Store(context)){
            assertTrue(store.insertRecord("برق کاوه","02112345678","سینی کابل","تهران"));
        }
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=controller.get();
            find(activity.getWindow().getDecorView(),"جستجو").performClick();
            assertNotNull(find(activity.getWindow().getDecorView(),"◈ برق کاوه"));
            find(activity.getWindow().getDecorView(),"مخاطبان").performClick();
            assertNotNull(find(activity.getWindow().getDecorView(),"◈ برق کاوه"));
        }
    }

    @Test public void sharedActionComponentsHaveMinimumAccessibleTouchHeight(){
        Button b=ElectricUi.button(RuntimeEnvironment.getApplication(),
            "جست‌وجو",()->{});
        assertTrue(b.getMinHeight()>=ElectricUi.dp(RuntimeEnvironment.getApplication(),48));
        assertNotNull(b.getContentDescription());
    }

    private static TextView find(View root,String target){
        if(root instanceof TextView && target.contentEquals(((TextView)root).getText()))return (TextView)root;
        if(root instanceof ViewGroup){
            ViewGroup group=(ViewGroup)root;
            for(int i=0;i<group.getChildCount();i++){
                TextView found=find(group.getChildAt(i),target);
                if(found!=null)return found;
            }
        }
        return null;
    }
}
