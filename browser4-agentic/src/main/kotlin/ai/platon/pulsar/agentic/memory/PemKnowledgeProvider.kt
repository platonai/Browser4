package ai.platon.pulsar.agentic.memory

import ai.platon.pulsar.agentic.tools.experience.ActionStep
import ai.platon.pulsar.agentic.tools.experience.ExecutionTrace
import ai.platon.pulsar.agentic.tools.experience.ExperienceQueryResult
import ai.platon.pulsar.agentic.tools.experience.FailureCategory
import ai.platon.pulsar.agentic.tools.experience.Intent
import ai.platon.pulsar.agentic.tools.experience.KnowledgeFacts
import ai.platon.pulsar.agentic.tools.experience.KnowledgeStore
import ai.platon.pulsar.agentic.tools.experience.PageState
import ai.platon.pulsar.agentic.tools.experience.TraceRecord
import ai.platon.pulsar.agentic.tools.experience.UrlNormalizer
import ai.platon.pulsar.common.getLogger
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * PEM (Progressive Experience Memory) as the L1 knowledge layer of the
 * generic agent memory system (§8).
 *
 * - [query] delegates to [KnowledgeStore.query] (6-level fallback unchanged)
 *   and renders a compact knowledge section for the recall injection.
 * - [deposit] folds a task's L0 events into an [ExecutionTrace] and writes it
 *   through the same [KnowledgeStore] primitives as `experience_save`
 *   (saveTrace + updateStats + hypothesis facts), then samples promotion.
 *   Keep in sync with `ExperienceToolExecutor.handleSave` — the MCP tool is
 *   the model-facing twin of this engine-side path.
 */
