package ai.platon.pulsar.rest.api.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * W3C WebDriver element identifier key
 */
const val ELEMENT_KEY = "element-6066-11e4-a52e-4f735466cecf"

// ========== Session DTOs ==========

/**
 * Capabilities for creating a new session
 */
data class Capabilities(
    val browserName: String? = "chrome",
    val browserVersion: String? = null,
    val platformName: String? = null,
    val acceptInsecureCerts: Boolean? = false,
    val pageLoadStrategy: String? = "normal",
    val proxy: Proxy? = null,
    val timeouts: Timeouts? = null,
    val unhandledPromptBehavior: String? = null
)

/**
 * Proxy configuration
 */
data class Proxy(
    val proxyType: String? = null,
    val proxyAutoconfigUrl: String? = null,
    val httpProxy: String? = null,
    val sslProxy: String? = null,
    val socksProxy: String? = null,
    val socksVersion: Int? = null,
    val noProxy: List<String>? = null
)

/**
 * Timeout configuration
 */
data class Timeouts(
    val script: Int? = null,
    val pageLoad: Int? = null,
    val implicit: Int? = null
)

/**
 * Browser4 specific load options
 */
data class LoadOptionsDto(
    val waitForSelector: String? = null,
    val waitForTimeout: Int? = null,
    val scrollCount: Int? = null,
    val delayAfterScroll: Int? = null,
    val requireSize: Int? = null,
    val refresh: Boolean? = null
)

/**
 * Request to create a new session
 */
data class NewSessionRequest(
    val capabilities: Capabilities? = null,
    val loadOptions: LoadOptionsDto? = null
)

/**
 * Session data returned in responses
 */
data class SessionData(
    val sessionId: String,
    val capabilities: Capabilities,
    val status: String = "active",
    val currentUrl: String? = null
)

/**
 * Response wrapper for new session creation
 */
data class NewSessionResponse(
    val value: NewSessionValue
)

data class NewSessionValue(
    val sessionId: String,
    val capabilities: Capabilities
)

/**
 * Response wrapper for session details
 */
data class SessionResponse(
    val value: SessionData
)

// ========== Navigation DTOs ==========

/**
 * Request to navigate to a URL
 */
data class SetUrlRequest(
    val url: String,
    val loadOptions: LoadOptionsDto? = null
)

// ========== Selector DTOs ==========

/**
 * Request containing a CSS selector
 */
data class SelectorRequest(
    val selector: String
)

/**
 * Request for waiting for a selector
 */
data class SelectorWaitRequest(
    val selector: String,
    val timeout: Int? = 30000
)

/**
 * Request for filling an input by selector
 */
data class SelectorFillRequest(
    val selector: String,
    val value: String
)

/**
 * Request for pressing a key on an element by selector
 */
data class SelectorPressRequest(
    val selector: String,
    val key: String
)

// ========== Element DTOs ==========

/**
 * Request using W3C locator strategy
 */
data class LocatorRequest(
    val using: String,
    val value: String
)

/**
 * Request to send keys to an element
 */
data class SendKeysRequest(
    val text: String
)

/**
 * W3C WebDriver element reference
 */
data class ElementRef(
    @JsonProperty(ELEMENT_KEY)
    val elementId: String
)

/**
 * Response wrapper for single element
 */
data class ElementRefResponse(
    val value: ElementRef
)

/**
 * Response wrapper for multiple elements
 */
data class ElementRefsResponse(
    val value: List<ElementRef>
)

// ========== Script DTOs ==========

/**
 * Request to execute a script
 */
data class ExecuteScriptRequest(
    val script: String,
    val args: List<Any?>? = null
)

// ========== Control DTOs ==========

/**
 * Request to delay execution
 */
data class DelayRequest(
    val milliseconds: Int
)

// ========== Event DTOs ==========

/**
 * Event configuration
 */
data class EventConfig(
    val id: String = UUID.randomUUID().toString(),
    val eventType: String,
    val selector: String? = null,
    val enabled: Boolean = true
)

/**
 * Event subscription request
 */
data class EventSubscription(
    val eventTypes: List<String>,
    val callbackUrl: String? = null
)

/**
 * Captured event data
 */
data class Event(
    val id: String = UUID.randomUUID().toString(),
    val eventType: String,
    val timestamp: Instant = Instant.now(),
    val data: Map<String, Any?>? = null
)

/**
 * Response wrapper for event configuration
 */
data class EventConfigResponse(
    val value: EventConfig
)

/**
 * Response wrapper for multiple event configurations
 */
data class EventConfigsResponse(
    val value: List<EventConfig>
)

/**
 * Response wrapper for events
 */
data class EventsResponse(
    val value: List<Event>
)

/**
 * Response wrapper for event subscription
 */
data class EventSubscriptionResponse(
    val value: EventSubscriptionValue
)

data class EventSubscriptionValue(
    val subscriptionId: String,
    val eventTypes: List<String>
)

// ========== Generic Response DTOs ==========

/**
 * Generic value response wrapper (for W3C WebDriver compatibility)
 */
data class ValueResponse<T>(
    val value: T?
)

/**
 * Error response following W3C WebDriver format
 */
data class ErrorResponse(
    val value: ErrorValue
)

data class ErrorValue(
    val error: String,
    val message: String,
    val stacktrace: String? = null
)

// ========== Helper Functions ==========

/**
 * Create a W3C WebDriver compatible error response
 */
fun errorResponse(error: String, message: String, stacktrace: String? = null): ErrorResponse {
    return ErrorResponse(ErrorValue(error, message, stacktrace))
}

/**
 * Wrap a value in a W3C WebDriver compatible response
 */
fun <T> valueResponse(value: T?): ValueResponse<T> {
    return ValueResponse(value)
}

/**
 * Generate element ID from selector (mock implementation)
 * Uses UUID to avoid collisions from hashCode
 */
fun generateElementId(selector: String): String {
    return "element-${java.util.UUID.randomUUID()}"
}
