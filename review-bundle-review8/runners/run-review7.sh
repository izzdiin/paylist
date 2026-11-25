#!/usr/bin/env bash
# run-review7.sh — review #7 (6025209778) evidence scenarios for C2 blocker (A).
#
# Finding 2 (concurrency/idempotency):
#   f2_mixed    manual re-match + birth hook + notify retry + competing
#               match, all racing on one row/bill -> exactly 1 effect.
#   f2_hookrace concurrent birth-hook calls racing concurrent re-matches.
#   f2_nodouble one payment can never attach to two bills.
# Finding 3 (code-birth completeness):
#   f3_hookfail injected total hook failure -> payment stays logged,
#               discoverable, and operator-re-matchable afterwards.
# Finding 4 (C6 policy + ambiguity):
#   f4_stale    stale + fresh UNPAID bills, same nominal -> ambiguous,
#               neither settles; resolution only after the candidate set
#               shrinks out-of-band (manual/VA channel), then re-match.
#   Distinct customers sharing one amount is ALREADY covered (S5a notify
#   path + R5 re-match path in run-contract.sh) and is not repeated here.
# Review #8 P1 (ambiguous -> HOLD), third entry path:
#   h10_hook_ambiguous birth hook sees 2 live candidates -> ambiguous,
#               0 effects; later hooks ignore ambiguous rows.
#   h11_hold_persists ambiguous row survives notify retry + 2x operator
#               re-match + birth hook: still ambiguous, 0 effects.
#
# Entry-path note (deliberate): the "manual click" actor is driven through
# probe-rematch.php, which calls the REAL qris_rematch_log() /
# paylist_rematch_log() — the same function the admin HTTP endpoint calls
# after its session+CSRF guard. This runner never forges an admin session
# and never calls the HTTP rematch endpoint; the guard layer itself is
# proven separately (R7 in run-contract.sh). The probe adds no
# concurrency semantics of its own: the race lives in the shared
# function (FOR UPDATE row lock + atomic bill claim).
#
# Every verdict is computed from SCRATCH TABLE CONTENTS, not HTTP alone.
# Usage: ./run-review7.sh [--target v2|wg|all] [--rounds N] [--only SCEN]
# Env:   SCRATCH_DIR (default /home/warganet/paylist-contract-c2)
# Per-actor responses are kept under
# $SCRATCH_DIR/results/run-review7-$RUN_ID/ (never in the mktemp dir,
# which the EXIT trap deletes) so a failed race stays diagnosable.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRATCH_DIR="${SCRATCH_DIR:-/home/warganet/paylist-contract-c2}"
TARGET="all"
ROUNDS=2
ONLY=""
while [ $# -gt 0 ]; do
    case "$1" in
        --target)   TARGET="$2"; shift 2 ;;
        --rounds)   ROUNDS="$2"; shift 2 ;;
        --only)     ONLY="$2"; shift 2 ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
done
case "$TARGET" in v2|wg|all) ;; *) echo "--target must be v2, wg or all" >&2; exit 2 ;; esac
case "$ONLY" in ""|f2_mixed|f2_hookrace|f2_nodouble|f4_stale|f3_hookfail|h10_hook_ambiguous|h11_hold_persists) ;; *) echo "--only must be one of f2_mixed f2_hookrace f2_nodouble f4_stale f3_hookfail h10_hook_ambiguous h11_hold_persists" >&2; exit 2 ;; esac
wanted() { [ -z "$ONLY" ] || [ "$ONLY" = "$1" ]; }

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

WORKDIR="$(mktemp -d /tmp/contract-run-review7.XXXXXX)"
trap 'rm -rf "$WORKDIR"' EXIT
mkdir -p "$SCRATCH_DIR/results"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
RESULTS_LOG="$SCRATCH_DIR/results/run-review7-$RUN_ID.log"
RESPDIR="$SCRATCH_DIR/results/run-review7-$RUN_ID"
mkdir -p "$RESPDIR"
exec > >(tee -a "$RESULTS_LOG") 2>&1

PASS=0; FAIL=0
declare -a VERDICTS=()

say()  { echo "[review7 $(date +%H:%M:%S)] $*"; }
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
assert_ge() { # label min actual (integer >=)
    if [ "$3" -ge "$2" ] 2>/dev/null; then pass "$1 (min=$2, actual=$3)";
    else fail "$1 (expected >= $2, got: $3)"; fi
}
verdict() {
    VERDICTS+=("$1: $2")
    echo "  ==> $1: $2"
}

# --- target mapping (same as run-contract.sh) -------------------------------
db_of()   { [ "$1" = "v2" ] && echo paylist_contract_v2 || echo paylist_contract_wg; }
base_of() { [ "$1" = "v2" ] && echo "${V2_HTTP_BASE:-8080}" || echo "${WG_HTTP_BASE:-8090}"; }
path_of() { [ "$1" = "v2" ] && echo bayarQR/notify.php || echo paylist/notify.php; }
log_of()  { [ "$1" = "v2" ] && echo qris_notif_log || echo paylist_log; }
bill_of() { [ "$1" = "v2" ] && echo proforma || echo payment; }
matched_bill_col_of() { [ "$1" = "v2" ] && echo matched_id_proforma || echo matched_id_payment; }
match_fn_of() { [ "$1" = "v2" ] && echo qris_match.php || echo paylist_match.php; }
match_dir_of() { [ "$1" = "v2" ] && echo bayarQR || echo paylist; }
hook_fn_of() { [ "$1" = "v2" ] && echo qris_kode_birth_rematch || echo paylist_kode_birth_rematch; }
port_for() { echo $(( $(base_of "$1") + ($2 % ${WORKERS:-4}) )); }

