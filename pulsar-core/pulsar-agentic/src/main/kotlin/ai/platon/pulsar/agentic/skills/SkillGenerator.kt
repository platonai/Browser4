package ai.platon.pulsar.agentic.skills

import ai.platon.pulsar.agentic.mcp.MCPInputSchema
import ai.platon.pulsar.agentic.mcp.MCPPropertySchema
import ai.platon.pulsar.agentic.mcp.MCPToolConverter
import ai.platon.pulsar.agentic.mcp.MCPToolDefinition
import ai.platon.pulsar.agentic.tools.ToolSpecification

/**
 * Generator for creating Claude Skills from Browser4 tools.
 *
 * This generator can:
 * - Convert MCP tools to skill format
 * - Generate predefined skills for common use cases
 * - Create custom skills from tool selections
 *
 * @author Vincent Zhang, ivincent.zhang@gmail.com, platon.ai
 */
object SkillGenerator {

    // Tools that may cause navigation
    private val navigationTools = setOf(
        "driver.navigateTo",
        "driver.click",
        "driver.reload",
        "driver.goBack",
        "driver.goForward",
    )

    // Read-only tools (no side effects)
    private val readOnlyTools = setOf(
        "driver.exists",
        "driver.isVisible",
        "driver.textContent",
        "driver.selectFirstTextOrNull",
        "fs.readString",
        "system.help",
    )

    /**
     * Convert an MCP tool definition to a skill tool.
     */
    fun toSkillTool(mcp: MCPToolDefinition): SkillTool {
        return SkillTool(
            name = mcp.name,
            description = mcp.description,
            inputSchema = convertInputSchema(mcp.inputSchema),
            mayNavigate = mcp.name in navigationTools,
            readOnly = mcp.name in readOnlyTools,
        )
    }

    /**
     * Convert multiple MCP tools to skill tools.
     */
    fun toSkillTools(mcpTools: List<MCPToolDefinition>): List<SkillTool> {
        return mcpTools.map { toSkillTool(it) }
    }

    /**
     * Generate a skill from a list of MCP tools.
     */
    fun generateSkill(
        name: String,
        displayName: String,
        description: String,
        instructions: String,
        mcpTools: List<MCPToolDefinition>,
        examples: List<SkillExample>? = null,
        metadata: SkillMetadata? = null,
        category: SkillCategory = SkillCategory.CUSTOM,
    ): ClaudeSkill {
        return ClaudeSkill(
            name = name,
            displayName = displayName,
            description = description,
            instructions = instructions,
            tools = toSkillTools(mcpTools),
            examples = examples,
            metadata = metadata ?: SkillMetadata(version = "1.0.0"),
            category = category,
        )
    }

