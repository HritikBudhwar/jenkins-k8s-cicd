/**
 * notify: print one summary block at the end of every build (image, where it
 * was deployed and, on failure, the stage and reason recorded by
 * failPipeline) and set the build description shown in the build list.
 *
 * GitHub commit statuses come from the GitHub Branch Source plugin, not from
 * here (not tested locally). Chat notifications (slackSend, mail) would go
 * here; they are left out because there was no chat server to test against.
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
