#!/usr/bin/env bash
# run-birth.sh — birth-fix (24 Nov 2026) evidence scenarios for C2 follow-up.
#
# Finding (verified from code): v2New code birth computed the used-code set
# from the LATEST proforma per customer only (JOIN MAX(id)), so a stale
# UNPAID bill's code was invisible and a fresh bill could be born with the
# same code -> identical nominal -> ambiguous. C6 activated this latent hole
# (the matcher now sees all UNPAID bills). Warganet's used-set was already
# correct (all UNPAID).
#
# Fix (two layers, all three birth sites):
#   (a) v2New used-set = all UNPAID coded proformas (warganet pattern).
#   (b) a candidate code is also rejected when its final amount
#       (total + 0.7% fee + code, matcher formula) equals another UNPAID
#       coded bill's final amount — different totals + different codes can
#       sum to the same amount.
#
#   f_birth_stale  stale UNPAID bill (code 105) + fresh bill, same customer
#                  -> birth must NOT pick 105 (old v2 code did).
#   f_birth_sum    twin bill whose nominal equals the fresh bill's nominal
#                  at the lowest free code -> birth must skip to a
#                  non-colliding code (old code on both systems did not).
#
# Entry path: the REAL pages over HTTP in the scratch docroots —
#   v2: bayar.php?id_pelanggan=... (public page)
#   wg: payment/checkout.php?idtrx=...&qris=1 (public bill checkout)
# No session is forged: both pages are public for bill payment.
# member/payment.php shares the identical birth decision logic with
# bayar.php — proven by the birth-block equivalence check in this runner,
# so testing bayar.php covers member/payment.php.
#
# Usage: ./run-birth.sh [--target v2|wg|all] [--pages fixed|orig]
#   --pages orig deploys the PRE-FIX pages as a negative control
#   (expected: f_birth_stale FAILs on v2, f_birth_sum FAILs on both).
# Env: SCRATCH_DIR (default /home/warganet/paylist-contract-c2),
#      STAGE_DIR (default /home/warganet/birth-fix).
#
# Every verdict is computed from SCRATCH TABLE CONTENTS, not HTTP alone.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRATCH_DIR="${SCRATCH_DIR:-/home/warganet/paylist-contract-c2}"
STAGE_DIR="${STAGE_DIR:-/home/warganet/birth-fix}"
TARGET="all"
PAGES="fixed"
while [ $# -gt 0 ]; do
    case "$1" in
        --target) TARGET="$2"; shift 2 ;;
        --pages)  PAGES="$2"; shift 2 ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
done
case "$TARGET" in v2|wg|all) ;; *) echo "--target must be v2, wg or all" >&2; exit 2 ;; esac
case "$PAGES" in fixed|orig) ;; *) echo "--pages must be fixed or orig" >&2; exit 2 ;; esac

[ -f "$SCRATCH_DIR/.env" ] || { echo "no $SCRATCH_DIR/.env — run setup-scratch.sh first" >&2; exit 2; }
[ -f "$SCRATCH_DIR/.my-contract.cnf" ] || { echo "no $SCRATCH_DIR/.my-contract.cnf — run setup-scratch.sh first" >&2; exit 2; }
# shellcheck disable=SC1090
. "$SCRATCH_DIR/.env"
CONTRACT_CNF="$SCRATCH_DIR/.my-contract.cnf"

MYSQL_CLI="$(command -v mariadb || command -v mysql || true)"
[ -n "$MYSQL_CLI" ] || { echo "mariadb/mysql client not found" >&2; exit 2; }
command -v curl >/dev/null  || { echo "curl not found" >&2; exit 2; }
command -v php >/dev/null    || { echo "php not found" >&2; exit 2; }

