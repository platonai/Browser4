package ai.platon.pulsar.agentic.tools.advanced.agent.skillsummary

import ai.platon.pulsar.agentic.common.AgentPaths
import ai.platon.pulsar.agentic.skills.SkillContext
import ai.platon.pulsar.agentic.skills.SkillInstaller
import ai.platon.pulsar.agentic.skills.SkillRegistry
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * 注册策略：按 [SkillSummaryConfig.RegisterMode] 决定是否把生成的草稿安装到运行时 [SkillRegistry]。
 *
 * - DRAFT：只落草稿区，不安装（默认，保守 — AC-2）。
 * - AUTO：校验通过的草稿调用 [SkillInstaller.install]（overwrite=false）；重名已注册时安全降级
 *   为 [Outcome.AlreadyExists]，交由去重追加分支（Task 6）处理，绝不抛异常影响原任务（NFR-1 / TR-5.3）。
 * - OFF：在 [SkillSummarizer.shouldRun] 已短路，不会到达这里。
 *
 * 抽象为接口以便测试注入 fake，避免污染全局 [SkillRegistry.instance] 单例。
 * 生产默认实现 [DefaultSkillRegistrar] 复用现有 [SkillInstaller] / [SkillRegistry]（不重复造轮子）。
 */
interface SkillRegistrar {
    /** 注册结果，供 [SkillSummarizer] 发射 `skill.summarize` 事件留痕。 */
    sealed interface Outcome {
        /** 草稿模式 / 非 auto：不安装。 */
        data object DraftOnly : Outcome
        /** 首次安装成功。 */
        data class Installed(val skillId: String, val deployedPath: String?) : Outcome
        /** 已存在同 id skill（auto + overwrite=false）：不覆盖；指纹索引会回滚，让下一次相似任务换名重新生成。 */
        data class AlreadyExists(val skillId: String) : Outcome
        /** 安装失败（install 返回 success=false 或抛异常）。 */
        data class Failed(val skillId: String, val reason: String) : Outcome
    }

    suspend fun register(
        draftDir: Path,
        skillId: String,
        mode: SkillSummaryConfig.RegisterMode,
    ): Outcome

    /**
     * 追加更新后覆盖部署（overwrite=true），仅用于 APPEND 分支。
     *
     * 实现必须拒绝替换不是由本机制部署的 skill，以及部署后被手工改过的 skill（返回 [Outcome.Failed]）。
     */
    suspend fun reinstall(targetDir: Path, skillId: String): Outcome
}

/**
 * 生产默认实现：所有异常都被吞掉并转为 [SkillRegistrar.Outcome.Failed]，
 * 草稿文件始终保留供人工审核（AC-6）。
 *
 * 覆盖安装（[reinstall]）只替换「由本机制部署、且部署后没有被改过」的 skill：
 * [SkillInstaller] 会先删掉整个部署目录再从草稿复制，撞名时被替换的可能是一个内置 skill，
 * 用户在部署目录上的手工修改也会丢。
 */
