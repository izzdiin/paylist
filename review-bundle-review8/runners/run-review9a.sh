#!/usr/bin/env bash
# run-review9a.sh — review #9 (6049617576) item (a): points race.
#
# Di jalur apply C2, status pelanggan dibaca TANPA kunci sebelum
# transaksi dan dipakai untuk memberi poin (UNPAID->10, ISOLIR->5,
# SUSPEND->1 — poin mengikuti status SEBELUM pembayaran). Perubahan
# status di tengah apply (mis. cron membalik UNPAID -> ISOLIR saat
# masa_aktif habis) membuat apply memberi poin dari status BASI.
# Perbaikan: kunci baris pelanggan (urutan kunci log -> tagihan ->
# pelanggan) dan baca ulang status di bawah kuncinya sebelum poin
# diturunkan; v2 juga membaca ulang masa_aktif agar jatuhTempo konsisten.
#
# Skenario (deterministik — interleaving diproduksi kunci baris lewat
# stale-orchestrator.php mode=status, BUKAN timing lomba):
#   f9_probe_sanity  kontrol positif: apply langsung via probe-apply
#                    tanpa gangguan -> tagihan lunas normal, poin 10
#                    (UNPAID).
#   f9_points_race   apply terblokir pada kunci log; status pelanggan
#                    dibalik UNPAID -> ISOLIR selagi apply terblokir
#                    (persis UPDATE cron status) -> apply harus MELUNASI
#                    dengan poin 5 (status SEGAR di bawah kunci),
#                    bukan 10 (status pra-kunci).
#
# Kontrol negatif: jalankan runner ini pada kode pra-perbaikan (apply
# versi review #8) — f9_points_race HARUS GAGAL (poin 10, bukan 5),
# membuktikan tes mendeteksi celah, bukan sekadar lolos.
#
# Entry-path note (deliberate): apply dijalankan lewat probe-apply.php,
# yang memanggil fungsi apply ASLI (qris_apply_payment /
# paylist_apply_payment) — fungsi yang sama dengan jalur notify dan
# re-match. Pembalikan status dilakukan orkestrator dengan UPDATE atas
# kolom yang sama dengan yang ditulis cron status
# (pelanggan.status_pembayaran); tes ini menguji kontrak apply ("poin
# mengikuti status di bawah kunci pelanggan"), bukan bagaimana status
# itu berubah.
#
# Every verdict is computed from SCRATCH TABLE CONTENTS, not HTTP alone.
# Usage: ./run-review9a.sh [--target v2|wg|all] [--only SCEN]
# Env:   SCRATCH_DIR (default /home/warganet/paylist-contract-c2)
# Per-actor responses are kept under
# $SCRATCH_DIR/results/run-review9a-$RUN_ID/ (never in the mktemp dir,
# which the EXIT trap deletes) so a failed scenario stays diagnosable.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRATCH_DIR="${SCRATCH_DIR:-/home/warganet/paylist-contract-c2}"
TARGET="all"
ONLY=""
while [ $# -gt 0 ]; do
    case "$1" in
        --target)   TARGET="$2"; shift 2 ;;
        --only)     ONLY="$2"; shift 2 ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
done
case "$TARGET" in v2|wg|all) ;; *) echo "--target must be v2, wg or all" >&2; exit 2 ;; esac
case "$ONLY" in ""|f9_probe_sanity|f9_points_race) ;; *) echo "--only must be one of f9_probe_sanity f9_points_race" >&2; exit 2 ;; esac
wanted() { [ -z "$ONLY" ] || [ "$ONLY" = "$1" ]; }

[ -f "$SCRATCH_DIR/.env" ] || { echo "no $SCRATCH_DIR/.env — run setup-scratch.sh first" >&2; exit 2; }
[ -f "$SCRATCH_DIR/.my-contract.cnf" ] || { echo "no $SCRATCH_DIR/.my-contract.cnf — run setup-scratch.sh first" >&2; exit 2; }
[ -f "$SCRIPT_DIR/stale-orchestrator.php" ] || { echo "no $SCRIPT_DIR/stale-orchestrator.php" >&2; exit 2; }
# shellcheck disable=SC1090
. "$SCRATCH_DIR/.env"
CONTRACT_CNF="$SCRATCH_DIR/.my-contract.cnf"

