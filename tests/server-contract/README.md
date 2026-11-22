# Real-server idempotency contract harness

Proves the server-side exactly-once contract for the real Paylist receiving
endpoints — not a fake ledger — as demanded by the ChatGPT re-review #5
(review 6011401446):

> "identify the real receiving endpoint(s), prove atomic dedupe by stable
> key across lost ACK, retries, concurrent POST and server restart. A
> file-backed fake ledger is not sufficient proof of billing-side
> exactly-once effects. If server code is external/unavailable, document
> the contract as an explicit unverified blocker rather than claiming it
> passed."

Both receiving endpoints are real, available, and tested here. Neither is
an unverified blocker.

## Endpoints under test

| Target | Production endpoint | Code tested (verbatim copy) |
|--------|---------------------|------------------------------|
| v2New | `bill.netterhome.net/bayarQR/notify.php` (stbjkt, DB `v2`) | `notify.php` + `bayarQR/qris_apply.php` + `functions/poin.php` |
| warganet | `warga.netter.my.id/paylist/notify.php` (laptopgrt, DB `warganet`) | `paylist/notify.php` + `paylist/paylist_apply.php` |

Copies used in the 2026-11-22 run (sha256, verified equal on both ends):

- v2New `notify.php` `2bea5462a886682390a4cf659a197a3b23f5919a0efe3f544b9991655553a552`
- v2New `qris_apply.php` `a35e11aeccb7c4594b1baef6c76da48ffa0db9714dcc82bef7e0a3281ec70448`
- v2New `poin.php` `5cb9090b0d51e37055246e9d1ce4aa2064933bd292a75a8172f48bd6877d73a2`
- warganet `notify.php` `a7403af61bf9f53f2b14b980d62795b407588df389a8f566306067ac16d7a1a0`
- warganet `paylist_apply.php` `dfebb8db583ada95762c023be0657805bb881c9e1ce5a703ae8acad727dd14dd`

The endpoint copies are **not** committed to this repo (they are production
billing code). The operator copies them at setup time (see below); the
harness refuses to run without them, and `setup-scratch.sh` prints the
sha256 of every file it assembles so the tested code is auditable.

## What is proven, and what is not

Two different questions, reported separately:

1. **Idempotency** — one stable key (`dedupe_key`) produces at most one
   business effect (one bill PAID, one `pemasukan` row), across lost ACK,
   retries, concurrent POSTs, and server restart. Scenarios S1–S4 (and S6
   for the warganet top-up branch) assert this from **table contents**,
   not from HTTP responses alone.
2. **Matching correctness** — *which* bill settles, or whether any does
   (audit findings C1/C2/C3/C6). Scenarios S5a–S5d document the actual
   behaviour with execution evidence. A matching defect is a **finding**,
   not a pass, and not something this harness fixes: the matcher is
   production billing code, out of scope for this repo.

| Scenario | What it does | Verdict kind |
|----------|--------------|--------------|
| S1 | Retry after a lost ACK (first POST fire-and-forget, socket closed without reading the response; then re-POST same key) | idempotency: exactly 1 effect |
| S2 | N concurrent POSTs, same key, several rounds | idempotency: exactly 1 effect |
| S3 | POST, restart the whole test stack (PHP workers + scratch MariaDB), re-POST same key | idempotency: still exactly 1 effect |
| S4 | Different keys, different payments | negative control: 2 effects (dedupe not over-broad) |
| S5a (C1) | Two candidates, same nominal | documented behaviour |
| S5b (C2) | Payment before `kode_unik` exists; code born later; retry + new key | documented behaviour |
| S5c (C3) | Two senders (different keys) racing on one candidate | documented behaviour; loser is `logged` or `matched`+unapplied depending on interleaving — in both cases exactly 1 effect and the loser never settles |
| S5c-2 | Two senders, two different candidates (race control) | both settle |
| S5d (C6) | Older + newer UNPAID bill, same customer | documented behaviour; v2New vs warganet contrast |
| S6 (wg) | Top-up branch: POST + retry | idempotency: exactly 1 credit |

Results of the 2026-11-22 run: see
[`results/RESULTS-2026-11-22.md`](results/RESULTS-2026-11-22.md).

## Architecture (scratch stack, laptopgrt2)

```
  operator copies (read-only scp from prod hosts, never committed)
  stbjkt:/usr/local/app/v2New/html/{bayarQR/{notify,qris_apply}.php,functions/poin.php}
  laptopgrt:/usr/local/app/app-warganet/html/paylist/{notify,paylist_apply}.php
        |                       |
        v                       v
  $SCRATCH_DIR/endpoint-src     $SCRATCH_DIR/endpoint-src-wg
        |                       |
        v  setup-scratch.sh     v
  $SCRATCH_DIR/html-v2          $SCRATCH_DIR/html-wg
  (endpoint copies + generated SCRATCH-ONLY db_config.php)
        |                       |
  4x php -S 127.0.0.1:8080-83   4x php -S 127.0.0.1:8090-93
  (dead-proxy env; php -S is single-threaded, so 4 workers
   give genuine concurrent execution against one DB)
        |                       |
        +-----------+-----------+
                    |
        dedicated scratch mariadbd (own datadir, own socket,
        127.0.0.1:3307, 64 MB buffer pool)
        DBs: paylist_contract_v2, paylist_contract_wg
        (schemas copied verbatim from production SHOW CREATE TABLE)
```

