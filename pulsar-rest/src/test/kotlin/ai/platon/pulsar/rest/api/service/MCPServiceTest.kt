package ai.platon.pulsar.rest.api.service

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.tools.crawl.ScrapeResponse
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.persist.metadata.ProtocolStatusCodes
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for MCPService.
 */
class MCPServiceTest {

    private lateinit var session: AgenticSession
    private lateinit var scrapeService: ScrapeService
    private lateinit var mcpService: MCPService
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    @BeforeEach
    fun setUp() {
        session = mockk(relaxed = true)
        scrapeService = mockk(relaxed = true)
        mcpService = MCPService(session, scrapeService)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    @DisplayName("test server info returns correct metadata")
    fun testServerInfo() {
        val info = mcpService.getServerInfo()

        assertEquals("browser4-mcp-server", info["name"])
        assertEquals("1.0.0", info["version"])
        assertNotNull(info["description"])
        assertNotNull(info["capabilities"])
    }

    @Test
    @DisplayName("test list tools returns all defined tools")
    fun testListTools() {
        val result = mcpService.listTools()

        val tools = result["tools"] as? List<*>
        assertNotNull(tools)
        assertTrue(tools!!.size >= 4) // At least 4 tools defined

        // Check that key tools are present
        val toolNames = tools.mapNotNull { (it as? Map<*, *>)?.get("name") }
        assertTrue(toolNames.contains("load_page"))
        assertTrue(toolNames.contains("scrape_data"))
        assertTrue(toolNames.contains("extract_text"))
        assertTrue(toolNames.contains("get_page_info"))
    }

    @Test
    @DisplayName("test list tools includes required schema information")
    fun testListToolsSchema() {
        val result = mcpService.listTools()

        val tools = result["tools"] as List<Map<String, Any>>
        val loadPageTool = tools.find { it["name"] == "load_page" }

        assertNotNull(loadPageTool)
        assertNotNull(loadPageTool!!["description"])
        assertNotNull(loadPageTool["inputSchema"])

        val schema = loadPageTool["inputSchema"] as Map<*, *>
        assertEquals("object", schema["type"])
        assertNotNull(schema["properties"])
        assertNotNull(schema["required"])
    }

    @Test
    @DisplayName("test call unknown tool throws exception")
    fun testCallUnknownTool() {
        val arguments = objectMapper.createObjectNode()
        
        val result = runBlocking {
            mcpService.callTool("unknown_tool", arguments)
        }

        assertTrue(result["isError"] as Boolean)
        val content = result["content"] as List<*>
        val errorText = (content[0] as Map<*, *>)["text"] as String
        assertTrue(errorText.contains("Unknown tool"))
    }

    @Test
    @DisplayName("test call tool with missing required argument returns error")
    fun testCallToolMissingArgument() {
        // Call load_page without required 'url' argument
        val arguments = objectMapper.createObjectNode()

        val result = runBlocking {
            mcpService.callTool("load_page", arguments)
        }

        assertTrue(result["isError"] as Boolean)
        val content = result["content"] as List<*>
        val errorText = (content[0] as Map<*, *>)["text"] as String
        assertTrue(errorText.contains("required") || errorText.contains("Error"))
    }

    @Test
    @DisplayName("test scrape data tool execution")
    fun testScrapeDataTool() {
        val sql = "SELECT * FROM test"
        val mockResponse = ScrapeResponse(
            result = "test result",
            statusCode = ResourceStatus.SC_OK,
            protocolStatusCode = ProtocolStatusCodes.SUCCESS
        )

        every { scrapeService.executeQuery(any()) } returns mockResponse

        val arguments = objectMapper.createObjectNode().apply {
            put("sql", sql)
        }

        val result = runBlocking {
            mcpService.callTool("scrape_data", arguments)
        }

        assertFalse(result.containsKey("isError"))
        verify(exactly = 1) { scrapeService.executeQuery(match { it.sql == sql }) }
    }
}
