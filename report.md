# گزارش جامع ممیزی، اصلاح معماری، پیاده‌سازی و تست سیستم لایسنس، شناسایی دستگاه و مدیریت نشست‌های NovaPulse

**وضعیت مأموریت:** اتمام کامل مراحل ممیزی، طراحی، پیاده‌سازی Backend / Database / Admin Panel / Android، درگاه امن و پرسرعت دانلود مستقیم APK روی سرور، مهاجرت ایمن دیتابیس زنده و تأیید تست‌های واحد و همزمانی  
**شاخه فعال پروژه (`PattNG`):** `feat/qsn-3d-smart-stage1` (شناسه اپلیکیشن: `com.quietstorm.ng`، نسخه `2.4.5`, `versionCode 753`)  
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
  * **حالت سازگاری موقت نسخه‌های قدیمی (`ALLOW_LEGACY_CLIENT_COMPAT=1`):** امکان ورود مجدد کاربران اپلیکیشن قدیمی (`v2.4.2` و قبل‌تر) با **همان کدهای لایسنس قبلی و بدون تغییر کلید** تا زمان نصب نسخه `v2.4.3`، با ایزوله‌سازی کامل از توکن‌های نسخه جدید (توکن‌های نسخه جدید هرگز در URL کار نمی‌کنند و اگر ظرفیت توسط دستگاه جدید پر شده باشد، نسخه قدیمی مسدود می‌شود). با تنظیم `ALLOW_LEGACY_CLIENT_COMPAT=0` در `.env` پس از آپدیت عمومی، ورود نسخه‌های قدیمی به‌طور کامل بسته می‌شود.
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
* **درگاه امن میزبانی مستقیم APK روی سرور و نودج دوره‌ای نسخه‌های قدیمی (`server.py` و `np_license/sessions.py`):**
  * **حل مشکل گیرکردن دانلود روی `0%`:** اندپوینت `/api/v1/license/app-update/files/<apk>` با پشتیبانی کامل از `HEAD`، دانلود تکه‌ای HTTP `Range` (`206 Partial Content`)، هدر `Accept-Ranges: bytes`، `X-Accel-Buffering: no` و کش باینری تأییدشده SHA-256 راه‌اندازی شد و لینک‌های دانلود در `/api/v1/license/app-update/latest` به‌صورت خودکار به `https://nova.dontbedumb.ir/api/v1/license/app-update/files/<apk>` بازنویسی و در پس‌زمینه از ریلیزهای جدید گیت‌هاب همگام‌سازی (Mirror) می‌شوند.
  * **قطع و یادآوری دوره‌ای نسخه‌های قدیمی:** با حفظ `ALLOW_LEGACY_CLIENT_COMPAT=1` (بدون تغییر کلیدهای مشتریان)، نسخه‌های پیش از `v2.4.3` پس از بازه `LEGACY_NUDGE_INTERVAL_SEC` (`900` ثانیه = ۱۵ دقیقه) در درخواست `/status` با پاسخ `401 legacy_update_required` و پیام واضح فارسی موقتاً قطع می‌شوند تا اپلیکیشن را بروزرسانی کنند؛ ورود مجدد با همان کلید بازه ۱۵ دقیقه‌ای بعدی را تا زمان نصب نسخه جدید بازنشانی می‌کند.
