package ai.platon.pulsar.rest.api.service

import ai.platon.pulsar.agentic.tools.high.crawl.common.*
import ai.platon.pulsar.common.LinkExtractors
import ai.platon.pulsar.common.ai.llm.PromptTemplate
import ai.platon.pulsar.common.ai.llm.PromptTemplateLoader
import ai.platon.pulsar.common.serialize.json.JSONExtractor
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.rest.api.entities.CommandRequest
import ai.platon.pulsar.rest.api.entities.CommandStatus
import ai.platon.pulsar.rest.api.entities.PromptRequest
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.session.PulsarSession
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Service

@Service
class ConversationService(
    val session: PulsarSession,
    val loadService: LoadService,
) {
    suspend fun chat(prompt: String): String {
        return session.chat(prompt).content
    }

    suspend fun chat(request: PromptRequest): String {
        request.args = LoadOptions.mergeArgs(request.args, "-refresh")
        val (page, document) = loadService.loadDocument(request)

        val prompt = request.prompt
        if (prompt.isNullOrBlank()) {
            return DEFAULT_INTRODUCE
        }

        return if (page.protocolStatus.isSuccess) {
            session.chat(prompt + "\n" + document.text).content
        } else {
            // Throw?
            page.protocolStatus.toString()
        }
    }

    /**
     * Converts a request string into a PromptRequestL2 object.
     *
     * This method extracts a URL from the request string and uses it to create a PromptRequestL2 object.
     * The URL is replaced with a placeholder in the request string to allow for caching of the result from the LLM.
     *
     * @param request The request string containing a URL.
     * @return A PromptRequestL2 object if a URL is found in the request string, null otherwise.
     * */
    suspend fun normalizePlainCommand(request: String): CommandRequest? {
        if (request.isBlank()) {
            return null
        }

        val urls = LinkExtractors.fromText(request)
        if (urls.isEmpty()) {
            return null
        }
        val url = urls.first()

        val json = convertPlainCommandToJSON(request, url)
        if (json.isNullOrBlank()) {
            return null
        }

        val request2: CommandRequest = pulsarObjectMapper().readValue(json)
        request2.url = url
        return request2
    }

    suspend fun convertPlainCommandToJSON(plainCommand: String, url: String): String? {
        require(URLUtils.isStandard(url)) { "URL must not be blank" }

        // Replace the URL in the request with a placeholder, so the result from the LLM can be cached.
        val processedRequest = plainCommand.replace(url, PLACEHOLDER_URL)

        val resource = "prompts/api/request/command/api_request_plain_command_conversion_prompt.md"
        val prompt = PromptTemplateLoader(
            resource,
            fallbackTemplate = API_REQUEST_PLAIN_COMMAND_CONVERSION_PROMPT,
            variables = mapOf(
                PLACEHOLDER_REQUEST_JSON_COMMAND_TEMPLATE to REQUEST_JSON_COMMAND_TEMPLATE,
                PLACEHOLDER_REQUEST_PLAIN_COMMAND_TEMPLATE to processedRequest
            ),
            reservedVariables = listOf(PLACEHOLDER_URL)
        ).load().render()

        var content = session.chat(prompt).content
        if (content.isBlank()) {
            return null
        }

        content = PromptTemplate(content, mapOf(PLACEHOLDER_URL to url)).render()

        return JSONExtractor.extractJsonBlocks(content).firstOrNull()
    }

    val resource = "prompts/api/request/command/convert_response_to_markdown_prompt.md"
    suspend fun convertResponseToMarkdown(jsonResponse: String): String {
        val userMessage = PromptTemplateLoader(
            resource,
            CONVERT_RESPONSE_TO_MARKDOWN_PROMPT,
            mapOf(PLACEHOLDER_JSON_STRING to jsonResponse)
        ).load().render()
        return session.chat(userMessage).content
    }

    suspend fun convertResponseToMarkdown(status: CommandStatus): String {
        val jsonResponse = pulsarObjectMapper().writeValueAsString(status)
        return convertResponseToMarkdown(jsonResponse)
    }
}
