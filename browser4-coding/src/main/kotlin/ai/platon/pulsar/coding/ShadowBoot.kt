package ai.platon.pulsar.coding

import org.slf4j.LoggerFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Boot smoke test for changes that touch the backend's start path (plan §D9).
 *
 * A compile check cannot catch a change that compiles but fails to start
 * (a broken bean graph, a duplicate auto-configuration, a bad `@Value`). For
 * those modules this class starts the **new code** once, on a spare port with a
 * throwaway data directory, waits for `/health`, and lets the caller refuse the
 * merge if the server never becomes ready.
 *
 * ## How the new code is put on the classpath
 *
 * Building a second runtime bundle costs minutes. Instead the worktree is
 * compiled (`mvn -pl <changed modules> -am compile`) and its `target/classes`
 * directories are placed **before** the existing bundle's `app-classes` and
 * `lib` jars. Changed classes shadow the old ones, everything else comes from
 * the bundle the backend already runs on. That is enough to prove "this change
 * boots"; a *dependency* version change still needs the normal build.
 *
 * ## Defaults and failure semantics
 *
 * The mode is `off` by default: the probe costs a Maven compile plus a JVM start
 * (tens of seconds to minutes), so it is an explicit operator choice. `auto`
 * probes only changes to the boot chain; `always` probes every code change.
 *
 * An **unavailable** probe (no runtime bundle, no compiler) is reported as
 * `skipped = true` and allows the merge: the gate's job is to catch a regression,
 * not to block work on machines that cannot run it. A probe that actually ran and
 * failed refuses the merge.
 */
object ShadowBoot {

    private val logger = LoggerFactory.getLogger(ShadowBoot::class.java)

    const val MODE_PROPERTY = "browser4.agent.isolation.shadowBoot"
    const val TIMEOUT_SECONDS_PROPERTY = "browser4.agent.isolation.shadowBootTimeoutSeconds"
    const val BUILD_TIMEOUT_SECONDS_PROPERTY = "browser4.agent.isolation.shadowBootBuildTimeoutSeconds"

    /** Main class of the runtime bundle (`application-bundle.properties` is loaded by its own profiles). */
    private const val MAIN_CLASS = "ai.platon.pulsar.apps.Browser4BundleApplicationKt"

    /** Modules whose changes can plausibly break startup. */
    private val BOOT_CHAIN_PREFIXES = listOf(
        "browser4-rest",
        "browser4-boot",
        "browser4-apps/",
        "browser4-agent-tools",
        "browser4-agentic",
        "browser4-coding",
        "browser4-core/",
        "browser4-pdk",
    )

    /** Outcome of one probe. */
    data class Outcome(
        val ok: Boolean,
        val skipped: Boolean,
        val reason: String,
        val logTail: String = "",
    )

    /** `off` | `auto` | `always`. */
    fun modeFromConfig(): String =
        System.getProperty(MODE_PROPERTY, "off").trim().lowercase()

    fun timeoutSecondsFromConfig(): Long =
        System.getProperty(TIMEOUT_SECONDS_PROPERTY, "120").trim().toLongOrNull()?.coerceAtLeast(10) ?: 120

    fun buildTimeoutSecondsFromConfig(): Long =
        System.getProperty(BUILD_TIMEOUT_SECONDS_PROPERTY, "600").trim().toLongOrNull()?.coerceAtLeast(30) ?: 600

    /**
     * Whether this change set needs a boot probe.
     *
     * @param changedFiles repo-relative paths from the isolated worktree
     * @param mode `off` / `auto` / `always`
     */
    fun shouldRun(changedFiles: Collection<String>, mode: String = modeFromConfig()): Boolean = when (mode) {
        "off" -> false
        "always" -> changedFiles.isNotEmpty()
        "auto" -> ModuleResolver.modulesOf(changedFiles).any { module ->
            BOOT_CHAIN_PREFIXES.any { module == it || module.startsWith(it) }
        }
        else -> {
            logger.warn("unknown {}=\"{}\"; shadow boot stays off", MODE_PROPERTY, mode)
            false
        }
    }

