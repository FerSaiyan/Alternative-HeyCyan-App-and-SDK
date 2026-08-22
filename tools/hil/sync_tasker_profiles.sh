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
  "Tasker_AI.prj.xml"
  "CyanBridge_LocalAgent_Tasker.prj.xml"
  "CyanBridge_AutoDiary_Tasker.prj.xml"
  "CyanBridge_VisualDiary_Tasker.prj.xml"
  "CyanBridge_HIL_Tasker.prj.xml"
)

# Classifies the current Tasker importer dialog and returns the confirming button.
#
# Prints "<x> <y> <kind>" where kind is one of: import, overwrite, enable.
# Prints "ERROR <reason>" when Tasker reports a failed import or missing permissions.
# Prints nothing when no known importer dialog is on screen.
classify_import_dialog() {
  local xml="$1"
  python3 -c '
import re, sys, xml.etree.ElementTree as ET
raw = sys.stdin.read()
try:
    root = ET.fromstring(raw)
except Exception:
    raise SystemExit(0)
texts = []
buttons = []
for n in root.iter("node"):
    t = (n.attrib.get("text", "") or "").strip()
    d = (n.attrib.get("content-desc", "") or "").strip()
    if t:
        texts.append(t.lower())
    if d:
        texts.append(d.lower())
    label = (t or d).lower()
    if label and n.attrib.get("clickable") == "true" and n.attrib.get("enabled") == "true":
        m = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.attrib.get("bounds", ""))
        if m:
            buttons.append((label, tuple(map(int, m.groups()))))
joined = " ".join(texts)
if ("import failed" in joined
        or ("couldn" in joined and "try to import" in joined)
        or "bad data" in joined or "missing permissions" in joined):
    print("ERROR importer reported failure")
    raise SystemExit(0)
kind = None
if "about to import" in joined:
    kind = "import"
elif "already exists" in joined and "overwrite" in joined:
    kind = "overwrite"
elif "enable all the profiles" in joined:
    kind = "enable"
if kind is None:
    raise SystemExit(0)
for want in ("yes", "ok", "allow"):
    for label, (x1, y1, x2, y2) in buttons:
        if label == want:
            print(f"{(x1+x2)//2} {(y1+y2)//2} {kind}")
            raise SystemExit(0)
' <<<"$xml"
}

# Confirms a complete Tasker project import deterministically.
#
# The importer always walks: import-confirm -> [overwrite-confirm] -> enable-profiles.
# Early exits previously left projects half-imported (stale tasks kept running), so this
# loop only succeeds after the enable step was accepted AND the importer activity has
# left the foreground across two consecutive polls.
accept_tasker_import_ui() {
  local deadline=$((SECONDS + 60))
  local saw_import=0 saw_overwrite=0 saw_enable=0
  local gone_checks=0
  while (( SECONDS < deadline )); do
    adb_for "$serial" shell rm -f /sdcard/cyanbridge-hil-tasker.xml >/dev/null 2>&1 || true
    adb_for "$serial" shell uiautomator dump /sdcard/cyanbridge-hil-tasker.xml >/dev/null 2>&1 || true
    local xml
    xml="$(adb_for "$serial" exec-out cat /sdcard/cyanbridge-hil-tasker.xml 2>/dev/null | tr -d '\r' || true)"
    local hit=""
    if [[ -n "$xml" ]]; then
      hit="$(classify_import_dialog "$xml" 2>/dev/null || true)"
    fi
    if [[ "$hit" == ERROR* ]]; then
      echo "Tasker import UI: ${hit#ERROR }" >&2
      adb_for "$serial" shell rm -f /sdcard/cyanbridge-hil-tasker.xml >/dev/null 2>&1 || true
      return 1
    fi
    if [[ -n "$hit" ]]; then
      local x y kind
      read -r x y kind <<<"$hit"
      case "$kind" in
        import) saw_import=1 ;;
        overwrite) saw_overwrite=1 ;;
        enable) saw_enable=1 ;;
      esac
      echo "Tasker import UI: confirming '$kind' at $x,$y" >&2
      adb_for "$serial" shell input tap "$x" "$y" >/dev/null
      gone_checks=0
      sleep 1
      continue
    fi

    if (( saw_import && saw_enable )); then
      local focus
      focus="$(adb_for "$serial" shell dumpsys window 2>/dev/null | grep -m1 'mCurrentFocus=' || true)"
      if grep -Eq 'ActivityImportTaskerDataFromXml|ActivityGenericAction|GrantPermissionsActivity|Just a moment' <<<"$focus"; then
        sleep 1
        continue
      fi
      gone_checks=$((gone_checks + 1))
      if (( gone_checks >= 2 )); then
        adb_for "$serial" shell rm -f /sdcard/cyanbridge-hil-tasker.xml >/dev/null 2>&1 || true
        return 0
      fi
      sleep 1
      continue
    fi
    sleep 1
  done
  echo "Tasker import UI: timed out (import=$saw_import overwrite=$saw_overwrite enable=$saw_enable)" >&2
  adb_for "$serial" shell rm -f /sdcard/cyanbridge-hil-tasker.xml >/dev/null 2>&1 || true
  return 1
}

stage_profile() {
  local profile="$1"
  local local_file="$2"
  local attempt
  for attempt in {1..5}; do
    adb_for "$serial" wait-for-device >/dev/null
    if "$ADB_BIN" -s "$serial" exec-in run-as "$CYANBRIDGE_PACKAGE" sh -c \
        "cat > cache/hil-profiles/$profile" <"$local_file"; then
      return 0
    fi
    echo "ADB disconnected while staging $profile; retrying ($attempt/5)" >&2
    sleep 1
  done
  return 1
}

open_profile() {
  local profile="$1"
  local attempt
  for attempt in {1..5}; do
    adb_for "$serial" wait-for-device >/dev/null
    if adb_for "$serial" shell am start -W \
        -n "$CYANBRIDGE_PACKAGE/.hil.HilTaskerProfileImportActivity" \
        --es profile "$profile" >/dev/null; then
      return 0
    fi
    adb_for "$serial" wait-for-device >/dev/null
    if adb_for "$serial" shell dumpsys activity activities 2>/dev/null \
        | grep -Fq "hil-profiles/$profile"; then
      return 0
    fi
    echo "ADB disconnected while opening $profile; retrying ($attempt/5)" >&2
    sleep 1
  done
  return 1
}

adb_for "$serial" shell run-as "$CYANBRIDGE_PACKAGE" mkdir -p cache/hil-profiles

# The Local Agent project exposes a make_call command, so Tasker must hold this runtime
# permission before its project importer can complete unattended.
adb_for "$serial" shell pm grant "$TASKER_PACKAGE" android.permission.CALL_PHONE >/dev/null

for profile in "${profiles[@]}"; do
  local_file="$HIL_REPO_ROOT/android/CyanBridge/tasker/$profile"
  [[ -f "$local_file" ]] || { echo "Missing branch profile: $local_file" >&2; exit 5; }

  echo "Staging $profile into CyanBridge debug cache"
  stage_profile "$profile" "$local_file" || {
    echo "Could not stage $profile after repeated ADB disconnects" >&2
    exit 7
  }

  echo "Opening $profile in Tasker"
  open_profile "$profile" || {
    echo "Could not open $profile after repeated ADB disconnects" >&2
    exit 7
  }

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
