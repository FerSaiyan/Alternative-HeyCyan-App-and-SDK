#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo 'Usage: [LITERTLM_VERSION=0.18.0] bash build_aar.sh PINNED_SOURCE BAZEL_7_6_1 STOCK_AAR OUTPUT_DIR' >&2
  exit 2
fi
source_dir=$(realpath "$1")
bazel=$(realpath "$2")
stock_aar=$(realpath "$3")
mkdir -p "$4"
output=$(realpath "$4")
here=$(cd "$(dirname "$0")" && pwd)
version=${LITERTLM_VERSION:-0.16.0}
case "$version" in
  # 0.16's kt_android_library is a macro (classes in *_kt.jar); 0.18's is a native rule.
  0.16.0) pin=924e79c91542761242244e4f1651851f822e4cbb kt_target=litertlm-android_kt kt_jar=litertlm-android_kt.jar ;;
  0.18.0) pin=b2f686e2ed4718fb84ec398a61dd59ca0f0aff27 kt_target=litertlm-android kt_jar=liblitertlm-android.jar ;;
  *) echo "Unsupported LITERTLM_VERSION: $version" >&2; exit 2 ;;
esac
patch="$here/litertlm-$version-shared-decision.patch"
test -f "$patch"
test "$(git -C "$source_dir" rev-parse HEAD)" = "$pin"
test "$(cat "$source_dir/.bazelversion")" = 7.6.1
: "${ANDROID_HOME:?Set ANDROID_HOME to the existing Android SDK}"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK r28b or newer}"
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" --version
if ! git -C "$source_dir" apply --reverse --check "$patch" 2>/dev/null; then
  git -C "$source_dir" apply --check "$patch"
  git -C "$source_dir" apply "$patch"
fi
# ABIS: space-separated arm64-v8a and/or x86_64 (the x86_64 AVD). JOBS: Bazel parallelism.
# BAZEL_OUTPUT_ROOT: put Bazel's ~15 GB output base on fast local storage (NVMe);
# RAM_FRACTION caps Bazel's scheduling so high JOBS cannot exhaust host memory.
abis=${ABIS:-arm64-v8a}
jobs=${JOBS:-20}
bazel_root=${BAZEL_OUTPUT_ROOT:-$output/bazel-cache}
ram_fraction=${RAM_FRACTION:-0.6}
llvm="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
# Host tools only (Android targets use the NDK). 0.18's .bazelrc forces CC=clang.
host_cc=${HOST_CC:-$(command -v clang || command -v gcc)}
cd "$source_dir"
jni_args=()
for abi in $abis; do
  case "$abi" in
    arm64-v8a) config=android_arm64 ;;
    x86_64) config=android_x86_64 ;;
    *) echo "Unsupported ABI: $abi" >&2; exit 2 ;;
  esac
  "$bazel" --output_user_root="$bazel_root" build --config="$config" --jobs="$jobs" \
    --local_resources=memory=HOST_RAM*"$ram_fraction" \
    --action_env=CC="$host_cc" --repo_env=CC="$host_cc" \
    --linkopt=-Wl,-z,max-page-size=16384 \
    //kotlin/java/com/google/ai/edge/litertlm/jni:litertlm_jni \
    "//kotlin/java/com/google/ai/edge/litertlm:$kt_target"
  # bazel-bin follows the last configuration, so copy each ABI's outputs now.
  jni_output="$source_dir/bazel-bin/kotlin/java/com/google/ai/edge/litertlm/jni/litertlm_jni"
  if [[ ! -f "$jni_output" ]]; then
    jni_output="$source_dir/bazel-bin/kotlin/java/com/google/ai/edge/litertlm/jni/liblitertlm_jni.so"
  fi
  install -m 0644 "$jni_output" "$output/liblitertlm_jni-$abi.so"
  install -m 0644 "$source_dir/bazel-bin/kotlin/java/com/google/ai/edge/litertlm/$kt_jar" "$output/litertlm-android-classes.jar"
  jni_args+=(--jni "$abi=$output/liblitertlm_jni-$abi.so")
  # The open-source build links this upstream LFS prebuilt dynamically; ship it.
  prebuilt_dir=android_${abi/arm64-v8a/arm64}
  jni_args+=(--bundle "$abi=$source_dir/prebuilt/$prebuilt_dir/libGemmaModelConstraintProvider.so")
  # Stock 0.16 ships no GPU accelerator, so Backend.GPU silently falls back to
  # CPU. The OpenCL accelerator/sampler need only platform libs (vendor OpenCL is
  # dlopened at runtime); WebGPU would also need Dawn and is not bundled.
  if [[ "$abi" == arm64-v8a ]]; then
    for gpu_lib in libLiteRtOpenClAccelerator.so libLiteRtTopKOpenClSampler.so; do
      jni_args+=(--bundle "$abi=$source_dir/prebuilt/$prebuilt_dir/$gpu_lib")
    done
  fi
done
python3 "$here/package_aar.py" \
  --base-aar "$stock_aar" \
  --classes "$output/litertlm-android-classes.jar" \
  "${jni_args[@]}" \
  --nm "$llvm/llvm-nm" --readelf "$llvm/llvm-readelf" \
  --upstream-revision "$pin" --patch "$patch" \
  --output "$output/litertlm-$version-shared-decisions-${abis// /+}.aar"
