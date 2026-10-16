import org.devops.PipelineConfig

/**
 * lintRepo: hadolint on the Dockerfile, then render every overlay a deploy
 * target uses and validate it with kubeconform, so a typo in a manifest fails
 * here instead of halfway through a deploy.
 *
 * Usage: lintRepo(cfg)
 */
def call(Map cfg) {
    // 1. Dockerfile lint. Rules can be tuned in .hadolint.yaml at the repo root.
    if (sh(script: "hadolint ${cfg.dockerfile}", returnStatus: true) != 0) {
        failPipeline("hadolint found problems in ${cfg.dockerfile} (listed above). " +
                     'Fix them, or ignore a rule on purpose in .hadolint.yaml with a comment explaining why.')
    }

    // 2. Kubernetes manifest validation for each overlay a target deploys.
    List<String> overlays = PipelineConfig.overlays(cfg)
    if (!overlays) {
        echo 'No deploy targets configured: skipping manifest validation.'
    }
    for (String overlay in overlays) {
        // kubeconform downloads JSON schemas from GitHub on first use (then caches
        // them), so try up to 3 times to ride out a network blip. A manifest that
        // is really invalid fails every attempt and stops the build.
        boolean valid = false
        for (int attempt = 1; attempt <= 3 && !valid; attempt++) {
            valid = sh(returnStatus: true, script: """#!/bin/bash
                set -euo pipefail
                mkdir -p "\$HOME/.cache/kubeconform"
                kustomize build ${overlay} \\
                  | kubeconform -strict -summary -cache "\$HOME/.cache/kubeconform" \\
                      -kubernetes-version ${cfg.kubernetesVersion} -
            """) == 0
            if (!valid && attempt < 3) {
                echo "kubeconform failed for ${overlay} (attempt ${attempt}/3), retrying in 5s..."
                sleep(time: 5, unit: 'SECONDS')
            }
        }
        if (!valid) {
            failPipeline("Manifest validation failed for ${overlay}: see the kustomize/kubeconform output above.")
        }
    }
}
