# AGENTS.md — PattNG Repository Workflow Rules

> This file is the **permanent project workflow contract** for every task performed in the
> `mo3iiibest77-hub/PattNG` repository. It applies to all agents, contributors, and automated
> tooling. Read it before making any change.

**Repository:** `mo3iiibest77-hub/PattNG`
**Primary branch:** `master`
**Related docs:** `Report.md` (persistent audit + change history), `docs/AGENTS.md` (v2rayNG
build/structure quick reference), `README.md`, `CR.md`.

PattNG is a fork of v2rayNG (application id `com.quietstorm.ng`, output APK
`QuietStormNG-...`). The custom layers that distinguish PattNG live mainly in:

- `V2rayNG/app/src/main/java/com/v2ray/ang/senpai/` — Cloudflare scanner + ISP manager.
- `V2rayNG/app/src/main/java/com/v2ray/ang/ui/CfScanActivity.kt` and
  `V2rayNG/app/src/main/java/com/v2ray/ang/service/CfScanService.kt` — CF scan UI/service.
- `V2rayNG/app/src/main/assets/cf_ranges_v4.txt` — Cloudflare IPv4 candidate ranges.
- `V2rayNG/app/src/main/java/com/v2ray/ang/handler/AngConfigManager.kt` — QSN/finalMask
  auto-injection on subscription import.
- `V2rayNG/app/src/main/java/com/v2ray/ang/core/CoreOutboundBuilder.kt` — finalMask/cipher
  application when building outbound config.
- Protocol URL parsers under `V2rayNG/app/src/main/java/com/v2ray/ang/fmt/`
  (`VlessFmt.kt`, `VmessFmt.kt`, `TrojanFmt.kt`, `ShadowsocksFmt.kt`,
  `Hysteria2Fmt.kt`, `WireguardFmt.kt`, `SocksFmt.kt`, `CustomFmt.kt`, `V2rayNFmt.kt`).

## A. Audit before editing

Before proposing or making any change, you **must**:

1. Read this `AGENTS.md`, `Report.md`, the latest GitHub commits on the target branch, and the
   relevant source files.
2. Understand the existing implementation and its dependencies (callers, data flow, config schema,
   AAR/native core contract).
3. Identify the **exact files and behaviors** affected by the task. State them explicitly.
4. Do **not** replace existing implementations with simplified approximations without explicit
   authorization. Preserve behavior unless the task explicitly authorizes a change.
5. Do **not** delete, disable, or silently change existing features. If a feature must be
   removed, flag it and stop for clarification.
6. If requirements conflict with existing behavior, **document the conflict** and stop for
   clarification when necessary. Do not silently resolve it.

## B. Preserve PattNG-specific functionality

Treat the following as **critical audit areas**. They are audit *targets*, not assumptions that
every behavior is correct. Inspect the actual code and record what is **verified, unverified,
broken, or missing** for each:

- **Cloudflare IP scanning and candidate selection** — `senpai/CloudflareScanner.kt`,
  `CfScanActivity.kt`, `CfScanService.kt`.
- **`cf_ranges_v4.txt` asset and related range-management** — the asset (628 IPv4 CIDRs at
  last audit) and any code that loads/updates/edits it.
- **`finalMask` / QSN injection** — auto-injection on xHTTP+TLS and WS+TLS subscription import
  (`AngConfigManager.kt`) and application in outbound building (`CoreOutboundBuilder.kt`).
- **Cipher Suite configuration and compatibility** — `cs` query param parsing (`FmtBase.kt`)
  and downstream use in config generation.
- **Subscription import and update logic** — `SubscriptionUpdater.kt`, `AngConfigManager.kt`,
  and import entry points.
- **WebSocket + TLS and XHTTP + TLS configuration handling** — network types in
  `enums/NetworkType.kt` (WS, XHTTP, HTTP_UPGRADE, ...), stream-settings construction in
  `CoreOutboundBuilder.kt`, and per-protocol parsers.
- **TLS SNI, HTTP Host, destination address, and other transport settings** — server edit UI
  (`ui/server/*`), `V2rayConfig.kt` DTOs, and outbound construction.
- **Existing support for VLESS, VMess, Trojan, and other protocols** — parsers in `fmt/`;
  never drop a protocol parser.
