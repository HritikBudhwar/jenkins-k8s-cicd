/**
 * notify: report the pipeline result, success or failure, in one consistent format.
 *
 * What it does today (all exercised by the local demo):
 *   - prints a summary block at the end of the console log: app, branch,
 *     commit, image, where it was deployed and, on failure, the stage and the
 *     reason recorded by failPipeline()
 *   - sets the build description, so the build list shows "3f2a9c1-17 -> dev"
 *     or "FAILED in Security Scan" at a glance
 * GitHub commit/PR status ("Jenkins: success/failure" checks) is reported
 * automatically by the GitHub Branch Source plugin; no code is needed here.
 *
 * Extension point: add slackSend/mail/office365ConnectorSend below if a chat
 * plugin is installed and configured. They are not included because the
 * local demo has no chat server to test them against.
 *
 * Usage: notify(cfg, 'SUCCESS' | 'FAILURE' | 'ABORTED')
 */
def call(Map cfg, String status) {
    String image = env.IMAGE ?: 'not built'
    String deployedTo = env.DEPLOYED_TO ?: 'nothing deployed'

    List<String> lines = [
        "==================== PIPELINE ${status} ====================",
        "App       : ${cfg.appName}",
        "Branch    : ${env.BRANCH_NAME ?: env.GIT_BRANCH ?: 'unknown'}",
        "Commit    : ${env.GIT_COMMIT ?: 'unknown'}",
        "Image     : ${image}",
        "Deployed  : ${deployedTo}",
        "Duration  : ${currentBuild.durationString.replace(' and counting', '')}",
    ]
    if (status != 'SUCCESS') {
        lines << "Stage     : ${env.FAILED_STAGE ?: 'see console log'}"
        lines << "Reason    : ${env.FAILURE_REASON ?: 'unexpected error; see the first red step in the log above'}"
    }
    lines << "Details   : ${env.BUILD_URL ?: ''}console"
    lines << '=' * 62
    echo lines.join('\n')

    if (status == 'SUCCESS') {
        currentBuild.description = env.DEPLOYED_TO ? "${env.IMAGE_TAG} -> ${env.DEPLOYED_TO}" : "${env.IMAGE_TAG ?: ''} (build + test only)"
    } else {
        currentBuild.description = "${status} in ${env.FAILED_STAGE ?: 'pipeline'}"
    }
}
