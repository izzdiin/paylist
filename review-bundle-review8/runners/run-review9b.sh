#!/usr/bin/env bash
# run-review9b.sh — review #9 (6049617576) item (b): forced-deadlock test.
#
# Review #9: "Zero observed 1213 is not a forced-deadlock test;
# demonstrate rollback and recoverable state." Tes ini MEMAKSA deadlock
# 1213 secara deterministik dan membuktikan: korban mendapat 1213,
# rollback PENUH tanpa efek, dan state tetap recoverable.
#
# Skenario (deterministik — interleaving diproduksi kunci baris lewat
# stale-orchestrator.php mode=deadlock + deadlock-peer.php, BUKAN
# timing/sleep):
#   apply: log -> tagihan -> pelanggan (urutan seragam semua apply)
#   peer:  pelanggan -> tagihan (persis v2 task/servicechange.php:
#          UPDATE pelanggan dulu, lalu UPDATE proforma — inversi yang
#          sudah ada di luar C2; untuk wg bentuk yang sama sebagai SQL
#          khusus-tes, karena servicechange wg hanya menyentuh pelanggan)
#   Orkestrasi: T mengunci log; peer memegang kunci pelanggan
#   (ready-file + trx_id); apply dijalankan dan TERBUKTI menunggu kunci
#   log T; T commit; apply maju (log, tagihan) lalu TERBUKTI menunggu
#   kunci pelanggan peer; baru peer meminta kunci tagihan -> siklus
#   lengkap -> InnoDB mendeteksi deadlock dan mengembalikan 1213 ke
#   korban.
#
#   Bobot korban (temuan empiris 25 Nov 2026, kontrol pertama):
#   InnoDB memilih korban berdasarkan BOBOT (jumlah kunci yang
#   dipegang), bukan jumlah baris yang diubah. Peer servicechange asli
#   (1 kunci pelanggan) LEBIH RINGAN dari apply (kunci log + tagihan),
#   sehingga dalam tabrakan nyata PEER-lah yang jadi korban 1213 dan
#   apply lunas normal (pembayaran menang). Untuk MEMAKSA apply menjadi
#   korban (kasus yang diminta review #9), peer diberi bobot tambahan
#   --extra-customers (4 pelanggan dummy ikut dikunci sebelum go);
#   urutan kunci peer tetap pelanggan dulu lalu tagihan.
#
# Skenario per target (v2 + wg):
#   f9d_probe_sanity     kontrol positif: apply langsung tanpa gangguan
#                        -> lunas normal, poin 10 (UNPAID).
#   f9d_lockwait_control kontrol negatif: peer memegang kunci pelanggan
#                        tapi TIDAK meminta kunci tagihan -> apply hanya
#                        menunggu, lalu lunas normal, TANPA deadlock
#                        (membuktikan deadlock berasal dari langkah kunci
#                        tagihan kedua, bukan dari kunci pelanggan saja).
#   f9d_deadlock         apply korban 1213 -> applied=false, rollback
#                        penuh (tagihan tetap UNPAID, pemasukan 0,
#                        status/masa/poin pelanggan utuh, log tetap
#                        matched + applied_at NULL + apply_note NULL),
#                        peer survivor commit (layanan baru), lalu apply
#                        ulang atas log yang sama BERHASIL (recoverable).
#
# Bukti 1213 (tiga lapis):
#   (1) teks apply sendiri: 'Deadlock found when trying to get lock'
#       (v2 di steps, wg di note) -- pada PHP 8.4.27 ini mysqli melempar
#       mysqli_sql_exception secara default (tanpa mysqli_report di
#       jalur apply manapun; dibuktikan tes CLI mandiri 25 Nov 2026),
#       jadi pesan 1213 sampai ke respons apply; TIDAK ada warning PHP
#       ke log worker (jumlah "Deadlock found" di log worker dicatat
#       saja, tetap 0).
#   (2) LATEST DETECTED DEADLOCK dari SHOW ENGINE INNODB STATUS,
#       ditangkap orkestrator ke $RESPDIR/<nama>.innodb-deadlock.txt
#       (InnoDB melaporkan satu rollback; "(N)" di "WE ROLL BACK TRANSACTION
#       (N)" adalah teks diagnostik yang nilainya bergantung versi -- 10.5.29
#       dan 11.8.6 menulis (1), 10.11.19 menulis (2) untuk deadlock yang sama
#       persis -- dan bukan indeks korban maupun jumlah yang di-rollback;
#       korban diidentifikasi dari teks apply + peer committed + cek rollback
#       baris-per-baris + re-apply yang berhasil, bukan nomor baris InnoDB).
#   (3) respons apply applied=false + 'GAGAL transaksi, rollback' (v2)
#       / 'gagal:' (wg), dan peer committed (errno 0).
#
# Entry-path note (deliberate): apply dijalankan lewat probe-apply.php,
# yang memanggil fungsi apply ASLI (qris_apply_payment /
# paylist_apply_payment) — fungsi yang sama dengan jalur notify dan
# re-match. Peer adalah SQL khusus-tes yang meniru urutan kunci
# servicechange (bukan servicechange.php itu sendiri, yang butuh sesi
# admin + efek WA Fonnte); yang diuji adalah kontrak konkurensi apply
# ("tabrakan inversi berakhir 1213 -> rollback -> applied=false,
# baris tetap matched + bisa dicocokkan ulang").
#
# Every verdict is computed from SCRATCH TABLE CONTENTS, not HTTP alone.
# Usage: ./run-review9b.sh [--target v2|wg|all] [--only SCEN]
# Env:   SCRATCH_DIR (default /home/warganet/paylist-contract-c2)
# Per-actor responses are kept under
# $SCRATCH_DIR/results/run-review9b-$RUN_ID/ (never in the mktemp dir,
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
case "$ONLY" in ""|f9d_probe_sanity|f9d_lockwait_control|f9d_deadlock) ;; *) echo "--only must be one of f9d_probe_sanity f9d_lockwait_control f9d_deadlock" >&2; exit 2 ;; esac
wanted() { [ -z "$ONLY" ] || [ "$ONLY" = "$1" ]; }

