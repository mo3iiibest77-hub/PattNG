# گزارش جامع ممیزی، اصلاح معماری، پیاده‌سازی و تست سیستم لایسنس، شناسایی دستگاه و مدیریت نشست‌های NovaPulse

**وضعیت مأموریت:** اتمام کامل مراحل ممیزی، طراحی، پیاده‌سازی Backend / Database / Admin Panel / Android، مهاجرت ایمن دیتابیس زنده و تأیید تست‌های واحد و همزمانی  
**شاخه فعال پروژه (`PattNG`):** `feat/qsn-3d-smart-stage1` (شناسه اپلیکیشن: `com.quietstorm.ng`، نسخه `2.4.2`)  
**نکته محرمانگی:** در این گزارش هیچ License Key، Session Token، رمز، ایمیل یا UUID مشتری درج نشده است.  
**نکته بیلد APK:** مطابق دستورالعمل پروژه، هیچ بیلد APK روی سرور انجام نمی‌شود و تولید/امضای رسمی APK منحصراً از طریق **GitHub Actions** (`.github/workflows/build.yml`) روی پوش شاخه `feat/qsn-3d-smart-stage1` انجام می‌گردد.

---

## بخش اول: گزارش ممیزی معماری قبلی (پیش از اصلاح)

### A. پروژه Android
1. **ریشه باگ شناسایی یکسان دستگاه (`Utils.kt`):**
   * تابع `getDeviceIdForXUDPBaseKey()` به جای خواندن مقدار واقعی از `ContentResolver`، ثابت متنی `"android_id"` (`Settings.Secure.ANDROID_ID`) را به بایت تبدیل کرده و پس از padding به ۳۲ بایت، با Base64 کدگذاری می‌کرد.
   * نتیجه: **همه گوشی‌ها یک رشته ۴۳ کاراکتری یکسان** به عنوان `device_id` به سرور می‌فرستادند و کنترل ظرفیت دستگاه‌ها عملاً خنثی بود.
2. **احراز هویت ناکافی نشست (`LicenseBackendClient.kt`):**
   * `redeem` و `refreshStatus` فقط `(license_key, device_id)` را ارسال می‌کردند؛ بدون `session_token`، بدون متادیتای دستگاه و بدون اثبات رمزنگاری‌شده.
   * `LicenseBackendClient` کد خطای `429 Too Many Requests` را نیز در بازه `400..499` به عنوان ابطال قطعی تلقی می‌کرد و در صورت Rate Limit موقت، نشست معتبر کاربر را پاک می‌کرد.
3. **ذخیره‌سازی و خروج ناقص:**
   * اطلاعات نشست به‌صورت ساده در MMKV نگهداری می‌شدند.
   * متدهای خروج (`logoutLicense()` و `stopAndWipeLicenseSession()` در `Qs3dViewModel.kt`) و تغییر لایسنس، درخواستی به سرور برای آزادسازی ظرفیت نمی‌فرستادند.
4. **مسیرهای دور زدن گیت لایسنس:**
   * `UrlSchemeActivity` با دریافت `ACTION_VIEW` و `ACTION_SEND` بدون بررسی فعال بودن نشست لایسنس، کانفیگ یا سابسکریپشن خارجی وارد کرده و کاربر را به `MainActivity` هدایت می‌کرد.
   * `LauncherManager` و `CoreServiceManager` پیش از شروع سرویس VPN بررسی مستقل نشست چندپروسه‌ای لایسنس نداشتند.

### B. پروژه Backend (`/opt/novapulse-license/server.py` و `licenses.db`)
1. `handle_redeem` و `/api/v1/license/status` بر پایه `(license_key, device_id)` عمل می‌کردند؛ بدون بررسی توکن نشست، در صورت تکرار `device_id` نشست قبلی بدون بررسی ظرفیت برگردانده می‌شد.
2. `session_token` به صورت متن خام (Plaintext) در ستون `license_devices.session_token` ذخیره و در URL اشتراک (`/api/v1/license/sub/{session_token}?device_id=...`) منتقل می‌شد.
3. `/api/v1/license/by-sub/<token>` بدون احراز هویت پنل اطلاعات کلید و شمارش دستگاه‌ها را برمی‌گرداند.
4. اندپوینت‌های `logout`، مدیریت ظرفیت `max_devices`، لیست دقیق دستگاه‌ها، ابطال تکی/کلی نشست و تاریخچه Audit وجود نداشتند.
5. تراکنش SQLite به جای `BEGIN IMMEDIATE` از تراکنش پیش‌فرض استفاده می‌کرد و `ADMIN_TOKEN` در کد فعال نبود.

