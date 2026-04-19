package ai.platon.pulsar.skeleton.crawl.fetch.privacy

import ai.platon.pulsar.common.browser.fingerprint.Fingerprint
import ai.platon.pulsar.common.serialize.json.prettyPulsarObjectMapper
import org.junit.jupiter.api.Assertions.*

class PrivacyAgentTest {
    @org.junit.jupiter.api.Test
    fun testGetFingerprint() {
        val profile = BrowserProfile.createRandomTemp()
        val fingerprint = profile.fingerprint
        assertNotNull(fingerprint)
    }
    @org.junit.jupiter.api.Test
    fun testGetId() {
        val profile = BrowserProfile.createRandomTemp()
        val id = profile.id
        assertNotNull(id)
    }

    @org.junit.jupiter.api.Test
    fun testToJSON() {
        val profile = BrowserProfile.createRandomTemp()
        profile.fingerprint = Fingerprint.EXAMPLE
        val json = prettyPulsarObjectMapper().writeValueAsString(profile)
        val obj = prettyPulsarObjectMapper().readValue(json, BrowserProfile::class.java)
        assertEquals(profile, obj)
    }
}
