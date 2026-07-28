package ai.platon.pulsar.common.browser.fingerprint

import ai.platon.pulsar.common.serialize.json.prettyPulsarObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * Tests for the fingerprint parameter data classes — creation, validation,
 * presets, and JSON serialization round-trips.
 */
class FingerprintParametersTest {

    private val mapper = prettyPulsarObjectMapper()

    // -- ScreenParameters ------------------------------------------------------

    @Test
    @DisplayName("creates ScreenParameters with valid values")
    fun testScreenParametersCreation() {
        val screen = ScreenParameters(
            width = 1920, height = 1080,
            availWidth = 1920, availHeight = 1040,
            colorDepth = 24, pixelDepth = 24,
            devicePixelRatio = 1.0
        )

        assertEquals(1920, screen.width)
        assertEquals(1080, screen.height)
        assertEquals(1920, screen.availWidth)
        assertEquals(1040, screen.availHeight)
        assertEquals(24, screen.colorDepth)
        assertEquals(24, screen.pixelDepth)
        assertEquals(1.0, screen.devicePixelRatio)
        assertEquals("landscape-primary", screen.orientation)
    }

    @Test
    @DisplayName("rejects negative screen width")
    fun testScreenParametersRejectsNegativeWidth() {
        assertFailsWith<IllegalArgumentException> {
            ScreenParameters(
                width = -1, height = 1080,
                availWidth = 1920, availHeight = 1040
            )
        }
    }

    @Test
    @DisplayName("rejects available width exceeding screen width")
    fun testScreenParametersRejectsAvailWidthExceedingWidth() {
        assertFailsWith<IllegalArgumentException> {
            ScreenParameters(
                width = 1920, height = 1080,
                availWidth = 2000,  // exceeds width
                availHeight = 1040
            )
        }
    }

    @Test
    @DisplayName("rejects available height exceeding screen height")
    fun testScreenParametersRejectsAvailHeightExceedingHeight() {
        assertFailsWith<IllegalArgumentException> {
            ScreenParameters(
                width = 1920, height = 1080,
                availWidth = 1920,
                availHeight = 1100  // exceeds height
            )
        }
    }

    @Test
    @DisplayName("rejects invalid color depth")
    fun testScreenParametersRejectsInvalidColorDepth() {
        assertFailsWith<IllegalArgumentException> {
            ScreenParameters(
                width = 1920, height = 1080,
                availWidth = 1920, availHeight = 1040,
                colorDepth = 16  // not in [24, 30, 32, 48]
            )
        }
    }

    @Test
    @DisplayName("rejects zero device pixel ratio")
    fun testScreenParametersRejectsZeroPixelRatio() {
        assertFailsWith<IllegalArgumentException> {
            ScreenParameters(
                width = 1920, height = 1080,
                availWidth = 1920, availHeight = 1040,
                devicePixelRatio = 0.0
            )
        }
    }

    @Test
    @DisplayName("DESKTOP_1920X1080 preset has correct values")
    fun testDesktopPreset() {
        val desktop = ScreenParameters.DESKTOP_1920X1080
        assertEquals(1920, desktop.width)
        assertEquals(1080, desktop.height)
        assertEquals(1920, desktop.availWidth)
        assertEquals(1040, desktop.availHeight)
        assertEquals(24, desktop.colorDepth)
        assertEquals(1.0, desktop.devicePixelRatio)
    }

    @Test
    @DisplayName("LAPTOP_1366X768 preset has correct values")
    fun testLaptopPreset() {
        val laptop = ScreenParameters.LAPTOP_1366X768
        assertEquals(1366, laptop.width)
        assertEquals(768, laptop.height)
        assertEquals(1366, laptop.availWidth)
        assertEquals(728, laptop.availHeight)
        assertEquals(1.0, laptop.devicePixelRatio)
    }

    @Test
    @DisplayName("MACBOOK_PRO_13 preset has Retina values")
    fun testMacBookProPreset() {
        val macbook = ScreenParameters.MACBOOK_PRO_13
        assertEquals(2560, macbook.width)
        assertEquals(1600, macbook.height)
        assertEquals(2.0, macbook.devicePixelRatio)
    }

