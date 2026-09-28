#!/usr/bin/env bash
# Reproducible rebuild of the patched llama.cpp embedding runtime AAR.
#
# Clones ljcamargo/kotlinllamacpp at the MANIFEST pin (or reuses
# $EMBEDDING_RUNTIME_SRC), applies the local patches in order, builds
# librnllama for x86_64 + arm64-v8a with the pinned NDK/CMake toolchain,
# installs the .so files into jniLibs, and assembles llamaCpp-release.aar.
# Model weights are never involved; the AAR stays outside Git by design.
set -euo pipefail

PIN_COMMIT="c292c068bdd258203dd41fc6d0f08578eddd59f3"
UPSTREAM_URL="https://github.com/ljcamargo/kotlinllamacpp.git"
NDK_VERSION="27.0.12077973"
ANDROID_PLATFORM="android-24"

here="$(cd "$(dirname "$0")" && pwd)"
patch_dir="$here/embedding-runtime"
manifest="$patch_dir/MANIFEST"
[[ -f "$manifest" ]] || { echo "Missing $manifest" >&2; exit 4; }
for patch in jni-list-contract.patch jniLibs-prebuilt.patch nembd-per-call.patch; do
  [[ -f "$patch_dir/$patch" ]] || { echo "Missing patch $patch" >&2; exit 4; }
done

src="${EMBEDDING_RUNTIME_SRC:-/tmp/opencode/kotlinllamacpp-fixed}"
if [[ -d "$src/.git" ]]; then
  actual="$(git -C "$src" rev-parse HEAD)"
  [[ "$actual" == "$PIN_COMMIT" ]] || {
    echo "Source tree $src is at $actual, expected pin $PIN_COMMIT" >&2
    exit 5
  }
  echo "Source tree at pinned commit $PIN_COMMIT"
else
  echo "Cloning $UPSTREAM_URL at $PIN_COMMIT into $src"
  git clone "$UPSTREAM_URL" "$src"
  git -C "$src" checkout "$PIN_COMMIT"
fi

# Idempotent patch application: skip patches that already apply cleanly in reverse.
for patch in jni-list-contract.patch jniLibs-prebuilt.patch nembd-per-call.patch; do
  if git -C "$src" apply --reverse --check "$patch_dir/$patch" >/dev/null 2>&1; then
    echo "Patch already applied: $patch"
  else
    echo "Applying patch: $patch"
    git -C "$src" apply "$patch_dir/$patch"
  fi
done

sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
ndk="$sdk_root/ndk/$NDK_VERSION"
[[ -d "$ndk" ]] || { echo "Missing NDK $NDK_VERSION at $ndk" >&2; exit 6; }
toolchain="$ndk/build/cmake/android.toolchain.cmake"
cmake_bin="${CMAKE_BIN:-$(command -v cmake)}"
ninja_bin="${NINJA_BIN:-$(command -v ninja || echo "$HOME/.local/bin/ninja")}"

build_abi() {
  local abi="$1" dir="$2"
  mkdir -p "$src/$dir"
  (
    cd "$src/$dir"
    "$cmake_bin" ../llamaCpp/src/main/cpp -G Ninja \
      -DCMAKE_TOOLCHAIN_FILE="$toolchain" \
      -DANDROID_ABI="$abi" \
      -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
      -DCMAKE_BUILD_TYPE=Release
    "$ninja_bin"
  )
}

build_abi x86_64 build-x86_64
build_abi arm64-v8a build-arm64

cp "$src/build-x86_64/librnllama.so" "$src/build-x86_64/librnllama_x86_64.so" \
  "$src/llamaCpp/src/main/jniLibs/x86_64/"
cp "$src/build-arm64/librnllama.so" "$src/build-arm64/librnllama_v8.so" \
  "$src/build-arm64/librnllama_v8_2.so" "$src/build-arm64/librnllama_v8_2_dotprod.so" \
  "$src/build-arm64/librnllama_v8_2_i8mm.so" "$src/build-arm64/librnllama_v8_2_dotprod_i8mm.so" \
  "$src/llamaCpp/src/main/jniLibs/arm64-v8a/"

export JAVA_HOME="${JAVA_HOME:-/opt/android-studio/jbr}"
export ANDROID_HOME="$sdk_root"
export ANDROID_SDK_ROOT="$sdk_root"
(
  cd "$src"
  ./gradlew --no-daemon :llamaCpp:assembleRelease
)

aar="$src/llamaCpp/build/outputs/aar/llamaCpp-release.aar"
echo "Built $aar"
sha256sum "$aar"
unzip -l "$aar" | grep -E 'jni/(arm64-v8a|x86_64)/librnllama.*\.so'
echo "Compare digests against $manifest before depending on this artifact."
