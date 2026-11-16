# Paylist v1.1 — Test Plan

Automated + manual device test matrix. Normative definitions live in the
design (`docs/phase1/04-final-design-v1.1.md` §10–§12); this file is the
working matrix for implementation. A documented fixture is not an
executed PASS (R35 watchpoint 3): every row below needs executable
evidence — a test run, a build log, or a recorded manual observation.

## 1. How to run the automated tests

On laptopgrt (2 GB RAM — the heap flags are mandatory):

```sh
export JAVA_HOME=~/jdk17 ANDROID_HOME=~/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
cd ~/paylist-1.3
./gradlew testDebugUnitTest --no-daemon --console=plain \
  -Dorg.gradle.jvmargs="-Xmx1024m" -Dkotlin.daemon.jvm.options="-Xmx512m"
```

Results: `app/build/test-results/testDebugUnitTest/*.xml` (per-class
pass/fail/skip counts) and `app/build/reports/tests/testDebugUnitTest/`
(HTML). CI runs the same command (see `.github/workflows/android-ci.yml`).

Unit tests never hit a live server: endpoint behaviour is pinned by
recorded fixtures under `app/src/test/resources/fixtures/legacy/`
(see §3). Instrumented tests (`src/androidTest/`) need a device or
emulator and are Stage B+.

## 2. Acceptance tests T01–T24 (design §10.1)

| ID | Test | Expected | Stage | Owner |
|---|---|---|---|---|
| T01 | Cold build from clean checkout | APK builds with documented toolchain and assets | A | build log |
| T02 | Legacy payment `matched` | Stop chain at matching route | C | `LegacyResponseClassifierTest` / `LegacyChainTest` |
| T03 | Legacy `logged` then `matched` | Advance exactly once; no duplicate settlement | C | `LegacyChainTest` |
| T04 | `ambiguous` | Stop chain, flag manual review | C | `LegacyResponseClassifierTest` |
| T05 | 2xx malformed payment | Same route retry; never fall through | C | `LegacyResponseClassifierTest` |
| T06 | 429 and `Retry-After` | No drop; scheduled retry | C | `LegacyResponseClassifierTest` / `SyncWorkerTest` |
| T07 | 401 invalid secret | BLOCKED visible; no silent removal | C | `LegacyResponseClassifierTest` |
| T08 | 5xx, timeout, offline | Retry with backoff, fairness | C | `SyncWorkerTest` |
| T09 | Kill process after DB commit | Event survives restart | B | `OutboxMigrationTest` |
| T10 | Crash during legacy import | Repeat import without duplicates or loss | B | `OutboxMigrationTest` / `CutoverTest` |
| T11 | Reboot / Doze / force-stop | Observe limitations; report capture vs delivery separately | E | manual device matrix (§5) |
| T12 | Duplicate live post/rescan | Legacy dedupe preserved | D | `CaptureStagingTest` |
| T13 | Group summary and notification update | Defined behaviour; no unintended payment claim | D | `PrivacyGateTest` / collector tests |
| T14a | OTP/token sample, non-B route | Not uploaded; redacted or rejected | D | `PrivacyGateTest` |
| T14b | OTP/token sample, B route exception (mixed financial+OTP, valid opt-in) | Mixed-event raw transmitted from the in-memory snapshot only, never persisted; pure-auth still dropped | D | `PrivacyGateTest` (F43 B1/B1b) |
| T15 | Secret rotation/Keystore invalidation | No plaintext fallback; actionable error | C | `SecretVaultTest` |
| T16 | Remove route while pending | BLOCKED, no silent drop | D | `ConsentTest` |
| T17 | Multiple event burst + slow endpoint | No callback/network lock; bounded worker concurrency | C/D | `SyncWorkerTest` |
| T18 | Heartbeat/test response (corrected per C1) | 3 cases: fresh notify (`match=null`) → ACK; catat (`match.logged`) → ACK; duplicate notify (`match.logged`) → ACK | A (fixtures) / C (classifier) | `LegacyContractFixturesTest` (A) |
| T19 | Server `matched` with apply error | Flag business issue, no automatic second settlement | C | `ResponseContractTest` (F34) |
| T20 | Upgrade APK with existing routes/queue | Settings and queued events preserved | B/E | `UpgradeCompatibilityTest` |
| T21 | Replay after timeout/lease-expiry against a mock | Exactly one settlement, `last_apply_status` never assumed | C | `DeliveryLeaseTest` |
| T22 | Cutover with old sender in-flight + crash in `DRAINING` | No concurrent double-send; safe state on restart | B/C | `CutoverTest` |
| T23 | `dedupe_key` live vs rescan with PrivacyGate active | Identical key | D | `ConsentTest` |
| T24 | `catat` direction after redaction | Direction classified correctly after PrivacyGate | D | `PrivacyGateTest` (F8/F19) |

