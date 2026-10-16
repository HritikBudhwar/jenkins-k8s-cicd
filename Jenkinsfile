/*
 * Jenkinsfile for demo-app. All CI/CD logic lives in the shared library
 * (vars/standardPipeline.groovy), so onboarding a new repo needs only this file.
 *
 * Deploy targets (clusters, namespaces, kubeconfig credential IDs), branch
 * rules and approvers come from this repo's entry in jenkins/seed/repos.yaml.
 * Any setting can also be given here, which overrides the YAML.
 */
@Library('cicd-shared-lib') _

standardPipeline(
    appName : 'demo-app',          // Deployment, Service and image name
    registry: 'localhost:5001'     // local registry from local-setup/docker-compose.yml
    // Example of an override in the Jenkinsfile:
    // deployTargets: [[environment: 'dev', cluster: 'kind-cicd-demo', namespace: 'demo-dev', credentialsId: 'kubeconfig-dev']]
)
