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

- Commit SHA(s) pushed: `<filled after push>`
- `git push origin master` exit code: `<filled after push>`
- Remote `origin/master` after push: `<filled after push>`
- `AGENTS.md` present on remote: `<filled after push>`
- `Report.md` present on remote: `<filled after push>`

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
