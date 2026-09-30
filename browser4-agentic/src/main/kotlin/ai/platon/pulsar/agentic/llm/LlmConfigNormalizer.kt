package ai.platon.pulsar.agentic.llm

import ai.platon.pulsar.common.getLogger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Normalize LLM API keys written in "env style" (e.g. `DEEPSEEK_API_KEY=sk-...`)
 * inside the enabled config directory (`~/.browser4/config/conf-enabled/`).
 *
 * Env-style keys are valid as real environment variables, but a `.properties` file
 * is loaded verbatim: the Pulsar engine looks up dotted keys such as
 * `deepseek.api.key`, so an env-style entry silently never binds and
 * `extract` / `summarize` / agent commands fail with
 * "LLM API key is not configured" even though `doctor` may report the key present.
 *
 * This normalizer runs once at application startup, before any session is created:
 *  1. rewrites env-style LLM keys in the enabled properties files to their dotted
 *     equivalents (idempotent — dotted keys and commented lines are left untouched),
 *  2. mirrors them into system properties, which the engine resolves *before* the
 *     file-backed properties (`AbstractRelaxedConfiguration.getUnrelaxed` order:
 *     volatile -> Spring Environment -> System.getProperty -> file), so lookups that
 *     read system properties directly (e.g. `doctor llm-status`) see them too.
 *
 * Explicit dotted keys already present in a file always win: an env-style duplicate
 * of an already-configured dotted key is skipped, never overwritten.
 */
object LlmConfigNormalizer {
    private val logger = getLogger(LlmConfigNormalizer::class)

    /** Env-style prefixes that identify LLM-related keys. */
    private val LLM_KEY_PREFIXES = listOf(
        "LLM_", "DEEPSEEK_", "OPENROUTER_", "VOLCENGINE_", "OPENAI_"
    )

    /**
     * The enabled config directory (`~/.browser4/config/conf-enabled`), the
     * same location the engine's `ImmutableConfig` loads properties from.
     * Resolved without the SDK's `AppPaths` so this module does not depend on
     * a specific pulsar-common version.
     */
    private fun enabledConfigDir(): Path =
        Path.of(System.getProperty("user.home"), ".browser4", "config", "conf-enabled")

    /**
     * Normalize every `*.properties` file in the enabled config directory.
     * Safe to call multiple times; returns immediately when nothing to do.
     */
    fun normalize() {
        try {
            val enabledDir = enabledConfigDir()
            if (!Files.isDirectory(enabledDir)) return
            normalizeDir(enabledDir)
        } catch (e: Exception) {
            logger.warn("Failed to normalize LLM keys in conf-enabled: {}", e.message)
        }
    }

    /**
     * Normalize every `*.properties` file directly under [dir].  Exposed for
     * tests; production code should use [normalize].
     */
    internal fun normalizeDir(dir: Path) {
        try {
            Files.list(dir).use { stream ->
                stream
                    .filter { it.fileName.toString().endsWith(".properties") }
                    .forEach { normalizeFile(it) }
            }
        } catch (e: Exception) {
            logger.warn("Failed to normalize LLM keys in {}: {}", dir, e.message)
        }
    }

    private fun normalizeFile(path: Path) {
        val lines = runCatching { Files.readAllLines(path) }.getOrNull() ?: return

        // Explicit dotted keys already present (non-comment) take priority.
        val dottedKeysInFile = lines
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
            .mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq <= 0) null else line.substring(0, eq).trim()
            }
            .toHashSet()

        var changed = false
        val normalized = lines.map { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                line
            } else {
                val eq = trimmed.indexOf('=')
                if (eq <= 0) {
                    line
                } else {
                    val key = trimmed.substring(0, eq).trim()
                    val dotted = envStyleToDotted(key)
                    if (dotted == null || dotted == key) {
                        line
                    } else if (dotted in dottedKeysInFile) {
                        // Explicit dotted config wins; leave the env-style line alone.
                        line
                    } else {
                        changed = true
                        val value = trimmed.substring(eq + 1).trim()
                        if (System.getProperty(dotted) == null) {
                            System.setProperty(dotted, value)
                            logger.info(
                                "Normalized env-style LLM key {} -> {} (system property)",
                                key, dotted
                            )
                        }
                        "$dotted=$value"
                    }
                }
            }
        }

        if (changed) {
            try {
                Files.write(path, normalized)
                logger.info("Rewrote {} with dotted LLM keys", path)
            } catch (e: Exception) {
                logger.warn(
                    "Cannot rewrite {} ({}); system properties were still set",
                    path, e.message
                )
            }
        }
    }

    private fun envStyleToDotted(key: String): String? {
        val upper = key.uppercase()
        if (LLM_KEY_PREFIXES.none { upper.startsWith(it) }) return null
        return upper.lowercase().replace('_', '.')
    }
}
