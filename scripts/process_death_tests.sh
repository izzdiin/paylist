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
# NEGATIVE-CONTROL ORACLE (re-review #4: the control's pass predicate
# must prove a no-kill was SPECIFICALLY detected, not merely that
# something failed). The control passes only when ALL of these hold:
#   1. PhaseA is classified RETURNED NORMALLY or PROCESS STILL ALIVE —
#      where RETURNED NORMALLY additionally requires rc == 0 and the
#      exact one-test verdict "OK (1 test)" (an "OK (0 tests)" run or
#      a nonzero exit means the method did not run as designed);
#   2. the durable r06 marker exists and is well-formed
#      ("a-complete pid=<pid> start=<startTicks>") — PhaseA's setUp
#      clears the prefs, so a marker found after the run was written
#      by THIS PhaseA run; when a process is still alive its PID (and
#      start time, when readable) must match the marker;
#   3. a PhaseB failure counts as the second catch ONLY when it is the
#      expected fresh-process assertion; a missing-marker, recovery
#      assertion, or infrastructure failure makes the run INVALID.
#
# Usage: run on a machine with an emulator (or device) attached and
# Gradle 8.9 on PATH (the CI job provides both). Exits 0 only when
# every PhaseB passes; a PhaseA death-check failure — including a
# missing kill — fails the script without running that PhaseB.
#
#   bash scripts/process_death_tests.sh                # R03/R04/R05
#   bash scripts/process_death_tests.sh --negative-control
#     Runs ONLY the r06 scenario, in which PhaseA writes the marker
#     but deliberately does NOT kill. The exit code is INVERTED: 0
#     means the harness correctly FAILED the run (the control works),
#     non-zero means the run passed (the false-positive is still
#     open). CI runs this as a separate job.
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

# Negative control: PhaseA writes the marker but deliberately does
# NOT kill. The harness must fail this run.
NEGATIVE_CONTROL="r06NegativeControlMarkerWithoutKill:r06NegativeControlMustFail"

fail=0
negative_control=0
if [ "${1:-}" = "--negative-control" ]; then
  negative_control=1
fi

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
  # Strip carriage returns: adb output may carry \r, which would
  # break the anchored "OK (1 test)" match below.
  out="${out//$'\r'/}"
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
    # A clean-looking pass is only a RETURNED NORMALLY verdict when it
    # is a genuine one-test pass: rc == 0 and exactly "OK (1 test)".
    # Anything else ("OK (0 tests)", a nonzero exit) means the method
    # did not run as designed — it must NOT fall through to the pidof
    # check, where a gone process would misclassify it as DIED.
    if [ "$rc" -ne 0 ]; then
      PHASE_A_VERDICT="INCONSISTENT RUN (clean-looking output but am instrument exited $rc)"
      echo "DEATH-CHECK FAIL: $PHASE_A_VERDICT"
      return 1
    fi
    if ! echo "$out" | grep -q "^OK (1 test)$"; then
      PHASE_A_VERDICT="NO SINGLE-TEST PASS (clean output but not exactly 'OK (1 test)' — the test method may not have run)"
      echo "DEATH-CHECK FAIL: $PHASE_A_VERDICT"
      return 1
    fi
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
# Returns 0 on pass; sets PHASE_B_VERDICT to a one-line description
# and PHASE_B_CLASS to pass | fresh_process | missing_marker |
# assertion | infra, so the negative-control branch can tell the
# expected fresh-process catch apart from unrelated failures.
PHASE_B_VERDICT=""
PHASE_B_CLASS=""
run_phase_b() {
  local method="$1"
  echo "== PhaseB $method (must pass) =="
  local out rc
  out=$(timeout 180 adb shell am instrument -w -e class "$PHASE_B#$method" "$TEST_PKG/$RUNNER" 2>&1)
  rc=$?
  out="${out//$'\r'/}"
  echo "$out"
  if [ "$rc" -eq 0 ] && echo "$out" | grep -q "^OK ("; then
    PHASE_B_VERDICT="OK verdict"
    PHASE_B_CLASS="pass"
    echo "PASS: PhaseB $method"
    return 0
  fi
  # Classify the failure by its cause. The message strings mirror
  # ProcessDeathPhaseB.requireFreshProcess: the fresh-process
  # assertFalse ("PhaseB runs in PhaseA's process") is the expected
  # second catch for a no-kill run; the missing-marker assertNotNull
  # ("PhaseA must have reached the kill point") means PhaseA never
  # reached its kill point at all.
  if echo "$out" | grep -q "PhaseB runs in PhaseA's process"; then
    PHASE_B_CLASS="fresh_process"
    PHASE_B_VERDICT="fresh-process assertion (PhaseB ran in PhaseA's process — the kill never happened)"
  elif echo "$out" | grep -q "PhaseA must have reached the kill point"; then
    PHASE_B_CLASS="missing_marker"
    PHASE_B_VERDICT="missing PhaseA marker (PhaseA never reached the kill point)"
  elif echo "$out" | grep -q "FAILURES"; then
    PHASE_B_CLASS="assertion"
    PHASE_B_VERDICT="test assertion failed (am instrument exited $rc)"
  elif [ "$rc" -eq 0 ]; then
    PHASE_B_CLASS="infra"
    PHASE_B_VERDICT="no OK verdict in output (am instrument exited 0)"
  else
    PHASE_B_CLASS="infra"
    PHASE_B_VERDICT="am instrument exited $rc"
  fi
  echo "FAIL: PhaseB $method ($PHASE_B_VERDICT)"
  return 1
}

