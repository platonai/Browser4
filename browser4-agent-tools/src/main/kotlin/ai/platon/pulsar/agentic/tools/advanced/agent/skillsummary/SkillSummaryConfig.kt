package ai.platon.pulsar.agentic.tools.advanced.agent.skillsummary

/**
 * 任务成功后自动总结为 SKILL 的运行时配置。
 *
 * 全部字段从系统属性读取（与 [ai.platon.pulsar.agentic.agents.AgentConfig] 同模式），
 * 默认值保守：功能关闭、注册模式为仅草稿。改默认值必须同步更新
 * `application.properties` 中的注释默认值与 `docs/config.md`。
 *
 * 每个解析都降级到默认值：一个非法的 `-D` 值绝不能让任务成功路径抛异常
 * （总结是后置副作用，NFR-1）。
 */
data class SkillSummaryConfig(
    /** 总开关。默认 false：全链路零行为变化。 */
    val enabled: Boolean = boolProp("browser4.agent.skill.summary.enabled", false),
    /** 注册策略：DRAFT（默认，只写草稿不注册）、AUTO（校验通过自动注册）、OFF。 */
    val registerMode: RegisterMode = RegisterMode.from(
        System.getProperty("browser4.agent.skill.summary.registerMode", "draft"),
    ),
    /**
     * 触发总结的最小 agent 轮次数。
     *
     * 默认 1：浏览类任务由 agent 单轮跑完，无论调用多少次工具 `rounds` 恒为 1，
     * 因此工作量门槛由 [minToolCalls] 承担，而不是轮次数。
     */
    val minRounds: Int = intProp("browser4.agent.skill.summary.minRounds", 1).coerceAtLeast(0),
    /** 触发总结的最小工具调用数。 */
    val minToolCalls: Int = intProp("browser4.agent.skill.summary.minToolCalls", 5).coerceAtLeast(0),
    /**
     * true 时要求本次运行至少有一次文件改动才会生成。
     *
     * 默认 true：只有真正产出文件改动的运行才被视为"有可复用资产"，纯查询 / 只读
     * 运行不生成草稿。注意浏览类任务的交付物写在隔离 workspace，不记录 mutation，
     * 因此开启后这类任务不会生成；需要覆盖时显式设 `-D...requireMutation=false`。
     */
    val requireMutation: Boolean = boolProp("browser4.agent.skill.summary.requireMutation", true),
    /**
     * 无文件改动任务（浏览、研究）的可复用性评分门槛。
     *
     * `score = 工具种类数 + 3 × 策略切换次数 − 2 × 原样重试次数`。低于该分视为
     * "一次跑通"或在空转，不生成 skill；策略切换（失败后换做法成功）权重最高，
     * 因为那才是复用时真正省事的知识点。
     */
    val browseMinScore: Int = intProp("browser4.agent.skill.summary.browseMinScore", 6)
        .coerceAtLeast(0),
    /** 去重相似度阈值（0.0..1.0），≥ 该值视为同一 skill，走追加而非新建。 */
    val dedupSimilarity: Double = doubleProp("browser4.agent.skill.summary.dedupSimilarity", 0.85)
        .coerceIn(0.0, 1.0),
    /** 同一指纹两次生成之间的冷却小时数。 */
    val cooldownHours: Long = longProp("browser4.agent.skill.summary.cooldownHours", 6L).coerceAtLeast(0L),
    /** 草稿区目录；空字符串表示用默认（AgentPaths 同级 skills-drafts）。 */
    val draftsDir: String = System.getProperty("browser4.agent.skill.summary.draftsDir", "") ?: "",
) {
    enum class RegisterMode {
        DRAFT, AUTO, OFF;

        companion object {
            fun from(value: String?): RegisterMode = when (value?.trim()?.lowercase()) {
                "auto" -> AUTO
                "off" -> OFF
                else -> DRAFT
            }
        }
    }

    companion object {
        /** 读取当前系统属性快照。每次调用重新读取，便于测试用 System.setProperty 覆盖。 */
        fun load(): SkillSummaryConfig = SkillSummaryConfig()

        private fun boolProp(key: String, default: Boolean): Boolean =
            System.getProperty(key)?.toBooleanStrictOrNull() ?: default

        private fun intProp(key: String, default: Int): Int =
            System.getProperty(key)?.toIntOrNull() ?: default

        private fun longProp(key: String, default: Long): Long =
            System.getProperty(key)?.toLongOrNull() ?: default

        private fun doubleProp(key: String, default: Double): Double =
            System.getProperty(key)?.toDoubleOrNull() ?: default
    }
}