* **به‌روزرسانی همیشه-آخرین‌نسخه، سخت‌سازی دانلودر و بازطراحی رابط بروزرسانی (`v2.4.5`):**
  * `NovaPulseApkDownloader.kt`: افزودن هدر `Accept-Encoding: identity` برای حفظ `Content-Length` واقعی، زمان‌بندی ۱۲/۴۵ ثانیه، گزارش پیشرفت روان حتی در نبود طول محتوا، و اولویت‌بندی خودکار درگاه امن سرور همراه با Mirror Fallback و Proxy Fallback.
  * `UpdateCheckerManager.kt` و `Qs3dViewModel.kt`: انتخاب همیشه-آخرین نسخه بین منابع و یادآوری غیرآزاردهنده بعد از بستن دیالوگ آپدیت (`۶۰۰` ثانیه).
  * `QuietStormActivity.kt` و `Qs3dSettingsScreen.kt`: بازطراحی بنر بالای صفحه، دیالوگ بروزرسانی شیشه‌ای-فضایی با نمایش انتقال نسخه (`vOld ➜ vNew`)، نوار پیشرفت گرادیانت و کارت بخش نسخه در تنظیمات.

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
   * `test_app_update_always_picks_highest_semver`: `ok`
   * `test_concurrency_20_threads_against_capacity_2`: `ok` (تضمین عدم عبور از ظرفیت ۲ در ۲۰ ترد همزمان)
   * `test_legacy_client_compat_window_and_new_app_isolation`: `ok` (بررسی ورود موقت نسخه قدیمی و ایزوله‌سازی کامل توکن و ظرفیت نسخه جدید)
   * `test_legacy_periodic_nudge_disconnects_and_re_redeem_resets`: `ok` (بررسی نودج ۱۵ دقیقه‌ای و کد `401 legacy_update_required` و ریست شدن پس از ورود مجدد)
   * `test_reject_legacy_shared_android_id_and_bad_proof`: `ok`
   * `test_self_hosted_apk_gateway_head_range_and_url_rewrite`: `ok` (بررسی `HEAD`، `Range 206` و بازنویسی خودکار آدرس به سرور و پرکردن `sha256`)
   * `test_single_device_enforcement_logout_and_replay_protection`: `ok`
   * `test_status_refresh_sub_header_auth_and_bysub_auth`: `ok`
   * `test_unlimited_capacity_and_bruteforce_rate_limit`: `ok`
   * **جمع:** `Ran 10 tests in 1.862s -> OK`
2. **بررسی Syntax فایل‌های پنل (`node --check /opt/novapulse-license/web/panel-core.js /opt/novapulse-license/web/hm.js /opt/novapulse-license/web/xui.js`):** خروج با کد `0` (بدون خطا).
3. **بررسی اندپوینت‌های زنده سرویس و Reverse Proxy:**
   * `http://127.0.0.1:4150/api/v1/license/health` -> `200`
   * `http://127.0.0.1:4150/api/v1/license/assets/panel-core.js` -> `200`
   * `http://127.0.0.1:4150/api/v1/license/by-sub/invalid` -> `401`
   * `http://127.0.0.1:4150/api/v1/license/sub/nps_fake` -> `401`
   * `POST http://127.0.0.1:4150/api/v1/license/status` بدون توکن -> `401`
   * `HEAD https://nova.dontbedumb.ir/api/v1/license/app-update/files/NovaPulse_2.4.4_arm64-v8a.apk` -> `HTTP/2 200` (`accept-ranges: bytes`, `content-length: 24928219`)
   * `https://nova.dontbedumb.ir/api/v1/license/assets/panel-core.js` -> `200`
   * `https://nova.dontbedumb.ir/np-hm-license.js` -> `200`
   * `https://127.0.0.1:2087/gXfxEJl46r0576NkI7/np-license-api/assets/panel-core.js` -> `200`
4. **بیلد و تست واحد Android در GitHub Actions:**
   * بیلد و امضای رسمی APK نسخه `2.4.5` (`versionCode 753`) همراه با اجرای `./gradlew testPlaystoreDebugUnitTest --no-daemon` از طریق **GitHub Actions** (`.github/workflows/build.yml`) روی تگ `v2.4.5` انجام می‌شود.

---

## بخش چهارم: مقاوم‌سازی کامل زیرساخت، امنیت کلاینت Android و بازطراحی رابط اعلانات (Phase 2B & Phase 3)

