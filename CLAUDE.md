# PattNG — feature/minimal-cloudflare-vpn

## Project Type
Fork of 2dust/v2rayNG (v2rayNG → patterniha/PattNG → this fork).
Android VPN client using Xray core via AndroidLibXrayLite .aar binding.
Target users: Iran (DPI evasion via Cloudflare front).

## Current Branch Goal
Add a minimal "Cloudflare VPN" mode:
- VLESS + WS + TLS or VLESS + XHTTP + TLS only
- Auto-select best Cloudflare edge IP via scanner
- Simplified one-tap UX

## Key File Paths
V2rayNG/app/src/main/java/com/v2ray/ang/
├── senpai/CloudflareScanner.kt   ← scanner logic
├── senpai/IspManager.kt          ← ISP/CIDR profile persistence
├── ui/                           ← Activities + Fragments
├── viewmodel/MainViewModel.kt    ← scan trigger, profile apply
├── service/V2RayServiceManager.kt ← VPN start/stop
├── dto/                          ← ProfileItem and config models
└── util/                         ← MmkvManager, config helpers

V2rayNG/app/src/main/assets/
└── cf_ranges_v4.txt              ← 632 Cloudflare CIDRs

## Build Commands
cd V2rayNG
./gradlew assembleDebug          # build APK
./gradlew :app:compileDebugKotlin # just check Kotlin compile errors

## CI Build Filtering (permanent)
Android builds and application tests run ONLY via GitHub Actions (never locally),
and only when application source or build-critical files change. The push trigger
of `.github/workflows/build.yml` filters on `paths` so documentation-only changes
(CLAUDE.md, Report.md, README and other Markdown files) do NOT start a build.
Build-critical paths are: Kotlin/Java sources, Android resources and manifests,
Gradle/build configuration, native/core integration (AndroidLibXrayLite,
hev-socks5-tunnel, compile-hevtun.sh) and tests. Keep this filter in sync whenever
build inputs or paths change.

## Rules
- Read the full file before editing it
- After each change: explain what changed and why
- Do NOT change libv2ray.aar or its binding interface
- Scanner runs via WorkManager — don't block main thread
- When modifying profiles, always check MmkvManager for persistence
- Ask before touching VpnService or service lifecycle code

