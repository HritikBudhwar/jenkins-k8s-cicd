import org.devops.ImageTag
import org.junit.Test

import static groovy.test.GroovyAssert.shouldFail

/** Tests for src/org/devops/ImageTag.groovy: immutable <sha>-<build> tags. */
class ImageTagTest {

    @Test
    void 'builds the tag from the short sha and the build number'() {
        assert ImageTag.from('3F2A9C1D0E5B6A7C8D9E0F1A2B3C4D5E6F7A8B9C', 17) == '3f2a9c1-17'
        assert ImageTag.from('abc1234', '5') == 'abc1234-5'
    }

    @Test
    void 'rejects values that are not a commit sha'() {
        shouldFail(IllegalArgumentException) { ImageTag.from('main', 1) }
        shouldFail(IllegalArgumentException) { ImageTag.from(null, 1) }
    }

    @Test
    void 'rejects a missing build number'() {
        shouldFail(IllegalArgumentException) { ImageTag.from('abc1234', null) }
    }

    @Test
    void 'builds the full image reference'() {
        assert ImageTag.fullImage('localhost:5001', 'demo-app', 'abc1234-5') == 'localhost:5001/demo-app:abc1234-5'
    }

    @Test
    void 'never allows latest'() {
        def e = shouldFail(IllegalArgumentException) { ImageTag.fullImage('localhost:5001', 'demo-app', 'latest') }
        assert e.message.contains('immutable')
    }
}
