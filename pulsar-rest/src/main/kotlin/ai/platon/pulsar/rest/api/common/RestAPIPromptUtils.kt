package ai.platon.pulsar.rest.api.common

import ai.platon.pulsar.common.ai.llm.PromptTemplateLoader
import ai.platon.pulsar.rest.api.service.CommandService.Companion.MIN_USER_MESSAGE_LENGTH

object RestAPIPromptUtils {

    fun normalizePageSummaryPrompt(message: String?): String? {
        val message2 = normalizeUserMessage(message) ?: return null

        val suffix = """

### Page Content:

{PLACEHOLDER_PAGE_CONTENT}

        """.trimIndent()

        return "$message2\n$suffix"
    }

    fun normalizeDataExtractionRules(message: String?): String? {
        val message2 = normalizeUserMessage(message) ?: return null

        val suffix = """

### Rules:
- According the request, extract fields from the page content
- Your result should be a JSON object, where the key is the field name and the value is the field value.

### Page Content:

{PLACEHOLDER_PAGE_CONTENT}

        """.trimIndent()

        return "$message2\n$suffix"
    }

    fun normalizeURIExtractionRules(urlDescription: String?): String? {
        if (true == urlDescription?.startsWith("Regex:")) {
            return urlDescription
        }

        val description = normalizeUserMessage(urlDescription) ?: return null
        if (description.isBlank()) {
            return null
        }

        val resource = "prompts/api/request/command/convert_uri_description_to_regex_prompt.md"
        return PromptTemplateLoader(
            resource,
            fallbackTemplate = CONVERT_URI_DESCRIPTION_TO_REGEX_PROMPT,
            variables = mapOf(PLACEHOLDER_URI_DESCRIPTION to description)
        ).load().render()
    }

    fun normalizeURIExtractionRegex(message: String?): Regex? {
        var message2 = normalizeUserMessage(message) ?: return null
        return try {
            message2 = message2.removePrefix("Regex:").trim()
            message2.toRegex()
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Normalizes the user message by trimming whitespace and ensuring it has a minimum length.
     *
     * deprecated, the link extraction rules should be a regex pattern
     * */
    fun normalizeURIExtractionRulesDeprecated(message: String?): String? {
        val message2 = normalizeUserMessage(message) ?: return null

        val suffix = """

### Rules:
- According the request, extract links from the page content
- The link should be a valid URL
- No duplicate links
- Your result should be a JSON object, where the key is the link and the value is the link text.

### Page Content:

{PLACEHOLDER_PAGE_CONTENT}

        """.trimIndent()

        return "$message2\n$suffix"
    }

    /**
     * Normalizes the user message by trimming whitespace and ensuring it has a minimum length.
     * */
    fun normalizeUserMessage(message: String?): String? {
        return message?.trim()?.takeIf { it.length > MIN_USER_MESSAGE_LENGTH }
    }

}
