package ai.platon.pulsar.skeleton.workflow.fetch

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.persist.model.GoraWebPage
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FetchResultTest {
    @Test
    fun `failed result retains the original exception`() {
        val page = GoraWebPage.newWebPage(
            "https://example.com",
            ImmutableConfig().toVolatileConfig()
        )
        val task = FetchTask.create(page)
        val failure = IllegalStateException("privacy context failed")

        val result = FetchResult.failed(task, failure)

        assertTrue(result.status.isFailed)
        assertSame(failure, result.exception)
    }
}
