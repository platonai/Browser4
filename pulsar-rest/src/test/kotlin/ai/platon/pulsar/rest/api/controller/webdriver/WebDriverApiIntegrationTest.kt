package ai.platon.pulsar.rest.api.controller.webdriver

import ai.platon.pulsar.rest.api.dto.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for WebDriver API controllers.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebDriverApiIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Test
    fun `test create and delete session`() {
        // Create session
        val createRequest = NewSessionRequest(
            capabilities = Capabilities(browserName = "chrome")
        )

        val createResult = mockMvc.post("/session") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(createRequest)
        }.andExpect {
            status { isOk() }
            content { contentType(MediaType.APPLICATION_JSON) }
            jsonPath("$.value.sessionId") { exists() }
            jsonPath("$.value.capabilities") { exists() }
        }.andReturn()

        // Extract session ID
        val responseBody = createResult.response.contentAsString
        val response = objectMapper.readValue(responseBody, NewSessionResponse::class.java)
        val sessionId = response.value.sessionId
        assertNotNull(sessionId)
        assertTrue(sessionId.isNotBlank())

        // Get session
        mockMvc.get("/session/$sessionId")
            .andExpect {
                status { isOk() }
                jsonPath("$.value.sessionId") { value(sessionId) }
                jsonPath("$.value.status") { value("active") }
            }

        // Delete session
        mockMvc.delete("/session/$sessionId")
            .andExpect {
                status { isOk() }
            }

        // Verify session no longer exists
        mockMvc.get("/session/$sessionId")
            .andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `test navigation endpoints`() {
        // Create session
        val sessionId = createTestSession()

        // Navigate to URL
        val navRequest = SetUrlRequest(url = "https://example.com")
        mockMvc.post("/session/$sessionId/url") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(navRequest)
        }.andExpect {
            status { isOk() }
        }

        // Get current URL
        mockMvc.get("/session/$sessionId/url")
            .andExpect {
                status { isOk() }
                jsonPath("$.value") { value("https://example.com") }
            }

        // Get base URI
        mockMvc.get("/session/$sessionId/baseUri")
            .andExpect {
                status { isOk() }
                jsonPath("$.value") { value("https://example.com") }
            }

        // Cleanup
        mockMvc.delete("/session/$sessionId")
    }

    @Test
    fun `test selector exists endpoint`() {
        val sessionId = createTestSession()

        val selectorRequest = SelectorRequest(selector = ".test-class")
        mockMvc.post("/session/$sessionId/selectors/exists") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(selectorRequest)
        }.andExpect {
            status { isOk() }
            jsonPath("$.value") { value(true) }
        }

        // Cleanup
        mockMvc.delete("/session/$sessionId")
    }

    @Test
    fun `test selector find element endpoint`() {
        val sessionId = createTestSession()

        val selectorRequest = SelectorRequest(selector = "#my-element")
        mockMvc.post("/session/$sessionId/selectors/element") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(selectorRequest)
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.$ELEMENT_KEY") { exists() }
        }

        // Cleanup
        mockMvc.delete("/session/$sessionId")
    }

    @Test
    fun `test selector click endpoint`() {
        val sessionId = createTestSession()

        val selectorRequest = SelectorRequest(selector = "button.submit")
        mockMvc.post("/session/$sessionId/selectors/click") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(selectorRequest)
        }.andExpect {
            status { isOk() }
        }

        // Cleanup
        mockMvc.delete("/session/$sessionId")
    }

    @Test
    fun `test standard element find endpoint`() {
        val sessionId = createTestSession()

        val locatorRequest = LocatorRequest(using = "css selector", value = "div.content")
        mockMvc.post("/session/$sessionId/element") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(locatorRequest)
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.$ELEMENT_KEY") { exists() }
        }

        // Cleanup
        mockMvc.delete("/session/$sessionId")
    }

    @Test
    fun `test script execution endpoint`() {
        val sessionId = createTestSession()

        val scriptRequest = ExecuteScriptRequest(
            script = "return document.title;",
            args = listOf()
        )
        mockMvc.post("/session/$sessionId/execute/sync") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(scriptRequest)
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.executed") { value(true) }
        }

        // Cleanup
        mockMvc.delete("/session/$sessionId")
    }

    @Test
    fun `test control endpoints`() {
        val sessionId = createTestSession()

        // Test delay
        val delayRequest = DelayRequest(milliseconds = 100)
        mockMvc.post("/session/$sessionId/control/delay") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(delayRequest)
        }.andExpect {
            status { isOk() }
        }

        // Test pause
        mockMvc.post("/session/$sessionId/control/pause")
            .andExpect {
                status { isOk() }
            }

        // Verify session is paused
        mockMvc.get("/session/$sessionId")
            .andExpect {
                status { isOk() }
                jsonPath("$.value.status") { value("paused") }
            }

        // Test stop
        mockMvc.post("/session/$sessionId/control/stop")
            .andExpect {
                status { isOk() }
            }

        // Cleanup
        mockMvc.delete("/session/$sessionId")
    }

    @Test
    fun `test event config endpoints`() {
        val sessionId = createTestSession()

        // Create event config
        val eventConfig = EventConfig(eventType = "click", selector = "button")
        mockMvc.post("/session/$sessionId/event-configs") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(eventConfig)
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.eventType") { value("click") }
            jsonPath("$.value.id") { exists() }
        }

        // Get event configs
        mockMvc.get("/session/$sessionId/event-configs")
            .andExpect {
                status { isOk() }
                jsonPath("$.value") { isArray() }
            }

        // Get events
        mockMvc.get("/session/$sessionId/events")
            .andExpect {
                status { isOk() }
                jsonPath("$.value") { isArray() }
            }

        // Cleanup
        mockMvc.delete("/session/$sessionId")
    }

    @Test
    fun `test event subscription endpoint`() {
        val sessionId = createTestSession()

        val subscription = EventSubscription(
            eventTypes = listOf("click", "load"),
            callbackUrl = "https://example.com/callback"
        )
        mockMvc.post("/session/$sessionId/events/subscribe") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(subscription)
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.subscriptionId") { exists() }
            jsonPath("$.value.eventTypes") { isArray() }
        }

        // Cleanup
        mockMvc.delete("/session/$sessionId")
    }

    @Test
    fun `test openapi yaml endpoint`() {
        mockMvc.get("/openapi.yaml")
            .andExpect {
                status { isOk() }
                content { string(org.hamcrest.Matchers.containsString("openapi: 3.1.0")) }
            }
    }

    @Test
    fun `test session not found returns 404`() {
        mockMvc.get("/session/non-existent-session")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.value.error") { exists() }
                jsonPath("$.value.message") { exists() }
            }
    }

    private fun createTestSession(): String {
        val createRequest = NewSessionRequest(
            capabilities = Capabilities(browserName = "chrome")
        )

        val result = mockMvc.post("/session") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(createRequest)
        }.andReturn()

        val responseBody = result.response.contentAsString
        val response = objectMapper.readValue(responseBody, NewSessionResponse::class.java)
        return response.value.sessionId
    }
}
