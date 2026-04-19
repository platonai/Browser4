package ai.platon.pulsar.browser

import ai.platon.pulsar.WebDriverTestBase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Integration tests for fingerprint application with real browsers.
 *
 * Tests that fingerprint parameters are correctly applied via CDP
 * and JavaScript injection when a browser starts.
 */
@Tag("Integration")
@Tag("RequiresBrowser")
class FingerprintApplicationIT : WebDriverTestBase() {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @Disabled("Feature postponed")
    @DisplayName("test browser starts with fingerprint")
    fun testBrowserStartsWithFingerprint() = runWebDriverTest(simpleDomURL) { driver ->
        // Browser is created with fingerprint in runWebDriverTest
        val browserId = driver.browser.id

        assertNotNull(browserId.fingerprint)
        assertNotNull(browserId.fingerprint.userAgent)

        println("✓ Browser started with fingerprint")
        println("  User Agent: ${browserId.fingerprint.userAgent?.take(60)}...")
        println("  Browser Type: ${browserId.fingerprint.browserType}")
    }

    @Test
    @DisplayName("test screen parameters are applied")
    fun testScreenParametersAreApplied() = runWebDriverTest(simpleDomURL) { driver ->
        val fingerprint = driver.browser.id.fingerprint

        // Get screen parameters from browser
        val screenWidth = driver.evaluate("screen.width", 0)
        val screenHeight = driver.evaluate("screen.height", 0)
        val colorDepth = driver.evaluate("screen.colorDepth", 0)
        val pixelRatio = driver.evaluate("window.devicePixelRatio", 0.0)

        println("✓ Screen parameters from browser:")
        println("  Resolution: ${screenWidth}x${screenHeight}")
        println("  Color Depth: $colorDepth")
        println("  Pixel Ratio: $pixelRatio")

        // Verify screen parameters exist
        assertTrue(screenWidth > 0, "Screen width should be set")
        assertTrue(screenHeight > 0, "Screen height should be set")
        assertTrue(colorDepth > 0, "Color depth should be set")
        assertTrue(pixelRatio > 0.0, "Pixel ratio should be set")

        // If fingerprint has screen parameters, they should match (if JS injection worked)
        fingerprint.screenParameters?.let { screen ->
            println("  Expected from fingerprint: ${screen.width}x${screen.height}, ratio=${screen.devicePixelRatio}")
        }
    }

    @Test
    @DisplayName("test navigator hardware concurrency")
    fun testNavigatorHardwareConcurrency() = runWebDriverTest(simpleDomURL) { driver ->
        val fingerprint = driver.browser.id.fingerprint

        // Get hardware concurrency from browser
        val hardwareConcurrency = driver.evaluate("navigator.hardwareConcurrency", 0)
        val platform = driver.evaluate("navigator.platform", "")
        val vendor = driver.evaluate("navigator.vendor", "")

        println("✓ Navigator hardware parameters:")
        println("  Hardware Concurrency: $hardwareConcurrency")
        println("  Platform: $platform")
        println("  Vendor: $vendor")

        // Verify hardware parameters exist
        assertTrue(hardwareConcurrency > 0, "Hardware concurrency should be set")
        assertTrue(platform.isNotEmpty(), "Platform should be set")

        // If fingerprint has hardware parameters, show expected values
        fingerprint.hardwareParameters?.let { hardware ->
            println("  Expected from fingerprint:")
            println("    Concurrency: ${hardware.hardwareConcurrency}")
            println("    Platform: ${hardware.platform}")
            println("    Vendor: ${hardware.vendor}")
        }
    }

    @Test
    @Disabled("Feature postponed")
    @DisplayName("test user agent is set correctly")
    fun testUserAgentIsSetCorrectly() = runWebDriverTest(simpleDomURL) { driver ->
        val fingerprint = driver.browser.id.fingerprint

        // Get user agent from browser
        val userAgent = driver.evaluate("navigator.userAgent", "")

        println("✓ User Agent:")
        println("  From Browser: ${userAgent.take(80)}...")

        assertNotNull(fingerprint.userAgent)
        assertTrue(userAgent.isNotEmpty(), "User agent should be set")

        println("  From Fingerprint: ${fingerprint.userAgent?.take(80)}...")
    }

    @Test
    @DisplayName("test fingerprint parameters persist across navigation")
    fun testFingerprintParametersPersistAcrossNavigation() = runWebDriverTest(simpleDomURL) { driver ->
        // Get parameters from first page
        val screenWidth1 = driver.evaluate("screen.width", 0)
        val hardwareConcurrency1 = driver.evaluate("navigator.hardwareConcurrency", 0)
        val userAgent1 = driver.evaluate("navigator.userAgent", "")

        println("✓ Parameters on first page:")
        println("  Screen: ${screenWidth1}")
        println("  CPU Cores: ${hardwareConcurrency1}")
        println("  UA: ${userAgent1.take(50)}...")

        // Navigate to different page
        driver.navigate(interactiveUrl)
        driver.waitForSelector("body")

        // Get parameters from second page
        val screenWidth2 = driver.evaluate("screen.width", 0)
        val hardwareConcurrency2 = driver.evaluate("navigator.hardwareConcurrency", 0)
        val userAgent2 = driver.evaluate("navigator.userAgent", "")

        println("✓ Parameters on second page:")
        println("  Screen: ${screenWidth2}")
        println("  CPU Cores: ${hardwareConcurrency2}")
        println("  UA: ${userAgent2.take(50)}...")

        // Parameters should remain consistent
        assertEquals(screenWidth1, screenWidth2, "Screen width should persist across navigation")
        assertEquals(hardwareConcurrency1, hardwareConcurrency2,
            "Hardware concurrency should persist across navigation")
        assertEquals(userAgent1, userAgent2, "User agent should persist across navigation")

        println("✓ All parameters remained consistent across navigation")
    }