WORKDIR="$(mktemp -d /tmp/contract-run-birth.XXXXXX)"
trap 'rm -rf "$WORKDIR"' EXIT
mkdir -p "$SCRATCH_DIR/results"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
RESULTS_LOG="$SCRATCH_DIR/results/run-birth-$RUN_ID.log"
RESPDIR="$SCRATCH_DIR/results/run-birth-$RUN_ID"
mkdir -p "$RESPDIR"
exec > >(tee -a "$RESULTS_LOG") 2>&1

PASS=0; FAIL=0
declare -a VERDICTS=()

say()  { echo "[birth $(date +%H:%M:%S)] $*"; }
note() { echo "      $*"; }

pass() { PASS=$((PASS+1)); echo "  PASS: $*"; }
fail() { FAIL=$((FAIL+1)); echo "  FAIL: $*"; }
assert_eq() { # label expected actual
    if [ "$2" = "$3" ]; then pass "$1 (expected=$2, actual=$3)";
    else fail "$1 (expected=$2, actual=$3)"; fi
}
assert_like() { # label regex value
    if printf '%s' "$3" | grep -Eq "$2"; then pass "$1 (matches /$2/)";
    else fail "$1 (expected /$2/, got: $3)"; fi
}
verdict() {
    VERDICTS+=("$1: $2")
    echo "  ==> $1: $2"
}

# --- target mapping (same as run-contract.sh) -------------------------------
db_of()   { [ "$1" = "v2" ] && echo paylist_contract_v2 || echo paylist_contract_wg; }
base_of() { [ "$1" = "v2" ] && echo "${V2_HTTP_BASE:-8080}" || echo "${WG_HTTP_BASE:-8090}"; }
bill_of() { [ "$1" = "v2" ] && echo proforma || echo payment; }

sql()  { "$MYSQL_CLI" --defaults-file="$CONTRACT_CNF" -N -B -D "$(db_of "$1")" -e "$2"; }
sql_h() { "$MYSQL_CLI" --defaults-file="$CONTRACT_CNF" -D "$(db_of "$1")" -e "$2"; }

expected() { php -r '$b=(int)$argv[1]; $k=(int)$argv[2]; $bi=(int)ceil($b); echo $bi + (int)ceil($bi*0.007) + $k;' "$1" "$2"; }