## 3. Stage A test inventory (this stage)

| Test class | Cases | What it pins |
|---|---|---|
| `parser/PaymentParserTest` | 21 | v2.3 `parseAmount` behaviour: Rp/IDR patterns, first-token rule (incl. the documented balance mis-pick), the `Rp49 ribu` quirk, no-amount → null. No behaviour change. |
| `delivery/LegacyContractFixturesTest` | 28 (27 fixtures + set-integrity) | §5.4 taxonomy over recorded `notify.php`/`catat.php` shapes: `MATCHED_SETTLED` / `MATCHED_NOT_APPLIED` / `MATCHED_UNCONFIRMED` / `AMBIGUOUS` / `LOGGED`, T18's 3 corrected cases, malformed 2xx, 429 + `Retry-After`, 408, 5xx, 401/403/400 → BLOCKED, 302 → BLOCKED. Test-local classifier; Stage C's `LegacyResponseClassifier` replaces it and must keep the fixtures green. |
| `core/model/DeliveryStateTest` | 18 | §5 state machine: claim/ACK/retry/BLOCKED paths, held states (`NEEDS_REVIEW`, `RAW_UNAVAILABLE`, sweep/reconciliation holds), terminal states, forbidden shortcuts. |

Fixture provenance: response shapes copied from the real PHP sources
(`warganet/paylist/notify.php`, `warganet/paylist/catat.php`); fixtures
the current PHP never emits are marked with a `note` in the fixture file
(defensive/synthetic). Customer ids in fixtures are fictional
(`11000099` / `9999`).

## 4. Fixture ownership F1–F68 (design §12.3)

| Fixtures | Owner test file | Stage |
|---|---|---|
| F1–F11, F13–F14, F19, F35, F43, F49 | `collector/PrivacyGateTest` | D |
| F50, F54 | `privacy/LegacyQueuePrivacyGateTest` — **activation gate: must PASS before any Stage C legacy import/cutover activation** | C |
| F45 | `parser/PaymentParserTest` (per-source pattern confirmation) | D |
| F46–F48, F51 | `matching/TransactionMatchingTest` | D |
| F52 | `matching/OrderRegistryTest` | D |
| F53 | `migration/ManualRecoveryRunbookTest` (documented rehearsal) | E |
| F55 | `diagnostics/DiagnosticsExportAbsenceTest` (static/build check) | E |
| F56–F68 | `export/ReconciliationExportTest` | D (F61 platform checks: E manual) |
| F12, F20, F27–F29, F31, F32, F36–F38, F54 | `migration/CutoverTest` | B/C |
| F18, F22, F28, F31, F36, F37a/b | `collector/CaptureStagingTest` | D |
| F15, F16, F21 | `collector/OnboardingTest` | D |
| F23, F26, F43 | `collector/ConsentTest` | C/D |
| F24, F25, F30, F33, F39 | `collector/AmountTest` | D |
| F34, F39 | `delivery/ResponseContractTest` | C |
| F44 | `security/SecretMigrationTest` / `UpgradeCompatibilityTest` | C/E |
| F40 | `delivery/TransportClientTest` (pre-socket liveness; **gate for the `NEVER_SENT` rule** — until E1/E2/E3 pass, orphaned pre-checkpoint attempts classify `UNKNOWN_PENDING_RECONCILIATION`) | C |
| F41 (incl. J–M) | `migration/CutoverTest` | C |
| F42 (T1a–T7) | P12 server tests | P12 work package |

