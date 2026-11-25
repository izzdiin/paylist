# MariaDB 10.5.29 — contract suites report (review #9 item c)

- Server: `mariadb:10.5`, digest
  `sha256:a530aeeefd82f4fa5150f391b6c75462140904780338766f6b03acecb1cca3ce`
  (`10.5.29-MariaDB-ubu2004` via `SELECT VERSION()`).
- Container `mariadb-105` (id `d9df06d510b6…`), `127.0.0.1:3308->3306`,
  datadir `/home/warganet/mariadb-10.5-data`
  (`--innodb-buffer-pool-size=64M --max-connections=32`).
- Scratch dir `/home/warganet/paylist-contract-m105`; workers v2 8180–8183,
  wg 8190–8193; harness copy `/home/warganet/c2-harness-mariadb/`.
- Schema: identik dengan scratch 11.8.6 (kolom+indeks+engine; selisih
  hanya fixture birth `midtrans`/`qris_statis` yang dibuat run-birth.sh).
  Kolom audit C2 + `idx_amount` terverifikasi di kedua DB.

## Results — 819/819 (25 Nov 2026 11:21–11:23 WIB)

| Suite | Result | Log (`/home/warganet/paylist-contract-m105/results/`, copy `…/paylist-contract-c2/results/mariadb105-*`) |
|---|---|---|
| contract | PASS=305 FAIL=0 | `run-20261125-112113.log` |
| review7 | PASS=202 FAIL=0 | `run-review7-20261125-112156.log` |
| review8 | PASS=121 FAIL=0 | `run-review8-20261125-112223.log` |
| birth | PASS=19 FAIL=0 | `run-birth-20261125-112242.log` |
| review9a | PASS=45 FAIL=0 | `run-review9a-20261125-112250.log` |
| review9b | PASS=127 FAIL=0 | `run-review9b-20261125-112253.log` (rerun `run-review9b-20261125-113146.log`, also 127/127) |

- 1020/1205/1213 counts: contract 0, review7 0, review8 8 (all forced
  1205, F8-lockwait), birth 0, review9a 0, review9b 14 (all forced 1213).
- S3 restart durability PASS (v2+wg); container `StartedAt
  2026-11-25T04:21:40Z` proves a real `docker stop` + `docker start`
  during the contract run.
- Deadlock report: `WE ROLL BACK TRANSACTION (1)` on both targets.
- Behavioral differences vs 11.8.6: none observed.
