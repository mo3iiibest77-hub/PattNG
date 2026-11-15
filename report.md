# گزارش جامع ممیزی، تحلیل ریشه باگ‌ها و طرح اصلاح معماری سیستم لایسنس، شناسایی دستگاه و مدیریت نشست‌های NovaPulse

**وضعیت مأموریت:** اتمام مرحله اول (ممیزی کامل و بدون تغییر) و مرحله دوم (طراحی معماری و طرح مهاجرت)  
**تغییرات اعمال‌شده روی دیتابیس زنده / سرویس بک‌اند / سورس اپلیکیشن تا این لحظه:** هیچ (صرفاً به‌روزرسانی مستند `report.md`)  
**نکته محرمانگی:** در این گزارش هیچ License Key، Session Token، ایمیل، UUID یا اطلاعات محرمانه‌ای درج نشده است.

---

## بخش اول: گزارش ممیزی معماری موجود (مرحله ۱)

### A. پروژه Android (`PattNG` — شاخه فعال `feat/qsn-3d-smart-stage1`، نسخه `2.4.2`، `applicationId = "com.quietstorm.ng"`)

#### ۱. ریشه اصلی باگ شناسایی دستگاه
* **فایل:** `V2rayNG/app/src/main/java/com/v2ray/ang/util/Utils.kt` (خطوط `370-378`)
* **تابع:** `getDeviceIdForXUDPBaseKey()`
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
* **علت فنی:** `Settings.Secure.ANDROID_ID` در Android SDK یک ثابت متنی (`"android_id"`) است، نه شناسه یکتای دستگاه. برای خواندن مقدار واقعی باید از `Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)` استفاده می‌شد. کد فعلی همان رشته ثابت `"android_id"` را با padding تا ۳۲ بایت به Base64 تبدیل می‌کند و **روی تمام گوشی‌های دنیا یک رشته ۴۳ کاراکتری یکسان** تولید می‌کند.
* **محل‌های استفاده از این تابع:**
  1. `SmartConnectCoordinator.kt` (خط `492`) هنگام `redeem` و `refreshStatus`
  2. `Qs3dViewModel.kt` (خط `548`) در Watchdog وضعیت لایسنس
  3. `CoreNativeManager.kt` (خط `33-34`) برای `Libv2ray.initCoreEnv(assetPath, deviceId)` (کلید پایه XUDP هسته)

#### ۲. مدل‌های درخواست/پاسخ، مسیرهای API و باگ‌های کلاینت
* **فایل:** `LicenseBackendClient.kt` (خطوط `23-262`)
  * `redeem()` (`/api/v1/license/redeem`) و `refreshStatus()` (`/api/v1/license/status`) تنها فیلدهای `license_key` و `device_id` را می‌فرستند.
  * هیچ `session_token`، مشخصات دستگاه (`manufacturer`، `model`، `os_version`، `api_level`، `app_version`) یا امضای نشست ارسال نمی‌شود.
  * مدل `LicenseRedeemResponse` فاقد فیلد `session_token` یا `session_id` مستقل است؛ توکن نشست صرفاً داخل رشته `subscription_url` جاسازی شده است.
  * **باگ کد خطای HTTP در Watchdog (خط `216`):**
    `isDefinitiveRejection = resp.code in 400..499` کد `429 Too Many Requests` را هم رد قطعی محسوب می‌کند! در صورت برخورد موقت به Rate Limit، اپلیکیشن به‌اشتباه نشست معتبر کاربر را پاک می‌کند.
* **محل ذخیره محلی (`MmkvManager.kt` خطوط `40-77` و `LicenseBackendClient.kt` خطوط `67-114`):**
  * کلید لایسنس (`qsn_saved_license_key`)، شناسه اشتراک فعال (`qsn_active_license_sub_id`)، وضعیت فعال بودن نشست (`qsn_license_session_active`)، لینک کامل سابسکریپشن (شامل توکن و `device_id` در `SUB`) و اطلاعات سهمیه (`qsn_sub_quota_*`) همگی به‌صورت متن عادی در storeهای MMKV ذخیره می‌شوند و از Android Keystore برای ذخیره امن هویت نصب یا توکن استفاده نشده است.