class PemKnowledgeProvider(
    private val knowledgeStore: KnowledgeStore,
    private val queryService: MemoryQueryService,
    private val minPromoteIntervalMinutes: Long = MemoryConfig.consolidationMinIntervalMinutes,
) : MemoryKnowledgeProvider {

    private val logger = getLogger(PemKnowledgeProvider::class)

    /** taskId → true: deposit is idempotent per task. */
    private val deposited = ConcurrentHashMap.newKeySet<String>()

    /** (domain,intent) → last promote timestamp; promotion sampling. */
    private val lastPromotedAt = ConcurrentHashMap<String, Long>()

    override suspend fun query(taskText: String, url: String?, scope: MemoryScope): KnowledgeHits {
        if (url == null) return KnowledgeHits()
        val result = knowledgeStore.query(url, taskText)
        val hit = toHit(result) ?: return KnowledgeHits()
        return KnowledgeHits(listOf(hit), rendered = renderHit(hit))
    }

    override suspend fun deposit(taskId: String, scope: MemoryScope): Boolean {
        if (!deposited.add(taskId)) return false
        return runCatching {
            val events = queryService.traceTask(taskId)
            val started = events.filterIsInstance<TaskStarted>().firstOrNull() ?: return false
            val completed = events.filterIsInstance<Completed>().lastOrNull()
            val failed = events.filterIsInstance<Failed>().lastOrNull()
            val url = started.urlCandidate
            if (url.isNullOrBlank() || !url.startsWith("http")) return false

            val outcome = if (failed != null) "failure" else "success"
            val instruction = started.instruction
            val classifiedIntent = Intent.classify(instruction)
            val intentKey = classifiedIntent.name.lowercase()
            val domainName = UrlNormalizer.extractDomain(url)

            val failureCategory = if (failed != null) {
                FailureCategory.classify(failed.errorBrief, null).name.lowercase()
            } else null

            val trace = TraceRecord(
                traceId = UUID.randomUUID().toString(),
                intent = intentKey,
                taskType = taskTypeOf(instruction),
                domain = domainName,
                url = url,
                urlPattern = urlPatternOf(url),
                outcome = outcome,
                failureCategory = failureCategory,
                actions = events.filterIsInstance<ToolExecuted>().mapIndexed { i, t ->
                    ActionStep(
                        sequence = i + 1,
                        action = t.tool,
                        value = t.argsBrief,
                        result = t.resultBrief,
                        durationMs = t.durationMs,
                        timestamp = t.timestamp,
                    )
                },
                finalState = PageState(
                    url = events.filterIsInstance<PageViewed>().lastOrNull()?.url,
                    title = events.filterIsInstance<PageViewed>().lastOrNull()?.title,
                ),
                durationMs = completed?.durationMs,
                errorMessage = failed?.errorBrief,
            )

            knowledgeStore.saveTrace(trace)
            knowledgeStore.updateStats(trace)

            // Hypothesis facts so list/query find the entry without deep_learn.
            if (knowledgeStore.loadFacts(domainName, intentKey) == null) {
                knowledgeStore.saveFacts(KnowledgeFacts.createHypothesis(intentKey, domainName, urlPatternOf(url)))
            }

            // Sampled promotion (throttled per (domain,intent)); never throws.
            promoteSampled(domainName, intentKey)
            true
        }.onFailure { e ->
            logger.warn("memory.pem.deposit failed for task {}: {}", taskId, e.message)
            deposited.remove(taskId) // allow a later retry
            false
        }.getOrDefault(false)
    }

    /** Promotion sampling: run only when the interval elapsed and stats allow. */
    private suspend fun promoteSampled(domain: String, intent: String) {
        val key = "$domain/$intent"
        val now = System.currentTimeMillis()
        val last = lastPromotedAt[key] ?: 0
        if (now - last < minPromoteIntervalMinutes * 60_000) return
        val stats = knowledgeStore.loadStats(domain, intent)
        if (stats.confidence >= 0.90) return
        runCatching {
            knowledgeStore.promoteToVerified(domain, intent)
            lastPromotedAt[key] = System.currentTimeMillis()
        }.onFailure { logger.warn("memory.pem.promote failed for {}: {}", key, it.message) }
    }

    private fun toHit(result: ExperienceQueryResult): KnowledgeHit? {
        if (result.tier == "P5" || result.confidence <= 0) return null
        val selectors = result.primarySelectors?.entries?.take(3)
            ?.joinToString("; ") { (k, v) -> "$k=$v" } ?: ""
        val blockers = result.knownBlockers?.take(2)?.joinToString("; ") { b ->
            "[${b.type}] ${b.note ?: b.selector ?: ""}"
        } ?: ""
        val snippet = listOf(
            result.domain?.let { "域 $it" },
            result.urlPattern?.let { "模式 $it" },
            selectors.takeIf { it.isNotBlank() }?.let { "选择器: $it" },
            blockers.takeIf { it.isNotBlank() }?.let { "障碍: $it" },
            result.warnings?.take(2)?.joinToString("; "),
        ).filterNotNull().joinToString(" · ")
        if (snippet.isBlank()) return null
        return KnowledgeHit(
            tier = result.tier,
            confidence = result.confidence,
            domain = result.domain,
            intent = result.intent,
            snippet = snippet.take(300),
        )
    }

    private fun renderHit(hit: KnowledgeHit): String =
        "- [L1] ${hit.tier} 置信度 ${"%.2f".format(hit.confidence)} · ${hit.snippet}"

    private fun taskTypeOf(instruction: String): String? = when {
        instruction.contains(Regex("(?i)(extract|scrape|fetch|get data)")) -> "extract"
        instruction.contains(Regex("(?i)(search|find|query)")) -> "search"
        instruction.contains(Regex("(?i)(login|sign ?in)")) -> "login"
        instruction.contains(Regex("(?i)(fill|form|register|sign ?up)")) -> "fill_form"
        instruction.contains(Regex("(?i)(navigate|go to|visit|open)")) -> "navigate"
        instruction.contains(Regex("(?i)(compare|vs|versus)")) -> "compare"
        else -> null
    }

    private fun urlPatternOf(normalizedUrl: String): String {
        val path = UrlNormalizer.extractPath(normalizedUrl)
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return "/*"
        val last = segments.last()
        val isLikelyId = last.any { it.isDigit() } && last.length > 4 && !last.all { it.isLetter() }
        return if (isLikelyId) "/" + segments.dropLast(1).joinToString("/") + "/*"
        else "/" + segments.joinToString("/")
    }
}
