package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

/**
 * Tests for [Authorizer]: the single decision point for every coding effect.
 *
 * The cases pin the fail-closed rules the later wiring (A3/A4) depends on: a missing grant, an
 * out-of-scope target, a request no single grant covers, an expired grant and a breached budget.
 */
class AuthorizerTest {

    @TempDir
    lateinit var workspace: Path

    private val now: Instant = Instant.parse("2026-10-31T00:00:00Z")

    private fun authorizer(vararg caps: Capability, at: Instant = now): Authorizer =
        Authorizer(caps.toList()) { at }

    private fun readCapability(): Capability = Capability(
        id = CapabilityId("cap-ws-read"),
        effects = setOf(Effect.FS_READ),
        scope = Scope(readPaths = setOf(workspace)),
    )

    private fun inside(name: String): Target.PathTarget = Target.PathTarget(workspace.resolve(name))

    private fun deny(decision: Decision): Decision.Deny {
        assertTrue(decision is Decision.Deny, "expected a refusal, got $decision")
        return decision as Decision.Deny
    }

    @Test
    @DisplayName("a request with no effects is always allowed")
    fun testNoEffectsIsAllowed() {
        assertEquals(Decision.Allow, Authorizer(emptyList()).decide(Request("coding.read", emptySet())))
    }

    @Test
    @DisplayName("an effect no capability grants is refused with the needed request")
    fun testMissingCapabilityIsRefused() {
        val decision = deny(
            Authorizer(emptyList()).decide(
                Request("coding.write", setOf(Effect.FS_WRITE), listOf(inside("a.txt"))),
            ),
        )
        assertEquals(PermissionAudit.CODE_TOOL_DENIED, decision.code)
        assertEquals(Effect.FS_WRITE, decision.effect)
        assertEquals(setOf(Effect.FS_WRITE), decision.needed.effects)
        assertTrue(decision.needed.targets.isNotEmpty())
    }

    @Test
    @DisplayName("a path inside a granted read root is allowed")
    fun testPathInsideScopeIsAllowed() {
        val request = Request("coding.read", setOf(Effect.FS_READ), listOf(inside("notes.md")))
        assertEquals(Decision.Allow, authorizer(readCapability()).decide(request))
    }

    @Test
    @DisplayName("a path outside every granted root is refused")
    fun testPathOutsideScopeIsRefused() {
        val outside = Target.PathTarget(workspace.parent.resolve("outside.txt"))
        val decision = deny(
            authorizer(readCapability()).decide(
                Request("coding.read", setOf(Effect.FS_READ), listOf(outside)),
            ),
        )
        assertEquals(PermissionAudit.CODE_PATH_OUTSIDE_WORKSPACE, decision.code)
        assertEquals(Effect.FS_READ, decision.effect)
    }

    @Test
    @DisplayName("a read grant does not imply a write grant")
    fun testReadScopeDoesNotGrantWrite() {
        val decision = deny(
            authorizer(readCapability()).decide(
                Request("coding.write", setOf(Effect.FS_WRITE), listOf(inside("a.txt"))),
            ),
        )
        assertEquals(PermissionAudit.CODE_TOOL_DENIED, decision.code)
        assertEquals(Effect.FS_WRITE, decision.effect)
    }

    @Test
    @DisplayName("an expired grant no longer applies")
    fun testExpiredCapabilityIsRefused() {
        val expired = readCapability().copy(expiresAt = now.minusSeconds(1))
        val decision = deny(
            authorizer(expired).decide(
                Request("coding.read", setOf(Effect.FS_READ), listOf(inside("notes.md"))),
            ),
        )
        assertEquals(PermissionAudit.CODE_TOOL_DENIED, decision.code)
    }

    @Test
    @DisplayName("a path that does not exist yet is judged by its nearest existing parent")
    fun testNonexistentPathUnderGrantedRootIsAllowed() {
        val request = Request(
            "coding.write",
            setOf(Effect.FS_WRITE),
            listOf(Target.PathTarget(workspace.resolve("a/b/c.txt"))),
        )
        val cap = readCapability().copy(
            effects = setOf(Effect.FS_WRITE),
            scope = Scope(writePaths = setOf(workspace)),
        )
        assertEquals(Decision.Allow, authorizer(cap).decide(request))
    }

