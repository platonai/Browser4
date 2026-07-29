package ai.platon.pulsar.rest.api.common

import ai.platon.browser4.boot.autoconfigure.test.PulsarTestContextInitializer
import ai.platon.pulsar.common.ai.llm.PromptTemplate
import ai.platon.pulsar.rest.api.TestHelper.MOCK_PRODUCT_DETAIL_URL
import ai.platon.pulsar.rest.api.config.MockEcServerConfiguration
import ai.platon.pulsar.rest.api.entities.CommandRequest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

const val PLACEHOLDER_PAGE_CONTENT = "page_content"

@SpringBootTest
@ContextConfiguration(initializers = [PulsarTestContextInitializer::class])
@Import(MockEcServerConfiguration::class)
class PromptsTest : MockEcServerTestBase() {
    // Lazy to defer URL resolution until after Spring starts the mock server
    private val request by lazy {
        CommandRequest(
            url = MOCK_PRODUCT_DETAIL_URL,
            onBrowserLaunchedActions = listOf(
                "clear browser cookies",
                "goto the website homepage",
            ),
            onPageReadyActions = listOf(
                "move cursor to the element with id 'title' and click it",
                "scroll to middle",
                "scroll to top",
                "get the text of the element with id 'title'"
            ),
            pageSummaryPrompt = "Tell me something about the page",
            dataExtractionRules = "Extract the title and price of the product",
            uriExtractionRules = "Links containing /dp/"
        )
    }
    private val textContent = "This is a sample product page with title 'Sample Product' and price '$19.99'."

    @Test
        @DisplayName("test pageSummaryPrompt")
    fun testPageSummaryPrompt() {
        // Test basic prompt normalization
        val pageSummaryPrompt = request.pageSummaryPrompt
        assertNotNull(pageSummaryPrompt)
        val prompt = PromptTemplate(pageSummaryPrompt, mapOf(PLACEHOLDER_PAGE_CONTENT to textContent)).render()
        assertTrue { prompt.isNotBlank() }
    }
}