# --- deploy the pages under test into the scratch docroots -------------------
deploy_pages() {
    local t="$1"
    if [ "$t" = "v2" ]; then
        [ -f "$STAGE_DIR/$PAGES/v2/bayar.php" ] || { echo "missing $STAGE_DIR/$PAGES/v2/bayar.php" >&2; exit 2; }
        cp "$STAGE_DIR/$PAGES/v2/bayar.php" "$SCRATCH_DIR/html-v2/bayar.php"
        mkdir -p "$SCRATCH_DIR/html-v2/payment"
        cp -r "$STAGE_DIR/stage/v2/payment/." "$SCRATCH_DIR/html-v2/payment/"
        cp "$STAGE_DIR/stage/v2/bayarQR/qris.php" "$SCRATCH_DIR/html-v2/bayarQR/qris.php"
        # The workers run as warganet and the docroot is owner-only
        # (600/700); root-owned copies are unreadable to them (HTTP 500).
        chown -R warganet:warganet "$SCRATCH_DIR/html-v2/bayar.php" "$SCRATCH_DIR/html-v2/payment" "$SCRATCH_DIR/html-v2/bayarQR/qris.php"
        find "$SCRATCH_DIR/html-v2/payment" -type d -exec chmod 700 {} +
        find "$SCRATCH_DIR/html-v2/payment" -type f -exec chmod 600 {} +
        chmod 600 "$SCRATCH_DIR/html-v2/bayar.php" "$SCRATCH_DIR/html-v2/bayarQR/qris.php"
        # Fixtures: bayar.php queries `midtrans` (line 353, clientKey for
        # the snap.js tag — uncaught mysqli exception on PHP 8.1+ when the
        # table is missing) and `qris_statis` (line 446, QR payload) on
        # every GET render. Neither table is in the scratch schema; create
        # them. midtrans gets one dummy row; qris_statis stays empty (the
        # page renders with a QR error, birth unaffected).
        sql v2 "CREATE TABLE IF NOT EXISTS midtrans (clientKey VARCHAR(255), serverKey VARCHAR(255));"
        sql v2 "INSERT INTO midtrans (clientKey, serverKey) SELECT 'SCRATCH-DUMMY-CLIENT', 'SCRATCH-DUMMY-SERVER' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM midtrans);"
        sql v2 "CREATE TABLE IF NOT EXISTS qris_statis (id INT AUTO_INCREMENT PRIMARY KEY, merchant_name VARCHAR(255), payload TEXT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);"
        php -l "$SCRATCH_DIR/html-v2/bayar.php" || { echo "php -l failed for bayar.php" >&2; exit 2; }
        say "v2 bayar.php ($PAGES) deployed, sha $(sha256sum "$SCRATCH_DIR/html-v2/bayar.php" | cut -d' ' -f1)"
    else
        [ -f "$STAGE_DIR/$PAGES/wg/checkout.php" ] || { echo "missing $STAGE_DIR/$PAGES/wg/checkout.php" >&2; exit 2; }
        mkdir -p "$SCRATCH_DIR/html-wg/payment"
        cp "$STAGE_DIR/$PAGES/wg/checkout.php" "$SCRATCH_DIR/html-wg/payment/checkout.php"
        cp -r "$STAGE_DIR/stage/wg/payment/." "$SCRATCH_DIR/html-wg/payment/"
        mkdir -p "$SCRATCH_DIR/html-wg/paylist"
        cp "$STAGE_DIR/stage/wg/paylist/qris_helper.php" "$SCRATCH_DIR/html-wg/paylist/qris_helper.php"
        # Same owner-only docroot as v2 (see above).
        chown -R warganet:warganet "$SCRATCH_DIR/html-wg/payment" "$SCRATCH_DIR/html-wg/paylist/qris_helper.php"
        find "$SCRATCH_DIR/html-wg/payment" -type d -exec chmod 700 {} +
        find "$SCRATCH_DIR/html-wg/payment" -type f -exec chmod 600 {} +
        chmod 600 "$SCRATCH_DIR/html-wg/paylist/qris_helper.php"
        php -l "$SCRATCH_DIR/html-wg/payment/checkout.php" || { echo "php -l failed for checkout.php" >&2; exit 2; }
        say "wg checkout.php ($PAGES) deployed, sha $(sha256sum "$SCRATCH_DIR/html-wg/payment/checkout.php" | cut -d' ' -f1)"
        # Fixture: checkout.php dies before the birth block without a
        # midtrans row (SELECT clientKey, serverKey ... num_rows == 0).
        # The scratch schema has no midtrans table; create it with dummy
        # keys (scratch-only; the Snap call on GET fails against the dead
        # proxy and is caught by the page's own try/catch).
        sql wg "CREATE TABLE IF NOT EXISTS midtrans (clientKey VARCHAR(255), serverKey VARCHAR(255));"
        sql wg "INSERT INTO midtrans (clientKey, serverKey) SELECT 'SCRATCH-DUMMY-CLIENT', 'SCRATCH-DUMMY-SERVER' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM midtrans);"
        # qris_statis: checkout.php queries it inside its own try/catch,
        # so a missing table only voids the QR — create it anyway so the
        # render takes the normal path.
        sql wg "CREATE TABLE IF NOT EXISTS qris_statis (id INT AUTO_INCREMENT PRIMARY KEY, merchant_name VARCHAR(255), payload TEXT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);"
    fi
    sleep 2 # let workers re-read the files (opcache revalidate window)
}

# --- preconditions ----------------------------------------------------------
"$MYSQL_CLI" --defaults-file="$CONTRACT_CNF" -e "SELECT 1" >/dev/null 2>&1 \
    || { echo "scratch MariaDB not reachable — run setup-scratch.sh first" >&2; exit 2; }
