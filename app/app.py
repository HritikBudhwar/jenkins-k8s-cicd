"""demo-app: a small Flask service for the CI/CD pipeline to build and deploy.

/ returns the version and commit baked into the image, so every deploy is
visibly different and the smoke test can check the new pods are serving.
"""
import os

from flask import Flask, jsonify
from prometheus_client import CONTENT_TYPE_LATEST, Counter, generate_latest

app = Flask(__name__)

# Set at build time (--build-arg) and by the overlay's ConfigMap.
APP_VERSION = os.getenv("APP_VERSION", "dev")
GIT_COMMIT = os.getenv("GIT_COMMIT", "unknown")
APP_ENV = os.getenv("APP_ENV", "local")

REQUESTS = Counter("app_requests_total", "Total HTTP requests served", ["endpoint"])


@app.route("/")
def index():
    REQUESTS.labels(endpoint="/").inc()
    return jsonify(app="demo-app", version=APP_VERSION, commit=GIT_COMMIT, environment=APP_ENV)


@app.route("/health")
def health():
    REQUESTS.labels(endpoint="/health").inc()
    return jsonify(status="ok")


@app.route("/ready")
def ready():
    REQUESTS.labels(endpoint="/ready").inc()
    # Nothing to check: the app has no database or other dependency.
    return jsonify(status="ready")


@app.route("/metrics")
def metrics():
    return generate_latest(), 200, {"Content-Type": CONTENT_TYPE_LATEST}


if __name__ == "__main__":
    # Local runs only; in the container gunicorn serves app:app.
    app.run(host="0.0.0.0", port=int(os.getenv("PORT", "8080")))
