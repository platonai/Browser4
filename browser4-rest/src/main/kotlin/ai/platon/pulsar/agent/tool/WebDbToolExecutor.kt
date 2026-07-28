package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.skeleton.session.PulsarSession
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.reflect.KClass

/**
 * Tool executor for web database (webdb) operations.
 *
 * Domain: `webdb`
 *
 * Supported methods:
 * - `export(sessionId, urls, outputDir)` — Export pages from the web database to a local directory
 */
class WebDbToolExecutor(
    private val sessionManager: PulsarSessionManager,
) : AbstractToolExecutor() {

    override val domain: String = "webdb"
    override val receiverClass: KClass<*> = PulsarSessionManager::class

    init {
        toolSpec["export"] = ToolSpec(
            domain = domain,
            method = "export",
            arguments = listOf(
                ToolSpec.Arg("sessionId", "String", null),
                ToolSpec.Arg("urls", "String", null),
                ToolSpec.Arg("outputDir", "String", null),
            ),
            returnType = "String",
            description = "Export pages from the web database to a local directory. " +
                "Provide a comma-separated list of URLs."
        )
        toolSpec["normalize"] = ToolSpec(
            domain = domain,
            method = "normalize",
            arguments = listOf(
                ToolSpec.Arg("sessionId", "String", null),
                ToolSpec.Arg("url", "String", null),
            ),
            returnType = "String",
            description = "Normalize a URL for use as a web database key. " +
                "Resolves redirects, normalizes paths, and validates the URL."
        )
    }

    override suspend fun callFunctionOn(
        domain: String,
        functionName: String,
        args: Map<String, Any?>,
        receiver: Any
    ): Any? {
        return when (functionName) {
            "export" -> export(args)
            "normalize" -> normalize(args)
            else -> throw IllegalArgumentException("Unsupported method '$functionName' for domain '$domain'")
        }
    }

    // =========================================================================
    // Export
    // =========================================================================

    private suspend fun export(args: Map<String, Any?>): String {
        val sessionId = requireSessionId(args)
        val urls = paramString(args, "urls", "export", required = false)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing required parameter 'urls' for webdb export")
        val outputDir = paramString(args, "outputDir", "export", required = false)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing required parameter 'outputDir' for webdb export")

        val managed = sessionManager.getSession(sessionId)
            ?: throw IllegalArgumentException("Session not found: $sessionId")

        return managed.withLock {
            val session = managed.agenticSession
            val targetDir = Path.of(outputDir).also { it.createDirectories() }
            val urlList = urls.split(",").map { it.trim() }.filter { it.isNotEmpty() }

            val results = urlList.map { url ->
                runCatching {
                    exportPage(session, url, targetDir)
                    mapOf("url" to url, "status" to "ok")
                }.getOrElse { e ->
                    mapOf("url" to url, "status" to "error", "error" to (e.message ?: "unknown"))
                }
            }

            val result = mapOf(
                "total" to results.size,
                "succeeded" to results.count { it["status"] == "ok" },
                "failed" to results.count { it["status"] == "error" },
                "results" to results,
            )
            pulsarObjectMapper().writeValueAsString(result)
        }
    }

    // =========================================================================
    // Normalize
    // =========================================================================

    private suspend fun normalize(args: Map<String, Any?>): String {
        val sessionId = requireSessionId(args)
        val url = paramString(args, "url", "normalize", required = false)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing required parameter 'url' for webdb normalize")

        val managed = sessionManager.getSession(sessionId)
            ?: throw IllegalArgumentException("Session not found: $sessionId")

        return managed.withLock {
            val session = managed.agenticSession
            val normUrl = session.normalize(url)
            normUrl.urlString
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun requireSessionId(args: Map<String, Any?>): String {
        return args["sessionId"]?.toString()
            ?: throw IllegalArgumentException("Missing required parameter: sessionId")
    }

    /**
     * Export a single page from webdb to the target directory.
     */
    private suspend fun exportPage(session: PulsarSession, url: String, targetDir: Path): String {
        val normalizedUrl = session.normalize(url).urlString
        val page = session.getOrNull(normalizedUrl)
            ?: throw IllegalArgumentException("Page not found in webdb: $url (normalized: $normalizedUrl)")
        val filename = sanitizeFilename(normalizedUrl)
        val path = targetDir.resolve(filename)
        return session.exportTo(page, path).toString()
    }

    /**
     * Derive a safe filename from a URL.
     * Example: "http://example.com/page" → "example.com_page.htm"
     */
    private fun sanitizeFilename(url: String): String {
        val cleaned = url
            .removePrefix("https://")
            .removePrefix("http://")
            .replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
            .take(200)
            .trimEnd('.')
        return if (cleaned.endsWith(".htm") || cleaned.endsWith(".html")) cleaned else "$cleaned.htm"
    }
}
