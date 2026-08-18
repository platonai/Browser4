package ai.platon.pulsar.boot.plugin

import ai.platon.pulsar.skeleton.plugin.PluginManifest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.core.env.Environment

class PluginLoadPolicyTest {

    private fun manifest(name: String = "plugin-a", defaultEnabled: Boolean = true): PluginManifest {
        return PluginManifest(
            name = name,
            version = "1.0.0",
            defaultEnabled = defaultEnabled,
        )
    }

    @Test
    fun `default-enabled plugin loads by default`() {
        val policy = PluginLoadPolicy(enableAll = false, enabledNames = emptySet(), disabledNames = emptySet())
        assertTrue(policy.isEnabled(manifest()))
        assertNull(policy.disabledReason(manifest()))
    }

    @Test
    fun `default-disabled plugin is skipped by default`() {
        val policy = PluginLoadPolicy(enableAll = false, enabledNames = emptySet(), disabledNames = emptySet())
        assertFalse(policy.isEnabled(manifest(defaultEnabled = false)))
        val reason = policy.disabledReason(manifest(defaultEnabled = false))
        assertTrue(reason!!.contains("opt-in"), "reason should explain how to enable: $reason")
        assertTrue(reason.contains("browser4.plugins.enable=plugin-a"))
    }

    @Test
    fun `enable-all activates default-disabled plugins`() {
        val policy = PluginLoadPolicy(enableAll = true, enabledNames = emptySet(), disabledNames = emptySet())
        assertTrue(policy.isEnabled(manifest(defaultEnabled = false)))
    }

    @Test
    fun `enable list activates a specific opt-in plugin`() {
        val policy = PluginLoadPolicy(
            enableAll = false,
            enabledNames = setOf("plugin-a"),
            disabledNames = emptySet(),
        )
        assertTrue(policy.isEnabled(manifest(defaultEnabled = false)))
        assertFalse(policy.isEnabled(manifest(name = "plugin-b", defaultEnabled = false)))
    }

    @Test
    fun `disable list blocks a default-enabled plugin`() {
        val policy = PluginLoadPolicy(
            enableAll = false,
            enabledNames = emptySet(),
            disabledNames = setOf("plugin-a"),
        )
        assertFalse(policy.isEnabled(manifest()))
        assertEquals("explicitly disabled via browser4.plugins.disable", policy.disabledReason(manifest()))
    }

    @Test
    fun `explicit disable wins over enable and enable-all`() {
        val policy = PluginLoadPolicy(
            enableAll = true,
            enabledNames = setOf("plugin-a"),
            disabledNames = setOf("plugin-a"),
        )
        assertFalse(policy.isEnabled(manifest()))
    }

    @Test
    fun `fromEnvironment reads overrides from Spring environment`() {
        val environment = Mockito.mock(Environment::class.java)
        Mockito.`when`(environment.getProperty("browser4.plugins.enable-all", Boolean::class.java, false))
            .thenReturn(false)
        Mockito.`when`(environment.getProperty("browser4.plugins.enable")).thenReturn(" plugin-a , plugin-b ")
        Mockito.`when`(environment.getProperty("browser4.plugins.disable")).thenReturn("plugin-c")

        val policy = PluginLoadPolicy.fromEnvironment(environment)

        assertTrue(policy.isEnabled(manifest(defaultEnabled = false)))
        assertTrue(policy.isEnabled(manifest(name = "plugin-b", defaultEnabled = false)))
        assertFalse(policy.isEnabled(manifest(name = "plugin-c")))
        assertFalse(policy.isEnabled(manifest(name = "plugin-d", defaultEnabled = false)))
    }
}
