package ai.platon.pulsar.coding

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Provisioning of an agent workspace that mirrors a Browser4 checkout.
 *
 * The CLI-engine agent workspace (`<app-data>/agent/<date>/<uuid>/workspace`) is
 * a scratch directory. When the task is a self-development task, coding tools
 * write repository-relative paths there (`browser4-coding/src/...`), so the
 * directory has to look like the repository: every reactor module visible, plus
 * the build entry files (`pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn`, `VERSION`,
 * `ROOT.md`).
 *
 * Before this existed, an agent that wanted to run Maven had to repair the
 * workspace itself — observed in a real run: it ran `mklink /J` for every
 * module, copied the root files, and even deleted/re-created the
 * `browser4-coding` junction. Doing it here is deterministic, happens before the
 * first tool call, and keeps the agent out of workspace topology.
 *
 * Behaviour:
 * - **Derived from the reactor, not hardcoded**: the module list comes from
 *   `<sourceRepo>/pom.xml` (`<module>` entries), so a new module is mirrored
 *   automatically.
 * - **Idempotent and non-destructive**: an existing entry is never replaced,
 *   links are only created for missing paths, and the pass is a no-op once the
 *   workspace is complete.
 * - **Safe when there is nothing to mirror**: a real checkout (already a repo
 *   root) and a scratch directory with no detectable source repo are left
 *   untouched.
 */
object WorkspaceMirror {

    private val logger = LoggerFactory.getLogger(WorkspaceMirror::class.java)

    /** Root files Maven and the repo markers need in the workspace. */
    private val ROOT_FILES = listOf("pom.xml", "mvnw", "mvnw.cmd", "VERSION", "ROOT.md")

    /** Root directories linked as a whole (Maven wrapper config). */
    private val ROOT_DIRS = listOf(".mvn")

    private val IS_WINDOWS = System.getProperty("os.name").lowercase().contains("win")

    /**
     * One provisioning pass.
     *
     * @property sourceRepo the checkout the workspace was mirrored from, or null
     *   when no checkout could be found (nothing was done).
     */
    data class Report(
        val sourceRepo: Path?,
        val linked: List<String> = emptyList(),
        val copied: List<String> = emptyList(),
        val skipped: List<String> = emptyList(),
        val errors: List<String> = emptyList(),
    ) {
        val changed: Boolean get() = linked.isNotEmpty() || copied.isNotEmpty()

        fun summary(): String = when {
            sourceRepo == null -> "no source checkout found — workspace left untouched"
            !changed -> "workspace already mirrors ${sourceRepo.fileName} (${skipped.size} entries)"
            else -> "mirrored ${sourceRepo.fileName}: ${linked.size} linked, ${copied.size} copied" +
                (if (errors.isEmpty()) "" else ", ${errors.size} failed")
        }
    }

    /**
     * The checkout the backend runs from (its working directory or a parent), or
     * null for a packaged runtime that is not inside a source tree.
     */
    fun defaultSourceRepo(): Path? = runCatching {
        CodingWorkspace.findRepoRootFrom(Path.of(System.getProperty("user.dir")))
    }.getOrNull()

    /**
     * Make [workspace] a complete mirror of [sourceRepo].
     *
     * @param workspace the agent workspace (may already contain a partial mirror)
     * @param sourceRepo the checkout to mirror; defaults to [defaultSourceRepo]
     */
    fun ensure(workspace: Path, sourceRepo: Path? = defaultSourceRepo()): Report {
        val source = sourceRepo?.toAbsolutePath()?.normalize() ?: return Report(null)
        val target = workspace.toAbsolutePath().normalize()
        if (!Files.isDirectory(source) || !Files.isDirectory(target)) {
            return Report(source, errors = listOf("not a directory: ${if (!Files.isDirectory(source)) source else target}"))
        }
        // Never mirror a checkout onto itself, or into one of its own subdirectories.
        if (isSamePath(source, target) || target.startsWith(source)) {
            return Report(source)
        }
        if (!Files.isRegularFile(source.resolve("pom.xml"))) {
            return Report(null)
        }

        val linked = mutableListOf<String>()
        val copied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val errors = mutableListOf<String>()

        for (module in reactorModules(source)) {
            val link = target.resolve(module)
            if (Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                skipped += module
                continue
            }
            linkDirectory(source.resolve(module), link)
                ?.let { errors += it }
                ?: linked.add(module)
        }
        for (dir in ROOT_DIRS) {
            val sourceDir = source.resolve(dir)
            if (!Files.isDirectory(sourceDir)) continue
            val link = target.resolve(dir)
            if (Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                skipped += dir
                continue
            }
            linkDirectory(sourceDir, link)
                ?.let { errors += it }
                ?: linked.add(dir)
        }
        for (file in ROOT_FILES) {
            val sourceFile = source.resolve(file)
            if (!Files.isRegularFile(sourceFile)) continue
            val destination = target.resolve(file)
            if (Files.exists(destination, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                skipped += file
                continue
            }
            runCatching {
                Files.copy(sourceFile, destination, StandardCopyOption.COPY_ATTRIBUTES)
                copied += file
            }.onFailure { errors += "$file: ${it.message}" }
        }

        if (linked.isNotEmpty() || errors.isNotEmpty()) {
            logger.info("workspace mirror {}: linked=[{}] copied=[{}] skipped={} errors=[{}]",
                target, linked.joinToString(","), copied.joinToString(","), skipped.size,
                errors.joinToString("; "))
        }
        return Report(source, linked, copied, skipped, errors)
    }

    /** Top-level `<module>` entries of [sourceRepo]/pom.xml, in declaration order. */
    fun reactorModules(sourceRepo: Path): List<String> = runCatching {
        val pom = sourceRepo.resolve("pom.xml")
        if (!Files.isRegularFile(pom)) return emptyList()
        MODULE_TAG.findAll(Files.readString(pom))
            .map { it.groupValues[1].trim().trimEnd('/') }
            .filter { it.isNotEmpty() && !it.startsWith("..") }
            .distinct()
            .toList()
    }.getOrDefault(emptyList())

    /** Whether [workspace] already exposes every reactor module of [sourceRepo]. */
    fun isComplete(workspace: Path, sourceRepo: Path): Boolean {
        val modules = reactorModules(sourceRepo)
        if (modules.isEmpty()) return false
        return modules.all { Files.exists(workspace.resolve(it), java.nio.file.LinkOption.NOFOLLOW_LINKS) }
    }

    /**
     * Link [sourceDir] as [link]. Returns an error message on failure, null on
     * success. A directory junction is used on Windows (no privilege needed);
     * a symbolic link elsewhere.
     */
    private fun linkDirectory(sourceDir: Path, link: Path): String? {
        if (!Files.isDirectory(sourceDir)) return "${link.fileName}: source directory missing"
        return runCatching {
            if (IS_WINDOWS) {
                val process = ProcessBuilder(
                    "cmd", "/c", "mklink", "/J",
                    link.toString(), sourceDir.toString(),
                ).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
                    return "mklink failed for ${link.fileName}: ${output.trim()}"
                }
            } else {
                Files.createSymbolicLink(link, sourceDir)
            }
            null
        }.getOrElse { "link ${link.fileName} failed: ${it.message}" }
    }

    private fun isSamePath(a: Path, b: Path): Boolean =
        if (IS_WINDOWS) a.toString().equals(b.toString(), ignoreCase = true) else a == b

    private val MODULE_TAG = Regex("<module>\\s*([^<]+?)\\s*</module>")
}
