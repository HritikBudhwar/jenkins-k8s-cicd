/**
 * buildImage: build the production image (the last stage of the Dockerfile).
 *
 * The image is tagged with env.IMAGE (<registry>/<app>:<sha>-<build>) and gets
 * the version and commit as build args, so the running app can report exactly
 * what it is (see the "/" endpoint of app/app.py).
 *
 * Usage: buildImage(cfg)   (expects env.IMAGE, env.IMAGE_TAG, env.GIT_COMMIT)
 */
def call(Map cfg) {
    failPipeline.onError("docker build failed for ${cfg.dockerfile} after 2 attempts (see the build output above)") {
        // Pulling the base image can hit a registry hiccup, so retry once.
        retry(2) {
            sh """docker build \\
                  --build-arg APP_VERSION=${env.IMAGE_TAG} \\
                  --build-arg GIT_COMMIT=${env.GIT_COMMIT} \\
                  -t ${env.IMAGE} \\
                  -f ${cfg.dockerfile} ${cfg.buildContext}"""
        }
    }
    echo "Built ${env.IMAGE}"
}