### ۱. بکاپ و بازیابی پیش از تغییرات زیرساخت
* پوشه بکاپ امن: `/root/backups/phase3-hardening-20261115-174110Z/` (`chmod 700` و `go-rwx`)
* محتویات بکاپ‌شده: SQLite `.backup` از `licenses.db` و `x-ui.db` (هر دو `PRAGMA integrity_check -> ok`)، `/etc/ssh`، فایل‌های systemd unit، `/opt/novapulse-license`، `/opt/hmpanel/{docker-compose.yml,.env,nginx}`، `/usr/local/bin/agy-account-sync.py`، تنظیمات `antigravity_tools`، پیکربندی `/etc/ufw` و خروجی‌های `iptables-save` (`iptables-before.v4` / `iptables-before.v6`).

### ۲. مقاوم‌سازی سیستم‌عامل، شبکه، SSH و سرویس‌ها
1. **آماده‌سازی احراز هویت کلیدمحور SSH (`22` و `101`) همراه با جلوگیری از قطع دسترسی مالک:**
   * با بررسی لاگ‌های واقعی سیستم (`/var/log/auth.log.1`) مشخص شد مالک اخیراً از خارج با رمز عبور وارد سرور شده است (`51.21.82.134`). بنابراین برای جلوگیری قطعی از قفل‌شدن دسترسی مدیریتی تا پیش از تأیید عملی ورود با کلید از دستگاه شخصی مالک، سیاست فعلی به‌صورت کنترل‌شده روی **آماده‌باش دوگانه** تنظیم شد:
     * فایل محدودکننده موقت `00-novapulse-ssh-hardening.conf` برداشته شد و `PasswordAuthentication yes` و `PermitRootLogin yes` همراه با `PubkeyAuthentication yes` و `KbdInteractiveAuthentication no` موقتاً حفظ شدند (`98-security-hardening.conf` و سرویس Fail2ban روی `sshd` فعال هستند).
   * حساب کمکی `ubuntu` (`uid=1000`, `sudo`) بدون تغییر حفظ شد.
   * کلید جدید اختصاصی `ed25519` برای دسترسی `root` ساخته و به `/root/.ssh/authorized_keys` (`0600`) اضافه شد:
     * **مسیر فایل کلید خصوصی روی سرور:** `/root/.ssh/novapulse_root_ed25519`
     * **اثر انگشت کلید عمومی:** `SHA256:OpT/sCN1RXHZMzjpeejWEmKqY2RvA5jTNu2zABgLlUA`
     * **اسکریپت قطع رمز عبور پس از تأیید مالک:** `/usr/local/sbin/novapulse-lock-ssh-keyonly.sh` (`chmod 700`) آماده شده تا بلافاصله پس از تأیید ورود موفق با کلید از دستگاه مالک اجرا شود و SSH را صرفاً کلیدمحور (`AuthenticationMethods publickey`) کند.
   * تأیید عملی: ورود محلی با کلید `/root/.ssh/novapulse_root_ed25519` روی هر دو پورت `22` و `101` موفق بوده است (`SSH_KEY_22_OK`, `SSH_KEY_101_OK`).

2. **فایروال IPv4 + IPv6 (`UFW`) و فیلترینگ `DOCKER-USER`:**
   * سیاست پیش‌فرض: `deny incoming`، `allow outgoing`، `deny routed` با `IPV6=yes`.
   * پورت‌های عمومی باز (بدون اختلال در کاربران VPN): `22, 101, 80, 443, 2087, 2083, 2097, 2052, 2053, 2096, 8080, 8443, 8444, 8445, 8447/tcp`.
   * پورت‌های داخلی `4150` (بک‌اند لایسنس)، `9090` (`sysmon`)، `20870` (`x-ui`) و پورت‌های داخلی Docker (`3100, 8088, 8090, 8100`) منحصراً از طریق رابط `br-50a43488fc95` برای IP ثابت **`172.18.0.4/32`** کانتینر Nginx مجاز هستند و از اینترنت یا سایر کانتینرها مسدود شدند.
   * زنجیره `DOCKER-USER` در `/etc/ufw/after.rules` (IPv4) و `/etc/ufw/after6.rules` (IPv6) نیز ترافیک مستقیم خارجی روی پورت‌های داخلی را Drop می‌کند.

