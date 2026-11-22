#!/usr/bin/env bash
# teardown-scratch.sh — stop the scratch stack started by setup-scratch.sh.
#
# Usage: ./teardown-scratch.sh [--purge]
#   --purge also deletes the scratch datadir, assembled docroots, secrets and
#   markers (scratch-only data; the endpoint sources are NOT deleted).
# Env:   SCRATCH_DIR (default /home/warganet/paylist-contract)

set -u
SCRATCH_DIR="${SCRATCH_DIR:-/home/warganet/paylist-contract}"
PURGE=0
[ "${1:-}" = "--purge" ] && PURGE=1

log() { echo "[teardown] $*"; }

# PHP workers.
for pidfile in "$SCRATCH_DIR"/php-*.pid; do
    [ -e "$pidfile" ] || continue
    pid="$(cat "$pidfile" 2>/dev/null || true)"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null || true
        log "stopped php worker pid $pid"
    fi
    rm -f "$pidfile"
done

# Scratch MariaDB (own instance only; system MariaDB on :3306 untouched).
MYSQLADMIN="$(command -v mariadb-admin || command -v mysqladmin || true)"
if [ -n "$MYSQLADMIN" ] && [ -S "$SCRATCH_DIR/mysql.sock" ] && [ -f "$SCRATCH_DIR/.my-root.cnf" ]; then
    "$MYSQLADMIN" --defaults-file="$SCRATCH_DIR/.my-root.cnf" shutdown 2>/dev/null || true
    for _ in $(seq 1 15); do
        [ -S "$SCRATCH_DIR/mysql.sock" ] || break
        sleep 1
    done
    log "scratch mariadbd stopped"
fi

if [ "$PURGE" = 1 ]; then
    rm -rf "$SCRATCH_DIR/mysql-data" "$SCRATCH_DIR/html-v2" "$SCRATCH_DIR/html-wg" \
        "$SCRATCH_DIR/.env" "$SCRATCH_DIR/.my-root.cnf" "$SCRATCH_DIR/.my-contract.cnf" \
        "$SCRATCH_DIR/.db-initialized" "$SCRATCH_DIR/mysql.sock" "$SCRATCH_DIR/mysql.pid" \
        "$SCRATCH_DIR/mysql.log" "$SCRATCH_DIR/php-"*.log
    log "purged scratch state (endpoint sources kept)"
fi

log "done"
