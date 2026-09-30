package ai.platon.pulsar.agentic.inference

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [RuntimeConfigRegistry] — the whitelist, validation, and
 * override storage backing the unified `/api/config/{key}` REST interface.
 */
class RuntimeConfigRegistryTest {

    @AfterEach
    fun clearOverrides() {
        RuntimeConfigRegistry.supportedKeys().forEach { RuntimeConfigRegistry.clearOverride(it) }
    }

    @Test
    @DisplayName("Registry whitelists both token keys")
    fun testSupportedKeysContainTokenKeys() {
        val keys = RuntimeConfigRegistry.supportedKeys()
        assertTrue(RequestTokenLimiter.CONFIG_KEY in keys)
        assertTrue(AgentTokenBudget.CONFIG_KEY in keys)
        assertTrue(RuntimeConfigRegistry.isSupported(RequestTokenLimiter.CONFIG_KEY))
        assertTrue(RuntimeConfigRegistry.isSupported(AgentTokenBudget.CONFIG_KEY))
    }

    @Test
    @DisplayName("Unknown keys are rejected")
    fun testUnknownKeyRejected() {
        assertFalse(RuntimeConfigRegistry.isSupported("some.random.key"))
        assertNull(RuntimeConfigRegistry.keyDef("some.random.key"))
        assertFailsWith<IllegalArgumentException> {
            RuntimeConfigRegistry.setOverride("some.random.key", "100")
        }
    }

    @Test
    @DisplayName("Key defs carry non-blank descriptions and defaults")
    fun testKeyDefsAreWellFormed() {
        RuntimeConfigRegistry.KEY_DEFS.forEach { def ->
            assertTrue(def.key.isNotBlank())
            assertTrue(def.description.isNotBlank())
            assertTrue(def.defaultValue.toLong() > 0)
            assertEquals(def.key, def.key.trim())
        }
    }

    @Test
    @DisplayName("setOverride stores and returns the normalized value")
    fun testSetOverrideStoresNormalizedValue() {
        val normalized = RuntimeConfigRegistry.setOverride(RequestTokenLimiter.CONFIG_KEY, " 800000 ")
        assertEquals("800000", normalized)
        assertEquals("800000", RuntimeConfigRegistry.getOverride(RequestTokenLimiter.CONFIG_KEY))
        assertEquals(800000, RuntimeConfigRegistry.getOverrideAsInt(RequestTokenLimiter.CONFIG_KEY))
    }

    @Test
    @DisplayName("'unlimited' (any case) is normalized to 0")
    fun testUnlimitedNormalizedToZero() {
        assertEquals("0", RuntimeConfigRegistry.setOverride(RequestTokenLimiter.CONFIG_KEY, "UNLIMITED"))
        assertEquals(0, RuntimeConfigRegistry.getOverrideAsInt(RequestTokenLimiter.CONFIG_KEY))
        assertEquals("0", RuntimeConfigRegistry.setOverride(AgentTokenBudget.CONFIG_KEY, "Unlimited"))
        assertEquals(0L, RuntimeConfigRegistry.getOverrideAsLong(AgentTokenBudget.CONFIG_KEY))
    }

    @Test
    @DisplayName("Invalid values are rejected with IllegalArgumentException")
    fun testInvalidValuesRejected() {
        listOf("-1", "abc", "", "1.5").forEach { raw ->
            assertFailsWith<IllegalArgumentException>(
                message = "Value '$raw' should be rejected"
            ) {
                RuntimeConfigRegistry.setOverride(RequestTokenLimiter.CONFIG_KEY, raw)
            }
            assertNull(RuntimeConfigRegistry.getOverride(RequestTokenLimiter.CONFIG_KEY))
        }
    }

    @Test
    @DisplayName("clearOverride falls back to null")
    fun testClearOverride() {
        RuntimeConfigRegistry.setOverride(AgentTokenBudget.CONFIG_KEY, "9000000")
        assertEquals(9000000L, RuntimeConfigRegistry.getOverrideAsLong(AgentTokenBudget.CONFIG_KEY))
        RuntimeConfigRegistry.clearOverride(AgentTokenBudget.CONFIG_KEY)
        assertNull(RuntimeConfigRegistry.getOverride(AgentTokenBudget.CONFIG_KEY))
    }

    @Test
    @DisplayName("A rejected value does not clobber an existing override")
    fun testRejectedValueKeepsPreviousOverride() {
        RuntimeConfigRegistry.setOverride(RequestTokenLimiter.CONFIG_KEY, "500000")
        assertFailsWith<IllegalArgumentException> {
            RuntimeConfigRegistry.setOverride(RequestTokenLimiter.CONFIG_KEY, "nope")
        }
        assertEquals(500000, RuntimeConfigRegistry.getOverrideAsInt(RequestTokenLimiter.CONFIG_KEY))
    }

    @Test
    @DisplayName("Long values beyond Int range are accepted for the budget key")
    fun testLongBudgetValueAccepted() {
        RuntimeConfigRegistry.setOverride(AgentTokenBudget.CONFIG_KEY, "10000000000")
        assertEquals(10_000_000_000L, RuntimeConfigRegistry.getOverrideAsLong(AgentTokenBudget.CONFIG_KEY))
    }
}
