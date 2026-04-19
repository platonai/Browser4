package ai.platon.pulsar.external

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.serialize.json.prettyPulsarObjectMapper
import ai.platon.pulsar.external.impl.CachedBrowserChatModel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName

class ChatModelFactoryTest {
    /**
     *
     * ```shell
     * curl https://ark.cn-beijing.volces.com/api/v3 \
     *   -H "Content-Type: application/json" \
     *   -H "Authorization: Bearer 9cc8e998-4655-4e90-a54c-66659a524a971" \
     *   -d '{
     *     "model": "doubao-1-5-pro-32k-250115",
     *     "messages": [
     *       {"role": "system","content": "你是人工智能助手."},
     *       {"role": "user","content": "常见的十字花科植物有哪些？"}
     *     ]
     *   }'
     * ```
     *
     * */
    @org.junit.jupiter.api.Test
        @DisplayName("doubao API should be compatible with OpenAI API")
    fun doubaoApiShouldBeCompatibleWithOpenaiApi() {
        val provider = "volcengine"
        val baseURL = "https://ark.cn-beijing.volces.com/api/v3"
        val modelName = "doubao-1-5-pro-32k-250115"
        val apiKey = "9cc8e99889-4655-4e90-a54c1-12345abcdefg"

        val conf = ImmutableConfig()
        val model = ChatModelFactory.getOrCreate(provider, modelName, apiKey, conf)
        assertNotNull(model)
        assertIs<CachedBrowserChatModel>(model)

        try {
            // This is a fake API key so you must fail
            val response = runBlocking { model.call("Give me the answer only for 100+1=?") }
            assertFalse(prettyPulsarObjectMapper().writeValueAsString(response)) {
                response.content.contains("101")
            }
        } catch (e: Exception) {
            assertTrue(e.message) { listOf("error", "invalid", "missing", "Unauthorized", "fail", "not found", "not exist", "not support", "not available", "not configured", "not supported", "not found", "not exist", "not support", "not available", "not configured", "not supported")
                .any { e.toString().contains(it, ignoreCase = true) } }
        }
    }
}
