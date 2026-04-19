package ai.platon.pulsar.agentic.skills.tools

import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.skills.DefinitionBackedSkill
import ai.platon.pulsar.agentic.skills.SkillContext
import ai.platon.pulsar.agentic.skills.SkillDefinitionLoader
import ai.platon.pulsar.agentic.skills.SkillLoader
import ai.platon.pulsar.agentic.skills.SkillRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class SkillToolExecutorScriptSupportTest {

    @Test
    fun skillToolExecutorExposesScriptToolSpecs() {
        val executor = SkillToolExecutor(SkillRegistry.instance)

        val specs = executor.getToolSpecs()

        assertTrue(specs.containsKey("runScript"))
        assertTrue(specs.containsKey("readReference"))
    }

    @Test
    fun runScriptShouldExecutePythonWhenAllowedViaSkillToolExecutor() {
        runBlocking {
            val registry = SkillRegistry.instance
            val context = SkillContext(sessionId = "ut")
            registry.clear(context)

            val skillRoot = Paths.get("src/test/resources/skills/script-runner")
            val definitions = SkillDefinitionLoader().loadFromDirectory(skillRoot.parent)
                .filter { it.skillId == "script-runner" }
            require(definitions.size == 1) { "Test skill definition 'script-runner' not found" }

            val skill = DefinitionBackedSkill(
                definitions.first(),
                DefinitionBackedSkill.Origin.FileSystem(skillRoot)
            )
            SkillLoader(registry).load(skill, context)

            val executor = SkillToolExecutor(registry)
            val target = SkillToolTarget(context)
            val eval = executor.callFunctionOn(
                ToolCall(
                    domain = "skill",
                    method = "runScript",
                    arguments = mutableMapOf(
                        "id" to "script-runner",
                        "path" to "scripts/echo_args.py",
                        "args" to listOf("a", "b"),
                        "timeoutMillis" to 10_000,
                        "maxOutputChars" to 10_000,
                    )
                ),
                target
            )

            assertNull(eval.exception)
            @Suppress("UNCHECKED_CAST")
            val result = eval.value as Map<String, Any?>
            assertNotNull(result)
            assertEquals(0, result["exitCode"])
            assertEquals(false, result["timedOut"])
            assertEquals(false, result["truncated"])

            val output = (result["output"] as String).trim()
            assertEquals("{\"args\": [\"a\", \"b\"]}", output)

            registry.clear(context)
        }
    }
}
