/**
 * secretScan: look for committed secrets (API keys, tokens, private keys) with gitleaks.
 *
 * Runs first, before anything is built or pushed, so a leaked secret never ends
 * up in an image layer or a registry. Findings are redacted in the log.
 * Allow-list false positives in .gitleaks.toml at the repo root.
 *
 * Usage: secretScan(cfg)
 */
def call(Map cfg) {
    // `gitleaks dir` scans the checked-out files. The full Git history is scanned
    // by scripts/validate.sh in GitHub Actions, where a full clone is cheap.
    int status = sh(
        script: 'gitleaks dir . --redact --no-banner --exit-code 1 --report-format json --report-path reports/gitleaks.json',
        returnStatus: true
    )
    if (status != 0) {
        failPipeline('Secret scan failed: gitleaks found what looks like a secret in the repository ' +
                     '(details, redacted, are in the log above and reports/gitleaks.json). ' +
                     'Remove it, ROTATE the secret (assume it is compromised), or allow-list a false positive in .gitleaks.toml.')
    }
}
