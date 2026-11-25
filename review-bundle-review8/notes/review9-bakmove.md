# Item (d) — .bak files out of the webroot (Paylist review #9, PR #4)

Date: 2026-11-25. Scope: deV2 staging + v2New production (stbjkt) + warganet production (laptopgrt).
No live code changed, nothing deleted (all `mv`), no DB writes, no deploys, no GitHub activity.
Harness `/home/warganet/paylist-contract-c2` untouched (it lives on laptopgrt2; not touched).

## Result summary

| App | Docroot | Moved | Failed | Remaining in docroot | Total size | Destination |
|---|---|---|---|---|---|---|
| deV2 (stbjkt :4600) | /usr/local/app/deV2/html | 162 | 0 | 0 | 3,087,521 B (2.94 MB) | /usr/local/app/deV2-backups/20261125-bak-webroot/ |
| v2New (stbjkt :4660) | /usr/local/app/v2New/html | 114 | 0 | 0 | 2,441,267 B (2.33 MB) | /usr/local/app/v2New-backups/20261125-bak-webroot/ |
| warganet (laptopgrt :3100) | /usr/local/app/app-warganet/html | 57 | 0 | 0 | 1,036,718 B (0.99 MB) | /usr/local/app/app-warganet-backups/20261125-bak-webroot/ |
| **Total** | | **333** | **0** | **0** | **6.26 MB** | |

Relative paths preserved under each destination (e.g. `bayarQR/qris_apply.php.bak-review9-points`).
Full before inventory (path + size): `before-deV2.txt`, `before-v2New.txt`, `before-warganet.txt`.
Full source → destination mapping: `move-deV2.log`, `move-v2New.log`, `move-warganet.log`.
Move script used: `bakmove.sh` (inventory → `mv` preserving relative path → re-find to confirm empty).

## Docroot ground truth (docker inspect, stbjkt)

- deV2 container mounts `/usr/local/app/deV2/html` → `/var/www/html`, port 4600.
- v2New container mounts `/usr/local/app/v2New/html` → `/var/www/html`, port 4660.
- warganet container (`warganet`) listens on :3100.

## v2New files NOT moved — already outside the served docroot (61 files)

`find` under `/usr/local/app/v2New` also found 61 `.bak*` files OUTSIDE `html/`:
`backup-cutover-20261024/` (25), `bak-archive-20261024/` (35), `quarantine/` (1:
`edit-data-cust.php.bak-restruktur-20261119`). These are siblings of `html/`, not mounted
into the container, and not served — verified: `GET /backup-cutover-20261024/`,
`/bak-archive-20261024/`, `/quarantine/` on :4660 all return 404. Left in place;
consolidating them into `v2New-backups/` is optional follow-up, not required for exposure.

## Exposure test BEFORE the move (local curl only, status codes only, no bodies read)

- deV2 :4600 — 8 sampled .bak URLs (incl. `bayarQR/qris_apply.php.bak-review9-points`,
  `member/bayar.php.bak-c2-20261123`, `db_config.php.bak-tls-prep-20261119`): all **403**.
- v2New :4660 — 6 sampled .bak URLs (incl. `member/login.php.bak-k1-20261119`,
  `bayarQR/qris_apply.php.bak-poin-20261119`): all **403**.
- warganet :3100 — 6 sampled .bak URLs: all **200 — EXPOSED**.
  Incl. `/db_config.php.bak-tz-20261021` (200), `/paylist/paylist_apply.php.bak-wafail-20261111` (200),
  `/mikmon/view/live_report.bak.php` (200 — a `.php` file, would execute if requested),
  `/payment/checkout.php.bak-fixcheckout-20261021` (200). This confirms the review #9
  concern was real for warganet. No file contents were fetched or recorded (curl `-o /dev/null`).

## Exposure test AFTER the move

- warganet :3100 — same 6 URLs: all **404** now.
- deV2 :4600 / v2New :4660 — same sampled URLs: still **403** (not 404). Verified this is
  pattern-based, not file-based: a never-existed URL (`/nope.php.bak-never-existed`) also
  returns 403 with the identical 316-byte body on both hosts, while live `/login.php`
  returns 200 / 8,899 bytes. So on deV2/v2New the `.bak*` URL pattern is denied by the
  server config regardless of file existence, and no source is served (316-byte error page).
  Files are confirmed gone from disk (`find` returns nothing under all three docroots).

## Runtime-reference check (grep `\.bak` in live PHP, before moving)

- deV2 + v2New: only 3 files match, all **comments only** — `bayarQR/notify.php:13`
  ("...dengan backup .bak dulu"), `member/login_otp.php:4` ("Berkas asli tersimpan di
  login_otp.php.bak-singlepage-20261119"), `member/login_loghp.php:6` (same pattern).
  No runtime load of any .bak file. (The comments now point at the new backup location;
  updating comment text was out of scope — no live code changes.)
- warganet: 1 match — `file/index.php:514`: `$newFileName = "{$fileName}-{$date}.bak";`.
  This is the file-manager code that CREATES a `.bak` on rename, not a load. Moving existing
  files does not break it. **Follow-up flag:** the file manager can create new `.bak` files
  inside the webroot in future; a code change to redirect those outside the webroot was
  out of scope for this item.
- `mikmon/view/live_report.bak.php` (warganet): unreferenced — router maps `live_report` to
  `view/live_report.php` (the live file); the `.bak.php` is an old Mar-2020 copy (1,361 B vs
  live 4,080 B). Moved with the rest.

## Smoke test (before → after, identical)

| URL | deV2 :4600 | v2New :4660 | warganet :3100 |
|---|---|---|---|
| `/` | 302 → 302 | 302 → 302 | 302 → 302 |
| `/login.php` | 200 → 200 | 200 → 200 | 200 → 200 |
| `/member/login.php` | 200 → 200 | 200 → 200 | 200 → 200 |
| `/index.php` | — | — | 302 → 302 |
| `/bayarQR/qris_apply.php` (GET) | — → 200 | — → 200 | — |
| `/member/payment.php` (GET) | — → 302 | — → 302 | — |
| `/paylist/paylist_apply.php` (GET) | — | — | — → 200 |
| `/mikmon/view/live_report.php` | — | — | — → 200 |

## Live-file sha256 (before → after, unchanged)

- deV2 `bayarQR/qris_apply.php`: `aecf9db69d0799f8dda311cad73473aea2a165be84b02d9e24f08c47b0432057` ✓
- v2New `bayarQR/qris_apply.php`: `a35e11aeccb7c4594b1baef6c76da48ffa0db9714dcc82bef7e0a3281ec70448` ✓
- warganet `paylist/paylist_apply.php`: `dfebb8db583ada95762c023be0657805bb881c9e1ce5a703ae8acad727dd14dd` ✓

## Notes / follow-ups (not done, out of scope)

1. v2New's 61 `.bak` files in `backup-cutover-20261024/`, `bak-archive-20261024/`,
   `quarantine/` are already outside the served docroot; left as-is.
2. warganet `file/index.php` file manager can create new `.bak` files in the webroot
   on future renames — needs a code change to redirect, if the owner wants it closed fully.
3. deV2/v2New return 403 (not 404) for `.bak*` URLs by server config; no content served.
