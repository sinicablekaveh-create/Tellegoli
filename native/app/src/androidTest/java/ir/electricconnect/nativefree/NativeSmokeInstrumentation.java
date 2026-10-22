package ir.electricconnect.nativefree;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.TextView;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Real Android/JNI smoke, without account credentials, member access or sending. */
public final class NativeSmokeInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args){super.onCreate(args);arguments=args;start();}
    private static void require(boolean value,String detail){if(!value)throw new AssertionError(detail);}
    private TextView find(View view,String label){
        if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return (TextView)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            TextView found=find(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;
        }
        return null;
    }
    private void click(Activity activity,String label){runOnMainSync(()->{
        TextView view=find(activity.getWindow().getDecorView(),label);
        require(view!=null,"Missing action: "+label);view.performClick();
    });waitForIdleSync();}
    private MainActivity launch(){
        Intent launch=new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity=(MainActivity)startActivitySync(launch);waitForIdleSync();return activity;
    }
    @Override public void onStart(){
        Bundle result=new Bundle();MainActivity activity=null;
        try {
            Context app=getTargetContext();
            require(app.getPackageName().endsWith(".sandbox"),"Smoke only runs on isolated sandbox package");
            System.loadLibrary("tdjni");
            require(Client.execute(new TdApi.SetLogVerbosityLevel(0)) instanceof TdApi.Ok,"TDLib JNI execute failed");
            long contacts,drafts;int queue;
            try(MainActivity.Store store=new MainActivity.Store(app)){
                contacts=store.count("records");drafts=store.count("drafts");
                List<String> before=LeadKeywords.imported(new DiscoveryData(store));
                result.putString("keywords_before",before.toString());
                if(arguments!=null&&"true".equals(arguments.getString("expectPersisted")))
                    require(before.contains("مجری کابل کشی"),"Keywords lost after process restart/reinstall");
            }
            queue=ScheduledQueue.snapshot(app).size();
            activity=launch();final MainActivity main=activity;
            runOnMainSync(()->{
                View root=main.getWindow().getDecorView();
                for(String label:new String[]{"جست‌وجوگر خودکار","صف و ارسال به گروه‌ها","مخاطبان و شماره‌های تماس","خروجی اکسل"})
                    require(find(root,label)!=null,"Missing home tool: "+label);
            });
            Bitmap screenshot=getUiAutomation().takeScreenshot();
            if(screenshot!=null)try(OutputStream out=new FileOutputStream(new File(app.getFilesDir(),"smoke-home.png"))){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}
            click(activity,"مخاطبان");click(activity,"جست‌وجوی آفلاین در شماره‌های ذخیره‌شده");
            ScheduledQueueActivity queueScreen=(ScheduledQueueActivity)startActivitySync(
                new Intent(app,ScheduledQueueActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            click(queueScreen,"افزودن این پیام به صف");
            require(queue==ScheduledQueue.snapshot(app).size(),"Queue accepted an unapproved message");
            runOnMainSync(queueScreen::finish);waitForIdleSync();
            click(activity,"جستجو");
            File keywords=new File(app.getFilesDir(),"smoke-keywords.xlsx");
            try(OutputStream out=new FileOutputStream(keywords)){
                ExcelWorkbook.write(out,"کلیدواژه",Arrays.asList(new String[]{"کلیدواژه"},
                    new String[]{"پیمانکار برق صنعتی"},new String[]{"مجری کابل کشی"}));
            }
            runOnMainSync(()->main.onActivityResult(7346,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(keywords))));
            boolean imported=false;long until=System.currentTimeMillis()+20000;
            while(System.currentTimeMillis()<until){
                try(MainActivity.Store store=new MainActivity.Store(app)){
                    if(LeadKeywords.imported(new DiscoveryData(store)).contains("مجری کابل کشی")){imported=true;break;}
                }Thread.sleep(100);
            }
            require(imported,"Direct XLSX activity import failed");waitForIdleSync();
            File workbook=new File(app.getFilesDir(),"smoke-combined.xlsx");
            runOnMainSync(()->main.onActivityResult(7345,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(workbook))));
            until=System.currentTimeMillis()+20000;
            boolean valid=false;
            while(System.currentTimeMillis()<until){
                try(ZipFile zip=new ZipFile(workbook)){valid=zip.getEntry("xl/worksheets/sheet1.xml")!=null;if(valid)break;}
                catch(IOException incomplete){Thread.sleep(100);}
            }
            require(valid,"Combined XLSX activity export failed");
            runOnMainSync(main::finish);waitForIdleSync();
            activity=launch();
            try(MainActivity.Store store=new MainActivity.Store(app)){
                require(contacts==store.count("records"),"Contact data changed during smoke");
                require(drafts==store.count("drafts"),"Draft data changed during smoke");
                require(LeadKeywords.imported(new DiscoveryData(store)).contains("مجری کابل کشی"),"SQLite keywords missing after restart");
            }
            require(queue==ScheduledQueue.snapshot(app).size(),"Queue changed during smoke");
            result.putString("smoke_result","PASS");
            result.putString("checks","Native TDLib JNI; four home tools; offline contacts; XLSX activity import/export; activity restart; SQLite/queue preservation");
            result.putLong("contacts_preserved",contacts);result.putLong("drafts_preserved",drafts);
            result.putString("live_account_tests","NOT RUN: no Telegram/Exa credentials");
            finish(Activity.RESULT_OK,result);
        }catch(Throwable error){
            result.putString("smoke_result","FAIL");result.putString("failure",error.toString());finish(Activity.RESULT_CANCELED,result);
        }finally {if(activity!=null){Activity last=activity;runOnMainSync(last::finish);}}
    }
}
