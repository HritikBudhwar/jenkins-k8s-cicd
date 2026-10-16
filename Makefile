# ---------------------------------------------------------------------------
# Shortcuts for the local demo and the checks. Run `make` to list targets.
# Needs: Docker (Desktop or Engine), make, bash, git. kind and kubectl are
# downloaded to local-setup/.bin by `make cluster` if you do not have them.
# Windows: run these from a WSL2 terminal (Ubuntu), not PowerShell/Git Bash.
# ---------------------------------------------------------------------------
SHELL         := /bin/bash
.DEFAULT_GOAL := help

COMPOSE  := docker compose -f local-setup/docker-compose.yml
CONTEXT  := kind-cicd-demo
REGISTRY := localhost:5001
APP      := demo-app
JDK_IMAGE := eclipse-temurin:17.0.16_8-jdk

# Use tools downloaded by setup.sh when they are not installed globally.
export PATH := $(CURDIR)/local-setup/.bin:$(PATH)

.PHONY: help cluster up down logs pods test test-app test-lib lint deploy-dev rotate-tokens clean

help: ## List the available targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

cluster: ## Create the kind cluster, namespaces, RBAC and the kubeconfigs for Jenkins
	./local-setup/setup.sh

up: ## Build and start Jenkins + registry (needs .env and `make cluster` first)
	@test -f .env || { echo "Missing .env: run 'cp .env.example .env' and fill it in."; exit 1; }
	@! grep -q '^JENKINS_ADMIN_PASSWORD=replace-me' .env || { echo "Set JENKINS_ADMIN_PASSWORD in .env first."; exit 1; }
	@test -f local-setup/.secrets/kubeconfig-dev || { echo "Missing kubeconfigs: run 'make cluster' first."; exit 1; }
	@# The group that owns docker.sock *as seen inside containers* differs between
	@# Docker Desktop (root) and Linux (docker). Ask Docker instead of guessing.
	DOCKER_GID=$$(docker run --rm -v /var/run/docker.sock:/var/run/docker.sock --entrypoint stat docker:29.9.0-cli -c %g /var/run/docker.sock) \
	  $(COMPOSE) up -d --build
	@echo "Waiting for Jenkins to become healthy (first start takes about a minute)..."
	@for i in $$(seq 1 60); do \
	  [ "$$(docker inspect -f '{{.State.Health.Status}}' jenkins)" = healthy ] && { echo "Jenkins is up: http://localhost:8080"; exit 0; }; \
	  sleep 5; done; echo "Jenkins did not become healthy; check: make logs"; exit 1

down: ## Stop Jenkins + registry (data volumes are kept)
	$(COMPOSE) down

logs: ## Follow the Jenkins controller log
	docker logs -f jenkins

pods: ## Show demo-app pods and images in both namespaces
	@for ns in demo-dev demo-prod; do echo "--- $$ns"; \
	  kubectl --context $(CONTEXT) -n $$ns get pods -l app=$(APP) \
	    -o custom-columns='NAME:.metadata.name,STATUS:.status.phase,READY:.status.containerStatuses[0].ready,RESTARTS:.status.containerStatuses[0].restartCount,IMAGE:.spec.containers[0].image'; done

test: test-app test-lib ## Run all unit tests (app + shared library)

test-app: ## App unit tests in the Dockerfile's test stage (same as the pipeline)
	docker build -q --target test -t $(APP)-test:local app
	docker run --rm $(APP)-test:local

test-lib: ## Shared-library tests (JenkinsPipelineUnit via the Gradle wrapper, in a JDK container)
	docker run --rm -u $$(id -u):$$(id -g) -e HOME=/tmp -e GRADLE_USER_HOME=/work/test/.gradle \
	  -v "$(CURDIR):/work" -w /work/test $(JDK_IMAGE) ./gradlew test --no-daemon --console=plain

lint: ## Static checks: hadolint, kubeconform, Groovy syntax, shellcheck, actionlint, gitleaks
	./scripts/validate.sh

deploy-dev: ## Build, push and deploy to demo-dev WITHOUT Jenkins (checks the cluster setup)
	@set -euo pipefail; \
	TAG="$$(git rev-parse --short=7 HEAD)-local"; IMAGE="$(REGISTRY)/$(APP):$$TAG"; \
	docker build -t "$$IMAGE" --build-arg APP_VERSION="$$TAG" --build-arg GIT_COMMIT="$$(git rev-parse HEAD)" app; \
	docker push "$$IMAGE"; \
	kubectl kustomize k8s/overlays/dev | sed "s#image: $(APP)\$$#image: $$IMAGE#" | kubectl --context $(CONTEXT) apply -f -; \
	kubectl --context $(CONTEXT) -n demo-dev rollout status deployment/$(APP) --timeout=120s; \
	echo "Deployed $$IMAGE to demo-dev (as your admin user, not via Jenkins)"

rotate-tokens: ## Renew the Jenkins deployer tokens (they expire after 30 days) and reload them
	./local-setup/setup.sh
	docker restart jenkins
	@echo "New kubeconfigs loaded. Jenkins restarts in about a minute."

clean: ## Delete EVERYTHING: containers, volumes (Jenkins data, images), kind cluster, kubeconfigs
	-$(COMPOSE) down -v
	-kind delete cluster --name cicd-demo
	rm -rf local-setup/.secrets
