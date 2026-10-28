package ai.platon.pulsar.agentic.llm

import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.config.CapabilityTypes.LLM_PROVIDER_CONFIG_PATH
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * Browser4's own LLM provider registry.
 *
 * `browser4-base` ships a classpath `providers.json` that decides **both** the
 * provider catalog and the auto-detection order.  Browser4 owns that order: it
 * decides which provider a request goes to when several API keys are configured,
 * and it is a product decision that must not change as a side effect of a base
 * library upgrade.
 *
 * The base already offers a supported override for exactly this:
 * [LLM_PROVIDER_CONFIG_PATH] makes the engine read an external registry instead
 * of the built-in one.  The property has to name a file on disk, while this
 * repository ships its copy as a classpath resource, so [install] bridges the
 * two: it writes the packaged copy to `<app data>/config/providers.json` and
 * points the property at it, before any session or chat model exists.
 *
 * The materialized file is owned by Browser4 and is rewritten whenever the
 * packaged copy changes.  An explicitly configured override — `-D` system
 * property or the `LLM_PROVIDER_CONFIG_PATH` environment variable — always wins
 * and is never touched.
 */
object Browser4ProviderRegistry {
    private val logger = getLogger(Browser4ProviderRegistry::class)

    /** Browser4's provider registry, packaged by `browser4-resources`. */
    const val RESOURCE_PATH: String = "/config/llm/providers.json"

    /** File name the engine reads once [LLM_PROVIDER_CONFIG_PATH] points at it. */
    const val FILE_NAME: String = "providers.json"

    /** Environment-variable spelling of [LLM_PROVIDER_CONFIG_PATH]. */
    private const val ENV_NAME: String = "LLM_PROVIDER_CONFIG_PATH"

    /**
     * Materialize the packaged registry and point [LLM_PROVIDER_CONFIG_PATH] at it.
     *
     * Idempotent: an existing file with identical content is left alone, so the
     * registry's modification time — and therefore the engine's parse cache — stays
     * stable across restarts.
     *
     * @param targetDir Directory the registry file is written to; defaults to the
     *        application's `config` directory.  Injectable so tests stay off the
     *        real data directory.
     * @return The registry file now in use, or `null` when an explicit override is
     *         already configured, the packaged resource is missing, or the copy
     *         failed — in which case the engine keeps the base library's built-in
     *         list.
     */
    @JvmStatic
    @JvmOverloads
    fun install(targetDir: Path = AppPaths.CONFIG_DIR): Path? {
        configuredOverride()?.let { configured ->
            logger.info(
                "LLM provider registry override is already configured ({}); Browser4's copy is not installed",
                configured
            )
            return null
        }

        val bytes = runCatching {
            Browser4ProviderRegistry::class.java.getResourceAsStream(RESOURCE_PATH)?.use { it.readBytes() }
        }.getOrNull() ?: run {
            logger.warn("Browser4 LLM provider registry resource is missing: {}", RESOURCE_PATH)
            return null
        }

        val target = targetDir.resolve(FILE_NAME)
        return try {
            Files.createDirectories(targetDir)
            val unchanged = Files.exists(target) && Files.readAllBytes(target).contentEquals(bytes)
            if (!unchanged) {
                // Parse before the engine ever reads it: a malformed registry would
                // otherwise fail at the first chat call, far from the packaging mistake.
                pulsarObjectMapper().readTree(bytes)
                Files.write(target, bytes)
                logger.info("Installed Browser4 LLM provider registry: {}", target)
            }
            System.setProperty(LLM_PROVIDER_CONFIG_PATH, target.toAbsolutePath().toString())
            target
        } catch (e: Exception) {
            logger.warn(
                "Failed to install the Browser4 LLM provider registry at {}: {}",
                target, e.message
            )
            null
        }
    }

    /** The explicitly configured registry path, if any. */
    private fun configuredOverride(): String? =
        System.getProperty(LLM_PROVIDER_CONFIG_PATH)?.takeIf { it.isNotBlank() }
            ?: System.getenv(ENV_NAME)?.takeIf { it.isNotBlank() }
}
