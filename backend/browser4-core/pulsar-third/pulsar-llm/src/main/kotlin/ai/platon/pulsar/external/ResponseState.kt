package ai.platon.pulsar.external

/**
 * The reason why a model call finished.
 */
enum class ResponseState {
    /**
     * The model call finished because the model decided the request was done.
     */
    STOP,

    /**
     * The call finished because the token length was reached.
     */
    LENGTH,

    /**
     * The call finished signalling a need for tool execution.
     */
    TOOL_EXECUTION,

    /**
     * The call finished signalling a need for content filtering.
     */
    CONTENT_FILTER,

    /**
     * The call finished for some other reason.
     */
    OTHER
}
