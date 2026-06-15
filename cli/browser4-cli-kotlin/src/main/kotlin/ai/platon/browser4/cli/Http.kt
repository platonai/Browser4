package ai.platon.browser4.cli

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * HTTP client for communicating with a Browser4 server via the MCP protocol
 * and REST endpoints.
 *
 * Mirrors the Rust [http.rs] module.
 */
object McpClient {

    private val mapper = jacksonObjectMapper()

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    private const val DEFAULT_TIMEOUT_SECS = 30L
    private const val NAVIGATION_TIMEOUT_SECS = 120L

    private val NAVIGATION_TOOLS = setOf(
        "browser_navigate", "browser_reload",
        "browser_navigate_back", "browser_navigate_forward",
    )

    /**
     * Calls a Browser4 MCP tool via `POST /mcp/call-tool` and returns the
     * text content from the MCP response.
     *
     * Navigational tools get a longer timeout (120s vs 30s).
     */
    fun callTool(
        baseUrl: String,
        sessionId: String?,
        tool: String,
        args: Map<String, Any>,
    ): Result<String> {
        return try {
            val timeoutSecs = if (tool in NAVIGATION_TOOLS) NAVIGATION_TIMEOUT_SECS
                else DEFAULT_TIMEOUT_SECS

            val url = "${baseUrl.trimEnd('/')}/mcp/call-tool"
            val arguments = if (sessionId != null) {
                args + ("sessionId" to sessionId)
            } else {
                args
            }
            val payload = mapOf("tool" to tool, "arguments" to arguments)
            val json = mapper.writeValueAsString(payload)

            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSecs))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                return Result.failure(
                    RuntimeException("HTTP ${response.statusCode()}: ${response.body()}")
                )
            }

            val data: Map<String, Any?> = mapper.readValue(response.body())
            if (data["isError"] == true) {
                val errorText = extractTextPayload(data)
                return Result.failure(RuntimeException(errorText))
            }

            val text = extractTextPayload(data)
            Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Sends a GET request to the given path on the server. */
    fun get(baseUrl: String, path: String): Result<String> {
        return try {
            val url = "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECS))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) Result.success(response.body())
            else Result.failure(RuntimeException("HTTP ${response.statusCode()}: ${response.body()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Sends a POST request with a JSON string body. */
    fun post(baseUrl: String, path: String, body: String): Result<String> {
        return try {
            val url = "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECS))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) Result.success(response.body())
            else Result.failure(RuntimeException("HTTP ${response.statusCode()}: ${response.body()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---- private ----

    /**
     * Extracts text content from an MCP response envelope.
     *
     * MCP responses have the form:
     * ```json
     * { "content": [{ "type": "text", "text": "..." }] }
     * ```
     */
    private fun extractTextPayload(data: Map<String, Any?>): String {
        val content = data["content"]
        if (content is List<*>) {
            for (item in content) {
                if (item is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val textItem = item as Map<String, Any?>
                    textItem["text"]?.let { return it.toString() }
                }
            }
        }
        // Fallback: return the whole response as a string
        return data.toString()
    }
}