sql()  { "$MYSQL_CLI" --defaults-file="$CONTRACT_CNF" -N -B -D "$(db_of "$1")" -e "$2"; }
sql_h() { "$MYSQL_CLI" --defaults-file="$CONTRACT_CNF" -D "$(db_of "$1")" -e "$2"; }

expected() { php -r '$b=(int)$argv[1]; $k=(int)$argv[2]; $bi=(int)ceil($b); echo $bi + (int)ceil($bi*0.007) + $k;' "$1" "$2"; }

payload() { # key amount -> payment JSON
    printf '{"type":"payment","device":"harness","pkg":"com.netter.paylist","dedupe_key":"%s","title":"QRIS","text":"Pembayaran QRIS","amount":%s,"notif_time":"2026-11-22 18:00:00"}' "$1" "$2"
}

post() { # target worker-index json -> response body
    curl -s -m 30 -X POST "http://127.0.0.1:$(port_for "$1" "$2")/$(path_of "$1")" \
        -H 'Content-Type: application/json' -H "X-QRIS-Secret: $SECRET" -d "$3"
}

hook_post() { # target bill_id kode -> probe-hook response body
    curl -s -m 30 -X POST "http://127.0.0.1:$(port_for "$1" 0)/probe-hook.php" \
        -d "bill_id=$2&kode=$3"
}

probe_rematch_post() { # target worker-index log_id -> probe response body
    curl -s -m 30 -X POST "http://127.0.0.1:$(port_for "$1" "$2")/probe-rematch.php" \
        -d "log_id=$3"
}

jget() { # python-expr json -> extracted value ("" on parse failure)
    python3 -c "import sys,json; d=json.load(sys.stdin); print($1)" <<<"$2" 2>/dev/null || echo ""
}

dump_actors() { # prefix -> one truncated line per actor response file
    local prefix="$1" f
    for f in "$RESPDIR"/"$prefix"-*.json; do
        [ -e "$f" ] || continue
        note "actor $(basename "$f" .json): $(head -c 400 "$f" | tr '\n' ' ')"
    done
}

# --- preconditions ----------------------------------------------------------
"$MYSQL_CLI" --defaults-file="$CONTRACT_CNF" -e "SELECT 1" >/dev/null 2>&1 \
    || { echo "scratch MariaDB not reachable — run setup-scratch.sh first" >&2; exit 2; }
for t in v2 wg; do
    if [ "$TARGET" = "all" ] || [ "$TARGET" = "$t" ]; then
        code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$(base_of "$t")/$(path_of "$t")" || true)"
        [ "$code" = "405" ] || { echo "workers for $t not up (HTTP $code) — run setup-scratch.sh first" >&2; exit 2; }
        pcode="$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$(base_of "$t")/probe-rematch.php" -d 'log_id=0' || true)"
        [ "$pcode" = "400" ] || { echo "probe-rematch.php missing for $t (HTTP $pcode) — re-run patched setup-scratch.sh" >&2; exit 2; }
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

seed_customer_v2() { # id status masa
    sql v2 "INSERT INTO pelanggan (id_pelanggan, username, nama_pelanggan, layanan, email, no_hp, tanggal_pemasangan, password, status_pembayaran, router, masa_aktif, poin) VALUES ($1, 'uji$1', 'Uji Kontrak $1', 'Test 10Mbps', '', '', '2026-01-01', 'x', '$2', NULL, '$3', 0);"
}
seed_proforma() { # id cust total kode|NULL idtrx
    sql v2 "INSERT INTO proforma (id, idtrx, id_pelanggan, nama_pelanggan, layanan, tagihan, tanggal_terbit, total_tagihan, status, kode_unik) VALUES ($1, '$5', $2, 'Uji Kontrak $2', 'Test 10Mbps', $3, '2026-11-22', $3, 'UNPAID', $4);"
}
seed_proforma_dated() { # id cust total kode|NULL idtrx tanggal_terbit
    sql v2 "INSERT INTO proforma (id, idtrx, id_pelanggan, nama_pelanggan, layanan, tagihan, tanggal_terbit, total_tagihan, status, kode_unik) VALUES ($1, '$5', $2, 'Uji Kontrak $2', 'Test 10Mbps', $3, '$6', $3, 'UNPAID', $4);"
}
seed_customer_wg() { # id status masa
    sql wg "INSERT INTO pelanggan (id_pelanggan, username, nama_pelanggan, layanan, email, no_hp, tanggal_pemasangan, password, status_pembayaran, router, masa_aktif, poin) VALUES ($1, 'uji$1', 'Uji Kontrak $1', 'Test 10Mbps', '', '', '2026-01-01', 'x', '$2', '', '$3', 0);"
}
seed_payment() { # id cust total kode|NULL idtrx layanan
    sql wg "INSERT INTO payment (id, nama_pelanggan, idtrx, status, id_pelanggan, layanan, tagihan, nama_mitra, metode, kode_unik) VALUES ($1, 'Uji Kontrak $2', '$5', 'UNPAID', '$2', '${6:-Test 10Mbps}', $3, NULL, 'Unknown', $4);"
}

AMT_A="$(expected 100000 123)"   # 100823
AMT_TWIN="$(expected 99900 223)" # 100823 (same nominal, other total+kode)

