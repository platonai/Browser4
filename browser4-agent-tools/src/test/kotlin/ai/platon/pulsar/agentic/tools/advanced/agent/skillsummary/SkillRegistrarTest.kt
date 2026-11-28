package ai.platon.pulsar.agentic.tools.advanced.agent.skillsummary

import ai.platon.pulsar.agentic.skills.SkillContext
import ai.platon.pulsar.agentic.skills.SkillInstaller
import ai.platon.pulsar.agentic.skills.SkillRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TR-5.x 真实安装路径验证：使用 [DefaultSkillRegistrar] 驱动真实 [SkillInstaller] /
 * [SkillRegistry.instance]，用唯一 skillId 避免冲突，[AfterEach] 调 [SkillInstaller.uninstall]
 * 清理全局注册与部署文件。
 */
@Tag("Unit")
@Tag("Fast")
class SkillRegistrarTest {

    @TempDir
    lateinit var tempDir: Path

    private val registry = SkillRegistry.instance
    private val installer = SkillInstaller()
    private val registrar: SkillRegistrar = DefaultSkillRegistrar(installer, registry)

    // Unique per JVM run so it can never clash with a pre-registered skill id.
    private val skillId = "b4-test-autosum-${System.nanoTime()}"

    @AfterEach
    fun cleanup() {
        runBlocking { runCatching { installer.uninstall(skillId, SkillContext("test-cleanup")) } }
    }

    private fun validSkillMd(): String = """
        ---
        name: $skillId
        description: Auto-generated test skill for registrar verification.
        version: "1.0.0"
        tags: [test]
        ---
        # $skillId
        ## Overview
        Test skill installed by the auto-summary registrar.
        ## When to Use
        Verifying the auto skill summary install path.
        ## How It Works
        1. Read the relevant area of the codebase.
        2. Apply the generalized change.
        ## Examples
        - Applied to a registrar verification run.
    """.trimIndent()

    private fun writeSkillDir(content: String = validSkillMd()): Path {
        val dir = tempDir.resolve(skillId)
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("SKILL.md"), content)
        return dir
    }

    @Test
    @DisplayName("TR-5.1 draft mode does not install; registry stays empty for the id")
    fun draftDoesNotInstall() {
        runBlocking {
            val dir = writeSkillDir()
            val outcome = registrar.register(dir, skillId, SkillSummaryConfig.RegisterMode.DRAFT)
            assertTrue(outcome is SkillRegistrar.Outcome.DraftOnly, "got $outcome")
            assertFalse(registry.contains(skillId), "draft must not register")
        }
    }

    @Test
    @DisplayName("TR-5.2 auto mode installs a valid skill; registry.get returns it")
    fun autoInstallsValidSkill() {
        runBlocking {
            val dir = writeSkillDir()
            val outcome = registrar.register(dir, skillId, SkillSummaryConfig.RegisterMode.AUTO)
            assertTrue(outcome is SkillRegistrar.Outcome.Installed, "got $outcome")
            assertNotNull(registry.get(skillId), "registry must contain the installed skill")
        }
    }

    @Test
    @DisplayName("TR-5.3 auto mode on an already-registered id returns AlreadyExists and never throws")
    fun autoAlreadyExistsDoesNotThrow() {
        runBlocking {
            val dir = writeSkillDir()
            val first = registrar.register(dir, skillId, SkillSummaryConfig.RegisterMode.AUTO)
            assertTrue(first is SkillRegistrar.Outcome.Installed, "first got $first")
            val second = registrar.register(dir, skillId, SkillSummaryConfig.RegisterMode.AUTO)
            assertTrue(second is SkillRegistrar.Outcome.AlreadyExists, "second got $second")
            // Reaching here without an exception is the pass condition.
        }
    }

    @Test
    @DisplayName("auto mode on a directory without SKILL.md returns Failed, no throw, no register")
    fun autoMissingSkillMdReturnsFailed() {
        runBlocking {
            val dir = tempDir.resolve("empty")
            Files.createDirectories(dir)
            val outcome = registrar.register(dir, skillId, SkillSummaryConfig.RegisterMode.AUTO)
            assertTrue(outcome is SkillRegistrar.Outcome.Failed, "got $outcome")
            assertFalse(registry.contains(skillId))
        }
    }

    @Test
    @DisplayName("reinstall redeploys a skill this registrar deployed and whose deployed copy was left alone")
    fun reinstallReplacesOwnUnmodifiedDeployment() {
        runBlocking {
            val dir = writeSkillDir()
            val outcome = registrar.register(dir, skillId, SkillSummaryConfig.RegisterMode.AUTO)
            assertTrue(outcome is SkillRegistrar.Outcome.Installed, "got $outcome")
            val deployed = Path.of(outcome.deployedPath!!)

            // The draft gains an example, as the append path would, plus the review sidecar.
            Files.writeString(dir.resolve("SKILL.md"), validSkillMd().replace("## Examples", "## Examples\n- A second run."))
            Files.writeString(dir.resolve("context.json"), """{"prompt":"the source task"}""")

            val re = registrar.reinstall(dir, skillId)
            assertTrue(re is SkillRegistrar.Outcome.Installed, "got $re")
            assertTrue(Files.readString(deployed.resolve("SKILL.md")).contains("A second run."))
            assertFalse(Files.exists(deployed.resolve("context.json")), "the draft sidecar must not ship with the skill")
            assertTrue(Files.exists(deployed.resolve(DefaultSkillRegistrar.DEPLOY_MARKER)))
        }
    }

    @Test
    @DisplayName("reinstall refuses to overwrite a deployed SKILL.md that was edited by hand")
    fun reinstallRefusesEditedDeployment() {
        runBlocking {
            val dir = writeSkillDir()
            val outcome = registrar.register(dir, skillId, SkillSummaryConfig.RegisterMode.AUTO)
            val deployed = Path.of((outcome as SkillRegistrar.Outcome.Installed).deployedPath!!)
            val edited = Files.readString(deployed.resolve("SKILL.md")) + "\na hand-written note\n"
            Files.writeString(deployed.resolve("SKILL.md"), edited)

            val failed = registrar.reinstall(dir, skillId) as? SkillRegistrar.Outcome.Failed
            assertNotNull(failed, "an edited deployment must not be overwritten")
            assertTrue(failed.reason.contains("edited"), failed.reason)
            assertEquals(edited, Files.readString(deployed.resolve("SKILL.md")))
        }
    }

    @Test
    @DisplayName("reinstall refuses to overwrite a skill that was not deployed by auto skill summary")
    fun reinstallRefusesForeignSkill() {
        runBlocking {
            // Installed the ordinary way, so the deployed directory carries no deploy marker.
            val dir = writeSkillDir()
            val result = installer.install(dir, SkillContext("test-foreign"), overwrite = false)
            assertTrue(result.success, result.message)
            val deployed = Path.of(result.deployedPath!!)
            val before = Files.readString(deployed.resolve("SKILL.md"))

            val failed = registrar.reinstall(dir, skillId) as? SkillRegistrar.Outcome.Failed
            assertNotNull(failed, "a foreign skill must not be overwritten")
            assertTrue(failed.reason.contains("not deployed by auto skill summary"), failed.reason)
            assertEquals(before, Files.readString(deployed.resolve("SKILL.md")))
        }
    }
}
