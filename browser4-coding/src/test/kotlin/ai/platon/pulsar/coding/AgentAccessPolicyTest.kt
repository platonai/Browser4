package ai.platon.pulsar.coding

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [AgentAccessPolicy]: the preset matrix, the deny list, and the server-wide resolution.
 */
class AgentAccessPolicyTest {

    @AfterEach
    fun clearProperties() {
        System.clearProperty(AgentAccessPolicy.PROP_ACCESS)
        System.clearProperty(AgentAccessPolicy.PROP_ALLOW_EXTERNAL_ACCESS)
        System.clearProperty(AgentAccessPolicy.PROP_ALLOW_DESTRUCTIVE)
        System.clearProperty(AgentAccessPolicy.PROP_AUTO_APPROVE)
    }

    private fun policy(preset: AccessPreset) = AgentAccessPolicy(preset = preset)

    @Test
    @DisplayName("full allows every tool family")
    fun testFullAllowsEverything() {
        val p = policy(AccessPreset.FULL)
        assertTrue(p.allowsTool("coding", "read"))
        assertTrue(p.allowsTool("coding", "write"))
        assertTrue(p.allowsTool("coding", "mvnBuild"))
        assertTrue(p.allowsTool("coding", "shell"))
        assertTrue(p.allowsTool("coding", "runCode"))
        assertTrue(p.allowsFileMutation())
        assertTrue(p.allowsExec())
    }

    @Test
    @DisplayName("write allows file mutation but no shell or code execution")
    fun testWriteHasNoExec() {
        val p = policy(AccessPreset.WRITE)
        assertTrue(p.allowsTool("coding", "read"))
        assertTrue(p.allowsTool("coding", "write"))
        assertTrue(p.allowsTool("coding", "mvnBuild"))
        assertFalse(p.allowsTool("coding", "shell"))
        assertFalse(p.allowsTool("coding", "shellSetEnv"))
        assertFalse(p.allowsTool("coding", "runCode"))
        assertTrue(p.allowsFileMutation())
        assertFalse(p.allowsExec())
    }

    @Test
    @DisplayName("read-build allows the build entry points but no mutation or exec")
    fun testReadBuildAllowsBuildOnly() {
        val p = policy(AccessPreset.READ_BUILD)
        assertTrue(p.allowsTool("coding", "read"))
        assertTrue(p.allowsTool("coding", "mvnBuild"))
        assertTrue(p.allowsTool("coding", "verify"))
        assertTrue(p.allowsTool("coding", "validate"))
        assertFalse(p.allowsTool("coding", "write"))
        assertFalse(p.allowsTool("coding", "delete"))
        assertFalse(p.allowsTool("coding", "shell"))
        assertFalse(p.allowsFileMutation())
        assertFalse(p.allowsExec())
    }

    @Test
    @DisplayName("read allows reading only")
    fun testReadIsReadOnly() {
        val p = policy(AccessPreset.READ)
        assertTrue(p.allowsTool("coding", "read"))
        assertTrue(p.allowsTool("coding", "listDir"))
        assertTrue(p.allowsTool("coding", "classInfo"))
        // `protect` without a path only lists the current protections; the mutating form is gated
        // separately by allowsFileMutation() at the executor level.
        assertTrue(p.allowsTool("coding", "protect"))
        assertFalse(p.allowsTool("coding", "write"))
        assertFalse(p.allowsTool("coding", "mvnBuild"))
        assertFalse(p.allowsTool("coding", "shell"))
    }

    @Test
    @DisplayName("an unclassified coding method fails closed to full only")
    fun testUnclassifiedMethodFailsClosed() {
        assertNull(policy(AccessPreset.FULL).gateOf("notARealMethod"))
        assertTrue(policy(AccessPreset.FULL).allowsTool("coding", "notARealMethod"))
        assertFalse(policy(AccessPreset.WRITE).allowsTool("coding", "notARealMethod"))
        assertFalse(policy(AccessPreset.READ).allowsTool("coding", "notARealMethod"))
    }

    @Test
    @DisplayName("gateOf classifies the representative methods of every family")
    fun testGateOf() {
        val p = policy(AccessPreset.FULL)
        assertEquals(ToolGate.READ, p.gateOf("read"))
        assertEquals(ToolGate.BUILD, p.gateOf("mvnBuild"))
        assertEquals(ToolGate.MUTATE, p.gateOf("write"))
        assertEquals(ToolGate.EXEC, p.gateOf("shell"))
        assertNull(p.gateOf("readMeNot"))
    }

    @Test
    @DisplayName("denyTools supports *, domain.*, domain.method and bare method")
    fun testDenyTools() {
        val bare = AgentAccessPolicy(denyTools = setOf("write"))
        assertFalse(bare.allowsTool("coding", "write"))
        assertTrue(bare.allowsTool("coding", "read"))

        val qualified = AgentAccessPolicy(denyTools = setOf("coding.write"))
        assertFalse(qualified.allowsTool("coding", "write"))
        assertTrue(qualified.allowsTool("coding", "read"))

        val domainWildcard = AgentAccessPolicy(denyTools = setOf("coding.*"))
        assertFalse(domainWildcard.allowsTool("coding", "read"))
        assertTrue(domainWildcard.allowsTool("b4", "goto"))

        val everything = AgentAccessPolicy(denyTools = setOf("*"))
        assertFalse(everything.allowsTool("coding", "read"))
        assertFalse(everything.allowsTool("b4", "goto"))
    }