    @Test
    @DisplayName("ScreenParameters serializes to JSON and back")
    fun testScreenParametersSerialization() {
        val screen = ScreenParameters.DESKTOP_1920X1080
        val json = mapper.writeValueAsString(screen)
        val deserialized = mapper.readValue(json, ScreenParameters::class.java)
        assertEquals(screen, deserialized)
    }

    // -- ViewportParameters ----------------------------------------------------

    @Test
    @DisplayName("creates ViewportParameters with valid values")
    fun testViewportParametersCreation() {
        val viewport = ViewportParameters(
            width = 1920, height = 1080,
            deviceScaleFactor = 1.0,
            isMobile = false, hasTouch = false,
            isLandscape = true
        )

        assertEquals(1920, viewport.width)
        assertEquals(1080, viewport.height)
        assertEquals(1.0, viewport.deviceScaleFactor)
        assertFalse(viewport.isMobile)
        assertFalse(viewport.hasTouch)
        assertTrue(viewport.isLandscape)
    }

    @Test
    @DisplayName("rejects negative viewport width")
    fun testViewportParametersRejectsNegativeWidth() {
        assertFailsWith<IllegalArgumentException> {
            ViewportParameters(width = -1, height = 1080)
        }
    }

    @Test
    @DisplayName("rejects zero device scale factor")
    fun testViewportParametersRejectsZeroScaleFactor() {
        assertFailsWith<IllegalArgumentException> {
            ViewportParameters(width = 1920, height = 1080, deviceScaleFactor = 0.0)
        }
    }

    @Test
    @DisplayName("DESKTOP viewport preset is not mobile")
    fun testDesktopViewportPreset() {
        val desktop = ViewportParameters.DESKTOP
        assertFalse(desktop.isMobile)
        assertFalse(desktop.hasTouch)
        assertTrue(desktop.isLandscape)
        assertEquals(1920, desktop.width)
    }

    @Test
    @DisplayName("LAPTOP viewport preset has correct dimensions")
    fun testLaptopViewportPreset() {
        val laptop = ViewportParameters.LAPTOP
        assertEquals(1366, laptop.width)
        assertEquals(768, laptop.height)
    }

    @Test
    @DisplayName("mobile viewport configuration")
    fun testMobileViewport() {
        val mobile = ViewportParameters(
            width = 375, height = 667,
            deviceScaleFactor = 2.0,
            isMobile = true, hasTouch = true,
            isLandscape = false
        )
        assertTrue(mobile.isMobile)
        assertTrue(mobile.hasTouch)
        assertFalse(mobile.isLandscape)
    }

    // -- GeoTimeParameters -----------------------------------------------------

    @Test
    @DisplayName("creates GeoTimeParameters with valid values")
    fun testGeoTimeParametersCreation() {
        val geoTime = GeoTimeParameters(
            timezone = "Asia/Shanghai",
            timezoneOffset = -480,
            locale = "zh-CN",
            languages = listOf("zh-CN", "zh", "en")
        )

        assertEquals("Asia/Shanghai", geoTime.timezone)
        assertEquals(-480, geoTime.timezoneOffset)
        assertEquals("zh-CN", geoTime.locale)
        assertEquals(3, geoTime.languages.size)
    }

    @Test
    @DisplayName("rejects blank timezone")
    fun testGeoTimeParametersRejectsBlankTimezone() {
        assertFailsWith<IllegalArgumentException> {
            GeoTimeParameters(
                timezone = "",
                timezoneOffset = 0,
                locale = "en-US",
                languages = listOf("en")
            )
        }
    }

    @Test
    @DisplayName("rejects blank locale")
    fun testGeoTimeParametersRejectsBlankLocale() {
        assertFailsWith<IllegalArgumentException> {
            GeoTimeParameters(
                timezone = "America/New_York",
                timezoneOffset = 0,
                locale = "",
                languages = listOf("en")
            )
        }
    }

    @Test
    @DisplayName("rejects empty languages list")
    fun testGeoTimeParametersRejectsEmptyLanguages() {
        assertFailsWith<IllegalArgumentException> {
            GeoTimeParameters(
                timezone = "America/New_York",
                timezoneOffset = 0,
                locale = "en-US",
                languages = emptyList()
            )
        }
    }

