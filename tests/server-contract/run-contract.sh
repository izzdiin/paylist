#!/usr/bin/env bash
# run-contract.sh — executes the real-server idempotency contract scenarios
# against the scratch stack built by setup-scratch.sh.
#
# What is proven (per target: v2New and warganet):
#   S1  retry after a lost ACK (fire-and-forget first POST, then re-POST
#       with the same stable dedupe_key) -> exactly 1 business effect.
#   S2  N concurrent POSTs with the same key (several rounds) -> exactly 1.
#   S3  POST, then restart the whole test stack (PHP workers + scratch
#       MariaDB), then re-POST the same key -> still exactly 1 effect.
#   S4  different keys, different payments -> 2 effects (negative control).
#   S5a C1: two candidates with the same nominal -> behaviour documented.
#   S5b C2: payment before kode_unik exists -> behaviour documented.
#   S5c C3: two senders racing on one candidate -> behaviour documented.
#       The loser's match_status is timing-dependent ('logged' if its
#       matcher ran after the winner committed, 'matched'+unapplied if
#       before); the invariant is exactly 1 effect + loser never settles.
#   S5d C6: older vs newer bill (v2New: latest-only matcher; warganet: all
#       UNPAID) -> behaviour documented, contrast explicit.
#   S6  (warganet only) top-up branch: retry -> exactly 1 credit.
#
# Every verdict is computed from SCRATCH TABLE CONTENTS after the scenario
# (bill status, pemasukan rows, log rows), not from HTTP responses alone.
# HTTP responses are secondary evidence (duplicate flag, match status).
#
# IDEMPOTENCY (S1-S4, S6) and MATCHING CORRECTNESS (S5a-S5d) are reported
# separately: a matching defect is a FINDING with execution evidence, never
# a reason to claim idempotency passed, and never something this harness
# fixes (the matcher is production billing code, out of scope).
#
# Usage: ./run-contract.sh [--target v2|wg|all] [--rounds N] [--parallel N]
# Env:   SCRATCH_DIR (default /home/warganet/paylist-contract)

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRATCH_DIR="${SCRATCH_DIR:-/home/warganet/paylist-contract}"
TARGET="all"
ROUNDS=3
PARALLEL=12
while [ $# -gt 0 ]; do
    case "$1" in
        --target)   TARGET="$2"; shift 2 ;;
        --rounds)   ROUNDS="$2"; shift 2 ;;
        --parallel) PARALLEL="$2"; shift 2 ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
done
case "$TARGET" in v2|wg|all) ;; *) echo "--target must be v2, wg or all" >&2; exit 2 ;; esac

[ -f "$SCRATCH_DIR/.env" ] || { echo "no $SCRATCH_DIR/.env — run setup-scratch.sh first" >&2; exit 2; }
[ -f "$SCRATCH_DIR/.my-contract.cnf" ] || { echo "no $SCRATCH_DIR/.my-contract.cnf — run setup-scratch.sh first" >&2; exit 2; }
# shellcheck disable=SC1090
. "$SCRATCH_DIR/.env"
CONTRACT_CNF="$SCRATCH_DIR/.my-contract.cnf"

MYSQL_CLI="$(command -v mariadb || command -v mysql || true)"
[ -n "$MYSQL_CLI" ] || { echo "mariadb/mysql client not found" >&2; exit 2; }
command -v curl >/dev/null  || { echo "curl not found" >&2; exit 2; }
command -v python3 >/dev/null || { echo "python3 not found" >&2; exit 2; }
command -v php >/dev/null    || { echo "php not found" >&2; exit 2; }

WORKDIR="$(mktemp -d /tmp/contract-run.XXXXXX)"
trap 'rm -rf "$WORKDIR"' EXIT
mkdir -p "$SCRATCH_DIR/results"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
RESULTS_LOG="$SCRATCH_DIR/results/run-$RUN_ID.log"
exec > >(tee -a "$RESULTS_LOG") 2>&1

PASS=0; FAIL=0
declare -a VERDICTS=()

say()  { echo "[run $(date +%H:%M:%S)] $*"; }
note() { echo "      $*"; }

pass() { PASS=$((PASS+1)); echo "  PASS: $*"; }
fail() { FAIL=$((FAIL+1)); echo "  FAIL: $*"; }
assert_eq() { # label expected actual
    if [ "$2" = "$3" ]; then pass "$1 (expected=$2, actual=$3)";
    else fail "$1 (expected=$2, actual=$3)"; fi
}
assert_like() { # label regex value
    if printf '%s' "$3" | grep -Eq "$2"; then pass "$1 (matches /$2/: $3)";
    else fail "$1 (expected /$2/, got: $3)"; fi
}
verdict() { # scenario verdict line, also collected for the summary
    VERDICTS+=("$1: $2")
    echo "  ==> $1: $2"
}

