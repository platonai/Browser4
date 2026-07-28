package ai.platon.pulsar.rest.api

import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.test.TestUrls
import org.junit.jupiter.api.Assumptions

object TestHelper {
    val session = PulsarContexts.getOrCreateSession()

    // Using mock EC server URLs instead of real Amazon URLs (ports resolved dynamically)
    val MOCK_PRODUCT_LIST_URL get() = TestUrls.MOCK_PRODUCT_LIST_URL

    val MOCK_PRODUCT_DETAIL_URL get() = TestUrls.MOCK_PRODUCT_DETAIL_URL

    suspend fun ensurePage(url: String) {
        val pageRequirement = { page: WebPage -> page.protocolStatus.isSuccess && page.persistedContentLength > 8000 }
        val page = session.load(url).takeIf(pageRequirement) ?: session.load(url, "-refresh")

        Assumptions.assumeTrue(page.protocolStatus.isSuccess)
        Assumptions.assumeTrue(page.contentLength > 0)
        if (page.isFetched) {
            Assumptions.assumeTrue(page.persistedContentLength > 0)
        }
    }
}
