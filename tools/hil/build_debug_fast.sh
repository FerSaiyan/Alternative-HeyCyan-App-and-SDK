#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
app_root="$repo_root/android/CyanBridge"
build_root="${CYANBRIDGE_FAST_BUILD_ROOT:-${XDG_CACHE_HOME:-$HOME/.cache}/cyanbridge/build-$(printf '%s' "$repo_root" | sha256sum | cut -c1-12)}"
abi="${CYANBRIDGE_BUILD_ABI:-arm64-v8a}"
export JAVA_HOME="${JAVA_HOME:-/opt/android-studio/jbr}"

mkdir -p "$build_root"
args=(
  --project-dir "$app_root"
  --project-cache-dir "$build_root/project-cache"
  --init-script "$repo_root/tools/hil/fast-build.init.gradle"
  "-PcyanbridgeBuildRoot=$build_root"
  "-PtestAbi=$abi"
)
if [[ -n "${LLAMA_SOURCE_DIR:-}" ]]; then
  args+=("-PllamaSourceDir=$LLAMA_SOURCE_DIR")
fi
if (( $# == 0 )); then
  set -- :app:assembleDebug
fi

"$app_root/gradlew" "${args[@]}" "$@"
printf '\nDebug APK: %s\n' "$build_root/CyanBridgeManagerApp/app/outputs/apk/debug/app-debug.apk"
