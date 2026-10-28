package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.AgentToolManager
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.common.getLogger
import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.reflect.KClass

class SystemToolExecutor(
    val agentToolManager: AgentToolManager
) : AbstractToolExecutor() {

    override val domain = "system"

    override val receiverClass: KClass<*> = SystemToolExecutor::class

    init {
        toolSpec["help"] = ToolSpec(
            domain = domain,
            method = "help",
            arguments = listOf(
                ToolSpec.Arg("domain", "String", null),
                ToolSpec.Arg("method", "String", null)
            ),
            returnType = "String",
            description = "Get help information for a specific tool method in a domain"
        )
        toolSpec["skillDoc"] = ToolSpec(
            domain = domain,
            method = "skillDoc",
            arguments = listOf(
                ToolSpec.Arg(
                    "name", "String", null,
                    "Bundled document name, e.g. SKILL.md or bulk-collection.md " +
                        "(reference docs are listed by the SKILL.md index).",
                ),
                ToolSpec.Arg(
                    "offset", "Int?", "null",
                    "Code-point offset of the slice to read. Omit it on SKILL.md to get the " +
                        "reference map (section headings + reference-doc offsets) instead of the body.",
                ),
                ToolSpec.Arg(
                    "limit", "Int", "$SKILL_DOC_PAGE_CHARS",
                    "Max characters in this slice (1..$MAX_SKILL_DOC_PAGE_CHARS).",
                ),
            ),
            returnType = "String",
            description = "Read a bundled browser4-cli skill document from the runtime classpath " +
                "(e.g. SKILL.md, quickstart.md, bulk-collection.md, agent-workspace.md, " +
                "htmlsnapshot.md, x-sql.md, crawl.md). " +
                "Without `offset`, SKILL.md returns a reference map (its section headings and the " +
                "bundled reference docs, with the offset of each) so you can load only the topic " +
                "you need; other documents return their first slice. " +
                "Page through a long document with offset=<nextOffset> from the result header."
        )
        toolSpec["taskComplete"] = ToolSpec(
            domain = domain,
            method = "taskComplete",
            arguments = listOf(
                ToolSpec.Arg("summary", "String"),
                ToolSpec.Arg("keyFindings", "List<String>", "[]"),
                ToolSpec.Arg("filesChanged", "List<String>", "[]"),
                ToolSpec.Arg("deletedFiles", "List<String>", "[]"),
                ToolSpec.Arg("problems", "List<String>", "[]"),
            ),
            returnType = "String",
            description = "Mark the current task as complete. summary: concise final report " +
                "(include the VERDICT_JSON line verbatim when coding.verify ran); " +
                "keyFindings: list of key findings; filesChanged: list of files changed; " +
                "deletedFiles: list of files deleted; problems: unresolved issues."
        )
    }

    fun help(domain: String, method: String): String {
        return agentToolManager.help(domain, method)
    }

    /**
     * Read one bundled browser4-cli skill document from the classpath
     * (under `skills/browser4-cli`), so the agent can consult the SKILL.md
     * reference docs without a source tree (design §4.3).
     *
     * ## Why it pages
     *
     * The whole document is 59 KB; it used to be returned in one shot and then cut
     * to the `ToolOutcome` budget, so the model saw `…[truncated 58125 chars]…`
     * and nothing else — the reference docs the prompt told it to consult were
     * unreachable (2026-10-28 run, 248 tool calls, 0 deliverables).
     *
     * Calling [skillDoc] for the primary document (`SKILL.md`) **without** `offset`
     * returns a reference map instead of the body: its section headings plus every
     * bundled reference doc, each with the offset to read it from. `offset` then
     * reads a slice. Any other document without `offset` returns its first slice.
     *
     * @param name document name (`SKILL.md`, `bulk-collection.md`, …)
     * @param offset code-point offset of the slice; `null` means "index" for
     *   SKILL.md and "start at 0" otherwise
     * @param limit max characters of the returned text (header included)
     */
    fun skillDoc(name: String, offset: Int? = null, limit: Int = SKILL_DOC_PAGE_CHARS): String {
        val (docName, content) = readBundledDoc(name)
            ?: return "Skill document not found: $name\n\nAvailable bundled documents:\n" +
                AVAILABLE_DOCS.joinToString("\n") { "  $it" }
        val bounded = content.take(MAX_SKILL_DOC_CHARS)
        if (offset == null && isPrimarySkillDoc(docName)) {
            return skillDocIndex(docName, bounded)
        }
        return skillDocPage(docName, bounded, offset ?: 0, limit)
    }

    /**
     * Metadata-only view of a bundled skill document for system prompts.
     *
     * Returns just the YAML frontmatter (name, title, description, tier, ...)
     * instead of the full document body, so prompts stay small and the model
     * loads the complete SKILL.md on demand via [skillDoc] (progressive
     * disclosure). Falls back to the first lines when a document has no
     * frontmatter.
     */
    fun skillDocMetadata(name: String): String {
        val content = readBundledDoc(name)?.second
            ?: return "Skill document not found: $name"
        return extractFrontmatter(content)?.let { "Skill: $name\n$it" }
            ?: content.lineSequence().take(5).joinToString("\n")
    }

    /**
     * Read one bundled browser4-cli document or return null when missing.
     *
     * Unlike [skillDoc] (which renders a "not found" help message), this is
     * for embedding document content into system prompts (e.g. the resident
     * CLI quick reference): the caller decides the fallback.
     */
    fun skillDocStrict(name: String): String? {
        val content = readBundledDoc(name)?.second ?: return null
        // Resident embedding: drop the YAML frontmatter (metadata for humans/tooling,
        // useless in prompts) so every request does not pay for boilerplate.
        return stripFrontmatter(content).take(MAX_SKILL_DOC_CHARS)
    }

    /** Path-traversal-safe name check shared by the doc readers; null when invalid. */
    private fun sanitizeDocName(name: String): String? {
        val safe = name.trim()
            .removePrefix("/")
            .removePrefix("skills/")
            .removePrefix("browser4-cli/")
        return safe.takeIf { it.isNotBlank() && !it.contains("..") && !it.contains('\\') }
    }

    /**
     * Read a bundled browser4-cli document: top-level first
     * (`/skills/browser4-cli/<name>`), then `references/` — reference docs
     * live in `skills/browser4-cli/references/` in the repo and are bundled
     * at that path.
     *
     * @return the resolved document name (as it should appear in a header —
     *   reference docs without the `references/` prefix) and its text, or null
     *   when no such resource exists
     */
    private fun readBundledDoc(name: String): Pair<String, String>? {
        val safe = sanitizeDocName(name) ?: throw IllegalArgumentException("Invalid skill doc name: $name")
        val direct = javaClass.getResourceAsStream("/skills/browser4-cli/$safe")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
        if (direct != null) return safe to direct
        val reference = javaClass.getResourceAsStream("/skills/browser4-cli/references/$safe")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
        return reference?.let { safe to it }
    }

    /** True for the skill's primary document — the one with a reference map. */
    private fun isPrimarySkillDoc(docName: String): Boolean =
        docName.equals(PRIMARY_SKILL_DOC, ignoreCase = true)

    /**
     * The reference map for the primary skill document: section headings and the
     * bundled reference docs, each with the offset it can be read from.
     *
     * Bounded to [MAX_SKILL_DOC_INDEX_CHARS]: it ships in the model's context, so
     * it must stay a map, not a document. Entries are appended whole — a map cut
     * mid-line would name an offset that is not a section start.
     */
    private fun skillDocIndex(docName: String, content: String): String {
        val totalCodePoints = content.codePointCount(0, content.length)
        val lines = content.lineSequence().toList()
        val sections = mutableListOf<Pair<Int, String>>()
        val linkedDocs = linkedMapOf<String, Int>()
        var codePointOffset = 0
        for (line in lines) {
            val heading = SECTION_HEADING.find(line)
            // Top-level sections only: sub-headings would fill the map's whole budget
            // and push the reference-doc list (the part the model actually needs) out.
            if (heading != null && heading.groupValues[1].length <= 2) {
                sections += codePointOffset to line.trim()
            }
            REFERENCE_LINK.findAll(line).forEach { match ->
                val doc = match.groupValues[1]
                if (doc !in linkedDocs) linkedDocs[doc] = codePointOffset
            }
            codePointOffset += line.codePointCount(0, line.length) + 1
        }

        val out = StringBuilder()
        out.append("$docName · reference map · totalLength=$totalCodePoints chars\n")
        out.append("Read a slice with system.skillDoc(\"$docName\", offset=<n>, limit=<chars>); ")
        out.append("sections:\n")
        for ((offset, heading) in sections) {
            appendIndexLine(out, "- offset $offset: $heading")
        }
        out.append("Bundled reference docs (read a whole topic with system.skillDoc(\"<name>\"); ")
        out.append("the offset is where $docName links it):\n")
        var dropped = 0
        for ((doc, offset) in linkedDocs) {
            if (!appendIndexLine(out, "- offset $offset: $doc")) dropped++
        }
        if (dropped > 0) {
            appendIndexLine(out, "  (+$dropped more — see the Reference Map section of $docName)")
        }
        return out.toString().trimEnd()
    }

    /** Append [line] while the bounded index still has room; false when it was dropped. */
    private fun appendIndexLine(out: StringBuilder, line: String): Boolean {
        if (out.length + line.length + 1 > MAX_SKILL_DOC_INDEX_CHARS) return false
        out.append(line).append('\n')
        return true
    }

    /**
     * One slice of a document, with a pagination header.
     *
     * The header (`offset`, `nextOffset`, `totalLength`) is what makes the slice
     * resumable: the model can continue exactly where this page stopped instead of
     * re-reading from the top. Offsets are **code points**, not UTF-16 units, so a
     * document containing CJK or emoji cannot be cut mid-character.
     */
    private fun skillDocPage(docName: String, content: String, offset: Int, limit: Int): String {
        val boundedLimit = limit.coerceIn(MIN_SKILL_DOC_PAGE_CHARS, MAX_SKILL_DOC_PAGE_CHARS)
        val total = content.codePointCount(0, content.length)
        val start = offset.coerceIn(0, total)
        val startChar = content.offsetByCodePoints(0, start)
        // Reserve room for the header (and the continuation footer when the slice ends).
        val headerReserve = 96
        val remainingCodePoints = total - start
        val maxContentChars = (boundedLimit - headerReserve).coerceAtLeast(1)
        // A code point is at least one UTF-16 char, so a code-point count bounded by
        // the char budget cannot exceed it (and never splits a surrogate pair).
        val count = minOf(maxContentChars, remainingCodePoints)
        val endChar = content.offsetByCodePoints(startChar, count)
        val end = start + count
        val slice = content.substring(startChar, endChar)
        val header = "$docName · offset=$start nextOffset=$end totalLength=$total"
        val footer = if (end < total) "\n… continue with offset=$end …" else ""
        return header + "\n\n" + slice + footer
    }

    /** Extract the YAML frontmatter between the leading `---` markers, if any. */
    private fun extractFrontmatter(content: String): String? {
        val trimmed = content.trimStart('\uFEFF', ' ', '\r', '\n')
        if (!trimmed.startsWith("---")) {
            return null
        }
        val end = trimmed.indexOf("\n---", 3)
        if (end < 0) {
            return null
        }
        return trimmed.substring(3, end).trim()
    }

    /** Strip the leading YAML frontmatter block, keeping only the document body. */
    private fun stripFrontmatter(content: String): String {
        val trimmed = content.trimStart('\uFEFF', ' ', '\r', '\n')
        if (!trimmed.startsWith("---")) {
            return content
        }
        val end = trimmed.indexOf("\n---", 3)
        if (end < 0) {
            return content
        }
        return trimmed.substring(end + 4).trimStart('\r', '\n')
    }

    /**
     * Completion protocol for the CLI tool-loop engine (design §3.1): the model
     * calls `system.taskComplete` instead of emitting a JSON completion marker.
     * Returns a confirmation string that feeds back into the conversation.
     */
    fun taskComplete(
        summary: String,
        keyFindings: List<String>?,
        filesChanged: List<String>?,
        problems: List<String>?,
    ): String {
        require(summary.isNotBlank()) { "taskComplete summary must not be blank" }
        return "Task marked complete. Summary: ${summary.take(200)}"
    }

    /**
     * Execute system.* expressions with named args.
     */
    @Suppress("UNUSED_PARAMETER")
    @Throws(IllegalArgumentException::class)
    override suspend fun callFunctionOn(
        domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
    ): Any {
        require(domain == this.domain) { "Unsupported domain: $domain" }
        require(functionName.isNotBlank()) { "Function name must not be blank" }

        return when (functionName) {
            "help" -> {
                validateArgs(args, allowed = setOf("domain", "method"), required = setOf("domain", "method"), functionName)
                help(args["domain"]!! as String, args["method"]!! as String)
            }
            "skillDoc" -> {
                validateArgs(
                    args, allowed = setOf("name", "offset", "limit"), required = setOf("name"), functionName
                )
                skillDoc(
                    name = args["name"]!! as String,
                    offset = intArg(args["offset"]),
                    limit = intArg(args["limit"]) ?: SKILL_DOC_PAGE_CHARS,
                )
            }
            "taskComplete" -> {
                validateArgs(
                    args, allowed = setOf("summary", "keyFindings", "filesChanged", "deletedFiles", "problems"),
                    required = setOf("summary"), functionName
                )
                taskComplete(
                    summary = args["summary"] as String,
                    keyFindings = stringListArg(args["keyFindings"]),
                    filesChanged = stringListArg(args["filesChanged"]),
                    problems = stringListArg(args["problems"]),
                )
            }

            else -> throw IllegalArgumentException("Unsupported system method: $functionName(${args.keys})")
        }
    }

    private fun stringListArg(value: Any?): List<String>? = when (value) {
        null -> null
        is List<*> -> value.mapNotNull { it?.toString() }
        else -> listOf(value.toString())
    }

    /** Optional integer tool argument; a blank/absent value means "not supplied". */
    private fun intArg(value: Any?): Int? = when (value) {
        null -> null
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    companion object {
        /** Upper bound for a returned skill document (chars). */
        private const val MAX_SKILL_DOC_CHARS = 120_000

        /** Default slice size; one default page fits the `ToolOutcome` body budget. */
        const val SKILL_DOC_PAGE_CHARS = 8_000

        /** Largest slice a caller may request (a bigger one is cut by `ToolOutcome`). */
        const val MAX_SKILL_DOC_PAGE_CHARS = 16_000

        /** Smallest slice, so a `limit` typo cannot return an unusable stub. */
        private const val MIN_SKILL_DOC_PAGE_CHARS = 256

        /** Hard bound on the reference map (it ships in the model's context). */
        private const val MAX_SKILL_DOC_INDEX_CHARS = 2_048

        /** The document whose no-offset form is the reference map. */
        private const val PRIMARY_SKILL_DOC = "SKILL.md"

        /** `## Section` / `### Subsection` headings (ATX style, no indentation). */
        private val SECTION_HEADING = Regex("""^(#{1,4})\s+(.*)$""")

        /** `references/<doc>.md` links in the reference map. */
        private val REFERENCE_LINK = Regex("""references/([A-Za-z0-9._-]+\.md)""")

        /** Curated list of bundled `skills/browser4-cli` documents. */
        private val AVAILABLE_DOCS = listOf(
            "SKILL.md", "quickstart.md", "agent.md", "agent-access.md", "agent-workspace.md",
            "attach.md", "browser-modes.md", "browser-state-import.md", "bulk-collection.md",
            "config.md", "crawl.md", "css-selector-bridge.md", "decision-trees.md",
            "development-mode.md", "emulation.md", "eval.md", "frames.md",
            "htmlsnapshot.md", "htmlsnapshot-scenarios.md", "htmlsnapshot-scenarios-advanced.md",
            "htmlsnapshot-scenarios-amazon.md", "htmlsnapshot-scenarios-audit.md",
            "htmlsnapshot-scenarios-extraction.md", "load-options-decision.md",
            "load-options-guide.md", "loop.md", "network.md", "plugins.md", "power-dom.md",
            "quick-patterns.md", "scrape-formats.md", "shell-quoting.md", "skills.md",
            "snapshot.md", "storage-state.md", "swarm.md", "tab-management.md", "tabs.md",
            "upload.md", "webdb.md", "x-sql.md", "x-sql-array-functions.md",
            "x-sql-dom-functions.md", "x-sql-dom-load-select.md", "x-sql-dom-select-functions.md",
            "x-sql-string-functions.md",
        )
    }
}

/**
 * Structured completion payload carried by `system.taskComplete` (design §3.1).
 */
data class TaskCompletion(
    val summary: String,
    val keyFindings: List<String>? = null,
    val filesChanged: List<String>? = null,
    val problems: List<String>? = null,
) {
    companion object {
        private val mapper = pulsarObjectMapper()

        /** Parse a native tool-call arguments JSON into a [TaskCompletion]. */
        fun fromJson(argumentsJson: String): TaskCompletion =
            mapper.readValue(argumentsJson)
    }
}
