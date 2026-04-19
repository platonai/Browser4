package ai.platon.pulsar.agentic.inference.history

import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.serialize.json.Pson

class DefaultHistoryRenderStrategy(
    private val maxCharacters: Int = 4_000,
    private val recentStepsToKeep: Int = 6,
    private val summaryStepLimit: Int = 6,
) : HistoryRenderStrategy {

    override fun render(agentHistory: AgentHistory, stateHistoryPath: String?): String {
        val history = agentHistory.states
        if (history.isEmpty()) {
            return ""
        }

        val recentStates = history.takeLast(recentStepsToKeep)
        val olderStates = history.dropLast(recentStates.size)
        val summarizedOlderStates = summarizeOlderStates(olderStates)

        val sections = buildList {
            add("")
            add("## Execution History")
            add("")
            add(
                if (olderStates.isEmpty()) {
                    "(showing all ${history.size} recorded steps)"
                } else {
                    "(compressed ${olderStates.size} earlier steps, keeping ${recentStates.size} most recent steps in detail)"
                }
            )
            add("")
            if (summarizedOlderStates.isNotEmpty()) {
                add("")
                add("### Earlier Steps Summary")
                add(summarizedOlderStates)
                add("")
            }
            add("")
            add("### Recent Steps")
            renderHistoryWithBudget(recentStates, this, stateHistoryPath)
            if (olderStates.isNotEmpty()) {
                add("")
                if (stateHistoryPath != null) {
                    add("Older steps remain available in persisted agent state history logs: $stateHistoryPath")
                } else {
                    add("Older steps remain available in persisted agent state history logs and checkpoints.")
                }
            } else if (stateHistoryPath != null) {
                add("")
                add("Full agent state history logs available at: $stateHistoryPath")
            }
            add("")
            add("---")
        }

        val rendered = sections.joinToString("\n")
        return rendered
    }

    private fun summarizeOlderStates(states: List<AgentState>): String {
        if (states.isEmpty()) {
            return ""
        }

        return states
            .chunked(summaryStepLimit)
            .joinToString("\n") { chunk ->
                val start = chunk.first().step
                val end = chunk.last().step
                val methods = chunk.mapNotNull { it.method }.distinct().joinToString(", ").ifBlank { "N/A" }
                val nextGoals = chunk.mapNotNull { it.nextGoal }.take(2).joinToString(" | ")
                val failures = chunk.count { it.hasErrors }
                val notes = listOfNotNull(
                    "\"stepRange\":\"$start-$end\"",
                    "\"methods\":\"${Strings.compactInline(methods, 80)}\"",
                    "\"failures\":$failures",
                    nextGoals.takeIf { it.isNotBlank() }?.let { "\"nextGoals\":\"${escapeJson(Strings.compactInline(it, 200))}\"" }
                )
                "{${notes.joinToString(",")}}"
            }
    }

    private fun renderHistoryWithBudget(recentStates: List<AgentState>, output: MutableList<String>, logPath: String? = null) {
        if (recentStates.isEmpty()) {
            return
        }

        var i = 0
        var renderedLength = 0
        var averageLength = 0
        var estimatedTotalLength = 0
        var hasCompressedHistory = false

        recentStates.forEach {
            ++i

            val compress = estimatedTotalLength > maxCharacters && i < recentStates.size - 2
            if (compress) {
                hasCompressedHistory = true
            }
            val rendered = renderDetailedState(it, compress)
            renderedLength += rendered.length
            averageLength = renderedLength / i
            estimatedTotalLength = averageLength * recentStates.size

            output.add(rendered)
        }

        val suffix = if (hasCompressedHistory) {
            val details = if (logPath != null) " at $logPath" else ""
            "\n\n(history budget applied; see persisted task logs$details for full details)"
        } else {
            "\n\n(history budget applied over ${recentStates.size} steps)"
        }

        output.add(suffix)
    }

    private fun renderDetailedState(state: AgentState, compress: Boolean = false): String {
        return Pson.toJson(
            mapOf(
                "step" to state.step,
                "toolCall" to state.actionDescription?.pseudoExpression,
                "exception" to state.exception?.brief(),
                "summary" to state.summary,
                "nextGoal" to state.nextGoal.takeIf { !compress },
                "thinking" to state.thinking.takeIf { !compress },
                "keyFindings" to state.keyFindings.takeIf { !compress }
            )
        )
    }

    private fun trimToBudget(rendered: String, totalSteps: Int, hasCompressedHistory: Boolean): String {
        if (rendered.length <= maxCharacters) {
            return rendered
        }

        val truncated = Strings.compactInline(rendered, maxCharacters)
        val suffix = if (hasCompressedHistory) {
            "\n\n(history budget applied; see persisted task logs for full details)"
        } else {
            "\n\n(history budget applied over $totalSteps steps)"
        }
        return truncated + suffix
    }

    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    }
}
