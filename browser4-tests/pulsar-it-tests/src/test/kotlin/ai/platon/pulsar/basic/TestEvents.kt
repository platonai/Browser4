package ai.platon.pulsar.basic

import ai.platon.pulsar.skeleton.common.persist.ext.options
import ai.platon.pulsar.skeleton.workflow.common.url.StatefulListenableHyperlink
import ai.platon.pulsar.skeleton.workflow.component.FetchComponent
import org.junit.jupiter.api.DisplayName
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.*

class TestEvents : TestBase() {

    @Autowired
    lateinit var fetchComponent: FetchComponent

    @BeforeTest
    fun setup() {
        val metrics = fetchComponent.coreMetrics
        assertNotNull(metrics)

        metrics.fetchTasks.mark(-metrics.fetchTasks.count)
        metrics.successFetchTasks.mark(-metrics.successFetchTasks.count)
        metrics.persists.reset()
    }

    @Test
    @DisplayName("When a page is fetched then events are fired and metrics are recorded")
    fun whenAPageIsFetchedThenEventsAreFiredAndMetricsAreRecorded() {
        val metrics = fetchComponent.coreMetrics
        assertNotNull(metrics)

        val url = "https://www.amazon.com/Best-Sellers-Beauty/zgbs/beauty"
        val hyperlink = StatefulListenableHyperlink(url, "", args = "-i 0s")

        val firedEvents = mutableListOf<String>()
        val eventHandler = hyperlink.eventHandlers.loadEventHandlers
        eventHandler.apply {
            onWillLoad.addLast { _ ->
                firedEvents.add("onBeforeLoad")
                assertEquals(0, metrics.fetchTasks.count)
                null
            }

            onFetched.addLast { page ->
                firedEvents.add("onAfterFetch")
                assertEquals(1, metrics.fetchTasks.count)
                assertEquals(1, metrics.successFetchTasks.count)
                // assertEquals(0, metrics.persistContentMBytes.counter.count)
            }

            onLoaded.addLast { page ->
                firedEvents.add("onAfterLoad")

                assertTrue { page.protocolStatus.isSuccess }
                assertTrue { page.isContentUpdated }

                assertTrue { page.options.persist }

                assertEquals(1, metrics.fetchTasks.count)
                assertEquals(1, metrics.successFetchTasks.count)
//                assertEquals(1, metrics.persists.counter.count)
//                assertEquals(0, metrics.persistContentMBytes.counter.count)
            }
        }

        session.load(hyperlink)

        assertTrue { "onBeforeLoad" in firedEvents }
        assertTrue { "onAfterFetch" in firedEvents }
        assertTrue { "onAfterLoad" in firedEvents }
    }
}