#### ۳. دریافت Subscription، خروج از لایسنس و Watchdog
* **دریافت کانفیگ (`SmartConnectCoordinator.kt` خطوط `486-543`، `AngConfigManager.kt` خطوط `465-547`، `HttpUtil.kt` خطوط `146-206`):**
  * پس از `redeem`، لینک سابسکریپشن بدون نیاز به هدر نشست و صرفاً با فراخوانی HTTP GET به `subscription_url` دانلود و ایمپورت می‌شود.
  * در صفحه تنظیمات (`Qs3dSettingsScreen.kt` خطوط `143-150`)، گزینه «تغییر لایسنس» بدون ارسال درخواست Logout برای لایسنس قبلی، مستقیماً لایسنس جدید را ثبت می‌کند؛ بنابراین ظرفیت لایسنس قبلی روی سرور آزاد نمی‌شود.
* **Watchdog (`Qs3dViewModel.kt` خطوط `540-600`):**
  * هر ۲۰ ثانیه یک‌بار (فقط هنگام روشن بودن VPN) و همچنین در `QuietStormActivity.onResume()` (با throttle ۵ ثانیه) متد `verifyLicenseSessionNow()` را صدا می‌زند.
  * در خطای شبکه یا قطع اینترنت (`IOException`) نشست را پاک نمی‌کند (`isDefinitiveRejection = false`)؛ اما در خطاهای `4xx` متد `forceDisconnectRevokedLicense()` را صدا زده، VPN را متوقف و اطلاعات محلی را پاک می‌کند.
  * **نقص:** چون `/status` توکن نشست را بررسی نمی‌کند و فقط `(license_key, device_id)` چک می‌شود، ابطال یک نشست مجزا یا جایگزینی نشست قابل تشخیص دقیق نیست.
* **خروج از لایسنس (`Qs3dViewModel.kt` خطوط `602-706`):**
  * **باگ بحرانی:** متدهای `logoutLicense()` و `stopAndWipeLicenseSession()` **هیچ درخواستی به سرور ارسال نمی‌کنند**. خروج فقط محلی است (توقف VPN و پاک‌سازی MMKV). پس از یکتا شدن `device_id`، نبود Logout سمت سرور باعث قفل شدن دائمی ظرفیت دستگاه روی سرور خواهد شد.

#### ۴. حفره‌های بایپس گیت لایسنس در Android
* **فایل `AndroidManifest.xml` (خطوط `190-208`) و `UrlSchemeActivity.kt` (خطوط `21-90`):**
  * `UrlSchemeActivity` با `android:exported="true"` تعریف شده و `ACTION_SEND` (`text/plain`) و `ACTION_VIEW` (`novapulse://install-sub`, `novapulse://install-config`) را می‌پذیرد و مستقیماً `AngConfigManager.importBatchConfig(...)` را اجرا کرده و `MainActivity` را باز می‌کند. این مسیر گیت لایسنس را کاملاً دور می‌زند!
* `QSTileService` و `WidgetProvider` (در پروسس `:RunSoLibV2RayDaemon`) بررسی صریحی روی معتبر بودن نشست لایسنس پیش از استارت سرویس ندارند.

#### ۵. تست‌های فعلی (`LicenseSecurityAndQuotaTest.kt`)
* تست‌های فعلی شامل `classifyInput`، `sanitizeForDisplay`، محاسبه درصد سهمیه، ترتیب Logout محلی (`LicenseLogoutOrchestrator`) و تشخیص خطای قطعی در `refreshStatus` هستند؛ اما هیچ تستی برای یکتایی Device ID، ارسال توکن نشست، عدم برخورد `429` به عنوان ابطال، و Logout سروری وجود ندارد.

