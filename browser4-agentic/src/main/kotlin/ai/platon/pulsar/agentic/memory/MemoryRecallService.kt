package ai.platon.pulsar.agentic.memory

/**
 * Run-start recall: renders the `## Memory` injection section for the system
 * prompt from the L0 event log (and, in M3, L1 PEM knowledge via the
 * knowledge provider).
 *
 * Discipline (aligned with the DSH reference): the injected section is a
 * bounded *directory* — the model asks for details through the explicit
 * `memory.read` tool, never via implicit full-text injection. An empty recall
 * renders an empty string (no tokens spent).
 */
class MemoryRecallService(
    private val queryService: MemoryQueryService,
    private val maxChars: Int = MemoryConfig.recallMaxChars,
    private val enabled: Boolean = MemoryConfig.autoRecall && MemoryConfig.enabled,
    private val knowledgeProvider: MemoryKnowledgeProvider? = null,
    private val profile: AgentProfile? = null,
) {

    /**
     * Recall relevant L0 facts (+ L1 knowledge when fused) for [taskText]
     * within [scope]. Returns the rendered section (empty when disabled,
     * blank input, or no hits at any layer — no tokens spent).
     *
     * [excludeTaskId] excludes the CURRENT task: the engine writes its
     * TaskStarted event before recalling, so without the exclusion the
     * section would hit the task's own freshly-written events (self-reference
     * noise — observed in real-environment e2e).
     */
    suspend fun recall(taskText: String, scope: MemoryScope, excludeTaskId: String? = null): String {
        if (!enabled || taskText.isBlank()) return ""
        val keywords = MemoryKeywords.extract(taskText, max = 8)
        if (keywords.isEmpty()) return ""

        val url = Sanitizer.extractUrl(taskText)
        val hits = queryService.searchEvents(
            keywords.joinToString(" "),
            SearchFilters(agentUuid = scope.agentUuid),
            limit = 5,
        ).hits.filter { excludeTaskId == null || it.taskId != excludeTaskId }
        val knowledge = knowledgeProvider?.query(taskText, url, scope)?.rendered ?: ""
        val prefs = profile?.render() ?: ""
        if (hits.isEmpty() && knowledge.isEmpty() && prefs.isEmpty()) return ""
        return render(hits, url, knowledge, prefs).take(maxChars)
    }

    private fun render(hits: List<SearchHit>, url: String?, knowledge: String, prefs: String): String = buildString {
        append("\n\n## Memory（自动召回；仅供参考，使用前验证）\n")
        hits.forEach { h ->
            val tool = h.tool?.let { " · $it" } ?: ""
            append("- [L0] ${DateTimes.brief(h.ts)}$tool · ${Sanitizer.brief(h.snippet, 140)}\n")
        }
        if (knowledge.isNotBlank()) {
            append(knowledge)
            if (!knowledge.endsWith('\n')) append('\n')
        }
        if (prefs.isNotBlank()) {
            append(prefs)
            if (!prefs.endsWith('\n')) append('\n')
        }
        url?.let { append("- 目标 URL: $it\n") }
    }

    private object DateTimes {
        fun brief(epochMs: Long): String {
            val d = java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault())
            return String.format("%04d-%02d-%02d %02d:%02d",
                d.year, d.monthValue, d.dayOfMonth, d.hour, d.minute)
        }
    }
}
