package ai.platon.pulsar.examples.sites.topEc.chinese.gome

import ai.platon.pulsar.skeleton.context.PulsarContexts

fun main() {
    val portalUrl = "https://list.gome.com.cn/cat10000092.html"
    val args = "-i 1s -ii 5d -ol a[href~=item] -ignoreFailure"

    val session = PulsarContexts.createSession()
    val pages = session.loadOutPages(portalUrl, args)
    val documents = pages.map { session.parse(it) }
    // do something with documents
    documents.forEach { println(it.title + " | " + it.baseURI) }
}
