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
1. **وضعیت SSH، آماده‌سازی ورود کلیدمحور استاندارد و جلوگیری از قطع دسترسی مالک:**
   * اسکریپت `/usr/local/sbin/novapulse-lock-ssh-keyonly.sh` (`chmod 700`) **اجرا نشده است**. سیاست فعلی روی پورت‌های `22` و `101` شامل `PermitRootLogin yes`، `PasswordAuthentication yes`، `PubkeyAuthentication yes` و `KbdInteractiveAuthentication no` است تا دسترسی فعلی مالک با رمز عبور بدون اختلال حفظ شود و `fail2ban` (`sshd`، `Currently banned: 0`) فعال است.
   * فایل کلید موقت تولیدشده روی سرور (`/root/.ssh/novapulse_root_ed25519`) **راهکار نهایی مالک محسوب نمی‌شود**؛ کلید اصلی باید روی سیستم شخصی مالک تولید و صرفاً کلید عمومی (`.pub`) به سرور منتقل شود.
   * مسیر استاندارد و مجوزهای احراز هویت آماده هستند (`StrictModes yes`، `AuthorizedKeysFile .ssh/authorized_keys .ssh/authorized_keys2`، `/root/.ssh` و `/home/ubuntu/.ssh` با مجوز `700` و `authorized_keys` با مجوز `600`).
   * **راهنمای استاندارد راه‌اندازی کلید SSH روی دستگاه مالک (بدون افشای کلید یا رمز):**
     1. روی کامپیوتر شخصی مالک (Windows PowerShell / macOS / Linux) یک جفت‌کلید `ed25519` بسازید:
        `ssh-keygen -t ed25519 -f ~/.ssh/novapulse_owner_ed25519 -C "owner-novapulse"`
     2. **نشست SSH اول را باز نگه دارید** (به‌عنوان پشتیبان ایمن) و فقط محتوای فایل **عمومی** (`novapulse_owner_ed25519.pub`) را به انتهای `/root/.ssh/authorized_keys` اضافه کنید (یا با `ssh-copy-id -p 101 -i ~/.ssh/novapulse_owner_ed25519.pub root@51.21.82.134`).
     3. بدون بستن نشست اول، یک **نشست SSH دوم** با احراز هویت صرفاً کلید باز کنید تا کارکرد کلید جدید تأیید شود:
        `ssh -p 101 -i ~/.ssh/novapulse_owner_ed25519 -o IdentitiesOnly=yes -o PubkeyAuthentication=yes -o PasswordAuthentication=no root@51.21.82.134`
     4. در صورت نیاز به بازگشت سریع (Rollback)، از همان نشست اول باز یا بکاپ `/root/backups/phase3-hardening-20261115-174110Z/ssh/` پیکربندی پیشین قابل بازیابی است. تا پیش از تأیید عملی نشست دوم از سیستم مالک، `PasswordAuthentication` فعال باقی می‌ماند.

2. **فایروال IPv4 + IPv6 (`UFW`)، `iptables`/`ip6tables`، `DOCKER-USER` و NAT:**
   * در بررسی زنده (`2026-11-15T19:46:42Z`–`19:47:09Z`):
     * `UFW` فعال با سیاست `deny (incoming), allow (outgoing), deny (routed)` روی IPv4 و `ufw6-user-input`.
     * در جدول `DOCKER-USER` برای IPv4 و IPv6، ترافیک ورودی از رابط عمومی `ens5` به‌جز مقصد `172.18.0.4` روی پورت‌های `80, 443, 2087` با قانون صریح `DROP` مسدود است (`RETURN` برای `ctstate RELATED,ESTABLISHED` و بریج‌های داخلی Docker حفظ شده است).
     * قوانین `iptables -t nat` (`Chain DOCKER`) تنها پورت‌های `80`, `443`, `2087` را به `172.18.0.4` و `8045` را به `127.0.0.1` (`antigravity-manager`) نگاشت می‌کنند.
     * پورت‌های عمومی VPN / Xray / MTProto / `x-ui` Sub (`2052, 2053, 2083, 2087, 2096, 2097, 8080, 8443, 8444, 8445, 8447`) و `22, 101, 80, 443` همگی `OPEN` هستند (`2083` تا تکمیل بررسی و مهاجرت کاربران بدون اختلال حفظ شده است).
     * پورت‌های داخلی `4150` (بک‌اند لایسنس)، `9090` (`sysmon`) و `20870` (`x-ui`) در تست هم‌جوار Docker (`hmpanel-panel -> 172.18.0.1`) هر سه **`TIMEOUT/BLOCKED`** و از کانتینر مجاز `hmpanel-nginx` به ترتیب `{"status": "ok"}`, `302`, `200` پاسخ دادند.
     * **محدودیت اسکن خارجی:** این محیط تنها به خود سرور دسترسی دارد و اسکن TCP واقعی از یک میزبان اینترنتی خارج از سرور اجرا نشده است (تست از خود سرور روی IP خودش مسیر Loopback را طی می‌کند و سند بسته‌بودن خارجی محسوب نمی‌شود)؛ بنابراین مسدودیت خارجی پورت‌های `4150`، `9090` و `20870` در دسته‌بندی **تکمیل‌شده در فایروال و تاییدشده بین‌کانتینری، اما تأییدنشده از بیرون سرور** ثبت می‌شود.

