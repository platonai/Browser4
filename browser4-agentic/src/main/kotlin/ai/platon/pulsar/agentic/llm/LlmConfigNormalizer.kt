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
 *  2. mirrors **all** LLM keys it finds — env-style *and* already-dotted — into system
 *     properties, which the engine resolves *before* the file-backed properties
 *     (`AbstractRelaxedConfiguration.getUnrelaxed` order: volatile -> Spring
 *     Environment -> System.getProperty -> file).
 *
 * Step 2 used to cover only env-style keys: a file already in the documented dotted
 * form (`deepseek.api.key=…`) was left entirely to the engine's own file loading. A
 * rebuilt runtime bundle then answered `No LLM configured` for `agent run` while
 * `doctor` read the very same file and reported `✓ LLM is configured` — so the
 * dotted keys now bind through system properties too.
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

    /** Dotted provider prefixes the engine resolves (`deepseek.api.key`, …). */
    private val PROVIDER_KEY_PREFIXES = listOf(
        "llm.", "deepseek.", "openrouter.", "volcengine.", "openai."
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
        // Every provider key this file configures, dotted form -> value. Mirrored
        // into system properties after the rewrite pass (see the class KDoc: the
        // engine's lookup order is volatile -> Spring Environment ->
        // System.getProperty -> file, so a system property is what actually binds).
        val providerKeys = LinkedHashMap<String, String>()
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
                    val value = trimmed.substring(eq + 1).trim()
                    val dotted = envStyleToDotted(key)
                    when {
                        dotted == null || dotted == key -> {
                            // Already-dotted provider configuration (`deepseek.api.key=…`):
                            // this is the documented file format, and it used to be left
                            // entirely to the engine's own file loading — which a live
                            // bundle run did NOT honour ("No LLM configured" while doctor
                            // read the very same file). Mirror it.
                            if (isProviderKey(key)) providerKeys[key] = value
                            line
                        }
                        dotted in dottedKeysInFile -> {
                            // Explicit dotted config wins; leave the env-style line alone.
                            line
                        }
                        else -> {
                            changed = true
                            providerKeys[dotted] = value
                            "$dotted=$value"
                        }
                    }
                }
            }
        }

        providerKeys.forEach { (key, value) ->
            if (System.getProperty(key) == null) {
                System.setProperty(key, value)
                logger.info("Mirrored LLM config {} from {} into system properties", key, path.fileName)
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

    /** Dotted provider keys the engine actually looks up (`deepseek.api.key`, …). */
    private fun isProviderKey(key: String): Boolean {
        val lower = key.lowercase()
        return PROVIDER_KEY_PREFIXES.any { lower.startsWith(it) }
    }

    private fun envStyleToDotted(key: String): String? {
        val upper = key.uppercase()
        if (LLM_KEY_PREFIXES.none { upper.startsWith(it) }) return null
        return upper.lowercase().replace('_', '.')
    }
}