# --- target mapping ---------------------------------------------------------
db_of()   { [ "$1" = "v2" ] && echo paylist_contract_v2 || echo paylist_contract_wg; }
base_of() { [ "$1" = "v2" ] && echo "${V2_HTTP_BASE:-8080}" || echo "${WG_HTTP_BASE:-8090}"; }
path_of() { [ "$1" = "v2" ] && echo bayarQR/notify.php || echo paylist/notify.php; }
log_of()  { [ "$1" = "v2" ] && echo qris_notif_log || echo paylist_log; }
bill_of() { [ "$1" = "v2" ] && echo proforma || echo payment; }
matched_bill_col_of() { [ "$1" = "v2" ] && echo matched_id_proforma || echo matched_id_payment; }
port_for() { # target index -> round-robin across the workers
    echo $(( $(base_of "$1") + ($2 % ${WORKERS:-4}) ))
}

sql()  { "$MYSQL_CLI" --defaults-file="$CONTRACT_CNF" -N -B -D "$(db_of "$1")" -e "$2"; }
sql_h() { "$MYSQL_CLI" --defaults-file="$CONTRACT_CNF" -D "$(db_of "$1")" -e "$2"; }

# Expected nominal, computed with the EXACT formula the endpoint uses
# (same PHP expression, so float behaviour is shared, not assumed).
expected() { php -r '$b=(int)$argv[1]; $k=(int)$argv[2]; $bi=(int)ceil($b); echo $bi + (int)ceil($bi*0.007) + $k;' "$1" "$2"; }

payload() { # key amount -> payment JSON
    printf '{"type":"payment","device":"harness","pkg":"com.netter.paylist","dedupe_key":"%s","title":"QRIS","text":"Pembayaran QRIS","amount":%s,"notif_time":"2026-11-22 18:00:00"}' "$1" "$2"
}

post() { # target worker-index json -> response body
    curl -s -m 30 -X POST "http://127.0.0.1:$(port_for "$1" "$2")/$(path_of "$1")" \
        -H 'Content-Type: application/json' -H "X-QRIS-Secret: $SECRET" -d "$3"
}

# Fire-and-forget POST: sends the full request, then closes the socket WITHOUT
# reading the response — a genuine lost-ACK simulation. The server still
# processes the request (PHP only notices the disconnect when it writes the
# response, after all DB work is done).
post_ff() { # target json
    local port path
    port="$(base_of "$1")"; path="$(path_of "$1")"
    python3 - "$port" "$path" "$SECRET" "$2" <<'PYEOF'
import socket, sys
port, path, secret, body = int(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4]
req = ("POST /%s HTTP/1.1\r\nHost: 127.0.0.1:%d\r\nContent-Type: application/json\r\n"
       "X-QRIS-Secret: %s\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s"
       % (path, port, secret, len(body.encode()), body))
s = socket.create_connection(("127.0.0.1", port), timeout=5)
s.sendall(req.encode())
s.close()  # lost ACK: never read the response
PYEOF
}

jget() { # python-expr json -> extracted value ("" on parse failure)
    python3 -c "import sys,json; d=json.load(sys.stdin); print($1)" <<<"$2" 2>/dev/null || echo ""
}

# --- preconditions ----------------------------------------------------------
"$MYSQL_CLI" --defaults-file="$CONTRACT_CNF" -e "SELECT 1" >/dev/null 2>&1 \
    || { echo "scratch MariaDB not reachable — run setup-scratch.sh first" >&2; exit 2; }
for t in v2 wg; do
    if [ "$TARGET" = "all" ] || [ "$TARGET" = "$t" ]; then
        code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$(base_of "$t")/$(path_of "$t")" || true)"
        [ "$code" = "405" ] || { echo "workers for $t not up (HTTP $code) — run setup-scratch.sh first" >&2; exit 2; }
    fi
done

# --- seeds ------------------------------------------------------------------
reset_tgt() { # wipe transactional tables (FK-safe order), keep config/refdata
    local db="$1"
    if [ "$db" = "v2" ]; then
        sql v2 "DELETE FROM pemasukan; DELETE FROM qris_notif_log; DELETE FROM proforma; DELETE FROM pelanggan;"
    else
        sql wg "DELETE FROM pemasukan_hotspot; DELETE FROM pemasukan; DELETE FROM saldo_history; DELETE FROM paylist_log; DELETE FROM report; DELETE FROM payment; DELETE FROM mitra; DELETE FROM pelanggan;"
    fi
}

seed_refdata_wg() {
    sql wg "INSERT IGNORE INTO pppoe (name, local_address, remote_address, rate_limit, parent_queue, only_one, masa_aktif) VALUES ('Test 10Mbps', '10.0.0.1', '10.0.0.2', '', '', 'yes', 30);"
}

