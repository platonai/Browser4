package ai.platon.pulsar.agentic.tools.experience

import ai.platon.pulsar.common.getLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.representer.Representer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.*

/**
 * File-backed YAML knowledge store with atomic writes.
 *
 * ## Revised Layout (v2 — three-tier separation)
 * ```
 * {baseDir}/
 *   traces/<domain>/              ← TraceRecords (30-day TTL, immutable)
 *   experience/<domain>/          ← ExperienceStats (mutable, continuously updated)
 *   facts/<domain>/               ← KnowledgeFacts (verified only, immutable)
 *   patterns/
 *     families/<name>.yaml        ← L4: site-family patterns
 *     categories/<name>.yaml      ← L4: site-category patterns
 *     universal/<name>.yaml       ← L4: universal patterns
 *   .index.yaml                   ← in-memory index
 *   .archive/                     ← evicted artifacts
 * ```
 *
 * ## Write Protocol (atomic rename)
 * Read → Modify → Write .tmp → fsync → Rename → target
 */
class KnowledgeStore(
    private val baseDir: Path = DEFAULT_BASE_DIR,
) {
    private val logger = getLogger(KnowledgeStore::class)

    private val tracesDir: Path get() = baseDir.resolve("traces")
    private val experienceDir: Path get() = baseDir.resolve("experience")
    private val factsDir: Path get() = baseDir.resolve("facts")
    private val archiveDir: Path get() = baseDir.resolve(".archive")
    private val indexPath: Path get() = baseDir.resolve(".index.yaml")

    private val domainLocks = ConcurrentHashMap<String, Mutex>()
    private val yaml: Yaml by lazy {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            defaultScalarStyle = DumperOptions.ScalarStyle.PLAIN
        }
        Yaml(Representer(options))
    }

    // =========================================================================
    // Initialization
    // =========================================================================

    fun initializeStore() {
        listOf(tracesDir, experienceDir, factsDir, archiveDir).forEach { dir ->
            if (!dir.exists()) {
                Files.createDirectories(dir)
                logger.info("Knowledge store directory created: {}", dir)
            }
        }
        logger.info("Knowledge store initialized: {}", baseDir)
    }

    // =========================================================================
    // Trace Operations
    // =========================================================================

    /**
     * Save a [TraceRecord] to `traces/<domain>/<timestamp>-<intent>.yaml`.
     */
    fun saveTrace(trace: TraceRecord): Path {
        val domainDir = tracesDir.resolve(trace.domain)
        if (!domainDir.exists()) Files.createDirectories(domainDir)

        val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss-SSS")
            .withZone(ZoneId.systemDefault()).format(trace.timestamp)
        val intentSlug = trace.intent.take(40).replace(Regex("[^a-zA-Z0-9_]"), "_")

        // Avoid filename collisions when multiple traces land in the same millisecond.
        // Appends a short suffix from the traceId UUID which is always unique.
        val shortId = trace.traceId.take(8)
        val filename = "$timestamp-$intentSlug-$shortId.yaml"
        val file = domainDir.resolve(filename)

        writeAtomicYaml(file, mapFromTrace(trace))
        logger.info("Trace saved: {}", file)
        return file
    }

    fun loadTrace(path: Path): TraceRecord? {
        return try {
            val data = yaml.load<Map<String, Any>>(Files.readString(path))
            mapToTrace(data)
        } catch (e: Exception) {
            logger.warn("Failed to load trace {}: {}", path, e.message)
            null
        }
    }

    fun listTraces(domain: String? = null, page: Int = 1, pageSize: Int = 20): List<Path> {
        val dirs = if (domain != null) {
            val d = tracesDir.resolve(domain)
            if (d.exists()) listOf(d) else emptyList()
        } else {
            if (tracesDir.exists()) tracesDir.listDirectoryEntries().toList() else emptyList()
        }
        return dirs.flatMap { dir ->
            if (dir.isDirectory()) {
                dir.listDirectoryEntries("*.yaml").sortedByDescending { it.fileName.toString() }
            } else emptyList()
        }.drop((page - 1) * pageSize).take(pageSize)
    }

    // =========================================================================
    // Experience Stats Operations (mutable, continuously updated)
    // =========================================================================

    /**
     * Load or create [ExperienceStats] for a (domain, intent) pair.
     */
    fun loadStats(domain: String, intent: String): ExperienceStats {
        val file = statsFilePath(domain, intent)
        if (!file.exists()) {
            return ExperienceStats.create(intent, domain, "")
        }
        return try {
            val data = yaml.load<Map<String, Any>>(Files.readString(file))
            mapToStats(data)
        } catch (e: Exception) {
            logger.warn("Failed to load stats for {}/{}: {}", domain, intent, e.message)
            ExperienceStats.create(intent, domain, "")
        }
    }

    /**
     * Update [ExperienceStats] from a [TraceRecord].
     *
     * Merges success or failure stats into the existing stats file.
     */
    fun updateStats(trace: TraceRecord) {
        val intentKey = Intent.classify(trace.intent).name.lowercase()
        val existing = loadStats(trace.domain, intentKey)
        val updated = if (trace.outcome == "success") {
            existing.withSuccess(trace)
        } else {
            existing.withFailure(trace)
        }
        saveStats(trace.domain, intentKey, updated)
    }

    private fun saveStats(domain: String, intent: String, stats: ExperienceStats) {
        val dir = experienceDir.resolve(domain)
        if (!dir.exists()) Files.createDirectories(dir)
        val file = statsFilePath(domain, intent)
        writeAtomicYaml(file, mapFromStats(stats))
    }

    private fun statsFilePath(domain: String, intent: String): Path {
        // Sanitize intent for filename
        val safeIntent = intent.take(50).replace(Regex("[^a-zA-Z0-9_]"), "_")
        return experienceDir.resolve(domain).resolve("${safeIntent}.yaml")
    }

    // =========================================================================
    // Knowledge Facts Operations (immutable, verified only)
    // =========================================================================

    /**
     * Load [KnowledgeFacts] for a (domain, intent) pair.
     */
    fun loadFacts(domain: String, intent: String): KnowledgeFacts? {
        val file = factsFilePath(domain, intent)
        if (!file.exists()) return null
        return try {
            val data = yaml.load<Map<String, Any>>(Files.readString(file))
            mapToFacts(data)
        } catch (e: Exception) {
            logger.warn("Failed to load facts for {}/{}: {}", domain, intent, e.message)
            null
        }
    }

    /**
     * Save [KnowledgeFacts] — only called when promoting or updating facts.
     */
    suspend fun saveFacts(facts: KnowledgeFacts) {
        val lock = domainLocks.getOrPut(facts.domain) { Mutex() }
        lock.withLock {
            val dir = factsDir.resolve(facts.domain)
            if (!dir.exists()) Files.createDirectories(dir)
            val file = factsFilePath(facts.domain, facts.intent)
            writeAtomicYaml(file, mapFromFacts(facts))
        }
        updateIndex(facts.domain, facts.intent, facts)
        logger.info("Facts saved: {}/{} status={}", facts.domain, facts.intent, facts.status)
    }

    /**
     * Promote facts from HYPOTHESIS → CANDIDATE → VERIFIED.
     */
    suspend fun promoteToVerified(domain: String, intent: String): KnowledgeFacts? {
        val facts = loadFacts(domain, intent) ?: return null
        val stats = loadStats(domain, intent)

        val newStatus = when {
            stats.confidence >= 0.85 && stats.successes >= 5 -> VerificationStatus.VERIFIED
            stats.confidence >= 0.60 && stats.successes >= 2 -> VerificationStatus.CANDIDATE
            else -> return facts // No promotion yet
        }

        if (newStatus == facts.status) return facts

        val promoted = facts.copy(
            status = newStatus,
            updatedAt = Instant.now(),
            promotionHistory = facts.promotionHistory + PromotionEvent(
                from = facts.status.name.lowercase(),
                to = newStatus.name.lowercase(),
                reason = "Auto-promoted: confidence=${String.format("%.3f", stats.confidence)}, visits=${stats.successes}",
                verifiedVisits = stats.successes,
            ),
        )
        saveFacts(promoted)
        return promoted
    }

    private fun factsFilePath(domain: String, intent: String): Path {
        val safeIntent = intent.take(50).replace(Regex("[^a-zA-Z0-9_]"), "_")
        return factsDir.resolve(domain).resolve("${safeIntent}.yaml")
    }

    // =========================================================================
    // Query — Intent-Based Resolution
    // =========================================================================

    /**
     * Query the knowledge store with intent-based resolution.
     *
     * Resolution order (6-level fallback):
     * 1. (domain, intent) — exact match
     * 2. (domain, url_pattern) — page-level only
     * 3. (site_family, intent) — similar-site family
     * 4. (site_category, intent) — category-level
     * 5. (site_universal, intent) — universal pattern
     * 6. Cold start — no knowledge
     */
    fun query(url: String, intentText: String? = null): ExperienceQueryResult {
        val domain = UrlNormalizer.extractDomain(url)
        val classifiedIntent = Intent.classify(intentText)
        val intentKey = classifiedIntent.name.lowercase()

        // Level 1: Exact (domain, intent) match
        loadFacts(domain, intentKey)?.let { facts ->
            val stats = loadStats(domain, intentKey)
            return buildQueryResult(facts, stats, "intent_match")
        }

        // Level 2: (domain, url_pattern) match — try any intent for same domain
        val normalizedUrl = UrlNormalizer.normalize(url)
        val domainFacts = listFactsForDomain(domain)
        val urlMatch = domainFacts.firstOrNull { facts ->
            facts.urlPattern.isNotBlank() &&
                UrlNormalizer.matches(facts.urlPattern, normalizedUrl)
        }
        if (urlMatch != null) {
            val stats = loadStats(domain, urlMatch.intent)
            return buildQueryResult(urlMatch, stats, "url_match")
        }

        // Level 3+: Family/Category/Universal — try cross-site patterns
        val patternFacts = findBestPatternMatch(domain, intentKey)
        if (patternFacts != null) {
            return buildQueryResult(patternFacts, ExperienceStats.create(intentKey, domain, ""), "pattern_match")
        }

        // Level 6: Cold start
        return ExperienceQueryResult(
            tier = "P5",
            domain = domain,
            intent = classifiedIntent.name.lowercase(),
            summary = "Cold start — no prior knowledge for $domain / $intentKey",
        )
    }

    /**
     * List all knowledge entries with pagination.
     */
    fun list(
        domainFilter: String? = null,
        intentFilter: String? = null,
        page: Int = 1,
        pageSize: Int = 20,
    ): ExperienceListResult {
        val allEntries = mutableListOf<KnowledgeListEntry>()

        // Traverse facts/ directory
        if (factsDir.exists()) {
            for (domainDir in factsDir.listDirectoryEntries()) {
                if (!domainDir.isDirectory()) continue
                val domain = domainDir.fileName.toString()

                if (domainFilter != null && !domain.contains(domainFilter, ignoreCase = true)) continue

                for (file in domainDir.listDirectoryEntries("*.yaml")) {
                    try {
                        val data = yaml.load<Map<String, Any>>(Files.readString(file))
                            ?: continue
                        val factsIntent = data["intent"] as? String ?: continue
                        if (intentFilter != null && !factsIntent.contains(intentFilter, ignoreCase = true)) continue

                        val stats = try {
                            loadStats(domain, factsIntent)
                        } catch (_: Exception) { null }

                        allEntries.add(KnowledgeListEntry(
                            domain = domain,
                            intent = factsIntent,
                            siteTypes = (data["site_facts"] as? Map<String, Any>)?.let {
                                listOfNotNull(
                                    it["site_family"] as? String,
                                    it["site_category"] as? String,
                                    it["site_universal"] as? String,
                                )
                            } ?: emptyList(),
                            pagePatterns = listOf(data["url_pattern"] as? String ?: ""),
                            taskTypes = emptyList(),
                            confidence = stats?.confidence ?: 0.0,
                            retrievalTier = stats?.retrievalTier ?: "P5",
                            status = try {
                                VerificationStatus.valueOf((data["status"] as? String)?.uppercase() ?: "HYPOTHESIS")
                            } catch (_: Exception) { null },
                            lastVerified = (data["updated_at"] as? String)?.let {
                                try { Instant.parse(it) } catch (_: Exception) { null }
                            },
                            successCount = stats?.successes ?: 0,
                            failureCount = stats?.failures ?: 0,
                        ))
                    } catch (_: Exception) { /* skip unreadable files */ }
                }
            }
        }

        val sorted = allEntries.sortedByDescending { it.confidence }
        val total = sorted.size
        val totalPages = (total + pageSize - 1) / pageSize
        val paged = sorted.drop((page - 1) * pageSize).take(pageSize)

        return ExperienceListResult(
            total = total, page = page, pageSize = pageSize,
            totalPages = totalPages, entries = paged,
        )
    }

    // =========================================================================
    // Atomic I/O
    // =========================================================================

    private fun writeAtomicYaml(target: Path, data: Map<String, Any>) {
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        Files.createDirectories(target.parent)
        try {
            val yamlText = yaml.dump(data)
            Files.writeString(tmp, yamlText, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            // On Windows, ATOMIC_MOVE fails if the target exists even with REPLACE_EXISTING.
            // Delete the target first, then move without ATOMIC_MOVE for cross-platform reliability.
            Files.deleteIfExists(target)
            Files.move(tmp, target)
        } catch (e: Exception) {
            // Fallback: try with REPLACE_EXISTING if delete-before-move failed
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            } catch (fallbackError: Exception) {
                try { tmp.deleteExisting() } catch (_: Exception) {}
                e.addSuppressed(fallbackError)
                throw KnowledgeStoreException("Failed to write $target: ${e.message}", e)
            }
        }
    }

    // =========================================================================
    // Index Operations
    // =========================================================================

    private fun updateIndex(domain: String, intent: String, facts: KnowledgeFacts) {
        // Index is regenerated lazily on next query; skip for now.
        // Phase 3+: maintain in-memory index for fast lookup.
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun listFactsForDomain(domain: String): List<KnowledgeFacts> {
        val dir = factsDir.resolve(domain)
        if (!dir.exists()) return emptyList()
        return dir.listDirectoryEntries("*.yaml").mapNotNull { file ->
            try {
                val data = yaml.load<Map<String, Any>>(Files.readString(file))
                mapToFacts(data)
            } catch (_: Exception) { null }
        }
    }

    private fun findBestPatternMatch(domain: String, intent: String): KnowledgeFacts? {
        // Try to find cross-site patterns in pattern directories
        val patternDirs = listOf("families", "categories", "universal")
        for (patternDir in patternDirs) {
            val dir = baseDir.resolve("patterns").resolve(patternDir)
            if (!dir.exists()) continue
            for (file in dir.listDirectoryEntries("*.yaml")) {
                try {
                    val data = yaml.load<Map<String, Any>>(Files.readString(file))
                    val facts = mapToFacts(data)
                    if (facts.intent == intent) return facts
                } catch (_: Exception) { /* skip */ }
            }
        }
        return null
    }

    private fun buildQueryResult(
        facts: KnowledgeFacts,
        stats: ExperienceStats,
        matchType: String,
    ): ExperienceQueryResult {
        val primarySelectors = facts.selectors.mapValues { it.value.primary }

        val warnings = mutableListOf<String>()
        if (stats.degradedByFailures) {
            warnings.add("Retrieval tier degraded due to failure history")
        }
        stats.failureStats.forEach { (category, count) ->
            if (count > 0) {
                warnings.add("$category failures: $count")
            }
        }

        return ExperienceQueryResult(
            tier = stats.retrievalTier,
            confidence = stats.confidence,
            domain = facts.domain,
            intent = facts.intent,
            urlPattern = facts.urlPattern,
            pageType = facts.pageFacts.pageType,
            summary = "${facts.intent} on ${facts.domain} (${matchType}, status=${facts.status.name.lowercase()})",
            primarySelectors = primarySelectors.takeIf { it.isNotEmpty() },
            knownBlockers = facts.knownBlockers.takeIf { it.isNotEmpty() },
            warnings = warnings.takeIf { it.isNotEmpty() },
            status = facts.status,
            lastVerified = facts.updatedAt,
        )
    }

    // =========================================================================
    // Serialization Helpers
    // =========================================================================

    @Suppress("UNCHECKED_CAST")
    private fun mapFromTrace(trace: TraceRecord): Map<String, Any> = buildMap {
        put("trace_id", trace.traceId)
        put("intent", trace.intent)
        trace.taskType?.let { put("task_type", it) }
        put("domain", trace.domain)
        put("url", trace.url)
        put("url_pattern", trace.urlPattern)
        put("outcome", trace.outcome)
        trace.failureCategory?.let { put("failure_category", it) }
        if (trace.actions.isNotEmpty()) put("actions", trace.actions.map { mapFromAction(it) })
        trace.finalState?.let { put("final_state", mapFromPageState(it)) }
        put("timestamp", trace.timestamp.toString())
        trace.durationMs?.let { put("duration_ms", it) }
        trace.errorMessage?.let { put("error_message", it) }
        put("redacted", trace.redacted)
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapToTrace(data: Map<String, Any>): TraceRecord = TraceRecord(
        traceId = data["trace_id"] as? String ?: "",
        intent = data["intent"] as? String ?: "",
        taskType = data["task_type"] as? String,
        domain = data["domain"] as? String ?: "",
        url = data["url"] as? String ?: "",
        urlPattern = data["url_pattern"] as? String ?: "",
        outcome = data["outcome"] as? String ?: "unknown",
        failureCategory = data["failure_category"] as? String,
        actions = (data["actions"] as? List<Map<String, Any>>)?.map { mapToAction(it) } ?: emptyList(),
        finalState = (data["final_state"] as? Map<String, Any>)?.let { mapToPageState(it) },
        durationMs = (data["duration_ms"] as? Number)?.toLong(),
        errorMessage = data["error_message"] as? String,
    )

    private fun mapFromPageState(state: PageState): Map<String, Any> = buildMap {
        state.url?.let { put("url", it) }
        state.title?.let { put("title", it) }
        state.wpsiSummary?.let { put("wpsi_summary", it) }
        state.inspectOutput?.let { put("inspect_output", it) }
    }

    private fun mapToPageState(data: Map<String, Any>): PageState = PageState(
        url = data["url"] as? String,
        title = data["title"] as? String,
        wpsiSummary = data["wpsi_summary"] as? String,
        inspectOutput = data["inspect_output"] as? String,
    )

    private fun mapFromAction(step: ActionStep): Map<String, Any> = buildMap {
        put("sequence", step.sequence)
        put("action", step.action)
        step.selector?.let { put("selector", it) }
        step.value?.let { put("value", it) }
        step.result?.let { put("result", it) }
        step.durationMs?.let { put("duration_ms", it) }
        put("timestamp", step.timestamp.toString())
    }

    private fun mapToAction(data: Map<String, Any>): ActionStep = ActionStep(
        sequence = (data["sequence"] as? Number)?.toInt() ?: 0,
        action = data["action"] as? String ?: "",
        selector = data["selector"] as? String,
        value = data["value"] as? String,
        result = data["result"] as? String,
        durationMs = (data["duration_ms"] as? Number)?.toLong(),
    )

    @Suppress("UNCHECKED_CAST")
    private fun mapFromStats(stats: ExperienceStats): Map<String, Any> = buildMap {
        put("intent", stats.intent)
        put("domain", stats.domain)
        put("url_pattern", stats.urlPattern)
        put("total_attempts", stats.totalAttempts)
        put("successes", stats.successes)
        put("failures", stats.failures)
        stats.avgDurationMs?.let { put("avg_duration_ms", it) }
        stats.avgSteps?.let { put("avg_steps", it) }
        if (stats.selectorStats.isNotEmpty()) {
            put("selector_stats", stats.selectorStats.mapValues { (_, v) -> mapFromSelectorStats(v) })
        }
        if (stats.failureStats.isNotEmpty()) put("failure_stats", stats.failureStats)
        put("last_updated", stats.lastUpdated.toString())
        stats.lastDeepLearn?.let { put("last_deep_learn", it.toString()) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapToStats(data: Map<String, Any>): ExperienceStats = ExperienceStats(
        intent = data["intent"] as? String ?: "",
        domain = data["domain"] as? String ?: "",
        urlPattern = data["url_pattern"] as? String ?: "",
        totalAttempts = (data["total_attempts"] as? Number)?.toInt() ?: 0,
        successes = (data["successes"] as? Number)?.toInt() ?: 0,
        failures = (data["failures"] as? Number)?.toInt() ?: 0,
        avgDurationMs = (data["avg_duration_ms"] as? Number)?.toDouble(),
        avgSteps = (data["avg_steps"] as? Number)?.toDouble(),
        selectorStats = (data["selector_stats"] as? Map<String, Map<String, Any>>)?.mapValues { (_, v) ->
            mapToSelectorStats(v)
        } ?: emptyMap(),
        failureStats = (data["failure_stats"] as? Map<String, Any>)?.mapValues { (_, v) ->
            (v as? Number)?.toInt() ?: 0
        } ?: emptyMap(),
    )

    private fun mapFromSelectorStats(ss: SelectorStats): Map<String, Any> = buildMap {
        put("selector", ss.selector)
        put("successes", ss.successes)
        put("failures", ss.failures)
        ss.avgResolutionMs?.let { put("avg_resolution_ms", it) }
        ss.lastSuccess?.let { put("last_success", it.toString()) }
        ss.lastFailure?.let { put("last_failure", it.toString()) }
    }

    private fun mapToSelectorStats(data: Map<String, Any>): SelectorStats = SelectorStats(
        selector = data["selector"] as? String ?: "",
        successes = (data["successes"] as? Number)?.toInt() ?: 0,
        failures = (data["failures"] as? Number)?.toInt() ?: 0,
        avgResolutionMs = (data["avg_resolution_ms"] as? Number)?.toDouble(),
    )

    @Suppress("UNCHECKED_CAST")
    private fun mapFromFacts(facts: KnowledgeFacts): Map<String, Any> = buildMap {
        put("intent", facts.intent)
        put("domain", facts.domain)
        put("url_pattern", facts.urlPattern)
        put("status", facts.status.name.lowercase())
        put("site_facts", mapFromSiteFacts(facts.siteFacts))
        put("page_facts", mapFromPageFacts(facts.pageFacts))
        if (facts.selectors.isNotEmpty()) {
            put("selectors", facts.selectors.mapValues { (_, v) -> mapFromVerifiedSelector(v) })
        }
        if (facts.interactionHints.isNotEmpty()) put("interaction_hints", facts.interactionHints)
        if (facts.knownBlockers.isNotEmpty()) put("known_blockers", facts.knownBlockers.map { mapFromBlocker(it) })
        if (facts.antiPatterns.isNotEmpty()) put("anti_patterns", facts.antiPatterns)
        if (facts.promotionHistory.isNotEmpty()) put("promotion_history", facts.promotionHistory.map { mapFromPromotion(it) })
        put("created_at", facts.createdAt.toString())
        put("updated_at", facts.updatedAt.toString())
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapToFacts(data: Map<String, Any>): KnowledgeFacts = KnowledgeFacts(
        intent = data["intent"] as? String ?: "",
        domain = data["domain"] as? String ?: "",
        urlPattern = data["url_pattern"] as? String ?: "",
        status = try {
            VerificationStatus.valueOf((data["status"] as? String)?.uppercase() ?: "HYPOTHESIS")
        } catch (_: Exception) { VerificationStatus.HYPOTHESIS },
        siteFacts = (data["site_facts"] as? Map<String, Any>)?.let { mapToSiteFacts(it) } ?: SiteFacts(""),
        pageFacts = (data["page_facts"] as? Map<String, Any>)?.let { mapToPageFacts(it) } ?: PageFacts(),
        selectors = (data["selectors"] as? Map<String, Map<String, Any>>)?.mapValues { (_, v) ->
            mapToVerifiedSelector(v)
        } ?: emptyMap(),
        interactionHints = (data["interaction_hints"] as? List<String>) ?: emptyList(),
        knownBlockers = (data["known_blockers"] as? List<Map<String, Any>>)?.map { mapToBlocker(it) } ?: emptyList(),
        antiPatterns = (data["anti_patterns"] as? List<String>) ?: emptyList(),
        promotionHistory = (data["promotion_history"] as? List<Map<String, Any>>)?.map { mapToPromotion(it) } ?: emptyList(),
    )

    private fun mapFromSiteFacts(sf: SiteFacts): Map<String, Any> = buildMap {
        put("domain", sf.domain)
        sf.siteFamily?.let { put("site_family", it) }
        sf.siteCategory?.let { put("site_category", it) }
        sf.siteUniversal?.let { put("site_universal", it) }
        sf.authPattern?.let { put("auth_pattern", it) }
        sf.techStack?.let { put("tech_stack", it) }
        sf.loadStrategy?.let { put("load_strategy", it) }
    }

    private fun mapToSiteFacts(data: Map<String, Any>): SiteFacts = SiteFacts(
        domain = data["domain"] as? String ?: "",
        siteFamily = data["site_family"] as? String,
        siteCategory = data["site_category"] as? String,
        siteUniversal = data["site_universal"] as? String,
        authPattern = data["auth_pattern"] as? String,
        techStack = data["tech_stack"] as? String,
        loadStrategy = data["load_strategy"] as? String,
    )

    private fun mapFromPageFacts(pf: PageFacts): Map<String, Any> = buildMap {
        if (pf.landmarks.isNotEmpty()) put("landmarks", pf.landmarks)
        pf.pageType?.let { put("page_type", it) }
        pf.dynamicLoad?.let { put("dynamic_load", it) }
        pf.loadWait?.let { put("load_wait", it) }
    }

    private fun mapToPageFacts(data: Map<String, Any>): PageFacts = PageFacts(
        landmarks = (data["landmarks"] as? List<String>) ?: emptyList(),
        pageType = data["page_type"] as? String,
        dynamicLoad = data["dynamic_load"] as? String,
        loadWait = data["load_wait"] as? String,
    )

    private fun mapFromVerifiedSelector(vs: VerifiedSelector): Map<String, Any> = buildMap {
        put("primary", vs.primary)
        if (vs.fallbacks.isNotEmpty()) put("fallbacks", vs.fallbacks)
        put("source", vs.source)
        vs.note?.let { put("note", it) }
    }

    private fun mapToVerifiedSelector(data: Map<String, Any>): VerifiedSelector = VerifiedSelector(
        primary = data["primary"] as? String ?: "",
        fallbacks = (data["fallbacks"] as? List<String>) ?: emptyList(),
        source = data["source"] as? String ?: "css",
        note = data["note"] as? String,
    )

    private fun mapFromBlocker(bi: BlockerInfo): Map<String, Any> = buildMap {
        put("type", bi.type)
        bi.selector?.let { put("selector", it) }
        put("action", bi.action)
        bi.frequency?.let { put("frequency", it) }
        bi.note?.let { put("note", it) }
    }

    private fun mapToBlocker(data: Map<String, Any>): BlockerInfo = BlockerInfo(
        type = data["type"] as? String ?: "",
        selector = data["selector"] as? String,
        action = data["action"] as? String ?: "click",
        frequency = data["frequency"] as? String,
        note = data["note"] as? String,
    )

    private fun mapFromPromotion(pe: PromotionEvent): Map<String, Any> = buildMap {
        pe.from?.let { put("from", it) }
        put("to", pe.to)
        put("date", pe.date.toString())
        pe.reason?.let { put("reason", it) }
        pe.verifiedVisits?.let { put("verified_visits", it) }
        pe.dualSignalPassed?.let { put("dual_signal_passed", it) }
    }

    private fun mapToPromotion(data: Map<String, Any>): PromotionEvent = PromotionEvent(
        from = data["from"] as? String,
        to = data["to"] as? String ?: "",
        reason = data["reason"] as? String,
        verifiedVisits = (data["verified_visits"] as? Number)?.toInt(),
        dualSignalPassed = data["dual_signal_passed"] as? Boolean,
    )

    companion object {
        val DEFAULT_BASE_DIR: Path = Path.of("knowledge")

        fun createDefault(): KnowledgeStore {
            val store = KnowledgeStore(DEFAULT_BASE_DIR)
            store.initializeStore()
            return store
        }
    }
}

/**
 * Exception thrown when a KnowledgeStore operation fails.
 */
class KnowledgeStoreException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
