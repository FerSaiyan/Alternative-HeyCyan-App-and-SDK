#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"

serial="${1:-${CYANBRIDGE_HIL_SERIAL:-}}"
if [[ -z "$serial" ]]; then
  serial="$(find_serial emulator || true)"
fi
if [[ -z "$serial" || "$serial" != emulator-* ]]; then
  echo "Walking Aid model CI requires an Android emulator serial" >&2
  exit 3
fi

adb_retry() {
  local attempt
  for attempt in 1 2 3 4 5; do
    if timeout 60s "$ADB_BIN" -s "$serial" wait-for-device >/dev/null 2>&1 &&
        adb_for "$serial" "$@"; then
      return 0
    fi
    echo "ADB command failed on $serial (attempt $attempt/5); waiting for reconnect" >&2
    sleep 2
  done
  return 1
}

app_file_exists() {
  local path="$1" state
  state="$(adb_retry shell \
    "if run-as '$CYANBRIDGE_PACKAGE' test -f '$path'; then echo yes; else echo no; fi" | tr -d '\r')"
  [[ "$state" == "yes" ]]
}

remote_file_exists() {
  local path="$1" state
  state="$(adb_retry shell \
    "if test -f '$path'; then echo yes; else echo no; fi" | tr -d '\r')"
  [[ "$state" == "yes" ]]
}

manifest="$HIL_REPO_ROOT/tools/hil/walking_aid_assets.tsv"
cache_dir="${WALKING_AID_CI_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/cyanbridge/walking-aid-model-ci}"
result_dir="$HIL_BUILD_DIR/results/walking-aid-model-ci"
mkdir -p "$cache_dir" "$result_dir"

download_asset() {
  local filename="$1" expected_size="$2" expected_sha="$3" url="$4"
  local target="$cache_dir/$filename"
  local actual_size actual_sha
  if [[ -f "$target" ]]; then
    actual_size="$(stat -c %s "$target")"
    actual_sha="$(sha256sum "$target" | awk '{print $1}')"
    if [[ "$actual_size" == "$expected_size" && "$actual_sha" == "$expected_sha" ]]; then
      echo "Using verified host cache: $filename" >&2
      return
    fi
    echo "Discarding invalid cached asset: $filename" >&2
    rm -f "$target"
  fi

  local partial="$target.part"
  rm -f "$partial"
  echo "Downloading pinned Walking Aid asset: $filename" >&2
  curl --fail --location --retry 3 --retry-all-errors --connect-timeout 30 \
    --max-time 600 --output "$partial" "$url"
  actual_size="$(stat -c %s "$partial")"
  [[ "$actual_size" == "$expected_size" ]] || {
    echo "$filename size mismatch: got $actual_size, expected $expected_size" >&2
    rm -f "$partial"
    exit 4
  }
  actual_sha="$(sha256sum "$partial" | awk '{print $1}')"
  [[ "$actual_sha" == "$expected_sha" ]] || {
    echo "$filename SHA-256 mismatch: got $actual_sha, expected $expected_sha" >&2
    rm -f "$partial"
    exit 4
  }
  mv "$partial" "$target"
}

total_bytes=0
largest_asset_bytes=0
mapfile -t asset_rows < <(grep -vE '^[[:space:]]*(#|$)' "$manifest")
for row in "${asset_rows[@]}"; do
  IFS=$'\t' read -r kind filename size sha url <<< "$row"
  [[ "$kind" == "model" || "$kind" == "fixture" ]] || {
    echo "Invalid asset kind in $manifest: $kind" >&2
    exit 4
  }
  download_asset "$filename" "$size" "$sha" "$url"
  total_bytes=$((total_bytes + size))
  if (( size > largest_asset_bytes )); then
    largest_asset_bytes="$size"
  fi
done

test "$(adb_retry get-state)" = "device"
package_path="$(adb_retry shell pm path "$CYANBRIDGE_PACKAGE" | tr -d '\r')"
test_package_path="$(adb_retry shell pm path "$CYANBRIDGE_TEST_PACKAGE" | tr -d '\r')"
[[ "$package_path" == package:* && "$test_package_path" == package:* ]] || {
  echo "Install the debug and androidTest APKs before running Walking Aid model CI" >&2
  exit 5
}

