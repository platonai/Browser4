package ai.platon.pulsar.agentic.skills

import ai.platon.pulsar.agentic.skills.tools.SkillScriptToolExecutor
import ai.platon.pulsar.agentic.skills.tools.SkillToolTarget
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SkillExecutionToolsTest {

    @Test
    fun readReferenceRejectsPathTraversal() {
        runBlocking {
            SkillBootstrap().initialize()
            val executor = SkillScriptToolExecutor(SkillRegistry.instance)
            val target = SkillToolTarget(SkillContext(sessionId = "ut"))

            assertThrows<IllegalArgumentException> {
                executor.callFunctionOn(
                    domain = "skill",
                    functionName = "readReference",
                    args = mapOf("id" to "pdf", "path" to "references/../SKILL.md"),
                    receiver = target
                )
            }
        }
    }

    @Test
    fun runScriptIsDeniedWithoutAllowedTools() {
        runBlocking {
            // mcp-builder skill does not set allowed-tools; ensure execution is denied.
            SkillBootstrap().initialize()
            val executor = SkillScriptToolExecutor(SkillRegistry.instance)
            val target = SkillToolTarget(SkillContext(sessionId = "ut"))

            val ex = assertThrows<IllegalArgumentException> {
                executor.callFunctionOn(
                    domain = "skill",
                    functionName = "runScript",
                    args = mapOf(
                        "id" to "mcp-builder",
                        "path" to "scripts/connections.py",
                        "args" to emptyList<String>()
                    ),
                    receiver = target
                )
            }

            assertTrue(ex.message!!.contains("allowed-tools"))
        }
    }
}