[ -f "$SCRATCH_DIR/.env" ] || { echo "no $SCRATCH_DIR/.env — run setup-scratch.sh first" >&2; exit 2; }
[ -f "$SCRATCH_DIR/.my-contract.cnf" ] || { echo "no $SCRATCH_DIR/.my-contract.cnf — run setup-scratch.sh first" >&2; exit 2; }
[ -f "$SCRIPT_DIR/stale-orchestrator.php" ] || { echo "no $SCRIPT_DIR/stale-orchestrator.php" >&2; exit 2; }
[ -f "$SCRIPT_DIR/deadlock-peer.php" ] || { echo "no $SCRIPT_DIR/deadlock-peer.php — deploy the review9b peer first" >&2; exit 2; }
# shellcheck disable=SC1090
. "$SCRATCH_DIR/.env"
CONTRACT_CNF="$SCRATCH_DIR/.my-contract.cnf"

MYSQL_CLI="$(command -v mariadb || command -v mysql || true)"
[ -n "$MYSQL_CLI" ] || { echo "mariadb/mysql client not found" >&2; exit 2; }
command -v curl >/dev/null  || { echo "curl not found" >&2; exit 2; }
command -v python3 >/dev/null || { echo "python3 not found" >&2; exit 2; }
command -v php >/dev/null    || { echo "php not found" >&2; exit 2; }

WORKDIR="$(mktemp -d /tmp/contract-run-review9b.XXXXXX)"
trap 'rm -rf "$WORKDIR"' EXIT
mkdir -p "$SCRATCH_DIR/results"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
RESULTS_LOG="$SCRATCH_DIR/results/run-review9b-$RUN_ID.log"
RESPDIR="$SCRATCH_DIR/results/run-review9b-$RUN_ID"
mkdir -p "$RESPDIR"
exec > >(tee -a "$RESULTS_LOG") 2>&1

PASS=0; FAIL=0
declare -a VERDICTS=()

say()  { echo "[review9b $(date +%H:%M:%S)] $*"; }
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
assert_file_contains() { # label file regex
    if grep -Eq "$3" "$2" 2>/dev/null; then pass "$1 (found /$3/ in $(basename "$2"))";
    else fail "$1 (missing /$3/ in $(basename "$2"))"; fi
}
verdict() {
    VERDICTS+=("$1: $2")
    echo "  ==> $1: $2"
}

