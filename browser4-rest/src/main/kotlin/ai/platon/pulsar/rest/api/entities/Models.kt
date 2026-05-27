package ai.platon.pulsar.rest.api.entities

import ai.platon.browser4.common.B4Constants.PROFILE_MODE_CAPABILITY
import ai.platon.pulsar.common.ManagedSession
import ai.platon.pulsar.common.SessionManager
import ai.platon.pulsar.skeleton.common.options.LoadOptions

/**
 * Request for chat
 *
 * @property url The page url
 * @property prompt The prompt, e.g. "Tell me something about the page"
 * @property args The load arguments
 * @property actions Instructs, e.g. "click the button with id 'submit'", [actions]  are performed after the active DOM is ready
 * */
data class PromptRequest constructor(
    /**
     * The page url
     * */
    var url: String,
    /**
     * The prompt, e.g. "Tell me something about the page"
     * */
    var prompt: String? = null,
    /**
     * The load arguments
     *
     * @see [LoadOptions]
     * */
    var args: String? = null,
    /**
     * Actions, e.g. "click the button with id 'submit'", [actions] are performed after the active DOM is ready
     * */
    var actions: List<String>? = null
)

data class ScrapeStatusRequest(
    val id: String,
)

data class SessionResponse(
    val sessionId: String,
    val status: String,
    val profileMode: String? = null,
    val capabilities: Map<String, Any?>? = null,
    val url: String? = null,
    val createdAt: Long,
    val lastAccessedAt: Long,
)

fun ManagedSession.toSessionResponse(): SessionResponse {
    val safeCapabilities = capabilities?.toMap()
    return SessionResponse(
        sessionId = sessionId,
        status = status,
        profileMode = safeCapabilities?.get(PROFILE_MODE_CAPABILITY)?.toString(),
        capabilities = safeCapabilities,
        url = url,
        createdAt = createdAt,
        lastAccessedAt = lastAccessedAt,
    )
}

/**
 * W3 resources
 * */
data class W3DocumentRequest(
    var url: String,
    val args: String? = null,
)

data class NavigateRequest(
    var url: String,
)

data class ScreenshotRequest(
    var id: String
)
