package ai.platon.pulsar.coding.sandbox

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plan P1-2 — launches a [ProcessSpec] in the Windows sandbox account and returns a real
 * [Process], so `coding.shell` and `coding.runCode` do not need a second execution path.
 *
 * `CreateProcessWithLogonW` is not used because it cannot inherit handles and therefore cannot
 * redirect stdout/stderr. The sequence is `LogonUserW` → `CreateProcessAsUserW` (inherit handles,
 * `CREATE_UNICODE_ENVIRONMENT`), with std handles wired to anonymous pipes. `CreateProcessAsUserW`
 * needs `SeAssignPrimaryTokenPrivilege` / `SeIncreaseQuotaPrivilege`; those are enabled best-effort
 * on the backend token, and a launch that still fails for lack of privilege is a
 * [SandboxUnavailableException] — not an unwrapped launch.
 */
object WindowsTokenLauncher {

    private val logger = LoggerFactory.getLogger(WindowsTokenLauncher::class.java)

    private const val SANDBOX_ID = WindowsSandboxBootstrap.SANDBOX_ID

    /**
     * Logs on as [account], launches [spec], and closes the logon token again.
     *
     * @param redirectErrorStream send stderr to the stdout pipe, matching `ProcessBuilder`
     * @throws SandboxUnavailableException when the account cannot log on or the process cannot be
     *   created (for example the backend lacks the token privileges)
     */
    fun start(spec: ProcessSpec, account: SandboxAccount, redirectErrorStream: Boolean = false): Process {
        requireWindows()
        val password = account.password()
        val token = PointerByReference()
        try {
            val loggedOn = Win32.advapi32.LogonUserW(
                WString(account.userName),
                WString("."),
                WString(String(password)),
                Win32.LOGON32_LOGON_INTERACTIVE,
                Win32.LOGON32_PROVIDER_DEFAULT,
                token,
            )
            if (!loggedOn) {
                fail(
                    "LogonUserW failed for sandbox account '${account.userName}' " +
                        "(error ${Native.getLastError()}); check the account and its credential",
                )
            }
        } finally {
            password.fill('\u0000')
        }
        val handle = token.value
            ?: throw SandboxUnavailableException(SANDBOX_ID, "LogonUserW returned no token for '${account.userName}'")
        try {
            enableTokenPrivileges()
            return startWithToken(spec, handle, redirectErrorStream)
        } finally {
            Win32.kernel32.CloseHandle(handle)
        }
    }

    /**
     * Launches [spec] with an already-open primary token; the caller keeps ownership of [token].
     *
     * Exposed for the Windows integration test, which reuses the current process token so the pipe
     * and command-line machinery can be exercised without a provisioned sandbox account.
     */
    internal fun startWithToken(spec: ProcessSpec, token: Pointer, redirectErrorStream: Boolean): Process {
        requireWindows()
        val program = spec.command.firstOrNull()
            ?: throw SandboxUnavailableException(SANDBOX_ID, "cannot launch an empty command")
        val pipes = createPipes(redirectErrorStream)
        try {
            val environment = mergedEnvironment(token, spec.environment)
            val environmentMemory = buildEnvironmentBlock(environment)
            val executable = resolveProgram(program, environment)
            val commandLine = buildWindowsCommandLine(spec.command)
            val commandMemory = Memory((commandLine.length + 1) * 2L).also { it.setWideString(0, commandLine) }
            val startup = Win32.StartupInfo().apply {
                dwFlags = Win32.STARTF_USESTDHANDLES
                hStdInput = pipes.childStdin
                hStdOutput = pipes.childStdout
                hStdError = pipes.childStderr
                write()
            }
            val info = Win32.ProcessInformation()
            val created = Win32.advapi32.CreateProcessAsUserW(
                token,
                WString(executable),
                commandMemory,
                null,
                null,
                true,
                Win32.CREATE_UNICODE_ENVIRONMENT or Win32.CREATE_NO_WINDOW,
                environmentMemory,
                WString(spec.directory.toAbsolutePath().toString()),
                startup,
                info,
            )
            val error = if (created) 0 else Native.getLastError()
            info.read()
            // The child owns its inherited copies; the parent must close its own copies of the
            // child-side ends or the pipes never report EOF.
            pipes.closeChildEnds()
            if (!created) {
                pipes.closeParentEnds()
                fail(describeLaunchFailure(error))
            }
            val processHandle = info.hProcess
                ?: throw SandboxUnavailableException(SANDBOX_ID, "CreateProcessAsUserW returned no process handle")
            info.hThread?.let { Win32.kernel32.CloseHandle(it) }
            logger.info("windows sandbox process started: pid={} command={}", info.dwProcessId, spec.command.first())
            return WindowsTokenProcess(
                processHandle = processHandle,
                processId = info.dwProcessId.toLong(),
                stdinHandle = pipes.parentStdin,
                stdoutHandle = pipes.parentStdout,
                stderrHandle = pipes.parentStderr,
            )
        } catch (e: Throwable) {
            pipes.closeAll()
            throw e
        }
    }

