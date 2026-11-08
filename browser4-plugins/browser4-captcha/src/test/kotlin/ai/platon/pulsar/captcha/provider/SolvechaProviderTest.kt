/**
 * Copyright (c) Vincent Zhang, ivincent.zhang@gmail.com, Platon.AI.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.platon.pulsar.captcha.provider

import ai.platon.pulsar.captcha.CaptchaServiceProvider
import ai.platon.pulsar.captcha.CaptchaSolveRequest
import ai.platon.pulsar.captcha.CaptchaStatus
import ai.platon.pulsar.captcha.CaptchaType
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/** Offline tests: the HTTP layer is replaced by an interceptor that returns canned JSON. */
@DisplayName("Solvecha provider")
class SolvechaProviderTest {

    private val seenPaths = mutableListOf<String>()
    private val seenBodies = mutableListOf<String>()

    private fun provider(vararg replies: String): SolvechaProvider =
        providerWithCodes(*replies.map { 200 to it }.toTypedArray())

    private fun providerWithCodes(vararg replies: Pair<Int, String>): SolvechaProvider {
        val queue = ArrayDeque(replies.toList())
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            seenPaths += req.url.encodedPath
            val buffer = okio.Buffer()
            req.body?.writeTo(buffer)
            seenBodies += buffer.readUtf8()
            val (code, reply) = queue.removeFirst()
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Error")
                .body(reply.toResponseBody("application/json".toMediaType()))
                .build()
        }
        return SolvechaProvider(
            apiKey = "dummy-key",
            pollInterval = 1.milliseconds,
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        )
    }

    private val imageRequest = CaptchaSolveRequest(
        type = CaptchaType.IMAGE,
        siteKey = "",
        imageData = byteArrayOf(1, 2, 3)
    )

    @Test
    fun `solves an image captcha`() = runBlocking {
        val solver = provider(
            """{"errorId":0,"taskId":"t1"}""",
            """{"errorId":0,"status":"processing"}""",
            """{"errorId":0,"status":"ready","solution":{"text":"x7k2p"}}"""
        )
        val solution = solver.solve(imageRequest)

        assertEquals(CaptchaStatus.SOLVED, solution.status)
        assertEquals("x7k2p", solution.token)
        assertEquals("t1", solution.taskId)
        assertEquals(CaptchaServiceProvider.SOLVECHA, solution.provider)
        assertEquals(listOf("/createTask", "/getTaskResult", "/getTaskResult"), seenPaths)
        assertTrue(seenBodies[0].contains("ImageToTextTask"))
        assertTrue(seenBodies[0].contains("AQID"))
    }

    @Test
    fun `token captcha types are unsupported and send no request`() = runBlocking {
        val solver = provider()
        for (type in listOf(CaptchaType.RECAPTCHA_V2, CaptchaType.HCAPTCHA, CaptchaType.TURNSTILE, CaptchaType.FUNCAPTCHA)) {
            val solution = solver.solve(CaptchaSolveRequest(type = type, siteKey = "key", pageUrl = "https://example.com"))
            assertFalse(solution.isSolved)
            assertEquals(CaptchaStatus.FAILED, solution.status)
            assertTrue(solution.error!!.startsWith("Unsupported CAPTCHA type"), solution.error)
        }
        assertTrue(seenPaths.isEmpty())
    }

    @Test
    fun `image request without image data is unsupported`() = runBlocking {
        val solution = provider().solve(CaptchaSolveRequest(type = CaptchaType.IMAGE, siteKey = ""))
        assertEquals(CaptchaStatus.FAILED, solution.status)
    }

    @Test
    fun `maps a createTask error to a failed solution`() = runBlocking {
        val solution = provider(
            """{"errorId":1,"errorCode":"ERROR_KEY_DENIED_ACCESS","errorDescription":"Invalid key"}"""
        ).solve(imageRequest)

        assertEquals(CaptchaStatus.FAILED, solution.status)
        assertEquals("Invalid key", solution.error)
        assertEquals(CaptchaServiceProvider.SOLVECHA, solution.provider)
    }

    @Test
    fun `fails at once on a getTaskResult error`() = runBlocking {
        val solution = provider(
            """{"errorId":0,"taskId":"t1"}""",
            """{"errorId":12,"errorCode":"ERROR_CAPTCHA_UNSOLVABLE","errorDescription":"Unsolvable"}"""
        ).solve(imageRequest)

        assertEquals(CaptchaStatus.FAILED, solution.status)
        assertEquals("Unsolvable", solution.error)
        assertEquals(listOf("/createTask", "/getTaskResult"), seenPaths)
    }

    @Test
    fun `keeps the API error of a 401 reply`() = runBlocking {
        val solution = providerWithCodes(
            401 to """{"errorId":1,"errorCode":"ERROR_KEY_DENIED_ACCESS","errorDescription":"The clientKey is missing or wrong."}"""
        ).solve(imageRequest)

        assertEquals(CaptchaStatus.FAILED, solution.status)
        assertEquals("The clientKey is missing or wrong.", solution.error)
    }

    @Test
    fun `rejects a non-JSON error reply without echoing the body`() = runBlocking {
        val solution = providerWithCodes(502 to "<html>Bad Gateway secret-ish body</html>").solve(imageRequest)

        assertEquals(CaptchaStatus.FAILED, solution.status)
        assertTrue(solution.error!!.startsWith("HTTP 502"), solution.error)
        assertFalse(solution.error!!.contains("Bad Gateway"), solution.error)
    }

    @Test
    fun `reads the balance`() = runBlocking {
        assertEquals(1.5, provider("""{"errorId":0,"balance":1.5}""").balance())
        assertEquals(0.0, provider("""{"errorId":1}""").balance())
    }
}
