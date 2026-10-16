import org.devops.PipelineConfig
import org.junit.Test

import static groovy.test.GroovyAssert.shouldFail

/**
 * Tests for src/org/devops/PipelineConfig.groovy: defaults, validation messages
 * and the branch -> environment rules.
 */
class PipelineConfigTest {

    /** A complete, valid config, as repos.yaml + Jenkinsfile would produce it. */
    static Map validConfig() {
        return [
            appName      : 'demo-app',
            registry     : 'localhost:5001',
            deployTargets: [
                [environment: 'dev', cluster: 'kind', namespace: 'demo-dev', credentialsId: 'kubeconfig-dev'],
                [environment: 'prod', cluster: 'kind', namespace: 'demo-prod', credentialsId: 'kubeconfig-prod'],
            ],
        ]
    }

    @Test
    void 'fills in defaults for keys that were not given'() {
        Map cfg = PipelineConfig.resolve(validConfig())
        assert cfg.devBranch == 'develop'
        assert cfg.prodBranch == 'main'
        assert cfg.trivySeverity == 'HIGH,CRITICAL'
        assert cfg.rolloutTimeout == '120s'
        assert cfg.deployTargets[0].overlay == 'k8s/overlays/dev'
        assert cfg.deployTargets[1].overlay == 'k8s/overlays/prod'
        assert cfg.deployTargets[0].rolloutTimeout == '120s'
    }

    @Test
    void 'values given by the user override the defaults'() {
        Map cfg = PipelineConfig.resolve(validConfig() + [prodBranch: 'release', rolloutTimeout: '5m'])
        assert cfg.prodBranch == 'release'
        assert cfg.deployTargets[0].rolloutTimeout == '5m'
    }

    @Test
    void 'defaults are not shared between calls'() {
        Map first = PipelineConfig.resolve(validConfig())
        first.deployTargets.clear()
        assert PipelineConfig.defaults().deployTargets == []
    }

    @Test
    void 'reports every missing required key in one error'() {
        def e = shouldFail(IllegalArgumentException) { PipelineConfig.resolve([:]) }
        assert e.message.contains("missing required key 'appName'")
        assert e.message.contains("Missing required key 'registry'")
    }

    @Test
    void 'rejects unknown keys so typos are caught'() {
        def e = shouldFail(IllegalArgumentException) {
            PipelineConfig.resolve(validConfig() + [regsitry: 'oops'])
        }
        assert e.message.contains("Unknown key 'regsitry'")
        assert e.message.contains('Valid keys:')
    }

    @Test
    void 'rejects app names that are not valid Kubernetes names'() {
        def e = shouldFail(IllegalArgumentException) {
            PipelineConfig.resolve(validConfig() + [appName: 'Demo_App'])
        }
        assert e.message.contains("'appName' value 'Demo_App' is not a valid Kubernetes name")
    }

    @Test
    void 'rejects a registry written as a URL'() {
        def e = shouldFail(IllegalArgumentException) {
            PipelineConfig.resolve(validConfig() + [registry: 'https://ghcr.io/me/'])
        }
        assert e.message.contains('without a scheme or trailing slash')
    }

    @Test
    void 'validates every deploy target and names the bad one'() {
        Map config = validConfig()
        config.deployTargets = [
            [environment: 'staging', cluster: 'kind', namespace: 'Bad_NS', credentialsId: ''],
        ]
        def e = shouldFail(IllegalArgumentException) { PipelineConfig.resolve(config) }
        assert e.message.contains("deployTargets[0]: 'environment' must be one of [dev, prod], got 'staging'")
        assert e.message.contains("deployTargets[0]: 'namespace' value 'Bad_NS' is not a valid Kubernetes name")
        assert e.message.contains("deployTargets[0]: missing 'credentialsId'")
    }

    @Test
    void 'rejects unknown keys inside a deploy target'() {
        Map config = validConfig()
        config.deployTargets[0].kubeconfig = 'oops'
        def e = shouldFail(IllegalArgumentException) { PipelineConfig.resolve(config) }
        assert e.message.contains("deployTargets[0]: unknown key 'kubeconfig'")
    }

    @Test
    void 'rejects badly formatted timeouts and severities'() {
        def e = shouldFail(IllegalArgumentException) {
            PipelineConfig.resolve(validConfig() + [rolloutTimeout: '2 minutes', trivySeverity: 'HIGH,SEVERE',
                                                    approvalTimeoutMinutes: 0])
        }
        assert e.message.contains("'rolloutTimeout' must look like '90s', '5m' or '1h'")
        assert e.message.contains('trivySeverity contains [SEVERE]')
        assert e.message.contains("'approvalTimeoutMinutes' must be a positive whole number")
    }

    @Test
    void 'maps branches to environments'() {
        Map cfg = PipelineConfig.resolve(validConfig())
        assert PipelineConfig.deployEnvironment(cfg, 'develop') == 'dev'
        assert PipelineConfig.deployEnvironment(cfg, 'main') == 'prod'
        assert PipelineConfig.deployEnvironment(cfg, 'origin/main') == 'prod'    // plain Pipeline job
        assert PipelineConfig.deployEnvironment(cfg, 'feature/login') == ''       // build + test only
        assert PipelineConfig.deployEnvironment(cfg, 'PR-12') == ''
        assert PipelineConfig.deployEnvironment(cfg, null) == ''
    }

    @Test
    void 'a branch whose environment has no targets is build-and-test only'() {
        Map config = validConfig()
        config.deployTargets = config.deployTargets.findAll { it.environment == 'prod' }
        Map cfg = PipelineConfig.resolve(config)
        assert PipelineConfig.deployEnvironment(cfg, 'develop') == ''
        assert PipelineConfig.deployEnvironment(cfg, 'main') == 'prod'
    }

    @Test
    void 'supports several clusters for one environment'() {
        Map config = validConfig()
        config.deployTargets << [environment: 'prod', cluster: 'prod-us', namespace: 'demo', credentialsId: 'kubeconfig-us']
        Map cfg = PipelineConfig.resolve(config)
        assert PipelineConfig.targetsFor(cfg, 'prod')*.cluster == ['kind', 'prod-us']
        assert PipelineConfig.describe(PipelineConfig.targetsFor(cfg, 'prod')) == 'kind/demo-prod, prod-us/demo'
        assert PipelineConfig.overlays(cfg) == ['k8s/overlays/dev', 'k8s/overlays/prod']
    }
}
