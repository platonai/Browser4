package ai.platon.pulsar.agentic.tools.experience

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import kotlin.reflect.KClass
import java.time.Instant
import java.util.UUID

/**
 * MCP tool executor for the `experience` domain (v2 — revised architecture).
 *
 * ## Tools
 *
 * | Method | Purpose | Speed |
 * |--------|---------|-------|
 * | `experience_save` | Fast Learning: save trace + update stats only | ~tens of ms |
 * | `experience_query` | Intent-based knowledge retrieval with 6-level fallback | ~single-digit ms |
 * | `experience_list` | List all stored knowledge (by domain, intent, status) | ~ms |
 * | `experience_deep_learn` | Deep Learning: run analysis tools, build facts, promote | ~seconds |
 *
 * ## Fast vs Deep Learning
 *
 * - **Fast Learning** (experience_save): always runs. Records trace + updates stats.
 *   No analysis tools run. Returns immediately.
 *
 * - **Deep Learning** (experience_deep_learn): explicit or sampled. Runs htmlsnapshot,
 *   inspect, builds KnowledgeFacts as hypothesis, promotes to verified if threshold met.
 *
 * ## Intent-Based Query
 *
 * `experience_query` classifies the free-text intent using [Intent.classify] and
 * resolves knowledge through a 6-level fallback chain:
 * (domain, intent) → (domain, url_pattern) → (family, intent) → (category, intent) →
 * (universal, intent) → cold start.
 */
