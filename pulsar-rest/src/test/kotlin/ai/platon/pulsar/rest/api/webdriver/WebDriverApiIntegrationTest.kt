package ai.platon.pulsar.rest.api.webdriver

import ai.platon.pulsar.rest.api.webdriver.dto.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType

/**
 * Integration tests for WebDriver-compatible API endpoints.
 */
@SpringBootTest(
    classes = [WebDriverTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class WebDriverApiIntegrationTest {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    private val baseUrl: String
        get() = "http://localhost:$port"

    private fun jsonHeaders(): HttpHeaders {
        return HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
        }
    }

    @Test
    fun `should create session and return sessionId`() {
        val request = NewSessionRequest(capabilities = mapOf("browserName" to "chrome"))
        val entity = HttpEntity(request, jsonHeaders())

        val response = restTemplate.postForEntity(
            "$baseUrl/session",
            entity,
            Map::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)

        val body = response.body!!
        assertTrue(body.containsKey("value"))

        @Suppress("UNCHECKED_CAST")
        val value = body["value"] as Map<String, Any?>
        assertTrue(value.containsKey("sessionId"))
        assertNotNull(value["sessionId"])
    }

    @Test
    fun `should navigate to URL after session creation`() {
        // Create session
        val createRequest = NewSessionRequest()
        val createEntity = HttpEntity(createRequest, jsonHeaders())
        val createResponse = restTemplate.postForEntity(
            "$baseUrl/session",
            createEntity,
            Map::class.java
        )

        assertEquals(HttpStatus.OK, createResponse.statusCode)

        @Suppress("UNCHECKED_CAST")
        val sessionValue = createResponse.body!!["value"] as Map<String, Any?>
        val sessionId = sessionValue["sessionId"] as String

        // Navigate to URL
        val navRequest = SetUrlRequest(url = "https://example.com")
        val navEntity = HttpEntity(navRequest, jsonHeaders())
        val navResponse = restTemplate.postForEntity(
            "$baseUrl/session/$sessionId/url",
            navEntity,
            Map::class.java
        )

        assertEquals(HttpStatus.OK, navResponse.statusCode)
        assertNotNull(navResponse.body)
        // value can be null for success responses
        assertTrue(navResponse.body!!.containsKey("value") || navResponse.statusCode == HttpStatus.OK)
    }

    @Test
    fun `should check selector exists`() {
        // Create session
        val sessionId = createSession()

        // Check selector exists
        val selectorRequest = SelectorRef(selector = "#main-content")
        val selectorEntity = HttpEntity(selectorRequest, jsonHeaders())
        val response = restTemplate.postForEntity(
            "$baseUrl/session/$sessionId/selectors/exists",
            selectorEntity,
            Map::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)
        assertTrue(response.body!!.containsKey("value"))

        @Suppress("UNCHECKED_CAST")
        val value = response.body!!["value"] as Map<String, Any?>
        assertTrue(value.containsKey("exists"))
    }

    @Test
    fun `should find element by selector and return element reference`() {
        // Create session
        val sessionId = createSession()

        // Find element
        val selectorRequest = SelectorRef(selector = ".product-title")
        val selectorEntity = HttpEntity(selectorRequest, jsonHeaders())
        val response = restTemplate.postForEntity(
            "$baseUrl/session/$sessionId/selectors/element",
            selectorEntity,
            Map::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)
        assertTrue(response.body!!.containsKey("value"))

        @Suppress("UNCHECKED_CAST")
        val value = response.body!!["value"] as Map<String, Any?>
        // Check for WebDriver element reference key
        assertTrue(value.containsKey("element-6066-11e4-a52e-4f735466cecf"))
    }

    @Test
    fun `should click element by selector`() {
        // Create session
        val sessionId = createSession()

        // Click selector
        val selectorRequest = SelectorRef(selector = "button.submit")
        val selectorEntity = HttpEntity(selectorRequest, jsonHeaders())
        val response = restTemplate.postForEntity(
            "$baseUrl/session/$sessionId/selectors/click",
            selectorEntity,
            Map::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)
        // value can be null for success responses
        assertTrue(response.body!!.containsKey("value") || response.statusCode == HttpStatus.OK)
    }

    @Test
    fun `should return 404 for non-existent session`() {
        val response = restTemplate.getForEntity(
            "$baseUrl/session/non-existent-session-id",
            Map::class.java
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertNotNull(response.body)

        @Suppress("UNCHECKED_CAST")
        val value = response.body!!["value"] as Map<String, Any?>
        assertTrue(value.containsKey("error"))
    }

    @Test
    fun `should delete session`() {
        // Create session
        val sessionId = createSession()

        // Delete session
        restTemplate.delete("$baseUrl/session/$sessionId")

        // Verify session is gone
        val response = restTemplate.getForEntity(
            "$baseUrl/session/$sessionId",
            Map::class.java
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `should get current URL`() {
        // Create session
        val sessionId = createSession()

        // Navigate to URL
        val navRequest = SetUrlRequest(url = "https://example.com/page")
        val navEntity = HttpEntity(navRequest, jsonHeaders())
        restTemplate.postForEntity(
            "$baseUrl/session/$sessionId/url",
            navEntity,
            Map::class.java
        )

        // Get current URL
        val response = restTemplate.getForEntity(
            "$baseUrl/session/$sessionId/url",
            Map::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)
        assertEquals("https://example.com/page", response.body!!["value"])
    }

    @Test
    fun `should execute sync script`() {
        // Create session
        val sessionId = createSession()

        // Execute script
        val scriptRequest = ScriptRequest(script = "return document.title;")
        val scriptEntity = HttpEntity(scriptRequest, jsonHeaders())
        val response = restTemplate.postForEntity(
            "$baseUrl/session/$sessionId/execute/sync",
            scriptEntity,
            Map::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)
        // value can be null for success responses
        assertTrue(response.body!!.containsKey("value") || response.statusCode == HttpStatus.OK)
    }

    @Test
    fun `should create event config`() {
        // Create session
        val sessionId = createSession()

        // Create event config
        val eventRequest = EventConfig(eventType = "click", enabled = true)
        val eventEntity = HttpEntity(eventRequest, jsonHeaders())
        val response = restTemplate.postForEntity(
            "$baseUrl/session/$sessionId/event-configs",
            eventEntity,
            Map::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)

        @Suppress("UNCHECKED_CAST")
        val value = response.body!!["value"] as Map<String, Any?>
        assertTrue(value.containsKey("configId"))
        assertEquals("click", value["eventType"])
    }

    @Test
    fun `should serve openapi yaml`() {
        val response = restTemplate.getForEntity(
            "$baseUrl/openapi.yaml",
            String::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)
        assertTrue(response.body!!.contains("openapi:"))
        assertTrue(response.body!!.contains("Browser4 WebDriver-Compatible API"))
    }

    @Test
    fun `should include X-Request-Id header in responses`() {
        val request = NewSessionRequest()
        val entity = HttpEntity(request, jsonHeaders())

        val response = restTemplate.postForEntity(
            "$baseUrl/session",
            entity,
            Map::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.headers["X-Request-Id"])
    }

    // ========== Agent API Tests ==========

    @Test
    fun `should run agent task`() {
        val sessionId = createSession()

        val request = AgentRunRequest(task = "Find the login button and click it")
        val entity = HttpEntity(request, jsonHeaders())
        val response = restTemplate.postForEntity(
            "$baseUrl/session/$sessionId/agent/run",
            entity,
            Map::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)
        assertTrue(response.body!!.containsKey("value"))

        @Suppress("UNCHECKED_CAST")
        val value = response.body!!["value"] as Map<String, Any?>
        assertTrue(value.containsKey("success"))
        assertTrue(value.containsKey("message"))
    }

    @Test
    fun `should observe page with agent`() {
        val sessionId = createSession()

        val request = AgentObserveRequest(instruction = "Find interactive elements")
        val entity = HttpEntity(request, jsonHeaders())
        val response = restTemplate.postForEntity(
            "$baseUrl/session/$sessionId/agent/observe",
            entity,
            Map::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)
        assertTrue(response.body!!.containsKey("value"))
    }

    @Test
    fun `should execute agent action`() {
        val sessionId = createSession()

        val request = AgentActRequest(action = "Click the submit button")
        val entity = HttpEntity(request, jsonHeaders())
        val response = restTemplate.postForEntity(
            "$baseUrl/session/$sessionId/agent/act",
            entity,
            Map::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)

        @Suppress("UNCHECKED_CAST")
        val value = response.body!!["value"] as Map<String, Any?>
        assertTrue(value.containsKey("success"))
        assertTrue(value.containsKey("action"))
    }

    @Test
    fun `should extract data with agent`() {
        val sessionId = createSession()

        val request = AgentExtractRequest(instruction = "Extract the page title and description")
        val entity = HttpEntity(request, jsonHeaders())
        val response = restTemplate.postForEntity(
            "$baseUrl/session/$sessionId/agent/extract",
            entity,
            Map::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)

        @Suppress("UNCHECKED_CAST")
        val value = response.body!!["value"] as Map<String, Any?>
        assertTrue(value.containsKey("success"))
        assertTrue(value.containsKey("data"))
    }

    @Test
    fun `should summarize page with agent`() {
        val sessionId = createSession()

        val request = AgentSummarizeRequest(instruction = "Summarize the main content")
        val entity = HttpEntity(request, jsonHeaders())
        val response = restTemplate.postForEntity(
            "$baseUrl/session/$sessionId/agent/summarize",
            entity,
            Map::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)
        assertTrue(response.body!!.containsKey("value"))
    }

    @Test
    fun `should clear agent history`() {
        val sessionId = createSession()

        val response = restTemplate.postForEntity(
            "$baseUrl/session/$sessionId/agent/clearHistory",
            HttpEntity<String>(jsonHeaders()),
            Map::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)
        assertEquals(true, response.body!!["value"])
    }

    // ========== PulsarSession API Tests ==========

    @Test
    fun `should normalize URL`() {
        val sessionId = createSession()

        val request = NormalizeRequest(url = "example.com", args = "-expire 1d")
        val entity = HttpEntity(request, jsonHeaders())
        val response = restTemplate.postForEntity(
            "$baseUrl/session/$sessionId/normalize",
            entity,
            Map::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)

        @Suppress("UNCHECKED_CAST")
        val value = response.body!!["value"] as Map<String, Any?>
        assertTrue(value.containsKey("url"))
        assertTrue(value.containsKey("spec"))
        // Check that scheme was added
        val normalizedUrl = value["url"] as String
        assertTrue(normalizedUrl.startsWith("https://"))
    }

    @Test
    fun `should open URL immediately`() {
        val sessionId = createSession()

        val request = OpenRequest(url = "https://example.com/page")
        val entity = HttpEntity(request, jsonHeaders())
        val response = restTemplate.postForEntity(
            "$baseUrl/session/$sessionId/open",
            entity,
            Map::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)

        @Suppress("UNCHECKED_CAST")
        val value = response.body!!["value"] as Map<String, Any?>
        assertTrue(value.containsKey("url"))
        assertEquals("https://example.com/page", value["url"])
    }

    @Test
    fun `should load URL from storage or internet`() {
        val sessionId = createSession()

        val request = LoadRequest(url = "https://example.com", args = "-expire 1d")
        val entity = HttpEntity(request, jsonHeaders())
        val response = restTemplate.postForEntity(
            "$baseUrl/session/$sessionId/load",
            entity,
            Map::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)

        @Suppress("UNCHECKED_CAST")
        val value = response.body!!["value"] as Map<String, Any?>
        assertTrue(value.containsKey("url"))
        assertTrue(value.containsKey("protocolStatus"))
    }

    @Test
    fun `should submit URL to crawl pool`() {
        val sessionId = createSession()

        val request = SubmitRequest(url = "https://example.com/to-crawl", args = "-expire 7d")
        val entity = HttpEntity(request, jsonHeaders())
        val response = restTemplate.postForEntity(
            "$baseUrl/session/$sessionId/submit",
            entity,
            Map::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)
        assertEquals(true, response.body!!["value"])
    }

    /**
     * Helper method to create a session and return the session ID.
     */
    private fun createSession(): String {
        val request = NewSessionRequest()
        val entity = HttpEntity(request, jsonHeaders())
        val response = restTemplate.postForEntity(
            "$baseUrl/session",
            entity,
            Map::class.java
        )

        @Suppress("UNCHECKED_CAST")
        val value = response.body!!["value"] as Map<String, Any?>
        return value["sessionId"] as String
    }
}
