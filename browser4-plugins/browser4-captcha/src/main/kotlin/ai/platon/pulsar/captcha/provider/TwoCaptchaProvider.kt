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
 * [2Captcha](https://2captcha.com) CAPTCHA solving provider.
 *
 * 2Captcha is the industry standard with the widest CAPTCHA-type coverage
 * and a simple REST API.
 *
 * API documentation: https://2captcha.com/api-docs
 */
open class TwoCaptchaProvider(
    private val apiKey: String,
    private val solveTimeout: Duration = 120.seconds,
    private val pollInterval: Duration = 1.seconds,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) : CaptchaSolver {
    private val logger = getLogger(TwoCaptchaProvider::class)

    override val serviceProvider: CaptchaServiceProvider
        get() = CaptchaServiceProvider.TWO_CAPTCHA

    private val json = ObjectMapper()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        private const val CREATE_TASK_URL = "https://api.2captcha.com/createTask"
        private const val GET_TASK_URL = "https://api.2captcha.com/getTaskResult"
        private const val BALANCE_URL = "https://api.2captcha.com/getBalance"
    }

    override suspend fun solve(request: CaptchaSolveRequest): CaptchaSolution {
        val startTime = System.currentTimeMillis()

        return try {
            val task = buildTask(request) ?: return CaptchaSolution.failed(
                CaptchaServiceProvider.TWO_CAPTCHA,
                "Unsupported CAPTCHA type: ${request.type}"
            )

            val createBody = mapOf(
                "clientKey" to apiKey,
                "task" to task
            )
            val createResponse = postJson<Map<String, Any?>>(CREATE_TASK_URL, createBody)
            val errorId = createResponse["errorId"]?.toString()?.toIntOrNull() ?: -1
            if (errorId != 0) {
                val errorDesc = createResponse["errorDescription"]?.toString() ?: "Unknown error"
                return CaptchaSolution.failed(CaptchaServiceProvider.TWO_CAPTCHA, errorDesc)
            }

            val taskId = createResponse["taskId"]?.toString()
                ?: return CaptchaSolution.failed(CaptchaServiceProvider.TWO_CAPTCHA, "No taskId in response")

            val token = pollForResult(taskId, startTime)
            val elapsedMs = System.currentTimeMillis() - startTime
            CaptchaSolution.solved(
                provider = CaptchaServiceProvider.TWO_CAPTCHA,
                token = token,
                taskId = taskId,
                solveTimeMs = elapsedMs
            )
        } catch (e: Exception) {
            logger.warn("2Captcha solve failed: {}", e.message)
            CaptchaSolution.failed(
                CaptchaServiceProvider.TWO_CAPTCHA,
                e.message ?: "Unknown error",
                System.currentTimeMillis() - startTime
            )
        }
    }

    override suspend fun reportBad(solution: CaptchaSolution) {
        val taskId = solution.taskId ?: return
        try {
            val body = mapOf(
                "clientKey" to apiKey,
                "taskId" to taskId
            )
            postJson<Map<String, Any?>>("https://api.2captcha.com/reportIncorrect", body)
            logger.debug("Bad solution reported for taskId={}", taskId)
        } catch (e: Exception) {
            logger.warn("Failed to report bad solution: {}", e.message)
        }
    }

    override suspend fun balance(): Double {
        return try {
            val body = mapOf("clientKey" to apiKey)
            val response = postJson<Map<String, Any?>>(BALANCE_URL, body)
            response["balance"]?.toString()?.toDoubleOrNull() ?: 0.0
        } catch (e: Exception) {
            logger.warn("Failed to get balance: {}", e.message)
            0.0
        }
    }

    private fun buildTask(request: CaptchaSolveRequest): Map<String, Any?>? {
        return when (request.type) {
            CaptchaType.RECAPTCHA_V2,
            CaptchaType.RECAPTCHA_V2_INVISIBLE -> mapOf(
                "type" to "RecaptchaV2TaskProxyless",
                "websiteURL" to request.pageUrl,
                "websiteKey" to request.siteKey,
                "isInvisible" to (request.type == CaptchaType.RECAPTCHA_V2_INVISIBLE)
            )

            CaptchaType.RECAPTCHA_V3 -> mapOf(
                "type" to "RecaptchaV3TaskProxyless",
                "websiteURL" to request.pageUrl,
                "websiteKey" to request.siteKey,
                "minScore" to (request.metadata["minScore"]?.toDoubleOrNull() ?: 0.3),
                "pageAction" to (request.metadata["action"] ?: "verify")
            )

            CaptchaType.HCAPTCHA -> mapOf(
                "type" to "HCaptchaTaskProxyless",
                "websiteURL" to request.pageUrl,
                "websiteKey" to request.siteKey
            )

            CaptchaType.TURNSTILE -> mapOf(
                "type" to "TurnstileTaskProxyless",
                "websiteURL" to request.pageUrl,
                "websiteKey" to request.siteKey
            )

            CaptchaType.IMAGE -> {
                val imageBase64 = request.imageData?.let { bytes ->
                    java.util.Base64.getEncoder().encodeToString(bytes)
                } ?: return null
                mapOf(
                    "type" to "ImageToTextTask",
                    "body" to imageBase64,
                    "case" to (request.metadata["caseSensitive"]?.toBoolean() ?: false)
                )
            }

            CaptchaType.FUNCAPTCHA -> mapOf(
                "type" to "FunCaptchaTaskProxyless",
                "websiteURL" to request.pageUrl,
                "websitePublicKey" to request.siteKey
            )

            else -> null
        }
    }

    private suspend fun pollForResult(taskId: String, startTime: Long): String {
        val deadline = startTime + solveTimeout.inWholeMilliseconds

        while (System.currentTimeMillis() < deadline) {
            val body = mapOf("clientKey" to apiKey, "taskId" to taskId)
            val response = postJson<Map<String, Any?>>(GET_TASK_URL, body)

            val status = response["status"]?.toString() ?: "processing"
            when (status) {
                "ready" -> {
                    @Suppress("UNCHECKED_CAST")
                    val solution = response["solution"] as? Map<String, Any?>
                    val token = solution?.get("gRecaptchaResponse")?.toString()
                        ?: solution?.get("token")?.toString()
                        ?: solution?.get("text")?.toString()
                        ?: throw IllegalStateException("No token in solution: $response")
                    return token
                }
                "processing" -> {
                    delay(pollInterval.inWholeMilliseconds.milliseconds)
                }
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
            val response = newCall(request).await()
            response.body?.use { body ->
                json.readValue(body.string())
            } ?: throw IllegalStateException("Empty response from $url")
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