    private fun requireWindows() {
        val os = System.getProperty("os.name", "")
        if (!os.lowercase().contains("win")) {
            throw SandboxUnavailableException(
                SANDBOX_ID,
                "Windows sandbox unavailable: the 'windows-acl' sandbox requires Windows, but os.name='$os'",
            )
        }
    }

    private fun fail(message: String): Nothing {
        logger.warn("windows sandbox unavailable: {}", message)
        throw SandboxUnavailableException(SANDBOX_ID, "Windows sandbox unavailable: $message")
    }

    private fun describeLaunchFailure(error: Int): String = when (error) {
        Win32.ERROR_PRIVILEGE_NOT_HELD ->
            "CreateProcessAsUserW failed (error $error): the backend lacks SeAssignPrimaryTokenPrivilege / " +
                "SeIncreaseQuotaPrivilege. Run the backend as a service or administrator, or grant those privileges."
        else -> "CreateProcessAsUserW failed (error $error)"
    }

    // ------------------------------------------------------------------
    // Token privileges
    // ------------------------------------------------------------------

    /**
     * Enables `SeAssignPrimaryTokenPrivilege` / `SeIncreaseQuotaPrivilege` on the backend token.
     *
     * Best-effort by design: the privileges may be absent (a plain user token) and a disabled
     * privilege cannot be enabled by `AdjustTokenPrivileges`. The launch then fails with error 1314
     * and [describeLaunchFailure] turns that into a structured, actionable exception. Never silent.
     */
    internal fun enableTokenPrivileges() {
        val token = PointerByReference()
        if (!Win32.advapi32.OpenProcessToken(
                Win32.kernel32.GetCurrentProcess(),
                Win32.TOKEN_ADJUST_PRIVILEGES or Win32.TOKEN_QUERY,
                token,
            )
        ) {
            logger.debug("OpenProcessToken failed ({}); relaying on existing privileges", Native.getLastError())
            return
        }
        val handle = token.value ?: return
        try {
            for (name in listOf("SeAssignPrimaryTokenPrivilege", "SeIncreaseQuotaPrivilege")) {
                val privileges = Win32.TokenPrivileges()
                privileges.PrivilegeCount = 1
                privileges.write()
                Win32.advapi32.LookupPrivilegeValueW(null, WString(name), privileges.Privileges[0].Luid)
                privileges.read()
                privileges.Privileges[0].Attributes = Win32.SE_PRIVILEGE_ENABLED
                privileges.write()
                Win32.advapi32.AdjustTokenPrivileges(
                    handle,
                    false,
                    privileges,
                    privileges.size().toInt(),
                    null,
                    null,
                )
            }
        } finally {
            Win32.kernel32.CloseHandle(handle)
        }
    }

    // ------------------------------------------------------------------
    // Pipes and handles
    // ------------------------------------------------------------------

    private class Pipes(
        val childStdin: Pointer,
        val childStdout: Pointer,
        val childStderr: Pointer,
        val parentStdin: Pointer,
        val parentStdout: Pointer,
        val parentStderr: Pointer,
        private val stderrMerged: Boolean,
    ) {
        private val closed = mutableSetOf<Pointer>()

        fun closeChildEnds() {
            close(childStdin)
            close(childStdout)
            close(childStderr)
        }

        fun closeParentEnds() {
            close(parentStdin)
            close(parentStdout)
            if (!stderrMerged) close(parentStderr)
        }

        fun closeAll() {
            closeChildEnds()
            closeParentEnds()
        }

        private fun close(handle: Pointer) {
            if (closed.add(handle)) Win32.kernel32.CloseHandle(handle)
        }
    }

