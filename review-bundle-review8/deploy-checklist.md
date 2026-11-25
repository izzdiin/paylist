# C2 deploy checklist + backup/rollback — v2New & warganet production

**Status:** DRAFT for owner approval. Production deploy is NOT approved;
it proceeds only after the reviewer accepts the review-#8 evidence AND
the owner approves this checklist. Nothing in this document deploys
itself. deV2 (staging) already carries the full review-#8 patch set:
birth-fix (deployed 24 Nov 07:52, backups `.bak-birthfix-20261124`)
and the review-#8 stale-binding fix (deployed 24 Nov 10:14, backup
`qris_apply.php.bak-review8-stale`).

**What's new in review #8 (vs the review-#7 bundle):**

- **Birth-fix (24 Nov 07:54):** `member/payment.php`, `bayar.php`
  (v2New) and `payment/checkout.php` (warganet) now compute the used
  code set from ALL UNPAID bills (v2New previously only the newest
  proforma per customer) and reject a candidate code whose final
  amount collides with another UNPAID bill. No schema change.
  Diffs: `diffs/v2-member-payment.diff`, `diffs/v2-bayar.diff`,
  `diffs/wg-checkout.diff`, `diffs/dev2-member-payment.diff`;
  incremental: `diffs/incremental/*.c2-to-birthfix.diff`.
