#!/usr/bin/env bash
# Opt-in real CPU GGUF embedding probe for the local Android emulator.
# This intentionally does not run in default CI and never uses the host GPU.
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
[[ -f "$gemma_host" ]] || { echo "Missing EmbeddingGemma GGUF: $gemma_host" >&2; exit 4; }
[[ -f "$qwen_host" ]] || { echo "Missing Qwen embedding GGUF: $qwen_host" >&2; exit 4; }

root="/data/local/tmp/jev-embedding"
adb_for "$serial" shell rm -rf "$root"
adb_for "$serial" shell mkdir -p "$root"
adb_for "$serial" push "$gemma_host" "$root/embeddinggemma-300M-Q8_0.gguf"
adb_for "$serial" push "$qwen_host" "$root/Qwen3-Embedding-0.6B-Q8_0.gguf"
adb_for "$serial" shell ls -lh "$root"

gemma_sha256="$(sha256sum "$gemma_host" | awk '{print $1}')"
qwen_sha256="$(sha256sum "$qwen_host" | awk '{print $1}')"
device_gemma_sha256="$(adb_for "$serial" shell sha256sum "$root/embeddinggemma-300M-Q8_0.gguf" | awk '{print $1}' | tr -d '\r')"
device_qwen_sha256="$(adb_for "$serial" shell sha256sum "$root/Qwen3-Embedding-0.6B-Q8_0.gguf" | awk '{print $1}' | tr -d '\r')"
echo "JEV_EMBED sha256 gemma_host=$gemma_sha256 gemma_device=$device_gemma_sha256"
echo "JEV_EMBED sha256 qwen_host=$qwen_sha256 qwen_device=$device_qwen_sha256"
[[ "$gemma_sha256" == "$device_gemma_sha256" ]] || {
  echo "EmbeddingGemma checksum mismatch after adb push" >&2
  exit 6
}
[[ "$qwen_sha256" == "$device_qwen_sha256" ]] || {
  echo "Qwen embedding checksum mismatch after adb push" >&2
  exit 6
}

cd "$HIL_REPO_ROOT/android/CyanBridge"
export JAVA_HOME="${JAVA_HOME:-/opt/android-studio/jbr}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

llama_runtime_arg=()
if [[ -n "${JEV_EMBEDDING_LLAMA_RUNTIME_AAR:-}" ]]; then
  [[ -f "$JEV_EMBEDDING_LLAMA_RUNTIME_AAR" ]] || {
    echo "Missing custom llama runtime AAR: $JEV_EMBEDDING_LLAMA_RUNTIME_AAR" >&2
    exit 5
  }
  llama_runtime_arg=("-PlocalLlamaRuntimeAarPath=$JEV_EMBEDDING_LLAMA_RUNTIME_AAR")
  echo "JEV_EMBED using custom llama runtime AAR=$JEV_EMBEDDING_LLAMA_RUNTIME_AAR"
fi

./gradlew --no-daemon --stacktrace "${llama_runtime_arg[@]}" -PtestAbi=x86_64 \
  :app:assembleDebug :app:assembleDebugAndroidTest

bash "$HIL_REPO_ROOT/tools/hil/install.sh" "$serial"

mkdir -p "$HIL_BUILD_DIR/results"
out="$HIL_BUILD_DIR/results/real-embedding-models-$serial.txt"
echo "JEV_EMBED host_gemma=$gemma_host host_qwen=$qwen_host serial=$serial" | tee "$out"
echo "JEV_EMBED sha256 gemma_host=$gemma_sha256 gemma_device=$device_gemma_sha256" | tee -a "$out"
echo "JEV_EMBED sha256 qwen_host=$qwen_sha256 qwen_device=$device_qwen_sha256" | tee -a "$out"
set +e
adb_for "$serial" shell am instrument -w -r \
  -e jev_embedding_gemma_path "$root/embeddinggemma-300M-Q8_0.gguf" \
  -e jev_embedding_qwen_path "$root/Qwen3-Embedding-0.6B-Q8_0.gguf" \
  -e class com.fersaiyan.cyanbridge.hil.RealEmbeddingModelEmulatorTest \
  "$CYANBRIDGE_TEST_PACKAGE/$CYANBRIDGE_TEST_RUNNER" | tee -a "$out"
status=${PIPESTATUS[0]}
set -e

if (( status != 0 )) || ! grep -Eq 'OK \([0-9]+ tests?\)|OK \([0-9]+ test\)' "$out"; then
  echo "Real embedding instrumentation failed; log=$out" >&2
  if (( status == 0 )); then exit 10; else exit "$status"; fi
fi
echo "Real embedding probe passed: $out"