3. **حذف رمزهای افشاشده و جداسازی محیطی `sysmon` و `agy-account-sync`:**
   * حذف کامنت‌های حاوی رمز از `/opt/hmpanel/nginx/conf.d/agy.conf` و `/opt/hamsoo/agy.conf`.
   * چرخش مستقل ۳ رمز (پسورد پنل `sysmon`، `API_KEY` و `WEB_PASSWORD` کانتینر `antigravity-manager`) بدون نگهداری در سورس یا کانفیگ وب‌سرور.
   * انتقال متغیرهای محیطی به فایل‌های `0600 root:root` (`/etc/sysmon/env`، `/etc/agy-account-sync/env`، `/etc/agy-account-sync/container.env`)، اعمال محدودیت‌های systemd (`NoNewPrivileges`, `PrivateTmp`, `ProtectSystem`, `ProtectHome`) روی `sysmon.service` و `xray-restart-watcher.service` و حذف کامل رمز fallback از `/usr/local/bin/agy-account-sync.py` (`HTTP 200` در همگام‌سازی واقعی تأیید شد).

4. **تثبیت IP کانتینر Nginx، اعتماد پروکسی، CORS، مهاجرت لایسنس‌های قدیمی و وابستگی PostgreSQL:**
   * تثبیت آدرس `ipv4_address: 172.18.0.4` برای `hmpanel-nginx` در `/opt/hmpanel/docker-compose.yml` (پایدار پس از recreate و restart).
   * محدودسازی `TRUSTED_PROXY_CIDRS` در `/opt/novapulse-license/.env` به `127.0.0.0/8,::1/128,172.18.0.4/32`.
   * استخراج دقیق دامنه‌های واقعی CORS از کانفیگ‌های زنده Nginx (`/opt/hmpanel/nginx/conf.d/`): صرفاً `https://nova.dontbedumb.ir` و `https://cdnflear.dontbedumb.ir:2087` در `server.py` مجاز هستند (`Vary: Origin`؛ درخواست `OPTIONS` با `Origin: https://evil.example` پاسخ `403` می‌گیرد و بدون جایگزینی سیستم Auth است).
   * **وضعیت PostgreSQL و مرز دسترسی Docker:** کانتینر `hmpanel-postgres` پورت `5432/tcp` را روی میزبان Publish نکرده است و ارتباط `novapulse-license` با نقش کمینه‌سطح `np_license_svc` (`NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT` و فقط `SELECT`/`UPDATE` ستونی روی `"Panel"` و `"Client"`) از طریق `docker exec -i hmpanel-postgres psql -U np_license_svc ...` با متغیرهای پارامتریک (`-v` / `:'var'`) انجام می‌شود. دسترسی به `"Admin"` و `"Panel".password` با خطای `permission denied` مسدود است. (وابستگی به `/var/run/docker.sock` در بخش ریسک‌های باقیمانده ثبت شده است.)
   * **وضعیت مهاجرت تدریجی لایسنس‌های قدیمی (`licenses.db` — مجموع `62` رکورد بدون ابطال کورکورانه):**
     * دستگاه‌های به‌روز (`device_id_legacy=0`, `active`): `13` دستگاه روی `2.4.5` و `3` دستگاه روی `2.4.4` (مجموع `16` دستگاه مدرن).
     * کاربران قدیمی فعال (`device_id_legacy=1`, `active`, `legacy (<2.4.3)`): `2` لایسنس (`NP-7SUH***` و `NP-5WYD***`).
     * لایسنس‌های نامشخص قدیمی (`device_id_legacy=1`, `status='legacy_ambiguous'`): `3` لایسنس (`NP-CU7F***`، `NP-R2DZ***`، `NP-7G76***`)؛ و `2` لایسنس منقضی/لغوشده طبیعی (`NP-WMSP***`, `NP-P834***`).
     * پنجره سازگاری (`ALLOW_LEGACY_CLIENT_COMPAT=1` و یادآور دوره‌ای هر `900s` با کد `401 legacy_update_required`) فعال نگه داشته شده تا کاربران قدیمی بدون قطع ناگهانی به نسخه `2.4.5` مهاجرت کنند.
   * **محافظت از درگاه APK تولید:** فایل‌های انتشار رسمی در `/opt/novapulse-license/releases/` (`NovaPulse_2.4.5_*.apk`) بدون تغییر و دست‌نخورده باقی ماندند تا هیچ جایگزینی پیش از تأیید نهایی مالک انجام نشود.

