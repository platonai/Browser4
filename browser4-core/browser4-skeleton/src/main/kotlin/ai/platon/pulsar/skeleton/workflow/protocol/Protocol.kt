package ai.platon.pulsar.skeleton.workflow.protocol

import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.workflow.common.LazyConfigurable
import crawlercommons.robots.BaseRobotRules

/**
 * A retriever for url content. Implemented by protocol extensions.
 */
interface Protocol : LazyConfigurable, AutoCloseable {

    fun setResponse(response: Response) {}

    fun getResponses(pages: Collection<WebPage>, volatileConfig: VolatileConfig): Collection<Response> {
        return emptyList()
    }

    /**
     * Reset the protocol environment, so the peer host view the client as a new one
     */
    fun reset() {}

    /**
     * Cancel the page
     */
    fun cancel(page: WebPage) {}

    /**
     * Cancel all fetching tasks
     */
    fun cancelAll() {}

    /**
     * Returns the [ProtocolOutput] for a fetch list entry.
     */
    @Throws(Exception::class)
    fun getProtocolOutput(page: WebPage): ProtocolOutput

    /**
     * Returns the [ProtocolOutput] for a fetch list entry.
     */
    @Throws(Exception::class)
    suspend fun getProtocolOutputDeferred(page: WebPage): ProtocolOutput

    /**
     * Retrieve robot rules applicable for this url.
     *
     * @param page The Web page
     * @return robot rules (specific for this url or default), never null
     */
    fun getRobotRules(page: WebPage): BaseRobotRules

    override fun close() {}
}
