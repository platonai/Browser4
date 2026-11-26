package ai.platon.pulsar.agentic.tools.advanced.agent.skillsummary

/**
 * 把任务 prompt 归一化为有意义的 token 集合，并计算两个集合的 Jaccard 相似度。
 *
 * 用于去重：两次 prompt 高度相似（token 重叠多）时，视为同一类任务，走追加而非新建。
 * 这是 embedding 的降级替代（P1）；关键词集合足以区分"重构 auth 模块"与"爬取价格列表"。
 */
object SkillFingerprint {

    private val STOPWORDS = setOf(
        "the", "a", "an", "to", "of", "and", "or", "in", "on", "for", "with", "is", "are",
        "was", "were", "be", "been", "this", "that", "it", "its", "as", "at", "by", "from",
        "up", "down", "out", "over", "under", "again", "further", "then", "once", "here",
        "there", "when", "where", "why", "how", "all", "any", "both", "each", "few", "more",
        "most", "other", "some", "such", "no", "nor", "not", "only", "own", "same", "so",
        "than", "too", "very", "can", "will", "just", "should", "now", "please", "help",
        "want", "need", "make", "get", "use", "using", "used",
    )

    /** CJK 连续片段。中文、日文汉字没有空格分词，需单独切分。 */
    private val CJK_RUN = Regex("[\\u4e00-\\u9fff\\u3400-\\u4dbf]+")

    /**
     * 小写、去标点、按空白分词、去停用词、去长度 ≤ 2 的词。
     *
     * CJK 片段按相邻双字（bigram）切分：中文任务 prompt 不含空格，只按空白分词会得到
     * 空指纹，导致整类任务被 "empty prompt fingerprint" 跳过。bigram 足以区分主题，
     * 也让 Jaccard 相似度对中文提示可用。
     */
    fun tokensOf(prompt: String?): Set<String> {
        if (prompt.isNullOrBlank()) return emptySet()
        val lowered = prompt.lowercase()
        val latin = lowered
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in STOPWORDS }
        val cjk = CJK_RUN.findAll(lowered)
            .flatMap { match -> cjkGrams(match.value).asSequence() }
            .toList()
        return (latin + cjk).toSet()
    }

    /** 单个 CJK 片段切成相邻双字；单字段整体保留。 */
    private fun cjkGrams(run: String): List<String> =
        if (run.length == 1) listOf(run) else run.windowed(2)

    /** Jaccard 相似度 = |交集| / |并集|，范围 0.0..1.0。两个空集视为完全相同（1.0）。 */
    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size
        val union = a.size + b.size - intersection
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }
}
