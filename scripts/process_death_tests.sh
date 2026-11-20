#!/bin/bash
# Real process-death tests (ChatGPT re-audit P1-1, R03/R04/R05) with a
# PID-handshake runner contract (ChatGPT re-review, PR #4 @ a6bfa1f).
#
# PhaseA drives the cutover / sender to the crash point and KILLS ITS
# OWN PROCESS; PhaseB (a fresh process) re-opens the same durable
# state and asserts the recovery. The two phases run as separate
# `am instrument` invocations because a kill ends the run it is in —
# they can never share one invocation, which is also why they are
# excluded from `connectedDebugAndroidTest` (notClass) and run here.
#
# RUNNER CONTRACT (why a green run means a real crash happened)
# -------------------------------------------------------------
# A PhaseA run counts as "the process died at the kill point" only
# if ALL of these hold:
#   1. its `am instrument` output shows NO clean pass ("OK (") and NO
#      test failure ("FAILURES") — a clean pass means the method
#      returned normally without killing; a failure means an assertion
#      fired before the kill;
#   2. the app process is GONE afterwards (`pidof` empty) — the kill
#      observed independently of anything the test itself reports;
#   3. PhaseB requires PhaseA's marker (kill boundary reached) AND a
#      fresh process identity (PID + process start time differ from
#      the identity PhaseA recorded in the marker) — if the system
#      reused PhaseA's still-alive process for PhaseB, the run fails.
# The old contract (marker only, PhaseA exit code discarded with
# `|| true`) could pass a run in which no kill ever happened.
#
# Usage: run on a machine with an emulator (or device) attached and
# Gradle 8.9 on PATH (the CI job provides both). Exits 0 only when
# every PhaseB passes; a PhaseA death-check failure — including a
# missing kill — fails the script without running that PhaseB.
#
#   bash scripts/process_death_tests.sh                # R03/R04/R05
set -u

TEST_PKG="com.netter.paylist.test"
APP_PKG="com.netter.paylist"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
PHASE_A="com.netter.paylist.ProcessDeathPhaseA"
PHASE_B="com.netter.paylist.ProcessDeathPhaseB"

# scenario -> "phaseA method : phaseB method"
SCENARIOS=(
  "r03KillAfterImportBeforeSweep:r03RetryCompletesWithoutDuplicatesOrLoss"
  "r04KillAfterSweepBeforeWorkerOnly:r04CommitWorkerOnlyOnRestart"
  "r05KillAfterPostBeforeAckPersistence:r05RetryDeliversExactlyOnce"
)

fail=0

cd "$(dirname "$0")/.." || exit 1

echo "== waiting for a device/emulator =="
adb wait-for-device
adb shell 'echo device ready, api: $(getprop ro.build.version.sdk)'

echo "== building debug + androidTest APKs =="
gradle assembleDebug assembleDebugAndroidTest --no-daemon --console=plain \
  -Dorg.gradle.jvmargs="-Xmx2048m" -Dkotlin.daemon.jvm.options="-Xmx1024m" || exit 1

echo "== installing APKs =="
adb install -r app/build/outputs/apk/debug/app-debug.apk || exit 1
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || exit 1

# Runs one PhaseA method and verifies the process actually died at
# the kill point. Prints the instrumentation output, then the
# verdict. Returns 0 only when the death is confirmed (abnormal exit
# + process gone). Sets PHASE_A_VERDICT to a one-line description.
PHASE_A_VERDICT=""
run_phase_a() {
  local method="$1"
  echo "== PhaseA $method (expected: the process dies) =="
  local out rc
  out=$(timeout 180 adb shell am instrument -w -e class "$PHASE_A#$method" "$TEST_PKG/$RUNNER" 2>&1)
  rc=$?
  echo "$out"
  echo "-- PhaseA $method: am instrument exited $rc --"
  if [ "$rc" -eq 124 ]; then
    PHASE_A_VERDICT="TIMEOUT (killed by the 180s guard, no kill observed)"
    echo "DEATH-CHECK FAIL: $PHASE_A_VERDICT"
    return 1
  fi
  if echo "$out" | grep -q "FAILURES"; then
    PHASE_A_VERDICT="ASSERTION FAILURE before the kill point"
    echo "DEATH-CHECK FAIL: $PHASE_A_VERDICT"
    return 1
  fi
  if echo "$out" | grep -q "^OK ("; then
    PHASE_A_VERDICT="RETURNED NORMALLY (clean pass, the process was never killed)"
    echo "DEATH-CHECK FAIL: $PHASE_A_VERDICT"
    return 1
  fi
  local alive
  alive=$(adb shell pidof "$APP_PKG" 2>/dev/null || true)
  if [ -n "$alive" ]; then
    PHASE_A_VERDICT="PROCESS STILL ALIVE (pidof $APP_PKG -> $alive)"
    echo "DEATH-CHECK FAIL: $PHASE_A_VERDICT"
    return 1
  fi
  PHASE_A_VERDICT="DIED (abnormal exit, process gone)"
  echo "DEATH-CHECK PASS: $PHASE_A_VERDICT"
  return 0
}

# Runs one PhaseB method, which must pass with a literal OK verdict.
# Returns 0 on pass; sets PHASE_B_VERDICT to a one-line description.
PHASE_B_VERDICT=""
run_phase_b() {
  local method="$1"
  echo "== PhaseB $method (must pass) =="
  local out rc
  out=$(timeout 180 adb shell am instrument -w -e class "$PHASE_B#$method" "$TEST_PKG/$RUNNER" 2>&1)
  rc=$?
  echo "$out"
  if [ "$rc" -ne 0 ]; then
    PHASE_B_VERDICT="am instrument exited $rc"
    echo "FAIL: PhaseB $method ($PHASE_B_VERDICT)"
    return 1
  fi
  if echo "$out" | grep -q "^OK ("; then
    PHASE_B_VERDICT="OK verdict"
    echo "PASS: PhaseB $method"
    return 0
  fi
  PHASE_B_VERDICT="no OK verdict in output"
  echo "FAIL: PhaseB $method ($PHASE_B_VERDICT)"
  return 1
}

for pair in "${SCENARIOS[@]}"; do
  method_a="${pair%%:*}"
  method_b="${pair##*:}"
  if ! run_phase_a "$method_a"; then
    echo "SCENARIO $method_a: FAILED at PhaseA ($PHASE_A_VERDICT) — skipping PhaseB"
    fail=1
    continue
  fi
  if ! run_phase_b "$method_b"; then
    fail=1
  fi
done

if [ "$fail" -ne 0 ]; then
  echo "PROCESS-DEATH TESTS: FAILED"
  exit 1
fi
echo "PROCESS-DEATH TESTS: ALL PASSED"
