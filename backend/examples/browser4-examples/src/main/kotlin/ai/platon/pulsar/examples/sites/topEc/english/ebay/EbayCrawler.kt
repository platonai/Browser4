package ai.platon.pulsar.examples.sites.topEc.english.ebay

import ai.platon.pulsar.skeleton.context.PulsarContexts

fun main() {
    val portalUrl = "https://www.ebay.com/b/Dolce-Gabbana-Bags-Handbags-for-Women/169291/bn_716146"
    val args = "-i 1s -ii 5d -ol a[href~=itm] -ignoreFailure"

    val session = PulsarContexts.createSession()
    val pages = session.loadOutPages(portalUrl, args)

    pages.forEach {
        val document = session.parse(it)
        println(document.title)
    }
}
