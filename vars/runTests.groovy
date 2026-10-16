/**
 * runTests: run the unit tests inside the Dockerfile's test stage.
 *
 * Why in Docker and not directly on the Jenkins agent?
 *   - Tests run on exactly the same Python and OS as the production image.
 *   - The agent needs no Python, pip or virtualenv, only Docker. Any repo
 *     with a "test" stage in its Dockerfile can use this step unchanged.
 *
 * The JUnit report is copied out of the container and published, so failures
 * appear on the build's "Test Result" page with the failing test names.
 *
 * Usage: runTests(cfg)   (expects env.IMAGE_TAG, set in the Checkout stage)
 */
def call(Map cfg) {
    String testImage = "${cfg.appName}-test:${env.IMAGE_TAG}"           // local only, never pushed
    String container = "${cfg.appName}-test-${env.BUILD_TAG}".replaceAll(/[^a-zA-Z0-9_.-]/, '-')

    failPipeline.onError("Could not build the '${cfg.testTarget}' stage of ${cfg.dockerfile}: a dependency failed to install " +
                         'or the Dockerfile is broken (see the docker output above). Is Docker reachable from the agent?') {
        sh "docker build --target ${cfg.testTarget} -t ${testImage} -f ${cfg.dockerfile} ${cfg.buildContext}"
    }

    // returnStatus: keep going on failure, so we can still collect the report.
    int status = sh(script: "docker run --name ${container} ${testImage}", returnStatus: true)
    sh "docker cp ${container}:${cfg.testReportPath} reports/junit.xml || echo 'No JUnit report produced.'"
    sh "docker rm -f ${container} >/dev/null 2>&1 || true"
    junit(testResults: 'reports/junit.xml', allowEmptyResults: true)

    if (status != 0) {
        failPipeline("Unit tests failed (exit code ${status}). Failing tests are listed on this build's " +
                     "'Test Result' page and in the pytest output above. Nothing was pushed or deployed.")
    }
}
