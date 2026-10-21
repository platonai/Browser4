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

import ai.platon.pulsar.agentic.model.TcEvaluate
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.tools.ToolResultTextRenderer
import ai.platon.pulsar.agentic.tools.specs.ToolResultValidator
import ai.platon.pulsar.captcha.CaptchaConfig
import ai.platon.pulsar.captcha.ChainedCaptchaSolver
import ai.platon.pulsar.captcha.FakePageDriver
import ai.platon.pulsar.captcha.detection.ChainedCaptchaDetector
import ai.platon.pulsar.captcha.detection.HCaptchaDetector
import ai.platon.pulsar.captcha.detection.ImageCaptchaDetector
import ai.platon.pulsar.captcha.detection.RecaptchaDetector
import ai.platon.pulsar.captcha.detection.TurnstileDetector
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `captcha.detect` is the plugin's primary tool, and what a client receives is the
 * *rendered* result, not the executor's return value. These tests therefore drive
 * the real executor and the real shared renderer, and assert on the JSON text a
 * client parses — that is where issue 123037#1 (a Kotlin `toString()` wrapped in a
 * `{"type":…,"description":…}` envelope) was visible.
 */
@DisplayName("captcha.detect tool result")
class CaptchaDetectToolResultTest {

    private val conf = ImmutableConfig()

    @Test
    @DisplayName("a detected widget is published as JSON, never as a toString envelope")
    fun detectedWidgetIsPublishedAsJson() {
        val json = detect(page(hcaptcha = FakePageDriver.HCAPTCHA))

        assertTrue(json.isObject, "the tool result must be a JSON object: $json")
        assertTrue(json["isPresent"].asBoolean())
        assertEquals("HCAPTCHA", json["captchaType"].asText())
        assertEquals("a5f74b19-demo", json["siteKey"].asText())
        assertEquals(".h-captcha", json["selector"].asText())
        assertTrue(json["action"].isNull, "hCaptcha has no action")
        assertEquals(0.95, json["confidence"].asDouble(), 0.0001)
        assertFalse(json.has("description"), "the toString envelope must be gone: $json")
        assertEquals(1, json["attempts"].asInt())
        assertEquals(0, json["waitMillis"].asLong())
        assertFalse(json["waited"].asBoolean())
        assertTrue(json.has("elapsedMillis"), "detection latency is reported: $json")
    }

    @Test
    @DisplayName("a page without a widget still carries every documented key as null/zero")
    fun negativePageKeepsEveryDocumentedKey() {
        val json = detect(page())

        assertFalse(json["isPresent"].asBoolean())
        assertEquals("UNKNOWN", json["captchaType"].asText())
        assertTrue(json["siteKey"].isNull, "siteKey must be present and null: $json")
        assertTrue(json["action"].isNull, "action must be present and null: $json")
        assertTrue(json["selector"].isNull, "selector must be present and null: $json")
        assertEquals(0.0, json["confidence"].asDouble(), 0.0001)
        assertEquals(0, json["metadata"]["widgetCount"].asInt())
        assertEquals(0, json["waitMillis"].asLong())
        assertEquals(1, json["attempts"].asInt())
        assertFalse(json["waited"].asBoolean())
    }

    @Test
    @DisplayName("every detector reports the widget count its page probe matched")
    fun everyDetectorReportsAWidgetCount() {
        val recaptcha = detect(page(recaptcha = FakePageDriver.RECAPTCHA_V2))
        assertEquals("RECAPTCHA_V2", recaptcha["captchaType"].asText())
        assertEquals(1, recaptcha["metadata"]["widgetCount"].asInt())

        val hcaptcha = detect(page(hcaptcha = FakePageDriver.HCAPTCHA))
        assertEquals(2, hcaptcha["metadata"]["widgetCount"].asInt())

        val turnstile = detect(page(turnstile = FakePageDriver.TURNSTILE))
        assertEquals("TURNSTILE", turnstile["captchaType"].asText())
        assertEquals(3, turnstile["metadata"]["widgetCount"].asInt())
        assertEquals("cdata-1", turnstile["metadata"]["cdata"].asText())

        val image = detect(page(image = FakePageDriver.IMAGE))
        assertEquals("IMAGE", image["captchaType"].asText())
        assertEquals(1, image["metadata"]["widgetCount"].asInt())
        assertEquals("#captcha-img", image["metadata"]["imageSelector"].asText())
    }

    @Test
    @DisplayName("waitMillis keeps polling until a lazily rendered widget appears")
    fun waitMillisPollsUntilALazyWidgetAppears() {
        var probes = 0
        val driver = FakePageDriver { script ->
            if (!script.contains("hcaptcha")) {
                FakePageDriver.NOT_PRESENT
            } else {
                ++probes
                // The widget is rendered by the page's own JavaScript: the first
                // probe misses it, every later probe finds it.
                if (probes == 1) FakePageDriver.NOT_PRESENT else FakePageDriver.HCAPTCHA
            }
        }

        val json = detect(driver, mutableMapOf("waitMillis" to 2_000L))

        assertTrue(json["isPresent"].asBoolean(), "the retry must find the widget: $json")
        assertEquals("HCAPTCHA", json["captchaType"].asText())
        assertEquals(2_000, json["waitMillis"].asLong())
        assertTrue(json["attempts"].asInt() >= 2, "the chain had to poll: $json")
        assertTrue(json["waited"].asBoolean(), "waited must be true when the chain polled: $json")
    }