The system MariaDB on port 3306 (synced `warganet` DB) is never touched.
The scratch instance can be restarted freely, which is what S3 relies on.

## Safety: how the harness guarantees no production effect

- **No production writes, ever.** The harness only ever talks to the
  scratch instance (port 3307) and the scratch docroots. Production hosts
  are touched read-only (scp of the endpoint files, `SHOW CREATE TABLE`).
- **No production credentials or config in the repo.** `db_config.php`
  is generated at setup with random scratch credentials stored in
  `$SCRATCH_DIR/.env` (0600, outside the repo). The Paylist secret is a
  fresh random value per scratch stack; only its hash goes into the
  scratch `paylist_config` table.
- **No production data.** All rows are synthetic (`Uji Kontrak 900001`,
  fake phone `6281234567890` for the mitra top-up seed). Customer
  `no_hp`/`email` seeds are empty strings.
- **External effects are neutralised, each by the endpoint's own guard
  or by construction:**
  - v2New: MikroTik, email and WhatsApp steps are skipped because the
    `router/`, `finance/` and `wa/` PHP files are **not copied** into the
    scratch tree (the code checks `file_exists` first), the scratch
    `router` table is empty, and seeded phone/email are empty.
  - warganet: MikroTik is skipped (empty `router` table). WhatsApp is
    **not** guarded in the code — `paylist_apply.php` always curls the
    real gateway after commit — so every PHP worker runs with a
    **dead-proxy environment** (`https_proxy`/`http_proxy`/`all_proxy`
    = `http://127.0.0.1:59999`, where nothing listens; libcurl honours the
    env proxy, verified empirically on this host). Setup proves the
    isolation with an in-worker probe that reproduces the exact gateway
    call and **aborts if it gets any HTTP response**. The expected
    evidence is a `FAILED: curl: ... port 59999 ...` row in the scratch
    `report` table — proof the attempt died locally.
- **Restart safety.** S3 restarts only the scratch MariaDB instance and
  the scratch PHP workers, never system services.

## Running it

Prerequisites on the scratch host: PHP ≥ 8.1 with mysqli/curl/mbstring,
MariaDB server binaries (`mariadb-install-db`, `mariadbd`, client,
`mysqladmin`), `curl`, `python3`, `ss`, `openssl`. Developed and run on
laptopgrt2 (PHP 8.4.27, MariaDB 11.8.6).

```sh
# 1. Copy the endpoint files (read-only) to the scratch host.
#    v2New (from stbjkt):
scp root@stbjkt.netter.my.id:/usr/local/app/v2New/html/bayarQR/notify.php \
    /usr/local/app/v2New/html/bayarQR/qris_apply.php \
    /usr/local/app/v2New/html/functions/poin.php  <scratch-host>:...
#    Layout expected by setup (defaults):
#      $SCRATCH_DIR/endpoint-src/bayarQR/{notify.php,qris_apply.php}
#      $SCRATCH_DIR/endpoint-src/functions/poin.php
#      $SCRATCH_DIR/endpoint-src-wg/paylist/{notify.php,paylist_apply.php}
#    (warganet files from laptopgrt:/usr/local/app/app-warganet/html/paylist/)
#    NEVER copy db_config.php or any production config.

# 2. Set up the scratch stack (idempotent; --reset drops scratch DBs).
cd tests/server-contract
./setup-scratch.sh [--target v2|wg|all] [--reset]

# 3. Run the contract scenarios.
./run-contract.sh [--target v2|wg|all] [--rounds N] [--parallel N]
# Full log: $SCRATCH_DIR/results/run-<timestamp>.log

# 4. Stop the stack when done (add --purge to delete scratch state).
./teardown-scratch.sh [--purge]
```

Environment overrides: `SCRATCH_DIR` (default
`/home/warganet/paylist-contract`), `ENDPOINT_SRC_V2`,
`ENDPOINT_SRC_WG`, `MYSQL_PORT` (3307), `V2_HTTP_BASE` (8080),
`WG_HTTP_BASE` (8090), `WORKERS` (4), `DEAD_PROXY_PORT` (59999).

## Why this does not run in CI

This harness needs PHP + a dedicated MariaDB instance; the Android CI
workflow cannot provide them, and changing the workflow is out of scope
for this item. The evidence is therefore **local, reproducible harness
evidence** (this directory + the run log), not a CI job. The commit that
adds this directory still passes the Android CI because it touches no
Android code — verified locally with
`./gradlew assembleDebug testDebugUnitTest` before push.

## Files

- `README.md` — this file.
- `setup-scratch.sh` — builds the scratch stack (DB instance, docroots,
  workers, isolation probe, smoke test).
- `run-contract.sh` — runs scenarios S1–S6 and asserts from table contents.
- `teardown-scratch.sh` — stops the stack; `--purge` deletes scratch state.
- `schema-v2.sql`, `schema-wg.sql` — scratch schemas, verbatim from
  production `SHOW CREATE TABLE` (2026-11-22), counters removed.
- `results/RESULTS-2026-11-22.md` — evidence from the first full run.
