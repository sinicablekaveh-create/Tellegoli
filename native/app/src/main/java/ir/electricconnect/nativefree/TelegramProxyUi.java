package ir.electricconnect.nativefree;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

/** One compact dialog shared by both Telegram screens. */
final class TelegramProxyUi {
    private TelegramProxyUi(){}

    static void show(Activity activity,TdlibEngine engine,TextView status) {
        EditText link=ElectricUi.field(activity,
            "لینک پروکسی (tg://proxy یا tg://socks)",false);
        link.setSingleLine(true);
        link.setSaveEnabled(false);
        link.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        link.setPadding(ElectricUi.dp(activity,13),ElectricUi.dp(activity,9),
            ElectricUi.dp(activity,13),ElectricUi.dp(activity,9));
        new AlertDialog.Builder(activity)
            .setTitle("پروکسی تلگرام")
            .setMessage("لینک پروکسی مورداعتماد خودت را وارد کن. فقط اتصال TDLib تغییر می‌کند.")
            .setView(link)
            .setPositiveButton("فعال‌سازی",(dialog,which)->
                engine.setTelegramProxyLink(link.getText().toString().trim(),
                    (ok,message)->{
                        if(!activity.isFinishing()&&!activity.isDestroyed()) {
                            status.setText(message);
                            android.widget.Toast.makeText(activity,message,
                                android.widget.Toast.LENGTH_LONG).show();
                        }
                    }))
            .setNegativeButton("قطع پروکسی",(dialog,which)->
                engine.disableTelegramProxy((ok,message)->{
                    if(!activity.isFinishing()&&!activity.isDestroyed()){
                        status.setText(message);
                        android.widget.Toast.makeText(activity,message,
                            android.widget.Toast.LENGTH_LONG).show();
                    }
                }))
            .setNeutralButton("انصراف",null)
            .show();
    }
}