- **Per-App Proxy and other existing capabilities** — `ui/perappproxy/`, `ui/apppicker/`.

## C. Keep automatic connection filtering separate from protocol support

Future automatic-connection behavior may prioritize **eligible WebSocket + TLS and XHTTP + TLS**
configurations. Any such filtering:

- Must **not** remove other protocols from the application.
- Must **not** destroy their ability to be imported, displayed, edited, or used through existing
  manual workflows.
- Must be implemented only under a **separate, explicitly authorized implementation task**.

Do **not** implement this behavior in a documentation-only task, or in any task that has not
explicitly authorized it.

## D. Mandatory Report.md maintenance

`Report.md` is the persistent audit and change-history document for this project. For every
future task that changes project files:

1. Update `Report.md` **in the same change set** (same commit or PR).
2. Record the task objective and implementation summary.
3. List **every changed file** and explain **why** it changed.
4. Document relevant implementation decisions and compatibility considerations.
5. Record **commands actually executed** and their **real outcomes** (exit codes / output
   summaries), not expected outcomes.
6. Record tests and builds as `PASS`, `FAIL`, `NOT RUN`, or `BLOCKED`.
7. Include failures, unresolved issues, risks, and remaining work.
8. Record the current branch and resulting commit SHA **after** the commit exists.
9. **Never** claim a test, build, runtime check, or push succeeded unless it was verified.
10. Do **not** erase important historical findings; append or update sections while preserving
    useful history.

For a **documentation-only task**, state explicitly that application source was not changed and
that application tests were not run, if applicable.

## E. GitHub is the review source of truth

After each authorized task:

1. Review the complete diff (`git diff` / `git diff --cached`).
2. Ensure unrelated files and secrets are not included.
3. Run appropriate tests and build checks when possible.
4. Update `Report.md`.
5. Commit the intended changes — stage files **explicitly** (`git add <file>`); never
   `git add .` or `git add -A`.
6. Push the commit to the intended GitHub branch.
7. Verify that the pushed commit is visible on GitHub.
8. Verify that the report on GitHub matches the committed report.
9. Provide the commit SHA, changed-file list, test results, and direct GitHub links.

Do **not** report a push as successful merely because a local commit exists. If the push fails,
report the exact failure and preserve the local work. **Never force-push, rewrite shared
history, or overwrite unrelated remote changes** to bypass a failure. If the remote branch has
advanced, inspect and reconcile the divergence safely (fetch, review, merge or rebase with
explicit user consent) before pushing.

## F. Security and scope

- **Never** expose private keys, access tokens, passwords, signing credentials, or other
  secrets in reports or commits.
- Do **not** commit generated secrets, local machine credentials, or unrelated build artifacts.
- Do **not** make unrelated refactors.
- Do **not** change application source during a documentation-only task.
- Do **not** start the new UI implementation without a separate, explicit implementation task.
- Redact credentials, tokens, and machine-specific identifiers when reporting remote URLs or
  config.

## G. Required completion summary

At the end of each task, report:

- Task completed **or** blocked.
- Files changed (list).
- Tests/builds actually run and their results (`PASS` / `FAIL` / `NOT RUN` / `BLOCKED`).
- Commit SHA (after the commit exists).
- Push verification status (verified on GitHub, or failed with exact error).
- Direct GitHub links to the changed files and `Report.md`.
- Known limitations and the next recommended step.

---

## Reference: build & test commands (from `docs/AGENTS.md`)

```sh
# Build (from repo root)
cd V2rayNG
./gradlew assembleFdroidDebug      # or assemblePlaystoreDebug

# Unit tests
cd V2rayNG && ./gradlew test
```

- Kotlin 2.4.0, AGP 9.2.1, Gradle Kotlin DSL.
- JUnit 4 + Mockito; unit tests under `app/src/test/java/`.
- Storage: MMKV exclusively (never SharedPreferences); `MmkvManager` is the data layer.
- Native core: AAR (`libv2ray`) from `AndroidLibXrayLite` / `AndroidLibV2rayLite`; prebuilt
  `.aar` files belong in `app/libs/`.
- Flavors: `fdroid` (suffix `.fdroid`) and `playstore` (no suffix); per-ABI version codes.