    @Test
    @DisplayName("one grant must cover the whole request, not one effect at a time")
    fun testRequestNeedsASingleCoveringGrant() {
        val readOnly = readCapability()
        val writeOnly = readCapability().copy(
            id = CapabilityId("cap-ws-write"),
            effects = setOf(Effect.FS_WRITE),
            scope = Scope(writePaths = setOf(workspace)),
        )
        val request = Request(
            "coding.edit",
            setOf(Effect.FS_READ, Effect.FS_WRITE),
            listOf(inside("a.txt")),
        )
        assertEquals(PermissionAudit.CODE_TOOL_DENIED, deny(authorizer(readOnly, writeOnly).decide(request)).code)
    }

    @Test
    @DisplayName("a bounded grant reports the spend it permitted")
    fun testBoundedGrantReturnsAllowWithBudget() {
        val cap = Capability(
            id = CapabilityId("cap-build"),
            effects = setOf(Effect.FS_EXEC),
            scope = Scope(commands = setOf("mvn")),
            limits = Limits(execCalls = 5),
        )
        val request = Request(
            "coding.shell",
            setOf(Effect.FS_EXEC),
            listOf(Target.CommandTarget("mvn")),
            Cost(execCalls = 1),
        )
        val decision = authorizer(cap).decide(request)
        assertTrue(decision is Decision.AllowWithBudget, "expected a budgeted allow, got $decision")
        decision as Decision.AllowWithBudget
        assertEquals(CapabilityId("cap-build"), decision.capability)
        assertEquals(Cost(execCalls = 1), decision.cost)
    }

    @Test
    @DisplayName("a request that breaches the budget is refused")
    fun testBudgetExceededIsRefused() {
        val cap = Capability(
            id = CapabilityId("cap-build"),
            effects = setOf(Effect.FS_EXEC),
            scope = Scope(commands = setOf("mvn")),
            limits = Limits(execCalls = 2),
        )
        val request = Request(
            "coding.shell",
            setOf(Effect.FS_EXEC),
            listOf(Target.CommandTarget("mvn")),
            Cost(execCalls = 2),
        )
        val decision = deny(
            authorizer(cap).decide(request, consumed = mapOf(cap.id to Cost(execCalls = 1))),
        )
        assertEquals(PermissionAudit.CODE_BUDGET_EXCEEDED, decision.code)
    }

    @Test
    @DisplayName("hosts match case-insensitively and ignore a trailing dot")
    fun testHostScopeMatching() {
        val cap = Capability(
            id = CapabilityId("cap-net"),
            effects = setOf(Effect.NET_CONNECT),
            scope = Scope(hosts = setOf("api.example.com")),
        )
        assertEquals(
            Decision.Allow,
            authorizer(cap).decide(
                Request("coding.shell", setOf(Effect.NET_CONNECT), listOf(Target.HostTarget("API.EXAMPLE.COM."))),
            ),
        )
        assertEquals(
            PermissionAudit.CODE_TOOL_DENIED,
            deny(
                authorizer(cap).decide(
                    Request("coding.shell", setOf(Effect.NET_CONNECT), listOf(Target.HostTarget("evil.example.org"))),
                ),
            ).code,
        )
    }

    @Test
    @DisplayName("commands match by basename without a launcher suffix")
    fun testCommandScopeMatching() {
        val cap = Capability(
            id = CapabilityId("cap-exec"),
            effects = setOf(Effect.FS_EXEC),
            scope = Scope(commands = setOf("git")),
        )
        assertEquals(
            Decision.Allow,
            authorizer(cap).decide(
                Request("coding.shell", setOf(Effect.FS_EXEC), listOf(Target.CommandTarget("/usr/bin/git.exe"))),
            ),
        )
        assertEquals(
            PermissionAudit.CODE_COMMAND_NOT_ALLOWED,
            deny(
                authorizer(cap).decide(
                    Request("coding.shell", setOf(Effect.FS_EXEC), listOf(Target.CommandTarget("curl"))),
                ),
            ).code,
        )
    }

    @Test
    @DisplayName("an effect without a matching target is refused, never treated as no target")
    fun testEffectWithoutTargetIsRefused() {
        val cap = readCapability()
        val decision = deny(
            authorizer(cap).decide(Request("coding.read", setOf(Effect.FS_READ), emptyList())),
        )
        assertEquals(PermissionAudit.CODE_TOOL_DENIED, decision.code)
        assertEquals(Effect.FS_READ, decision.effect)
    }
}
