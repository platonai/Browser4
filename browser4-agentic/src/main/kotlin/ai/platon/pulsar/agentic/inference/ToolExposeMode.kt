package ai.platon.pulsar.agentic.inference

import ai.platon.pulsar.common.config.ImmutableConfig

/**
 * Controls how tools are exposed to the LLM during inference.
 *
 * - [TEXT]: Tools rendered as Kotlin-like signatures in the system prompt.
 *   Messages collapsed to two plain strings via [BrowserChatModel.call].
 *   **This is today's behaviour and the default.**
 * - [CHAT]: Structured [ChatMessage] lists through
 *   [BrowserChatModel.langChainChat]. Tools still rendered in the system
 *   prompt.  Behaviourally identical to TEXT.
 * - [TOOL_CALLING]: Structured messages + native [ToolSpecification] objects
 *   via [BrowserChatModel.langChainChat] with function-calling protocol.
 *   The tool list is omitted from the system prompt.
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
         * - `text` (default)  → [TEXT]
         * - `chat`            → [CHAT]
         * - `toolCalling` or `langchain4j` → [TOOL_CALLING]
         */
        fun from(conf: ImmutableConfig): ToolExposeMode {
            return when (conf.get("agent.tool.expose.mode")?.lowercase()) {
                "chat" -> CHAT
                "toolcalling", "langchain4j" -> TOOL_CALLING
                else -> TEXT
            }
        }
    }
}
