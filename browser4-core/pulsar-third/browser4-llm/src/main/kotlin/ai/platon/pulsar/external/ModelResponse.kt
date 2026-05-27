package ai.platon.pulsar.external

import java.time.Instant

data class ModelResponse constructor(
    var content: String,
    var state: ResponseState = ResponseState.STOP,
    var tokenUsage: TokenUsage = TokenUsage(),
    var startTime: Instant? = null,
    /**
     * An error message to passed back to the model
     * */
    var modelError: String? = null,
) {
    override fun toString() = content

    companion object {
        val EMPTY = ModelResponse("", ResponseState.OTHER)
        val INTERNAL_ERROR = ModelResponse("(InternalError)", ResponseState.OTHER)
        val LLM_NOT_AVAILABLE = ModelResponse("LLM not available", ResponseState.OTHER)
    }
}
