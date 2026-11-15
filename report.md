# گزارش تخصصی باگ نشست‌ها و فعال‌سازی همزمان چند دستگاه در NovaPulse

## ۱. خلاصه اجرایی (Executive Summary)
در سیستم لایسنسینگ NovaPulse، سقف مجاز اتصال دستگاه‌ها (`max_devices`) روی ۱ تنظیم شده بود و انتظار می‌رفت هر لایسنس منحصراً روی یک گوشی فعال باشد و اتصال دستگاه دوم بلاک شود. با این حال، در تست‌های عملی مشاهده شد که با وارد کردن لایسنس روی گوشی دوم، هر دو دستگاه بدون مانع وارد پنل اشتراک و نشست فعال شده و لیست سرورها را دریافت می‌کنند.

پس از ممیزی کامل کدهای کلاینت اندروید (مخزن PattNG) و بک‌اند لایسنس سرور (`/opt/novapulse-license/server.py`) و دیتابیس `licenses.db`، علت ریشه‌ای کشف شد: **شناسه سخت‌افزاری دستگاه (Device ID) روی تمام گوشی‌های اندرویدی یک مقدار ثابت و تکراری ارسال می‌شود.** بنابراین سرور تمام گوشی‌های عالم را «یک دستگاه واحد» تشخیص داده و سقف دستگاه‌ها هیچ‌گاه تریگر نمی‌شود.

---

## ۲. کالبدشکافی فنی و ریشه اصلی باگ (Root Cause Analysis)

### ۲.۱. باگ بحرانی در کلاینت اندروید (`Utils.kt`)
در متد تولید شناسه دستگاه در مسیر زیر:
`V2rayNG/app/src/main/java/com/v2ray/ang/util/Utils.kt` (خطوط ۳۷۰ تا ۳۷۸):

```kotlin
fun getDeviceIdForXUDPBaseKey(): String {
    return try {
        val androidId = Settings.Secure.ANDROID_ID.toByteArray(Charsets.UTF_8)
        Base64.encodeToString(androidId.copyOf(32), Base64.NO_PADDING.or(Base64.URL_SAFE))
    } catch (e: Exception) {
        LogUtil.e(AppConfig.TAG, "Failed to generate device ID", e)
        ""
    }
}
```

#### خطای مهلک چیست؟
`Settings.Secure.ANDROID_ID` در فریم‌ورک سیستم‌عامل اندروید یک ثابت از نوع String به مقدار متنی `"android_id"` است؛ نه خود شناسه دستگاه!
برای خواندن شناسه یکتای دستگاه در اندروید باید از فراخوانی زیر استفاده شود:
```kotlin
Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
```
اما در کد فعلی، بایت‌های کلمه ثابت `"android_id"` با ۳۲ بایت پدینگ صفر تبدیل به Base64 شده است:
```text
YW5kcm9pZF9pZAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
```
رمزگشایی رشته فوق دقیقاً برابر است با:
`b'android_id\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00'`

### ۲.۲. وضعیت دیتابیس سرور (`licenses.db`)
بررسی رکوردهای جدول `license_devices` روی سرور زنده نشان داد که تمامی رکوردهای ثبت‌شده دارای همین شناسه یکسان هستند:
```text
id | license_id | device_id                                    | session_token                                    | last_seen_at
4  | 38         | YW5kcm9pZF9pZAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA | nps_566868bbaeae315598921979536e7382_6988586c4358 | 1794698603
6  | 6          | YW5kcm9pZF9pZAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA | nps_73fd2352ed2ff2c1085af2cdc9f6cfa5_092b2e6ca079 | 1794744737
13 | 19         | YW5kcm9pZF9pZAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA | nps_b7cd55f7ce0a930ed06c4aa307a9816b_4dec837e7584 | 1794744726
```

### ۲.۳. رفتار بک‌اند سرور در مواجهه با این باگ
در فایل `/opt/novapulse-license/server.py` در متد `handle_redeem`:
```python
existing_dev = conn.execute(
    "SELECT * FROM license_devices WHERE license_id = ? AND device_id = ?",
    (row["id"], device_id),
).fetchone()

if existing_dev is not None:
    # سرور فرض می‌کند این همان دستگاه قبلی است که دوباره درخواست داده!
    conn.execute("UPDATE license_devices SET last_seen_at = ? WHERE id = ?", (now, existing_dev["id"]))
    session_token = existing_dev["session_token"]
else:
    # این بلوک برای کنترل سقف max_devices است که اصلاً فراخوانی نمی‌شود!
    used_count = conn.execute("SELECT count(*) FROM license_devices WHERE license_id = ?", (row["id"],)).fetchone()[0]
    if max_dev > 0 and used_count >= max_dev:
        self.send_json(403, {"status": "device_limit", "message": "سقف دستگاه تکمیل شده است"})
```
چون گوشی دوم هم با شناسه `YW5kcm9pZF9pZ...` می‌آید، شرط `existing_dev is not None` برقرار شده و سرور بدون چک کردن سقف، توکن قبلی را تحویل گوشی دوم می‌دهد و هر دو فعال می‌مانند.

