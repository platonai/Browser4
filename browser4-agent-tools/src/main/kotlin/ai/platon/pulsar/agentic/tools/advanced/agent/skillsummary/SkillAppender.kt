package ai.platon.pulsar.agentic.tools.advanced.agent.skillsummary

import ai.platon.pulsar.agentic.common.AgentPaths
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * 去重追加：当指纹命中已有 skill（decider 决定 APPEND）时，把本次任务作为一个 Example 条目
 * 追加到已有 SKILL.md，并 bump patch version。
 *
 * 目标文件定位：优先草稿区 `draftsDir / (targetSkillId + "-" + 时间戳) / SKILL.md`（取最新），否则已部署的
 * `<skillsDir>/<targetSkillId>/SKILL.md`。两者都不存在 → 返回 not-found（调用方降级）。
 *
 * 幂等（TR-6.2）：每个 Example 条目录入 `<!-- task-id: <taskId> -->` 标记，追加前扫描文件；
 * 同一 taskId 已追加过则直接返回 already-appended，绝不产生重复条目。
 *
 * 所有异常吞没为 [Result.appended]=false，绝不影响原任务（NFR-1）。
 */
class SkillAppender(
    private val draftsDir: Path,
    private val skillsDir: Path = AgentPaths.SKILLS_DIR,
) {
    private val logger = LoggerFactory.getLogger(SkillAppender::class.java)

    data class Result(
        val targetDir: Path?,
        val skillFile: Path?,
        val appended: Boolean,
        val reason: String,
    )

    fun append(targetSkillId: String, taskId: String, context: TaskSummaryContext): Result {
        val skillFile = locateSkillFile(targetSkillId)
            ?: return Result(null, null, appended = false, reason = "target skill not found: $targetSkillId")
        return runCatching {
            var content = skillFile.readText()
            val marker = "<!-- task-id: $taskId -->"
            if (content.contains(marker)) {
                return Result(skillFile.parent, skillFile, appended = false, reason = "already appended for task $taskId")
            }
            content = appendExample(content, marker, context)
            content = bumpVersion(content)
            skillFile.writeText(content)
            Result(skillFile.parent, skillFile, appended = true, reason = "ok")
        }.getOrElse {
            logger.warn("skill append failed for {}: {}", targetSkillId, it.message)
            Result(skillFile.parent, skillFile, appended = false, reason = it.message ?: "append exception")
        }
    }

    private fun locateSkillFile(targetSkillId: String): Path? {
        if (Files.isDirectory(draftsDir)) {
            val freshest = Files.list(draftsDir).use { stream ->
                stream
                    .filter { Files.isDirectory(it) && it.fileName.toString().startsWith("$targetSkillId-") }
                    .max(Comparator.comparingLong { Files.getLastModifiedTime(it).toMillis() })
                    .orElse(null)
            }
            if (freshest != null) {
                val f = freshest.resolve("SKILL.md")
                if (f.exists()) return f
            }
        }
        val deployed = skillsDir.resolve(targetSkillId).resolve("SKILL.md")
        return deployed.takeIf { it.isRegularFile() }
    }

    private fun appendExample(content: String, marker: String, context: TaskSummaryContext): String {
        val tools = context.toolCounts.entries.joinToString(", ") { (k, v) -> "$k($v)" }.ifBlank { "n/a" }
        val prompt = (context.prompt ?: "").take(80).replace("\n", " ")
        val entry = "- $prompt — tools: $tools $marker\n"
        val header = "## Examples"
        val idx = content.indexOf(header)
        if (idx < 0) return content.trimEnd() + "\n\n$header\n$entry"
        val after = idx + header.length
        val nextHeading = content.indexOf("\n## ", after)
        return if (nextHeading < 0) {
            content.trimEnd() + "\n" + entry
        } else {
            val before = content.substring(0, nextHeading).trimEnd()
            val rest = content.substring(nextHeading)
            "$before\n$entry$rest"
        }
    }

    private val versionRegex = Regex("""(?m)^version:\s*["']?(\d+)\.(\d+)\.(\d+)["']?""")
    private fun bumpVersion(content: String): String {
        val m = versionRegex.find(content) ?: return content
        val (major, minor, patch) = m.destructured
        return content.replaceRange(m.range, "version: \"$major.$minor.${patch.toInt() + 1}\"")
    }
}