evidence() { # dump the tables a scenario's verdict rests on
    local t="$1" log bill mbc
    log="$(log_of "$t")"; bill="$(bill_of "$t")"; mbc="$(matched_bill_col_of "$t")"
    note "-- $t evidence: $log"
    sql_h "$t" "SELECT id, dedupe_key, match_status, matched_id_pelanggan, $mbc AS matched_bill, (applied_at IS NOT NULL) AS applied, rematch_count, rematched_by, LEFT(COALESCE(apply_note,''), 90) AS note FROM $log ORDER BY id;" | sed 's/^/      /'
    note "-- $t evidence: $bill + pemasukan"
    sql_h "$t" "SELECT id, id_pelanggan, status FROM $bill ORDER BY id;" | sed 's/^/      /'
    sql_h "$t" "SELECT COUNT(*) AS pemasukan_rows FROM pemasukan;" | sed 's/^/      /'
}

# --- F2: mixed 4-way race ----------------------------------------------------
f2_mixed() {
    local t="$1" before_fail="$FAIL" r
    say "F2-mixed [$t] manual re-match + birth hook + notify retry + competing match, $ROUNDS rounds"
    for r in $(seq 1 "$ROUNDS"); do
        local key="f2m-$t-r$r-early" compkey="f2m-$t-r$r-comp"
        reset_tgt "$t"
        if [ "$t" = "v2" ]; then
            seed_customer_v2 900001 UNPAID 0
            seed_proforma 910001 900001 100000 NULL PRO-TEST-910001
        else
            seed_customer_wg 900001 UNPAID 0
            seed_payment 920001 900001 100000 NULL TRX-TEST-920001
        fi
        local billid=$([ "$t" = "v2" ] && echo 910001 || echo 920001)
        post "$t" 0 "$(payload "$key" "$AMT_A")" > /dev/null
        local logid
        logid="$(sql "$t" "SELECT id FROM $(log_of "$t") WHERE dedupe_key='$key';")"
        assert_eq "F2-mixed [$t] r$r setup: early row logged" "logged" "$(sql "$t" "SELECT match_status FROM $(log_of "$t") WHERE id=$logid;")"
        # The QR page is rendered: kode is born.
        sql "$t" "UPDATE $(bill_of "$t") SET kode_unik=123 WHERE id=$billid;"
        # 12 actors at once, spread across the workers:
        #  4x manual re-match (probe), 2x birth hook, 3x notify retry
        #  (same key), 3x competing fresh notify (new key, same amount).
        local -a pids=() i
        for i in 1 2 3 4; do
            probe_rematch_post "$t" "$i" "$logid" > "$RESPDIR/f2m-$t-r$r-rem-$i.json" &
            pids+=($!)
        done
        for i in 1 2; do
            hook_post "$t" "$billid" 123 > "$RESPDIR/f2m-$t-r$r-hook-$i.json" &
            pids+=($!)
        done
        for i in 1 2 3; do
            post "$t" "$i" "$(payload "$key" "$AMT_A")" > "$RESPDIR/f2m-$t-r$r-retry-$i.json" &
            pids+=($!)
            post "$t" "$i" "$(payload "$compkey" "$AMT_A")" > "$RESPDIR/f2m-$t-r$r-comp-$i.json" &
            pids+=($!)
        done
        wait "${pids[@]}"
        dump_actors "f2m-$t-r$r"
        assert_eq "F2-mixed [$t] r$r bills PAID (exactly one)" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(bill_of "$t") WHERE status='PAID';")"
        assert_eq "F2-mixed [$t] r$r pemasukan rows (exactly one)" "1" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
        assert_eq "F2-mixed [$t] r$r applied log rows (exactly one)" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE applied_at IS NOT NULL;")"
        assert_eq "F2-mixed [$t] r$r log rows (early + competing only)" "2" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t");")"
        assert_eq "F2-mixed [$t] r$r distinct dedupe keys (stable, no new key)" "2" "$(sql "$t" "SELECT COUNT(DISTINCT dedupe_key) FROM $(log_of "$t");")"
        assert_eq "F2-mixed [$t] r$r early key kept its row" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE dedupe_key='$key';")"
        assert_eq "F2-mixed [$t] r$r competing key kept its row" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE dedupe_key='$compkey';")"
        # No payment attached to two bills: applied rows point at one bill,
        # and it is the PAID one.
        assert_eq "F2-mixed [$t] r$r applied rows point at one bill" "1" "$(sql "$t" "SELECT COUNT(DISTINCT $(matched_bill_col_of "$t")) FROM $(log_of "$t") WHERE applied_at IS NOT NULL;")"
        assert_eq "F2-mixed [$t] r$r applied row points at the PAID bill" "$billid" "$(sql "$t" "SELECT $(matched_bill_col_of "$t") FROM $(log_of "$t") WHERE applied_at IS NOT NULL LIMIT 1;")"
        # The loser is exactly one row and is marked with the winner (C3).
        assert_eq "F2-mixed [$t] r$r exactly one unapplied (loser) row" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE applied_at IS NULL;")"
        assert_eq "F2-mixed [$t] r$r loser marked with winner (C3)" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE applied_at IS NULL AND (apply_note LIKE '%kalah berebut%' OR apply_note LIKE '%nominal sama%');")"
        # At least one re-match attempt was audited on the early row.
        assert_ge "F2-mixed [$t] r$r early row rematch_count>=1" "1" "$(sql "$t" "SELECT rematch_count FROM $(log_of "$t") WHERE dedupe_key='$key';")"
    done
    evidence "$t"
    [ "$FAIL" = "$before_fail" ] && verdict "F2-mixed [$t] 4-way race" "PASS (12 actors/round x $ROUNDS, exactly 1 effect; loser marked; keys stable)" \
        || verdict "F2-mixed [$t] 4-way race" "FAIL"
}

