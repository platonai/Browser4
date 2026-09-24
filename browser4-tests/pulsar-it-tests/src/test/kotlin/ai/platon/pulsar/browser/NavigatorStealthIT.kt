package ai.platon.pulsar.browser

import ai.platon.pulsar.WebDriverTestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Integration tests for the navigator surface a bot detector reads.
 *
 * These pin the browser4-side contract, not a detector's opinion:
 *
 * 1. **The headless token must never reach the wire.** A CDP-driven headless Chrome advertises
 *    `HeadlessChrome/<version>` by default, which is a one-line bot verdict (SannySoft
 *    `HEADCHR_UA`, Incolumitas `intoli.userAgent`, BrowserScan's aggregate "Robot" badge,
 *    "You are a bot!" on deviceandbrowserinfo) and contradicts the `Sec-CH-UA*` client hints the
 *    same browser sends. The launch-time `--user-agent` fix lives in
 *    [ai.platon.pulsar.chrome.Browser4UserAgent]; this test proves it reaches the page.
 * 2. **Values browser4 does not own must stay plausible.** `navigator.deviceMemory` and
 *    `navigator.maxTouchPoints` come from Chrome and the host, not from browser4. The assertions
 *    below encode the coherence rules real Chrome satisfies, so a future override that produces a
 *    raw host value (31.7 GB) or a touch count without a coarse pointer is caught here.
 */
@Tag("Integration")
@Tag("RequiresBrowser")
class NavigatorStealthIT : WebDriverTestBase() {

    private val headlessToken = Regex("Headless", RegexOption.IGNORE_CASE)

    /** The reduced product token Chrome reports after user-agent reduction: `Chrome/153.0.0.0`. */
    private val reducedChromeToken = Regex("""Chrome/\d+\.0\.0\.0""")

    @Test
    @DisplayName("the user agent never advertises the headless token")
    fun userAgentNeverAdvertisesTheHeadlessToken() = runWebDriverTest(simpleDomURL) { driver ->
        val userAgent = driver.evaluate("navigator.userAgent", "")
        println("navigator.userAgent = $userAgent")

        assertTrue(userAgent.isNotEmpty(), "navigator.userAgent must not be empty")
        assertFalse(
            headlessToken.containsMatchIn(userAgent),
            "the user agent still advertises the headless token: $userAgent"
        )
        assertTrue(
            reducedChromeToken.containsMatchIn(userAgent),
            "expected Chrome's reduced product token (Chrome/<major>.0.0.0): $userAgent"
        )
    }

    @Test
    @DisplayName("the client hints agree with the user agent")
    fun clientHintsAgreeWithTheUserAgent() = runWebDriverTest(simpleDomURL) { driver ->
        val brands = driver.evaluate(
            "navigator.userAgentData ? navigator.userAgentData.brands.map(b => b.brand).join(', ') : ''",
            ""
        )
        println("navigator.userAgentData.brands = $brands")

        // A blank result means the page is not a secure context, where userAgentData is not exposed.
        if (brands.isEmpty()) {
            return@runWebDriverTest
        }

        assertFalse(
            headlessToken.containsMatchIn(brands),
            "the client hints still advertise the headless token: $brands"
        )
        assertTrue(
            brands.contains("Google Chrome"),
            "expected the Chrome brand the reduced user agent claims: $brands"
        )
    }

    @Test
    @DisplayName("device memory is a plausible Chrome bucket, never a raw host value")
    fun deviceMemoryIsAPlausibleChromeBucket() = runWebDriverTest(simpleDomURL) { driver ->
        val deviceMemory = driver.evaluate("navigator.deviceMemory", 0.0)
        println("navigator.deviceMemory = $deviceMemory")

        // `navigator.deviceMemory` is [SecureContext]; undefined maps to the 0.0 default here.
        if (deviceMemory <= 0.0) {
            return@runWebDriverTest
        }

        // Chrome derives the value from the host's RAM by rounding down to a power of two, so a
        // value off the power-of-two ladder means something replaced Chrome's own computation.
        val exponent = Math.log(deviceMemory) / Math.log(2.0)
        assertEquals(
            Math.floor(exponent), exponent, 1e-9,
            "navigator.deviceMemory must be a power of two, got $deviceMemory"
        )
        assertTrue(deviceMemory >= 0.25, "navigator.deviceMemory is below the API minimum: $deviceMemory")
    }

    @Test
    @DisplayName("touch signals are internally coherent")
    fun touchSignalsAreCoherent() = runWebDriverTest(simpleDomURL) { driver ->
        val maxTouchPoints = driver.evaluate("navigator.maxTouchPoints", 0)
        val anyCoarse = driver.evaluate("matchMedia('(any-pointer: coarse)').matches", false)
        val coarse = driver.evaluate("matchMedia('(pointer: coarse)').matches", false)
        val hasTouchEvent = driver.evaluate("('ontouchstart' in window)", false)
        println(
            "touch surface: maxTouchPoints=$maxTouchPoints, any-pointer:coarse=$anyCoarse, " +
                "pointer:coarse=$coarse, ontouchstart=$hasTouchEvent"
        )

        assertTrue(maxTouchPoints >= 0, "navigator.maxTouchPoints must not be negative: $maxTouchPoints")
        // A device that reports touch points must expose a coarse pointer somewhere, otherwise the
        // two readings describe different hardware.
        if (maxTouchPoints > 0) {
            assertTrue(
                anyCoarse || hasTouchEvent,
                "maxTouchPoints=$maxTouchPoints but no coarse pointer and no touch event support"
            )
        }
        // A coarse primary pointer implies a coarse pointer somewhere.
        if (coarse) {
            assertTrue(anyCoarse, "'(pointer: coarse)' is true while '(any-pointer: coarse)' is false")
        }
    }
}