---

### ۳. اصلاحات امنیتی کلاینت Android (`PattNG-stage1`)
1. **پشتیبانی ایمن از چرخش گواهی امضای APK (`NovaPulseApkDownloader.kt` & `AppSecurityGuardTest.kt`):**
   * افزودن متدهای خالص قابل‌تست JVM (`verifySigningCertificateDigests`، `verifyLegacySignatureDigests` و اضافه‌بارهای بایت‌محور) با حفظ گواهی فعلی Release (`A0:16:C9:...`).
   * در Android 9+ (`API 28+`)، استخراج صحیح `SigningInfo` با تفکیک دقیق `hasMultipleSigners()`: بررسی `signingCertificateHistory` برای گواهی‌های چرخش‌یافته تک‌امضا و `apkContentsSigners` برای حالت چندامضا، با رد کامل حالت‌های ناقص/خالی (Fail-Closed).
2. **غیرفعال‌سازی استخراج و پشتیبان‌گیری خودکار داده‌ها (`AndroidManifest.xml`, `res/xml/data_extraction_rules.xml`):**
   * افزودن `android:fullBackupContent="false"` و `android:dataExtractionRules="@xml/data_extraction_rules"` برای مسدودسازی Cloud Backup و Device-to-Device Transfer روی تمام دامنه‌ها (`root`, `file`, `database`, `sharedpref`, `external`).
3. **بستن مسیرهای بایپس گیت لایسنس و دستکاری زمان اجرا (`LauncherManager.kt`, `CoreVpnService.kt`, `CoreProxyOnlyService.kt`, `CoreRootService.kt`, `BootReceiver.kt`, `UrlSchemeActivity.kt`):**
   * الزام بررسی هم‌زمان `!AppSecurityGuard.isRuntimeTampered()` و `LicenseBackendClient.isLicenseSessionActive()` پیش از راه‌اندازی تونل VPN (`setupVpnService()`)، سرویس‌های پروکسی/روت، همگام‌سازی خودکار بوت و پردازش Deep Link/Intent خارجی.

---

### ۴. بازطراحی UI کامپوننت اعلانات، بروزرسانی و پیام‌های امنیتی (`Part 4`)
* **به‌روزرسانی پیام‌های سرور (`server.py` و `np_license/sessions.py`):** ساختاردهی واضح پیام‌های فارسی بدون اموجی‌های شلوغ یا لینک‌های پرانتزدار، همراه با لینک و آیدی تمیز `@NovaPulseVPN`، `https://t.me/NovaPulseVPN` و `@Mo3i_Best`.
* **پارسر متنی خالص JVM (`NovaPulseNoticeParser.kt` + `NovaPulseNoticeParserTest.kt`):**
  * تشخیص هوشمند سطح اهمیت پیام (`MANDATORY_SECURITY`، `SECURITY_NOTICE`، `UPDATE_INFO`، `INFO`)، تفکیک بخش‌های هشدار، وضعیت ایمن و راهنمای پشتیبانی، جداسازی خودکار اعداد نسخه (`v2.4.5`) و لینک‌ها با کاراکترهای ایزوله‌ساز Bidi (`LRI/PDI`) برای نمایش بدون بهم‌ریختگی در متن فارسی راست‌به‌چپ، و حذف علائم نگارشی چسبیده از انتهای لینک‌ها (`@Mo3i_Best،`، `https://t.me/...).`).
