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
package ai.platon.pulsar.captcha.tools

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.captcha.*
import ai.platon.pulsar.captcha.detection.ChainedCaptchaDetector
import ai.platon.pulsar.captcha.inject.CaptchaTokenInjector
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.core.api.WebDriver
import kotlin.reflect.KClass

/**
 * LLM agent tool executor for the `captcha` domain.
 *
 * Provides AI agents with the ability to:
 * - `captcha.detect` — detect CAPTCHA on the current page
 * - `captcha.solve` — solve a CAPTCHA by type and siteKey
 * - `captcha.solveImage` — solve an image CAPTCHA from base64 data
 * - `captcha.getBalance` — check the solver's account balance
 *
 * Register as:
 * ```kotlin
 * agentToolManager.registerCustomToolExecutor(CaptchaToolExecutor(...))
 * ```
 */
open class CaptchaToolExecutor(
    private val captchaSolver: ChainedCaptchaSolver,
    private val captchaDetector: ChainedCaptchaDetector,
    private val tokenInjectors: Map<CaptchaType, CaptchaTokenInjector>,
    private val config: CaptchaConfig
) : AbstractToolExecutor() {
    private val logger = getLogger(CaptchaToolExecutor::class)

    override val domain = "captcha"

    override val receiverClass: KClass<*> = WebDriver::class

    init {
        toolSpec["detect"] = ToolSpec(
            domain = domain,
            method = "detect",
            arguments = emptyList(),
            returnType = "CaptchaDetectionResult",
            description = "Detect CAPTCHA on the current page. Returns the type and siteKey if found.",
            help = """
                captcha.detect()

                Detects known CAPTCHA types (reCAPTCHA v2/v3, hCaptcha, Cloudflare Turnstile, image) on the current page.
                Returns a CaptchaDetectionResult with isPresent, captchaType, siteKey, and confidence.
            """.trimIndent()
        )

        toolSpec["solve"] = ToolSpec(
            domain = domain,
            method = "solve",
            arguments = listOf(
                ToolSpec.Arg("type", "String"),
                ToolSpec.Arg("siteKey", "String"),
                ToolSpec.Arg("pageUrl", "String?", "null")
            ),
            returnType = "CaptchaSolution",
            description = "Solve a CAPTCHA with the given type and siteKey. Type can be RECAPTCHA_V2, RECAPTCHA_V3, HCAPTCHA, TURNSTILE, or IMAGE.",
            help = """
                captcha.solve(type: String, siteKey: String)
                captcha.solve(type: String, siteKey: String, pageUrl: String?)

                Submits the CAPTCHA to the configured solving service and returns the solution token.
                Supported types: RECAPTCHA_V2, RECAPTCHA_V2_INVISIBLE, RECAPTCHA_V3, HCAPTCHA, TURNSTILE, IMAGE, FUNCAPTCHA.
            """.trimIndent()
        )

        toolSpec["solveImage"] = ToolSpec(
            domain = domain,
            method = "solveImage",
            arguments = listOf(
                ToolSpec.Arg("base64Image", "String"),
                ToolSpec.Arg("caseSensitive", "Boolean?", "false")
            ),
            returnType = "CaptchaSolution",
            description = "Solve an image CAPTCHA by providing base64-encoded image data.",
            help = """
                captcha.solveImage(base64Image: String)
                captcha.solveImage(base64Image: String, caseSensitive: Boolean?)

                Returns the recognized text from the image CAPTCHA.
            """.trimIndent()
        )

        toolSpec["getBalance"] = ToolSpec(
            domain = domain,
            method = "getBalance",
            arguments = emptyList(),
            returnType = "Double",
            description = "Get the current account balance of the CAPTCHA solving service.",
            help = """
                captcha.getBalance()

                Returns the USD balance of the primary configured CAPTCHA solving service.
            """.trimIndent()
        )
    }

    override suspend fun callFunctionOn(
        domain: String,
        functionName: String,
        args: Map<String, Any?>,
        receiver: Any
    ): Any? {
        val driver = receiver as? WebDriver

        return when (functionName) {
            "detect" -> {
                requireNotNull(driver) { "detect requires a WebDriver receiver" }
                captchaDetector.detect(driver)
            }

            "solve" -> {
                val typeStr = paramString(args, "type", functionName)!!
                val captchaType = try {
                    CaptchaType.valueOf(typeStr.uppercase())
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException(
                        "Unknown CAPTCHA type: $typeStr. Supported: ${CaptchaType.entries.filter { it != CaptchaType.UNKNOWN }}"
                    )
                }
                val siteKey = paramString(args, "siteKey", functionName)!!
                val pageUrl = paramString(
                    args, "pageUrl", functionName,
                    required = false, default = driver?.currentUrl() ?: ""
                )!!

                val request = CaptchaSolveRequest(
                    type = captchaType,
                    siteKey = siteKey,
                    pageUrl = pageUrl,
                    proxy = config.solveProxy
                )
                captchaSolver.solve(request)
            }

            "solveImage" -> {
                val base64Image = paramString(args, "base64Image", functionName)!!
                val caseSensitive = paramBool(
                    args, "caseSensitive", functionName,
                    required = false, default = false
                ) ?: false

                val imageBytes = java.util.Base64.getDecoder().decode(base64Image)
                val request = CaptchaSolveRequest(
                    type = CaptchaType.IMAGE,
                    siteKey = "",
                    imageData = imageBytes,
                    metadata = mapOf("caseSensitive" to caseSensitive.toString()),
                    proxy = config.solveProxy
                )
                captchaSolver.solve(request)
            }

            "getBalance" -> {
                captchaSolver.balance()
            }

            else -> throw IllegalArgumentException("Unsupported captcha method: $functionName. Use detect, solve, solveImage, or getBalance.")
        }
    }
}
