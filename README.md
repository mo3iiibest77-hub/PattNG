# NovaPulse (نواپالس)

<div align="center">

**Smart CDN-Aware Encrypted VPN Client for Android — Powered by Xray-core & Automated License Provisioning**

[![Android API](https://img.shields.io/badge/Android%20API-24%2B-00E5FF.svg?style=flat)](https://developer.android.com/about/versions/android-7.0)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF.svg)](https://kotlinlang.org)
[![Build](https://img.shields.io/github/actions/workflow/status/mo3iiibest77-hub/PattNG/build.yml?branch=feat%2Fqsn-3d-smart-stage1&label=CI%20Build)](https://github.com/mo3iiibest77-hub/PattNG/actions)
[![Releases](https://img.shields.io/github/v/release/mo3iiibest77-hub/PattNG?label=Release&color=00FFA3)](https://github.com/mo3iiibest77-hub/PattNG/releases)
[![Telegram Support](https://img.shields.io/badge/Support-@Mo3i__Best-0088cc?logo=telegram)](https://t.me/Mo3i_Best)

</div>

---

## Overview

**NovaPulse** (`com.quietstorm.ng`) is a custom-engineered Android VPN client designed for effortless, censored-network-resilient connectivity using license-key activation and intelligent physical-network CDN IP optimization.

### Key Features

- **License-Only Activation & Device Binding (`NP-XXXX-XXXX-XXXX` / QR)**
  - Supports automated provisioning for **HM Panel** and **3x-ui** subscriptions via `https://nova.dontbedumb.ir`.
  - Enforces device slot limits, keeps activation keys hidden from UI state, and cleanly wipes local session/configs when logging out after confirming tunnel termination.
- **Smart CDN Classification & Fast-Entry Tunneling**
  - Distinguishes **Cloudflare** vs. **CloudFront / Origin** configs via DNS/header heuristics and operator-specific discovery.
  - Connects rapidly via TLS-SNI entry, then runs background clean-IP optimization (including optional 2-hour periodic tuning) over the physical network (`PhysicalNetworkGuard`) without looping through the tunnel.
  - Built-in **100+ deduplicated Cloudflare seed/curated CIDR pool** alongside AWS CloudFront official `ip-ranges.json` sync.
- **3D Glassmorphism Jetpack Compose UI (Persian RTL Default & English)**
  - Animated stationary prism border beams, dark obsidian/aurora palette, country-themed backdrop inside the main hero card, interactive dual-percentage quota gauge (`Used %` vs. `Remaining %`), per-app split tunneling, and standalone **Guide & License Promo** screen (`@Mo3i_Best`).
- **Custom NovaPulse System Notification**
  - Dedicated dark-glass `RemoteViews` notification layout (`notif_novapulse_collapsed` & `notif_novapulse_expanded`) displaying live download/upload throughput cards and direct Reconnect / Disconnect controls.
- **Built-In In-App Self-Updater**
  - Checks `https://nova.dontbedumb.ir/api/v1/license/app-update/latest` (with automatic GitHub Releases fallback) on launch and on demand in Settings.
  - Detects the phone's primary CPU ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`), recommends the matching APK alongside `universal`, downloads directly with live `%` progress and APK magic-byte/SHA-256 checks, and launches Android package installer via `FileProvider`.

---

## Architecture

```text
NovaPulse Android Client (V2rayNG/app)
├── AppConfig.kt                         # Brand constants, channels, GitHub & NovaPulse update endpoints
├── senpai/                              # Smart CDN & License Engine
│   ├── LicenseBackendClient.kt          # License redeem, quota headers & device-bound sub sync
│   ├── SmartConnectCoordinator.kt       # Fast SNI connect + background optimization & verification
│   ├── CloudflareScanner.kt             # Direct physical-network clean-IP scanner
│   ├── CloudFrontScanner.kt             # AWS CloudFront CIDR scanner
│   ├── CloudflareSeedRanges.kt          # Curated 100+ Cloudflare CIDR seed pool
│   ├── ProviderRangeStore.kt            # Discovered + seed + AWS CIDR persistence
│   └── PhysicalNetworkGuard.kt          # Binds sockets to physical network (Wi-Fi/Cellular), bypassing VPN
├── handler/
│   ├── NotificationManager.kt           # Custom RemoteViews notification + 3s live traffic telemetry
│   ├── UpdateCheckerManager.kt          # Multi-source release check + ABI-matched APK resolution
│   └── NovaPulseApkDownloader.kt        # Streaming APK downloader + FileProvider installer launcher
└── ui/qsn3d/                            # Primary Jetpack Compose Experience
    ├── QuietStormActivity.kt            # Entry Activity, top update banner & update dialog
    ├── Qs3dViewModel.kt                 # StateFlow orchestrator, logout safety & self-update flow
    ├── Qs3dConnectScreen.kt             # Main orb dashboard, live speeds & country card backdrop
    ├── Qs3dConfigsScreen.kt             # Privacy-hardened node list (remarks + delay + CDN badge only)
    ├── Qs3dRangesScreen.kt              # Discovery radar & read-only CIDR explorer
    ├── Qs3dSubscriptionScreen.kt        # Bounded Dual-% quota bar, dates & device slots
    ├── Qs3dSettingsScreen.kt            # License logout/change, updater card, language & Per-App proxy
    └── Qs3dAboutPromoScreen.kt          # Standalone guide, FAQ & Telegram purchase/support screen
```

---

## Downloads & Releases

All official builds are published as **Standard / PlayStore flavor** (`com.quietstorm.ng`, no F-Droid builds):

- `NovaPulse_<version>_arm64-v8a.apk` — Recommended for modern 64-bit Android phones
- `NovaPulse_<version>_armeabi-v7a.apk` — 32-bit ARM devices
- `NovaPulse_<version>_x86_64.apk` / `NovaPulse_<version>_x86.apk` — Emulators / x86 tablets
- `NovaPulse_<version>_universal.apk` — Universal multi-ABI package

Download directly from [GitHub Releases](https://github.com/mo3iiibest77-hub/PattNG/releases) or trigger auto-update from **Settings → App Version & Updates** inside NovaPulse.

---

## Building & Testing

### Prerequisites
- Android SDK (compileSdk `37`, minSdk `24`), JDK `21`, NDK `29.0.14206865` (for `hev-socks5-tunnel`)
- `libv2ray.aar` in `V2rayNG/app/libs/`

### Run Unit Tests
```bash
cd V2rayNG
./gradlew testPlaystoreDebugUnitTest --no-daemon
```

### Build Signed Release APKs
```bash
cd V2rayNG
./gradlew assemblePlaystoreRelease \
  -Pandroid.injected.signing.store.file=/path/to/keystore.jks \
  -Pandroid.injected.signing.store.password=... \
  -Pandroid.injected.signing.key.alias=... \
  -Pandroid.injected.signing.key.password=...
```

Outputs are written to `V2rayNG/app/build/outputs/apk/playstore/release/NovaPulse_*.apk`.

### Automated GitHub Release Workflow
Pushing a version tag (`v2.4.0`, `v2.5.0`, ...) or running `.github/workflows/build.yml` via `workflow_dispatch` with `release_tag`:
1. Runs full unit tests (`testPlaystoreDebugUnitTest`).
2. Builds `playstore` release APKs only (fails CI immediately if any `fdroid` artifact appears).
3. Verifies `applicationId = com.quietstorm.ng`, monotonic `versionCode`, and exact release signing certificate SHA-256.
4. Generates `SHA256SUMS.txt` and publishes all clean APKs to GitHub Releases.

---

## License Server & Update Hosting (`/opt/novapulse-license`)

- `POST /api/v1/license/redeem` — Activates license key and issues device-bound session token.
- `GET /api/v1/license/app-update/latest` — Returns latest NovaPulse release metadata (supports local `/opt/novapulse-license/releases/latest.json` override or cached GitHub Release metadata, filtering out non-APK / `fdroid` entries).
- `GET /api/v1/license/app-update/files/<apk>` — Streams locally hosted `.apk` files directly over HTTPS when placed in `/opt/novapulse-license/releases/`.

---

## Support

- **Telegram**: [@Mo3i_Best](https://t.me/Mo3i_Best)
- **Privacy Policy**: [CR.md](./CR.md)