    @Test
    @DisplayName("non-coding domains are not governed by the preset")
    fun testOtherDomainsUnaffected() {
        assertTrue(policy(AccessPreset.READ).allowsTool("b4", "goto"))
        assertTrue(policy(AccessPreset.READ).allowsTool("system", "listTools"))
    }

    @Test
    @DisplayName("AccessPreset.parse accepts aliases and rejects unknown values")
    fun testPresetParsing() {
        assertEquals(AccessPreset.FULL, AccessPreset.parse("full"))
        assertEquals(AccessPreset.FULL, AccessPreset.parse(" FULL "))
        assertEquals(AccessPreset.WRITE, AccessPreset.parse("write"))
        assertEquals(AccessPreset.READ_BUILD, AccessPreset.parse("read-build"))
        assertEquals(AccessPreset.READ_BUILD, AccessPreset.parse("read_build"))
        assertEquals(AccessPreset.READ, AccessPreset.parse("READ"))
        assertNull(AccessPreset.parse(null))
        assertNull(AccessPreset.parse("  "))
        assertNull(AccessPreset.parse("readonly"))
    }

    @Test
    @DisplayName("serverDefault resolves the preset from browser4.agent.access")
    fun testServerDefaultPreset() {
        val initial = AgentAccessPolicy.serverDefault
        assertEquals(AccessPreset.FULL, initial.preset)
        assertFalse(initial.allowExternalAccess)
        assertTrue(initial.allowDestructive)

        System.setProperty(AgentAccessPolicy.PROP_ACCESS, "read")
        assertEquals(AccessPreset.READ, AgentAccessPolicy.serverDefault.preset)
    }

    @Test
    @DisplayName("serverDefault keeps the legacy switches working")
    fun testServerDefaultLegacySwitches() {
        System.setProperty(AgentAccessPolicy.PROP_ALLOW_EXTERNAL_ACCESS, "true")
        System.setProperty(AgentAccessPolicy.PROP_ALLOW_DESTRUCTIVE, "false")
        val p = AgentAccessPolicy.serverDefault
        assertTrue(p.allowExternalAccess)
        assertFalse(p.allowDestructive)
    }

    @Test
    @DisplayName("an unknown access preset falls back to full instead of narrowing silently")
    fun testServerDefaultUnknownPreset() {
        System.setProperty(AgentAccessPolicy.PROP_ACCESS, "readonly")
        assertEquals(AccessPreset.FULL, AgentAccessPolicy.serverDefault.preset)
    }

    @Test
    @DisplayName("automatic approval widens a preset only up to its ceiling, and is audited")
    fun testAutoApprovalWidensUpToCeiling() {
        val policy = AgentAccessPolicy(preset = AccessPreset.READ, autoApproveUpTo = AccessPreset.WRITE)

        assertTrue(policy.allowsFileMutation(), "the write ceiling must reach the file-system layer")
        assertFalse(policy.allowsExec(), "a write ceiling must not enable the shell")
        assertTrue(policy.allowsTool("coding", "write"))
        assertTrue(policy.allowsTool("coding", "mvnBuild"))
        assertFalse(policy.allowsTool("coding", "shell"))

        assertNull(policy.denial("coding", "write"))
        val shell = policy.denial("coding", "shell")
        assertNotNull(shell)
        val shellMessage = shell!!.message ?: ""
        assertTrue(
            shellMessage.contains("autoApprove=full"),
            "a refusal must tell the caller how to approve it: $shellMessage"
        )

        val audit = PermissionAudit.snapshot()
        assertTrue(
            audit.keys.any { it.startsWith("coding.write:${PermissionAudit.CODE_AUTO_APPROVED}") },
            "an automatic approval must leave an audit entry: $audit"
        )
    }

    @Test
    @DisplayName("automatic approval never overrides an explicit denyTools entry")
    fun testAutoApprovalNeverOverridesDenyTools() {
        val policy = AgentAccessPolicy(
            preset = AccessPreset.FULL,
            denyTools = setOf("coding.shell"),
            autoApproveUpTo = AccessPreset.FULL,
        )

        assertFalse(policy.allowsTool("coding", "shell"))
        val denial = policy.denial("coding", "shell")
        assertNotNull(denial)
        val denialMessage = denial!!.message ?: ""
        assertTrue(
            denialMessage.contains("denyTools"),
            "an explicit denial must stay explicit: $denialMessage"
        )
    }

