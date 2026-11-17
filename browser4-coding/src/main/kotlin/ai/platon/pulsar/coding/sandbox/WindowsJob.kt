package ai.platon.pulsar.coding.sandbox

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import org.slf4j.LoggerFactory

/**
 * A Windows job object used as the **process-tree lifetime** of one sandbox.
 *
 * `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` terminates every process in the job when the last job handle
 * closes: the backend exits, its sandboxed children go with it. Two problems this fixes, both seen in
 * the 2026-11-16 review of run `966a5f2e`:
 *
 *  - a killed backend left `python`/`cmd`/`java` orphans behind (stray children from earlier runs
 *    were still alive), and
 *  - a task that started a daemon outlived its own task, so "the task ended" did not mean "its
 *    processes ended".
 *
 * Assignment is **best-effort and never fatal**: a process already inside a job that forbids nesting
 * refuses the assignment, and losing the cleanup guarantee must not lose the command. The failure is
 * logged and reported by [assign], never turned into a refusal.
 */
class WindowsJob(private val name: String? = null) : AutoCloseable {

    companion object {
        private val logger = LoggerFactory.getLogger(WindowsJob::class.java)
    }

    /** `null` when the job could not be created (a non-Windows host, or a hard OS error). */
    private val handle: Pointer? = runCatching { create() }.getOrNull()

    @Volatile
    private var closed = false

    /** True when the job exists and children can be attached to it. */
    val isOpen: Boolean get() = handle != null && !closed

    /**
     * Assigns an already-open process handle; the caller keeps ownership of it.
     *
     * @return true when the process is now a member of the job
     */
    fun assign(processHandle: Pointer): Boolean {
        val job = handle ?: return false
        if (closed) return false
        val assigned = runCatching { Win32.kernel32.AssignProcessToJobObject(job, processHandle) }
            .getOrDefault(false)
        if (!assigned) {
            logger.warn(
                "could not attach a process to the sandbox job '{}' (error {}); it will not be " +
                    "cleaned up with the backend",
                name ?: "unnamed", Native.getLastError(),
            )
        }
        return assigned
    }

    /**
     * Assigns the process with [pid], opening a handle for the duration.
     *
     * @return true when the process was found and is now a member of the job
     */
    fun attach(pid: Long): Boolean {
        if (pid <= 0 || !isOpen) return false
        val process = runCatching {
            Win32.kernel32.OpenProcess(
                Win32.PROCESS_SET_QUOTA or Win32.PROCESS_TERMINATE or Win32.PROCESS_QUERY_LIMITED_INFORMATION,
                false,
                pid.toInt(),
            )
        }.getOrNull() ?: return false
        return try {
            assign(process)
        } finally {
            Win32.kernel32.CloseHandle(process)
        }
    }

    /**
     * Closes the job, which — with `KILL_ON_JOB_CLOSE` — terminates every member process.
     *
     * Idempotent: a second close is a no-op rather than a double `CloseHandle`.
     */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        handle?.let { Win32.kernel32.CloseHandle(it) }
    }

    private fun create(): Pointer? {
        val job = Win32.kernel32.CreateJobObjectW(null, name?.let { WString(it) }) ?: run {
            logger.warn("could not create the sandbox job '{}' (error {})", name, Native.getLastError())
            return null
        }
        val info = Win32.JobObjectExtendedLimitInformation().apply {
            BasicLimitInformation.LimitFlags = Win32.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        }
        info.write()
        val configured = Win32.kernel32.SetInformationJobObject(
            job,
            Win32.JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,
            info.pointer,
            info.size(),
        )
        if (!configured) {
            val error = Native.getLastError()
            Win32.kernel32.CloseHandle(job)
            logger.warn("could not configure the sandbox job '{}' (error {})", name, error)
            return null
        }
        logger.info("sandbox job ready: name={} (kill-on-close)", name ?: "unnamed")
        return job
    }
}
