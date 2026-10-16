# jenkins-k8s-cicd

A CI/CD pipeline built with **Git, Jenkins and Kubernetes** that runs on a laptop.

A push to GitHub triggers Jenkins. Jenkins scans the code for secrets, lints it, runs the unit tests, builds a container image, scans the image for vulnerabilities, pushes it and deploys it to Kubernetes. If the new version does not become healthy, Jenkins **rolls it back automatically**. Production releases wait for a person to approve them.

The Jenkins controller, its plugins and credentials (Configuration-as-Code), the jobs (Job DSL seed job), the pipeline (a Groovy shared library), the Kubernetes manifests (Kustomize) and the RBAC are all files in this repo.

```
 git push ──► GitHub ──webhook──► Jenkins (standardPipeline from the shared library)
                                    │
   Checkout ► Secret Scan ► Lint ► Unit Test ► Build Image ► Security Scan
                                                                   │
           ┌───────────────────────────────────────────────────────┤
  develop  │  Push ► Deploy to Dev ► Smoke Test                    │  any other branch / PR:
  main     │  Push ► Manual Approval ► Deploy to Prod ► Smoke Test │  build + test only
           └──────────────┬────────────────────────────────────────┘
                          ▼
           kind cluster:  demo-dev  /  demo-prod   (rollout status ► rollout undo on failure)
```

## What each branch does

| Branch | Built and tested | Image pushed | Deployed to | Approval |
|---|---|---|---|---|
| `develop` | yes | yes | `demo-dev` | no |
| `main` | yes | yes | `demo-prod` | yes, in Jenkins |
| `feature/*`, `PR-*` | yes | no | nothing | no |

Pull request builds (`PR-*`) are configured but were not tested locally.

Images are tagged `<commit-sha>-<build-number>` (for example `3f2a9c1-17`). The pipeline never uses `latest`, so every deploy can be traced to a commit and rolled back.

## Quick start

You need Docker, `make`, `git` and `bash` (on Windows: a WSL2 terminal). [docs/SETUP.md](docs/SETUP.md) covers each step in detail, plus GitHub and the webhook.

```bash
cp .env.example .env          # set JENKINS_ADMIN_PASSWORD, GITHUB_TOKEN and GITHUB_WEBHOOK_SECRET
# in a fork: also set your GitHub user in .env and jenkins/seed/repos.yaml (owner:)
make cluster                  # kind cluster + namespaces + RBAC + kubeconfigs for Jenkins
make up                       # build and start Jenkins and the local registry
# open http://localhost:8080 and log in: the seed job creates the demo-app pipeline
make pods                     # see what is running in demo-dev and demo-prod
```

Run `make` to list every target. `make clean` deletes everything the demo created.

## Repository layout

| Path | What it is |
|---|---|
| `app/` | Sample Flask service with `/health`, `/ready` and `/metrics` endpoints, and a multi-stage Dockerfile (its `test` stage runs pytest) |
| `k8s/base`, `k8s/overlays/{dev,prod}` | Kustomize manifests: Deployment, Service, HPA, PodDisruptionBudget, NetworkPolicy |
| `k8s/rbac/` | Least-privilege `jenkins-deployer` ServiceAccount and a script that generates its kubeconfig |
| `vars/`, `src/`, `resources/` | Jenkins shared library: `standardPipeline` and one step per stage |
| `Jenkinsfile` | How a repo uses the library (about 10 lines) |
| `Jenkinsfile.standalone` | A simplified version of the same pipeline in one file, without the library, to read top to bottom (lint-checked, not run as a job) |
| `jenkins/` | Controller image: pinned plugins, CLI tools, JCasC config, the seed job and `repos.yaml` |
| `local-setup/` | kind cluster config, `setup.sh` and docker compose (Jenkins + registry) |
| `scripts/` | `validate.sh` (all static checks) and `break-deploy.sh` (rollback demo) |
| `test/` | Unit tests for the shared library (JenkinsPipelineUnit + Gradle) |
| `.github/workflows/ci.yml` | GitHub Actions: lint, unit tests, Jenkins image build, and a deploy to kind |

## Adding a repo or a cluster

Edit **`jenkins/seed/repos.yaml`** and push. The seed job polls the repo and creates or updates the Jenkins jobs within about 5 minutes. Each deploy target names a cluster, a namespace and a kubeconfig credential. Several targets with the same environment deploy one after another, for example to two prod regions. See [docs/SETUP.md](docs/SETUP.md#add-a-new-repository).

## Documentation

- [docs/SETUP.md](docs/SETUP.md): install, GitHub token and webhook, onboarding repos and clusters, token rotation, troubleshooting
- [docs/TESTING.md](docs/TESTING.md): static checks, unit tests, CI, and step-by-step failure scenarios (failing test, vulnerable image, broken deploy with rollback, missing credential)
- [docs/SECURITY.md](docs/SECURITY.md): how secrets, RBAC and images are protected, and what to change before using this in production
- [docs/MONITORING.md](docs/MONITORING.md): build results and logs, Prometheus metrics for Jenkins and the app, example alerts

## Checks

```bash
make lint    # hadolint, kubeconform, Groovy syntax, shellcheck, actionlint, gitleaks
make test    # app unit tests + shared library unit tests
```

GitHub Actions runs both on every push to `main` or `develop` and on every pull request, then deploys the app to a kind cluster and smoke-tests it.

## What was tested, and what was not

Tested on Ubuntu Linux with kind and the Jenkins from this repo, pointed at a local Git server instead of GitHub:
the full pipeline on `develop`, `main` (approve and abort) and a feature branch, plus the failure scenarios in [docs/TESTING.md](docs/TESTING.md):
failing unit test, vulnerable dependency, broken deploy rolled back, failed smoke test rolled back, missing credential and a config typo.
The GitHub Actions workflow runs on GitHub and passes.

Not tested end to end: the GitHub webhook through ngrok (only its signature check), a real GitHub token and commit statuses, pull request builds, registry login (the local registry has none), macOS, Windows and ARM machines.

## License

MIT, see [LICENSE](LICENSE).
