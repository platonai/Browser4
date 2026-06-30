package ai.platon.pulsar.examples.demos.config

import ai.platon.pulsar.examples.demos.BrowseEventDemos
import ai.platon.pulsar.examples.demos.CrawlEventDemos
import ai.platon.pulsar.examples.demos.LoadEventDemos
import ai.platon.pulsar.skeleton.plugin.BrowseEventMount
import ai.platon.pulsar.skeleton.plugin.CrawlEventMount
import ai.platon.pulsar.skeleton.plugin.LoadEventMount
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy

/**
 * Spring Boot auto-configuration that exposes the demo event handlers
 * as [PluginMount] beans so [ai.platon.browser4.boot.plugin.PluginManager]
 * can discover and wire them into the global event bus.
 *
 * ## How it works
 *
 * 1. This class is listed in `META-INF/browser4-plugin.json` under
 *    `autoConfigurationClasses`.
 * 2. When the plugin JAR is placed in `plugins/` (or installed via
 *    `POST /api/plugins/install`), Spring Boot loads this configuration.
 * 3. Each `@Bean` method creates a [PluginMount] implementation.
 * 4. [PluginManager] discovers all [PluginMount] beans and calls their
 *    `configure*Handlers()` methods, wiring handlers into
 *    [ai.platon.pulsar.skeleton.event.PulsarEventBus].
 *
 * ## Plugin installation via REST API
 *
 * ```bash
 * # Install
 * curl -X POST http://localhost:8080/api/plugins/install \
 *   -F "file=@browser4-event-demos-1.0.0.jar"
 *
 * # List installed plugins
 * curl http://localhost:8080/api/plugins
 *
 * # Remove
 * curl -X DELETE http://localhost:8080/api/plugins/browser4-event-demos
 * ```
 *
 * **Note:** Installed plugins take effect after an application restart.
 */
@AutoConfiguration
@Lazy
open class EventDemosAutoConfiguration {

    /**
     * Registers 2 crawl-phase event handlers:
     * - [CrawlEventDemos.onWillLoad] — URL filtering
     * - [CrawlEventDemos.onLoaded] — result handling
     */
    @Bean(name = ["crawlEventDemosMount"])
    open fun crawlEventDemosMount(): CrawlEventMount = CrawlEventDemos()

    /**
     * Registers 9 load-phase event handlers:
     * - onNormalize, onWillLoad, onWillFetch, onFetched
     * - onWillParse, onWillParseHTMLDocument, onHTMLDocumentParsed
     * - onParsed, onLoaded
     */
    @Bean(name = ["loadEventDemosMount"])
    open fun loadEventDemosMount(): LoadEventMount = LoadEventDemos()

    /**
     * Registers 17 browse-phase event handlers:
     * - onWillLaunchBrowser → onBrowserLaunched → onWillFetch →
     *   onWillNavigate → onNavigated → onWillInteract →
     *   onWillCheckDocumentState → onDocumentFullyLoaded →
     *   onWillScroll → onDidScroll → onDocumentSteady ★ →
     *   onWillComputeFeature → onFeatureComputed →
     *   onDidInteract → onWillStopTab → onTabStopped → onFetched
     */
    @Bean(name = ["browseEventDemosMount"])
    open fun browseEventDemosMount(): BrowseEventMount = BrowseEventDemos()
}
