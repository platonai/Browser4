package ai.platon.pulsar.coding.sandbox

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Plan P3-4 (decision D1) — turn the sandbox on for **new** installations only.
 *
 * A boundary that changes what every existing command may do cannot be switched on underneath an
 * installation that already exists: an ACL miss would surface as failed builds. So:
 *
 *  - an explicit `-Dbrowser4.agent.sandbox=<id>` always wins (and is never reinterpreted);
 *  - an installation whose data directory already exists keeps the historical `off`;
 *  - a fresh installation (no data directory yet) starts with the platform backend;
 *  - the decision is persisted next to the data directory, so a later start does not re-read the
 *    filesystem state and drift.
 *
 * Only Windows is defaulted for now: the Linux/macOS backends arrive with P2 (gate G2), and
 * selecting a backend that is not implemented is a hard failure, which must never be the default.
 *
 * This runs from the application entry points, not from the library default: unit tests and
 * embedded users keep `off` unless they ask for a sandbox explicitly.
 */
object SandboxDefaults {

    private val logger = LoggerFactory.getLogger(SandboxDefaults::class.java)

    /** Records the decision next to the data directory; its content is the chosen backend id. */
    internal const val MARKER = "sandbox-default"

    /**
     * Applies the default and publishes it as [ExecutionSandboxes.PROP_SANDBOX].
     *
     * @param dataDir the installation's data directory (`<browser4.data.dir>/.browser4`)
     * @return the effective backend id
     */
    fun apply(dataDir: Path = defaultDataDir()): String {
        val explicit = System.getProperty(ExecutionSandboxes.PROP_SANDBOX)?.trim()?.takeIf { it.isNotEmpty() }
        if (explicit != null) {
            val id = explicit.lowercase()
            logger.info("sandbox configured explicitly: {}", id)
            return id
        }

        val marker = dataDir.resolve(MARKER)
        val stored = if (Files.isRegularFile(marker)) {
            runCatching { Files.readString(marker).trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        if (stored != null) {
            publish(stored)
            logger.info("sandbox default restored: {} (marker={})", stored, marker)
            return stored
        }

        val fresh = !Files.isDirectory(dataDir)
        val id = if (fresh) defaultForPlatform() else OFF
        runCatching {
            Files.createDirectories(dataDir)
            Files.writeString(marker, id)
        }.onFailure { logger.warn("Failed to persist the sandbox default in {}: {}", marker, it.message) }
        publish(id)
        logger.info(
            "sandbox default chosen: {} (freshInstallation={} dataDir={})",
            id, fresh, dataDir,
        )
        return id
    }

    /** The backend a fresh installation starts with; only a working backend may be a default. */
    internal fun defaultForPlatform(osName: String = System.getProperty("os.name", "")): String =
        if (osName.lowercase().contains("win")) WindowsSandboxBootstrap.SANDBOX_ID else OFF

    /**
     * The installation's data directory, resolved the same way the rest of the product resolves it
     * (`<browser4.data.dir or user.home>/.browser4`).
     */
    fun defaultDataDir(): Path = Path.of(
        System.getProperty("browser4.data.dir", System.getProperty("user.home")),
        ".browser4",
    )

    private fun publish(id: String) {
        System.setProperty(ExecutionSandboxes.PROP_SANDBOX, id)
    }

    private const val OFF = "off"
}
