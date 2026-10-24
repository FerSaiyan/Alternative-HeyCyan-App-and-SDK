#!/usr/bin/env bash
# Builds llama.xcframework (iPhone + Apple-silicon simulator) for CyanBridge's
# on-device models. The official release zip ships no iOS-simulator slice, so we
# build from source with llama.cpp's own build-xcframework.sh. Requires cmake.
#
# usage: ios/scripts/build_llama_xcframework.sh [llama.cpp tag]
set -euo pipefail

TAG="${1:-b11321}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/LocalModels/Frameworks/llama.xcframework"
WORK="${TMPDIR:-/tmp}/cyanbridge-llama-$TAG"

command -v cmake >/dev/null || { echo "cmake is required (brew install cmake)" >&2; exit 1; }

if [[ ! -d "$WORK/.git" ]]; then
    rm -rf "$WORK"
    git clone --depth 1 --branch "$TAG" https://github.com/ggml-org/llama.cpp.git "$WORK"
fi

(cd "$WORK" && ./build-xcframework.sh ios-sim ios-device)

rm -rf "$DEST"
mkdir -p "$(dirname "$DEST")"
cp -R "$WORK/build-apple/llama.xcframework" "$DEST"
echo "$TAG" > "$(dirname "$DEST")/llama.version"
echo "llama.xcframework ($TAG) -> $DEST"
