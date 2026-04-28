package ai.platon.pulsar.browser.common

import ai.platon.browser4.driver.common.InteractSettings
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
//        val settings2 = Gson().fromJson(json, InteractSettings::class.java)
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
//        val settings2 = Gson().fromJson(json, InteractSettings::class.java)
        assertEquals(settings.toString(), settings2.toString())
    }
}