### C. وضعیت اولیه دیتابیس و پنل مدیریت
* تعداد کل لایسنس‌ها پیش از مهاجرت: `60` لایسنس (`1`: `57` عدد، `2`: `1` عدد، `3`: `1` عدد، `-1`: `1` عدد).
* تعداد ردیف‌های دستگاه قبلی در `license_devices`: `6` ردیف با یک `device_id` ثابت مشترک (`YW5kcm9pZF9pZAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA`) و توکن متن خام.
* `hm.js` و `xui.js` صرفاً شمارنده و دکمه `rotate` را نمایش می‌دادند و امکان تعیین `max_devices` یا مدیریت نشست‌های تکی نداشتند.

---

## بخش دوم: تغییرات معماری و پیاده‌سازی نهایی

### ۱. Backend جدید و ماژولار (`/opt/novapulse-license/np_license/`)
* **`np_license/security.py`:**
  * تولید و اعتبارسنجی Hash نشست با `HMAC-SHA256` (`hash_session_token`) با استفاده از `TOKEN_SECRET` — توکن خام هرگز در دیتابیس ذخیره نمی‌شود.
  * تولید `session_token`، `session_id` و `challenge_nonce` با انتروپی رمزنگاری‌شده.
  * اعتبارسنجی `is_valid_device_id` همراه با مسدودسازی صریح شناسه ثابت قدیمی (`LEGACY_SHARED_ANDROID_ID = "YW5kcm9pZF9pZAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"`).
  * تأیید امضای ECDSA P-256 (`verify_device_proof`, `parse_p256_public_key`, `canonical_proof_message` با فرمت `v1|<nonce>|<device_id>|<purpose>` و پشتیبانی از DER و P1363).
  * توابع کمکی پنهان‌سازی لاگ و نرمال‌سازی IP (`redact_license_key`, `short_device_id`, `normalize_client_ip`).
* **`np_license/db.py`:**
  * باز کردن اتصال با `isolation_level=None`، `PRAGMA busy_timeout=15000`، `WAL`، `foreign_keys=ON` و مدیر زمینه `tx_immediate` (`BEGIN IMMEDIATE`) برای اتمیک بودن کامل کنترل ظرفیت در درخواست‌های همزمان.
* **`np_license/migrations.py`:**
  * مهاجرت Idempotent با جدول `schema_migrations` (`version=1`, `001_session_security_architecture`).
  * افزودن ستون `max_devices_source`، بازسازی ایمن `license_devices` با تبدیل توکن‌های قدیمی به Hash و برچسب‌گذاری ۶ ردیف قدیمی مشترک با وضعیت **`legacy_ambiguous`**.
  * ایجاد ایندکس یکتای شرطی `idx_license_devices_active_pair ON license_devices(license_id, device_id) WHERE status = 'active'`.
  * ایجاد جداول `auth_challenges` و `license_audit_logs`.
* **`np_license/sessions.py` و `server.py`:**
  * `create_auth_challenge` و الزام چالش + امضای P-256 در `redeem_license_session` و `refresh_session` (با چرخش توکن در refresh).
  * تفکیک دقیق `/api/v1/license/redeem` (ایجاد نشست جدید فقط در صورت آزاد بودن ظرفیت؛ بازگرداندن `403` در صورت پر بودن ظرفیت و جلوگیری از تصاحب نشست فعال بدون توکن معتبر) از `/api/v1/license/status` (بررسی هدرهای نشست، `401 session_revoked` برای نشست‌های باطل/خارج‌شده بدون پاک‌کردن Hash تا علت ابطال دقیق گزارش شود).
  * `logout_session` برای آزادسازی فوری ظرفیت در سرور و ثبت Audit.
  * احراز هویت `authorize_subscription_fetch` برای `/api/v1/license/sub` **صرفاً از طریق هدرهای `Authorization: Bearer <session_token>` و `X-Device-Id`**؛ درخواست‌های قدیمی لینک مستقیم `/sub/<token>` با `401 url_auth_removed` رد می‌شوند.
  * محافظت `/api/v1/license/by-sub/` با احراز هویت پنل ادمین (Bearer پنل HMPanel، Cookie 3x-ui یا `ADMIN_TOKEN` با مقایسه constant-time).
  * APIهای مدیریت برای پنل: لیست دستگاه‌ها (`admin_list_devices`)، تغییر ظرفیت با پیش‌نمایش و سیاست کاهش `revoke_excess`/`keep_excess` (`admin_set_capacity`)، ابطال تکی (`admin_revoke_session`)، ابطال کل (`admin_revoke_all_sessions`) و مشاهده Audit Log بدون افشای کلید/توکن خام (`admin_list_audit_logs`).
  * Rate Limit ضد Brute-Force (`429`) روی اندپوینت‌های عمومی لایسنس.
  * ارائه ماژول مشترک پنل در مسیر `/api/v1/license/assets/panel-core.js` و حفظ همگام‌سازی بدون بازنویسی تنظیمات دستی `max_devices_source`.

