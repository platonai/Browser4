package ai.platon.pulsar.skeleton.workflow.parse

import ai.platon.pulsar.common.FlowState
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.config.Parameterized
import ai.platon.pulsar.common.readable
import ai.platon.pulsar.persist.ParseStatus
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.common.message.MiscMessageWriter
import ai.platon.pulsar.skeleton.common.persist.ext.loadEventHandlers
import ai.platon.pulsar.skeleton.event.PulsarEventBus
import ai.platon.pulsar.skeleton.workflow.common.LazyConfigurable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentSkipListSet
import kotlin.system.measureTimeMillis

class PageParser(
    val parserFactory: ParserFactory,
    override var conf: ImmutableConfig,
    val messageWriter: MiscMessageWriter? = null
) : Parameterized, LazyConfigurable, AutoCloseable {
    private val logger = LoggerFactory.getLogger(PageParser::class.java)

    val unparsableTypes = ConcurrentSkipListSet<CharSequence>()

    constructor(parserFactory: ParserFactory, conf: ImmutableConfig) : this(
        parserFactory,
        conf,
        MiscMessageWriter()
    )

    /**
     * @param conf The configuration
     */
    constructor(conf: ImmutableConfig) : this(ParserFactory(conf), conf)

    override fun configure(conf1: ImmutableConfig) {
    }

    /**
     * Parses given web page and stores parsed content within page. Puts a meta-redirect to outlinks.
     *
     * @param page The web page
     * @return ParseResult
     * A non-null ParseResult contains the main result and status of this parse
     */
    fun parse(page: WebPage): ParseResult {
        try {
            return parse0(page)
        } catch (e: Throwable) {
            // Log any exceptions that occur during the parsing process.
            logger.error("Failed to parse | ${page.url}", e)
        }

        return ParseResult.failed(ParseStatus.FAILED_EXCEPTION, "Unknown")
    }

    override fun close() {

    }

    /**
     * Parses the given webpage and returns the parsing result.
     *
     * This function attempts to parse the provided [page] using the [doParse] function.
     * If the parsing is successful, it updates the [page]'s parse status and marks accordingly.
     * In case of any exceptions during the parsing process, the error is logged, and a default [ParseResult] is returned.
     *
     * @param page The [WebPage] object to be parsed.
     * @return [ParseResult] A non-null [ParseResult] object that contains the main result and status of the parsing operation.
     *                        If an exception occurs, a default [ParseResult] is returned.
     */
    private fun parse0(page: WebPage): ParseResult {
        // Attempt to parse the webpage using the doParse function.
        val parseResult = doParse(page)

        // If the parsing was successful, update the page's parse status and marks.
        if (parseResult.isParsed) {
            page.parseStatus = parseResult

            // If the parsing result indicates success, perform additional actions.
            if (parseResult.isSuccess) {
                // do something
            }
        }

        // Return the parsing result.
        return parseResult
    }

    private fun doParse(page: WebPage): ParseResult {
        if (page.isInternal) {
            return ParseResult().apply { flowStatus = FlowState.BREAK }
        }

        return try {
            onWillParse(page)
            applyParsers(page)
        } catch (e: ParserNotFound) {
            unparsableTypes.add(page.contentType)
            logger.warn("No parser found for <" + page.contentType + ">\n" + e.message)
            return ParseResult.failed(ParseStatus.FAILED_NO_PARSER, page.contentType)
        } catch (e: Throwable) {
            logger.warn("Failed to parse | ${page.configuredUrl}", e)
            return ParseResult.failed(e)
        } finally {
            onParsed(page)
        }
    }

    private fun onWillParse(page: WebPage) {
        try {
            // The more specific handlers has the opportunity to override the result of more general handlers.
            page.loadEventHandlers?.onWillParse?.invoke(page)
            PulsarEventBus.pageEventHandlers?.loadEventHandlers?.onWillParse?.invoke(page)
            // Forward to server-side event handlers (non-blocking)
            PulsarEventBus.emitLoadEvent("onWillParse", page)
        } catch (e: Throwable) {
            logger.warn("[onWillParse]", e)
        }
    }

    private fun onParsed(page: WebPage) {
        try {
            PulsarEventBus.pageEventHandlers?.loadEventHandlers?.onParsed?.invoke(page)
            // The more specific handlers has the opportunity to override the result of more general handlers.
            page.loadEventHandlers?.onParsed?.invoke(page)
            // Forward to server-side event handlers (non-blocking)
            PulsarEventBus.emitLoadEvent("onParsed", page)
        } catch (e: Throwable) {
            logger.warn("[onParsed]", e)
        }
    }

    /**
     * @throws ParseException If there is an error parsing.
     */
    @Throws(ParseException::class)
    private fun applyParsers(page: WebPage): ParseResult {
        if (page.isInternal) {
            return ParseResult().apply { flowStatus = FlowState.BREAK }
        }

        var parseResult = ParseResult()
        val parsers = parserFactory.getParsers(page.contentType, page.url)

        for (parser in parsers) {
            val millis = measureTimeMillis {
                // Optimized for html content
                // To parse non-html content, the parser might run into an endless loop,
                // run it in a separate coroutine to protect the process
                parseResult = takeIf { parser.timeout.seconds > 0 }?.runParser(parser, page)?:parser.parse(page)
            }
            parseResult.parsers.add(parser::class)

            val m = page.pageModel
            if (logger.isDebugEnabled && millis > 10_000 && m != null) {
                logger.debug("It takes {} to parse {}/{}/{} fields | {}", Duration.ofMillis(millis).readable(),
                        m.numNonBlankFields, m.numNonNullFields, m.numFields, page.url)
            }

            // Found a suitable parser and successfully parsed
            if (parseResult.shouldBreak) {
                break
            }
        }

        return parseResult
    }

    private fun runParser(p: Parser, page: WebPage): ParseResult {
        return runBlocking {
            withTimeout(p.timeout.toMillis()) {
                val deferred = async { p.parse(page) }
                deferred.await()
            }
        }
    }
}