* **کامپوننت سه‌بعدی-شیشه‌ای یکپارچه (`NovaPulseNoticeDialog.kt`):**
  * استفاده هم‌زمان در `QuietStormActivity.kt` (دیالوگ بروزرسانی، دیالوگ اعلان امنیتی/لایسنس و دیالوگ رضایت بهینه‌سازی)، `CheckUpdateActivity.kt` و جایگزینی اسنک‌بار پیش‌فرض با `Qs3dSnackbarHost` شیشه‌ای.
  * طراحی چندلایه با هدر Emblem وکتور اختصاصی (متوقف در صورت خاموش‌بودن انیمیشن یا رفتن به پس‌زمینه توسط `rememberMotionActive()`)، نمایشگر انتقال نسخه (`v2.4.4 -> v2.4.5`) و ABI، کارت‌های رنگی بخش‌بندی‌شده، لینک‌های آبی/فیروزه‌ای قابل‌کلیک درون متن و چیپ‌های دسترسی مستقیم تلگرام (`SupportLinks.openTelegramTarget`) با ساختار واکنش‌گرا برای نمایشگرهای باریک `320dp`.
* **تست رندر واقعی، راست‌به‌چپ (RTL) و کلیک لینک‌ها در CI (`NovaPulseNoticeUiScreenshotTest.kt` + `.github/workflows/build.yml`):**
  * رندر واقعی Compose با Robolectric Native Graphics (`@GraphicsMode(NATIVE)`) در حالت‌های فارسی اجباری (`412dp`)، آپدیت اختیاری با نوار پیشرفت (`412dp`)، اعلان امنیتی در صفحه باریک (`320dp`)، حالت انگلیسی (`412dp`) و همچنین تست اجبار `LayoutDirection.Rtl` روی لوکیل `en-US` و شبیه‌سازی کلیک واقعی روی چیپ‌های «کانال رسمی» و «پشتیبانی تلگرام» همراه با تأیید `Intent.ACTION_VIEW`، و آپلود خروجی `ui-screenshots` به‌عنوان Artifact در گیت‌هاب اکشنز.

---

### ۵. ماتریس نهایی راستی‌آزمایی (Verification Matrix)