# --- F2: hook vs re-match race -----------------------------------------------
f2_hookrace() {
    local t="$1" before_fail="$FAIL" r
    say "F2-hookrace [$t] 6 concurrent hook calls vs 6 concurrent re-matches, $ROUNDS rounds"
    for r in $(seq 1 "$ROUNDS"); do
        local key="f2h-$t-r$r-early"
        reset_tgt "$t"
        if [ "$t" = "v2" ]; then
            seed_customer_v2 900001 UNPAID 0
            seed_proforma 910001 900001 100000 NULL PRO-TEST-910001
        else
            seed_customer_wg 900001 UNPAID 0
            seed_payment 920001 900001 100000 NULL TRX-TEST-920001
        fi
        local billid=$([ "$t" = "v2" ] && echo 910001 || echo 920001)
        post "$t" 0 "$(payload "$key" "$AMT_A")" > /dev/null
        local logid
        logid="$(sql "$t" "SELECT id FROM $(log_of "$t") WHERE dedupe_key='$key';")"
        sql "$t" "UPDATE $(bill_of "$t") SET kode_unik=123 WHERE id=$billid;"
        local -a pids=() i
        for i in 1 2 3 4 5 6; do
            hook_post "$t" "$billid" 123 > "$RESPDIR/f2h-$t-r$r-hook-$i.json" &
            pids+=($!)
            probe_rematch_post "$t" "$i" "$logid" > "$RESPDIR/f2h-$t-r$r-rem-$i.json" &
            pids+=($!)
        done
        wait "${pids[@]}"
        dump_actors "f2h-$t-r$r"
        assert_eq "F2-hookrace [$t] r$r bills PAID (exactly one)" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(bill_of "$t") WHERE status='PAID';")"
        assert_eq "F2-hookrace [$t] r$r pemasukan rows (exactly one)" "1" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
        assert_eq "F2-hookrace [$t] r$r applied log rows (exactly one)" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE applied_at IS NOT NULL;")"
        assert_eq "F2-hookrace [$t] r$r log rows (still one)" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t");")"
        assert_ge "F2-hookrace [$t] r$r rematch_count>=1" "1" "$(sql "$t" "SELECT rematch_count FROM $(log_of "$t") WHERE dedupe_key='$key';")"
    done
    evidence "$t"
    [ "$FAIL" = "$before_fail" ] && verdict "F2-hookrace [$t] hook vs re-match" "PASS (12 actors/round x $ROUNDS, exactly 1 effect)" \
        || verdict "F2-hookrace [$t] hook vs re-match" "FAIL"
}

# --- F2: one payment cannot attach to two bills ------------------------------
f2_nodouble() {
    local t="$1" key="f2n-$t-1" before_fail="$FAIL"
    say "F2-nodouble [$t] settled payment re-matched after a twin bill appears"
    reset_tgt "$t"
    if [ "$t" = "v2" ]; then
        seed_customer_v2 900001 UNPAID 0
        seed_proforma 910001 900001 100000 123 PRO-TEST-910001
    else
        seed_customer_wg 900001 UNPAID 0
        seed_payment 920001 900001 100000 123 TRX-TEST-920001
    fi
    local billA=$([ "$t" = "v2" ] && echo 910001 || echo 920001)
    local billB=$([ "$t" = "v2" ] && echo 910002 || echo 920002)
    post "$t" 0 "$(payload "$key" "$AMT_A")" > /dev/null
    local logid
    logid="$(sql "$t" "SELECT id FROM $(log_of "$t") WHERE dedupe_key='$key';")"
    assert_eq "F2-nodouble [$t] setup: bill A PAID" "PAID" "$(sql "$t" "SELECT status FROM $(bill_of "$t") WHERE id=$billA;")"
    assert_eq "F2-nodouble [$t] setup: pemasukan 1" "1" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
    # A twin bill with the SAME nominal appears afterwards.
    assert_eq "F2-nodouble [$t] twin nominal sanity" "$AMT_A" "$AMT_TWIN"
    if [ "$t" = "v2" ]; then
        seed_proforma 910002 900001 99900 223 PRO-TEST-910002
    else
        seed_payment 920002 900001 99900 223 TRX-TEST-920002
    fi
    assert_eq "F2-nodouble [$t] setup: bill B UNPAID" "UNPAID" "$(sql "$t" "SELECT status FROM $(bill_of "$t") WHERE id=$billB;")"
    # Re-match the ALREADY-APPLIED row: must refuse, no second attach.
    local rr
    rr="$(probe_rematch_post "$t" 1 "$logid")"
    note "rematch applied-row response: $rr"
    assert_eq "F2-nodouble [$t] re-match applied row: ok=false" "False" "$(jget "d.get('ok')" "$rr")"
    assert_eq "F2-nodouble [$t] re-match applied row: applied=false" "False" "$(jget "d.get('applied')" "$rr")"
    assert_like "F2-nodouble [$t] re-match applied row: reason given" "sudah diterapkan" "$(jget "d.get('note')" "$rr")"
    assert_eq "F2-nodouble [$t] bill B still UNPAID" "UNPAID" "$(sql "$t" "SELECT status FROM $(bill_of "$t") WHERE id=$billB;")"
    assert_eq "F2-nodouble [$t] pemasukan still 1" "1" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
    assert_eq "F2-nodouble [$t] row still attached to bill A only" "$billA" "$(sql "$t" "SELECT $(matched_bill_col_of "$t") FROM $(log_of "$t") WHERE id=$logid;")"
    assert_eq "F2-nodouble [$t] rematch_count stays 0 (refused before stamp)" "0" "$(sql "$t" "SELECT rematch_count FROM $(log_of "$t") WHERE id=$logid;")"
    # A NEW payment for the same amount settles bill B normally (single
    # candidate now that A is PAID) — the twin is not blocked, only the
    # already-applied row is.
    post "$t" 2 "$(payload "f2n-$t-2" "$AMT_A")" > /dev/null
    assert_eq "F2-nodouble [$t] bill B PAID by its own payment" "PAID" "$(sql "$t" "SELECT status FROM $(bill_of "$t") WHERE id=$billB;")"
    assert_eq "F2-nodouble [$t] pemasukan now 2 (one per payment)" "2" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
    evidence "$t"
    [ "$FAIL" = "$before_fail" ] && verdict "F2-nodouble [$t] no double attach" "PASS (applied row refuses re-match; twin settles only via its own payment)" \
        || verdict "F2-nodouble [$t] no double attach" "FAIL"
}