MYSQL_CLI="$(command -v mariadb || command -v mysql || true)"
[ -n "$MYSQL_CLI" ] || { echo "mariadb/mysql client not found" >&2; exit 2; }
command -v curl >/dev/null  || { echo "curl not found" >&2; exit 2; }
command -v python3 >/dev/null || { echo "python3 not found" >&2; exit 2; }
command -v php >/dev/null    || { echo "php not found" >&2; exit 2; }

WORKDIR="$(mktemp -d /tmp/contract-run-review9a.XXXXXX)"
trap 'rm -rf "$WORKDIR"' EXIT
mkdir -p "$SCRATCH_DIR/results"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
RESULTS_LOG="$SCRATCH_DIR/results/run-review9a-$RUN_ID.log"
RESPDIR="$SCRATCH_DIR/results/run-review9a-$RUN_ID"
mkdir -p "$RESPDIR"
exec > >(tee -a "$RESULTS_LOG") 2>&1

PASS=0; FAIL=0
declare -a VERDICTS=()

say()  { echo "[review9a $(date +%H:%M:%S)] $*"; }
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
verdict() {
    VERDICTS+=("$1: $2")
    echo "  ==> $1: $2"
}

# --- target mapping (same as run-review8.sh) --------------------------------
db_of()   { [ "$1" = "v2" ] && echo paylist_contract_v2 || echo paylist_contract_wg; }
base_of() { [ "$1" = "v2" ] && echo "${V2_HTTP_BASE:-8080}" || echo "${WG_HTTP_BASE:-8090}"; }
path_of() { [ "$1" = "v2" ] && echo bayarQR/notify.php || echo paylist/notify.php; }
log_of()  { [ "$1" = "v2" ] && echo qris_notif_log || echo paylist_log; }
bill_of() { [ "$1" = "v2" ] && echo proforma || echo payment; }
matched_bill_col_of() { [ "$1" = "v2" ] && echo matched_id_proforma || echo matched_id_payment; }
port_for() { echo $(( $(base_of "$1") + ($2 % ${WORKERS:-4}) )); }

sql()  { "$MYSQL_CLI" --defaults-file="$CONTRACT_CNF" -N -B -D "$(db_of "$1")" -e "$2"; }
sql_h() { "$MYSQL_CLI" --defaults-file="$CONTRACT_CNF" -D "$(db_of "$1")" -e "$2"; }

expected() { php -r '$b=(int)$argv[1]; $k=(int)$argv[2]; $bi=(int)ceil($b); echo $bi + (int)ceil($bi*0.007) + $k;' "$1" "$2"; }

probe_apply_post() { # target worker-index log_id -> probe response body
    curl -s -m 60 -X POST "http://127.0.0.1:$(port_for "$1" "$2")/probe-apply.php" \
        -d "log_id=$3"
}

# orchestrate target name log_id mode [extra orchestrator args...]
# Runs stale-orchestrator.php; writes its JSON to $RESPDIR/<name>.json and
# echoes the file path. Returns the orchestrator's exit code.
orchestrate() {
    local t="$1" name="$2" logid="$3" mode="$4"; shift 4
    local out="$RESPDIR/$name.json"
    local probe_url="http://127.0.0.1:$(port_for "$t" 1)/probe-apply.php"
    php "$SCRIPT_DIR/stale-orchestrator.php" \
        --target "$t" --cnf "$SCRATCH_DIR/.my-root.cnf" --db-name "$(db_of "$t")" \
        --log-id "$logid" --probe-url "$probe_url" --mode "$mode" \
        --resp-file "$RESPDIR/$name.apply.json" --code-file "$RESPDIR/$name.httpcode" \
        "$@" > "$out" 2> "$RESPDIR/$name.stderr"
    local rc=$?
    if [ $rc -ne 0 ]; then
        # Notes go to stderr: this function's stdout is captured as the
        # output-file path by the caller.
        note "orchestrator FAILED (rc=$rc): $(cat "$RESPDIR/$name.stderr" | head -c 500)" >&2
        note "orchestrator stdout: $(cat "$out" | head -c 500)" >&2
    fi
    echo "$out"
    return $rc
}

