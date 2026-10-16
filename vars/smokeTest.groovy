/**
 * smokeTest: after a deploy, check that the new version really answers.
 *
 * A green `rollout status` only proves the pods pass their probes. This step
 * also checks the app returns the expected version through its Service.
 * The test itself is resources/org/devops/smoke-test.sh, shipped inside the
 * library, so every repo gets the same test.
 *
 * Returns true/false instead of failing, so the caller (standardPipeline)
 * can roll back before failing the build.
 *
 * Usage: boolean ok = smokeTest(cfg, target)   (expects env.IMAGE_TAG)
 */
boolean call(Map cfg, Map target) {
    writeFile(file: '.smoke-test.sh', text: libraryResource('org/devops/smoke-test.sh'))
    int status = 1
    withKubeConfig([credentialsId: target.credentialsId]) {
        status = sh(script: "bash .smoke-test.sh ${target.namespace} ${cfg.appName} ${env.IMAGE_TAG}",
                    returnStatus: true)
    }
    return status == 0
}
