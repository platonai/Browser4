package ai.platon.pulsar.app

import ai.platon.pulsar.boot.autoconfigure.PulsarContextInitializer
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.skeleton.session.PulsarSession
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.ImportResource

@SpringBootApplication
@ImportResource("classpath:pulsar-beans/app-context.xml")
@ComponentScan(
    "ai.platon.pulsar.boot.autoconfigure"
)
class Browser4NativeImageApplication(
    val session: PulsarSession
) {
    private val logger = getLogger(Browser4NativeImageApplication::class)

    @Value("\${server.port:8182}")
    var port: Int = 8182

    @Value("\${server.servlet.context-path:}")
    lateinit var contextPath: String

    @Value("\${server.hostname:localhost}")
    lateinit var hostname: String

    @PostConstruct
    fun showHelp() {
        try {
            val pid = try {
                ProcessHandle.current().pid()
            } catch (_: Throwable) {
                -1L
            }

            logger.info("Welcome to Browser4! (pid={})", pid)
            logger.info(
                "To stop Browser4: press Ctrl+C in the console, or send a SIGTERM/stop the process (pid={}).",
                pid
            )
        } catch (e: Exception) {
            logger.error("Failed to display help message", e)
        }
    }
}

fun main(args: Array<String>) {
    runApplication<Browser4NativeImageApplication>(*args) {
        addInitializers(PulsarContextInitializer())
        setLogStartupInfo(true)
    }
}