### ۲. ارتقای پنل‌های مدیریت (`/opt/novapulse-license/web/`)
* **`panel-core.js` (`window.NovaPulseAdminUI.openDeviceManagerModal`):**
  * دکمه‌های سریع ظرفیت (`1, 2, 3, 5, 10, نامحدود`) و ورودی عددی دلخواه + پیش‌نمایش نشست‌های مازاد پیش از کاهش ظرفیت.
  * جدول کامل دستگاه‌ها (مدل، برند، نسخه اندروید، نسخه اپ، IP، زمان ورود، آخرین فعالیت، وضعیت و علت ابطال) با رندر ایمن `textContent`.
  * دکمه‌های `لغو نشست` (تکی) و `لغو همه نشست‌های فعال` + بخش مشاهده `Audit Log`.
* **`hm.js` و `xui.js`:**
  * ارسال اعتبارنامه صحیح پنل (`Authorization: Bearer` در HM و `credentials: "same-origin"` در 3x-ui) در فراخوانی‌های `by-sub` و افزودن دکمه مدیریت دستگاه‌ها و ظرفیت (`📱 دستگاه‌ها (X / Y)`).

### ۳. تغییرات کلاینت Android (`PattNG-stage1/V2rayNG`)
* **`senpai/DeviceCryptoProvider.kt`:** رابط رمزنگاری و پیاده‌سازی سخت‌افزاری `AndroidKeystoreCryptoProvider` (کلید AES-256-GCM با نام `np_sec_aes_v1` و جفت‌کلید ECDSA P-256 با نام `np_sec_ecdsa_v1`).
* **`senpai/DeviceIdentityManager.kt`:** تولید و نگهداری امن هویت نصب (CSPRNG + Android Keystore + مقدار واقعی `Settings.Secure.ANDROID_ID` به عنوان ورودی کمکی) -> تولید `deviceId` یکتای ۶۴ کاراکتری SHA-256، امضای اثبات P-256 (`signProof`)، متادیتای دستگاه و کلید یکتای `getXudpBaseKey()`.
* **`util/Utils.kt`:** اتصال `getDeviceIdForXUDPBaseKey()` به `DeviceIdentityManager.getXudpBaseKey()`.
* **`senpai/LicenseSessionStore.kt`:** ذخیره رمزنگاری‌شده توکن نشست، کلید لایسنس و صف `pendingLogout` در MMKV چندپروسه‌ای (`MMKV.MULTI_PROCESS_MODE`, شناسه `"np_lic_secure_v2"`).
* **`senpai/LicenseBackendClient.kt`:**
  * پشتیبانی از جریان Challenge + ECDSA Proof در `/redeem` و `/refresh`.
  * ارسال هدرهای `Authorization: Bearer <session_token>` و `X-Device-Id` در `/status`، `/refresh`، `/logout` و `/sub`.
  * حاشیه ProGuard/Gson: اضافه شدن `@SerializedName("nonce")` به `ChallengeResponsePayload` و قانون `-keep class com.v2ray.ang.senpai.LicenseBackendClient$ChallengeResponsePayload { *; }` در `V2rayNG/app/proguard-rules.pro` برای بیلد Release با R8.
  * تفکیک قطعی `401/403` از `429`/5xx/خطای شبکه؛ ارسال Logout امن لایسنس قبلی هنگام تعویض لایسنس.
* **`util/HttpUtil.kt` و `senpai/SubscriptionQuotaStore.kt`:** تزریق خودکار هدرهای نشست برای آدرس اشتراک NovaPulse و پاک‌سازی هرگونه توکن از URL مسیر `/api/v1/license/sub`.
* **`senpai/SmartConnectCoordinator.kt`, `ui/qsn3d/Qs3dViewModel.kt`, `ui/qsn3d/Qs3dLicenseScreen.kt`:**
  * استفاده از `DeviceIdentityManager.getDeviceId(context)`، نمایش ظرفیت نامحدود (`-1` -> `novapulse_unlimited`)، تخلیه `pendingLogout` در `verifyLicenseSessionNow`، و ارسال Logout سروری پیش از پاک‌سازی کامل در `logoutLicense`.
* **بستن بایپس‌ها (`UrlSchemeActivity.kt`, `LauncherManager.kt`, `CoreServiceManager.kt`):**
  * مسدودسازی ایمپورت اکسترنال لینک/کانفیگ و هدایت به `QuietStormActivity` زمانی که `!LicenseBackendClient.isLicenseSessionActive()`.
  * مسدودسازی اجرای سرویس VPN و هسته در `LauncherManager` و `CoreServiceManager` بدون نشست لایسنس فعال.

---