for t in v2 wg; do
    if [ "$TARGET" = "all" ] || [ "$TARGET" = "$t" ]; then
        path=$([ "$t" = "v2" ] && echo bayarQR/notify.php || echo paylist/notify.php)
        code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$(base_of "$t")/$path" || true)"
        [ "$code" = "405" ] || { echo "workers for $t not up (HTTP $code) — run setup-scratch.sh first" >&2; exit 2; }
        deploy_pages "$t"
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
seed_proforma_dated() { # id cust total kode|NULL idtrx tanggal_terbit
    sql v2 "INSERT INTO proforma (id, idtrx, id_pelanggan, nama_pelanggan, layanan, tagihan, tanggal_terbit, total_tagihan, status, kode_unik) VALUES ($1, '$5', $2, 'Uji Kontrak $2', 'Test 10Mbps', $3, '$6', $3, 'UNPAID', $4);"
}
seed_customer_wg() { # id status masa
    sql wg "INSERT INTO pelanggan (id_pelanggan, username, nama_pelanggan, layanan, email, no_hp, tanggal_pemasangan, password, status_pembayaran, router, masa_aktif, poin) VALUES ($1, 'uji$1', 'Uji Kontrak $1', 'Test 10Mbps', '', '', '2026-01-01', 'x', '$2', '', '$3', 0);"
}
seed_payment() { # id cust total kode|NULL idtrx layanan
    sql wg "INSERT INTO payment (id, nama_pelanggan, idtrx, status, id_pelanggan, layanan, tagihan, nama_mitra, metode, kode_unik) VALUES ($1, 'Uji Kontrak $2', '$5', 'UNPAID', '$2', '${6:-Test 10Mbps}', $3, NULL, 'Unknown', $4);"
}

# --- page drivers (birth happens on render) ---------------------------------
render_v2() { # id_pelanggan outfile -> HTTP code
    curl -s -m 60 -o "$2" -w '%{http_code}' "http://127.0.0.1:$(base_of v2)/bayar.php?id_pelanggan=$1"
}
render_wg() { # idtrx outfile -> HTTP code
    curl -s -m 60 -o "$2" -w '%{http_code}' "http://127.0.0.1:$(base_of wg)/payment/checkout.php?idtrx=$1&qris=1"
}

evidence() { # dump the bill table a scenario's verdict rests on
    local t="$1" bill
    bill="$(bill_of "$t")"
    note "-- $t evidence: $bill"
    sql_h "$t" "SELECT id, id_pelanggan, status, kode_unik FROM $bill ORDER BY id;" | sed 's/^/      /'
}

