package ai.platon.pulsar.external

import ai.platon.pulsar.common.ResourceLoader
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.dom.Documents
import ai.platon.pulsar.test.TestResourceUtil
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

@Tag("ExternalResourceDependent")
class ChatModelTests {

    companion object {
        private val url = TestResourceUtil.PRODUCT_DETAIL_URL
        private val args = "-requireSize 200000"
        private val productHtml = ResourceLoader.readString("pages/amazon/B08PP5MSVB.original.htm")
        private val productText = ResourceLoader.readString("prompts/product.txt")
        private val clusterAnalysisPrompt = ResourceLoader.readString("prompts/data-expert/fulltext/prompt.p1723107189.6.remarkable.txt")
        private val conf = ImmutableConfig(loadDefaults = true)
        private val isModelConfigured get() = ChatModelFactory.isModelConfigured(conf)
        private lateinit var model: BrowserChatModel

        @BeforeAll
        @JvmStatic
        fun checkConfiguration() {
            ChatModelTestBase.checkConfiguration()
            model = ChatModelFactory.getOrCreate(conf)
        }
    }

    @BeforeTest
    fun checkIfModelConfigured() {
        Assumptions.assumeTrue(isModelConfigured, "Model is not configured")
    }

    @BeforeTest
    fun checkResource() {
        assertTrue { productHtml.isNotBlank() }
        assertTrue { productText.isNotBlank() }
    }

    @Test
    fun `introduce model`() {
        val prompt = "introduce yourself, what can you do for me?"
        val response = runBlocking { model.call(prompt) }
        printlnPro(response.content)

        assertTrue { response.tokenUsage.inputTokenCount > 0 }
        assertTrue { response.tokenUsage.outputTokenCount > 0 }
        assertTrue { response.tokenUsage.totalTokenCount > 0 }
        assertTrue { response.state == ResponseState.STOP }
    }

    @Test
    fun `should generate answer and return token usage and finish reason stop`() {
        val document = Documents.parse(productHtml, url)

        val prompt = "以下是一个电商网站的网页内容，找出商品标题、商品价格："
        val response = runBlocking { model.call(document, prompt) }
        printlnPro(response.content)

        assertTrue { response.tokenUsage.inputTokenCount > 0 }
        assertTrue { response.tokenUsage.outputTokenCount > 0 }
        assertTrue { response.tokenUsage.totalTokenCount > 0 }
        assertTrue { response.state == ResponseState.STOP }
    }

    @Test
    fun `should generate answer from the partial content of a webpage`() {
        val text = productText
        val prompt = """
以下我将提供一个典型电商网站的商品页面内容，找出商品标题、商品价格和评分，并且以以下格式输出：

从提供的网页内容中，以下是商品的相关信息：

- **商品标题**:
  （这里是商品标题）

- **商品价格**:
   （这里是商品价格）

- **商品评分**:
    （这里是商品评分）
        """.trimIndent()
        val response = runBlocking { model.callUmSm(text, prompt) }
        printlnPro(response.content)

        assertTrue { response.tokenUsage.inputTokenCount > 0 }
        assertTrue { response.tokenUsage.outputTokenCount > 0 }
        assertTrue { response.tokenUsage.totalTokenCount > 0 }
        assertTrue { response.state == ResponseState.STOP }
    }

    @Test
    fun `When ask LLM to analyze cluster then it responses with json`() {
        val response = runBlocking { model.call(clusterAnalysisPrompt) }
        printlnPro(response.content)

        assertTrue { response.tokenUsage.inputTokenCount > 0 }
        assertTrue { response.tokenUsage.outputTokenCount > 0 }
        assertTrue { response.tokenUsage.totalTokenCount > 0 }
        assertTrue { response.state == ResponseState.STOP }
    }
}

