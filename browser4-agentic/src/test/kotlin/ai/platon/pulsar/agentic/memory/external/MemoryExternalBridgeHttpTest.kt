package ai.platon.pulsar.agentic.memory.external

import ai.platon.pulsar.agentic.mcp.server.McpHttpServer
import ai.platon.pulsar.agentic.model.TcEvaluate
import ai.platon.pulsar.agentic.model.ToolCallResult
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.AgentToolManager
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M4 http transport: the bridge connects to a REAL in-process MCP SSE server
 * ([McpHttpServer] over Ktor) and discovers/calls its tools over the wire —
 * full protocol: initialize → tools/list → tools/call.
 */
@DisplayName("MemoryExternalBridge http transport (M4)")
class MemoryExternalBridgeHttpTest {

    private lateinit var memoryServer: McpHttpServer
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var bridge: MemoryExternalBridge? = null

    private val stored = mutableMapOf<String, String>()

    @BeforeEach
    fun setUp() {
        // Fixture tool manager exposing two memory tools.
        val executor = mockk<ToolExecutor>(relaxed = true)
        every { executor.domain } returns "mem"
        every { executor.getToolSpecs() } returns mapOf(
            "memory_store" to ToolSpec(
                "mem", "memory_store",
                listOf(ToolSpec.Arg("key", "String", null), ToolSpec.Arg("value", "String", null)),
                "String", "Store a value under a key.",
            ),
            "memory_search" to ToolSpec(
                "mem", "memory_search",
                listOf(ToolSpec.Arg("query", "String", null)),
                "String", "Search stored values.",
            ),
        )
        val toolManager = mockk<AgentToolManager>(relaxed = true)
        every { toolManager.registeredExecutors } returns mapOf("mem" to executor)
        coEvery { toolManager.execute(any()) } answers {
            val tc = firstArg<ai.platon.pulsar.agentic.model.ToolCall>()
            val args = tc.arguments
            val text = when (tc.method) {
                "memory_store" -> {
                    stored[args["key"]?.toString() ?: ""] = args["value"]?.toString() ?: ""
                    "stored"
                }
                "memory_search" -> {
                    val q = args["query"]?.toString() ?: ""
                    stored.filterValues { it.contains(q) }.entries.joinToString("; ") { "${it.key}=${it.value}" }
                        .ifEmpty { "no hits" }
                }
                else -> "ok"
            }
            ToolCallResult(evaluate = TcEvaluate(value = text), message = null)
        }

        val port = ServerSocket(0).use { it.localPort }
        memoryServer = McpHttpServer(toolManager, port = port, host = "127.0.0.1")
        memoryServer.start()
    }

    @AfterEach
    fun tearDown() {
        runCatching { bridge?.close() }
        runCatching { memoryServer.stop() }
        scope.cancel()
    }

    private fun newBridge(url: String): MemoryExternalBridge {
        val b = MemoryExternalBridge(
            ExternalMemoryConfig(transport = "http", url = url, toolPrefix = "mem"),
            scope,
        )
        bridge = b
        return b
    }

    private fun endpoint(): String = "http://127.0.0.1:${memoryServer.actualPort}/mcp/sse"

    @Test
    @DisplayName("connects over SSE, discovers tools, and routes calls over the wire")
    fun testHttpConnectDiscoverCall() = runBlocking {
        val bridge = newBridge(endpoint())
        assertTrue(bridge.awaitConnected(timeoutMs = 10_000), "bridge must connect to the HTTP fixture server")

        // The fixture server (Browser4MCPServer) prefixes non-tab domains.
        val specs = bridge.getToolSpecs()
        assertEquals(setOf("mem_memory_store", "mem_memory_search"), specs.map { it.method }.toSet())

        val saved = bridge.call("mem_memory_store", mapOf("key" to "k1", "value" to "amazon price"))
        assertEquals("stored", saved)
        val found = bridge.call("mem_memory_search", mapOf("query" to "amazon"))
        assertTrue(found.contains("k1=amazon price"), "search must find the stored value: $found")
    }

    @Test
    @DisplayName("missing url fails loudly for the http transport")
    fun testMissingUrl() = runBlocking {
        val bridge = MemoryExternalBridge(ExternalMemoryConfig(transport = "http", url = null), scope)
        val ok = bridge.awaitConnected(timeoutMs = 3_000)
        assertEquals(false, ok) // connect fails; no crash
        bridge.close()
    }
}