# --- F4: stale + fresh bills, same nominal -----------------------------------
f4_stale() {
    local t="$1" key="f4-$t-1" before_fail="$FAIL"
    say "F4-stale [$t] stale + fresh UNPAID bills, same nominal"
    reset_tgt "$t"
    if [ "$t" = "v2" ]; then
        seed_customer_v2 900001 UNPAID 0
        seed_proforma_dated 910001 900001 100000 123 PRO-TEST-910001 2026-09-01
        seed_proforma_dated 910002 900001 99900 223 PRO-TEST-910002 2026-11-22
    else
        seed_customer_wg 900001 UNPAID 0
        seed_payment 920001 900001 100000 123 TRX-TEST-920001
        seed_payment 920002 900001 99900 223 TRX-TEST-920002
        sql wg "UPDATE payment SET waktu='2026-09-01 00:00:00' WHERE id=920001;"
    fi
    local billOld=$([ "$t" = "v2" ] && echo 910001 || echo 920001)
    local billNew=$([ "$t" = "v2" ] && echo 910002 || echo 920002)
    assert_eq "F4-stale [$t] twin nominal sanity" "$AMT_A" "$AMT_TWIN"
    local r1
    r1="$(post "$t" 0 "$(payload "$key" "$AMT_A")")"
    note "resp stale-twin payment: $r1"
    assert_eq "F4-stale [$t] stale twins -> ambiguous" "ambiguous" "$(jget "(d.get('match') or {}).get('status')" "$r1")"
    assert_eq "F4-stale [$t] bills PAID (none settles)" "0" "$(sql "$t" "SELECT COUNT(*) FROM $(bill_of "$t") WHERE status='PAID';")"
    assert_eq "F4-stale [$t] pemasukan rows" "0" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
    assert_like "F4-stale [$t] ambiguous note names candidates (C1)" "ambiguous: 2 kandidat" "$(sql "$t" "SELECT apply_note FROM $(log_of "$t") WHERE dedupe_key='$key';")"
    local logid
    logid="$(sql "$t" "SELECT id FROM $(log_of "$t") WHERE dedupe_key='$key';")"
    # Re-match alone CANNOT resolve genuine ambiguity: the matcher still
    # sees two live candidates. This is the documented mechanism limit —
    # the operator must shrink the candidate set first (see below).
    local rr
    rr="$(probe_rematch_post "$t" 1 "$logid")"
    note "rematch ambiguous response: $rr"
    assert_eq "F4-stale [$t] re-match still ambiguous (2 live candidates)" "ambiguous" "$(jget "d.get('after')" "$rr")"
    assert_eq "F4-stale [$t] re-match applied=false" "False" "$(jget "d.get('applied')" "$rr")"
    assert_eq "F4-stale [$t] bills PAID still 0" "0" "$(sql "$t" "SELECT COUNT(*) FROM $(bill_of "$t") WHERE status='PAID';")"
    # Resolution: the operator settles the FRESH bill out-of-band (manual
    # finance entry / VA channel — the harness UPDATE stands in for that
    # channel, which bypasses the matcher by design). The ambiguous row is
    # then re-matched: one candidate left, the stale bill settles.
    if [ "$t" = "v2" ]; then
        sql v2 "UPDATE proforma SET status='PAID', tanggal_bayar='2026-11-23' WHERE id=$billNew;"
    else
        sql wg "UPDATE payment SET status='PAID' WHERE id=$billNew;"
    fi
    local rr2
    rr2="$(probe_rematch_post "$t" 1 "$logid")"
    note "rematch after set shrinks: $rr2"
    assert_eq "F4-stale [$t] after shrink: rematch matched" "matched" "$(jget "d.get('after')" "$rr2")"
    assert_eq "F4-stale [$t] after shrink: rematch applied" "True" "$(jget "d.get('applied')" "$rr2")"
    assert_eq "F4-stale [$t] stale bill PAID" "PAID" "$(sql "$t" "SELECT status FROM $(bill_of "$t") WHERE id=$billOld;")"
    assert_eq "F4-stale [$t] pemasukan rows (only the matched one)" "1" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
    assert_eq "F4-stale [$t] row attached to the stale bill" "$billOld" "$(sql "$t" "SELECT $(matched_bill_col_of "$t") FROM $(log_of "$t") WHERE id=$logid;")"
    evidence "$t"
    [ "$FAIL" = "$before_fail" ] && verdict "F4-stale [$t] stale twins" "PASS (ambiguous while 2 live; re-match alone cannot pick; settles stale bill after out-of-band shrink)" \
        || verdict "F4-stale [$t] stale twins" "FAIL"
}