- **Stale-binding fix (24 Nov 10:16, review #8 P1):** both apply
  files revalidate the matched binding (bill id / type / amount /
  status) fresh under the log row lock + bill row lock and reject a
  stale selection with zero effects. No schema change. Diffs:
  `diffs/v2-qris-apply.diff`, `diffs/wg-paylist-apply.diff`;
  incremental: `diffs/incremental/*.review7-to-review8.diff`.
- **New tests H10/H11 (24 Nov 10:21):** the birth-hook entry path to
  ambiguous→HOLD (H10) and the full HOLD invariant (H11) are now
  covered in `run-review7.sh`. Full suite: 202 PASS, 0 FAIL
  (`logs/run-review7-20261124-102117.log`).
- **Note on `dev2-member-payment.diff`:** deV2 now runs the
  production-based birth-fix file (sha `fbc1fee0…`, verified live),
  so this diff is larger than the review-#7 version — it shows the
  deV2 original vs the production-based file now deployed there.

**Patch set (sha256 manifest: `SHA256SUMS.md` in this directory):**

| System | New files | Patched files | Migration |
|---|---|---|---|
| v2New | `bayarQR/qris_match.php`, `bayarQR/qris_rematch.php` | `bayarQR/notify.php`, `member/payment.php` (birth-fix), `bayar.php` (birth-fix), `payment/index.php`, `bayarQR/qris_apply.php` (review-#8 stale-binding fix) | `migrate-c2-v2.sql` (3 audit columns) |
| warganet | `paylist/paylist_match.php`, `paylist/paylist_rematch.php`, `csrf.php` | `paylist/notify.php`, `payment/checkout.php` (birth-fix), `payment/index.php`, `paylist/paylist_apply.php` (review-#8 stale-binding fix) | `migrate-c2-wg.sql` (3 audit columns + `idx_amount`) |

`csrf.php` on v2New is unchanged (the file already existed; the C2 diff
is empty). Warganet previously had no CSRF helper, so `csrf.php` is new
there. `bayar.php` (v2New) carries the hook transplant proven by the
deV2 E2E; its diff is `diffs/v2-bayar.diff`.

## Pre-deploy verification (do not skip)

1. **Production drift check (read-only).** Re-verify every production
   file's sha256 against `SHA256SUMS.md` ("originals" section) before
   touching anything. If any file drifted since the manifest was made,
   STOP and re-diff: the patch may no longer apply cleanly. Last
   verified 24 Nov 2026 ~10:25 WIB — zero drift on all 10 files.
2. **Production schema check (read-only).** `SHOW CREATE TABLE` on
   `qris_notif_log` / `proforma` (v2) and `paylist_log` / `payment`
   (warganet). Expected pre-deploy state (verified 24 Nov ~10:25 WIB):
   `qris_notif_log` has `idx_amount` + `UNIQUE(dedupe_key)`, lacks the
   3 rematch columns (2,607 rows); `paylist_log` lacks `idx_amount`
   AND the 3 columns (1,884 rows); `payment` has `idx_kode_unik`.
   Parity vs deV2/scratch confirmed the same day: the ONLY schema
   differences are exactly the migration deltas (3 columns v2New;
   3 columns + `idx_amount` warganet) plus AUTO_INCREMENT counters.
   The migrations add exactly the missing pieces; no other drift was
   found. If the schema differs, STOP.
3. **Harness green.** The regression suite (`run-contract.sh`, both
   targets), the review-#7 suite (`run-review7.sh`, both targets,
   now including H10/H11), the review-#8 suite (`run-review8.sh`)
   and the birth suite (`run-birth.sh`) must be green on the EXACT
   bytes being deployed (same shas as the manifest). Last full runs
   (24 Nov, logs in `logs/`): contract 305 PASS 0 FAIL; review7
   202 PASS 0 FAIL (148 + H10 30 + H11 24); review8 121 PASS
   0 FAIL; birth 19 PASS 0 FAIL. Total 647 assertions green.
4. **php -l every file** before copying it (13 files + 2 apply files).
   The deploy scripts abort on a lint failure.
5. **Backup window.** Deploy outside peak payment hours. The file copy
   is atomic per file (`cp` over the live file); the migration runs in
   under a second on these table sizes (2,607 / 1,884 rows as of
   24 Nov). No maintenance window is required, but do not deploy while
   a payment incident is being worked.

## Deploy order

1. **v2New first** (the system with the live E2E proof on deV2), then
   warganet. One system at a time; verify each before starting the
   next.
2. Per system:
   a. `.bak` backup of every file being replaced
      (suffix `.bak-c2-review8` for this patch set), in place next
      to the original. New files have no backup (they did not exist).
      (deV2 already carries `.bak-birthfix-20261124` and
      `.bak-review8-stale` backups from the staging deploys.)
   b. `mysqldump` the two affected tables (log + bill table) to
      `/usr/local/app/<system>/backup-pre-c2-YYYYMMDD/` before the
      migration. Row counts recorded before/after.
   c. Copy patched + new files (owner `www-data`, mode 644).
   d. Run the migration SQL. Verify: row counts unchanged, 3 columns
      present (warganet: + `idx_amount`).
   e. Smoke (no login needed): GET rematch endpoint → 405;
      GET notify → 405; POST rematch without session → 401.
      Any other code → STOP and roll back (below).
   f. Verify the Paylist tab renders (admin login, by the owner or an
      operator — Muse does not forge sessions): "Belum cocok" filter
      present, per-row "Cocokkan ulang" button present, audit columns
      visible. Do NOT click re-match on a real row during verification
      unless the row is a known test case.

## Rollback

**File rollback (fast, no data loss):** copy each `.bak` file back over
its live file, delete the new files (`qris_match.php`,
`qris_rematch.php` / `paylist_match.php`, `paylist_rematch.php`,
warganet `csrf.php` only if it did not exist before — it did not),
verify shas against the manifest "originals". The old `notify.php`
does not reference the new files, so the old behaviour is restored
immediately. No restart needed (PHP reads files per request; allow a
few seconds for opcache revalidation).

**Migration rollback:** the migration is additive-only (new nullable /
defaulted columns + one index). Rolling it back is NOT required for the
old code to work — the old code ignores the extra columns. If a full
schema rollback is wanted anyway:

```sql
-- v2New
ALTER TABLE qris_notif_log
  DROP COLUMN rematch_count, DROP COLUMN last_rematch_at,
  DROP COLUMN rematched_by;
-- warganet
ALTER TABLE paylist_log
  DROP COLUMN rematch_count, DROP COLUMN last_rematch_at,
  DROP COLUMN rematched_by, DROP KEY idx_amount;
```

**Data rollback:** if a bad settlement occurred (none is expected —
the harness proves exactly-once per payment), restore the two dumped
tables from the pre-deploy `mysqldump`. Note that restoring
`qris_notif_log` / `paylist_log` also restores pre-deploy match
statuses; any notifications that arrived during the deploy window
would need re-delivery from the Paylist app (durable queue) or manual
reconciliation. This is the only step that needs the owner's explicit
decision — do not restore tables unilaterally.

**Rollback triggers (any one is enough):** smoke codes wrong; Paylist
tab fails to render; a notification errors (check the PHP error log);
an operator reports a wrong settlement; the owner says so.

## Post-deploy observation (first 24h)

- Watch the PHP error log on both systems for `qris_` / `paylist_`
  errors.
- Check the Paylist tab: new notifications still match and settle as
  before (the matcher formula is unchanged; S1–S4/S6 prove it).
- Confirm no `ER_CHECKREAD` (1020) errors: the review-#7 fix locks the
  log row inside the apply transaction and the review-#8 fix revalidates
  the binding under the same locks; a 1020 after deploy would mean the
  fixed apply file is not live — verify its sha.
- The birth hook fires only when a customer opens the QR page; the
  first hook-triggered settlement is the live proof the hook works in
  production (it was proven on deV2 via the public link, proforma 310).
