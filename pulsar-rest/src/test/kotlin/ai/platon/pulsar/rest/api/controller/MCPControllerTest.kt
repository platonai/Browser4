package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.rest.api.service.MCPService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for MCPController.
 */
class MCPControllerTest {

    private lateinit var mcpService: MCPService
    private lateinit var mcpController: MCPController
    private val objectMapper = jacksonObjectMapper()

    @BeforeEach
    fun setUp() {
        mcpService = mockk()
        mcpController = MCPController(mcpService)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    @DisplayName("test get server info delegates to service")
    fun testGetServerInfo() {
        val expectedInfo = mapOf(
            "name" to "browser4-mcp-server",
            "version" to "1.0.0",
            "description" to "test description"
        )

        every { mcpService.getServerInfo() } returns expectedInfo

        val result = mcpController.getServerInfo()

        assertEquals(expectedInfo, result)
        verify(exactly = 1) { mcpService.getServerInfo() }
    }

    @Test
    @DisplayName("test list tools delegates to service")
    fun testListTools() {
        val expectedTools = mapOf(
            "tools" to listOf(
                mapOf(
                    "name" to "test_tool",
                    "description" to "test description"
                )
            )
        )

        every { mcpService.listTools() } returns expectedTools

        val result = mcpController.listTools()

        assertEquals(expectedTools, result)
        verify(exactly = 1) { mcpService.listTools() }
    }

    @Test
    @DisplayName("test call tool with valid request")
    fun testCallToolValid() {
        val toolName = "load_page"
        val arguments = objectMapper.createObjectNode().apply {
            put("url", "https://example.com")
        }
        val request = objectMapper.createObjectNode().apply {
            put("name", toolName)
            set<JsonNode>("arguments", arguments)
        }

        val expectedResult = mapOf(
            "content" to listOf(
                mapOf(
                    "type" to "text",
                    "text" to "test result"
                )
            )
        )

        coEvery { mcpService.callTool(toolName, arguments) } returns expectedResult

        val result = mcpController.callTool(request)

        assertEquals(expectedResult, result)
        coVerify(exactly = 1) { mcpService.callTool(toolName, arguments) }
    }

    @Test
    @DisplayName("test call tool throws exception when name is missing")
    fun testCallToolMissingName() {
        val request = objectMapper.createObjectNode()

        val exception = assertThrows(IllegalArgumentException::class.java) {
            mcpController.callTool(request)
        }

        assertTrue(exception.message?.contains("Tool name is required") == true)
    }
}
