/**
 * deployToK8s: deploy env.IMAGE to one target (cluster + namespace), with automatic rollback.
 *
 * Steps:
 *   1. check the target's kubeconfig credential exists (clear error if not)
 *   2. kustomize edit set image / set namespace   -> render the manifests
 *   3. kubectl apply
 *   4. kubectl rollout status --timeout           -> wait until the new pods are Ready
 *   5. on timeout/failure: print diagnostics, kubectl rollout undo, and fail
 *      the build with a message that says the rollback happened
 *
 * A target looks like (see PipelineConfig.validateTarget):
 *   [environment: 'dev', cluster: 'kind-cicd-demo', namespace: 'demo-dev',
 *    credentialsId: 'kubeconfig-dev', overlay: 'k8s/overlays/dev', rolloutTimeout: '120s']
 *
 * Each cluster has its own Jenkins credential, so adding a cluster means
 * adding one credential and one YAML entry, with no code change.
 *
 * Usage: deployToK8s(cfg, target)  and  deployToK8s.rollback(cfg, target)
 */
def call(Map cfg, Map target) {
    String ns = target.namespace
    String where = "${target.cluster}/${ns}"
    String manifest = "reports/manifest-${target.environment}-${target.cluster}-${ns}.yaml"

    checkCredential(target)

    // withKubeConfig (kubernetes-cli plugin) writes the credential to a temporary
    // kubeconfig file, sets KUBECONFIG, and deletes the file when the block ends.
    withKubeConfig([credentialsId: target.credentialsId]) {
        // 1. Render the manifests. The edit only changes the workspace copy, never Git.
        dir(target.overlay) {
            sh "kustomize edit set image ${cfg.appName}=${env.IMAGE}"
            sh "kustomize edit set namespace ${ns}"
        }
        // The rendered file is archived with the build: an audit trail of exactly what was applied.
        sh "kustomize build ${target.overlay} > ${manifest}"

        // 2. Apply. If this fails nothing changed (or only some objects did),
        // so there is nothing to roll back: fail with a hint.
        if (sh(script: "kubectl apply -n ${ns} -f ${manifest}", returnStatus: true) != 0) {
            failPipeline("kubectl apply failed on ${where} (see the error above). " +
                         "'forbidden' means the jenkins-deployer Role lacks a permission (k8s/rbac/jenkins-deployer.yaml); " +
                         "'connection refused'/'timeout' means Jenkins cannot reach the cluster API server.")
        }

        // 3. Wait for the rollout. maxUnavailable: 0 keeps the old pods serving while we wait.
        int rollout = sh(script: "kubectl -n ${ns} rollout status deployment/${cfg.appName} --timeout=${target.rolloutTimeout}",
                         returnStatus: true)
        if (rollout != 0) {
            printDiagnostics(ns, cfg.appName)
            boolean rolledBack = undo(ns, cfg.appName, target.rolloutTimeout)
            if (rolledBack) {
                failPipeline("Deployment of ${env.IMAGE} to ${where} did not become ready within ${target.rolloutTimeout}. " +
                             'It was AUTOMATICALLY ROLLED BACK to the previous revision, which is serving traffic again. ' +
                             'The pod status and events above show why the new pods failed (crash, failing probe, image pull...).')
            }
            failPipeline("Deployment of ${env.IMAGE} to ${where} failed AND the automatic rollback did not succeed " +
                         '(on a first-ever deployment there is no previous revision to go back to). ' +
                         "Investigate now: kubectl -n ${ns} get pods; kubectl -n ${ns} rollout history deployment/${cfg.appName}")
        }
        echo "Deployed ${env.IMAGE} to ${where}"
    }
}

/**
 * Roll a target back to its previous revision. Used by standardPipeline when
 * the smoke test fails after a rollout that looked healthy.
 * Returns true if the rollback finished.
 */
boolean rollback(Map cfg, Map target) {
    boolean ok = false
    withKubeConfig([credentialsId: target.credentialsId]) {
        ok = undo(target.namespace, cfg.appName, target.rolloutTimeout)
    }
    return ok
}

// ------------------------------------------------------------------ helpers

/** kubectl rollout undo, then wait until the old revision is Ready again. */
private boolean undo(String ns, String deployment, String timeout) {
    echo "Rolling back deployment/${deployment} in ${ns} to the previous revision..."
    if (sh(script: "kubectl -n ${ns} rollout undo deployment/${deployment}", returnStatus: true) != 0) {
        echo 'kubectl rollout undo failed (no previous revision?).'
        return false
    }
    return sh(script: "kubectl -n ${ns} rollout status deployment/${deployment} --timeout=${timeout}",
              returnStatus: true) == 0
}

/** Print what an engineer would check first. Never fails the build by itself. */
private void printDiagnostics(String ns, String deployment) {
    echo "Rollout failed. Diagnostics for ${ns}/${deployment}:"
    sh(returnStatus: true, script: """
        kubectl -n ${ns} get pods -l app=${deployment} -o wide
        kubectl -n ${ns} describe deployment ${deployment} | tail -n 25
        kubectl -n ${ns} get events --sort-by=.lastTimestamp | tail -n 20
    """)
}

/**
 * Fail early, with an actionable message, if the kubeconfig credential is
 * missing or has the wrong type, instead of a stack trace deep in the plugin.
 */
private void checkCredential(Map target) {
    try {
        withCredentials([file(credentialsId: target.credentialsId, variable: 'KUBECONFIG_CHECK')]) {
            echo "Using kubeconfig credential '${target.credentialsId}' for ${target.cluster}/${target.namespace}"
        }
    } catch (InterruptedException e) {
        throw e   // build aborted or timed out: do not turn that into a credential error
    } catch (Exception e) {
        failPipeline("Kubeconfig credential '${target.credentialsId}' for ${target.cluster}/${target.namespace} " +
                     "was not found or is not a 'Secret file' credential (${e.message}). " +
                     'Add it to jenkins/casc/jenkins.yaml (credentials added in the UI are deleted at the next restart, ' +
                     "because Configuration-as-Code manages them): see docs/SETUP.md 'Add a new Kubernetes cluster'.")
    }
}
