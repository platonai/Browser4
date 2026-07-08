package ai.platon.pulsar.pdk.testplugin.config

import ai.platon.pulsar.pdk.testplugin.integration.TestBrowseEventHandler
import ai.platon.pulsar.pdk.testplugin.integration.TestLoadEventHandler
import ai.platon.pulsar.skeleton.plugin.BrowseEventMount
import ai.platon.pulsar.skeleton.plugin.CrawlEventMount
import ai.platon.pulsar.skeleton.plugin.LoadEventMount
import ai.platon.pulsar.skeleton.event.BrowseEventHandlers
import ai.platon.pulsar.skeleton.event.CrawlEventHandlers
import ai.platon.pulsar.skeleton.event.LoadEventHandlers
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy

/**
 * Reference auto-configuration that demonstrates all three event-phase mount points.
 *
 * This is the main entry point for the test plugin. When the plugin JAR is placed
 * in the plugins/ directory and the application restarts:
 *
 * 1. Spring Boot discovers this class via AutoConfiguration.imports
 * 2. PluginManager finds the PluginMount beans
 * 3. Each mount's configure*Handlers() method is called to wire event handlers
 */
@AutoConfiguration
@Lazy
open class TestPluginAutoConfiguration : BrowseEventMount, LoadEventMount, CrawlEventMount {

    override fun configureBrowseHandlers(handlers: BrowseEventHandlers) {
        handlers.onDocumentSteady.addLast { page, driver ->
            // Mark: test plugin onDocumentSteady handler
        }
    }

    override fun configureLoadHandlers(handlers: LoadEventHandlers) {
        handlers.onHTMLDocumentParsed.addLast { page, doc ->
            // Mark: test plugin onHTMLDocumentParsed handler
        }
    }

    override fun configureCrawlHandlers(handlers: CrawlEventHandlers) {
        handlers.onWillLoad.addLast { url ->
            // Mark: test plugin onWillLoad handler (pass through)
            url
        }
    }

    @Bean
    open fun testBrowseEventHandler(): TestBrowseEventHandler = TestBrowseEventHandler()

    @Bean
    open fun testLoadEventHandler(): TestLoadEventHandler = TestLoadEventHandler()
}
