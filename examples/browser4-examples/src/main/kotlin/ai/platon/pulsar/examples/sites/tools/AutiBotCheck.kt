package ai.platon.pulsar.examples.sites.tools

import ai.platon.pulsar.common.proxy.ProxyPool
import ai.platon.pulsar.examples.sites.tools.proxy.TemporaryProxyLoader
import ai.platon.pulsar.ql.context.SQLContexts

/**
 * Bot check blogs and tools:
 *
 * https://amiunique.org/
 * https://gologin.com/check-browser
 * https://browserleaks.com/canvas
 * https://browserleaks.com/
 * https://privacybee.com/blog/browser-fingerprinting/
 * */
fun main() {
    val urls = """
http://www.baidu.com
https://bot.sannysoft.com/
https://intoli.com/blog/making-chrome-headless-undetectable/chrome-headless-test.html
https://arh.antoinevastel.com/bots/areyouheadless
        """.trimIndent().split("\n")
        .map { it.trim() }
        .filter { it.startsWith("http") }
        .take(1)
    
    val session = SQLContexts.createSession()
    
    val proxyPool = session.context.getBean(ProxyPool::class)
    val proxyLoader = TemporaryProxyLoader(proxyPool)
    proxyLoader.loadProxies()
    
    urls.forEach { session.open(it) }
    
    readLine()
}