# --- F-birth-stale -----------------------------------------------------------
f_birth_stale() {
    local t="$1" before_fail="$FAIL"
    say "F-birth-stale [$t] stale UNPAID bill (code 105) + fresh bill, same customer"
    reset_tgt "$t"
    if [ "$t" = "v2" ]; then
        seed_customer_v2 900001 UNPAID 0
        seed_proforma_dated 910001 900001 100000 105 PRO-TEST-910001 2026-09-01
        seed_proforma_dated 910002 900001 100000 NULL PRO-TEST-910002 2026-11-22
        # Fillers: codes 100-104 visibly used (latest bills of other
        # customers), so the old code's lowest-free pick lands on 105.
        local i
        for i in 1 2 3 4 5; do
            seed_customer_v2 "90001$i" UNPAID 0
            seed_proforma_dated "91001$i" "90001$i" 50000 "$((99 + i))" "PRO-TEST-91001$i" 2026-11-22
        done
        local http
        http="$(render_v2 900001 "$RESPDIR/fbs-$t.html")"
        note "bayar.php HTTP $http"
        assert_eq "F-birth-stale [$t] page renders 200" "200" "$http"
        assert_like "F-birth-stale [$t] page loaded the FRESH bill" "INV-TEST-910002" "$(cat "$RESPDIR/fbs-$t.html")"
        assert_eq "F-birth-stale [$t] fresh bill born code (not the stale 105)" "106" "$(sql "$t" "SELECT kode_unik FROM proforma WHERE id=910002;")"
        assert_eq "F-birth-stale [$t] stale bill code untouched" "105" "$(sql "$t" "SELECT kode_unik FROM proforma WHERE id=910001;")"
        assert_eq "F-birth-stale [$t] fresh nominal != stale nominal" "100806" "$(expected 100000 "$(sql "$t" "SELECT kode_unik FROM proforma WHERE id=910002;")")"
    else
        seed_customer_wg 900001 UNPAID 0
        seed_payment 920001 900001 100000 105 TRX-TEST-920001
        sql wg "UPDATE payment SET waktu='2026-09-01 00:00:00' WHERE id=920001;"
        seed_payment 920002 900001 100000 NULL TRX-TEST-920002
        local i
        for i in 1 2 3 4 5; do
            seed_customer_wg "90001$i" UNPAID 0
            seed_payment "92001$i" "90001$i" 50000 "$((99 + i))" "TRX-TEST-92001$i"
        done
        local http
        http="$(render_wg TRX-TEST-920002 "$RESPDIR/fbs-$t.html")"
        note "checkout.php HTTP $http"
        assert_eq "F-birth-stale [$t] page renders 200" "200" "$http"
        assert_eq "F-birth-stale [$t] fresh bill born code (not the stale 105)" "106" "$(sql "$t" "SELECT kode_unik FROM payment WHERE id=920002;")"
        assert_eq "F-birth-stale [$t] stale bill code untouched" "105" "$(sql "$t" "SELECT kode_unik FROM payment WHERE id=920001;")"
    fi
    evidence "$t"
    [ "$FAIL" = "$before_fail" ] && verdict "F-birth-stale [$t]" "PASS (birth skips the stale bill's code)" \
        || verdict "F-birth-stale [$t]" "FAIL"
}

# --- F-birth-sum -------------------------------------------------------------
f_birth_sum() {
    local t="$1" before_fail="$FAIL"
    say "F-birth-sum [$t] twin nominal at the lowest free code must be skipped"
    reset_tgt "$t"
    # Twin: 99900 + fee 700 + code 200 = 100800.
    # Fresh: 100000 + fee 700 + code 100 = 100800 (same nominal!).
    # Code 100 is FREE, so only the amount check can reject it.
    assert_eq "F-birth-sum [$t] twin nominal sanity" "100800" "$(expected 99900 200)"
    assert_eq "F-birth-sum [$t] collision sanity (fresh @100)" "100800" "$(expected 100000 100)"
    if [ "$t" = "v2" ]; then
        seed_customer_v2 900001 UNPAID 0
        seed_proforma_dated 910001 900001 99900 200 PRO-TEST-910001 2026-11-22
        seed_proforma_dated 910002 900001 100000 NULL PRO-TEST-910002 2026-11-23
        local http
        http="$(render_v2 900001 "$RESPDIR/fbss-$t.html")"
        note "bayar.php HTTP $http"
        assert_eq "F-birth-sum [$t] page renders 200" "200" "$http"
        assert_like "F-birth-sum [$t] page loaded the FRESH bill" "INV-TEST-910002" "$(cat "$RESPDIR/fbss-$t.html")"
        assert_eq "F-birth-sum [$t] fresh bill born code (100 skipped: amount collides)" "101" "$(sql "$t" "SELECT kode_unik FROM proforma WHERE id=910002;")"
        assert_eq "F-birth-sum [$t] fresh nominal != twin nominal" "100801" "$(expected 100000 "$(sql "$t" "SELECT kode_unik FROM proforma WHERE id=910002;")")"
    else
        seed_customer_wg 900001 UNPAID 0
        seed_payment 920001 900001 99900 200 TRX-TEST-920001
        seed_payment 920002 900001 100000 NULL TRX-TEST-920002
        local http
        http="$(render_wg TRX-TEST-920002 "$RESPDIR/fbss-$t.html")"
        note "checkout.php HTTP $http"
        assert_eq "F-birth-sum [$t] page renders 200" "200" "$http"
        assert_eq "F-birth-sum [$t] fresh bill born code (100 skipped: amount collides)" "101" "$(sql "$t" "SELECT kode_unik FROM payment WHERE id=920002;")"
    fi
    evidence "$t"
    [ "$FAIL" = "$before_fail" ] && verdict "F-birth-sum [$t]" "PASS (birth skips the amount-colliding code)" \
        || verdict "F-birth-sum [$t]" "FAIL"
}