    /**
     * Compile the changed modules and try to start the backend from the worktree.
     *
     * @param repoRoot the checkout the worktree belongs to (its runtime bundle is reused)
     * @param worktree the isolated tree holding the task's code
     * @param changedFiles repo-relative paths the task changed
     */
    fun verify(
        repoRoot: Path,
        worktree: Path,
        changedFiles: Collection<String>,
        timeoutSeconds: Long = timeoutSecondsFromConfig(),
        buildTimeoutSeconds: Long = buildTimeoutSecondsFromConfig(),
        mode: String = modeFromConfig(),
    ): Outcome {
        if (!shouldRun(changedFiles, mode)) {
            return Outcome(true, true, "shadow boot not required (mode=$mode)")
        }
        val modules = ModuleResolver.modulesOf(changedFiles).filter { it.isNotBlank() }
        if (modules.isEmpty()) {
            return Outcome(true, true, "shadow boot skipped: no module owns the changed files")
        }
        val bundle = findBundle(worktree, repoRoot)
            ?: return Outcome(true, true, "shadow boot skipped: no assembled runtime bundle to borrow its libraries from")
        val java = bundle.javaExecutable
            ?: return Outcome(true, true, "shadow boot skipped: the bundle has no java executable")

        val buildLog = worktree.resolve(".browser4/shadow-boot-build.log")
        compile(worktree, modules, buildTimeoutSeconds, buildLog)?.let { failure ->
            return Outcome(false, false, "shadow boot build failed: $failure", tail(buildLog, 40))
        }

        val port = runCatching { ServerSocket(0).use { it.localPort } }.getOrElse { e ->
            return Outcome(true, true, "shadow boot skipped: no free port (${e.message})")
        }
        val dataDir = Files.createTempDirectory("b4-shadow-data-")
        val tmpDir = Files.createTempDirectory("b4-shadow-tmp-")
        val bootLog = Files.createTempFile("b4-shadow-boot-", ".log")
        val process = try {
            ProcessBuilder(
                java.toString(),
                "-cp", classpath(bundle, worktree),
                "-Dapp.name=browser4",
                "-Dapp.data.dir=$dataDir",
                "-Dapp.tmp.base.dir=$tmpDir",
                "-Dserver.port=$port",
                "-Dbrowser4.agent.sandbox=off",
                MAIN_CLASS,
            )
                .directory(worktree.toFile())
                .redirectErrorStream(true)
                .redirectOutput(bootLog.toFile())
                .start()
        } catch (e: Exception) {
            cleanup(dataDir, tmpDir, bootLog)
            return Outcome(true, true, "shadow boot skipped: could not start java (${e.message})")
        }

        logger.info("shadow boot: starting the worktree build on port {}", port)
        return try {
            awaitReady(process, port, timeoutSeconds, bootLog)
        } finally {
            killTree(process)
            cleanup(dataDir, tmpDir, bootLog)
        }
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    internal data class Bundle(
        val root: Path,
        val appClasses: Path,
        val lib: Path,
        val javaExecutable: Path?,
    )

    /**
     * The assembled dev bundle, if the checkout has one.
     *
     * Layout: `browser4-apps/browser4-bundle/target/runtime-bundle/_work/<platform>/<bundle>/lib`,
     * with `app-classes` as a sibling of the `<bundle>` directory. The platform
     * segment is discovered instead of hardcoded because it carries the target
     * triple.
     */
    internal fun findBundle(worktree: Path, repoRoot: Path): Bundle? {
        for (root in listOf(worktree, repoRoot)) {
            val work = root.resolve("browser4-apps/browser4-bundle/target/runtime-bundle/_work")
            if (!Files.isDirectory(work)) continue
            val found = runCatching {
                Files.walk(work, 6).use { stream ->
                    stream.filter { Files.isDirectory(it) && it.fileName.toString() == "lib" }
                        .map { it.parent }
                        .filter { Files.isDirectory(it) }
                        .filter { Files.isDirectory(it.parent.resolve("app-classes")) }
                        .findFirst()
                        .orElse(null)
                }
            }.getOrNull() ?: continue
            val javaName = if (isWindows()) "java.exe" else "java"
            val java = found.resolve("runtime/bin/$javaName").takeIf { Files.isRegularFile(it) }
            return Bundle(found, found.parent.resolve("app-classes"), found.resolve("lib"), java)
        }
        return null
    }

    /** Compile the changed modules (and their upstreams) in the worktree. Returns null on success. */
    private fun compile(worktree: Path, modules: List<String>, timeoutSeconds: Long, log: Path): String? {
        val windows = isWindows()
        val mvnw = worktree.resolve(if (windows) "mvnw.cmd" else "mvnw")
        val (program, prefix) = when {
            Files.isRegularFile(mvnw) && windows -> "cmd" to listOf("/c", mvnw.toString())
            Files.isRegularFile(mvnw) -> mvnw.toString() to emptyList()
            else -> "mvn" to emptyList()
        }
        val command = prefix + listOf(
            "-B", "-q", "-pl", modules.joinToString(","), "-am", "-DskipTests", "compile",
        )
        runCatching { Files.createDirectories(log.parent) }
        logger.info("shadow boot: compiling {} in {}", modules.joinToString(","), worktree)
        val process = runCatching {
            ProcessBuilder(listOf(program) + command)
                .directory(worktree.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start()
        }.getOrElse { return "could not start the compiler: ${it.message}" }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            killTree(process)
            return "build timed out after ${timeoutSeconds}s"
        }
        return if (process.exitValue() == 0) null else "exit ${process.exitValue()}"
    }

    private fun classpath(bundle: Bundle, worktree: Path): String {
        val entries = mutableListOf<String>()
        // The task's own classes first: they must shadow the bundle's older jars.
        for (module in ModuleMap.MODULES) {
            val classes = worktree.resolve(module).resolve("target/classes")
            if (Files.isDirectory(classes)) entries += classes.toString()
        }
        entries += bundle.appClasses.toString()
        runCatching {
            Files.list(bundle.lib).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".jar") }
                    .sorted()
                    .forEach { entries.add(it.toString()) }
            }
        }
        return entries.joinToString(File.pathSeparator)
    }

    private fun awaitReady(process: Process, port: Int, timeoutSeconds: Long, bootLog: Path): Outcome {
        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                return Outcome(
                    false,
                    false,
                    "shadow boot failed: the server exited before /health became ready (exit ${process.exitValue()})",
                    tail(bootLog, 50),
                )
            }
            if (healthy(port)) {
                logger.info("shadow boot: /health became ready on port {}", port)
                return Outcome(true, false, "shadow boot ready on port $port")
            }
            Thread.sleep(1_000)
        }
        return Outcome(
            false,
            false,
            "shadow boot failed: /health was not ready within ${timeoutSeconds}s",
            tail(bootLog, 50),
        )
    }

    private fun healthy(port: Int): Boolean = runCatching {
        val connection = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
        connection.connectTimeout = 1_000
        connection.readTimeout = 1_000
        connection.requestMethod = "GET"
        try {
            connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    private fun killTree(process: Process) {
        runCatching { process.descendants().forEach { it.destroyForcibly() } }
        runCatching { process.destroyForcibly() }
        runCatching { process.waitFor(10, TimeUnit.SECONDS) }
    }

    private fun cleanup(vararg paths: Path) {
        paths.forEach { path ->
            runCatching {
                if (Files.isDirectory(path)) {
                    Files.walk(path).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                } else {
                    Files.deleteIfExists(path)
                }
            }
        }
    }

    private fun tail(path: Path, lines: Int): String = runCatching {
        Files.readAllLines(path).takeLast(lines).joinToString("\n")
    }.getOrDefault("")

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")
}