# Delete only stale test-owned files. Never clear the persistent emulator's CyanBridge data,
# because it may contain a verified Pro account or other manually provisioned HIL settings.
adb_retry shell run-as "$CYANBRIDGE_PACKAGE" rm -rf files/walking_aid_ci >/dev/null 2>&1 || true
adb_retry shell \
  "rm -rf /data/local/tmp/cyanbridge-walking-aid-ci /data/local/tmp/cyanbridge-walking-aid-ci-*" \
  >/dev/null 2>&1 || true
adb_retry shell pm trim-caches 1G >/dev/null 2>&1 || true

minimum_free_after_stage=$((256 * 1024 * 1024))
required_free=$((total_bytes + largest_asset_bytes + minimum_free_after_stage))
available_kb="$(adb_retry shell df -Pk /data | tr -d '\r' | awk 'END {print $4}')"
[[ "$available_kb" =~ ^[0-9]+$ ]] || { echo "Could not read emulator /data free space" >&2; exit 6; }
available_bytes=$((available_kb * 1024))
if (( available_bytes < required_free )); then
  cat >&2 <<EOF
Emulator /data has only $((available_bytes / 1024 / 1024)) MiB free; Walking Aid CI needs
$((required_free / 1024 / 1024)) MiB. Use a dedicated AVD with an 8 GiB data partition, or set
disk.dataPartition.size=8G in its config.ini while the AVD is stopped and cold boot it.
EOF
  exit 6
fi

declare -a staged_models=()
cleanup() {
  set +e
  adb_retry shell run-as "$CYANBRIDGE_PACKAGE" rm -rf files/walking_aid_ci >/dev/null 2>&1
  adb_retry shell \
    "rm -rf /data/local/tmp/cyanbridge-walking-aid-ci /data/local/tmp/cyanbridge-walking-aid-ci-*" \
    >/dev/null 2>&1
  local filename marker
  for filename in "${staged_models[@]}"; do
    marker="files/$filename.walking-aid-ci-staged"
    adb_retry shell run-as "$CYANBRIDGE_PACKAGE" rm -f "files/$filename" >/dev/null 2>&1
    if app_file_exists "files/$filename.walking-aid-ci-backup"; then
      adb_retry shell run-as "$CYANBRIDGE_PACKAGE" mv \
        "files/$filename.walking-aid-ci-backup" "files/$filename" >/dev/null 2>&1
    fi
    adb_retry shell run-as "$CYANBRIDGE_PACKAGE" rm -f "$marker" >/dev/null 2>&1
  done
}
trap cleanup EXIT

adb_retry shell run-as "$CYANBRIDGE_PACKAGE" mkdir -p files/walking_aid_ci
for row in "${asset_rows[@]}"; do
  IFS=$'\t' read -r kind filename size sha url <<< "$row"
  if [[ "$kind" == "model" ]]; then
    target="files/$filename"
    backup="files/$filename.walking-aid-ci-backup"
    marker="files/$filename.walking-aid-ci-staged"
    # Recover a test-owned model after a prior process was interrupted while ADB was offline.
    if app_file_exists "$marker"; then
      adb_retry shell run-as "$CYANBRIDGE_PACKAGE" rm -f "$target"
      if app_file_exists "$backup"; then
        adb_retry shell run-as "$CYANBRIDGE_PACKAGE" mv "$backup" "$target"
      fi
      adb_retry shell run-as "$CYANBRIDGE_PACKAGE" rm -f "$marker"
    fi
    # Recover an interrupted prior run before inspecting the user's model file.
    if app_file_exists "$backup"; then
      adb_retry shell run-as "$CYANBRIDGE_PACKAGE" rm -f "$target"
      adb_retry shell run-as "$CYANBRIDGE_PACKAGE" mv "$backup" "$target"
    fi
    existing_sha=""
    if app_file_exists "$target"; then
      existing_sha="$(adb_retry shell run-as "$CYANBRIDGE_PACKAGE" sha256sum "$target" | awk '{print $1}')"
    fi
    if [[ "$existing_sha" == "$sha" ]]; then
      echo "Using verified model already installed on emulator: $filename" >&2
      continue
    fi
    adb_retry shell run-as "$CYANBRIDGE_PACKAGE" touch "$marker"
    staged_models+=("$filename")
    if app_file_exists "$target"; then
      adb_retry shell run-as "$CYANBRIDGE_PACKAGE" mv "$target" "$backup"
    fi
  else
    target="files/walking_aid_ci/$filename"
  fi

  remote="/data/local/tmp/cyanbridge-walking-aid-ci-$filename"
  echo "Staging $filename into the app sandbox" >&2
  adb_retry push "$cache_dir/$filename" "$remote" >/dev/null
  adb_retry shell chmod 0644 "$remote"
  adb_retry shell run-as "$CYANBRIDGE_PACKAGE" cp "$remote" "$target"
  adb_retry shell rm -f "$remote"
  device_sha="$(adb_retry shell run-as "$CYANBRIDGE_PACKAGE" sha256sum "$target" | awk '{print $1}')"
  [[ "$device_sha" == "$sha" ]] || { echo "Device staging checksum failed for $filename" >&2; exit 7; }
