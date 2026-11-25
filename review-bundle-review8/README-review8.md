# C2 patch bundle — response to re-review #8 (review 6032791246, PR #4)

Artifacts for the reviewer's "post artifacts and focused tests, not
another summary only" request. Production-vs-fixed unified diffs,
migration SQL, sha256 manifest, test runners + logs, and the deploy
checklist. **Nothing here is deployed to production** — the patch set
is proven on the scratch harness and on deV2 (staging) only.

## Layout

| Path | Contents |
|---|---|
| `diffs/*.diff` | Production → review-#8-state unified diffs (11 files; `v2-csrf.diff` is empty — that file is unchanged) |
| `diffs/new-files/` | Full content of the 5 new files (matcher + rematch endpoints, warganet `csrf.php`) |
| `diffs/incremental/` | What changed since review #7: 3× `c2-to-birthfix` diffs + 2× `review7-to-review8` diffs |
| `migrate-c2-v2.sql`, `migrate-c2-wg.sql` | Additive-only migrations (3 audit columns; warganet also `idx_amount`). No schema change in review #8 |
| `SHA256SUMS.md` | sha256 manifest: production originals, review-#8 patched files, new files, deV2 live shas, bundle diffs |
| `deploy-checklist.md` | Pre-deploy verification, deploy order, backup/rollback, post-deploy observation (DRAFT — production deploy NOT approved) |
| `logs/` | Latest green test logs (see below) |
| `runners/` | The test runners + scratch setup + schema that produced the logs |
| `wg-parity-probe.php` | Read-only schema probe used for the warganet production parity check |
| `make-diffs.sh` | Regenerates `diffs/` + `SHA256SUMS.md` from the source tree |
| `diffs/*.prod-to-review9a.diff`, `diffs/*.review8-to-review9a.diff` | Review #9 item (a): production → review-#9a and review-#8 → review-#9a diffs for both apply files + the orchestrator |
| `runners/run-review9a.sh`, `runners/run-review9b.sh`, `runners/deadlock-peer.php` | Review #9 runners: points-race suite, forced-deadlock (1213) suite + its peer process |
| `runners/stale-orchestrator.php` | Updated orchestrator (review #9a `status` mode + review #9b `deadlock` mode) |
| `notes/` | Work notes per review-#9 item (points, deadlock, MariaDB, .bak move, Stage D) + per-version MariaDB reports |
| `draft-reply.md` | HISTORICAL: the review-#7 reply draft (already posted as PR #4 comment 7117931174). Not part of the review-#8 answer |

## What changed since review #7

1. **Birth-fix (24 Nov 07:54)** — v2New `member/payment.php` +
   `bayar.php` used-set now covers ALL UNPAID bills (was: newest
   proforma per customer); all three birth sites
   (+ warganet `payment/checkout.php`) reject a candidate code whose
   final amount collides with another UNPAID bill. Live-verified on
   deV2 (seed rounds + 45/0 browser sim).
2. **Stale-binding fix (24 Nov 10:16, review #8 P1)** — both apply
   files revalidate the matched binding fresh under the log row lock
   + bill row lock and reject a stale selection with zero effects
   (no settlement, no points, no WA/router, `match_status`
   untouched). Negative control on review-#7 code proved the hole
   (43 FAIL as designed); fixed code is green.
3. **HOLD tests H10/H11 (24 Nov 10:21)** — the third ambiguous→HOLD
   entry path (birth hook, H10) and the full HOLD invariant (H11:
   retry + 2× re-match + hook, zero effects) are now covered.

## Test evidence (all logs in `logs/`, both targets v2New + warganet)

| Suite | Result | Log |
|---|---|---|
| Contract / exactly-once (`run-contract.sh`) | 305 PASS, 0 FAIL | `run-20261124-101010.log` |
| Review #7 concurrency + HOLD (`run-review7.sh`, incl. H10/H11) | 202 PASS, 0 FAIL | `run-review7-20261124-102117.log` |
| H10 only (birth hook → ambiguous) | 30 PASS, 0 FAIL | `run-review7-20261124-101927.log` |
| H11 only (HOLD persists) | 24 PASS, 0 FAIL | `run-review7-20261124-101938.log` |
| Review #8 stale binding (`run-review8.sh`) | 121 PASS, 0 FAIL | `run-review8-20261124-100935.log` |
| Birth (`run-birth.sh --pages fixed`) | 19 PASS, 0 FAIL | `run-birth-20261124-101222.log` |

Total: 647 assertions green. Zero occurrences of 1020/1213/deadlock
in the post-fix logs; `Innodb_deadlocks = 0` after all suites.

## Parity verification (24 Nov ~10:25 WIB, read-only)

- **Code:** all 10 production files re-verified byte-identical to the
  review-#7 manifest (zero drift). Production untouched:
  v2New `qris_apply.php` `a35e11ae…`, warganet `paylist_apply.php`
  `dfebb8db…`.
- **deV2 live:** `member/payment.php` `fbc1fee0…`,
  `bayar.php` `4ff18fc6…`, `qris_apply.php` `99a99e48…` — all match
  the patched files in this bundle.
- **Schema:** production vs deV2 (v2New) and production vs scratch
  (warganet) differ ONLY by exactly the migration deltas
  (3 rematch columns; warganet also `idx_amount`) plus
  AUTO_INCREMENT counters. `proforma` / `payment` tables identical
  modulo counters.

## Verifying the bundle

`SHA256SUMS.md` lists the sha256 of every source file and every diff
in the bundle. The `## bundle diffs` section is relative to `diffs/`;
the other sections are relative to the `c2-impl/` source tree.
Re-running `make-diffs.sh` from that tree reproduces `diffs/` and
`SHA256SUMS.md` byte-identically (except the generation timestamp).

## Review #9 follow-up (25 Nov 2026)

Response to re-review #9 (review 6049617576, PR #4). Items (a)–(d)
below are in this bundle; items (e)+(f) live on PR #5 and are
cross-referenced only — no Android code is in this bundle. **Still
nothing deployed to production** — the pending production deploy now
also includes the item-(a) apply fix, and still awaits owner +
reviewer approval. deV2 carries only the item-(a) apply fix (sha
below); the (d) .bak move already happened on all three hosts.

New artifacts in this bundle: `diffs/*.prod-to-review9a.diff` +
`*.review8-to-review9a.diff` (both apply files + orchestrator),
`runners/run-review9a.sh`, `runners/run-review9b.sh`,
`runners/deadlock-peer.php`, updated `runners/stale-orchestrator.php`
(`status` + `deadlock` modes), `notes/` (one work-notes file per item
+ the two per-version MariaDB reports), and the 25 Nov green logs in
`logs/`. Work notes: `notes/review9-points.md`,
`notes/review9-deadlock.md`, `notes/review9-mariadb.md`,
`notes/review9-bakmove.md`, `notes/review9-staged.md`.

### (a) Points race fix — apply now reads status under the customer-row lock

In the C2 apply path the customer status was read WITHOUT a lock
before the transaction and used to award points (UNPAID→10,
ISOLIR→5, SUSPEND→1). A status flip mid-apply (e.g. the status cron
turning UNPAID→ISOLIR as `masa_aktif` expired) made apply award
points from a stale status. Fix (both apply files, no schema change):
lock the customer row (`SELECT ... FOR UPDATE`) and re-read status
(and `masa_aktif`, v2) under the lock before points are derived;
pre-lock status/points lookups deleted. Warganet top-up unchanged
(no points path there). Diffs:
`diffs/v2-qris-apply.prod-to-review9a.diff`,
`diffs/wg-paylist-apply.prod-to-review9a.diff` (plus the
`review8-to-review9a` incrementals).

- Negative control on review-#8 code: 42 PASS, 3 FAIL as designed
  (points 10 instead of 5, v2+wg).
- Regression after the fix: **45 PASS, 0 FAIL**
  (`logs/run-review9a-20261125-105944.log`); full suites re-run green
  the same morning (contract 305, review7 202, review8 121, birth 19
  — see logs).
- deV2 live: `bayarQR/qris_apply.php` sha `aecf9db6…` (backup
  `qris_apply.php.bak-review9-points`, since moved out of the webroot
  by item (d)); warganet fix verified by harness only (no warganet
  staging host).

### (b) Forced-deadlock test (ER 1213) — 127/127

Two transactions take the same rows in opposite lock order
(apply: log → bill → customer; peer mimicking
`task/servicechange.php`: customer → bill), synchronised via row
locks + `INNODB_LOCK_WAITS` polling, not timing. The apply is proven
the 1213 victim (`applied=false`, full rollback verified row by
row), the peer commits as survivor, and a re-apply of the same log
then succeeds (recoverable state). **127 PASS, 0 FAIL**
(`logs/run-review9b-20261125-114407.log`, post-fix rerun).
**Total across all six suites on 11.8.6: 819/819**
(305 + 202 + 121 + 19 + 45 + 127).

Correction recorded in `notes/review9-deadlock.md` §3.3: the original
claim that `(N)` in `WE ROLL BACK TRANSACTION (N)` is the rolled-back
COUNT was proven wrong on MariaDB 10.11.19 (one rollback, but `(2)`).
`(N)` identifies NEITHER the victim NOR the count — it is
version-dependent diagnostic text (cf. MDEV-37845). The assertion was
loosened to `WE ROLL BACK TRANSACTION \([0-9]+\)`; the victim is
identified from the apply's own 1213 text + peer committed + the
row-by-row rollback checks + the successful re-apply.

### (c) MariaDB 10.5.29 and 10.11.19 — 819/819 on all three versions

The full contract suites were run on REAL MariaDB 10.5.29 and
10.11.19 (Docker, `mariadb:10.5` / `mariadb:10.11`), not inferred
from 11.8.6. Schema identical to the 11.8.6 scratch (columns +
indexes + engine); S3 restart durability PASS on real
`docker stop` + `docker start` in both versions.

| Suite | 11.8.6 | 10.5.29 | 10.11.19 |
|---|---|---|---|
| contract / review7 / review8 / birth / review9a / review9b | 305 / 202 / 121 / 19 / 45 / 127 | 305 / 202 / 121 / 19 / 45 / 127 | 305 / 202 / 121 / 19 / 45 / 127 |
| **Total** | **819/819** | **819/819** | **819/819** |

Note on the 10.11 history: the FIRST 10.11 run was 817/819 — the
same diagnostic-text assertion from (b) failed twice (v2+wg) because
10.11 writes `(2)` where 11.8.6/10.5 write `(1)`; every behavioural
deadlock assertion passed on 10.11 in that run too. After the
`(N)`-pattern loosening (§(b) above), review9b re-ran 127/127 on
all three versions, so 10.11 is 819/819 as well. The per-version
reports in `notes/` (`mariadb105-REPORT.md`,
`mariadb1011-REPORT.md`) were written before the loosening and still
record the first-run numbers (819/819 and 817/819); the follow-up
and the final 127/127 reruns are documented in
`notes/review9-mariadb.md` §6.6–6.7, and the post-fix rerun logs are
in `logs/` (`mariadb105-run-review9b-20261125-114343.log`,
`mariadb1011-run-review9b-20261125-114358.log`, plus the per-version
contract logs `mariadb105-run-20261125-112113.log`,
`mariadb1011-run-20261125-112627.log`).

### (d) 333 .bak files moved out of the webroot

Review #9 asked for `.bak` backups to live outside the webroot.
Moved (all `mv`, nothing deleted, no code changed, no DB writes):
deV2 162 files, v2New 114, warganet 57 — **333 total, 0 failed,
0 remaining in any docroot** (6.26 MB), to
`/usr/local/app/<app>-backups/20261125-bak-webroot/` with relative
paths preserved. Full inventories + move logs are referenced in
`notes/review9-bakmove.md` (before-*.txt / move-*.log live with the
work notes, not in this bundle).

- **Warganet was genuinely exposed:** sampled `.bak` URLs returned
  HTTP **200 before** the move (incl. `db_config.php.bak-*` and
  `paylist_apply.php.bak-*`) and **404 after**. No file contents
  were fetched or recorded (status codes only).
- deV2 / v2New returned 403 before and after (pattern-based denial
  by server config — a never-existed `.bak` URL also returns 403);
  no source was served there, and the files are confirmed gone
  from disk.
- v2New's 61 other `.bak` files already outside `html/`
  (`backup-cutover-20261024/`, `bak-archive-20261024/`,
  `quarantine/`) were left in place — not served (404 on all three
  paths). Smoke tests before → after identical on all three hosts;
  live-file shas unchanged (deV2 `qris_apply.php` `aecf9db6…`,
  v2New `a35e11ae…`, warganet `dfebb8db…`).
- Follow-up (not done): warganet `file/index.php` can still create
  new `.bak` files in the webroot on future renames — needs a code
  change to redirect, if the owner wants it closed fully.

### (e)+(f) Stage D audit P1s — PR #5 only (cross-reference)

On branch `stage-d/collector-security` (PR #5, still DRAFT, head
`c598f33`); no Android code is copied into this billing bundle.

- **(e) `bfc59f8`** — durable capture-overflow accounting +
  acceptance boundary (RECEIVED → ACCEPTED → ENQUEUED):
  `CaptureCoordinator.kt`, `CaptureLogEntity.kt` (new
  `OVERFLOW_DROPPED` outcome), `HistoryActivity.kt`,
  `PaylistListenerService.kt`, new `CaptureOverflowTest.kt`
  (4 tests). 492 unit tests green locally; CI run 45553020145
  success (both jobs).
- **(f) `c598f33`** — `CAPTURE_TO_ROOM_READY` restored to `false`
  (1 file, `PaylistApplication.kt`); flips back to `true` only when
  PR #4 and Stage D combine with all R01–R08, process-death and
  Stage D tests green. 492 unit tests green locally; CI run
  45553968019 success (both jobs).
- Detail: `notes/review9-staged.md`.
