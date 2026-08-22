package ai.platon.pulsar.agentic.cli

import ai.platon.pulsar.common.getLogger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Thrown when no usable browser4-cli binary can be found. */
class CliUnavailableException(message: String) : IllegalStateException(message)

/**
 * Resolves the browser4-cli binary for this environment.
 *
 * Resolution order (M0/design §4.2):
 * 1. explicit path (configuration);
 * 2. bundled binary (next to the app / `bin/`);
 * 3. PATH lookup;
 * 4. repo dev wrapper (`b4w.ps1` / `b4w.sh`) when the working directory is
 *    inside a Browser4 source tree;
 * 5. otherwise fail loudly with install guidance (auto-install is a later
 *    milestone — never silently degrade).
 *
 * The resolved binary is version-probed once and cached.
 */
class CliBinaryResolver(
    private val explicitPath: Path? = null,
) {
    private val logger = getLogger(this)
    private val versionCache = ConcurrentHashMap<Path, String>()

    fun resolve(): Path {
        explicitPath?.let { p ->
            if (Files.exists(p)) return p
            logger.warn("Configured browser4-cli path does not exist: {}", p)
        }

        bundled()?.let { return it }
        pathLookup()?.let { return it }
        devWrapper()?.let { return it }

        throw CliUnavailableException(
            "browser4-cli not found. Install it via 'browser4-cli install' (or set " +
                "the explicit path in configuration), or run from a Browser4 source tree " +
                "where b4w.ps1/b4w.sh is present."
        )
    }

    /** Probe `--version` once per binary; returns null on failure. */
    fun version(binary: Path): String? {
        versionCache[binary]?.let { return it }
        return try {
            val proc = ProcessBuilder(binary.toString(), "--version")
                .redirectErrorStream(true)
                .start()
            if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                null
            } else {
                val text = proc.inputStream.bufferedReader().use { it.readText() }.trim()
                text.takeIf { it.isNotEmpty() }?.also { versionCache[binary] = it }
            }
        } catch (e: IOException) {
            logger.warn("browser4-cli version probe failed: {}", e.message)
            null
        }
    }

    private fun bundled(): Path? {
        val candidates = buildList {
            add(Path.of("bin"))
            System.getProperty("java.home")?.let { add(Path.of(it).resolve("..").resolve("bin")) }
        }
        val exe = if (isWindows) "browser4-cli.exe" else "browser4-cli"
        candidates.forEach { dir ->
            val p = dir.resolve(exe)
            if (Files.isRegularFile(p)) return p
        }
        return null
    }

    private fun pathLookup(): Path? {
        val pathVar = envLookup("PATH") ?: return null
        val exe = if (isWindows) "browser4-cli.exe" else "browser4-cli"
        return pathVar.split(if (isWindows) ";" else ":")
            .filter { it.isNotBlank() }
            .map { Path.of(it).resolve(exe) }
            .firstOrNull { Files.isRegularFile(it) }
    }

    private fun devWrapper(): Path? {
        var dir = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            val wrapper = if (isWindows) dir.resolve("b4w.ps1") else dir.resolve("b4w.sh")
            if (Files.isRegularFile(wrapper)) return wrapper
            dir = dir.parent ?: break
        }
        return null
    }

    private fun envLookup(name: String): String? {
        val env = System.getenv()
        return env.entries.firstOrNull { it.key.equals(name, ignoreCase = isWindows) }?.value
    }

    private val isWindows: Boolean
        get() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