seed_customer_v2() { # id status masa
    sql v2 "INSERT INTO pelanggan (id_pelanggan, username, nama_pelanggan, layanan, email, no_hp, tanggal_pemasangan, password, status_pembayaran, router, masa_aktif, poin) VALUES ($1, 'uji$1', 'Uji Kontrak $1', 'Test 10Mbps', '', '', '2026-01-01', 'x', '$2', NULL, '$3', 0);"
}
seed_proforma() { # id cust total kode|NULL idtrx
    sql v2 "INSERT INTO proforma (id, idtrx, id_pelanggan, nama_pelanggan, layanan, tagihan, tanggal_terbit, total_tagihan, status, kode_unik) VALUES ($1, '$5', $2, 'Uji Kontrak $2', 'Test 10Mbps', $3, '2026-11-22', $3, 'UNPAID', $4);"
}
seed_customer_wg() { # id status masa
    sql wg "INSERT INTO pelanggan (id_pelanggan, username, nama_pelanggan, layanan, email, no_hp, tanggal_pemasangan, password, status_pembayaran, router, masa_aktif, poin) VALUES ($1, 'uji$1', 'Uji Kontrak $1', 'Test 10Mbps', '', '', '2026-01-01', 'x', '$2', '', '$3', 0);"
}
seed_payment() { # id cust total kode|NULL idtrx layanan
    sql wg "INSERT INTO payment (id, nama_pelanggan, idtrx, status, id_pelanggan, layanan, tagihan, nama_mitra, metode, kode_unik) VALUES ($1, 'Uji Kontrak $2', '$5', 'UNPAID', '$2', '${6:-Test 10Mbps}', $3, NULL, 'Unknown', $4);"
}
seed_mitra_wg() {
    sql wg "INSERT INTO mitra (id_mitra, nama_mitra, no_hp, email, alamat, bank, no_rek, saldo, refcode) VALUES (990001, 'Mitra Uji', '6281234567890', 'mitra@uji.test', 'Uji', 'Uji', '123', 0.00, 'UJI001');"
}

# Standard single-bill seed: customer 900001, bill total 100000 kode 123.
# Second bill (customer 900002): total 150000 kode 45.
AMT_A="$(expected 100000 123)"   # 100823
AMT_B="$(expected 150000 45)"    # 151095
AMT_TOPUP="$(expected 50000 77)" # 50427

seed_single() { # target — one customer, one bill (AMT_A)
    reset_tgt "$1"
    if [ "$1" = "v2" ]; then
        seed_customer_v2 900001 UNPAID 0
        seed_proforma 910001 900001 100000 123 PRO-TEST-910001
    else
        seed_customer_wg 900001 UNPAID 0
        seed_payment 920001 900001 100000 123 TRX-TEST-920001
    fi
}
seed_double() { # target — two customers, two bills (AMT_A, AMT_B)
    reset_tgt "$1"
    if [ "$1" = "v2" ]; then
        seed_customer_v2 900001 UNPAID 0
        seed_customer_v2 900002 UNPAID 0
        seed_proforma 910001 900001 100000 123 PRO-TEST-910001
        seed_proforma 910002 900002 150000 45 PRO-TEST-910002
    else
        seed_customer_wg 900001 UNPAID 0
        seed_customer_wg 900002 UNPAID 0
        seed_payment 920001 900001 100000 123 TRX-TEST-920001
        seed_payment 920002 900002 150000 45 TRX-TEST-920002
    fi
}

evidence() { # dump the tables a scenario's verdict rests on
    local t="$1" log bill mbc
    log="$(log_of "$t")"; bill="$(bill_of "$t")"; mbc="$(matched_bill_col_of "$t")"
    note "-- $t evidence: $log"
    sql_h "$t" "SELECT id, dedupe_key, match_status, matched_id_pelanggan, $mbc AS matched_bill, (applied_at IS NOT NULL) AS applied FROM $log ORDER BY id;" | sed 's/^/      /'
    note "-- $t evidence: $bill + pemasukan + pelanggan"
    sql_h "$t" "SELECT id, id_pelanggan, status FROM $bill ORDER BY id;" | sed 's/^/      /'
    sql_h "$t" "SELECT COUNT(*) AS pemasukan_rows FROM pemasukan;" | sed 's/^/      /'
    sql_h "$t" "SELECT id_pelanggan, status_pembayaran, masa_aktif, poin FROM pelanggan ORDER BY id_pelanggan;" | sed 's/^/      /'
    if [ "$t" = "wg" ]; then
        sql_h "$t" "SELECT COUNT(*) AS report_rows FROM report; SELECT target, LEFT(status, 80) AS status FROM report ORDER BY id;" | sed 's/^/      /'
    fi
}