---

### B. پروژه Backend و Reverse Proxy (`/opt/novapulse-license/server.py` و `hmpanel-nginx`)

* سرویس `/opt/novapulse-license/server.py` (`1647` خط، اجرا با `novapulse-license.service` روی پورت `4150`) پشت `hmpanel-nginx` قرار دارد.
* در Nginx، مسیر `/api/v1/license/` دارای `limit_req zone=api_limit burst=30 nodelay;` است.
* **باگ‌ها و ضعف‌های امنیتی Backend:**
  1. **اتکا به `(license_key, device_id)` بدون احراز هویت نشست:**
     * در `handle_redeem` (خطوط `1312-1391`)، اگر `(license_id, device_id)` قبلاً وجود داشته باشد، بدون بررسی توکن نشست یا ظرفیت، `last_seen_at` آپدیت شده و همان `session_token` تحویل داده می‌شود.
     * اندپوینت `/api/v1/license/status` از همین تابع `handle_redeem(is_status_only=True)` استفاده می‌کند؛ یعنی هر کسی که `license_key` و `device_id` را بداند می‌تواند توکن نشست فعال دستگاه دیگر را دوباره بگیرد (تصاحب نشست).
  2. **نگهداری و انتقال ناامن Session Token:**
     * توکن نشست به‌صورت Plaintext در ستون `license_devices.session_token` ذخیره می‌شود.
     * لینک اشتراک به‌صورت `GET /api/v1/license/sub/{session_token}?device_id={device_id}` ساخته می‌شود که هم توکن و هم شناسه دستگاه را در URL و لاگ‌های Nginx افشا می‌کند.
  3. **عدم وجود احراز هویت در `/api/v1/license/by-sub/<token>` (خطوط `1246-1310`):**
     * هر درخواست‌کننده‌ای که `sub_token` یا `xui_sub_id` را بداند می‌تواند `license_key` و آمار دستگاه‌ها را بدون احراز هویت دریافت کند.
  4. **نبود APIهای مدیریت ظرفیت، نشست و خروج:**
     * هیچ Endpointی برای `POST /api/v1/license/logout` وجود ندارد.
     * هیچ Endpointی برای ویرایش `max_devices`، مشاهده جزئیات نشست‌های ثبت‌شده، ابطال یک نشست تکی، ابطال همه نشست‌ها بدون اجبار به چرخش کلید، و مشاهده تاریخچه Audit وجود ندارد.
     * توابع همگام‌سازی (`sync_from_hmpanel` و `sync_from_xui`) فقط هنگام ساخت اولیه `max_devices` را از `limitIp` (یا `1` وقتی `limitIp <= 0`) مقداردهی می‌کنند و در آپدیت‌های بعدی آن را دست‌نخورده می‌گذارند.
  5. **اتمیک بودن تراکنش همزمان:**
     * با اینکه `DB_LOCK` در سطح Thread وجود دارد، تراکنش SQLite با `BEGIN` پیش‌فرض (Deferred) باز می‌شود؛ استفاده صریح از `BEGIN IMMEDIATE` + `PRAGMA busy_timeout` برای حفاظت کامل در برابر Race Condition ضروری است.
  6. متغیر `ADMIN_TOKEN` در `.env` تعریف شده ولی در کد استفاده نمی‌شود؛ احراز هویت پنل ادمین صرفاً از طریق `_verify_panel_admin` (Bearer توکن HMPanel یا Cookie نشست 3x-ui) انجام می‌شود.

---

### C. ساختار دیتابیس `/opt/novapulse-license/licenses.db` (داده‌های آماری — کاملاً بدون افشای اسرار)

