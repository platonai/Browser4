package ai.platon.pulsar.rest.api.webdriver

import ai.platon.pulsar.rest.api.dto.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for WebDriver-compatible API endpoints.
 * Tests session management, navigation, and selector operations.
 */
@SpringBootTest(classes = [WebDriverApiTestApplication::class])
@AutoConfigureMockMvc
@ActiveProfiles("rest")
class WebDriverApiIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Test
    fun `create session should return sessionId`() {
        val request = NewSessionRequest(capabilities = mapOf("browserName" to "chrome"))
        
        val result = mockMvc.perform(
            post("/session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.sessionId").exists())
            .andReturn()

        val response = objectMapper.readValue(result.response.contentAsString, NewSessionResponse::class.java)
        assertNotNull(response.value.sessionId)
        assertTrue(response.value.sessionId.isNotEmpty())
    }

    @Test
    fun `navigate to URL should succeed`() {
        // First create a session
        val createRequest = NewSessionRequest(capabilities = emptyMap())
        val createResult = mockMvc.perform(
            post("/session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest))
        )
            .andExpect(status().isOk)
            .andReturn()

        val sessionResponse = objectMapper.readValue(createResult.response.contentAsString, NewSessionResponse::class.java)
        val sessionId = sessionResponse.value.sessionId

        // Navigate to URL
        val navRequest = SetUrlRequest(url = "https://example.com")
        mockMvc.perform(
            post("/session/$sessionId/url")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(navRequest))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value").doesNotExist())

        // Verify URL was set
        mockMvc.perform(get("/session/$sessionId/url"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value").value("https://example.com"))
    }

    @Test
    fun `selector exists should return exists true`() {
        // Create session
        val createRequest = NewSessionRequest(capabilities = emptyMap())
        val createResult = mockMvc.perform(
            post("/session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest))
        )
            .andExpect(status().isOk)
            .andReturn()

        val sessionResponse = objectMapper.readValue(createResult.response.contentAsString, NewSessionResponse::class.java)
        val sessionId = sessionResponse.value.sessionId

        // Check selector exists
        val selectorRequest = SelectorRef(selector = "#test-element")
        mockMvc.perform(
            post("/session/$sessionId/selectors/exists")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(selectorRequest))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.exists").value(true))
    }

    @Test
    fun `selector element should return element reference`() {
        // Create session
        val createRequest = NewSessionRequest(capabilities = emptyMap())
        val createResult = mockMvc.perform(
            post("/session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest))
        )
            .andExpect(status().isOk)
            .andReturn()

        val sessionResponse = objectMapper.readValue(createResult.response.contentAsString, NewSessionResponse::class.java)
        val sessionId = sessionResponse.value.sessionId

        // Find element by selector
        val selectorRequest = SelectorRef(selector = ".my-button")
        mockMvc.perform(
            post("/session/$sessionId/selectors/element")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(selectorRequest))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.element-6066-11e4-a52e-4f735466cecf").exists())
            .andExpect(jsonPath("$.value.elementId").exists())
    }

    @Test
    fun `selector click should succeed`() {
        // Create session
        val createRequest = NewSessionRequest(capabilities = emptyMap())
        val createResult = mockMvc.perform(
            post("/session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest))
        )
            .andExpect(status().isOk)
            .andReturn()

        val sessionResponse = objectMapper.readValue(createResult.response.contentAsString, NewSessionResponse::class.java)
        val sessionId = sessionResponse.value.sessionId

        // Click selector
        val selectorRequest = SelectorRef(selector = "button.submit")
        mockMvc.perform(
            post("/session/$sessionId/selectors/click")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(selectorRequest))
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `delete session should succeed`() {
        // Create session
        val createRequest = NewSessionRequest(capabilities = emptyMap())
        val createResult = mockMvc.perform(
            post("/session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest))
        )
            .andExpect(status().isOk)
            .andReturn()

        val sessionResponse = objectMapper.readValue(createResult.response.contentAsString, NewSessionResponse::class.java)
        val sessionId = sessionResponse.value.sessionId

        // Delete session
        mockMvc.perform(delete("/session/$sessionId"))
            .andExpect(status().isOk)

        // Verify session is deleted
        mockMvc.perform(get("/session/$sessionId"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `get session for non-existent session should return 404`() {
        mockMvc.perform(get("/session/non-existent-session-id"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.value.error").value("no such session"))
    }

    @Test
    fun `X-Request-Id header should be included in response`() {
        val requestId = "test-request-id-12345"
        val createRequest = NewSessionRequest(capabilities = emptyMap())
        
        mockMvc.perform(
            post("/session")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", requestId)
                .content(objectMapper.writeValueAsString(createRequest))
        )
            .andExpect(status().isOk)
            .andExpect(header().string("X-Request-Id", requestId))
    }
}
