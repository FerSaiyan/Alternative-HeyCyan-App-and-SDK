#!/usr/bin/env bash
# Opt-in physical/emulator Tasker + AutoInput YouTube smoke test.
# Tasker profiles must be imported first with sync_tasker_profiles.sh.
set -euo pipefail
source "$(dirname "$0")/common.sh"

serial="${1:-${CYANBRIDGE_HIL_SERIAL:-}}"
if [[ -z "$serial" ]]; then
  serial="$(find_serial any || true)"
fi
[[ -n "$serial" ]] || { echo "No Tasker HIL target supplied" >&2; exit 3; }

package_installed "$serial" "$TASKER_PACKAGE" || { echo "Tasker is not installed" >&2; exit 4; }
package_installed "$serial" "$AUTOINPUT_PACKAGE" || { echo "AutoInput is not installed" >&2; exit 4; }
package_installed "$serial" com.google.android.youtube || { echo "YouTube is not installed" >&2; exit 4; }
package_installed "$serial" "$CYANBRIDGE_PACKAGE" || { echo "Install the CyanBridge debug APK first" >&2; exit 4; }

export CYANBRIDGE_HIL_SERIAL="$serial"
export CYANBRIDGE_HIL_LOCAL_AI=true
export CYANBRIDGE_HIL_YOUTUBE=true

bash "$HIL_REPO_ROOT/tools/hil/run_instrumentation.sh" \
  "$serial" hardware com.fersaiyan.cyanbridge.hil.LocalAiTaskerYouTubeHilTest
