#!/usr/bin/env bash
# Install a CyanBridge build WITHOUT ever uninstalling, so app data survives reflashes.
#
# Why: debug builds used the default debug key and release builds the release key. Installing one
# over the other fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE, and Android Studio then offers to
# uninstall, which wipes files/local_agent_memory, recordings, glasses photos and the Keystore key
# that wraps the Memory Vault master key (so even exported vault snapshots become unreadable).
# app/build.gradle now signs debug builds with the release key.
#
# Usage (from android/CyanBridge):
#   scripts/install_debug_keep_data.sh [--build] [--apk PATH] [--serial SERIAL]
#   scripts/install_debug_keep_data.sh --rotate-from-debug-key [--build] [--apk PATH] [--serial SERIAL]
#
#   --build                  run ./gradlew :app:assembleDebug first
#   --apk PATH               APK to install (default app/build/outputs/apk/debug/app-debug.apk)
#   --serial SERIAL          adb device serial
#   --rotate-from-debug-key  ONE-TIME migration for a phone whose installed app is signed with the
#                            default debug key: re-sign the APK with an APK Signature Scheme v3
#                            proof-of-rotation (debug key -> release key, Android 9+), so it installs
#                            as an update and keeps all data. Later installs need no flag.
#   --dry-run                build/sign/verify only, do not install
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
PKG="com.fersaiyan.cyanbridge"
APK="app/build/outputs/apk/debug/app-debug.apk"
SERIAL=""
BUILD=0
ROTATE=0
DRY=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --build) BUILD=1 ;;
    --apk) APK="$2"; shift ;;
    --serial) SERIAL="$2"; shift ;;
    --rotate-from-debug-key) ROTATE=1 ;;
    --dry-run) DRY=1 ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
APKSIGNER="$(ls "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"
ADB=(adb)
[[ -n "$SERIAL" ]] && ADB=(adb -s "$SERIAL")

if [[ $BUILD -eq 1 ]]; then
  ./gradlew :app:assembleDebug
fi
[[ -f "$APK" ]] || { echo "APK not found: $APK (use --build)" >&2; exit 1; }

prop() { grep -E "^$1=" keystore.properties | head -1 | cut -d= -f2-; }

if [[ $ROTATE -eq 1 ]]; then
  [[ -f keystore.properties ]] || { echo "keystore.properties missing: release key needed for rotation" >&2; exit 1; }
  [[ -x "$APKSIGNER" ]] || { echo "apksigner not found under $SDK/build-tools" >&2; exit 1; }
  RELEASE_KS="$(prop storeFile)"
  [[ "$RELEASE_KS" = /* ]] || RELEASE_KS="$PWD/$RELEASE_KS"
  RELEASE_ALIAS="$(prop keyAlias)"
  export CB_STORE_PASS="$(prop storePassword)" CB_KEY_PASS="$(prop keyPassword)"
  DEBUG_KS="${CB_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
  LINEAGE="${CB_ROTATION_LINEAGE:-$HOME/.android/cyanbridge-debug-to-release.lineage}"
  if [[ ! -f "$LINEAGE" ]]; then
    echo "creating signing lineage (debug key -> release key): $LINEAGE"
    "$APKSIGNER" rotate --out "$LINEAGE" \
      --old-signer --ks "$DEBUG_KS" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
      --new-signer --ks "$RELEASE_KS" --ks-key-alias "$RELEASE_ALIAS" --ks-pass env:CB_STORE_PASS --key-pass env:CB_KEY_PASS
  fi
  ROTATED="${APK%.apk}-rotated.apk"
  "$APKSIGNER" sign --out "$ROTATED" \
    --ks "$DEBUG_KS" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
    --next-signer --ks "$RELEASE_KS" --ks-key-alias "$RELEASE_ALIAS" --ks-pass env:CB_STORE_PASS --key-pass env:CB_KEY_PASS \
    --lineage "$LINEAGE" --rotation-min-sdk-version 28 \
    "$APK"
  unset CB_STORE_PASS CB_KEY_PASS
  "$APKSIGNER" verify --min-sdk-version 28 --print-certs "$ROTATED" | grep -E "Signer|SHA-256" || true
  APK="$ROTATED"
fi

if [[ $DRY -eq 1 ]]; then
  echo "dry run: would install $APK"
  exit 0
fi

echo "installing $APK (adb install -r -d, never uninstalls)"
set +e
OUT="$("${ADB[@]}" install -r -d "$APK" 2>&1)"
RC=$?
set -e
echo "$OUT"
if [[ $RC -eq 0 && "$OUT" == *Success* ]]; then
  echo "installed; app data kept"
  exit 0
fi
if [[ "$OUT" == *INSTALL_FAILED_UPDATE_INCOMPATIBLE* || "$OUT" == *"signatures do not match"* ]]; then
  cat >&2 <<EOF

The installed $PKG is signed with a different key than this APK. NOT uninstalling (that would wipe
all app data). Options:
  - If the phone has a build signed with the default debug key, run once:
        scripts/install_debug_keep_data.sh --rotate-from-debug-key
  - Otherwise export a backup in CyanBridge Settings first and decide manually.
EOF
  exit 3
fi
exit "${RC:-1}"