* **جدول `licenses`:** مجموعاً `60` رکورد (`59` رکورد `source='hmpanel'` و `1` رکورد `source='xui'`).
  * توزیع `max_devices`:
    * `1` دستگاه: `57` لایسنس
    * `2` دستگاه: `1` لایسنس
    * `3` دستگاه: `1` لایسنس
    * `-1` (نامحدود): `1` لایسنس
  * وضعیت: `0` رکورد `revoked=1` و `4` رکورد با `panel_enabled=0`.
  * Indexهای موجود: `idx_licenses_xui_sub`، `idx_licenses_sub_token` و ایندکس‌های `UNIQUE` روی `license_key` و `hmpanel_client_id`.
* **جدول `license_devices`:**
  * مجموعاً `6` رکورد فعال مربوط به `6` لایسنس مجزا از `hmpanel` (چهار مورد با `max_devices=1`، یک مورد با `max_devices=3` و یک مورد با `max_devices=-1`).
  * **تمام ۶ رکورد** دارای یک `device_id` ثابت ۴۳ کاراکتری یکسان (ناشی از باگ `Utils.kt`) و `session_token` متن خام هستند.
  * ستون‌های متادیتای دستگاه (برند، مدل، نسخه اندروید، نسخه اپ، IP، وضعیت نشست، تاریخ/دلیل ابطال) و جدول رویدادهای ممیزی (`Audit`) هنوز وجود ندارند.
* **مقدار `limitIp` در پنل‌های بالادستی:**
  * در Postgres پنل HM و SQLite پنل 3x-ui هر دو `56` کاربر دارای `limitIp = 0`، یک کاربر `1`، یک کاربر `2` و یک کاربر `3` هستند. طبق سیاست فعلی، `limitIp <= 0` هنگام ساخت لایسنس به `max_devices = 1` نگاشت می‌شود.

---

### D. پنل مدیریت (`web/hm.js` و `web/xui.js`)

* در حال حاضر هر دو اسکریپت صرفاً کلید لایسنس، برچسب خلاصه `used_devices / max_devices`، دکمه کپی، و دکمه «تغییر رندوم و قطع اتصال» (`/rotate`) را نمایش می‌دهند (و در `xui.js` مانیتور کاربران بدون لایسنس نیز فعال است).
* **موارد مفقود:**
  * ادمین نمی‌تواند هنگام ساخت یا ویرایش لایسنس مقدار `max_devices` را تعیین یا تغییر دهد.
  * جدول لیست دستگاه‌های متصل (مدل، برند، نسخه اندروید، نسخه اپ، IP، اولین ورود، آخرین فعالیت، وضعیت) وجود ندارد.
  * دکمه ابطال تک‌دستگاه، ابطال همه نشست‌ها، و نمایش پیش‌نمایش کاهش ظرفیت وجود ندارد.

---

## بخش دوم: طراحی معماری هدف و برنامه اجرا (مرحله ۲، ۳، ۴ و ۵)

### ۱. شناسه مستقل و امن دستگاه در Android (`DeviceIdentityManager`)
* **اصلاح XUDP Base Key:** اصلاح `Utils.getDeviceIdForXUDPBaseKey(context)` تا از شناسه واقعی نصب/دستگاه استفاده کند و همیشه یک رشته ۳۲ بایتی معتبر Base64 URL-safe یکتا برگرداند (بدون مقدار ثابت `"android_id"`).
* **تولید و نگهداری هویت نصب (`Install Identity`):**
  1. تولید یک شناسه تصادفی رمزنگاری‌شده (`UUIDv4` با `SecureRandom`) در اولین اجرا.
  2. رمزنگاری با کلید AES-256-GCM ساخته‌شده داخل **Android Keystore** (`KeyGenParameterSpec`, `minSdk = 24`) و ذخیره متن رمزنگاری‌شده به همراه IV در `SharedPreferences` خصوصی اپلیکیشن (ثابت در برابر بسته شدن برنامه و ری‌استارت گوشی، بدون افشای متن خام).
  3. خواندن امن `Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)` با در نظر گرفتن اینکه ممکن است `null` یا تکراری باشد (به عنوان ورودی تکمیلی).
  4. ترکیب و هش کردن یک‌طرفه با SHA-256:  
     `device_id = SHA-256("novapulse:dev:v2:" + installSecretUuid + ":" + safeAndroidId)` (۶۴ کاراکتر هگز).  
     هیچ اطلاعات خام یا حساسی از گوشی به سرور ارسال نمی‌شود و هر نصب یک `device_id` کاملاً مستقل و غیرقابل حدس دارد.

