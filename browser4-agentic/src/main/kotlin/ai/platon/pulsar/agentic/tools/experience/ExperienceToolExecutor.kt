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
                ToolSpec.Arg("facts", "String", null),
            ),
            returnType = "String",
            description = "Fast Learning: save task trace and update experience stats. " +
                "Runs in ~tens of ms. No analysis tools executed. " +
                "Use experience_deep_learn for analysis and knowledge promotion. " +
                "Optional `facts` (JSON string or object) merges retrospective knowledge " +
                "(selectors/interaction_hints/known_blockers/anti_patterns) into the " +
                "(domain, intent) facts entry — the writer path for lessons learned on a task. " +
                "Refused when the entry is VERIFIED (immutable).",
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

        // Validate URL format — at minimum warn if not http/https
        val trimmedUrl = url.trim()
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            throw IllegalArgumentException(
                "Invalid URL: '$trimmedUrl' — must start with http:// or https://. " +
                "Example: https://example.com/page"
            )
        }
        // DefaultArgumentNormalizer converts snake_case keys to camelCase (task_type → taskType).
        // Accept both forms so the executor works regardless of whether the normalizer runs.
        val taskType = paramString(args, "task_type", "save", required = false)
            ?: paramString(args, "taskType", "save", required = false)

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
        // Use explicit --intent first, then trace's intent field, then fall back to
        // taskType (e.g., login → LOGIN, extract_product_detail → EXTRACT).
        val classifiedIntent = Intent.classify(
            intentText ?: executionTrace.intent
            ?: taskTypeToIntent(taskType ?: executionTrace.taskType)
        )
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

        // Fast Learning: save trace + update stats + index facts
        val tracePath = knowledgeStore.saveTrace(trace)
        knowledgeStore.updateStats(trace)

        val intentKey = classifiedIntent.name.lowercase()

        // Optional retrospective knowledge: `facts` seeds/merges the
        // otherwise writer-less selectors/hints/blockers/anti-patterns of the
        // (domain, intent) facts entry (see KnowledgeFacts).  Accepts a JSON
        // string OR a structured object (MCP may deliver either); nested keys
        // tolerate camelCase and snake_case spellings.
        val factsValue = args["facts"]
        var factsMerged: Boolean? = null
        var factsStatus: String? = null
        var factsRejected: Boolean? = null
        var factsMessage: String? = null
        val patch = parseFactsPatch(factsValue)
        if (!patch.isEmpty) {
            val mergeResult = knowledgeStore.mergeFacts(domainName, intentKey, urlPattern, patch)
            factsMerged = !mergeResult.rejected
            factsRejected = mergeResult.rejected
            factsStatus = mergeResult.facts.status.name.lowercase()
            factsMessage = mergeResult.message
        } else {
            // No knowledge patch: still create empty HYPOTHESIS facts so
            // list() and query() find this (domain, intent) entry without an
            // explicit deep_learn call.
            val existingFacts = knowledgeStore.loadFacts(domainName, intentKey)
            if (existingFacts == null) {
                val facts = KnowledgeFacts.createHypothesis(intentKey, domainName, urlPattern)
                knowledgeStore.saveFacts(facts)
            }
        }

        // Load stats for response
        val stats = knowledgeStore.loadStats(domainName, intentKey)

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
            factsMerged = factsMerged,
            factsStatus = factsStatus,
            factsRejected = factsRejected,
            factsMessage = factsMessage,
            message = buildString {
                append("Fast Learning complete for $domainName")
                if (failureCategory != null) append(" — failure: $failureCategory")
                if (factsMessage != null) append(". $factsMessage")
            },
        )
        return mapper.writeValueAsString(result)
    }

    /**
     * Parse the optional `facts` argument of experience_save into a
     * [FactsPatch].  Accepts:
     * - `null`/missing → empty patch;
     * - a JSON string (CLI `--facts @file` / inline JSON);
     * - a structured Map (LLM tool calls deliver objects natively).
     *
     * Nested keys tolerate both snake_case and camelCase spellings
     * (`interaction_hints` / `interactionHints`, …); selector values accept
     * `{primary, fallbacks?, note?}` (and `source` is ignored/kept as-is).
     */
    private fun parseFactsPatch(value: Any?): FactsPatch {
        if (value == null) return FactsPatch()
        val map: Map<*, *> = when (value) {
            is String -> {
                val trimmed = value.trim()
                if (trimmed.isEmpty() || trimmed == "{}" || trimmed == "null") return FactsPatch()
                try {
                    mapper.readValue(trimmed, Map::class.java)
                } catch (e: Exception) {
                    throw IllegalArgumentException(
                        "Failed to parse facts JSON: ${e.message}. " +
                            "Expected: {\"selectors\":{...},\"interaction_hints\":[...],\"known_blockers\":[...],\"anti_patterns\":[...]}"
                    )
                }
            }
            is Map<*, *> -> value
            else -> throw IllegalArgumentException(
                "facts must be a JSON string or object (got ${value::class.simpleName})"
            )
        }

        fun pick(vararg keys: String): Any? {
            for ((k, v) in map) {
                val key = k?.toString() ?: continue
                if (keys.any { it.equals(key, ignoreCase = true) }) return v
            }
            return null
        }
        fun strList(v: Any?): List<String> = when (v) {
            null -> emptyList()
            is List<*> -> v.mapNotNull { it?.toString() }
            is String -> v.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }

        val selectors = mutableMapOf<String, VerifiedSelector>()
        (pick("selectors") as? Map<*, *>)?.forEach { (key, raw) ->
            val selMap = raw as? Map<*, *> ?: return@forEach
            val primary = selMap.entries.firstOrNull { (k, _) ->
                k?.toString()?.equals("primary", true) == true
            }?.value?.toString() ?: return@forEach
            val fallbacks = strList(selMap.entries.firstOrNull { (k, _) ->
                k?.toString()?.equals("fallbacks", true) == true
            }?.value)
            val note = selMap.entries.firstOrNull { (k, _) ->
                k?.toString()?.equals("note", true) == true
            }?.value?.toString()
            selectors[key?.toString() ?: primary] = VerifiedSelector(
                primary = primary,
                fallbacks = fallbacks,
                note = note,
            )
        }

        val blockers = (pick("known_blockers", "blockers") as? List<*>).orEmpty().mapNotNull { raw ->
            val b = raw as? Map<*, *> ?: return@mapNotNull null
            fun field(vararg keys: String): String? =
                b.entries.firstOrNull { (k, _) -> keys.any { it.equals(k?.toString(), true) } }?.value?.toString()
            val type = field("type") ?: return@mapNotNull null
            BlockerInfo(
                type = type,
                selector = field("selector"),
                action = field("action") ?: "click",
                frequency = field("frequency"),
                note = field("note"),
            )
        }

        return FactsPatch(
            selectors = selectors,
            interactionHints = strList(pick("interaction_hints", "interactionHints", "hints")),
            knownBlockers = blockers,
            antiPatterns = strList(pick("anti_patterns", "antiPatterns")),
        )
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
        // DefaultArgumentNormalizer converts snake_case keys to camelCase.
        // Accept both forms.
        val intentFilter = paramString(args, "intent_filter", "list", required = false)
            ?: paramString(args, "intentFilter", "list", required = false)
        val page = paramInt(args, "page", "list", required = false, default = 1) ?: 1
        val pageSize = paramInt(args, "page_size", "list", required = false, default = 20)
            ?: paramInt(args, "pageSize", "list", required = false, default = 20) ?: 20

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

    /**
     * Map a task type string to the closest intent when no explicit intent is provided.
     *
     * Maps canonical task types (from [TaskType] enum) to the most likely [Intent].
     * Used as a fallback when --intent is omitted and the trace has a taskType field.
     */
    private fun taskTypeToIntent(taskType: String?): String? {
        if (taskType.isNullOrBlank()) return null
        val lower = taskType.lowercase().trim()
        return when {
            lower.contains("login") || lower.contains("sign_in") -> "login"
            lower.contains("extract") || lower.contains("scrape") || lower.contains("fetch") -> "extract"
            lower.contains("search") || lower.contains("find") || lower.contains("query") -> "search"
            lower.contains("fill_form") || lower.contains("fill") || lower.contains("form") -> "fill_form"
            lower.contains("navigate") || lower.contains("goto") || lower.contains("visit") -> "navigate"
            lower.contains("checkout") || lower.contains("purchase") || lower.contains("order") -> "checkout"
            lower.contains("add_to_cart") || lower.contains("cart") -> "buy"
            lower.contains("download") || lower.contains("save_file") -> "download"
            lower.contains("monitor") || lower.contains("watch") || lower.contains("track") -> "monitor"
            lower.contains("compare") || lower.contains("diff") -> "compare"
            lower.contains("publish") || lower.contains("post") || lower.contains("x_post")
                || lower.contains("cross") || lower.contains("发帖") || lower.contains("发布") -> "publish"
            else -> null
        }
    }

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
