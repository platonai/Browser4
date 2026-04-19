package ai.platon.pulsar.rest.api.service

import ai.platon.pulsar.boot.autoconfigure.test.PulsarTestContextInitializer
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.external.ChatModelFactory
import ai.platon.pulsar.rest.api.TestHelper.MOCK_PRODUCT_DETAIL_URL
import ai.platon.pulsar.rest.api.common.MockEcServerTestBase
import ai.platon.pulsar.rest.api.config.MockEcServerConfiguration
import ai.platon.pulsar.rest.api.entities.PromptRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName

@Tag("Slow")
@SpringBootTest
@ContextConfiguration(initializers = [PulsarTestContextInitializer::class])
@Import(MockEcServerConfiguration::class)
class ExtractServiceTest : MockEcServerTestBase() {

    @Autowired
    private lateinit var conf: ImmutableConfig

    @Autowired
    private lateinit var extractService: ExtractService

    @BeforeEach
    override fun setup() {
        super.setup()
        Assumptions.assumeTrue(ChatModelFactory.isModelConfigured(conf))
    }

    @Test
        @DisplayName("test extract")
    fun testExtract() {
        val request = PromptRequest(MOCK_PRODUCT_DETAIL_URL, "title, price, images")
        val response = runBlocking { extractService.extract(request) }
        printlnPro(response)
        assertTrue { response.isNotEmpty() }
    }

    @Test
        @DisplayName("test extract with actions")
    fun testExtractWithActions() {
        val actions = """
            move cursor to the element with id 'title' and click it
            scroll to middle
            scroll to top
            get the text of the element with id 'title'
        """.trimIndent().split("\n")
        val request = PromptRequest(
            MOCK_PRODUCT_DETAIL_URL, "title, price, images", "", actions = actions
        )

        val response = runBlocking { extractService.extract(request) }
        printlnPro(response)
        assertTrue { response.isNotEmpty() }
    }
}

