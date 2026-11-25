#!/usr/bin/env bash
# run-review8.sh — review #8 (6032791246) P1: stale match binding.
#
# Re-review #8 membantah klaim "known-safe edge" §13.5 desain (review #7):
# klaim "tagihan lama aman karena klaimnya gagal" hanya berlaku bila
# tagihan itu sudah PAID. Bila log berpindah kandidat sementara tagihan
# yang dipilih semula masih UNPAID, apply pra-perbaikan mengklaim id
# tagihan BASI dari baca seleksi (tanpa kunci) dan melunasi tagihan yang
# SALAH. Perbaikan: di bawah kunci baris log, baca ulang ID/jenis/
# nominal/status matched yang segar; revalidasi kelayakan tagihan +
# nominal di bawah kunci baris tagihan; tolak pilihan basi tanpa efek.
#
# Skenario (deterministik — interleaving diproduksi kunci baris lewat
# stale-orchestrator.php, BUKAN timing lomba):
#   f8_probe_sanity  kontrol positif: apply langsung via probe-apply
#                    tanpa gangguan -> tagihan lunas normal (membuktikan
#                    probe + jalur normal bekerja).
#   f8_bill_switch   apply terblokir pada kunci log; binding dialihkan ke
#                    tagihan pelanggan LAIN (nominal sama) selagi tagihan
#                    asal tetap UNPAID -> apply harus MENOLAK: tagihan
#                    asal tetap UNPAID, pelanggan yang salah tidak
#                    terlunasi, tanpa efek samping (poin/saldo/masa).
#   f8_nominal       apply terblokir; total tagihan diubah (simulasi edit
#                    admin) sehingga nominal harapan != amount log ->
#                    apply harus MENOLAK (revalidasi nominal).
#   f8_topup_switch  (wg) cermin f8_bill_switch untuk cabang top-up
#                    saldo mitra: binding dialihkan ke top-up mitra lain
#                    -> tolak; kedua saldo mitra tidak berubah.
#   f8_lockwait      apply terblokir melewati innodb_lock_wait_timeout
#                    (1205) -> rollback penuh, tanpa efek dan tanpa jejak
#                    (apply_note tetap NULL); baris tetap matched +
#                    bisa dicocokkan ulang.
#
# Kontrol negatif: jalankan runner ini pada kode pra-perbaikan (apply
# versi review #7) — f8_bill_switch / f8_nominal / f8_topup_switch
# HARUS GAGAL (tagihan basi ikut lunas), membuktikan tes mendeteksi
# celah, bukan sekadar lolos.
#
# Entry-path note (deliberate): apply dijalankan lewat probe-apply.php,
# yang memanggil fungsi apply ASLI (qris_apply_payment /
# paylist_apply_payment) — fungsi yang sama dengan jalur notify dan
# re-match. Pengalihan binding dilakukan orkestrator dengan UPDATE atas
# kolom yang sama dengan yang ditulis transisi re-match (matched_id_*
# + status tetap 'matched'); pemicu realistisnya: admin mengubah total
# tagihan asal (keluar dari kandidasi) lalu operator me-re-match ke
# kandidat tunggal yang tersisa — apply yang sedang berjalan membaca
# seleksi sebelum pengalihan itu. Tes ini menguji kontrak apply ("bila
# binding berubah sejak seleksi, tolak"), bukan bagaimana binding itu
# berubah.
#
# Every verdict is computed from SCRATCH TABLE CONTENTS, not HTTP alone.
# Usage: ./run-review8.sh [--target v2|wg|all] [--only SCEN]
# Env:   SCRATCH_DIR (default /home/warganet/paylist-contract-c2)
# Per-actor responses are kept under
# $SCRATCH_DIR/results/run-review8-$RUN_ID/ (never in the mktemp dir,
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
case "$ONLY" in ""|f8_probe_sanity|f8_bill_switch|f8_nominal|f8_topup_switch|f8_lockwait) ;; *) echo "--only must be one of f8_probe_sanity f8_bill_switch f8_nominal f8_topup_switch f8_lockwait" >&2; exit 2 ;; esac
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

WORKDIR="$(mktemp -d /tmp/contract-run-review8.XXXXXX)"
trap 'rm -rf "$WORKDIR"' EXIT
mkdir -p "$SCRATCH_DIR/results"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
RESULTS_LOG="$SCRATCH_DIR/results/run-review8-$RUN_ID.log"
RESPDIR="$SCRATCH_DIR/results/run-review8-$RUN_ID"
mkdir -p "$RESPDIR"
exec > >(tee -a "$RESULTS_LOG") 2>&1

