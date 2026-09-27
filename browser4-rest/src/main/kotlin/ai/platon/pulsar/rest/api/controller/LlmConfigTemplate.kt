package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.AppPaths
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists

/**
 * The user-editable LLM configuration file, and the template shipped for it.
 *
 * Configuration is read once, at startup, from `<app data dir>/config/conf-enabled/`.
 * Nothing ships a file there and nothing used to print its full name, so a user (or
 * the agent acting for them) had to invent both the directory and the file name —
 * the gap behind "I configured the file and nothing changed".
 *
 * This object is the single place that answers *which* file that is and how to give
 * the user one, so the `doctor` endpoint and the CLI cannot drift apart.
 */
object LlmConfigTemplate {

    /** The file name the configuration loader looks for. */
    const val FILE_NAME: String = "application-private.properties"

    /**
     * The commented template bundled on the classpath (inside `browser4-resources`).
     *
     * It carries a `.template` suffix because `.gitignore` deliberately ignores every
     * `application-private.properties` — a shipped template must not have to fight that
     * rule (or be force-added around it).  [install] writes it out under [FILE_NAME].
     */
    const val RESOURCE: String = "/config/conf-available/$FILE_NAME.template"

    /**
     * What [install] did.
     *
     * @property fileName        The template's file name.
     * @property availablePath   `<config>/conf-available/<fileName>` — the inert copy.
     * @property enabledPath     `<config>/conf-enabled/<fileName>` — the copy that is read.
     * @property templateWritten `true` when the inert copy was created by this call.
     * @property enabledWritten  `true` when the active copy was created by this call, i.e.
     *                           the next start reads the file (configuration is loaded
     *                           once, at startup).
     */
    data class Installation(
        val fileName: String,
        val availablePath: Path,
        val enabledPath: Path,
        val templateWritten: Boolean,
        val enabledWritten: Boolean,
    ) {
        /** `true` when the active copy was created and a restart can therefore change behavior. */
        val restartRequired: Boolean get() = enabledWritten
    }

    /** The file the backend reads, i.e. `<app data dir>/config/conf-enabled/<FILE_NAME>`. */
    fun enabledPath(): Path = AppPaths.CONFIG_ENABLED_DIR.resolve(FILE_NAME)

    /** The inert template location, i.e. `<app data dir>/config/conf-available/<FILE_NAME>`. */
    fun availablePath(): Path = AppPaths.CONFIG_AVAILABLE_DIR.resolve(FILE_NAME)

    /**
     * Materialize the bundled template into `conf-available/` and enable it in
     * `conf-enabled/`.
     *
     * Existing files are never overwritten, so this is safe to call from a repair
     * command: a user's edited configuration always wins.
     *
     * @param availableDir The inert template directory; defaults to the app's
     *        `conf-available`.  Injectable so that tests stay off the real data dir.
     * @param enabledDir The loaded directory; defaults to the app's `conf-enabled`.
     * @return The installation, or `null` when the bundled template is missing from
     *         the classpath (broken packaging).
     */
    @Throws(IOException::class)
    fun install(
        availableDir: Path = AppPaths.CONFIG_AVAILABLE_DIR,
        enabledDir: Path = AppPaths.CONFIG_ENABLED_DIR,
    ): Installation? {
        val template = javaClass.getResourceAsStream(RESOURCE)?.use { it.readBytes() } ?: return null

        val availablePath = availableDir.resolve(FILE_NAME)
        val templateWritten = writeIfAbsent(availablePath, template)
        val enabledWritten = copyIfAbsent(availableDir, enabledDir, FILE_NAME)

        return Installation(
            fileName = FILE_NAME,
            availablePath = availablePath,
            enabledPath = enabledDir.resolve(FILE_NAME),
            templateWritten = templateWritten,
            enabledWritten = enabledWritten,
        )
    }

    /**
     * Abbreviate the user's home directory in [path] to `~`, so that a report reads as
     * `~/.browser4/config/conf-enabled/application-private.properties` instead of
     * leaking a machine-specific prefix.
     */
    fun displayPath(path: Path): String {
        val absolute = path.toAbsolutePath().toString()
        val home = AppContext.USER_HOME.trimEnd('/', '\\')
        if (home.isEmpty() || !absolute.startsWith(home)) {
            return absolute
        }

        return "~" + absolute.substring(home.length)
    }

    /** @return `true` when the file was created. */
    @Throws(IOException::class)
    private fun writeIfAbsent(path: Path, content: ByteArray): Boolean {
        if (path.exists()) {
            return false
        }

        path.parent?.let { Files.createDirectories(it) }
        Files.write(path, content, StandardOpenOption.CREATE_NEW)
        return true
    }

    /** @return `true` when `sourceDir/<fileName>` was copied into [targetDir]. */
    @Throws(IOException::class)
    private fun copyIfAbsent(sourceDir: Path, targetDir: Path, fileName: String): Boolean {
        val source = sourceDir.resolve(fileName)
        if (!source.exists()) {
            return false
        }

        val target = targetDir.resolve(fileName)
        if (target.exists()) {
            return false
        }

        Files.createDirectories(targetDir)
        Files.copy(source, target)
        return true
    }
}
