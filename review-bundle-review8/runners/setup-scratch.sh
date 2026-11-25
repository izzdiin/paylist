#!/usr/bin/env bash
# setup-scratch.sh — set up the scratch stack for the real-server idempotency
# contract harness (Paylist audit item: CONTRACT IDEMPOTENSI SERVER NYATA).
#
# What this builds (all on the local scratch host, nothing production):
#   * a DEDICATED MariaDB instance (own datadir, own socket, port 3307) with
#     two scratch databases: paylist_contract_v2 and paylist_contract_wg.
#     The system MariaDB (port 3306, synced warganet DB) is never touched.
#   * two assembled docroots (html-v2, html-wg) holding verbatim copies of the
#     production endpoint files plus a generated SCRATCH-ONLY db_config.php.
#     The operator copies the endpoint files from production hosts first
#     (see README.md); this script never touches production.
#   * 4 PHP built-in-server workers per target (v2: 8080-8083, wg: 8090-8093).
#     php -S is single-threaded per process, so 4 workers give genuine
#     concurrent execution of the endpoint against one database.
#   * a dead-proxy environment for every worker (https_proxy et al. point at
#     127.0.0.1:59999, where nothing listens) so the warganet apply path's
#     hard-coded WhatsApp curl can never leave the box. Setup PROVES this
#     with an in-worker probe and aborts if the probe gets any HTTP response.
#
# Idempotent: re-running only starts what is not running and refreshes the
# assembled docroots from the endpoint sources. Use --reset to drop and
# recreate the scratch databases (data loss is scratch-only by design).
#
# Usage: ./setup-scratch.sh [--target v2|wg|all] [--reset]
# Env:   SCRATCH_DIR (default /home/warganet/paylist-contract)
#        ENDPOINT_SRC_V2 (default $SCRATCH_DIR/endpoint-src)
#        ENDPOINT_SRC_WG (default $SCRATCH_DIR/endpoint-src-wg)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRATCH_DIR="${SCRATCH_DIR:-/home/warganet/paylist-contract}"
ENDPOINT_SRC_V2="${ENDPOINT_SRC_V2:-$SCRATCH_DIR/endpoint-src}"
ENDPOINT_SRC_WG="${ENDPOINT_SRC_WG:-$SCRATCH_DIR/endpoint-src-wg}"
MYSQL_PORT="${MYSQL_PORT:-3307}"
V2_HTTP_BASE="${V2_HTTP_BASE:-8080}"
WG_HTTP_BASE="${WG_HTTP_BASE:-8090}"
WORKERS="${WORKERS:-4}"
DEAD_PROXY_PORT="${DEAD_PROXY_PORT:-59999}"

TARGET="all"
RESET=0
while [ $# -gt 0 ]; do
    case "$1" in
        --target) TARGET="$2"; shift 2 ;;
        --reset)  RESET=1; shift ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
done
case "$TARGET" in v2|wg|all) ;; *) echo "--target must be v2, wg or all" >&2; exit 2 ;; esac

MYSQL_DATA="$SCRATCH_DIR/mysql-data"
MYSQL_SOCK="$SCRATCH_DIR/mysql.sock"
MYSQL_PID="$SCRATCH_DIR/mysql.pid"
MYSQL_LOG="$SCRATCH_DIR/mysql.log"
ENV_FILE="$SCRATCH_DIR/.env"
ROOT_CNF="$SCRATCH_DIR/.my-root.cnf"
CONTRACT_CNF="$SCRATCH_DIR/.my-contract.cnf"

log() { echo "[setup] $*"; }
die() { echo "[setup] FATAL: $*" >&2; exit 1; }

