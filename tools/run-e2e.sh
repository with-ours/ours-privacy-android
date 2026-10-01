#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
capture_dir="$(mktemp -d)"
recorder_pid=""
cleanup() {
  if [[ -n "$recorder_pid" ]]; then
    kill "$recorder_pid" 2>/dev/null || true
    wait "$recorder_pid" 2>/dev/null || true
  fi
  rm -rf "$capture_dir"
}
trap cleanup EXIT

cd "$repo_dir"
python3 tools/payload-recorder/server.py --port 8765 --out "$capture_dir" &
recorder_pid="$!"
sleep 1
kill -0 "$recorder_pid"

./gradlew :oursprivacydemo:connectedDebugAndroidTest \
  -PrecorderUrl=http://10.0.2.2:8765 \
  -PdemoToken=e2e-token

version="$(sed -n 's/^VERSION_NAME=//p' gradle.properties)"
for attempt in {1..30}; do
  if python3 tools/payload-recorder/assert_payloads.py \
    --out "$capture_dir" --version "$version" >/dev/null 2>&1; then
    python3 tools/payload-recorder/assert_payloads.py \
      --out "$capture_dir" --version "$version"
    exit 0
  fi
  sleep 1
done
python3 tools/payload-recorder/assert_payloads.py \
  --out "$capture_dir" --version "$version"
