package ai.platon.pulsar.chrome

import ai.platon.pulsar.api.model.ChromeVersion
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Guards the `browser4-base` pin against `/json/version` payload changes.
 *
 * Chrome adds fields to its DevTools `/json/version` response over time — Chrome 156 added
 * `File-Path-Style`. `ChromeImpl` deserializes that payload with a plain [ObjectMapper]
 * (unknown properties fail by default), so a browser newer than the pinned model made every
 * navigation die with a bare `createDevTools` error while the CDP endpoint answered fine.
 *
 * The base library fixed this in 4.11.27; the version pin in `browser4-dependencies` has to
 * stay on a release that carries the fix, otherwise the driver cannot talk to the Chrome
 * installed on this machine (observed with Chrome/156.0.8078.73 on 2026-10-29).
 */
@DisplayName("ChromeVersion /json/version compatibility")
class ChromeVersionCompatibilityTest {

    private val mapper = ObjectMapper()

    @Test
    @DisplayName("parses a Chrome 156 payload that carries File-Path-Style")
    fun parsesPayloadWithUnknownField() {
        val json = """
            {
              "Browser": "Chrome/156.0.8078.73",
              "Protocol-Version": "1.3",
              "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.73 Safari/537.36",
              "V8-Version": "15.6.0",
              "WebKit-Version": "537.36 (@0)",
              "webSocketDebuggerUrl": "ws://127.0.0.1:9222/devtools/browser/2f6a",
              "File-Path-Style": "win"
            }
        """.trimIndent()

        val version = mapper.readValue(json, ChromeVersion::class.java)

        assertNotNull(version)
        assertEquals("Chrome/156.0.8078.73", version.browser)
        assertEquals("1.3", version.protocolVersion)
        assertEquals("ws://127.0.0.1:9222/devtools/browser/2f6a", version.webSocketDebuggerUrl)
        assertNotNull(version.userAgent)
    }

    @Test
    @DisplayName("ChromeVersion keeps tolerating unknown fields")
    fun modelIgnoresUnknownFields() {
        val annotation = ChromeVersion::class.java
            .getAnnotation(JsonIgnoreProperties::class.java)

        assertNotNull(annotation, "ChromeVersion lost @JsonIgnoreProperties — check the browser4-base pin")
        assertTrue(annotation.ignoreUnknown, "ChromeVersion must ignore unknown /json/version fields")
    }
}
