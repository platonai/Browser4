package ai.platon.pulsar.agentic.tools.high.crawl

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.tools.high.crawl.common.DomUtils
import ai.platon.pulsar.agentic.tools.high.crawl.common.PLACEHOLDER_PAGE_CONTENT
import ai.platon.pulsar.agentic.tools.high.crawl.common.RestAPIPromptUtils
import ai.platon.pulsar.agentic.tools.high.crawl.common.ScrapeAPIUtils
import ai.platon.pulsar.agentic.tools.high.crawl.service.ScrapeService
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.ai.llm.PromptTemplate
import ai.platon.pulsar.common.alwaysFalse
import ai.platon.pulsar.common.concurrent.ConcurrentExpiringLRUCache
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.serialize.json.FlatJSONExtractor
import ai.platon.pulsar.common.sql.SQLTemplate
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.dom.UriExtractor
import ai.platon.pulsar.dom.nodes.node.ext.numChars
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.crawl.DefaultServerSideEventHandlers
import ai.platon.pulsar.skeleton.crawl.PageEventHandlers
import ai.platon.pulsar.skeleton.crawl.PulsarEventBus
import ai.platon.pulsar.skeleton.crawl.event.impl.PageEventHandlersFactory
import kotlinx.coroutines.*
import java.io.Closeable
import java.nio.file.Files
import java.time.Duration
import kotlin.io.path.writeText

