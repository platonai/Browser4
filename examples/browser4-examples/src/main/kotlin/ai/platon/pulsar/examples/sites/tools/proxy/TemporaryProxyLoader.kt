package ai.platon.pulsar.examples.sites.tools.proxy

import ai.platon.pulsar.browser.common.BrowserSettings
import ai.platon.pulsar.common.DateTimes
import ai.platon.pulsar.common.NetUtil
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.proxy.ProxyEntry
import ai.platon.pulsar.common.proxy.ProxyPool
import java.net.Proxy

class TemporaryProxyLoader(
    private val proxyPool: ProxyPool,
) {
    private val logger = getLogger(this)

    fun loadProxies() {
        logger.warn("!!! This is a temporary solution to load proxies !!!")

        // only works before 2023-08-25
        // # IP:PORT:USER:PASS
        val proxyString = """
# SOCKS5 - (port 12324)
# IP:PORT:USER:PASS
//146.247.127.238:12324:14a678fa9996c:505721cc2c
//191.96.34.9:12324:14a678fa9996c:505721cc2c
//185.158.105.182:12324:14a678fa9996c:505721cc2c
//194.121.51.251:12324:14a678fa9996c:505721cc2c
//152.89.0.179:12324:14a678fa9996c:505721cc2c
192.168.56.1:10808:abc:abc
//127.0.0.1:10808:abc:abc
        """.trimIndent()

        val proxies = proxyString
            .split("\n").asSequence()
            .map { it.trim() }
            .filter { it.trim().matches("^\\d+.+".toRegex()) }
            .map { it.split(":").map { it.trim() } }
            .filter { it.size == 4 }
            .map { ProxyEntry(it[0], it[1].toInt(), username = it[2], password = it[3]) }
            .onEach { it.type = Proxy.Type.SOCKS }
            .onEach { it.declaredTTL = DateTimes.doomsday }
            .toMutableList()

        if (proxies.isEmpty()) {
            logger.info("No proxy available")
            return
        }

        proxies.forEach { proxy ->
            if (!NetUtil.testTcpNetwork(proxy.host, proxy.port)) {
                logger.info("Proxy not available: {}", proxy)
                return
            }
        }

        proxies.forEach {
            proxyPool.offer(it)
            // ensure enough proxies
            proxyPool.offer(it)
        }

        logger.info("There are {} proxies in pool", proxyPool.size)
    }
}
