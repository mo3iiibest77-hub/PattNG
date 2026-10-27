# PattNG Server and GitHub Audit Report

> This is the persistent audit and change-history document for the `mo3iiibest77-hub/PattNG`
> repository. It is maintained per `AGENTS.md` Section D. Historical findings are preserved;
> new entries are appended. Do not erase prior findings.

---

## 1. Audit Summary

| Field | Value |
|---|---|
| Latest audit timestamp | 2026-10-26 22:34:27 UTC |
| Repository URL | git@github.com:mo3iiibest77-hub/PattNG.git (SSH) — see Section 3 for history |
| Repository owner | mo3iiibest77-hub |
| Repository name | PattNG |
| Primary branch | `master` |
| Application identity | v2rayNG fork — application id `com.quietstorm.ng`, output APK `QuietStormNG`, versionName `2.3.4`, versionCode `744` |
| Overall outcome | Documentation-only baseline task. `AGENTS.md` (workflow rules) and this `Report.md` (audit baseline) added/updated. Application source unchanged. Build/tests NOT RUN. Local commits from the prior audit task are preserved and pushed in this task. |

### Task history (append-only)

| Date (UTC) | Task | Outcome |
|---|---|---|
| 2026-10-26 21:59 | Initial server + GitHub audit | Repository cloned fresh into `/root/PattNG`; `Report.md` committed locally (`77d188f9`). Push **blocked** — no Git credentials on server (HTTPS remote, no credential helper / token / gh). Preserved locally. |
| 2026-10-26 22:15 | Corrected Report.md publication status | Second local commit (`36ad9da6`) correcting Section 7 to state push was blocked. Remote switched to SSH after this. |
| 2026-10-26 22:34:27 UTC | Repository workflow + audit baseline (this task) | `AGENTS.md` created at repo root; `Report.md` updated as audit baseline with verified source-area inventory and Next Audit Tasks. Prior 2 local commits + this one pushed to `master` via SSH. |

## 2. Server Discovery

- **Existing checkout found before initial clone:** No.
- **Paths searched (summarized):**
  - Current working directory: `/root`.
  - Home directory tree under `/root` (max depth 4), excluding system/sensitive directories
    (`.codebuddy`, `.cache`, `.config`, `.npm`, `.nvm`, `.docker`, `.gnupg`, `.ssh`,
    `.acme.sh`, `.claude`, `.codex`, `.local`, `snap`).
  - Common development locations: `/root/Project`, `/root/scripts`, `/root/tools`,
    `/root/vendor-src`, `/opt`, `/srv`, `/home`.
  - Searched for any directory literally named `PattNG` (case-insensitive) under `/root`
    (max depth 4).
  - Searched for any Git repository whose configured remote points to
    `mo3iiibest77-hub/PattNG`.
- **Result of search:** The only Git repository found under `/root` was
  `/root/vendor-src/Estedad`, whose remote does **not** match `mo3iiibest77-hub/PattNG`. No
  directory named `PattNG` existed, and no checkout with a matching remote was found.
- **Absolute path of the selected checkout:** `/root/PattNG` (freshly cloned on 2026-10-26).
- **Cloning was required:** Yes.
- **Multiple checkouts found:** No — only one checkout exists at `/root/PattNG`.

> Note: The search covered reasonable development directories under the current user's home and
> common system locations, but was not an exhaustive scan of the entire filesystem. The
> conclusion is "not found in the locations inspected," not "does not exist anywhere on the
> server."

## 3. Local Git Status

