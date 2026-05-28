package ai.platon.pulsar.app

import ai.platon.pulsar.boot.autoconfigure.PulsarContextInitializer
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.external.ChatModelFactory
import ai.platon.pulsar.skeleton.session.PulsarSession
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.ImportResource

@SpringBootApplication
@ComponentScan(
    "ai.platon.pulsar.boot.autoconfigure",
    "ai.platon.pulsar.rest",
)
class Browser4Application {
    private val logger = getLogger(Browser4Application::class)

    @Value("\${server.port:8182}")
    var port: Int = 8182

    @Value("\${server.servlet.context-path:}")
    lateinit var contextPath: String

    @Value("\${server.hostname:localhost}")
    lateinit var hostname: String

    @Autowired
    lateinit var session: PulsarSession

    @PostConstruct
    fun showHelp() {
        try {
            val llmHelp = getLLMStatusMessage()
            val urls = buildServerUrls()
            val help = buildHelpMessage(llmHelp, urls)

            val pid = try {
                ProcessHandle.current().pid()
            } catch (_: Throwable) {
                -1L
            }

            logger.info("Welcome to Browser4! (pid={}) \n{}", pid, help)
            logger.info("To stop Browser4: press Ctrl+C in the console, or send a SIGTERM/stop the process (pid={}).", pid)
        } catch (e: Exception) {
            logger.error("Failed to display help message", e)
        }
    }

    private fun getLLMStatusMessage(): String {
        return try {
            val hasLLM = ChatModelFactory.isModelConfigured(session.configuration, verbose = false)
            if (hasLLM) {
                "LLM is configured, you can use LLM commands."
            } else {
                "LLM is not configured, you can only use non-LLM commands. X-SQL is still available. " +
                        "It is highly recommended to set OPENROUTER_API_KEY or other LLM keys to enable LLM features."
            }
        } catch (e: Exception) {
            logger.warn("Failed to check LLM configuration", e)
            "LLM configuration check failed. Please verify your setup."
        }
    }

    private fun buildServerUrls(): ServerUrls {
        val baseURL = URLUtils.buildServerUrl(hostname, port, contextPath)
            .replace("/api", "") // Ensure the URL is correct even if the API is upgraded
            .trimEnd('/')

        return ServerUrls(
            frontend = "$baseURL/command.html",
            commandEndpoint = "$baseURL/api/commands/plain",
            scrapingEndpoint = "$baseURL/api/x/e"
        )
    }

    private fun buildHelpMessage(llmHelp: String, urls: ServerUrls): String {
        val builder = StringBuilder()
        builder.appendLine("====================================================================================")
        builder.appendLine(llmHelp)
//        builder.appendLine("------------------------------------------------------------------------------")
//        builder.appendLine("Example 1: 使用 Web UI (Using the Web UI):")
//        builder.appendLine(urls.frontend)
//        builder.appendLine("------------------------------------------------------------------------------")
//        builder.appendLine("Example 2: Open task - Ask Browser4 for Anything:")
//        builder.appendLine(buildOpenTaskExample(urls.commandEndpoint))
//        builder.appendLine("------------------------------------------------------------------------------")
//        builder.appendLine("Example 3: Automation/Extraction Task - Zero Code:")
//        builder.appendLine(buildScrapingExample(urls.commandEndpoint))
//        builder.appendLine("------------------------------------------------------------------------------")
//        builder.appendLine("Example 4: For Advanced Extract Task — LLM + X-SQL: Precise, Flexible, Powerful:")
//        builder.appendLine(buildAdvancedScrapingExample(urls.commandEndpoint))
        return builder.toString()
    }

    private fun buildOpenTaskExample(commandEndpoint: String): String {
        return """
            ```shell
            curl -X POST "$commandEndpoint" -H "Content-Type: text/plain" -d "
                Go to https://www.amazon.com/dp/B08PP5MSVB

                After browser launch: clear browser cookies.
                After page load: scroll to the middle.

                Summarize the product.
                Extract: product name, price, ratings.
                Find all links containing /dp/.
            "
            ```
        """.trimIndent()
    }

    private fun buildScrapingExample(commandEndpoint: String): String {
        return """
            ```shell
            curl -X POST "$commandEndpoint" -H "Content-Type: text/plain" -d "
                Go to https://www.amazon.com/dp/B08PP5MSVB

                After browser launch: clear browser cookies.
                After page load: scroll to the middle.

                Summarize the product.
                Extract: product name, price, ratings.
                Find all links containing /dp/.
            "
            ```
        """.trimIndent()
    }

    private fun buildAdvancedScrapingExample(commandEndpoint: String): String {
        return """
            ```shell
            curl -X POST "$commandEndpoint" -H "Content-Type: text/plain" -d "
                Go to https://www.amazon.com/dp/B08PP5MSVB

                After browser launch: clear browser cookies.
                After page load: scroll to the middle.

                Summarize the product.
                Extract: product name, price, ratings.
                Find all links containing /dp/.

                X-SQL:
                ```sql
                select
                  dom_base_uri(dom) as url,
                  dom_first_text(dom, '#productTitle') as title,
                  dom_first_slim_html(dom, 'img:expr(width > 400)') as img
                from load_and_select('https://www.amazon.com/dp/B08PP5MSVB', 'body');
                ```
            "
            ```
        """.trimIndent()
    }

    private data class ServerUrls(
        val frontend: String,
        val commandEndpoint: String,
        val scrapingEndpoint: String
    )
}

fun runBrowser4AgentsApplication(args: Array<String>) {
    runApplication<Browser4Application>(*args) {
        addInitializers(PulsarContextInitializer())
        setAdditionalProfiles("agents", "private", "advanced")
        setLogStartupInfo(true)
    }
}

fun main(args: Array<String>) = runBrowser4AgentsApplication(args)
