package ai.platon.pulsar.summarydemo

import ai.platon.pulsar.skeleton.workflow.parse.html.PageSummaryAlgorithm
import ai.platon.pulsar.skeleton.workflow.parse.html.PageSummaryInput

/**
 * Demo summary algorithm that emits a minimal plain-text outline instead of
 * the built-in WPSI YAML.
 */
class OutlineSummaryAlgorithm : PageSummaryAlgorithm {
    override val id = "outline-demo"
    override val displayName = "Outline Demo Summary"
    override val description = "Demo plugin algorithm: plain-text outline of headings."
    override val version = "1.0.0"

    override fun generate(input: PageSummaryInput): String {
        val headings = input.document.select("h1, h2, h3")
            .joinToString("\n") { "- [${it.tagName()}] ${it.text().take(80)}" }
        return buildString {
            appendLine("outline-demo summary")
            appendLine("url: ${input.pageUrl}")
            appendLine("title: ${input.title}")
            appendLine("headings:")
            append(headings.ifBlank { "  (none)" })
        }
    }
}
