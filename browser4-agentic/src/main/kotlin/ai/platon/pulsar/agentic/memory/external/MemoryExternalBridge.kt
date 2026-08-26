package ai.platon.pulsar.agentic.memory.external

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.common.getLogger
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.mcpSseTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlin.time.Duration.Companion.milliseconds
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** A created transport plus the resource that must be closed with it (e.g. the process). */
data class TransportResource(
    val transport: Transport,
    val closer: AutoCloseable? = null,
)

/** Creates the MCP transport the bridge connects through (injectable for tests). */
fun interface MemoryTransportFactory {
    suspend fun create(): TransportResource
}

/** Spawns the configured external memory server as a subprocess (stdio transport). */
class ProcessMemoryTransportFactory(
    private val config: ExternalMemoryConfig,
) : MemoryTransportFactory {

    override suspend fun create(): TransportResource {
        val command = requireNotNull(config.command) {
            "browser4.agent.memory.external.command is required for the stdio transport"
        }
        val parts = splitCommandLine(command)
        val process = ProcessBuilder(parts).start()
        return TransportResource(
            transport = StdioClientTransport(
                input = process.inputStream.asSource().buffered(),
                output = process.outputStream.asSink().buffered(),
                error = process.errorStream.asSource().buffered(),
            ),
            closer = AutoCloseable {
                runCatching { process.destroyForcibly() }
                runCatching { process.waitFor() }
            },
        )
    }

    /** Minimal shell-like split: whitespace-separated tokens, double quotes group. */
    private fun splitCommandLine(line: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        line.forEach { ch ->
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch.isWhitespace() && !inQuotes -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }
}

/**
 * Connects to an external memory server over the MCP SSE (streamable HTTP)
 * transport. The server owns its lifecycle — the bridge only closes its own
 * Ktor client.
 */
class HttpMemoryTransportFactory(
    private val url: String,
    private val timeoutMs: Long = 30_000,
) : MemoryTransportFactory {

    override suspend fun create(): TransportResource {
        // The MCP SSE transport requires the Ktor client SSE plugin.
        val httpClient = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO) {
            install(io.ktor.client.plugins.sse.SSE)
        }
        val transport = httpClient.mcpSseTransport(url, timeoutMs.milliseconds) { }
        return TransportResource(
            transport = transport,
            closer = AutoCloseable { runCatching { httpClient.close() } },
        )
    }
}

/**
 * L2 external memory MCP bridge (design M4, aligned with the DSH reference):
 * connects to a third-party memory server (Memorix / MCP Reference Memory /
 * Engram or any MCP server), discovers its tools, and routes `tools/call`
 * through the MCP client.
 *
 * Responsibility boundary: Browser4 starts/stops the server process (stdio)
 * and bridges the tool interface; the provider owns storage, accounts and
 * data policy. The bridge is a derived convenience — it is never the
 * authoritative memory (L0 log + L1 PEM remain the source of truth).
 */
class MemoryExternalBridge(
    private val config: ExternalMemoryConfig,
    private val scope: CoroutineScope,
    private val transportFactory: MemoryTransportFactory? = null,
) : AutoCloseable {

    private val logger = getLogger(MemoryExternalBridge::class)

    private val connectJob: Job
    @Volatile
    private var client: Client? = null
    @Volatile
    private var resource: TransportResource? = null
    private val connected = AtomicBoolean(false)

    /** Discovered tool specs, keyed by the provider's raw tool name. */
    private val toolSpecs = ConcurrentHashMap<String, ToolSpec>()

    init {
        connectJob = scope.launch { connect() }
    }

    /**
     * Wait until the connect + discovery handshake finished (or timed out).
     * Returns true when tools were discovered.
     */
    suspend fun awaitConnected(timeoutMs: Long = config.connectTimeoutMs): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!connected.get() && connectJob.isActive) delay(50)
            connected.get()
        } ?: false

    fun isConnected(): Boolean = connected.get()

    /** Discovered tool specs (method = provider tool name). */
    fun getToolSpecs(): List<ToolSpec> = toolSpecs.values.toList()

    /** Call a provider tool with plain arguments; returns the text payload. */
    suspend fun call(toolName: String, args: Map<String, Any?>): String {
        val c = client ?: throw IllegalStateException("External memory bridge is not connected")
        val result = c.callTool(toolName, args)
        val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
        return if (result.isError == true) "[error] $text" else text
    }

    override fun close() {
        connectJob.cancel()
        // The MCP client's close is suspend; run it to completion (bounded shutdown).
        runCatching { kotlinx.coroutines.runBlocking { client?.close() } }
        runCatching { resource?.closer?.close() }
    }

    private suspend fun connect() {
        runCatching {
            val factory = transportFactory ?: when (config.transport) {
                "http" -> HttpMemoryTransportFactory(
                    requireNotNull(config.url) {
                        "browser4.agent.memory.external.url is required for the http transport"
                    },
                    config.connectTimeoutMs,
                )
                else -> ProcessMemoryTransportFactory(config)
            }
            val res = factory.create()
            resource = res
            val c = Client(Implementation(name = "browser4-memory-bridge", version = "1.0.0"))
            c.connect(res.transport)
            client = c

            val tools = c.listTools().tools
            tools.forEach { t ->
                if (config.toolAllowlist.isEmpty() || t.name in config.toolAllowlist) {
                    toolSpecs[t.name] = toToolSpec(t)
                }
            }
            connected.set(true)
            logger.info(
                "External memory bridge connected: {} tools (prefix '{}')",
                toolSpecs.size, config.toolPrefix,
            )
        }.onFailure {
            logger.warn("External memory bridge connect failed: {}", it.message)
        }
    }

    private fun toToolSpec(tool: Tool): ToolSpec {
        val required = tool.inputSchema?.required?.toSet() ?: emptySet()
        val args = tool.inputSchema?.properties?.let { props ->
            props.entries.mapNotNull { (name, propNode) ->
                val type = (propNode as? kotlinx.serialization.json.JsonObject)
                    ?.get("type")?.toString()?.trim('"') ?: "string"
                ToolSpec.Arg(
                    name = name,
                    type = jsonTypeToKotlin(type),
                    // ToolSpec semantic: null defaultValue = REQUIRED. Optional
                    // provider parameters get a "" placeholder so the executor's
                    // required-set derivation matches the provider's schema.
                    defaultValue = if (name in required) null else "",
                )
            }
        }.orEmpty()
        return ToolSpec(
            domain = config.toolPrefix,
            method = tool.name,
            arguments = args,
            returnType = "String",
            description = tool.description?.trim()?.takeIf { it.isNotBlank() }
                ?: "External memory tool ${tool.name}",
        )
    }

    private fun jsonTypeToKotlin(jsonType: String): String = when (jsonType.lowercase()) {
        "integer" -> "Int"
        "number" -> "Double"
        "boolean" -> "Boolean"
        "array" -> "List<String>"
        "object" -> "Map<String, Any>"
        else -> "String"
    }
}
