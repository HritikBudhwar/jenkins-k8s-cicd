import org.devops.ImageTag
import org.devops.PipelineConfig

/**
 * standardPipeline: the one reusable CI/CD pipeline every repo calls.
 *
 * A repo onboards with a ~10-line Jenkinsfile:
 *
 *   @Library('cicd-shared-lib') _
 *   standardPipeline(appName: 'demo-app', registry: 'localhost:5001')
 *
 * Deploy targets, branch rules and approvers normally come from that repo's
 * entry in jenkins/seed/repos.yaml (the seed job stores them on the repo's
 * Jenkins folder as PIPELINE_CONFIG). Any key can also be passed in the
 * Jenkinsfile, which wins. All keys and defaults: src/org/devops/PipelineConfig.groovy
 *
 * Flow:
 *   Checkout -> Secret Scan -> Lint -> Unit Test -> Build Image -> Security Scan
 *     -> Push Image -> Deploy to Dev -> Smoke Test: Dev                  (develop branch)
 *     -> Push Image -> Manual Approval -> Deploy to Prod -> Smoke Test: Prod   (main branch)
 *   Any other branch / PR: stops after Security Scan (build + test only).
 *
 * The prod stages run on a fresh agent after the approval, so a build waiting
 * for approval holds no executor.
 */
def call(Map userConfig = [:]) {
    // Validate BEFORE allocating an agent: a typo fails in seconds with a clear list of problems.
    Map cfg = loadConfig(userConfig)

    pipeline {
        // No agent for the whole pipeline: the Manual Approval stage can wait up to
        // approvalTimeoutMinutes, and a waiting build must not hold an executor
        // (two pending prod approvals would otherwise block every other build).
        // The two stage groups below each take an agent for the work they do.
        agent none

        options {
            timeout(time: cfg.pipelineTimeoutMinutes, unit: 'MINUTES')    // never hang forever
            timestamps()                                                    // time on every log line
            buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
            disableConcurrentBuilds()            // two deploys of one branch must not race
            skipDefaultCheckout(true)            // checkout is an explicit, retried stage below
        }

        triggers {
            // Primary trigger: the GitHub webhook (Multibranch + GitHub Branch Source).
            // Fallback: poll Git every ~15 min in case a webhook is lost. Trade-off:
            // polling costs a `git ls-remote` per branch and adds up to 15 min of delay,
            // so it is only a safety net. Set pollSchedule: '' to turn it off.
            pollSCM(cfg.pollSchedule)
        }

        stages {
            // Everything up to and including the dev deploy, on one agent and workspace.
            stage('Build and Test') {
                agent { label cfg.agentLabel }       // '' = any agent
                stages {
                    stage('Checkout') {
                        steps {
                            script {
                                failPipeline.onError('Could not check out the repository after 3 attempts: check the repo URL, its credentials and the network') {
                                    retry(3) {      // the Git server or network can blip
                                        Map scmVars = checkout(scm)
                                        env.GIT_COMMIT = scmVars.GIT_COMMIT
                                        env.GIT_BRANCH = scmVars.GIT_BRANCH
                                    }
                                }
                                env.IMAGE_TAG = ImageTag.from(env.GIT_COMMIT, env.BUILD_NUMBER)
                                env.IMAGE = ImageTag.fullImage(cfg.registry, cfg.appName, env.IMAGE_TAG)
                                env.DEPLOY_ENV = PipelineConfig.deployEnvironment(cfg, env.BRANCH_NAME ?: env.GIT_BRANCH)
                                sh 'mkdir -p reports'
                                echo "Image: ${env.IMAGE}. Deploys to: ${env.DEPLOY_ENV ?: 'nothing (build + test only for this branch)'}"
                            }
                        }
                    }

                    stage('Secret Scan') {
                        when { expression { cfg.secretScan } }
                        steps { secretScan(cfg) }
                    }

                    stage('Lint') {
                        steps { lintRepo(cfg) }
                    }

                    stage('Unit Test') {
                        steps { runTests(cfg) }
                    }

                    stage('Build Image') {
                        steps { buildImage(cfg) }
                    }

                    stage('Security Scan') {
                        steps { scanImage(cfg) }
                    }

                    stage('Push Image') {
                        when { expression { env.DEPLOY_ENV in ['dev', 'prod'] } }
                        steps { pushImage(cfg) }
                    }

                    stage('Deploy to Dev') {
                        when { expression { env.DEPLOY_ENV == 'dev' } }
                        steps { script { deployEnvironment(cfg, 'dev') } }
                    }

                    stage('Smoke Test: Dev') {
                        when { expression { env.DEPLOY_ENV == 'dev' } }
                        steps { script { smokeTestEnvironment(cfg, 'dev') } }
                    }
                }
                post {
                    always {
                        // Test report, Trivy report, gitleaks report and rendered manifests.
                        archiveArtifacts(artifacts: 'reports/**', allowEmptyArchive: true)
                    }
                    cleanup {
                        // Remove local images (the registry has the pushed one) and the workspace,
                        // so agents do not slowly fill their disks.
                        sh "docker rmi ${env.IMAGE ?: ''} ${cfg.appName}-test:${env.IMAGE_TAG ?: 'none'} >/dev/null 2>&1 || true"
                        cleanWs(deleteDirs: true, disableDeferredWipeout: true, notFailBuild: true)
                    }
                }
            }

            stage('Manual Approval') {
                when { expression { env.DEPLOY_ENV == 'prod' } }
                steps {
                    // A person confirms every production release. Only cfg.approvers may
                    // click "Deploy". If nobody answers in time, the build is aborted,
                    // not deployed. No agent is held while waiting.
                    timeout(time: cfg.approvalTimeoutMinutes, unit: 'MINUTES') {
                        input(message: "Deploy ${env.IMAGE} to prod (${PipelineConfig.describe(PipelineConfig.targetsFor(cfg, 'prod'))})?",
                              ok: 'Deploy to prod',
                              submitter: cfg.approvers)
                    }
                }
            }

            // After approval: a fresh agent, the exact commit that was built and approved.
            stage('Release to Prod') {
                when {
                    beforeAgent true                 // do not allocate an agent just to skip the stage
                    expression { env.DEPLOY_ENV == 'prod' }
                }
                agent { label cfg.agentLabel }
                stages {
                    stage('Deploy to Prod') {
                        steps {
                            script {
                                checkoutBuiltCommit()
                                deployEnvironment(cfg, 'prod')
                            }
                        }
                    }

                    stage('Smoke Test: Prod') {
                        steps { script { smokeTestEnvironment(cfg, 'prod') } }
                    }
                }
                post {
                    always {
                        archiveArtifacts(artifacts: 'reports/**', allowEmptyArchive: true)
                    }
                    cleanup {
                        cleanWs(deleteDirs: true, disableDeferredWipeout: true, notFailBuild: true)
                    }
                }
            }
        }

        post {
            // notify only echoes and sets the build description: it needs no agent.
            success { notify(cfg, 'SUCCESS') }
            failure { notify(cfg, 'FAILURE') }
            aborted { notify(cfg, 'ABORTED') }
        }
    }
}