# --- S1: retry after lost ACK ----------------------------------------------
s1_retry() {
    local t="$1" key="s1-$t-retry" before_fail="$FAIL"
    say "S1 [$t] retry after lost ACK (key=$key, amount=$AMT_A)"
    seed_single "$t"
    post_ff "$t" "$(payload "$key" "$AMT_A")"
    sleep 2
    # The fire-and-forget POST must have landed; otherwise this scenario
    # proves nothing (the retry would be the first effective POST).
    local landed
    landed="$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE dedupe_key='$key';")"
    if [ "$landed" != "1" ]; then
        fail "S1 [$t] fire-and-forget POST did not land (log rows=$landed); aborting S1"
        verdict "S1 [$t] retry/lost-ACK" "SETUP-FAILED"
        return
    fi
    note "fire-and-forget POST landed (log row present, ACK never read)"
    local resp dup status
    resp="$(post "$t" 0 "$(payload "$key" "$AMT_A")")"
    dup="$(jget "d.get('duplicate')" "$resp")"
    status="$(jget "(d.get('match') or {}).get('status')" "$resp")"
    note "retry response: $resp"
    assert_eq "S1 [$t] retry flagged duplicate" "True" "$dup"
    assert_eq "S1 [$t] retry reports stored match" "matched" "$status"
    assert_eq "S1 [$t] log rows for key" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE dedupe_key='$key';")"
    assert_eq "S1 [$t] bills PAID" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(bill_of "$t") WHERE status='PAID';")"
    assert_eq "S1 [$t] pemasukan rows" "1" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
    assert_eq "S1 [$t] applied log rows" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE applied_at IS NOT NULL;")"
    assert_eq "S1 [$t] customer masa_aktif 0->30" "30" "$(sql "$t" "SELECT masa_aktif FROM pelanggan WHERE id_pelanggan=900001;")"
    assert_eq "S1 [$t] customer poin 0->10 (UNPAID)" "10" "$(sql "$t" "SELECT poin FROM pelanggan WHERE id_pelanggan=900001;")"
    if [ "$t" = "wg" ]; then
        # The warganet WA attempt must have died at the dead proxy: exactly
        # one FAILED report row naming the proxy, and no real send.
        assert_eq "S1 [wg] report rows (WA attempt recorded)" "1" "$(sql wg "SELECT COUNT(*) FROM report;")"
        assert_like "S1 [wg] report status names dead proxy" "FAILED: curl:.*59999" "$(sql wg "SELECT status FROM report ORDER BY id LIMIT 1;")"
    fi
    evidence "$t"
    [ "$FAIL" = "$before_fail" ] && verdict "S1 [$t] retry/lost-ACK" "PASS (2 POSTs, 1 business effect)" \
        || verdict "S1 [$t] retry/lost-ACK" "FAIL"
}

# --- S2: concurrent POSTs, same key -----------------------------------------
s2_concurrent() {
    local t="$1" r before_fail="$FAIL"
    say "S2 [$t] $PARALLEL concurrent POSTs, same key, $ROUNDS rounds"
    for r in $(seq 1 "$ROUNDS"); do
        local key="s2-$t-r$r" i
        seed_single "$t"
        # NOTE: wait for the curl PIDs explicitly. A bare `wait` would also
        # wait for the tee process substitution from the log redirect, which
        # never exits until the script ends — the run would wedge here.
        local -a pids=()
        for i in $(seq 1 "$PARALLEL"); do
            post "$t" "$i" "$(payload "$key" "$AMT_A")" > "$WORKDIR/s2-$t-r$r-$i.json" &
            pids+=($!)
        done
        wait "${pids[@]}"
        local dups nondups
        dups="$(grep -l '"duplicate":true' "$WORKDIR"/s2-$t-r$r-*.json 2>/dev/null | wc -l | tr -d ' ')"
        nondups="$(grep -l '"duplicate":false' "$WORKDIR"/s2-$t-r$r-*.json 2>/dev/null | wc -l | tr -d ' ')"
        assert_eq "S2 [$t] round $r duplicate responses" "$((PARALLEL - 1))" "$dups"
        assert_eq "S2 [$t] round $r non-duplicate responses" "1" "$nondups"
        assert_eq "S2 [$t] round $r log rows" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE dedupe_key='$key';")"
        assert_eq "S2 [$t] round $r bills PAID" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(bill_of "$t") WHERE status='PAID';")"
        assert_eq "S2 [$t] round $r pemasukan rows" "1" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
    done
    evidence "$t"
    [ "$FAIL" = "$before_fail" ] && verdict "S2 [$t] concurrent POST" "PASS ($PARALLEL POSTs x $ROUNDS rounds, 1 effect each)" \
        || verdict "S2 [$t] concurrent POST" "FAIL"
}

