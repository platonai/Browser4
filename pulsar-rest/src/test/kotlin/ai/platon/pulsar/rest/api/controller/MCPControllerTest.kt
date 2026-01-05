package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.mcp.*
import ai.platon.pulsar.rest.api.service.MCPService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import kotlin.test.assertTrue

/**
 * Tests for MCPController REST API endpoints.
 */
@SpringBootTest(classes = [Application::class])
@AutoConfigureMockMvc
class MCPControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var mcpService: MCPService

    private val objectMapper = jacksonObjectMapper()

    @BeforeEach
    fun setup() {
        // Reset the registry to initial state before each test
        MCPToolRegistry.instance.reset()
    }

    // ========================================================================
    // Tool Endpoint Tests
    // ========================================================================

    @Test
    fun `test list tools returns non-empty list`() {
        mockMvc.perform(get("/api/mcp/tools"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.tools").isArray)
            .andExpect(jsonPath("$.tools").isNotEmpty)
    }

    @Test
    fun `test list tools by domain`() {
        mockMvc.perform(get("/api/mcp/tools/domain/driver"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.tools").isArray)
    }

    @Test
    fun `test get specific tool`() {
        mockMvc.perform(get("/api/mcp/tools/driver.click"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.name").value("driver.click"))
    }

    @Test
    fun `test execute tool call`() {
        val request = ToolCallRequest(
            name = "driver.click",
            arguments = mapOf("selector" to "#button")
        )

        mockMvc.perform(
            post("/api/mcp/tools/call")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.content").isArray)
    }

    @Test
    fun `test execute unknown tool returns error`() {
        val request = ToolCallRequest(
            name = "unknown.tool",
            arguments = emptyMap()
        )

        mockMvc.perform(
            post("/api/mcp/tools/call")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.isError").value(true))
    }

    // ========================================================================
    // Resource Endpoint Tests
    // ========================================================================

    @Test
    fun `test list resources`() {
        mockMvc.perform(get("/api/mcp/resources"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.resources").isArray)
    }

    @Test
    fun `test list resource templates`() {
        mockMvc.perform(get("/api/mcp/resources/templates"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.resourceTemplates").isArray)
    }

    // ========================================================================
    // Prompt Endpoint Tests
    // ========================================================================

    @Test
    fun `test list prompts`() {
        mockMvc.perform(get("/api/mcp/prompts"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.prompts").isArray)
    }

    // ========================================================================
    // Server Info Endpoint Tests
    // ========================================================================

    @Test
    fun `test get capabilities`() {
        mockMvc.perform(get("/api/mcp/capabilities"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.tools").exists())
    }

    @Test
    fun `test get server info`() {
        mockMvc.perform(get("/api/mcp/info"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.name").value("Browser4"))
            .andExpect(jsonPath("$.version").exists())
    }

    @Test
    fun `test get stats`() {
        mockMvc.perform(get("/api/mcp/stats"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.tools").isNumber)
    }

    // ========================================================================
    // JSON-RPC Endpoint Tests
    // ========================================================================

    @Test
    fun `test JSON-RPC tools-list`() {
        val request = """
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "tools/list"
            }
        """.trimIndent()

        mockMvc.perform(
            post("/api/mcp/rpc")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request)
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.result.tools").isArray)
    }

    @Test
    fun `test JSON-RPC initialize`() {
        val request = """
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "clientInfo": {
                        "name": "TestClient",
                        "version": "1.0"
                    }
                }
            }
        """.trimIndent()

        mockMvc.perform(
            post("/api/mcp/rpc")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request)
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.result.protocolVersion").exists())
            .andExpect(jsonPath("$.result.serverInfo.name").value("Browser4"))
    }

    @Test
    fun `test JSON-RPC method not found`() {
        val request = """
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "unknown/method"
            }
        """.trimIndent()

        mockMvc.perform(
            post("/api/mcp/rpc")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request)
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error.code").value(-32601))
    }

    @Test
    fun `test JSON-RPC ping`() {
        val request = """
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "ping"
            }
        """.trimIndent()

        mockMvc.perform(
            post("/api/mcp/rpc")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request)
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.result").exists())
    }
}