# --- tool resolution -------------------------------------------------------
INSTALL_DB="$(command -v mariadb-install-db || command -v mysql_install_db || true)"
MARIADBD="$(command -v mariadbd || command -v mysqld || true)"
MYSQL_CLI="$(command -v mariadb || command -v mysql || true)"
MYSQLADMIN="$(command -v mariadb-admin || command -v mysqladmin || true)"
[ -n "$INSTALL_DB" ] || die "mariadb-install-db not found"
[ -n "$MARIADBD" ]   || die "mariadbd/mysqld not found"
[ -n "$MYSQL_CLI" ]  || die "mariadb/mysql client not found"
[ -n "$MYSQLADMIN" ] || die "mariadb-admin/mysqladmin not found"
command -v php >/dev/null   || die "php not found"
command -v curl >/dev/null  || die "curl not found"
command -v ss >/dev/null    || die "ss (iproute2) not found"

want_v2=0; want_wg=0
[ "$TARGET" = "v2" ]  || [ "$TARGET" = "all" ] && want_v2=1
[ "$TARGET" = "wg" ]  || [ "$TARGET" = "all" ] && want_wg=1

# --- endpoint sources must already exist (operator-copied, never in repo) --
if [ "$want_v2" = 1 ]; then
    for f in bayarQR/notify.php bayarQR/qris_apply.php bayarQR/qris_match.php bayarQR/qris_rematch.php functions/poin.php csrf.php; do
        [ -f "$ENDPOINT_SRC_V2/$f" ] || die "missing v2New endpoint copy: $ENDPOINT_SRC_V2/$f (see README.md)"
    done
fi
if [ "$want_wg" = 1 ]; then
    for f in paylist/notify.php paylist/paylist_apply.php paylist/paylist_match.php paylist/paylist_rematch.php csrf.php; do
        [ -f "$ENDPOINT_SRC_WG/$f" ] || die "missing warganet endpoint copy: $ENDPOINT_SRC_WG/$f (see README.md)"
    done
fi

# --- dead proxy port must be free (nothing may ever listen there) ----------
if ss -ltn 2>/dev/null | grep -q ":$DEAD_PROXY_PORT "; then
    die "port $DEAD_PROXY_PORT is in use; the dead proxy would not be dead"
fi

mkdir -p "$SCRATCH_DIR" "$SCRATCH_DIR/results"

