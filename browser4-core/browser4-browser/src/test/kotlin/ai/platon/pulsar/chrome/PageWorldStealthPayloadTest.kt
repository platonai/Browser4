package ai.platon.pulsar.chrome

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pins the page-world stealth payload (`js/stealth.js`) to its deliberate, conditional form.
 *
 * `ai.platon.pulsar.api.scripting.DualWorldScriptLoader` concatenates every classpath
 * `js/` PAGE_WORLD_RESOURCE and registers the result with
 * `Page.addScriptToEvaluateOnNewDocument` for every document. The base library ships a
 * puppeteer-extra-plugin-stealth bundle there; this module's `js/stealth.js` shadows it with
 * the *same* bundle behind one guard:
 *
 * ```js
 * if (navigator.webdriver === true) { …bundle… }
 * ```
 *
 * The bundle's evasions only work together with a `Function.prototype.toString` Proxy and an
 * `iframe.contentWindow` evasion, and public native-tampering detectors rate both as rating-1
 * bot verdicts (`https://bot-detector.rebrowser.net/`, measured on an extension-attached real
 * Edge). Patching is therefore only justified when the browser already admits it is automated:
 * a CDP-driven browser reports `navigator.webdriver === true`, while a real browser reached
 * through the extension - and a Browser4 launch, which passes
 * `--disable-blink-features=AutomationControlled` - reports `false` and must stay untouched.
 *
 * A shadow resource is invisible to the compiler and to the base library, so the only way to
 * keep the intent is to read it back here: the guard, the payload and the provenance must all
 * survive an edit. Diagnostics that reproduce the finding live in
 * `docs-dev/copilot/bot-stealth-probes/` (`plain-rebrowser.mjs`).
 */
@DisplayName("Page-world stealth payload")
class PageWorldStealthPayloadTest {

    private fun payload(): String =
        javaClass.getResourceAsStream("/js/stealth.js")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("js/stealth.js is missing from the classpath")

    /** The part of the resource a browser would execute (the documentation header removed). */
    private fun code(): String {
        val payload = payload()
        val headerEnd = payload.indexOf("*/")
        assertTrue(headerEnd >= 0, "js/stealth.js must start with its documentation header")
        return payload.substring(headerEnd + 2).trim()
    }

    @Test
    @DisplayName("the shadow resource documents why it is conditional")
    fun testShadowResourceIsDocumented() {
        val payload = payload()

        assertNotNull(payload)
        assertTrue(payload.contains("CONDITIONAL")) {
            "js/stealth.js must keep its 'CONDITIONAL' header"
        }
        assertTrue(payload.contains("bot-detector.rebrowser.net")) {
            "js/stealth.js must cite the detector finding so the trade-off stays auditable"
        }
    }

    @Test
    @DisplayName("the whole payload runs only when the browser admits it is automated")
    fun testPayloadIsGuardedByTheWebdriverFlag() {
        val code = code()

        assertTrue(
            code.startsWith("if (navigator.webdriver === true) {"),
            "the payload must be guarded by `if (navigator.webdriver === true)`: a real browser " +
                "reached through the extension reports false and must not be patched. Starts with: ${code.take(120)}"
        )
        assertTrue(code.trimEnd().endsWith("}")) { "the guard must close at the end of the resource" }
    }

    @Test
    @DisplayName("the guarded bundle is still the vendored stealth payload")
    fun testPayloadSurvivesInsideTheGuard() {
        val code = code()

        // `_utilsFns` / `_mainFunction` are the extract-stealth-evasions wrapper's own keys; their
        // presence proves the bundle was not emptied while keeping the guard.
        assertTrue(code.contains("_utilsFns")) { "the puppeteer-extra bundle is missing" }
        assertTrue(code.contains("puppeteer-extra")) { "the bundle's provenance header is missing" }
        assertTrue(
            code.indexOf("_utilsFns") > code.indexOf("if (navigator.webdriver === true) {"),
            "the bundle must sit *inside* the guard, not before it"
        )
    }
}
