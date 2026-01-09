package ai.platon.pulsar.examples.sites.topEc.chinese.login.tmall

import ai.platon.pulsar.examples.sites.topEc.chinese.login.taobao.TaobaoLoginHandler
import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.skeleton.session.PulsarSession

fun main() {
    val portalUrl = "https://list.tmall.com/search_product.htm?q=大家电"
    val args = "-i 1s -ii 5m -ol a[href~=detail] -ignoreFailure"

    // login parameters
    val username = System.getenv("PULSAR_TAOBAO_USERNAME") ?: "MustFallUsername"
    val password = System.getenv("PULSAR_TAOBAO_PASSWORD") ?: "MustFallPassword"

    val session: PulsarSession = PulsarContexts.createSession()

    val options = session.options(args)
    val loginHandler = TaobaoLoginHandler(username, password, warnUpUrl = portalUrl)
    options.eventHandlers.browseEventHandlers.onBrowserLaunched.addLast(loginHandler)

    session.loadOutPages(portalUrl, options)

    PulsarContexts.await()
}
