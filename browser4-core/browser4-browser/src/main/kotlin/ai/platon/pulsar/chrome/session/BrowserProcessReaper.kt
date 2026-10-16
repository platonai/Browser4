package ai.platon.pulsar.chrome.session

import ai.platon.pulsar.common.browser.BrowserFiles
import ai.platon.pulsar.common.getLogger
import java.nio.file.Files
import java.nio.file.Path

/**
 * What happened when the reaper tried to terminate one browser process tree.
 *
 * [SURVIVED] and [NOT_OURS] and [NO_PID_FILE] are reported, never smoothed over:
 * a shutdown path that says "terminated" while the process is still running is
 * exactly the lie this exists to stop.
 */
enum class ProcessTermination {
    /** The launch left no pid marker — nothing was proven either way. */
    NO_PID_FILE,

    /** The pid marker names a process that is already gone. */
    ALREADY_GONE,

    /** The pid is alive but is not the browser this session launched (pid reuse). */
    NOT_OURS,

    /** The process tree is gone (it was killed, or exited while we waited). */
    TERMINATED,

    /** The kill was issued but the process is still alive after the wait. */
    SURVIVED,

    /** Terminating threw; [ProcessTerminationReport.detail] carries the message. */
    FAILED,
    ;

    /** True only when the caller may state that the browser is really gone. */
    val isGone: Boolean get() = this == ALREADY_GONE || this == TERMINATED
}

/**
 * Honest outcome of reaping one browser process tree.
 *
 * @property outcome the classified result
 * @property pid the pid named by the session's own pid marker, when there was one
 * @property pidFile the marker that was read (`<contextDir>/launcher.pid`)
 * @property detail free-text evidence (command line, failure message)
 * @property requestedTerminations how many processes the kill covered
 */
data class ProcessTerminationReport(
    val outcome: ProcessTermination,
    val pid: Long? = null,
    val pidFile: Path? = null,
    val detail: String = "",
    val requestedTerminations: Int = 0,
) {
    /** True when the browser process is verified to be gone. */
    val isGone: Boolean get() = outcome.isGone

    /** One line a caller can put in a log or a tool response. */
    val summary: String
        get() = when (outcome) {
            ProcessTermination.NO_PID_FILE -> "no pid marker at $pidFile; browser state unknown"
            ProcessTermination.ALREADY_GONE -> "browser process $pid already exited"
            ProcessTermination.NOT_OURS ->
                "pid $pid is alive but does not look like the browser this session launched; left alone"
            ProcessTermination.TERMINATED -> "browser process tree $pid terminated"
            ProcessTermination.SURVIVED -> "browser process tree $pid survived the kill; it is still running"
            ProcessTermination.FAILED -> "could not terminate browser process $pid: $detail"
        }
}

/**
 * The process facts the reaper needs, behind a seam so the decision logic can be
 * tested without spawning (or killing) anything.
 */
data class ProcessFacts(
    val pid: Long,
    val alive: Boolean,
    /** Executable path, when the OS exposes it. */
    val command: String? = null,
    /** Full command line, when the OS exposes it. */
    val commandLine: String? = null,
)

/** Process-table seam: the real implementation is [JdkProcessTable]. */
interface ProcessTable {
    /** Facts for [pid], or `null` when no such process can be observed at all. */
    fun facts(pid: Long): ProcessFacts?

    /**
     * Ask [pid] and its descendants to terminate, escalating to a forced kill.
     *
     * @return how many processes the request covered (never a success claim —
     *   the caller verifies liveness afterwards).
     */
    fun terminateTree(pid: Long): Int
}

/**
 * [ProcessTable] backed by [ProcessHandle] (JDK 9+), so it needs no native code
 * and behaves the same on Windows, Linux and macOS.
 */
