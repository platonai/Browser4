package ai.platon.pulsar.skeleton.common.proxy

import ai.platon.pulsar.external.ChatModelFactory
import ai.platon.pulsar.skeleton.context.PulsarContexts
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

@Tag("ExternalResourceDependent")
class UniversalProxyParserTest {
    private val session = PulsarContexts.getOrCreateSession()
    private val conf = session.sessionConfig

    @BeforeEach
    fun setUp() {
        Assumptions.assumeTrue(ChatModelFactory.isModelConfigured(conf))
    }

    @Test
    fun `test parseUniversalProxy`() {
        val parser = UniversalProxyParser()
        val proxies = parser.parse(
            """
proxy01.example.com:8080
192.168.1.1:3128
proxy-nyc-1.company.net:8000
proxy02.local:1080
10.0.0.22:8888
uk1.proxy.co:3129
vpn-proxy.alpha.net:443
proxy-asia-77.net:9090
proxy03.example.org:8081
172.16.254.3:1080
proxy-node4.intra:8001
de-frankfurt.proxy:3128
proxy07.datacenter.local:1081
nl.proxy.region.cloud:1085
203.0.113.55:8000
proxy-lon03.isp.com:8090
sg.proxy.service:1080
external.proxy42.net:3127
proxy.custom.subnet:9999
proxy89.myvpn.network:443

                """
        )

        proxies.forEach {
            // logPrintln(it)
            assertTrue { it.host.isNotBlank() }
        }
    }

    @Test
    fun `test parseUniversalProxy with NON-STANDARD proxy`() {
        val parser = UniversalProxyParser()
        val proxies = parser.parse(
            """
        :8080
        192.168.1.1
        proxy-nyc-1.company.net:invalid-port
        proxy02.local:65536
        10.0.0.22:-1
        uk1.proxy.co:
        vpn-proxy.alpha.net:0
        proxy-asia-77.net:abc
        proxy03.example.org:80a
        172.16.254.3: 1080
        proxy-node4.intra8001
        de-frankfurt.proxy:3128:extra
        proxy07.datacenter.local:1081#comment
        nl.proxy.region.cloud:1085
        203.0.113.55 :8000b
        proxy-lon03.isp.com : 8090a
        sg.proxy.service:1080
        external.proxy42.net:3127
        proxy.custom.subnet:9999
        proxy89.myvpn.network:443

        """
        )

        // assertTrue { proxies.isEmpty() }
        proxies.forEach {
            // logPrintln(it)
            assertTrue { it.host.isNotBlank() }
        }
    }
}

