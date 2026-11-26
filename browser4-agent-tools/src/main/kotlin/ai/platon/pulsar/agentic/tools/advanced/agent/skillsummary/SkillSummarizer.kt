package ai.platon.pulsar.agentic.tools.advanced.agent.skillsummary

import ai.platon.pulsar.agentic.common.AgentPaths
import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskEvent
import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskStatus
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * 任务成功后自动总结为 SKILL 的编排器（best-effort 后置副作用）。
 *
 * 触发点：`StatefulAgentRunner` 在 `task.done` 之后异步调用 [maybeSummarize]。
 * 任何异常都被吞掉记日志并发射一个 `skill.summarize` action=`fail` 事件，
 * 绝不影响原任务的成功状态（NFR-1）。
 *
 * 流水线：采集（Task 2）→ 判定（Task 3）→ 生成（Task 4）→ 注册（Task 5）→
 * 去重追加（Task 6）。当前文件随各 task 逐步填充 [summarizePipeline]。
 */
object SkillSummarizer {
    private val logger = LoggerFactory.getLogger(SkillSummarizer::class.java)

    /**
     * 可替换的总结实现，便于测试注入失败（验证异常吞没，NFR-1）。
     * 生产默认 no-op；后续 task 将真实流水线绑定到 [summarizePipeline]。
     */
    internal var summarizeFn: (AgentTaskStatus, SkillSummaryConfig) -> Unit = { _, _ -> }

    /**
     * 任务成功后调用（最简形式，供测试/独立调用）。未启用时立即返回，零副作用。
     */
    fun maybeSummarize(
        status: AgentTaskStatus,
        config: SkillSummaryConfig = SkillSummaryConfig.load(),
    ) {
        if (!shouldRun(config)) return
        runCatching { summarizeFn(status, config) }
            .onFailure { logger.warn("auto skill summary failed for task {}: {}", status.id, it.message) }
    }

    /**
     * 任务成功后调用（完整形式，由 StatefulAgentRunner 接入）。
     *
     * @param status 成功终态的任务状态
     * @param events 该任务的完整事件流（已按 seq 排序，来自 AgentTaskEventStore）
     * @param emit 事件发射回调：runner 把 `skill.summarize` 事件写入 task 的 eventLog + store
     * @param config 配置快照
     */
    suspend fun maybeSummarize(
        status: AgentTaskStatus,
        events: List<AgentTaskEvent>,
        emit: (type: String, detail: Map<String, Any?>?, text: String?) -> Unit,
        config: SkillSummaryConfig = SkillSummaryConfig.load(),
        llmCall: (suspend (instruction: String, contextText: String) -> String?)? = null,
    ) {
        if (!shouldRun(config)) return
        val start = System.currentTimeMillis()
        runCatching {
            val context = TaskContextCollector.collect(status, events)
            emit(
                AgentTaskEvent.TYPE_SKILL_SUMMARIZE,
                mapOf(
                    "action" to "collect",
                    "rounds" to context.rounds,
                    "toolCalls" to context.toolCalls,
                    "mutations" to context.mutations,
                    "filesChanged" to context.filesChanged.size,
                    "durationMs" to (System.currentTimeMillis() - start),
                ),
                "auto skill summary: collected context for ${context.toolCalls} tool calls " +
                    "(${context.prompt?.length ?: 0} chars prompt)",
            )
            summarizePipeline(status, context, config, emit, llmCall)
        }.onFailure {
            logger.warn("auto skill summary failed for task {}: {}", status.id, it.message)
            runCatching {
                emit(
                    AgentTaskEvent.TYPE_SKILL_SUMMARIZE,
                    mapOf(
                        "action" to "fail",
                        "reason" to (it.message ?: "exception"),
                        "durationMs" to (System.currentTimeMillis() - start),
                    ),
                    "auto skill summary failed: ${it.message}",
                )
            }
        }
    }

    /** 是否应该执行总结：总开关开启且注册模式非 off。 */
    internal fun shouldRun(config: SkillSummaryConfig): Boolean =
        config.enabled && config.registerMode != SkillSummaryConfig.RegisterMode.OFF