PASS=0; FAIL=0
declare -a VERDICTS=()

say()  { echo "[review8 $(date +%H:%M:%S)] $*"; }
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

# --- target mapping (same as run-contract.sh) -------------------------------
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

# --- seeds ------------------------------------------------------------------
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
seed_mitra_wg() { # id_mitra nama no_hp saldo refcode
    sql wg "INSERT INTO mitra (id_mitra, nama_mitra, no_hp, email, alamat, bank, no_rek, saldo, refcode) VALUES ($1, '$2', '$3', 'mitra@uji.test', 'Uji', 'Uji', '123', $4, '$5');"
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
AMT_TWIN="$(expected 99900 223)"  # 100823 (same nominal, other total+kode)
AMT_T1="$(expected 50000 101)"    # 50451 (top-up mitra 1)
AMT_T2="$(expected 49900 201)"    # 50451 (top-up mitra 2, same nominal)
AMT_A_NEW="$(expected 100500 123)" # 101327 (nominal sesudah total diubah)

evidence() { # dump the tables a scenario's verdict rests on
    local t="$1" log bill mbc
    log="$(log_of "$t")"; bill="$(bill_of "$t")"; mbc="$(matched_bill_col_of "$t")"
    note "-- $t evidence: $log"
    sql_h "$t" "SELECT id, dedupe_key, match_status, matched_id_pelanggan, $mbc AS matched_bill, (applied_at IS NOT NULL) AS applied, LEFT(COALESCE(apply_note,''), 120) AS note FROM $log ORDER BY id;" | sed 's/^/      /'
    note "-- $t evidence: $bill + pemasukan + pelanggan"
    sql_h "$t" "SELECT id, id_pelanggan, status FROM $bill ORDER BY id;" | sed 's/^/      /'
    sql_h "$t" "SELECT COUNT(*) AS pemasukan_rows FROM pemasukan; SELECT id_pelanggan, status_pembayaran, masa_aktif, poin FROM pelanggan ORDER BY id_pelanggan;" | sed 's/^/      /'
}

# --- F8: positive control — plain apply through the probe --------------------
f8_probe_sanity() {
    local t="$1" key="f8s-$t-1" before_fail="$FAIL"
    say "F8-probe-sanity [$t] plain apply via probe-apply.php (no interference)"
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
    assert_eq "F8-sanity [$t] setup: log matched to bill" "$billid" "$(sql "$t" "SELECT $(matched_bill_col_of "$t") FROM $(log_of "$t") WHERE id=$logid;")"
    local rr
    rr="$(probe_apply_post "$t" 1 "$logid")"
    note "probe-apply response: $(printf '%s' "$rr" | head -c 400)"
    assert_eq "F8-sanity [$t] probe ok" "True" "$(jget "d.get('ok')" "$rr")"
    assert_eq "F8-sanity [$t] apply applied=true" "True" "$(jget "(d.get('apply') or {}).get('applied')" "$rr")"
    assert_eq "F8-sanity [$t] bill PAID" "PAID" "$(sql "$t" "SELECT status FROM $(bill_of "$t") WHERE id=$billid;")"
    assert_eq "F8-sanity [$t] pemasukan rows (exactly one)" "1" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
    assert_eq "F8-sanity [$t] log applied_at set" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE id=$logid AND applied_at IS NOT NULL;")"
    assert_eq "F8-sanity [$t] customer masa_aktif 0->30" "30" "$(sql "$t" "SELECT masa_aktif FROM pelanggan WHERE id_pelanggan=900001;")"
    assert_eq "F8-sanity [$t] customer poin 0->10 (UNPAID)" "10" "$(sql "$t" "SELECT poin FROM pelanggan WHERE id_pelanggan=900001;")"
    evidence "$t"
    [ "$FAIL" = "$before_fail" ] && verdict "F8-probe-sanity [$t] plain apply" "PASS (probe drives the real apply; normal settlement unchanged)" \
        || verdict "F8-probe-sanity [$t] plain apply" "FAIL"
}

# --- F8: binding switched to another customer's bill mid-apply ---------------
f8_bill_switch() {
    local t="$1" key="f8b-$t-1" before_fail="$FAIL"
    say "F8-bill-switch [$t] binding switched to another customer's bill while the origin bill stays UNPAID"
    reset_tgt "$t"
    if [ "$t" = "v2" ]; then
        seed_customer_v2 900001 UNPAID 0
        seed_customer_v2 900002 UNPAID 0
        seed_proforma 910001 900001 100000 123 PRO-TEST-910001
        seed_proforma 910002 900002 99900 223 PRO-TEST-910002
        seed_log_v2 "$key" "$AMT_A" 900001 910001
    else
        seed_customer_wg 900001 UNPAID 0
        seed_customer_wg 900002 UNPAID 0
        seed_payment 920001 900001 100000 123 TRX-TEST-920001
        seed_payment 920002 900002 99900 223 TRX-TEST-920002
        seed_log_wg "$key" "$AMT_A" 900001 920001
    fi
    local billA=$([ "$t" = "v2" ] && echo 910001 || echo 920001)
    local billB=$([ "$t" = "v2" ] && echo 910002 || echo 920002)
    assert_eq "F8-bill-switch [$t] twin nominal sanity (B is a genuine candidate)" "$AMT_A" "$AMT_TWIN"
    local logid
    logid="$(sql "$t" "SELECT id FROM $(log_of "$t") WHERE dedupe_key='$key';")"
    assert_eq "F8-bill-switch [$t] setup: log matched to bill A" "$billA" "$(sql "$t" "SELECT $(matched_bill_col_of "$t") FROM $(log_of "$t") WHERE id=$logid;")"
    local orch
    orch="$(orchestrate "$t" "f8b-$t" "$logid" bill --switch-bill "$billB" --switch-customer 900002)" || {
        fail "F8-bill-switch [$t] orchestrator failed (see $orch)"
        verdict "F8-bill-switch [$t] stale binding rejected" "FAIL (orchestration)"
        return
    }
    note "orchestrator: $(cat "$orch" | head -c 600)"
    assert_eq "F8-bill-switch [$t] orchestration ok" "True" "$(jget "d.get('ok')" "$(cat "$orch")")"
    assert_eq "F8-bill-switch [$t] apply provably blocked on the lock first" "True" "$(jget "d.get('blocked_detected')" "$(cat "$orch")")"
    assert_eq "F8-bill-switch [$t] probe HTTP 200" "200" "$(jget "d.get('http_code')" "$(cat "$orch")")"
    assert_eq "F8-bill-switch [$t] apply applied=false (rejected)" "False" "$(apply_applied "$orch")"
    assert_like "F8-bill-switch [$t] apply says rejected (binding basi)" "ditolak.*binding basi" "$(apply_text "$orch")"
    assert_eq "F8-bill-switch [$t] origin bill A still UNPAID" "UNPAID" "$(sql "$t" "SELECT status FROM $(bill_of "$t") WHERE id=$billA;")"
    assert_eq "F8-bill-switch [$t] other bill B still UNPAID (not settled either)" "UNPAID" "$(sql "$t" "SELECT status FROM $(bill_of "$t") WHERE id=$billB;")"
    assert_eq "F8-bill-switch [$t] pemasukan rows (none)" "0" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
    assert_eq "F8-bill-switch [$t] customer A masa_aktif unchanged (0)" "0" "$(sql "$t" "SELECT masa_aktif FROM pelanggan WHERE id_pelanggan=900001;")"
    assert_eq "F8-bill-switch [$t] customer A poin unchanged (0)" "0" "$(sql "$t" "SELECT poin FROM pelanggan WHERE id_pelanggan=900001;")"
    assert_eq "F8-bill-switch [$t] customer A still UNPAID" "UNPAID" "$(sql "$t" "SELECT status_pembayaran FROM pelanggan WHERE id_pelanggan=900001;")"
    assert_eq "F8-bill-switch [$t] customer B masa_aktif unchanged (0)" "0" "$(sql "$t" "SELECT masa_aktif FROM pelanggan WHERE id_pelanggan=900002;")"
    assert_eq "F8-bill-switch [$t] customer B poin unchanged (0)" "0" "$(sql "$t" "SELECT poin FROM pelanggan WHERE id_pelanggan=900002;")"
    assert_eq "F8-bill-switch [$t] customer B still UNPAID" "UNPAID" "$(sql "$t" "SELECT status_pembayaran FROM pelanggan WHERE id_pelanggan=900002;")"
    assert_eq "F8-bill-switch [$t] log still matched (fresh binding kept)" "matched" "$(sql "$t" "SELECT match_status FROM $(log_of "$t") WHERE id=$logid;")"
    assert_eq "F8-bill-switch [$t] log still points at fresh bill B" "$billB" "$(sql "$t" "SELECT $(matched_bill_col_of "$t") FROM $(log_of "$t") WHERE id=$logid;")"
    assert_eq "F8-bill-switch [$t] log still unapplied" "0" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE id=$logid AND applied_at IS NOT NULL;")"
    assert_like "F8-bill-switch [$t] rejection recorded in apply_note" "^ditolak \\(binding basi\\)" "$(sql "$t" "SELECT apply_note FROM $(log_of "$t") WHERE id=$logid;")"
    if [ "$t" = "wg" ]; then
        assert_eq "F8-bill-switch [wg] report rows (no WA attempted)" "0" "$(sql wg "SELECT COUNT(*) FROM report;")"
    fi
    evidence "$t"
    [ "$FAIL" = "$before_fail" ] && verdict "F8-bill-switch [$t] stale binding rejected" "PASS (origin UNPAID, wrong customer not settled, no side effects, rejection noted)" \
        || verdict "F8-bill-switch [$t] stale binding rejected" "FAIL"
}

# --- F8: bill total changed mid-apply (nominal revalidation) -----------------
f8_nominal() {
    local t="$1" key="f8n-$t-1" before_fail="$FAIL"
    say "F8-nominal [$t] bill total changed mid-apply (expected nominal no longer matches the log amount)"
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
    assert_eq "F8-nominal [$t] new nominal sanity (differs from log amount)" "$AMT_A_NEW" "$(expected 100500 123)"
    local logid
    logid="$(sql "$t" "SELECT id FROM $(log_of "$t") WHERE dedupe_key='$key';")"
    local orch
    orch="$(orchestrate "$t" "f8n-$t" "$logid" nominal --bill-id "$billid" --switch-total 100500)" || {
        fail "F8-nominal [$t] orchestrator failed (see $orch)"
        verdict "F8-nominal [$t] nominal change rejected" "FAIL (orchestration)"
        return
    }
    note "orchestrator: $(cat "$orch" | head -c 600)"
    assert_eq "F8-nominal [$t] orchestration ok" "True" "$(jget "d.get('ok')" "$(cat "$orch")")"
    assert_eq "F8-nominal [$t] apply provably blocked on the lock first" "True" "$(jget "d.get('blocked_detected')" "$(cat "$orch")")"
    assert_eq "F8-nominal [$t] apply applied=false (rejected)" "False" "$(apply_applied "$orch")"
    assert_like "F8-nominal [$t] apply names the nominal mismatch" "nominal harapan" "$(apply_text "$orch")"
    assert_eq "F8-nominal [$t] bill still UNPAID" "UNPAID" "$(sql "$t" "SELECT status FROM $(bill_of "$t") WHERE id=$billid;")"
    assert_eq "F8-nominal [$t] pemasukan rows (none)" "0" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
    assert_eq "F8-nominal [$t] customer masa_aktif unchanged (0)" "0" "$(sql "$t" "SELECT masa_aktif FROM pelanggan WHERE id_pelanggan=900001;")"
    assert_eq "F8-nominal [$t] customer poin unchanged (0)" "0" "$(sql "$t" "SELECT poin FROM pelanggan WHERE id_pelanggan=900001;")"
    assert_eq "F8-nominal [$t] log still matched to the same bill" "$billid" "$(sql "$t" "SELECT $(matched_bill_col_of "$t") FROM $(log_of "$t") WHERE id=$logid;")"
    assert_eq "F8-nominal [$t] log still unapplied" "0" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE id=$logid AND applied_at IS NOT NULL;")"
    assert_like "F8-nominal [$t] rejection recorded in apply_note" "^ditolak \\(binding basi\\).*nominal harapan" "$(sql "$t" "SELECT apply_note FROM $(log_of "$t") WHERE id=$logid;")"
    evidence "$t"
    [ "$FAIL" = "$before_fail" ] && verdict "F8-nominal [$t] nominal change rejected" "PASS (bill UNPAID, no side effects, nominal mismatch noted)" \
        || verdict "F8-nominal [$t] nominal change rejected" "FAIL"
}

# --- F8: top-up binding switched to another mitra mid-apply (wg only) --------
f8_topup_switch() {
    local t="wg" key="f8u-wg-1" before_fail="$FAIL"
    say "F8-topup-switch [wg] top-up binding switched to another mitra while the origin top-up stays UNPAID"
    reset_tgt wg
    seed_mitra_wg 990001 'Mitra Uji Satu' 6281111111111 10000.00 UJI001
    seed_mitra_wg 990002 'Mitra Uji Dua' 6282222222222 20000.00 UJI002
    seed_payment 920101 6281111111111 50000 101 TRX-TOPUP-920101 TopUp-Saldo
    seed_payment 920102 6282222222222 49900 201 TRX-TOPUP-920102 TopUp-Saldo
    seed_log_wg "$key" "$AMT_T1" 6281111111111 920101
    assert_eq "F8-topup-switch [wg] twin nominal sanity (T2 is a genuine candidate)" "$AMT_T1" "$AMT_T2"
    local logid
    logid="$(sql wg "SELECT id FROM paylist_log WHERE dedupe_key='$key';")"
    assert_eq "F8-topup-switch [wg] setup: log matched to top-up T1" "920101" "$(sql wg "SELECT matched_id_payment FROM paylist_log WHERE id=$logid;")"
    local orch
    orch="$(orchestrate wg "f8u-wg" "$logid" bill --switch-bill 920102 --switch-customer 6282222222222)" || {
        fail "F8-topup-switch [wg] orchestrator failed (see $orch)"
        verdict "F8-topup-switch [wg] stale top-up rejected" "FAIL (orchestration)"
        return
    }
    note "orchestrator: $(cat "$orch" | head -c 600)"
    assert_eq "F8-topup-switch [wg] orchestration ok" "True" "$(jget "d.get('ok')" "$(cat "$orch")")"
    assert_eq "F8-topup-switch [wg] apply provably blocked on the lock first" "True" "$(jget "d.get('blocked_detected')" "$(cat "$orch")")"
    assert_eq "F8-topup-switch [wg] apply applied=false (rejected)" "False" "$(apply_applied "$orch")"
    assert_like "F8-topup-switch [wg] apply says rejected (binding basi)" "ditolak.*binding basi" "$(apply_text "$orch")"
    assert_eq "F8-topup-switch [wg] origin top-up T1 still UNPAID" "UNPAID" "$(sql wg "SELECT status FROM payment WHERE id=920101;")"
    assert_eq "F8-topup-switch [wg] other top-up T2 still UNPAID" "UNPAID" "$(sql wg "SELECT status FROM payment WHERE id=920102;")"
    assert_eq "F8-topup-switch [wg] mitra M1 saldo unchanged (10000.00)" "10000.00" "$(sql wg "SELECT saldo FROM mitra WHERE id_mitra=990001;")"
    assert_eq "F8-topup-switch [wg] mitra M2 saldo unchanged (20000.00)" "20000.00" "$(sql wg "SELECT saldo FROM mitra WHERE id_mitra=990002;")"
    assert_eq "F8-topup-switch [wg] saldo_history rows (none)" "0" "$(sql wg "SELECT COUNT(*) FROM saldo_history;")"
    assert_eq "F8-topup-switch [wg] pemasukan_hotspot rows (none)" "0" "$(sql wg "SELECT COUNT(*) FROM pemasukan_hotspot;")"
    assert_eq "F8-topup-switch [wg] pemasukan rows (none)" "0" "$(sql wg "SELECT COUNT(*) FROM pemasukan;")"
    assert_eq "F8-topup-switch [wg] log still matched (fresh binding kept)" "matched" "$(sql wg "SELECT match_status FROM paylist_log WHERE id=$logid;")"
    assert_eq "F8-topup-switch [wg] log still points at fresh top-up T2" "920102" "$(sql wg "SELECT matched_id_payment FROM paylist_log WHERE id=$logid;")"
    assert_eq "F8-topup-switch [wg] log still unapplied" "0" "$(sql wg "SELECT COUNT(*) FROM paylist_log WHERE id=$logid AND applied_at IS NOT NULL;")"
    assert_like "F8-topup-switch [wg] rejection recorded in apply_note" "^ditolak \\(binding basi\\)" "$(sql wg "SELECT apply_note FROM paylist_log WHERE id=$logid;")"
    assert_eq "F8-topup-switch [wg] report rows (none)" "0" "$(sql wg "SELECT COUNT(*) FROM report;")"
    evidence wg
    [ "$FAIL" = "$before_fail" ] && verdict "F8-topup-switch [wg] stale top-up rejected" "PASS (both top-ups UNPAID, both mitra saldos unchanged, rejection noted)" \
        || verdict "F8-topup-switch [wg] stale top-up rejected" "FAIL"
}

# --- F8: lock wait timeout (1205) -> full rollback, no trace -----------------
f8_lockwait() {
    local t="$1" key="f8l-$t-1" before_fail="$FAIL"
    say "F8-lockwait [$t] apply blocked past innodb_lock_wait_timeout (1205) -> rollback, no effects, no trace"
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
    local orch
    orch="$(orchestrate "$t" "f8l-$t" "$logid" lockwait --hold-seconds 5 --probe-lock-wait-timeout 2)" || {
        fail "F8-lockwait [$t] orchestrator failed (see $orch)"
        verdict "F8-lockwait [$t] 1205 rollback" "FAIL (orchestration)"
        return
    }
    note "orchestrator: $(cat "$orch" | head -c 600)"
    assert_eq "F8-lockwait [$t] orchestration ok" "True" "$(jget "d.get('ok')" "$(cat "$orch")")"
    assert_eq "F8-lockwait [$t] apply provably blocked on the lock first" "True" "$(jget "d.get('blocked_detected')" "$(cat "$orch")")"
    assert_eq "F8-lockwait [$t] apply applied=false" "False" "$(apply_applied "$orch")"
    assert_like "F8-lockwait [$t] apply reports lock wait timeout (1205)" "Lock wait timeout" "$(apply_text "$orch")"
    assert_eq "F8-lockwait [$t] bill still UNPAID" "UNPAID" "$(sql "$t" "SELECT status FROM $(bill_of "$t") WHERE id=$billid;")"
    assert_eq "F8-lockwait [$t] pemasukan rows (none)" "0" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
    assert_eq "F8-lockwait [$t] customer masa_aktif unchanged (0)" "0" "$(sql "$t" "SELECT masa_aktif FROM pelanggan WHERE id_pelanggan=900001;")"
    assert_eq "F8-lockwait [$t] customer poin unchanged (0)" "0" "$(sql "$t" "SELECT poin FROM pelanggan WHERE id_pelanggan=900001;")"
    assert_eq "F8-lockwait [$t] log still matched to the same bill" "$billid" "$(sql "$t" "SELECT $(matched_bill_col_of "$t") FROM $(log_of "$t") WHERE id=$logid;")"
    assert_eq "F8-lockwait [$t] log still unapplied" "0" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE id=$logid AND applied_at IS NOT NULL;")"
    assert_eq "F8-lockwait [$t] apply_note still NULL (rollback left no trace)" "" "$(sql "$t" "SELECT COALESCE(apply_note,'') FROM $(log_of "$t") WHERE id=$logid;")"
    evidence "$t"
    [ "$FAIL" = "$before_fail" ] && verdict "F8-lockwait [$t] 1205 rollback" "PASS (1205 -> full rollback, row stays matched + re-matchable, no trace)" \
        || verdict "F8-lockwait [$t] 1205 rollback" "FAIL"
}

# --- main -------------------------------------------------------------------
say "review8 run $RUN_ID (target=$TARGET)"
say "amounts: AMT_A=$AMT_A (100000+fee+123), AMT_TWIN=$AMT_TWIN (99900+fee+223), AMT_T1=$AMT_T1 (50000+fee+101), AMT_T2=$AMT_T2 (49900+fee+201), AMT_A_NEW=$AMT_A_NEW (100500+fee+123)"

run_target() {
    local t="$1"
    wanted f8_probe_sanity && f8_probe_sanity "$t"
    wanted f8_bill_switch  && f8_bill_switch "$t"
    wanted f8_nominal      && f8_nominal "$t"
    wanted f8_lockwait     && f8_lockwait "$t"
}

[ "$TARGET" = "v2" ]  || [ "$TARGET" = "all" ] && run_target v2
[ "$TARGET" = "wg" ]  || [ "$TARGET" = "all" ] && run_target wg
if { [ "$TARGET" = "wg" ] || [ "$TARGET" = "all" ]; } && wanted f8_topup_switch; then
    f8_topup_switch
fi

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
