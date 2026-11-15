# NovaPulse — Engineering & Build Guide

## Build & Flavor Rules

- Only the `playstore` distribution flavor exists (`applicationId = "com.quietstorm.ng"`, `DISTRIBUTION = "NovaPulse Official"`). **F-Droid (`fdroid`) builds are permanently removed and blocked in CI.**
- Kotlin `2.4.0`, AGP `9.2.1`, Java `17`, Gradle Kotlin DSL (`V2rayNG/app/build.gradle.kts`).

```sh
cd V2rayNG
./gradlew assemblePlaystoreDebug
./gradlew assemblePlaystoreRelease
```

## Unit Tests

```sh
cd V2rayNG
./gradlew testPlaystoreDebugUnitTest --no-daemon
```

Unit tests under `app/src/test/java/` must run cleanly on pure JVM (`LogUtil` and `Utils` fall back gracefully without `android.util.Log` mocking).

## Core Project Layout

- `com.v2ray.ang.AppConfig` — package constants, `NOVAPULSE_VPN_CH_V2`, GitHub (`mo3iiibest77-hub/PattNG`), support Telegram (`https://t.me/Mo3i_Best`), and server updater endpoint.
- `com.v2ray.ang.senpai.*` — CDN classifier (`Cloudflare`/`CloudFront`), physical-network clean-IP scanners, 100+ seed/curated Cloudflare CIDRs, smart-connect orchestrator, and license entitlement client (`https://nova.dontbedumb.ir`).
- `com.v2ray.ang.handler.NotificationManager` — custom `RemoteViews` dark-glass foreground/speed notification (`notif_novapulse_collapsed.xml`, `notif_novapulse_expanded.xml`).
- `com.v2ray.ang.handler.UpdateCheckerManager` & `NovaPulseApkDownloader` — server + GitHub Releases updater with device ABI matching (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`, `universal`) and `FileProvider` APK installer.
- `com.v2ray.ang.ui.qsn3d.*` — Jetpack Compose UI (`QuietStormActivity`, `Qs3dViewModel`, `Qs3dConnectScreen`, `Qs3dConfigsScreen`, `Qs3dRangesScreen`, `Qs3dSubscriptionScreen`, `Qs3dSettingsScreen`, `Qs3dLicenseScreen`, `Qs3dAboutPromoScreen`).

## Critical Guardrails

- Keep `applicationId = "com.quietstorm.ng"` and signing keystore intact; `versionCode` must stay strictly monotonic (`4000748+`).
- Never expose `savedLicenseKey` or raw server protocol/host/port strings in UI models or logs.
- Use `MMKV` (`MmkvManager`) exclusively for persistence; never introduce `SharedPreferences`.
