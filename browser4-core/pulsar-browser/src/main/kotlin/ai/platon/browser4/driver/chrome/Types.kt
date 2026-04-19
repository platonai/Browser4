package ai.platon.browser4.driver.chrome

import ai.platon.cdt.kt.protocol.types.network.LoadNetworkResourcePageResult
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Duration
import java.time.Instant

/**
 * NodeId does not explicitly prohibit 0, but as seen in the internal implementation (Chromium source code):
 * - All valid nodes are assigned NodeIds starting from 1
 * - `0` is reserved as an "invalid / null node"
 *
 * DOM.NodeId #
 * Unique DOM node identifier.
 * Type: integer
 *
 * DOM.BackendNodeId #
 * Unique DOM node identifier used to reference a node that may not have been pushed to the front-end.
 * Type: integer
 *
 * References:
 * - [NodeId](https://chromedevtools.github.io/devtools-protocol/tot/DOM/#type-NodeId)
 * */
data class NodeRef constructor(
    val nodeId: Int = 0,
    // backend node id is more stable
    val backendNodeId: Int = 0,
    // objectId is ephemeral; do not cache across calls. Always resolve a fresh objectId when needed.
    val objectId: String? = null
) {
    // Playwright compatible selector for backend node id, e.g. "e12345" for backendNodeId 12345
    val selector get() = "e$backendNodeId"

    /**
     * Check if the node may exist.
     *
     * At least one of nodeId and backendNodeId is positive.
     * */
    fun mayExist(): Boolean {
        return nodeId > 0 || backendNodeId > 0
    }

    fun isNull(): Boolean {
        return nodeId == 0 && backendNodeId == 0
    }
}

class ChromeVersion {
    @JsonProperty("Browser")
    val browser: String? = null

    @JsonProperty("Protocol-Version")
    val protocolVersion: String? = null

    @JsonProperty("User-Agent")
    val userAgent: String? = null

    @JsonProperty("V8-Version")
    val v8Version: String? = null

    @JsonProperty("WebKit-Version")
    val webKitVersion: String? = null

    @JsonProperty("webSocketDebuggerUrl")
    val webSocketDebuggerUrl: String? = null
}

class ChromeTab {
    var id: String = ""
    var parentId: String? = null
    var description: String? = null
    var title: String? = null
    var type: String? = null
    var url: String? = null
    var devtoolsFrontendUrl: String? = null
    var webSocketDebuggerUrl: String? = null
    var faviconUrl: String? = null

    val createTime = Instant.now()

    val urlOrEmpty get() = url ?: ""

    fun isPageType(): Boolean = PAGE_TYPE == type

    companion object {
        const val PAGE_TYPE = "page"
    }
}

class MethodInvocation(
    var id: Long = 0,
    var method: String,
    var params: Map<String, Any>? = null
) {
    override fun toString(): String {
        val parameters = params?.entries?.joinToString(", ") { it.key + ": " + "..." }
        return if (parameters != null) "$method($parameters)" else "$method()"
    }
}

class DevToolsConfig(
    var readTimeout: Duration = Duration.ofSeconds(READ_TIMEOUT_SECONDS)
) {
    companion object {
        private const val READ_TIMEOUT_PROPERTY = "browser.driver.chrome.readTimeout"
        private val READ_TIMEOUT_SECONDS = System.getProperty(READ_TIMEOUT_PROPERTY, "30").toLong()
    }
}

class NetworkResourceResponse(
    val success: Boolean = false,
    val netError: Int = 0,
    val netErrorName: String = "",
    /** Request isn't made */
    val httpStatusCode: Int = 0,
    val stream: String? = null,
    val headers: Map<String, Any?>? = null,
) {
    companion object {
        fun from(res: LoadNetworkResourcePageResult): NetworkResourceResponse {
            val success = res.success
            val netError = res.netError?.toInt() ?: 0
            val netErrorName = res.netErrorName ?: ""
            val httpStatusCode = res.httpStatusCode?.toInt() ?: 400
            // All pulsar added headers have a prefix Q-
            val headers = res.headers?.toMutableMap() ?: mutableMapOf()
            headers["Q-client"] = "Chrome"
            return NetworkResourceResponse(success, netError, netErrorName, httpStatusCode, res.stream, headers)
        }
    }
}
