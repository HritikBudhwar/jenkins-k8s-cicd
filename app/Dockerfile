# ---------------------------------------------------------------------------
# demo-app image. Three stages:
#   deps    : install pinned Python packages into /install
#   test    : runs the unit tests (the pipeline builds this with --target test)
#   runtime : final image; only the app code and runtime packages, non-root
# The base image is pinned by tag AND digest, so a rebuild can never pull a
# different image silently. Renovate/Dependabot can bump both together.
# ---------------------------------------------------------------------------
ARG PYTHON_IMAGE=python:3.12.15-slim-trixie@sha256:a6e34c598f2467ed0e9a8d349809fcd8b5c603269512df273a0bb1784edc11b1

# ---- deps: build the dependency layer once, reuse it in test and runtime ----
FROM ${PYTHON_IMAGE} AS deps
WORKDIR /build
COPY requirements.txt .
# --prefix installs into /install so the runtime stage can copy just that folder.
RUN pip install --no-cache-dir --prefix=/install -r requirements.txt

# ---- test: same Python as production, plus pytest ---------------------------
FROM ${PYTHON_IMAGE} AS test
WORKDIR /src
COPY --from=deps /install /usr/local
COPY requirements-dev.txt requirements.txt ./
RUN pip install --no-cache-dir -r requirements-dev.txt
COPY app.py ./
COPY tests/ ./tests/
# Tests run when the container starts, so the pipeline can copy the JUnit
# report out of the stopped container even when tests fail.
CMD ["pytest", "-v", "--junitxml=/src/reports/junit.xml", "tests/"]

# ---- runtime: the image that is pushed and deployed ------------------------
FROM ${PYTHON_IMAGE} AS runtime

# Values passed with --build-arg by the pipeline; shown by the "/" endpoint.
ARG APP_VERSION=dev
ARG GIT_COMMIT=unknown

# PYTHONDONTWRITEBYTECODE: no .pyc files (the root filesystem is read-only in K8s).
# PYTHONUNBUFFERED: logs go straight to stdout, so `kubectl logs` shows them at once.
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    APP_VERSION=${APP_VERSION} \
    GIT_COMMIT=${GIT_COMMIT} \
    PORT=8080

# OCI labels let you trace a running image back to the commit that built it.
LABEL org.opencontainers.image.title="demo-app" \
      org.opencontainers.image.revision="${GIT_COMMIT}" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.licenses="MIT"

# Dedicated unprivileged user with a fixed numeric UID. Kubernetes'
# runAsNonRoot check needs a numeric UID to verify the user is not root.
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app

COPY --from=deps /install /usr/local
WORKDIR /app
# Code stays owned by root, so the app user can read it but cannot modify it.
COPY app.py .

USER 10001
EXPOSE 8080

# Used by plain `docker run` / docker compose. Kubernetes ignores HEALTHCHECK
# and uses the liveness/readiness probes in k8s/base/deployment.yaml instead.
# The slim image has no curl, so the check uses Python's standard library.
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/health', timeout=2)"]

# gunicorn is a production WSGI server (Flask's built-in server is for development only).
# One worker with threads keeps the Prometheus counters in a single process,
# so /metrics always reports totals for the whole container.
CMD ["gunicorn", "--bind", "0.0.0.0:8080", "--workers", "1", "--threads", "4", \
     "--access-logfile", "-", "--worker-tmp-dir", "/tmp", "app:app"]
