package ai.platon.pulsar.coding

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * The strategy-file channel of operator approvals (plan P4-4): pre-approved grants for
 * unattended jobs (coworker queues, CI), loaded once when the task starts so the job never
 * waits on an operator.
 *
 * The file is `<workspace>/.browser4/access-policy.json`:
 *
 * ```json
 * {
 *   "grants": [
 *     { "effect": "fs.write", "target": "src/generated", "ttlSeconds": 3600, "reason": "codegen output" },
 *     { "effect": "fs.exec",  "target": "git" }
 *   ]
 * }
 * ```
 *
 * `.browser4` is a protected directory (see `DEFAULT_PROTECTED_DIRS`), so the agent cannot
 * write it — a job cannot rewrite its own permissions. `target` is interpreted by effect: a
 * command line for `fs.exec` / `proc.spawn`, a host for `net.connect`, otherwise a path that is
 * resolved against the workspace when relative.
 *
 * Parsing is deliberately forgiving for an operator-authored file: a missing file means "no
 * pre-approvals", unknown fields are ignored, malformed entries are skipped with a warning.
 * What is NOT forgiven is a rail: an entry that crosses `denyTools`, a protected path or the
 * workspace boundary is refused at approval time, and the refusal is audited like any other.
 */
object AccessPolicyFile {

    /** Location of the strategy file, relative to the task workspace. */
    const val RELATIVE_PATH = ".browser4/access-policy.json"

    private val logger = LoggerFactory.getLogger(AccessPolicyFile::class.java)
    private val mapper = ObjectMapper()

    /**
     * One `grants` entry. [effect] uses the `Effect` id spelling (`fs.write`, also accepting
     * `fs_write` in any casing — see [Effect.parse]); [ttlSeconds] / [reason] are optional, and
     * a `null` ttl means the grant lasts until the task ends.
     */
    data class Entry(
        val effect: String,
        val target: String,
        val ttlSeconds: Long? = null,
        val reason: String? = null,
    )

    /**
     * Reads [path], returning the well-formed entries.
     *
     * A missing file, unreadable JSON or a missing `grants` array all mean "no entries" — the
     * strategy file is optional by design, so none of these is an error.
     */
    fun load(path: Path): List<Entry> {
        if (!Files.isRegularFile(path)) return emptyList()
        val root = try {
            mapper.readTree(Files.readString(path))
        } catch (e: Exception) {
            logger.warn("access policy file {} is not valid JSON ({}); ignoring it", path, e.message)
            return emptyList()
        }
        val grants = root.get("grants")
        if (grants == null || !grants.isArray) {
            logger.warn("access policy file {} has no 'grants' array; ignoring it", path)
            return emptyList()
        }
        return grants.mapNotNull { node ->
            val effect = node.get("effect")?.asText()?.trim()
            val target = node.get("target")?.asText()?.trim()
            if (effect.isNullOrEmpty() || target.isNullOrEmpty()) {
                logger.warn("access policy file {}: skipping an entry without effect/target: {}", path, node)
                null
            } else {
                Entry(
                    effect = effect,
                    target = target,
                    ttlSeconds = node.get("ttlSeconds")?.takeIf { it.isNumber }?.asLong()?.takeIf { it > 0 },
                    reason = node.get("reason")?.asText()?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
        }
    }
}