# --- secrets: generated once, scratch-only, never committed -----------------
if [ ! -f "$ENV_FILE" ]; then
    umask 077
    {
        echo "SECRET=$(openssl rand -hex 32 2>/dev/null || head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
        echo "DB_PASS=$(openssl rand -hex 16 2>/dev/null || head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
        echo "ROOT_PASS=$(openssl rand -hex 16 2>/dev/null || head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    } > "$ENV_FILE"
    chmod 600 "$ENV_FILE"
    log "generated scratch secrets in $ENV_FILE (0600)"
fi
# shellcheck disable=SC1090
. "$ENV_FILE"
SECRET_HASH="$(printf '%s' "$SECRET" | sha256sum | cut -d' ' -f1)"

umask 077
cat > "$ROOT_CNF" <<EOF
[client]
user=root
password=$ROOT_PASS
socket=$MYSQL_SOCK
EOF
cat > "$CONTRACT_CNF" <<EOF
[client]
user=contract
password=$DB_PASS
socket=$MYSQL_SOCK
EOF
chmod 600 "$ROOT_CNF" "$CONTRACT_CNF"

# --- dedicated scratch MariaDB instance ------------------------------------
if [ ! -d "$MYSQL_DATA/mysql" ]; then
    log "initialising scratch datadir $MYSQL_DATA"
    "$INSTALL_DB" --user="$(id -un)" --datadir="$MYSQL_DATA" \
        --auth-root-authentication-method=normal --skip-test-db
fi

mysql_running() {
    [ -S "$MYSQL_SOCK" ] && "$MYSQLADMIN" --defaults-file="$ROOT_CNF" ping >/dev/null 2>&1
}

if ! mysql_running; then
    # Server already up but root password not set yet (interrupted first run):
    # do not try to start a second one.
    if [ -S "$MYSQL_SOCK" ] && "$MYSQLADMIN" --socket="$MYSQL_SOCK" -u root ping >/dev/null 2>&1; then
        log "scratch mariadbd already running (root password not set yet)"
    else
        # A stale socket without a server (e.g. after an unclean stop) blocks startup.
        [ -S "$MYSQL_SOCK" ] && rm -f "$MYSQL_SOCK"
        log "starting scratch mariadbd (port $MYSQL_PORT, socket $MYSQL_SOCK)"
        # NOTE: mariadbd 11.8 has no --daemonize; detach with setsid instead.
        setsid "$MARIADBD" --user="$(id -un)" --datadir="$MYSQL_DATA" --socket="$MYSQL_SOCK" \
            --port="$MYSQL_PORT" --bind-address=127.0.0.1 --pid-file="$MYSQL_PID" \
            --log-error="$MYSQL_LOG" --innodb-buffer-pool-size=64M --max-connections=32 \
            >/dev/null 2>&1 < /dev/null &
        # First boot has no root password yet; ping without auth until it answers.
        up=0
        for _ in $(seq 1 30); do
            if "$MYSQLADMIN" --socket="$MYSQL_SOCK" -u root ping >/dev/null 2>&1 \
                || mysql_running; then up=1; break; fi
            sleep 1
        done
        if [ "$up" != 1 ]; then
            tail -25 "$MYSQL_LOG" >&2 || true
            die "scratch mariadbd did not come up within 30s (see $MYSQL_LOG)"
        fi
    fi
fi

# First-time init (or --reset): root password, databases, user, schema, seed.
MARKER="$SCRATCH_DIR/.db-initialized"
if [ ! -f "$MARKER" ]; then
    log "first-time DB init: setting root password, creating scratch DBs"
    if "$MYSQLADMIN" --socket="$MYSQL_SOCK" -u root ping >/dev/null 2>&1; then
        "$MYSQLADMIN" --socket="$MYSQL_SOCK" -u root password "$ROOT_PASS"
    else
        log "root password already set (marker missing but DB initialised)"
    fi
    "$MYSQL_CLI" --defaults-file="$ROOT_CNF" -e \
        "CREATE USER IF NOT EXISTS 'contract'@'127.0.0.1' IDENTIFIED BY '$DB_PASS';
         CREATE USER IF NOT EXISTS 'contract'@'localhost' IDENTIFIED BY '$DB_PASS';
         GRANT ALL PRIVILEGES ON \`paylist_contract_v2\`.* TO 'contract'@'127.0.0.1';
         GRANT ALL PRIVILEGES ON \`paylist_contract_v2\`.* TO 'contract'@'localhost';
         GRANT ALL PRIVILEGES ON \`paylist_contract_wg\`.* TO 'contract'@'127.0.0.1';
         GRANT ALL PRIVILEGES ON \`paylist_contract_wg\`.* TO 'contract'@'localhost';
         FLUSH PRIVILEGES;"
    touch "$MARKER"
fi

init_db() { # $1 = v2|wg
    local tgt="$1" db schema
    if [ "$tgt" = "v2" ]; then db="paylist_contract_v2"; schema="$SCRIPT_DIR/schema-v2.sql";
    else db="paylist_contract_wg"; schema="$SCRIPT_DIR/schema-wg.sql"; fi
    if [ "$RESET" = 1 ]; then
        log "reset: dropping $db"
        "$MYSQL_CLI" --defaults-file="$ROOT_CNF" -e "DROP DATABASE IF EXISTS \`$db\`;"
    fi
    local exists
    exists="$("$MYSQL_CLI" --defaults-file="$ROOT_CNF" -N -B -e \
        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME='$db';")"
    if [ "$exists" = "0" ]; then
        log "loading schema $schema into $db"
        "$MYSQL_CLI" --defaults-file="$ROOT_CNF" < "$schema"
        "$MYSQL_CLI" --defaults-file="$ROOT_CNF" -D "$db" -e \
            "INSERT INTO poin_status (status_pembayaran, poin) VALUES
               ('UNPAID', 10), ('ISOLIR', 5), ('SUSPEND', 1);
             INSERT INTO paylist_config (secret_hash, updated_by) VALUES
               ('$SECRET_HASH', 'contract-harness');"
    else
        log "$db already exists (use --reset to recreate)"
    fi
}
[ "$want_v2" = 1 ] && init_db v2
[ "$want_wg" = 1 ] && init_db wg

# --- C2 migration (audit columns + wg idx_amount; check-then-apply) ---------
# Same SQL as the production migration (migrate-c2-*.sql in this directory);
# applied here so the scratch schema matches post-migration production.
# Idempotent: skipped when rematch_count already exists (S3 restarts setup).
apply_c2_migration() { # $1 = v2|wg
    local tgt="$1" db table mig
    if [ "$tgt" = "v2" ]; then db="paylist_contract_v2"; table="qris_notif_log"; mig="$SCRIPT_DIR/migrate-c2-v2.sql";
    else db="paylist_contract_wg"; table="paylist_log"; mig="$SCRIPT_DIR/migrate-c2-wg.sql"; fi
    [ -f "$mig" ] || die "missing C2 migration: $mig"
    local has
    has="$("$MYSQL_CLI" --defaults-file="$ROOT_CNF" -N -B -e "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='$db' AND TABLE_NAME='$table' AND COLUMN_NAME='rematch_count';")"
    if [ "$has" = "0" ]; then
        log "applying C2 migration to $db ($table)"
        "$MYSQL_CLI" --defaults-file="$ROOT_CNF" -D "$db" < "$mig"
        log "C2 migration applied to $db"
    else
        log "C2 migration already present in $db"
    fi
}
[ "$want_v2" = 1 ] && apply_c2_migration v2
[ "$want_wg" = 1 ] && apply_c2_migration wg

# --- assemble docroots (always refreshed from the endpoint sources) ---------
write_db_config() { # $1 = dir, $2 = db name
    cat > "$1/db_config.php" <<EOF
<?php
// SCRATCH-ONLY db_config.php, generated by setup-scratch.sh.
// Points at the dedicated scratch MariaDB instance on port $MYSQL_PORT.
// NEVER copy the production db_config.php here; NEVER commit this file.
\$conn = new mysqli('127.0.0.1', 'contract', '$DB_PASS', '$2', $MYSQL_PORT);
\$conn->set_charset('utf8mb4');
EOF
    chmod 600 "$1/db_config.php"
}

if [ "$want_v2" = 1 ]; then
    HTML_V2="$SCRATCH_DIR/html-v2"
    rm -rf "$HTML_V2"
    mkdir -p "$HTML_V2/bayarQR" "$HTML_V2/functions"
    cp "$ENDPOINT_SRC_V2/bayarQR/notify.php" "$HTML_V2/bayarQR/notify.php"
    cp "$ENDPOINT_SRC_V2/bayarQR/qris_apply.php" "$HTML_V2/bayarQR/qris_apply.php"
    cp "$ENDPOINT_SRC_V2/bayarQR/qris_match.php" "$HTML_V2/bayarQR/qris_match.php"
    cp "$ENDPOINT_SRC_V2/bayarQR/qris_rematch.php" "$HTML_V2/bayarQR/qris_rematch.php"
    cp "$ENDPOINT_SRC_V2/functions/poin.php" "$HTML_V2/functions/poin.php"
    cp "$ENDPOINT_SRC_V2/csrf.php" "$HTML_V2/csrf.php"
    write_db_config "$HTML_V2" "paylist_contract_v2"
    # Harness-owned probe (NOT endpoint code): calls the REAL kode-birth
    # hook function with a synthetic bill id + kode, to prove the hook
    # logic (lookup + rematch + apply) without rendering the member page.
    cat > "$HTML_V2/probe-hook.php" <<'PHP'
<?php
// Harness probe only: exercises qris_kode_birth_rematch() directly.
// Not part of the endpoint; lives only in the scratch docroot.
header('Content-Type: application/json');
require_once __DIR__ . '/db_config.php';
$db = $conn ?? ($mysqli ?? null);
if (!($db instanceof mysqli)) { http_response_code(500); echo json_encode(['ok' => false, 'error' => 'db']); exit; }
require_once __DIR__ . '/bayarQR/qris_match.php';
$bill = (int)($_POST['bill_id'] ?? 0);
$kode = (int)($_POST['kode'] ?? 0);
if ($bill <= 0 || $kode <= 0) { http_response_code(400); echo json_encode(['ok' => false, 'error' => 'bad_input']); exit; }
echo json_encode(['ok' => true, 'hook' => qris_kode_birth_rematch($db, $bill, $kode)]);
PHP
    # Harness-owned probe (NOT endpoint code): calls the REAL re-match
    # function qris_rematch_log() directly — the same function the admin
    # endpoint (qris_rematch.php) calls after its session+CSRF guard.
    # Used for concurrency races (review #7 finding 2), where driving the
    # HTTP endpoint would need an admin session; the guard layer itself
    # is proven separately (R7).
    cat > "$HTML_V2/probe-rematch.php" <<'PHP'
<?php
// Harness probe only: exercises qris_rematch_log() directly.
// Not part of the endpoint; lives only in the scratch docroot.
header('Content-Type: application/json');
require_once __DIR__ . '/db_config.php';
$db = $conn ?? ($mysqli ?? null);
if (!($db instanceof mysqli)) { http_response_code(500); echo json_encode(['ok' => false, 'error' => 'db']); exit; }
require_once __DIR__ . '/bayarQR/qris_match.php';
$log = (int)($_POST['log_id'] ?? 0);
if ($log <= 0) { http_response_code(400); echo json_encode(['ok' => false, 'error' => 'bad_input']); exit; }
echo json_encode(qris_rematch_log($db, $log, 'harness-race'));
PHP
    # Harness-owned probe (NOT endpoint code): calls the REAL apply
    # function qris_apply_payment() directly — the same function the
    # notify path and qris_rematch_log() call. Used by run-review8.sh
    # (review #8 P1 stale match binding) to drive an apply while
    # another connection holds the log row lock, producing the exact
    # deterministic interleaving (selection -> binding switch -> lock)
    # that a timing race cannot reproduce reliably. Optional POST
    # lock_wait_timeout=N sets the session innodb_lock_wait_timeout
    # for the 1205 rollback probe; default = server default (50s).
    cat > "$HTML_V2/probe-apply.php" <<'PHP'
<?php
// Harness probe only: exercises qris_apply_payment() directly.
// Not part of the endpoint; lives only in the scratch docroot.
header('Content-Type: application/json');
require_once __DIR__ . '/db_config.php';
$db = $conn ?? ($mysqli ?? null);
if (!($db instanceof mysqli)) { http_response_code(500); echo json_encode(['ok' => false, 'error' => 'db']); exit; }
require_once __DIR__ . '/bayarQR/qris_apply.php';
$log = (int)($_POST['log_id'] ?? 0);
if ($log <= 0) { http_response_code(400); echo json_encode(['ok' => false, 'error' => 'bad_input']); exit; }
$lwt = (int)($_POST['lock_wait_timeout'] ?? 0);
if ($lwt > 0) { $db->query("SET SESSION innodb_lock_wait_timeout = $lwt"); }
echo json_encode(['ok' => true, 'apply' => qris_apply_payment($db, $log)]);
PHP
    log "html-v2 assembled:"
    sha256sum "$HTML_V2/bayarQR/notify.php" "$HTML_V2/bayarQR/qris_apply.php" "$HTML_V2/bayarQR/qris_match.php" "$HTML_V2/bayarQR/qris_rematch.php" "$HTML_V2/functions/poin.php" "$HTML_V2/csrf.php" | sed 's/^/[setup]   /'
fi

if [ "$want_wg" = 1 ]; then
    HTML_WG="$SCRATCH_DIR/html-wg"
    rm -rf "$HTML_WG"
    mkdir -p "$HTML_WG/paylist"
    cp "$ENDPOINT_SRC_WG/paylist/notify.php" "$HTML_WG/paylist/notify.php"
    cp "$ENDPOINT_SRC_WG/paylist/paylist_apply.php" "$HTML_WG/paylist/paylist_apply.php"
    cp "$ENDPOINT_SRC_WG/paylist/paylist_match.php" "$HTML_WG/paylist/paylist_match.php"
    cp "$ENDPOINT_SRC_WG/paylist/paylist_rematch.php" "$HTML_WG/paylist/paylist_rematch.php"
    cp "$ENDPOINT_SRC_WG/csrf.php" "$HTML_WG/csrf.php"
    write_db_config "$HTML_WG" "paylist_contract_wg"
    # Harness-owned probe (NOT endpoint code): calls the REAL kode-birth
    # hook function with a synthetic bill id + kode.
    cat > "$HTML_WG/probe-hook.php" <<'PHP'
<?php
// Harness probe only: exercises paylist_kode_birth_rematch() directly.
// Not part of the endpoint; lives only in the scratch docroot.
header('Content-Type: application/json');
require_once __DIR__ . '/db_config.php';
$db = $conn ?? ($mysqli ?? null);
if (!($db instanceof mysqli)) { http_response_code(500); echo json_encode(['ok' => false, 'error' => 'db']); exit; }
require_once __DIR__ . '/paylist/paylist_match.php';
$bill = (int)($_POST['bill_id'] ?? 0);
$kode = (int)($_POST['kode'] ?? 0);
if ($bill <= 0 || $kode <= 0) { http_response_code(400); echo json_encode(['ok' => false, 'error' => 'bad_input']); exit; }
echo json_encode(['ok' => true, 'hook' => paylist_kode_birth_rematch($db, $bill, $kode)]);
PHP
    # Harness-owned probe (NOT endpoint code): calls the REAL re-match
    # function paylist_rematch_log() directly — the same function the admin
    # endpoint (paylist_rematch.php) calls after its session+CSRF guard.
    # Used for concurrency races (review #7 finding 2); the guard layer
    # itself is proven separately (R7).
    cat > "$HTML_WG/probe-rematch.php" <<'PHP'
<?php
// Harness probe only: exercises paylist_rematch_log() directly.
// Not part of the endpoint; lives only in the scratch docroot.
header('Content-Type: application/json');
require_once __DIR__ . '/db_config.php';
$db = $conn ?? ($mysqli ?? null);
if (!($db instanceof mysqli)) { http_response_code(500); echo json_encode(['ok' => false, 'error' => 'db']); exit; }
require_once __DIR__ . '/paylist/paylist_match.php';
$log = (int)($_POST['log_id'] ?? 0);
if ($log <= 0) { http_response_code(400); echo json_encode(['ok' => false, 'error' => 'bad_input']); exit; }
echo json_encode(paylist_rematch_log($db, $log, 'harness-race'));
PHP
    # Harness-owned probe (NOT endpoint code): calls the REAL apply
    # function paylist_apply_payment() directly (cermin probe-apply.php
    # v2New di atas; mencakup cabang top-up lewat dispatch di dalamnya).
    # Dipakai run-review8.sh untuk interleaving stale-binding yang
    # deterministik + probe rollback 1205 (POST lock_wait_timeout).
    cat > "$HTML_WG/probe-apply.php" <<'PHP'
<?php
// Harness probe only: exercises paylist_apply_payment() directly.
// Not part of the endpoint; lives only in the scratch docroot.
header('Content-Type: application/json');
require_once __DIR__ . '/db_config.php';
$db = $conn ?? ($mysqli ?? null);
if (!($db instanceof mysqli)) { http_response_code(500); echo json_encode(['ok' => false, 'error' => 'db']); exit; }
require_once __DIR__ . '/paylist/paylist_apply.php';
$log = (int)($_POST['log_id'] ?? 0);
if ($log <= 0) { http_response_code(400); echo json_encode(['ok' => false, 'error' => 'bad_input']); exit; }
$lwt = (int)($_POST['lock_wait_timeout'] ?? 0);
if ($lwt > 0) { $db->query("SET SESSION innodb_lock_wait_timeout = $lwt"); }
echo json_encode(['ok' => true, 'apply' => paylist_apply_payment($db, $log)]);
PHP
    # Harness-owned probe (NOT endpoint code): verifies from inside a worker
    # that the warganet WhatsApp curl cannot leave the box.
    cat > "$HTML_WG/probe-curl.php" <<'PHP'
<?php
// Harness probe only: reproduces the exact outbound call paylist_apply.php
// makes, to prove it dies at the dead proxy. Not part of the endpoint.
header('Content-Type: application/json');
$c = curl_init('https://wawarga.netter.my.id/send/message');
curl_setopt_array($c, [
    CURLOPT_RETURNTRANSFER => true,
    CURLOPT_TIMEOUT => 5,
    CURLOPT_HTTPHEADER => ['Content-Type: application/json'],
    CURLOPT_POSTFIELDS => json_encode(['phone' => '', 'message' => 'probe']),
]);
curl_exec($c);
echo json_encode(['http_code' => curl_getinfo($c, CURLINFO_RESPONSE_CODE),
                  'error' => curl_error($c)]);
PHP
    log "html-wg assembled:"
    sha256sum "$HTML_WG/paylist/notify.php" "$HTML_WG/paylist/paylist_apply.php" "$HTML_WG/paylist/paylist_match.php" "$HTML_WG/paylist/paylist_rematch.php" "$HTML_WG/csrf.php" | sed 's/^/[setup]   /'
fi

# --- PHP workers (dead-proxy env; 4 per target for true concurrency) --------
start_workers() { # $1 = target, $2 = base port, $3 = docroot
    local tgt="$1" base="$2" root="$3" i port pidfile
    for i in $(seq 0 $((WORKERS - 1))); do
        port=$((base + i))
        pidfile="$SCRATCH_DIR/php-$tgt-$port.pid"
        if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
            log "php worker $tgt:$port already running (pid $(cat "$pidfile"))"
            continue
        fi
        if ss -ltn 2>/dev/null | grep -q ":$port "; then
            die "port $port is already in use by an unknown process"
        fi
        setsid env -i PATH="$PATH" HOME="$HOME" \
            https_proxy="http://127.0.0.1:$DEAD_PROXY_PORT" \
            http_proxy="http://127.0.0.1:$DEAD_PROXY_PORT" \
            all_proxy="http://127.0.0.1:$DEAD_PROXY_PORT" \
            php -d session.save_path="$SCRATCH_DIR/sessions" -S "127.0.0.1:$port" -t "$root" \
            >"$SCRATCH_DIR/php-$tgt-$port.log" 2>&1 < /dev/null &
        # $! is setsid's pid, not php's; resolve the real worker pid below.
        log "php worker $tgt:$port starting"
    done
    # Liveness: GET on notify.php must answer 405 (method_not_allowed).
    local path="bayarQR/notify.php"
    [ "$tgt" = "wg" ] && path="paylist/notify.php"
    for i in $(seq 0 $((WORKERS - 1))); do
        port=$((base + i))
        pidfile="$SCRATCH_DIR/php-$tgt-$port.pid"
        code=""
        for _ in $(seq 1 15); do
            code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/$path" || true)"
            [ "$code" = "405" ] && break
            sleep 1
        done
        [ "$code" = "405" ] || die "php worker $tgt:$port did not come up (last HTTP $code)"
        # Record the actual php pid for teardown/restart. Pola pgrep harus
        # cocok dengan baris perintah worker APA ADANYA (ada flag
        # `-d session.save_path=...` di antara `php` dan `-S`); pola lama
        # `php -S 127.0.0.1:$port ` tidak cocok sehingga pgrep gagal dan
        # `set -euo pipefail` mematikan setup DIAM-DIAM di sini (23 Nov).
        pgrep -f " -S 127.0.0.1:$port " | head -1 > "$pidfile" || die "could not resolve pid for worker $tgt:$port"
        [ -s "$pidfile" ] || die "could not resolve pid for worker $tgt:$port (empty pidfile)"
        log "php worker $tgt:$port up (pid $(cat "$pidfile"))"
    done
}
# Shared PHP session store for the admin-session fixture (the rematch
# endpoint's session guard reads it; run-contract.sh seeds it).
mkdir -p "$SCRATCH_DIR/sessions"
[ "$want_v2" = 1 ] && start_workers v2 "$V2_HTTP_BASE" "$SCRATCH_DIR/html-v2"
[ "$want_wg" = 1 ] && start_workers wg "$WG_HTTP_BASE" "$SCRATCH_DIR/html-wg"

# --- isolation probe: the warganet WhatsApp call must die locally -----------
if [ "$want_wg" = 1 ]; then
    probe="$(curl -s -m 10 "http://127.0.0.1:$WG_HTTP_BASE/probe-curl.php")"
    log "isolation probe: $probe"
    probe_code="$(printf '%s' "$probe" | grep -o '"http_code":[0-9]*' | cut -d: -f2)"
    if [ "$probe_code" != "0" ] || ! printf '%s' "$probe" | grep -q "$DEAD_PROXY_PORT"; then
        die "ISOLATION BROKEN: the in-worker curl reached the network ($probe). Workers left running for inspection; run teardown-scratch.sh, fix the proxy env, and retry. DO NOT run scenarios."
    fi
    log "isolation proven: outbound curl dies at the dead proxy (http_code 0)"
fi

# --- smoke test: heartbeat through each target ------------------------------
smoke() { # $1 = target, $2 = base port, $3 = path
    local resp
    resp="$(curl -s -m 10 -X POST "http://127.0.0.1:$2/$3" \
        -H 'Content-Type: application/json' -H "X-QRIS-Secret: $SECRET" \
        -d '{"type":"heartbeat","device":"smoke","dedupe_key":"smoke-'"$1"'-1","title":"","text":""}')"
    printf '%s' "$resp" | grep -q '"ok":true' || die "smoke heartbeat $1 failed: $resp"
    log "smoke heartbeat $1 OK: $resp"
}
[ "$want_v2" = 1 ] && smoke v2 "$V2_HTTP_BASE" "bayarQR/notify.php"
[ "$want_wg" = 1 ] && smoke wg "$WG_HTTP_BASE" "paylist/notify.php"

# Rematch endpoint answers 405 to GET (POST-only) on both targets.
smoke_rematch_get() { # $1 = target, $2 = base port, $3 = path
    local code
    code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$2/$3" || true)"
    [ "$code" = "405" ] || die "rematch GET smoke $1 failed (HTTP $code, expected 405)"
    log "rematch GET smoke $1 OK (405 POST-only)"
}
[ "$want_v2" = 1 ] && smoke_rematch_get v2 "$V2_HTTP_BASE" "bayarQR/qris_rematch.php"
[ "$want_wg" = 1 ] && smoke_rematch_get wg "$WG_HTTP_BASE" "paylist/paylist_rematch.php"

log "scratch stack ready: mariadbd :$MYSQL_PORT, v2 workers :$V2_HTTP_BASE-$((V2_HTTP_BASE + WORKERS - 1)), wg workers :$WG_HTTP_BASE-$((WG_HTTP_BASE + WORKERS - 1))"
log "run: ./run-contract.sh [--target v2|wg|all]"
