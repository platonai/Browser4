package ai.platon.pulsar.skeleton.workflow.protocol

import ai.platon.pulsar.common.ResourceLoader
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.stringify
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.metadata.FetchMode
import ai.platon.pulsar.skeleton.crawl.protocol.Protocol
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
class ProtocolFactory(private val immutableConfig: ImmutableConfig) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(ProtocolFactory::class.java)

    private val protocols: MutableMap<String, Protocol> = ConcurrentHashMap()
    private val closed = AtomicBoolean()

    init {
        ResourceLoader.readAllLines("protocol-plugins.txt")
            .asSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("#") }
            .map { it.split("\\s+".toRegex()) }
            .filter { it.size >= 2 }
            .map { it[0] to getInstance(it) }
            .filter { it.second != null }
            .associate { it.first to it.second!! }
            .onEach { it.value.configure(immutableConfig) }
            .toMap(protocols)
        protocols.keys.joinToString(", ", "Supported protocols: ", "")
            .also { logger.debug(it) }
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
        return protocols[protocolName]
    }

    fun getProtocol(mode: FetchMode): Protocol? {
        return getProtocol(mode.name.lowercase(Locale.getDefault()) + "://")
    }

    private fun getInstance(config: List<String>): Protocol? {
        try {
            // config[0] is the protocol name, config[1] is the class name, and the rest are properties
            val className = config[1]
            return Class.forName(className).constructors.first().newInstance() as Protocol
        } catch (e: ClassNotFoundException) {
            logger.error(e.stringify())
        } catch (e: InstantiationException) {
            logger.error(e.stringify())
        } catch (e: IllegalAccessException) {
            logger.error(e.stringify())
        }
        return null
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            protocols.values.forEach { protocol: Protocol ->
                try {
                    protocol.close()
                } catch (e: Throwable) {
                    logger.error(e.toString())
                }
            }
            protocols.clear()
        }
    }
}