# --- F3: injected hook failure + operator recovery ----------------------------
f3_hookfail() {
    local t="$1" key="f3-$t-1" before_fail="$FAIL"
    say "F3-hookfail [$t] total hook failure -> payment stays discoverable + recoverable"
    local match_file="$SCRATCH_DIR/html-$t/$(match_dir_of "$t")/$(match_fn_of "$t")"
    local src_file
    if [ "$t" = "v2" ]; then src_file="$SCRATCH_DIR/endpoint-src/bayarQR/qris_match.php";
    else src_file="$SCRATCH_DIR/endpoint-src-wg/paylist/paylist_match.php"; fi
    local hook_fn
    hook_fn="$(hook_fn_of "$t")"
    local backup="$WORKDIR/match-backup-$t.php"
    cp "$match_file" "$backup"
    local src_sha
    src_sha="$(sha256sum "$src_file" | cut -d' ' -f1)"
    reset_tgt "$t"
    if [ "$t" = "v2" ]; then
        seed_customer_v2 900001 UNPAID 0
        seed_proforma 910001 900001 100000 NULL PRO-TEST-910001
    else
        seed_customer_wg 900001 UNPAID 0
        seed_payment 920001 900001 100000 NULL TRX-TEST-920001
    fi
    local billid=$([ "$t" = "v2" ] && echo 910001 || echo 920001)
    post "$t" 0 "$(payload "$key" "$AMT_A")" > /dev/null
    local logid
    logid="$(sql "$t" "SELECT id FROM $(log_of "$t") WHERE dedupe_key='$key';")"
    assert_eq "F3-hookfail [$t] setup: early row logged" "logged" "$(sql "$t" "SELECT match_status FROM $(log_of "$t") WHERE id=$logid;")"
    sql "$t" "UPDATE $(bill_of "$t") SET kode_unik=123 WHERE id=$billid;"
    # Break the hook in the SCRATCH docroot copy only (endpoint source
    # untouched; restored below and verified by sha). The injected throw
    # simulates a catastrophic hook failure (e.g. DB error in lookup).
    python3 - "$match_file" "$hook_fn" <<'PYEOF'
import sys
path, fn = sys.argv[1], sys.argv[2]
src = open(path, encoding='utf-8').read()
anchor = "function %s(" % fn
assert src.count(anchor) == 1, "hook function anchor not unique"
idx = src.index(anchor)
eol = src.index("\n", idx)
inject = "\n    throw new RuntimeException('review7-injected-hook-failure');"
src = src[:eol] + inject + src[eol:]
open(path, 'w', encoding='utf-8').write(src)
print("hook broken in scratch copy: %s" % path)
PYEOF
    sleep 3  # let workers re-read the file (opcache revalidate window)
    local hr
    hr="$(hook_post "$t" "$billid" 123)"
    note "hook response with broken hook: $(printf '%s' "$hr" | head -c 200)"
    # The probe has no try/catch (unlike the real page call-sites, which
    # catch hook failures so the QR still renders): it 500s. What matters
    # is the database state, not the probe's HTTP code.
    if printf '%s' "$hr" | grep -q '"ok":true'; then
        fail "F3-hookfail [$t] broken hook still reported ok"
    else
        pass "F3-hookfail [$t] broken hook did not report ok"
    fi
    assert_eq "F3-hookfail [$t] row still logged after hook failure" "logged" "$(sql "$t" "SELECT match_status FROM $(log_of "$t") WHERE id=$logid;")"
    assert_eq "F3-hookfail [$t] row still unapplied" "0" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE id=$logid AND applied_at IS NOT NULL;")"
    assert_eq "F3-hookfail [$t] bill still UNPAID" "UNPAID" "$(sql "$t" "SELECT status FROM $(bill_of "$t") WHERE id=$billid;")"
    # Discoverability: the "Belum cocok" filter shows rows with
    # (logged|ambiguous|matched-unapplied) AND amount NOT NULL
    # (verified in the patched Paylist tab code, both systems).
    assert_eq "F3-hookfail [$t] row visible to Belum-cocok filter (logged + amount)" "1" \
        "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE id=$logid AND match_status='logged' AND amount IS NOT NULL;")"
    # Restore the scratch copy and verify it is byte-identical to the
    # endpoint source again.
    cp "$backup" "$match_file"
    sleep 3
    local restored_sha
    restored_sha="$(sha256sum "$match_file" | cut -d' ' -f1)"
    assert_eq "F3-hookfail [$t] scratch matcher restored (sha == endpoint src)" "$src_sha" "$restored_sha"
    # Operator recovery: re-match the row — it settles now.
    local rr
    rr="$(probe_rematch_post "$t" 1 "$logid")"
    note "recovery rematch response: $rr"
    assert_eq "F3-hookfail [$t] recovery rematch ok" "True" "$(jget "d.get('ok')" "$rr")"
    assert_eq "F3-hookfail [$t] recovery rematch matched" "matched" "$(jget "d.get('after')" "$rr")"
    assert_eq "F3-hookfail [$t] recovery rematch applied" "True" "$(jget "d.get('applied')" "$rr")"
    assert_eq "F3-hookfail [$t] bill PAID after recovery" "PAID" "$(sql "$t" "SELECT status FROM $(bill_of "$t") WHERE id=$billid;")"
    assert_eq "F3-hookfail [$t] pemasukan rows (exactly one)" "1" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
    evidence "$t"
    [ "$FAIL" = "$before_fail" ] && verdict "F3-hookfail [$t] hook failure recovery" "PASS (failure leaves payment logged + discoverable; operator re-match recovers it)" \
        || verdict "F3-hookfail [$t] hook failure recovery" "FAIL"
}

