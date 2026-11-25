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
