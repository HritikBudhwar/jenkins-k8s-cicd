/**
 * failPipeline: stop the build with a clear, human-readable reason.
 *
 * Every step in this library fails through here instead of calling `error`
 * directly. The stage name and reason are saved in env, so the final
 * notification (notify.groovy) can say exactly what broke and what to do,
 * instead of only "script returned exit code 1".
 *
 * Usage:
 *   failPipeline("Unit tests failed: see the Test Result page.")
 *
 *   // Run a block; if a command inside it fails, fail with this reason instead:
 *   failPipeline.onError('docker build failed (see output above)') {
 *       sh 'docker build ...'
 *   }
 */
def call(String reason) {
    env.FAILED_STAGE = env.STAGE_NAME ?: 'unknown stage'
    env.FAILURE_REASON = reason
    error(reason)
}

def onError(String reason, Closure body) {
    try {
        return body()
    } catch (InterruptedException e) {
        throw e      // build aborted or timed out: keep ABORTED, do not relabel it as a failure
    } catch (Exception e) {
        call("${reason} [${e.message}]")
    }
}