# --- S3: restart between POSTs ----------------------------------------------
s3_restart() {
    local t="$1" key="s3-$t-restart" before_fail="$FAIL"
    say "S3 [$t] POST, restart whole test stack, re-POST same key"
    seed_single "$t"
    local resp
    resp="$(post "$t" 0 "$(payload "$key" "$AMT_A")")"
    note "first POST response: $resp"
    assert_eq "S3 [$t] first POST applied" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(bill_of "$t") WHERE status='PAID';")"
    say "S3 [$t] restarting stack (PHP workers + scratch MariaDB)..."
    "$SCRIPT_DIR/teardown-scratch.sh" >/dev/null
    "$SCRIPT_DIR/setup-scratch.sh" --target all >/dev/null || {
        fail "S3 [$t] stack did not come back up after restart"
        verdict "S3 [$t] restart durability" "SETUP-FAILED"
        return
    }
    say "S3 [$t] stack back up; re-POSTing same key"
    resp="$(post "$t" 0 "$(payload "$key" "$AMT_A")")"
    note "re-POST response: $resp"
    assert_eq "S3 [$t] re-POST flagged duplicate" "True" "$(jget "d.get('duplicate')" "$resp")"
    assert_eq "S3 [$t] log rows for key after restart" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE dedupe_key='$key';")"
    assert_eq "S3 [$t] bills PAID after restart" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(bill_of "$t") WHERE status='PAID';")"
    assert_eq "S3 [$t] pemasukan rows after restart" "1" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
    assert_eq "S3 [$t] applied_at survived restart" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE dedupe_key='$key' AND applied_at IS NOT NULL;")"
    evidence "$t"
    [ "$FAIL" = "$before_fail" ] && verdict "S3 [$t] restart durability" "PASS (1 effect across restart)" \
        || verdict "S3 [$t] restart durability" "FAIL"
}

# --- S4: negative control ----------------------------------------------------
s4_negative() {
    local t="$1" before_fail="$FAIL"
    say "S4 [$t] negative control: 2 keys, 2 payments -> 2 effects"
    seed_double "$t"
    local r1 r2
    r1="$(post "$t" 0 "$(payload "s4-$t-a" "$AMT_A")")"
    r2="$(post "$t" 1 "$(payload "s4-$t-b" "$AMT_B")")"
    note "resp A: $r1"
    note "resp B: $r2"
    assert_eq "S4 [$t] bills PAID" "2" "$(sql "$t" "SELECT COUNT(*) FROM $(bill_of "$t") WHERE status='PAID';")"
    assert_eq "S4 [$t] pemasukan rows" "2" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
    assert_eq "S4 [$t] log rows" "2" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t");")"
    assert_eq "S4 [$t] applied log rows" "2" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE applied_at IS NOT NULL;")"
    evidence "$t"
    [ "$FAIL" = "$before_fail" ] && verdict "S4 [$t] negative control" "PASS (2 keys -> 2 effects, dedupe not over-broad)" \
        || verdict "S4 [$t] negative control" "FAIL"
}

# --- S5a / C1: two candidates, same nominal ----------------------------------
s5a_ambiguous() {
    local t="$1" before_fail="$FAIL"
    say "S5a/C1 [$t] two candidates, same nominal (ambiguous?)"
    reset_tgt "$t"
    if [ "$t" = "v2" ]; then
        seed_customer_v2 900001 UNPAID 0; seed_customer_v2 900002 UNPAID 0
        seed_proforma 910001 900001 100000 123 PRO-TEST-910001
        seed_proforma 910002 900002 100000 123 PRO-TEST-910002
    else
        seed_customer_wg 900001 UNPAID 0; seed_customer_wg 900002 UNPAID 0
        seed_payment 920001 900001 100000 123 TRX-TEST-920001
        seed_payment 920002 900002 100000 123 TRX-TEST-920002
    fi
    local r1 r2 r3
    r1="$(post "$t" 0 "$(payload "s5a-$t-1" "$AMT_A")")"
    r2="$(post "$t" 1 "$(payload "s5a-$t-1" "$AMT_A")")"
    r3="$(post "$t" 2 "$(payload "s5a-$t-2" "$AMT_A")")"
    note "resp 1st key: $r1"
    note "resp retry same key: $r2"
    note "resp 2nd key, same amount: $r3"
    assert_eq "S5a [$t] 1st POST match=ambiguous" "ambiguous" "$(jget "(d.get('match') or {}).get('status')" "$r1")"
    assert_eq "S5a [$t] retry still ambiguous (stored)" "ambiguous" "$(jget "(d.get('match') or {}).get('status')" "$r2")"
    assert_eq "S5a [$t] 2nd key also ambiguous" "ambiguous" "$(jget "(d.get('match') or {}).get('status')" "$r3")"
    assert_eq "S5a [$t] bills PAID (none must settle)" "0" "$(sql "$t" "SELECT COUNT(*) FROM $(bill_of "$t") WHERE status='PAID';")"
    assert_eq "S5a [$t] pemasukan rows" "0" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
    assert_eq "S5a [$t] ambiguous log rows" "2" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE match_status='ambiguous';")"
    evidence "$t"
    [ "$FAIL" = "$before_fail" ] && verdict "S5a/C1 [$t] ambiguous" "DOCUMENTED: 2 candidates -> ambiguous, neither settles" \
        || verdict "S5a/C1 [$t] ambiguous" "UNEXPECTED (see FAILs above)"
}

