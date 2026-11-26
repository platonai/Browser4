package ai.platon.pulsar.agentic.tools.advanced.agent.skillsummary

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * 草稿区的指纹索引（`<draftsDir>/index.json`），用于任务去重。
 *
 * 记录每个已生成 skill 的 prompt token 集合、skillId、最后生成时间、example 数。
 * 所有读写线程安全（synchronized）；持久化失败只降级为内存态，绝不抛异常影响主流程。
 *
 * 冷却判断不在 [lookup] 内做（lookup 只负责找最相似的命中条目），
 * 由调用方（[SkillSummaryDecider]）根据 [FingerprintEntry.lastGeneratedAt] 决定
 * 是 append 还是因冷却 skip——这样"刚生成的 skill 仍可被 lookup 命中"，避免永远不命中。
 */
class SkillFingerprintIndex(private val indexFile: Path) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private data class IndexFile(val entries: MutableList<FingerprintEntry> = mutableListOf())

    private val mapper: ObjectMapper = pulsarObjectMapper()
    private val entries: MutableList<FingerprintEntry> = synchronized(this) { load() }

    /**
     * 查找与 [tokens] 相似度 ≥ [threshold] 的已有 skill，返回相似度最高的条目。
     * 不做冷却过滤——命中条目的冷却由调用方判断。无命中返回 null。
     */
    internal fun lookup(
        tokens: Set<String>,
        threshold: Double,
        now: Long = System.currentTimeMillis(),
    ): FingerprintEntry? {
        synchronized(this) {
            var best: FingerprintEntry? = null
            var bestSim = -1.0
            for (e in entries) {
                val sim = SkillFingerprint.jaccard(tokens, e.tokens.toSet())
                if (sim >= threshold && sim > bestSim) {
                    best = e
                    bestSim = sim
                }
            }
            return best
        }
    }

    /** 记录一个新生成的 skill。 */
    fun record(tokens: Set<String>, skillId: String, now: Long = System.currentTimeMillis()) {
        synchronized(this) {
            entries.add(
                FingerprintEntry(
                    fingerprint = tokens.sorted().joinToString(","),
                    tokens = tokens.sorted(),
                    skillId = skillId,
                    lastGeneratedAt = now,
                    exampleCount = 1,
                ),
            )
            save()
        }
    }

    /** 已有 skill 追加了一个 example：计数 +1 并刷新时间戳。 */
    fun bump(skillId: String, now: Long = System.currentTimeMillis()) {
        synchronized(this) {
            entries.firstOrNull { it.skillId == skillId }?.let {
                it.exampleCount++
                it.lastGeneratedAt = now
                save()
            }
        }
    }

    internal fun size(): Int = synchronized(this) { entries.size }

    private fun load(): MutableList<FingerprintEntry> {
        if (!Files.exists(indexFile)) return mutableListOf()
        return runCatching {
            mapper.readValue(Files.readAllBytes(indexFile), IndexFile::class.java).entries
        }.getOrElse { mutableListOf() }
    }

    private fun save() {
        runCatching {
            indexFile.parent?.let { Files.createDirectories(it) }
            // 原子替换：并发写同一份索引时不会留下半截文件，读者也永远看到完整内容。
            val tmp = indexFile.resolveSibling("${indexFile.fileName}.tmp")
            Files.write(
                tmp,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(IndexFile(entries)),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}

/** 索引里的一条记录。internal 以便同包的 decider 读取 [lastGeneratedAt] 判断冷却。 */
internal data class FingerprintEntry(
    val fingerprint: String,
    val tokens: List<String>,
    val skillId: String,
    var lastGeneratedAt: Long,
    var exampleCount: Int,
)
