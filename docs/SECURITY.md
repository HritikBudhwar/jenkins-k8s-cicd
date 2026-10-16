# Security

How secrets, cluster access and images are protected, what the local demo gives up for convenience, and what to change before a real team uses it.

- [Secrets](#secrets)
- [Kubernetes access: least privilege](#kubernetes-access-least-privilege)
- [Supply chain: what reaches the cluster](#supply-chain-what-reaches-the-cluster)
- [Runtime hardening](#runtime-hardening)
- [Jenkins hardening](#jenkins-hardening)
- [Known trade-offs of the local demo](#known-trade-offs-of-the-local-demo)
- [Production checklist](#production-checklist)
- [If a secret leaks](#if-a-secret-leaks)

## Secrets

**No secret is ever committed.** Every secret has a single source:

| Secret | Where it lives | How Jenkins gets it |
|---|---|---|
| Jenkins admin password, GitHub token, webhook secret | `.env` (git-ignored) | docker compose passes them as environment variables; JCasC turns them into the admin user and Jenkins credentials |
| Kubeconfigs (one per cluster/namespace) | `local-setup/.secrets/` (git-ignored; mode 600, or 644 when your UID is not 1000 so the jenkins user in the container can read it) | mounted read-only at `/run/secrets/` (docker compose secrets); JCasC loads them as *Secret file* credentials |
| Registry password (optional, `registryCredentialsId`) | Jenkins credential | `withCredentials` during the push only |

Inside the pipeline:

- Credentials are only used inside `withCredentials` / `withKubeConfig`. Jenkins masks their values in the build log. `withKubeConfig` writes the kubeconfig to a temporary file and deletes it when the step ends.
- Not tested locally, because the local registry needs no login: the registry password goes to `docker login --password-stdin`, never on a command line where `ps` would show it. The shell command is in single quotes, so Groovy never puts the secret into the script text. `DOCKER_CONFIG` points into the workspace, so the login token is deleted with the workspace.
- `.gitignore` excludes `.env`, `*kubeconfig*`, `*.pem`, `*.key` and `local-setup/.secrets/`.

**Secret scanning** catches mistakes:

- The pipeline's first stage, **Secret Scan**, runs gitleaks on the checked-out code before anything is built. A leaked key never ends up in an image layer or a registry. Findings are redacted in the log.
- `make lint` and CI also scan **every commit in the Git history**. A secret that was committed and later deleted is still in Git and must be rotated.
- False positives can be allow-listed in `.gitleaks.toml`. Only the `replace-me` placeholders are allow-listed today.

## Kubernetes access: least privilege

Jenkins never uses a cluster-admin kubeconfig. For each target namespace it uses the `jenkins-deployer` ServiceAccount from `k8s/rbac/jenkins-deployer.yaml`:

- a **Role** (namespace-scoped), not a ClusterRole: it cannot see or change other namespaces or cluster-wide objects
- it can create/update/patch only the object types in `k8s/base` (Deployment, Service, ConfigMap, HPA, PodDisruptionBudget, NetworkPolicy)
- it can read pods, logs, events and ReplicaSets (for `rollout status`, `rollout undo` and diagnostics), and port-forward (for the smoke test)
- it **cannot** read Secrets, delete anything or exec into pods

`make cluster` checks this after every run. The dev deployer must be able to patch Deployments in `demo-dev` and port-forward, and must not be able to read Secrets, delete Deployments or touch `demo-prod`:

```
  dev deployer: patch deployments in demo-dev                yes
  dev deployer: create pods/portforward in demo-dev          yes
  dev deployer: get secrets in demo-dev                      no
  dev deployer: delete deployments in demo-dev               no
  dev deployer: patch deployments in demo-prod               no
```

**Tokens are short-lived.** `k8s/rbac/generate-kubeconfig.sh` uses `kubectl create token`: a bound token valid for 30 days. No long-lived token Secret is created. Renew with `make rotate-tokens` (see [SETUP.md](SETUP.md#renew-the-deployer-tokens-every-30-days)).

**One credential per target.** A leaked dev kubeconfig cannot deploy to prod.

## Supply chain: what reaches the cluster

- **Pinned inputs.** Base images are pinned by tag and digest (`python:3.12.15-slim-trixie@sha256:...`, `jenkins/jenkins:2.580.1-lts-jdk21@sha256:...`, the registry and the kind node). Python packages are pinned in `requirements*.txt`. Jenkins plugins are pinned in `jenkins/plugins.txt`. CLI tools in the Jenkins image come from their publishers' images at fixed versions. Downloads in `setup.sh` (kind, kubectl) and the Gradle distribution are verified against SHA-256 checksums.
- **Scan before push.** Trivy scans the built image before the **Push Image** stage. HIGH or CRITICAL vulnerabilities with a fix available fail the build, so a vulnerable image never reaches the registry or a cluster. Unfixable CVEs are not blocking: they would fail every build with nothing to do. They still show in a full `trivy image` run. To accept a specific risk on purpose, list its CVE in a `.trivyignore` file with a comment explaining why.
- **Immutable tags.** Images are tagged `<sha>-<build>`. `latest` is rejected by the library (`ImageTag.fullImage`). Every running pod can be traced to a commit, and `rollout undo` returns to an exact earlier image.
- **Audit trail.** The exact manifests applied to each cluster are archived with the build (`reports/manifest-<env>-<cluster>-<namespace>.yaml`).
- **Reviewed prod releases.** `main` deploys only after a person approves it in Jenkins. `approvers` restricts who can, and the approver's name is logged (`Approved by admin`).

## Runtime hardening

From `app/Dockerfile` and `k8s/base/deployment.yaml`:

- runs as a fixed non-root UID (10001); `runAsNonRoot: true` makes the kubelet refuse to start it as root
- `readOnlyRootFilesystem: true`, only an `emptyDir` `/tmp`; app code is owned by root, so the app cannot modify itself
- `allowPrivilegeEscalation: false`, all Linux capabilities dropped, `seccompProfile: RuntimeDefault`
- `automountServiceAccountToken: false`: the app has no Kubernetes API token
- CPU and memory requests and limits
- **NetworkPolicy:** default deny; ingress only on port 8080 from the same namespace and from a `monitoring` namespace; egress only DNS. Checked on kind: a pod in `default` cannot reach the app, a pod in `demo-dev` can.

## Jenkins hardening

- No setup wizard and no sign-up. One admin user from `.env`. Anonymous users can see nothing.
- CSRF protection is on (Jenkins default). The `/github-webhook/` endpoint is exempt by design and protected instead by the **webhook HMAC secret**: unsigned or wrongly signed deliveries are rejected with `400 No valid signature found`.
- `/prometheus/` metrics require a login.
- Jenkins and the registry listen on `127.0.0.1` only. Expose Jenkins to GitHub through an HTTPS tunnel (ngrok), never by binding to `0.0.0.0`.
- The controller runs as UID 1000, not root. Plugins and the JCasC file are baked into the image and read-only for the jenkins user. Configuration changed in the UI is overwritten at the next restart, so Git stays the source of truth.
- Fork pull requests are **not** built: untrusted code must not run with the pipeline's credentials. Only branches and PRs from the repo itself are discovered. (Configured in `seedJob.groovy`; not tested locally.)

## Known trade-offs of the local demo

These are acceptable on a laptop and **not** in a shared environment:

| Trade-off | Risk | Production alternative |
|---|---|---|
| Jenkins uses the host's Docker socket (`/var/run/docker.sock`) to build images | Access to the socket is root-equivalent on the host: a malicious Jenkinsfile can take over the machine | Ephemeral build agents (Kubernetes plugin pods) building with rootless BuildKit or Kaniko; set the controller's executors to 0 |
| Builds run on the controller (2 executors) | A build can read the controller's files, including `JENKINS_HOME` secrets | Separate agents, connected inbound or via SSH |
| *Logged-in users can do anything* authorization | Every user is an admin | Matrix or role-based authorization; SSO (GitHub OAuth, OIDC) |
| The shared library and the Job DSL seed run outside the Groovy sandbox | Whoever can push to `main` of this repo can run any code on the controller | Protect `main`: required pull request reviews, required CI checks, CODEOWNERS for `vars/`, `src/` and `jenkins/` |
| Registry over plain HTTP, no authentication | Anyone on the machine can push or pull images | A real registry (GHCR, ECR, Harbor) over TLS, with `registryCredentialsId` |
| `demo-dev` and `demo-prod` share one cluster | A cluster-level problem affects both | Separate clusters, each with its own credential, as `repos.yaml` already supports |
| Kubeconfig tokens rotated by hand every 30 days | A forgotten rotation breaks deploys; a leaked token is valid until it expires | Workload identity / OIDC federation between CI and the cloud provider, or a secrets manager with automatic rotation |

## Production checklist

- [ ] Build agents separate from the controller, ephemeral, no Docker socket; controller executors = 0
- [ ] SSO and role-based authorization in Jenkins; approvers set to a group
- [ ] `main` protected: reviews, required CI checks, CODEOWNERS for the shared library and `jenkins/`
- [ ] Separate prod cluster(s); one kubeconfig credential per cluster
- [ ] Private registry over TLS with credentials; consider image signing (cosign) and admission control that only allows signed images
- [ ] Secrets from a secrets manager (Vault, cloud KMS) instead of `.env` files
- [ ] Jenkins behind TLS (reverse proxy or ingress); the webhook endpoint the only public path
- [ ] Automatic dependency updates (Renovate or Dependabot) for base image digests, Python packages and plugins
- [ ] Back up `JENKINS_HOME` (build history). Configuration is already in Git.

## If a secret leaks

1. **Rotate it first.** Assume it is compromised the moment it reached a remote. Removing it from Git does not help on its own: clones and forks keep the history.
   - GitHub token: revoke it on GitHub, create a new one, update `.env`, then `make down && make up`.
   - Webhook secret: generate a new one, update `.env` and the GitHub webhook.
   - Kubeconfig token: delete and recreate the ServiceAccount (`kubectl -n <ns> delete serviceaccount jenkins-deployer`, then re-apply `k8s/rbac/jenkins-deployer.yaml`). This invalidates every token issued for it. Then `make rotate-tokens`.
2. Remove it from the code, and from the history if needed (`git filter-repo`).
3. Check the logs of the affected system (GitHub audit log, Kubernetes audit log) for use of the leaked secret.
