package ai.platon.pulsar.basic

import ai.platon.pulsar.skeleton.crawl.component.FetchComponent
import ai.platon.pulsar.skeleton.crawl.component.LoadComponent
import kotlin.test.*
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertNotNull
import org.junit.jupiter.api.DisplayName

class TestRequiredComponents: TestBase() {

    @Autowired
    lateinit var loadComponent: LoadComponent

    @Autowired
    lateinit var fetchComponent: FetchComponent

    @Test
        @DisplayName("When AmazonCrawler started then coreMetrics is working")
    fun whenAmazonCrawlerStartedThenCoreMetricsIsWorking() {
        assertNotNull(fetchComponent.coreMetrics)
        assertNotNull(loadComponent.parseComponent)
        assertNotNull(loadComponent.statusTracker)
    }
}
