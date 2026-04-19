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

fun buildToolSpecContent(toolFormat: ToolSpecFormat): String {
    val toolSpecContent = when (toolFormat) {
        ToolSpecFormat.KOTLIN -> """
```
${ToolCallSpecificationRenderer.render(includeCustomDomains = true)}
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

fun buildToolUseSections(toolFormat: ToolSpecFormat = ToolSpecFormat.KOTLIN): String {
    return """
## Tool Usage

$TOOL_CALL_RULE_CONTENT

### Skill Tool Types

$SKILL_TOOL_TYPE_DEFINITIONS

### `agent.extract` Data Types

$EXTRACTION_TOOL_NOTE_CONTENT

### Tool List

${buildToolSpecContent(toolFormat)}

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
fun buildMainSystemPromptV1(toolFormat: ToolSpecFormat): String {
    return """
# System Instructions

## Language

- Default working language: **$workingLanguage**
- Always reply in the same language as the user request.

---

## File Handling

- Use the file system to save your processing progress and final results.
- Prefer `fs.*` tools for file operations.
- Use `plan.md` if you have a plan.
- Use `results.md` to summarize final task results.

---

## When to Finish

End the task only when one of the following is true, and output the `Task Completion Output` JSON format:
- The requested task is fully complete.
- An unrecoverable error prevents further progress.
- The user explicitly asks you to stop.

---

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

---

${buildToolUseSections(toolFormat)}

        """.trimIndent()
}