    @Test
    @DisplayName("rejects latitude outside -90..90")
    fun testGeoTimeParametersRejectsInvalidLatitude() {
        assertFailsWith<IllegalArgumentException> {
            GeoTimeParameters(
                timezone = "America/New_York",
                timezoneOffset = 0,
                locale = "en-US",
                languages = listOf("en"),
                latitude = 91.0
            )
        }
    }

    @Test
    @DisplayName("rejects longitude outside -180..180")
    fun testGeoTimeParametersRejectsInvalidLongitude() {
        assertFailsWith<IllegalArgumentException> {
            GeoTimeParameters(
                timezone = "America/New_York",
                timezoneOffset = 0,
                locale = "en-US",
                languages = listOf("en"),
                longitude = -181.0
            )
        }
    }

    @Test
    @DisplayName("CHINA preset has correct values")
    fun testChinaPreset() {
        val china = GeoTimeParameters.CHINA
        assertEquals("Asia/Shanghai", china.timezone)
        assertEquals(-480, china.timezoneOffset)
        assertEquals("zh-CN", china.locale)
        assertTrue(china.languages.contains("zh-CN"))
    }

    @Test
    @DisplayName("US_EAST preset has correct values")
    fun testUsEastPreset() {
        val usEast = GeoTimeParameters.US_EAST
        assertEquals("America/New_York", usEast.timezone)
        assertEquals(300, usEast.timezoneOffset)
        assertEquals("en-US", usEast.locale)
    }

    @Test
    @DisplayName("UK preset has correct values")
    fun testUkPreset() {
        val uk = GeoTimeParameters.UK
        assertEquals("Europe/London", uk.timezone)
        assertEquals(0, uk.timezoneOffset)
        assertEquals("en-GB", uk.locale)
    }

    @Test
    @DisplayName("GeoTimeParameters with coordinates serializes correctly")
    fun testGeoTimeParametersWithCoordinates() {
        val geoTime = GeoTimeParameters(
            timezone = "America/New_York",
            timezoneOffset = 300,
            locale = "en-US",
            languages = listOf("en-US"),
            latitude = 40.7128,
            longitude = -74.0060,
            accuracy = 50.0
        )
        val json = mapper.writeValueAsString(geoTime)
        val deserialized = mapper.readValue(json, GeoTimeParameters::class.java)
        // Jackson serializes doubles with default precision; check individual fields
        assertEquals(geoTime.timezone, deserialized.timezone)
        assertEquals(geoTime.timezoneOffset, deserialized.timezoneOffset)
        assertEquals(geoTime.locale, deserialized.locale)
        assertEquals(geoTime.languages, deserialized.languages)
        assertEquals(40.7128, deserialized.latitude!!, 0.01)
        assertEquals(-74.0060, deserialized.longitude!!, 0.01)
        assertEquals(50.0, deserialized.accuracy!!, 0.01)
    }

    // -- HardwareParameters ----------------------------------------------------

    @Test
    @DisplayName("creates HardwareParameters with valid values")
    fun testHardwareParametersCreation() {
        val hardware = HardwareParameters(
            hardwareConcurrency = 8,
            deviceMemory = 16,
            maxTouchPoints = 0,
            platform = "Win32",
            vendor = "Google Inc.",
            vendorSub = "",
            productSub = "20030107"
        )

        assertEquals(8, hardware.hardwareConcurrency)
        assertEquals(16, hardware.deviceMemory)
        assertEquals(0, hardware.maxTouchPoints)
        assertEquals("Win32", hardware.platform)
        assertEquals("Google Inc.", hardware.vendor)
    }

    @Test
    @DisplayName("rejects zero hardware concurrency")
    fun testHardwareParametersRejectsZeroConcurrency() {
        assertFailsWith<IllegalArgumentException> {
            HardwareParameters(hardwareConcurrency = 0, platform = "Win32")
        }
    }

