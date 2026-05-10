package ai.platon.pulsar.agentic.skills

import ai.platon.pulsar.agentic.skills.tools.SkillScriptToolExecutor
import ai.platon.pulsar.agentic.skills.tools.SkillToolTarget
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class SkillScriptExecutionIntegrationTest {

    @Test
    fun runScriptShouldExecutePythonWhenAllowed() {
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

            val executor = SkillScriptToolExecutor(registry)
            val target = SkillToolTarget(SkillContext(sessionId = "ut"))

            @Suppress("UNCHECKED_CAST")
            val result = executor.callFunctionOn(
                domain = "skill",
                functionName = "runScript",
                args = mapOf(
                    "id" to "script-runner",
                    "path" to "scripts/echo_args.py",
                    "args" to listOf("a", "b"),
                    "timeoutMillis" to 10_000,
                    "maxOutputChars" to 10_000
                ),
                receiver = target
            ) as Map<String, Any?>

            assertEquals(0, result["exitCode"])
            assertEquals(false, result["timedOut"])
            assertEquals(false, result["truncated"])

            // output should be: {"args": ["a", "b"]}
            val output = (result["output"] as String).trim()
            assertEquals("{\"args\": [\"a\", \"b\"]}", output)
        }
    }
}
