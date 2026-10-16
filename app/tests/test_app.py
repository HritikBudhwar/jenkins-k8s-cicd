"""Unit tests for demo-app. The pipeline runs them in the Dockerfile's "test" stage.

Flask's test client calls the endpoints in-process: no server and no network,
so the tests are fast and deterministic.
"""
import os
import sys

import pytest

# app.py reads these at import time, so set them before importing it.
os.environ.update(APP_VERSION="abc1234-42", GIT_COMMIT="abc1234def5678", APP_ENV="test")

# Make "import app" work no matter which directory pytest is started from.
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import app as app_module  # noqa: E402  (import after the env/path setup above)


@pytest.fixture
def client():
    """A Flask test client: sends requests to the app without starting a server."""
    app_module.app.config["TESTING"] = True
    return app_module.app.test_client()


def test_index_reports_version_and_commit(client):
    resp = client.get("/")
    assert resp.status_code == 200
    body = resp.get_json()
    assert body["app"] == "demo-app"
    assert body["version"] == "abc1234-42"
    assert body["commit"] == "abc1234def5678"
    assert body["environment"] == "test"


def test_health_is_ok(client):
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.get_json() == {"status": "ok"}


def test_ready_is_ready(client):
    resp = client.get("/ready")
    assert resp.status_code == 200
    assert resp.get_json() == {"status": "ready"}


def test_metrics_exposes_prometheus_format(client):
    client.get("/")  # make at least one request so the counter has a sample
    resp = client.get("/metrics")
    assert resp.status_code == 200
    assert resp.content_type.startswith("text/plain")
    text = resp.get_data(as_text=True)
    assert "app_requests_total" in text
    assert 'endpoint="/"' in text


def test_unknown_route_returns_404(client):
    assert client.get("/does-not-exist").status_code == 404
