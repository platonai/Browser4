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
package ai.platon.pulsar.captcha.detection

import ai.platon.pulsar.captcha.CaptchaDetectionResult
import ai.platon.pulsar.captcha.CaptchaType
import ai.platon.pulsar.captcha.FakePageDriver
import ai.platon.pulsar.common.config.ImmutableConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Every detector must fill the same metadata keys: issue 123037#8 reported
 * `widgetCount` coming from the reCAPTCHA detector only, while hCaptcha and
 * Turnstile — whose page probes count widgets too — returned an empty map.
 */
@DisplayName("captcha detector metadata")
class CaptchaDetectorMetadataTest {

    private val conf = ImmutableConfig()

    @Test
    @DisplayName("reCAPTCHA reports its widget count")
    fun recaptchaReportsItsWidgetCount() {
        val result = detect(RecaptchaDetector(conf), FakePageDriver.RECAPTCHA_V2)

        assertTrue(result.isPresent)
        assertEquals(CaptchaType.RECAPTCHA_V2, result.captchaType)
        assertEquals("6Le-demo", result.siteKey)
        assertEquals("1", result.metadata[CaptchaDetectionResult.METADATA_WIDGET_COUNT])
    }

    @Test
    @DisplayName("hCaptcha reports its widget count")
    fun hcaptchaReportsItsWidgetCount() {
        val result = detect(HCaptchaDetector(conf), FakePageDriver.HCAPTCHA)

        assertTrue(result.isPresent)
        assertEquals(CaptchaType.HCAPTCHA, result.captchaType)
        assertEquals("2", result.metadata[CaptchaDetectionResult.METADATA_WIDGET_COUNT])
    }

    @Test
    @DisplayName("Turnstile reports its widget count next to its probe extras")
    fun turnstileReportsItsWidgetCount() {
        val result = detect(TurnstileDetector(conf), FakePageDriver.TURNSTILE)

        assertTrue(result.isPresent)
        assertEquals(CaptchaType.TURNSTILE, result.captchaType)
        assertEquals("verify", result.action)
        assertEquals("3", result.metadata[CaptchaDetectionResult.METADATA_WIDGET_COUNT])
        assertEquals("cdata-1", result.metadata["cdata"])
    }

    @Test
    @DisplayName("the image detector reports a widget count as well")
    fun imageDetectorReportsAWidgetCount() {
        val result = detect(ImageCaptchaDetector(conf), FakePageDriver.IMAGE)

        assertTrue(result.isPresent)
        assertEquals(CaptchaType.IMAGE, result.captchaType)
        assertEquals("1", result.metadata[CaptchaDetectionResult.METADATA_WIDGET_COUNT])
        assertEquals("#captcha-img", result.metadata["imageSelector"])
        assertEquals("#captcha-input", result.metadata["inputSelector"])
    }

    @Test
    @DisplayName("a page without a widget reports nothing present and no metadata")
    fun negativePageReportsNoMetadata() {
        val result = detect(HCaptchaDetector(conf), FakePageDriver.NOT_PRESENT)

        assertFalse(result.isPresent)
        assertEquals(CaptchaType.UNKNOWN, result.captchaType)
        assertNull(result.siteKey)
        assertTrue(result.metadata.isEmpty(), "no widget was matched, so no metadata: ${result.metadata}")
    }

    @Test
    @DisplayName("a probe that reports no widget count still fills the key")
    fun missingProbeCountFallsBackToOne() {
        // An older/partial page probe that omits widgetCount must not leave the key
        // out: consumers rely on it being there (issue 123037#8).
        val result = detect(HCaptchaDetector(conf), """{"present":true,"siteKey":"older-probe"}""")

        assertTrue(result.isPresent)
        assertEquals("1", result.metadata[CaptchaDetectionResult.METADATA_WIDGET_COUNT])
    }

    private fun detect(detector: CaptchaDetector, probeAnswer: String): CaptchaDetectionResult {
        val driver = FakePageDriver { probeAnswer }
        return runBlocking { detector.detect(driver.driver) }
    }
}
