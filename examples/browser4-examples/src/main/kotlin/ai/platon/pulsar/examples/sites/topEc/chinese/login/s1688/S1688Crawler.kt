package ai.platon.pulsar.examples.sites.topEc.chinese.login.s1688

import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.skeleton.crawl.event.impl.LoginHandler
import ai.platon.pulsar.skeleton.session.PulsarSession

class S1688Crawler(
    val portalUrl: String = "https://list.tmall.com/search_product.htm?q=大家电",
    val args: String = "-i 1s -ii 5m -ol a[href~=detail] -ignoreFailure",
    val session: PulsarSession = PulsarContexts.createSession()
) {
    // login parameters
    val loginUrl = "https://login.taobao.com"
    val activateSelector = ".password-login-tab-item"
    val usernameSelector = "input#fm-login-id"
    val username = System.getenv("PULSAR_TAOBAO_USERNAME") ?: "MustFallUsername"
    val passwordSelector = "input#fm-login-password"
    val password = System.getenv("PULSAR_TAOBAO_PASSWORD") ?: "MustFallPassword"
    val submitSelector = "button[type=submit]"

    fun crawl() {
        val options = session.options(args)

        val loginHandler = LoginHandler(loginUrl,
            usernameSelector, username, passwordSelector, password, submitSelector, activateSelector)
        options.eventHandlers.browseEventHandlers.onBrowserLaunched.addLast(loginHandler)

        session.loadOutPages(portalUrl, options)
    }
}

fun main() {
    S1688Crawler().crawl()
    PulsarContexts.await()
}
