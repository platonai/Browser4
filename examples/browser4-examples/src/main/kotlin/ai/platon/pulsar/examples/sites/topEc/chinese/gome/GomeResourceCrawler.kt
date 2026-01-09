package ai.platon.pulsar.examples.sites.topEc.chinese.gome

import ai.platon.pulsar.skeleton.context.PulsarContexts

fun main() {
    val portalUrl = "https://item.gome.com.cn/A0008106499-pop8020999364.html"
    val url = "https://item.gome.com.cn/robots.txt"
    val args = "-refresh"

    val session = PulsarContexts.createSession()
    session.delete(url)
    val resource = session.loadResource(url, portalUrl, args)
    println(resource.protocolStatus)
    println(resource.headers)
    println(resource.contentAsString)
    
    readlnOrNull()
}
