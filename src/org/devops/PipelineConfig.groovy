package org.devops

import com.cloudbees.groovy.cps.NonCPS

/**
 * PipelineConfig: validates the map given to standardPipeline(...) and fills in defaults.
 *
 * Why a class in src/ instead of logic in vars/?
 *   - It is plain Groovy with no Jenkins steps, so it can be unit tested quickly
 *     (test/src/test/groovy/PipelineConfigTest.groovy).
 *   - It reports ALL problems at once with clear messages, instead of failing
 *     20 minutes later in the deploy stage because of a typo.
 *
 * Methods are @NonCPS: they only do pure computation (no steps), and @NonCPS
 * lets them use closures (each/collect/findAll) safely inside Jenkins.
 *
 * Config sources, merged in this order (later wins):
 *   1. DEFAULTS below
 *   2. the repo's `pipeline:` section in jenkins/seed/repos.yaml (via the seed job)
 *   3. the map passed in the repo's Jenkinsfile
 */
class PipelineConfig implements Serializable {

    private static final long serialVersionUID = 1L

    /** Environments the pipeline knows about. Each maps to a set of stages. */
    static final List<String> ENVIRONMENTS = ['dev', 'prod']

    /** Keys allowed inside one entry of deployTargets. */
    static final List<String> TARGET_KEYS = ['environment', 'cluster', 'namespace', 'credentialsId',
                                             'overlay', 'rolloutTimeout']