3. **حذف رمزهای افشاشده و جداسازی محیطی `sysmon` و `agy-account-sync`:**
   * حذف کامنت‌های حاوی رمز از `/opt/hmpanel/nginx/conf.d/agy.conf` و `/opt/hamsoo/agy.conf`.
   * چرخش مستقل ۳ رمز (پسورد پنل `sysmon`، `API_KEY` و `WEB_PASSWORD` کانتینر `antigravity-manager`) بدون نگهداری در سورس یا کانفیگ وب‌سرور.
   * انتقال متغیرهای محیطی به فایل‌های `0600 root:root` (`/etc/sysmon/env`، `/etc/agy-account-sync/env`، `/etc/agy-account-sync/container.env`)، اعمال محدودیت‌های systemd (`NoNewPrivileges`, `PrivateTmp`, `ProtectSystem`, `ProtectHome`) روی `sysmon.service` و `xray-restart-watcher.service` و حذف کامل رمز fallback از `/usr/local/bin/agy-account-sync.py` (`HTTP 200` در همگام‌سازی واقعی تأیید شد).

4. **تثبیت IP کانتینر Nginx، اعتماد پروکسی، CORS، مهاجرت لایسنس‌های قدیمی و وابستگی PostgreSQL:**
   * تثبیت آدرس `ipv4_address: 172.18.0.4` برای `hmpanel-nginx` در `/opt/hmpanel/docker-compose.yml` (پایدار پس از recreate و restart).
   * محدودسازی `TRUSTED_PROXY_CIDRS` در `/opt/novapulse-license/.env` به `127.0.0.0/8,::1/128,172.18.0.4/32`.
   * استخراج دقیق دامنه‌های واقعی CORS از کانفیگ‌های زنده Nginx (`/opt/hmpanel/nginx/conf.d/`): صرفاً `https://nova.dontbedumb.ir` و `https://cdnflear.dontbedumb.ir:2087` در `server.py` مجاز هستند (`Vary: Origin`؛ درخواست `OPTIONS` با `Origin: https://evil.example` پاسخ `403` می‌گیرد و بدون جایگزینی سیستم Auth است).
   * **وضعیت PostgreSQL و مرز دسترسی Docker:** کانتینر `hmpanel-postgres` پورت `5432/tcp` را روی میزبان Publish نکرده است و ارتباط `novapulse-license` با نقش کمینه‌سطح `np_license_svc` (`NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT` و فقط `SELECT`/`UPDATE` ستونی روی `"Panel"` و `"Client"`) از طریق `docker exec -i hmpanel-postgres psql -U np_license_svc ...` با متغیرهای پارامتریک (`-v` / `:'var'`) انجام می‌شود. دسترسی به `"Admin"` و `"Panel".password` با خطای `permission denied` مسدود است.
   * **وضعیت زنده مهاجرت تدریجی لایسنس‌های قدیمی (`licenses.db` در `2026-11-15T19:46:56Z` — مجموع `62` لایسنس):**
     * دستگاه‌های مدرن به‌روز (`device_id_legacy=0`, `active`): `13` دستگاه روی `2.4.5` و `3` دستگاه روی `2.4.4` (مجموع `16` دستگاه مدرن).
     * دستگاه‌های قدیمی فعال (`device_id_legacy=1`, `status='active'`, `legacy (<2.4.3)`): **`3` دستگاه فعال** شامل `NP-CU7F***` (تازه‌وارد در آخرین ساعت، سقف `3`), `NP-7SUH***` (سقف `1`), `NP-5WYD***` (سقف `-1`).
     * رکوردهای نامشخص قدیمی (`device_id_legacy=1`, `status='legacy_ambiguous'`): اکنون **`2` رکورد باقی‌مانده** (`NP-7G76***` و `NP-R2DZ***`)؛ و **`2` ردیف `revoked`** (`NP-P834***`, `NP-WMSP***`).
     * **برنامه مهاجرت مرحله‌ای (بدون قطع ناگهانی):** (۱) حفظ `ALLOW_LEGACY_CLIENT_COMPAT=1` و یادآور `900s` تا ارتقای کاربران فعلی، (۲) جایگزینی فایل‌های APK رسمی پس از تأیید مالک و اطلاع‌رسانی در کانال، (۳) رسیدن شمار دستگاه‌های `active legacy (<2.4.3)` به `0`، و (۴) غیرفعال‌سازی `ALLOW_LEGACY_CLIENT_COMPAT=0`.
   * **درگاه APK تولید و مقایسه با بیلد CI (بدون جایگزینی فایل‌های سرور):**
     * **فایل‌های تولید فعلی در `/opt/novapulse-license/releases/` (`Nov 15 14:12` — دست‌نخورده):**
       * `NovaPulse_2.4.5_arm64-v8a.apk`: `sha256=7f23a2f7a9c22249c9590a6d8d1b14c05d4d94f0ca9abafd1a2df1b0c7106565` (گواهی v2: `a016c9bf4d8e7341efd565f7fc1959235fcdf74c63feb1e31dbaee86a9c22ae1`)
       * `NovaPulse_2.4.5_armeabi-v7a.apk`: `sha256=1228100240fdacb6e33031da7d02beee83423dce23e16ea4e58462b15d747839` (گواهی v2: `a016c9bf...`)
       * `NovaPulse_2.4.5_universal.apk`: `sha256=ded9a412e88ab93c99582df908abb95cc55c89e751e0e985345be98f7c35c12f` (گواهی v2: `a016c9bf...`)
       * `NovaPulse_2.4.5_x86.apk`: `sha256=9b5d25272d454131768309e42666c6a8172a11b468f522091e390959e93bf723` (گواهی v2: `a016c9bf...`)
       * `NovaPulse_2.4.5_x86_64.apk`: `sha256=11040c4148b514fb9c9a23465d0edfdb62923d11759334e91a86bb87fce9939d` (گواهی v2: `a016c9bf...`)
     * **مقایسه Git بین `e6e7f4fa` (کامیت آزموده‌شده در CI Run `43750483880`) و `HEAD`:** از کامیت `e6e7f4fa` به بعد **فقط `report.md` تغییر کرده** و هیچ تغییر کد یا تستی پس از آن ایجاد نشده است (`git diff --name-status e6e7f4fa..f377a9ce -> M report.md`).
     * در CI Run `43750483880` هر ۵ ABI بیلد و آپلود شده‌اند؛ گام بررسی Workflow (`build.yml`) به‌صورت مستقیم `sha256=84584a8f417224b85763a6a67416ecc1704de2689bd863a14288d97881de3fa8` و گواهی `a016c9bf...` را برای `arm64-v8a` چاپ و تأیید کرده است (دریافت فایل ZIP آرتیفکت سایر ۴ ABI نیازمند احراز هویت GitHub است).

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
* **تست خودکار Compose و محدودیت دریافت اسکرین‌شات (`NovaPulseNoticeUiScreenshotTest.kt` + Run `43750483880`):**
  * اجرای رندر Compose (Robolectric `4.16.1` با `NATIVE` graphics)، بررسی وجود متن‌ها، عدم سفید/سیاه‌بودن بایتمپ، اجبار `LayoutDirection.Rtl` زیر لوکیل `en-US` و شبیه‌سازی کلیک روی چیپ‌های تلگرام (`Intent.ACTION_VIEW`) در Run `43750483880` (`99/99` موفق) و تولید آرتیفکت `ui-screenshots` (`2,555,713` بایت).
  * در بررسی زنده (`2026-11-15T19:46:56Z`) دانلود آرتیفکت از REST API گیت‌هاب بدون PAT خطای **`401 Unauthorized`** داد؛ بنابراین بررسی چشمی واقعی فایل‌های PNG روی چشم انسان انجام نشده و صراحتاً در بخش **تکمیل‌شده اما بدون تأیید بصری خارجی** ثبت می‌شود.