    /**
     * Generate the Web Browsing skill.
     */
    fun generateWebBrowsingSkill(): ClaudeSkill {
        val toolNames = listOf(
            "driver.navigateTo",
            "driver.reload",
            "driver.goBack",
            "driver.goForward",
            "driver.waitForSelector",
            "driver.exists",
            "driver.isVisible",
            "driver.focus",
            "driver.hover",
            "driver.click",
            "driver.scrollTo",
            "driver.scrollToTop",
            "driver.scrollToBottom",
            "driver.scrollToMiddle",
            "driver.scrollBy",
            "driver.textContent",
            "driver.selectFirstTextOrNull",
            "driver.delay",
        )

        val allTools = MCPToolConverter.getAllBuiltInMCPTools()
        val selectedTools = allTools.filter { it.name in toolNames }

        val examples = listOf(
            SkillExample(
                input = "Go to google.com and search for 'AI news'",
                output = "Successfully searched for 'AI news' on Google",
                steps = listOf(
                    SkillExampleStep(
                        tool = "driver.navigateTo",
                        arguments = mapOf("url" to "https://google.com"),
                        description = "Navigate to Google",
                    ),
                    SkillExampleStep(
                        tool = "driver.fill",
                        arguments = mapOf("selector" to "textarea[name='q']", "text" to "AI news"),
                        description = "Enter search query",
                    ),
                    SkillExampleStep(
                        tool = "driver.click",
                        arguments = mapOf("selector" to "input[name='btnK']"),
                        description = "Click search button",
                    ),
                ),
                tags = listOf("search", "navigation"),
            ),
            SkillExample(
                input = "Scroll down the page and check if there's a 'Load More' button",
                steps = listOf(
                    SkillExampleStep(
                        tool = "driver.scrollBy",
                        arguments = mapOf("pixels" to 500.0),
                        description = "Scroll down 500 pixels",
                    ),
                    SkillExampleStep(
                        tool = "driver.exists",
                        arguments = mapOf("selector" to "button:contains('Load More')"),
                        description = "Check for Load More button",
                    ),
                ),
                tags = listOf("scroll", "element-check"),
            ),
        )

        return ClaudeSkill(
            name = "web_browsing",
            displayName = "Web Browsing",
            description = "Browse and interact with web pages including navigation, scrolling, and element interaction",
            instructions = """Use this skill to navigate websites, interact with page elements, and retrieve page content.

Key capabilities:
- Navigate to URLs and manage browser history (back/forward/reload)
- Wait for elements to appear before interacting
- Check element visibility and existence
- Scroll pages in various ways (to element, by pixels, to top/bottom)
- Get page text content

Best practices:
1. Always wait for elements before clicking or filling
2. Use specific selectors (IDs > classes > tag names)
3. Check element existence before interaction
4. Handle navigation events by waiting for page load

Common selectors:
- By ID: #elementId
- By class: .className
- By tag: button, input, a
- By attribute: [name='value']
- Combined: button.primary#submit""",
            tools = toSkillTools(selectedTools),
            examples = examples,
            metadata = SkillMetadata(
                version = "1.0.0",
                author = "Browser4 Team",
                tags = listOf("browsing", "navigation", "interaction"),
            ),
            category = SkillCategory.BROWSER_AUTOMATION,
        )
    }

    /**
     * Generate the Form Automation skill.
     */
    fun generateFormAutomationSkill(): ClaudeSkill {
        val toolNames = listOf(
            "driver.waitForSelector",
            "driver.exists",
            "driver.isVisible",
            "driver.focus",
            "driver.click",
            "driver.fill",
            "driver.type",
            "driver.press",
            "driver.check",
            "driver.uncheck",
        )

        val allTools = MCPToolConverter.getAllBuiltInMCPTools()
        val selectedTools = allTools.filter { it.name in toolNames }

        val examples = listOf(
            SkillExample(
                input = "Fill out a login form with username 'user@example.com' and password 'secret123'",
                output = "Successfully filled and submitted the login form",
                steps = listOf(
                    SkillExampleStep(
                        tool = "driver.waitForSelector",
                        arguments = mapOf("selector" to "input[type='email']"),
                        description = "Wait for email field",
                    ),
                    SkillExampleStep(
                        tool = "driver.fill",
                        arguments = mapOf("selector" to "input[type='email']", "text" to "user@example.com"),
                        description = "Enter email",
                    ),
                    SkillExampleStep(
                        tool = "driver.fill",
                        arguments = mapOf("selector" to "input[type='password']", "text" to "secret123"),
                        description = "Enter password",
                    ),
                    SkillExampleStep(
                        tool = "driver.click",
                        arguments = mapOf("selector" to "button[type='submit']"),
                        description = "Click submit button",
                    ),
                ),
                tags = listOf("login", "form"),
            ),
            SkillExample(
                input = "Check the 'Remember me' checkbox and accept terms",
                steps = listOf(
                    SkillExampleStep(
                        tool = "driver.check",
                        arguments = mapOf("selector" to "#remember-me"),
                        description = "Check remember me",
                    ),
                    SkillExampleStep(
                        tool = "driver.check",
                        arguments = mapOf("selector" to "#accept-terms"),
                        description = "Accept terms",
                    ),
                ),
                tags = listOf("checkbox", "form"),
            ),
        )

        return ClaudeSkill(
            name = "form_automation",
            displayName = "Form Automation",
            description = "Fill out and submit web forms including text inputs, checkboxes, and buttons",
            instructions = """Use this skill to automate form filling and submission.

Key capabilities:
- Fill text inputs with values
- Type text character by character (for special inputs)
- Check and uncheck checkboxes
- Press keyboard keys (Enter, Tab, etc.)
- Click buttons and submit forms

Best practices:
1. Wait for form elements before filling
2. Use fill() for direct value setting, type() for character-by-character input
3. Check element visibility before interaction
4. Handle form validation by checking for error messages
5. Use press() with "Enter" key for form submission when appropriate

Common form selectors:
- Email: input[type='email'], input[name='email']
- Password: input[type='password']
- Submit: button[type='submit'], input[type='submit']
- Checkbox: input[type='checkbox']""",
            tools = toSkillTools(selectedTools),
            examples = examples,
            metadata = SkillMetadata(
                version = "1.0.0",
                author = "Browser4 Team",
                tags = listOf("form", "input", "automation"),
            ),
            category = SkillCategory.FORM_AUTOMATION,
        )
    }