object JdkProcessTable : ProcessTable {
    override fun facts(pid: Long): ProcessFacts? {
        if (pid <= 0) return null
        val handle = ProcessHandle.of(pid).orElse(null) ?: return ProcessFacts(pid, alive = false)
        if (!handle.isAlive) return ProcessFacts(pid, alive = false)
        val info = handle.info()
        return ProcessFacts(
            pid = pid,
            alive = true,
            command = info.command().orElse(null),
            commandLine = info.commandLine().orElse(null),
        )
    }

    override fun terminateTree(pid: Long): Int {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return 0
        var count = 0
        // Children first (deepest last), so a helper process cannot outlive its
        // parent: Chrome spawns one process per renderer/gpu/utility.
        val descendants = handle.descendants().toList().asReversed()
        descendants.forEach { child ->
            if (runCatching { child.destroy() }.getOrDefault(false)) count++
        }
        // Give the graceful pass a moment, then force what is left.
        runCatching { handle.destroy() }.getOrDefault(false).let { if (it) count++ }
        if (handle.isAlive) {
            descendants.filter { it.isAlive }.forEach { child ->
                runCatching { child.destroyForcibly() }
            }
            if (runCatching { handle.destroyForcibly() }.getOrDefault(false)) count++
        }
        return count
    }
}

/**
 * Terminates the browser process tree a session launched, and says honestly
 * whether it succeeded.
 *
 * The launcher already records the pid it started in `<contextDir>/launcher.pid`
 * and closes it on the normal path, so this is the *safety net*: it runs after
 * the session's browser was closed and only acts when the process is still
 * alive. Two properties make it safe to run on a machine with other agents'
 * browsers:
 *
 * 1. it only ever looks at the pid in ONE session's own context directory;
 * 2. it refuses to kill a process whose command line does not name that
 *    directory, because a recycled pid can belong to anything.
 *
 * @param processTable the process access seam (fake in tests)
 * @param killWaitMillis how long to wait for a killed tree to disappear
 */
