package ir.electricconnect.nativefree;

import org.json.JSONObject;

/** Converts Exa HTTP failures into actionable messages without exposing credentials
 * or echoing arbitrary, potentially sensitive server response bodies. */
final class ExaHttpError {
    private ExaHttpError() {}

    static String describe(int status, String responseBody) {
        String tag = "";
        if (responseBody != null && !responseBody.isEmpty()) {
            try {
                String value = new JSONObject(responseBody).optString("tag", "");
                if (value.matches("[A-Z][A-Z0-9_]{1,79}")) tag = value;
            } catch (Exception ignored) {
                // Some proxies return HTML instead of an Exa JSON error.
            }
        }
        String detail = tag.isEmpty() ? "" : " | Exa: " + tag;
        switch (status) {
            case 400:
                return "پارامترهای درخواست توسط Exa رد شدند (HTTP 400). "
                        + "ممکن است اشکال از تنظیمات جست‌وجوی برنامه باشد." + detail;
            case 401:
                return "احراز هویت Exa رد شد (HTTP 401). "
                        + "کلید فعال را در dashboard.exa.ai/api-keys بررسی و دوباره ذخیره کنید." + detail;
            case 403:
                return "Exa اجازهٔ این درخواست را نداد (HTTP 403). "
                        + "مجوزهای حساب و محدودیت‌های دسترسی را بررسی کنید؛ "
                        + "این خطا به‌تنهایی ثابت نمی‌کند کلید اشتباه است." + detail;
            case 402:
                return "اعتبار یا صورتحساب حساب Exa اجازهٔ جست‌وجو نمی‌دهد (HTTP 402)." + detail;
            case 429:
                return "محدودیت تعداد درخواست‌های Exa فعال شده (HTTP 429). "
                        + "بعداً دوباره امتحان کنید." + detail;
            default:
                if (status >= 500 && status <= 599) {
                    return "خطای موقت سمت سرویس Exa (HTTP " + status + ")." + detail;
                }
                return "Exa درخواست را رد کرد (HTTP " + status + ")." + detail;
        }
    }
}
