package ai.platon.pulsar.agentic.tools.advanced.agent.skillsummary

/**
 * 判定一次成功运行是否值得生成 SKILL，以及是新建还是追加。
 *
 * 两条判定轨：
 * - **有文件改动**（coding 类）：只要通过 rounds / toolCalls 门槛即可生成。
 * - **无文件改动**（浏览/研究类）：没有 mutation 可判，改用可复用性评分
 *   `工具种类数 + 3 × 策略切换次数 − 2 × 原样重试次数`，低于
 *   [SkillSummaryConfig.browseMinScore] 不生成。一次跑通的简单运行分数低，
 *   多轮纠错后确认的运行因策略切换而得分高。该评分由
 *   [SkillSummaryConfig.browseScoreGate] 控制，关掉它等于放行所有无改动运行。
 *
 * 之后才是指纹去重。不满足则 skip 并给出原因。
 */
class SkillSummaryDecider(
    private val index: SkillFingerprintIndex,
    private val config: SkillSummaryConfig,
) {
    fun decide(context: TaskSummaryContext, now: Long = System.currentTimeMillis()): SummaryDecision {
        if (context.rounds < config.minRounds) {
            return SummaryDecision.skip("rounds ${context.rounds} < minRounds ${config.minRounds}")
        }
        if (context.toolCalls < config.minToolCalls) {
            return SummaryDecision.skip("toolCalls ${context.toolCalls} < minToolCalls ${config.minToolCalls}")
        }
        if (config.browseScoreGate && context.mutations < 1) {
            val kinds = context.toolCounts.size
            val score = kinds + 3 * context.strategySwitches - 2 * context.verbatimRetries
            if (score < config.browseMinScore) {
                return SummaryDecision.skip(
                    "browse run score=$score < browseMinScore=${config.browseMinScore} " +
                        "(toolKinds=$kinds, strategySwitches=${context.strategySwitches}, " +
                        "verbatimRetries=${context.verbatimRetries})",
                )
            }
        }
        val tokens = SkillFingerprint.tokensOf(context.prompt)
        if (tokens.isEmpty()) {
            return SummaryDecision.skip("empty prompt fingerprint (no meaningful tokens)")
        }
        val hit = index.lookup(tokens, config.dedupSimilarity, now)
        val cooldownMs = config.cooldownHours * 3_600_000L
        return when {
            hit == null -> SummaryDecision.generate()
            cooldownMs > 0 && now - hit.lastGeneratedAt < cooldownMs ->
                SummaryDecision.skip(
                    "cooling: skill ${hit.skillId} last generated " +
                        "${now - hit.lastGeneratedAt}ms ago (< ${config.cooldownHours}h cooldown)",
                )
            else -> SummaryDecision.append(hit.skillId)
        }
    }
}

/** 判定结果。 */
data class SummaryDecision(
    val action: Action,
    val reason: String? = null,
    val targetSkillId: String? = null,
) {
    enum class Action { GENERATE, APPEND, SKIP }

    companion object {
        fun generate() = SummaryDecision(Action.GENERATE)
        fun append(skillId: String) = SummaryDecision(Action.APPEND, targetSkillId = skillId)
        fun skip(reason: String) = SummaryDecision(Action.SKIP, reason = reason)
    }
}
