package ai.platon.pulsar.skeleton.workflow.common.url

import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.model.GoraWebPage
import ai.platon.pulsar.skeleton.common.urls.NormURL
import ai.platon.pulsar.skeleton.event.WebPageHandler
import java.util.concurrent.TimeUnit

internal class CompleteWebPageHyperlinkHandler(val link: CompletableListenableHyperlink<WebPage>): WebPageHandler() {
    override fun invoke(page: WebPage) {
        link.complete(page)
        link.eventHandlers.loadEventHandlers.onLoaded.remove(this)

        // TODO: the following code might be better
//        if (link.eventHandlers.loadEvent.onLoaded.remove(this)) {
//            link.complete(page)
//        }
    }
}

/**
 * Create a completable listenable hyperlink
 * */
fun NormURL.toCompletableListenableHyperlink(): CompletableListenableHyperlink<WebPage> {
    val link = CompletableListenableHyperlink<WebPage>(urlString, args = args, href = hrefSpec)

    link.eventHandlers.loadEventHandlers.onLoaded.addLast(CompleteWebPageHyperlinkHandler(link))
    options.rawEvent?.let { link.eventHandlers.chain(it) }

    link.completeOnTimeout(GoraWebPage.NIL, options.pageLoadTimeout.seconds + 1, TimeUnit.SECONDS)

    return link
}
