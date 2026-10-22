package ir.electricconnect.nativefree;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A small native Views design system used across Electric Connect.
 *
 * RTL, Persian labels, scalable sp text, >=48dp interaction targets,
 * contrast, spacing and visual status hierarchy are kept consistent.
 * No WebView, network calls, external assets or additional runtime dependency.
 */
final class ElectricUi {
    private ElectricUi() {}

    static final int INK = Color.rgb(15, 41, 54);
    static final int TEAL = Color.rgb(0, 101, 110);
    static final int LIGHT_TEAL = Color.rgb(232, 246, 245);
    static final int BG = Color.rgb(245, 248, 251);
    static final int BORDER = Color.rgb(219, 228, 233);
    static final int MUTED = Color.rgb(80, 102, 114);
    static final int WHITE = Color.WHITE;
    static final int RED = Color.rgb(158, 39, 49);
    static final int GREEN = Color.rgb(21, 109, 83);
    static final int AMBER = Color.rgb(138, 89, 9);
    static final int SOFT_RED = Color.rgb(255, 240, 241);

    static int dp(Context c, int n) {
        return (int) (c.getResources().getDisplayMetrics().density * n + .5f);
    }

    static GradientDrawable shape(Context c, int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static GradientDrawable outlined(Context c, int fill, int radiusDp) {
        GradientDrawable g = shape(c, fill, radiusDp);
        g.setStroke(dp(c, 1), BORDER);
        return g;
    }

    static GradientDrawable heroGradient(Context c) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(12, 56, 70), Color.rgb(0, 104, 112)});
        g.setCornerRadius(dp(c, 22));
        return g;
    }

    static LinearLayout column(Context c) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return v;
    }

    static LinearLayout row(Context c) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    static TextView text(Context c, String value, int sp, int color, boolean bold) {
        TextView v = new TextView(c);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTextDirection(View.TEXT_DIRECTION_RTL);
        v.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        v.setIncludeFontPadding(true);
        v.setLineSpacing(dp(c, 3), 1f);
        if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setPadding(dp(c, 2), dp(c, 4), dp(c, 2), dp(c, 4));
        return v;
    }

    static LinearLayout card(Context c) {
        LinearLayout v = column(c);
        v.setBackground(outlined(c, WHITE, 18));
        v.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        v.setElevation(dp(c, 1));
        return v;
    }

    static LinearLayout hero(Context c) {
        LinearLayout v = column(c);
        v.setBackground(heroGradient(c));
        v.setPadding(dp(c, 19), dp(c, 20), dp(c, 19), dp(c, 20));
        return v;
    }

    static void addBlock(Context c, LinearLayout parent, View view, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(c, topDp), 0, 0);
        parent.addView(view, lp);
    }

    static void section(Context c, LinearLayout parent, String title, String subtitle) {
        addBlock(c, parent, text(c, title, 19, INK, true), 18);
        if (subtitle != null && !subtitle.isEmpty()) {
            addBlock(c, parent, text(c, subtitle, 13, MUTED, false), 1);
        }
    }

    static TextView badge(Context c, String label, int color, int fill) {
        TextView v = text(c, label, 12, color, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(c, 13), dp(c, 6), dp(c, 13), dp(c, 6));
        v.setBackground(shape(c, fill, 12));
        return v;
    }

    static Button button(Context c, String label, Runnable action) {
        return button(c, label, action, false, false);
    }

    static Button button(Context c, String label, Runnable action,
                         boolean outlinedStyle, boolean destructive) {
        Button v = new Button(c);
        v.setAllCaps(false);
        v.setText(label);
        v.setTextSize(14);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setTextColor(destructive ? RED : outlinedStyle ? INK : WHITE);
        v.setMinHeight(dp(c, 52));
        v.setMinimumHeight(dp(c, 52));
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        v.setBackground(outlinedStyle || destructive
                ? outlined(c, destructive ? SOFT_RED : WHITE, 14)
                : shape(c, TEAL, 14));
        v.setOnClickListener(view -> action.run());
        v.setContentDescription(label);
        return v;
    }

    static EditText field(Context c, String hint, boolean multiline) {
        EditText v = new EditText(c);
        v.setHint(hint);
        v.setHintTextColor(MUTED);
        v.setTextColor(INK);
        v.setTextSize(15);
        v.setTextDirection(View.TEXT_DIRECTION_RTL);
        v.setGravity(Gravity.RIGHT | (multiline ? Gravity.TOP : Gravity.CENTER_VERTICAL));
        v.setSingleLine(!multiline);
        v.setMinHeight(dp(c, multiline ? 112 : 56));
        v.setPadding(dp(c, 15), dp(c, 13), dp(c, 15), dp(c, 13));
        v.setBackground(outlined(c, WHITE, 13));
        v.setContentDescription(hint);
        if (multiline) {
            v.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            v.setMinLines(3);
        }
        return v;
    }

    static LinearLayout header(Context c, String title, String subtitle) {
        LinearLayout group = column(c);
        group.setPadding(dp(c, 18), dp(c, 10), dp(c, 18), dp(c, 14));
        group.setBackground(shape(c, WHITE, 0));
        TextView h = text(c, title, 22, INK, true);
        group.addView(h);
        if (subtitle != null && !subtitle.isEmpty()) {
            group.addView(text(c, subtitle, 13, MUTED, false));
        }
        return group;
    }

    static void styleWindow(Activity activity) {
        activity.getWindow().setStatusBarColor(BG);
        activity.getWindow().setNavigationBarColor(WHITE);
        if (Build.VERSION.SDK_INT >= 26) {
            activity.getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR |
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }
}
