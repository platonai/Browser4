package ai.platon.pulsar.agentic.tools.advanced.agent.skillsummary

import ai.platon.pulsar.agentic.skills.SkillContext
import ai.platon.pulsar.agentic.skills.SkillInstaller
import ai.platon.pulsar.agentic.skills.SkillRegistry
import org.slf4j.LoggerFactory
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
        /** 已存在同 id skill（auto + overwrite=false）：不覆盖，走追加分支。 */
        data class AlreadyExists(val skillId: String) : Outcome
        /** 安装失败（install 返回 success=false 或抛异常）。 */
        data class Failed(val skillId: String, val reason: String) : Outcome
    }

    suspend fun register(
        draftDir: Path,
        skillId: String,
        mode: SkillSummaryConfig.RegisterMode,
    ): Outcome

    /** 追加更新后覆盖部署（overwrite=true），仅用于 APPEND 分支。 */
    suspend fun reinstall(targetDir: Path, skillId: String): Outcome
}

/**
 * 生产默认实现：所有异常都被吞掉并转为 [SkillRegistrar.Outcome.Failed]，
 * 草稿文件始终保留供人工审核（AC-6）。
 */
class DefaultSkillRegistrar(
    private val installer: SkillInstaller = SkillInstaller(),
    private val registry: SkillRegistry = SkillRegistry.instance,
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
            val result = installer.install(
                draftDir,
                SkillContext(sessionId = "auto-skill-summary"),
                overwrite = false,
            )
            if (result.success) SkillRegistrar.Outcome.Installed(result.skillId, result.deployedPath)
            else SkillRegistrar.Outcome.Failed(result.skillId.ifBlank { skillId }, result.message)
        }.getOrElse { SkillRegistrar.Outcome.Failed(skillId, it.message ?: "install exception") }
            .also { if (it is SkillRegistrar.Outcome.Failed) logger.warn("auto skill register failed for {}: {}", skillId, it.reason) }
    }

    override suspend fun reinstall(targetDir: Path, skillId: String): SkillRegistrar.Outcome {
        if (!registry.contains(skillId)) return SkillRegistrar.Outcome.Failed(skillId, "not registered, cannot reinstall")
        return runCatching {
            val result = installer.install(
                targetDir,
                SkillContext(sessionId = "auto-skill-summary"),
                overwrite = true,
            )
            if (result.success) SkillRegistrar.Outcome.Installed(result.skillId, result.deployedPath)
            else SkillRegistrar.Outcome.Failed(result.skillId.ifBlank { skillId }, result.message)
        }.getOrElse { SkillRegistrar.Outcome.Failed(skillId, it.message ?: "reinstall exception") }
            .also { if (it is SkillRegistrar.Outcome.Failed) logger.warn("auto skill reinstall failed for {}: {}", skillId, it.reason) }
    }
}