---

### ۵. ماتریس نهایی راستی‌آزمایی و زمان‌بندی تست‌ها (`2026-11-15T19:46Z–19:47Z`)

| تست / مقصد / زمان (UTC) | وضعیت | نتیجه مشاهده‌شده |
| :--- | :---: | :--- |
| تست واحد Backend (`unittest discover`, `19:13:12Z`) | **Verified** | `Ran 13 tests in 2.197s -> OK` |
| Nginx Upstream -> Backend/Sysmon/x-ui (`172.18.0.4 -> 172.18.0.1`, `19:46:56Z`) | **Verified** | `4150`: `{"status": "ok"}`, `9090`: `HTTP 302`, `20870`: `HTTP 200` |
| ایزوله‌سازی بین‌کانتینری Docker (`hmpanel-panel -> 172.18.0.1`, `19:46:56Z`) | **Verified** | پورت‌های `4150`, `9090`, `20870` همگی `TIMEOUT/BLOCKED` |
| Reverse Proxy عمومی IPv4/IPv6 & `2083` (`19:46:56Z–19:47:09Z`) | **Verified** | `nova.dontbedumb.ir` (IPv4): `200`; `cdnflear.dontbedumb.ir:2087` (IPv4): `200`; `:2083`: `403` (فعال)؛ `[2a05:..:71e0]:443` (IPv6 مستقیم محلی): `200` (دامنه `nova.dontbedumb.ir` رکورد DNS AAAA ندارد) |
| پورت‌های Xray / MTProto / Panel (`2052, 2053, 2083, 2087, 2096, 2097, 8080, 8443-8447`, `19:47:09Z`) | **Verified** | همگی در حالت `LISTEN` و اتصال TCP موفق (`OPEN`) |
| اسکن خارجی اینترنتی پورت‌های `4150, 9090, 20870` | **NOT Externally Verified** | قوانین `UFW` و `DOCKER-USER` (`ens5` DROP برای IPv4/IPv6) و ایزوله‌سازی بین‌کانتینری تأیید شد، اما میزبان خارجی مستقل در دسترس نبود |
| امضای v2 و SHA-256 هر ۵ APK تولیدی در `/opt/novapulse-license/releases/` (`19:47:32Z`) | **Verified** | هر ۵ ABI دست‌نخورده و امضاشده با `a016c9bf...` |
| بیلد و تست Android در GitHub Actions (`Run 43750483880`, کامیت `e6e7f4fa`) | **Verified (Code) / Visual Uninspected** | `99/99` تست موفق؛ `arm64-v8a` `sha256=84584a8f...` و گواهی `a016c9bf...` تأیید شد؛ `ui-screenshots` دانلود نشد (`401 Unauthorized` بدون PAT) |

