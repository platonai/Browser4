package ai.platon.pulsar.my.tools

import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.api.WebDriver
import kotlin.reflect.KClass
import ai.platon.pulsar.my.service.WordcountService

open class WordcountToolExecutor(private val service: WordcountService) : AbstractToolExecutor() {
    override val domain = "wordcount"

    override val receiverClass: KClass<*> = WebDriver::class

    init {
        toolSpec["getWordCount"] = ToolSpec(
            domain = domain,
            method = "getWordCount",
            arguments = listOf(ToolSpec.Arg("text", "String")),
            returnType = "WordCountResult",
            description = "Count words, characters, non-whitespace characters and lines in a plain text."
        )
    }

    override suspend fun callFunctionOn(
        domain: String,
        functionName: String,
        args: Map<String, Any?>,
        receiver: Any
    ): Any? {
        require(domain == this.domain)
        if (functionName == "getWordCount") {
            val text = args["text"] as? String ?: throw IllegalArgumentException("text is required")
            return service.getWordCount(text)
        }
        throw IllegalArgumentException("Unknown function: $functionName")
    }
}
