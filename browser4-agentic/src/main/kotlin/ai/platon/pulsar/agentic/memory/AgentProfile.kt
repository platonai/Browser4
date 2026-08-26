package ai.platon.pulsar.agentic.memory

import ai.platon.pulsar.common.getLogger
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User preference memory (design §9, M3): a lightweight YAML KV per principal
 * (`profiles/<principal>.yaml`).
 *
 * Discipline: only EXPLICIT preferences are recorded — the agent extracts
 * them from completion summaries (e.g. "以后用中文输出" → `language=zh`) and
 * counts the domains tasks actually visited (`domain_count:<domain>`). No
 * implicit inference, so noise stays out of the profile.
 */
class AgentProfile(
    private val baseDir: Path,
    private val principal: String = "default",
) {
    private val logger = getLogger(AgentProfile::class)

    private val file: Path = baseDir.resolve("profiles").resolve(sanitize(principal) + ".yaml")

    private val yaml: Yaml by lazy {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            defaultScalarStyle = DumperOptions.ScalarStyle.PLAIN
        }
        Yaml(options)
    }

    private val values = ConcurrentHashMap<String, String>()
    private val counters = ConcurrentHashMap<String, Long>()
    private val dirty = AtomicBoolean(false)

    companion object {
        /** Allows `:` for scoped keys such as `domain_count:<domain>`. */
        private val KEY_PATTERN = Regex("[a-zA-Z0-9_.:-]{1,48}")
        private val LANGUAGE_RE = Regex(
            "(?i)(以后|今后|下次)?(都|就)?(用|使用|以)?(中文|汉语|英文|英语|english)(输出|总结|回答|回复|汇报|沟通)?"
        )

        /** `https?://host/...` → host (best-effort). */
        fun extractDomain(url: String?): String? {
            if (url.isNullOrBlank()) return null
            val m = Regex("https?://([^/\\s]+)").find(url) ?: return null
            return m.groupValues[1].removePrefix("www.").lowercase()
        }
    }

    init {
        load()
    }

    fun load() {
        runCatching {
            if (!Files.exists(file)) return
            val data = yaml.load<Map<String, Any>>(Files.readString(file, StandardCharsets.UTF_8))
            (data["values"] as? Map<*, *>)?.forEach { (k, v) -> values[k.toString()] = v.toString() }
            (data["counters"] as? Map<*, *>)?.forEach { (k, v) ->
                (v as? Number)?.let { counters[k.toString()] = it.toLong() }
            }
        }.onFailure { logger.warn("memory.profile.load failed: {}", it.message) }
    }

    /** Set one explicit preference; persists immediately. Returns a confirmation. */
    @Synchronized
    fun set(key: String, value: String): String {
        require(KEY_PATTERN.matches(key)) { "Invalid profile key '$key'" }
        values[key] = Sanitizer.brief(value, 100)
        save()
        return "Preference saved: $key=$value"
    }

    fun get(key: String): String? = values[key]

    /** Count one observation (e.g. a visited domain); persists immediately. */
    @Synchronized
    fun increment(key: String): Long {
        require(KEY_PATTERN.matches(key)) { "Invalid profile key '$key'" }
        val n = (counters[key] ?: 0) + 1
        counters[key] = n
        save()
        return n
    }

    /**
     * Extract EXPLICIT user preferences from a completion summary / user text
     * (conservative: language switches only, no inference). Persists on change.
     */
    @Synchronized
    fun applyExplicitPrefs(text: String?) {
        if (text.isNullOrBlank()) return
        var changed = false
        LANGUAGE_RE.find(text)?.let { m ->
            val lang = m.value.lowercase().let {
                when {
                    it.contains("english") || it.contains("英文") || it.contains("英语") -> "en"
                    it.contains("中文") || it.contains("汉语") -> "zh"
                    else -> null
                }
            }
            if (lang != null && values["language"] != lang) {
                values["language"] = lang
                changed = true
            }
        }
        if (changed) save()
    }

    /** Rendered preference line for the recall injection, or null when empty. */
    fun render(maxChars: Int = 300): String? {
        val parts = mutableListOf<String>()
        values.entries.sortedBy { it.key }.forEach { (k, v) -> parts.add("$k=$v") }
        counters.entries
            .filter { it.key.startsWith("domain_count:") }
            .sortedByDescending { it.value }
            .take(2)
            .forEach { (k, v) -> parts.add("常访问 ${k.substringAfter(':')}×$v") }
        if (parts.isEmpty()) return null
        return ("- 用户偏好: " + parts.joinToString(" · ")).take(maxChars)
    }

    private fun save() {
        runCatching {
            Files.createDirectories(file.parent)
            val data = linkedMapOf<String, Any>(
                "values" to values.toSortedMap(),
                "counters" to counters.toSortedMap(),
            )
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(tmp, yaml.dump(data), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            Files.deleteIfExists(file)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            dirty.set(false)
        }.onFailure { logger.warn("memory.profile.save failed: {}", it.message) }
    }

    private fun sanitize(principal: String): String =
        principal.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(40)
}