    private fun createPipes(redirectErrorStream: Boolean): Pipes {
        val attributes = Win32.SecurityAttributes().apply {
            bInheritHandle = 1
            write()
        }
        fun pipe(): Pair<Pointer, Pointer> {
            val read = PointerByReference()
            val write = PointerByReference()
            if (!Win32.kernel32.CreatePipe(read, write, attributes, 0)) {
                throw SandboxUnavailableException(
                    SANDBOX_ID,
                    "Windows sandbox unavailable: CreatePipe failed (error ${Native.getLastError()})",
                )
            }
            return read.value to write.value
        }

        val (stdinRead, stdinWrite) = pipe()
        val (stdoutRead, stdoutWrite) = pipe()
        val (stderrRead, stderrWrite) = pipe()
        // The parent's ends must not be inherited by the child.
        Win32.kernel32.SetHandleInformation(stdinWrite, Win32.HANDLE_FLAG_INHERIT, 0)
        Win32.kernel32.SetHandleInformation(stdoutRead, Win32.HANDLE_FLAG_INHERIT, 0)
        Win32.kernel32.SetHandleInformation(stderrRead, Win32.HANDLE_FLAG_INHERIT, 0)
        if (redirectErrorStream) {
            // stderr shares the stdout pipe; the standalone stderr pipe is not used at all.
            Win32.kernel32.CloseHandle(stderrRead)
            Win32.kernel32.CloseHandle(stderrWrite)
        }
        return Pipes(
            childStdin = stdinRead,
            childStdout = stdoutWrite,
            childStderr = if (redirectErrorStream) stdoutWrite else stderrWrite,
            parentStdin = stdinWrite,
            parentStdout = stdoutRead,
            parentStderr = if (redirectErrorStream) stdoutRead else stderrRead,
            stderrMerged = redirectErrorStream,
        )
    }

    // ------------------------------------------------------------------
    // Environment and command line
    // ------------------------------------------------------------------

    /**
     * Variables whose value belongs to the sandbox account, not to the backend process. Keeping
     * the token's values is what gives the child a writable profile (`TEMP`, `APPDATA`, ...);
     * toolchain locations (`PATH`, `JAVA_HOME`, `GRADLE_HOME`, ...) still come from the declared
     * environment so builds find the same toolchain.
     */
    private val TOKEN_OWNED_VARIABLES: Set<String> = setOf(
        "USERPROFILE", "APPDATA", "LOCALAPPDATA", "HOMEDRIVE", "HOMEPATH", "USERNAME", "USERDOMAIN",
        "TEMP", "TMP", "SYSTEMROOT", "SYSTEMDRIVE", "WINDIR", "COMSPEC", "PATHEXT",
        "PROGRAMDATA", "PROGRAMFILES", "PROGRAMFILES(X86)", "COMMONPROGRAMFILES",
        "COMMONPROGRAMFILES(X86)", "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE",
        "PROCESSOR_IDENTIFIER", "OS",
    )

    private fun mergedEnvironment(token: Pointer, declared: Map<String, String>): Map<String, String> {
        val block = PointerByReference()
        if (Win32.userenv.CreateEnvironmentBlock(block, token, false)) {
            val pointer = block.value
            if (pointer != null) {
                try {
                    return mergeEnvironment(readEnvironmentBlock(pointer), declared)
                } finally {
                    Win32.userenv.DestroyEnvironmentBlock(pointer)
                }
            }
        }
        logger.warn(
            "CreateEnvironmentBlock failed ({}); the child gets the declared environment only",
            Native.getLastError(),
        )
        return declared
    }

    private fun readEnvironmentBlock(block: Pointer): Map<String, String> {
        val entries = LinkedHashMap<String, String>()
        var offset = 0L
        while (true) {
            // `getWideString` maps wchar_t (2 bytes on Windows); decoding UTF-16LE through a byte
            // string reader splits each `NAME=value` entry at its first NUL byte.
            val entry = block.getWideString(offset)
            if (entry.isEmpty()) break
            offset += (entry.length + 1) * 2L
            val separator = entry.indexOf('=')
            // A leading '=' marks a hidden drive entry ("=C:=C:\..."), not a variable.
            if (separator <= 0) continue
            entries[entry.substring(0, separator)] = entry.substring(separator + 1)
        }
        return entries
    }

    /** @see TOKEN_OWNED_VARIABLES */
    internal fun mergeEnvironment(tokenEnvironment: Map<String, String>, declared: Map<String, String>): Map<String, String> {
        val merged = LinkedHashMap(tokenEnvironment)
        for ((name, value) in declared) {
            val existing = merged.keys.firstOrNull { it.equals(name, ignoreCase = true) }
            if (existing != null && existing.uppercase() in TOKEN_OWNED_VARIABLES) continue
            merged[name] = value
        }
        return merged
    }