### ۲. متادیتای دستگاه (Device Telemetry)
کلاینت همراه با درخواست‌های `redeem` و `status` شیء `device_info` را ارسال می‌کند:
* `manufacturer` (`Build.MANUFACTURER`)
* `model` (`Build.MODEL`)
* `os_version` (`Build.VERSION.RELEASE`)
* `api_level` (`Build.VERSION.SDK_INT`)
* `app_version` (`BuildConfig.VERSION_NAME`)

سرور در جدول ارتقایافته `license_devices` مقادیر زیر را ثبت می‌کند:
* `device_id`, `device_id_prefix`, `manufacturer`, `model`, `os_version`, `api_level`, `app_version`, `session_token_hash`, `status` (`active`, `revoked`, `logged_out`, `legacy_ambiguous`), `first_seen_at`, `last_seen_at`, `last_ip` (استخراج از `X-Real-IP` / `X-Forwarded-For` صرفاً به‌عنوان متادیتای امنیتی، نه شناسه اصلی)، `revoked_at`, `revoke_reason`.

### ۳. احراز هویت مستقل نشست‌ها و محافظت در برابر تصاحب نشست
* **توکن نشست و نگهداری Hash:**
  * در `POST /api/v1/license/redeem` (فقط در صورت داشتن ظرفیت مجاز)، یک توکن تصادفی با انتروپی بالا (`nps2_` + ۴۸ کاراکتر hex تصادفی) تولید شده، **تنها یک بار به کلاینت مجاز** داده می‌شود و در دیتابیس **فقط `HMAC-SHA256(TOKEN_SECRET, raw_token)`** در ستون `session_token_hash` ذخیره می‌گردد.
  * کلاینت `session_token` را نیز به کمک Android Keystore به‌صورت رمزنگاری‌شده روی دستگاه ذخیره می‌کند.
* **تفکیک دقیق مسیرها و جلوگیری از Session Hijacking:**
  1. **`POST /api/v1/license/redeem`:**
     * فقط با `(license_key, device_id)` یک نشست **جدید** می‌سازد اگر و فقط اگر دستگاه در حال حاضر نشست `active` نداشته باشد و ظرفیت `active_devices < max_devices` اجازه دهد.
     * اگر ظرفیت پر باشد (`active_devices >= max_devices`)، خطای `403` با `status: "device_limit"` برمی‌گرداند و نشست‌های فعال قبلی دست‌نخورده می‌مانند.
     * اگر **همین `device_id`** از قبل نشست `active` داشته باشد: سرور بدون دریافت `session_token` معتبر فعلی، **هرگز توکن نشست را دوباره افشا یا بازتولید نمی‌کند** (تا صرف دانستن `license_key` و حدس/کپی `device_id` نتواند نشست فعال را تصاحب کند).
  2. **`POST /api/v1/license/status`:**
     * **الزام احراز هویت با هر سه مورد:** `license_key` + `device_id` + `session_token` (در هدر `Authorization: Bearer <token>` یا `X-Session-Token` و `X-Device-Id`).
     * توکن یک دستگاه روی دستگاه دیگری با `device_id` متفاوت **هرگز** معتبر نیست (`403`).
     * نشست `revoked` یا `logged_out` فوراً با `403` (`status: "session_revoked"`) رد می‌شود تا Watchdog اتصال VPN را قطع کند.
     * کلاینت کد `429` را از `401/403/404` تفکیک می‌کند تا Rate Limit موقت باعث Logout اشتباهی نشود.
  3. **`POST /api/v1/license/logout`:**
     * با `(device_id, session_token)` معتبر، سرور وضعیت نشست را در تراکنش اتمیک به `logged_out` تغییر داده، رویداد Audit ثبت می‌کند و ظرفیت دستگاه فوراً آزاد می‌شود.
     * در سمت کلاینت: ابتدا Logout به سرور ارسال و تأیید دریافت می‌شود؛ سپس VPN متوقف و اطلاعات محلی پاک می‌گردد (اگر اینترنت قطع بود، کلاینت به کاربر اطلاع می‌دهد که آزادسازی ظرفیت در سرور نیازمند اینترنت است و اطلاعات لازم را برای تلاش مجدد امن یا آزادسازی از پنل حفظ می‌کند).

