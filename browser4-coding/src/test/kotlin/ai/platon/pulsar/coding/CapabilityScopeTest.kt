package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tests for the capability data model: effect parsing, grant lifetime, cost arithmetic and target
 * normalization. These are the primitives [Authorizer] builds its decisions on.
 */
class CapabilityScopeTest {

    private val now: Instant = Instant.parse("2026-10-31T00:00:00Z")

    @Test
    @DisplayName("effect ids parse in dotted and underscored form, unknown values return null")
    fun testEffectParsing() {
        assertEquals(Effect.FS_READ, Effect.parse("fs.read"))
        assertEquals(Effect.FS_READ, Effect.parse("FS_READ"))
        assertEquals(Effect.NET_CONNECT, Effect.parse(" net_connect "))
        assertNull(Effect.parse("fs.delete"))
        assertNull(Effect.parse(null))
        assertNull(Effect.parse("  "))
    }

    @Test
    @DisplayName("a grant without an expiry is always active; an expired one is not")
    fun testCapabilityLifetime() {
        val cap = Capability(CapabilityId("cap"), setOf(Effect.FS_READ))
        assertTrue(cap.isActiveAt(now))
        assertFalse(cap.copy(expiresAt = now.minusSeconds(1)).isActiveAt(now))
        assertTrue(cap.copy(expiresAt = now.plusSeconds(60)).isActiveAt(now))
    }

    @Test
    @DisplayName("grants reports only the declared effects")
    fun testGrants() {
        val cap = Capability(CapabilityId("cap"), setOf(Effect.FS_READ))
        assertTrue(cap.grants(Effect.FS_READ))
        assertFalse(cap.grants(Effect.FS_WRITE))
    }

    @Test
    @DisplayName("cost adds up and compares against a partial limit set")
    fun testCostArithmetic() {
        val total = Cost(durationSec = 1, writeBytes = 2, execCalls = 3) +
            Cost(durationSec = 4, writeBytes = 5, execCalls = 6)
        assertEquals(Cost(durationSec = 5, writeBytes = 7, execCalls = 9), total)

        assertFalse(total.exceeds(Limits.NONE))
        assertFalse(total.exceeds(Limits(execCalls = 9)), "exactly at the limit is still allowed")
        assertTrue(total.exceeds(Limits(execCalls = 8)))
        assertTrue(total.exceeds(Limits(writeBytes = 6)))
        assertFalse(total.exceeds(Limits(writeBytes = 7)))
    }

    @Test
    @DisplayName("limits are unbounded only when no dimension is set")
    fun testLimitsUnbounded() {
        assertTrue(Limits.NONE.isUnbounded)
        assertFalse(Limits(execCalls = 0).isUnbounded)
    }

    @Test
    @DisplayName("host targets ignore case and a trailing dot")
    fun testHostTargetNormalization() {
        assertEquals("api.example.com", Target.HostTarget("API.Example.com.").normalized)
    }

    @Test
    @DisplayName("command targets reduce to a lower-cased basename without a launcher suffix")
    fun testCommandTargetNormalization() {
        assertEquals("git", Target.CommandTarget("/usr/bin/git").normalized)
        assertEquals("git", Target.CommandTarget("C:\\Program Files\\Git\\cmd\\GIT.EXE").normalized)
        assertEquals("mvnw", Target.CommandTarget("mvnw.cmd").normalized)
        assertEquals("powershell", Target.CommandTarget("PowerShell.exe").normalized)
    }

    @Test
    @DisplayName("an empty scope grants nothing")
    fun testEmptyScope() {
        assertTrue(Scope.EMPTY.readPaths.isEmpty())
        assertTrue(Scope.EMPTY.hosts.isEmpty())
        assertTrue(Scope.EMPTY.commands.isEmpty())
    }

    @Test
    @DisplayName("capability id renders as its value")
    fun testCapabilityIdToString() {
        assertEquals("cap-ws-write", CapabilityId("cap-ws-write").toString())
    }
}