    /** UTF-16LE `NAME=value` entries, each NUL-terminated, the block itself double-NUL-terminated. */
    internal fun buildEnvironmentBlock(environment: Map<String, String>): Memory {
        val text = buildString {
            for ((name, value) in environment) {
                if (name.isEmpty() || name.contains('=')) continue
                append(name).append('=').append(value).append('\u0000')
            }
            append('\u0000')
        }
        // Written as explicit UTF-16LE bytes: the block contains embedded NULs, which a wide-string
        // setter would treat as a terminator. The two trailing NULs are part of the encoding already,
        // so the allocation is exact — reading past it would see uninitialized memory.
        val bytes = text.toByteArray(Charsets.UTF_16LE)
        return Memory(bytes.size.toLong()).also { it.write(0, bytes, 0, bytes.size) }
    }

    /**
     * Resolves a bare command name against the sandbox environment's `PATH` / `PATHEXT`.
     *
     * `CreateProcessAsUserW` does not search `PATH` the way `ProcessBuilder` does: launching
     * `cmd.exe` from a workspace directory failed with ERROR_FILE_NOT_FOUND (2026-10-31). The shell
     * always builds `cmd.exe /c <text>`, so resolving here keeps the call sites unchanged and honors
     * the *sandbox* environment (the declared toolchain `PATH`), not the backend's.
     *
     * A name with a path separator is returned unchanged, so explicit paths keep working.
     */
    internal fun resolveProgram(program: String, environment: Map<String, String>): String {
        if (program.isEmpty() || program.contains('\\') || program.contains('/')) return program
        val names = if (program.substringAfterLast('.', "").isNotEmpty()) {
            listOf(program)
        } else {
            val extensions = environmentValue(environment, "PATHEXT")
                ?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: DEFAULT_PATHEXT
            listOf(program) + extensions.map { program + it.lowercase() }
        }
        val directories = environmentValue(environment, "PATH")
            ?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }
            .orEmpty()
        for (directory in directories) {
            for (name in names) {
                val candidate = Path.of(directory, name)
                if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().toString()
            }
        }
        return program
    }

    private fun environmentValue(environment: Map<String, String>, name: String): String? =
        environment.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** Quotes one argument with the `CommandLineToArgvW` rules `ProcessBuilder` uses. */
    internal fun quoteWindowsArgument(argument: String): String {
        if (argument.isEmpty()) return "\"\""
        if (argument.none { it == ' ' || it == '\t' || it == '"' }) return argument
        val quoted = StringBuilder("\"")
        var backslashes = 0
        for (character in argument) {
            when (character) {
                '\\' -> backslashes++
                '"' -> {
                    quoted.append("\\".repeat(backslashes * 2 + 1)).append('"')
                    backslashes = 0
                }
                else -> {
                    quoted.append("\\".repeat(backslashes)).append(character)
                    backslashes = 0
                }
            }
        }
        quoted.append("\\".repeat(backslashes * 2)).append('"')
        return quoted.toString()
    }

    internal fun buildWindowsCommandLine(command: List<String>): String =
        command.joinToString(" ") { quoteWindowsArgument(it) }

    /** Fallback when the environment has no `PATHEXT`, matching a default Windows install. */
    private val DEFAULT_PATHEXT = listOf(".COM", ".EXE", ".BAT", ".CMD")
}

/**
 * A [Process] backed by Win32 handles, so the call sites keep using `inputStream` / `errorStream` /
 * `waitFor` / `destroyForcibly` unchanged.
 *
 * Handles are closed deterministically: the thread handle right after creation (in the launcher),
 * the process handle once the exit code has been read, and each pipe handle when its stream is
 * closed (the readers in `CodingAgentShell` / `CodeRunner` always `use` them). The stdin write end
 * is closed when the process exits, because neither call site keeps it open on purpose.
 */
