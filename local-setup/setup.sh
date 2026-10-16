#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Creates the local Kubernetes side of the demo (run via `make cluster`):
#   1. kind cluster "cicd-demo" (downloads pinned kind/kubectl to .bin/ if missing)
#   2. tells the kind node that localhost:5001 means the "kind-registry" container
#   3. namespaces demo-dev and demo-prod (they stand in for two clusters)
#   4. the least-privilege jenkins-deployer ServiceAccount + Role in each
#   5. one kubeconfig per namespace in local-setup/.secrets/ (git-ignored),
#      which docker compose mounts into Jenkins as credentials
#   6. checks that the permissions are what we expect
# Safe to run more than once.
# ---------------------------------------------------------------------------
set -euo pipefail

CLUSTER=cicd-demo
CONTEXT="kind-$CLUSTER"
NAMESPACES=(demo-dev demo-prod)
KIND_VERSION=v0.33.0
KUBECTL_VERSION=v1.36.4
REGISTRY_NAME=kind-registry
REGISTRY_PORT=5001

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="$ROOT/local-setup/.bin"
SECRETS="$ROOT/local-setup/.secrets"
export PATH="$BIN:$PATH"

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

# ---- 0. tools ---------------------------------------------------------------
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }

# Download a binary and verify it against the publisher's SHA-256 checksum.
download() {
  local name="$1" url="$2" sum_url="$3"
  log "Downloading $name to $BIN (not found on PATH)"
  mkdir -p "$BIN"
  curl -fsSLo "$BIN/$name" "$url"
  local expected
  expected="$(curl -fsSL "$sum_url" | cut -d' ' -f1)"
  if [ "$(sha256 "$BIN/$name")" != "$expected" ]; then
    rm -f "$BIN/$name"
    echo "ERROR: checksum mismatch for $name, download deleted." >&2
    exit 1
  fi
  chmod +x "$BIN/$name"
}

command -v docker >/dev/null || { echo "ERROR: docker is required (Docker Desktop or Docker Engine)." >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "ERROR: Docker is not running. Start Docker Desktop first." >&2; exit 1; }

OS="$(uname -s | tr '[:upper:]' '[:lower:]')"                      # linux | darwin
ARCH="$(uname -m)"; case "$ARCH" in x86_64) ARCH=amd64 ;; aarch64|arm64) ARCH=arm64 ;; esac

if ! command -v kind >/dev/null; then
  download kind "https://kind.sigs.k8s.io/dl/$KIND_VERSION/kind-$OS-$ARCH" \
                "https://kind.sigs.k8s.io/dl/$KIND_VERSION/kind-$OS-$ARCH.sha256sum"
fi
if ! command -v kubectl >/dev/null; then
  download kubectl "https://dl.k8s.io/release/$KUBECTL_VERSION/bin/$OS/$ARCH/kubectl" \
                   "https://dl.k8s.io/release/$KUBECTL_VERSION/bin/$OS/$ARCH/kubectl.sha256"
fi

# ---- 1. cluster -------------------------------------------------------------
if kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  log "kind cluster '$CLUSTER' already exists"
else
  log "Creating kind cluster '$CLUSTER'"
  kind create cluster --config "$ROOT/local-setup/kind-config.yaml" --wait 120s
fi
kubectl config use-context "$CONTEXT" >/dev/null
KUBECTL=(kubectl --context "$CONTEXT")

# ---- 2. registry mapping ------------------------------------------------------
# Inside the node, "localhost" is the node itself. This containerd setting
# sends pulls of localhost:5001/... to the registry container on the kind network.
log "Pointing localhost:$REGISTRY_PORT at the '$REGISTRY_NAME' container on each node"
for node in $(kind get nodes --name "$CLUSTER"); do
  docker exec "$node" mkdir -p "/etc/containerd/certs.d/localhost:$REGISTRY_PORT"
  printf '[host."http://%s:5000"]\n' "$REGISTRY_NAME" \
    | docker exec -i "$node" cp /dev/stdin "/etc/containerd/certs.d/localhost:$REGISTRY_PORT/hosts.toml"
done

# ---- 3 + 4. namespaces and RBAC ---------------------------------------------
for ns in "${NAMESPACES[@]}"; do
  log "Namespace $ns + jenkins-deployer RBAC"
  "${KUBECTL[@]}" create namespace "$ns" --dry-run=client -o yaml | "${KUBECTL[@]}" apply -f -
  "${KUBECTL[@]}" apply -n "$ns" -f "$ROOT/k8s/rbac/jenkins-deployer.yaml"
done

# ---- 5. kubeconfigs for Jenkins ---------------------------------------------
# Jenkins runs in a container on the "kind" network, so it reaches the API server
# by the node's container name, not via 127.0.0.1 like your laptop does.
# (The API server certificate includes that name.)
SERVER="https://$CLUSTER-control-plane:6443"
mkdir -p "$SECRETS"
for ns in "${NAMESPACES[@]}"; do
  out="$SECRETS/kubeconfig-${ns#demo-}"           # kubeconfig-dev, kubeconfig-prod
  "$ROOT/k8s/rbac/generate-kubeconfig.sh" "$ns" "$out" "$SERVER" 720h
  # Compose mounts the file with its host owner and mode. The jenkins user in the
  # container has UID 1000; if your UID differs (some Linux setups), the file has
  # to be readable by others. It stays in a git-ignored folder on your machine.
  if [ "$(id -u)" != "1000" ]; then chmod 644 "$out"; fi
done

# ---- 6. verify least privilege ------------------------------------------------
log "Checking jenkins-deployer permissions (expected: yes yes no no no)"
SA_DEV="system:serviceaccount:demo-dev:jenkins-deployer"
check() { printf '  %-58s %s\n' "$1" "$("${KUBECTL[@]}" auth can-i "${@:2}" --as "$SA_DEV" 2>/dev/null || true)"; }
check "dev deployer: patch deployments in demo-dev"         patch deployments -n demo-dev
check "dev deployer: create pods/portforward in demo-dev"   create pods --subresource=portforward -n demo-dev
check "dev deployer: get secrets in demo-dev"               get secrets -n demo-dev
check "dev deployer: delete deployments in demo-dev"        delete deployments -n demo-dev
check "dev deployer: patch deployments in demo-prod"        patch deployments -n demo-prod

cat <<EOF

$(printf '\033[1;32m')Cluster ready.$(printf '\033[0m') Kubeconfigs for Jenkins: local-setup/.secrets/kubeconfig-{dev,prod}

Next steps:
  1. cp .env.example .env      # then fill in the values (see docs/SETUP.md)
  2. make up                   # builds and starts Jenkins + the registry
  3. open http://localhost:8080 and log in with JENKINS_ADMIN_ID / JENKINS_ADMIN_PASSWORD
  4. run "seed-job" once: it creates the demo-app Multibranch Pipeline
  5. watch pods:  kubectl --context $CONTEXT get pods -n demo-dev -w
EOF
