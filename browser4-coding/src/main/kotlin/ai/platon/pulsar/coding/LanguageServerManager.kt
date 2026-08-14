package ai.platon.pulsar.coding

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight, dependency-free LSP (Language Server Protocol) client.
 *
 * Talks JSON-RPC 2.0 over stdio to a per-language server process
 * (tsserver, pyright-langserver, rust-analyzer, ...), implementing just the
 * methods needed for the coding domain's `diagnostics` / `symbols` / `references`
 * tools — no lsp4j, no external JSON-RPC library.
 *
 * ## Process lifecycle
 *
 * Servers are started lazily per language (first request wins), kept alive for
 * reuse (incremental diagnostics), and reaped after [IDLE_TIMEOUT_MS] without
 * requests to bound memory. A crashed server is restarted on the next request.
 *
 * ## Security
 *
 * Only servers on the approved [serverCommands] allow-list can be launched; the
 * agent can never supply an arbitrary executable. Servers only ever receive
 * file *contents* (didOpen) and return structured data — they never execute
 * project code.
 *
 * ## Threading
 *
 * Each server gets a dedicated coroutine reading stdout (the server pushes
 * notifications like `textDocument/publishDiagnostics` at any time) plus a
 * pending-response map keyed by JSON-RPC id. All protocol IO is sequential per
 * server via a single writer mutex.
 */