    static final List<String> TRIVY_SEVERITIES = ['UNKNOWN', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL']

    /** Kubernetes names (namespaces, app names) must be DNS-1123 labels. */
    private static final String DNS_LABEL = /[a-z0-9]([-a-z0-9]*[a-z0-9])?/

    /**
     * Every supported key and its default. A null default means "required".
     * Returned as a new map each time so callers can never change the defaults.
     */
    @NonCPS
    static Map<String, Object> defaults() {
        return [
            appName               : null,              // required; also the Deployment, Service and image name
            registry              : null,              // required; e.g. 'localhost:5001' or 'ghcr.io/me'
            dockerfile            : 'app/Dockerfile',
            buildContext          : 'app',
            testTarget            : 'test',            // Dockerfile stage that runs the unit tests
            testReportPath        : '/src/reports/junit.xml', // JUnit file inside the test container
            devBranch             : 'develop',         // this branch deploys to the 'dev' targets
            prodBranch            : 'main',            // this branch deploys to the 'prod' targets after approval
            deployTargets         : [],                // list of maps, see validateTarget()
            approvers             : '',                // Jenkins users/groups allowed to approve prod ('' = anyone with Build)
            approvalTimeoutMinutes: 30,
            pipelineTimeoutMinutes: 60,
            rolloutTimeout        : '120s',            // max wait for `kubectl rollout status`
            trivySeverity         : 'HIGH,CRITICAL',   // these severities fail the build
            secretScan            : true,              // run gitleaks on the checked-out code
            kubernetesVersion     : '1.36.0',          // schema version used by kubeconform
            registryCredentialsId : '',                // Jenkins username/password credential; '' = no login
            agentLabel            : '',                // Jenkins agent label; '' = any agent
            pollSchedule          : 'H/15 * * * *',    // pollSCM fallback if a webhook is missed; '' = off
        ]
    }

    /**
     * Merge user config onto defaults, validate everything, and return the final config.
     * Throws IllegalArgumentException listing every problem found.
     */
    @NonCPS
    static Map resolve(Map userConfig) {
        Map input = userConfig ?: [:]
        Map cfg = defaults()
        List<String> errors = []

        // 1. Unknown keys are almost always typos ('regsitry'), so reject them loudly.
        input.keySet().each { key ->
            if (!cfg.containsKey(key)) {
                errors << "Unknown key '${key}'. Valid keys: ${cfg.keySet().sort().join(', ')}".toString()
            }
        }
        input.each { key, value ->
            if (cfg.containsKey(key) && value != null) {
                cfg[key] = value
            }
        }

        // 2. Required values and simple formats.
        requireDnsLabel(cfg, 'appName', errors)
        String registry = cfg.registry?.toString()
        if (!registry) {
            errors << "Missing required key 'registry' (for example 'localhost:5001')."
        } else if (registry.contains('://') || registry.endsWith('/') || registry =~ /\s/) {
            errors << "registry '${registry}' must be a host[:port][/path] without a scheme or trailing slash, e.g. 'localhost:5001'.".toString()
        }
        ['devBranch', 'prodBranch', 'dockerfile', 'buildContext', 'testTarget'].each { key ->
            if (!(cfg[key] instanceof String) || !cfg[key]) {
                errors << "'${key}' must be a non-empty string.".toString()
            }
        }
        if (cfg.devBranch && cfg.devBranch == cfg.prodBranch) {
            errors << "devBranch and prodBranch must be different (both are '${cfg.devBranch}').".toString()
        }
        ['approvalTimeoutMinutes', 'pipelineTimeoutMinutes'].each { key ->
            if (!(cfg[key] instanceof Integer) || cfg[key] <= 0) {
                errors << "'${key}' must be a positive whole number of minutes, got '${cfg[key]}'.".toString()
            }
        }
        validateDuration(cfg.rolloutTimeout, 'rolloutTimeout', errors)
        List<String> severities = cfg.trivySeverity.toString().split(',').collect { it.trim() }
        List<String> badSeverities = severities.findAll { !(it in TRIVY_SEVERITIES) }
        if (badSeverities) {
            errors << "trivySeverity contains ${badSeverities}; allowed values: ${TRIVY_SEVERITIES.join(',')}.".toString()
        }

        // 3. Deploy targets: the part that makes the pipeline multi-cluster.
        if (!(cfg.deployTargets instanceof List)) {
            errors << "'deployTargets' must be a list of maps, e.g. [[environment: 'dev', cluster: 'kind', namespace: 'demo-dev', credentialsId: 'kubeconfig-dev']]."
            cfg.deployTargets = []
        }
        List<Map> targets = []
        cfg.deployTargets.eachWithIndex { target, i ->
            targets << validateTarget(target, i, cfg.rolloutTimeout, errors)
        }
        cfg.deployTargets = targets

        if (errors) {
            throw new IllegalArgumentException(errors.collect { " - ${it}" }.join('\n'))
        }
        return cfg
    }

    /**
     * Which environment does this branch deploy to? Returns 'dev', 'prod', or ''
     * (build and test only). Also returns '' if no targets are configured for
     * that environment, so a repo without clusters still builds safely.
     */
    @NonCPS
    static String deployEnvironment(Map cfg, String branch) {
        String name = (branch ?: '').replaceFirst(/^origin\//, '')   // plain Pipeline jobs report 'origin/main'
        String environment = ''
        if (name == cfg.prodBranch) {
            environment = 'prod'
        } else if (name == cfg.devBranch) {
            environment = 'dev'
        }
        return (environment && targetsFor(cfg, environment)) ? environment : ''
    }

    /** All deploy targets for one environment, in the order they were configured. */
    @NonCPS
    static List<Map> targetsFor(Map cfg, String environment) {
        return cfg.deployTargets.findAll { it.environment == environment }
    }

    /** Distinct overlay folders used by any target; the Lint stage validates each one. */
    @NonCPS
    static List<String> overlays(Map cfg) {
        return cfg.deployTargets.collect { it.overlay }.unique()
    }

    /** Human-readable "cluster/namespace" list for logs and notifications. */
    @NonCPS
    static String describe(List<Map> targets) {
        return targets.collect { "${it.cluster}/${it.namespace}" }.join(', ')
    }

    // ---------------------------------------------------------------- helpers

    @NonCPS
    private static Map validateTarget(Object raw, int index, Object defaultTimeout, List<String> errors) {
        String where = "deployTargets[${index}]"
        if (!(raw instanceof Map)) {
            errors << "${where} must be a map, got '${raw}'.".toString()
            return [:]
        }
        Map target = new LinkedHashMap(raw as Map)
        target.keySet().findAll { !(it in TARGET_KEYS) }.each { key ->
            errors << "${where}: unknown key '${key}'. Valid keys: ${TARGET_KEYS.join(', ')}".toString()
        }
        if (!(target.environment in ENVIRONMENTS)) {
            errors << "${where}: 'environment' must be one of ${ENVIRONMENTS}, got '${target.environment}'.".toString()
        }
        requireDnsLabel(target, 'namespace', errors, where)
        ['cluster', 'credentialsId'].each { key ->
            if (!target[key]) {
                errors << "${where}: missing '${key}'.".toString()
            }
        }
        // Defaults: overlay folder named after the environment, global rollout timeout.
        target.overlay = target.overlay ?: "k8s/overlays/${target.environment}".toString()
        target.rolloutTimeout = target.rolloutTimeout ?: defaultTimeout
        validateDuration(target.rolloutTimeout, "${where}.rolloutTimeout", errors)
        return target
    }

    @NonCPS
    private static void requireDnsLabel(Map map, String key, List<String> errors, String where = '') {
        String prefix = where ? "${where}: " : ''
        Object value = map[key]
        if (!value) {
            errors << "${prefix}missing required key '${key}'.".toString()
        } else if (!(value.toString() ==~ DNS_LABEL) || value.toString().length() > 63) {
            errors << "${prefix}'${key}' value '${value}' is not a valid Kubernetes name (lowercase letters, digits and '-', max 63 chars).".toString()
        }
    }

    @NonCPS
    private static void validateDuration(Object value, String key, List<String> errors) {
        if (!(value?.toString() ==~ /\d+[smh]/)) {
            errors << "'${key}' must look like '90s', '5m' or '1h', got '${value}'.".toString()
        }
    }
}
