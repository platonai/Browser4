package ai.platon.pulsar.app.api.controller

import ai.platon.pulsar.agentic.ActionOptions
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.external.ChatModelFactory
import ai.platon.pulsar.rest.api.entities.NavigateRequest
import ai.platon.pulsar.test.TestResourceUtil.Companion.PRODUCT_DETAIL_URL
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.springframework.web.context.request.async.AsyncRequestTimeoutException
import kotlin.test.Ignore

@Ignore("Disabled temporarily, Run the tests manually")
@Tag("MustManuallyRun")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SinglePageApplicationControllerTest : IntegrationTestBase() {

    fun health() {
        val response = client.get().uri("/api")
            .exchange()
            // If not initialized yet, health may be 500 (unhealthy). Don't hard fail at this stage.
            .expectStatus().value { /* accept any */ }
            .expectBody(Map::class.java)
            .returnResult()

        @Suppress("UNCHECKED_CAST")
        val body = response.responseBody as? Map<String, Any?>
        assertThat(body).isNotNull
        assertThat(body!!).containsKey("status")
    }

    fun init() {
        val response = client.post().uri("/api/init")
            .body(mapOf("api_key" to ""))
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody(Map::class.java)
            .returnResult()

        @Suppress("UNCHECKED_CAST")
        val body = response.responseBody as? Map<String, Any?>
        assertThat(body).isNotNull
        assertThat(body!!["status"]).isEqualTo("healthy")
    }

    @BeforeEach
    fun `init Single Page Application`() {
        Assumptions.assumeTrue { ChatModelFactory.isModelConfigured(session.configuration) }

        health()
        init()
    }

    @Order(10)
    @Test
    fun `navigate to product page`() {
        val request = NavigateRequest(PRODUCT_DETAIL_URL)
        val result = client.post().uri("/api/navigate")
            .body(request)
            .exchange()
            .expectStatus().isOk
            .expectBody(SinglePageApplicationController.BrowserActionResult::class.java)
            .returnResult()
            .responseBody

        assertThat(result).isNotNull
        assertThat(result!!.success).isTrue()
        assertThat(result.url).isNotBlank()
        assertThat(result.screenshot_base64).isNotNull
    }

    @Order(20)
    @Test
    fun `act with searching`() {
        val request = ActionOptions(action = "search latest iphone, give me a summary of the search result")

        var lastStatus: Int? = null
        var lastBody: SinglePageApplicationController.BrowserActionResult? = null
        var lastError: Throwable? = null

        val maxAttempts = 3
        val delayMillis = 1500L
        repeat(maxAttempts) { attempt ->
            try {
                val exchangeResult = client.post().uri("/api/act")
                    .body(request)
                    .exchange()
                    // this endpoint can take long; we retry when it fails
                    .expectStatus().value { status -> lastStatus = status }
                    .expectBody(SinglePageApplicationController.BrowserActionResult::class.java)
                    .returnResult()

                lastBody = exchangeResult.responseBody

                if (lastStatus != null && lastStatus in 200..299 && lastBody != null) {
                    val result = lastBody
                    printlnPro("Attempt ${attempt + 1}: action='${result.action}' message='${result.message}'")
                    assertThat(result.message).isNotBlank
                    return
                } else {
                    printlnPro("Attempt ${attempt + 1}: Non-success status=$lastStatus")
                }
            } catch (ex: AsyncRequestTimeoutException) {
                lastError = ex
                printlnPro("Attempt ${attempt + 1}: AsyncRequestTimeoutException: ${ex.message}")
            } catch (ex: Exception) {
                lastError = ex
                printlnPro("Attempt ${attempt + 1}: Exception: ${ex::class.simpleName} - ${ex.message}")
            }
            if (attempt < maxAttempts - 1) {
                Thread.sleep(delayMillis)
            }
        }

        Assertions.fail<Any>(
            "Failed to perform SPA act after $maxAttempts attempts. lastStatus=$lastStatus lastBody=$lastBody lastError=${lastError?.message}"
        )
    }

    @Order(30)
    @Test
    fun `take screenshot`() {
        val result = client.post().uri("/api/screenshot")
            .body(emptyMap<String, Any?>())
            .exchange()
            .expectStatus().isOk
            .expectBody(SinglePageApplicationController.BrowserActionResult::class.java)
            .returnResult()
            .responseBody

        assertThat(result).isNotNull
        assertThat(result!!.screenshot_base64).isNotNull
        assertThat(result.screenshot_base64!!).isNotBlank()

        printlnPro("Screenshot captured successfully (base64 length): ${result.screenshot_base64.length}")

        // Optional: decode and persist for manual inspection
        val exportPath = AppPaths.getRandomProcTmpTmpPath("screenshot-", ".jpg")
        val bytes = java.util.Base64.getDecoder().decode(result.screenshot_base64)
        java.nio.file.Files.write(exportPath, bytes, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)
        printlnPro("Screenshot saved to: $exportPath")

        assertThat(java.nio.file.Files.exists(exportPath)).isTrue()
        assertThat(java.nio.file.Files.size(exportPath)).isGreaterThan(0)
    }

    @Order(40)
    @Test
    fun `extract with prompt`() {
        val requestBody = mapOf(
            "instruction" to "name, price",
            "iframes" to true,
        )

        val result = client.post().uri("/api/extract")
            .body(requestBody)
            .exchange()
            .expectStatus().isOk
            .expectBody(SinglePageApplicationController.BrowserActionResult::class.java)
            .returnResult()
            .responseBody

        assertThat(result).isNotNull
        assertThat(result!!.message).isNotBlank
    }
}
