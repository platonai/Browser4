package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.AgentToolManager
import ai.platon.pulsar.common.getLogger
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
        val safe = name.trim()
            .removePrefix("/")
            .removePrefix("skills/")
            .removePrefix("browser4-cli/")
        require(safe.isNotBlank() && !safe.contains("..") && !safe.contains('\\')) {
            "Invalid skill doc name: $name"
        }
        val resource = "/skills/browser4-cli/$safe"
        val content = javaClass.getResourceAsStream(resource)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: return "Skill document not found: $name\n\nAvailable bundled documents:\n" +
                AVAILABLE_DOCS.joinToString("\n") { "  $it" }
        return content.take(MAX_SKILL_DOC_CHARS)
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

            else -> throw IllegalArgumentException("Unsupported system method: $functionName(${args.keys})")
        }
    }

    companion object {
        /** Upper bound for a returned skill document (chars). */
        private const val MAX_SKILL_DOC_CHARS = 120_000

        /** Curated list of bundled `skills/browser4-cli` documents. */
        private val AVAILABLE_DOCS = listOf(
            "SKILL.md", "agent.md", "attach.md", "browser-state-import.md", "crawl.md",
            "css-selector-bridge.md", "development.md", "htmlsnapshot.md",
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