    /**
     * 总结流水线：判定 → 生成 → 注册 → 追加。
     *
     * Task 2 为 no-op（采集已在 [maybeSummarize] 完成并发射 `collect` 事件）；
     * Task 3 起逐步接入。每一步都通过 [emit] 发射 `skill.summarize` 事件留痕。
     */
    private suspend fun summarizePipeline(
        status: AgentTaskStatus,
        context: TaskSummaryContext,
        config: SkillSummaryConfig,
        emit: (type: String, detail: Map<String, Any?>?, text: String?) -> Unit,
        llmCall: (suspend (instruction: String, contextText: String) -> String?)? = null,
    ) {
        val index = indexFactory(config)
        val decision = SkillSummaryDecider(index, config).decide(context)
        when (decision.action) {
            SummaryDecision.Action.SKIP -> emit(
                AgentTaskEvent.TYPE_SKILL_SUMMARIZE,
                mapOf("action" to "skip", "reason" to (decision.reason ?: "")),
                "auto skill summary skipped: ${decision.reason ?: ""}",
            )
            SummaryDecision.Action.APPEND -> {
                val targetSkillId = decision.targetSkillId ?: ""
                val result = appenderFactory(config).append(targetSkillId, status.id, context)
                val detail = linkedMapOf<String, Any?>(
                    "action" to "append",
                    "targetSkillId" to targetSkillId,
                    "appended" to result.appended,
                    "reason" to result.reason,
                )
                result.targetDir?.let { detail["dir"] = it.toString() }
                emit(
                    AgentTaskEvent.TYPE_SKILL_SUMMARIZE,
                    detail,
                    "auto skill summary append to $targetSkillId: ${result.reason}",
                )
                if (result.appended) {
                    runCatching { index.bump(targetSkillId) }
                        .onFailure { logger.warn("skill summary index bump failed for {}: {}", targetSkillId, it.message) }
                }
                // auto 模式下追加成功后覆盖部署（overwrite=true）；draft 只更新草稿副本。
                if (result.appended &&
                    config.registerMode == SkillSummaryConfig.RegisterMode.AUTO &&
                    result.targetDir != null
                ) {
                    val outcome = registrarFactory(config).reinstall(result.targetDir, targetSkillId)
                    val regAction = when (outcome) {
                        is SkillRegistrar.Outcome.Installed -> "registered"
                        is SkillRegistrar.Outcome.Failed -> "register-failed"
                        else -> "draft"
                    }
                    val regText = when (outcome) {
                        is SkillRegistrar.Outcome.Installed -> "re-registered after append (auto)"
                        is SkillRegistrar.Outcome.Failed -> "re-register failed: ${outcome.reason}"
                        else -> "kept as draft (not registered)"
                    }
                    emit(
                        AgentTaskEvent.TYPE_SKILL_SUMMARIZE,
                        mapOf("action" to regAction, "skillId" to targetSkillId, "mode" to config.registerMode.name),
                        "auto skill summary $regText: $targetSkillId",
                    )
                }
            }
            SummaryDecision.Action.GENERATE -> {
                val draft = generatorFactory(config, llmCall).generate(context)
                val detail = linkedMapOf<String, Any?>(
                    "action" to if (draft.validation.valid) "generate" else "fail",
                    "skillId" to draft.skillId,
                    "dir" to draft.dir.toString(),
                    "llmUsed" to draft.llmUsed,
                    "valid" to draft.validation.valid,
                )
                if (!draft.validation.valid) {
                    detail["issues"] = draft.validation.issues.joinToString("; ") { it.message }
                }
                emit(
                    AgentTaskEvent.TYPE_SKILL_SUMMARIZE,
                    detail,
                    "auto skill summary ${if (draft.validation.valid) "generated" else "failed validation"}: ${draft.skillId}",
                )
                if (draft.validation.valid) {
                    runCatching { index.record(SkillFingerprint.tokensOf(context.prompt), draft.skillId) }
                        .onFailure { logger.warn("skill summary index record failed for {}: {}", draft.skillId, it.message) }
                    val outcome = registrarFactory(config).register(draft.dir, draft.skillId, config.registerMode)
                    val regAction = when (outcome) {
                        is SkillRegistrar.Outcome.DraftOnly -> "draft"
                        is SkillRegistrar.Outcome.Installed -> "registered"
                        is SkillRegistrar.Outcome.AlreadyExists -> "already-exists"
                        is SkillRegistrar.Outcome.Failed -> "register-failed"
                    }
                    val regText = when (outcome) {
                        is SkillRegistrar.Outcome.DraftOnly -> "kept as draft (not registered)"
                        is SkillRegistrar.Outcome.Installed -> "registered to runtime registry (auto)"
                        is SkillRegistrar.Outcome.AlreadyExists -> "already registered; append path (Task 6)"
                        is SkillRegistrar.Outcome.Failed -> "register failed: ${outcome.reason}"
                    }
                    emit(
                        AgentTaskEvent.TYPE_SKILL_SUMMARIZE,
                        mapOf("action" to regAction, "skillId" to draft.skillId, "mode" to config.registerMode.name),
                        "auto skill summary $regText: ${draft.skillId}",
                    )
                }
            }
        }
    }

    /** 草稿区根目录（config.draftsDir 非空时用它，否则 `<app data>/agent/skills-drafts`）。 */
    internal fun draftsDirOf(config: SkillSummaryConfig): Path {
        val dir = if (config.draftsDir.isNotBlank()) Path.of(config.draftsDir)
        else AgentPaths.AGENT_BASE_DIR.resolve("skills-drafts")
        runCatching { Files.createDirectories(dir) }
        return dir
    }

    /** 指纹索引工厂，可替换以便测试注入临时索引。 */
    internal var indexFactory: (SkillSummaryConfig) -> SkillFingerprintIndex = { cfg ->
        SkillFingerprintIndex(draftsDirOf(cfg).resolve("index.json"))
    }

    /**
     * LLM 单次调用入口，由 StatefulAgentRunner 在持有 agent 时注入真实实现
     * （复用 agent 的 cta / InferenceEngine）。为 null 时 generator 走降级路径
     * （只写 context.json，不生成 SKILL.md）—— 绝不阻塞主流程（NFR-1）。
     */
    internal var llmCall: (suspend (instruction: String, contextText: String) -> String?)? = null

    /** 草稿生成器工厂，可替换以便测试注入 mock LLM / 临时目录。 */
    internal var generatorFactory: (SkillSummaryConfig, (suspend (instruction: String, contextText: String) -> String?)?) -> SkillDraftGenerator = { cfg, llm ->
        SkillDraftGenerator(draftsDirOf(cfg), llm = llm ?: llmCall ?: { _, _ -> null })
    }

    /** 注册器工厂，可替换以便测试注入 fake。 */
    internal var registrarFactory: (SkillSummaryConfig) -> SkillRegistrar = { DefaultSkillRegistrar() }

    /** 追加器工厂，可替换以便测试注入临时目录。 */
    internal var appenderFactory: (SkillSummaryConfig) -> SkillAppender = { cfg -> SkillAppender(draftsDirOf(cfg)) }
}