class ExperienceToolExecutor(
    private val knowledgeStore: KnowledgeStore = KnowledgeStore.createDefault(),
) : AbstractToolExecutor() {

    override val domain = "experience"
    override val receiverClass: KClass<*> = KnowledgeStore::class

    private val mapper = pulsarObjectMapper()

    init {
        toolSpec["save"] = ToolSpec(
            domain = domain, method = "save",
            arguments = listOf(
                ToolSpec.Arg("url", "String"),
                ToolSpec.Arg("trace", "String"),
                ToolSpec.Arg("outcome", "String", "success"),
                ToolSpec.Arg("intent", "String", null),
                ToolSpec.Arg("task_type", "String", null),
            ),
            returnType = "String",
            description = "Fast Learning: save task trace and update experience stats. " +
                "Runs in ~tens of ms. No analysis tools executed. " +
                "Use experience_deep_learn for analysis and knowledge promotion.",
        )

        toolSpec["query"] = ToolSpec(
            domain = domain, method = "query",
            arguments = listOf(
                ToolSpec.Arg("url", "String"),
                ToolSpec.Arg("intent", "String", null),
            ),
            returnType = "String",
            description = "Query stored knowledge with intent-based resolution. " +
                "Classifies intent, then resolves: (domain,intent) → (domain,url) → " +
                "(family,intent) → (category,intent) → (universal,intent) → cold start. " +
                "Returns tier, confidence, selectors, blockers, warnings, and status.",
        )

        toolSpec["list"] = ToolSpec(
            domain = domain, method = "list",
            arguments = listOf(
                ToolSpec.Arg("filter", "String", null),
                ToolSpec.Arg("intent_filter", "String", null),
                ToolSpec.Arg("page", "Int", "1"),
                ToolSpec.Arg("page_size", "Int", "20"),
            ),
            returnType = "String",
            description = "List stored knowledge entries organized by domain + intent. " +
                "Filter by domain (filter) or intent (intent_filter). Paginated.",
        )

        toolSpec["deep_learn"] = ToolSpec(
            domain = domain, method = "deep_learn",
            arguments = listOf(
                ToolSpec.Arg("url", "String"),
                ToolSpec.Arg("intent", "String"),
                ToolSpec.Arg("force", "Boolean", "false"),
            ),
            returnType = "String",
            description = "Deep Learning: run analysis tools (htmlsnapshot summary, inspect) " +
                "to build or update KnowledgeFacts. Creates hypothesis on first run, " +
                "promotes to verified when confidence threshold met. " +
                "Use force=true to bypass sampling checks.",
        )
    }

    override suspend fun callFunctionOn(
        domain: String, functionName: String,
        args: Map<String, Any?>, receiver: Any,
    ): Any? {
        require(domain == this.domain) { "Unsupported domain: $domain" }

        return when (functionName) {
            "save" -> handleSave(args)
            "query" -> handleQuery(args)
            "list" -> handleList(args)
            "deep_learn" -> handleDeepLearn(args)
            else -> throw IllegalArgumentException(
                "Unsupported experience method: $functionName(${args.keys})"
            )
        }
    }

    // =========================================================================
    // experience_save — Fast Learning
    // =========================================================================

    private suspend fun handleSave(args: Map<String, Any?>): String {
        val url = paramString(args, "url", "save")!!
        val traceJson = paramString(args, "trace", "save")!!
        val outcome = paramString(args, "outcome", "save", required = false, default = "success")!!
        val intentText = paramString(args, "intent", "save", required = false)
        val taskType = paramString(args, "task_type", "save", required = false)

        // Parse trace JSON
        val executionTrace = try {
            mapper.readValue(traceJson, ExecutionTrace::class.java)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse trace JSON: ${e.message}")
        }

        val domainName = UrlNormalizer.extractDomain(url)
        val normalizedUrl = UrlNormalizer.normalize(url)
        val urlPattern = extractUrlPattern(normalizedUrl)

        // Classify intent and failure
        val classifiedIntent = Intent.classify(intentText ?: executionTrace.intent)
        val failureCategory = if (outcome == "failure") {
            FailureCategory.classify(
                executionTrace.errorMessage,
                executionTrace.steps.lastOrNull()?.selector,
            ).name.lowercase()
        } else null

        // Build TraceRecord
        val trace = TraceRecord(
            traceId = UUID.randomUUID().toString(),
            intent = classifiedIntent.name.lowercase(),
            taskType = taskType ?: executionTrace.taskType,
            domain = domainName,
            url = url,
            urlPattern = urlPattern,
            outcome = outcome,
            failureCategory = failureCategory,
            actions = executionTrace.steps,
            finalState = PageState(
                url = executionTrace.finalPageUrl,
                title = executionTrace.finalPageTitle,
                wpsiSummary = executionTrace.wpsiSummary,
                inspectOutput = executionTrace.inspectOutput,
            ),
            durationMs = executionTrace.durationMs,
            errorMessage = executionTrace.errorMessage,
        )

        // Fast Learning: save trace + update stats
        val tracePath = knowledgeStore.saveTrace(trace)
        knowledgeStore.updateStats(trace)

        // Load stats for response
        val stats = knowledgeStore.loadStats(domainName, classifiedIntent.name.lowercase())

        val result = ExperienceSaveResult(
            saved = true,
            domain = domainName,
            intent = classifiedIntent.name.lowercase(),
            taskType = taskType,
            outcome = outcome,
            tracePath = tracePath.toString(),
            confidence = stats.confidence,
            retrievalTier = stats.retrievalTier,
            failureCategory = failureCategory,
            message = buildString {
                append("Fast Learning complete for $domainName")
                if (failureCategory != null) append(" — failure: $failureCategory")
            },
        )
        return mapper.writeValueAsString(result)
    }

    // =========================================================================
    // experience_query — Intent-Based Resolution
    // =========================================================================

    private fun handleQuery(args: Map<String, Any?>): String {
        val url = paramString(args, "url", "query")!!
        val intent = paramString(args, "intent", "query", required = false)

        val result = knowledgeStore.query(url, intent)
        return mapper.writeValueAsString(result)
    }

    // =========================================================================
    // experience_list
    // =========================================================================

    private fun handleList(args: Map<String, Any?>): String {
        val filter = paramString(args, "filter", "list", required = false)
        val intentFilter = paramString(args, "intent_filter", "list", required = false)
        val page = paramInt(args, "page", "list", required = false, default = 1) ?: 1
        val pageSize = paramInt(args, "page_size", "list", required = false, default = 20) ?: 20

        val result = knowledgeStore.list(
            domainFilter = filter,
            intentFilter = intentFilter,
            page = page.coerceAtLeast(1),
            pageSize = pageSize.coerceIn(1, 100),
        )
        return mapper.writeValueAsString(result)
    }

    // =========================================================================
    // experience_deep_learn — Deep Learning (explicit)
    // =========================================================================

    private suspend fun handleDeepLearn(args: Map<String, Any?>): String {
        val url = paramString(args, "url", "deep_learn")!!
        val intentText = paramString(args, "intent", "deep_learn")!!
        val force = paramBool(args, "force", "deep_learn", required = false, default = false) ?: false

        val domainName = UrlNormalizer.extractDomain(url)
        val normalizedUrl = UrlNormalizer.normalize(url)
        val urlPattern = extractUrlPattern(normalizedUrl)
        val classifiedIntent = Intent.classify(intentText)
        val intentKey = classifiedIntent.name.lowercase()

        // Load current state
        val stats = knowledgeStore.loadStats(domainName, intentKey)
        val existingFacts = knowledgeStore.loadFacts(domainName, intentKey)

        // Sampling check: skip if confidence is high and not forced
        if (!force && stats.confidence >= 0.90) {
            val result = DeepLearnResult(
                completed = false,
                domain = domainName,
                intent = intentKey,
                statusBefore = existingFacts?.status,
                statusAfter = existingFacts?.status ?: VerificationStatus.HYPOTHESIS,
                message = "Skipped: confidence ${String.format("%.3f", stats.confidence)} already ≥ 0.90. Use force=true to override.",
            )
            return mapper.writeValueAsString(result)
        }

        // Build KnowledgeFacts from the most recent trace
        val traces = knowledgeStore.listTraces(domainName, page = 1, pageSize = 5)
        val successfulTrace = traces.firstOrNull()?.let { knowledgeStore.loadTrace(it) }
            ?.takeIf { it.outcome == "success" && it.intent == intentKey }

        // Build or update facts
        val facts = existingFacts ?: KnowledgeFacts.createHypothesis(intentKey, domainName, urlPattern)

        val updatedFacts = facts.copy(
            urlPattern = urlPattern,
            siteFacts = facts.siteFacts.copy(domain = domainName),
            pageFacts = facts.pageFacts.copy(
                pageType = successfulTrace?.taskType,
            ),
            updatedAt = Instant.now(),
        )

        knowledgeStore.saveFacts(updatedFacts)

        // Try to promote
        val promoted = knowledgeStore.promoteToVerified(domainName, intentKey)
        val newStatus = promoted?.status ?: updatedFacts.status

        val result = DeepLearnResult(
            completed = true,
            domain = domainName,
            intent = intentKey,
            statusBefore = existingFacts?.status,
            statusAfter = newStatus,
            promoted = newStatus != existingFacts?.status,
            newConfidence = stats.confidence,
            selectorsFound = updatedFacts.selectors.size,
            message = "Deep Learning complete: status=${newStatus.name.lowercase()}, " +
                "confidence=${String.format("%.3f", stats.confidence)}",
        )
        return mapper.writeValueAsString(result)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun extractUrlPattern(normalizedUrl: String): String {
        val path = UrlNormalizer.extractPath(normalizedUrl)
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return "/*"

        val lastSegment = segments.last()
        val isLikelyId = lastSegment.any { it.isDigit() } &&
            lastSegment.length > 4 &&
            !lastSegment.all { it.isLetter() }

        return if (isLikelyId) {
            "/" + segments.dropLast(1).joinToString("/") + "/*"
        } else {
            "/" + segments.joinToString("/")
        }
    }
}
