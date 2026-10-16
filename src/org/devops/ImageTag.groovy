package org.devops

import com.cloudbees.groovy.cps.NonCPS

/**
 * ImageTag: builds immutable, traceable image tags.
 *
 * Format: <short git sha>-<build number>, e.g. 3f2a9c1-17
 *   - the SHA tells you exactly which commit is running
 *   - the build number keeps tags unique if the same commit is rebuilt
 *
 * 'latest' is never allowed: it is mutable, so you cannot tell what is
 * running, and `kubectl rollout undo` cannot bring back a previous version.
 */
class ImageTag implements Serializable {

    private static final long serialVersionUID = 1L

    /** Create the tag from the full commit SHA and the Jenkins build number. */
    @NonCPS
    static String from(String commitSha, Object buildNumber) {
        String sha = (commitSha ?: '').trim().toLowerCase()
        if (!(sha ==~ /[0-9a-f]{7,40}/)) {
            throw new IllegalArgumentException("Cannot build an image tag: '${commitSha}' is not a Git commit SHA.")
        }
        String build = (buildNumber ?: '').toString().trim()
        if (!(build ==~ /\d+/)) {
            throw new IllegalArgumentException("Cannot build an image tag: build number '${buildNumber}' is not a number.")
        }
        return "${sha.take(7)}-${build}".toString()
    }

    /** Full image reference, e.g. localhost:5001/demo-app:3f2a9c1-17 */
    @NonCPS
    static String fullImage(String registry, String name, String tag) {
        if (!tag || tag == 'latest') {
            throw new IllegalArgumentException("Refusing to use image tag '${tag}': tags must be immutable (<sha>-<build>).")
        }
        return "${registry}/${name}:${tag}".toString()
    }
}