# apply_text jsonfile -> the apply's human-readable outcome text:
# v2 steps[] joined, wg single note. ("" when the probe errored.)
apply_text() {
    python3 -c "
import sys, json
d = json.load(open(sys.argv[1]))
a = d.get('apply') or {}
steps = a.get('steps')
if isinstance(steps, list):
    print('; '.join(str(s) for s in steps))
else:
    print(a.get('note') or '')
" "$1" 2>/dev/null || echo ""
}

apply_applied() { # jsonfile -> True/False/""
    python3 -c "
import sys, json
d = json.load(open(sys.argv[1]))
print((d.get('apply') or {}).get('applied'))
" "$1" 2>/dev/null || echo ""
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
        pcode="$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$(base_of "$t")/probe-apply.php" -d 'log_id=0' || true)"
        [ "$pcode" = "400" ] || { echo "probe-apply.php missing for $t (HTTP $pcode) — re-run patched setup-scratch.sh" >&2; exit 2; }
    fi
done
# The status mode needs the review #9 orchestrator extension.
grep -q "mode=status" "$SCRIPT_DIR/stale-orchestrator.php" \
    || { echo "stale-orchestrator.php lacks mode=status — deploy the review9a version first" >&2; exit 2; }

# --- seeds (same as run-review8.sh) -----------------------------------------
reset_tgt() { # wipe transactional tables (FK-safe order), keep config/refdata
    local db="$1"
    if [ "$db" = "v2" ]; then
        sql v2 "DELETE FROM pemasukan; DELETE FROM qris_notif_log; DELETE FROM proforma; DELETE FROM pelanggan;"
    else
        sql wg "DELETE FROM pemasukan_hotspot; DELETE FROM pemasukan; DELETE FROM saldo_history; DELETE FROM paylist_log; DELETE FROM report; DELETE FROM payment; DELETE FROM mitra; DELETE FROM pelanggan;"
        sql wg "INSERT IGNORE INTO pppoe (name, local_address, remote_address, rate_limit, parent_queue, only_one, masa_aktif) VALUES ('Test 10Mbps', '10.0.0.1', '10.0.0.2', '', '', 'yes', 30);"
    fi
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

# Direct matched-log seed: reproduces EXACTLY the row state the matcher
# writes for a single-candidate payment (match_status='matched',
# matched_* set, applied_at NULL). Seeding via POST is impossible here:
# the notify path auto-applies a single candidate immediately, and this
# suite tests the APPLY function, not the matcher — the matched state is
# the precondition, not the thing under test.
seed_log_v2() { # dedupe_key amount cust bill
    sql v2 "INSERT INTO qris_notif_log (device, source_pkg, amount, raw_title, raw_text, notif_time, dedupe_key, match_status, matched_id_pelanggan, matched_id_proforma, applied_at, payload_json) VALUES ('harness', 'com.netter.paylist', $2, 'QRIS', 'Pembayaran QRIS', '2026-11-22 18:00:00', '$1', 'matched', $3, $4, NULL, '{}');"
}
seed_log_wg() { # dedupe_key amount cust bill
    sql wg "INSERT INTO paylist_log (device, source_pkg, amount, raw_title, raw_text, notif_time, dedupe_key, match_status, matched_id_pelanggan, matched_id_payment, applied_at, payload_json) VALUES ('harness', 'com.netter.paylist', $2, 'QRIS', 'Pembayaran QRIS', '2026-11-22 18:00:00', '$1', 'matched', $3, $4, NULL, '{}');"
}

AMT_A="$(expected 100000 123)"    # 100823

evidence() { # dump the tables a scenario's verdict rests on
    local t="$1" log bill mbc
    log="$(log_of "$t")"; bill="$(bill_of "$t")"; mbc="$(matched_bill_col_of "$t")"
    note "-- $t evidence: $log"
    sql_h "$t" "SELECT id, dedupe_key, match_status, matched_id_pelanggan, $mbc AS matched_bill, (applied_at IS NOT NULL) AS applied, LEFT(COALESCE(apply_note,''), 120) AS note FROM $log ORDER BY id;" | sed 's/^/      /'
    note "-- $t evidence: $bill + pemasukan + pelanggan"
    sql_h "$t" "SELECT id, id_pelanggan, status FROM $bill ORDER BY id;" | sed 's/^/      /'
    sql_h "$t" "SELECT COUNT(*) AS pemasukan_rows FROM pemasukan; SELECT id_pelanggan, status_pembayaran, masa_aktif, poin FROM pelanggan ORDER BY id_pelanggan;" | sed 's/^/      /'
}

# --- F9: positive control — plain apply through the probe --------------------
f9_probe_sanity() {
    local t="$1" key="f9s-$t-1" before_fail="$FAIL"
    say "F9-probe-sanity [$t] plain apply via probe-apply.php (no interference)"
    reset_tgt "$t"
    if [ "$t" = "v2" ]; then
        seed_customer_v2 900001 UNPAID 0
        seed_proforma 910001 900001 100000 123 PRO-TEST-910001
        seed_log_v2 "$key" "$AMT_A" 900001 910001
    else
        seed_customer_wg 900001 UNPAID 0
        seed_payment 920001 900001 100000 123 TRX-TEST-920001
        seed_log_wg "$key" "$AMT_A" 900001 920001
    fi
    local billid=$([ "$t" = "v2" ] && echo 910001 || echo 920001)
    local logid
    logid="$(sql "$t" "SELECT id FROM $(log_of "$t") WHERE dedupe_key='$key';")"
    assert_eq "F9-sanity [$t] setup: log matched to bill" "$billid" "$(sql "$t" "SELECT $(matched_bill_col_of "$t") FROM $(log_of "$t") WHERE id=$logid;")"
    local rr
    rr="$(probe_apply_post "$t" 1 "$logid")"
    note "probe-apply response: $(printf '%s' "$rr" | head -c 400)"
    assert_eq "F9-sanity [$t] probe ok" "True" "$(jget "d.get('ok')" "$rr")"
    assert_eq "F9-sanity [$t] apply applied=true" "True" "$(jget "(d.get('apply') or {}).get('applied')" "$rr")"
    assert_eq "F9-sanity [$t] bill PAID" "PAID" "$(sql "$t" "SELECT status FROM $(bill_of "$t") WHERE id=$billid;")"
    assert_eq "F9-sanity [$t] pemasukan rows (exactly one)" "1" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
    assert_eq "F9-sanity [$t] log applied_at set" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE id=$logid AND applied_at IS NOT NULL;")"
    assert_eq "F9-sanity [$t] customer status PAID" "PAID" "$(sql "$t" "SELECT status_pembayaran FROM pelanggan WHERE id_pelanggan=900001;")"
    assert_eq "F9-sanity [$t] customer masa_aktif 0->30" "30" "$(sql "$t" "SELECT masa_aktif FROM pelanggan WHERE id_pelanggan=900001;")"
    assert_eq "F9-sanity [$t] customer poin 0->10 (UNPAID, undisturbed)" "10" "$(sql "$t" "SELECT poin FROM pelanggan WHERE id_pelanggan=900001;")"
    evidence "$t"
    [ "$FAIL" = "$before_fail" ] && verdict "F9-probe-sanity [$t] plain apply" "PASS (probe drives the real apply; normal settlement unchanged)" \
        || verdict "F9-probe-sanity [$t] plain apply" "FAIL"
}

# --- F9: customer status flipped UNPAID -> ISOLIR mid-apply ------------------
f9_points_race() {
    local t="$1" key="f9p-$t-1" before_fail="$FAIL"
    say "F9-points-race [$t] status flipped UNPAID -> ISOLIR while apply is blocked on the log lock (cron flip)"
    reset_tgt "$t"
    if [ "$t" = "v2" ]; then
        seed_customer_v2 900001 UNPAID 0
        seed_proforma 910001 900001 100000 123 PRO-TEST-910001
        seed_log_v2 "$key" "$AMT_A" 900001 910001
    else
        seed_customer_wg 900001 UNPAID 0
        seed_payment 920001 900001 100000 123 TRX-TEST-920001
        seed_log_wg "$key" "$AMT_A" 900001 920001
    fi
    local billid=$([ "$t" = "v2" ] && echo 910001 || echo 920001)
    local logid
    logid="$(sql "$t" "SELECT id FROM $(log_of "$t") WHERE dedupe_key='$key';")"
    assert_eq "F9-points-race [$t] setup: log matched to bill" "$billid" "$(sql "$t" "SELECT $(matched_bill_col_of "$t") FROM $(log_of "$t") WHERE id=$logid;")"
    assert_eq "F9-points-race [$t] setup: customer UNPAID before the flip" "UNPAID" "$(sql "$t" "SELECT status_pembayaran FROM pelanggan WHERE id_pelanggan=900001;")"
    local orch
    orch="$(orchestrate "$t" "f9p-$t" "$logid" status --switch-customer 900001 --switch-status ISOLIR)" || {
        fail "F9-points-race [$t] orchestrator failed (see $orch)"
        verdict "F9-points-race [$t] points from locked status" "FAIL (orchestration)"
        return
    }
    note "orchestrator: $(cat "$orch" | head -c 600)"
    assert_eq "F9-points-race [$t] orchestration ok" "True" "$(jget "d.get('ok')" "$(cat "$orch")")"
    assert_eq "F9-points-race [$t] apply provably blocked on the lock first" "True" "$(jget "d.get('blocked_detected')" "$(cat "$orch")")"
    assert_eq "F9-points-race [$t] probe HTTP 200" "200" "$(jget "d.get('http_code')" "$(cat "$orch")")"
    assert_eq "F9-points-race [$t] apply applied=true (settles; only the points basis changes)" "True" "$(apply_applied "$orch")"
    if [ "$t" = "v2" ]; then
        assert_like "F9-points-race [$t] apply names the fresh status (ISOLIR)" "status lama ISOLIR" "$(apply_text "$orch")"
    fi
    assert_eq "F9-points-race [$t] bill PAID" "PAID" "$(sql "$t" "SELECT status FROM $(bill_of "$t") WHERE id=$billid;")"
    assert_eq "F9-points-race [$t] pemasukan rows (exactly one)" "1" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
    assert_eq "F9-points-race [$t] log applied_at set" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE id=$logid AND applied_at IS NOT NULL;")"
    assert_eq "F9-points-race [$t] log still matched to the same bill" "$billid" "$(sql "$t" "SELECT $(matched_bill_col_of "$t") FROM $(log_of "$t") WHERE id=$logid;")"
    assert_eq "F9-points-race [$t] customer status PAID" "PAID" "$(sql "$t" "SELECT status_pembayaran FROM pelanggan WHERE id_pelanggan=900001;")"
    assert_eq "F9-points-race [$t] customer masa_aktif 0->30" "30" "$(sql "$t" "SELECT masa_aktif FROM pelanggan WHERE id_pelanggan=900001;")"
    assert_eq "F9-points-race [$t] customer poin 0->5 (ISOLIR under lock, NOT 10 from stale UNPAID)" "5" "$(sql "$t" "SELECT poin FROM pelanggan WHERE id_pelanggan=900001;")"
    evidence "$t"
    [ "$FAIL" = "$before_fail" ] && verdict "F9-points-race [$t] points from locked status" "PASS (settled with 5 points from the fresh ISOLIR status)" \
        || verdict "F9-points-race [$t] points from locked status" "FAIL"
}

# --- main -------------------------------------------------------------------
say "review9a run $RUN_ID (target=$TARGET)"
say "amounts: AMT_A=$AMT_A (100000+fee+123); poin_status UNPAID=10 ISOLIR=5 SUSPEND=1"

run_target() {
    local t="$1"
    wanted f9_probe_sanity && f9_probe_sanity "$t"
    wanted f9_points_race  && f9_points_race "$t"
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
