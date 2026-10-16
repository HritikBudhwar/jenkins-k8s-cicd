#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Creates a kubeconfig for the jenkins-deployer ServiceAccount in one namespace.
# Upload the output file to Jenkins as a "Secret file" credential
# (one credential per cluster/namespace, e.g. kubeconfig-dev, kubeconfig-prod).
#
# Usage:
#   k8s/rbac/generate-kubeconfig.sh <namespace> <output-file> [server-url] [token-duration]
#
#   namespace       namespace where jenkins-deployer.yaml was applied
#   output-file     where to write the kubeconfig (mode 600)
#   server-url      API server URL *as Jenkins will see it*. Default: the URL in
#                   your current kubectl context.
#   token-duration  lifetime of the token. Default 720h (30 days).
#
# Security: the token comes from `kubectl create token`, so it is short-lived
# and bound to the ServiceAccount. No long-lived token Secret is created.
# Rotate it by re-running this script and updating the Jenkins credential.
# Run it as someone who is allowed to create tokens (e.g. cluster admin).
# ---------------------------------------------------------------------------
set -euo pipefail

NAMESPACE="${1:?usage: $0 <namespace> <output-file> [server-url] [token-duration]}"
OUTPUT="${2:?usage: $0 <namespace> <output-file> [server-url] [token-duration]}"
SERVER="${3:-$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}')}"
DURATION="${4:-720h}"
SA="jenkins-deployer"

# Fail early with a clear message instead of writing a half-broken kubeconfig.
if ! kubectl -n "$NAMESPACE" get serviceaccount "$SA" >/dev/null 2>&1; then
  echo "ERROR: ServiceAccount $SA not found in namespace $NAMESPACE." >&2
  echo "       Apply it first: kubectl apply -n $NAMESPACE -f k8s/rbac/jenkins-deployer.yaml" >&2
  exit 1
fi

TOKEN="$(kubectl -n "$NAMESPACE" create token "$SA" --duration="$DURATION")"

# Copy the cluster CA from the current context so Jenkins verifies the server's TLS certificate.
CA_FILE="$(mktemp)"
trap 'rm -f "$CA_FILE"' EXIT
kubectl config view --raw --minify -o jsonpath='{.clusters[0].cluster.certificate-authority-data}' \
  | base64 -d > "$CA_FILE"

# Write a fresh kubeconfig with one cluster, one user and one context.
rm -f "$OUTPUT"
umask 077   # file readable by the owner only
KCFG=(kubectl config --kubeconfig="$OUTPUT")
"${KCFG[@]}" set-cluster target --server="$SERVER" --certificate-authority="$CA_FILE" --embed-certs=true >/dev/null
"${KCFG[@]}" set-credentials "$SA" --token="$TOKEN" >/dev/null
"${KCFG[@]}" set-context deploy --cluster=target --user="$SA" --namespace="$NAMESPACE" >/dev/null
"${KCFG[@]}" use-context deploy >/dev/null
chmod 600 "$OUTPUT"

echo "Wrote $OUTPUT (namespace=$NAMESPACE, server=$SERVER, token valid for $DURATION)"
