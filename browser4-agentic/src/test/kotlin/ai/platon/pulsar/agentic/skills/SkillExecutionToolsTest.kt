package ai.platon.pulsar.agentic.skills

import ai.platon.pulsar.agentic.skills.tools.SkillScriptToolExecutor
import ai.platon.pulsar.agentic.skills.tools.SkillToolTarget
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Guards the two security gates of the skill script tools: references cannot escape the skill
 * root, and a script cannot run unless `allowed-tools` grants the interpreter.
 *
 * `browser4-cli` (the only bundled skill) covers the reference gate; the script gate needs a
 * resource-backed skill that actually ships a python file under `scripts/`, so it is built in a
 * temp directory.
 */
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
                    args = mapOf("id" to "browser4-cli", "path" to "references/../SKILL.md"),
                    receiver = target
                )
            }
        }
    }

    @Test
    fun runScriptIsDeniedWhenAllowedToolsDoNotGrantPython(@TempDir tempDir: Path) {
        runBlocking {
            SkillBootstrap().initialize()

            val skillDir = tempDir.resolve("no-tools-skill")
            Files.createDirectories(skillDir.resolve("scripts"))
            Files.writeString(skillDir.resolve("scripts/demo.py"), "print('should never run')\n")
            Files.writeString(
                skillDir.resolve("SKILL.md"),
                """
                ---
                name: no-tools-skill
                description: Fixture skill whose allowed-tools do not grant python.
                tags:
                  - test
                ---

                # No Tools Skill
                """.trimIndent()
            )

            val definition = SkillDefinitionLoader().loadFromDirectory(tempDir).single()
            val registry = SkillRegistry.instance
            val context = SkillContext(sessionId = "ut")
            registry.register(
                DefinitionBackedSkill(definition, DefinitionBackedSkill.Origin.FileSystem(skillDir)),
                context
            )

            try {
                val executor = SkillScriptToolExecutor(registry)
                val target = SkillToolTarget(context)

                val ex = assertThrows<IllegalArgumentException> {
                    executor.callFunctionOn(
                        domain = "skill",
                        functionName = "runScript",
                        args = mapOf(
                            "id" to "no-tools-skill",
                            "path" to "scripts/demo.py",
                            "args" to emptyList<String>()
                        ),
                        receiver = target
                    )
                }

                assertTrue(ex.message!!.contains("allowed-tools"), "Actual message: ${ex.message}")
            } finally {
                registry.unregister("no-tools-skill", context)
            }
        }
    }
}
