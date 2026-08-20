#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"

serial="${1:-${CYANBRIDGE_HIL_SERIAL:-}}"
mode="${2:-hardware}"
classes="${3:-}"
glasses="${CYANBRIDGE_HIL_GLASSES:-false}"
expect_visual_fact="${CYANBRIDGE_HIL_EXPECT_VISUAL_FACT:-false}"

if [[ -z "$serial" ]]; then
  serial="$(find_serial any || true)"
fi
if [[ -z "$serial" ]]; then
  echo "No Android target available for instrumentation" >&2
  exit 3
fi

mkdir -p "$HIL_BUILD_DIR/results"
out="$HIL_BUILD_DIR/results/instrumentation-${mode}-${serial//[:\/]/_}.txt"

cmd=(
  shell am instrument -w -r
  -e hil_mode "$mode"
  -e hil_glasses "$glasses"
  -e hil_expect_visual_fact "$expect_visual_fact"
)
if [[ -n "$classes" ]]; then
  cmd+=( -e class "$classes" )
fi
cmd+=( "$CYANBRIDGE_TEST_PACKAGE/$CYANBRIDGE_TEST_RUNNER" )

set +e
adb_for "$serial" "${cmd[@]}" | tee "$out"
status=${PIPESTATUS[0]}
set -e

if (( status != 0 )); then
  echo "Instrumentation command failed with adb status $status" >&2
  exit "$status"
fi
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=Process crashed' "$out"; then
  echo "Instrumentation reported test failures" >&2
  exit 10
fi
if ! grep -Eq 'OK \([0-9]+ tests?\)|OK \([0-9]+ test\)' "$out"; then
  echo "Instrumentation did not report a successful JUnit completion" >&2
  exit 11
fi

echo "Instrumentation passed: $out"