# --- H10: kode-birth hook must NOT settle an ambiguous row -------------------
# Skenario: pembayaran datang sebelum kode mana pun lahir (logged). Kode
# tagihan A lahir tetapi hook-nya TERLEWAT (gaya f3: klaim terjadi, hook
# tidak jalan — disimulasikan dengan UPDATE langsung tanpa hook_post).
# Kode tagihan B lahir dan hook-nya jalan: matcher kini melihat DUA
# kandidat -> baris harus menjadi ambiguous, nol efek bisnis.
h10_hook_ambiguous() {
    local t="$1" key="h10-$t-1" before_fail="$FAIL"
    say "H10-hook-ambiguous [$t] hook must not settle while 2 candidates live"
    reset_tgt "$t"
    if [ "$t" = "v2" ]; then
        seed_customer_v2 900001 UNPAID 0
        seed_proforma 910001 900001 100000 NULL PRO-TEST-910001
        seed_proforma 910002 900001 99900 NULL PRO-TEST-910002
    else
        seed_customer_wg 900001 UNPAID 0
        seed_payment 920001 900001 100000 NULL TRX-TEST-920001
        seed_payment 920002 900001 99900 NULL TRX-TEST-920002
    fi
    local billA=$([ "$t" = "v2" ] && echo 910001 || echo 920001)
    local billB=$([ "$t" = "v2" ] && echo 910002 || echo 920002)
    # Pembayaran datang sebelum kode mana pun ada -> logged, tanpa kandidat.
    post "$t" 0 "$(payload "$key" "$AMT_A")" > /dev/null
    assert_eq "H10 [$t] early POST logged" "logged" "$(sql "$t" "SELECT match_status FROM $(log_of "$t") WHERE dedupe_key='$key';")"
    # Kode A lahir, hook-nya TERLEWAT (UPDATE langsung, tanpa hook_post).
    sql "$t" "UPDATE $(bill_of "$t") SET kode_unik=123 WHERE id=$billA;"
    assert_eq "H10 [$t] row still logged after missed hook" "logged" "$(sql "$t" "SELECT match_status FROM $(log_of "$t") WHERE dedupe_key='$key';")"
    # Kode B lahir dan hook-nya JALAN. Matcher kini melihat dua kandidat.
    sql "$t" "UPDATE $(bill_of "$t") SET kode_unik=223 WHERE id=$billB;"
    local hr
    hr="$(hook_post "$t" "$billB" 223)"
    note "hook response (ambiguous expected): $hr"
    assert_eq "H10 [$t] hook probe ok" "True" "$(jget "d.get('ok')" "$hr")"
    assert_eq "H10 [$t] hook checked 1 waiting row" "1" "$(jget "(d.get('hook') or {}).get('checked')" "$hr")"
    assert_eq "H10 [$t] hook applied 0 (ambiguous)" "0" "$(jget "(d.get('hook') or {}).get('applied')" "$hr")"
    assert_eq "H10 [$t] row ambiguous after hook" "ambiguous" "$(sql "$t" "SELECT match_status FROM $(log_of "$t") WHERE dedupe_key='$key';")"
    assert_eq "H10 [$t] bills PAID (none settles)" "0" "$(sql "$t" "SELECT COUNT(*) FROM $(bill_of "$t") WHERE status='PAID';")"
    assert_eq "H10 [$t] pemasukan rows" "0" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
    assert_eq "H10 [$t] row unapplied" "0" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE dedupe_key='$key' AND applied_at IS NOT NULL;")"
    assert_like "H10 [$t] ambiguous note names candidates (C1)" "ambiguous: 2 kandidat" "$(sql "$t" "SELECT apply_note FROM $(log_of "$t") WHERE dedupe_key='$key';")"
    assert_eq "H10 [$t] hook stamped rematch (kode-birth)" "kode-birth" "$(sql "$t" "SELECT rematched_by FROM $(log_of "$t") WHERE dedupe_key='$key';")"
    assert_eq "H10 [$t] rematch_count=1 (hook attempt stamped)" "1" "$(sql "$t" "SELECT rematch_count FROM $(log_of "$t") WHERE dedupe_key='$key';")"
    # Panggilan hook kedua (tagihan A): baris sudah ambiguous, dan hook
    # hanya memindai baris 'logged' -> tidak ada yang diperiksa, tetap
    # nol efek.
    local hr2
    hr2="$(hook_post "$t" "$billA" 123)"
    assert_eq "H10 [$t] 2nd hook call checks 0 rows (ambiguous ignored)" "0" "$(jget "(d.get('hook') or {}).get('checked')" "$hr2")"
    assert_eq "H10 [$t] still 0 effects after 2nd hook" "0" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
    # Keterlihatan: filter "Belum cocok" menampilkan baris ambiguous.
    assert_eq "H10 [$t] row visible to Belum-cocok filter (ambiguous + amount)" "1" \
        "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE dedupe_key='$key' AND match_status='ambiguous' AND amount IS NOT NULL;")"
    evidence "$t"
    [ "$FAIL" = "$before_fail" ] && verdict "H10-hook-ambiguous [$t]" "PASS (hook sees 2 candidates -> ambiguous, 0 effects; ambiguous rows ignored by later hooks)" \
        || verdict "H10-hook-ambiguous [$t]" "FAIL"
}

