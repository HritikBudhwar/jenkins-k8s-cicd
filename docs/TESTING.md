# Testing

The project is tested at four levels. The first three run in seconds to minutes without Jenkins. The last one exercises the real pipeline.

| Level | Command | What it checks |
|---|---|---|
| Static checks | `make lint` | Dockerfiles, Kubernetes manifests, Groovy syntax, shell scripts, GitHub workflows, committed secrets |
| Unit tests | `make test` | The Flask app (pytest) and the shared library logic (JenkinsPipelineUnit) |
| CI | GitHub Actions | Both of the above, plus a Jenkins image build and a real deploy to a kind cluster |
| End to end | push to Git | The full Jenkins pipeline, including the [failure scenarios](#end-to-end-scenarios) below |

All of them only need Docker: every tool runs in a container at a pinned version.

## Static checks: `make lint`

`scripts/validate.sh` runs six checks. It always runs all of them and lists the failures at the end:

| Check | Tool | Files |
|---|---|---|
| Dockerfiles | hadolint | `app/Dockerfile`, `jenkins/Dockerfile` (rules in `.hadolint.yaml`) |
| Kubernetes manifests | kustomize + kubeconform `-strict` | every overlay in `k8s/overlays/` and `k8s/rbac/jenkins-deployer.yaml`. A misspelled field fails. |
| Groovy syntax | Groovy 2.4 (the version inside Jenkins) | Jenkinsfiles, `vars/`, `src/`, the seed script, the tests |
| Shell scripts | shellcheck | every `*.sh` |
| GitHub workflows | actionlint | `.github/workflows/` |
| Secrets | gitleaks | the files you would commit (tracked + new, not git-ignored) **and every commit in the history** |

The versions of hadolint, kustomize, kubeconform and gitleaks are read from `jenkins/Dockerfile`, so `make lint` on your laptop, in CI and in the Jenkins Lint stage give the same answer.

## Unit tests: `make test`

**App** (`make test-app`): builds the `test` stage of `app/Dockerfile` and runs pytest in it, the same way the pipeline does. The tests are in `app/tests/test_app.py` and cover every endpoint, and that `/` reports the version baked into the image.

**Shared library** (`make test-lib`): runs `test/` with Gradle (wrapper included, Java runs in a container) and [JenkinsPipelineUnit](https://github.com/jenkinsci/JenkinsPipelineUnit), which runs pipeline code outside Jenkins with steps like `sh` replaced by mocks.

| Test class | Covers |
|---|---|
| `PipelineConfigTest` | defaults; every validation message (missing/unknown keys, bad names, URLs as registry, bad timeouts and severities); branch to environment mapping; several clusters per environment |
| `ImageTagTest` | `<sha>-<build>` tags; `latest` and non-SHA values are rejected |
| `DeployToK8sTest` | the real `vars/deployToK8s.groovy`: a successful deploy; **a failed rollout is rolled back after diagnostics, and the build fails with "AUTOMATICALLY ROLLED BACK"**; a failed rollback; a failed `kubectl apply` (no rollback attempted); a missing kubeconfig credential (fails before touching the cluster) |

These tests do catch real bugs. If you remove the `rollout undo` call from `deployToK8s.groovy`, three of them fail.

To run a single test class: `cd test && ./gradlew test --tests DeployToK8sTest` (needs a local JDK 17; `make test-lib` does not).

## CI: GitHub Actions

`.github/workflows/ci.yml` runs on every push to `main` and `develop` and on every pull request:

| Job | Steps |
|---|---|
| `lint` | `make lint`, with the full Git history so gitleaks scans every commit |
| `unit-tests` | `make test-app`, `make test-lib` |
| `jenkins-image` | `docker build jenkins`: the pinned plugins must resolve and every CLI tool in the image must run |
| `deploy-kind` | `make cluster`, starts the registry, `make deploy-dev`, then the same smoke test Jenkins runs (`resources/org/devops/smoke-test.sh`) |

This workflow tests the platform code in this repo. Jenkins builds and deploys the app. Make the CI checks required on `main` (branch protection) so the shared library cannot change without them passing.

## End-to-end scenarios

These run the real pipeline in your local Jenkins (see [SETUP.md](SETUP.md)). Each scenario is a small change you push to `develop`. Each was run on the local demo, and the messages shown come from those runs.

Useful while you test:

```bash
make pods                                                         # what is running where
kubectl --context kind-cicd-demo -n demo-dev get pods -l app=demo-app -w   # watch a rollout live
```

To undo a scenario's change afterwards: `git revert HEAD && git push origin develop`.

### 1. Happy path: `develop` deploys to dev

Push any change to `develop` (for example, edit a comment in `app/app.py`).

**Expected:** every stage is green. The build summary ends with:

```
==================== PIPELINE SUCCESS ====================
Deployed  : dev: kind-cicd-demo/demo-dev
```

`make pods` shows the new image tag (`<sha>-<build>`) in `demo-dev`. The smoke test log shows `OK /health`, `OK /ready`, `OK / reports version <tag>` and `OK /metrics`.

### 2. Release: `main` waits for approval, then deploys to prod

Merge `develop` into `main` (or push to `main`).

**Expected:** the build stops at **Manual Approval** with *Deploy &lt;image&gt; to prod (kind-cicd-demo/demo-prod)?*. While it waits, it holds no executor, so other builds keep running.

- Click **Deploy to prod**: the log shows `Approved by admin`, then the deploy and smoke test run on `demo-prod` (3 replicas).
- Click **Abort** instead: the build ends `ABORTED` and nothing is deployed. If nobody answers within 30 minutes (`approvalTimeoutMinutes`), the same happens.

### 3. Feature branch: build and test only

```bash
git checkout -b feature/demo && git push -u origin feature/demo
```

**Expected:** Jenkins discovers the branch. Checkout to Security Scan run, then `Push Image` and every deploy stage are *skipped due to when conditional*. The log says `Deploys to: nothing (build + test only for this branch)`. Pull requests (`PR-*`) behave the same way.

### 4. A failing unit test stops the build

In `app/app.py`, change `jsonify(app="demo-app"` to `jsonify(app="demo-ap"`. Push to `develop`.

**Expected:** the build fails in **Unit Test**. The *Test Result* page shows `1 failed, 4 passed` with the failing test `test_index_reports_version_and_commit`. Every later stage is skipped, so no image is pushed and nothing is deployed.

```
Stage     : Unit Test
Reason    : Unit tests failed (exit code 1). Failing tests are listed on this build's 'Test Result' page and in the pytest output above. Nothing was pushed or deployed.
```

### 5. A vulnerable dependency blocks the image

Add a known-vulnerable package to `app/requirements.txt`:

```
urllib3==1.24.1
```

Push to `develop`.

**Expected:** unit tests pass, the image is built, and **Security Scan** fails. Trivy lists the HIGH CVEs (for example `CVE-2019-11324` in urllib3) with the fixed version. The image is **not pushed**.

```
Stage     : Security Scan
Reason    : Security scan failed: Trivy found HIGH,CRITICAL vulnerabilities with a fix available in localhost:5001/demo-app:<tag>. The image was NOT pushed or deployed. ...
```

The full report is archived with the build as `reports/trivy-report.txt`.

### 6. A broken deploy is rolled back automatically

```bash
scripts/break-deploy.sh on     # readiness probe now points to a path that returns 404
git commit -am "test: break readiness probe" && git push origin develop
```

Watch the pods while it runs. The new pod starts but never becomes Ready. The old pod keeps serving the whole time, because the rollout never removes an old pod before a new one is Ready (`maxUnavailable: 0`).

**Expected:** after `rolloutTimeout` (120s), Jenkins prints diagnostics (pods, Deployment, events: `Readiness probe failed: HTTP probe failed with statuscode: 404`), runs `kubectl rollout undo`, waits for the old revision, and fails the build:

```
Stage     : Deploy to Dev
Reason    : Deployment of localhost:5001/demo-app:<tag> to kind-cicd-demo/demo-dev did not become ready within 120s. It was AUTOMATICALLY ROLLED BACK to the previous revision, which is serving traffic again. ...
```

`make pods` shows `demo-dev` still on the previous image. Restore the probe with `scripts/break-deploy.sh off`, then commit and push.

`kubectl rollout undo` prints a warning that the `last-applied-configuration` annotation is not updated. That is harmless here: the next deploy applies the complete manifests from Git again.

**Variant: healthy pods, wrong behavior.** Probes only prove the pods are up. The smoke test also checks that the service answers with the version that was just built. To see it catch a bad release, make the image report a wrong version. In `app/Dockerfile`, change `APP_VERSION=${APP_VERSION} \` to `APP_VERSION=not-${APP_VERSION} \`, then push to `develop`. The rollout succeeds, then:

```
SMOKE TEST FAILED: expected version 5d8f913-15, the service answered with: {..."version":"not-5d8f913-15"}
Rolling back deployment/demo-app in demo-dev to the previous revision...
Deployed  : dev: kind-cicd-demo/demo-dev (ROLLED BACK on kind-cicd-demo/demo-dev)
Stage     : Smoke Test: Dev
Reason    : Smoke test failed on kind-cicd-demo/demo-dev: localhost:5001/demo-app:5d8f913-15 did not answer correctly (see output above). It was automatically rolled back to the previous revision.
```

### 7. A missing cluster credential

In `jenkins/seed/repos.yaml`, change the dev target's `credentialsId: kubeconfig-dev` to `kubeconfig-staging`, a credential that does not exist. Push it to **`main`**: the seed job reads `repos.yaml` from `main`. Run *seed-job* (or wait up to 5 minutes), then run the `develop` build.

**Expected:** the build passes every check, then fails at the start of **Deploy to Dev**, before running any `kubectl` command:

```
Stage     : Deploy to Dev
Reason    : Kubeconfig credential 'kubeconfig-staging' for kind-cicd-demo/demo-dev was not found or is not a 'Secret file' credential (...). Add it to jenkins/casc/jenkins.yaml (...): see docs/SETUP.md 'Add a new Kubernetes cluster'.
```

Revert the change on `main` and run the seed job again.

### 8. A configuration typo fails fast

In the `Jenkinsfile`, change `registry:` to `regsitry:`. Push to `develop`.

**Expected:** the build fails within seconds, before any agent is used:

```
standardPipeline: invalid configuration. Fix these in the Jenkinsfile or jenkins/seed/repos.yaml:
 - Unknown key 'regsitry'. Valid keys: agentLabel, appName, approvalTimeoutMinutes, ...
 - Missing required key 'registry' (for example 'localhost:5001').
```