---

## بخش پنجم: طبقه‌بندی نهایی وضعیت‌ها (چهار وضعیت تفکیک‌شده)

### ۱. Completed and independently verified (تکمیل‌شده و راستی‌آزمایی‌شده)
* فعال‌بودن تمام ۹ سرویس تولیدی (`ssh`, `fail2ban`, `ufw`, `docker`, `x-ui`, `novapulse-license`, `sysmon`, `agy-account-sync`, `xray-restart-watcher`) و پاسخ سالم `/api/v1/license/health` (`200`).
* موفقیت کامل تست‌های واحد Backend (`13/13`)، چرخه زنده Auth لایسنس، دسترسی کمینه‌سطح PostgreSQL (`np_license_svc`) و پاک‌سازی/چرخش رمزهای `sysmon` و `agy-account-sync`.
* فعال‌بودن `UFW` + `DOCKER-USER` (IPv4 & IPv6)، بازبودن کامل تمام پورت‌های VPN / Xray / MTProto / `2083`، کارکرد صحیح Upstream از `hmpanel-nginx` (`172.18.0.4`) و مسدودبودن دسترسی مستقیم از کانتینر غیرمجاز `hmpanel-panel` به `4150, 9090, 20870`.
* حفظ دسترسی امن SSH با رمز و آماده‌باش `authorized_keys` (`700`/`600`) بدون اجرای `novapulse-lock-ssh-keyonly.sh`، حفظ کلید و بکاپ قابل‌بازگشت.
* وضعیت زنده `licenses.db` با حفظ کاربران قدیمی بدون ابطال ناگهانی (`3` دستگاه فعال `<2.4.3`، `2` رکورد `legacy_ambiguous`، `16` دستگاه مدرن `2.4.4`/`2.4.5`).
* بررسی SHA-256 و گواهی v2 تمام ۵ فایل APK تولیدی فعلی (`/opt/novapulse-license/releases/`) بدون جایگزینی، هم‌خوانی `e6e7f4fa..HEAD` (تنها تغییر در `report.md`)، و موفقیت `99/99` تست واحد + بیلد `arm64-v8a` در Run `43750483880`.

