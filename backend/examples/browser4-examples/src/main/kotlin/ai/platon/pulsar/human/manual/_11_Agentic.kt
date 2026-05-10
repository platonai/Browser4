package ai.platon.pulsar.human.manual

import ai.platon.pulsar.agentic.context.AgenticContexts

/**
 * # Agentic Browser Automation - AI-Powered Natural Language Control
 *
 * This example demonstrates Browser4's agentic capabilities, allowing you to
 * control the browser using natural language instructions powered by LLM.
 *
 * ## Key Concepts:
 * 1. **Agentic Session** - AI-enhanced browser session
 * 2. **BrowserAgent** - AI agent that interprets natural language commands
 * 3. **Task Execution** - Multi-step tasks described in plain language
 * 4. **LLM Integration** - Connection to language model providers
 *
 * ## Agentic Architecture:
 * ```
 * Natural Language Task → LLM Parser → Action Plan → WebDriver Execution → Results
 * ```
 *
 * ## Supported Task Types:
 * 1. **Navigation** - "go to website X"
 * 2. **Search** - "search for Y"
 * 3. **Reading** - "read the article"
 * 4. **Extraction** - "find the price"
 * 5. **Multi-step** - "search, read, and summarize"
 *
 * ## LLM Configuration Required:
 * To use agentic features, configure an LLM provider in application.properties:
 * ```properties
 * # OpenAI
 * llm.provider=openai
 * llm.apiKey=sk-xxx
 *
 * # Or Volcengine
 * llm.provider=volcengine
 * llm.apiKey=xxx
 * ```
 *
 * ## Example Tasks:
 * - "go to amazon.com and search for wireless earbuds under $50"
 * - "open hacker news, read top 3 articles and give me a summary"
 * - "go to weather.com and tell me tomorrow's forecast for Seattle"
 *
 * ## AI Integration Notes:
 * - Tasks can be in any language (English, Chinese, etc.)
 * - The agent handles navigation, waiting, and error recovery
 * - Results are returned in natural language
 * - Complex multi-step tasks are broken down automatically
 *
 * @see AgenticContexts Factory for creating agentic sessions and agents
 * @see BrowserAgent The AI agent that executes natural language tasks
 */
class AgentRunner {
    // =====================================================================
    // Agent Initialization
    // =====================================================================
    //
    // Get or create a BrowserAgent instance.
    // AI Note: The agent connects to the configured LLM provider and
    // manages browser instances for task execution.
    val agent = AgenticContexts.getOrCreateAgent()

    /**
     * Executes an agentic task using natural language.
     *
     * AI Note: This method demonstrates running a single task, but the
     * agent can handle multiple tasks in sequence. Each task is:
     * 1. Parsed by the LLM to understand intent
     * 2. Broken into actionable steps
     * 3. Executed via WebDriver
     * 4. Results summarized and returned
     */
    suspend fun run() {
        // =====================================================================
        // Task Definitions
        // =====================================================================
        //
        // A collection of example tasks demonstrating different capabilities.
        // Each task is a complete natural language instruction.
        val tasks = """
go to https://news.ycombinator.com/news , search for browser and read top 5 articles and give me a summary
go to amazon.com and search for pens to draw on whiteboards
打开百度查找厦门岛旅游景点，给出一个总结
go to https://news.ycombinator.com/news , open the 4-th articles in new tab
go to https://news.ycombinator.com/news , read top 3 articles and give me a summary
        """.lines().filter { it.isNotBlank() }

        // =====================================================================
        // Task Breakdown (AI Analysis)
        // =====================================================================
        //
        // Task 1: "go to hacker news, search for browser, read top 5, summarize"
        // - Step 1: Navigate to URL
        // - Step 2: Find and use search function
        // - Step 3: Click on each of top 5 results
        // - Step 4: Extract content from each article
        // - Step 5: Generate summary using LLM
        //
        // Task 2: "go to amazon, search for whiteboard pens"
        // - Step 1: Navigate to amazon.com
        // - Step 2: Find search box
        // - Step 3: Type search query
        // - Step 4: Submit search
        // - Step 5: Return results page
        //
        // Task 3: (Chinese) "search Baidu for Xiamen tourist spots, summarize"
        // - Demonstrates multi-language support
        // - Same pattern: navigate, search, extract, summarize
        //
        // Task 4: "open the 4th article in new tab"
        // - Demonstrates tab management
        // - Ordinal understanding (4th)
        //
        // Task 5: "read top 3 articles and summarize"
        // - Multi-page reading
        // - Content aggregation
        // - Summary generation

        // =====================================================================
        // Task Execution
        // =====================================================================
        //
        // Select the first task for execution.
        // AI Note: Change index to run different tasks, or loop through all.
        val task = tasks[0]

        // Run the task - this is an async operation
        // AI Note: agent.run() is a suspend function that:
        // 1. Sends task to LLM for planning
        // 2. Executes each step via WebDriver
        // 3. Handles errors and retries
        // 4. Returns natural language result
        agent.run(task)

        // =====================================================================
        // Advanced Usage Examples (Reference)
        // =====================================================================
        //
        // Example: Run multiple tasks in sequence
        // ```kotlin
        // for (task in tasks) {
        //     val result = agent.run(task)
        //     println("Task: $task")
        //     println("Result: $result")
        // }
        // ```
        //
        // Example: Custom task with context
        // ```kotlin
        // val context = "I'm looking for budget options"
        // agent.run("$context - search amazon for headphones under $30")
        // ```
        //
        // Example: Interactive session
        // ```kotlin
        // agent.run("go to google.com")
        // agent.run("search for kotlin tutorials")
        // agent.run("click the first result")
        // agent.run("summarize what you see")
        // ```
    }
}

/**
 * Main entry point for agentic browser automation.
 *
 * AI Note: Uses suspend fun main() because agent.run() is a suspend function.
 * Requires configured LLM provider to function properly.
 */
suspend fun main() = AgentRunner().run()