## بخش سوم: بکاپ، مهاجرت دیتابیس و نتایج تست‌های تأییدشده

### ۱. بکاپ و مهاجرت دیتابیس زنده (`/opt/novapulse-license/licenses.db`)
* مسیر بکاپ پیش از اعمال تغییرات: `/opt/novapulse-license/backups/20261115T124949Z/` شامل کپی کامل `licenses.db` (`53248` بایت)، `server.py` (`66618` بایت) و پوشه `web/`.
* وضعیت دیتابیس زنده پس از مهاجرت و بازراه‌اندازی `novapulse-license.service`:
  * سرویس `novapulse-license.service`: `active`
  * نتیجه `PRAGMA integrity_check;`: `ok`
  * جدول `schema_migrations`: `1|001_session_security_architecture`
  * تعداد کل لایسنس‌ها: `60` بدون حذف یا تغییر کلید مشتریان (ظرفیت `-1`: `1` عدد، `1`: `57` عدد، `2`: `1` عدد، `3`: `1` عدد)
  * رکوردهای قدیمی دستگاه: `6` رکورد با وضعیت `legacy_ambiguous`

### ۲. دستورالعمل بازگشت (Rollback) در صورت نیاز
1. توقف سرویس: `systemctl stop novapulse-license.service`
2. بازگردانی فایل‌های بکاپ:
   * `cp /opt/novapulse-license/backups/20261115T124949Z/licenses.db /opt/novapulse-license/licenses.db`
   * `cp /opt/novapulse-license/backups/20261115T124949Z/server.py /opt/novapulse-license/server.py`
   * `rm -rf /opt/novapulse-license/web && cp -r /opt/novapulse-license/backups/20261115T124949Z/web /opt/novapulse-license/web`
3. بازبینی سلامت SQLite (`sqlite3 /opt/novapulse-license/licenses.db "PRAGMA integrity_check;"`) و راه‌اندازی مجدد سرویس: `systemctl start novapulse-license.service`.

### ۳. نتایج تست‌های خودکار و سلامت زنده (پس از آخرین ویرایش)
1. **تست یکپارچه و همزمانی Backend (`python3 /opt/novapulse-license/tests/test_license_security.py`):**
   * `test_admin_capacity_preview_revoke_and_audit_redaction`: `ok`
   * `test_concurrency_20_threads_against_capacity_2`: `ok` (تضمین عدم عبور از ظرفیت ۲ در ۲۰ ترد همزمان)
   * `test_reject_legacy_shared_android_id_and_bad_proof`: `ok`
   * `test_single_device_enforcement_logout_and_replay_protection`: `ok`
   * `test_status_refresh_sub_header_auth_and_bysub_auth`: `ok`
   * `test_unlimited_capacity_and_bruteforce_rate_limit`: `ok`
   * **جمع:** `Ran 6 tests in 1.834s -> OK`
2. **بررسی Syntax فایل‌های پنل (`node --check /opt/novapulse-license/web/panel-core.js /opt/novapulse-license/web/hm.js /opt/novapulse-license/web/xui.js`):** خروج با کد `0` (بدون خطا).
3. **بررسی اندپوینت‌های زنده سرویس و Reverse Proxy:**
   * `http://127.0.0.1:4150/api/v1/license/health` -> `200`
   * `http://127.0.0.1:4150/api/v1/license/assets/panel-core.js` -> `200`
   * `http://127.0.0.1:4150/api/v1/license/by-sub/invalid` -> `401`
   * `http://127.0.0.1:4150/api/v1/license/sub/nps_fake` -> `401`
   * `POST http://127.0.0.1:4150/api/v1/license/status` بدون توکن -> `401`
   * `https://nova.dontbedumb.ir/api/v1/license/assets/panel-core.js` -> `200`
   * `https://nova.dontbedumb.ir/np-hm-license.js` -> `200`
   * `https://127.0.0.1:2087/gXfxEJl46r0576NkI7/np-license-api/assets/panel-core.js` -> `200`
4. **تست‌های واحد JVM اپلیکیشن Android (`./gradlew testPlaystoreDebugUnitTest --no-daemon --console=plain`):**
   * `BUILD SUCCESSFUL in 1m 5s`
   * خروجی XML تست‌ها: **`total=85, passed=85, failed=0, skipped=0`** (شامل تست‌های جدید `DeviceIdentityAndSessionSecurityTest` و `LicenseSecurityAndQuotaTest` برای تفکیک `429` از `401 session_revoked`).
   * **بیلد APK نهایی:** از روی کدهای پوش‌شده روی شاخه `feat/qsn-3d-smart-stage1` توسط **GitHub Actions** (`.github/workflows/build.yml`) اجرا و آرتیفکت‌های امضاشده تولید می‌گردد.