private class WindowsTokenProcess(
    private val processHandle: Pointer,
    private val processId: Long,
    stdinHandle: Pointer,
    stdoutHandle: Pointer,
    stderrHandle: Pointer,
) : Process() {

    private val kernel = Win32.kernel32

    @Volatile
    private var cachedExitCode: Int? = null

    private val processClosed = AtomicBoolean(false)
    private val stdout = HandleInputStream(stdoutHandle)
    private val stderr = HandleInputStream(stderrHandle)
    private val stdin = HandleOutputStream(stdinHandle)

    override fun getOutputStream(): OutputStream = stdin

    override fun getInputStream(): InputStream = stdout

    override fun getErrorStream(): InputStream = stderr

    override fun waitFor(): Int {
        cachedExitCode?.let { return it }
        kernel.WaitForSingleObject(processHandle, Win32.INFINITE)
        return takeExitCode()
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        cachedExitCode?.let { return true }
        val millis = unit.toMillis(timeout).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        return when (kernel.WaitForSingleObject(processHandle, millis)) {
            Win32.WAIT_OBJECT_0 -> {
                takeExitCode()
                true
            }
            Win32.WAIT_TIMEOUT -> false
            else -> throw IOException("WaitForSingleObject failed (error ${Native.getLastError()})")
        }
    }

    override fun exitValue(): Int {
        cachedExitCode?.let { return it }
        return takeExitCode()
    }

    override fun isAlive(): Boolean {
        if (cachedExitCode != null) return false
        val code = IntByReference()
        if (!kernel.GetExitCodeProcess(processHandle, code)) return false
        return code.value == Win32.STILL_ACTIVE
    }

    override fun destroy() {
        if (processClosed.get()) return
        kernel.TerminateProcess(processHandle, 1)
    }

    override fun destroyForcibly(): Process {
        destroy()
        return this
    }

    override fun supportsNormalTermination(): Boolean = false

    override fun pid(): Long = processId

    private fun takeExitCode(): Int {
        cachedExitCode?.let { return it }
        val code = IntByReference()
        if (!kernel.GetExitCodeProcess(processHandle, code)) {
            throw IOException("GetExitCodeProcess failed (error ${Native.getLastError()})")
        }
        val value = code.value
        if (value == Win32.STILL_ACTIVE) throw IllegalThreadStateException("process has not exited")
        runCatching { stdin.close() }
        closeProcessHandle()
        cachedExitCode = value
        return value
    }

    private fun closeProcessHandle() {
        if (processClosed.compareAndSet(false, true)) kernel.CloseHandle(processHandle)
    }
}

/** Reads a Win32 pipe handle until the child closes its write end. */
private class HandleInputStream(private val handle: Pointer) : InputStream() {

    private val kernel = Win32.kernel32
    private val buffer = Memory(BUFFER_SIZE.toLong())
    private val closed = AtomicBoolean(false)
    private var position = 0
    private var limit = 0

    override fun read(): Int {
        if (position >= limit && !fill()) return -1
        val value = buffer.getByte(position.toLong()).toInt()
        position++
        return value and 0xFF
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset + length <= destination.size) { "invalid range" }
        if (length == 0) return 0
        if (position >= limit && !fill()) return -1
        val count = minOf(length, limit - position)
        buffer.read(position.toLong(), destination, offset, count)
        position += count
        return count
    }

    override fun available(): Int = (limit - position).coerceAtLeast(0)

    override fun close() {
        if (closed.compareAndSet(false, true)) kernel.CloseHandle(handle)
    }

    private fun fill(): Boolean {
        if (closed.get()) return false
        val count = IntByReference()
        if (!kernel.ReadFile(handle, buffer, BUFFER_SIZE, count, null)) {
            val error = Native.getLastError()
            if (error == Win32.ERROR_BROKEN_PIPE || error == Win32.ERROR_HANDLE_EOF || closed.get()) return false
            throw IOException("ReadFile failed (error $error)")
        }
        position = 0
        limit = count.value
        return limit > 0
    }

    companion object {
        private const val BUFFER_SIZE = 8192
    }
}

/** Writes to a Win32 pipe handle (the child's stdin). */
private class HandleOutputStream(private val handle: Pointer) : OutputStream() {

    private val kernel = Win32.kernel32
    private val closed = AtomicBoolean(false)

    override fun write(value: Int) {
        write(byteArrayOf(value.toByte()), 0, 1)
    }

    override fun write(source: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= source.size) { "invalid range" }
        if (length == 0) return
        if (closed.get()) throw IOException("stdin is closed")
        val buffer = Memory(length.toLong())
        buffer.write(0, source, offset, length)
        val written = IntByReference()
        if (!kernel.WriteFile(handle, buffer, length, written, null)) {
            throw IOException("WriteFile failed (error ${Native.getLastError()})")
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) kernel.CloseHandle(handle)
    }
}
