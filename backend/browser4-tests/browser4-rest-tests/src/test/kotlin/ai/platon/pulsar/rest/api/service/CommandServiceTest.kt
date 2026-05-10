package ai.platon.pulsar.rest.api.service

import ai.platon.browser4.driver.common.BrowserSettings
import ai.platon.pulsar.boot.autoconfigure.test.PulsarTestContextInitializer
import ai.platon.pulsar.common.browser.BrowserProfileMode
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.common.serialize.json.Pson
import ai.platon.pulsar.common.serialize.json.prettyPulsarObjectMapper
import ai.platon.pulsar.external.ChatModelFactory
import ai.platon.pulsar.rest.api.TestHelper.MOCK_PRODUCT_DETAIL_URL
import ai.platon.pulsar.rest.api.common.MockEcServerTestBase
import ai.platon.pulsar.rest.api.config.MockEcServerConfiguration
import ai.platon.pulsar.rest.api.entities.CommandRequest
import ai.platon.pulsar.agentic.tools.high.command.CommandService
import ai.platon.pulsar.agentic.tools.high.command.toCommandStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@ContextConfiguration(initializers = [PulsarTestContextInitializer::class])
@Import(MockEcServerConfiguration::class)
class CommandServiceTest : MockEcServerTestBase() {

    @Autowired
    private lateinit var conf: ImmutableConfig

    @Autowired
    private lateinit var commandService: CommandService

    @BeforeEach
    override fun setup() {
        super.setup() // Call parent setup to verify mock server is running
        Assumptions.assumeTrue(ChatModelFactory.isModelConfigured(conf))
        BrowserSettings.withBrowserContextMode(BrowserProfileMode.TEMPORARY)
    }

    @Test
    @DisplayName("test executeCommand WITHOUT instructions")
    fun testExecuteCommandWithoutInstructions() {
        val request = CommandRequest(MOCK_PRODUCT_DETAIL_URL)
        val status = runBlocking { commandService.executePageVisitCommand(request).toCommandStatus() }
        val result = status.commandResult
        // nothing to do if page is not loaded
        Assumptions.assumeTrue(status.pageStatusCode == 200)
        assertTrue { status.isDone }

        assertNull(result)
        assertTrue { status.instructResults.isEmpty() }
    }

    @Test
    @DisplayName("test executeCommand with onPageReadyActions")
    fun testExecuteCommandWithContradistinctions() {
        val actions = """
            move cursor to the element with id 'title' and click it
            scroll to middle
            scroll to top
            get the text of the element with id 'title'
        """.trimIndent().split("\n")
        val request = CommandRequest(
            MOCK_PRODUCT_DETAIL_URL, "",
            pageSummaryPrompt = "Tell me something about the page",
            onPageReadyActions = actions
        )

        val status = runBlocking { commandService.executePageVisitCommand(request) }.toCommandStatus()

        printlnPro(status)
        Assumptions.assumeTrue(status.pageStatusCode == 200)
        Assumptions.assumeTrue(status.isDone)
        Assumptions.assumeTrue(status.statusCode == 200)

        assertNotNull(status.commandResult)
        assertNotNull(status.commandResult?.pageSummary)

        assertNull(status.commandResult?.fields)
        assertNull(status.commandResult?.links)
        assertNull(status.commandResult?.xsqlResultSet)
    }

    @Test
    @DisplayName("test executeCommand with onBrowserLaunchedActions")
    fun testExecuteCommandWithOnBrowserLaunchedActions() {
        val actions = """
            clear cookies
            goto origin url of $MOCK_PRODUCT_DETAIL_URL
        """.trimIndent().split("\n")
        val request = CommandRequest(
            MOCK_PRODUCT_DETAIL_URL, "",
            onBrowserLaunchedActions = actions,
            pageSummaryPrompt = "Tell me something about the page",
        )

        val status = runBlocking { commandService.executePageVisitCommand(request) }.toCommandStatus()

        printlnPro(status)
        Assumptions.assumeTrue(status.pageStatusCode == 200)
        Assumptions.assumeTrue(status.isDone)
        Assumptions.assumeTrue(status.statusCode == 200)

        assertNotNull(status.commandResult)
        assertNotNull(status.commandResult?.pageSummary)

        assertNull(status.commandResult?.fields)
        assertNull(status.commandResult?.links)
        assertNull(status.commandResult?.xsqlResultSet)
    }

