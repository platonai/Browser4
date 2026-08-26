package ai.platon.pulsar.agentic.memory

import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.getLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Path

/**
 * Facade of the generic agent memory system for one agent (or the shared
 * backend when [scope] has no agentUuid).
 *
 * - [sink] — unified write path (engine observation points);
 * - [queryService] — exact reads + search (live-preferred, SQLite FTS index);
 * - [scratchpad] — working-memory write board;
 * - [recall] — run-start injection section (L0 facts + L1 knowledge);
 * - [knowledgeProvider] / [consolidator] — L1 PEM fusion (M3).
 *
 * Design: docs-dev/copilot/robust-browser-agent-memory-system-design.md (§4).
 */
open class AgentMemory(
    val scope: MemoryScope,
    rootDir: Path = defaultRootDir(),
    enabled: Boolean = MemoryConfig.enabled,
    /** PEM knowledge store root; defaults to the PEM `knowledge.dir` convention. */
    knowledgeDir: Path? = null,
) : AutoCloseable {

    private val logger = getLogger(AgentMemory::class)
    private val memoryJob = SupervisorJob()
    private val memoryScope = CoroutineScope(Dispatchers.Default + memoryJob)

    val eventLog: AgentEventLog = AgentEventLog(rootDir, MemoryConfig.logTtlDays)
    val buffer: EventBuffer = EventBuffer(MemoryConfig.bufferSize)

    /**
     * Derived FTS search index (M2, SQLite FTS5). Disposable and lazily
     * aligned: a missing/corrupt index only degrades search. Creation failures
     * never propagate — memory must not break the agent.
     */
    val queryIndex: MemoryQueryIndex? = if (enabled && MemoryConfig.indexEnabled) {
        runCatching {
            val name = scope.agentUuid?.let { "memory-index-$it.sqlite" } ?: "memory-index.sqlite"
            SqliteMemoryQueryIndex(rootDir.resolve(name), eventLog, agentUuid = scope.agentUuid)
        }.onFailure {
            logger.warn("memory.index.init failed, search degraded to log scan: {}", it.message)
        }.getOrNull()
    } else null

    val queryService: MemoryQueryService =
        DefaultMemoryQueryService(eventLog, buffer, enabled, index = queryIndex)

    /** [knowledgeDir] parameter, then the `knowledge.dir` system property, else null. */
    private val resolvedKnowledgeDir: Path? = knowledgeDir
        ?: System.getProperty("knowledge.dir")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }

    /** L1 knowledge layer (PEM), created when consolidation is enabled (M3). */
    val knowledgeProvider: MemoryKnowledgeProvider? = if (enabled && MemoryConfig.consolidationEnabled) {
        runCatching {
            PemKnowledgeProvider(
                // Explicit param wins; otherwise honor the PEM `knowledge.dir`
                // system property (engine-side memory must not fall back to a
                // cwd-relative "knowledge" when the property is configured);
                // last resort keeps the PEM default (relative "knowledge").
                knowledgeStore = (resolvedKnowledgeDir?.let { ai.platon.pulsar.agentic.tools.experience.KnowledgeStore(it) }
                    ?: ai.platon.pulsar.agentic.tools.experience.KnowledgeStore())
                    .apply { initializeStore() },
                queryService = queryService,
            )
        }.onFailure {
            logger.warn("memory.pem.init failed, knowledge layer disabled: {}", it.message)
        }.getOrNull()
    } else null

    /** Background L0→L1 consolidator; schedules deposits after task completion. */
    val consolidator: MemoryConsolidator? = knowledgeProvider?.let {
        MemoryConsolidator(it, scope, memoryScope)
    }

    /**
     * L2 external memory MCP bridge (M4; default off). Connects asynchronously
     * on creation; the engine registers its discovered tools after
     * [ai.platon.pulsar.agentic.memory.external.MemoryExternalBridge.awaitConnected].
     */
    val externalBridge: ai.platon.pulsar.agentic.memory.external.MemoryExternalBridge? =
        if (enabled && MemoryConfig.externalEnabled) {
            runCatching {
                ai.platon.pulsar.agentic.memory.external.MemoryExternalBridge(
                    ai.platon.pulsar.agentic.memory.external.ExternalMemoryConfig.fromSystem(),
                    memoryScope,
                )
            }.onFailure {
                logger.warn("memory.external.init failed, L2 bridge disabled: {}", it.message)
            }.getOrNull()
        } else null

    val scratchpad: TaskScratchpad = TaskScratchpad()
    val sink: AgentMemorySink = AgentMemorySink(eventLog, buffer, enabled)

    /** User preference memory (design §9): explicit preferences + visited domains. */
    val profile: AgentProfile = AgentProfile(rootDir, principal = scope.agentUuid ?: "default")

    val recall: MemoryRecallService =
        MemoryRecallService(queryService, knowledgeProvider = knowledgeProvider, profile = profile)

    /** The task id of the run currently being observed (set by the engine). */
    @Volatile
    var currentTaskId: String? = null

    /** Write a scratchpad note and record the NoteWritten event. */
    fun note(key: String, value: String, taskId: String? = null): String {
        val result = scratchpad.note(key, value)
        val agentUuid = scope.agentUuid ?: "default"
        sink.noteWritten(taskId ?: currentTaskId ?: "unknown", agentUuid, key, value)
        return result
    }

    /**
     * Rolling hygiene on construction (engine-side counterpart of the Spring
     * shared backend's boot archive): move expired raw event files to
     * `.archive` so per-agent logs never grow without bound. Best-effort.
     */
    init {
        if (enabled) {
            runCatching { eventLog.archiveExpired() }
                .onFailure { logger.warn("memory.event.archive failed: {}", it.message) }
        }
    }

    /** Count one visited domain (called by the engine after task start). */
    fun recordTaskDomain(urlCandidate: String?) {
        val domain = AgentProfile.extractDomain(urlCandidate) ?: return
        profile.increment("domain_count:$domain")
    }

    /** Extract explicit user preferences from a completion summary (conservative). */
    fun applyUserPreferences(text: String?) {
        profile.applyExplicitPrefs(text)
    }

    override fun close() {
        runCatching { queryIndex?.close() }
            .onFailure { logger.warn("memory.index.close failed: {}", it.message) }
        runCatching { externalBridge?.close() }
        runCatching { memoryScope.cancel() }
    }

    companion object {
        /** `<APP_DATA>/memory` — the durable home of events + index. */
        fun defaultRootDir(): Path = AppContext.APP_DATA_DIR.resolve("memory")
    }
}
