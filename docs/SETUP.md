# Setup

This guide takes you from a fresh clone to a working pipeline. A push to GitHub then builds, tests, scans and deploys the app to a local Kubernetes cluster.

- [1. Prerequisites](#1-prerequisites)
- [2. Put the code on GitHub](#2-put-the-code-on-github)
- [3. Create a GitHub token](#3-create-a-github-token)
- [4. Configure](#4-configure)
- [5. Start the cluster and Jenkins](#5-start-the-cluster-and-jenkins)
- [6. First builds](#6-first-builds)
- [7. Trigger builds on push (GitHub webhook)](#7-trigger-builds-on-push-github-webhook)
- [Add a new repository](#add-a-new-repository)
- [Add a new Kubernetes cluster](#add-a-new-kubernetes-cluster)
- [Renew the deployer tokens (every 30 days)](#renew-the-deployer-tokens-every-30-days)
- [Day-to-day commands](#day-to-day-commands)
- [Troubleshooting](#troubleshooting)

## 1. Prerequisites

| | Linux | macOS | Windows |
|---|---|---|---|
| Docker | Docker Engine with the compose plugin; add your user to the `docker` group | Docker Desktop | Docker Desktop with the WSL2 backend |
| Shell tools | `git`, `make`, `bash`, `curl` (`sudo apt install git make curl`) | `xcode-select --install` | Inside WSL2 Ubuntu: `sudo apt install git make curl` |

- **Resources:** give Docker at least 4 CPUs and 6 GB of memory. Keep about 15 GB of disk free for the Jenkins image, the kind node, the Trivy vulnerability database and the build images.
- **Ports:** `127.0.0.1:8080` (Jenkins) and `127.0.0.1:5001` (registry) must be free.
- **kind and kubectl** do not need to be installed: `make cluster` downloads pinned, checksum-verified copies to `local-setup/.bin/` if they are not on your `PATH`.
- **Intel or ARM** (Apple Silicon): every image used is multi-arch.

**Windows:** run every command in a WSL2 Ubuntu terminal, not in PowerShell or Git Bash. Clone the repo inside the Linux file system (for example `~/jenkins-k8s-cicd`), not under `/mnt/c/`. That keeps file permissions and line endings intact and is much faster. In Docker Desktop, turn on *Settings > Resources > WSL integration* for your Ubuntu distribution.

## 2. Put the code on GitHub

Jenkins builds from GitHub, so the repo needs to be there, with a `main` and a `develop` branch.

```bash
# Create an empty repo named jenkins-k8s-cicd on github.com first (no README), then:
git remote add origin https://github.com/HritikBudhwar/jenkins-k8s-cicd.git   # your fork's URL in a fork
git push -u origin main
git push origin main:develop      # develop starts as a copy of main
```

On GitHub, protect `main` (*Settings > Branches > Add rule*: require a pull request, and require the checks from the CI workflow (`lint`, `unit-tests`, `jenkins-image`, `deploy-kind`) to pass). This repo holds the shared library, which Jenkins runs with full trust (see [SECURITY.md](SECURITY.md)), so changes to `main` should always be reviewed.

## 3. Create a GitHub token

Jenkins uses a fine-grained personal access token to list branches and pull requests, and to report build results on commits.

On GitHub go to *Settings > Developer settings > Personal access tokens > Fine-grained tokens > Generate new token*:

- **Repository access:** *Only select repositories*, then pick `jenkins-k8s-cicd`
- **Repository permissions:**
  - Contents: **Read-only**
  - Metadata: **Read-only** (selected automatically)
  - Commit statuses: **Read and write** (Jenkins marks commits green or red)
  - Pull requests: **Read-only** (Jenkins builds pull requests)
- **Expiration:** pick a date and put a reminder in your calendar. When the token expires, branch scans fail with `401 Bad credentials`.

Copy the token. GitHub shows it only once.

## 4. Configure

```bash
cp .env.example .env
```

Fill in `.env`. It is git-ignored, and every value is explained in the file:

| Variable | Value |
|---|---|
| `JENKINS_ADMIN_PASSWORD` | A strong password for the Jenkins `admin` user |
| `GITHUB_USER` | Your GitHub username (preset to `HritikBudhwar`) |
| `GITHUB_TOKEN` | The token from step 3 |
| `GITHUB_WEBHOOK_SECRET` | Output of `openssl rand -hex 20`. You will paste it into GitHub in step 7. |
| `PLATFORM_REPO_URL` | `https://github.com/HritikBudhwar/jenkins-k8s-cicd.git` (preset; your fork's URL in a fork) |

**`jenkins/seed/repos.yaml`** already points at `HritikBudhwar/jenkins-k8s-cicd` (`owner: HritikBudhwar`). In a fork, change `owner` to your GitHub username, then commit and push it. The seed job reads `repos.yaml` from GitHub, not from your disk:

```bash
git commit -am "chore(seed): set repository owner" && git push origin main && git push origin main:develop
```

## 5. Start the cluster and Jenkins

```bash
make cluster    # about 1 minute
make up         # first run about 3-5 minutes: builds the Jenkins image
```

`make cluster` (that is, `local-setup/setup.sh`):

1. creates the kind cluster `cicd-demo`
2. points `localhost:5001` inside the cluster at the local registry container
3. creates the namespaces `demo-dev` and `demo-prod`, which stand in for two clusters
4. applies the least-privilege `jenkins-deployer` ServiceAccount and Role in each namespace
5. writes one kubeconfig per namespace to `local-setup/.secrets/`, which is git-ignored
6. checks the permissions. You should see `yes yes no no no`: the deployer can update Deployments and port-forward, and cannot read Secrets, delete objects or touch the other namespace.

`make up` builds the Jenkins image (pinned plugins and tools) and starts Jenkins and the registry. At startup, Configuration-as-Code (`jenkins/casc/jenkins.yaml`) creates the admin user, the credentials (GitHub token, webhook secret, both kubeconfigs), the shared library and the seed job. No setup wizard appears.

Open <http://localhost:8080> and log in as `admin` with your password.

## 6. First builds

1. **seed-job** runs by itself within about 5 minutes (it polls this repo). Click *Build Now* to skip the wait. It reads `jenkins/seed/repos.yaml` and creates the **demo-app** Multibranch Pipeline.
2. demo-app scans GitHub and builds `main` and `develop`:
   - `develop` runs every stage and deploys to `demo-dev`.
   - `main` runs the same checks, then stops at **Manual Approval**. Open the build and click *Deploy to prod* (or *Abort*). The build waits 30 minutes and is aborted if nobody answers. It does not hold an executor while it waits.
3. Check the result:

   ```bash
   make pods
   kubectl --context kind-cicd-demo -n demo-dev port-forward svc/demo-app 8081:80
   curl localhost:8081/      # {"app":"demo-app","version":"<sha>-<build>",...}
   ```

The end of every build log has a summary: app, branch, commit, image, where it was deployed, and, on failure, the stage and the reason. The same summary is in the build description in the build list. Test results are on the build's *Test Result* page. The Trivy report, gitleaks report and the exact manifests applied are under *Build Artifacts*.

## 7. Trigger builds on push (GitHub webhook)

Without a webhook, Jenkins still notices new commits by polling (every ~15 minutes per branch) and a daily branch scan. With a webhook, GitHub tells Jenkins about each push and pull request right away.

GitHub cannot reach `localhost`, so you need a public HTTPS tunnel to port 8080. [ngrok](https://ngrok.com/download) works on all three platforms. Create a free account, add its authtoken (`ngrok config add-authtoken <token>`), then:

```bash
ngrok http 8080
# Forwarding  https://<random>.ngrok-free.app -> http://localhost:8080
```

1. Put the ngrok URL in `.env` as `JENKINS_URL=https://<random>.ngrok-free.app/`, then `make down && make up`. Jenkins uses this URL in build links and commit statuses.
2. On GitHub, open the repo's *Settings > Webhooks > Add webhook*:
   - **Payload URL:** `https://<random>.ngrok-free.app/github-webhook/` (keep the trailing slash)
   - **Content type:** `application/json`
   - **Secret:** the `GITHUB_WEBHOOK_SECRET` value from `.env`
   - **Events:** *Let me select individual events*: **Pushes** and **Pull requests**
3. Save. In the webhook's *Recent Deliveries* tab, the `ping` event should show a 200 response.
4. Push a commit to `develop`. The build starts within seconds.

Jenkins rejects deliveries that are not signed with the shared secret. On ngrok's free plan the URL changes every time you restart ngrok. When it does, update the webhook's Payload URL and `JENKINS_URL`.

## Add a new repository

Any repo can use the pipeline. It needs three things:

1. **A Jenkinsfile** that calls the library:

   ```groovy
   @Library('cicd-shared-lib') _
   standardPipeline(appName: 'orders-service', registry: 'localhost:5001')
   ```

2. **A Dockerfile with a `test` stage** that runs the tests and writes a JUnit report (see `app/Dockerfile`), and a final stage that is the runtime image. The paths are configurable: `dockerfile`, `buildContext`, `testTarget`, `testReportPath`.
3. **Kustomize overlays** at `k8s/overlays/dev` and `k8s/overlays/prod`, with a Deployment and Service named after `appName`, and an image placeholder named `appName`. Copy this repo's `k8s/` folder and rename.

Then add an entry to `jenkins/seed/repos.yaml`. There is a commented example for `orders-service` at the end of the file. Push it. The seed job creates the job within about 5 minutes.

Every setting `standardPipeline` accepts, with its default, is listed in `src/org/devops/PipelineConfig.groovy`: branches, approvers, timeouts, Trivy severities, registry credentials, agent label and more. Settings can go in the repo's `pipeline:` section in `repos.yaml` (preferred: one reviewed place) or in its Jenkinsfile, which wins. Typos are caught. An unknown key fails the build within seconds and lists the valid keys.

## Add a new Kubernetes cluster

A deploy target is a cluster + namespace + the Jenkins credential holding its kubeconfig. Adding one needs no code change.

**1. On the cluster, as a cluster admin**, create the namespace and the deployer identity:

```bash
kubectl create namespace orders
kubectl apply -n orders -f k8s/rbac/jenkins-deployer.yaml
```

**2. Generate a kubeconfig for Jenkins.** The third argument is the API server URL as Jenkins sees it, which can differ from the URL in your own kubeconfig:

```bash
k8s/rbac/generate-kubeconfig.sh orders local-setup/.secrets/kubeconfig-orders https://<api-server>:6443
```

The token in it is short-lived (30 days by default, 4th argument) and bound to the ServiceAccount.

**3. Give it to Jenkins as a credential, through Configuration-as-Code.** Do not add it in the Jenkins UI. JCasC manages the credential store, so a credential added by hand is **deleted at the next Jenkins restart**.

In `local-setup/docker-compose.yml`, add the file under `secrets:` in two places:

```yaml
services:
  jenkins:
    secrets:
      - kubeconfig-dev
      - kubeconfig-prod
      - kubeconfig-orders          # new
secrets:
  kubeconfig-orders:               # new
    file: ./.secrets/kubeconfig-orders
```

In `jenkins/casc/jenkins.yaml`, add a credential next to `kubeconfig-prod`:

```yaml
          - file:
              scope: GLOBAL
              id: kubeconfig-orders
              description: "kubeconfig: jenkins-deployer in orders"
              fileName: kubeconfig-orders
              secretBytes: "${readFileBase64:/run/secrets/kubeconfig-orders}"
```

Then run `make up` (it rebuilds the image, which contains `jenkins.yaml`). If you see `kubeconfig-orders` under *Manage Jenkins > Credentials*, it worked.

**4. Add the target** to the repo's `deployTargets` in `jenkins/seed/repos.yaml`, and push:

```yaml
        - environment: prod
          cluster: prod-eu
          namespace: orders
          credentialsId: kubeconfig-orders
```

Targets with the same `environment` are deployed one after another, each with its own rollout check, smoke test and rollback. The build log and summary list them all (`prod: kind-cicd-demo/demo-prod, prod-eu/orders`).

If a build runs before the credential exists, it fails in the deploy stage, before touching any cluster, with: `Kubeconfig credential 'kubeconfig-orders' ... was not found`.

**Network:** Jenkins must reach the API server. For the local kind cluster that works because Jenkins joins kind's Docker network and uses `https://cicd-demo-control-plane:6443`. For a cloud cluster, use its public or VPN endpoint.

## Renew the deployer tokens (every 30 days)

The kubeconfigs that `make cluster` generates contain tokens that are valid for 30 days. After that, deploys fail with `error: You must be logged in to the server (Unauthorized)`. To renew them:

```bash
make rotate-tokens     # regenerates the kubeconfigs and restarts Jenkins, which reloads them
```

For other clusters, re-run `k8s/rbac/generate-kubeconfig.sh` for that namespace, then `docker restart jenkins`.

## Day-to-day commands

| Command | What it does |
|---|---|
| `make` | List all targets |
| `make pods` | Pods and images in `demo-dev` and `demo-prod` |
| `make logs` | Follow the Jenkins controller log |
| `make down` / `make up` | Stop and start Jenkins and the registry. Jenkins data is kept. |
| `make deploy-dev` | Build and deploy to `demo-dev` from your laptop, without Jenkins. Useful to check the cluster side alone. |
| `make lint` / `make test` | Static checks / unit tests (see [TESTING.md](TESTING.md)) |
| `make clean` | Delete everything: containers, Jenkins data, registry images, the kind cluster, kubeconfigs |

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `make up`: `Missing kubeconfigs` | Run `make cluster` first. Jenkins joins the network that kind creates. |
| `make up`: `network kind declared as external, but could not be found` | The kind cluster is gone (for example after `kind delete cluster`). Run `make cluster`. |
| `Jenkins did not become healthy` | `make logs`. A line with `ConfigurationAsCode` and `SEVERE` points to an error in `jenkins/casc/jenkins.yaml` or a missing value in `.env`. |
| seed-job fails: `source.owner is still 'replace-me'` | Set your GitHub username in `jenkins/seed/repos.yaml` and push it (step 4). |
| Branch indexing: `401 Bad credentials` | The GitHub token is wrong or expired. Update `GITHUB_TOKEN` in `.env`, then `make down && make up`. |
| No build after a push | Check the webhook's *Recent Deliveries* on GitHub. `400 No valid signature found` means the webhook Secret differs from `GITHUB_WEBHOOK_SECRET`. A timeout means ngrok is not running or the URL changed. Polling still picks the commit up within ~15 minutes. |
| `permission denied ... /var/run/docker.sock` in a build | `make up` detects the socket's group. If you started Jenkins with plain `docker compose`, use `make up` instead. |
| Deploy: `Unauthorized` | Deployer token expired: `make rotate-tokens`. |
| Deploy: `forbidden: ... cannot <verb> resource "<kind>"` | Your manifests contain an object type the deployer Role does not allow. Add it to `k8s/rbac/jenkins-deployer.yaml` and re-apply it. |
| Pods stuck in `ImagePullBackOff` | The registry container is not running (`docker ps \| grep kind-registry`), or the cluster was recreated without `make cluster` (which sets up the registry mapping). |
| `bad interpreter: /bin/bash^M` (Windows) | The files were checked out with Windows line endings. Clone inside WSL, not under `/mnt/c/`. `.gitattributes` forces LF for new clones. |
| Disk full | Builds remove their images, but Docker's build cache grows: `docker builder prune`. |
| HPA shows `<unknown>` CPU and `FailedGetResourceMetric` events | kind has no metrics-server, so the HPA cannot read CPU and keeps the replica count from the overlay. Install metrics-server to see it scale. The pipeline does not depend on it. |
