package ai.platon.pulsar.skeleton.llm

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.external.BrowserChatModel
import ai.platon.pulsar.external.ChatModelSettings
import ai.platon.pulsar.external.ModelResponse
import ai.platon.pulsar.external.ResponseState
import ai.platon.pulsar.external.TokenUsage as PulsarTokenUsage
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.output.FinishReason
import dev.langchain4j.model.output.TokenUsage as LangChainTokenUsage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test-only [BrowserChatModel] that never talks to a real LLM.
 *
 * Every method reads a local file and converts its content into a
 * [ModelResponse] or LangChain4j [ChatResponse].  The outer test is
 * responsible for preparing the file(s) that stand in for the LLM reply.
 *
 * Two source layouts are supported:
 * - A single file: every call returns the same content (the test can rewrite
 *   the file between calls).
 * - A directory: files are consumed in lexicographic name order, one per call
 *   (e.g. `000.json`, `001.json`, ...), which lets a test script a full
 *   multi-turn agent conversation.
 *
 * Plain text files are returned as the model's text.  JSON files may specify
 * `text`, `toolCalls`, `finishReason`, token counts and `modelError`:
 * ```json
 * {
 *   "text": "done",
 *   "toolCalls": [
 *     {"id": "t1", "name": "system.taskComplete", "arguments": {"summary": "ok"}}
 *   ],
 *   "finishReason": "TOOL_EXECUTION",
 *   "inputTokens": 10,
 *   "outputTokens": 5
 * }
 * ```
 */
class FileBackedChatModel(
    conf: ImmutableConfig,
    private val source: Path,
) : BrowserChatModel {
    private val logger = getLogger(this)
    private val objectMapper = jacksonObjectMapper()
    private val nextIndex = AtomicInteger(0)
    private var currentMarker: String? = null

    override val settings: ChatModelSettings = ChatModelSettings(conf)

    override suspend fun call(userMessage: String, category: String?): ModelResponse =
        readMockResponse().toModelResponse()

    override suspend fun callSmUm(
        systemMessage: String,
        userMessage: String,
        imageUrl: String?,
        b64Image: String?,
        mediaType: String?,
        category: String?,
    ): ModelResponse = readMockResponse().toModelResponse()

    override suspend fun callUmSm(
        userMessage: String,
        systemMessage: String,
        imageUrl: String?,
        b64Image: String?,
        mediaType: String?,
        category: String?,
    ): ModelResponse = readMockResponse().toModelResponse()

    override suspend fun langChainChat(chatRequest: ChatRequest, category: String?): ChatResponse =
        readMockResponse().toChatResponse()

    override suspend fun langChainChat(
        vararg messages: ChatMessage,
        category: String?,
    ): ChatResponse = readMockResponse().toChatResponse()

    override suspend fun langChainChat(
        messages: List<ChatMessage>,
        category: String?,
    ): ChatResponse = readMockResponse().toChatResponse()

    override fun close() = Unit

    private fun readMockResponse(): MockResponse {
        val path = nextSourceFile()
        val content = Files.readString(path)
        logger.info("Mock LLM response from {} ({} bytes)", path, content.length)
        return MockResponse.parse(content, objectMapper)
    }

    private fun nextSourceFile(): Path {
        if (!Files.isDirectory(source)) {
            require(Files.isRegularFile(source)) {
                "BROWSER4_TEST_LLM_RESPONSE_FILE must point to a readable file, got: $source"
            }
            return source
        }

        syncDirectoryMarker()

        val files = mutableListOf<String>()
        Files.newDirectoryStream(source).use { stream ->
            stream.forEach { path ->
                if (Files.isRegularFile(path) && path.fileName.toString().endsWith(".json")) {
                    files += path.fileName.toString()
                }
            }
        }
        files.sort()
        if (files.isEmpty()) {
            throw IllegalStateException(
                "Mock LLM response directory is empty: $source"
            )
        }

        val index = nextIndex.getAndIncrement()
        if (index >= files.size) {
            throw IllegalStateException(
                "Mock LLM response directory exhausted: consumed ${files.size} of ${files.size} file(s) in $source"
            )
        }
        return source.resolve(files[index])
    }

    /**
     * When the outer test switches the response directory to a new scenario
     * (same path, different marker file), reset the sequence counter so a
     * long-lived backend can serve a fresh scripted conversation.
     */
    private fun syncDirectoryMarker() {
        val markerPath = source.resolve("scenario.txt")
        val marker = if (Files.isRegularFile(markerPath)) {
            Files.readString(markerPath).trim().ifBlank { null }
        } else {
            null
        }
        if (marker != currentMarker) {
            nextIndex.set(0)
            currentMarker = marker
        }
    }

    private fun MockResponse.toModelResponse(): ModelResponse = ModelResponse(
        content = text,
        state = finishReason.toResponseState(),
        tokenUsage = PulsarTokenUsage(inputTokens, outputTokens, inputTokens + outputTokens),
        modelError = modelError,
    )

    private fun MockResponse.toChatResponse(): ChatResponse = ChatResponse.builder()
        .aiMessage(
            if (toolCalls.isEmpty()) {
                AiMessage.from(text)
            } else {
                AiMessage.from(text, toolCalls)
            }
        )
        .tokenUsage(LangChainTokenUsage(inputTokens, outputTokens, inputTokens + outputTokens))
        .finishReason(finishReason)
        .build()

    private data class MockResponse(
        val text: String,
        val toolCalls: List<ToolExecutionRequest>,
        val finishReason: FinishReason,
        val inputTokens: Int,
        val outputTokens: Int,
        val modelError: String?,
    ) {
        companion object {
            fun parse(content: String, objectMapper: com.fasterxml.jackson.databind.ObjectMapper): MockResponse {
                val trimmed = content.trim()
                if (trimmed.isEmpty()) {
                    return MockResponse("", emptyList(), FinishReason.STOP, 0, 0, null)
                }

                val root = try {
                    objectMapper.readTree(trimmed)
                } catch (e: Exception) {
                    // Not JSON — treat the whole file as the assistant's text.
                    return MockResponse(trimmed, emptyList(), FinishReason.STOP, 0, 0, null)
                }
                if (root == null || !root.isObject) {
                    return MockResponse(trimmed, emptyList(), FinishReason.STOP, 0, 0, null)
                }

                val text = root.textOr("text")
                    ?: root.textOr("content")
                    ?: ""
                val toolCalls = root.toolCalls()
                val finishReason = root.finishReason(toolCalls.isNotEmpty())
                val inputTokens = root.intOr("inputTokens", 0)
                val outputTokens = root.intOr("outputTokens", 0)
                val modelError = root.nullableTextOr("modelError")
                return MockResponse(text, toolCalls, finishReason, inputTokens, outputTokens, modelError)
            }
        }
    }

}

