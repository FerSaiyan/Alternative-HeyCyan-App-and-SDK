#!/usr/bin/env bash
# Opt-in CPU benchmark for bounded mobile actions. Model weights stay outside Git.
set -euo pipefail
source "$(dirname "$0")/common.sh"

serial="${1:-${CYANBRIDGE_HIL_SERIAL:-}}"
if [[ -z "$serial" ]]; then
  serial="$(find_serial emulator || true)"
fi
[[ -n "$serial" ]] || { echo "No emulator serial supplied" >&2; exit 3; }

models_dir="${JEV_EMBEDDING_MODELS_DIR:-/tmp/opencode/jev-embedding-models}"
gemma_host="${JEV_EMBEDDING_GEMMA_MODEL:-$models_dir/embeddinggemma-300M-Q8_0.gguf}"
qwen_host="${JEV_EMBEDDING_QWEN_MODEL:-$models_dir/Qwen3-Embedding-0.6B-Q8_0.gguf}"
runtime_aar="${JEV_EMBEDDING_LLAMA_RUNTIME_AAR:-/tmp/opencode/kotlinllamacpp-fixed/llamaCpp/build/outputs/aar/llamaCpp-release.aar}"
dataset="$HIL_REPO_ROOT/android/CyanBridge/calibration/local_agent_mobile_actions_v1.jsonl"
for required in "$gemma_host" "$qwen_host" "$runtime_aar" "$dataset"; do
  [[ -f "$required" ]] || { echo "Missing required external input: $required" >&2; exit 4; }
done

python3 "$HIL_REPO_ROOT/tools/benchmarks/generate_local_agent_calibration.py"

device_root="/data/local/tmp/jev-embedding"
adb_for "$serial" shell mkdir -p "$device_root"
adb_for "$serial" push "$gemma_host" "$device_root/embeddinggemma-300M-Q8_0.gguf"
adb_for "$serial" push "$qwen_host" "$device_root/Qwen3-Embedding-0.6B-Q8_0.gguf"
adb_for "$serial" push "$dataset" "$device_root/local_agent_mobile_actions_v1.jsonl"

for pair in \
  "$gemma_host:$device_root/embeddinggemma-300M-Q8_0.gguf" \
  "$qwen_host:$device_root/Qwen3-Embedding-0.6B-Q8_0.gguf"; do
  host_path="${pair%%:*}"
  device_path="${pair#*:}"
  host_sha="$(sha256sum "$host_path" | awk '{print $1}')"
  device_sha="$(adb_for "$serial" shell sha256sum "$device_path" | awk '{print $1}' | tr -d '\r')"
  [[ "$host_sha" == "$device_sha" ]] || { echo "Checksum mismatch for $host_path" >&2; exit 6; }
  echo "JEV_CAL sha256 host=$host_sha device=$device_sha file=$(basename "$host_path")"
done

export JAVA_HOME="${JAVA_HOME:-/opt/android-studio/jbr}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
(
  cd "$HIL_REPO_ROOT/android/CyanBridge"
  ./gradlew --no-daemon --stacktrace \
    "-PlocalLlamaRuntimeAarPath=$runtime_aar" -PtestAbi=x86_64 \
    :app:assembleDebug :app:assembleDebugAndroidTest
)
bash "$HIL_REPO_ROOT/tools/hil/install.sh" "$serial"

mkdir -p "$HIL_BUILD_DIR/results"
raw_log="$HIL_BUILD_DIR/results/local-agent-calibration-$serial-logcat.txt"
report="$HIL_BUILD_DIR/results/local-agent-calibration-$serial.json"
needle_report="${JEV_NEEDLE_REPORT:-/tmp/opencode/needle3/mobile-actions-benchmark-256.json}"

adb_for "$serial" logcat -G 32M
# One model per instrumentation process. The patched runtime caches n_embd in
# a function-local static, so a second model in the same process would inherit
# the first model's output dimension (observed: Qwen truncated 1024 -> 768
# after Gemma). See the calibration doc section for 2026-09-20.
models_to_run=(${JEV_CALIBRATION_MODELS:-EmbeddingGemma-300M-Q8_0 Qwen3-Embedding-0.6B-Q8_0})
: > "$raw_log"
for model in "${models_to_run[@]}"; do
  adb_for "$serial" logcat -c
  set +e
  adb_for "$serial" shell am instrument -w -r \
    -e jev_calibration_dataset_path "$device_root/local_agent_mobile_actions_v1.jsonl" \
    -e jev_embedding_gemma_path "$device_root/embeddinggemma-300M-Q8_0.gguf" \
    -e jev_embedding_qwen_path "$device_root/Qwen3-Embedding-0.6B-Q8_0.gguf" \
    -e jev_calibration_model "$model" \
    -e class com.fersaiyan.cyanbridge.hil.RealDecisionCalibrationEmulatorTest \
    "$CYANBRIDGE_TEST_PACKAGE/$CYANBRIDGE_TEST_RUNNER"
  instrument_status=$?
  set -e

  # Large x86_64 model runs can restart adbd while instrumentation continues.
  # Wait for the explicit final marker rather than treating that transport reset
  # as model failure. Never run adb install while a calibration is in flight:
  # installPackageLI kills the app process mid-run.
  deadline=$((SECONDS + 1200))
  while (( SECONDS < deadline )); do
    if adb_for "$serial" logcat -d -b all 2>/dev/null | grep -q "JEV_CAL_MODEL_END name=$model"; then
      break
    fi
    if ! adb_for "$serial" shell pidof "$CYANBRIDGE_PACKAGE" >/dev/null 2>&1; then
      echo "Calibration process for $model exited before its final marker (instrument status $instrument_status)" >&2
      exit 10
    fi
    sleep 5
  done

  adb_for "$serial" logcat -d -b all -v threadtime | grep 'JEV_CAL_' >> "$raw_log"
  if ! grep -q "JEV_CAL_MODEL_END name=$model" "$raw_log"; then
    echo "Calibration timed out for $model; log=$raw_log" >&2
    exit 11
  fi
done

analyzer=(
  python3 "$HIL_REPO_ROOT/tools/benchmarks/analyze_local_agent_calibration.py"
  --android-log "$raw_log"
  --output "$report"
)
if [[ -f "$needle_report" ]]; then
  analyzer+=(--needle-report "$needle_report")
fi
"${analyzer[@]}"
echo "Local-agent calibration passed: report=$report log=$raw_log"
