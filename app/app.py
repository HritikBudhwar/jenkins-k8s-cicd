"""demo-app: a tiny Flask service used to demonstrate the CI/CD pipeline.

Endpoints:
  /         JSON with app version + Git commit, so every deploy is visibly different
  /health   liveness probe: "the process is alive"
  /ready    readiness probe: "I can take traffic"
  /metrics  Prometheus metrics (request counter + Python runtime metrics)
"""
import os  # read configuration from environment variables

from flask import Flask, jsonify  # Flask = web framework, jsonify = build a JSON response
from prometheus_client import CONTENT_TYPE_LATEST, Counter, generate_latest  # metrics library

app = Flask(__name__)  # create the web application object; gunicorn imports this as "app:app"

APP_VERSION = os.getenv("APP_VERSION", "dev")  # image tag, baked into the image at build time
GIT_COMMIT = os.getenv("GIT_COMMIT", "unknown")  # full commit SHA, baked in at build time
APP_ENV = os.getenv("APP_ENV", "local")  # "dev" or "prod", comes from the Kubernetes ConfigMap

# A counter metric with one label, so Prometheus can count requests per endpoint.
REQUESTS = Counter("app_requests_total", "Total HTTP requests served", ["endpoint"])


@app.route("/")  # register the function below for HTTP GET /
def index():
    REQUESTS.labels(endpoint="/").inc()  # add 1 to the counter for "/"
    return jsonify(app="demo-app", version=APP_VERSION, commit=GIT_COMMIT, environment=APP_ENV)


@app.route("/health")  # liveness: Kubernetes restarts the container if this stops answering
def health():
    REQUESTS.labels(endpoint="/health").inc()  # count health checks too
    return jsonify(status="ok")  # 200 OK; nothing to check because the app has no dependencies


@app.route("/ready")  # readiness: Kubernetes only sends traffic to pods that answer 200 here
def ready():
    REQUESTS.labels(endpoint="/ready").inc()  # count readiness checks
    return jsonify(status="ready")  # a real app would check its database/cache here


@app.route("/metrics")  # Prometheus scrapes this URL
def metrics():
    return generate_latest(), 200, {"Content-Type": CONTENT_TYPE_LATEST}  # body, status, headers


if __name__ == "__main__":  # only true for "python app.py"; in the container gunicorn runs the app
    app.run(host="0.0.0.0", port=int(os.getenv("PORT", "8080")))  # dev server on all interfaces
