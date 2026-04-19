package ai.platon.pulsar.skeleton.crawl.event

import ai.platon.pulsar.skeleton.crawl.*

abstract class AbstractLoadEventHandlers(
    override val onNormalize: UrlFilterEventHandler = UrlFilterEventHandler(),
    override val onWillLoad: UrlEventHandler = UrlEventHandler(),
    override val onWillFetch: WebPageEventHandler = WebPageEventHandler(),
    override val onFetched: WebPageEventHandler = WebPageEventHandler(),
    override val onWillParse: WebPageEventHandler = WebPageEventHandler(),
    override val onWillParseHTMLDocument: WebPageEventHandler = WebPageEventHandler(),
    override val onHTMLDocumentParsed: HTMLDocumentEventHandler = HTMLDocumentEventHandler(),
    override val onParsed: WebPageEventHandler = WebPageEventHandler(),
    override val onLoaded: WebPageEventHandler = WebPageEventHandler()
): LoadEventHandlers {

    override fun chain(other: LoadEventHandlers): AbstractLoadEventHandlers {
        onNormalize.addLast(other.onNormalize)
        onWillLoad.addLast(other.onWillLoad)
        onWillFetch.addLast(other.onWillFetch)
        onFetched.addLast(other.onFetched)
        onWillParse.addLast(other.onWillParse)
        onWillParseHTMLDocument.addLast(other.onWillParseHTMLDocument)
        onHTMLDocumentParsed.addLast(other.onHTMLDocumentParsed)
        onParsed.addLast(other.onParsed)
        onLoaded.addLast(other.onLoaded)

        return this
    }
}

abstract class AbstractCrawlEventHandlers(
    override val onWillLoad: UrlAwareEventHandler = UrlAwareEventHandler(),
    override val onLoaded: UrlAwareWebPageEventHandler = UrlAwareWebPageEventHandler()
): CrawlEventHandlers {
    override fun chain(other: CrawlEventHandlers): CrawlEventHandlers {
        onWillLoad.addLast(other.onWillLoad)
        onLoaded.addLast(other.onLoaded)
        return this
    }
}

abstract class AbstractBrowseEventHandlers(
    override val onWillLaunchBrowser: WebPageEventHandler = WebPageEventHandler(),
    override val onBrowserLaunched: WebPageWebDriverEventHandler = WebPageWebDriverEventHandler(),

    override val onWillFetch: WebPageWebDriverEventHandler = WebPageWebDriverEventHandler(),
    override val onFetched: WebPageWebDriverEventHandler = WebPageWebDriverEventHandler(),

    override val onWillNavigate: WebPageWebDriverEventHandler = WebPageWebDriverEventHandler(),
    override val onNavigated: WebPageWebDriverEventHandler = WebPageWebDriverEventHandler(),

    override val onWillInteract: WebPageWebDriverEventHandler = WebPageWebDriverEventHandler(),
    override val onDidInteract: WebPageWebDriverEventHandler = WebPageWebDriverEventHandler(),

    override val onWillScroll: WebPageWebDriverEventHandler = WebPageWebDriverEventHandler(),
    override val onDidScroll: WebPageWebDriverEventHandler = WebPageWebDriverEventHandler(),

    override val onWillCheckDocumentState: WebPageWebDriverEventHandler = WebPageWebDriverEventHandler(),
    override val onDocumentFullyLoaded: WebPageWebDriverEventHandler = WebPageWebDriverEventHandler(),

    override val onDocumentSteady: WebPageWebDriverEventHandler = WebPageWebDriverEventHandler(),

    override val onWillComputeFeature: WebPageWebDriverEventHandler = WebPageWebDriverEventHandler(),
    override val onFeatureComputed: WebPageWebDriverEventHandler = WebPageWebDriverEventHandler(),

    override val onWillStopTab: WebPageWebDriverEventHandler = WebPageWebDriverEventHandler(),
    override val onTabStopped: WebPageWebDriverEventHandler = WebPageWebDriverEventHandler()
): BrowseEventHandlers {

    override fun chain(other: BrowseEventHandlers): BrowseEventHandlers {
        onWillLaunchBrowser.addLast(other.onWillLaunchBrowser)
        onBrowserLaunched.addLast(other.onBrowserLaunched)

        onWillFetch.addLast(other.onWillFetch)
        onFetched.addLast(other.onFetched)

        onWillNavigate.addLast(other.onWillNavigate)
        onNavigated.addLast(other.onNavigated)

        onWillInteract.addLast(other.onWillInteract)
        onDidInteract.addLast(other.onDidInteract)

        onWillCheckDocumentState.addLast(other.onWillCheckDocumentState)
        onDocumentFullyLoaded.addLast(other.onDocumentFullyLoaded)

        onWillScroll.addLast(other.onWillScroll)
        onDidScroll.addLast(other.onDidScroll)

        onDocumentSteady.addLast(other.onDocumentSteady)

        onWillComputeFeature.addLast(other.onWillComputeFeature)
        onFeatureComputed.addLast(other.onFeatureComputed)

        onWillStopTab.addLast(other.onWillStopTab)
        onTabStopped.addLast(other.onTabStopped)

        return this
    }
}

abstract class AbstractPageEventHandlers(
    override var loadEventHandlers: LoadEventHandlers,
    override var browseEventHandlers: BrowseEventHandlers,
    override var crawlEventHandlers: CrawlEventHandlers
): PageEventHandlers {

    override fun chain(other: PageEventHandlers): PageEventHandlers {
        loadEventHandlers.chain(other.loadEventHandlers)
        browseEventHandlers.chain(other.browseEventHandlers)
        crawlEventHandlers.chain(other.crawlEventHandlers)
        return this
    }
}