# --- Negative-control marker verification (re-review #4) ---
# The r06 marker lives in the app's "paylist" SharedPreferences
# (/data/data/<pkg>/shared_prefs/paylist.xml), key
# "process_death_r06", value "a-complete pid=<pid>
# start=<startTicks>" (ProcessDeathHarness.writeMarker). PhaseA's
# setUp clears those prefs, so a marker found after the run was
# written by THIS PhaseA run. The runner reads it back via run-as
# (debug build) and verifies it independently of the test's own
# report — a no-kill verdict without a marker proves nothing.
MARKER_PID=""
MARKER_START=""
read_control_marker() {
  local scenario="$1"
  local xml value
  xml=$(adb shell run-as "$APP_PKG" cat "/data/data/$APP_PKG/shared_prefs/paylist.xml" 2>/dev/null) || return 1
  [ -n "$xml" ] || return 1
  value=$(printf '%s' "$xml" | grep -o "<string name=\"process_death_${scenario}\">[^<]*</string>" | head -n 1 | sed 's/^[^>]*>//; s|</string>$||')
  [ -n "$value" ] || return 1
  # Full-format validation + extraction in one step: anything that is
  # not exactly "a-complete pid=<digits> start=<digits>" fails.
  MARKER_PID=$(printf '%s' "$value" | sed -nE 's/^a-complete pid=([0-9]+) start=(-?[0-9]+)$/\1/p')
  MARKER_START=$(printf '%s' "$value" | sed -nE 's/^a-complete pid=([0-9]+) start=(-?[0-9]+)$/\2/p')
  [ -n "$MARKER_PID" ] && [ -n "$MARKER_START" ]
}

# Process start time (field 22 of /proc/<pid>/stat), or empty when
# unreadable. Mirrors ProcessDeathHarness.currentProcessIdentity:
# comm may contain spaces/parens, so split after the LAST ") ".
proc_start_ticks() {
  local pid="$1" stat rest
  stat=$(adb shell cat "/proc/$pid/stat" 2>/dev/null) || return 1
  [ -n "$stat" ] || return 1
  rest="${stat##*) }"
  printf '%s' "$rest" | awk '{print $20}'
}