done

test_class="com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidVisionStackIntegrationTest"
serial_slug="${serial//[:\/]/_}"
declare -a phases=(
  "yolo11:realModel_runYolo11OnGlassesLikeJpegs"
  "yolo-world:realModel_runYoloWorldOnGlassesLikeJpegs"
  "depth-anything:realModel_runDepthAnythingOnGlassesLikeJpegs"
)

# Run each large graph in a fresh instrumentation process. Closing an Interpreter releases its
# Java owner, but native LiteRT/XNNPACK allocations are not guaranteed to return to Android before
# another graph is created in the same process.
for phase in "${phases[@]}"; do
  IFS=: read -r phase_name method <<< "$phase"
  out="$result_dir/instrumentation-$phase_name-$serial_slug.txt"
  logcat_out="$result_dir/litert-logcat-$phase_name-$serial_slug.txt"
  remote_out="/data/local/tmp/cyanbridge-walking-aid-ci-$phase_name.out"
  success_marker="files/walking_aid_ci/results/$phase_name.success"
  failure_marker="files/walking_aid_ci/results/$phase_name.failure"

  test "$(adb_retry get-state)" = "device"
  adb_retry shell am force-stop "$CYANBRIDGE_PACKAGE" >/dev/null 2>&1 || true
  adb_retry logcat -c
  echo "Running $phase_name real-model inference on $serial" >&2
  adb_retry shell rm -f "$remote_out"
  adb_retry shell run-as "$CYANBRIDGE_PACKAGE" rm -f "$success_marker" "$failure_marker"

  # Keep best-effort JUnit output in the guest and rely on the test's app-sandbox result marker.
  # A CPU-heavy emulator inference can reset ADB and its originating shell even though the
  # instrumentation process continues and finishes successfully.
  instrument_command="am instrument -w -r -e walkingAidRealModels true -e class $test_class#$method $CYANBRIDGE_TEST_PACKAGE/$CYANBRIDGE_TEST_RUNNER > $remote_out 2>&1"
  adb_retry shell "nohup sh -c '$instrument_command' >/dev/null 2>&1 </dev/null &"

  deadline=$((SECONDS + 5 * 60))
  phase_result=""
  while (( SECONDS < deadline )); do
    if timeout 30s "$ADB_BIN" -s "$serial" wait-for-device >/dev/null 2>&1; then
      if app_file_exists "$success_marker"; then
        phase_result="pass"
        break
      fi
      if app_file_exists "$failure_marker"; then
        phase_result="fail"
        break
      fi
    fi
    sleep 2
  done
  if [[ -z "$phase_result" ]]; then
    echo "Walking Aid $phase_name did not leave an on-device test result" >&2
    exit 12
  fi
  if remote_file_exists "$remote_out"; then
    adb_retry shell cat "$remote_out" | tr -d '\r' > "$out"
  else
    : > "$out"
  fi
  if [[ "$phase_result" == "fail" ]]; then
    adb_retry shell run-as "$CYANBRIDGE_PACKAGE" cat "$failure_marker" | tee -a "$out" >&2
    echo "Walking Aid $phase_name instrumentation reported a failure" >&2
    exit 10
  fi
  echo "DEVICE_TEST_RESULT: PASS ($phase_name)" | tee -a "$out"
  adb_retry logcat -d -s WalkingAidModelCI LiteRtVisionBackend > "$logcat_out" || true
  adb_retry shell rm -f "$remote_out" >/dev/null 2>&1 || true
done

echo "Walking Aid on-device model CI passed; results: $result_dir"
