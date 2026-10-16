#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Smoke test for a freshly deployed app. Loaded by vars/smokeTest.groovy with
# libraryResource, so every repo using the library gets the same test.
#
# Usage: smoke-test.sh <namespace> <service> <expected-version>
#
# How it reaches the app: `kubectl port-forward svc/<service>` through the
# Kubernetes API, so the Service does not have to be exposed outside the
# cluster. Port 0 lets kubectl pick a free local port, so parallel builds never
# clash over a port.
# Checks: /health and /ready return 200, "/" reports the expected version
# (proves the NEW pods are serving), and /metrics answers.
# ---------------------------------------------------------------------------
set -euo pipefail

NAMESPACE="${1:?namespace required}"
SERVICE="${2:?service required}"
EXPECTED_VERSION="${3:?expected version required}"

PF_LOG="$(mktemp)"
kubectl -n "$NAMESPACE" port-forward "svc/$SERVICE" :80 >"$PF_LOG" 2>&1 &
PF_PID=$!
# Always stop the port-forward and clean up, whether the test passes or fails.
trap 'kill "$PF_PID" 2>/dev/null || true; rm -f "$PF_LOG"' EXIT

# Wait (max ~10s) for kubectl to print "Forwarding from 127.0.0.1:<port> -> 8080".
PORT=""
for _ in $(seq 1 20); do
  PORT="$(sed -n 's/^Forwarding from 127\.0\.0\.1:\([0-9]*\).*/\1/p' "$PF_LOG" | head -n 1)"
  [ -n "$PORT" ] && break
  sleep 0.5
done
if [ -z "$PORT" ]; then
  echo "SMOKE TEST FAILED: could not port-forward to svc/$SERVICE in $NAMESPACE:" >&2
  cat "$PF_LOG" >&2
  exit 1
fi
BASE="http://127.0.0.1:$PORT"

# GET a path, retrying a few times (the first request through a new tunnel can be slow).
fetch() {
  local path="$1" attempt
  for attempt in 1 2 3 4 5; do
    if curl -fsS --max-time 5 "$BASE$path"; then return 0; fi
    echo "  $path not answering yet (attempt $attempt/5)" >&2
    sleep 2
  done
  return 1
}

for path in /health /ready; do
  fetch "$path" >/dev/null || { echo "SMOKE TEST FAILED: $path did not return 200" >&2; exit 1; }
  echo "OK   $path"
done

BODY="$(fetch /)" || { echo "SMOKE TEST FAILED: / did not return 200" >&2; exit 1; }
echo "     / -> $BODY"
if ! grep -Eq "\"version\": ?\"$EXPECTED_VERSION\"" <<<"$BODY"; then
  echo "SMOKE TEST FAILED: expected version $EXPECTED_VERSION, the service answered with: $BODY" >&2
  exit 1
fi
echo "OK   / reports version $EXPECTED_VERSION"

fetch /metrics | grep -q '^app_requests_total' \
  || { echo "SMOKE TEST FAILED: /metrics has no app_requests_total" >&2; exit 1; }
echo "OK   /metrics"

echo "Smoke test passed for $SERVICE in $NAMESPACE"