### ۴. کنترل اتمیک ظرفیت `max_devices` در سرور
* مرجع اصلی: `licenses.max_devices` در دیتابیس سرور (`1` به‌عنوان پیش‌فرض برای لایسنس جدید، مگر اینکه ادمین مقدار دیگری تعیین کرده باشد؛ `-1` برای نامحدود).
* استفاده از `with DB_LOCK:` به همراه `conn.execute("BEGIN IMMEDIATE")` در SQLite برای شمارش `COUNT(*) WHERE license_id = ? AND status = 'active'` و ثبت نشست جدید؛ تضمین می‌کند حتی در صدها درخواست همزمان، تعداد نشست‌های پذیرفته‌شده از `max_devices` بیشتر نشود.

### ۵. بستن دریافت Subscription خارج از اپلیکیشن و مسیرهای بایپس
* **تحویل Subscription نشست‌محور:**
  * مسیر جدید `GET /api/v1/license/sub` احراز هویت را از **هدرهای `Authorization: Bearer <session_token>` و `X-Device-Id: <device_id>`** می‌خواند و دیگر توکن و شناسه دستگاه در URL قرار نمی‌گیرند (جلوگیری از ثبت توکن در لاگ‌های دسترسی و اشتراک‌گذاری لینک خام).
  * متدهای `AngConfigManager.updateConfigViaSub` و `SubscriptionQuotaStore` برای درخواست‌های دامنه بک‌اند NovaPulse هدرهای نشست را تزریق می‌کنند.
  * مسیر `UrlSchemeActivity` برای وارد کردن مستقیم لینک/کانفیگ خارجی بدون لایسنس فعال محدود/مسدود می‌شود تا هیچ لینک سابسکریپشن یا کانفیگ مستقیمی نتواند گیت لایسنس را دور بزند.
* **محافظت `by-sub`:** محدودسازی افشای اطلاعات حساس در اندپوینت `/by-sub` (نمایش وضعیت و کلید صرفاً برای پنل‌های احراز هویت‌شده با `_verify_panel_admin` و حفظ سازگاری کنترل‌شده پورتال عمومی `/p/<token>` تنها در سطح مجاز).
* **نکته شفاف درباره لایه Xray (3X-UI / HMPanel):**
  * تا زمانی که یک دستگاه مجاز است، سرور کانفیگ‌های فیلترشده VLESS+TLS را به اپ می‌دهد. اگر کاربری بعداً ابطال شود:
    * اپلیکیشن NovaPulse در اولین چرخه Watchdog (`status`) یا رفرش، نشست باطل‌شده را تشخیص داده، VPN را قطع و دیتای محلی را پاک می‌کند.
    * اگر کاربری به‌طور تهاجمی با ابزارهای سطح روت UUID را از حافظه استخراج کرده باشد، ادمین می‌تواند با گزینه «قطع فوری و چرخش UUID» (`rotate_uid=true`) دسترسی لایه Xray را هم در آن واحد مسدود کند.

