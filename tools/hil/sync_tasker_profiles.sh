#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"

serial="${1:-${CYANBRIDGE_HIL_SERIAL:-}}"
if [[ -z "$serial" ]]; then
  serial="$(find_serial physical || true)"
fi
if [[ -z "$serial" ]]; then
  echo "No physical phone available for Tasker profile sync" >&2
  exit 3
fi

package_installed "$serial" "$CYANBRIDGE_PACKAGE" || {
  echo "Install the CyanBridge debug APK before syncing Tasker profiles" >&2
  exit 4
}
package_installed "$serial" "$TASKER_PACKAGE" || {
  echo "Tasker is not installed" >&2
  exit 4
}

# Production profiles first, then the HIL controller that invokes their real task names.
profiles=(
  "Tasker_AI.xml"
  "CyanBridge_LocalAgent_Tasker.XML"
  "CyanBridge_AutoDiary_Tasker.XML"
  "CyanBridge_VisualDiary_Tasker.XML"
  "CyanBridge_HIL_Tasker.XML"
)

find_tappable_bounds() {
  local xml="$1"
  python3 -c '
import re, sys, xml.etree.ElementTree as ET
raw = sys.stdin.read()
try:
    root = ET.fromstring(raw)
except Exception:
    raise SystemExit(1)
preferred = [
    "import", "replace", "overwrite", "yes", "ok", "apply", "done", "continue"
]
nodes = []
for n in root.iter("node"):
    label = (n.attrib.get("text", "") or n.attrib.get("content-desc", "")).strip().lower()
    if not label:
        continue
    bounds = n.attrib.get("bounds", "")
    m = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if not m:
        continue
    nodes.append((label, tuple(map(int, m.groups()))))
for wanted in preferred:
    for label, bounds in nodes:
        if label == wanted or label.startswith(wanted + " ") or wanted in label:
            x1, y1, x2, y2 = bounds
            print(f"{(x1+x2)//2} {(y1+y2)//2} {label}")
            raise SystemExit(0)
raise SystemExit(2)
' <<<"$xml"
}

accept_tasker_import_ui() {
  local deadline=$((SECONDS + 25))
  local taps=0
  while (( SECONDS < deadline )); do
    adb_for "$serial" shell uiautomator dump /sdcard/cyanbridge-hil-tasker.xml >/dev/null 2>&1 || true
    local xml
    xml="$(adb_for "$serial" exec-out cat /sdcard/cyanbridge-hil-tasker.xml 2>/dev/null | tr -d '\r' || true)"
    local hit=""
    if [[ -n "$xml" ]]; then
      hit="$(find_tappable_bounds "$xml" 2>/dev/null || true)"
    fi
    if [[ -n "$hit" ]]; then
      local x y label
      read -r x y label <<<"$hit"
      echo "Tasker import UI: tapping '$label' at $x,$y" >&2
      adb_for "$serial" shell input tap "$x" "$y" >/dev/null
      taps=$((taps + 1))
      sleep 1
      continue
    fi

    # Once at least one confirmation was accepted and no further import/replace button
    # remains, consider the normal Tasker UI state reached. Functional tests verify it.
    if (( taps > 0 )); then
      adb_for "$serial" shell rm -f /sdcard/cyanbridge-hil-tasker.xml >/dev/null 2>&1 || true
      return 0
    fi
    sleep 1
  done
  adb_for "$serial" shell rm -f /sdcard/cyanbridge-hil-tasker.xml >/dev/null 2>&1 || true
  return 1
}

adb_for "$serial" shell run-as "$CYANBRIDGE_PACKAGE" mkdir -p cache/hil-profiles

for profile in "${profiles[@]}"; do
  local_file="$HIL_REPO_ROOT/android/CyanBridge/tasker/$profile"
  [[ -f "$local_file" ]] || { echo "Missing branch profile: $local_file" >&2; exit 5; }

  echo "Staging $profile into CyanBridge debug cache"
  "$ADB_BIN" -s "$serial" exec-in run-as "$CYANBRIDGE_PACKAGE" sh -c \
    "cat > cache/hil-profiles/$profile" <"$local_file"

  echo "Opening $profile in Tasker"
  adb_for "$serial" shell am start -W \
    -n "$CYANBRIDGE_PACKAGE/.hil.HilTaskerProfileImportActivity" \
    --es profile "$profile" >/dev/null

  if ! accept_tasker_import_ui; then
    echo "Could not confirm Tasker import for $profile" >&2
    echo "If an OLED-protection overlay owns the foreground UI, configure it so Tasker remains logically interactive during HIL profile sync." >&2
    exit 6
  fi
  sleep 1
done

# Return to a neutral app state. The instrumentation suite launches its own fixture next.
adb_for "$serial" shell input keyevent KEYCODE_HOME >/dev/null || true
echo "Tasker profile sync completed for ${#profiles[@]} branch profiles"