### ۲. Completed but not externally verified (تکمیل‌شده ولی بدون تأیید خارجی)
* **بسته‌بودن خارجی پورت‌های `4150`، `9090` و `20870` از اینترنت:** قوانین `UFW`، `iptables` (`DOCKER-USER` روی `ens5`)، `ip6tables` و مسدودیت بین‌کانتینری تأیید شده‌اند، اما به‌دلیل نبود ماشین تست خارج از VPS، Port Scan واقعی از بیرون انجام نشده است.
* **بازبینی چشمی فایل‌های PNG آرتیفکت `ui-screenshots`:** تست‌های خودکار Compose (`99/99` شامل RTL، کلیک چیپ تلگرام و بایتمپ غیرخالی) پاس شده‌اند، اما فایل ZIP آرتیفکت از REST API گیت‌هاب بدون PAT با `401 Unauthorized` رد شد و تصاویر با چشم انسان دیده نشده‌اند.
* **تست گواهی/SHA-256 مجزای ۴ ABI دیگر در CI (`armeabi-v7a`, `x86`, `x86_64`, `universal`):** در Run `43750483880` ساخته و آپلود شده‌اند، اما اسکریپت `build.yml` فقط خروجی SHA-256 و `apksigner` فایل `arm64-v8a` را در لاگ چاپ کرده است.

### ۳. Pending owner action (در انتظار اقدام مالک)
* **ساخت کلید اختصاصی SSH روی دستگاه شخصی مالک،** قراردادن کلید عمومی در `/root/.ssh/authorized_keys` و **آزمون عملی در نشست SSH دوم** (پیش از هر تصمیم برای محدودسازی رمز).
* **بررسی بصری دستی فایل‌های `ui-screenshots`** در صفحه Run [`43750483880`](https://github.com/mo3iiibest77-hub/PattNG/actions/runs/43750483880) پس از ورود به حساب GitHub.
* **تصمیم و تأیید نهایی مالک برای جایگزینی APKهای تولید** در `/opt/novapulse-license/releases/` با خروجی‌های `e6e7f4fa` پس از مشاهده بصری و تست روی گوشی واقعی.
* **اجرای Scan پورت خارجی** از دستگاه شخصی یا ابزار خارجی برای تأیید بسته‌بودن `4150, 9090, 20870` از اینترنت.

### ۴. Remaining security risks (ریسک‌های امنیتی باقی‌مانده)
* **`PasswordAuthentication yes` و `PermitRootLogin yes` در SSH:** تا زمانی که مالک ورود با کلید شخصی خود را تأیید نکند عمداً باز مانده است (کاهش ریسک فعلی با `fail2ban` روی پورت‌های `22` و `101`).
* **وابستگی سرویس `novapulse-license` به کاربر `root` و `/var/run/docker.sock`:** به‌علت همگام‌سازی PostgreSQL از طریق `docker exec -i hmpanel-postgres psql` (`5432` روی میزبان بسته است).
* **بازبودن موقت `ALLOW_LEGACY_CLIENT_COMPAT=1`:** تا پایان ارتقای `3` دستگاه فعال قدیمی (`NP-CU7F***`، `NP-7SUH***`، `NP-5WYD***`) و تعیین تکلیف `2` ردیف `legacy_ambiguous`.
* **باقی‌ماندن فایل کلید موقت `/root/.ssh/novapulse_root_ed25519` روی دیسک سرور:** پس از آنکه مالک کلید اختصاصی ساخته‌شده روی دستگاه خودش را تست کرد، بهتر است کلید موقت و سطر عمومی مرتبط (`novapulse-root-admin@prod`) از سرور حذف شوند.