class StatefulPageVisitor(
    val session: AgenticSession,
) : Closeable {
    private val logger = getLogger(StatefulPageVisitor::class)

    private val scrapeService = ScrapeService(session)

    private val statusCache = ConcurrentExpiringLRUCache<String, PageVisitStatus>(Duration.ofHours(2))

    fun create(): PageVisitStatus {
        val status = PageVisitStatus()
        // status.request = request
        statusCache.putDatum(status.id, status)
        status.emitEvent("StatefulPageVisitor.onCreated")
        return status
    }

    suspend fun visit(request: PageVisitRequest): PageVisitStatus {
        val eventHandlers = PageEventHandlersFactory.create()
        return visit(request, eventHandlers)
    }

    suspend fun visit(
        request: PageVisitRequest,
        status: PageVisitStatus,
        eventHandlers: PageEventHandlers
    ): PageVisitStatus {
        doVisit(request, status, eventHandlers)
        return status
    }

    /**
     * Executes a page-visit command.
     *
     * Each command creates its own [DefaultServerSideEventHandlers] instance and binds it to the current
     * coroutine via [PulsarEventBus.withServerSideEventHandlers], so multiple commands can run concurrently without
     * cross-talk between SSE streams.
     */
    suspend fun visit(request: PageVisitRequest, eventHandlers: PageEventHandlers): PageVisitStatus {
        val status = create()
        doVisit(request, status, eventHandlers)
        return status
    }

    fun getStatus(id: String) = statusCache.getDatum(id)

    fun getResult(id: String) = statusCache.getDatum(id)?.pageVisitResult

    /**
     * Executes a command based on the provided PromptRequestL2 object.
     *
     * This method loads the document associated with the request, processes the chat and data extraction rules,
     * and returns a PromptResponseL2 object containing the results.
     *
     * @param request The PromptRequestL2 object containing the URL and other parameters.
     * @return A PromptResponseL2 object containing the result of the command execution.
     * */
    private suspend fun doVisit(
        request: PageVisitRequest,
        status: PageVisitStatus,
        eventHandlers: PageEventHandlers
    ): PageVisitStatus {
        try {
            status.refresh(ResourceStatus.SC_PROCESSING)

            // Create and wire up ServerSideEventHandlers for this command
            val serverSideEventHandlers = DefaultServerSideEventHandlers()
            status.serverSideEventHandlers = serverSideEventHandlers

            supervisorScope {
                // Start a background job to collect events and update status
                val eventCollectorJob = launch {
                    try {
                        serverSideEventHandlers.eventFlow.collect { event ->
                            status.emitEvent(event.eventType)
                            logger.info("Collected event {} for command {}", event.eventType, status.id)
                        }
                    } catch (e: CancellationException) {
                        logger.debug("Event collector cancelled for command {}", status.id)
                        throw e
                    } catch (e: Exception) {
                        logger.error("Error collecting events for command ${status.id}", e)
                    }
                }

                try {
                    // Bind server-side event handlers to THIS coroutine so multiple commands can run concurrently.
                    PulsarEventBus.withServerSideEventHandlers(serverSideEventHandlers) {
                        visitAnalyzeAndExtractPage(request, status, eventHandlers)
                    }
                } finally {
                    // Cancel event collector when command completes
                    eventCollectorJob.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status.message = e.message
            status.failed(ResourceStatus.SC_EXPECTATION_FAILED)
        } finally {
            status.done()
        }

        return status
    }

    internal suspend fun visitAnalyzeAndExtractPage(
        request: PageVisitRequest,
        status: PageVisitStatus,
        eventHandlers: PageEventHandlers
    ) {
        val url = request.url
        require(URLUtils.isStandard(url)) { "Invalid URL: $url" }

        request.enhanceArgs()
        val (page, document) = scrapeService.loadDocument(request, eventHandlers)

        if (page.isNil) {
            status.failed(ResourceStatus.SC_EXPECTATION_FAILED)
            return
        }

        analyzeAndExtractPage(page, document, request, status)
    }

    internal suspend fun analyzeAndExtractPage(
        page: WebPage,
        document: FeaturedDocument,
        request: PageVisitRequest,
        status: PageVisitStatus
    ) {
        val url = request.url
        require(URLUtils.isStandard(url)) { "Invalid URL: $url" }

        status.pageStatusCode = page.protocolStatus.minorCode
        status.pageContentBytes = page.originalContentLength.toInt()
        if (!page.protocolStatus.isSuccess) {
            return
        }

        analyzeDocumentWithExceptionsHandled(page, document, request, status)

        logger.info("Finished executeCommandStepByStep | status: {} | {}", status.status, document.baseURI)

        val sqlTemplate = request.xsql
        if (sqlTemplate != null && ScrapeAPIUtils.isScrapeUDF(sqlTemplate)) {
            status.refresh(ResourceStatus.SC_PROCESSING)
            val sql = SQLTemplate(sqlTemplate).createSQL(url)
            runCatching { executeQuery(sql, status) }.onFailure { logger.warn("Failed to execute query", it) }
        }

        status.refresh(ResourceStatus.SC_OK)
    }

    private suspend fun analyzeDocumentWithExceptionsHandled(
        page: WebPage, document: FeaturedDocument, request: PageVisitRequest, status: PageVisitStatus
    ) {
        try {
            doAnalyzeDocument(page, document, request, status)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status.message = e.message
            status.failed(ResourceStatus.SC_EXPECTATION_FAILED)
        }
    }

    private suspend fun doAnalyzeDocument(
        page: WebPage, document: FeaturedDocument, request: PageVisitRequest, status: PageVisitStatus
    ) {
        // the 0-based screen number, 0.00 means at the top of the first screen, 1.50 means halfway through the second screen.
        val screenNumber = page.activeDOMMetadata?.screenNumber ?: 0f

        val pageSummaryPrompt = RestAPIPromptUtils.normalizePageSummaryPrompt(request.pageSummaryPrompt)
        val dataExtractionRules = RestAPIPromptUtils.normalizeDataExtractionRules(request.dataExtractionRules)
        var richText: String? = null
        var textContent: String? = null
        if (pageSummaryPrompt != null || dataExtractionRules != null) {
            // TODO: DomUtils.selectNthScreenText
            textContent = when {
                request.richText == true -> {
                    DomUtils.selectNthScreenRichText(screenNumber, document).also { richText = it }
                }

                else -> {
                    document.text
                    // DomUtils.selectNthScreenText(screenNumber, document)
                }
            }
            status.emitEvent("textContent")

            if (textContent.isBlank()) {
                if (document.body.numChars > 100) {
                    val path = document.export()
                    logger.warn(
                        "Not textContent found on screen: {} but there are chars in body: {}, exported to {}",
                        screenNumber, document.body.numChars, path.toUri()
                    )
                }
                return
            }

            if (pageSummaryPrompt != null) {
                val instruct =
                    PromptTemplate(pageSummaryPrompt, mapOf(PLACEHOLDER_PAGE_CONTENT to textContent)).render()
                performInstruct("pageSummary", instruct, status)
                logger.info("pageSummary: {}", status.pageVisitResult?.pageSummary)
            }

            if (dataExtractionRules != null) {
                val instruct =
                    PromptTemplate(dataExtractionRules, mapOf(PLACEHOLDER_PAGE_CONTENT to textContent)).render()
                performInstruct("fields", instruct, status, "map") { content ->
                    FlatJSONExtractor.extract(content)
                }
                logger.info("fields: {}", status.pageVisitResult?.fields)
            }
        }

        var uriExtractionRules = request.uriExtractionRules
        uriExtractionRules = RestAPIPromptUtils.normalizeURIExtractionRules(uriExtractionRules)
        if (uriExtractionRules != null) {
            val regex = resolveUriExtractionRegex(uriExtractionRules, request.inferUriExtractionRegex == true) ?: return

            val allURIs = UriExtractor().extractAllUris(document, document.baseURI)

            if (alwaysFalse()) {
                val allURIText = allURIs.joinToString("\n")
                val path = AppPaths.getProcTmpTmpDirectory("command").resolve("uris.txt")
                withContext(Dispatchers.IO) {
                    Files.createDirectories(path.parent)
                }
                path.writeText(allURIText)
            }

            val uris = allURIs.filter { it.matches(regex) }
            if (uris.isNotEmpty()) {
                val result = PGInstructResult.ok("links", uris, "list")
                status.addInstructResult(result)
            }

            logger.info("Extracted {}/{} uris using regex >>>{}<<<", uris.size, allURIs.size, regex)
        }
    }

    /**
     * Resolves a URI extraction regex.
     *
     * If [uriExtractionRules] already starts with `Regex:`, we parse it directly.
     * Otherwise, we optionally infer a `Regex:` rule via LLM with a timeout.
     *
     * @return The resolved [Regex], or null if it cannot be resolved.
     */
    private suspend fun resolveUriExtractionRegex(uriExtractionRules: String, inferRegex: Boolean): Regex? {
        val rules = RestAPIPromptUtils.normalizeURIExtractionRules(uriExtractionRules) ?: return null

        var resolvedRules = rules
        if (!resolvedRules.startsWith("Regex:")) {
            if (!inferRegex) {
                logger.info(
                    "Skip URI regex inference (inferUriExtractionRegex!=true). " +
                            "Please provide uriExtractionRules as a 'Regex:' pattern."
                )
                return null
            }

            val inferred = chatWithLLMWithTimeout(resolvedRules, Duration.ofSeconds(45))
            if (inferred.isBlank()) {
                logger.warn("URI regex inference timed out or returned empty")
                return null
            }

            resolvedRules = inferred
            if (!resolvedRules.startsWith("Regex:")) {
                logger.warn("Link extraction rules must start with 'Regex:', but got: {}", resolvedRules)
                return null
            }
        }

        return RestAPIPromptUtils.normalizeURIExtractionRegex(resolvedRules)
    }

    private suspend fun chatWithLLMWithTimeout(instruct: String, timeout: Duration): String {
        val content = withTimeoutOrNull(timeout.toMillis()) {
            chatWithLLM(instruct)
        }

        return content ?: ""
    }

    private suspend fun performInstruct(
        name: String, instruct: String, status: PageVisitStatus,
        resultType: String = "string",
        mappingFunction: (String) -> Any = { it.trim() }
    ) {
        val content = chatWithLLM(instruct)
        val result = PGInstructResult.ok(name, mappingFunction(content), resultType)
        status.addInstructResult(result)
    }

    private suspend fun chatWithLLM(instruct: String): String {
        try {
            return session.chat(instruct).content
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Avoid logging full prompt content (may be large / contain sensitive text).
            logger.error("Failed to chat with LLM | promptLength={}", instruct.length, e)
            return ""
        }
    }

    private fun executeQuery(sql: String, status: PageVisitStatus) {
        val scrapeRequest = ScrapeRequest(sql)
        try {
            val scrapeResponse = scrapeService.executeQuery(scrapeRequest)
            status.statusCode = scrapeResponse.statusCode
            status.ensurePageVisitResult().xsqlResultSet = scrapeResponse.resultSet
        } catch (e: Exception) {
            status.statusCode = ResourceStatus.SC_EXPECTATION_FAILED
        }
    }

    override fun close() {
    }
}
