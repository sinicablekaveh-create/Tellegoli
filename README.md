# Tellegoli — تلگولی

اپلیکیشن مستقل اندروید با رابط فارسی برای جستجوی مخاطبان عمومی صنعت برق، گروه‌های عمومی تلگرام، ورود و خروجی اکسل، ورود به حساب شخصی با TDLib و صف محدود پیام‌های صریحاً تأییدشده.

## نسخهٔ کد
- Android package ID: `ir.electricconnect.tellegoli` — مستقل از Electric Connect اصلی
- Version: `0.10.2` (`versionCode=21`)
- Java: 17 | AGP: 8.9.1 | Gradle: 8.11.1 | compileSdk: 35
- Source: `native/app/src/main/`
- Tests: `native/app/src/test/`
- Codemagic workflow: `tellegoli`? **از نام واقعی `electric-connect-0102-android` در `codemagic.yaml` استفاده کنید.**

## ساخت APK با Codemagic
1. در [Codemagic](https://codemagic.io/) مخزن عمومی [Tellegoli](https://github.com/sinicablekaveh-create/Tellegoli) را متصل کنید.
2. ساخت را روی شاخهٔ `main` و workflow «Tellegoli 0.10.2 - Tests, lint and standalone APK» اجرا کنید.
3. Codemagic ابتدا `testDebugUnitTest` و `lintDebug` سپس `assembleDebug` را اجرا می‌کند.
4. فقط اگر تست‌ها، Lint و بررسی APK موفق باشند، فایل `dist-codemagic/tellegoli.apk` در **Artifacts** ساخته می‌شود؛ گزارش تست‌ها نیز منتشر می‌شوند.
5. نسخهٔ Debug برای نصب آزمایشی است؛ برای توزیع مداوم به کلید امضای پایدار نیاز است.

## قابلیت‌ها
- جستجوی شماره تماس **عمومی کسب‌وکارها** با حذف تکراری و ذخیرهٔ منبع؛ نه استخراج شمارهٔ اعضای گروه‌ها.
- کشف گروه‌های **عمومی** تلگرام با کلیدواژه‌های برق، مجزا از گروه‌هایی که حساب واقعاً عضو آنهاست.
- ورودی و خروجی فایل Excel XLSX، ثبت داده در SQLite و پشتیبان‌گیری.
- TDLib برای ورود به حساب شخصی، مشاهدهٔ چت‌ها و گروه‌ها؛ اعتبارسنجی با حساب واقعی هنوز لازم است.
- صف پیام صرفاً برای گروه‌های منتخب و با تأیید کاربر، فاصلهٔ **حداقل** ۸ دقیقه؛ WorkManager زمان دقیق را تضمین نمی‌کند.
- Exa اختیاری است؛ منابع عمومی رایگان بدون کلید Exa نیز در سورس وجود دارند.

## حریم خصوصی و ایمنی
**هیچ‌گاه** api_hash، api_id خصوصی، توکن، گذرواژه، Keystore، فایل نشست TDLib، شماره‌های شخصی یا دادهٔ پشتیبان را به مخزن عمومی Commit نکنید. اطلاعات ورود باید فقط هنگام استفاده و در حافظهٔ خصوصی برنامه وارد شوند.

## وضعیت اعتبارسنجی
این مخزن انتقال سورس برنامه از پروژهٔ Electric Connect، شاخهٔ `feature/simple-search-unified-excel-import-20261022` (commit `96310d9f1ab912a9ad42fd9263a5753f43628ebd`) است. انجام انتقال به‌معنای قبولی تست‌ها، موفقیت ساخت، نصب روی گوشی یا تست حساب Telegram نیست؛ تنها گزارش واقعی Codemagic می‌تواند نتیجهٔ ساخت را تأیید کند.