    @Test
    @DisplayName("rejects negative hardware concurrency")
    fun testHardwareParametersRejectsNegativeConcurrency() {
        assertFailsWith<IllegalArgumentException> {
            HardwareParameters(hardwareConcurrency = -1, platform = "Win32")
        }
    }

    @Test
    @DisplayName("rejects blank platform")
    fun testHardwareParametersRejectsBlankPlatform() {
        assertFailsWith<IllegalArgumentException> {
            HardwareParameters(hardwareConcurrency = 8, platform = "")
        }
    }

    @Test
    @DisplayName("WINDOWS_DESKTOP preset has correct values")
    fun testWindowsDesktopPreset() {
        val windows = HardwareParameters.WINDOWS_DESKTOP
        assertEquals("Win32", windows.platform)
        assertEquals(8, windows.hardwareConcurrency)
        assertEquals(8, windows.deviceMemory)
        assertEquals("Google Inc.", windows.vendor)
    }

    @Test
    @DisplayName("MAC_LAPTOP preset has correct values")
    fun testMacLaptopPreset() {
        val mac = HardwareParameters.MAC_LAPTOP
        assertEquals("MacIntel", mac.platform)
        assertEquals(8, mac.hardwareConcurrency)
        assertEquals(16, mac.deviceMemory)
        assertEquals("Apple Computer, Inc.", mac.vendor)
    }

    @Test
    @DisplayName("LINUX_DESKTOP preset has correct values")
    fun testLinuxDesktopPreset() {
        val linux = HardwareParameters.LINUX_DESKTOP
        assertEquals("Linux x86_64", linux.platform)
        assertEquals(4, linux.hardwareConcurrency)
        assertEquals(8, linux.deviceMemory)
    }

    @Test
    @DisplayName("HardwareParameters with null deviceMemory is valid")
    fun testHardwareParametersWithNullDeviceMemory() {
        val hardware = HardwareParameters(
            hardwareConcurrency = 8,
            deviceMemory = null,
            platform = "Win32"
        )
        assertNull(hardware.deviceMemory)
        assertEquals(8, hardware.hardwareConcurrency)
    }

    // -- WebGLParameters -------------------------------------------------------

    @Test
    @DisplayName("creates WebGLParameters with valid values")
    fun testWebGLParametersCreation() {
        val webgl = WebGLParameters(
            vendor = "Google Inc. (Intel)",
            renderer = "ANGLE (Intel, Intel(R) UHD Graphics Direct3D11 vs_5_0 ps_5_0)",
            unmaskedVendor = "Intel Inc.",
            unmaskedRenderer = "Intel(R) UHD Graphics",
            shadingLanguageVersion = "WebGL GLSL ES 1.0",
            maxTextureSize = 16384,
            maxViewportDims = listOf(16384, 16384)
        )

        assertTrue(webgl.vendor.contains("Intel"))
        assertTrue(webgl.renderer.contains("ANGLE"))
        assertEquals("Intel Inc.", webgl.unmaskedVendor)
        assertEquals(16384, webgl.maxTextureSize)
    }

    @Test
    @DisplayName("rejects blank vendor")
    fun testWebGLParametersRejectsBlankVendor() {
        assertFailsWith<IllegalArgumentException> {
            WebGLParameters(vendor = "", renderer = "ANGLE")
        }
    }

    @Test
    @DisplayName("rejects blank renderer")
    fun testWebGLParametersRejectsBlankRenderer() {
        assertFailsWith<IllegalArgumentException> {
            WebGLParameters(vendor = "Google Inc.", renderer = "")
        }
    }

    @Test
    @DisplayName("INTEL_INTEGRATED preset has correct values")
    fun testIntelIntegratedPreset() {
        val intel = WebGLParameters.INTEL_INTEGRATED
        assertTrue(intel.vendor.contains("Intel"))
        assertTrue(intel.renderer.contains("Intel"))
        assertEquals("Intel Inc.", intel.unmaskedVendor)
    }

    @Test
    @DisplayName("NVIDIA_DISCRETE preset has correct values")
    fun testNvidiaDiscretePreset() {
        val nvidia = WebGLParameters.NVIDIA_DISCRETE
        assertTrue(nvidia.vendor.contains("NVIDIA"))
        assertTrue(nvidia.renderer.contains("NVIDIA"))
        assertEquals("NVIDIA Corporation", nvidia.unmaskedVendor)
    }