# --- birth-block equivalence: member/payment.php vs bayar.php ----------------
# The member page needs a member session, so it cannot be rendered over
# HTTP in this harness. Instead, prove its birth DECISION logic is the
# same code as bayar.php's: extract the block from `$terpakai = [];` to
# the candidate loop's end, strip comments/blank lines, normalise
# indentation, and diff. (Hook require paths differ by design and sit
# outside the extracted block.)
birth_block_equivalence() {
    local before_fail="$FAIL"
    say "Birth-block equivalence [v2] member/payment.php vs bayar.php ($PAGES)"
    local extract="$WORKDIR/extract-block.py"
    cat > "$extract" <<'PYEOF'
import re, sys
path, out = sys.argv[1], sys.argv[2]
src = open(path, encoding='utf-8').read().splitlines()
start = next(i for i, l in enumerate(src) if '$terpakai = [];' in l)
end = next(i for i, l in enumerate(src) if i > start and 'if ($calon === 0)' in l)
lines = []
for l in src[start:end]:
    s = l.strip()
    if not s or s.startswith('//'):
        continue
    lines.append(re.sub(r'\s+', ' ', s))
open(out, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
PYEOF
    python3 "$extract" "$STAGE_DIR/$PAGES/v2/member-payment.php" "$WORKDIR/block-member.txt"
    python3 "$extract" "$STAGE_DIR/$PAGES/v2/bayar.php" "$WORKDIR/block-bayar.txt"
    if diff -u "$WORKDIR/block-member.txt" "$WORKDIR/block-bayar.txt" > "$WORKDIR/block-diff.txt"; then
        pass "Birth-block equivalence [v2] member/payment.php == bayar.php (decision logic identical)"
    else
        fail "Birth-block equivalence [v2] member/payment.php != bayar.php"
        sed 's/^/      /' "$WORKDIR/block-diff.txt"
    fi
    [ "$FAIL" = "$before_fail" ] && verdict "Birth-block equivalence [v2]" "PASS (same decision logic; bayar.php test covers member/payment.php)" \
        || verdict "Birth-block equivalence [v2]" "FAIL"
}

# --- main -------------------------------------------------------------------
say "birth run $RUN_ID (target=$TARGET, pages=$PAGES)"

run_target() {
    local t="$1"
    f_birth_stale "$t"
    f_birth_sum "$t"
}

[ "$TARGET" = "v2" ]  || [ "$TARGET" = "all" ] && run_target v2
[ "$TARGET" = "wg" ]  || [ "$TARGET" = "all" ] && run_target wg
[ "$TARGET" = "v2" ]  || [ "$TARGET" = "all" ] && birth_block_equivalence

echo
echo "==================== SUMMARY ===================="
for v in "${VERDICTS[@]}"; do echo "  $v"; done
echo "-------------------------------------------------"
echo "assertions: PASS=$PASS FAIL=$FAIL"
echo "full log: $RESULTS_LOG"
echo "page responses: $RESPDIR/"
if [ "$FAIL" != "0" ]; then
    echo "RESULT: $FAIL ASSERTION(S) FAILED — see log"
    exit 1
fi
echo "RESULT: ALL SCENARIOS BEHAVED AS SPECIFIED"
exit 0
