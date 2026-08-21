package ai.platon.pulsar.agentic.prompts

import ai.platon.pulsar.agentic.inference.PromptBuilder.Companion.EXTRACTION_TOOL_NOTE_CONTENT
import ai.platon.pulsar.agentic.inference.PromptBuilder.Companion.TOOL_CALL_RULE_CONTENT
import ai.platon.pulsar.agentic.inference.PromptBuilder.Companion.buildResponseSchema
import ai.platon.pulsar.agentic.inference.PromptBuilder.Companion.workingLanguage
import ai.platon.pulsar.agentic.inference.action.OBSERVE_RESPONSE_COMPLETE_SCHEMA
import ai.platon.pulsar.agentic.skills.SkillRegistry
import ai.platon.pulsar.agentic.tools.specs.ToolCallSpecificationRenderer
import ai.platon.pulsar.agentic.tools.specs.ToolSpecFormat

/**
 * Note appended to the system prompt for coding tasks: page-state response
 * fields are irrelevant without a page — the model should fill "N/A" instead
 * of describing a non-existent page (design §3.5 / P4.3).
 */
private const val CODING_NO_PAGE_FIELDS_NOTE = """

**Coding task — no page context.** In your response, set `screenshotContentSummary`
and `currentPageContentSummary` to `"N/A"` — no browser page is attached to this
task. If you need web page information, explicitly call `tab.navigate` /
`tab.ariaSnapshot` / `tab.textContent` / `tab.eval` first.
"""

private val FILE_HANDLING_BROWSER = """
## File Handling

- Use the file system to save your processing progress and final results.
- Prefer `fs.*` tools for file operations.
- Use `plan.md` if you have a plan.
- Use `results.md` to summarize final task results.
- NEVER rename/delete a file to "move" content: write the new file first, verify it
  exists and is correct, and only then delete the old one. Deleting an old file whose
  content only exists under the old name destroys data irreversibly.
""".trimIndent()

private val FILE_HANDLING_CODING = """
## File Handling

- Operate on the repository workspace with `coding.*` tools, not `fs.*`.
- Read before editing: use `coding.read`, `coding.listDir`, `coding.glob`, and `coding.grep`
  to ground every change in the actual repository layout.
- Use `plan.md` for a short implementation plan and `results.md` for final results.
- Prefer `coding.replace`/`coding.write` for precise edits; never rewrite a whole file blindly.
- NEVER rename/delete a file to "move" content: write the new file first, verify it
  exists and is correct, and only then delete the old one. Deleting an old file whose
  content only exists under the old name destroys data irreversibly.
""".trimIndent()

private val REASONING_PATTERN_BROWSER = """
### Reasoning Pattern

To complete `<user_request>`, follow this reasoning pattern:

```
<thinking>
[1] Goal analysis: Relate the current sub-goal to the overall objective.
[2] State check: Review the current page, screenshot, and previous result.
[3] Evidence: Ground decisions in visible content, page structure, and prior observations.
[4] Blockers: Identify what is preventing progress.
[5] Plan: Choose the smallest effective next action.
</thinking>
```
""".trimIndent()

private val REASONING_PATTERN_CODING = """
### Reasoning Pattern

To complete `<user_request>`, follow this reasoning pattern:

```
<thinking>
[1] Goal analysis: Relate the current sub-goal to the overall objective.
[2] Code state check: Review the files, modules, tests, and latest tool output relevant to the change.
[3] Evidence: Ground decisions in actual code, build output, test counts, and validator results.
[4] Blockers: Identify compilation errors, test failures, or unknown APIs preventing progress.
[5] Plan: Choose the smallest effective next coding action.
</thinking>
```
""".trimIndent()

/**
 * Skill tool type definitions for the system prompt.
 *
 * These type definitions help the LLM understand the data structures returned by skill-related tool calls.
 */
val SKILL_TOOL_TYPE_DEFINITIONS = """
```kotlin
// Skill summary used during discovery and matching
data class SkillSummary(
    val id: String,          // Unique skill identifier
    val name: String,        // Display name
    val description: String, // Capability summary
    val version: String,     // Semantic version
    val tags: Set<String>    // Classification tags
)

// Activated skill payload, including full SKILL.md content and resource paths
data class SkillActivation(
    val id: String,              // Unique skill identifier
    val name: String,            // Display name
    val version: String,         // Semantic version
    val skillMd: String,         // Full SKILL.md content
    val scriptsPath: String?,    // Script directory path (optional)
    val referencesPath: String?, // Reference docs path (optional)
    val assetsPath: String?      // Asset directory path (optional)
)

// Skill execution result
data class SkillResult(
    val success: Boolean,          // Whether execution succeeded
    val data: Any?,                // Result payload
    val message: String?,          // Result summary
    val metadata: Map<String, Any> // Extra metadata
)
```
""".trimIndent()

