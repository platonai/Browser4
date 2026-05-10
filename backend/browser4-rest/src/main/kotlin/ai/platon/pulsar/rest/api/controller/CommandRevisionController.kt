package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.tools.high.crawl.common.*
import ai.platon.pulsar.common.LinkExtractors
import ai.platon.pulsar.common.ai.llm.PromptTemplateLoader
import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.rest.api.service.ConversationService
import kotlinx.coroutines.runBlocking
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*

@RestController
@CrossOrigin
@RequestMapping(
    "api/command-revisions",
    consumes = [MediaType.ALL_VALUE],
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class CommandRevisionController(
    private val conversationService: ConversationService,
) {
    @PostMapping(produces = [MediaType.TEXT_PLAIN_VALUE])
    suspend fun create(@RequestBody prompt: String): String {
        var urls = LinkExtractors.fromText(prompt)
        if (urls.isEmpty()) {
            urls = setOf(AppConstants.EXAMPLE_URL)
        }

        val json = conversationService.convertPlainCommandToJSON(prompt, urls.first()) ?: return prompt

        val resource = "prompts/api/request/command/command_revision_template.md"
        val message = PromptTemplateLoader(
            resource,
            fallbackTemplate = COMMAND_REVISION_TEMPLATE,
            variables = mapOf(
                PLACEHOLDER_REQUEST_JSON_COMMAND_TEMPLATE to REQUEST_JSON_COMMAND_TEMPLATE,
                PLACEHOLDER_REQUEST_PLAIN_COMMAND_TEMPLATE to REQUEST_PLAIN_COMMAND_TEMPLATE,
                PLACEHOLDER_JSON_VALUE to json
            )
        ).load().render()

        return runBlocking { conversationService.chat(message) }
    }
}
