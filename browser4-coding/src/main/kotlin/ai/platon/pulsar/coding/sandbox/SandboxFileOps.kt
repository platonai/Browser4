package ai.platon.pulsar.coding.sandbox

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Plan P3-6 — the **write side** of the file tools, executed inside the OS boundary.
 *
 * Rationale (design `docs/agent-permissions-redesign.md` §16): the backend process itself is Medium
 * and unconfined by construction (it hosts the browser and the whole product), so `coding.write` used
 * to be the widest channel an agent had — a path guard is all that stood between a workspace-relative
 * argument and `D:\`. Sinking the mutation into a child that runs **inside** the same boundary the
 * shell uses turns that guard back into what it should be: a fast-fail check in front of a kernel
 * decision.
 *
 * Only *mutations* travel this path. Reads keep their historical behaviour on purpose, and the reason
 * differs per mode:
 *  - coding: `ReadScope.CURRENT_USER` **is** the goal (the developer's own read access), so a read
 *    from the backend and a read from the sandbox are the same thing;
 *  - browse: the read scope is the workspace, which the container can read exactly like the backend,
 *    so sinking the read would buy nothing an in-process read does not already have.
 *
 * Implementations must **throw** on failure — never fall back to an in-process write. A refused
 * mutation is a tool error the model can read; a silently unconfined one is the bug this interface
 * exists to prevent.
 */
interface SandboxFileOps {

    /** Backend id that executes the operations (`coding.permissions` names it). */
    val backendId: String

    /**
     * Replace [target] with [bytes] (creating parent directories), atomically where the filesystem
     * allows a rename.
     */
    fun write(target: Path, bytes: ByteArray)

    /** Create [target] and its parents. */
    fun mkdirs(target: Path)

    /** Delete [target] (a file, or a directory when [recursive]). */
    fun delete(target: Path, recursive: Boolean)

    /** Copy [source] to [destination], recursively for a directory. */
    fun copy(source: Path, destination: Path, recursive: Boolean)

    /** Move/rename [source] to [destination], replacing an existing file. */
    fun move(source: Path, destination: Path)
}

/**
 * The [SandboxFileOps] implementation: one sandboxed `powershell` child per operation.
 *
 * Why a child process at all: an integrity level (coding) or a container SID (browse) belongs to a
 * *process*, so the backend cannot lower its own rights for one `open(2)` without giving up its role.
 * The child is started through the same seam the shell uses ([ExecutionSandbox.prepare] →
 * [ProcessLauncher]), so the boundary is the backend's own, not a second implementation that could
 * drift from it.
 *
 * Why PowerShell with `-File` and plain argv (and not `cmd /c` or a payload string): the launcher
 * quotes argv with the C-runtime rules and the shell then parses it again, so a payload that carries
 * its own quotes cannot survive both layers — the same lesson the Low-integrity probe already
 * records (`echo x> "C:\a b\f.txt"` reported "the filename, directory name, or volume label syntax is
 * incorrect"). Passing paths as ordinary arguments keeps each layer's quoting correct.
 *
 * Why a **staged payload file** instead of the content on the command line: a command line is capped
 * (~32 KB) and would have to encode bytes; the content is instead written to
 * `<workspace>/.b4scratch/fs-bridge/payload-*.bin` — a path inside the boundary every backend grants
 * — and the child `ReplaceFile`s it into place. That keeps the write atomic and never puts file
 * content on a command line.
 *
 * One child per operation is deliberate (2026-11-17): a persistent helper would save ~400 ms per
 * mutation but add a lifecycle (single-flight lock, hang detection, restart, orphan cleanup) whose
 * failure modes are exactly the "silent" ones this redesign exists to remove. The cost is measured
 * and acceptable relative to an LLM round or a build; a batched protocol is the follow-up if a real
 * run ever shows it dominating.
 *
 * Tampering is not an escape: the script and the payload live in the task's own workspace, which the
 * agent may write, but the child still runs inside the boundary — a doctored script inherits the
 * token/container of its parent and can therefore reach no more than the boundary allows.
 */
class SandboxedFileOps(
    private val sandbox: ExecutionSandbox,
    /** The task workspace: the operating directory and the root the boundary was provisioned for. */
    private val workspace: Path,
    /** Folders `agent run --add-dir` granted to the task; the boundary grants them like the workspace. */
    private val extraWritableRoots: List<Path> = emptyList(),
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : SandboxFileOps {

    companion object {
        /**
         * Upper bound on one file operation. A `ReplaceFile` of a large source and the `Copy-Item`
         * of a tree both fit comfortably; a hung child is killed and reported instead of blocking the
         * agent round forever.
         */
        const val DEFAULT_TIMEOUT_SECONDS = 120L

        /** Directory (inside the workspace) holding the bridge script and the staged payloads. */
        const val BRIDGE_DIR = ".b4scratch/fs-bridge"

        /** File name of the bridge script inside [BRIDGE_DIR]. */
        private const val SCRIPT_NAME = "b4-fs.ps1"

        /** Prefix the script uses for a caught failure, so the reason can be lifted out of the output. */
        private const val ERROR_PREFIX = "B4FS-ERROR"

        private val logger = LoggerFactory.getLogger(SandboxedFileOps::class.java)

        /**
         * One-shot file-operation script. Every operation reports through the exit code and one
         * `B4FS-ERROR <reason>` line, so the caller can put the real cause in front of the model.
         */
        private val SCRIPT = """
            param(
              [string]${'$'}Op = '',
              [string]${'$'}Target = '',
              [string]${'$'}Payload = '',
              [string]${'$'}Destination = '',
              [string]${'$'}Recursive = 'false'
            )
            ${'$'}ErrorActionPreference = 'Stop'
            function New-Parent([string]${'$'}Path) {
              ${'$'}parent = [System.IO.Path]::GetDirectoryName(${'$'}Path)
              if (${'$'}parent -and -not [System.IO.Directory]::Exists(${'$'}parent)) {
                [void][System.IO.Directory]::CreateDirectory(${'$'}parent)
              }
            }
            function Set-ContentFrom([string]${'$'}Source, [string]${'$'}Target) {
              New-Parent ${'$'}Target
              try {
                if ([System.IO.File]::Exists(${'$'}Target)) {
                  [System.IO.File]::Replace(${'$'}Source, ${'$'}Target, ${'$'}null)
                } else {
                  [System.IO.File]::Move(${'$'}Source, ${'$'}Target)
                }
                return
              } catch { }
              Copy-Item -LiteralPath ${'$'}Source -Destination ${'$'}Target -Force -ErrorAction Stop
              Remove-Item -LiteralPath ${'$'}Source -Force -ErrorAction SilentlyContinue
            }
            try {
              switch (${'$'}Op) {
                'write' { Set-ContentFrom ${'$'}Payload ${'$'}Target }
                'mkdir' { [void][System.IO.Directory]::CreateDirectory(${'$'}Target) }
                'delete' {
                  if ([System.IO.Directory]::Exists(${'$'}Target)) {
                    [System.IO.Directory]::Delete(${'$'}Target, (${'$'}Recursive -eq 'true'))
                  } elseif ([System.IO.File]::Exists(${'$'}Target)) {
                    [System.IO.File]::Delete(${'$'}Target)
                  } else { throw "not found: ${'$'}Target" }
                }
                'copy' {
                  if ([System.IO.Directory]::Exists(${'$'}Target)) {
                    Copy-Item -LiteralPath ${'$'}Target -Destination ${'$'}Destination -Recurse -Force -ErrorAction Stop
                  } else {
                    # `Files.copy` refuses a file onto an existing directory; keep that answer.
                    if ([System.IO.Directory]::Exists(${'$'}Destination)) {
                      throw "destination is a directory: ${'$'}Destination"
                    }
                    New-Parent ${'$'}Destination
                    Copy-Item -LiteralPath ${'$'}Target -Destination ${'$'}Destination -Force -ErrorAction Stop
                  }
                }
                'move' {
                  New-Parent ${'$'}Destination
                  if ([System.IO.File]::Exists(${'$'}Destination)) { [System.IO.File]::Delete(${'$'}Destination) }
                  if ([System.IO.Directory]::Exists(${'$'}Target)) {
                    [System.IO.Directory]::Move(${'$'}Target, ${'$'}Destination)
                  } else {
                    [System.IO.File]::Move(${'$'}Target, ${'$'}Destination)
                  }
                }
                default { throw "unknown operation: ${'$'}Op" }
              }
              exit 0
            } catch {
              [Console]::Out.WriteLine('B4FS-ERROR ' + ${'$'}_.Exception.Message)
              exit 1
            }
        """.trimIndent()

        /**
         * The bridge for [sandbox] in [workspace], or `null` when this backend cannot execute file
         * operations inside its boundary ([ExecutionSandbox.executesFileOps]).
         *
         * `null` is not a fallback to something unconfined: the caller keeps its historical
         * in-process path and reports it as "policy layer only" in `coding.permissions`. The
         * alternative — wrapping the write in a boundary that does not exist — is exactly the
         * narrative the 2026-11-16 review removed.
         */
        fun forSandbox(
            sandbox: ExecutionSandbox,
            workspace: Path,
            extraWritableRoots: List<Path> = emptyList(),
        ): SandboxFileOps? = if (sandbox.executesFileOps) {
            SandboxedFileOps(sandbox, workspace, extraWritableRoots)
        } else {
            null
        }
    }

    override val backendId: String get() = sandbox.id

    /** The workspace the boundary was provisioned for, absolute and normalised. */
    private val root: Path = workspace.toAbsolutePath().normalize()

    override fun write(target: Path, bytes: ByteArray) {
        val payload = stage(bytes)
        try {
            execute("write", target, payload = payload)
        } finally {
            // The child moved (or copied) it away; whatever is left is ours to remove. A leftover
            // payload is a full copy of the file, so it must not survive a successful write.
            runCatching { Files.deleteIfExists(payload) }
        }
    }

    override fun mkdirs(target: Path) = execute("mkdir", target)

    override fun delete(target: Path, recursive: Boolean) =
        execute("delete", target, recursive = recursive)

    override fun copy(source: Path, destination: Path, recursive: Boolean) =
        execute("copy", source, destination = destination, recursive = recursive)

    override fun move(source: Path, destination: Path) =
        execute("move", source, destination = destination)

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Stages [bytes] under the bridge directory and returns the payload path. */
    private fun stage(bytes: ByteArray): Path {
        val dir = bridgeDir()
        Files.createDirectories(dir)
        // Re-check after creating it: the directory that now exists must still be the workspace's.
        bridgeDir()
        val payload = Files.createTempFile(dir, "payload-", ".bin")
        Files.write(payload, bytes)
        return payload
    }

    /**
     * The bridge directory, proven not to be reachable through a link.
     *
     * The backend writes the staged payload and the script here with its own (Medium) rights, so a
     * junction at `.b4scratch` or `.b4scratch/fs-bridge` would turn those writes into writes anywhere
     * the developer can write — outside the workspace, and outside the boundary the child runs in.
     */
    private fun bridgeDir(): Path {
        val dir = root.resolve(BRIDGE_DIR)
        ScratchBoundary.refusal(root, dir)?.let {
            throw IOException("the sandbox file bridge of '$root' is not safe to use: $it")
        }
        return dir
    }

    /**
     * Runs one scripted operation inside the boundary.
     *
     * Every failure mode ends as an [IOException] naming the backend and the real cause: the caller
     * ([CodingAgentFileSystem]) already turns an [IOException] into the structured tool error, and a
     * refused write must never look like a successful one.
     */
    private fun execute(
        op: String,
        target: Path,
        payload: Path? = null,
        destination: Path? = null,
        recursive: Boolean = false,
    ) {
        val script = writeScriptOnce()
        val command = buildList {
            add("powershell.exe")
            add("-NoProfile")
            add("-NonInteractive")
            add("-ExecutionPolicy")
            add("Bypass")
            add("-File")
            add(script.toString())
            add("-Op")
            add(op)
            add("-Target")
            add(target.toAbsolutePath().normalize().toString())
            payload?.let {
                add("-Payload")
                add(it.toAbsolutePath().normalize().toString())
            }
            destination?.let {
                add("-Destination")
                add(it.toAbsolutePath().normalize().toString())
            }
            if (recursive) {
                add("-Recursive")
                add("true")
            }
        }
        val spec = ProcessSpec(
            command = command,
            // The workspace, not the bridge directory: the script carries absolute paths, and the
            // child's working directory must be a place every backend grants.
            directory = root,
            environment = SandboxEnvironment.build(System.getenv()),
        )
        val prepared = try {
            sandbox.prepare(spec, root, allowNetwork = false, extraWritableRoots = extraWritableRoots)
        } catch (e: Exception) {
            throw IOException(
                "the OS sandbox '${sandbox.id}' could not start for '$op' on '$target': ${e.message}",
                e,
            )
        }
        val process = try {
            ProcessLauncher.start(prepared, redirectErrorStream = true)
        } catch (e: Exception) {
            throw IOException(
                "the OS sandbox '${sandbox.id}' could not launch '$op' on '$target': ${e.message}",
                e,
            )
        }
        val outcome = SandboxProbe.await(process, timeoutSeconds)
        if (outcome.exitCode == 0) {
            logger.debug("sandboxed file operation ok: backend={} op={} target={}", sandbox.id, op, target)
            return
        }
        throw IOException(
            "the OS sandbox '${sandbox.id}' refused the '$op' of '$target' " +
                "(exit=${outcome.exitCode}): ${describeFailure(outcome.output)}",
        )
    }

    /**
     * Makes sure the bridge script is in place and returns its path.
     *
     * It has to live where the boundary can read it: the backend's own directories are Medium (or
     * outside a container's access set), while `.b4scratch` is inside the workspace every backend
     * grants. The content is compared before writing, so a concurrent (or repeated) call cannot
     * replace a script a sibling child is in the middle of reading.
     */
    private fun writeScriptOnce(): Path {
        val dir = bridgeDir()
        val script = dir.resolve(SCRIPT_NAME)
        if (Files.isRegularFile(script) && runCatching { Files.readString(script) }.getOrNull() == SCRIPT) {
            return script
        }
        Files.createDirectories(dir)
        bridgeDir()
        val tmp = Files.createTempFile(dir, "b4-fs-", ".ps1")
        try {
            Files.writeString(tmp, SCRIPT)
            Files.move(tmp, script, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
        return script
    }

    /**
     * The first meaningful line of the child's output — the `B4FS-ERROR` reason when the script
     * caught one, otherwise whatever the child printed.
     */
    private fun describeFailure(text: String, limit: Int = 400): String {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        lines.firstOrNull { it.startsWith(ERROR_PREFIX) }?.let {
            return it.removePrefix(ERROR_PREFIX).trim().take(limit)
        }
        return lines.firstOrNull()?.take(limit) ?: "no output"
    }
}