---

## ۳. خلاء‌های معماری در ساختار فعلی نشست‌ها (Session Management Gaps)

1. **نبود مشخصات فینگرپرینت گوشی (Device Metadata):**
   * هیچ مشخصاتی از نوع گوشی کاربر مانند برند (`Build.MANUFACTURER`)، مدل (`Build.MODEL`)، نام سیستم‌عامل و آی‌پی فرستاده نمی‌شود.
2. **نبود مکانیزم مدیریت نشست‌ها در پنل ادمین و UI کاربر:**
   * ادمین یا کاربر در صفحه لایسنس نمی‌تواند ببیند چه مدل گوشی‌هایی وصل هستند و امکان قطع اتصال (Kick/Revoke) یک دستگاه خاص وجود ندارد.
3. **نبود چرخه انقضا یا ابطال هنگام تغییر دستگاه:**
   * اگر کاربری گوشیش را عوض کند، نمی‌تواند به انتخاب خود دستگاه قدیمی را خارج کرده و دستگاه جدید را جایگزین کند.

---

## ۴. طرح فنی و گام‌های اجرایی برای اصلاح (Implementation Plan)

### گام اول: اصلاح کلاینت اندروید (App Side)
1. **تولید شناسه پایدار و یکتای سخت‌افزاری:**
   * ایجاد تابعی در کلاینت که ترکیب زیر را دریافت کرده و هش SHA-256 آن را به عنوان `device_id` تولید کند:
     - `Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)`
     - یک کلید رندوم ذخیره‌شده پایدار در حافظه امن (`EncryptedSharedPreferences` یا `MMKV`)
2. **ارسال مشخصات دستگاه در درخواست فعال‌سازی (`redeem`):**
   * اضافه کردن فیلدهای زیر به بدنه درخواست فعال‌سازی لایسنس:
     - `device_id`: شناسه سخت‌افزاری هش‌شده یکتا
     - `device_model`: مثلاً "Samsung SM-S918B"
     - `os_version`: مثلاً "Android 14 (API 34)"
     - `app_version`: "2.4.2"

### گام دوم: ارتقای بک‌اند سرور و دیتابیس (Server Side)
1. **توسعه اسکیما دیتابیس `license_devices`:**
   * افزودن ستون‌های:
     - `device_name TEXT`
     - `client_ip TEXT`
     - `os_version TEXT`
     - `is_active INTEGER DEFAULT 1`
2. **انفاذ سخت‌گیرانه سقف مجاز (`max_devices Enforcement`):**
   * هر دستگاه جدید یک شناسه کاملاً متفاوت خواهد داشت.
   * اگر تعداد دستگاه‌های ثبت‌شده برابر با `max_devices` باشد:
     - درخواست دستگاه جدید با کد `403` و پیام `سقف مجاز دستگاه‌ها تکمیل شده است` رد می‌شود.
3. **اندپوینت مدیریت و حذف نشست‌ها (Revoke Session):**
   * ایجاد اندپوینت `/api/v1/license/sessions` برای لیست کردن نشست‌ها.
   * ایجاد اندپوینت `/api/v1/license/sessions/revoke` برای ابطال سشن یک دستگاه خاص توسط ادمین یا کاربر.
   * به محض ابطال سشن یک دستگاه، Watchdog کلاینت در اولین بررسی دوره‌ای، وضعیت را رد صلاحیت تشخیص داده و وی‌پی‌ان را فوراً قطع (Disconnect) می‌کند.

---

## ۵. نتیجه‌گیری و وضعیت گیت‌هاب
برای اینکه با پوش کردن گزارش‌ها و مستندات، بیلدهای بیهوده در GitHub Actions تریگر نشوند، تنظیم `paths-ignore` در فایل `.github/workflows/build.yml` فعال گردید تا فایل‌های داکیومنت (`*.md` و پوشه `docs`) بدون تریگر بیلد کامیت شوند.