| Field | Value |
|---|---|
| Current branch | `master` |
| HEAD before this task | `36ad9da66d4e0b68cc143fec01b52dda4ad583fa` |
| `origin/master` before this task | `d6b7f027090fbe374155431a698ba330a48d8cc8` |
| Local commits not yet pushed (before this task) | 2 (`77d188f9`, `36ad9da6`) |
| Remote commits not present locally | 0 (local not behind) |
| Configured remote name | `origin` |
| Remote fetch URL | `git@github.com:mo3iiibest77-hub/PattNG.git` (SSH; was HTTPS during the initial audit — see history) |
| Remote push URL | `git@github.com:mo3iiibest77-hub/PattNG.git` |
| Upstream tracking branch | `origin/master` |
| SSH auth verified | Yes — `ssh -T git@github.com` returns "Hi mo3iiibest77-hub! You've successfully authenticated..." |
| Working-tree status (before this task's edits) | Clean |

**Remote URL history:** The initial clone used HTTPS
(`https://github.com/mo3iiibest77-hub/PattNG.git`) and push failed with no credentials. The
remote is now SSH (`git@github.com:mo3iiibest77-hub/PattNG.git`) and SSH key auth is
configured and verified. No credentials, tokens, or keys are stored in the repo.

## 4. Remote Repository Status

| Field | Value |
|---|---|
| Remote default branch | `master` (`origin/HEAD -> origin/master`) |
| Latest remote commit hash | `d6b7f027090fbe374155431a698ba330a48d8cc8` |
| Latest remote commit author | mo3iiibest77-hub |
| Latest remote commit date | 2026-10-23 12:37:53 +0330 |
| Latest remote commit subject | Create main.yml |
| Fetching remote metadata succeeded | Yes (`git fetch origin` exit 0; SSH auth OK) |
| Other remote branches observed | `origin/main`, `origin/fix/isp-discovery-quality` |
| Errors or limitations | None at this audit. (Initial audit push failure is historical — see Section 1.) |

## 5. Latest Commits

Latest five commits from the remote default branch (`origin/master`) at the time of this audit,
newest first:

| # | Short hash | Author | Commit date | Subject | GitHub URL |
|---|---|---|---|---|---|
| 1 | `d6b7f027` | mo3iiibest77-hub | 2026-10-23 12:37:53 +0330 | Create main.yml | https://github.com/mo3iiibest77-hub/PattNG/commit/d6b7f027090fbe374155431a698ba330a48d8cc8 |
| 2 | `c489a5aa` | Mo3iBest | 2026-09-08 11:35:37 +0000 | feat: auto-inject QSN finalMask on xHTTP+TLS and WS+TLS subscription import | https://github.com/mo3iiibest77-hub/PattNG/commit/c489a5aa2b59ccb92be7c8f849ef80ef54f99c81 |
| 3 | `e4439ecc` | Mo3iBest | 2026-08-21 00:12:58 +0000 | fix: add onAddManualCidr/onRemoveManualCidr to Activity | https://github.com/mo3iiibest77-hub/PattNG/commit/e4439eccb3c8fbdbf77b288110ede775a8af004d |
| 4 | `a7025726` | Mo3iBest | 2026-08-20 23:57:10 +0000 | fix: S_BgCard->S_Card, S_S_Gold->S_Gold | https://github.com/mo3iiibest77-hub/PattNG/commit/a7025726482b64ed141286f888f65332349f6fb6 |
| 5 | `db6c4ce7` | Mo3iBest | 2026-08-20 23:41:18 +0000 | feat: resume discovery, manual CIDR, show saved ranges | https://github.com/mo3iiibest77-hub/PattNG/commit/db6c4ce7a35f6f3be2a44021f1b9a5983a9d3bbe |

> Note: Local commits `77d188f9` and `36ad9da6` (the prior audit's Report.md commits) were not
> yet on the remote at audit time. They are pushed as part of this task — see Section 7.

## 6. Comparison and Findings

**Verified facts:**

- **Project already on the server?** No — prior to the initial audit on 2026-10-26, the
  repository was not present in any inspected location. It was cloned fresh into
  `/root/PattNG`. (Historical finding, preserved.)
- **Local and remote histories match (at pre-task HEAD)?** At the start of this task, local
  `master` was **2 commits ahead** of `origin/master` (the two Report.md commits from the
  prior audit), and **0 behind**. The common ancestor with `origin/master` is
  `d6b7f027`.
- **Divergence handling:** No divergence to reconcile — local is strictly ahead, remote
  unchanged. Push is a fast-forward.
- **Uncommitted changes before this task's edits?** None (working tree was clean).
- **Remote access succeeded?** Yes. `git fetch origin` (SSH) completed with exit 0; `ssh -T
  git@github.com` authenticates as `mo3iiibest77-hub`.

**Source-area inventory (verified by inspection, not by build/run):**

The following PattNG-specific functionality areas were located in the source. Their **presence**
is verified; their **correctness/runtime behavior** is **NOT verified** (no build or test was
run in this documentation-only task). Each is an audit target for a future implementation task.

| Area | Verified location(s) | Status |
|---|---|---|
| Cloudflare IP scanning & candidate selection | `senpai/CloudflareScanner.kt`, `ui/CfScanActivity.kt`, `service/CfScanService.kt` | Present — **unverified** behavior |
| `cf_ranges_v4.txt` asset & range management | `V2rayNG/app/src/main/assets/cf_ranges_v4.txt` (632 lines, 628 IPv4 CIDRs per header comment) | Present — **unverified** loading/update paths |
| `finalMask` / QSN injection | `handler/AngConfigManager.kt:429-433` (auto-inject on xHTTP+TLS / WS+TLS sub import); `core/CoreOutboundBuilder.kt:267,338,520-521` (apply to outbound) | Present — **unverified** end-to-end |
| Cipher Suite configuration | `fmt/FmtBase.kt:86,108` (`cs` query param); referenced in `AppConfig.kt`, `ServerActivity.kt`, `VmessFmt.kt` | Present — **unverified** compatibility |
| Subscription import & update | `handler/SubscriptionUpdater.kt`, `handler/AngConfigManager.kt`; entry points in `ui/sub/` | Present — **unverified** |
| WS + TLS and XHTTP + TLS handling | `enums/NetworkType.kt` (WS, XHTTP, HTTP_UPGRADE, ...); stream settings in `CoreOutboundBuilder.kt`; parsers in `fmt/` | Present — **unverified** |
| TLS SNI / HTTP Host / dest address / transport settings | `ui/server/*` (`ServerActivity.kt`, `BaseServerActivity.kt`, `ServerUiState.kt`); `dto/V2rayConfig.kt`, `dto/entities/ProfileItem.kt` | Present — **unverified** |
| VLESS / VMess / Trojan / other protocols | `fmt/VlessFmt.kt`, `fmt/VmessFmt.kt`, `fmt/TrojanFmt.kt`, `fmt/ShadowsocksFmt.kt`, `fmt/Hysteria2Fmt.kt`, `fmt/WireguardFmt.kt`, `fmt/SocksFmt.kt`, `fmt/CustomFmt.kt`, `fmt/V2rayNFmt.kt` | Present — **unverified** |
| Per-App Proxy | `ui/perappproxy/`, `ui/apppicker/` | Present — **unverified** |
| ISP manager (PattNG-specific) | `senpai/IspManager.kt` | Present — **unverified** |

**Build/test status (this task):** NOT RUN. This is a documentation-only task; no application
source was changed and no Gradle build, unit test, or instrumented test was executed.

**Unknowns / limitations:**

- The server-side search (initial audit) was limited to reasonable dev directories and was not
  an exhaustive whole-filesystem scan.
- The source inventory above confirms **presence** of the PattNG-specific areas but does **not**
  confirm correctness, runtime behavior, or completeness. See "Next Audit Tasks".

## 7. Report Publication

| Field | Value |
|---|---|
| Report file path | `/root/PattNG/Report.md` |
| Workflow file path | `/root/PattNG/AGENTS.md` (new in this task) |
| Branch to which changes are pushed | `master` |
| Files staged in this task's commit | `AGENTS.md` (new), `Report.md` (updated) |
| Commit message (this task) | `docs: add AGENTS.md workflow rules and update Report.md audit baseline` |
| Prior audit commits also pushed | `77d188f9`, `36ad9da6` (preserved from initial audit) |
| Push method | SSH (`git@github.com:mo3iiibest77-hub/PattNG.git`) |
| Push status | See verification block below (populated after push) |
| Direct GitHub URL to AGENTS.md | https://github.com/mo3iiibest77-hub/PattNG/blob/master/AGENTS.md |
| Direct GitHub URL to Report.md | https://github.com/mo3iiibest77-hub/PattNG/blob/master/Report.md |

**Push history (historical, preserved):**

- 2026-10-26 (initial audit): Push **FAILED** — HTTPS remote, GitHub prompted for username, no
  credential helper / `gh` CLI / `GITHUB_TOKEN` on server. Local commits preserved. No
  bypass attempted.
- 2026-10-26 22:34:27 UTC (this task): Remote is now SSH with verified key auth. Push attempted — see
  verification block below.

**Verification block (populated after the push in this task):**

- Commit SHA(s) pushed (this task): `7e7fe27cc8d68da8673a4e0b4426dac2ea99d219` (plus preserved prior commits `77d188f9`, `36ad9da6`).
- `git push origin master` exit code: `0`. Remote updated: `d6b7f027..7e7fe27c  master -> master` (fast-forward, no force).
- Remote `origin/master` after push: `7e7fe27cc8d68da8673a4e0b4426dac2ea99d219` (matches local `master`; ahead 0, behind 0).
- `AGENTS.md` present on remote: Yes (blob `76d8d6ef8d8bcbedc05c6e7d4e52d98bebb2a3ce`).
- `Report.md` present on remote: Yes (blob `418befd72f980fc221e02043dc5314821d479ce4`).
- Direct GitHub commit URL: https://github.com/mo3iiibest77-hub/PattNG/commit/7e7fe27cc8d68da8673a4e0b4426dac2ea99d219
- Direct GitHub URL to AGENTS.md: https://github.com/mo3iiibest77-hub/PattNG/blob/master/AGENTS.md
- Direct GitHub URL to Report.md: https://github.com/mo3iiibest77-hub/PattNG/blob/master/Report.md

## 8. Next Audit Tasks

These are the next source areas that require **implementation-level inspection** (presence is
confirmed above; correctness is not). Each should be a separately authorized task:

1. **Cloudflare scanner runtime path** — Trace `CfScanService` → `CloudflareScanner` end to
   end: range loading from `cf_ranges_v4.txt`, candidate selection strategy, result write-back
   to a profile, and interaction with `IspManager`. Verify the scan actually updates a usable
   outbound.
2. **finalMask / QSN injection correctness** — In `AngConfigManager.kt:429-433`, the
   auto-injected JSON is a hardcoded string. Verify (a) it is only injected for xHTTP+TLS and
   WS+TLS, (b) it is not double-injected on re-import, (c) `CoreOutboundBuilder.kt` consumes
   it correctly, and (d) it round-trips through save/edit/reload.
3. **Cipher suite (`cs`) compatibility** — Confirm which Xray core versions honor the
   `cipherSuites` field, and that the UI prevents invalid combinations. Cross-check the
   prebuilt AAR core version.
4. **Subscription import/update lifecycle** — `SubscriptionUpdater` scheduling, update on
   app start, manual refresh, and conflict handling when a server entry is edited locally but
   updated remotely.
5. **WS+TLS / XHTTP+TLS transport settings** — Verify SNI, Host, path, and dest are preserved
   through the parser → DTO → outbound builder path for both transports.
6. **Per-App Proxy** — Verify the package list is persisted and enforced in VPN mode and
   proxy-only mode.
7. **Build & test baseline** — Run `cd V2rayNG && ./gradlew assembleFdroidDebug` and `./gradlew test`
   to establish a green/red baseline before any implementation task. Record results as PASS/FAIL.

> None of the above authorizes implementation work. Each requires a separate explicit task.
---

## 9. Phase 2 — Automatic Connection Logic (feature/minimal-cloudflare-vpn)

> Branch `feature/minimal-cloudflare-vpn` (created from `a52f20c2` on `master`).
> Scope: implement auto-connect logic and essential technical components only — no UI changes,
> no builds on server. Build validation deferred to GitHub Actions on push.

| Field | Value |
|---|---|
| Task start (UTC) | 2026-10-26 23:06 |
| Branch | `feature/minimal-cloudflare-vpn` |
| Branch base | `a52f20c2` (master) |
| Phase scope | Automatic connection logic, config filter, scanner integration, unit tests |
| UI changes | None |
| Server-side builds | None — builds only via GitHub Actions `build.yml` |

### 9.1 Files Created

| File | Purpose |
|---|---|
| `V2rayNG/app/src/main/java/com/v2ray/ang/ui/connect/AutoConfigFilter.kt` | Protocol-independent eligibility filter: ws/xhttp + TLS + CF HTTPS port. Does NOT remove ineligible configs from storage. |
| `V2rayNG/app/src/main/java/com/v2ray/ang/ui/connect/ConnectionOrchestrator.kt` | Full auto-connect flow: sub fetch → parse → filter → ensure settings → CF scan → apply IP → start VPN → await core confirmation. Single-flight via Mutex, cancellable. No fake success — polls `CoreServiceManager.isRunning()`. |
| `V2rayNG/app/src/test/java/com/v2ray/ang/ui/connect/AutoConfigFilterTest.kt` | 19 unit tests covering all eligibility criteria (network, security, port, protocol params, edge cases). |
| `V2rayNG/app/src/test/java/com/v2ray/ang/ui/connect/ConnectionOrchestratorTest.kt` | 8 unit tests covering state/result sealed interface contracts and cancel safety. |

### 9.2 Files Modified

| File | Change |
|---|---|
| `V2rayNG/app/src/main/java/com/v2ray/ang/senpai/CloudflareScanner.kt` | Added public aliases `PRODUCTION_FINALMASK_VALUE` and `PRODUCTION_CIPHERSUITES_VALUE` so the orchestrator can fill blank settings without duplicating the hardcoded values. Private originals remain the single source of truth. No behavioral change to scanner logic. |

### 9.3 Design Decisions

- **Config filtering is protocol-independent**: Any of VLESS, VMess, Trojan, SS, SOCKS, WireGuard, Hysteria2, HTTP that meets ws/xhttp + TLS + CF HTTPS port criteria is eligible. Parsers and existing protocol support are untouched.
- **HTTPS ports**: `{443, 2053, 2083, 2087, 2096, 8443}` — Cloudflare CDN HTTPS edge ports (documented standard, not guessed). HTTP-edge ports (2052, 2082, 2086, 2095) are excluded because auto-connect requires TLS.
- **Transport preservation**: The orchestrator only writes `server`, `fingerPrint`, and blank `finalMask`/`cipherSuites` — exactly what `CloudflareScanner.applyBestIp` already does. SNI, Host, Path, network, security, and all protocol params are never touched.
- **No fake success**: `waitForCoreRunning()` polls `CoreServiceManager.isRunning()` (which reads `coreController.isRunning`) with a 30s timeout. If the core does not report running, the result is `AutoConnectResult.Failure`.
- **Single-flight**: A `Mutex` ensures only one `autoConnect()` call runs at a time. Concurrent calls return `Failure("Already running")`.
- **Cancellation**: `cancel()` calls `CloudflareScanner.cancel()` and cancels any pending wait job. Safe to call when idle.

### 9.4 Connection State Machine

```
WaitingForSubscription
  → FetchingSubscription
  → ParsingConfigurations
  → FilteringConfigurations
  → ApplyingConnectionSettings
  → ScanningCloudflareIps
  → SelectingBestIp(bestIp)
  → StartingVpn
  → Connected(guid) | Failed(reason)
```

States are a sealed interface (`AutoConnectState`) so Phase 3 UI can render them with an exhaustive `when`.

### 9.5 Build/Test Status

- **Server-side build**: NOT RUN (per AGENTS.md — builds only via GitHub Actions).
- **Unit tests**: NOT RUN on server. Will be validated by GitHub Actions `build.yml` on push to `feature/minimal-cloudflare-vpn`.
- **GitHub Actions build result**: Pending — will be reported here after push.

### 9.6 Verification block (populated after push)

- Commit SHA pushed: `e02796a1` (6 files, 777 insertions)
- `git push origin feature/minimal-cloudflare-vpn` exit code: `0`. New branch created on remote.
- GitHub PR URL: https://github.com/mo3iiibest77-hub/PattNG/pull/new/feature/minimal-cloudflare-vpn
- **GitHub Actions build**: `build.yml` triggers only on `push: branches: [master]` and `workflow_dispatch`. It did **not** auto-trigger for this feature branch. The build must be triggered manually:
  - Option A: Open the PR on GitHub (URL above) and run the workflow via Actions tab → "Build APK" → "Run workflow" → select `feature/minimal-cloudflare-vpn`.
  - Option B: Merge the PR to `master` (auto-triggers build).
  - Option C: Temporarily add `feature/minimal-cloudflare-vpn` to the `build.yml` branches list (requires separate authorization — not done in this task).
- Build result: **PENDING** — awaiting manual trigger on GitHub.


---

## 10. Corrective Phase — Code Fixes, Architecture, Security, GitHub Actions

> Branch: `feature/minimal-cloudflare-vpn` (same branch, no new branch, no history rewrite).
> Scope: limited corrective fixes on Phase 2 code. No UI, no Phase 3.

### 10.1 Files Changed and Reasons

| File | Change | Reason |
|---|---|---|
| `ui/connect/AutoConfigFilter.kt` | Removed HTTP ports (2052, 2082, 2086, 2095) from `HTTPS_PORTS`. Added `HTTP_ONLY_PORTS` set. | These are Cloudflare HTTP-edge ports, not HTTPS. Auto-connect requires TLS. They were in the set despite the comment saying excluded. |
| `ui/connect/ConnectionOrchestrator.kt` | Full rewrite of internal logic. See 10.2. | Fix Mutex import, GUID pairing, scan wait, CoreServiceManager native-lib risk, subscription security, cancel cleanup. |
| `senpai/CloudflareScanner.kt` | `applyBestIp`: fingerPrint now only set if blank (was unconditional). Log sanitized. | `CoreOutboundBuilder.populateTlsSettings` line 558 already hardcodes `fingerprint="unsafe"` for all TLS configs. Profile value is not load-bearing. Overwriting was unnecessary mutation. |
| `handler/AngConfigManager.kt` | `updateConfigViaSub`: replaced `LogUtil.i(TAG, url)` with `LogUtil.i(TAG, "Updating subscription: " + remarks)`. | Full subscription URL was logged. May contain auth tokens. Now logs only remarks name. |
| `test/.../AutoConfigFilterTest.kt` | Rewritten: 27 tests. Fixed configType val assignment. Added 4 HTTP port rejection tests + disjoint-set test. | configType is a val — apply{} does not compile. HTTP ports were in HTTPS_PORTS. |
| `test/.../ConnectionOrchestratorTest.kt` | Rewritten: 10 tests. Removed sealed-interface instantiation tests. Added: error sanitization, cancel safety, GUID-Profile pairing, single-flight. | Previous tests proved nothing about behavior. |
| `test/.../CloudflareScannerPreservationTest.kt` | New: 18 tests. Regression tests for TLS/transport preservation. | Verifies SNI/Host/Path/XHTTP/Network/Security/auth/ALPN/insecure/pinnedCA preserved. |
| `.github/workflows/main.yml` | **Deleted.** | Empty file (1 byte). Caused failed runs #1-#5 on every push. |
| `.github/workflows/build.yml` | Added `feature/minimal-cloudflare-vpn` to push branches. | Build now triggers on feature branch push. |
| `.github/workflows/fastlane.yml` | Added `feature/minimal-cloudflare-vpn` to push and PR branches. | Fastlane validation now runs on feature branch. |

### 10.2 ConnectionOrchestrator Fixes

| Issue | Root Cause | Fix |
|---|---|---|
| Mutex import | `kotlinx.coroutines.Mutex` — wrong package. | `kotlinx.coroutines.sync.Mutex` (matches MainViewModel). |
| GUID misalignment | `allGuids[allConfigs.indexOf(selected)]` — mapNotNull shifts indices. Duplicates return first match. | `mapNotNull { guid -> decodeServerConfig(guid)?.let { guid to it } }` producing `List<Pair<String, ProfileItem>>`. GUID always belongs to selected profile. |
| Scan wait race | Polling loop with delay(500). onFinish(null) left bestResult as null (same as initial) — loop continued until timeout. | `CompletableDeferred<CandidateResult?>`. onFinish(best) completes with best. onCancelled completes with null. No polling. |
| waitJob unused | Declared as Job? but never assigned. | Removed. Replaced with CompletableDeferred fields. |
| CoreServiceManager native risk | `isRunning()` accesses `coreController` which is eagerly initialized via `newCoreController()` at singleton load. Loads native libs in UI process. | `waitForCoreRunningBroadcast()`: registers BroadcastReceiver for BROADCAST_ACTION_ACTIVITY, awaits MSG_STATE_RUNNING (11) or MSG_STATE_START_FAILURE (32) from daemon. No native-lib loading in UI process. |
| Subscription URL in errors | `Failure("... ${e.message}")` — e.message may contain URL. | Removed e.message from failure messages. All messages are static strings. |
| Cancel cleanup | cancel() only called CloudflareScanner.cancel() and waitJob?.cancel() (no-op). Receiver could leak. | cancel() sets flag, completes deferreds, cleanup() in finally unregisters receiver. |

### 10.3 CoreServiceManager Architecture Finding

**Risk confirmed:** `CoreServiceManager` object singleton has:
```kotlin
private val coreController: CoreController = CoreNativeManager.newCoreController(CoreCallback())
```
This is **eager initialization** — referencing CoreServiceManager for the first time triggers newCoreController(), loading native libraries via JNI.

`LauncherManager.startContextService()` comment: "Note: isRunning check is removed here to avoid loading Native libraries in the UI process."

**Fix:** Orchestrator now uses `waitForCoreRunningBroadcast()` — registers BroadcastReceiver for BROADCAST_ACTION_ACTIVITY, awaits MSG_STATE_RUNNING from daemon. Matches existing architecture. No new path, no native-core disruption.

### 10.4 Fingerprint/Transport Finding

**`applyBestIp` previously set `fingerPrint = "unsafe"` unconditionally.**

**Finding:** `CoreOutboundBuilder.populateTlsSettings` (line 558) already hardcodes:
```kotlin
fingerprint = "unsafe", // QuietStormNG: always force unsafe fingerprint for CF configs
```
Applied to all TLS configs regardless of profileItem.fingerPrint. Profile value is not load-bearing.

**Fix:** `applyBestIp` now only sets fingerPrint if blank. No functional change to outbound config. 18 regression tests verify preservation.

**Preserved fields:** SNI, Host, Path, network, security, serverPort, password, username, method, publicKey, ALPN, xhttpMode, xhttpExtra, insecure, pinnedCA256, configType.

### 10.5 GitHub Actions — Failed Runs Root Cause

**Root cause:** `.github/workflows/main.yml` was empty (1 byte). GitHub Actions parsed it as a workflow and failed on every push.

**Failed runs due to main.yml:**
- #1 (d6b7f02, master, Oct 23) — Failure
- #2 (7e7fe27, master, Oct 26) — Failure
- #3 (a52f20c, master, Oct 26) — Failure
- #4 (e02796a, feature branch, Oct 26) — Failure
- #5 (1bc51b6, feature branch, Oct 26) — Failure

**Successful Build APK runs (master only):**
- #61 (d6b7f02) — 9m 6s — Success
- #62 (7e7fe27) — 6m 48s — Success
- #63 (a52f20c) — 5m 46s — Success

**Fix:** Deleted main.yml. Added feature/minimal-cloudflare-vpn to build.yml and fastlane.yml.

### 10.6 Test Summary

| Test File | Tests | Coverage |
|---|---|---|
| `AutoConfigFilterTest.kt` | 27 | HTTPS port acceptance, HTTP port rejection (4+collective), disjoint sets, transport rejection, security rejection, port rejection, server rejection, protocol auth rejection, order preservation, empty input. |
| `ConnectionOrchestratorTest.kt` | 10 | Error sanitization, cancel safety, GUID-Profile pairing, single-flight contract. |
| `CloudflareScannerPreservationTest.kt` | 18 | SNI/Host/Path/XHTTP/Network/Security/Port/Auth/ALPN/insecure/pinnedCA/configType preserved. fingerPrint/finalMask/cipherSuites only filled if blank. |
| **Total** | **55** | |

### 10.7 Verification block

- Commits pushed (this corrective phase):
  - `76b87df5` — primary corrective commit (11 files, +668/-161)
  - `4db90f95` — follow-up: broadcast extra key (`key` not `what`), `Utils.receiverFlags()`, register-before-start race, `Stopping` state on cancel, exception-class-only logging
- Push method: SSH. No force push. No master push. Only related files staged explicitly.

**GitHub Actions — verified results:**

| Run | Workflow | Commit | Branch | Status | Duration |
|---|---|---|---|---|---|
| #65 | Build APK | `4db90f9` | feature/minimal-cloudflare-vpn | **Success** | 6m 51s |
| #64 | Build APK | `76b87df` | feature/minimal-cloudflare-vpn | **Success** | 6m 45s |
| #65 | Validate Fastlane metadata | `4db90f9` | feature/minimal-cloudflare-vpn | **Success** | 9s |
| #64 | Validate Fastlane metadata | `76b87df` | feature/minimal-cloudflare-vpn | **Success** | 12s |

- Build APK run #65 URL: https://github.com/mo3iiibest77-hub/PattNG/actions/runs/40420680113
- Build APK run #64 URL: https://github.com/mo3iiibest77-hub/PattNG/actions/runs/40419937803
- Fastlane run #65 URL: https://github.com/mo3iiibest77-hub/PattNG/actions/runs/40420680103

**Artifacts (Build APK #65):**

| Artifact | Size | SHA-256 |
|---|---|---|
| arm64-v8a | 60.7 MB | `062440ae16b7452cb3d844793d6b2b31ed328bba1dee2b655456031379a8fa33` |
| armeabi-v7a | 61.4 MB | `0f36def9ceb2a99bd47d54ecf2a6116669e92fe9707b723944c8a79ae5ce0ff0` |
| universal-apk | 136 MB | `c39430e49b1a8a19ee3bd1110e85127847737210520d8a375dc846b41606a5e9` |
| x86-apk | 126 MB | `b7dd0f412d18e584b9cf5ab23261c7822ba38acc5867478f337ff2b885fd7576` |

- `sign-and-release` job: skipped (runs only on `workflow_dispatch` with a `release_tag` input) — expected.
- The APK build is now triggered automatically on push to `feature/minimal-cloudflare-vpn` (previously master-only).
- **Note:** these artifacts prove the code **compiles and packages**; they do not prove runtime behavior of the auto-connect flow (see remaining risks).

### 10.8 Remaining Risks / Tests Not Run

- **Unit tests were not executed locally or on the server** (no Gradle build on the server per AGENTS.md; the `build.yml` workflow runs `assembleRelease`, which does **not** run unit tests). The 55 unit tests compile as part of the build only if `test` is invoked — it is not. **The unit tests have not been executed.** Running them requires a separate `./gradlew test` job (not added here, to avoid scope creep) or a local run by the maintainer.
- **Runtime behavior of auto-connect is unverified** — no device/emulator run. Specifically unverified at runtime: subscription refresh, CF scan result handling, `MSG_STATE_RUNNING` broadcast reception timing, and VPN start confirmation.
- **Already-running-core edge case:** if the VPN core is already running when `autoConnect` is called, the daemon may not emit a fresh `MSG_STATE_RUNNING`, so `awaitCoreRunning` could time out. Not handled in this phase (no explicit status-query broadcast added).
- **`ensureSettings` / `applyBestIp` preservation** is verified by simulation tests (pure logic), not against real MMKV storage.
- **`Utils.receiverFlags()` returns `RECEIVER_EXPORTED` on API 33+** — this matches the existing project pattern (MainRepository), but it does mean the receiver is technically exportable. This is pre-existing project behavior, not introduced here.

---

## 11. Independent Re-audit — Quiet Storm NG (corrective pass 2)

> Branch: `feature/minimal-cloudflare-vpn`. `master` untouched at `a52f20c2`.
> No new branch, no history rewrite, no force push, no local/server Gradle build.

### 11.1 Verified baseline before this pass

| Item | Value |
|---|---|
| Branch / HEAD | `feature/minimal-cloudflare-vpn` / `fbfcc16a` |
| `master` | `a52f20c2` (unchanged) |
| Working tree | clean |
| Commits ahead of master | 7 |
| Last green CI | Build APK #68 (`fbfcc16`) — **success**, 7m 15s, 4 artifacts |

Build APK #68 also ran the `Run unit tests` step successfully, so the 55 tests
present at that commit genuinely executed and passed on CI (Gradle fails with
"No tests found for given includes" when a `--tests` filter matches nothing, so
a green step proves the tests ran).

### 11.2 Defects found in this pass

| # | Severity | File | Defect | Fix |
|---|---|---|---|---|
| 1 | **Critical** | `ConnectionOrchestrator.kt` | Waited only for `MSG_STATE_RUNNING`, but the daemon announces a successful start with `MSG_STATE_START_SUCCESS`; `MSG_STATE_RUNNING` is sent **only** as a reply to `MSG_REGISTER_CLIENT` (`CoreServiceManager.kt:175` vs `:458`). Every fresh connect would have timed out and reported failure while the VPN was actually up. | Treat `START_SUCCESS` and `RUNNING` both as "core is up". |
| 2 | **High** | `ConnectionOrchestrator.kt` | Already-connected case: no new `RUNNING` broadcast is emitted, so the wait timed out; worse, the newly selected config and freshly applied best IP would never take effect. | Probe with `MSG_REGISTER_CLIENT`; if already running, `LauncherManager.restartService()` so the selected config/IP is actually loaded. `STOP_SUCCESS` is ignored during the wait because restart emits it legitimately. |
| 3 | **Critical (security)** | `HttpUtil.kt` | `LogUtil.d("Adding custom header: $key = $value")` logged subscription header **values** (commonly `Authorization`/`X-API-Key`), and `downloadToFile` logged the full URL on failure. | Log header name only; added `redactUrl()` (scheme + host + `<redacted>`) for download failures. |
| 4 | High | `ConnectionOrchestrator.kt` | The 180 s scan safety timeout returned null but left `CloudflareScanner` running in its own scope: orphaned CPU/network work and temp MMKV profiles. | Cancel the scanner when the deferred is still incomplete after the timeout. |
| 5 | High | `ConnectionOrchestrator.kt` | `decodeSubscriptions().first()` could pick a **disabled** subscription, yielding `configCount == 0` and a misleading "Subscription returned 0 configs" failure. | Select the first **enabled** subscription; explicit "No enabled subscription configured" otherwise. |
| 6 | Medium | `ConnectionOrchestrator.kt` | Unexpected exceptions from MMKV/scanner/service escaped `autoConnect` instead of producing a result. | Catch `Exception` (rethrow `CancellationException`) and return a sanitized `Failure`. |
| 7 | Medium | `ConnectionOrchestratorTest.kt` | GUID-pairing tests re-implemented the logic instead of testing production code — they would pass even if the orchestrator regressed. | Extracted `pairGuidsWithProfiles()` and the tests now call the real function, plus a selection test that runs the real `AutoConfigFilter.isEligible`. |
| 8 | Low | `AutoConfigFilter.kt` | Case-sensitivity of `network`/`security` was undocumented and looked like an oversight. | Documented that it is intentional and matches `NetworkType.fromString` / `CoreOutboundBuilder`, which are also case-sensitive; accepting "TLS"/"WS" would only produce a broken outbound. |
| 9 | Medium (product) | `res/values*/strings.xml` | `app_name` was `QuietStormNG` in the default locale and still `v2rayNG` in 8 locales, so the launcher label was the upstream name on those devices. | Set `app_name` to `Quiet Storm NG` in all 9 files (`ac93a7c3`). |

### 11.3 Verified non-issues (checked, no change made)

- **finalMask injection is not duplicated**: `AngConfigManager.parseConfig` guards with `config.finalMask.isNullOrBlank()`.
- **`applyBestIp` preserves transport**: it writes only `server`, plus `fingerPrint`/`finalMask`/`cipherSuites` **only when blank**. `CoreOutboundBuilder.populateTlsSettings` hardcodes `fingerprint = "unsafe"` for all TLS configs (line 558), so the stored value is not load-bearing; the previously unconditional overwrite was unnecessary mutation.
- **Filter never deletes configs**: `AutoConfigFilter` is referenced only by `ConnectionOrchestrator`; parsers and manual paths are untouched.
- **HTTPS port set is correct**: `{443, 2053, 2083, 2087, 2096, 8443}`; HTTP-only `{2052, 2082, 2086, 2095}` are excluded and asserted disjoint by test.
- **Cipher suites**: `cs` query param is parsed in `FmtBase` and preserved; the orchestrator only fills blanks.
- **Broadcast contract**: the message id lives in the `key` extra (`MessageHelper.messageIntent`), and `Utils.receiverFlags()` matches `MainRepository`.
- **No native core in the UI process**: `CoreServiceManager` eagerly builds `coreController` in its object initializer, so `isRunning()` is never called from the orchestrator.

### 11.4 Tests

| File | Tests | What it proves |
|---|---|---|
| `AutoConfigFilterTest.kt` | 27 | Accepted HTTPS ports, rejected HTTP-only ports (individually and as a set), disjointness, transport/security/port/server/auth rejection, order preservation, empty input. |
| `ConnectionOrchestratorTest.kt` | 13 | Real `pairGuidsWithProfiles` (order, dropped nulls, duplicate profiles, GUID↔profile identity), selection over the real filter, result contracts, no URL/credential material in failure text, cancel safety. |
| `CloudflareScannerPreservationTest.kt` | 18 | SNI/Host/Path/xhttpMode/xhttpExtra/network/security/port/auth/ALPN/insecure/pinnedCA/configType preserved; fingerPrint/finalMask/cipherSuites only filled when blank. |
| **Total** | **58** | |

CI execution: `build.yml` runs `./gradlew testFdroidDebugUnitTest --tests "com.v2ray.ang.ui.connect.*"`.

### 11.5 NOT RUN

- Instrumented / on-device tests: **NOT RUN** (no emulator or device).
- Real end-to-end connect (subscription → scan → tunnel up): **NOT RUN**.
- `applyBestIp` against real MMKV: covered only by a pure-logic simulation test.

### 11.6 Remaining risks

- `Utils.receiverFlags()` returns `RECEIVER_EXPORTED` on API 33+. A hostile app could spoof `MSG_STATE_RUNNING`/`MSG_STATE_START_SUCCESS`. This matches the existing project-wide pattern (`MainRepository`), so it was not changed here.
- The `MSG_REGISTER_CLIENT` probe adds ~1.5 s before the first start when no daemon is running.
- When the core is already up, the flow restarts it so the new config/best IP applies — a brief tunnel drop.
- No automatic retry/reconnect loop; a failure returns to the caller.
- `CloudflareScanner` temp profiles (`cfscanner-*`) are removed in `finally`, but a process kill mid-scan can leave orphans.
- Unit tests are JVM-only; they cannot catch Android lifecycle or process-boundary regressions.

### 11.7 CI evidence for this pass

Both commits of this pass were built exclusively on GitHub Actions. No Gradle/APK build was run on the SSH server or locally.

| Commit | Workflow run | Conclusion | `build` job | `Run unit tests` step | Artifacts |
|---|---|---|---|---|---|
| `34b3710c` fix(audit) | [Build APK run 40424887098](https://github.com/mo3iiibest77-hub/PattNG/actions/runs/40424887098) | **success** | success | success | arm64-v8a 60.7 MB, armeabi-v7a 61.4 MB, universal-apk 136.0 MB, x86-apk 126.0 MB |
| `ac93a7c3` fix(branding) | [Build APK run 40425712096](https://github.com/mo3iiibest77-hub/PattNG/actions/runs/40425712096) | **success** | success (5.7 min) | success | arm64-v8a 60.7 MB, armeabi-v7a 61.4 MB, universal-apk 136.0 MB, x86-apk 126.0 MB |

Evidence details:

- Step list of run `40425712096`: `Checkout code`, `Setup Android SDK`, `Install NDK`, `Copy libhevtun`, `Fetch AndroidLibXrayLite tag`, `Download libv2ray`, `Run unit tests`, `Build APK`, `Upload arm64-v8a/armeabi-v7a/x86/universal APK` — all `success`.
- The only check annotation on `34b3710c` is a `notice`: "The ubuntu-latest label will migrate to Ubuntu 26 beginning October 19, 2026" (runner-image deprecation). It is not a build warning or failure.
- `Run unit tests` runs `./gradlew testFdroidDebugUnitTest --tests "com.v2ray.ang.ui.connect.*"`. Gradle fails that task with "No tests found for given includes" when the filter matches nothing, so a green step proves the 58 tests in section 11.4 actually executed.
- `sign-and-release` is `skipped` by design for non-tag pushes; `Validate Fastlane metadata` is `success`.

### 11.8 Product identity (found during this pass)

| Severity | File(s) | Defect | Fix |
|---|---|---|---|
| Medium (product) | `res/values/strings.xml` + 8 locales | `app_name` was `QuietStormNG` (no spaces) in the default locale and still literally `v2rayNG` in `values-ar`, `values-bn`, `values-bqi-rIR`, `values-fa`, `values-ru`, `values-vi`, `values-zh-rCN`, `values-zh-rTW` — the launcher label would have been the upstream name on those devices. | Set `app_name` to `Quiet Storm NG` in all 9 files (commit `ac93a7c3`). |

Deliberately **not** changed: `applicationId` (`com.quietstorm.ng`), `namespace` (`com.v2ray.ang`), and `app_tile_name` (the quick-settings tile label, a functional string, not the launcher name).


---

## 12. Phase 3 — minimal UI, connection wiring and release readiness

> Branch: `feature/minimal-cloudflare-vpn`. `master` untouched at `a52f20c2`.
> No local or server Gradle build was run; every compile/test result below comes from GitHub Actions.

### 12.1 Objective

Turn the Phase 2 auto-connect logic (which until now had **zero callers**) into a shippable,
installable product: a two-screen UI (Connection, Apps) that reflects only real state, plus the
defects that wiring exposed.

### 12.2 Navigation audit (before -> after)

Before this pass the launcher activity was `ui.main.MainActivity` with 10 drawer destinations
(`MainDrawer.kt:37-48`): Subscriptions, PerAppProxy, Routing, UserAssets, Settings, Promotion,
Logcat, CheckUpdate, BackupRestore, About — plus the group/server pager and its bottom bar.

| Destination | Before | After |
|---|---|---|
| Connection (new) | did not exist | launcher, tab 1 |
| Apps / per-app proxy (new) | drawer -> `PerAppProxyActivity` | launcher, tab 2 |
| Subscription list, server list, routing, user assets, settings, logcat, backup, update, about | launcher drawer | still present in the app, no longer reachable from the launcher |
| `v2rayng://install-config`, `v2rayng://install-sub` | `UrlSchemeActivity` -> `MainActivity` | unchanged (import must keep working) |
| Quick settings tile long-press (`QS_TILE_PREFERENCES`) | `MainActivity` | unchanged |
| Scanner import (`ScScannerActivity`) | -> `MainActivity` | unchanged |
| VPN notification tap | `MainActivity` | `QuietStormActivity` |

No screen, parser, service or capability was deleted. `MainActivity` keeps `exported="true"`
because the system starts it for `QS_TILE_PREFERENCES`; it lost only its MAIN/LAUNCHER filter.

### 12.3 Implemented

| File | Purpose |
|---|---|
| `ui/qsn/QsContract.kt` | `QsStatus`, `QsStep`, `QsUiState`, the pure `withConnectState()` mapper and `QsUrlValidator` |
| `ui/qsn/QsViewModel.kt` | Owns the orchestrator, the subscription URL and the daemon event stream; single-flight connect, cancel, disconnect with a real stop confirmation |
| `ui/qsn/QuietStormActivity.kt` | Launcher activity: two tabs, VPN consent flow, restart-on-setting-change |
| `ui/qsn/QsConnectScreen.kt` | Status card, subscription card, progress/step list, error card, real connection details |
| `ui/qsn/QsAppsScreen.kt` | Per-app list wired to the existing `PerAppProxyViewModel` |
| `res/values/strings.xml`, `res/values-fa/strings.xml` | 39 new strings (en + fa) |
| `AndroidManifest.xml` | `QuietStormActivity` is the launcher; `MainActivity` keeps only the QS tile filter |
| `handler/NotificationManager.kt` | Notification opens the minimal UI |
| `.github/workflows/build.yml` | Unit tests now cover `ui.qsn.*`, print per-class counts and fail if 0 tests run |
| `.github/scripts/summarize-unit-tests.py` | Parses the Gradle XML results into a per-class summary |

### 12.4 Connection state contract

`Connected` is produced only by `MSG_STATE_START_SUCCESS` / `MSG_STATE_RUNNING` (both real daemon
signals) or by `AutoConnectResult.Success`, which the orchestrator emits only after the daemon
confirmed running. `QsContractTest.noStateClaimsConnectedWithoutARunningCore` asserts that no other
orchestrator state can report `Connected` while the core is down.

Restoration: on cold start the view model re-sends `MSG_REGISTER_CLIENT` after its collector is
attached, because `MainRepository` probes in its constructor and that reply can be dropped. The UI
never reads a cached "was connected" flag.

### 12.5 Defects found and fixed in this pass

| # | Severity | File | Defect | Fix |
|---|---|---|---|---|
| 1 | **Critical** | `ConnectionOrchestrator.kt` | `autoConnect()` ran `AngConfigManager.updateConfigViaSub()` — blocking OkHttp — on the caller thread. Called from `viewModelScope` (Main) it would ANR. | The flow now runs inside `withContext(Dispatchers.IO)`; callers cannot get it wrong. |
| 2 | High | `ConnectionOrchestrator.kt` | Cancelling the coroutine (activity destroyed mid-scan) left `CloudflareScanner` running in its own scope, writing temp profiles with nobody listening. | `cancel()` is now called in the `CancellationException` branch. |
| 3 | High | `ConnectionOrchestrator.kt` + UI | With no VPN consent `CoreVpnService.setupVpnService()` returns false and `stopSelf()`s **without** broadcasting `START_FAILURE`, so every such attempt burned the 30 s timeout and reported "core did not start". | Consent is requested before the flow; the orchestrator also fails fast with `VPN permission required`. |
| 4 | Medium | `CloudflareScanner.kt` | `encodeServerConfig()` publishes every profile into its subscription list, so a `cfscanner-*` temp profile orphaned by process death becomes a visible fake config. | `purgeStaleTempProfiles()` runs at the start of every scan. |
| 5 | Medium | `NotificationManager.kt` | The VPN notification opened the full PattNG UI. | Opens `QuietStormActivity`. |
| 6 | Medium | `ui/connect/*` | Phase 2 shipped an orchestrator with **zero callers** — the automatic flow was unreachable from any UI. | Wired into `QsViewModel`. |
| 7 | Low | `build.yml` | The test filter covered only `com.v2ray.ang.ui.connect.*`; a new package would silently never run. | Filter covers `ui.qsn.*` too, counts are printed, and the step fails when 0 tests execute. |

### 12.6 Tests

`QsContractTest` (25 tests) exercises the real `QsUiState.withConnectState()` and the real
`QsUrlValidator` — not re-implementations:

- URL: https/http accepted, whitespace trimmed, blank / non-http / no-scheme / no-host / embedded-space rejected, non-ASCII host accepted.
- Mapping: every step maps to its own UI step; scan progress produces a real fraction; `Connected` requires the daemon signal; a failure while the tunnel is up keeps `Connected`; a failure while it is down becomes `Error`; cancel reports `Disconnected` or `Connected` truthfully; missing subscription is an error.
- Property: for all 13 non-connected orchestrator states, no mapping reports `Connected` while the core is down.

CI runs `com.v2ray.ang.ui.connect.*` + `com.v2ray.ang.ui.qsn.*` = **83 tests**.

### 12.7 CI evidence for this pass

Every build below ran on GitHub Actions. No Gradle task was executed on the server or locally.

| Commit | Workflow run | Result |
|---|---|---|
| `4d419ffa` phase 3, first attempt | [Build APK 40429625735](https://github.com/mo3iiibest77-hub/PattNG/actions/runs/40429625735) | **failure** — main and test sources compiled; exactly one test failed: `QsContractTest > acceptsNonAsciiHost`. `java.net.URI` returns a null host for a non-ASCII authority instead of throwing, so the IDN fallback was never reached. |
| `1bbe4f16` validator fix | [Build APK 40430257755](https://github.com/mo3iiibest77-hub/PattNG/actions/runs/40430257755) | **success** — 5.0 min, 4 artifacts |
| `6813b5db` branding + CI annotation + report | [Build APK 40431293682](https://github.com/mo3iiibest77-hub/PattNG/actions/runs/40431293682) | **success** — 7.8 min, 4 artifacts |

Run `40430257755` step list: `Checkout code`, `Setup Android SDK`, `Install NDK`, `Copy libhevtun`,
`Fetch AndroidLibXrayLite tag`, `Download libv2ray`, `Decode Keystore`, `Run unit tests`,
`Build APK`, `Upload arm64-v8a/armeabi-v7a/x86/universal APK` — all `success`.
`Build libhevtun` / `Save libhevtun` were `skipped` because the NDK-keyed cache was restored.
`sign-and-release` is `skipped` by design for non-tag pushes.

Artifacts of run `40430257755` (not expired): `arm64-v8a` 60.8 MB, `armeabi-v7a` 61.5 MB,
`x86-apk` 126.2 MB, `universal-apk` 136.0 MB.

Run `40431293682` also carries the executed-test annotation, which is the authoritative count:

    unit tests executed: 83 (failures=0 errors=0 skipped=0)

83 = 58 (`AutoConfigFilterTest` 27, `CloudflareScannerPreservationTest` 18, `ConnectionOrchestratorTest` 13)
+ 25 (`QsContractTest`). The only other annotation is the runner-image deprecation notice.

The failed run is recorded on purpose: it proves the `Run unit tests` step really executes the
filtered suite and can fail, rather than passing vacuously. `summarize-unit-tests.py` fails the
step when zero tests execute and emits the executed count as a workflow annotation.

### 12.8 NOT RUN

- On-device / instrumented tests: **NOT RUN** (no device or emulator).
- Real end-to-end connect (subscription -> scan -> tunnel up): **NOT RUN**.
- Per-app enforcement on a device (`addAllowedApplication`): **NOT RUN**.
- Rotation / process-death behaviour: **NOT RUN** (state design is rotation-safe, but unproven on hardware).
- Traffic statistics in the UI: **not implemented** — the core exposes them only inside the daemon process; the screen states this instead of showing fake numbers.

### 12.9 Remaining risks

- The subscription fetch is blocking OkHttp with a 15 s timeout per attempt, so Cancel during the fetch is honoured at the next checkpoint, not instantly.
- The full PattNG UI is still reachable through `v2rayng://` deep links, the QS tile preferences and the scanner import flow. This is intentional (import must keep working) but means the minimal UI is not the only surface.
- `MainActivity` stays exported for `QS_TILE_PREFERENCES`; any app that knows the component name can start it.
- Changing the per-app selection restarts a running tunnel on the next tab switch or resume, not instantly.
- `Utils.receiverFlags()` still uses `RECEIVER_EXPORTED` on API 33+, so status broadcasts can be spoofed by a hostile app (project-wide pattern, unchanged).
- No automatic retry when the flow fails; the user must tap Retry.

### 12.10 Branding sweep (same pass)

| File | Change |
|---|---|
| `res/values-vi/strings.xml` | 9 user-visible strings still said `v2rayNG` (toasts, notification stop action, page title, Tasker action, tile first-use hint) -> `Quiet Storm NG` |
| `res/values-ru/strings.xml` | `app_widget_name` / `app_tile_name` said `v2rayNG`; they are action labels, so they now use the functional label the other locales use (`Переключить`) instead of the long product name |
| `fastlane/metadata/android/en-US/title.txt` | store title `v2rayNG` -> `Quiet Storm NG` |

Checked and unchanged: `applicationId` (`com.quietstorm.ng`), `namespace` (`com.v2ray.ang`),
`app_name` in all 9 locales (already `Quiet Storm NG`), and
`fastlane/metadata/android/en-US/full_description.txt`, which still carries the upstream project
attribution and the upstream Telegram link. Rewriting store copy was not part of this task and is
listed as a remaining item.

### 12.11 Device test guide (next step, needs real hardware)

Install `arm64-v8a` (or `universal-apk` if unsure) from the run artifacts.

| # | Scenario | Expected | On failure capture |
|---|---|---|---|
| 1 | Launch the app | "Quiet Storm NG", two tabs, status Disconnected, empty URL field | screenshot |
| 2 | Paste a subscription URL, Save | "N configs" and a last-update time appear | the count shown, the HTTP status if it fails (never the URL) |
| 3 | Update | Config count refreshes; on a bad URL: "Update failed: ..." with no URL in the text | the message text |
| 4 | Connect (first run) | system VPN consent dialog, then steps: subscription -> configs -> scan -> start -> Connected | which step was last highlighted |
| 5 | Cancel during the scan | status returns to Disconnected, no lingering CPU/network use | whether the scan kept running |
| 6 | Disconnect | status Disconnected within a few seconds; the notification disappears | whether it stuck on "Disconnecting..." |
| 7 | Close and reopen while connected | status Connected without pressing anything | what the app showed |
| 8 | Rotate the screen while connecting | the step list keeps its place; no restart of the flow | which step reset |
| 9 | Apps tab: enable per-app proxy, select 2 apps, back to Connection | the tunnel restarts once, only those apps go through it | whether all apps or none went through |
| 10 | Airplane mode / dead subscription | a readable error, Retry available, status never claims Connected | the message text |

Do not paste subscription URLs, tokens or config links into bug reports; the UI and the logs are
designed not to need them.

### 12.12 Final state of this pass

| Item | Value |
|---|---|
| Branch | `feature/minimal-cloudflare-vpn` |
| Last verified code SHA | `6813b5db` (run 40431293682, success) |
| `master` | untouched at `a52f20c2` |
| Commits ahead of master | 20 |
| Working tree | clean before this doc commit |
| Verified by CI | compile (main + test), 83 unit tests, release APK signing, 4 APK artifacts |
| Not verified | anything that needs a device: real tunnel, per-app enforcement, rotation, process death |


## 13. Feature release — persistent edge, benchmarking, configuration and range management

Baseline for this pass: `a52f20c2` on `master` (untouched), `9c7597de` on the feature branch with 83 unit tests green.

### 13.1 Requested capabilities and their state

| # | Capability | State | Where |
|---|---|---|---|
| 1 | Persistent IP selection across reconnects | implemented | `ui/qsn/domain/IpSelection.kt`, `ui/qsn/data/QsPrefs.kt`, `ui/connect/ConnectionOrchestrator.kt` |
| 2 | Advanced benchmarking | implemented | `ui/qsn/domain/CandidateMetrics.kt`, `senpai/CloudflareScanner.kt` |
| 3 | Manual import with a visible + control and Persian errors | implemented | `ui/qsn/QsConfigScreen.kt`, `QsViewModel.importManualConfig` |
| 4 | Configuration management: view, edit, duplicate-to-manual, delete, filter | implemented | `ui/qsn/domain/ConfigFilters.kt`, `QsConfigScreen.kt` |
| 5 | Multi-subscription, collapsible, per-subscription update, enable/disable | implemented | `ui/qsn/domain/Subscriptions.kt`, `QsConfigScreen.kt` |
| 6 | IP ranges with CIDR validation, hard limits and dedupe | implemented | `ui/qsn/domain/ScanRange.kt`, `ui/qsn/QsRangesScreen.kt` |
| 7 | Four-tab bottom navigation | implemented | `ui/qsn/QuietStormActivity.kt` |
| 8 | Voxora Web light theme | implemented | `ui/compose/Theme.kt` |
| 9 | Low-cost connection background | implemented | `ui/qsn/QsBackground.kt` |
| 10 | Per-app proxy preserved | unchanged | `ui/qsn/QsAppsScreen.kt` |

### 13.2 Persistent IP selection

A reconnect used to rescan every time. Now the winning edge is stored per configuration and reused.

Cache key (SHA-256 over a joined field list):

`v2|guid|configType|port|network|security|sni|host|path|xhttpMode|rangeFingerprint`

The server field is deliberately **excluded**: applying the selected IP rewrites `profile.server`, so
including it would invalidate the entry the first time it is used. No credential, UUID, password or
subscription URL is part of the key.

| Age of the entry | Action |
|---|---|
| < 30 min | reuse with no network round trip |
| 30 min – 24 h | one TCP connect plus one TLS handshake with SNI, 2 s timeout, platform trust store |
| > 24 h, failed probe, foreign fingerprint, non-IPv4 value, timestamp in the future | clear and rescan |

Editing the transport fields, changing the enabled range set, or bumping
`IpFingerprint.SCAN_SETTINGS_VERSION` invalidates the entry. The health probe transfers no payload
and never weakens TLS (no trust-all manager, no hostname verifier override).

### 13.3 Benchmarking

The old scanner chose `maxByOrNull { it.uploadKBps }`: a fast upload could hide a dead link and
download speed was ignored entirely. Replaced by `CandidateRanker` with explicit weights:

| Dimension | Weight | Normalisation |
|---|---|---|
| Latency (median of 3 probes) | 0.30 | 25 ms → 100, 700 ms → 0 |
| Jitter (median absolute deviation) | 0.10 | 0 ms → 100, ≥ 250 ms → 0 |
| Download | 0.25 | relative to the best candidate in the batch |
| Upload | 0.15 | relative to the best candidate in the batch |
| Stability | 0.20 | successful probes ÷ all probes |

Final score = `base × (1 − 0.35 × failureRatio)`, clamped to 0..100. Ties break on score, then
median latency, then IP, so the same input always produces the same winner.

What each number actually is, without overstating it:

- **latency** — median of three `measureOutboundDelay` calls through the candidate's own config
  against `https://cp.cloudflare.com/cdn-cgi/trace`.
- **jitter** — median absolute deviation of those three samples, used instead of max−min so a single
  spike does not dominate.
- **download / upload** — 1 MiB each way through the candidate config via a temporary SOCKS inbound
  (`RealTrafficSpeedTest`). This is a burst measurement, not a sustained throughput test.
- **stability** — share of probes that answered at all; failures and timeouts are counted separately.
- **confidence** — how many of the five dimensions were measurable, 20 points each. A candidate with
  no traffic measurement can never beat a measured one, because the missing dimension contributes
  zero rather than being silently skipped.

A failed traffic test no longer discards an edge that clearly answered; it leaves throughput unknown.

### 13.4 Manual import

Paste one or more share links, `AngConfigManager.importBatchConfig` stores them in the manual group.
Duplicates are detected by hashing the connection identity of every stored profile before and after
the import (`ManualConfigRules.keyOf`) and the freshly imported copies of an already stored
configuration are removed again. The identity ignores remarks and timestamps, and includes the
credential so two different keys on the same server are not treated as duplicates.

All new user-facing messages go through resources, so `values-fa` produces Persian text:

| Situation | Persian string |
|---|---|
| empty input | یک لینک بچسبانید |
| nothing parseable | هیچ کانفیگ پشتیبانیشده‌ای در متن پیدا نشد |
| duplicate only | این کانفیگ از قبل ذخیره شده است |
| partial | %1$d درونریزی شد، %2$d تکراری نادیده گرفته شد |

### 13.5 Configuration management

Rows come from `MmkvManager.decodeAllServerList()` and carry no credential. Filters combine with
AND; selecting `All` clears the others; an empty set behaves as `All`.

Edit opens the real protocol editor through `ProfileEditorResult.editIntent` (the mapping now lives
in one place and `MainActivity` uses it too), so editing a configuration is not a reduced
re-implementation. Subscription configurations can also be copied into the manual group, because a
subscription refresh replaces them and an in-place edit would be lost. Delete asks for confirmation.

### 13.6 Multi-subscription

`SubscriptionRules.chooseActive` is used by **both** the panel and the connect flow, so the row the
user sees highlighted is exactly the one that will be refreshed.

- an explicit choice wins while it is enabled;
- a disabled explicit choice falls back to the first enabled subscription;
- when every subscription is disabled the flow fails explicitly instead of silently using one;
- a subscription written by the previous build has the same stored shape, so it appears as one
  enabled row with **no migration write**.

### 13.7 IP ranges

| Limit | Value |
|---|---|
| Smallest accepted prefix | /16 |
| Addresses sampled per range | 4 |
| Hard ceiling on candidates | 64 |
| Overlap handling | a range fully contained in another is dropped |

Input is normalised to the network address (`104.16.5.9/24` → `104.16.5.0/24`), so the same range
cannot be added twice under two spellings. `Scan now` runs a standalone scan with progress and
stores the winner; it does not start or stop the tunnel. Changing any range clears the cached edge.

### 13.8 Navigation

Four tabs: Connection, Configurations, IP Ranges, Apps. Switching tabs only changes the composed
screen; it never calls the service, never restarts the tunnel and never drops the in-flight
connection state.

### 13.9 Visual design

The palette was read from the real Voxora Web source on this machine
(`/opt/voxora-web/DESIGN.md` and the light block of `/opt/voxora-web/public/styles.css`), not
invented:

| Token | Value | Source |
|---|---|---|
| background | `#FBF8F2` | `--vx-bg` |
| surface | `#FFFFFF` | `--vx-surface` |
| surface 1 / 2 | `#F6F1E7` / `#EFE8DA` | `--vx-surface-1` / `-2` |
| content | `#1C1812` | `--vx-content` |
| muted | `#5E564A` | `--vx-muted` |
| line | `#E3DBCB` | `--vx-line` |
| accent ink | `#8A5A00` | `--vx-accent` |
| accent fill | `#D9A441` | `--vx-accent-fill` |

Two deliberate deviations, as requested: the success green is `#23A55A`, brighter and cleaner than
the reference `#2B7A4B`, with `#17703F` as its ink for text on paper; the error red stays
`#CF4848`. Cards use 18 dp corners, controls 14 dp, spacing on a 4 dp base.

The background is a static vertical wash plus two radial glows. It costs one linear and two radial
gradients, no bitmaps and no blur; the drift advances five times per second rather than per frame;
it stops when the activity is not resumed and stays frozen when the system animator duration scale is
zero.

### 13.10 Security and privacy

- The IP cache stores a hash and public measurements only.
- Subscription URLs are never logged; `SubscriptionRules.redact` keeps scheme and host and drops
  path, query and user-info. The panel displays the redacted form.
- `ManualConfigRules.contentKey` hashes before the value leaves the function.
- The health probe uses the platform trust store with SNI; no certificate or hostname verification is
  disabled anywhere in this change.
- No new permission and no new exported component: the four tabs live inside the existing
  `QuietStormActivity`.

### 13.11 Tests

116 new JVM test methods in six classes, all driving the production code:

| Class | Methods | Covers |
|---|---|---|
| `CandidateRankerTest` | 20 | median/MAD, weights, download over upload, failure penalty, tie-break, confidence |
| `IpSelectionTest` | 18 | freshness, TTL, foreign fingerprint, future clock, IPv4 validation, key stability |
| `ScanRangeTest` | 28 | CIDR parsing, /16 floor, /32, normalisation, dedupe, 64 ceiling, determinism |
| `ConfigFiltersTest` | 15 | AND combination, every filter, All exclusivity, matches/apply agreement |
| `ManualConfigTest` | 16 | eligibility rules, case insensitivity, duplicate key semantics |
| `SubscriptionsTest` | 19 | redaction, name limits, legacy row, ordering, active selection |

83 + 116 = **199**, which matches the count the CI run reported.

### 13.12 CI evidence

| Run | SHA | Result | Notes |
|---|---|---|---|
| [40437459423](https://github.com/mo3iiibest77-hub/PattNG/actions/runs/40437459423) | `13f9089f` | **failure** | `:app:compileFdroidDebugKotlin` — two real errors, recorded rather than hidden |
| [40437968381](https://github.com/mo3iiibest77-hub/PattNG/actions/runs/40437968381) | `a2f094ec` | **success** | 7 m 32 s, 02:21:40Z → 02:29:12Z |

The first run failed for two reasons, both fixed in `a2f094ec`:

1. `Theme.kt` referenced `QSRed` after the private constant was replaced by the public `QsRed`.
   Fixing the name alone would have been wrong: the public token was declared *after* the dark scheme
   that uses it, and Kotlin initialises top-level properties in declaration order, so the dark error
   colour would have been transparent at runtime. The accent tokens were moved above the dark scheme.
2. `IpHealthChecker` called `createSocket(Socket, String, int, boolean)` on
   `SSLSocketFactory.getDefault()`, which is declared to return `SocketFactory` and has no such
   overload. It now casts to `SSLSocketFactory` first.

Successful run details:

- annotation: `unit tests executed: 199 (failures=0 errors=0 skipped=0)`
- artifacts: `arm64-v8a` 61.0 MB, `armeabi-v7a` 61.7 MB, `x86-apk` 126.6 MB,
  `universal-apk` 136.2 MB, none expired
- `master` untouched at `a52f20c2`, working tree clean, 23 commits ahead

### 13.13 NOT verified

Everything below needs real hardware and is not claimed:

- a real tunnel over a real network, and whether the reused IP still works after a device reboot;
- whether the ranker's winner is actually the fastest in practice on a given carrier;
- per-app enforcement, rotation, process death, Doze;
- the visual result on a device (the palette is verified as code, not as pixels);
- battery impact of the background, measured rather than reasoned about.

### 13.14 Remaining risks

- Three latency samples are a small sample; the median is robust to one spike but not to a burst of
  congestion. `LATENCY_SAMPLES` is a single constant if this proves too noisy.
- A single 1 MiB transfer measures burst throughput, not sustained throughput.
- `decodeAllServerList()` is re-read on every refresh; with a very large subscription the list
  rebuild is O(n) work on the main thread. It is fast for hundreds of rows, not proven for thousands.
- The standalone `Scan now` reuses the connect flow's scanner, so it shares its 180 s safety timeout
  (`SCAN_TIMEOUT_MS`).
- Each candidate costs a 1 MiB upload plus a 1 MiB download. With no ranges configured the scanner
  still uses its own 30-candidate list, as before; a full 64-candidate range scan moves up to 64 MiB
  in each direction. The ceiling bounds the work, it does not make it free.


## 14. Release pipeline fix — the installed APK was the wrong flavour

### 14.1 Symptom

Build #78 was installed and the new capabilities were not visible in the app.

### 14.2 Root cause

The build step ran `./gradlew assembleRelease`, which assembles **every** product flavour, and the
upload globs had a wildcard in the flavour segment:

`path: V2rayNG/app/build/outputs/apk/*/release/*arm64-v8a*.apk`

That matches both `fdroid/release/…-fdroid_arm64-v8a.apk` and
`playstore/release/…_arm64-v8a.apk`, so each "one APK" artifact actually contained two APKs.
`actions/upload-artifact` keeps the least common ancestor directory, so the zip contained a
`fdroid/` folder and a `playstore/` folder, with the F-Droid file sorting first.

### 14.3 Evidence

The artifact of build #78 ([run 40439097049](https://github.com/mo3iiibest77-hub/PattNG/actions/runs/40439097049),
commit `a816a782`) was downloaded and listed:

`
  Length      Date    Time    Name
---------  ---------- -----   ----
 32848673  2026-10-27 02:40   fdroid/release/QuietStormNG_2.3.4-fdroid_arm64-v8a.apk
 32848581  2026-10-27 02:40   playstore/release/QuietStormNG_2.3.4_arm64-v8a.apk
--------                     -------
 65697254                     2 files
`

Both files were parsed (binary AndroidManifest) and inspected:

| Property | `…-fdroid_arm64-v8a.apk` | `…_arm64-v8a.apk` |
|---|---|---|
| package | `com.quietstorm.ng.fdroid` | `com.quietstorm.ng` |
| versionCode | 5,074,401 | 4,000,744 |
| versionName | 2.3.4 | 2.3.4 |
| `CandidateRanker` in dex | present | present |
| `QsConfigScreen` in dex | present | present |
| "Cloudflare-ready" in resources.arsc | present | present |

So the **code was correct** in both files. The build never produced a stale APK; the problem was
which of the two files the user installed.

### 14.4 Why the app showed nothing new

The F-Droid APK declares `com.quietstorm.ng.fdroid`. Android cannot install it *over* an existing
`com.quietstorm.ng` — different applicationId means a different app. Installing it therefore left
the previously installed `com.quietstorm.ng` untouched, and the home screen ended up with two icons
carrying the same label, "Quiet Storm NG". Opening the old one (the one already there) shows the old
UI, which is exactly the reported symptom.

The pipeline made that the likely outcome: the F-Droid file was listed first, it was the same size,
it had the same label, and it carried a *higher* versionCode (5,074,401 vs 4,000,744), so it looked
like the newer build.

### 14.5 Changes

`.github/workflows/build.yml`:

| Before | After |
|---|---|
| `testFdroidDebugUnitTest` | `testPlaystoreDebugUnitTest` — the F-Droid variant is never compiled |
| `licenseFdroidReleaseReport` | `licensePlaystoreReleaseReport` |
| `assembleRelease` (all flavours) | `assemblePlaystoreRelease` |
| `apk/*/release/*arm64-v8a*.apk` | `apk/playstore/release/QuietStormNG_*_arm64-v8a.apk` |
| `apk/*/release/*armeabi-v7a*.apk` | `apk/playstore/release/QuietStormNG_*_armeabi-v7a.apk` |
| `apk/*/release/*universal*.apk` | `apk/playstore/release/QuietStormNG_*_universal.apk` |
| `apk/*/release/*x86*.apk` (also matched x86_64) | removed |
| no check | new "Verify APK identity, signature and features" step |
| upload could silently produce nothing | `if-no-files-found: error` |

The verification step fails the build when any of the following is true:

- any `*-fdroid*` file exists anywhere under `app/build/outputs/apk`;
- `git rev-parse HEAD` differs from `GITHUB_SHA`;
- a glob does not resolve to exactly one existing APK;
- `aapt2 dump badging` does not report `name='com.quietstorm.ng'`;
- `apksigner verify` rejects an APK, or the three signer reports differ;
- `CandidateRanker`, `QsConfigScreen` or the "Cloudflare-ready" resource is missing from the dex
  or the resource table of any APK.

### 14.6 The three APKs

Build #80 ([run 40443901521](https://github.com/mo3iiibest77-hub/PattNG/actions/runs/40443901521),
commit `130b1472`):

| Artifact | File | Size | SHA-256 |
|---|---|---|---|
| `arm64-v8a` | QuietStormNG_2.3.4_arm64-v8a.apk | 32,848,581 B | `99be9b70c2d40196ad24f62a03b820c9516fda4bfc1c26d104bee8be7b853a27` |
| `armeabi-v7a` | QuietStormNG_2.3.4_armeabi-v7a.apk | 33,241,015 B | `438fa22257206284bc9b7e3bcc09cb6c33a91c733d5f390b2beaed57a428f31f` |
| `universal-apk` | QuietStormNG_2.3.4_universal.apk | 72,645,070 B | `7f20c63e6aa4afd62ff8bacdfb88e71f5766f17a13b88d3f943bfd63abba942a` |

All three, verified locally against the downloaded artifacts:

- exactly one file per artifact zip, at the zip root, with no `fdroid/` directory;
- package `com.quietstorm.ng`, versionCode 4,000,744, versionName 2.3.4;
- APK Signature Scheme v2 block present, and the CI step confirmed the three signer reports are
  identical (one key);
- `CandidateRanker` and `QsConfigScreen` present in the dex, "Cloudflare-ready" present in
  `resources.arsc`;
- 7 native libraries in each ABI split, 28 in the universal APK.

Artifact sizes confirm the fix without opening them: the arm64 artifact went from 61.0 MB (two APKs)
to 30.5 MB (one APK), the universal artifact from 136.2 MB to 68.1 MB.

### 14.7 No F-Droid output

`GET /repos/…/actions/runs/40443901521/artifacts` returns exactly three entries:
`arm64-v8a`, `armeabi-v7a`, `universal-apk`. There is no F-Droid artifact, and the build tree
contains no `*-fdroid*` file — the step that would have produced one is no longer invoked, and the
verification step would fail the run if one appeared.

### 14.8 CI evidence

| Run | Commit | Result | Note |
|---|---|---|---|
| [#79](https://github.com/mo3iiibest77-hub/PattNG/actions/runs/40442891992) | `0d7187de` | failure | the new verification step rejected all three APKs |
| [#80](https://github.com/mo3iiibest77-hub/PattNG/actions/runs/40443901521) | `130b1472` | success | 6 m 06 s, 03:23:33Z → 03:29:39Z |

Run #79 failed because of the verification step itself, not the build. No APK was rejected by
`apksigner` (the rejection branch never fired and the v2 signing block is present in every file),
but the certificate digest was not extracted from the captured output, so the step treated the APKs
as unsigned. The step now captures stdout and stderr together, matches the digest line without
anchoring it, and falls back to comparing the whole signer report when no digest line is printed.
Run #80 passed it.

Run #80 also reported `unit tests executed: 199 (failures=0 errors=0 skipped=0)`.

### 14.9 How to confirm the new capabilities are in a downloaded APK

Any of these can be run on the artifact itself:

`
unzip -l arm64-v8a.zip                       # exactly one .apk, no fdroid/ folder
unzip -p <apk> 'classes*.dex' | grep -ac CandidateRanker
unzip -p <apk> 'classes*.dex' | grep -ac QsConfigScreen
unzip -p <apk> resources.arsc | grep -ac 'Cloudflare-ready'
$ANDROID_HOME/build-tools/<ver>/aapt2 dump badging <apk> | head -1
$ANDROID_HOME/build-tools/<ver>/apksigner verify --print-certs <apk>
`

On the device: the launcher entry opens the four-tab screen (Connection, Configurations, IP Ranges,
Apps). If an older icon with the same name is still present, it belongs to the previous
`com.quietstorm.ng` install or to a previously installed `com.quietstorm.ng.fdroid`; uninstalling
both and installing only the new APK removes the ambiguity.

### 14.10 NOT verified

- the three APKs were not installed on a physical device in this pass;
- the signer digest equality was proven by `apksigner` output inside CI and by the v2 block being
  present in all three files, not by comparing certificate bytes independently;
- x86 and x86_64 splits are still compiled by AGP (the ABI split configuration was not changed) but
  no artifact publishes them.

## 15. Guided connection — manual config choice, manual IP choice, per-subscription edge application

**Branch:** `feature/minimal-cloudflare-vpn` · **Status:** implemented, CI verification in §15.9.

### 15.1 What existed before this pass

The previous release could already scan Cloudflare ranges, rank candidates and reuse a saved edge.
The problems were behavioural rather than cosmetic:

| # | Observed behaviour | Why it was wrong |
|---|---|---|
| 1 | The subscription form on Connection was always open | A saved subscription kept the main screen busy, and there was no way to fold the form away |
| 2 | `connect()` went from "Checking Configs" straight to the scan | The user never saw which config was used, and could not pick another |
| 3 | The scan reported only `done/total` | Results arrived in one batch at the end; per-IP measurements were never shown |
| 4 | The scanner's winner was applied without asking | The user had no say in the final IP |
| 5 | `CloudflareScanner.applyBestIp` wrote `profile.server = ip` | The origin address was destroyed, so Connection Details showed the Cloudflare IP as "Server" |
| 6 | Only the config chosen for the scan received the edge | A subscription with three eligible configs ended up with one rewritten and two stale |
| 7 | `QsConnectScreen` printed `qs_speed_unavailable` | False: the core does expose counters through `CoreServiceManager.queryAllOutboundTrafficStats()` |
| 8 | Configurations was a flat list | No per-subscription grouping, no separate place for manual imports |
| 9 | No language control on the new UI | The Persian resources existed but were unreachable from Quiet Storm NG |
| 10 | `PREF_SPEED_ENABLED` defaulted to `false` | A fresh install had no Receive/Send in the notification |

### 15.2 Root causes, as proven from the code

- **Origin loss (5):** `CloudflareScanner.applyBestIp` assigned `profile.server = bestIp`. Since
  `CoreOutboundBuilder.getServerAddress()` reads `profile.server`, the same field was doing two
  jobs: "the real server" (for display and for re-resolving) and "the address to dial".
- **Single-config application (6):** `ConnectionOrchestrator.connectSelected()` called
  `applyBestIp(selectedGuid, bestIp)` for one GUID only; there was no notion of a subscription
  scope.
- **"No traffic counters" (7):** the message was a hard-coded string added when the UI had no
  subscription to the daemon's statistics. The counters exist and are consumed by
  `NotificationManager.updateSpeedNotificationOnce()` in the service process.
- **Counter ownership:** `queryAllOutboundTrafficStats()` returns the bytes accumulated since the
  previous call. Two readers (notification and UI) would have halved each other's deltas, so a
  shared, throttled measurement was required before the UI could show honest numbers.

### 15.3 Files changed and why

| File | Change | Reason |
|---|---|---|
| `dto/entities/ProfileItem.kt` | new `qsEdgeIp` field; cleared in `duplicateIdentity()` | Keep the origin in `server` and the chosen edge separately; the edge is app state, not config identity |
| `core/CoreOutboundBuilder.kt` | `getServerAddress()` returns `qsEdgeIp` when set | One choke point: the core dials the edge while SNI/Host/Path/TLS keep the origin values |
| `senpai/CloudflareScanner.kt` | `applyBestIp` writes `qsEdgeIp`; temp probe profiles clear it | Preserves the origin; stops a stale edge from overriding the candidate under test |
| `ui/connect/AutoConfigFilter.kt` | added `Ineligibility` + `ineligibility()`; `isEligible` delegates | One rule engine, with a reason the UI can show |
| `ui/connect/ConnectionOrchestrator.kt` | config-choice gate, live `ScanCandidateFound`, IP-choice gate, `ApplyingEdge`, `applyEdgeToScope()` | The flow now waits for the user and writes the edge to the whole scope |
| `ui/qsn/domain/EdgeApplication.kt` (new) | pure scope selection + application | Testable without Android; guarantees "same subscription, eligible only" |
| `ui/qsn/domain/ScanCandidate.kt` (new) | UI-facing measurement + `MetricFormat` | "N/A" instead of a fabricated number; KB/s and MB/s |
| `ui/qsn/domain/ConfigChoice.kt` (new) | one row of the config picker | No credential leaves the profile |
| `ui/qsn/domain/ConfigGrouping.kt` (new) | per-subscription grouping + manual bucket | Sections cannot mix configs from different subscriptions |
| `ui/qsn/domain/FlowRules.kt` (new) | panel rule, notification default, language toggle, range counts | Small rules, unit tested against the code the app runs |
| `ui/qsn/domain/ConfigFilters.kt` | `ConfigRow.subscriptionId`, eligibility injected | The badge now uses the same rule as the flow |
| `ui/qsn/QsContract.kt` | new steps, new state fields, `QsFlowText`, `rankCandidates` | The UI renders real states only |
| `ui/qsn/QsViewModel.kt` | panel toggle, choices, traffic polling, language, notification default, failure localisation | Wires the above to the daemon and to storage |
| `ui/qsn/QsConnectScreen.kt` | collapsible panel, 10-step list, origin/edge rows, Receive/Send, EN/FA | §1, §2, §8, §9, §11 |
| `ui/qsn/QsScanScreen.kt` (new) | config picker, live results, IP confirmation | §4, §5, §6 |
| `ui/qsn/QsConfigScreen.kt` | grouped sections, "Added by You", curated chips, translucent cards | §10, §13 |
| `ui/qsn/QsRangesScreen.kt` | range count, locked state while scanning | §14 |
| `ui/qsn/QsBackground.kt` | node field + `shouldAnimateBackground` | §13 |
| `ui/qsn/QuietStormActivity.kt` | scan-step routing, foreground signal, new callbacks | §2, §9 |
| `ui/qsn/data/QsPrefs.kt` | persisted panel state | §1.8 |
| `handler/MmkvManager.kt` | `containsSettings()` | Distinguish "never set" from "set to false" |
| `AppConfig.kt` | `MSG_QUERY_TRAFFIC`, `MSG_TRAFFIC_RESULT` | UI -> service measurement request |
| `dto/TrafficSnapshot.kt` (new) | serializable measurement | Cross-process delivery of real numbers |
| `core/CoreServiceManager.kt` | throttled `currentTrafficSnapshot()` + handler | Single owner of the native counters |
| `handler/NotificationManager.kt` | reads the shared snapshot | Notification and UI no longer steal each other's deltas |
| `ui/main/MainServiceEvent.kt`, `ui/main/MainRepository.kt` | `TrafficUpdate` event | Existing broadcast path reused |
| `res/values/strings.xml`, `res/values-fa/strings.xml` | 57 new `qs_*` keys in both files | No hard-coded UI text |

### 15.4 Behaviour now

1. **Collapsible subscription panel.** Header row with a rotating arrow; the form closes only after
   `saveSubscription` really wrote the subscription, and stays open with its error when the URL is
   rejected. The collapsed/expanded state is stored in `QsPrefs`, so a tab switch or a cold start
   shows the same thing.
2. **Guided flow.** `Preparing -> Updating Subscription -> Checking Configs -> choose config ->
   Scanning -> live results -> choose IP -> Selecting Best IP -> apply -> real VPN start ->
   Connected`. Each step is emitted by the orchestrator, not by the UI. `Connected` still requires
   `MSG_STATE_START_SUCCESS`/`MSG_STATE_RUNNING`.
3. **Eligibility.** `AutoConfigFilter` remains the only rule (WS or XHTTP, `tls`, real
   `serverPort` in {443, 2053, 2083, 2087, 2096, 8443}, a server, a credential). The port is read
   from `serverPort`, never from remarks. Other protocols keep their parsers and remain editable.
4. **Config choice.** Only eligible configs of the active subscription are offered; a config from
   another subscription cannot appear because the list is built from that subscription's GUIDs.
5. **Live scan.** `ScanCandidateFound` is emitted per candidate; ping, jitter, download, upload,
   stability, errors and timeouts are rendered, `N/A` when not measured. Jitter is suppressed below
   two samples.
6. **Manual IP choice.** The ranker's winner is marked "Suggested" but nothing is written until the
   user confirms a candidate that answered. Cancel completes the pending question, so the flow
   really stops.
7. **Per-subscription application.** `applyEdgeToScope()` writes `qsEdgeIp` to every eligible
   config of the selected subscription (or only the pinned manual config). Origin, name, protocol,
   TLS, SNI, Host, Path, `cs` and `finalMask` are untouched; `CoreOutboundBuilder` substitutes the
   edge only for the destination address.
8. **Connection Details.** "Server" is `profile.server` (origin, or "Unknown"); the Cloudflare
   address has its own "Edge IP" row.
9. **Traffic.** The service answers `MSG_QUERY_TRAFFIC` with a `TrafficSnapshot` measured from the
   native counters, throttled to ~1 Hz and shared with the notification. The UI polls once a second
   only while connected **and** foregrounded, and clears the rates on disconnect. Zero is rendered
   as `0 KB/s`; unavailable is `N/A`; the first sample shows "Measuring".
10. **Configurations.** One collapsible section per subscription (only Cloudflare-ready rows, with
    "N of M ready"), then "Added by You" for manual imports (all of them, eligible or not). The
    confusing chips ("Other transport", "Not eligible", "Manual", "Subscription") are no longer
    top-level categories.
11. **Language.** An "EN / FA" chip on Connection, Configurations and IP Ranges calls the existing
    `AppLocaleManager.setApplicationLanguage()`, which persists through MMKV + AppCompat and
    recreates the activity. Layouts follow the RTL locale; no subscription or setting is touched.
12. **Notification default.** `PREF_SPEED_ENABLED` is written to `true` only when the key does not
    exist, so a fresh install gets Receive/Send and an existing choice is preserved.
13. **Background.** The wash gained 16 drifting nodes with thin links, drawn at 5 fps, stopped when
    the screen is not resumed and frozen when animations are disabled. Cards on Configurations are
    90–92 % opaque so the field stays visible without hurting contrast.
14. **Ranges.** "N ranges, M enabled" is shown; adding, toggling and deleting are disabled while a
    scan runs, with an explicit notice.

### 15.5 Tests added (all JVM, no Robolectric)

| Test class | Covers requirement |
|---|---|
| `ui/qsn/domain/EdgeApplicationTest` | 9, 10, 11, 13 |
| `ui/qsn/domain/ConfigGroupingTest` | 7, 8 |
| `ui/connect/AutoConfigFilterEligibilityTest` | 4, 5, 6 |
| `ui/qsn/ConnectFlowContractTest` | 1, 2, 3 |
| `ui/qsn/domain/TrafficPresentationTest` | 14, 15, 16 |
| `ui/qsn/domain/LanguageToggleTest` | 17 |
| `ui/qsn/LocalizationCoverageTest` | 18 (EN/FA key parity) |
| `ui/qsn/domain/RangesSummaryTest` | 19 |
| `ui/qsn/BackgroundMotionTest` | 20 |
| `ui/qsn/domain/ProfilePersistenceTest` | 12 |
| `ui/connect/CloudflareScannerPreservationTest` (rewritten) | 11, 13 — now calls the real applier |

Two requirements cannot be asserted in a JVM test and are verified by inspection instead:

- **RTL layout (18):** the app relies on the Android locale + `values-fa`; the key-parity test proves
  the strings exist, but the visual direction needs a device. Manual check: switch to FA, confirm
  the tab bar order, arrow direction and text alignment.
- **Panel collapse (1, 2):** the rule is unit tested (`SubscriptionPanelRules`), but the click path
  needs a device. Manual check: save a valid URL (panel closes), save an invalid one (panel stays
  open with the error).

### 15.6 Not changed on purpose

- `CfScanActivity` / `CfScanService` (the standalone ISP scan) still exist and still work; they now
  write the edge through `applyBestIp`, so they also keep the origin address.
- No protocol parser, no `fmt/` file and no Per-App Proxy code was touched.
- The IP Ranges default CIDR list was left exactly as it was: no new range was invented.

### 15.7 Known limitations

- A subscription refresh rebuilds the profiles, which drops `qsEdgeIp`; the next connect re-applies
  the cached edge (or rescans when the cache is stale). This is deliberate: a stale edge must not
  survive a config change.
- Editing a profile in the full editor rebuilds it from the form state, so the edge is cleared and
  the origin is dialed again until the next scan.
- The traffic window is established by the first query after a start, so the very first sample
  shows "Measuring" and the rate appears one second later.
- `Connection Details` shows the origin address as stored. If a config was imported with a domain
  that only resolves through the CDN, that domain is what is shown; it is not resolved back to an
  origin IP, because that information does not exist locally.

### 15.8 Verification commands used

```sh
git status --porcelain           # clean before the change
git diff --stat                  # reviewed the whole diff
git diff --cached --name-only    # explicit staging only
```

### 15.9 CI result

Recorded in the next commit after the workflow finished; see the table below.

| Item | Result |
|---|---|
| Unit tests | NOT RUN at the time of writing |
| Playstore release build | NOT RUN at the time of writing |
| APK artifacts | NOT RUN at the time of writing |
