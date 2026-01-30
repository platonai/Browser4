package ai.platon.pulsar.rest.api.service

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.tools.crawl.ScrapeRequest
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.persist.WebPage
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Service

/**
 * MCP (Model Context Protocol) Service for Browser4.
 *
 * This service exposes Browser4's capabilities as MCP tools that can be used by
 * MCP clients like Claude Desktop, Cursor, or other AI assistants.
 *
 * The service implements the MCP protocol to provide:
 * - Tool listing (list_tools)
 * - Tool execution (call_tool)
 * - Server information
 *
 * Available tools:
 * - load_page: Load a web page and return its content
 * - scrape_data: Extract structured data using X-SQL
 * - extract_text: Extract text content from a page
 * - take_screenshot: Capture a screenshot of a page
 *
 * @property session The agentic session for executing browser operations.
 * @property scrapeService The scrape service for data extraction.
 */
@Service
class MCPService(
    private val session: AgenticSession,
    private val scrapeService: ScrapeService
) {
    private val logger = getLogger(this)
    private val objectMapper = jacksonObjectMapper()

    companion object {
        const val SERVER_NAME = "browser4-mcp-server"
        const val SERVER_VERSION = "1.0.0"
        const val SERVER_DESCRIPTION = "Browser4 MCP Server - AI-powered browser automation and data extraction"
    }

    /**
     * Tool definitions for the MCP server.
     */
    private val toolDefinitions = mapOf(
        "load_page" to ToolDefinition(
            name = "load_page",
            description = "Load a web page and return its content. Supports advanced options like wait conditions, scrolling, and custom headers.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "url" to mapOf(
                        "type" to "string",
                        "description" to "The URL of the page to load"
                    ),
                    "options" to mapOf(
                        "type" to "string",
                        "description" to "Optional load options (e.g., '-expires 1d -refresh')"
                    )
                ),
                "required" to listOf("url")
            )
        ),
        "scrape_data" to ToolDefinition(
            name = "scrape_data",
            description = "Extract structured data from web pages using X-SQL queries. Supports complex data extraction patterns.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "sql" to mapOf(
                        "type" to "string",
                        "description" to "The X-SQL query to execute for data extraction"
                    )
                ),
                "required" to listOf("sql")
            )
        ),
        "extract_text" to ToolDefinition(
            name = "extract_text",
            description = "Extract clean text content from a web page, removing HTML tags and scripts.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "url" to mapOf(
                        "type" to "string",
                        "description" to "The URL of the page to extract text from"
                    ),
                    "selector" to mapOf(
                        "type" to "string",
                        "description" to "Optional CSS selector to extract text from specific elements"
                    )
                ),
                "required" to listOf("url")
            )
        ),
        "get_page_info" to ToolDefinition(
            name = "get_page_info",
            description = "Get metadata and information about a web page including title, status, and load time.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "url" to mapOf(
                        "type" to "string",
                        "description" to "The URL of the page to get information about"
                    )
                ),
                "required" to listOf("url")
            )
        )
    )

    /**
     * Get server information.
     */
    fun getServerInfo(): Map<String, Any> {
        return mapOf(
            "name" to SERVER_NAME,
            "version" to SERVER_VERSION,
            "description" to SERVER_DESCRIPTION,
            "capabilities" to mapOf(
                "tools" to mapOf<String, Any>()
            )
        )
    }

    /**
     * List all available tools.
     */
    fun listTools(): Map<String, Any> {
        val tools = toolDefinitions.values.map { tool ->
            mapOf(
                "name" to tool.name,
                "description" to tool.description,
                "inputSchema" to tool.inputSchema
            )
        }

        return mapOf(
            "tools" to tools
        )
    }

    /**
     * Execute a tool with the given arguments.
     *
     * @param toolName The name of the tool to execute.
     * @param arguments The arguments for the tool.
     * @return The result of the tool execution.
     */
    suspend fun callTool(toolName: String, arguments: JsonNode): Map<String, Any> {
        logger.debug("Calling tool '{}' with arguments: {}", toolName, arguments)

        return try {
            val result = when (toolName) {
                "load_page" -> executeLoadPage(arguments)
                "scrape_data" -> executeScrapeData(arguments)
                "extract_text" -> executeExtractText(arguments)
                "get_page_info" -> executeGetPageInfo(arguments)
                else -> throw IllegalArgumentException("Unknown tool: $toolName")
            }

            mapOf(
                "content" to listOf(
                    mapOf(
                        "type" to "text",
                        "text" to result
                    )
                )
            )
        } catch (e: Exception) {
            logger.error("Error executing tool '{}': {}", toolName, e.message, e)
            mapOf(
                "isError" to true,
                "content" to listOf(
                    mapOf(
                        "type" to "text",
                        "text" to "Error: ${e.message}"
                    )
                )
            )
        }
    }

    /**
     * Execute the load_page tool.
     */
    private fun executeLoadPage(arguments: JsonNode): String {
        val url = arguments.get("url")?.asText()
            ?: throw IllegalArgumentException("url is required")
        val options = arguments.get("options")?.asText() ?: ""

        val page = session.load(url, options)
        
        return buildString {
            appendLine("Page loaded successfully:")
            appendLine("URL: ${page.url}")
            appendLine("Title: ${page.pageTitle}")
            appendLine("Status: ${page.protocolStatus}")
            appendLine("Content Length: ${page.contentLength} bytes")
            appendLine()
            appendLine("Text Content Preview (first 500 chars):")
            val text = page.contentText ?: ""
            appendLine(if (text.length > 500) text.substring(0, 500) + "..." else text)
        }
    }

    /**
     * Execute the scrape_data tool.
     */
    private fun executeScrapeData(arguments: JsonNode): String {
        val sql = arguments.get("sql")?.asText()
            ?: throw IllegalArgumentException("sql is required")

        val request = ScrapeRequest(sql)
        val response = scrapeService.executeQuery(request)

        return buildString {
            appendLine("Scrape completed:")
            appendLine("Status: ${response.statusCode}")
            appendLine("Page Status: ${response.pageStatusCode}")
            appendLine()
            appendLine("Result:")
            appendLine(response.resultSet?.toString() ?: "No results")
        }
    }

    /**
     * Execute the extract_text tool.
     */
    private fun executeExtractText(arguments: JsonNode): String {
        val url = arguments.get("url")?.asText()
            ?: throw IllegalArgumentException("url is required")
        val selector = arguments.get("selector")?.asText()

        val page = session.load(url)
        val document = session.parse(page)

        val text = if (selector != null) {
            document.selectFirstTextOrNull(selector) ?: ""
        } else {
            document.text
        }

        return buildString {
            appendLine("Text extracted from: $url")
            if (selector != null) {
                appendLine("Selector: $selector")
            }
            appendLine("Length: ${text.length} characters")
            appendLine()
            appendLine("Content:")
            appendLine(text)
        }
    }

    /**
     * Execute the get_page_info tool.
     */
    private fun executeGetPageInfo(arguments: JsonNode): String {
        val url = arguments.get("url")?.asText()
            ?: throw IllegalArgumentException("url is required")

        val page = session.load(url)

        return buildString {
            appendLine("Page Information:")
            appendLine("URL: ${page.url}")
            appendLine("Title: ${page.pageTitle}")
            appendLine("Content Type: ${page.contentType}")
            appendLine("Status: ${page.protocolStatus}")
            appendLine("Encoding: ${page.encoding}")
            appendLine("Content Length: ${page.contentLength} bytes")
            appendLine("Fetch Time: ${page.fetchTime}")
            appendLine("Modified Time: ${page.modifiedTime}")
        }
    }

    /**
     * Internal tool definition class.
     */
    private data class ToolDefinition(
        val name: String,
        val description: String,
        val inputSchema: Map<String, Any>
    )
}