# --- target mapping (same as run-review9a.sh) --------------------------------
db_of()   { [ "$1" = "v2" ] && echo paylist_contract_v2 || echo paylist_contract_wg; }
base_of() { [ "$1" = "v2" ] && echo "${V2_HTTP_BASE:-8080}" || echo "${WG_HTTP_BASE:-8090}"; }
path_of() { [ "$1" = "v2" ] && echo bayarQR/notify.php || echo paylist/notify.php; }
log_of()  { [ "$1" = "v2" ] && echo qris_notif_log || echo paylist_log; }
bill_of() { [ "$1" = "v2" ] && echo proforma || echo payment; }
matched_bill_col_of() { [ "$1" = "v2" ] && echo matched_id_proforma || echo matched_id_payment; }
port_for() { echo $(( $(base_of "$1") + ($2 % ${WORKERS:-4}) )); }
# Log worker PHP yang melayani probe-apply (port_for t 1 -> v2:8081, wg:8091).
worker_log_of() { [ "$1" = "v2" ] && echo "$SCRATCH_DIR/php-v2-8081.log" || echo "$SCRATCH_DIR/php-wg-8091.log"; }

sql()  { "$MYSQL_CLI" --defaults-file="$CONTRACT_CNF" -N -B -D "$(db_of "$1")" -e "$2"; }
sql_h() { "$MYSQL_CLI" --defaults-file="$CONTRACT_CNF" -D "$(db_of "$1")" -e "$2"; }

expected() { php -r '$b=(int)$argv[1]; $k=(int)$argv[2]; $bi=(int)ceil($b); echo $bi + (int)ceil($bi*0.007) + $k;' "$1" "$2"; }

probe_apply_post() { # target worker-index log_id -> probe response body
    curl -s -m 60 -X POST "http://127.0.0.1:$(port_for "$1" "$2")/probe-apply.php" \
        -d "log_id=$3"
}