| بخش / آزمون | وضعیت | نتیجه مشاهده‌شده |
| :--- | :---: | :--- |
| تست واحد و امنیت Backend (`unittest discover`) | **PASS** | `13/13` تست موفق (شامل تست Proxy CIDR `/32` و CORS و نودج نسخه < `2.4.4`) |
| چرخه زنده API لایسنس (`challenge -> redeem -> status -> sub_noauth -> logout -> post_logout`) | **PASS** | پاسخ‌های `200 -> 200 -> 200 -> 401 -> 200 -> 401` و پاک‌سازی رکورد تست |
| دسترسی کمینه‌سطح PostgreSQL (`np_license_svc`) | **PASS** | شمارش `Client` (`59`) موفق؛ `Panel.password` و `Admin` با `permission denied` مسدود |
| ایزوله‌سازی فایروال کانتینر و پورت‌های داخلی (`4150, 9090, 20870`) | **PASS** | مسدود از کانتینر `hmpanel-panel`؛ قابل‌دسترس فقط از `hmpanel-nginx` (`172.18.0.4`) |
| دسترسی SSH کلیدمحور (`22` و `101`) بدون قطع دسترسی مالک | **DEFERRED (Safe)** | `SSH_KEY_22_OK` و `SSH_KEY_101_OK` موفق؛ ورود رمز عمداً تا تأیید کلید مالک باز ماند (`/usr/local/sbin/novapulse-lock-ssh-keyonly.sh` آماده اجرا) |
| سلامت سرویس‌های VPN و شبکه (`ssh`, `fail2ban`, `ufw`, `docker`, `x-ui`, `novapulse-license`, `sysmon`, `agy-account-sync`, `xray-restart-watcher`) | **PASS** | تمام `9` سرویس `active`؛ `62` ردیف لایسنس (`16` مدرن `2.4.4`/`2.4.5`، `2` legacy فعال، `3` `legacy_ambiguous`، `2` revoked) |
| تست واحد، RTL، کلیک چیپ‌ها و رندر سه‌بعدی در GitHub Actions (`feat/qsn-3d-smart-stage1`) | **PENDING_PUSH** | آخرین اجرا در Run [`43745916261`](https://github.com/mo3iiibest77-hub/PattNG/actions/runs/43745916261) (`a9f1ac74`: `98/98` موفق، `ui-screenshots` با حجم `2,555,713` بایت)؛ اجرای جدید با تست RTL/کلیک در حال ثبت است |

---

## بخش پنجم: ریسک‌های باقیمانده و محدودیت‌های صریح (Residual Risks & Open Items)

این موارد به‌صورت عمدی باز نگه داشته شده یا دارای محدودیت محیطی هستند و نباید تکمیل‌شده تلقی شوند:

1. **فعال‌بودن موقت احراز هویت با رمز عبور در SSH (ریسک باز عمدی تا تأیید مالک):**
   * `PasswordAuthentication yes` و `PermitRootLogin yes` روی پورت‌های `22` و `101` فعال هستند تا دسترسی مالک از طریق رمز مسدود نشود (Fail2ban فعال است).
   * **اقدام بعدی:** دریافت `/root/.ssh/novapulse_root_ed25519` روی دستگاه شخصی، تأیید ورود موفق با کلید از بیرون سرور، و سپس اجرای `/usr/local/sbin/novapulse-lock-ssh-keyonly.sh`.
2. **اجرای `novapulse-license` با کاربر `root` و دسترسی به Docker Socket:**
   * به‌دلیل وابستگی Sync با کانتینر `hmpanel-postgres` از طریق `docker exec -i hmpanel-postgres psql ...` (پورت `5432` روی میزبان بسته است)، سرویس `novapulse-license` همچنان به `/var/run/docker.sock` متکی است. مهاجرت به اتصال شبکه ایزوله روی همان Bridge داکر می‌تواند در فاز بعدی انجام شود.
3. **بازبودن موقت پنجره سازگاری لایسنس‌های قدیمی (`ALLOW_LEGACY_CLIENT_COMPAT=1`):**
   * هنوز `2` دستگاه فعال با نسخه `<2.4.3` و `3` ردیف `legacy_ambiguous` در `licenses.db` وجود دارند. پس از ارتقای این کاربران با پیام یادآور دوره‌ای (`900s`)، می‌توان `ALLOW_LEGACY_CLIENT_COMPAT=0` را فعال کرد.
4. **محدودیت بازبینی بصری مستقیم فایل‌های PNG اسکرین‌شات در میزبان:**
   * Workflow در GitHub Actions اسکرین‌شات‌های واقعی Compose را تولید و در `ui-screenshots` آپلود می‌کند (و تست‌ها عدم سفید/سیاه‌بودن و ابعاد واقعی را در زمان رندر چک می‌کنند)، اما دانلود ZIP آرتیفکت از REST API گیت‌هاب نیازمند توکن احراز هویت (PAT) است؛ بنابراین بررسی چشمی نهایی فایل‌های PNG از طریق رابط کاربری GitHub بر عهده مالک است.
5. **به‌روزرسانی نکردن APKهای تولید در `/opt/novapulse-license/releases/`:**
   * فایل‌های فعلی روی سرور (`NovaPulse_2.4.5_*.apk` مربوط به ساعت `14:12`) مطابق دستور بدون جایگزینی حفظ شدند تا بیلد امضاشده و آزموده‌شده نهایی پس از تأیید مالک منتشر شود.

