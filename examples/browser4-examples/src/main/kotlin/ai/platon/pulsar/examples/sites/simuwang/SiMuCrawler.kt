package ai.platon.pulsar.examples.sites.simuwang

import ai.platon.pulsar.skeleton.crawl.event.impl.CloseMaskLayerHandler
import ai.platon.pulsar.skeleton.crawl.event.impl.LoginHandler
import ai.platon.pulsar.ql.context.SQLContexts

class SiMuLoginHandler(
    loginUrl: String,
    username: String,
    password: String,
    usernameSelector: String = "input[name=username]",
    passwordSelector: String = "input[type=password]",
    submitSelector: String = "button.comp-login-btn",
    warnUpUrl: String? = null,
    activateSelector: String = "button.comp-login-b2",
): LoginHandler(loginUrl,
    usernameSelector, username, passwordSelector, password,
    submitSelector, warnUpUrl, activateSelector
)

open class SiMuCrawler {
    // general parameters
    val portalUrl = "https://dc.simuwang.com/"
    val args = "-i 30s -ii 30s -ol a[href~=product] -tl 10"
    // login parameters
    val username = System.getenv("PULSAR_SIMUWANG_USERNAME") ?: "MustFallUsername"
    val password = System.getenv("PULSAR_SIMUWANG_PASSWORD") ?: "MustFallPassword"
    // mask layer handling
    val closeMaskLayerSelector = ".comp-alert-btn"

    val context = SQLContexts.create()
    val session = context.createSession()

    val loginHandler = SiMuLoginHandler(portalUrl, username, password)
    val closeMaskLayerHandler = CloseMaskLayerHandler(closeMaskLayerSelector)
    val options = session.options(args).also {
        it.eventHandlers.browseEventHandlers.onBrowserLaunched.addLast(loginHandler)
        it.eventHandlers.browseEventHandlers.onDocumentFullyLoaded.addLast(closeMaskLayerHandler)
    }

    open fun crawl() {
        // load out pages
        val pages = session.loadOutPages(portalUrl, options)
        // parse to jsoup documents
        val documents = pages.map { session.parse(it) }
        // use the documents
        // ...
        // wait for all done
        context.await()
    }
}

fun main() = SiMuCrawler().crawl()
