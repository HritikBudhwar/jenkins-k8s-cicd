#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Demo helper: make the NEXT deployment fail on purpose, to show the automatic
# rollback (docs/TESTING.md, scenario 6).
#
#   scripts/break-deploy.sh on    # readiness probe points to a path that returns 404
#   scripts/break-deploy.sh off   # restore the correct probe
#
# After "on": commit + push to develop. The new pods never become Ready,
# `kubectl rollout status` times out, deployToK8s runs `kubectl rollout undo`,
# and the build fails with "AUTOMATICALLY ROLLED BACK". The old pods keep
# serving the whole time (maxUnavailable: 0).
# ---------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")/.."
FILE=k8s/base/deployment.yaml
GOOD='path: /ready'
BAD='path: /broken-on-purpose   # BROKEN by scripts/break-deploy.sh: run "scripts/break-deploy.sh off"'

case "${1:-}" in
  on)
    grep -q 'broken-on-purpose' "$FILE" && { echo "Already broken."; exit 0; }
    sed -i.bak "s|${GOOD}|${BAD}|" "$FILE"
    rm -f "$FILE.bak"
    grep -q 'broken-on-purpose' "$FILE" || { echo "ERROR: could not find '$GOOD' in $FILE" >&2; exit 1; }
    echo "Readiness probe broken in $FILE. Now: git commit -am 'test: break readiness probe' && git push origin develop"
    ;;
  off)
    sed -i.bak "s|${BAD}|${GOOD}|" "$FILE"
    rm -f "$FILE.bak"
    ! grep -q 'broken-on-purpose' "$FILE" || { echo "ERROR: could not restore $FILE; fix it by hand" >&2; exit 1; }
    echo "Readiness probe restored in $FILE. Commit and push to deploy a healthy version again."
    ;;
  *)
    echo "usage: $0 on|off" >&2
    exit 1
    ;;
esac
