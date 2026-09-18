#!/usr/bin/env bash
# Jev-like local-agent CI for the local Linux PC emulator.
# Mirrors tools/hil/run_instrumentation.sh + android-tasker-hil.yml conventions:
# - JVM unit tests first (fast, no device) with --info for precise failure logs
# - then assembleDebug + assembleDebugAndroidTest (-PtestAbi=x86_64)
# - then find/start persistent emulator, install, run JevLikeDecisionEmulatorTest
# Only touches Jev-like scopes; never the full Tasker/email/glasses HIL layers.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HIL_DIR="$REPO_ROOT/tools/hil"
BUILD_DIR="$REPO_ROOT/build/hil"
RESULTS_DIR="$BUILD_DIR/results"
mkdir -p "$RESULTS_DIR"

JVM_FILTERS=(
  "com.fersaiyan.cyanbridge.ai.decision.*"
  "com.fersaiyan.cyanbridge.ai.live.GeminiLiveControlRouterTest"
  "com.fersaiyan.cyanbridge.ai.router.AssistantRequestRouterJevTest"
  "com.fersaiyan.cyanbridge.ai.router.AssistantRequestRouterTest"
  "com.fersaiyan.cyanbridge.localagent.UiActionCandidateBuilderTest"
  "com.fersaiyan.cyanbridge.localagent.LocalAgentUiControlProtocolTest"
)
EMULATOR_CLASS="com.fersaiyan.cyanbridge.hil.JevLikeDecisionEmulatorTest"

log() { echo "[JEV_CI] $*" >&2; }
fail() { echo "[JEV_CI][FAIL] $*" >&2; exit 1; }

export JAVA_HOME="${JAVA_HOME:-/opt/android-studio/jbr}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

log "toolchain uname=$(uname -s) arch=$(uname -m) java=$JAVA_HOME sdk=$ANDROID_HOME"
test "$(uname -s)" = "Linux" || fail "requires Linux, got $(uname -s)"
command -v python3 >/dev/null || fail "python3 missing"
test -x "$JAVA_HOME/bin/java" || fail "JAVA_HOME java missing at $JAVA_HOME/bin/java"
"$JAVA_HOME/bin/java" -version 2>&1 | head -n 3
command -v adb >/dev/null || fail "adb missing on PATH"
adb version | head -n 2

cd "$REPO_ROOT/android/CyanBridge"

log "step=1/4 jvm unit tests filters=${JVM_FILTERS[*]}"
set +e
GRADLE_ARGS=(--no-daemon --stacktrace --info :app:testDebugUnitTest)
for f in "${JVM_FILTERS[@]}"; do GRADLE_ARGS+=(--tests "$f"); done
./gradlew "${GRADLE_ARGS[@]}" 2>&1 | tee "$RESULTS_DIR/jev-jvm-tests.log"
JVM_CODE=${PIPESTATUS[0]}
set -e
log "jvm exit=$JVM_CODE log=$RESULTS_DIR/jev-jvm-tests.log"
if [ "$JVM_CODE" -ne 0 ]; then
  log "failing JVM test classes:"
  grep -E "FAILED|AssertionError|expected" "$RESULTS_DIR/jev-jvm-tests.log" | head -n 60 || true
  log "test XML reports:"
  find app/build/test-results -name "*.xml" -newer "$REPO_ROOT/settings.gradle" 2>/dev/null | head || find . -path "*test-results*jev*" | head -n 20 || true
  fail "JVM decision tests failed (see $RESULTS_DIR/jev-jvm-tests.log)"
fi

log "step=2/4 assemble debug + androidTest APKs (-PtestAbi=x86_64)"
./gradlew --no-daemon --stacktrace -PtestAbi=x86_64 :app:assembleDebug :app:assembleDebugAndroidTest 2>&1 | tee "$RESULTS_DIR/jev-assemble.log"
log "assemble ok"

log "step=3/4 find or start local-PC emulator"
SERIAL="$(bash "$HIL_DIR/start_emulator.sh")" || fail "start_emulator.sh failed"
test -n "$SERIAL" || fail "empty emulator serial"
test "$(adb -s "$SERIAL" get-state)" = "device" || fail "emulator $SERIAL not device"
log "emulator serial=$SERIAL"

log "install branch build on $SERIAL"
bash "$HIL_DIR/install.sh" "$SERIAL" 2>&1 | tee "$RESULTS_DIR/jev-install.log"

log "step=4/4 emulator instrumentation $EMULATOR_CLASS"
set +e
bash "$HIL_DIR/run_instrumentation.sh" "$SERIAL" emulator "$EMULATOR_CLASS" 2>&1 | tee "$RESULTS_DIR/jev-emulator.log"
EMU_CODE=${PIPESTATUS[0]}
set -e
log "emulator exit=$EMU_CODE log=$RESULTS_DIR/jev-emulator.log"
if [ "$EMU_CODE" -ne 0 ]; then
  log "collecting diagnostics for $SERIAL"
  bash "$HIL_DIR/collect_diagnostics.sh" "$SERIAL" || true
  fail "emulator JevLikeDecisionEmulatorTest failed (see $RESULTS_DIR/jev-emulator.log)"
fi

log "PASS all Jev-like layers: jvm + assemble + emulator($SERIAL)"