/**
 * Build skill summaries section for the system prompt.
 *
 * Returns a formatted string containing all registered skill summaries,
 * or an empty string if no skills are registered.
 */
fun buildSkillSummariesSection(): String {
    val summaries = SkillRegistry.instance.listSkillSummaries()
    if (summaries.isEmpty()) {
        return ""
    }

    val summaryLines = summaries.joinToString("\n") { skill ->
        "- **${skill.name}** (`${skill.id}` v${skill.version}): ${skill.description}"
    }

    return """
Registered skills:
- Use `skill.list()` to refresh the full list.
- Use `skill.activate(id)` to load the complete skill documentation.
- Use `skill.run(id, params)` to execute a skill.

$summaryLines

---
""".trimIndent()
}

/**
 * Build main system prompt (v20260123).
 *
 * Note: Must be generated on demand so newly registered custom tools/skills are reflected in the tool list.
 */
fun buildMainSystemPromptV1(): String = buildMainSystemPromptV1(ToolSpecFormat.KOTLIN)

fun buildToolSpecContent(
    toolFormat: ToolSpecFormat,
    codingTask: Boolean? = null,
    disclosure: String = "tiered",
): String {
    val toolSpecContent = when (toolFormat) {
        ToolSpecFormat.KOTLIN -> """
```
${ToolCallSpecificationRenderer.renderTiered(includeCustomDomains = true, codingTask = codingTask, disclosure = disclosure)}
```
""".trimIndent()

        ToolSpecFormat.JSON -> """
```json
${ToolCallSpecificationRenderer.renderJson(includeCustomDomains = true)}
```
""".trimIndent()
    }

    return toolSpecContent
}

fun buildToolUseSections(
    toolFormat: ToolSpecFormat = ToolSpecFormat.KOTLIN,
    includeToolList: Boolean = true,
    codingTask: Boolean? = null,
    disclosure: String = "tiered",
): String {
    return """
## Tool Usage

$TOOL_CALL_RULE_CONTENT

### Skill Tool Types

$SKILL_TOOL_TYPE_DEFINITIONS

### `agent.extract` Data Types

$EXTRACTION_TOOL_NOTE_CONTENT

${if (includeToolList) """
### Tool List

${buildToolSpecContent(toolFormat, codingTask, disclosure)}
""" else ""}
### Available Skills

${buildSkillSummariesSection()}

---

    """.trimIndent()
}

/**
 * Build main system prompt (v20260123) with specified tool format.
 *
 * @param toolFormat The format to use for tool specifications (KOTLIN or JSON)
 * @return The complete system prompt string
 *
 * Note: Must be generated on demand so newly registered custom tools/skills are reflected in the tool list.
 */
fun buildMainSystemPromptV1(
    toolFormat: ToolSpecFormat,
    includeToolList: Boolean = true,
    codingTask: Boolean? = null,
    disclosure: String = "tiered",
): String {
    return """
# System Instructions

## Language

- Default working language: **$workingLanguage**
- Always reply in the same language as the user request.

---

${if (codingTask == true) FILE_HANDLING_CODING else FILE_HANDLING_BROWSER}

---

## When to Finish

End the task only when one of the following is true, and output the `Task Completion Output` JSON format:
- The requested task is fully complete.
- An unrecoverable error prevents further progress.
- The user explicitly asks you to stop.

When the task is complete and no further tool call is needed, output the
`Task Completion Output` JSON **immediately** — never reply with plain text or
explanations instead. Text-only responses without a completion marker waste
steps and are indistinguishable from a stalled agent.

**Anchor the completion summary to measured evidence.** If the task specifies
quality gates (build, tests, validation, deploy), your `summary` MUST list each
gate with its actual measured result — exit code, test counts, or validator
output you actually observed. A gate you did not run must be reported as
"not run", and a gate that failed must be reported as "failed" with the error
— never claim success without the tool output in front of you.

---

${if (codingTask == true) REASONING_PATTERN_CODING else REASONING_PATTERN_BROWSER}

---

## Output Requirements

- Output must match exactly one of the JSON formats below.
- Output JSON only, with no extra text.

### Action Output

- Return at most one element.
- `arguments` must follow the tool method parameter order.

Output format:
${buildResponseSchema()}

### Task Completion Output

Output format:
$OBSERVE_RESPONSE_COMPLETE_SCHEMA

${if (codingTask == true) CODING_NO_PAGE_FIELDS_NOTE else ""}
---

${buildToolUseSections(toolFormat, includeToolList, codingTask, disclosure)}

        """.trimIndent()
}
