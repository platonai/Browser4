package ai.platon.pulsar.common.browser.fingerprint

import ai.platon.pulsar.common.browser.BrowserType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [FingerprintValidator.validate] — ensures loaded fingerprints
 * are consistent and reasonable.
 */
class FingerprintValidatorTest {

    private val validator = FingerprintValidator()

    // -- Valid fingerprints ----------------------------------------------------

    @Test
    @DisplayName("valid Windows desktop fingerprint passes")
    fun testValidWindowsDesktopFingerprint() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            screenParameters = ScreenParameters.DESKTOP_1920X1080,
            viewportParameters = ViewportParameters.DESKTOP,
            geoTimeParameters = GeoTimeParameters.US_EAST,
            hardwareParameters = HardwareParameters.WINDOWS_DESKTOP,
            webGLParameters = WebGLParameters.INTEL_INTEGRATED
        )

        val result = validator.validate(fingerprint)
        assertTrue(result.isValid, "Expected valid fingerprint, got: ${result}")
        assertFalse(result.hasWarnings, "Expected no warnings, got: ${result.warnings}")
    }

    @Test
    @DisplayName("valid Mac fingerprint passes")
    fun testValidMacFingerprint() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
            hardwareParameters = HardwareParameters.MAC_LAPTOP
        )

        val result = validator.validate(fingerprint)
        assertTrue(result.isValid, "Mac fingerprint should be valid: ${result}")
    }

    @Test
    @DisplayName("valid Linux fingerprint passes")
    fun testValidLinuxFingerprint() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
            hardwareParameters = HardwareParameters.LINUX_DESKTOP
        )

        val result = validator.validate(fingerprint)
        assertTrue(result.isValid, "Linux fingerprint should be valid: ${result}")
    }

    @Test
    @DisplayName("bare fingerprint with no extended parameters passes")
    fun testBareFingerprintPasses() {
        val fingerprint = Fingerprint(browserType = BrowserType.PULSAR_CHROME)

        val result = validator.validate(fingerprint)
        assertTrue(result.isValid, "Bare fingerprint should pass validation: ${result}")
        assertFalse(result.hasWarnings)
    }

    // -- User agent / platform consistency ------------------------------------

    @Test
    @DisplayName("detects Windows UA with Mac platform mismatch")
    fun testWindowsUaWithMacPlatformMismatch() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            hardwareParameters = HardwareParameters(
                hardwareConcurrency = 8,
                platform = "MacIntel"  // mismatch: Windows UA with Mac platform
            )
        )

        val result = validator.validate(fingerprint)
        assertFalse(result.isValid)
        assertTrue(
            result.errors.any { it.contains("Windows") && it.contains("MacIntel") },
            "Expected error about Windows/MacIntel mismatch, got: ${result.errors}"
        )
    }

    @Test
    @DisplayName("detects Mac UA with Linux platform mismatch")
    fun testMacUaWithLinuxPlatformMismatch() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36",
            hardwareParameters = HardwareParameters(
                hardwareConcurrency = 4,
                platform = "Linux x86_64"  // mismatch
            )
        )

        val result = validator.validate(fingerprint)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("Mac") && it.contains("Linux") },
            "Expected error about Mac/Linux mismatch, got: ${result.errors}")
    }

    @Test
    @DisplayName("detects Linux UA with Mac platform mismatch")
    fun testLinuxUaWithMacPlatformMismatch() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36",
            hardwareParameters = HardwareParameters(
                hardwareConcurrency = 8,
                platform = "MacIntel"  // mismatch
            )
        )

        val result = validator.validate(fingerprint)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("Linux") && it.contains("MacIntel") },
            "Expected error about Linux/MacIntel mismatch, got: ${result.errors}")
    }

    @Test
    @DisplayName("warns when Chrome UA has non-Google vendor")
    fun testChromeVendorMismatchWarning() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
            hardwareParameters = HardwareParameters(
                hardwareConcurrency = 8,
                platform = "Win32",
                vendor = "Apple Computer, Inc."  // wrong vendor for Chrome
            )
        )

        val result = validator.validate(fingerprint)
        assertTrue(result.isValid, "Should be valid but have warnings")
        assertTrue(result.hasWarnings)
        assertTrue(
            result.warnings.any { it.contains("Chrome") && it.contains("Google Inc.") },
            "Expected warning about Chrome vendor, got: ${result.warnings}"
        )
    }

    @Test
    @DisplayName("warns when Safari UA has non-Apple vendor")
    fun testSafariVendorMismatchWarning() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Safari/537.36",
            hardwareParameters = HardwareParameters(
                hardwareConcurrency = 8,
                platform = "MacIntel",
                vendor = "Google Inc."  // wrong vendor for Safari
            )
        )

        val result = validator.validate(fingerprint)
        assertTrue(result.isValid, "Should be valid but have warnings")
        assertTrue(result.hasWarnings)
        assertTrue(
            result.warnings.any { it.contains("Safari") && it.contains("Apple Computer, Inc.") },
            "Expected warning about Safari vendor, got: ${result.warnings}"
        )
    }

    // -- Screen / viewport consistency ----------------------------------------

    @Test
    @DisplayName("detects viewport width exceeding screen width")
    fun testViewportWidthExceedsScreenWidth() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            screenParameters = ScreenParameters(
                width = 1366, height = 768,
                availWidth = 1366, availHeight = 728
            ),
            viewportParameters = ViewportParameters(
                width = 1920,  // exceeds screen width of 1366
                height = 768
            )
        )

        val result = validator.validate(fingerprint)
        assertFalse(result.isValid)
        assertTrue(
            result.errors.any { it.contains("Viewport width") && it.contains("exceeds") },
            "Expected error about viewport width exceeding screen, got: ${result.errors}"
        )
    }

    @Test
    @DisplayName("detects viewport height exceeding screen height")
    fun testViewportHeightExceedsScreenHeight() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            screenParameters = ScreenParameters(
                width = 1920, height = 1080,
                availWidth = 1920, availHeight = 1040
            ),
            viewportParameters = ViewportParameters(
                width = 1920,
                height = 1200  // exceeds screen height of 1080
            )
        )

        val result = validator.validate(fingerprint)
        assertFalse(result.isValid)
        assertTrue(
            result.errors.any { it.contains("Viewport height") && it.contains("exceeds") },
            "Expected error about viewport height exceeding screen, got: ${result.errors}"
        )
    }

    @Test
    @DisplayName("warns on device scale factor / pixel ratio mismatch")
    fun testDeviceScaleFactorMismatchWarning() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36",
            screenParameters = ScreenParameters(
                width = 2560, height = 1600,
                availWidth = 2560, availHeight = 1577,
                devicePixelRatio = 2.0
            ),
            viewportParameters = ViewportParameters(
                width = 1280, height = 800,
                deviceScaleFactor = 1.5  // mismatch with devicePixelRatio=2.0
            )
        )

        val result = validator.validate(fingerprint)
        assertTrue(result.isValid, "Should be valid, got: ${result}")
        assertTrue(result.hasWarnings)
        assertTrue(
            result.warnings.any { it.contains("scale factor") },
            "Expected warning about scale factor mismatch, got: ${result.warnings}"
        )
    }

    @Test
    @DisplayName("warns on unusual viewport aspect ratio")
    fun testUnusualViewportAspectRatioWarning() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            screenParameters = ScreenParameters(
                width = 5000, height = 500,
                availWidth = 5000, availHeight = 500
            ),
            viewportParameters = ViewportParameters(
                width = 5000,  // aspect ratio 10:1 — very unusual
                height = 500
            )
        )

        val result = validator.validate(fingerprint)
        // This should still be valid (no errors) but trigger a warning
        assertTrue(result.hasWarnings, "Expected aspect ratio warning")
        assertTrue(
            result.warnings.any { it.contains("aspect ratio") },
            "Expected warning about unusual aspect ratio, got: ${result.warnings}"
        )
    }

    // -- Hardware reasonability ------------------------------------------------

    @Test
    @DisplayName("detects unreasonably high CPU core count")
    fun testUnreasonableHardwareConcurrency() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            hardwareParameters = HardwareParameters(
                hardwareConcurrency = 256,  // unreasonably high
                platform = "Win32"
            )
        )

        val result = validator.validate(fingerprint)
        assertFalse(result.isValid)
        assertTrue(
            result.errors.any { it.contains("Hardware concurrency") },
            "Expected error about hardware concurrency, got: ${result.errors}"
        )
    }

    @Test
    @DisplayName("detects unreasonably high device memory")
    fun testUnreasonableDeviceMemory() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            hardwareParameters = HardwareParameters(
                hardwareConcurrency = 8,
                deviceMemory = 512,  // 512 GB — unreasonably high
                platform = "Win32"
            )
        )

        val result = validator.validate(fingerprint)
        assertFalse(result.isValid)
        assertTrue(
            result.errors.any { it.contains("Device memory") },
            "Expected error about device memory, got: ${result.errors}"
        )
    }

    @Test
    @DisplayName("null device memory passes without warnings")
    fun testNullDeviceMemoryPasses() {
        // deviceMemory is optional; omitting it should not trigger any warnings
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            hardwareParameters = HardwareParameters(
                hardwareConcurrency = 8,
                deviceMemory = null,
                platform = "Win32"
            )
        )

        val result = validator.validate(fingerprint)
        assertTrue(result.isValid, "Null device memory should be valid: ${result}")
        assertFalse(result.hasWarnings, "No warnings expected: ${result.warnings}")
        assertFalse(
            result.warnings.any { it.contains("Device memory") },
            "No device memory warnings expected when not specified, got: ${result.warnings}"
        )
    }

    @Test
    @DisplayName("warns when mobile device has no touch points")
    fun testMobileDeviceWithoutTouchPoints() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            userAgent = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36",
            viewportParameters = ViewportParameters(
                width = 375, height = 667,
                isMobile = true, hasTouch = true
            ),
            hardwareParameters = HardwareParameters(
                hardwareConcurrency = 4,
                maxTouchPoints = 0,  // mobile should have touch points
                platform = "Linux armv8l"
            )
        )

        val result = validator.validate(fingerprint)
        assertTrue(result.isValid, "Should be valid, got: ${result}")
        assertTrue(result.hasWarnings)
        assertTrue(
            result.warnings.any { it.contains("Mobile") && it.contains("maxTouchPoints") },
            "Expected warning about mobile touch points, got: ${result.warnings}"
        )
    }

    @Test
    @DisplayName("warns when desktop device has touch points")
    fun testDesktopWithTouchPoints() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            viewportParameters = ViewportParameters(
                width = 1920, height = 1080,
                isMobile = false, hasTouch = false
            ),
            hardwareParameters = HardwareParameters(
                hardwareConcurrency = 8,
                maxTouchPoints = 5,  // desktop typically has 0
                platform = "Win32"
            )
        )

        val result = validator.validate(fingerprint)
        assertTrue(result.hasWarnings, "Expected warning about desktop touch points")
        assertTrue(
            result.warnings.any { it.contains("Desktop") && it.contains("maxTouchPoints") },
            "Expected warning about desktop touch points, got: ${result.warnings}"
        )
    }

    @Test
    @DisplayName("warns on unusually high max touch points")
    fun testUnusuallyHighMaxTouchPoints() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            hardwareParameters = HardwareParameters(
                hardwareConcurrency = 8,
                maxTouchPoints = 20,  // unusually high
                platform = "Win32"
            )
        )

        val result = validator.validate(fingerprint)
        assertTrue(result.hasWarnings, "Expected warning about high touch points")
        assertTrue(
            result.warnings.any { it.contains("touch points") && it.contains("high") },
            "Expected warning about high touch points, got: ${result.warnings}"
        )
    }

    // -- Geo-time consistency --------------------------------------------------

    @Test
    @DisplayName("detects latitude without longitude")
    fun testLatitudeWithoutLongitude() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            geoTimeParameters = GeoTimeParameters(
                timezone = "America/New_York",
                timezoneOffset = 300,
                locale = "en-US",
                languages = listOf("en-US"),
                latitude = 40.7128  // missing longitude
            )
        )

        val result = validator.validate(fingerprint)
        assertFalse(result.isValid)
        assertTrue(
            result.errors.any { it.contains("Latitude") && it.contains("without longitude") },
            "Expected error about latitude without longitude, got: ${result.errors}"
        )
    }

    @Test
    @DisplayName("detects longitude without latitude")
    fun testLongitudeWithoutLatitude() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            geoTimeParameters = GeoTimeParameters(
                timezone = "America/New_York",
                timezoneOffset = 300,
                locale = "en-US",
                languages = listOf("en-US"),
                longitude = -74.0060  // missing latitude
            )
        )

        val result = validator.validate(fingerprint)
        assertFalse(result.isValid)
        assertTrue(
            result.errors.any { it.contains("Longitude") && it.contains("without latitude") },
            "Expected error about longitude without latitude, got: ${result.errors}"
        )
    }

    @Test
    @DisplayName("warns when first language does not match locale")
    fun testLanguageLocaleMismatch() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            geoTimeParameters = GeoTimeParameters(
                timezone = "America/New_York",
                timezoneOffset = 300,
                locale = "zh-CN",        // Chinese locale
                languages = listOf("en-US", "en")  // but English first
            )
        )

        val result = validator.validate(fingerprint)
        assertTrue(result.isValid, "Should be valid but have warnings")
        assertTrue(result.hasWarnings)
        assertTrue(
            result.warnings.any { it.contains("language") && it.contains("locale") },
            "Expected warning about language/locale mismatch, got: ${result.warnings}"
        )
    }

    @Test
    @DisplayName("warns on non-standard timezone format")
    fun testNonStandardTimezoneFormat() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            geoTimeParameters = GeoTimeParameters(
                timezone = "new_york",  // not IANA format (should be America/New_York)
                timezoneOffset = 300,
                locale = "en-US",
                languages = listOf("en-US")
            )
        )

        val result = validator.validate(fingerprint)
        assertTrue(result.hasWarnings, "Expected warning about timezone format")
        assertTrue(
            result.warnings.any { it.contains("Timezone") },
            "Expected warning about timezone format, got: ${result.warnings}"
        )
    }

    @Test
    @DisplayName("warns on out-of-range timezone offset")
    fun testOutOfRangeTimezoneOffset() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            geoTimeParameters = GeoTimeParameters(
                timezone = "America/New_York",
                timezoneOffset = 900,  // outside -720..+840 range
                locale = "en-US",
                languages = listOf("en-US")
            )
        )

        val result = validator.validate(fingerprint)
        assertTrue(result.hasWarnings, "Expected warning about timezone offset")
        assertTrue(
            result.warnings.any { it.contains("Timezone offset") },
            "Expected warning about offset, got: ${result.warnings}"
        )
    }

    // -- WebGL consistency ----------------------------------------------------

    @Test
    @DisplayName("warns when Apple GPU appears on non-Mac platform")
    fun testAppleGpuOnNonMacPlatform() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            hardwareParameters = HardwareParameters.WINDOWS_DESKTOP,
            webGLParameters = WebGLParameters.APPLE_M1  // Apple GPU on Windows
        )

        val result = validator.validate(fingerprint)
        assertTrue(result.hasWarnings, "Expected warning about Apple GPU on non-Mac")
        assertTrue(
            result.warnings.any { it.contains("Apple") && it.contains("MacIntel") },
            "Expected warning about Apple GPU platform, got: ${result.warnings}"
        )
    }

    @Test
    @DisplayName("warns on unusually low max texture size")
    fun testLowMaxTextureSize() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            webGLParameters = WebGLParameters(
                vendor = "Google Inc.",
                renderer = "ANGLE",
                maxTextureSize = 1024  // unusually low
            )
        )

        val result = validator.validate(fingerprint)
        assertTrue(result.hasWarnings, "Expected warning about low texture size")
        assertTrue(
            result.warnings.any { it.contains("texture size") && it.contains("low") },
            "Expected warning about low texture size, got: ${result.warnings}"
        )
    }

    @Test
    @DisplayName("warns on unusually high max texture size")
    fun testHighMaxTextureSize() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            webGLParameters = WebGLParameters(
                vendor = "Google Inc.",
                renderer = "ANGLE",
                maxTextureSize = 65536  // unusually high
            )
        )

        val result = validator.validate(fingerprint)
        assertTrue(result.hasWarnings, "Expected warning about high texture size")
        assertTrue(
            result.warnings.any { it.contains("texture size") && it.contains("high") },
            "Expected warning about high texture size, got: ${result.warnings}"
        )
    }

    // -- User agent requirement with extended params --------------------------

    @Test
    @DisplayName("requires userAgent when screen parameters are present")
    fun testUserAgentRequiredWithScreenParameters() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            screenParameters = ScreenParameters.DESKTOP_1920X1080
            // no userAgent
        )

        val result = validator.validate(fingerprint)
        assertFalse(result.isValid)
        assertTrue(
            result.errors.any { it.contains("User agent") && it.contains("required") },
            "Expected error about userAgent required, got: ${result.errors}"
        )
    }

    @Test
    @DisplayName("requires userAgent when hardware parameters are present")
    fun testUserAgentRequiredWithHardwareParameters() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            hardwareParameters = HardwareParameters.WINDOWS_DESKTOP
            // no userAgent
        )

        val result = validator.validate(fingerprint)
        assertFalse(result.isValid)
        assertTrue(
            result.errors.any { it.contains("User agent") && it.contains("required") },
            "Expected error about userAgent required, got: ${result.errors}"
        )
    }

    // -- ValidationResult ------------------------------------------------------

    @Test
    @DisplayName("ValidationResult summary formats correctly")
    fun testValidationResultSummary() {
        assertEquals(
            "Validation passed",
            ValidationResult(emptyList(), emptyList()).summary()
        )
        assertEquals(
            "Validation passed with 2 warnings",
            ValidationResult(emptyList(), listOf("w1", "w2")).summary()
        )
        assertEquals(
            "Validation failed with 3 errors",
            ValidationResult(listOf("e1", "e2", "e3"), emptyList()).summary()
        )
        assertEquals(
            "Validation failed with 1 errors",
            ValidationResult(listOf("e1"), listOf("w1")).summary()
        )
    }

    @Test
    @DisplayName("complete consistent fingerprint passes all checks")
    fun testCompleteConsistentFingerprint() {
        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            screenParameters = ScreenParameters(
                width = 1920, height = 1080,
                availWidth = 1920, availHeight = 1040,
                colorDepth = 24, pixelDepth = 24,
                devicePixelRatio = 1.0
            ),
            viewportParameters = ViewportParameters(
                width = 1920, height = 1080,
                deviceScaleFactor = 1.0,
                isMobile = false, hasTouch = false
            ),
            geoTimeParameters = GeoTimeParameters(
                timezone = "America/New_York",
                timezoneOffset = 300,
                locale = "en-US",
                languages = listOf("en-US", "en")
            ),
            hardwareParameters = HardwareParameters(
                hardwareConcurrency = 8,
                deviceMemory = 8,
                maxTouchPoints = 0,
                platform = "Win32",
                vendor = "Google Inc."
            ),
            webGLParameters = WebGLParameters.INTEL_INTEGRATED,
            canvasParameters = CanvasParameters.DEFAULT,
            mediaParameters = MediaParameters.DESKTOP,
            miscParameters = MiscParameters.DEFAULT
        )

        val result = validator.validate(fingerprint)
        assertTrue(result.isValid, "Complete consistent fingerprint should pass: ${result}")
        assertFalse(
            result.hasWarnings,
            "Complete consistent fingerprint should have no warnings: ${result.warnings}"
        )
    }
}
