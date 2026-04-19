package ai.platon.pulsar.basic.crawl

import ai.platon.pulsar.basic.MockDegeneratedListenableHyperlink
import ai.platon.pulsar.basic.MockListenableHyperlink
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.basic.TestBase
import org.junit.jupiter.api.Tag
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName

/**
 * Test event handlers.
 *
 * The test cases are passed when run separately, but are failed when running in batch mode in linux
 * using the following command:
 *
 * ```kotlin
 * mvn -X -pl browser4-tests
 * ```
 *
 * It seems that await() never returns, and the test cases are blocked.
 * TODO: Investigate the root cause of the issue.
 */
@Tag("BatchTestFailed")
@Ignore("BatchTestFailed, run this test separately and investigate the root cause of the issue")
class TestEventHandlers : TestBase() {

    @Test
    fun whenLoadAListenableLink_ThenEventsAreTriggered() {
        logger.info("Testing - whenLoadAListenableLink_ThenEventsAreTriggered")

        val url = MockListenableHyperlink("https://www.amazon.com")
        context.submit(url).await()
        url.await()
        assertTrue(url.isDone())

        url.triggeredEvents.forEach {
            printlnPro(it)
        }

        assertContentEquals(url.expectedEvents, url.triggeredEvents)

        logger.info("Tested - whenLoadAListenableLink_ThenEventsAreTriggered")
    }

    @Test
        @DisplayName("When load degenerated link then load event is performed")
    fun whenLoadDegeneratedLinkThenLoadEventIsPerformed() {
        logger.info("Testing - whenLoadDegeneratedLink_ThenEventsAreTriggered")

        val url = MockDegeneratedListenableHyperlink()
        context.submit(url).await()
        url.await()

        url.triggeredEvents.forEach {
            printlnPro(it)
        }

        assertContentEquals(url.expectedEvents, url.triggeredEvents)

        logger.info("Tested - whenLoadDegeneratedLink_ThenEventsAreTriggered")
    }
}

