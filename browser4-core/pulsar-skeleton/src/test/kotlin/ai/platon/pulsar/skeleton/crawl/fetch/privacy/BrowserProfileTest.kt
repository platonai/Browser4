package ai.platon.pulsar.skeleton.crawl.fetch.privacy

import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.browser.fingerprint.Fingerprint
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BrowserProfileTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun createUsesExistingFingerprintWhenPresent() {
        val profileDir = tempDir.resolve("existing-profile")
        Files.createDirectories(profileDir)
        val fingerprintFile = profileDir.resolve("fingerprint.json")
        val existingFingerprint = Fingerprint(BrowserType.PLAYWRIGHT_CHROME).apply {
            userAgent = "custom-agent"
        }
        pulsarObjectMapper().writeValue(fingerprintFile.toFile(), existingFingerprint)

        val profile = BrowserProfile.create(BrowserType.PULSAR_CHROME, profileDir)

        assertEquals(BrowserType.PULSAR_CHROME, profile.fingerprint.browserType)
        assertEquals("custom-agent", profile.fingerprint.userAgent)
        assertEquals(fingerprintFile.toString(), profile.fingerprint.source)
    }
}