# orchestrate_deadlock target name logid [skip-flag] [extra-customers]
# Runs stale-orchestrator.php mode=deadlock; writes its JSON to
# $RESPDIR/<name>.json and echoes the file path. Returns the
# orchestrator's exit code.
orchestrate_deadlock() {
    local t="$1" name="$2" logid="$3" skip="${4:-}" extra="${5:-}"
    local out="$RESPDIR/$name.json"
    local probe_url="http://127.0.0.1:$(port_for "$t" 1)/probe-apply.php"
    local billid=$([ "$t" = "v2" ] && echo 910001 || echo 920001)
    local extra_args=()
    [ -n "$skip" ] && extra_args+=(--skip-invoice-lock)
    [ -n "$extra" ] && extra_args+=(--extra-customers "$extra")
    php "$SCRIPT_DIR/stale-orchestrator.php" \
        --target "$t" --cnf "$SCRATCH_DIR/.my-root.cnf" --db-name "$(db_of "$t")" \
        --log-id "$logid" --probe-url "$probe_url" --mode deadlock \
        --resp-file "$RESPDIR/$name.apply.json" --code-file "$RESPDIR/$name.httpcode" \
        --switch-customer 900001 --bill-id "$billid" \
        --peer-script "$SCRIPT_DIR/deadlock-peer.php" \
        --peer-layanan "Test 20Mbps" --peer-tagihan "100000" \
        --deadlock-file "$RESPDIR/$name.innodb-deadlock.txt" \
        "${extra_args[@]}" > "$out" 2> "$RESPDIR/$name.stderr"
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

# deadlock_count target -> jumlah baris "Deadlock found" di log worker probe
deadlock_count() { local n; n="$(grep -c "Deadlock found" "$(worker_log_of "$1")" 2>/dev/null || true)"; echo "${n:-0}"; }

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
# The deadlock mode needs the review #9b orchestrator + peer extension.
grep -q "mode=deadlock" "$SCRIPT_DIR/stale-orchestrator.php" \
    || { echo "stale-orchestrator.php lacks mode=deadlock — deploy the review9b version first" >&2; exit 2; }
grep -q "skip-invoice-lock" "$SCRIPT_DIR/deadlock-peer.php" \
    || { echo "deadlock-peer.php lacks --skip-invoice-lock — deploy the review9b version first" >&2; exit 2; }

# --- seeds (same as run-review9a.sh) -----------------------------------------
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

seed_target() { # target key
    local t="$1" key="$2"
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
}

evidence() { # dump the tables a scenario's verdict rests on
    local t="$1" log bill mbc
    log="$(log_of "$t")"; bill="$(bill_of "$t")"; mbc="$(matched_bill_col_of "$t")"
    note "-- $t evidence: $log"
    sql_h "$t" "SELECT id, dedupe_key, match_status, matched_id_pelanggan, $mbc AS matched_bill, (applied_at IS NOT NULL) AS applied, LEFT(COALESCE(apply_note,''), 120) AS note FROM $log ORDER BY id;" | sed 's/^/      /'
    note "-- $t evidence: $bill + pemasukan + pelanggan"
    sql_h "$t" "SELECT id, id_pelanggan, layanan, tagihan, status FROM $bill ORDER BY id;" | sed 's/^/      /'
    sql_h "$t" "SELECT COUNT(*) AS pemasukan_rows FROM pemasukan; SELECT id_pelanggan, layanan, status_pembayaran, masa_aktif, poin FROM pelanggan ORDER BY id_pelanggan;" | sed 's/^/      /'
    if [ "$t" = "wg" ]; then
        sql_h "$t" "SELECT COUNT(*) AS report_rows FROM report;" | sed 's/^/      /'
    fi
}

# --- F9D: positive control — plain apply through the probe --------------------
f9d_probe_sanity() {
    local t="$1" key="f9d-s-$t-1" before_fail="$FAIL"
    say "F9D-probe-sanity [$t] plain apply via probe-apply.php (no interference)"
    seed_target "$t" "$key"
    local billid=$([ "$t" = "v2" ] && echo 910001 || echo 920001)
    local logid
    logid="$(sql "$t" "SELECT id FROM $(log_of "$t") WHERE dedupe_key='$key';")"
    assert_eq "F9D-sanity [$t] setup: log matched to bill" "$billid" "$(sql "$t" "SELECT $(matched_bill_col_of "$t") FROM $(log_of "$t") WHERE id=$logid;")"
    local rr
    rr="$(probe_apply_post "$t" 1 "$logid")"
    note "probe-apply response: $(printf '%s' "$rr" | head -c 400)"
    assert_eq "F9D-sanity [$t] probe ok" "True" "$(jget "d.get('ok')" "$rr")"
    assert_eq "F9D-sanity [$t] apply applied=true" "True" "$(jget "(d.get('apply') or {}).get('applied')" "$rr")"
    assert_eq "F9D-sanity [$t] bill PAID" "PAID" "$(sql "$t" "SELECT status FROM $(bill_of "$t") WHERE id=$billid;")"
    assert_eq "F9D-sanity [$t] pemasukan rows (exactly one)" "1" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
    assert_eq "F9D-sanity [$t] log applied_at set" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE id=$logid AND applied_at IS NOT NULL;")"
    assert_eq "F9D-sanity [$t] customer status PAID" "PAID" "$(sql "$t" "SELECT status_pembayaran FROM pelanggan WHERE id_pelanggan=900001;")"
    assert_eq "F9D-sanity [$t] customer masa_aktif 0->30" "30" "$(sql "$t" "SELECT masa_aktif FROM pelanggan WHERE id_pelanggan=900001;")"
    assert_eq "F9D-sanity [$t] customer poin 0->10 (UNPAID, undisturbed)" "10" "$(sql "$t" "SELECT poin FROM pelanggan WHERE id_pelanggan=900001;")"
    evidence "$t"
    [ "$FAIL" = "$before_fail" ] && verdict "F9D-probe-sanity [$t] plain apply" "PASS (probe drives the real apply; normal settlement unchanged)" \
        || verdict "F9D-probe-sanity [$t] plain apply" "FAIL"
}

# --- F9D: negative control — peer holds the customer lock but never asks
# for the invoice lock: the apply only waits, then settles normally, and
# NO deadlock is observed (worker-log "Deadlock found" count unchanged).
f9d_lockwait_control() {
    local t="$1" key="f9d-c-$t-1" before_fail="$FAIL"
    say "F9D-lockwait-control [$t] peer holds customer lock, skips invoice lock -> apply must settle, no deadlock"
    seed_target "$t" "$key"
    local billid=$([ "$t" = "v2" ] && echo 910001 || echo 920001)
    local logid
    logid="$(sql "$t" "SELECT id FROM $(log_of "$t") WHERE dedupe_key='$key';")"
    assert_eq "F9D-control [$t] setup: log matched to bill" "$billid" "$(sql "$t" "SELECT $(matched_bill_col_of "$t") FROM $(log_of "$t") WHERE id=$logid;")"
    local dl_before
    dl_before="$(deadlock_count "$t")"
    local orch
    orch="$(orchestrate_deadlock "$t" "f9d-c-$t" "$logid" skip)" || {
        fail "F9D-control [$t] orchestrator failed (see $orch)"
        verdict "F9D-control [$t] customer lock alone is not a deadlock" "FAIL (orchestration)"
        return
    }
    note "orchestrator: $(cat "$orch" | head -c 600)"
    assert_eq "F9D-control [$t] orchestration ok" "True" "$(jget "d.get('ok')" "$(cat "$orch")")"
    assert_eq "F9D-control [$t] apply provably blocked on the lock first" "True" "$(jget "d.get('blocked_detected')" "$(cat "$orch")")"
    assert_eq "F9D-control [$t] apply provably waited on the peer customer lock" "True" "$(jget "d.get('apply_waited_on_peer')" "$(cat "$orch")")"
    assert_eq "F9D-control [$t] probe HTTP 200" "200" "$(jget "d.get('http_code')" "$(cat "$orch")")"
    assert_eq "F9D-control [$t] apply applied=true (settles after the peer commits)" "True" "$(apply_applied "$orch")"
    assert_eq "F9D-control [$t] peer committed (survivor)" "committed" "$(jget "(d.get('peer') or {}).get('outcome')" "$(cat "$orch")")"
    assert_eq "F9D-control [$t] no new deadlock in worker log ($dl_before -> $(deadlock_count "$t"))" "$dl_before" "$(deadlock_count "$t")"
    assert_eq "F9D-control [$t] bill PAID" "PAID" "$(sql "$t" "SELECT status FROM $(bill_of "$t") WHERE id=$billid;")"
    assert_eq "F9D-control [$t] pemasukan rows (exactly one)" "1" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
    assert_eq "F9D-control [$t] customer poin 0->10" "10" "$(sql "$t" "SELECT poin FROM pelanggan WHERE id_pelanggan=900001;")"
    assert_eq "F9D-control [$t] peer effect visible: customer layanan Test 20Mbps" "Test 20Mbps" "$(sql "$t" "SELECT layanan FROM pelanggan WHERE id_pelanggan=900001;")"
    evidence "$t"
    [ "$FAIL" = "$before_fail" ] && verdict "F9D-control [$t] customer lock alone is not a deadlock" "PASS (apply waited, then settled; zero new 1213)" \
        || verdict "F9D-control [$t] customer lock alone is not a deadlock" "FAIL"
}

# --- F9D: forced deadlock 1213 — victim rolls back fully, state recoverable --
f9d_deadlock() {
    local t="$1" key="f9d-d-$t-1" before_fail="$FAIL"
    say "F9D-deadlock [$t] forced 1213: apply (log->bill->customer) vs peer (customer->bill)"
    seed_target "$t" "$key"
    local billid=$([ "$t" = "v2" ] && echo 910001 || echo 920001)
    local logid
    logid="$(sql "$t" "SELECT id FROM $(log_of "$t") WHERE dedupe_key='$key';")"
    assert_eq "F9D-deadlock [$t] setup: log matched to bill" "$billid" "$(sql "$t" "SELECT $(matched_bill_col_of "$t") FROM $(log_of "$t") WHERE id=$logid;")"
    assert_eq "F9D-deadlock [$t] setup: bill UNPAID" "UNPAID" "$(sql "$t" "SELECT status FROM $(bill_of "$t") WHERE id=$billid;")"
    assert_eq "F9D-deadlock [$t] setup: customer UNPAID, masa 0, poin 0" "UNPAID|0|0" "$(sql "$t" "SELECT CONCAT(status_pembayaran, '|', masa_aktif, '|', poin) FROM pelanggan WHERE id_pelanggan=900001;")"
    assert_eq "F9D-deadlock [$t] setup: log apply_note NULL" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE id=$logid AND apply_note IS NULL;")"
    # Pelanggan dummy untuk bobot peer (lihat tajuk): peer mengunci
    # keempatnya sebelum go agar bobotnya melampaui apply.
    if [ "$t" = "v2" ]; then
        for d in 900002 900003 900004 900005; do seed_customer_v2 "$d" UNPAID 0; done
    else
        for d in 900002 900003 900004 900005; do seed_customer_wg "$d" UNPAID 0; done
    fi
    assert_eq "F9D-deadlock [$t] setup: 4 dummy customers seeded" "4" "$(sql "$t" "SELECT COUNT(*) FROM pelanggan WHERE id_pelanggan IN (900002,900003,900004,900005);")"
    local dl_before
    dl_before="$(deadlock_count "$t")"
    local orch
    orch="$(orchestrate_deadlock "$t" "f9d-d-$t" "$logid" "" "900002,900003,900004,900005")" || {
        fail "F9D-deadlock [$t] orchestrator failed (see $orch)"
        verdict "F9D-deadlock [$t] forced 1213, full rollback, recoverable" "FAIL (orchestration)"
        return
    }
    note "orchestrator: $(cat "$orch" | head -c 800)"
    assert_eq "F9D-deadlock [$t] orchestration ok" "True" "$(jget "d.get('ok')" "$(cat "$orch")")"
    assert_eq "F9D-deadlock [$t] apply provably blocked on the log lock first" "True" "$(jget "d.get('blocked_detected')" "$(cat "$orch")")"
    assert_eq "F9D-deadlock [$t] apply provably waited on the peer customer lock" "True" "$(jget "d.get('apply_waited_on_peer')" "$(cat "$orch")")"
    assert_eq "F9D-deadlock [$t] probe HTTP 200" "200" "$(jget "d.get('http_code')" "$(cat "$orch")")"
    # (i) the victim gets 1213: apply fails, peer (survivor) commits.
    assert_eq "F9D-deadlock [$t] apply applied=false (victim)" "False" "$(apply_applied "$orch")"
    if [ "$t" = "v2" ]; then
        assert_like "F9D-deadlock [$t] apply reports its transaction rolled back" "GAGAL transaksi, rollback" "$(apply_text "$orch")"
    else
        assert_like "F9D-deadlock [$t] apply reports its transaction rolled back" "gagal: " "$(apply_text "$orch")"
    fi
    assert_eq "F9D-deadlock [$t] peer committed (survivor, not victim)" "committed" "$(jget "(d.get('peer') or {}).get('outcome')" "$(cat "$orch")")"
    assert_eq "F9D-deadlock [$t] peer errno 0 (no 1213 on the survivor)" "0" "$(jget "(d.get('peer') or {}).get('errno')" "$(cat "$orch")")"
    assert_eq "F9D-deadlock [$t] peer locked 4 dummy customers (weight)" "4" "$(jget "(d.get('peer') or {}).get('extra_updated')" "$(cat "$orch")")"
    # 1213 evidence, layer 1: InnoDB latest deadlock section.
    local dlfile="$RESPDIR/f9d-d-$t.innodb-deadlock.txt"
    assert_file_contains "F9D-deadlock [$t] InnoDB reports LATEST DETECTED DEADLOCK" "$dlfile" "LATEST DETECTED DEADLOCK"
    assert_file_contains "F9D-deadlock [$t] InnoDB rolled back one transaction" "$dlfile" "WE ROLL BACK TRANSACTION"
    # NOTE (review9b finding, revised 25 Nov 2026 after item (c)): "(N)" in
    # "WE ROLL BACK TRANSACTION (N)" is version-dependent diagnostic text and
    # identifies NEITHER the victim NOR the rolled-back count: MariaDB 10.5.29
    # and 11.8.6 write (1) while 10.11.19 writes (2) for the IDENTICAL deadlock
    # (same peer 7 row locks listed (1), apply 3 row locks listed (2), victim
    # proven to be the apply on all three versions); 11.8.6 writes (1) while
    # the victim is transaction (2), and 10.11 rolls back exactly one
    # transaction (peer proven committed) yet writes (2). Cf. MDEV-37845
    # (10.6.19). So this assertion only checks that InnoDB reports a rollback;
    # the victim is identified from the apply's own text (below) + peer
    # committed + the row-by-row rollback checks + the successful re-apply,
    # not the InnoDB line number.
    assert_file_contains "F9D-deadlock [$t] InnoDB rolled back a transaction" "$dlfile" "WE ROLL BACK TRANSACTION \([0-9]+\)"
    assert_file_contains "F9D-deadlock [$t] deadlock section shows the apply waiting on pelanggan" "$dlfile" "FROM pelanggan WHERE id_pelanggan"
    if [ "$t" = "v2" ]; then
        assert_file_contains "F9D-deadlock [$t] deadlock section shows the peer waiting on the bill lock" "$dlfile" "UPDATE proforma SET layanan"
    else
        assert_file_contains "F9D-deadlock [$t] deadlock section shows the peer waiting on the bill lock" "$dlfile" "UPDATE payment SET layanan"
    fi
    assert_like "F9D-deadlock [$t] apply names the deadlock itself (ER 1213 in its own text)" "Deadlock found" "$(apply_text "$orch")"
    note "deadlock section: $(head -c 900 "$dlfile" | tr '\n' ' ')"
    # 1213 evidence, layer 2: the apply's own text names the deadlock (asserted
    # above). The worker log is recorded, not asserted: on this PHP 8.4.27
    # mysqli throws mysqli_sql_exception by default (no mysqli_report anywhere
    # in the apply path -- verified by a standalone CLI test), so no PHP
    # warning is emitted and the worker log stays at 0.
    note "F9D-deadlock [$t] worker log Deadlock found count: $dl_before -> $(deadlock_count "$t") (stays 0; see note above)"
    # (ii) the victim rolls back FULLY — no settlement, no points, no
    # period/balance change, no apply_note, no match_status change.
    assert_eq "F9D-deadlock [$t] bill still UNPAID (no settlement)" "UNPAID" "$(sql "$t" "SELECT status FROM $(bill_of "$t") WHERE id=$billid;")"
    assert_eq "F9D-deadlock [$t] pemasukan rows still 0" "0" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
    assert_eq "F9D-deadlock [$t] customer status still UNPAID" "UNPAID" "$(sql "$t" "SELECT status_pembayaran FROM pelanggan WHERE id_pelanggan=900001;")"
    assert_eq "F9D-deadlock [$t] customer masa_aktif still 0" "0" "$(sql "$t" "SELECT masa_aktif FROM pelanggan WHERE id_pelanggan=900001;")"
    assert_eq "F9D-deadlock [$t] customer poin still 0" "0" "$(sql "$t" "SELECT poin FROM pelanggan WHERE id_pelanggan=900001;")"
    assert_eq "F9D-deadlock [$t] log still matched" "matched" "$(sql "$t" "SELECT match_status FROM $(log_of "$t") WHERE id=$logid;")"
    assert_eq "F9D-deadlock [$t] log still matched to the same bill" "$billid" "$(sql "$t" "SELECT $(matched_bill_col_of "$t") FROM $(log_of "$t") WHERE id=$logid;")"
    assert_eq "F9D-deadlock [$t] log applied_at still NULL" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE id=$logid AND applied_at IS NULL;")"
    assert_eq "F9D-deadlock [$t] log apply_note still NULL (no failure note written)" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE id=$logid AND apply_note IS NULL;")"
    # Survivor effects are the peer's, and only the peer's.
    assert_eq "F9D-deadlock [$t] survivor: customer layanan Test 20Mbps (peer committed)" "Test 20Mbps" "$(sql "$t" "SELECT layanan FROM pelanggan WHERE id_pelanggan=900001;")"
    assert_eq "F9D-deadlock [$t] survivor: bill layanan Test 20Mbps (peer committed)" "Test 20Mbps" "$(sql "$t" "SELECT layanan FROM $(bill_of "$t") WHERE id=$billid;")"
    assert_eq "F9D-deadlock [$t] survivor: bill tagihan unchanged (100000)" "100000" "$(sql "$t" "SELECT CAST(tagihan AS SIGNED) FROM $(bill_of "$t") WHERE id=$billid;")"
    assert_eq "F9D-deadlock [$t] survivor: 4 dummies layanan Test 20Mbps (peer committed)" "4" "$(sql "$t" "SELECT COUNT(*) FROM pelanggan WHERE id_pelanggan IN (900002,900003,900004,900005) AND layanan='Test 20Mbps';")"
    assert_eq "F9D-deadlock [$t] survivor: dummies status/masa/poin untouched" "4" "$(sql "$t" "SELECT COUNT(*) FROM pelanggan WHERE id_pelanggan IN (900002,900003,900004,900005) AND status_pembayaran='UNPAID' AND masa_aktif=0 AND poin=0;")"
    if [ "$t" = "wg" ]; then
        assert_eq "F9D-deadlock [$t] report rows still 0 (no WA sent by the victim)" "0" "$(sql "$t" "SELECT COUNT(*) FROM report;")"
    fi
    evidence "$t"
    # (iv) recoverable state: the same log re-applies cleanly.
    say "F9D-deadlock [$t] recoverability: re-apply the same log"
    local rr2
    rr2="$(probe_apply_post "$t" 1 "$logid")"
    note "re-apply response: $(printf '%s' "$rr2" | head -c 400)"
    assert_eq "F9D-deadlock [$t] re-apply probe ok" "True" "$(jget "d.get('ok')" "$rr2")"
    assert_eq "F9D-deadlock [$t] re-apply applied=true" "True" "$(jget "(d.get('apply') or {}).get('applied')" "$rr2")"
    assert_eq "F9D-deadlock [$t] re-apply: bill PAID" "PAID" "$(sql "$t" "SELECT status FROM $(bill_of "$t") WHERE id=$billid;")"
    assert_eq "F9D-deadlock [$t] re-apply: pemasukan rows (exactly one)" "1" "$(sql "$t" "SELECT COUNT(*) FROM pemasukan;")"
    assert_eq "F9D-deadlock [$t] re-apply: log applied_at set" "1" "$(sql "$t" "SELECT COUNT(*) FROM $(log_of "$t") WHERE id=$logid AND applied_at IS NOT NULL;")"
    assert_eq "F9D-deadlock [$t] re-apply: customer status PAID" "PAID" "$(sql "$t" "SELECT status_pembayaran FROM pelanggan WHERE id_pelanggan=900001;")"
    assert_eq "F9D-deadlock [$t] re-apply: customer masa_aktif 0->30" "30" "$(sql "$t" "SELECT masa_aktif FROM pelanggan WHERE id_pelanggan=900001;")"
    assert_eq "F9D-deadlock [$t] re-apply: customer poin 0->10 (UNPAID)" "10" "$(sql "$t" "SELECT poin FROM pelanggan WHERE id_pelanggan=900001;")"
    evidence "$t"
    [ "$FAIL" = "$before_fail" ] && verdict "F9D-deadlock [$t] forced 1213, full rollback, recoverable" "PASS (victim 1213 + full rollback + survivor committed + re-apply succeeded)" \
        || verdict "F9D-deadlock [$t] forced 1213, full rollback, recoverable" "FAIL"
}

# --- main -------------------------------------------------------------------
say "review9b run $RUN_ID (target=$TARGET)"
say "amounts: AMT_A=$AMT_A (100000+fee+123); poin_status UNPAID=10 ISOLIR=5 SUSPEND=1; peer layanan Test 20Mbps, tagihan unchanged"

run_target() {
    local t="$1"
    wanted f9d_probe_sanity     && f9d_probe_sanity "$t"
    wanted f9d_lockwait_control && f9d_lockwait_control "$t"
    wanted f9d_deadlock         && f9d_deadlock "$t"
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
