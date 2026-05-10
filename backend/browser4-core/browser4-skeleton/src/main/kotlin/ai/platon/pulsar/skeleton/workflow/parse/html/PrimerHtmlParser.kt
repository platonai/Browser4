package ai.platon.pulsar.skeleton.workflow.parse.html

import ai.platon.pulsar.common.config.CapabilityTypes.PARSE_DEFAULT_ENCODING
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.config.Params
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.metadata.ParseStatusCodes
import ai.platon.pulsar.skeleton.common.persist.ext.loadEventHandlers
import ai.platon.pulsar.skeleton.common.persist.ext.options
import ai.platon.pulsar.skeleton.event.PulsarEventBus
import ai.platon.pulsar.skeleton.workflow.parse.ParseFilters
import ai.platon.pulsar.skeleton.workflow.parse.ParseResult
import ai.platon.pulsar.skeleton.workflow.parse.Parser
import org.slf4j.LoggerFactory
import java.net.MalformedURLException
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Primer HTML parser is a built-in parser while others should be a protocol-plugin.
 * Primer HTML parser uses Jsoup to parse the page content to a HTML document.
 * The parser can also register [ai.platon.pulsar.skeleton.workflow.parse.ParseFilter]s to extend the system.
 */
class PrimerHtmlParser(
    private val parseFilters: ParseFilters? = null,
    private val conf: ImmutableConfig,
) : Parser {
    companion object {
        val numHtmlParses = AtomicInteger()
        val numHtmlParsed = AtomicInteger()
    }

    private val logger = LoggerFactory.getLogger(PrimerHtmlParser::class.java)
    private val tracer = logger.takeIf { it.isDebugEnabled }
    private val defaultCharEncoding = conf.get(PARSE_DEFAULT_ENCODING, "utf-8")
    private val primerParser = PrimerParser(conf)
    /**
     * Optimized for html content
     * To parse non-html content, the parser might run into an endless loop,
     * run it in a separate coroutine to protect the process
     * */
    override val timeout = Duration.ZERO

    init {
        logger.debug(params.formatAsLine())
    }

    constructor(conf: ImmutableConfig): this(null, conf)

    override fun getParams(): Params {
        return Params.of(
            "className", this.javaClass.simpleName,
            "defaultCharEncoding", defaultCharEncoding,
            "parseFilters", parseFilters?.javaClass?.simpleName ?: "null",
        )
    }

    override fun parse(page: WebPage): ParseResult {
        return try {
            // The base url is set by protocol, it might be different from the page url
            // if the request redirects.
            onWillParseHTMLDocument(page)

            val parseContext = primerParser.parseHTMLDocument(page)

            parseFilters?.filter(parseContext)

            checkHTMLRequirement(parseContext)

            parseContext.document?.let { onHTMLDocumentParsed(page, it) }

            parseContext.parseResult
        } catch (e: MalformedURLException) {
            ParseResult.failed(ParseStatusCodes.FAILED_MALFORMED_URL, e.message)
        } catch (e: Exception) {
            ParseResult.failed(ParseStatusCodes.FAILED_INVALID_FORMAT, e.message)
        }
    }

    private fun onWillParseHTMLDocument(page: WebPage) {
        numHtmlParses.incrementAndGet()

        try {
            // notice the calling order.
            // The more specific handlers has the opportunity to override the result of more general handlers.
            page.loadEventHandlers?.onWillParseHTMLDocument?.invoke(page)
            PulsarEventBus.pageEventHandlers?.loadEventHandlers?.onWillParseHTMLDocument?.invoke(page)
            // Forward to server-side event handlers (non-blocking)
            PulsarEventBus.emitLoadEvent("onWillParseHTMLDocument", page)
        } catch (e: Throwable) {
            logger.warn("Failed to invoke onWillParseHTMLDocument | ${page.configuredUrl}", e)
        }
    }

    private fun checkHTMLRequirement(parseContext: ParseContext): ParseContext {
        val page = parseContext.page
        val document = parseContext.document ?: return parseContext
        val options = page.options
        val selector = options.requireNotBlank
        if (selector.isNotBlank()) {
            val element = document.selectFirstOrNull(selector)

            val text = element?.text() ?: ""
            if (text.isBlank()) {
                // The required condition is not matched, the page is not valid
                val message = "Required element is not blank | $selector"
                parseContext.parseResult = ParseResult.failed(ParseStatusCodes.FAILED_MISSING_PARTS, message)
            }
        }

        return parseContext
    }

    private fun onHTMLDocumentParsed(page: WebPage, document: FeaturedDocument) {
        try {
            // The more specific handlers has the opportunity to override the result of more general handlers.
            page.loadEventHandlers?.onHTMLDocumentParsed?.invoke(page, document)
            PulsarEventBus.pageEventHandlers?.loadEventHandlers?.onHTMLDocumentParsed?.invoke(page, document)
            // Forward to server-side event handlers (non-blocking)
            PulsarEventBus.emitLoadEvent("onHTMLDocumentParsed", page, metadata = mapOf("hasDocument" to true))
        } catch (e: Throwable) {
            logger.warn("Failed to invoke onHTMLDocumentParsed | ${page.configuredUrl}", e)
        } finally {
            numHtmlParsed.incrementAndGet()
        }
    }
}
