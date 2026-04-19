
package ai.platon.pulsar.protocol.browser

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.protocol.crowd.ForwardingProtocol
import ai.platon.pulsar.skeleton.crawl.protocol.ProtocolFactory
import kotlin.test.*

/**
 * Unit test for new protocol plugin.
 */
class TestProtocolFactory {
    private val protocolFactory = ProtocolFactory(ImmutableConfig())

    @Test
    @Throws(Exception::class)
    fun testGetProtocol() {
        // clowd protocol is not supported currently
//        assertEquals(ForwardingProtocol::class.java.name,
//                protocolFactory.getProtocol("crowd:http://example.com")?.javaClass?.name)

        assertEquals(BrowserEmulatorProtocol::class.java.name,
                protocolFactory.getProtocol("browser:http://example.com")?.javaClass?.name)
    }
}