    /**
     * Generate the Data Extraction skill.
     */
    fun generateDataExtractionSkill(): ClaudeSkill {
        val toolNames = listOf(
            "driver.textContent",
            "driver.selectFirstTextOrNull",
            "agent.extract",
            "agent.summarize",
        )

        val allTools = MCPToolConverter.getAllBuiltInMCPTools()
        val selectedTools = allTools.filter { it.name in toolNames }

        val examples = listOf(
            SkillExample(
                input = "Extract all the product names and prices from this e-commerce page",
                output = "Extracted product data in JSON format",
                steps = listOf(
                    SkillExampleStep(
                        tool = "agent.extract",
                        arguments = mapOf(
                            "instruction" to "Extract all product names and prices",
                            "schema" to """{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"price":{"type":"string"}}}}"""
                        ),
                        description = "Extract structured product data",
                    ),
                ),
                tags = listOf("extraction", "ecommerce"),
            ),
            SkillExample(
                input = "Summarize the main article on this news page",
                steps = listOf(
                    SkillExampleStep(
                        tool = "agent.summarize",
                        arguments = mapOf("selector" to "article.main-content"),
                        description = "Summarize the article content",
                    ),
                ),
                tags = listOf("summarize", "news"),
            ),
        )

        return ClaudeSkill(
            name = "data_extraction",
            displayName = "Data Extraction",
            description = "Extract and summarize data from web pages using AI-powered analysis",
            instructions = """Use this skill to extract structured data and summaries from web pages.

Key capabilities:
- Get raw text content from pages or elements
- Extract structured data with custom JSON schemas
- Summarize page content with AI

Best practices:
1. Use textContent() for simple text extraction
2. Use selectFirstTextOrNull() for specific element text
3. Use agent.extract() with a JSON schema for structured data
4. Use agent.summarize() for content summaries
5. Define precise schemas for better extraction quality

JSON Schema examples:
- Array of objects: {"type":"array","items":{"type":"object","properties":{...}}}
- Simple object: {"type":"object","properties":{"field1":{"type":"string"}}}""",
            tools = toSkillTools(selectedTools),
            examples = examples,
            metadata = SkillMetadata(
                version = "1.0.0",
                author = "Browser4 Team",
                tags = listOf("extraction", "data", "ai"),
            ),
            category = SkillCategory.DATA_EXTRACTION,
        )
    }