class DefaultSkillRegistrar(
    private val installer: SkillInstaller = SkillInstaller(),
    private val registry: SkillRegistry = SkillRegistry.instance,
    private val skillsDir: Path = AgentPaths.SKILLS_DIR,
) : SkillRegistrar {
    private val logger = LoggerFactory.getLogger(DefaultSkillRegistrar::class.java)

    override suspend fun register(
        draftDir: Path,
        skillId: String,
        mode: SkillSummaryConfig.RegisterMode,
    ): SkillRegistrar.Outcome {
        if (mode != SkillSummaryConfig.RegisterMode.AUTO) return SkillRegistrar.Outcome.DraftOnly
        // 重名：overwrite=false 下 installer 也会返回失败，这里提前判为已存在，交由追加分支。
        if (registry.contains(skillId)) return SkillRegistrar.Outcome.AlreadyExists(skillId)
        return runCatching {
            install(draftDir, skillId, overwrite = false)
        }.getOrElse { SkillRegistrar.Outcome.Failed(skillId, it.message ?: "install exception") }
            .also { if (it is SkillRegistrar.Outcome.Failed) logger.warn("auto skill register failed for {}: {}", skillId, it.reason) }
            .let { if (it is SkillRegistrar.Outcome.Installed) finishDeploy(it) else it }
    }

    override suspend fun reinstall(targetDir: Path, skillId: String): SkillRegistrar.Outcome {
        if (!registry.contains(skillId)) return SkillRegistrar.Outcome.Failed(skillId, "not registered, cannot reinstall")
        val deployedDir = skillsDir.resolve(skillId)
        val deployedHash = runCatching {
            Files.readAllLines(deployedDir.resolve(DEPLOY_MARKER))
                .firstOrNull { it.startsWith("sha256: ") }
                ?.removePrefix("sha256: ")
        }.getOrNull()
        val manual = "Promote the draft manually if you really want to replace it."
        when {
            deployedHash == null ->
                return SkillRegistrar.Outcome.Failed(
                    skillId,
                    "refusing to overwrite '$skillId': it was not deployed by auto skill summary. $manual",
                )
            sha256Of(deployedDir.resolve("SKILL.md")) != deployedHash ->
                return SkillRegistrar.Outcome.Failed(
                    skillId,
                    "refusing to overwrite '$skillId': the deployed SKILL.md was edited outside auto skill summary. $manual",
                )
        }
        return runCatching {
            install(targetDir, skillId, overwrite = true)
        }.getOrElse { SkillRegistrar.Outcome.Failed(skillId, it.message ?: "reinstall exception") }
            .also { if (it is SkillRegistrar.Outcome.Failed) logger.warn("auto skill reinstall failed for {}: {}", skillId, it.reason) }
            .let { if (it is SkillRegistrar.Outcome.Installed) finishDeploy(it) else it }
    }

    private suspend fun install(targetDir: Path, skillId: String, overwrite: Boolean): SkillRegistrar.Outcome {
        val result = installer.install(
            targetDir,
            SkillContext(sessionId = "auto-skill-summary"),
            overwrite = overwrite,
        )
        return if (result.success) {
            SkillRegistrar.Outcome.Installed(result.skillId, result.deployedPath)
        } else {
            SkillRegistrar.Outcome.Failed(result.skillId.ifBlank { skillId }, result.message)
        }
    }

    /**
     * 部署完成后收尾：草稿的侧车文件 [CONTEXT_SIDECAR]（来源任务的完整 prompt 和结果）不属于
     * skill，不留在部署目录里；再写下部署标记，记录这次部署的 SKILL.md 摘要。
     */
    private fun finishDeploy(outcome: SkillRegistrar.Outcome.Installed): SkillRegistrar.Outcome.Installed {
        val dir = outcome.deployedPath?.let { runCatching { Path.of(it) }.getOrNull() } ?: return outcome
        runCatching {
            Files.deleteIfExists(dir.resolve(CONTEXT_SIDECAR))
            val digest = sha256Of(dir.resolve("SKILL.md")) ?: return@runCatching
            Files.writeString(dir.resolve(DEPLOY_MARKER), "skill-id: ${outcome.skillId}\nsha256: $digest\n")
        }.onFailure { logger.debug("writing the auto skill deploy marker failed for {}: {}", outcome.skillId, it.message) }
        return outcome
    }

    /** 文件内容的 SHA-256（十六进制）；读不到时为 null。 */
    private fun sha256Of(file: Path): String? = runCatching {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(file))
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()

    companion object {
        /** 部署目录里的标记文件：上一次部署由本机制完成，并记录了当时 SKILL.md 的摘要。 */
        const val DEPLOY_MARKER = ".auto-skill-summary"

        /** 草稿侧车文件：来源任务的上下文，不随 skill 部署。 */
        const val CONTEXT_SIDECAR = "context.json"
    }
}
