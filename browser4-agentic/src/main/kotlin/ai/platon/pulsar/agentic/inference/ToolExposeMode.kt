package ai.platon.pulsar.agentic.inference

import ai.platon.pulsar.common.config.ImmutableConfig

/**
 * Controls how tools are exposed to the LLM during inference.
 *
 * - [TEXT]: Tools rendered as Kotlin-like signatures in the system prompt.
 *   Messages collapsed to two plain strings via [BrowserChatModel.call].
 *   Legacy mode; tool results flow back only through the bounded
 *   previous-step-result message.
 * - [CHAT]: Structured [ChatMessage] lists through
 *   [BrowserChatModel.langChainChat]. Tools still rendered in the system
 *   prompt.  Behaviourally identical to TEXT.
 * - [TOOL_CALLING]: Structured messages + native [ToolSpecification] objects
 *   via [BrowserChatModel.langChainChat] with function-calling protocol.
 *   Tool results feed back into the conversation automatically — **the default**.
 */
enum class ToolExposeMode {
    TEXT,
    CHAT,
    TOOL_CALLING;

    /** True when native (API-level) tool calling is active. */
    val nativeToolCalling: Boolean get() = this == TOOL_CALLING

    /** True when the tool list should be included in the system prompt text. */
    val includeToolListInPrompt: Boolean get() = this != TOOL_CALLING

    companion object {
        /**
         * Parse [ToolExposeMode] from configuration.
         *
         * Reads key `agent.tool.expose.mode`:
         * - `toolCalling` or `langchain4j` (default) → [TOOL_CALLING]
         * - `chat`                                → [CHAT]
         * - `text`                                → [TEXT] (explicit fallback)
         */
        fun from(conf: ImmutableConfig): ToolExposeMode {
            return when (conf.get("agent.tool.expose.mode")?.lowercase()) {
                "text" -> TEXT
                "chat" -> CHAT
                else -> TOOL_CALLING
            }
        }
    }
}
