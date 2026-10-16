#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Static checks for the whole repo. Run by `make lint` and by GitHub Actions
# (.github/workflows/ci.yml), so a mistake is caught before Jenkins sees it.
#
#   1. hadolint     Dockerfiles (app/ and jenkins/)
#   2. kubeconform  every Kustomize overlay + the RBAC manifest, against the
#                   Kubernetes API schema
#   3. Groovy       syntax of Jenkinsfiles, vars/, src/ and the seed script,
#                   parsed with the Groovy version Jenkins uses
#   4. shellcheck   every shell script
#   5. actionlint   GitHub Actions workflows
#   6. gitleaks     secrets in the files you would commit, and in the full
#                   Git history
#
# Every tool runs in its official container image, so the only requirement is
# Docker. Versions of the tools that Jenkins also runs (hadolint, kustomize,
# kubeconform, gitleaks) are read from jenkins/Dockerfile, so this script,
# CI and the pipeline always use the same versions.
#
# All checks run even if one fails; the summary at the end lists the failures.
# ---------------------------------------------------------------------------
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
ROOT="$PWD"

if ! command -v docker >/dev/null || ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker is required and must be running." >&2
  exit 1
fi

# Image of a tool stage in jenkins/Dockerfile, e.g. "FROM hadolint/hadolint:v2.15.1 AS hadolint".
image_from_dockerfile() {
  local ref
  ref="$(awk -v name="$1" '$1 == "FROM" && $3 == "AS" && $4 == name { print $2 }' jenkins/Dockerfile)"
  [ -n "$ref" ] || { echo "ERROR: no 'FROM ... AS $1' stage in jenkins/Dockerfile" >&2; exit 1; }
  echo "$ref"
}
HADOLINT_IMAGE="$(image_from_dockerfile hadolint)"
KUSTOMIZE_IMAGE="$(image_from_dockerfile kustomize)"
KUBECONFORM_IMAGE="$(image_from_dockerfile kubeconform)"
GITLEAKS_IMAGE="$(image_from_dockerfile gitleaks)"
SHELLCHECK_IMAGE=koalaman/shellcheck:v0.11.0
ACTIONLINT_IMAGE=rhysd/actionlint:1.7.12
JDK_IMAGE="${JDK_IMAGE:-eclipse-temurin:17.0.16_8-jdk}"     # same as the Makefile

# Kubernetes schema version: the pipeline's default, so lint here = Lint stage in Jenkins.
K8S_VERSION="$(sed -n "s/.*kubernetesVersion *: *'\([0-9.]*\)'.*/\1/p" src/org/devops/PipelineConfig.groovy)"

# Run a container as you (not root), with the repo mounted at /work.
in_docker() {
  docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp -v "$ROOT:/work" -w /work "$@"
}

FAILED=()
check() {
  local name="$1"; shift
  printf '\n\033[1;34m==> %s\033[0m\n' "$name"
  if "$@"; then
    printf '\033[32mPASS\033[0m  %s\n' "$name"
  else
    printf '\033[31mFAIL\033[0m  %s\n' "$name"
    FAILED+=("$name")
  fi
}

# ---- 1. Dockerfiles ---------------------------------------------------------
check_dockerfiles() {
  in_docker "$HADOLINT_IMAGE" hadolint --config .hadolint.yaml app/Dockerfile jenkins/Dockerfile \
    && echo "app/Dockerfile and jenkins/Dockerfile: no findings"
}

# ---- 2. Kubernetes manifests ------------------------------------------------
check_manifests() {
  local ok=0 overlay
  for overlay in k8s/overlays/*/; do
    overlay="${overlay%/}"
    echo "--- $overlay"
    in_docker "$KUSTOMIZE_IMAGE" build "$overlay" \
      | docker run --rm -i "$KUBECONFORM_IMAGE" -strict -summary -kubernetes-version "$K8S_VERSION" - \
      || ok=1
  done
  echo "--- k8s/rbac/jenkins-deployer.yaml"
  docker run --rm -i "$KUBECONFORM_IMAGE" -strict -summary -kubernetes-version "$K8S_VERSION" - \
    < k8s/rbac/jenkins-deployer.yaml || ok=1
  return "$ok"
}

# ---- 3. Groovy syntax -------------------------------------------------------
check_groovy() {
  in_docker -e GRADLE_USER_HOME=/work/test/.gradle -w /work/test "$JDK_IMAGE" \
    ./gradlew checkGroovySyntax --no-daemon --quiet --console=plain
}

# ---- 4. Shell scripts -------------------------------------------------------
check_shell() {
  local scripts=()
  # Every *.sh in the repo except downloaded/generated folders.
  while IFS= read -r -d '' f; do scripts+=("$f"); done < <(
    find . -name '*.sh' -type f \
      -not -path './.git/*' -not -path './local-setup/.bin/*' -not -path './.github/modernize/*' \
      -not -path './test/.gradle/*' -not -path './test/build/*' -print0 | sort -z)
  in_docker "$SHELLCHECK_IMAGE" --severity=style "${scripts[@]}" \
    && printf '%s\n' "${scripts[@]}" | sed 's/^/OK    /'
}

# ---- 5. GitHub Actions workflows -------------------------------------------
check_workflows() {
  if ! ls .github/workflows/*.yml >/dev/null 2>&1; then
    echo "No workflows found: skipped."
    return 0
  fi
  in_docker "$ACTIONLINT_IMAGE" -color && echo ".github/workflows: no findings"
}

# ---- 6. Secrets -------------------------------------------------------------
check_secrets() {
  local ok=0 tmp
  # a) The files you would commit: tracked + untracked-but-not-ignored. Ignored
  #    files (.env, local-setup/.secrets/ with real tokens) are skipped on purpose:
  #    they never leave your machine.
  tmp="$(mktemp -d)"
  if [ -d .git ]; then
    git ls-files -z --cached --others --exclude-standard \
      | while IFS= read -r -d '' f; do [ -f "$f" ] && printf '%s\0' "$f"; done \
      | tar --null -T - -cf - | tar -xf - -C "$tmp"
  else
    cp -R . "$tmp"
  fi
  echo "--- working tree (files git would commit)"
  docker run --rm -v "$tmp:/scan:ro" -v "$ROOT/.gitleaks.toml:/config.toml:ro" "$GITLEAKS_IMAGE" \
    dir /scan --config /config.toml --redact --no-banner --exit-code 1 || ok=1
  rm -rf "$tmp"

  # b) Every commit in the history: a secret that was committed and then deleted
  #    is still in Git, and still has to be rotated.
  if [ -d .git ]; then
    echo "--- full Git history"
    in_docker "$GITLEAKS_IMAGE" git /work --config /work/.gitleaks.toml --redact --no-banner --exit-code 1 || ok=1
  fi
  return "$ok"
}

check "Dockerfiles (hadolint)"                 check_dockerfiles
check "Kubernetes manifests (kubeconform $K8S_VERSION)" check_manifests
check "Groovy syntax (Groovy 2.4, as in Jenkins)" check_groovy
check "Shell scripts (shellcheck)"             check_shell
check "GitHub Actions workflows (actionlint)"  check_workflows
check "Secrets (gitleaks)"                     check_secrets

echo
if [ "${#FAILED[@]}" -eq 0 ]; then
  printf '\033[1;32mAll checks passed.\033[0m\n'
else
  printf '\033[1;31m%d check(s) failed:\033[0m\n' "${#FAILED[@]}"
  printf '  - %s\n' "${FAILED[@]}"
  exit 1
fi