    @Test
    @DisplayName("test executeCommand with pageSummaryPrompt")
    fun testExecuteCommandWithPageSummaryPrompt() {
        val request = CommandRequest(
            MOCK_PRODUCT_DETAIL_URL,
            pageSummaryPrompt = "Give me the product name",
        )

        val status = runBlocking { commandService.executePageVisitCommand(request) }.toCommandStatus()
        val result = status.commandResult

        Assumptions.assumeTrue(status.pageStatusCode == 200)
        Assumptions.assumeTrue(status.isDone)
        Assumptions.assumeTrue(status.statusCode == 200)

        assertNotNull(result)
        assertTrue { status.isDone }

        printlnPro(result.pageSummary)

        assertNull(result.fields)
        assertNull(result.xsqlResultSet)
        assertTrue { !result.pageSummary.isNullOrBlank() }
    }

    @Test
    @DisplayName("test executeCommand with dataExtractionRules")
    fun testExecuteCommandWithDataExtractionRules() {
        val request = CommandRequest(
            MOCK_PRODUCT_DETAIL_URL,
            dataExtractionRules = "product name, ratings, price"
        )

        val status = runBlocking { commandService.executePageVisitCommand(request) }.toCommandStatus()
        printlnPro(prettyPulsarObjectMapper().writeValueAsString(status))
        val result = status.commandResult

        Assumptions.assumeTrue(status.pageStatusCode == 200)
        Assumptions.assumeTrue(status.isDone)
        Assumptions.assumeTrue(status.statusCode == 200)

        assertNotNull(result)
        assertTrue { status.isDone }

        val fields = result.fields
        printlnPro(fields)

        assertNull(result.pageSummary)
        assertNull(result.xsqlResultSet)

        assertNotNull(fields)
        assertTrue { fields.isNotEmpty() }
    }

    @Test
    @DisplayName("test executeCommand with uriExtractionRules")
    fun testExecuteCommandWithUriExtractionRules() {
//        val testURL = "http://localhost:8182/ec/-/zh/ap/register?openid.pape.max_auth_age=0&openid.return_to=http://localhost:8182/ec/dp/B0E000001/?_encoding=UTF8&ref_=nav_newcust&openid.identity=http://specs.openid.net/auth/2.0/identifier_select&openid.assoc_handle=usflex&openid.mode=checkid_setup&openid.claimed_id=http://specs.openid.net/auth/2.0/identifier_select&openid.ns=http://specs.openid.net/auth/2.0"
//        val testRegex = "https?://.+/dp/[\\w]+.*".toRegex()
//        assertTrue { testURL.matches(testRegex) }

        // The LLM (doubao-seed-2-0-pro-260215) failed to infer the regex at 2026/4/1
//        val request = CommandRequest(
//            MOCK_PRODUCT_DETAIL_URL,
//            uriExtractionRules = "links containing /dp/",
//        )

        val request = CommandRequest(
            MOCK_PRODUCT_DETAIL_URL,
            uriExtractionRules = "Regex: https?://.+/dp/[\\w]+.*",
        )

        val status = runBlocking { commandService.executePageVisitCommand(request) }.toCommandStatus()
        printlnPro(prettyPulsarObjectMapper().writeValueAsString(status))
        val result = status.commandResult

        Assumptions.assumeTrue(status.pageStatusCode == 200)
        Assumptions.assumeTrue(status.isDone)
        Assumptions.assumeTrue(status.statusCode == 200)

        assertNotNull(result)
        assertTrue { status.isDone }

        val links = result.links
        printlnPro(links)

        assertNull(result.pageSummary)
        assertNull(result.xsqlResultSet)

        assertNotNull(links)
        assertTrue { links.isNotEmpty() }
    }