    @Test
    @DisplayName("an expired wait window reports a negative result with its timing")
    fun expiredWaitWindowReportsTiming() {
        val json = detect(page(), mutableMapOf("waitMillis" to 150L))

        assertFalse(json["isPresent"].asBoolean())
        assertTrue(json["attempts"].asInt() >= 2, "the chain must have polled: $json")
        assertTrue(json["waited"].asBoolean())
        assertTrue(
            json["elapsedMillis"].asLong() >= 150,
            "a waited detection reports the whole window: $json"
        )
    }

    @Test
    @DisplayName("a wait window outside 0..30000 fails the call instead of hanging")
    fun waitWindowOutsideTheSupportedRangeIsRejected() {
        val negative = evaluate(page(), mutableMapOf("waitMillis" to -1L))
        assertNotNull(negative.exception, "a negative wait window must fail")
        assertTrue(
            negative.exception!!.cause!!.message!!.contains("waitMillis"),
            "the error must name the argument: ${negative.exception!!.cause!!.message}"
        )

        val oversized = evaluate(page(), mutableMapOf("waitMillis" to 30_001L))
        assertNotNull(oversized.exception, "an unbounded wait window must fail")
        assertTrue(
            oversized.exception!!.cause!!.message!!.contains("30000"),
            "the error must state the bound: ${oversized.exception!!.cause!!.message}"
        )
    }

    @Test
    @DisplayName("every payload shape honours the outputSchema the tool declares")
    fun payloadHonoursTheDeclaredOutputSchema() {
        val executor = executor(page(hcaptcha = FakePageDriver.HCAPTCHA))
        val spec = executor.getToolSpecs()["detect"]!!
        assertNotNull(spec.outputSchema, "detect must declare its result contract")

        val payloads = listOf(
            renderText(executor, page(hcaptcha = FakePageDriver.HCAPTCHA)),
            renderText(executor, page()),
            renderText(executor, page(turnstile = FakePageDriver.TURNSTILE))
        )

        payloads.forEach { text ->
            val parsed = ToolResultValidator.parse(text)
            assertNotNull(parsed, "the payload must parse as JSON: $text")
            assertEquals(
                emptyList<ToolResultValidator.Issue>(),
                ToolResultValidator.validate(spec, parsed),
                "payload violates the declared schema: $text"
            )
        }
    }

    private fun page(
        recaptcha: String = FakePageDriver.NOT_PRESENT,
        hcaptcha: String = FakePageDriver.NOT_PRESENT,
        turnstile: String = FakePageDriver.NOT_PRESENT,
        image: String = FakePageDriver.NOT_PRESENT
    ) = FakePageDriver(FakePageDriver.page(recaptcha, hcaptcha, turnstile, image))

    private fun executor(driver: FakePageDriver): CaptchaToolExecutor {
        val detector = ChainedCaptchaDetector(conf)
        detector.addLast(RecaptchaDetector(conf))
        detector.addLast(HCaptchaDetector(conf))
        detector.addLast(TurnstileDetector(conf))
        detector.addLast(ImageCaptchaDetector(conf))

        return CaptchaToolExecutor(
            captchaSolver = ChainedCaptchaSolver(conf),
            captchaDetector = detector,
            tokenInjectors = emptyMap(),
            config = CaptchaConfig()
        )
    }

    private fun detect(
        driver: FakePageDriver,
        args: MutableMap<String, Any?> = mutableMapOf()
    ): JsonNode {
        val evaluate = evaluate(executor(driver), driver, args)
        assertNull(evaluate.exception, "the tool call failed: ${evaluate.exception?.cause?.message}")
        return pulsarObjectMapper().readTree(ToolResultTextRenderer.render(evaluate))
    }

    private fun renderText(
        executor: CaptchaToolExecutor,
        driver: FakePageDriver,
        args: MutableMap<String, Any?> = mutableMapOf()
    ): String = ToolResultTextRenderer.render(evaluate(executor, driver, args))

    private fun evaluate(
        driver: FakePageDriver,
        args: MutableMap<String, Any?>
    ): TcEvaluate = evaluate(executor(driver), driver, args)

    private fun evaluate(
        executor: CaptchaToolExecutor,
        driver: FakePageDriver,
        args: MutableMap<String, Any?>
    ): TcEvaluate = runBlocking {
        executor.callFunctionOn(ToolCall("captcha", "detect", args), driver.driver)
    }
}
