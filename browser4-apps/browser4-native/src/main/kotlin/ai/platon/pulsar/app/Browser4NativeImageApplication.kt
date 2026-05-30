package ai.platon.pulsar.app

import ai.platon.browser4.boot.autoconfigure.Browser4AutoConfiguration
import ai.platon.browser4.boot.autoconfigure.PulsarContextInitializer
import ai.platon.pulsar.browser.BrowserManager
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.dom.Documents
import ai.platon.pulsar.loop.TaskLoops
import ai.platon.pulsar.persist.WebDb
import ai.platon.pulsar.ql.h2.H2MemoryDb
import ai.platon.pulsar.ql.h2.H2SessionFactory
import ai.platon.pulsar.skeleton.session.PulsarSession
import ai.platon.pulsar.skeleton.workflow.common.GlobalCache
import ai.platon.pulsar.skeleton.workflow.common.GlobalCacheFactory
import ai.platon.pulsar.skeleton.workflow.component.BatchFetchComponent
import ai.platon.pulsar.skeleton.workflow.component.LoadComponent
import ai.platon.pulsar.skeleton.workflow.component.ParseComponent
import ai.platon.pulsar.skeleton.workflow.component.UpdateComponent
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.ImportResource
import kotlin.io.path.exists

@SpringBootApplication
@Import(Browser4AutoConfiguration::class)
class Browser4NativeImageApplication(
    val session: PulsarSession,
    val webDb: WebDb,
    val globalCacheFactory: GlobalCacheFactory,
    val fetchComponent: BatchFetchComponent,
    val parseComponent: ParseComponent,
    val updateComponent: UpdateComponent,
    val loadComponent: LoadComponent,
    val globalCache: GlobalCache,
    val browserManager: BrowserManager,
    val taskLoops: TaskLoops
) {
    private val logger = getLogger(Browser4NativeImageApplication::class)

    @Value("\${server.port:8882}")
    var port: Int = 8882

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

    @PostConstruct
    fun fixNativeImage() {
        // createRequiredResources(AppPaths::class)
        check(AppPaths.TMP_DIR.exists()) { "Temporary directory does not exist: ${AppPaths.TMP_DIR}" }
        val document = Documents.parse("<html></html>")
        require(document.html.isNotBlank()) { "The document must not be blank" }
        val result = runCatching { H2SessionFactory.getInstance().getSession(-1) }
        require(result.isFailure) { "The H2 session could not be opened" }
        H2MemoryDb().getRandomConnectionOrNull()?.use { it.prepareCall("call 1+1").executeQuery() }
    }
}

fun main(args: Array<String>) {
    runApplication<Browser4NativeImageApplication>(*args) {
        addInitializers(PulsarContextInitializer())
        setLogStartupInfo(true)
        setWebApplicationType(WebApplicationType.SERVLET)
    }
}
