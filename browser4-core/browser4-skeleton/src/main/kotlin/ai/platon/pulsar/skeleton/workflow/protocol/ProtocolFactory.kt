package ai.platon.pulsar.skeleton.workflow.protocol

import ai.platon.pulsar.core.api.WebPage
import ai.platon.pulsar.persist.metadata.FetchMode
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Creates and caches [Protocol] plugins. Protocol plugins should define
 * the attribute "protocolName" with the name of the protocol that they
 * implement.
 */
class ProtocolFactory(
    protocols: List<Protocol> = emptyList()
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(ProtocolFactory::class.java)

    private val protocolMap: MutableMap<String, Protocol> = ConcurrentHashMap()
    private val closed = AtomicBoolean()

    init {
        protocolMap.putAll(protocols.associateBy { it.name })
    }

    /**
     * Get the protocol for a page.
     *
     * Using major protocol/sub protocol is a good idea, for example:
     * selenium:http://www.baidu.com/
     * jdbc:h2:tcp://localhost/~/test
     */
    fun getProtocol(page: WebPage): Protocol {
        val fetchMode = FetchMode.BROWSER
        page.fetchMode = fetchMode

        return when (fetchMode) {
            FetchMode.BROWSER -> getProtocol("browser:" + page.url)
            else -> getProtocol(page.url)
        } ?: throw ProtocolNotFound(page.url)
    }

    /**
     * Returns the appropriate [Protocol] implementation for a url.
     *
     * @param url The url
     * @return The appropriate [Protocol] implementation for a given [url].
     */
    fun getProtocol(url: String): Protocol? {
        val protocolName = StringUtils.substringBefore(url, ":")
        // sub protocol can be supported by main:sub://example.com later
        return protocolMap[protocolName]
    }

    fun getProtocol(mode: FetchMode): Protocol? {
        return getProtocol(mode.name.lowercase(Locale.getDefault()) + "://")
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            protocolMap.values.forEach { protocol: Protocol ->
                try {
                    protocol.close()
                } catch (e: Throwable) {
                    logger.error(e.toString())
                }
            }
            protocolMap.clear()
        }
    }
}