class BrowserProcessReaper(
    private val processTable: ProcessTable = JdkProcessTable,
    private val killWaitMillis: Long = DEFAULT_KILL_WAIT_MILLIS,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
) {
    private val logger = getLogger(BrowserProcessReaper::class)

    /**
     * Terminate the browser process tree recorded for [contextDir].
     *
     * @param contextDir the session's chrome context dir — the directory the
     *   launcher writes `launcher.pid` into (the per-launch `cx.<n>` directory,
     *   whose `PULSAR_CHROME` child is the user data dir Chrome was started with)
     * @return an honest report; [ProcessTerminationReport.isGone] is the only
     *   claim callers may repeat to a user
     */
    fun terminate(contextDir: Path?): ProcessTerminationReport {
        val pidFile = contextDir?.resolve(BrowserFiles.PID_FILE_NAME)
            ?: return ProcessTerminationReport(ProcessTermination.NO_PID_FILE, detail = "no context dir")
        if (!Files.isRegularFile(pidFile)) {
            return ProcessTerminationReport(
                ProcessTermination.NO_PID_FILE, pidFile = pidFile, detail = "missing ${pidFile.fileName}"
            )
        }

        val pid = runCatching { Files.readString(pidFile).trim().toLong() }.getOrNull()
        if (pid == null || pid <= 0) {
            return ProcessTerminationReport(
                ProcessTermination.NO_PID_FILE, pidFile = pidFile, detail = "unreadable pid marker"
            )
        }

        val facts = processTable.facts(pid)
        if (facts == null || !facts.alive) {
            return ProcessTerminationReport(ProcessTermination.ALREADY_GONE, pid = pid, pidFile = pidFile)
        }

        // A live pid is not proof: it may be an unrelated process that inherited
        // the number. Only a command line naming this profile is evidence.
        if (!commandsThisProfile(facts, contextDir)) {
            logger.warn(
                "Browser pid {} (from {}) is alive but its command line does not name {}; not killing it",
                pid, pidFile, contextDir
            )
            return ProcessTerminationReport(
                ProcessTermination.NOT_OURS,
                pid = pid,
                pidFile = pidFile,
                detail = facts.commandLine ?: facts.command ?: "command line unavailable",
            )
        }

        return try {
            val covered = processTable.terminateTree(pid)
            val gone = waitUntilGone(pid)
            if (gone) {
                logger.info("Reaped browser process tree {} ({} process(es) signalled)", pid, covered)
                ProcessTerminationReport(
                    ProcessTermination.TERMINATED, pid = pid, pidFile = pidFile, requestedTerminations = covered
                )
            } else {
                logger.warn(
                    "Browser process {} is still alive {} ms after the kill was issued; it is leaking",
                    pid, killWaitMillis
                )
                ProcessTerminationReport(
                    ProcessTermination.SURVIVED, pid = pid, pidFile = pidFile, requestedTerminations = covered
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to terminate browser process {}: {}", pid, e.message)
            ProcessTerminationReport(
                ProcessTermination.FAILED, pid = pid, pidFile = pidFile, detail = e.message ?: e.javaClass.simpleName
            )
        }
    }

    private fun waitUntilGone(pid: Long): Boolean {
        val deadline = System.currentTimeMillis() + killWaitMillis
        while (System.currentTimeMillis() < deadline) {
            if (processTable.facts(pid)?.alive != true) return true
            runCatching { Thread.sleep(pollIntervalMillis) }
        }
        return processTable.facts(pid)?.alive != true
    }

    companion object {
        /** How long a killed tree gets to disappear before it counts as surviving. */
        const val DEFAULT_KILL_WAIT_MILLIS: Long = 5_000

        private const val DEFAULT_POLL_INTERVAL_MILLIS: Long = 50

        /**
         * The pid the session's own launcher recorded in [contextDir], or null.
         *
         * The marker lives directly in the context directory (`<contextDir>/launcher.pid`),
         * beside the per-launch `PULSAR_CHROME` user data dir Chrome was started with.
         * Shared with the window probe so both read the launch the same way — an
         * unreadable or absent marker is "unknown", never a fabricated pid.
         */
        fun readLauncherPid(contextDir: Path?): Long? {
            val pidFile = contextDir?.resolve(BrowserFiles.PID_FILE_NAME) ?: return null
            if (!Files.isRegularFile(pidFile)) return null
            return runCatching { Files.readString(pidFile).trim().toLong() }
                .getOrNull()
                ?.takeIf { it > 0 }
        }

        /**
         * Whether [facts] describe the browser this profile launched.
         *
         * The launcher starts Chrome with `--user-data-dir=<profile data dir>`, so
         * the command line names the directory. Separators and case differ per
         * platform, so both sides are normalized; anything unreadable is NOT a
         * match — an unverifiable process is never killed.
         */
        internal fun commandsThisProfile(facts: ProcessFacts, contextDir: Path?): Boolean {
            val commandLine = facts.commandLine
            if (commandLine.isNullOrBlank() || contextDir == null) return false

            val command = normalizeForMatch(commandLine)
            val dir = normalizeForMatch(contextDir.toAbsolutePath().normalize().toString())
            if (dir.isNotBlank() && command.contains(dir)) return true

            // Tolerate a shortened/8.3 form of the same path: the profile data dir
            // sits in a rotation dir whose name is unique enough to identify it.
            val profileDir = normalizeForMatch(contextDir.fileName?.toString().orEmpty())
            val rotationDir = normalizeForMatch(contextDir.parent?.fileName?.toString().orEmpty())
            return command.contains("--user-data-dir") &&
                profileDir.isNotBlank() &&
                rotationDir.isNotBlank() &&
                command.contains(profileDir) &&
                command.contains(rotationDir)
        }

        private fun normalizeForMatch(value: String): String =
            value.replace('\\', '/').lowercase().trimEnd('/')
    }
}