/**
 * The prod stages run on a new agent after the approval, so check the code out
 * again and make sure it is the commit that was built, scanned and approved.
 */
private void checkoutBuiltCommit() {
    String commit = ''
    failPipeline.onError('Could not check out the repository for the prod deploy after 3 attempts') {
        retry(3) {
            commit = checkout(scm).GIT_COMMIT
        }
    }
    if (commit != env.GIT_COMMIT) {
        failPipeline("The prod deploy checked out ${commit}, but ${env.GIT_COMMIT} was built and approved. Nothing was deployed.")
    }
    sh 'mkdir -p reports'
}

/**
 * Merge central config (from repos.yaml, via the folder property set by the seed
 * job) with the Jenkinsfile's config, then validate. The Jenkinsfile wins.
 */
private Map loadConfig(Map userConfig) {
    Map central = [:]
    withFolderProperties {
        if (env.PIPELINE_CONFIG) {
            central = readJSON(text: env.PIPELINE_CONFIG, returnPojo: true)
        }
    }
    try {
        return PipelineConfig.resolve(central + (userConfig ?: [:]))
    } catch (IllegalArgumentException e) {
        error("standardPipeline: invalid configuration. Fix these in the Jenkinsfile or jenkins/seed/repos.yaml:\n${e.message}")
    }
}

/** Deploy to every target of one environment (e.g. several prod clusters), one after another. */
private void deployEnvironment(Map cfg, String environment) {
    List<Map> targets = PipelineConfig.targetsFor(cfg, environment)
    for (Map target in targets) {
        deployToK8s(cfg, target)
    }
    env.DEPLOYED_TO = "${environment}: ${PipelineConfig.describe(targets)}"
}

/** Smoke test every target of one environment; on failure roll that target back and fail. */
private void smokeTestEnvironment(Map cfg, String environment) {
    for (Map target in PipelineConfig.targetsFor(cfg, environment)) {
        if (!smokeTest(cfg, target)) {
            boolean rolledBack = deployToK8s.rollback(cfg, target)
            env.DEPLOYED_TO = "${env.DEPLOYED_TO} (ROLLED BACK on ${target.cluster}/${target.namespace})"
            failPipeline("Smoke test failed on ${target.cluster}/${target.namespace}: ${env.IMAGE} did not answer correctly (see output above). " +
                         (rolledBack ? 'It was automatically rolled back to the previous revision.'
                                     : 'Automatic rollback FAILED: check the deployment manually.'))
        }
    }
}