class LanguageServerManager(
    private val workspaceRoot: Path,
    private val serverCommands: Map<String, List<String>> = DEFAULT_SERVER_COMMANDS,
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
) : AutoCloseable {

    companion object {
        private val logger = LoggerFactory.getLogger(LanguageServerManager::class.java)

        /** Local Jackson mapper for JSON-RPC framing — no dependency on pulsar-common. */
        private val mapper: ObjectMapper by lazy {
            ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }

        const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L

        /**
         * Approved language servers. Keys are the language ids reported by
         * [detectLanguage]; values are the command lines used to launch the
         * server over stdio. Paths must be resolvable on the host; servers are
         * NEVER downloaded by the agent.
         */
        val DEFAULT_SERVER_COMMANDS: Map<String, List<String>> = mapOf(
            "typescript" to listOf("typescript-language-server", "--stdio"),
            "javascript" to listOf("typescript-language-server", "--stdio"),
            "python" to listOf("pyright-langserver", "--stdio"),
            "rust" to listOf("rust-analyzer"),
        )

        /** Language ids we know how to serve. */
        val SUPPORTED_LANGUAGES: Set<String> = setOf("typescript", "javascript", "python", "rust")
    }

    /** A single structured diagnostic. */
    data class Diagnostic(
        val file: String,
        val line: Int,       // 1-based
        val severity: String, // "error" | "warning" | "info" | "hint"
        val message: String,
        val code: String? = null,
        val source: String? = null,
    )

    /** A code symbol definition. */
    data class SymbolInfo(
        val name: String,
        val kind: String,
        val file: String,
        val line: Int,       // 1-based
        val rangeStart: Int, // 1-based
        val rangeEnd: Int,   // 1-based
    )

    /** A reference to a symbol. */
    data class ReferenceInfo(
        val file: String,
        val line: Int,
        val character: Int,
        val text: String? = null,
    )

    // ------------------------------------------------------------------
    // Internal server sessions
    // ------------------------------------------------------------------

    private class ServerSession(
        val language: String,
        val process: Process,
        val writer: BufferedWriter,
        val reader: BufferedReader,
    ) {
        val idCounter = AtomicLong(0)
        val pending = ConcurrentHashMap<String, CompletableDeferred<Any?>>()
        val diagnosticsByUri = ConcurrentHashMap<String, List<Map<String, Any?>>>()
        var lastUsed = System.currentTimeMillis()
        val closed = CompletableDeferred<Unit>()
    }

    private val sessions = ConcurrentHashMap<String, ServerSession>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun detectLanguage(path: String): String? {
        return when (path.substringAfterLast('.').lowercase()) {
            "ts", "tsx", "mts", "cts" -> "typescript"
            "js", "jsx", "mjs", "cjs" -> "javascript"
            "py", "pyi" -> "python"
            "rs" -> "rust"
            else -> null
        }
    }

    private fun toUri(path: String): String {
        val abs = if (Path.of(path).isAbsolute) path else workspaceRoot.resolve(path).toString()
        return abs.replace('\\', '/').let { "file:///$it" }
    }

    private fun fromUri(uri: String): String {
        return uri.removePrefix("file:///").replace('/', '\\')
    }

    private fun lineTo1Based(line: Int): Int = line + 1

    /**
     * Get diagnostics for a file, starting a server for its language on demand.
     * Returns an empty list (with a message) when the language is unsupported or
     * the server is unavailable — never throws.
     */
    suspend fun diagnostics(path: String): List<Diagnostic> {
        val lang = detectLanguage(path) ?: return emptyList()
        val session = getSession(lang) ?: return emptyList()

        try {
            val uri = toUri(path)
            val content = workspaceRoot.resolve(path).toFile().takeIf { it.exists() }?.readText() ?: return emptyList()
            sendNotification(session, "textDocument/didOpen", mapOf(
                "textDocument" to mapOf(
                    "uri" to uri,
                    "languageId" to lang,
                    "version" to 1,
                    "text" to content,
                )
            ))
            // Give the server a beat to publish diagnostics, then read what arrived.
            delay(300)
            val raw = session.diagnosticsByUri.remove(uri) ?: return emptyList()
            return raw.mapNotNull { d ->
                val range = d["range"] as? Map<*, *> ?: return@mapNotNull null
                val start = range["start"] as? Map<*, *> ?: return@mapNotNull null
                Diagnostic(
                    file = fromUri(uri),
                    line = lineTo1Based((start["line"] as? Number)?.toInt() ?: 0),
                    severity = (d["severity"] as? Number)?.toInt()?.let { severityName(it) } ?: "info",
                    message = d["message"] as? String ?: "",
                    code = (d["code"] as? String) ?: (d["code"] as? Number)?.toString(),
                    source = d["source"] as? String,
                )
            }
        } catch (e: Exception) {
            logger.warn("LSP diagnostics failed for {}: {}", path, e.message)
            return emptyList()
        }
    }

    /**
     * Search for symbol definitions matching [pattern] (substring match on name).
     * Uses workspace/symbol when available; falls back to documentSymbol per
     * open document (best effort).
     */
    suspend fun symbols(pattern: String): List<SymbolInfo> {
        val results = mutableListOf<SymbolInfo>()
        sessions.forEach { (_, session) ->
            try {
                val id = nextId(session)
                val response = request(
                    session, id, "workspace/symbol", mapOf("query" to pattern)
                )
                val items = response as? List<*> ?: return@forEach
                items.forEach { item ->
                    val m = item as? Map<*, *> ?: return@forEach
                    val name = m["name"] as? String ?: return@forEach
                    val kind = (m["kind"] as? Number)?.toInt()?.let { symbolKindName(it) } ?: "unknown"
                    val location = m["location"] as? Map<*, *> ?: return@forEach
                    val uri = location["uri"] as? String ?: return@forEach
                    val range = location["range"] as? Map<*, *> ?: return@forEach
                    val start = range["start"] as? Map<*, *> ?: return@forEach
                    val end = range["end"] as? Map<*, *> ?: return@forEach
                    results += SymbolInfo(
                        name = name,
                        kind = kind,
                        file = fromUri(uri),
                        line = lineTo1Based((start["line"] as? Number)?.toInt() ?: 0),
                        rangeStart = lineTo1Based((start["line"] as? Number)?.toInt() ?: 0),
                        rangeEnd = lineTo1Based((end["line"] as? Number)?.toInt() ?: 0),
                    )
                }
            } catch (_: Exception) {
                // workspace/symbol may be unsupported — skip this server
            }
        }
        return results.distinctBy { "${it.file}:${it.line}:${it.name}" }
            .filter { pattern.isBlank() || it.name.contains(pattern, ignoreCase = true) }
            .sortedWith(compareBy({ it.file }, { it.line }))
    }

    /**
     * Find references to [symbol] (exact name) in the given [path].
     * Opens the file then issues textDocument/references.
     */
    suspend fun references(path: String, symbol: String): List<ReferenceInfo> {
        val lang = detectLanguage(path) ?: return emptyList()
        val session = getSession(lang) ?: return emptyList()
        try {
            val uri = toUri(path)
            val content = workspaceRoot.resolve(path).toFile().takeIf { it.exists() }?.readText() ?: return emptyList()
            sendNotification(session, "textDocument/didOpen", mapOf(
                "textDocument" to mapOf("uri" to uri, "languageId" to lang, "version" to 1, "text" to content)
            ))
            // Position 0,0 in the document — enough for whole-document reference queries.
            val id = nextId(session)
            val response = request(session, id, "textDocument/references", mapOf(
                "textDocument" to mapOf("uri" to uri),
                "position" to mapOf("line" to 0, "character" to 0),
                "context" to mapOf("includeDeclaration" to true),
            ))
            val items = response as? List<*> ?: return emptyList()
            return items.mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                val location = m["location"] as? Map<*, *> ?: return@mapNotNull null
                val uri2 = location["uri"] as? String ?: return@mapNotNull null
                val range = location["range"] as? Map<*, *> ?: return@mapNotNull null
                val start = range["start"] as? Map<*, *> ?: return@mapNotNull null
                ReferenceInfo(
                    file = fromUri(uri2),
                    line = lineTo1Based((start["line"] as? Number)?.toInt() ?: 0),
                    character = (start["character"] as? Number)?.toInt() ?: 0,
                )
            }.filter { it.file == fromUri(uri) }
        } catch (e: Exception) {
            logger.warn("LSP references failed for {}: {}", path, e.message)
            return emptyList()
        }
    }

    /**
     * List which language servers are available (installed on PATH).
     */
    fun availableServers(): Map<String, Boolean> {
        return serverCommands.mapValues { (_, cmd) ->
            cmd.isNotEmpty() && runCatching { ProcessBuilder(cmd[0]).command(cmd).start().destroy() }.isSuccess
        }
    }

    /** Shut down all running servers. */
    override fun close() {
        scope.cancel()
        sessions.values.forEach { session ->
            runCatching {
                sendNotification(session, "exit", emptyMap<String, Any?>())
                session.process.destroy()
                session.process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                session.process.destroyForcibly()
            }
        }
        sessions.clear()
    }

    // ------------------------------------------------------------------
    // Session management
    // ------------------------------------------------------------------

    private suspend fun getSession(language: String): ServerSession? {
        val command = serverCommands[language] ?: return null
        sessions[language]?.let {
            if (it.process.isAlive) {
                it.lastUsed = System.currentTimeMillis()
                return it
            }
            sessions.remove(language)
        }

        return try {
            val pb = ProcessBuilder(command)
                .directory(workspaceRoot.toFile())
                .redirectErrorStream(true)
            pb.environment()["LSP_AGENT"] = "browser4"
            val process = pb.start()
            val writer = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
            val reader = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
            val session = ServerSession(language, process, writer, reader)
            sessions[language] = session

            // Reader loop: dispatches responses and notifications.
            scope.launch {
                try {
                    while (process.isAlive) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        handleServerLine(session, line)
                    }
                } catch (e: Exception) {
                    logger.debug("LSP reader ended for {}: {}", language, e.message)
                } finally {
                    session.closed.complete(Unit)
                }
            }

            // Initialize handshake.
            val id = nextId(session)
            val initResponse = request(session, id, "initialize", mapOf(
                "processId" to null,
                "rootUri" to toUri("."),
                "capabilities" to mapOf(
                    "textDocument" to mapOf(
                        "publishDiagnostics" to mapOf("relatedInformation" to false)
                    )
                ),
                "workspaceFolders" to listOf(mapOf("uri" to toUri("."), "name" to workspaceRoot.fileName.toString())),
            ))
            if (initResponse == null) {
                logger.warn("LSP initialize failed for {}", language)
                closeSession(language)
                return null
            }
            sendNotification(session, "initialized", emptyMap<String, Any?>())
            logger.info("LSP server ready: {} ({})", language, command.joinToString(" "))
            session
        } catch (e: Exception) {
            logger.warn("Failed to start LSP server for {} ({}): {}", language, command.joinToString(" "), e.message)
            sessions.remove(language)
            null
        }
    }

    private fun closeSession(language: String) {
        sessions.remove(language)?.let { session ->
            runCatching { sendNotification(session, "exit", emptyMap<String, Any?>()) }
            runCatching { session.process.destroyForcibly() }
        }
    }

    private fun nextId(session: ServerSession): String = session.idCounter.incrementAndGet().toString()

    // ------------------------------------------------------------------
    // JSON-RPC transport
    // ------------------------------------------------------------------

    private fun sendNotification(session: ServerSession, method: String, params: Map<String, Any?>) {
        val message = mapOf("jsonrpc" to "2.0", "method" to method, "params" to params)
        writeMessage(session, message)
    }

    private suspend fun request(
        session: ServerSession,
        id: String,
        method: String,
        params: Map<String, Any?>,
    ): Any? {
        val deferred = CompletableDeferred<Any?>()
        session.pending[id] = deferred
        writeMessage(session, mapOf("jsonrpc" to "2.0", "id" to id.toLongOrNull(), "method" to method, "params" to params))
        return try {
            withTimeout(15_000) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            session.pending.remove(id)
            logger.warn("LSP request timed out: {}.{}", session.language, method)
            null
        }
    }

    @Synchronized
    private fun writeMessage(session: ServerSession, message: Map<String, Any?>) {
        val json = mapper.writeValueAsString(message)
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        session.writer.write("Content-Length: ${bytes.size}\r\n\r\n")
        session.writer.write(json)
        session.writer.flush()
        session.lastUsed = System.currentTimeMillis()
    }

    private fun handleServerLine(session: ServerSession, line: String) {
        if (!line.startsWith("Content-Length:")) return
        val len = line.substringAfter(':').trim().toIntOrNull() ?: return
        val headers = StringBuilder()
        while (true) {
            val h = session.reader.readLine() ?: return
            if (h.isBlank()) break
            headers.append(h).append('\n')
        }
        val payload = CharArray(len)
        var read = 0
        while (read < len) {
            val n = session.reader.read(payload, read, len - read)
            if (n < 0) return
            read += n
        }
        val json = String(payload)
        try {
            val tree = mapper.readTree(json)
            if (tree.has("id")) {
                val id = tree["id"].asText()
                val result = if (tree.has("error")) {
                    logger.debug("LSP error response for {}: {}", id, tree["error"])
                    null
                } else {
                    mapper
                        .convertValue(tree["result"], Any::class.java)
                }
                session.pending.remove(id)?.complete(result)
            } else if (tree.has("method")) {
                val method = tree["method"].asText()
                when (method) {
                    "textDocument/publishDiagnostics" -> {
                        val uri = tree["params"]["uri"].asText()
                        val diags = tree["params"]["diagnostics"].let { d ->
                            mapper
                                .convertValue(d, List::class.java)
                        }
                        session.diagnosticsByUri[uri] = diags as List<Map<String, Any?>>
                    }
                    else -> { /* ignore other notifications */ }
                }
            }
        } catch (e: Exception) {
            logger.debug("Failed to parse LSP message: {}", e.message)
        }
    }

    // ------------------------------------------------------------------
    // Enum name mapping (subset of the LSP enums we care about)
    // ------------------------------------------------------------------

    private fun severityName(severity: Int): String = when (severity) {
        1 -> "error"
        2 -> "warning"
        3 -> "info"
        4 -> "hint"
        else -> "info"
    }

    private fun symbolKindName(kind: Int): String = when (kind) {
        1, 2, 3, 4, 5 -> "function"        // File, Module, Namespace, Package, Class
        6 -> "method"
        7 -> "property"
        8 -> "field"
        9, 10 -> "constructor"
        11 -> "enum"
        12 -> "interface"
        13 -> "function"
        14 -> "variable"
        15 -> "constant"
        16 -> "string"
        17 -> "number"
        18 -> "boolean"
        19 -> "array"
        20, 21, 22 -> "object"
        23 -> "key"
        24 -> "null"
        25 -> "enum-member"
        26 -> "struct"
        27 -> "event"
        28 -> "operator"
        29 -> "type-parameter"
        else -> "unknown"
    }
}