# --- S5b / C2: payment before kode_unik exists -------------------------------
s5b_early() {
    local t="$1" before_fail="$FAIL"
    say "S5b/C2 [$t] payment arrives before kode_unik exists"
    reset_tgt "$t"
    if [ "$t" = "v2" ]; then
        seed_customer_v2 900001 UNPAID 0
        seed_proforma 910001 900001 100000 NULL PRO-TEST-910001
    else
        seed_customer_wg 900001 UNPAID 0
        seed_payment 920001 900001 100000 NULL TRX-TEST-920001
    fi
    local r1 r2 r3
    r1="$(post "$t" 0 "$(payload "s5b-$t-early" "$AMT_A")")"
    note "resp before kode exists: $r1"
    assert_eq "S5b [$t] early POST match=logged" "logged" "$(jget "(d.get('match') or {}).get('status')" "$r1")"
    # The QR page is rendered later: kode_unik is born (123, the code the
    # posted amount assumed), so the bill would match from now on.
    sql "$t" "UPDATE $(bill_of "$t") SET kode_unik=123 WHERE id=$([ "$t" = "v2" ] && echo 910001 || echo 920001);"
    r2="$(post "$t" 1 "$(payload "s5b-$t-early" "$AMT_A")")"
    note "resp retry same key after kode born: $r2"
    assert_eq "S5b [$t] retry still logged (no re-match)" "logged" "$(jget "(d.get('match') or {}).get('status')" "$r2")"
    assert_eq "S5b [$t] original row never applied" "0" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE dedupe_key='s5b-$t-early' AND applied_at IS NOT NULL;")"
    r3="$(post "$t" 2 "$(payload "s5b-$t-late" "$AMT_A")")"
    note "resp NEW key, same amount, after kode born: $r3"
    assert_eq "S5b [$t] new key matches (proves it would have matched)" "matched" "$(jget "(d.get('match') or {}).get('status')" "$r3")"
    assert_eq "S5b [$t] bills PAID (only via the new key)" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(bill_of "$t") WHERE status='PAID';")"
    evidence "$t"
    [ "$FAIL" = "$before_fail" ] && verdict "S5b/C2 [$t] early payment" "DOCUMENTED: logged, never re-matched; settles only if re-sent under a new key" \
        || verdict "S5b/C2 [$t] early payment" "UNEXPECTED (see FAILs above)"
}

# --- S5c / C3: two senders racing on one candidate ---------------------------
s5c_race() {
    local t="$1" before_fail="$FAIL" half=$((PARALLEL / 2)) i
    say "S5c/C3 [$t] two senders (different keys) racing on ONE candidate"
    seed_single "$t"
    local -a pids=()
    for i in $(seq 1 "$half"); do
        post "$t" "$i" "$(payload "s5c-$t-A" "$AMT_A")" > "$WORKDIR/s5c-$t-A-$i.json" &
        pids+=($!)
        post "$t" "$i" "$(payload "s5c-$t-B" "$AMT_A")" > "$WORKDIR/s5c-$t-B-$i.json" &
        pids+=($!)
    done
    wait "${pids[@]}"
    assert_eq "S5c [$t] log rows (one per sender)" "2" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t");")"
    assert_eq "S5c [$t] bills PAID (exactly one)" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(bill_of "$t") WHERE status='PAID';")"
    assert_eq "S5c [$t] pemasukan rows (exactly one)" "1" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
    assert_eq "S5c [$t] applied log rows (exactly one)" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE applied_at IS NOT NULL;")"
    # The loser's match_status is timing-dependent — two interleavings:
    #  - loser matched AFTER the winner committed -> 'logged' (no UNPAID
    #    candidate left when its matcher ran);
    #  - loser matched BEFORE the winner committed -> 'matched', then the
    #    atomic claim (UPDATE ... WHERE status<>'PAID', affected_rows=1)
    #    lets only the winner apply -> 'matched' + applied_at NULL.
    # In both cases exactly one effect happens and the loser never settles.
    # The run on 2026-11-22 observed BOTH: 'logged' on v2New, 'matched'
    # on warganet. Assert the invariant, report the observed interleaving.
    local loser_status
    loser_status="$(sql "$t" "SELECT match_status FROM $(log_of "$t") WHERE applied_at IS NULL ORDER BY id LIMIT 1;")"
    assert_eq "S5c [$t] exactly one unapplied (loser) row" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE applied_at IS NULL;")"
    case "$loser_status" in
        logged|matched) pass "S5c [$t] loser status is a known interleaving ($loser_status)" ;;
        *) fail "S5c [$t] loser status unexpected ($loser_status)" ;;
    esac
    note "S5c [$t] observed interleaving: loser ended '$loser_status'"
    evidence "$t"
    [ "$FAIL" = "$before_fail" ] && verdict "S5c/C3 [$t] sender race" "DOCUMENTED: 1 effect (atomic claim holds); loser '$loser_status', never settles, no re-match" \
        || verdict "S5c/C3 [$t] sender race" "UNEXPECTED (see FAILs above)"
}

