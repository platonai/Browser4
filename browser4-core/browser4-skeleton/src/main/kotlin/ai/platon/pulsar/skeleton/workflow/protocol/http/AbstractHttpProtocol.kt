package ai.platon.pulsar.skeleton.workflow.protocol.http

import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.HttpHeaders
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.core.api.WebPage
import ai.platon.pulsar.persist.ProtocolStatus
import ai.platon.pulsar.persist.RetryScope
import ai.platon.pulsar.persist.metadata.FetchMode
import ai.platon.pulsar.persist.metadata.Name
import ai.platon.pulsar.persist.metadata.ProtocolStatusCodes
import ai.platon.pulsar.skeleton.workflow.common.MimeTypeResolver
import ai.platon.pulsar.skeleton.workflow.protocol.Protocol
import ai.platon.pulsar.skeleton.workflow.protocol.ProtocolOutput
import ai.platon.pulsar.skeleton.workflow.protocol.Response
import crawlercommons.robots.BaseRobotRules
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

abstract class AbstractHttpProtocol : Protocol {
    protected val closed = AtomicBoolean()

    val isActive get() = !closed.get() && AppContext.isActive

    /**
     * The configuration
     */
    override lateinit var conf: ImmutableConfig

    private lateinit var robots: HttpRobotRulesParser

    private val mimeTypeResolver = MimeTypeResolver()

    /**
     * Set up the protocol.
     * Sometimes the protocol can not to be constructed with parameters, so it need a secondary setup.
     * */
    override fun configure(conf1: ImmutableConfig) {
        conf = conf1
        robots = HttpRobotRulesParser(conf1)
    }

    override fun reset() {
    }

    @Throws(Exception::class)
    override suspend fun getProtocolOutputDeferred(page: WebPage): ProtocolOutput {
        val startTime = Instant.now()
        val response = getResponseDeferred(page, false)
            ?: return ProtocolOutput(ProtocolStatus.retry(RetryScope.CRAWL, "Null response from protocol"))
        setResponseTime(startTime, page, response)
        return getOutputWithHttpCodeTranslated(page.url, response)
    }

    private fun getOutputWithHttpCodeTranslated(url: String, response: Response): ProtocolOutput {
        var u = URI(url).toURL()
        val httpCode = response.httpCode
        val pageDatum = response.pageDatum
        val content = pageDatum.content

        val contentType = response.getHeader(HttpHeaders.CONTENT_TYPE)
        pageDatum.contentType = mimeTypeResolver.resolveMimeType(contentType, url, content)

        val headers = pageDatum.headers
        val finalProtocolStatus = if (httpCode >= ProtocolStatusCodes.INCOMPATIBLE_CODE_START) {
            response.protocolStatus
        } else {
            ProtocolStatusTranslator.translateHttpCode(httpCode)
        }

        when (httpCode) {
            in 300..399 -> {
                // handle redirect
                // some broken servers, such as MS IIS, use lowercase header name...
                val redirect = response.getHeader("Location") ?: response.getHeader("location") ?: ""
                u = u.toURI().resolve(redirect).toURL()
                finalProtocolStatus.args[ProtocolStatus.ARG_REDIRECT_TO_URL] = u.toString()
            }
        }

        return ProtocolOutput(pageDatum, headers, finalProtocolStatus)
    }

    private fun setResponseTime(startTime: Instant, page: WebPage, response: Response) {
        val pageFetchMode = page.fetchMode
        val elapsedTime = if (pageFetchMode == FetchMode.BROWSER) {
            val requestTime = response.getHeader(HttpHeaders.Q_REQUEST_TIME)?.toLongOrNull() ?: -1
            val responseTime = response.getHeader(HttpHeaders.Q_RESPONSE_TIME)?.toLongOrNull() ?: -1
            if (requestTime > 0 && responseTime > 0) {
                Duration.ofMillis(responseTime - requestTime)
            } else {
                // Non-positive means an invalid response time which indicates a bug
                Duration.ZERO
            }
        } else {
            Duration.between(startTime, Instant.now())
        }
        page.metadata[Name.RESPONSE_TIME] = elapsedTime.toString()
    }

    @Throws(Exception::class)
    abstract suspend fun getResponseDeferred(page: WebPage, followRedirects: Boolean): Response?

    override suspend fun getRobotRules(page: WebPage): BaseRobotRules {
        return robots.getRobotRulesSet(this, page.url)
    }

    override fun close() {
        closed.set(true)
    }

    override fun toString(): String {
        return javaClass.simpleName
    }
}
