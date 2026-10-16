# Jenkins controller with pinned plugins, its config baked in, and the CLI
# tools the pipeline calls. The tools are copied from their publishers' images
# at pinned versions instead of curl | sh downloads; they are static binaries,
# so they run on the Debian-based Jenkins image.
FROM docker:29.9.0-cli AS docker-cli
FROM docker/buildx-bin:0.38.0 AS buildx
FROM registry.k8s.io/kubectl:v1.36.4 AS kubectl
FROM registry.k8s.io/kustomize/kustomize:v5.8.3 AS kustomize
FROM hadolint/hadolint:v2.15.1 AS hadolint
FROM ghcr.io/yannh/kubeconform:v0.8.0 AS kubeconform
FROM aquasec/trivy:0.76.0 AS trivy
FROM ghcr.io/gitleaks/gitleaks:v8.30.1 AS gitleaks

FROM jenkins/jenkins:2.580.1-lts-jdk21@sha256:a660310e39ade10631f774bacd5219de767dfd08947ea5c32a739b5e5bb382c1

# Tools are installed as root-owned files the jenkins user can run but not modify.
COPY --from=docker-cli  /usr/local/bin/docker  /usr/local/bin/docker
COPY --from=buildx      /buildx                /usr/local/lib/docker/cli-plugins/docker-buildx
COPY --from=kubectl     /bin/kubectl           /usr/local/bin/kubectl
COPY --from=kustomize   /app/kustomize         /usr/local/bin/kustomize
COPY --from=hadolint    /bin/hadolint          /usr/local/bin/hadolint
COPY --from=kubeconform /kubeconform           /usr/local/bin/kubeconform
COPY --from=trivy       /usr/local/bin/trivy   /usr/local/bin/trivy
COPY --from=gitleaks    /usr/bin/gitleaks      /usr/local/bin/gitleaks

# Skip the interactive setup wizard; JCasC configures everything.
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false" \
    CASC_JENKINS_CONFIG=/usr/share/jenkins/casc/jenkins.yaml

# Plugins: baked into the image, so every controller started from it is identical.
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

# JCasC file lives outside JENKINS_HOME (a volume) and is read-only for the
# jenkins user, so the configuration in Git is always what the controller runs.
COPY casc/jenkins.yaml /usr/share/jenkins/casc/jenkins.yaml

# Never run the controller as root. 1000 is the "jenkins" user of the base image
# (numeric, so tools that check for non-root can verify it).
USER 1000

# Fails if the tools are missing or broken, so a bad image never ships.
RUN docker --version && docker buildx version && kubectl version --client && kustomize version \
 && hadolint --version && kubeconform -v && trivy --version && gitleaks version
