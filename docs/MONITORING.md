# Monitoring

Three things to watch: **the pipeline** (are builds passing, are deploys going out), **the app** (is it serving) and **the cluster** (are rollouts healthy).

- [Pipeline: what every build reports](#pipeline-what-every-build-reports)
- [Jenkins metrics in Prometheus](#jenkins-metrics-in-prometheus)
- [Alerts](#alerts)
- [App metrics](#app-metrics)
- [Cluster and deployments](#cluster-and-deployments)
- [Logs](#logs)

## Pipeline: what every build reports

No setup needed:

| Where | What you see |
|---|---|
| End of the console log | A summary: app, branch, commit, image, where it was deployed, duration and, on failure, **the stage and the reason** (for example `Reason: Security scan failed: Trivy found HIGH,CRITICAL vulnerabilities...`) |
| Build list (build description) | `b977fea-12 -> dev: kind-cicd-demo/demo-dev`, `FAILURE in Security Scan`, or `(build + test only)` |
| *Test Result* page | Passed and failed tests, with history across builds |
| *Build Artifacts* | `junit.xml`, `trivy-report.txt`, `gitleaks.json`, and the exact manifests applied to each cluster |
| GitHub | A green or red status on the commit and pull request, posted by the GitHub Branch Source plugin (needs the *Commit statuses* token permission). Not tested locally. |

Jenkins keeps the last 20 builds per branch and the artifacts of the last 10.

**Chat or email notifications** are not included, because the local demo has no chat server to test them against. To add them, install a plugin (for example `slack`) and call it in `vars/notify.groovy`. That function already receives the status and has every value of the summary.

## Jenkins metrics in Prometheus

The Prometheus plugin exposes metrics at `http://localhost:8080/prometheus/`. The endpoint **requires a login**, because it reveals job names and results. Prometheus authenticates with a Jenkins API token.

**1. Create an API token.** In Jenkins: your user name (top right) > *Security* > *API Token* > *Add new Token*, name it `prometheus`, *Generate*. Save it to a file:

```bash
mkdir -p ~/prometheus && cd ~/prometheus
printf '%s' '<the token>' > jenkins-token && chmod 644 jenkins-token
```

**2. Prometheus config** (`~/prometheus/prometheus.yml`):

```yaml
global:
  scrape_interval: 30s
scrape_configs:
  - job_name: jenkins
    metrics_path: /prometheus/
    basic_auth:
      username: admin                                # the user who owns the token
      password_file: /etc/prometheus/jenkins-token
    static_configs:
      - targets: ["jenkins:8080"]                    # container name on the "kind" network
```

**3. Run Prometheus** on the same Docker network as Jenkins:

```bash
docker run -d --name prometheus --network kind -p 127.0.0.1:9090:9090 \
  -v ~/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro \
  -v ~/prometheus/jenkins-token:/etc/prometheus/jenkins-token:ro \
  prom/prometheus:v3.13.4
```

Open <http://localhost:9090/targets>: the `jenkins` target should be **UP**. The plugin refreshes job metrics every 2 minutes, so after a Jenkins restart they take up to 2 minutes to appear.

**Useful metrics.** Jobs are labeled `jenkins_job="demo-app/develop"`:

| Metric | Meaning |
|---|---|
| `up{job="jenkins"}` | 1 if Prometheus can scrape Jenkins |
| `default_jenkins_builds_last_build_result_ordinal` | Result of each job's last build: 0 success, 1 unstable, 2 failure, 3 not built, 4 aborted |
| `default_jenkins_builds_last_build_duration_milliseconds` | Duration of each job's last build |
| `default_jenkins_builds_success_build_count_total`, `..._failed_build_count_total` | Build counters per job (since Jenkins started) |
| `default_jenkins_builds_duration_milliseconds_summary_sum` / `_count` | Total build time and number of builds; divide for the average |
| `jenkins_queue_size_value` | Builds waiting for an executor |
| `default_jenkins_executors_busy`, `default_jenkins_executors_available` | Executor usage |

Example queries:

```promql
# Branches whose last build failed (nothing new is being deployed from them)
default_jenkins_builds_last_build_result_ordinal{jenkins_job=~".+/(main|develop)"} == 2

# Last build time per deploy branch, in seconds
default_jenkins_builds_last_build_duration_milliseconds{jenkins_job=~".+/(main|develop)"} / 1000
```

## Alerts

A starting set of Prometheus alerting rules. They pass `promtool check rules`. Save them as `rules.yml`, mount the file into the container, and add `rule_files: ["/etc/prometheus/rules.yml"]` to `prometheus.yml`. Sending alerts out (email, chat) needs an Alertmanager, which is not part of this demo.

```yaml
groups:
  - name: jenkins
    rules:
      - alert: JenkinsDown
        expr: up{job="jenkins"} == 0
        for: 5m
        annotations:
          summary: "Prometheus cannot scrape Jenkins (down, or the API token was revoked)"
      - alert: DeployBranchFailing
        # 2 = FAILURE (0 SUCCESS, 1 UNSTABLE, 3 NOT_BUILT, 4 ABORTED)
        expr: default_jenkins_builds_last_build_result_ordinal{jenkins_job=~".+/(main|develop)"} == 2
        for: 10m
        annotations:
          summary: "Last build of {{ $labels.jenkins_job }} failed: nothing new is being deployed from it"
      - alert: JenkinsQueueBacklog
        expr: jenkins_queue_size_value > 5
        for: 15m
        annotations:
          summary: "{{ $value }} builds are waiting for an executor"
      - alert: PipelineSlow
        expr: default_jenkins_builds_last_build_duration_milliseconds{jenkins_job=~".+/(main|develop)"} > 30 * 60 * 1000
        annotations:
          summary: "{{ $labels.jenkins_job }} took more than 30 minutes"
```

## App metrics

The app serves Prometheus metrics at `/metrics`:

- `app_requests_total{endpoint="..."}`: requests served, per endpoint
- the standard Python process metrics (`process_cpu_seconds_total`, `process_resident_memory_bytes`, ...)

The smoke test checks `/metrics` after every deploy. To look at it yourself:

```bash
kubectl --context kind-cicd-demo -n demo-dev port-forward svc/demo-app 8081:80
curl -s localhost:8081/metrics | grep app_requests_total
```

**Scraping from inside the cluster.** The app's NetworkPolicy allows ingress on port 8080 from a namespace named `monitoring`, so a Prometheus there can scrape the pods. With the [kube-prometheus-stack](https://github.com/prometheus-community/helm-charts/tree/main/charts/kube-prometheus-stack) Helm chart (not installed by this demo, and this example is not tested), a `PodMonitor` like the one below selects the pods. By default the chart only picks up PodMonitors labeled with its Helm release name:

```yaml
apiVersion: monitoring.coreos.com/v1
kind: PodMonitor
metadata:
  name: demo-app
  namespace: monitoring
  labels:
    release: kube-prometheus-stack      # your Helm release name
spec:
  namespaceSelector:
    matchNames: [demo-dev, demo-prod]
  selector:
    matchLabels:
      app: demo-app
  podMetricsEndpoints:
    - port: http
      path: /metrics
```

Each pod counts its own requests (one gunicorn worker per pod), so sum across pods: `sum by (namespace, endpoint) (rate(app_requests_total[5m]))`.

## Cluster and deployments

```bash
make pods                                                                    # pods and images in both namespaces
kubectl --context kind-cicd-demo -n demo-prod rollout history deployment/demo-app   # revisions (5 are kept for rollback)
kubectl --context kind-cicd-demo -n demo-prod get events --sort-by=.lastTimestamp
```

The pipeline already watches every rollout. `kubectl rollout status` waits up to `rolloutTimeout` for the new pods to pass their readiness probe. When a deploy fails, it prints the pods, the Deployment description and the latest events into the build log before rolling back.

The HorizontalPodAutoscaler needs metrics-server, which kind does not include. Without it, `kubectl get hpa` shows `<unknown>` CPU, and the replica count stays at the overlay's value (dev 1, prod 3).

## Logs

| What | Command |
|---|---|
| App logs (gunicorn access log on stdout) | `kubectl --context kind-cicd-demo -n demo-dev logs -l app=demo-app --tail=50` |
| Jenkins controller | `make logs` |
| A build | the build's *Console Output*, with a timestamp on every line |
| Jenkins health | `docker inspect -f '{{.State.Health.Status}}' jenkins` (docker compose checks `/login` every 15s) |
