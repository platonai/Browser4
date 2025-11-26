package ai.platon.pulsar.rest.api.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Request to create a new WebDriver session.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class NewSessionRequest(
    val capabilities: Map<String, Any?>? = null,
    val desiredCapabilities: Map<String, Any?>? = null
)

/**
 * Response for new session creation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class NewSessionResponse(
    val value: NewSessionValue
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class NewSessionValue(
    val sessionId: String,
    val capabilities: Map<String, Any?> = emptyMap()
)

/**
 * Session information response.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SessionInfo(
    val value: SessionInfoValue
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SessionInfoValue(
    val sessionId: String,
    val capabilities: Map<String, Any?> = emptyMap(),
    val url: String? = null,
    val status: String = "active"
)

/**
 * Request to navigate to a URL.
 */
data class SetUrlRequest(
    val url: String
)

/**
 * CSS selector reference.
 */
data class SelectorRef(
    val selector: String
)

/**
 * Wait for selector request.
 */
data class SelectorWaitRequest(
    val selector: String,
    val timeout: Int = 5000
)

/**
 * Fill selector request.
 */
data class SelectorFillRequest(
    val selector: String,
    val value: String
)

/**
 * Press key on selector request.
 */
data class SelectorPressRequest(
    val selector: String,
    val key: String
)

/**
 * WebDriver find element request.
 */
data class FindElementRequest(
    val using: String,
    val value: String
)

/**
 * WebDriver element reference (W3C format).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ElementRef(
    @JsonProperty("element-6066-11e4-a52e-4f735466cecf")
    val webDriverElementId: String,
    val elementId: String = webDriverElementId
)

/**
 * Send keys request. Either text or value must be provided.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SendKeysRequest(
    val text: String? = null,
    val value: List<String>? = null
) {
    /**
     * Gets the effective text to send.
     * @return Combined text from text field or value array
     */
    fun getEffectiveText(): String = text ?: value?.joinToString("") ?: ""
}

/**
 * Execute script request.
 */
data class ExecuteScriptRequest(
    val script: String,
    val args: List<Any?> = emptyList()
)

/**
 * Delay request.
 */
data class DelayRequest(
    val ms: Int
)

/**
 * Event configuration.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class EventConfig(
    val id: String? = null,
    val eventType: String,
    val selector: String? = null,
    val enabled: Boolean = true
)

/**
 * Event data.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Event(
    val id: String,
    val eventType: String,
    val timestamp: Long,
    val data: Map<String, Any?> = emptyMap()
)

/**
 * Event subscription request.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class EventSubscription(
    val eventTypes: List<String>,
    val callback: String? = null
)

/**
 * Generic value response wrapper (WebDriver format).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ValueResponse<T>(
    val value: T?
)

/**
 * Error response (WebDriver format).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ErrorResponse(
    val value: ErrorValue
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ErrorValue(
    val error: String,
    val message: String,
    val stacktrace: String? = null
)

/**
 * Selector exists response.
 */
data class SelectorExistsValue(
    val exists: Boolean
)

/**
 * Wait for selector response.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SelectorWaitValue(
    val found: Boolean,
    val elementId: String? = null
)

/**
 * Subscription response.
 */
data class SubscriptionValue(
    val subscriptionId: String
)
