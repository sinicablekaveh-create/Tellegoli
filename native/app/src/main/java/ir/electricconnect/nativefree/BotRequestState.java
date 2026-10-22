package ir.electricconnect.nativefree;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Request ownership and send limits survive replacement of the bot screen. */
final class BotRequestState {
    private static final long COOLDOWN_MS = 8L * 60L * 1000L;
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private final SharedPreferences prefs;
    private final LongSupplier clock;

    BotRequestState(SharedPreferences prefs, LongSupplier clock) {
        this.prefs = prefs;
        this.clock = clock;
    }

    String begin(boolean sending) {
        if (!BUSY.compareAndSet(false, true)) return "درخواست دیگری در حال اجراست.";
        long now = clock.getAsLong();
        long until = prefs.getLong("retry_until", 0L);
        if (sending) {
            long success = prefs.getLong("last_success", 0L);
            if (success > 0) until = Math.max(until, success + COOLDOWN_MS);
        }
        if (until > now) {
            BUSY.set(false);
            return "محدودیت زمانی تلگرام؛ " + ((until - now + 999) / 1000) + " ثانیه باقی‌مانده.";
        }
        return null;
    }

    @SuppressLint("ApplySharedPref") // Called on the worker; flush limits before releasing request ownership.
    void finish(boolean sending, boolean success, int retrySeconds) {
        try {
            long now = clock.getAsLong();
            SharedPreferences.Editor edit = prefs.edit();
            if (sending && success) edit.putLong("last_success", now);
            if (retrySeconds > 0) edit.putLong("retry_until", now + retrySeconds * 1000L);
            // Persist before releasing ownership, independently of Activity lifecycle.
            edit.commit();
        } finally {
            BUSY.set(false);
        }
    }
}