# Verifies the control marker for scenario $1. Exits 1 (INVALID) when
# the marker is missing/malformed, or when a still-alive process does
# not match the recorded identity. When no process is alive the
# existence + well-formedness of the marker is the evidence (PhaseA's
# setUp cleared the prefs, so it can only be this run's marker).
# Must run BEFORE PhaseB: PhaseB's tearDown clears the marker.
verify_control_marker() {
  local scenario="$1"
  if ! read_control_marker "$scenario"; then
    echo "NEGATIVE CONTROL: INVALID — no well-formed PhaseA marker found for $scenario (PhaseA did not reach its marker; a no-kill verdict without a marker cannot demonstrate no-kill detection)."
    exit 1
  fi
  local alive
  alive=$(adb shell pidof "$APP_PKG" 2>/dev/null || true)
  if [ -n "$alive" ]; then
    case " $alive " in
      *" $MARKER_PID "*)
        ;;
      *)
        echo "NEGATIVE CONTROL: INVALID — marker pid ($MARKER_PID) is not among the live $APP_PKG processes ($alive); the marker is stale or belongs to another process."
        exit 1
        ;;
    esac
    # Start-time cross-check (PID reuse guard), mirroring the test's
    # own fallback: when the live start time cannot be read, the PID
    # match above is the evidence.
    if [ "$MARKER_START" != "-1" ]; then
      local live_start
      live_start=$(proc_start_ticks "$MARKER_PID")
      if [ -n "$live_start" ] && [ "$live_start" != "$MARKER_START" ]; then
        echo "NEGATIVE CONTROL: INVALID — marker start time ($MARKER_START) does not match the live process start time ($live_start); PID reuse suspected."
        exit 1
      fi
    fi
  fi
  echo "MARKER VERIFIED: $scenario marker pid=$MARKER_PID start=$MARKER_START"
}

if [ "$negative_control" -eq 1 ]; then
  method_a="${NEGATIVE_CONTROL%%:*}"
  method_b="${NEGATIVE_CONTROL##*:}"
  echo "== NEGATIVE CONTROL: PhaseA writes the marker but does NOT kill =="
  echo "== (the harness must FAIL this run; a pass means the false-positive is still open) =="
  # The pass predicate is SPECIFIC (re-review #3, tightened in
  # re-review #4): the control passes only when the runner classifies
  # PhaseA as a no-kill — RETURNED NORMALLY (rc == 0, exactly
  # "OK (1 test)") or PROCESS STILL ALIVE — AND the durable r06
  # marker exists, is well-formed, and matches the PhaseA process.
  # Any other PhaseA failure (TIMEOUT, ASSERTION FAILURE,
  # INCONSISTENT RUN, NO SINGLE-TEST PASS) means the control
  # scenario itself did not run as designed, so the job is INVALID,
  # not passed. A PhaseB failure counts as the second catch ONLY
  # when it is the expected fresh-process assertion; a
  # missing-marker, recovery assertion, or infrastructure failure
  # is likewise INVALID.
  control_caught=""
  if ! run_phase_a "$method_a"; then
    case "$PHASE_A_VERDICT" in
      "RETURNED NORMALLY"*|"PROCESS STILL ALIVE"*)
        # The no-kill verdict is only valid with marker evidence —
        # verified BEFORE PhaseB runs (its tearDown clears the marker).
        verify_control_marker "r06"
        control_caught="runner: $PHASE_A_VERDICT (marker pid=$MARKER_PID start=$MARKER_START)"
        ;;
      *)
        echo "NEGATIVE CONTROL: INVALID — PhaseA failed for an unrelated reason ($PHASE_A_VERDICT); the no-kill scenario did not run as designed, so this run cannot demonstrate no-kill detection."
        exit 1
        ;;
    esac
  fi
  # Run PhaseB anyway: if the system reused PhaseA's still-alive
  # process, the fresh-PID check is a second, independent catch.
  if ! run_phase_b "$method_b"; then
    if [ -n "$control_caught" ]; then
      if [ "$PHASE_B_CLASS" = "fresh_process" ]; then
        control_caught="$control_caught; PhaseB: $PHASE_B_VERDICT"
      else
        echo "NEGATIVE CONTROL: INVALID — PhaseB failed for an unrelated reason ($PHASE_B_VERDICT); the expected fresh-process catch did not occur, so this run cannot demonstrate no-kill detection."
        exit 1
      fi
    else
      echo "NEGATIVE CONTROL: INVALID — PhaseB failed ($PHASE_B_VERDICT) but PhaseA did not catch a no-kill (runner: $PHASE_A_VERDICT); a PhaseB failure alone cannot demonstrate no-kill detection."
      exit 1
    fi
  fi
  if [ -n "$control_caught" ]; then
    echo "NEGATIVE CONTROL: PASSED — the harness correctly failed the no-kill run ($control_caught)"
    exit 0
  fi
  echo "NEGATIVE CONTROL: FAILED — the no-kill run PASSED (runner: $PHASE_A_VERDICT; PhaseB: $PHASE_B_VERDICT). The false-positive is still open."
  exit 1
fi

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
