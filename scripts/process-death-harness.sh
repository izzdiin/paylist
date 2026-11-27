#!/bin/bash
# Process-death harness (Stage D audit follow-up, PR #5 re-review
# 27 Nov): ACTUAL SIGKILL evidence for the capture pipeline.
#
# Flow:
#   1. Install the debug app + instrumented test APKs.
#   2. Run the two SETUP phases (ProcessDeathHarnessTest
#      test01/test02) — each leaves durable state in a file-backed
#      Room database and deliberately never closes anything.
#   3. Kill the app process with kill -9 (falling back to
#      `am kill`, then `am force-stop`, each reported honestly).
#   4. Run the two RECOVERY phases (test03/test04) in a fresh
#      process and assert exactly-once recovery.
#
# Requires: a connected device or emulator (`adb devices`), the
# APKs built (assembleDebug + assembleDebugAndroidTest). Output is
# tee'd to process-death-evidence.log in the current directory.
#
# This harness is the ONLY basis for a process-kill claim: the
# JVM unit suite cannot kill its own process, and coordinator
# replacement is not a SIGKILL.
set -uo pipefail

PKG="com.netter.paylist"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
CLS="com.netter.paylist.collector.ProcessDeathHarnessTest"
APP_APK="${APP_APK:-app/build/outputs/apk/debug/app-debug.apk}"
TEST_APK="${TEST_APK:-app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}"
LOG="process-death-evidence.log"

exec > >(tee -a "$LOG") 2>&1
echo "=== process-death harness $(date -Is) ==="

adb get-state >/dev/null 2>&1 || { echo "FAIL: no adb device/emulator connected"; exit 1; }
[ -f "$APP_APK" ] || { echo "FAIL: app APK not found: $APP_APK (build assembleDebug first)"; exit 1; }
[ -f "$TEST_APK" ] || { echo "FAIL: test APK not found: $TEST_APK (build assembleDebugAndroidTest first)"; exit 1; }

echo "--- install ---"
adb install -r "$APP_APK" || exit 1
adb install -r "$TEST_APK" || exit 1

run_phase() { # $1 = comma-separated class#method list, $2 = label
    echo "--- instrumentation: $2 ---"
    adb shell am instrument -w -e class "$1" "$PKG.test/$RUNNER"
}

run_phase "$CLS#test01_afterStage_setup_acceptedParked,$CLS#test02_beforeStage_setup_receivedOnly" "setup phases"

echo "--- SIGKILL ---"
PID="$(adb shell pidof "$PKG" | tr -d '\r' | awk '{print $1}')"
if [ -z "$PID" ]; then
    echo "FAIL: app process not running after setup phases (nothing to kill)"
    exit 1
fi
echo "app pid: $PID"
KILL_METHOD=""
if adb shell "kill -9 $PID" >/dev/null 2>&1; then
    KILL_METHOD="kill -9"
else
    echo "kill -9 not permitted from adb shell; falling back to am kill"
    adb shell am kill "$PKG" >/dev/null 2>&1 && KILL_METHOD="am kill"
fi
sleep 1
if [ -n "$(adb shell pidof "$PKG" | tr -d '\r')" ]; then
    echo "process still alive after $KILL_METHOD; falling back to am force-stop"
    adb shell am force-stop "$PKG"
    KILL_METHOD="am force-stop"
    sleep 1
fi
if [ -n "$(adb shell pidof "$PKG" | tr -d '\r')" ]; then
    echo "FAIL: could not kill the app process"
    exit 1
fi
echo "process killed via: $KILL_METHOD"

run_phase "$CLS#test03_afterStage_recovery_promotedExactlyOnce,$CLS#test04_beforeStage_recovery_rescanExactlyOnce" "recovery phases"

echo "=== harness done $(date -Is) — check for OK/FAILURES in the instrumentation output above ==="
