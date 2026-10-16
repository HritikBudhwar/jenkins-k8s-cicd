/**
 * scanImage: scan the built image with Trivy and block it if it is vulnerable.
 *
 * Policy: fail on HIGH/CRITICAL vulnerabilities that have a fix available
 * (--ignore-unfixed). Unfixable CVEs would block every build with nothing
 * actionable; they are still visible by running Trivy without that flag.
 * Accept a specific risk on purpose by listing its CVE in .trivyignore, with
 * a comment saying why.
 *
 * The scan runs BEFORE the push, so a vulnerable image never reaches the
 * registry or a cluster.
 *
 * Usage: scanImage(cfg)   (expects env.IMAGE)
 */
def call(Map cfg) {
    // Exit code 3 means "vulnerabilities found". Trivy uses 1 for its own errors
    // (image not found, DB download failed), so the two failures give different messages.
    final int VULNS_FOUND = 3

    // The vulnerability DB download is the flaky, network-bound part: retry it.
    failPipeline.onError('Could not download the Trivy vulnerability database after 3 attempts (network?). The image was NOT pushed.') {
        retry(3) {
            sh 'trivy image --download-db-only --no-progress'
        }
    }

    int status = sh(returnStatus: true, script: """trivy image --skip-db-update --no-progress \\
        --severity ${cfg.trivySeverity} --ignore-unfixed --exit-code ${VULNS_FOUND} \\
        --format table --output reports/trivy-report.txt ${env.IMAGE}""")
    sh 'cat reports/trivy-report.txt 2>/dev/null || true'

    if (status == VULNS_FOUND) {
        failPipeline("Security scan failed: Trivy found ${cfg.trivySeverity} vulnerabilities with a fix available in ${env.IMAGE}. " +
                     'The image was NOT pushed or deployed. Upgrade the affected packages/base image ' +
                     '(see reports/trivy-report.txt, archived with this build).')
    } else if (status != 0) {
        failPipeline("Trivy could not complete the scan (exit code ${status}); see the log above. " +
                     'The image was NOT pushed. This is a scanner/network error, not a vulnerability.')
    }
}