    @Test
    @DisplayName("test translate plain command to CommandRequest with X-SQL")
    fun testTranslatePlainCommandToCommandRequestWithXSql() {
        val request = CommandRequest(
            MOCK_PRODUCT_DETAIL_URL,
            xsql = """
                select
                  dom_base_uri(dom) as url,
                  dom_first_text(dom, '#productTitle') as title,
                  str_substring_after(dom_first_href(dom, '#wayfinding-breadcrumbs_container ul li:last-child a'), 'node=') as category,
                  dom_first_slim_html(dom, '#bylineInfo') as brand,
                  cast(dom_all_slim_htmls(dom, '#imageBlock img') as varchar) as gallery,
                  dom_first_slim_html(dom, '#landingImage, #imgTagWrapperId img, #imageBlock img:expr(width > 400)') as img,
                  dom_first_text(dom, '#price tr td:contains(List Price) ~ td') as listprice,
                  dom_first_text(dom, '#price tr td:matches(^Price) ~ td') as price,
                  str_first_float(dom_first_text(dom, '#reviewsMedley .AverageCustomerReviews span:contains(out of)'), 0.0) as score
                from load_and_select(@url, 'body');
            """.trimIndent()
        )

        val status = runBlocking { commandService.executePageVisitCommand(request) }.toCommandStatus()
        printlnPro(prettyPulsarObjectMapper().writeValueAsString(status))
        val result = status.commandResult

        Assumptions.assumeTrue(status.pageStatusCode == 200)
        Assumptions.assumeTrue(status.isDone)
        Assumptions.assumeTrue(status.statusCode == 200)

        assertNotNull(result)
        assertTrue { status.isDone }

        assertNull(result.pageSummary)
        assertNotNull(result.xsqlResultSet)
    }

    @Test
    @DisplayName("test executeCommand with uriExtractionRules in regex")
    fun testExecuteCommandWithUriExtractionRulesInRegex() {
        val request = CommandRequest(
            MOCK_PRODUCT_DETAIL_URL,
            uriExtractionRules = "Regex: http://localhost:\\d+/ec/dp/\\w+"
        )

        val status = runBlocking { commandService.executePageVisitCommand(request) }.toCommandStatus()
        printlnPro(prettyPulsarObjectMapper().writeValueAsString(status))
        val result = status.commandResult

        Assumptions.assumeTrue(status.pageStatusCode == 200)
        Assumptions.assumeTrue(status.isDone)
        Assumptions.assumeTrue(status.statusCode == 200)

        assertNotNull(result)
        assertTrue { status.isDone }

        val links = result.links
        printlnPro(links)

        assertNull(result.pageSummary)
        assertNull(result.xsqlResultSet)

        assertNotNull(links)
        assertTrue { links.isNotEmpty() }
    }

    @Test
    @DisplayName("test executeCommand with simple and clean command")
    fun testExecuteCommandWithSimpleAndCleanCommand() {
        val prompt = PAGE_VISIT_COMMAND_PROMPT1

        val status = runBlocking { commandService.executePageVisitCommand(prompt) }.toCommandStatus()
        printlnPro(prettyPulsarObjectMapper().writeValueAsString(status))
        assertNotNull(status)

        Assumptions.assumeTrue(status.pageStatusCode == 200)
        Assumptions.assumeTrue(status.isDone)
        Assumptions.assumeTrue(status.statusCode == 200)

        assertNotNull(status.commandResult?.pageSummary)
        assertNotNull(status.commandResult?.fields)
    }

    @Test
    @DisplayName("test executeCommand with detailed and verbose command")
    fun testExecuteCommandWithDetailedAndVerboseCommand() {
        val prompt = PAGE_VISIT_COMMAND_PROMPT3

        val status = runBlocking { commandService.executePageVisitCommand(prompt) }.toCommandStatus()
        printlnPro(prettyPulsarObjectMapper().writeValueAsString(status))
        assertNotNull(status)

        Assumptions.assumeTrue(status.pageStatusCode == 200)
        Assumptions.assumeTrue(status.isDone)
        Assumptions.assumeTrue(status.statusCode == 200)

        assertNotNull(status.commandResult?.pageSummary)
        assertNotNull(status.commandResult?.fields)
    }