    /**
     * Generate the File Operations skill.
     */
    fun generateFileOperationsSkill(): ClaudeSkill {
        val toolNames = listOf(
            "fs.writeString",
            "fs.readString",
            "fs.replaceContent",
        )

        val allTools = MCPToolConverter.getAllBuiltInMCPTools()
        val selectedTools = allTools.filter { it.name in toolNames }

        val examples = listOf(
            SkillExample(
                input = "Save the extracted data to a file called 'data.json'",
                steps = listOf(
                    SkillExampleStep(
                        tool = "fs.writeString",
                        arguments = mapOf(
                            "filename" to "data.json",
                            "content" to """{"items":[]}"""
                        ),
                        description = "Write data to file",
                    ),
                ),
                tags = listOf("file", "write"),
            ),
            SkillExample(
                input = "Read the configuration from config.yaml",
                steps = listOf(
                    SkillExampleStep(
                        tool = "fs.readString",
                        arguments = mapOf("filename" to "config.yaml"),
                        description = "Read configuration file",
                    ),
                ),
                tags = listOf("file", "read"),
            ),
        )

        return ClaudeSkill(
            name = "file_operations",
            displayName = "File Operations",
            description = "Read, write, and modify files on the local filesystem",
            instructions = """Use this skill to work with files on the local filesystem.

Key capabilities:
- Write content to files
- Read content from files
- Replace specific content in files

Best practices:
1. Use appropriate file extensions (.json, .txt, .yaml, etc.)
2. Handle file not found errors gracefully
3. Use replaceContent() for targeted modifications
4. Ensure proper file permissions exist

Supported operations:
- writeString: Create or overwrite a file with content
- readString: Read entire file content as string
- replaceContent: Find and replace text in a file""",
            tools = toSkillTools(selectedTools),
            examples = examples,
            metadata = SkillMetadata(
                version = "1.0.0",
                author = "Browser4 Team",
                tags = listOf("file", "io", "filesystem"),
            ),
            category = SkillCategory.FILE_OPERATIONS,
        )
    }

    /**
     * Generate the System skill with help tools.
     */
    fun generateSystemSkill(): ClaudeSkill {
        val toolNames = listOf(
            "system.help",
        )

        val allTools = MCPToolConverter.getAllBuiltInMCPTools()
        val selectedTools = allTools.filter { it.name in toolNames }

        val examples = listOf(
            SkillExample(
                input = "Show me all available driver tools",
                steps = listOf(
                    SkillExampleStep(
                        tool = "system.help",
                        arguments = mapOf("domain" to "driver"),
                        description = "Get help for driver domain",
                    ),
                ),
                tags = listOf("help", "documentation"),
            ),
        )

        return ClaudeSkill(
            name = "system",
            displayName = "System",
            description = "System utilities including help and documentation",
            instructions = """Use this skill to get help and documentation about available tools.

Key capabilities:
- Get help for all tools in a domain
- Get help for specific tools

Usage:
- system.help("driver") - List all driver tools
- system.help("driver", "click") - Get detailed help for driver.click""",
            tools = toSkillTools(selectedTools),
            examples = examples,
            metadata = SkillMetadata(
                version = "1.0.0",
                author = "Browser4 Team",
                tags = listOf("system", "help"),
            ),
            category = SkillCategory.SYSTEM,
        )
    }

    /**
     * Get all predefined skills.
     */
    fun getAllPredefinedSkills(): List<ClaudeSkill> {
        return listOf(
            generateWebBrowsingSkill(),
            generateFormAutomationSkill(),
            generateDataExtractionSkill(),
            generateFileOperationsSkill(),
            generateSystemSkill(),
        )
    }

    /**
     * Generate a skill from tool names.
     */
    fun generateSkillFromToolNames(
        name: String,
        displayName: String,
        description: String,
        instructions: String,
        toolNames: List<String>,
        examples: List<SkillExample>? = null,
        category: SkillCategory = SkillCategory.CUSTOM,
    ): ClaudeSkill {
        val allTools = MCPToolConverter.getAllBuiltInMCPTools()
        val selectedTools = allTools.filter { it.name in toolNames }

        return generateSkill(
            name = name,
            displayName = displayName,
            description = description,
            instructions = instructions,
            mcpTools = selectedTools,
            examples = examples,
            category = category,
        )
    }

    // ========================================================================
    // Private Helpers
    // ========================================================================

    private fun convertInputSchema(mcp: MCPInputSchema): SkillToolInputSchema {
        return SkillToolInputSchema(
            type = mcp.type,
            properties = mcp.properties.mapValues { convertPropertySchema(it.value) },
            required = mcp.required,
            additionalProperties = mcp.additionalProperties,
        )
    }

    private fun convertPropertySchema(mcp: MCPPropertySchema): SkillPropertySchema {
        return SkillPropertySchema(
            type = mcp.type,
            description = mcp.description,
            default = mcp.default,
            enumValues = mcp.enumValues,
            items = mcp.items?.let { convertPropertySchema(it) },
        )
    }
}
