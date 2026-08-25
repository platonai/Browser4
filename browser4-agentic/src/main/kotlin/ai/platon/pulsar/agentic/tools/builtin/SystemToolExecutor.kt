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
            arguments = listOf(ToolSpec.Arg("name", "String")),
            returnType = "String",
            description = "Read a bundled browser4-cli skill document from the runtime classpath " +
                "(e.g. SKILL.md, snapshot.md, htmlsnapshot.md, x-sql.md, crawl.md). " +
                "The available document names are listed when an unknown name is requested."
        )
        toolSpec["taskComplete"] = ToolSpec(
            domain = domain,
            method = "taskComplete",
            arguments = listOf(
                ToolSpec.Arg("summary", "String"),
                ToolSpec.Arg("keyFindings", "List<String>", "[]"),
                ToolSpec.Arg("filesChanged", "List<String>", "[]"),
                ToolSpec.Arg("problems", "List<String>", "[]"),
            ),
            returnType = "String",
            description = "Mark the current task as complete. summary: concise final report; " +
                "keyFindings: list of key findings; filesChanged: list of files changed; " +
                "problems: unresolved issues."
        )
    }

    fun help(domain: String, method: String): String {
        return agentToolManager.help(domain, method)
    }

    /**
     * Read one bundled browser4-cli skill document from the classpath
     * (under `skills/browser4-cli`), so the agent can consult the SKILL.md
     * reference docs without a source tree (design §4.3).
     */
    fun skillDoc(name: String): String {
        val content = readBundledDoc(name)
            ?: return "Skill document not found: $name\n\nAvailable bundled documents:\n" +
                AVAILABLE_DOCS.joinToString("\n") { "  $it" }
        return content.take(MAX_SKILL_DOC_CHARS)
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
        val content = readBundledDoc(name)
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
    fun skillDocStrict(name: String): String? =
        readBundledDoc(name)?.take(MAX_SKILL_DOC_CHARS)

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
     */
    private fun readBundledDoc(name: String): String? {
        val safe = sanitizeDocName(name) ?: throw IllegalArgumentException("Invalid skill doc name: $name")
        val direct = javaClass.getResourceAsStream("/skills/browser4-cli/$safe")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
        if (direct != null) return direct
        return javaClass.getResourceAsStream("/skills/browser4-cli/references/$safe")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
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
                validateArgs(args, allowed = setOf("name"), required = setOf("name"), functionName)
                skillDoc(args["name"]!! as String)
            }
            "taskComplete" -> {
                validateArgs(
                    args, allowed = setOf("summary", "keyFindings", "filesChanged", "problems"),
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

    companion object {
        /** Upper bound for a returned skill document (chars). */
        private const val MAX_SKILL_DOC_CHARS = 120_000

        /** Curated list of bundled `skills/browser4-cli` documents. */
        private val AVAILABLE_DOCS = listOf(
            "SKILL.md", "quickstart.md", "agent.md", "attach.md", "browser-state-import.md", "config.md",
            "crawl.md", "css-selector-bridge.md", "htmlsnapshot.md",
            "htmlsnapshot-scenarios.md", "htmlsnapshot-scenarios-advanced.md",
            "htmlsnapshot-scenarios-amazon.md", "htmlsnapshot-scenarios-audit.md",
            "htmlsnapshot-scenarios-extraction.md", "load-options-guide.md", "loop.md",
            "power-dom.md", "shell-quoting.md", "skills.md", "snapshot.md", "storage-state.md",
            "swarm.md", "webdb.md", "x-sql.md", "x-sql-array-functions.md",
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
