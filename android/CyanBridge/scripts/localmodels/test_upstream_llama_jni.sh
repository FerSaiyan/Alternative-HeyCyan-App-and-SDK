#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 3 ]]; then
    echo "Usage: $0 upstream-source-dir chat.gguf embedding.gguf"
    exit 1
fi
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BUILD="${CYAN_LLAMA_HOST_BUILD_DIR:-/tmp/opencode/cyan-llama-host-smoke}"
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
cmake -S "$ROOT/llama-runtime/src/main/cpp" -B "$BUILD" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DCYAN_LLAMA_SOURCE_DIR="$1"
cmake --build "$BUILD" --target cyan_llama --parallel 4
mkdir -p "$BUILD/classes"
"$JAVA_HOME/bin/javac" -d "$BUILD/classes" \
    "$ROOT/llama-runtime/src/testHost/java/com/fersaiyan/cyanbridge/llama/UpstreamLlamaBridge.java"
"$JAVA_HOME/bin/java" -cp "$BUILD/classes" com.fersaiyan.cyanbridge.llama.UpstreamLlamaBridge \
    "$BUILD/libcyan_llama.so" "$2" "$3" \
    "$ROOT/app/src/androidTest/assets/audio/gemini_live_hello_5s.wav"
