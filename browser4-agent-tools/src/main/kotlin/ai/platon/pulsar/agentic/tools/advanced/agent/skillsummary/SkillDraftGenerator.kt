package ai.platon.pulsar.agentic.tools.advanced.agent.skillsummary

import ai.platon.pulsar.agentic.common.SecretRedactor
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.coding.ArtifactValidator
import ai.platon.pulsar.coding.Severity
import ai.platon.pulsar.coding.ValidationResult
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 生成 SKILL.md 草稿的流水线（Task 4）。
 *
 * 流程：渲染 LLM prompt → 调 LLM（可降级）→ [SecretRedactor] 扫描 →
 * 解析/修补 frontmatter `name` → [ArtifactValidator] 校验 → 落盘到草稿区。
 *
 * 所有依赖通过构造注入（[llm] / [validator] / [redactor]），便于单元测试替换。
 * 任何阶段失败都不抛异常影响主流程——返回一个带 [DraftResult.validation] 的结果，
 * 由 [SkillSummarizer] 决定发射 `generate` / `fail` 事件（NFR-1）。
 *
 * @param draftsDir 草稿区根目录（`<app data>/agent/skills-drafts` 或配置指定）
 * @param llm 单次 LLM 调用：(instruction, contextText) → 原始输出；返回 null 表示 LLM 不可用，触发降级
 * @param validator skill 内容校验器，默认 [ArtifactValidator.validateSkill]
 * @param redactor 敏感值扫描器，默认 [SecretRedactor.redactText]
 * @param clock 时间源，便于测试
 */
