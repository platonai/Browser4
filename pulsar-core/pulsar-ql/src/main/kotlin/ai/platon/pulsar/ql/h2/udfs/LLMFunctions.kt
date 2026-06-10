package ai.platon.pulsar.ql.h2.udfs

import ai.platon.pulsar.common.ExperimentalApi
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.serialize.json.JSONExtractor
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.ql.common.annotation.UDFGroup
import ai.platon.pulsar.ql.common.annotation.UDFunction
import ai.platon.pulsar.ql.common.types.ValueDom
import ai.platon.pulsar.ql.common.types.ValueStringJSON
import ai.platon.pulsar.skeleton.context.PulsarContexts
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

const val DATA_EXTRACTION_RULES_PLACEHOLDER = "{DATA_EXTRACTION_RULES}"

const val LLM_UDF_EXTRACT_PROMPT =
    """
Extract the specified fields from the given HTML content and return the result as a JSON object.

Use the following format:

```json
{
  "field1": "value1",
  "field2": "value2"
}
```

Data extraction rules:
{DATA_EXTRACTION_RULES}

Ensure all extracted values are clean and trimmed. If a field cannot be found, set its value to null.

"""

@Suppress("unused")
@ExperimentalApi
@UDFGroup(namespace = "LLM")
object LLMFunctions {
    private val logger = getLogger(this)
    private val session get() = PulsarContexts.getOrCreateSession()
    private val llmFailureWarnings = AtomicInteger(0)

    @JvmStatic
    @UDFunction(description = "Get the LLM model name")
    fun modelName(): String {
        return session.configuration["llm.name"] ?: "unknown"
    }

    @JvmStatic
    @UDFunction(description = "Chat with the LLM model")
    fun chat(prompt: String): String {
        return try {
            runBlocking { session.chat(prompt).content }
        } catch (e: Exception) {
            // Handle interruption gracefully
            when (e) {
                is InterruptedException, is kotlinx.coroutines.CancellationException -> {
                    logger.info("Chat interrupted, returning empty result")
                    ""
                }
                else -> throw e
            }
        }
    }

    @JvmStatic
    @UDFunction(description = "Chat with the LLM model")
    fun chat(dom: ValueDom, prompt: String): String {
        return try {
            runBlocking { session.chat(prompt, dom.element).content }
        } catch (e: Exception) {
            // Handle interruption gracefully
            when (e) {
                is InterruptedException, is kotlinx.coroutines.CancellationException -> {
                    logger.info("Chat interrupted, returning empty result")
                    ""
                }
                else -> throw e
            }
        }
    }

    @JvmStatic
    @UDFunction(description = "Extract fields from the content of the given DOM with the LLM model")
    fun extract(dom: ValueDom, dataExtractionRules: String): ValueStringJSON {
        val result = extractInternal(dom.element.text(), dataExtractionRules)
        return ValueStringJSON.get(pulsarObjectMapper().writeValueAsString(result), Map::class.qualifiedName)
    }

    fun extractInternal(domContent: String, dataExtractionRules: String): Map<String, String> {
        val prompt = LLM_UDF_EXTRACT_PROMPT.replace(DATA_EXTRACTION_RULES_PLACEHOLDER, dataExtractionRules)
        val content = try {
            runBlocking { session.chat(prompt + "\n" + domContent).content }
        } catch (e: Exception) {
            // Handle interruption gracefully
            when (e) {
                is InterruptedException, is kotlinx.coroutines.CancellationException -> {
                    logger.info("Extraction interrupted, returning empty result")
                    return emptyMap()
                }
                else -> throw e
            }
        }

        val jsonBlocks = JSONExtractor.extractJsonBlocks(content)
        if (jsonBlocks.isEmpty()) {
            if (llmFailureWarnings.get() % 50 == 0) {
                logger.warn("{}th failure to extract a JSON from LLM's response | {}", llmFailureWarnings.get().inc(), content)
            }
            llmFailureWarnings.incrementAndGet()
            return mapOf()
        }

        return try {
            val jsonBlock = jsonBlocks[0]
            val result: Map<String, String> = pulsarObjectMapper().readValue(jsonBlock)
            result
        } catch (e: Exception) {
            logger.warn("Failed to parse JSON from LLM's response | $content", e)
            mapOf()
        }
    }
}