    @Test
    @DisplayName("APPLE_M1 preset has correct values")
    fun testAppleM1Preset() {
        val apple = WebGLParameters.APPLE_M1
        assertEquals("Apple Inc.", apple.vendor)
        assertEquals("Apple M1", apple.renderer)
        assertEquals("Apple Inc.", apple.unmaskedVendor)
    }

    // -- CanvasParameters ------------------------------------------------------

    @Test
    @DisplayName("creates CanvasParameters with a seed")
    fun testCanvasParametersWithSeed() {
        val canvas = CanvasParameters(fingerprintSeed = "test-seed")
        assertEquals("test-seed", canvas.fingerprintSeed)
    }

    @Test
    @DisplayName("DEFAULT canvas has no seed")
    fun testCanvasDefaultHasNoSeed() {
        val canvas = CanvasParameters.DEFAULT
        assertNull(canvas.fingerprintSeed)
    }

    @Test
    @DisplayName("CanvasParameters serializes to JSON and back")
    fun testCanvasParametersSerialization() {
        val canvas = CanvasParameters(fingerprintSeed = "seed-abc")
        val json = mapper.writeValueAsString(canvas)
        val deserialized = mapper.readValue(json, CanvasParameters::class.java)
        assertEquals(canvas, deserialized)
    }

    // -- MediaDevice -----------------------------------------------------------

    @Test
    @DisplayName("creates MediaDevice with valid kind")
    fun testMediaDeviceCreation() {
        val device = MediaDevice(
            deviceId = "default",
            label = "Default - Microphone (Realtek)",
            kind = "audioinput"
        )

        assertEquals("default", device.deviceId)
        assertTrue(device.label.contains("Microphone"))
        assertEquals("audioinput", device.kind)
    }

    @Test
    @DisplayName("rejects invalid device kind")
    fun testMediaDeviceRejectsInvalidKind() {
        assertFailsWith<IllegalArgumentException> {
            MediaDevice(deviceId = "dev1", label = "Test", kind = "invalid")
        }
    }

    @Test
    @DisplayName("rejects blank device ID")
    fun testMediaDeviceRejectsBlankDeviceId() {
        assertFailsWith<IllegalArgumentException> {
            MediaDevice(deviceId = "", label = "Test", kind = "audioinput")
        }
    }

    @Test
    @DisplayName("supports all three valid device kinds")
    fun testMediaDeviceValidKinds() {
        val audioInput = MediaDevice("1", "Mic", "audioinput")
        val audioOutput = MediaDevice("2", "Speaker", "audiooutput")
        val videoInput = MediaDevice("3", "Camera", "videoinput")

        assertEquals("audioinput", audioInput.kind)
        assertEquals("audiooutput", audioOutput.kind)
        assertEquals("videoinput", videoInput.kind)
    }

    // -- MediaParameters -------------------------------------------------------

    @Test
    @DisplayName("creates MediaParameters with devices")
    fun testMediaParametersCreation() {
        val media = MediaParameters(
            audioInputDevices = listOf(MediaDevice("mic1", "Microphone", "audioinput")),
            audioOutputDevices = listOf(MediaDevice("spk1", "Speakers", "audiooutput")),
            videoInputDevices = listOf(MediaDevice("cam1", "Webcam", "videoinput"))
        )

        assertEquals(1, media.audioInputDevices.size)
        assertEquals(1, media.audioOutputDevices.size)
        assertEquals(1, media.videoInputDevices.size)
    }

    @Test
    @DisplayName("empty MediaParameters is valid")
    fun testEmptyMediaParameters() {
        val media = MediaParameters()
        assertTrue(media.audioInputDevices.isEmpty())
        assertTrue(media.audioOutputDevices.isEmpty())
        assertTrue(media.videoInputDevices.isEmpty())
    }

