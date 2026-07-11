package ai.platon.browser4.api.model

import ai.platon.browser4.api.DelayPreset
import ai.platon.browser4.api.InteractSettings
import ai.platon.pulsar.common.browser.InteractLevel
import ai.platon.pulsar.common.config.CapabilityTypes
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BrowserSettingsTests {

    @Test
    fun testInteractSettings() {
        val settings = InteractSettings()
        settings.overrideSystemProperties()

        val json = System.getProperty(CapabilityTypes.BROWSER_INTERACT_SETTINGS)
        assertNotNull(json)

        val settings2: InteractSettings = pulsarObjectMapper().readValue(json)
        assertEquals(settings.toString(), settings2.toString())
    }

    @Test
    fun testDelayPolicy() {
        val settings = InteractSettings()
        val delayPolicy = settings.generateRestrictedDelayPolicy()

        assertNotNull(delayPolicy[""])
        assertNotNull(delayPolicy["default"])
        delayPolicy.values.forEach { assertTrue(it.first >= 50, "range: $it") }
        delayPolicy.values.forEach { assertTrue(it.last <= 2000, "range: $it") }
    }

    @Test
    fun testDelayPreset() {
        val settings = InteractSettings()
        val defaultPolicy = settings.generateRestrictedDelayPolicy().toMap()
        val fastestPolicy = settings.applyDelayPreset(DelayPreset.FASTEST).generateRestrictedDelayPolicy()

        assertEquals(10..10, fastestPolicy["gap"])
        assertEquals(10..10, fastestPolicy["type"])
        assertEquals(fastestPolicy["default"], fastestPolicy[""])

        val fastPolicy = settings.applyDelayPreset(DelayPreset.FAST).generateRestrictedDelayPolicy()
        assertTrue((fastPolicy["type"]?.last ?: Int.MAX_VALUE) < (defaultPolicy["type"]?.last ?: Int.MIN_VALUE))
        assertEquals(fastPolicy["default"], fastPolicy[""])

        val stealthPolicy = settings.applyDelayPreset(DelayPreset.STEALTH).generateRestrictedDelayPolicy()
        assertTrue((stealthPolicy["gap"]?.first ?: Int.MIN_VALUE) > (defaultPolicy["gap"]?.first ?: Int.MAX_VALUE))
        assertEquals(stealthPolicy["default"], stealthPolicy[""])

        settings.applyDelayPreset(DelayPreset.DEFAULT)
        assertEquals(defaultPolicy["type"], settings.delayPolicy["type"])
        assertEquals(settings.delayPolicy["default"], settings.delayPolicy[""])
    }

    @Test
    fun testFastestDelayPolicy() {
        val policy = InteractSettings.FASTEST_DELAY_POLICY

        assertEquals(10..10, policy["gap"])
        assertEquals(10..10, policy["type"])
        assertEquals(policy["default"], policy[""])
        policy.values.forEach { assertEquals(10..10, it) }
    }

    @Test
    fun testCreateLevelDelayPresetMapping() {
        val fastest = InteractSettings.create(InteractLevel.FASTEST).delayPolicy
        val fast = InteractSettings.create(InteractLevel.FASTER).delayPolicy
        val normal = InteractSettings.create(InteractLevel.DEFAULT).delayPolicy
        val stealth = InteractSettings.create(InteractLevel.BEST_DATA).delayPolicy

        assertEquals(10..10, fastest["gap"])
        assertEquals(10..10, fastest["type"])
        assertEquals(fastest["default"], fastest[""])
        assertTrue((fast["type"]?.last ?: Int.MAX_VALUE) < (normal["type"]?.last ?: Int.MIN_VALUE))
        assertTrue((normal["type"]?.last ?: Int.MAX_VALUE) < (stealth["type"]?.last ?: Int.MIN_VALUE))
        assertEquals(fast["default"], fast[""])
        assertEquals(normal["default"], normal[""])
        assertEquals(stealth["default"], stealth[""])
    }

    @Test
    fun testTimeoutPolicy() {
        val settings = InteractSettings()
        val timeoutPolicy = settings.generateRestrictedTimeoutPolicy()

        assertNotNull(timeoutPolicy[""])
        assertNotNull(timeoutPolicy["default"])
        timeoutPolicy.values.forEach { assertTrue(it >= settings.minTimeout, "timeout: $it") }
        timeoutPolicy.values.forEach { assertTrue(it <= settings.maxTimeout, "timeout: $it") }
    }

    @Test
    fun testInteractSettingsJson() {
        val settings = InteractSettings()
        val json = settings.toJson()
        assertNotNull(json)

        val settings2: InteractSettings = pulsarObjectMapper().readValue(json)
        assertNotNull(settings2)
        assertEquals(settings.toString(), settings2.toString())
    }

    @Test
    fun testOverrideConfiguration() {
        val settings = InteractSettings()
        val conf = MutableConfig()
        settings.overrideConfiguration(conf)

        val json = conf.get(CapabilityTypes.BROWSER_INTERACT_SETTINGS)
        assertNotNull(json)

        val settings2: InteractSettings = pulsarObjectMapper().readValue(json)
        assertEquals(settings.toString(), settings2.toString())
    }
}