# --- S5c-2: two senders, two different candidates (control for the race) ----
s5c2_two() {
    local t="$1" before_fail="$FAIL" half=$((PARALLEL / 2)) i
    say "S5c-2 [$t] two senders, two DIFFERENT candidates (race control)"
    seed_double "$t"
    local -a pids=()
    for i in $(seq 1 "$half"); do
        post "$t" "$i" "$(payload "s5c2-$t-A" "$AMT_A")" > "$WORKDIR/s5c2-$t-A-$i.json" &
        pids+=($!)
        post "$t" "$i" "$(payload "s5c2-$t-B" "$AMT_B")" > "$WORKDIR/s5c2-$t-B-$i.json" &
        pids+=($!)
    done
    wait "${pids[@]}"
    assert_eq "S5c-2 [$t] bills PAID" "2" "$(sql "$t" "SELECT COUNT(*) FROM $(bill_of "$t") WHERE status='PAID';")"
    assert_eq "S5c-2 [$t] pemasukan rows" "2" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
    assert_eq "S5c-2 [$t] applied log rows" "2" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE applied_at IS NOT NULL;")"
    evidence "$t"
    [ "$FAIL" = "$before_fail" ] && verdict "S5c-2 [$t] race control" "PASS (2 independent candidates settle concurrently)" \
        || verdict "S5c-2 [$t] race control" "FAIL"
}

# --- S5d / C6: older vs newer bill ------------------------------------------
s5d_latest() {
    local t="$1" before_fail="$FAIL"
    say "S5d/C6 [$t] older UNPAID bill + newer UNPAID bill, same customer"
    reset_tgt "$t"
    if [ "$t" = "v2" ]; then
        seed_customer_v2 900001 UNPAID 0
        seed_proforma 910001 900001 100000 123 PRO-TEST-910001
        seed_proforma 910002 900001 150000 45 PRO-TEST-910002
        local r_old r_new
        r_old="$(post "$t" 0 "$(payload "s5d-$t-old" "$AMT_A")")"
        note "resp older-bill amount: $r_old"
        assert_eq "S5d [v2] older bill invisible (logged)" "logged" "$(jget "(d.get('match') or {}).get('status')" "$r_old")"
        r_new="$(post "$t" 1 "$(payload "s5d-$t-new" "$AMT_B")")"
        note "resp newer-bill amount: $r_new"
        assert_eq "S5d [v2] newer bill matches" "matched" "$(jget "(d.get('match') or {}).get('status')" "$r_new")"
        assert_eq "S5d [v2] older bill still UNPAID" "UNPAID" "$(sql v2 "SELECT status FROM proforma WHERE id=910001;")"
        assert_eq "S5d [v2] newer bill PAID" "PAID" "$(sql v2 "SELECT status FROM proforma WHERE id=910002;")"
        evidence "$t"
        [ "$FAIL" = "$before_fail" ] && verdict "S5d/C6 [v2] latest-only" "DOCUMENTED: matcher sees ONLY the latest bill per customer (JOIN MAX(id)); older bill never matches" \
            || verdict "S5d/C6 [v2] latest-only" "UNEXPECTED (see FAILs above)"
    else
        seed_customer_wg 900001 UNPAID 0
        seed_payment 920001 900001 100000 123 TRX-TEST-920001
        seed_payment 920002 900001 150000 45 TRX-TEST-920002
        local r_old r_new
        r_old="$(post "$t" 0 "$(payload "s5d-$t-old" "$AMT_A")")"
        note "resp older-bill amount: $r_old"
        assert_eq "S5d [wg] older bill MATCHES (all-UNPAID matcher)" "matched" "$(jget "(d.get('match') or {}).get('status')" "$r_old")"
        assert_eq "S5d [wg] older bill PAID" "PAID" "$(sql wg "SELECT status FROM payment WHERE id=920001;")"
        r_new="$(post "$t" 1 "$(payload "s5d-$t-new" "$AMT_B")")"
        note "resp newer-bill amount: $r_new"
        assert_eq "S5d [wg] newer bill also matches" "matched" "$(jget "(d.get('match') or {}).get('status')" "$r_new")"
        assert_eq "S5d [wg] newer bill PAID" "PAID" "$(sql wg "SELECT status FROM payment WHERE id=920002;")"
        assert_eq "S5d [wg] pemasukan rows" "2" "$(sql wg "SELECT COUNT(*) FROM pemasukan;")"
        evidence "$t"
        [ "$FAIL" = "$before_fail" ] && verdict "S5d/C6 [wg] all-UNPAID" "DOCUMENTED: matcher sees ALL UNPAID bills (contrast with v2New); older bill settles" \
            || verdict "S5d/C6 [wg] all-UNPAID" "UNEXPECTED (see FAILs above)"
    fi
}