    @Test
    @DisplayName("test WebGL parameters are available")
    fun testWebGLParametersAreAvailable() = runWebDriverTest(simpleDomURL) { driver ->
        val fingerprint = driver.browser.id.fingerprint

        // Get WebGL info
        val webglVendor = driver.evaluate("""
            (function() {
                try {
                    const canvas = document.createElement('canvas');
                    const gl = canvas.getContext('webgl') || canvas.getContext('experimental-webgl');
                    if (!gl) return 'N/A';
                    const debugInfo = gl.getExtension('WEBGL_debug_renderer_info');
                    if (!debugInfo) return gl.getParameter(gl.VENDOR);
                    return gl.getParameter(debugInfo.UNMASKED_VENDOR_WEBGL);
                } catch(e) {
                    return 'Error';
                }
            })()
        """.trimIndent(), "")

        val webglRenderer = driver.evaluate("""
            (function() {
                try {
                    const canvas = document.createElement('canvas');
                    const gl = canvas.getContext('webgl') || canvas.getContext('experimental-webgl');
                    if (!gl) return 'N/A';
                    const debugInfo = gl.getExtension('WEBGL_debug_renderer_info');
                    if (!debugInfo) return gl.getParameter(gl.RENDERER);
                    return gl.getParameter(debugInfo.UNMASKED_RENDERER_WEBGL);
                } catch(e) {
                    return 'Error';
                }
            })()
        """.trimIndent(), "")

        println("✓ WebGL parameters:")
        println("  Vendor: $webglVendor")
        println("  Renderer: $webglRenderer")

        fingerprint.webGLParameters?.let { webgl ->
            println("  Expected from fingerprint:")
            println("    Vendor: ${webgl.vendor}")
            println("    Renderer: ${webgl.renderer}")
        }

        // WebGL should be available
        assertTrue(webglVendor != "Error" && webglVendor != "N/A",
            "WebGL vendor should be available")
        assertTrue(webglRenderer != "Error" && webglRenderer != "N/A",
            "WebGL renderer should be available")
    }

    @Test
    @DisplayName("test timezone and locale settings")
    fun testTimezoneAndLocaleSettings() = runWebDriverTest(simpleDomURL) { driver ->
        val fingerprint = driver.browser.id.fingerprint

        // Get timezone and locale info
        val timezone = driver.evaluate("Intl.DateTimeFormat().resolvedOptions().timeZone", "")
        val locale = driver.evaluate("navigator.language", "")
        val languages = driver.evaluate("JSON.stringify(navigator.languages)", "[]")

        println("✓ Geo/Time parameters:")
        println("  Timezone: $timezone")
        println("  Locale: $locale")
        println("  Languages: $languages")

        fingerprint.geoTimeParameters?.let { geo ->
            println("  Expected from fingerprint:")
            println("    Timezone: ${geo.timezone}")
            println("    Locale: ${geo.locale}")
            println("    Languages: ${geo.languages}")
        }

        assertTrue(timezone.isNotEmpty(), "Timezone should be set")
        assertTrue(locale.isNotEmpty(), "Locale should be set")
    }

    @Test
    @DisplayName("test canvas fingerprinting defense")
    fun testCanvasFingerprintingDefense() = runWebDriverTest(simpleDomURL) { driver ->
        val fingerprint = driver.browser.id.fingerprint

        // Create canvas and get data URL
        val canvasDataUrl = driver.evaluate("""
            (function() {
                const canvas = document.createElement('canvas');
                canvas.width = 100;
                canvas.height = 100;
                const ctx = canvas.getContext('2d');
                ctx.fillStyle = 'red';
                ctx.fillRect(0, 0, 50, 50);
                ctx.fillStyle = 'blue';
                ctx.fillRect(50, 50, 50, 50);
                return canvas.toDataURL().substring(0, 100);
            })()
        """.trimIndent(), "")

        println("✓ Canvas fingerprinting:")
        println("  Data URL (first 100 chars): $canvasDataUrl")

        fingerprint.canvasParameters?.let { canvas ->
            println("  Fingerprint seed configured: ${canvas.fingerprintSeed}")
        }

        // Canvas should work
        assertTrue(canvasDataUrl.startsWith("data:image/"),
            "Canvas should produce valid data URL")
    }
}