    @Test
    @DisplayName("DESKTOP media preset has at least one device per category")
    fun testDesktopMediaPreset() {
        val desktop = MediaParameters.DESKTOP
        assertTrue(desktop.audioInputDevices.isNotEmpty(), "Should have at least one audio input")
        assertTrue(desktop.audioOutputDevices.isNotEmpty(), "Should have at least one audio output")
        assertTrue(desktop.videoInputDevices.isNotEmpty(), "Should have at least one video input")
    }

    // -- MiscParameters --------------------------------------------------------

    @Test
    @DisplayName("creates MiscParameters with Do Not Track")
    fun testMiscParametersWithDoNotTrack() {
        val misc = MiscParameters(
            doNotTrack = "1",
            cookieEnabled = true,
            pdfViewerEnabled = true
        )

        assertEquals("1", misc.doNotTrack)
        assertTrue(misc.cookieEnabled)
        assertTrue(misc.pdfViewerEnabled)
    }

    @Test
    @DisplayName("creates MiscParameters with disabled cookies")
    fun testMiscParametersWithDisabledCookies() {
        val misc = MiscParameters(
            cookieEnabled = false,
            pdfViewerEnabled = true
        )

        assertFalse(misc.cookieEnabled)
    }

    @Test
    @DisplayName("DEFAULT preset has no Do Not Track")
    fun testMiscDefaultPreset() {
        val misc = MiscParameters.DEFAULT
        assertNull(misc.doNotTrack)
        assertTrue(misc.cookieEnabled)
        assertTrue(misc.pdfViewerEnabled)
        assertTrue(misc.plugins.isEmpty())
        assertTrue(misc.mimeTypes.isEmpty())
    }

    // -- All parameters serialization round-trip -------------------------------

    @Test
    @DisplayName("all parameter types serialize to JSON and deserialize back")
    fun testAllParametersSerializationRoundTrip() {
        data class ParamPair(val obj: Any, val clazz: Class<*>)

        val params = listOf(
            ParamPair(ScreenParameters.DESKTOP_1920X1080, ScreenParameters::class.java),
            ParamPair(ViewportParameters.DESKTOP, ViewportParameters::class.java),
            ParamPair(GeoTimeParameters.CHINA, GeoTimeParameters::class.java),
            ParamPair(HardwareParameters.WINDOWS_DESKTOP, HardwareParameters::class.java),
            ParamPair(WebGLParameters.INTEL_INTEGRATED, WebGLParameters::class.java),
            ParamPair(CanvasParameters(fingerprintSeed = "test"), CanvasParameters::class.java),
            ParamPair(MediaParameters.DESKTOP, MediaParameters::class.java),
            ParamPair(MiscParameters.DEFAULT, MiscParameters::class.java),
        )

        params.forEach { (obj, clazz) ->
            val json = mapper.writeValueAsString(obj)
            val deserialized = mapper.readValue(json, clazz)
            assertEquals(obj, deserialized, "Failed round-trip for ${clazz.simpleName}")
        }
    }

    // -- Cross-parameter consistency (verified via init blocks) ----------------

    @Test
    @DisplayName("all preset combinations form a consistent fingerprint")
    fun testAllPresetsFormConsistentFingerprint() {
        // This verifies that the standard presets can coexist in a Fingerprint
        // without violating any init-block constraints
        val fingerprint = Fingerprint(
            browserType = ai.platon.pulsar.common.browser.BrowserType.PULSAR_CHROME,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
            screenParameters = ScreenParameters.DESKTOP_1920X1080,
            viewportParameters = ViewportParameters.DESKTOP,
            geoTimeParameters = GeoTimeParameters.US_EAST,
            hardwareParameters = HardwareParameters.WINDOWS_DESKTOP,
            webGLParameters = WebGLParameters.INTEL_INTEGRATED,
            canvasParameters = CanvasParameters.DEFAULT,
            mediaParameters = MediaParameters.DESKTOP,
            miscParameters = MiscParameters.DEFAULT
        )

        // Should not throw, and should pass validation
        val validator = FingerprintValidator()
        val result = validator.validate(fingerprint)
        assertTrue(result.isValid, "All presets should form a consistent fingerprint: ${result}")
        assertFalse(result.hasWarnings, "No warnings expected: ${result.warnings}")
    }
}