    @Test
    @DisplayName("automatic approval is off unless it is configured")
    fun testAutoApprovalDefaultsOff() {
        assertNull(AgentAccessPolicy().autoApproveUpTo)
        assertNull(AgentAccessPolicy.serverDefault.autoApproveUpTo)

        System.setProperty(AgentAccessPolicy.PROP_AUTO_APPROVE, "write")
        assertEquals(AccessPreset.WRITE, AgentAccessPolicy.serverDefault.autoApproveUpTo)

        System.setProperty(AgentAccessPolicy.PROP_AUTO_APPROVE, "whatever")
        assertNull(
            AgentAccessPolicy.serverDefault.autoApproveUpTo,
            "an unknown ceiling must stay off rather than widen silently"
        )
    }

    @Test
    @DisplayName("a task that names neither override inherits both server-wide values")
    fun testTaskOverridesInheritServerDefaults() {
        val server = AgentAccessPolicy(preset = AccessPreset.READ, autoApproveUpTo = AccessPreset.WRITE)

        val inherited = server.withTaskOverrides(null, null)
        assertEquals(AccessPreset.READ, inherited.preset)
        assertEquals(AccessPreset.WRITE, inherited.autoApproveUpTo)

        val overridden = server.withTaskOverrides(AccessPreset.READ_BUILD, AccessPreset.FULL)
        assertEquals(AccessPreset.READ_BUILD, overridden.preset)
        assertEquals(AccessPreset.FULL, overridden.autoApproveUpTo)

        // One override does not drag the other along: a task that only narrows the preset keeps
        // the server-wide ceiling, and a task that only approves keeps the server-wide preset.
        assertEquals(
            AccessPreset.WRITE,
            server.withTaskOverrides(AccessPreset.FULL, null).autoApproveUpTo
        )
        assertEquals(
            AccessPreset.READ,
            server.withTaskOverrides(null, AccessPreset.FULL).preset
        )
    }

    @Test
    @DisplayName("skill discovery and documentation are reads; running, deploying and reloading skills need full")
    fun testSkillDomainIsGatedPerMethod() {
        val read = policy(AccessPreset.READ)
        assertTrue(read.allowsTool("skill", "list"))
        assertTrue(read.allowsTool("skill", "info"), "info only inspects a skill, so it is a read")
        assertTrue(read.allowsTool("skill", "activate"))
        assertTrue(read.allowsTool("skill", "readDocumentation"))
        assertTrue(read.allowsTool("skill", "readReference"))
        assertFalse(read.allowsTool("skill", "run"))
        assertFalse(read.allowsTool("skill", "runScript"))
        assertFalse(read.allowsTool("skill", "install"))
        assertFalse(read.allowsTool("skill", "uninstall"))
        assertFalse(read.allowsTool("skill", "reload"), "reload re-reads files outside the workspace")

        val full = policy(AccessPreset.FULL)
        assertTrue(full.allowsTool("skill", "run"))
        assertTrue(full.allowsTool("skill", "install"))
        assertTrue(full.allowsTool("skill", "reload"))
    }

    @Test
    @DisplayName("a skill method missing from the matrix fails closed even under full-less presets")
    fun testUnclassifiedSkillMethodFailsClosed() {
        assertTrue(policy(AccessPreset.FULL).allowsTool("skill", "unclassified"))
        assertFalse(policy(AccessPreset.WRITE).allowsTool("skill", "unclassified"))
        assertFalse(policy(AccessPreset.READ).allowsTool("skill", "unclassified"))
    }

    @Test
    @DisplayName("an unknown domain requires full until it is classified")
    fun testUnknownDomainRequiresFull() {
        assertTrue(policy(AccessPreset.FULL).allowsTool("plugin.foo", "run"))
        assertFalse(policy(AccessPreset.WRITE).allowsTool("plugin.foo", "run"))
        assertFalse(policy(AccessPreset.READ).allowsTool("plugin.foo", "run"))
    }

    @Test
    @DisplayName("the built-in browsing domains stay available under every preset")
    fun testBrowsingDomainsAreUngated() {
        for (preset in AccessPreset.values()) {
            val p = policy(preset)
            assertTrue(p.allowsTool("tab", "navigate"), "tab is ungated under ${preset.id}")
            assertTrue(p.allowsTool("browser", "snapshot"), "browser is ungated under ${preset.id}")
            assertTrue(p.allowsTool("b4", "run"), "b4 is ungated under ${preset.id}")
            assertTrue(p.allowsTool("system", "taskComplete"), "system is ungated under ${preset.id}")
        }
        // An explicit denial still wins over the exemption.
        assertFalse(AgentAccessPolicy(denyTools = setOf("b4.run")).allowsTool("b4", "run"))
    }

    @Test
    @DisplayName("denial refuses skill execution under read and explains the preset to widen")
    fun testDenialRefusesSkillExecutionUnderRead() {
        val read = policy(AccessPreset.READ)
        assertNull(read.denial("skill", "list"))
        val denied = read.denial("skill", "run")
        assertNotNull(denied)
        val message = denied!!.message.orEmpty()
        assertTrue(message.contains("access preset 'read'"), message)
        assertTrue(message.contains("autoApprove=full"), message)

        assertNull(policy(AccessPreset.FULL).denial("skill", "run"))
    }
}
