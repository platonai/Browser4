package ai.platon.pulsar.coding

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Minimal fake LSP server used by [LanguageServerManagerTest].
 *
 * Runs as a separate JVM process over stdio, implementing just enough of the
 * LSP protocol to exercise the client: initialize handshake, initialized
 * notification, didOpen handling, and a `textDocument/publishDiagnostics`
 * push for the "diag" script.
 *
 * Usage: `java -cp <test-classpath> FakeLspServerMain <script>`
 */
object FakeLspServerMain {

    private val mapper = ObjectMapper()

    @JvmStatic
    fun main(args: Array<String>) {
        val script = args.firstOrNull() ?: "diag"
        val reader = BufferedReader(InputStreamReader(System.`in`, StandardCharsets.UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(System.out, StandardCharsets.UTF_8))

        // Echo loop: read Content-Length framed messages, respond as needed.
        while (true) {
            val line = reader.readLine() ?: break
            if (!line.startsWith("Content-Length:")) continue
            val len = line.substringAfter(':').trim().toInt()
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isBlank()) break
            }
            val payload = CharArray(len)
            var read = 0
            while (read < len) {
                val n = reader.read(payload, read, len - read)
                if (n < 0) return
                read += n
            }
            val msg = mapper.readTree(String(payload))

            when {
                msg.has("id") && msg["method"]?.asText() == "initialize" -> {
                    // Handshake success — report serverInfo and full capabilities.
                    write(writer, mapOf(
                        "jsonrpc" to "2.0",
                        "id" to msg["id"].asText(),
                        "result" to mapOf(
                            "capabilities" to mapOf(
                                "textDocumentSync" to 1,
                                "workspaceSymbolProvider" to true,
                                "referencesProvider" to true,
                            ),
                            "serverInfo" to mapOf("name" to "fake-lsp", "version" to "0.1")
                        )
                    ))
                }
                msg.has("id") && msg["method"]?.asText() == "textDocument/references" -> {
                    write(writer, mapOf(
                        "jsonrpc" to "2.0",
                        "id" to msg["id"].asText(),
                        "result" to listOf(
                            mapOf(
                                "uri" to (msg["params"]["textDocument"]["uri"].asText()),
                                "range" to mapOf(
                                    "start" to mapOf("line" to 2, "character" to 0),
                                    "end" to mapOf("line" to 2, "character" to 5)
                                )
                            )
                        )
                    ))
                }
                msg.has("id") -> {
                    // workspace/symbol and any other request: empty result.
                    write(writer, mapOf(
                        "jsonrpc" to "2.0",
                        "id" to msg["id"].asText(),
                        "result" to emptyList<Any>()
                    ))
                }
                msg.has("method") && msg["method"].asText() == "textDocument/didOpen" -> {
                    if (script == "diag") {
                        val uri = msg["params"]["textDocument"]["uri"].asText()
                        // Push a single error diagnostic at line 0 (1-based line 1).
                        write(writer, mapOf(
                            "jsonrpc" to "2.0",
                            "method" to "textDocument/publishDiagnostics",
                            "params" to mapOf(
                                "uri" to uri,
                                "diagnostics" to listOf(
                                    mapOf(
                                        "range" to mapOf(
                                            "start" to mapOf("line" to 0, "character" to 0),
                                            "end" to mapOf("line" to 0, "character" to 3)
                                        ),
                                        "severity" to 1,
                                        "code" to "TS1000",
                                        "source" to "fake",
                                        "message" to "fake error on line 1"
                                    )
                                )
                            )
                        ))
                    }
                }
                else -> {
                    // ignore (initialized notification etc.)
                }
            }
        }
        writer.flush()
    }

    private fun write(writer: BufferedWriter, message: Map<String, Any?>) {
        val json = mapper.writeValueAsString(message)
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        writer.write("Content-Length: ${bytes.size}\r\n\r\n")
        writer.write(json)
        writer.flush()
    }
}