class SkillDraftGenerator(
    private val draftsDir: Path,
    private val llm: suspend (instruction: String, contextText: String) -> String?,
    private val validator: (content: String, fileName: String) -> ValidationResult =
        { content, fileName -> ArtifactValidator.validateSkill(content, fileName) },
    private val redactor: (String) -> String = { SecretRedactor.redactText(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val logger = LoggerFactory.getLogger(SkillDraftGenerator::class.java)

    /** 生成结果。[validation] 反映校验是否通过（无 ERROR）；[llmUsed]=false 表示走了降级路径。 */
    data class DraftResult(
        val skillId: String,
        val dir: Path,
        val skillMd: String?,
        val validation: ValidationResult,
        val llmUsed: Boolean,
    )

    /** 生成一份草稿。永不抛异常。 */
    suspend fun generate(context: TaskSummaryContext): DraftResult = runCatching {
        generateInternal(context)
    }.getOrElse { ex ->
        logger.warn("skill draft generation threw for task {}: {}", context.taskId, ex.message)
        // 降级：写 context.json 到一个错误命名的目录，便于事后排查
        val dir = writeContextOnly(context, suffix = "error")
        DraftResult(
            skillId = dir.fileName.toString(),
            dir = dir,
            skillMd = null,
            validation = ValidationResult.of(
                listOf(ai.platon.pulsar.coding.ValidationIssue(Severity.ERROR, "generator exception: ${ex.message}")),
            ),
            llmUsed = false,
        )
    }

    private suspend fun generateInternal(context: TaskSummaryContext): DraftResult {
        val contextText = renderContext(context)
        val raw = llm(LLM_INSTRUCTION, contextText)
        if (raw.isNullOrBlank()) {
            // TR-4.4: LLM 不可用 → 只写 context.json，不生成 SKILL.md
            val dir = writeContextOnly(context, suffix = "no-llm")
            return DraftResult(
                skillId = dir.fileName.toString(),
                dir = dir,
                skillMd = null,
                validation = ValidationResult.of(
                    listOf(ai.platon.pulsar.coding.ValidationIssue(Severity.INFO, "LLM unavailable; context.json only")),
                ),
                llmUsed = false,
            )
        }

        // 敏感兜底：先对原始产物做一轮 SecretRedactor 扫描（TR-4.3）
        val redacted = redactor(raw)
        // 防幻觉：allowed-tools 只保留采集到的真实工具名（TR-4.2）
        val sanitized = ensureAllowedTools(redacted, context.toolCounts.keys)
        // 解析/修补 name：保证 frontmatter name 合法且 == 目录名
        val name = resolveSkillName(sanitized, context)
        val finalContent = ensureProvenance(ensureName(sanitized, name), context)
        val skillDir = draftsDir.resolve("$name-${clock()}")
        val fileName = "$name/SKILL.md"
        val validation = validator(finalContent, fileName)

        runCatching {
            Files.createDirectories(skillDir)
            Files.writeString(skillDir.resolve("SKILL.md"), finalContent)
            Files.writeString(skillDir.resolve("context.json"), redactor(pulsarObjectMapper().writeValueAsString(context.toJsonable())))
        }.onFailure { logger.warn("writing draft files failed for {}: {}", name, it.message) }

        return DraftResult(
            skillId = name,
            dir = skillDir,
            skillMd = finalContent,
            validation = validation,
            llmUsed = true,
        )
    }

    // ---- frontmatter / name handling ----

    private val nameRegex = Regex("""^name:\s*(\S+)""", RegexOption.MULTILINE)
    private val frontmatterBlock = Regex("""^---\s*\n(.*?)\n---\s*\n""", RegexOption.DOT_MATCHES_ALL)
    private val kebabCase = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    private val allowedToolsRegex = Regex("""^allowed-tools:\s*(.*)$""", RegexOption.MULTILINE)
    private val generatedRegex = Regex("""^generated:\s*""", RegexOption.MULTILINE)
    private val sourceTaskIdRegex = Regex("""^source-task-id:\s*""", RegexOption.MULTILINE)
    private val generatedAtRegex = Regex("""^generated-at:\s*""", RegexOption.MULTILINE)

    /** 从内容里取 name；缺失或非法则从 prompt 派生，保证合法 kebab-case。 */
    internal fun resolveSkillName(content: String, context: TaskSummaryContext): String {
        val parsed = nameRegex.find(content)?.groupValues?.get(1)?.trim()?.trim('"', '\'')
        if (!parsed.isNullOrBlank() && parsed.matches(kebabCase) && !parsed.contains("--")) return parsed
        return deriveSkillName(context.prompt)
    }

    /** 确保 frontmatter 里 `name:` 存在且等于 [name]（修补 LLM 漏写或写错的情况）。 */
    internal fun ensureName(content: String, name: String): String {
        val fm = frontmatterBlock.find(content) ?: return content
        val block = fm.groupValues[1]
        val newBlock = if (nameRegex.containsMatchIn(block)) {
            nameRegex.replace(block) { "name: $name" }
        } else {
            "name: $name\n$block"
        }
        return content.replaceRange(fm.range, "---\n$newBlock\n---\n")
    }

    /**
     * 注入溯源标记（FR-7）：在 frontmatter 补 `generated: true`、`source-task-id`、`generated-at`，
     * 已存在则不覆盖。保证每个自动生成的 SKILL.md 可追溯到来源任务与生成时间。
     */
    internal fun ensureProvenance(content: String, context: TaskSummaryContext): String {
        val fm = frontmatterBlock.find(content) ?: return content
        var block = fm.groupValues[1]
        if (!generatedRegex.containsMatchIn(block)) block = "generated: true\n$block"
        if (!sourceTaskIdRegex.containsMatchIn(block) && context.taskId.isNotBlank()) {
            block = "source-task-id: ${context.taskId}\n$block"
        }
        if (!generatedAtRegex.containsMatchIn(block)) block = "generated-at: ${clock()}\n$block"
        return content.replaceRange(fm.range, "---\n$block\n---\n")
    }

    /**
     * 防幻觉（TR-4.2）：frontmatter 里 `allowed-tools` 只保留 [knownTools] 中真实存在的工具名，
     * 删掉 LLM 编造的工具；若该行不存在且 [knownTools] 非空，则补一行。
     */
    internal fun ensureAllowedTools(content: String, knownTools: Set<String>): String {
        val fm = frontmatterBlock.find(content) ?: return content
        val block = fm.groupValues[1]
        val newBlock = if (allowedToolsRegex.containsMatchIn(block)) {
            allowedToolsRegex.replace(block) { match ->
                val listed = match.groupValues[1].trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                val kept = listed.filter { it in knownTools }
                "allowed-tools: ${kept.joinToString(" ")}"
            }
        } else if (knownTools.isNotEmpty()) {
            "allowed-tools: ${knownTools.take(12).joinToString(" ")}\n$block"
        } else {
            block
        }
        return content.replaceRange(fm.range, "---\n$newBlock\n---\n")
    }

    /** 从 prompt 派生一个合法的 kebab-case skill 名（取前若干有意义 token）。 */
    internal fun deriveSkillName(prompt: String?): String {
        val tokens = SkillFingerprint.tokensOf(prompt).take(4)
        val base = if (tokens.isEmpty()) "auto-skill" else tokens.joinToString("-")
        return base.take(40).trimEnd('-')
    }

    // ---- prompt rendering ----

    internal fun renderContext(context: TaskSummaryContext): String = buildString {
        appendLine("== Task prompt ==")
        // NFR-2 depth-in-defense: redact before sending any free-form task text to the LLM provider.
        appendLine(redactor(context.prompt ?: "(empty)"))
        appendLine()
        appendLine("== Real tools used (allowed-tools may ONLY reference these) ==")
        val tools = context.toolCounts.keys.toList()
        appendLine(tools.joinToString(", ").ifEmpty { "(none)" })
        appendLine()
        appendLine("== Tool call sequence (callId -> tool, ok, durMs) ==")
        context.toolSequence.take(40).forEach { rec ->
            appendLine("- ${rec.tool} (ok=${rec.ok}, ${rec.durationMs}ms)")
        }
        appendLine()
        appendLine("== Verify verdict ==")
        appendLine(redactor(context.verifyVerdict ?: "(none)"))
        appendLine()
        appendLine("== Result summary ==")
        appendLine(redactor((context.result ?: "(empty)").take(2000)))
        appendLine()
        appendLine("== Files changed (${context.filesChanged.size}) ==")
        context.filesChanged.take(50).forEach { appendLine("- $it") }
        appendLine()
        appendLine("== Stats: rounds=${context.rounds} toolCalls=${context.toolCalls} mutations=${context.mutations} ==")
    }

    // ---- file helpers ----

    private fun writeContextOnly(context: TaskSummaryContext, suffix: String): Path {
        val dir = draftsDir.resolve("context-only-$suffix-${clock()}")
        runCatching {
            Files.createDirectories(dir)
            Files.writeString(
                dir.resolve("context.json"),
                redactor(pulsarObjectMapper().writeValueAsString(context.toJsonable())),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
        }
        return dir
    }

    private fun Path.writeString(name: String, content: String) {
        Files.writeString(this.resolve(name), content)
    }

    companion object {
        internal const val LLM_INSTRUCTION = """You turn a successful automation run into a reusable Browser4 SKILL.

Output ONLY a single Markdown file. It MUST start with YAML frontmatter between --- lines, followed by the body.

Frontmatter (required):
- name: a kebab-case lowercase identifier (letters/digits/single hyphens only, no "--")
- description: 1-1024 chars describing when to use this skill
- allowed-tools: a SPACE-separated list. ONLY use tool names from the "Real tools used" list below. Do NOT invent tools.
- version: "1.0.0"
- generated: true

Body (use ## sections):
- ## Overview — what the skill does
- ## When to Use — the trigger conditions
- ## How It Works — generalized step-by-step. Do NOT hardcode one-off values: no concrete URLs, no passwords, no one-time IDs, no absolute paths specific to one run. Keep it reusable.
- ## Examples (optional) — one short, anonymized example.

Rules:
- NEVER output secrets, tokens, passwords, cookies, or API keys.
- Output Markdown only. No preamble, no explanation, no code fences around the whole file.
"""
    }
}

/** 把 [TaskSummaryContext] 转成可序列化的 Map（避免 data class 直接序列化时的字段问题）。 */
internal fun TaskSummaryContext.toJsonable(): Map<String, Any?> = mapOf(
    "taskId" to taskId,
    "prompt" to prompt,
    "result" to result,
    "workspace" to workspace,
    "rounds" to rounds,
    "toolCalls" to toolCalls,
    "mutations" to mutations,
    "toolCounts" to toolCounts,
    "toolSequence" to toolSequence.map { mapOf("tool" to it.tool, "ok" to it.ok, "durationMs" to it.durationMs) },
    "verifyVerdict" to verifyVerdict,
    "filesChanged" to filesChanged,
    "inputTokens" to inputTokens,
    "outputTokens" to outputTokens,
    "durationMs" to durationMs,
)
