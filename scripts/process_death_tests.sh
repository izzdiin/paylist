#!/bin/bash
# Real process-death tests (ChatGPT re-audit P1-1, R03/R04/R05).
#
# PhaseA drives the cutover / sender to the crash point and KILLS ITS
# OWN PROCESS; PhaseB (a fresh process) re-opens the same durable
# state and asserts the recovery. The two phases run as separate
# `am instrument` invocations because a kill ends the run it is in —
# they can never share one invocation, which is also why they are
# excluded from `connectedDebugAndroidTest` (notClass) and run here.
#
# Usage: run on a machine with an emulator (or device) attached and
# Gradle 8.9 on PATH (the CI job provides both). Exits 0 only when
# every PhaseB passes; a PhaseB failure — including a missing PhaseA
# marker, which means PhaseA never reached its kill point — fails
# the script.
#
# NOTE: PhaseA's abnormal exit is EXPECTED (its process dies by
# design). The script does not judge PhaseA by its exit code; the
# verdict comes from PhaseB, which requires PhaseA's marker.
set -u

TEST_PKG="com.netter.paylist.test"
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

run_phase_a() {
  local method="$1"
  echo "== PhaseA $method (expected: the process dies) =="
  # The kill ends the instrumentation abnormally; a timeout guard
  # keeps a hung run from blocking the job (PhaseB then fails on
  # the missing marker, which is the honest verdict).
  timeout 180 adb shell am instrument -w -e class "$PHASE_A#$method" "$TEST_PKG/$RUNNER" || true
}

run_phase_b() {
  local method="$1"
  echo "== PhaseB $method (must pass) =="
  local out
  local rc
  out=$(timeout 180 adb shell am instrument -w -e class "$PHASE_B#$method" "$TEST_PKG/$RUNNER" 2>&1)
  rc=$?
  echo "$out"
  if [ "$rc" -ne 0 ]; then
    echo "FAIL: PhaseB $method (am instrument exited $rc)"
    fail=1
    return
  fi
  if echo "$out" | grep -q "^OK ("; then
    echo "PASS: PhaseB $method"
  else
    echo "FAIL: PhaseB $method (no OK verdict in output)"
    fail=1
  fi
}

for pair in "${SCENARIOS[@]}"; do
  method_a="${pair%%:*}"
  method_b="${pair##*:}"
  run_phase_a "$method_a"
  run_phase_b "$method_b"
done

if [ "$fail" -ne 0 ]; then
  echo "PROCESS-DEATH TESTS: FAILED"
  exit 1
fi
echo "PROCESS-DEATH TESTS: ALL PASSED"