# --- S6 (warganet only): top-up branch --------------------------------------
s6_topup() {
    local before_fail="$FAIL" key="s6-wg-topup"
    say "S6 [wg] top-up branch: POST + retry (key=$key, amount=$AMT_TOPUP)"
    reset_tgt wg
    seed_mitra_wg
    seed_payment 920099 6281234567890 50000 77 TRX-TOPUP-920099 TopUp-Saldo
    # Top-up rows carry the mitra phone as id_pelanggan and the mitra name.
    sql wg "UPDATE payment SET nama_pelanggan='Mitra Uji' WHERE id=920099;"
    local r1 r2
    r1="$(post wg 0 "$(payload "$key" "$AMT_TOPUP")")"
    r2="$(post wg 1 "$(payload "$key" "$AMT_TOPUP")")"
    note "resp 1st: $r1"
    note "resp retry: $r2"
    assert_eq "S6 [wg] 1st POST matched" "matched" "$(jget "(d.get('match') or {}).get('status')" "$r1")"
    assert_eq "S6 [wg] retry flagged duplicate" "True" "$(jget "d.get('duplicate')" "$r2")"
    assert_eq "S6 [wg] payment PAID" "PAID" "$(sql wg "SELECT status FROM payment WHERE id=920099;")"
    assert_eq "S6 [wg] mitra saldo credited once (50000)" "50000.00" "$(sql wg "SELECT saldo FROM mitra WHERE id_mitra=990001;")"
    assert_eq "S6 [wg] saldo_history rows" "1" "$(sql wg "SELECT COUNT(*) FROM saldo_history;")"
    assert_eq "S6 [wg] pemasukan_hotspot rows" "1" "$(sql wg "SELECT COUNT(*) FROM pemasukan_hotspot;")"
    assert_eq "S6 [wg] applied log rows" "1" "$(sql wg "SELECT COUNT(*) FROM paylist_log WHERE applied_at IS NOT NULL;")"
    sql_h wg "SELECT id, nama_pelanggan, status FROM payment WHERE id=920099; SELECT id_mitra, saldo FROM mitra; SELECT COUNT(*) AS history FROM saldo_history; SELECT COUNT(*) AS hotspot FROM pemasukan_hotspot;" | sed 's/^/      /'
    [ "$FAIL" = "$before_fail" ] && verdict "S6 [wg] top-up retry" "PASS (2 POSTs, 1 credit of Rp50.000)" \
        || verdict "S6 [wg] top-up retry" "FAIL"
}

# --- main -------------------------------------------------------------------
say "contract run $RUN_ID (target=$TARGET, rounds=$ROUNDS, parallel=$PARALLEL)"
say "amounts: AMT_A=$AMT_A (100000+fee+123), AMT_B=$AMT_B (150000+fee+45), AMT_TOPUP=$AMT_TOPUP (50000+fee+77)"
[ "$TARGET" = "wg" ] || [ "$TARGET" = "all" ] && seed_refdata_wg

run_target() {
    local t="$1"
    s1_retry "$t"
    s2_concurrent "$t"
    s3_restart "$t"
    s4_negative "$t"
    s5a_ambiguous "$t"
    s5b_early "$t"
    s5c_race "$t"
    s5c2_two "$t"
    s5d_latest "$t"
    [ "$t" = "wg" ] && s6_topup
}

[ "$TARGET" = "v2" ]  || [ "$TARGET" = "all" ] && run_target v2
[ "$TARGET" = "wg" ]  || [ "$TARGET" = "all" ] && run_target wg

echo
echo "==================== SUMMARY ===================="
for v in "${VERDICTS[@]}"; do echo "  $v"; done
echo "-------------------------------------------------"
echo "assertions: PASS=$PASS FAIL=$FAIL"
echo "full log: $RESULTS_LOG"
if [ "$FAIL" = "0" ]; then
    echo "RESULT: ALL SCENARIOS BEHAVED AS SPECIFIED"
    exit 0
else
    echo "RESULT: $FAIL ASSERTION(S) FAILED — see log"
    exit 1
fi
