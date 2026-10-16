import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.Before
import org.junit.Test

import static groovy.test.GroovyAssert.shouldFail

/**
 * Tests for vars/deployToK8s.groovy, mainly the automatic rollback logic.
 *
 * JenkinsPipelineUnit loads the real step script, and Jenkins steps are replaced
 * by mocks: every `sh` command is recorded in `commands`, and `exitCode` decides
 * what each command "returns", so we can simulate a failed rollout.
 */
class DeployToK8sTest extends BasePipelineTest {

    /** Thrown by the mocked `error` step, like a failed Jenkins build. */
    static class BuildFailed extends RuntimeException {
        BuildFailed(String message) { super(message) }
    }

    List<String> commands = []
    Closure<Integer> exitCode = { String cmd -> 0 }      // default: every command succeeds
    boolean credentialExists = true
    Map env = [:]
    def step

    Map cfg = [appName: 'demo-app']
    Map target = [environment: 'dev', cluster: 'kind', namespace: 'demo-dev',
                  credentialsId: 'kubeconfig-dev', overlay: 'k8s/overlays/dev', rolloutTimeout: '120s']

    @Before
    @Override
    void setUp() {
        super.setUp()
        env = [IMAGE: 'localhost:5001/demo-app:abc1234-7', STAGE_NAME: 'Deploy to Dev']
        binding.setVariable('env', env)

        // sh: record the command, then succeed or fail as exitCode decides.
        helper.registerAllowedMethod('sh', [Map]) { Map args ->
            commands << args.script.trim()
            int code = exitCode(args.script)
            if (args.returnStatus) {
                return code
            }
            if (code != 0) {
                throw new BuildFailed("script returned exit code ${code}")
            }
            return null
        }
        helper.registerAllowedMethod('sh', [String]) { String script ->
            commands << script.trim()
            if (exitCode(script) != 0) {
                throw new BuildFailed('script returned exit code 1')
            }
        }
        helper.registerAllowedMethod('error', [String]) { String msg -> throw new BuildFailed(msg) }
        helper.registerAllowedMethod('withKubeConfig', [Map, Closure]) { Map args, Closure body -> body() }
        helper.registerAllowedMethod('file', [Map]) { Map args -> args }
        helper.registerAllowedMethod('withCredentials', [List, Closure]) { List creds, Closure body ->
            if (!credentialExists) {
                throw new IllegalStateException("Could not find credentials entry with ID '${creds[0].credentialsId}'")
            }
            body()
        }

        // deployToK8s calls failPipeline: use the real script, so we also test that it records the reason.
        def failPipeline = loadScript('../vars/failPipeline.groovy')
        helper.registerAllowedMethod('failPipeline', [String]) { String reason -> failPipeline.call(reason) }

        step = loadScript('../vars/deployToK8s.groovy')
    }

    @Test
    void 'successful deploy sets the image, applies and waits, without rolling back'() {
        step.call(cfg, target)

        assert commands.contains('kustomize edit set image demo-app=localhost:5001/demo-app:abc1234-7')
        assert commands.contains('kustomize edit set namespace demo-dev')
        assert commands.any { it.startsWith('kubectl apply -n demo-dev -f reports/manifest-dev-kind-demo-dev.yaml') }
        assert commands.any { it.contains('rollout status deployment/demo-app --timeout=120s') }
        assert !commands.any { it.contains('rollout undo') }
    }

    @Test
    void 'failed rollout is rolled back automatically and the build fails with a clear message'() {
        int rolloutStatusCalls = 0
        exitCode = { String cmd ->
            if (cmd.contains('rollout status')) {
                rolloutStatusCalls++
                return rolloutStatusCalls == 1 ? 1 : 0     // new version times out, old version recovers
            }
            return 0
        }

        def e = shouldFail(BuildFailed) { step.call(cfg, target) }

        assert commands.any { it == 'kubectl -n demo-dev rollout undo deployment/demo-app' }
        assert e.message.contains('AUTOMATICALLY ROLLED BACK')
        assert e.message.contains('kind/demo-dev')
        assert env.FAILURE_REASON == e.message           // recorded for the final notification
        assert env.FAILED_STAGE == 'Deploy to Dev'
        // The rollback happens AFTER the failed rollout, never before.
        int firstRollout = commands.findIndexOf { it.contains('rollout status') }
        assert commands.findIndexOf { it.contains('rollout undo') } > firstRollout
    }

    @Test
    void 'diagnostics are printed before rolling back'() {
        int rolloutStatusCalls = 0
        exitCode = { String cmd -> cmd.contains('rollout status') && ++rolloutStatusCalls == 1 ? 1 : 0 }

        shouldFail(BuildFailed) { step.call(cfg, target) }

        int diagnostics = commands.findIndexOf { it.contains('get events') }
        assert diagnostics >= 0
        assert diagnostics < commands.findIndexOf { it.contains('rollout undo') }
    }

    @Test
    void 'reports when the rollback itself fails as on a first deployment'() {
        exitCode = { String cmd -> (cmd.contains('rollout status') || cmd.contains('rollout undo')) ? 1 : 0 }

        def e = shouldFail(BuildFailed) { step.call(cfg, target) }

        assert e.message.contains('automatic rollback did not succeed')
        assert e.message.contains('kubectl -n demo-dev rollout history deployment/demo-app')
    }

    @Test
    void 'failed apply does not try to roll back'() {
        exitCode = { String cmd -> cmd.startsWith('kubectl apply') ? 1 : 0 }

        def e = shouldFail(BuildFailed) { step.call(cfg, target) }

        assert e.message.contains('kubectl apply failed on kind/demo-dev')
        assert !commands.any { it.contains('rollout') }
    }

    @Test
    void 'missing kubeconfig credential fails before touching the cluster'() {
        credentialExists = false

        def e = shouldFail(BuildFailed) { step.call(cfg, target) }

        assert e.message.contains("Kubeconfig credential 'kubeconfig-dev'")
        assert e.message.contains('docs/SETUP.md')
        assert !commands.any { it.startsWith('kubectl') || it.startsWith('kustomize') }
    }

    @Test
    void 'rollback() undoes the deployment and waits for the old revision'() {
        assert step.rollback(cfg, target) == true
        assert commands == ['kubectl -n demo-dev rollout undo deployment/demo-app',
                            'kubectl -n demo-dev rollout status deployment/demo-app --timeout=120s']
    }
}
