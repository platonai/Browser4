package ai.platon.pulsar.heavy

import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.workflow.common.url.ListenableHyperlink
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Test the performance of Browser4, every test url will be a local file, so the performance is not affected by network latency.
 *
 * The test first generate 10000 temporary files in the local file system, and then run the test.
 *
 * Notice: before we load the local files using Browser4, we have to transform the paths using [URLUtils.pathToLocalURL].
 * */
@Tag("HeavyTest")
class MassiveUrlPoolTest: MassiveTestBase() {

    @Test
    fun test() {
        Assumptions.assumeTrue { testFileCount > 0 }

        PulsarSettings.maxBrowserContexts(4).maxOpenTabs(8)

        val links = testPaths.asSequence().map { URLUtils.pathToLocalURL(it) }.map { createHyperlink(it) }

        links.forEach {
            session.submit(it, "-refresh")
        }

        session.context.await()
    }

    private fun createHyperlink(url: String): ListenableHyperlink {
        val link = ListenableHyperlink(url, "")
        val le = link.eventHandlers.loadEventHandlers
        val be = link.eventHandlers.browseEventHandlers

        be.onDocumentFullyLoaded.addLast { page, driver ->
            val text = driver.selectFirstTextOrNull("body")
            text
        }

        return link
    }
}
