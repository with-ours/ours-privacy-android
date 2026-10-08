#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
capture_dir="${E2E_CAPTURE_DIR:-$(mktemp -d)}"
mkdir -p "$capture_dir"
if compgen -G "$capture_dir/*_ingest.json" > /dev/null; then
  echo "Capture directory must be empty: $capture_dir" >&2
  exit 1
fi
token="e2e-$(python3 -c 'import uuid; print(uuid.uuid4().hex)')"
recorder_pid=""
cleanup() {
  if [[ -n "$recorder_pid" ]]; then
    kill "$recorder_pid" 2>/dev/null || true
    wait "$recorder_pid" 2>/dev/null || true
  fi
  if [[ -z "${E2E_CAPTURE_DIR:-}" ]]; then
    rm -rf "$capture_dir"
  fi
}
trap cleanup EXIT

cd "$repo_dir"
adb wait-for-device
adb uninstall com.oursprivacy.oursprivacydemo >/dev/null 2>&1 || true
python3 tools/payload-recorder/server.py --port 8765 --out "$capture_dir" &
recorder_pid="$!"
sleep 1
kill -0 "$recorder_pid"

./gradlew :oursprivacydemo:connectedDebugAndroidTest \
  -PrecorderUrl=http://10.0.2.2:8765 \
  "-PdemoToken=$token"

version="$(sed -n 's/^VERSION_NAME=//p' gradle.properties)"
for attempt in {1..30}; do
  if python3 tools/payload-recorder/assert_payloads.py \
    --out "$capture_dir" --version "$version" --token "$token" >/dev/null 2>&1; then
    python3 tools/payload-recorder/assert_payloads.py \
      --out "$capture_dir" --version "$version" --token "$token"
    exit 0
  fi
  sleep 1
done
python3 tools/payload-recorder/assert_payloads.py \
  --out "$capture_dir" --version "$version" --token "$token"
