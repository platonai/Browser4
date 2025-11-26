package ai.platon.pulsar.rest.api.webdriver

import ai.platon.pulsar.rest.api.webdriver.dto.*
import ai.platon.pulsar.rest.api.webdriver.store.InMemoryStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for WebDriver API endpoints.
 * These tests verify the basic functionality of the mocked skeleton implementation.
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

    @Autowired
    lateinit var store: InMemoryStore

    val baseUrl get() = "http://localhost:$port"

    @BeforeEach
    fun setup() {
        // Reset store between tests by creating a new session
    }

    @Test
    fun `should create session and return sessionId`() {
        val request = NewSessionRequest(capabilities = mapOf("browserName" to "chrome"))
        
        val response = restTemplate.postForEntity(
            "$baseUrl/session",
            request,
            SessionResponseWrapper::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)
        assertNotNull(response.body?.value?.sessionId)
        assertTrue(response.body?.value?.sessionId?.isNotBlank() == true)
    }

    @Test
    fun `should get session after creation`() {
        // Create session first
        val createRequest = NewSessionRequest()
        val createResponse = restTemplate.postForEntity(
            "$baseUrl/session",
            createRequest,
            SessionResponseWrapper::class.java
        )
        val sessionId = createResponse.body?.value?.sessionId
        assertNotNull(sessionId)

        // Get session
        val getResponse = restTemplate.getForEntity(
            "$baseUrl/session/$sessionId",
            SessionResponseWrapper::class.java
        )

        assertEquals(HttpStatus.OK, getResponse.statusCode)
        assertEquals(sessionId, getResponse.body?.value?.sessionId)
    }

    @Test
    fun `should return 404 for non-existent session`() {
        val response = restTemplate.getForEntity(
            "$baseUrl/session/non-existent-session",
            String::class.java
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `should delete session`() {
        // Create session first
        val createRequest = NewSessionRequest()
        val createResponse = restTemplate.postForEntity(
            "$baseUrl/session",
            createRequest,
            SessionResponseWrapper::class.java
        )
        val sessionId = createResponse.body?.value?.sessionId
        assertNotNull(sessionId)

        // Delete session
        restTemplate.delete("$baseUrl/session/$sessionId")

        // Verify session no longer exists
        val getResponse = restTemplate.getForEntity(
            "$baseUrl/session/$sessionId",
            String::class.java
        )
        assertEquals(HttpStatus.NOT_FOUND, getResponse.statusCode)
    }

    @Test
    fun `should navigate to URL`() {
        // Create session
        val sessionId = createSession()

        // Navigate to URL
        val navRequest = SetUrlRequest(url = "https://example.com")
        val navResponse = restTemplate.postForEntity(
            "$baseUrl/session/$sessionId/url",
            navRequest,
            String::class.java
        )

        assertEquals(HttpStatus.OK, navResponse.statusCode)

        // Verify URL was set
        val urlResponse = restTemplate.getForEntity(
            "$baseUrl/session/$sessionId/url",
            StringValueWrapper::class.java
        )
        assertEquals(HttpStatus.OK, urlResponse.statusCode)
        assertEquals("https://example.com", urlResponse.body?.value)
    }

    @Test
    fun `should check selector exists`() {
        val sessionId = createSession()
        
        val request = SelectorRef(selector = ".my-class")
        val response = restTemplate.postForEntity(
            "$baseUrl/session/$sessionId/selectors/exists",
            request,
            BooleanValueWrapper::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body?.value == true)
    }

    @Test
    fun `should find element by selector`() {
        val sessionId = createSession()
        
        val request = SelectorRef(selector = "#submit-button")
        val response = restTemplate.postForEntity(
            "$baseUrl/session/$sessionId/selectors/element",
            request,
            ElementRefWrapper::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body?.value)
        assertNotNull(response.body?.value?.elementId)
    }

    @Test
    fun `should click element by selector`() {
        val sessionId = createSession()
        
        val request = SelectorRef(selector = "#submit-button")
        val response = restTemplate.postForEntity(
            "$baseUrl/session/$sessionId/selectors/click",
            request,
            String::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
    }

    @Test
    fun `should find element using W3C strategy`() {
        val sessionId = createSession()
        
        val request = FindElementRequest(using = "css selector", value = ".my-class")
        val response = restTemplate.postForEntity(
            "$baseUrl/session/$sessionId/element",
            request,
            ElementRefWrapper::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body?.value?.elementId)
    }

    @Test
    fun `should execute sync script`() {
        val sessionId = createSession()
        
        val request = ExecuteScriptRequest(script = "return document.title", args = emptyList())
        val response = restTemplate.postForEntity(
            "$baseUrl/session/$sessionId/execute/sync",
            request,
            StringValueWrapper::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
    }

    @Test
    fun `should create and get event configs`() {
        val sessionId = createSession()
        
        // Create event config
        val config = EventConfig(eventType = "click", selector = "#button")
        val createResponse = restTemplate.postForEntity(
            "$baseUrl/session/$sessionId/event-configs",
            config,
            EventConfigWrapper::class.java
        )
        assertEquals(HttpStatus.OK, createResponse.statusCode)

        // Get event configs
        val getResponse = restTemplate.getForEntity(
            "$baseUrl/session/$sessionId/event-configs",
            EventConfigsWrapper::class.java
        )
        assertEquals(HttpStatus.OK, getResponse.statusCode)
        assertTrue((getResponse.body?.value?.size ?: 0) >= 1)
    }

    @Test
    fun `should serve OpenAPI YAML`() {
        val response = restTemplate.getForEntity(
            "$baseUrl/openapi.yaml",
            String::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)
        assertTrue(response.body?.contains("openapi: 3.1.0") == true)
        assertTrue(response.body?.contains("/session") == true)
    }

    private fun createSession(): String {
        val request = NewSessionRequest()
        val response = restTemplate.postForEntity(
            "$baseUrl/session",
            request,
            SessionResponseWrapper::class.java
        )
        return response.body?.value?.sessionId 
            ?: throw IllegalStateException("Failed to create session")
    }

    // Wrapper classes for response deserialization
    data class SessionResponseWrapper(val value: SessionValue?)
    data class StringValueWrapper(val value: String?)
    data class BooleanValueWrapper(val value: Boolean?)
    data class ElementRefWrapper(val value: ElementRef?)
    data class EventConfigWrapper(val value: EventConfig?)
    data class EventConfigsWrapper(val value: List<EventConfig>?)
}