# --- H11: an ambiguous row survives every automatic + operator prod ---------
# Invarian HOLD penuh dalam satu skenario: setelah ambiguous, tidak ada
# jalur masuk — retry notify kunci-sama, re-match operator (dua kali),
# hook kode-birth — yang melunasi apa pun atau mengubah nasib baris.
# Semua asersi dari isi tabel, bukan dari HTTP saja.
h11_hold_persists() {
    local t="$1" key="h11-$t-1" before_fail="$FAIL"
    say "H11-hold-persists [$t] ambiguous row survives retry + rematch + hook"
    reset_tgt "$t"
    if [ "$t" = "v2" ]; then
        seed_customer_v2 900001 UNPAID 0
        seed_proforma_dated 910001 900001 100000 123 PRO-TEST-910001 2026-09-01
        seed_proforma_dated 910002 900001 99900 223 PRO-TEST-910002 2026-11-22
    else
        seed_customer_wg 900001 UNPAID 0
        seed_payment 920001 900001 100000 123 TRX-TEST-920001
        seed_payment 920002 900001 99900 223 TRX-TEST-920002
        sql wg "UPDATE payment SET waktu='2026-09-01 00:00:00' WHERE id=920001;"
    fi
    local billA=$([ "$t" = "v2" ] && echo 910001 || echo 920001)
    local r1
    r1="$(post "$t" 0 "$(payload "$key" "$AMT_A")")"
    assert_eq "H11 [$t] POST ambiguous" "ambiguous" "$(jget "(d.get('match') or {}).get('status')" "$r1")"
    local logid
    logid="$(sql "$t" "SELECT id FROM $(log_of "$t") WHERE dedupe_key='$key';")"
    # 1. Retry notify kunci-sama -> status tersimpan ambiguous, nol efek.
    local r2
    r2="$(post "$t" 1 "$(payload "$key" "$AMT_A")")"
    assert_eq "H11 [$t] retry still ambiguous (stored)" "ambiguous" "$(jget "(d.get('match') or {}).get('status')" "$r2")"
    # 2. Re-match operator, dua kali -> ambiguous keduanya, applied=false.
    local rr1 rr2
    rr1="$(probe_rematch_post "$t" 1 "$logid")"
    rr2="$(probe_rematch_post "$t" 1 "$logid")"
    assert_eq "H11 [$t] rematch 1 still ambiguous" "ambiguous" "$(jget "d.get('after')" "$rr1")"
    assert_eq "H11 [$t] rematch 1 applied=false" "False" "$(jget "d.get('applied')" "$rr1")"
    assert_eq "H11 [$t] rematch 2 still ambiguous" "ambiguous" "$(jget "d.get('after')" "$rr2")"
    assert_eq "H11 [$t] rematch 2 applied=false" "False" "$(jget "d.get('applied')" "$rr2")"
    # 3. Hook kode-birth untuk tagihan A -> baris sudah ambiguous, hook
    # hanya memindai baris 'logged'.
    local hr
    hr="$(hook_post "$t" "$billA" 123)"
    assert_eq "H11 [$t] hook checks 0 rows (ambiguous ignored)" "0" "$(jget "(d.get('hook') or {}).get('checked')" "$hr")"
    # Keadaan akhir: tetap ambiguous, belum diterapkan, nol efek bisnis.
    assert_eq "H11 [$t] row still ambiguous" "ambiguous" "$(sql "$t" "SELECT match_status FROM $(log_of "$t") WHERE id=$logid;")"
    assert_eq "H11 [$t] row unapplied" "0" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE id=$logid AND applied_at IS NOT NULL;")"
    assert_eq "H11 [$t] bills PAID (none settles)" "0" "$(sql "$t" "SELECT COUNT(*) FROM $(bill_of "$t") WHERE status='PAID';")"
    assert_eq "H11 [$t] pemasukan rows" "0" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
    assert_eq "H11 [$t] rematch_count=2 (both attempts stamped)" "2" "$(sql "$t" "SELECT rematch_count FROM $(log_of "$t") WHERE id=$logid;")"
    evidence "$t"
    [ "$FAIL" = "$before_fail" ] && verdict "H11-hold-persists [$t]" "PASS (ambiguous survives retry + 2x rematch + hook, 0 effects)" \
        || verdict "H11-hold-persists [$t]" "FAIL"
}

# --- main -------------------------------------------------------------------
say "review7 run $RUN_ID (target=$TARGET, rounds=$ROUNDS)"
say "amounts: AMT_A=$AMT_A (100000+fee+123), AMT_TWIN=$AMT_TWIN (99900+fee+223)"

run_target() {
    local t="$1"
    wanted f2_mixed    && f2_mixed "$t"
    wanted f2_hookrace && f2_hookrace "$t"
    wanted f2_nodouble && f2_nodouble "$t"
    wanted f4_stale    && f4_stale "$t"
    wanted f3_hookfail && f3_hookfail "$t"
    wanted h10_hook_ambiguous && h10_hook_ambiguous "$t"
    wanted h11_hold_persists && h11_hold_persists "$t"
}

[ "$TARGET" = "v2" ]  || [ "$TARGET" = "all" ] && run_target v2
[ "$TARGET" = "wg" ]  || [ "$TARGET" = "all" ] && run_target wg

echo
echo "==================== SUMMARY ===================="
for v in "${VERDICTS[@]}"; do echo "  $v"; done
echo "-------------------------------------------------"
echo "assertions: PASS=$PASS FAIL=$FAIL"
echo "full log: $RESULTS_LOG"
echo "actor responses: $RESPDIR/"
if [ "$FAIL" != "0" ]; then
    # Deadlock forensics: the latest InnoDB deadlock (if any) is the
    # most likely silent killer of an apply transaction — it is caught
    # inside the apply function and never reaches the PHP error log.
    # Needs PROCESS privilege: use the root credentials file.
    echo "--- InnoDB latest deadlock (if any) ---"
    "$MYSQL_CLI" --defaults-file="$SCRATCH_DIR/.my-root.cnf" -N -B -e "SHOW ENGINE INNODB STATUS\G" 2>/dev/null \
        | awk '/LATEST DETECTED DEADLOCK/,/TRANSACTIONS/' | head -60 | sed 's/^/      /'
    echo "RESULT: $FAIL ASSERTION(S) FAILED — see log"
    exit 1
fi
echo "RESULT: ALL SCENARIOS BEHAVED AS SPECIFIED"
exit 0