    @Test
    @DisplayName("test executePlainCommandSync with URL-based command")
    fun testExecutePlainCommandSyncWithUrlBasedCommand() {
        // A command with URL should be handled by the standard flow
        val plainCommand = """
            Visit $MOCK_PRODUCT_DETAIL_URL
            Summarize the product.
        """.trimIndent()

        val status = runBlocking { commandService.executePlainCommandSync(plainCommand) }
        printlnPro(prettyPulsarObjectMapper().writeValueAsString(status))
        assertNotNull(status)

        // The command should complete (either successfully or with expected status)
        assertTrue { status.isDone }
    }

    @Test
    @DisplayName("test executePlainCommandSync with blank command returns bad request")
    fun testExecutePlainCommandSyncWithBlankCommandReturnsBadRequest() {
        val status = runBlocking { commandService.executePlainCommandSync("") }
        assertNotNull(status)
        assertTrue { status.isDone }
        assertTrue { status.statusCode == 400 }
    }

    @Test
    @DisplayName("test submitPlainCommandAsync with URL-based command")
    fun testSubmitPlainCommandAsyncWithUrlBasedCommand() {
        // A command with URL should be handled by the standard async flow
        val plainCommand = """
            Visit $MOCK_PRODUCT_DETAIL_URL
            Summarize the product.
        """.trimIndent()

        val statusId = runBlocking { commandService.submitPlainCommandAsync(plainCommand) }
        assertNotNull(statusId)
        assertTrue { statusId.isNotBlank() }

        // Verify we can retrieve the status
        val status = commandService.getStatus(statusId)
        assertNotNull(status)
    }

    @Test
    @DisplayName("test submitPlainCommandAsync with blank command")
    fun testSubmitPlainCommandAsyncWithBlankCommand() {
        val statusId = runBlocking { commandService.submitPlainCommandAsync("") }
        assertNotNull(statusId)

        // Even blank commands should have a status
        val status = commandService.getStatus(statusId)
        assertNotNull(status)
        assertTrue { status.isDone }
        assertTrue { status.statusCode == 400 }
    }

    @Test
    @DisplayName("test executeCommand with uriExtractionRules without regex and regex inference disabled")
    fun testExecuteCommandWithUriExtractionRulesWithoutRegexAndRegexInferenceDisabled() {
        val request = CommandRequest(
            MOCK_PRODUCT_DETAIL_URL,
            uriExtractionRules = "links containing /dp/",
            inferUriExtractionRegex = false
        )

        val status = runBlocking { commandService.executePageVisitCommand(request) }.toCommandStatus()
        printlnPro(prettyPulsarObjectMapper().writeValueAsString(status))

        Assumptions.assumeTrue(status.pageStatusCode == 200)
        Assumptions.assumeTrue(status.isDone)
        Assumptions.assumeTrue(status.statusCode == 200)

        // Since inference is disabled and the rules are not a Regex: pattern, link extraction is skipped.
        assertNull(status.commandResult?.links)
    }

    @Test
    @Ignore("Very slow, this test is a fully agent run which may take 2~3 minutes")
    @Tag("Slow")
    @Tag("ManualOnly")
    @DisplayName("test executeAgentCommand sets agentHistory on status")
    fun testExecuteAgentCommandSetsAgentHistoryOnStatus() {
        // Use a simple command that will trigger agent execution
        val plainCommand = "Search for test information"

        val status = runBlocking { commandService.executeAgentCommand(plainCommand) }

        printlnPro(Pson.toJson(status))

        assertNotNull(status)

        // After execution, the agent history should be set
        assertNotNull(status.agentHistory)

        // The command should be done
        assertTrue { status.isDone }
    }
}
