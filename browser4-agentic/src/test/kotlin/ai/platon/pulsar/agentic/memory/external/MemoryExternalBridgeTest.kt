package ai.platon.pulsar.agentic.memory.external

import ai.platon.pulsar.agentic.model.ToolSpec
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * M4 external memory bridge tests: a fixture MCP memory server runs in-process
 * over pipes (mirroring Browser4MCPServerE2ETest), and the bridge connects as
 * a real MCP client — full protocol: initialize → tools/list → tools/call.
 */
@DisplayName("MemoryExternalBridge (M4)")
class MemoryExternalBridgeTest {

    private lateinit var memoryServer: Server
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var bridge: MemoryExternalBridge? = null

    private val stored = mutableMapOf<String, String>()

    @BeforeEach
    fun setUp() {
        memoryServer = Server(
            serverInfo = Implementation(name = "fixture-memory-server", version = "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))
            ),
            instructions = "Fixture external memory server for bridge tests.",
        ) {
            addTool(
                name = "memory_store",
                description = "Store a value under a key.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("key", JsonObject(mapOf("type" to JsonPrimitive("string"))))
                        put("value", JsonObject(mapOf("type" to JsonPrimitive("string"))))
                    },
                    required = listOf("key", "value"),
                ),
            ) { request ->
                val args = request.params.arguments ?: buildJsonObject { }
                stored[args["key"]?.toString()?.trim('"') ?: ""] = args["value"]?.toString()?.trim('"') ?: ""
                CallToolResult(content = listOf(TextContent(text = "stored")))
            }
            addTool(
                name = "memory_search",
                description = "Search stored values.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("query", JsonObject(mapOf("type" to JsonPrimitive("string"))))
                    },
                    required = listOf("query"),
                ),
            ) { request ->
                val query = request.params.arguments?.get("query")?.toString()?.trim('"') ?: ""
                val hits = stored.filterValues { it.contains(query) }.entries.joinToString("; ") { "${it.key}=${it.value}" }
                CallToolResult(content = listOf(TextContent(text = hits.ifEmpty { "no hits" })))
            }
            addTool(
                name = "memory_get",
                description = "Get one stored value, with an optional default.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("key", JsonObject(mapOf("type" to JsonPrimitive("string"))))
                        put("default", JsonObject(mapOf("type" to JsonPrimitive("string"))))
                    },
                    required = listOf("key"), // `default` is OPTIONAL
                ),
            ) { request ->
                val args = request.params.arguments ?: buildJsonObject { }
                val key = args["key"]?.toString()?.trim('"') ?: ""
                val fallback = args["default"]?.toString()?.trim('"')
                CallToolResult(content = listOf(TextContent(text = stored[key] ?: fallback ?: "not found")))
            }
        }
    }

    @AfterEach
    fun tearDown() {
        runCatching { bridge?.close() }
        runCatching { runBlocking { memoryServer.close() } }
        serverJob?.cancel()
        scope.cancel()
    }

    /** Pipe-based transport factory pointing at the in-process fixture server. */
    private fun pipeFactory(): MemoryTransportFactory {
        val c2sIn = PipedInputStream()
        val c2sOut = PipedOutputStream(c2sIn)
        val s2cIn = PipedInputStream()
        val s2cOut = PipedOutputStream(s2cIn)

        serverJob = scope.launch {
            memoryServer.connect(
                StdioServerTransport(
                    inputStream = c2sIn.asSource().buffered(),
                    outputStream = s2cOut.asSink().buffered(),
                )
            )
        }
        return MemoryTransportFactory {
            TransportResource(
                transport = io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport(
                    input = s2cIn.asSource().buffered(),
                    output = c2sOut.asSink().buffered(),
                ),
                closer = null,
            )
        }
    }

    private fun newBridge(config: ExternalMemoryConfig = ExternalMemoryConfig(toolPrefix = "mem")): MemoryExternalBridge {
        val b = MemoryExternalBridge(config, scope, transportFactory = pipeFactory())
        bridge = b
        return b
    }

    @Test
    @DisplayName("connects, discovers tools, and routes calls over the full MCP protocol")
    fun testConnectDiscoverCall() = runBlocking {
        val bridge = newBridge()
        assertTrue(bridge.awaitConnected(timeoutMs = 5_000), "bridge must connect to the fixture server")

        val specs = bridge.getToolSpecs()
        assertEquals(setOf("memory_store", "memory_search", "memory_get"), specs.map { it.method }.toSet())
        val store = specs.first { it.method == "memory_store" }
        assertEquals(listOf("key", "value"), store.arguments.map { it.name })

        // Write then search — state persists on the server side.
        val saved = bridge.call("memory_store", mapOf("key" to "k1", "value" to "amazon price"))
        assertEquals("stored", saved)
        val found = bridge.call("memory_search", mapOf("query" to "amazon"))
        assertTrue(found.contains("k1=amazon price"), "search must find the stored value: $found")
    }

    @Test
    @DisplayName("allowlist filters discovered tools")
    fun testAllowlist() = runBlocking {
        val bridge = newBridge(ExternalMemoryConfig(toolPrefix = "mem", toolAllowlist = setOf("memory_search")))
        assertTrue(bridge.awaitConnected(timeoutMs = 5_000))
        assertEquals(listOf("memory_search"), bridge.getToolSpecs().map { it.method })
    }

    @Test
    @DisplayName("executor exposes discovered specs and validates arguments")
    fun testExecutor() {
        runBlocking {
            val bridge = newBridge()
            assertTrue(bridge.awaitConnected(timeoutMs = 5_000))
            val executor = MemoryExternalToolExecutor(bridge, "mem")

            assertEquals(setOf("memory_store", "memory_search", "memory_get"), executor.getToolSpecs().keys)
            // ToolCallSpecificationProvider feeds the registry/prompt.
            assertEquals(3, executor.getToolCallSpecifications().size)

            val result = executor.callFunctionOn("mem", "memory_store", mapOf("key" to "a", "value" to "b"), Any())
            assertEquals("stored", result)

            assertFailsWith<IllegalArgumentException> {
                runBlocking { executor.callFunctionOn("mem", "memory_store", mapOf("key" to "a"), Any()) }
            }
            assertFailsWith<IllegalArgumentException> {
                runBlocking { executor.callFunctionOn("mem", "nope", mapOf(), Any()) }
            }
            assertFailsWith<IllegalArgumentException> {
                runBlocking { executor.callFunctionOn("other", "memory_store", mapOf("key" to "a", "value" to "b"), Any()) }
            }
        }
    }

    @Test
    @DisplayName("spec mapping carries descriptions and argument types")
    fun testSpecMapping() {
        // Discovered specs come from the fixture's JSON schema.
        runBlocking {
            val bridge = newBridge()
            assertTrue(bridge.awaitConnected(timeoutMs = 5_000))
            val spec = bridge.getToolSpecs().first { it.method == "memory_store" }
            assertEquals("mem", spec.domain)
            assertEquals("String", spec.arguments.first { it.name == "key" }.type)
            assertTrue(spec.description!!.contains("Store"))
        }
    }

    @Test
    @DisplayName("optional provider parameters are not forced required")
    fun testOptionalParameter() {
        runBlocking {
            val bridge = newBridge()
            assertTrue(bridge.awaitConnected(timeoutMs = 5_000))
            val executor = MemoryExternalToolExecutor(bridge, "mem")

            val get = executor.getToolSpecs()["memory_get"]!!
            // `key` is required (null default), `default` is optional ("" placeholder).
            assertEquals(null, get.arguments.first { it.name == "key" }.defaultValue)
            assertEquals("", get.arguments.first { it.name == "default" }.defaultValue)

            // Calling with only the required arg succeeds.
            val result = executor.callFunctionOn("mem", "memory_get", mapOf("key" to "missing"), Any())
            assertEquals("not found", result)
            // Optional arg is honored when supplied.
            val withDefault = executor.callFunctionOn(
                "mem", "memory_get", mapOf("key" to "missing", "default" to "fallback"), Any(),
            )
            assertEquals("fallback", withDefault)
            // Missing the required arg fails.
            assertFailsWith<IllegalArgumentException> {
                runBlocking { executor.callFunctionOn("mem", "memory_get", mapOf(), Any()) }
            }
        }
    }

    @Test
    @DisplayName("fails loudly without a command for the stdio transport")
    fun testMissingCommand() {
        val config = ExternalMemoryConfig(enabled = true, command = null)
        val bridge = MemoryExternalBridge(config, scope, transportFactory = null)
        runBlocking {
            val ok = bridge.awaitConnected(timeoutMs = 3_000)
            assertEquals(false, ok) // connect fails; no crash
        }
        bridge.close()
    }
}
