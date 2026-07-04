package ai.platon.pulsar.apps

import ai.platon.browser4.boot.plugin.PluginClasspathEnhancer
import ai.platon.pulsar.apps.native.Browser4NativeHints
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.external.ChatModelFactory
import ai.platon.pulsar.rest.ApiApplication
import ai.platon.pulsar.skeleton.session.PulsarSession
import kotlin.concurrent.thread
import org.springframework.context.annotation.ImportRuntimeHints
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import java.nio.file.Path

@SpringBootApplication
@Import(ApiApplication::class)
@ImportRuntimeHints(Browser4NativeHints::class)
class Browser4StandaloneApplication(
    val session: PulsarSession
) {
    private val logger = getLogger(Browser4StandaloneApplication::class)

    @Value("\${server.port:8182}")
    var port: Int = 8182

    @Value("\${server.servlet.context-path:}")
    lateinit var contextPath: String

    @Value("\${server.hostname:localhost}")
    lateinit var hostname: String

    @EventListener(ApplicationReadyEvent::class)
    fun showHelp() {
        thread(isDaemon = true) {
            try {
                val llmHelp = getLLMStatusMessage()
                val help = buildHelpMessage(llmHelp)

                val pid = try {
                    ProcessHandle.current().pid()
                } catch (_: Throwable) {
                    -1L
                }

                logger.info("Welcome to Browser4! (pid={}) \n{}", pid, help)
                logger.info(
                    "To stop Browser4: press Ctrl+C in the console, or send a SIGTERM/stop the process (pid={}).",
                    pid
                )
            } catch (e: Exception) {
                logger.error("Failed to display help message", e)
            }
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

    private fun buildHelpMessage(llmHelp: String): String {
        val builder = StringBuilder()
        builder.appendLine("====================================================================================")
        builder.appendLine(llmHelp)
        return builder.toString()
    }
}

fun runBrowser4StandaloneApplication(args: Array<String>) {
    // PluginClasspathEnhancer creates a URLClassLoader at runtime to load
    // plugin JARs from ./plugins/.  This is incompatible with GraalVM native
    // image's closed-world model, where all classes must be known at build
    // time.  In native mode, plugins must be included on the classpath at
    // build time instead.
    if (System.getProperty("org.graalvm.nativeimage.imagecode") == null) {
        PluginClasspathEnhancer.enhance(Path.of("plugins"))
    }
    runApplication<Browser4StandaloneApplication>(*args) {
        setAdditionalProfiles("standalone", "private", "advanced")
        setLogStartupInfo(true)
    }
}

fun main(args: Array<String>) = runBrowser4StandaloneApplication(args)
