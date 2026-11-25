# MariaDB 10.11.19 — contract suites report (review #9 item c)

- Server: `mariadb:10.11`, digest
  `sha256:e5fe75b2b9de0eb9f6f9a4d9c97272fb3a7480e389ccd487a786bab2a12cc0d7`
  (`10.11.19-MariaDB-ubu2204` via `SELECT VERSION()`).
- Container `mariadb-1011`, `127.0.0.1:3309->3306`,
  datadir `/home/warganet/mariadb-10.11-data`
  (`--innodb-buffer-pool-size=64M --max-connections=32`).
- Scratch dir `/home/warganet/paylist-contract-m1011`; workers v2 8280–8283,
  wg 8290–8293; harness copy `/home/warganet/c2-harness-mariadb/`.
- Schema: identik dengan scratch 11.8.6 (kolom+indeks+engine; selisih
  hanya fixture birth `midtrans`/`qris_statis` yang dibuat run-birth.sh).
  Kolom audit C2 + `idx_amount` terverifikasi di kedua DB.

## Results — 817/819 (25 Nov 2026 11:26–11:28 WIB)

| Suite | Result | Log (`/home/warganet/paylist-contract-m1011/results/`, copy `…/paylist-contract-c2/results/mariadb1011-*`) |
|---|---|---|
| contract | PASS=305 FAIL=0 | `run-20261125-112627.log` |
| review7 | PASS=202 FAIL=0 | `run-review7-20261125-112709.log` |
| review8 | PASS=121 FAIL=0 | `run-review8-20261125-112736.log` |
| birth | PASS=19 FAIL=0 | `run-birth-20261125-112755.log` |
| review9a | PASS=45 FAIL=0 | `run-review9a-20261125-112802.log` |
| review9b | **PASS=125 FAIL=2** | `run-review9b-20261125-112806.log` (rerun `run-review9b-20261125-113117.log`, also 125/127) |

- 1020/1205/1213 counts: contract 0, review7 0, review8 8 (all forced
  1205, F8-lockwait), birth 0, review9a 0, review9b 14 (all forced 1213).
- S3 restart durability PASS (v2+wg); container `StartedAt
  2026-11-25T04:26:53Z` proves a real `docker stop` + `docker start`
  during the contract run.

## The 2 failures — diagnostic text only, NOT behavior

Both failures are the same assertion, v2 + wg:
`F9D-deadlock [v2|wg] InnoDB rolled back exactly one transaction
(missing /WE ROLL BACK TRANSACTION \(1\)/ in f9d-d-*.innodb-deadlock.txt)`.

- On 10.11 the deadlock report says `WE ROLL BACK TRANSACTION (2)`;
  on 10.5 and 11.8.6 it says `(1)` — for the SAME deadlock shape with
  the SAME proven victim (apply, 3 row locks, listed as (2); peer,
  7 row locks, listed as (1), committed).
- Deterministic: rerun of review9b on 10.11 reproduces 125/127 with
  `(2)`; rerun on 10.5 reproduces 127/127 with `(1)`.
- Behavior is identical: on 10.11 every behavioral deadlock assertion
  PASSES (apply 1213 + `applied=false`, peer committed, full rollback
  verified row by row, re-apply succeeds → PAID / masa 30 / poin 10).
- The `(N)` does not map consistently to victim or count across
  versions (11.8.6 says `(1)` for a `(2)` victim; 10.11 says `(2)` for
  a `(2)` victim; cf. MDEV-37845 on 10.6.19). The assertion was NOT
  edited; the follow-up decision (loosen the pattern vs pin per
  version) is left to the parent/reviewer. Full analysis:
  `~/workspace/c2-impl/review9-mariadb/NOTES.md` §6.6.
