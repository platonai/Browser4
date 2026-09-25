package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.boot.autoconfigure.Browser4AutoConfiguration
import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.GenericAgenticSession
import ai.platon.pulsar.api.model.BrowserSettings
import ai.platon.pulsar.common.browser.BrowserProfileMode
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.rest.api.service.crawl.CrawlService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.ResponseEntity
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.test.web.servlet.client.expectBody
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.assertTrue

@SpringBootTest(
    classes = [Application::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import(Browser4AutoConfiguration::class)
open class IntegrationTestBase {

    @LocalServerPort
    var serverPort: Int = 0

    @Autowired
    lateinit var session: AgenticSession

    @Autowired
    lateinit var configuration: ImmutableConfig

    val hostname = "127.0.0.1"

    val baseUri get() = String.format("http://%s:%d", hostname, serverPort)

    // Build a RestTestClient bound to the running server on demand
    protected val client get() = RestTestClient.bindToServer().baseUrl(baseUri).build()

    /**
     * The client for a call the server may legitimately keep working on for minutes.
     *
     * A synchronous page visit is executed under the server's own task budget
     * (`CrawlService.DEFAULT_TASK_TIMEOUT_MS`, 10 minutes) and the visit drives a
     * browser page load, which on a loaded CI runner is not fast: run 36172619505's
     * log has a 2.3 KiB localhost fixture page taking 3 m 8.7 s.  [client]'s socket
     * timeout is not chosen by this suite at all — it is Apache HttpClient 5's
     * `RequestConfig.DEFAULT` of 3 minutes, inherited from whichever HTTP client
     * Spring auto-detects — so `CommandXSqlTest` was killed at 180.1 s by
     * `SocketTimeoutException: Read timed out` while the server was still visiting.
     *
     * A deadline below the contract it waits on is the mistake §31.2 fixed on the
     * crawl waits, so the bound is derived the same way here: the server's budget
     * plus the same two minutes `CrawlTestBase.crawlTerminalWait` adds for the
     * round's report window and the response.  Callers that simply poll keep [client]
     * on purpose — a poll that hangs should fail fast, not hold the suite for another
     * ten minutes.
     */
    protected val pageVisitClient: RestTestClient get() =
        RestTestClient.bindToServer(pageVisitRequestFactory).baseUrl(baseUri).build()

    /**
     * The client behind [pageVisitClient] — the same auto-detected client as [client], with the
     * read timeout this suite actually means.  [client] leaves it at the client library's
     * default, which is how the 3-minute bound arrived: nobody chose it.
     */
    private val pageVisitRequestFactory: ClientHttpRequestFactory by lazy {
        HttpComponentsClientHttpRequestFactory().apply {
            setReadTimeout(PAGE_VISIT_READ_TIMEOUT)
        }
    }

    protected fun getHtml(path: String): ResponseEntity<String> =
        client.get().uri(path)
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult()
            .let { result -> ResponseEntity(result.responseBody!!, result.responseHeaders, result.status) }

    protected fun getJson(path: String): ResponseEntity<String> =
        client.get().uri(path)
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult()
            .let { result -> ResponseEntity(result.responseBody!!, result.responseHeaders, result.status) }

    @BeforeTest
    fun setup() {
        assertTrue("Session should be GenericAgenticSession, actual ${session.javaClass}") { session is  GenericAgenticSession }
        BrowserSettings.withBrowserContextMode(BrowserProfileMode.TEMPORARY)
        assertTrue("Server port should have been injected and > 0, but was $serverPort") { serverPort > 0 }
    }

    private companion object {
        /**
         * [CrawlService.DEFAULT_TASK_TIMEOUT_MS] plus the round's report window and the response
         * itself — the same derivation as `CrawlTestBase.crawlTerminalWait`, so no call here
         * waits longer than a crawl wait does.  The connect timeout stays at its default: a
         * localhost connect either answers at once or is refused at once.
         */
        private val PAGE_VISIT_READ_TIMEOUT: Duration =
            Duration.ofMillis(CrawlService.DEFAULT_TASK_TIMEOUT_MS + 120_000L)
    }
}