### ۶. قابلیت‌های پنل مدیریت (`hm.js` و `xui.js` + APIهای ادمین `server.py`)
* **ساخت و ویرایش ظرفیت:** انتخاب سریع (`1`, `2`, `3`, `5`, `10`, نامحدود `-1` یا عدد دلخواه) + مقدار پیش‌فرض `1`.
* **مدیریت کامل نشست‌ها در مودال هر کاربر:**
  * نمایش لیست دستگاه‌های متصل و سابقه نشست‌ها شامل: برند و مدل گوشی، نسخه اندروید، نسخه اپلیکیشن، IP ثبت‌شده، اولین ورود، آخرین فعالیت، وضعیت (`فعال` / `ابطال‌شده` / `خارج‌شده` / `قدیمی مبهم`).
  * دکمه **«قطع نشست»** برای ابطال تکی هر دستگاه و دکمه **«ابطال تمام نشست‌ها»** (با ثبت Audit).
* **سیاست کاهش ظرفیت (Capacity Reduction):**
  * هنگام کاهش `max_devices` زیر تعداد نشست‌های فعال فعلی، ابتدا پیش‌نمایش (`preview=true`) به ادمین نمایش داده می‌شود: **قدیمی‌ترین نشست‌های معتبر حفظ** و نشست‌های مازاد به‌صورت اتمیک با ثبت رویداد Audit و دلیل `capacity_reduced` غیرفعال (`revoked`) می‌شوند.

### ۷. طرح مهاجرت امن دیتابیس قدیمی (مرحله ۵)
* **گام اول:** تهیه Backup کامل و تست شده از `/opt/novapulse-license/licenses.db` قبل از هر تغییر.
* **وضعیت فعلی داده‌ها:** از `60` لایسنس، `54` لایسنس هیچ دستگاهی ندارند و `6` لایسنس دارای رکورد قدیمی با `device_id` ثابت مشترک هستند.
* **سیاست ایمن:**
  1. هیچ لایسنس مشتری حذف یا باطل نمی‌شود و ارتباط `license_key` با `sub_token` / `xui_sub_id` کاملاً حفظ می‌شود.
  2. توکن‌های ۶ رکورد قدیمی به hash تبدیل شده و وضعیت این ۶ ردیف به **`legacy_ambiguous`** علامت‌گذاری می‌شود (و هیچ‌کدام به‌صورت تصادفی به یک گوشی نسبت داده نمی‌شوند) تا در پنل ادمین با برچسب واضح برای تصمیم‌گیری ادمین نمایش داده شوند.
  3. مهاجرت با یک اسکریپت idempotent (قابل تکرار و قابل بازگشت از بکاپ) همراه با گزارش دقیق آماری انجام خواهد شد.

### ۸. برنامه تست‌های اجباری (مرحله ۶)
پس از پیاده‌سازی، تمام ۸ سناریو به‌صورت تست‌های خودکار واحد/یکپارچه و تست زنده روی سرور بررسی و مستند خواهند شد:
1. ظرفیت ۱ دستگاه (ورود A، رد شدن B، ادامه کار A)
2. ظرفیت ۲ دستگاه (ورود A و B، رد شدن C، کار مستقل A و B)
3. خروج و جایگزینی (خروج واقعی A با تأیید سرور، ورود B، عدم اعتبار توکن قبلی A)
4. ابطال از پنل ادمین (ابطال یکی از دو دستگاه فعال، قطع شدن خودکار VPN دستگاه ابطال‌شده، ادامه کار دستگاه دیگر)
5. درخواست‌های همزمان (`Race Condition` با درخواست‌های موازی روی یک لایسنس محدود)
6. یکتایی و پایداری Device ID بین دو نصب مستقل و بعد از اجرای مجدد
7. دستکاری امنیتی (توکن نامعتبر، توکن ابطالشده، توکن دستگاه دیگر، کلید نامعتبر، سشن منقضی، تکرار/Replay درخواست، تغییر Device ID، دسترسی مستقیم به Subscription URL بدون هدر نشست)
8. تست رگرسیون موتور اتصال VPN، اسکن Cloudflare/CloudFront، اجرای کامل Unit Testهای Android و بررسی لاگ‌های سرویس بدون افشای کلید/توکن.