private fun JsonNode.textOr(field: String): String? {
    val node = get(field) ?: return null
    return if (node.isMissingNode || node.isNull) null else node.asText()
}

private fun JsonNode.nullableTextOr(field: String): String? {
    val node = get(field) ?: return null
    return if (node.isMissingNode || node.isNull) null else node.asText()
}

private fun JsonNode.intOr(field: String, default: Int): Int {
    val node = get(field) ?: return default
    return if (node.isMissingNode || node.isNull) default else node.asInt(default)
}

private fun JsonNode.toolCalls(): List<ToolExecutionRequest> {
    val node = get("toolCalls") ?: return emptyList()
    if (node.isMissingNode || node.isNull || !node.isArray) return emptyList()
    return node.mapIndexed { index, call ->
        val name = call.get("name")?.asText()?.trim().orEmpty()
        require(name.isNotBlank()) {
            "Mock LLM toolCalls[$index] is missing a non-blank 'name'"
        }
        val id = call.get("id")?.asText()?.trim().orEmpty()
            .ifBlank { "mock-tool-$index" }
        val argumentsNode = call.get("arguments")
        val arguments = when {
            argumentsNode == null || argumentsNode.isMissingNode || argumentsNode.isNull -> "{}"
            argumentsNode.isTextual -> argumentsNode.asText()
            else -> argumentsNode.toString()
        }
        ToolExecutionRequest.builder()
            .id(id)
            .name(name)
            .arguments(arguments)
            .build()
    }
}

private fun JsonNode.finishReason(defaultToToolExecution: Boolean): FinishReason {
    val raw = get("finishReason")?.asText()?.trim().orEmpty()
    return if (raw.isBlank()) {
        if (defaultToToolExecution) FinishReason.TOOL_EXECUTION else FinishReason.STOP
    } else {
        runCatching { FinishReason.valueOf(raw.uppercase()) }
            .getOrDefault(FinishReason.STOP)
    }
}

private fun FinishReason.toResponseState(): ResponseState = when (this) {
    FinishReason.STOP -> ResponseState.STOP
    FinishReason.LENGTH -> ResponseState.LENGTH
    FinishReason.TOOL_EXECUTION -> ResponseState.TOOL_EXECUTION
    FinishReason.CONTENT_FILTER -> ResponseState.CONTENT_FILTER
    FinishReason.OTHER -> ResponseState.OTHER
}
