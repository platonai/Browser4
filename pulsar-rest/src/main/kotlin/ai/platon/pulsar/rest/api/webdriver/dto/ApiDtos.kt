package ai.platon.pulsar.rest.api.webdriver.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

/**
 * Request to create a new browser session.
 */
data class NewSessionRequest(
    val capabilities: Map<String, Any?>? = null,
    val desiredCapabilities: Map<String, Any?>? = null
)

/**
 * Response containing session information wrapped in WebDriver-style value.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SessionValue(
    val sessionId: String,
    val capabilities: Map<String, Any?> = emptyMap()
)

/**
 * Request to navigate to a URL.
 */
data class SetUrlRequest(
    val url: String
)

/**
 * CSS selector reference for element operations.
 */
data class SelectorRef(
    val selector: String
)

/**
 * Request to wait for element by selector.
 */
data class SelectorWaitRequest(
    val selector: String,
    val timeout: Int = 30000
)

/**
 * Request to fill element with value by selector.
 */
data class SelectorFillRequest(
    val selector: String,
    val value: String
)

/**
 * Request to press key on element by selector.
 */
data class SelectorPressRequest(
    val selector: String,
    val key: String
)

/**
 * Request to find element using W3C WebDriver strategy.
 */
data class FindElementRequest(
    val using: String,
    val value: String
) {
    companion object {
        const val STRATEGY_CSS = "css selector"
        const val STRATEGY_XPATH = "xpath"
        const val STRATEGY_ID = "id"
        const val STRATEGY_NAME = "name"
        const val STRATEGY_TAG_NAME = "tag name"
        const val STRATEGY_CLASS_NAME = "class name"
        const val STRATEGY_LINK_TEXT = "link text"
        const val STRATEGY_PARTIAL_LINK_TEXT = "partial link text"
    }
}

/**
 * W3C WebDriver element reference.
 * The property name is the W3C standard element identifier.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ElementRef(
    @JsonProperty("element-6066-11e4-a52e-4f735466cecf")
    val elementId: String
)

/**
 * Request to send keys to an element.
 */
data class ElementValueRequest(
    val text: String? = null,
    val value: List<String>? = null
)

/**
 * Request to execute JavaScript.
 */
data class ExecuteScriptRequest(
    val script: String,
    val args: List<Any?>? = null
)

/**
 * Request to add a delay.
 */
data class DelayRequest(
    val ms: Int
)

/**
 * Event configuration.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class EventConfig(
    val id: String = UUID.randomUUID().toString(),
    val eventType: String,
    val selector: String? = null,
    val options: Map<String, Any?>? = null
)

/**
 * Captured event.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Event(
    val id: String = UUID.randomUUID().toString(),
    val eventType: String,
    val timestamp: Long = System.currentTimeMillis(),
    val data: Map<String, Any?>? = null
)

/**
 * Request to subscribe to events.
 */
data class EventSubscribeRequest(
    val eventTypes: List<String>
)

/**
 * Event subscription.
 */
data class Subscription(
    val subscriptionId: String = UUID.randomUUID().toString(),
    val eventTypes: List<String>
)

/**
 * Generic WebDriver-style response wrapper.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class WebDriverResponse<T>(
    val value: T
)

/**
 * Error details for WebDriver-style error response.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ErrorValue(
    val error: String,
    val message: String,
    val stacktrace: String? = null
)

/**
 * Session state stored in memory.
 */
data class SessionState(
    val sessionId: String,
    var currentUrl: String? = null,
    var documentUri: String? = null,
    var baseUri: String? = null,
    val capabilities: Map<String, Any?> = emptyMap(),
    var isPaused: Boolean = false,
    var isStopped: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Element state stored in memory.
 */
data class ElementState(
    val elementId: String,
    val sessionId: String,
    val selector: String,
    val locatorStrategy: String? = null,
    val locatorValue: String? = null,
    var text: String? = null,
    var attributes: MutableMap<String, String> = mutableMapOf(),
    val createdAt: Long = System.currentTimeMillis()
)
