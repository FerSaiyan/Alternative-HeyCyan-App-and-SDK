#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root/android/CyanBridge"

# Keep hosted-emulator artifacts compact and fail quickly on portable logic regressions.
./gradlew --no-daemon --stacktrace -PtestAbi=x86_64 \
  :shared:portabilityTest

# The complete unit suite runs in Android self-hosted CI. Keep this feature workflow focused
# so an unrelated timing-sensitive socket test cannot prevent the Meta instrumentation tests.
./gradlew --no-daemon --stacktrace -PtestAbi=x86_64 \
  :app:testDebugUnitTest \
  --tests com.fersaiyan.cyanbridge.ui.MetaPairingScreenStateTest \
  --tests com.fersaiyan.cyanbridge.devices.metarayban.MetaDatRegistrationSupportTest

# Pass both classes through one property; repeated Gradle properties overwrite each other.
# Retry once because hosted Android emulators can occasionally drop an instrumentation process
# while remaining online. A deterministic test failure still fails on the second attempt.
instrumentation_args=(
  --no-daemon
  --stacktrace
  -PtestAbi=x86_64
  :app:connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=com.fersaiyan.cyanbridge.ui.MetaPairingMockFlowTest,com.fersaiyan.cyanbridge.ui.MetaPairingScreenTest,com.fersaiyan.cyanbridge.devices.metarayban.MetaDatSdkMockTest
)
if ! ./gradlew "${instrumentation_args[@]}"; then
  echo "Mock DAT instrumentation failed; retrying once on the same emulator" >&2
  adb wait-for-device
  ./gradlew "${instrumentation_args[@]}"
fi
