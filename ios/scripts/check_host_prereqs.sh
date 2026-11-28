#!/bin/bash
# Checks (and where it is safe, prepares) what an Xcode build of CyanBridgeKMPHost needs on a Mac:
# JDK 17 for the Gradle build phase, an Android SDK for Gradle's configuration of the shared module, and
# ios/LocalModels/Frameworks/llama.xcframework (built from source by build_llama_xcframework.sh, needs cmake).
# Exit status: 1 with a message saying what to install.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
fail() { echo "missing: $*" >&2; exit 1; }

command -v xcodebuild >/dev/null || fail "Xcode (xcodebuild)"
xcodebuild -version | head -1

if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
fi
[ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ] || fail "JDK 17 (brew install --cask temurin@17, or Android Studio's JBR)"
echo "JDK: $JAVA_HOME"

PROPS="$ROOT/android/CyanBridge/local.properties"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f "$PROPS" ]; then SDK="$(sed -n 's/^sdk.dir=//p' "$PROPS" | head -1)"; fi
if [ -z "$SDK" ] && [ -d "$HOME/Library/Android/sdk" ]; then SDK="$HOME/Library/Android/sdk"; fi
[ -n "$SDK" ] && [ -d "$SDK" ] || fail "Android SDK: install Android Studio (or brew install --cask android-commandlinetools, then 'yes | sdkmanager --licenses'), or set ANDROID_HOME"
if [ ! -f "$PROPS" ] || ! grep -q '^sdk.dir=' "$PROPS"; then
  echo "sdk.dir=$SDK" >> "$PROPS"   # local.properties is git-ignored
  echo "wrote sdk.dir to $PROPS"
fi
echo "Android SDK: $SDK"

if [ ! -d "$ROOT/ios/LocalModels/Frameworks/llama.xcframework" ]; then
  command -v cmake >/dev/null || fail "cmake (brew install cmake), needed once to build llama.xcframework"
  echo "building llama.xcframework once (several minutes)"
  "$ROOT/ios/scripts/build_llama_xcframework.sh"
fi
echo "prerequisites OK"
