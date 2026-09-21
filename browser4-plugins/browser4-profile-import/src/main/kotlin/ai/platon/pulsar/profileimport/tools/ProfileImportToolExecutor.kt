package ai.platon.pulsar.profileimport.tools

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.profileimport.service.ProfileImportService
import kotlin.reflect.KClass

/**
 * Tool executor exposing browser personal-data import as MCP/LLM tools.
 *
 * Domain: `profile_import`
 *
 * Supported methods:
 * - `list_sources()` — discover installed browsers and their profiles
 * - `import(source, profile?, data?, into?)` — import bookmarks / history /
 *   passwords / cookies / extensions from Chrome / Edge / Safari into a
 *   Browser4-managed snapshot directory
 */
class ProfileImportToolExecutor(
    private val profileImportService: ProfileImportService,
) : AbstractToolExecutor() {

    override val domain: String = "profile_import"
    override val receiverClass: KClass<*> = ProfileImportService::class

    init {
        toolSpec["list_sources"] = ToolSpec(
            domain = domain,
            method = "list_sources",
            arguments = emptyList(),
            returnType = "Map",
            description = "List installed browsers (Chrome, Edge, Safari) and their profiles on this machine.",
            cliName = "profile sources",
        )

        toolSpec["import"] = ToolSpec(
            domain = domain,
            method = "import",
            arguments = listOf(
                ToolSpec.Arg("source", "String", null),
                ToolSpec.Arg("profile", "String?", null),
                ToolSpec.Arg("data", "String?", null),
                ToolSpec.Arg("into", "String?", null),
            ),
            returnType = "Map",
            description = "Import browser personal data into a Browser4-managed profile snapshot. " +
                "source: chrome|edge|safari; profile: Chrome/Edge profile name or directory " +
                "(default: first profile); data: comma-separated subset of " +
                "bookmarks,history,passwords,cookies,extensions (default: all); " +
                "into: reserved for future use.",
            cliName = "profile import",
        )
    }

    override suspend fun callFunctionOn(
        domain: String,
        functionName: String,
        args: Map<String, Any?>,
        receiver: Any,
    ): Any? {
        require(domain == this.domain) { "Unsupported domain: $domain" }

        return when (functionName) {
            "list_sources" -> profileImportService.listSources()
            "import" -> {
                val source = paramString(args, "source", functionName)!!
                val profile = paramString(args, "profile", functionName, required = false, default = null)
                val data = paramString(args, "data", functionName, required = false, default = null)
                val into = paramString(args, "into", functionName, required = false, default = null)
                profileImportService.import(source, profile, data, into)
            }
            else -> throw IllegalArgumentException("Unsupported profile_import method: $functionName")
        }
    }
}