F17 is deferred (post-MVP, replaced by F53). F61 splits: deterministic
automated injection unit tests gate D→E; Excel/Calc/Sheets import checks
are tracked manual Stage E evidence (PASS/FAIL/NOT RUN per platform and
version; NOT RUN is never a PASS).

## 5. Manual device test matrix (Stages D/E)

Run on a physical device (the listener phone, g57, Android 9+). Record
device, Android version, build, date, and result per row.

| # | Check | Evidence |
|---|---|---|
| M1 | Listener permission grant/revoke; capture starts/stops accordingly | on-device observation |
| M2 | Doze pilot: heartbeat cadence under Doze over 2–4 weeks (T11); capture vs delivery reported separately | heartbeat log + server log |
| M3 | Force-stop / reboot: WorkManager reschedules; no silent queue loss (T11) | on-device observation |
| M4 | Battery-optimisation exemption prompt flow | on-device observation |
| M5 | Device-auth gate for identifier export (F64): biometric / device credential / no secure lock (fail-closed) | on-device observation |
| M6 | Spreadsheet import of a reconciliation export in Excel, LibreOffice Calc, Google Sheets (F61b): formula-injection guard holds; PASS/FAIL/NOT RUN per platform + version | per-platform record |
| M7 | Notification permission denied → review queue/badge fallback (F43 B17) | on-device observation |
| M8 | Upgrade from v2.3 with routes + queue present (T20, F44) | before/after counts |
| M9 | Keystore invalidation (lock-screen change) → route `DISABLED_MISSING_SECRET`, no plaintext fallback (T15) | on-device observation |
| M10 | API 28–30 device-transfer behaviour for backup rules (F35) | platform docs + device test |
| M11 | Manual recovery rehearsal (F53): crash at each copy-before-delete step; no automatic mode reversal; no new idempotency key | rehearsal notes in `docs/operations.md` |
| M12 | No second sender on the listener phone (G8/C15) | owner confirmation in the issue |

## 6. Gates (design §10.3, §11)

| Gate | Evidence |
|---|---|
| G1 Clean build (T01) | `assembleDebug` passes from fresh checkout on laptopgrt with restored wrapper + icons; build log |
| G2 Contract fixtures (T02–T08, T18 corrected) | classifier tests green (Stage A fixtures + Stage C classifier) |
| G3 Migration (T09, T10, T20) | crash-safe import, duplicate `qkey` idempotent, count/checksum verification, legacy snapshot retained |
| G4 Lease + replay (T21) | exactly one settlement; `last_apply_status` never assumed `'ok'` |
| G5 Cutover (T22) | no concurrent double-send; safe state on restart |
| G6 Dedupe-key identity (T23) | identical key live vs rescan |
| G7 Privacy (T24, F43 B14) | redaction holds; `catat` direction correct after PrivacyGate; B14 passes against actual recorder semantics |
| G8 No second sender | owner verifies on g57; recorded in the issue |
| G9 Pilot | 2–4 weeks; zero duplicate settlements (per-`dedupe_key` join, not raw totals); all reconciliation items resolved |
| G10 Rollback rehearsal | procedure executed in rehearsal, not just documented (F53) |
| G11-pilot / G11-production | separate owner approvals |

Additional pre-pilot gates: F40 passes (gate for the `NEVER_SENT`
rule); P12 exists and F42 passes; P11 verified; per-route prior-effect
table complete; per-endpoint contract table complete.
