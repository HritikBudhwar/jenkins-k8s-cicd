/**
 * pushImage: push the scanned image to the registry.
 *
 * If cfg.registryCredentialsId is set, log in with that Jenkins
 * "Username with password" credential. Security details:
 *   - withCredentials masks the values in the build log
 *   - the password goes through stdin, never on the command line (where `ps` could see it)
 *   - the shell command is in single quotes, so Groovy never interpolates the secret
 *   - DOCKER_CONFIG points to the workspace, so the login token is deleted with
 *     the workspace and never left in the agent's home directory
 *
 * Usage: pushImage(cfg)   (expects env.IMAGE)
 */
def call(Map cfg) {
    failPipeline.onError("Could not push ${env.IMAGE} after 3 attempts: is the registry ${cfg.registry} reachable, " +
                         'and are the credentials (registryCredentialsId) right?') {
        // Registry pushes are network calls that fail now and then: retry.
        retry(3) {
            if (cfg.registryCredentialsId) {
                withCredentials([usernamePassword(credentialsId: cfg.registryCredentialsId,
                                                  usernameVariable: 'REGISTRY_USER',
                                                  passwordVariable: 'REGISTRY_PASSWORD')]) {
                    withEnv(["DOCKER_CONFIG=${env.WORKSPACE}/.docker-config", "REGISTRY_HOST=${cfg.registry.split('/')[0]}"]) {
                        sh 'echo "$REGISTRY_PASSWORD" | docker login "$REGISTRY_HOST" -u "$REGISTRY_USER" --password-stdin'
                        sh "docker push ${env.IMAGE}"
                        sh 'docker logout "$REGISTRY_HOST"'
                    }
                }
            } else {
                sh "docker push ${env.IMAGE}"
            }
        }
    }
    echo "Pushed ${env.IMAGE}"
}
