#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEFAULT_SOURCE_DIR="$ROOT_DIR/../_local_termux_server"
FALLBACK_SOURCE_DIR="$ROOT_DIR/../../../../CyanBridge/_local_termux_server_phone_current"
SOURCE_DIR="${CYANBRIDGE_SERVER_SOURCE:-}"
CARELENS_PROJECT_JSON="$ROOT_DIR/../../Carelens/.vercel/project.json"
LOCAL_PROJECT_JSON="$ROOT_DIR/.vercel/project.json"
STAGE_DIR="$(mktemp -d /tmp/opencode/cyanbridge-vercel-XXXXXX)"

if [[ -z "$SOURCE_DIR" ]]; then
  if [[ -f "$DEFAULT_SOURCE_DIR/app.py" ]]; then
    SOURCE_DIR="$DEFAULT_SOURCE_DIR"
  else
    SOURCE_DIR="$FALLBACK_SOURCE_DIR"
  fi
fi

cleanup() {
  rm -rf "$STAGE_DIR"
}
trap cleanup EXIT

if [[ ! -f "$SOURCE_DIR/app.py" ]]; then
  printf 'Missing source server app: %s/app.py\n' "$SOURCE_DIR" >&2
  exit 1
fi

if [[ ! -f "$SOURCE_DIR/requirements.txt" ]]; then
  printf 'Missing source server requirements: %s/requirements.txt\n' "$SOURCE_DIR" >&2
  exit 1
fi

PROJECT_JSON_SOURCE=""
if [[ -f "$LOCAL_PROJECT_JSON" ]]; then
  PROJECT_JSON_SOURCE="$LOCAL_PROJECT_JSON"
elif [[ -f "$CARELENS_PROJECT_JSON" ]]; then
  PROJECT_JSON_SOURCE="$CARELENS_PROJECT_JSON"
fi

if [[ -z "$PROJECT_JSON_SOURCE" ]]; then
  printf 'Missing Vercel project link. Run `npx vercel link` in vercel_server/ or keep ../../Carelens/.vercel/project.json available.\n' >&2
  exit 1
fi

mkdir -p "$STAGE_DIR/api" "$STAGE_DIR/_local_termux_server" "$STAGE_DIR/.vercel"

cp "$ROOT_DIR/api/index.py" "$STAGE_DIR/api/index.py"
cp "$ROOT_DIR/vercel.json" "$STAGE_DIR/vercel.json"
cp "$SOURCE_DIR/app.py" "$STAGE_DIR/_local_termux_server/app.py"
cp "$SOURCE_DIR/requirements.txt" "$STAGE_DIR/requirements.txt"
cp "$PROJECT_JSON_SOURCE" "$STAGE_DIR/.vercel/project.json"

printf 'Staged minimal CyanBridge Vercel deploy at %s\n' "$STAGE_DIR"
du -sh "$STAGE_DIR"

if [[ "$#" -eq 0 ]]; then
  set -- --prod --archive=tgz
fi

cd "$STAGE_DIR"
npx vercel "$@"
