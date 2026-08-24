package ai.platon.pulsar.loop.impl

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.urls.PlainUrl
import ai.platon.pulsar.common.urls.UrlAware
import ai.platon.pulsar.core.api.WebPage
import ai.platon.pulsar.persist.ProtocolStatus
import ai.platon.pulsar.persist.RetryScope
import ai.platon.pulsar.persist.metadata.ProtocolStatusCodes
import ai.platon.pulsar.persist.model.GoraWebPage
import ai.platon.pulsar.skeleton.context.support.AbstractPulsarContext
import ai.platon.pulsar.skeleton.session.BasicPulsarSession
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Regression tests for issue #577: the streaming task loop retried tasks
 * without any bound because `page.fetchRetries` was never advanced in the
 * streaming path (the WebDB fetch schedule that increments it is not used by
 * [StreamingTaskRunner]). A PrivacyException-driven crawl retry therefore ran
 * forever ("Trying 1th 43s later" in the logs).
 */
class StreamingTaskRunnerRetryTest {

    private val context = mockk<AbstractPulsarContext>(relaxed = true)
    private val session = BasicPulsarSession(context, ImmutableConfig().toVolatileConfig())
    private val runner = StreamingTaskRunner(emptySequence<UrlAware>(), session, autoClose = false)

    private fun invokeHandleRetry0(url: UrlAware, page: WebPage?) {
        val method = StreamingTaskRunner::class.java
            .getDeclaredMethod("handleRetry0", UrlAware::class.java, WebPage::class.java)
        method.isAccessible = true
        method.invoke(runner, url, page)
    }

    private fun retryPage(url: String = "https://example.com"): GoraWebPage {
        val page = GoraWebPage.newWebPage(url, ImmutableConfig().toVolatileConfig())
        page.protocolStatus = ProtocolStatus.retry(RetryScope.CRAWL, "PrivacyException")
        return page
    }

    @Test
    fun `retry within budget advances the retry counter`() {
        val page = retryPage()
        page.fetchRetries = 0
        page.maxRetries = 3

        invokeHandleRetry0(PlainUrl("https://example.com"), page)

        assertEquals(1, page.fetchRetries, "The streaming loop must advance the retry counter")
        assertTrue(page.protocolStatus.isRetry)
    }

    @Test
    fun `retry budget exhaustion stops the retry loop and marks the page as failed`() {
        val page = retryPage()
        page.fetchRetries = 3
        page.maxRetries = 3

        invokeHandleRetry0(PlainUrl("https://example.com"), page)

        // No more retries: the page must reach a terminal (failed) state so
        // downstream consumers can report the failure instead of retrying forever.
        assertTrue(
            page.protocolStatus.isFailed,
            "Expected a failed protocol status but got ${page.protocolStatus}"
        )
        assertEquals(ProtocolStatusCodes.SC_REQUEST_TIMEOUT, page.protocolStatus.minorCode)
    }

    @Test
    fun `maxRetries fallback protects pages without an explicit budget`() {
        val page = retryPage()
        page.fetchRetries = 3
        page.maxRetries = 0 // not set — must fall back to a sane default (3)

        invokeHandleRetry0(PlainUrl("https://example.com"), page)

        assertTrue(page.protocolStatus.isFailed, "A page without maxRetries must still be bounded")
    }

    @Test
    fun `null page retry is bounded by the link retry budget`() {
        val link = PlainUrl("https://example.com/null-page")

        // No page at all: the first two attempts are re-queued...
        invokeHandleRetry0(link, null)
        invokeHandleRetry0(link, null)
        invokeHandleRetry0(link, null)

        // ... the fourth attempt (3 = default budget) stops the loop.
        val attempts = readNullPageRetryCount(runner, link)
        assertEquals(3, attempts)
    }

    private fun readNullPageRetryCount(runner: StreamingTaskRunner, url: UrlAware): Int? {
        val field = StreamingTaskRunner::class.java.getDeclaredField("nullPageRetryCounts")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(runner) as MutableMap<UrlAware, Int>
        return map[url]
    }
}
