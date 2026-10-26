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

import ai.platon.pulsar.agentic.model.ToolExample
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
            arguments = listOf(
                ToolSpec.Arg(
                    "waitMillis", "Long?", "0",
                    "Wait window in milliseconds for a lazily rendered widget: the page is re-probed " +
                        "every captcha.detect.poll.interval.ms until a CAPTCHA appears or the window " +
                        "expires. 0 (default) probes once. Maximum $MAX_DETECT_WAIT_MILLIS ms."
                )
            ),
            returnType = "Map<String, Any?>",
            description = "Detect CAPTCHA on the current page. Returns a JSON object with isPresent, " +
                "captchaType, siteKey, confidence, metadata and the detection timing.",
            help = """
                captcha.detect()
                captcha.detect(waitMillis: Long? = 0)

                Detects known CAPTCHA types (reCAPTCHA v2/v3, hCaptcha, Cloudflare Turnstile, image) on the current page.

                Returns a JSON object whose keys are always present:
                - isPresent: whether a CAPTCHA was found
                - captchaType: RECAPTCHA_V2, RECAPTCHA_V3, HCAPTCHA, TURNSTILE, IMAGE, ... (UNKNOWN when not present)
                - siteKey: the widget's data-sitekey, null when the page has none
                - action: reCAPTCHA v3/Turnstile action, null when the widget does not declare one
                - selector: CSS selector of the widget, null when not present
                - confidence: 0.0 - 1.0
                - metadata: object, always present; widgetCount is the number of widgets the page probe
                  matched, plus probe extras such as cdata (Turnstile) or imageSelector/inputSelector (image)
                - waitMillis: the wait window this call allowed (0 = single probe)
                - attempts: how many times the detector chain ran
                - waited: true when the first probe found nothing and the detector retried after waiting
                - elapsedMillis: wall-clock cost of the detection itself, in milliseconds

                Latency: each attempt is a single JavaScript evaluation per detector (typically a few
                milliseconds); the call overhead around it belongs to the client/transport, not the plugin.
                The first detect right after navigation can take seconds when the page's own JavaScript
                still occupies the main thread. Use waitMillis when the widget may render late, otherwise
                a call that arrives too early reports isPresent=false with attempts=1 and waited=false.
            """.trimIndent(),
            examples = listOf(
                ToolExample(title = "probe the page once", runnable = true),
                ToolExample(
                    title = "wait up to 5s for a lazily rendered widget",
                    args = mapOf("waitMillis" to "5000"),
                    notes = "polls every captcha.detect.poll.interval.ms; the result reports attempts/waited"
                )
            ),
            outputSchema = CaptchaToolPayload.DETECT_OUTPUT_SCHEMA
        )

        toolSpec["solve"] = ToolSpec(
            domain = domain,
            method = "solve",
            arguments = listOf(
                ToolSpec.Arg("type", "String"),
                ToolSpec.Arg("siteKey", "String"),
                ToolSpec.Arg("pageUrl", "String?", "null")
            ),
            returnType = "Map<String, Any?>",
            description = "Solve a CAPTCHA with the given type and siteKey. Type can be RECAPTCHA_V2, RECAPTCHA_V3, HCAPTCHA, TURNSTILE, or IMAGE.",
            help = """
                captcha.solve(type: String, siteKey: String)
                captcha.solve(type: String, siteKey: String, pageUrl: String?)

                Submits the CAPTCHA to the configured solving service and fails the call when no provider
                is configured or the solve does not succeed. Supported types: RECAPTCHA_V2,
                RECAPTCHA_V2_INVISIBLE, RECAPTCHA_V3, HCAPTCHA, TURNSTILE, IMAGE, FUNCAPTCHA.

                Returns a JSON object whose keys are always present:
                - token: the solved response token, ready for the widget's response field
                - taskId: the provider's task id (for reporting a bad solve), null when the provider has none
                - status: SOLVED
                - provider: CAPSOLVER, TWO_CAPTCHA, ANTI_CAPTCHA or SOLVECHA
                - solveTimeMs: how long the provider took, in milliseconds
                - error: null on success, the provider's message when the solve failed
            """.trimIndent(),
            outputSchema = CaptchaToolPayload.SOLUTION_OUTPUT_SCHEMA
        )

        toolSpec["solveImage"] = ToolSpec(
            domain = domain,
            method = "solveImage",
            arguments = listOf(
                ToolSpec.Arg("base64Image", "String"),
                ToolSpec.Arg("caseSensitive", "Boolean?", "false")
            ),
            returnType = "Map<String, Any?>",
            description = "Solve an image CAPTCHA by providing base64-encoded image data.",
            help = """
                captcha.solveImage(base64Image: String)
                captcha.solveImage(base64Image: String, caseSensitive: Boolean?)

                Returns the recognized text of the image CAPTCHA in the same JSON object `captcha.solve`
                returns: token (the recognized text), taskId, status, provider, solveTimeMs and error
                (null on success).
            """.trimIndent(),
            outputSchema = CaptchaToolPayload.SOLUTION_OUTPUT_SCHEMA
        )

        toolSpec["getBalance"] = ToolSpec(
            domain = domain,
            method = "getBalance",
            arguments = emptyList(),
            returnType = "Map<String, Any?>",
            description = "Get the account balance of the configured CAPTCHA solving service(s), or a 'configured: false' marker when no provider is set up.",
            help = """
                captcha.getBalance()

                Returns a JSON object with:
                - configured: whether at least one solving provider is registered
                - balance: the USD balance averaged across all configured providers, null when unconfigured
                - providers: array of the configured provider names, [] when unconfigured
                - message: configuration hint, sent only when no provider is registered
            """.trimIndent(),
            outputSchema = CaptchaToolPayload.BALANCE_OUTPUT_SCHEMA
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
                val waitMillis = paramLong(
                    args, "waitMillis", functionName,
                    required = false, default = 0L
                ) ?: 0L

                // Publish a JSON payload, never the result data class: the tool-result
                // renderer has no mapping for a domain object and would fall back to
                // `{"type": ..., "description": "<toString()>"}` (issue 123037#1).
                CaptchaToolPayload.detect(captchaDetector.detect(driver, waitMillis))
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
                // Same reason as `detect`: publish a JSON payload, never the solution
                // data class, which would reach the client as a toString envelope.
                CaptchaToolPayload.solution(solveOrThrow(request))
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
                CaptchaToolPayload.solution(solveOrThrow(request))
            }

            "getBalance" -> {
                if (captchaSolver.size == 0) {
                    // `balance`/`providers` must reach the client as `null`/`[]`
                    // instead of vanishing: an unconfigured install then cannot be
                    // mistaken for a renamed field (issue 123037#5).
                    CaptchaToolPayload.balance(
                        configured = false,
                        balance = null,
                        providers = emptyList(),
                        message = NO_PROVIDER_MESSAGE
                    )
                } else {
                    CaptchaToolPayload.balance(
                        configured = true,
                        balance = captchaSolver.balance(),
                        providers = captchaSolver.providers.map { it.name }
                    )
                }
            }

            else -> throw IllegalArgumentException("Unsupported captcha method: $functionName. Use detect, solve, solveImage, or getBalance.")
        }
    }

    /**
     * Solve [request], failing loudly when no provider is configured or the
     * solving attempt did not succeed. Throwing here lets the MCP layer mark
     * the tool result as `isError=true` instead of returning a FAILED payload
     * that looks like a normal (non-error) response.
     */
    private suspend fun solveOrThrow(request: CaptchaSolveRequest): CaptchaSolution {
        check(captchaSolver.size > 0) { NO_PROVIDER_MESSAGE }

        val solution = captchaSolver.solve(request)
        if (!solution.isSolved) {
            throw IllegalStateException(
                "CAPTCHA solving failed (status=${solution.status}, provider=${solution.provider}): ${solution.error ?: "no error details"}"
            )
        }
        return solution
    }

    companion object {
        const val NO_PROVIDER_MESSAGE =
            "No CAPTCHA solving provider configured — set captcha.capsolver.api.key (or captcha.twocaptcha.api.key / captcha.anticaptcha.api.key)"

        /**
         * Largest wait window `detect` accepts, mirroring
         * [ChainedCaptchaDetector.MAX_WAIT_MILLIS] so the documented bound and the
         * enforced one cannot drift apart.
         */
        private const val MAX_DETECT_WAIT_MILLIS = ChainedCaptchaDetector.MAX_WAIT_MILLIS
    }
}
