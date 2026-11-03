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

import ai.platon.pulsar.captcha.*
import ai.platon.pulsar.common.getLogger
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [Solvecha](https://solvecha.com) CAPTCHA recognition provider.
 *
 * Solvecha solves image and text CAPTCHAs only. It uses a CapSolver-style
 * `createTask` / `getTaskResult` API with the `ImageToTextTask` task type.
 * Token CAPTCHAs (reCAPTCHA, hCaptcha, Turnstile, FunCaptcha) are not supported
 * and return an "Unsupported CAPTCHA type" failure.
 *
 * API documentation: https://solvecha.com/docs
 */
open class SolvechaProvider(
    private val apiKey: String,
    private val solveTimeout: Duration = 120.seconds,
    private val pollInterval: Duration = 1.seconds,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val baseUrl: String = DEFAULT_BASE_URL
) : CaptchaSolver {
    private val logger = getLogger(SolvechaProvider::class)

    override val serviceProvider: CaptchaServiceProvider
        get() = CaptchaServiceProvider.SOLVECHA

    private val json = ObjectMapper()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.solvecha.com"
    }

    override suspend fun solve(request: CaptchaSolveRequest): CaptchaSolution {
        val startTime = System.currentTimeMillis()

        return try {
            val task = buildTask(request) ?: return CaptchaSolution.failed(
                CaptchaServiceProvider.SOLVECHA,
                "Unsupported CAPTCHA type: ${request.type}"
            )

            val createBody = mapOf(
                "clientKey" to apiKey,
                "task" to task
            )
            val createResponse = postJson<Map<String, Any?>>("$baseUrl/createTask", createBody)
            val errorId = createResponse["errorId"]?.toString()?.toIntOrNull() ?: -1
            if (errorId != 0) {
                val errorDesc = createResponse["errorDescription"]?.toString()
                    ?: createResponse["errorCode"]?.toString() ?: "Unknown error"
                return CaptchaSolution.failed(CaptchaServiceProvider.SOLVECHA, errorDesc)
            }

            val taskId = createResponse["taskId"]?.toString()
                ?: return CaptchaSolution.failed(CaptchaServiceProvider.SOLVECHA, "No taskId in response")

            val text = pollForResult(taskId, startTime)

            CaptchaSolution.solved(
                provider = CaptchaServiceProvider.SOLVECHA,
                token = text,
                taskId = taskId,
                solveTimeMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            logger.warn("Solvecha solve failed: {}", e.message)
            CaptchaSolution.failed(
                CaptchaServiceProvider.SOLVECHA,
                e.message ?: "Unknown error",
                System.currentTimeMillis() - startTime
            )
        }
    }

    override suspend fun reportBad(solution: CaptchaSolution) {
        // Solvecha has no "report incorrect" endpoint.
        logger.debug("Bad solution reported for taskId={}", solution.taskId)
    }

    override suspend fun balance(): Double {
        return try {
            val response = postJson<Map<String, Any?>>("$baseUrl/getBalance", mapOf("clientKey" to apiKey))
            response["balance"]?.toString()?.toDoubleOrNull() ?: 0.0
        } catch (e: Exception) {
            logger.warn("Failed to get balance: {}", e.message)
            0.0
        }
    }

    /** Only image CAPTCHAs are supported; every other type returns null. */
    private fun buildTask(request: CaptchaSolveRequest): Map<String, Any?>? {
        if (request.type != CaptchaType.IMAGE) return null
        val imageBase64 = request.imageData?.let { bytes ->
            java.util.Base64.getEncoder().encodeToString(bytes)
        } ?: return null
        return mapOf(
            "type" to "ImageToTextTask",
            "body" to imageBase64
        )
    }

    private suspend fun pollForResult(taskId: String, startTime: Long): String {
        val deadline = startTime + solveTimeout.inWholeMilliseconds

        while (System.currentTimeMillis() < deadline) {
            val body = mapOf("clientKey" to apiKey, "taskId" to taskId)
            val response = postJson<Map<String, Any?>>("$baseUrl/getTaskResult", body)

            val errorId = response["errorId"]?.toString()?.toIntOrNull() ?: 0
            if (errorId != 0) {
                val errorDesc = response["errorDescription"]?.toString()
                    ?: response["errorCode"]?.toString() ?: "Solvecha error $errorId"
                throw IllegalStateException(errorDesc)
            }

            when (val status = response["status"]?.toString() ?: "processing") {
                "ready" -> {
                    @Suppress("UNCHECKED_CAST")
                    val solution = response["solution"] as? Map<String, Any?>
                    return solution?.get("text")?.toString()
                        ?: throw IllegalStateException("No text in solution: $response")
                }
                "processing" -> delay(pollInterval.inWholeMilliseconds.milliseconds)
                else -> {
                    val errorDesc = response["errorDescription"]?.toString() ?: "Unknown status: $status"
                    throw IllegalStateException(errorDesc)
                }
            }
        }

        throw IllegalStateException("Solve timed out after ${solveTimeout.inWholeSeconds}s")
    }

    private suspend inline fun <reified T> postJson(url: String, body: Any): T {
        val bodyJson = json.writeValueAsString(body)
        val requestBody = bodyJson.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Content-Type", "application/json")
            .build()

        return with(httpClient) {
            newCall(request).await().use { response ->
                val text = response.body?.string()
                    ?: throw IllegalStateException("Empty response from $url")
                if (!response.isSuccessful) {
                    // Solvecha answers API errors (e.g. a wrong key) with a 4xx status and a JSON
                    // body that carries errorId/errorDescription; keep those so callers see the reason.
                    val apiError = runCatching { json.readValue<Map<String, Any?>>(text) }.getOrNull()
                    if (apiError?.get("errorId") == null) {
                        throw IllegalStateException("HTTP ${response.code} from $url")
                    }
                }
                json.readValue(text)
            }
        }
    }

    private suspend fun okhttp3.Call.await(): okhttp3.Response {
        val call = this
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            call.enqueue(object : okhttp3.Callback {
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    continuation.resumeWith(Result.success(response))
                }

                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    continuation.resumeWith(Result.failure(e))
                }
            })
        }
    }
}
