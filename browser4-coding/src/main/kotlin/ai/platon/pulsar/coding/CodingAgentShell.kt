package ai.platon.pulsar.coding

import org.slf4j.LoggerFactory
import ai.platon.pulsar.coding.sandbox.ExecutionSandbox
import ai.platon.pulsar.coding.sandbox.ExecutionSandboxes
import ai.platon.pulsar.coding.sandbox.ProcessLauncher
import ai.platon.pulsar.coding.sandbox.ProcessSpec
import ai.platon.pulsar.coding.sandbox.SandboxDenials
import ai.platon.pulsar.coding.sandbox.SandboxEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.isDirectory

/**
 * Enhanced shell command execution subsystem for AI coding agents.
 *
 * Extends [AgentShell]'s basic read-only command support to include full
 * development tooling: compilers, build systems, package managers, version control,
 * scripting runtimes, and more. Designed for use in autonomous coding agents that need
 * to write, build, test, and deploy code.
 *
 * ## Execution model
 *
 * Commands run through `cmd.exe /c` on Windows and `sh -c` elsewhere, so the
 * command syntax is the platform shell's, not a POSIX one. stdout/stderr are
 * decoded as strict UTF-8 first and as the OS native encoding second (see
 * [decodeProcessOutput]), which keeps localized Windows output readable.
 *
 * ## Security Model
 *
 * Commands are validated against a tiered allow-list:
 * - **safe** — read-only commands always permitted (subset of [AgentShell] whitelist)
 * - **dev** — development tools permitted when [allowDevTools] is true (default)
 * - **network** — network-accessing tools require explicit opt-in ([allowNetwork])
 * - **destructive** — write/delete operations require explicit opt-in ([allowDestructive])
 *
 * There is **no syntax gate**. A command line is decomposed into the simple commands it executes
 * (`&&`, `|`, substitutions, inline scripts) and every one of them is checked; a shape the
 * decomposer cannot parse (`for` loops, `else if`, nested blocks, `goto`/`call` payloads) is handed
 * to the platform shell verbatim, and the shell's own parse error is the feedback. The vetoes that
 * do not need the decomposition — [BLOCKED_PATTERNS], credentials and protected paths, a disabled
 * network/destructive tier, and the runtime vetoes (do not kill or rebuild the backend that runs
 * this task, do not re-link the workspace) — still apply to a handed-off line.
 *
 * Environment variables can be injected per-session or per-command.
 */
class CodingAgentShell(
    private val baseDir: Path,
    private val defaultTimeoutSeconds: Long = 120L,
    private val allowDevTools: Boolean = true,
    private val allowNetwork: Boolean = false,
    /**
     * Live network projection (plan P4-1): when set it overrides [allowNetwork] on every call, so
     * an operator approval of `net.connect` issued while the task runs takes effect without a new
     * shell. The host scope is advisory: the sandbox seam is a boolean, so only the effect is
     * enforced here.
     */
    private val allowNetworkProvider: (() -> Boolean)? = null,
    /**
     * OS sandbox seam for this shell instance (`agent run --unrestricted`).
     *
     * `null` — the default — keeps the process-wide [ExecutionSandboxes] choice from
     * `browser4.agent.sandbox`, so every other task behaves exactly as before. An unrestricted
     * task passes the pass-through sandbox explicitly: that is what makes its commands run with
     * the current OS user's own permissions instead of the confined profile.
     */
    private val sandbox: ExecutionSandbox? = null,

    /**
     * Whether this task runs with the caller's own OS permissions (`agent run --unrestricted`).
     *
     * The only shell rule this flag lifts is the **tier** allow-list: a program that is neither
     * [SAFE_COMMANDS] nor [DEV_COMMANDS] nor on `PATH` (a PowerShell cmdlet such as `Get-Content`, or
     * `wmic`) is handed over like any other. Keeping it refused made the flag's documented promise
     * false — `powershell -Command "Get-Content x"` failed with `COMMAND_NOT_ALLOWED` while the model
     * had been told that inline scripts run as written (2026-11-14 supervision run). The list is a
     * *spelling* rule, not a boundary: the same body already runs unexamined through `powershell -File`.
     *
     * Everything else keeps applying, and the order in [validateSimpleCommandTier] is what guarantees
     * it: hardcoded blocked patterns, explicit deny, deny patterns, credential and protected paths,
     * [FORBIDDEN_COMMANDS], [processKillViolation] and [bootstrapViolation] are all checked before
     * the tier verdict, so a lifted line can reach nothing they would not have reached anyway. The
     * same vetoes are re-checked at program positions for a line the decomposer cannot parse
     * ([handOffVetoViolation]) — that hand-off happens in every mode, because there is no syntax
     * gate to lift.
     *
     * Defaults to `false`, so every other task keeps exactly the historical tier behaviour.
     */
    private val unrestricted: Boolean = false,
    /**
     * When false, the destructive command tier (`rm`, `del`, `move`, `taskkill`, …) is refused.
     *
     * This is **not** a read-only switch — reads and builds work either way — and it is not the
     * workspace boundary either: [allowExternalAccess] decides where a command may point, this flag
     * decides what it may do. `agent run --access write` clears it; `read-build` and `read` refuse
     * the shell outright rather than reaching this flag.
     */
    private val allowDestructive: Boolean = true,
    /** v0.3: when false the shell's working directory is constrained to [baseDir]
     *  by lexical prefix matching (junction-aware, so module subdirectories
     *  work). When true any path is accepted — same switch as
     *  [CodingAgentFileSystem.allowExternalAccess] so the two tools stay in
     *  lockstep. */
    private val allowExternalAccess: Boolean = false,
    /**
     * Credential locations (home-relative, `~` expanded) that may never appear as a **path
     * argument**, whatever [allowExternalAccess] says. Same set as
     * [CodingAgentFileSystem.sensitivePaths] so the file tools and the shell agree.
     */
    private val sensitivePaths: Set<String> = CodingAgentFileSystem.DEFAULT_SENSITIVE_PATHS,
    /**
     * Workspace-relative directories the shell may not name at all (as an argument, a redirection
     * target or a working directory).
     *
     * The file tools already refuse to write under them ([CodingAgentFileSystem.protectedDirs]);
     * without this the shell was a one-line bypass — `echo forged >
     * .browser4/data/agent/agent-tasks.jsonl` passed validation because the argument guard only
     * knew about credential locations.
     */
    private val protectedDirs: Set<String> = CodingAgentFileSystem.DEFAULT_PROTECTED_DIRS,
    /**
     * `-Dbrowser4.agent.shell.pathGuard=off` disables the credential argument check.
     *
     * **Deprecated** (plan P3-5/D2) and advisory: the string layer is a fast-fail UX that saves a
     * sandbox launch and gives the model an actionable hint — it is *not* a security boundary.
     * Prompt-injected scripts can spell a path the tokenizer never sees, so enforcement lives in
     * the OS sandbox ([ai.platon.pulsar.coding.sandbox.ExecutionSandboxes]), not in this switch.
     *
     * [protectedDirs] is unaffected: those are rails (the agent's own bookkeeping), not a
     * capability heuristic somebody may need to switch off locally.
     */
    private val pathGuardEnabled: Boolean =
        System.getProperty("browser4.agent.shell.pathGuard", "on").lowercase() != "off",

    /**
     * Whether a literal absolute path argument outside the workspace is refused when
     * [allowExternalAccess] is off (default `on`).
     *
     * Separate from [pathGuardEnabled] because the two answer different questions: the
     * credential check is about secrets, this one is about the workspace boundary. Like the
     * credential check it is advisory (fast-fail), not an enforcement layer; the sandbox decides
     * what a process may actually reach.
     */
    private val workspaceBoundaryEnabled: Boolean =
        System.getProperty("browser4.agent.shell.workspaceBoundary", "on").lowercase() != "off",

    /**
     * Refuse commands that rebuild or restart the backend the task is running inside
     * (`b4w build|stop|install|upgrade|uninstall|recover`, `bin/build.ps1`,
     * `build-runtime-bundle.ps1`).
     *
     * The self-development isolation plan (§D8) treats the launch path as operator
     * work: a task that rebuilds its own runtime can leave the checkout with no
     * runnable backend. Compiling and testing (`coding.mvnBuild`/`coding.verify`)
     * is unaffected — it writes `target/`, not the runtime bundle.
     * `-Dbrowser4.agent.shell.forbidBootstrap=off` restores the old behaviour.
     */
    private val forbidBootstrap: Boolean =
        System.getProperty("browser4.agent.shell.forbidBootstrap", "on").lowercase() != "off",
    /**
     * `build-only` profile: development tools are narrowed to [BUILD_COMMANDS].
     *
     * Interpreters (`python`/`node`/`pwsh`/`sh`/`cmd`), cloud CLIs, containers, remote shells and
     * VCS are dev tools in the full profile but each is arbitrary code execution, which the
     * `write` and `read-build` presets do not grant. [AgentAccessPolicy.allowsExec] is not
     * consulted here on purpose: this shell backs the internal build runner.
     */
    private val buildOnly: Boolean = false,
    /**
     * When false the shell refuses every command up front.
     *
     * Used by [CodingComponents.shell] for the model-facing shell when the policy does not allow
     * execution; the internal build shell keeps it true (see [buildOnly]).
     */
    private val allowExecution: Boolean = true,
    /**
     * Live execution projection (plan P4-1): when set it overrides [allowExecution] on every call,
     * so an operator approval issued while the task runs takes effect without a new shell.
     */
    private val allowExecutionProvider: (() -> Boolean)? = null,
    /**
     * Live command-scope projection (plan P4-1): when set, a command that the preset does not
     * grant must be covered by an active operator grant for its program. Compared by program name,
     * the same granularity [AccessApprovals.commandRail] approves. `null` means "no target-level
     * check" (the internal build shell and direct construction).
     */
    private val commandAllowedProvider: ((String) -> Boolean)? = null,
    /**
     * The boundary [Authorizer] shared with the file system. When provided, [workspaceBoundaryViolation]
     * pins its request to [CodingComponents.CAP_SHELL_BOUNDARY] (lexical scope + toolchain/launcher
     * exemptions). When null, the instance builds its own boundary authorizer — kept so tests and
     * direct construction still work.
     */
    private val boundaryAuthorizer: Authorizer? = null,
    /**
     * Environment variables seeded into every command of this shell.
     *
     * Used to keep a nested `browser4-cli` on the same backend and state namespace as
     * the agent (see [CodingComponents.shell]); a per-task variable set later through
     * [setEnv] overrides a seed of the same name.
     */
    initialSessionEnv: Map<String, String> = emptyMap(),
    /**
     * Roots outside the workspace that may be named by a command argument for **reading**
     * ([ReadablePaths]).
     *
     * Empty for direct construction (historical workspace-only scope); the production wiring
     * ([CodingComponents.shell]) passes `ReadablePaths.roots()`, so `type <app data>/logs/...`
     * works while a credential location in the same tree stays refused. The boundary decision
     * asks for `fs.read` only: a path argument is where a command *reaches*, and the write side is
     * governed by the command tier and `allowDestructive`.
     */
    private val readableRoots: Set<Path> = emptySet(),
    /**
     * Folders granted to this task besides the workspace (`agent run --add-dir`).
     *
     * They are in the shell scope (the injected [boundaryAuthorizer] already has them) and, more
     * importantly here, they are handed to the execution sandbox as extra writable roots: with
     * `-Dbrowser4.agent.sandbox=windows-acl` the low-privilege account must be granted the same
     * folder, otherwise the string layer would allow a command the OS then denies.
     */
    private val additionalDirs: Set<Path> = emptySet(),
) {
    /**
     * The workspace this shell is bound to.
     *
     * Public because other tools that spawn processes must resolve relative paths the
     * same way the shell does: `b4.run` shipped a cwd of its own, and after a
     * workspace rebind it ran in the PREVIOUS task's workspace while `coding.*`
     * wrote into the new one (2026-10-28: a relative `--file .b4scratch/ev.js`
     * "not found" four times, pointing at another agent's directory). Reading the
     * root from the receiver makes the two agree by construction.
     */
    val workspaceRoot: Path get() = baseDir

    companion object {
        const val MAX_TIMEOUT_SECONDS = 600L
        const val MAX_OUTPUT_CHARS = 200_000

        /**
         * How long to wait for a captured pipe to reach EOF after the process exited.
         * A detached grandchild can keep the write end open (see [awaitCapturedOutput]).
         */
        private const val OUTPUT_CLOSE_GRACE_SECONDS = 5L

        /** Tool name the audit records shell refusals under. */
        private const val SHELL_TOOL = "coding.shell"

        /** Prefix of every [argumentPathViolation] message; used to pick the audit code. */
        private const val PATH_GUARD_PREFIX = "path guard: "

        /**
         * Spelled into every string-guard refusal (plan P3-5/D2).
         *
         * The text used to advertise `-Dbrowser4.agent.shell.pathGuard=off`, which read as an
         * official bypass and implied the switch was a boundary. The guard is advisory, so the
         * refusal now says so and points at the workspace instead.
         */
        private const val ADVISORY_NOTE =
            "the path check is advisory — the OS sandbox enforces the boundary when enabled; "

        /** Prefix of every [protectedPathViolation] message; used to pick the audit code. */
        private const val PROTECTED_PATH_PREFIX = "protected path: "

        /**
         * Prefix of the marker that says "the decomposer could not parse this shape".
         *
         * The marker never reaches the model: [validateCommand] turns it into a hand-off to the
         * platform shell ([handOffVetoViolation]), because there is no syntax gate. It is a private
         * signal between [validateSegments], [conditionalViolation] and that hand-off.
         */
        private const val UNPARSED_PREFIX = "cannot parse: "

        /**
         * Prefixes of the remaining decomposer markers.
         *
         * They mean "this shape cannot be decomposed", not "the policy said no": [isUnparsed]
         * collects the whole family so [validateCommand] knows the line has to be handed over.
         */
        private const val SUBSTITUTION_PREFIX = "command substitution rejected"

        private const val INLINE_SCRIPT_PREFIX = "inline script via "

        private const val EMPTY_COMMAND_VIOLATION = "empty or invalid command"

        /**
         * Prefix of the recursion-limit markers (`nested inline shell script …`, `nested command
         * substitution …`): the decomposer stopped rather than guessing. It belongs to the unparsed
         * family; the hand-off then refuses if its own scan stops at the same depth.
         */
        private const val NESTED_SCRIPT_PREFIX = "nested "

        /** Separates a wrapper reason from the inner one (`inline script via 'sh' rejected — …`). */
        private const val INNER_REASON_SEPARATOR = " — "

        /**
         * Fragments of the refusals **no** flag, preset or operator approval can lift.
         *
         * [permanentVetoHint] keys the hint on them. Each refusal message is composed from the same
         * constant the classifier matches, so the text and the classification cannot drift apart —
         * which is how the model ended up being told to `coding.requestAccess` a veto that never
         * lifts (2026-11-14 supervision run).
         */
        private const val VETO_WORKSPACE_LINK =
            "workspace links are provisioned by the framework, not by the agent"

        private const val VETO_BACKEND_KILL = "would terminate the Browser4 backend"

        private const val VETO_BOOTSTRAP = "rebuilds or restarts the backend this task runs inside"

        private const val VETO_BOOTSTRAP_SCRIPT = "refusing to run a bootstrap/recovery script"

        private const val VETO_EXPLICIT_DENY = "is explicitly denied"

        private const val VETO_DENY_PATTERN = "matches deny pattern: "

        /**
         * The two tier *category* vetoes, which [commandRefusal] hints at separately: they are
         * liftable (a wider preset, or `net.connect` approval), unlike the permanent ones above.
         */
        private const val VETO_NETWORK_DISABLED =
            "requires network access but it is disabled by the access policy"

        private const val VETO_DESTRUCTIVE_DISABLED =
            "is a destructive command but allowDestructive=false"

        /** Redirection introduced by an argument (`>file`, `>>file`, `2>file`, `&>file`). */
        private val REDIRECTION_PREFIX = Regex("""^(?:\d+|&)?>>?""")

        private val logger = LoggerFactory.getLogger(CodingAgentShell::class.java)

        private val PS_ENV_VAR = Regex("""\${'$'}env:([A-Za-z_][A-Za-z0-9_]*)""", RegexOption.IGNORE_CASE)
        private val WIN_ENV_VAR = Regex("""%([A-Za-z_][A-Za-z0-9_]*)%""")
        private val POSIX_ENV_VAR = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)""")

        /** Words that identify a credential artefact when a variable cannot be expanded. */
        private val SENSITIVE_PATH_KEYWORDS = listOf(
            ".ssh", "id_rsa", "id_ed25519", ".aws", ".gnupg", "gcloud", ".browser4",
        )

        private val IS_WINDOWS: Boolean =
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

        /** `/s`, `/b`, `/c:…` — Windows command switches, not POSIX paths. */
        private val WINDOWS_SWITCH = Regex("""^/[A-Za-z?][A-Za-z0-9:._-]*$""")

        /**
         * Build launchers that may be spelled with an absolute path outside the
         * workspace (`C:\tools\mvnw.cmd`, `/usr/bin/python3`). Data files stay contained.
         *
         * Visible to [CodingComponents] so the shared boundary [Authorizer] can encode the
         * launcher exemption as a scope entry instead of a separate shell-side check.
         */
        internal val LAUNCHER_BASENAMES = setOf(
            "mvnw", "mvnw.cmd", "mvnw.bat", "mvn", "mvn.cmd", "mvn.bat",
            "gradlew", "gradlew.bat", "gradle",
            "java", "javac", "javaw", "node", "npm", "npx", "pnpm", "yarn",
            "python", "python3", "python.exe", "py", "pip", "pip3",
            "pwsh", "powershell", "cmd", "bash", "sh", "zsh",
            "git", "cargo", "rustc", "go", "dotnet", "kotlin", "kotlinc",
        )

        /** Executable script/binary extensions — a launcher, never a data file. */
        internal val EXECUTABLE_EXTENSIONS = setOf("cmd", "bat", "exe", "ps1", "sh", "py", "com", "msc")

        /**
         * Charset of the bytes a child process writes to stdout/stderr.
         *
         * On Windows `cmd.exe` and its built-ins emit the console code page
         * (cp936/GBK on a Chinese system) while modern tools they spawn (Maven,
         * git, python) emit UTF-8. The JVM default charset is UTF-8 since JEP 400
         * (Java 18+), so decoding a localized `dir` listing with it produced
         * U+FFFD garbage the agent could not read — it then re-ran the command
         * with different syntax instead of using the output.
         *
         * `native.encoding` is the JVM's record of the OS native encoding; it is
         * UTF-8 on Linux/macOS, so this only changes Windows behaviour.
         */
        private val NATIVE_OUTPUT_CHARSET: Charset = runCatching {
            val name = System.getProperty("native.encoding")
                ?: System.getProperty("sun.jnu.encoding")
            if (name.isNullOrBlank()) StandardCharsets.UTF_8 else Charset.forName(name)
        }.getOrElse { StandardCharsets.UTF_8 }

        /**
         * Decode a child process's stdout/stderr bytes.
         *
         * Strict UTF-8 when the bytes decode cleanly (Maven, git, python), and
         * the platform native encoding otherwise (cmd.exe built-ins on a
         * localized Windows). A leading UTF-8 BOM is stripped.
         */
        fun decodeProcessOutput(bytes: ByteArray): String {
            if (bytes.isEmpty()) return ""
            val utf8 = runCatching {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            }.getOrNull()
            val text = utf8 ?: String(bytes, NATIVE_OUTPUT_CHARSET)
            return if (text.startsWith('\uFEFF')) text.substring(1) else text
        }

        // ------------------------------------------------------------------
        // Command categories
        // ------------------------------------------------------------------

        /** Read-only / safe commands — always permitted (subset of AgentShell whitelist) */
        val SAFE_COMMANDS = setOf(
            "ls", "dir", "pwd", "cd", "tree",
            "cat", "type", "less", "more", "head", "tail",
            "grep", "findstr", "awk", "sed",
            "wc", "sort", "uniq", "cut", "tr",
            "echo", "printf", "date", "time",
            "uname", "hostname", "uptime", "whoami", "id",
            "free", "df", "du", "ps", "top", "pgrep",
            "env", "printenv", "which", "where", "whereis",
            "ip", "ss",
            "man", "help", "info",
        )

        /** Development tools — permitted when [allowDevTools] is true */
        val DEV_COMMANDS = setOf(
            // Version control
            "git", "svn", "hg",
            // Java / JVM
            "java", "javac", "mvn", "mvnw", "gradle", "gradlew", "kotlin", "kotlinc",
            // Rust
            "cargo", "rustc", "rustup", "rustfmt",
            // Node.js / Frontend
            "node", "npm", "npx", "yarn", "pnpm", "tsc",
            "webpack", "vite", "esbuild",
            // Python
            "python", "python3", "pip", "pip3", "poetry", "uv", "pytest", "black", "ruff",
            // C / C++
            "gcc", "g++", "clang", "clang++", "make", "cmake", "ninja",
            // Go
            "go", "gofmt",
            // .NET
            "dotnet",
            // Shell scripting (inline scripts are recursively validated, see
            // validateSegments → inlineScript; `cmd` is Windows-only)
            "bash", "sh", "zsh", "dash", "powershell", "pwsh", "cmd",
            // Package management
            "apt", "apt-get", "brew", "choco", "winget", "yum", "dnf", "pacman",
            // Database clients
            "sqlite3", "psql", "mysql",
            // Container / orchestration
            "docker", "kubectl", "helm",
            // Infrastructure
            "terraform", "tofu",
            // Cloud CLIs
            "aws", "gcloud", "az",
            // SSH / remote
            "ssh", "scp",
        )

        /**
         * Build toolchain — the only development tools a [buildOnly] shell may run.
         *
         * Kept deliberately narrow: `mvn`/`cargo`/`npm` etc. execute the project's own build
         * scripts, so `read-build` is about preventing accidental damage and data exfiltration,
         * not about sandboxing hostile code (see the plan's threat model).
         */
        val BUILD_COMMANDS = setOf(
            "mvn", "mvnw", "gradle", "gradlew",
            "cargo", "rustc",
            "npm", "npx", "tsc",
            "go", "dotnet",
            "make", "cmake", "ninja", "javac", "kotlinc",
        )

        /** Commands that access the network — always require [allowNetwork] */
        val NETWORK_COMMANDS = setOf(
            "curl", "wget", "nc", "telnet", "nslookup", "dig",
        )

        /** Commands that can modify filesystem outside workspace */
        val DESTRUCTIVE_COMMANDS = setOf(
            "rm", "del", "rmdir", "mv", "move", "cp", "copy", "xcopy",
            "chmod", "chown", "icacls",
            "mount",
            "dd", "mkfs", "fdisk",
            "kill", "killall", "pkill", "taskkill",
            "shutdown", "reboot",
            "systemctl", "service", "sc",
        )

        /**
         * Commands the coding shell NEVER runs, whatever the flags say.
         *
         * Link/mount tools rewrite the workspace *topology*: a link the agent
         * creates (or deletes) turns later path resolution into something the
         * operator never approved. Observed in a real run: an agent used
         * `mklink /J` to rebuild a dozen module junctions inside its workspace,
         * then deleted and re-created one mid-run. The workspace is now
         * provisioned by the framework itself (`WorkspaceMirror`), so the agent
         * has no legitimate need for these tools.
         */
        val FORBIDDEN_COMMANDS = setOf(
            "mklink", "ln", "mount",
        )

        /**
         * Commands that terminate processes.
         *
         * They are not forbidden — killing a stray dev server is legitimate — but a task
         * must not kill the runtime that executes it (see [processKillViolation]).
         */
        private val PROCESS_KILL_COMMANDS = setOf(
            "taskkill", "tskill", "pskill", "kill", "pkill", "killall", "stop-process", "spps",
        )

        /** The pid named by `taskkill /PID 42`, `Stop-Process -Id 42`, `kill -9 42`, … */
        private val PID_ARGUMENT = Regex(
            """(?i)(?:/pid[=\s]+|-id[=\s]+|--pid[=\s]+|\bkill\s+(?:-\w+\s+)*)(\d+)"""
        )

        private val BLOCKED_PATTERNS = listOf(
            Regex("rm\\s+-[^\\s]*r[^\\s]*\\s+/\\s*$"),
            Regex("rm\\s+-[^\\s]*r[^\\s]*\\s+/\\*"),
            Regex("mkfs\\."),
            Regex("dd\\s+.*of=/dev/"),
            Regex("shutdown"),
            Regex("reboot"),
            Regex("init\\s+[06]"),
            Regex(":\\(\\)\\{"),
            Regex(">\\s*/dev/sd"),
            Regex("format\\s+[a-z]:", RegexOption.IGNORE_CASE),
        )

        /** `VAR=value` prefix that may precede the executable in a simple command. */
        private val ENV_ASSIGNMENT = Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*$""")

        /**
         * Executable suffixes stripped by [normalizeCommandName] before allow-list
         * membership. `.cmd`/`.bat`/`.exe`/`.com` cover Windows launchers
         * (`mvnw.cmd` → `mvnw`, which is what the DEV allow-list names).
         */
        private val EXECUTABLE_SUFFIXES = listOf(".exe", ".cmd", ".bat", ".com", ".ps1")

        /** Recursion guard for `sh -c "sh -c '...'"` style nesting. */
        private const val MAX_INLINE_SCRIPT_DEPTH = 3

        /**
         * Head budget kept for compilers/tests/linters when the output is
         * truncated: enough for the reactor banner, while the tail keeps the
         * "BUILD FAILURE" verdict and the last diagnostics.
         */
        private const val DIAGNOSTIC_HEAD_CHARS = 2_000

        /**
         * Commands whose output must be truncated tail-first (the verdict and
         * the actionable errors are at the end).
         */
        private val DIAGNOSTIC_COMMAND = Regex(
            """(^|[\s/\\])(mvn|mvnw|mvnw\.cmd|mvn\.cmd|gradle|gradlew|cargo|npm|pnpm|yarn|pytest|go|dotnet|tsc|eslint|ruff|black|ktlint|javac)\b""",
            RegexOption.IGNORE_CASE
        )
    }

    private val sessionCounter = AtomicLong(0)
    private val results = ConcurrentHashMap<String, ShellResult>()

    /** Per-session environment variables injected into all commands */
    private val sessionEnv: MutableMap<String, String> = ConcurrentHashMap(initialSessionEnv)

    /** Base working directory — resolved to canonical path for security checks */
    private val canonicalBaseDir: Path = baseDir.toRealPath()
    /** Canonical workspace directory used as the default command working directory. */
    val canonicalWorkspaceDir: Path get() = canonicalBaseDir

    // ------------------------------------------------------------------
    // Per-instance allow / deny overrides
    // ------------------------------------------------------------------

    /** Explicitly allowed base command names (e.g. "ffmpeg"). Overrides category membership. */
    private val explicitAllow: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Explicitly denied base command names (e.g. "git"). Takes priority over everything except blocked patterns. */
    private val explicitDeny: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Regex patterns that allow matching commands. The full command string is tested. */
    private val allowPatterns: MutableList<Regex> = mutableListOf()

    /** Regex patterns that deny matching commands. Takes priority over allow patterns. */
    private val denyPatterns: MutableList<Regex> = mutableListOf()

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Whether [baseCommand] is permitted by the enabled development-tool profile.
     *
     * In [buildOnly] mode the profile is [BUILD_COMMANDS] instead of [DEV_COMMANDS]; everything
     * else (safe, network, destructive, explicit allow/deny) is unchanged.
     */
    private fun devToolAllowed(baseCommand: String): Boolean =
        allowDevTools && baseCommand in if (buildOnly) BUILD_COMMANDS else DEV_COMMANDS

    /**
     * Execute a command with full development tool support.
     *
     * @param command The command to execute
     * @param timeoutSeconds Timeout in seconds (default: 120, max: 600)
     * @param workingDir Working directory override (absolute, or relative to baseDir)
     * @param env Additional environment variables for this command
     * @return Formatted execution result string
     */
    suspend fun execute(
        command: String,
        timeoutSeconds: Long = defaultTimeoutSeconds,
        workingDir: String? = null,
        env: Map<String, String> = emptyMap(),
    ): String {
        if (!isExecutionAllowed()) {
            return "Error: " + execDisabledRefusal()
        }
        if (command.isBlank()) {
            return "Error: Command must not be blank."
        }

        val violation = validateCommand(normalizeShellCommand(command))
        if (violation != null) {
            return "Error: " + commandRefusal(violation)
        }
        operatorScopeViolation(normalizeShellCommand(command))?.let { return "Error: " + it }
        recordScopeAdvisories(normalizeShellCommand(command))

        val effectiveTimeout = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)
        val sessionId = "devshell-${sessionCounter.incrementAndGet()}"

        // v0.3: resolveWorkingDir now throws on bad paths — we do NOT catch
        // WorkspaceEscapeException/WorkspaceDirMissingException here; they
        // must surface to the ToolExecutionCoordinator's [fail] wrapper.
        val dir = resolveWorkingDir(workingDir)

        return try {
            val result = withContext(Dispatchers.IO) {
                runCommand(sessionId, normalizeShellCommand(command), effectiveTimeout, dir, env)
            }
            results[sessionId] = result
            formatResult(result)
        } catch (e: IOException) {
            "Error: I/O failure for '$command' — ${e.message ?: ""}"
        } catch (e: SecurityException) {
            "Error: Permission denied for '$command' — ${e.message ?: ""}"
        } catch (e: WorkspaceEscapeException) {
            throw e  // v0.3: propagate to caller so [fail] can be emitted
        } catch (e: WorkspaceDirMissingException) {
            throw e  // v0.3: propagate
        } catch (e: Exception) {
            logger.warn("Unexpected error executing '$command'", e)
            "Error: Unexpected error executing '$command' — ${e.message ?: ""}"
        }
    }

    /**
     * Execute a command and return the raw [ShellResult].
     */
    suspend fun executeRaw(
        command: String,
        timeoutSeconds: Long = defaultTimeoutSeconds,
        workingDir: String? = null,
        env: Map<String, String> = emptyMap(),
    ): ShellResult {
        if (!isExecutionAllowed()) {
            return ShellResult("", command, -1, "", execDisabledRefusal(), 0)
        }
        if (command.isBlank()) {
            return ShellResult("", command, -1, "", "Command must not be blank.", 0)
        }

        val violation = validateCommand(normalizeShellCommand(command))
        if (violation != null) {
            return ShellResult("", command, -1, "", commandRefusal(violation), 0)
        }
        operatorScopeViolation(normalizeShellCommand(command))?.let {
            return ShellResult("", command, -1, "", it, 0)
        }
        recordScopeAdvisories(normalizeShellCommand(command))

        val effectiveTimeout = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)
        val sessionId = "devshell-${sessionCounter.incrementAndGet()}"
        // v0.3: was `resolveWorkingDir(workingDir) ?: canonicalBaseDir.toFile()` —
        // the silent fallback hid misconfiguration from the caller. Now we throw.
        val dir = resolveWorkingDir(workingDir)

        return try {
            val result = withContext(Dispatchers.IO) {
                runCommand(sessionId, normalizeShellCommand(command), effectiveTimeout, dir, env)
            }
            results[sessionId] = result
            result
        } catch (e: WorkspaceEscapeException) {
            throw e  // v0.3: propagate
        } catch (e: WorkspaceDirMissingException) {
            throw e  // v0.3: propagate
        } catch (e: Exception) {
            ShellResult(sessionId, command, -1, "", e.message ?: "Unknown error", 0)
        }
    }

    fun readOutput(sessionId: String): String {
        val result = results[sessionId]
            ?: return "Error: No result found for session '$sessionId'."
        return formatResult(result)
    }

    fun getStatus(sessionId: String): String {
        val result = results[sessionId]
            ?: return "Error: No session found with ID '$sessionId'."
        val status = if (result.success) "SUCCESS" else "FAILED"
        return "Session '$sessionId': status=$status, exitCode=${result.exitCode}, " +
            "timedOut=${result.timedOut}, duration=${result.durationMs}ms, " +
            "command='${result.command}'"
    }

    fun listSessions(): String {
        if (results.isEmpty()) return "No shell sessions recorded."
        val sb = StringBuilder()
        sb.appendLine("Shell sessions (${results.size} total):")
        for ((id, result) in results) {
            val status = if (result.success) "SUCCESS" else "FAILED"
            sb.appendLine("- $id: status=$status, command='${result.command}', duration=${result.durationMs}ms")
        }
        return sb.toString().trimEnd()
    }

    /**
     * Set a persistent environment variable for all subsequent commands in this session.
     */
    fun setEnv(name: String, value: String) {
        sessionEnv[name] = value
    }

    /**
     * Remove a persistent environment variable.
     */
    fun unsetEnv(name: String) {
        sessionEnv.remove(name)
    }

    /**
     * Get all session environment variables.
     */
    fun getEnv(): Map<String, String> = sessionEnv.toMap()

    // ------------------------------------------------------------------
    // Allow / deny API
    // ------------------------------------------------------------------

    /**
     * Allow a specific command by its base name (e.g. "ffmpeg", "pandoc").
     * This adds the command to a per-instance allow-list that overrides
     * the category check — even if the command is not in any predefined set,
     * it will be permitted.
     *
     * Example:
     * ```kotlin
     * shell.allowCommand("ffmpeg")    // allow ffmpeg even though it's not in DEV_COMMANDS
     * shell.allowCommand("pandoc")    // allow pandoc
     * ```
     */
    fun allowCommand(name: String) {
        explicitAllow.add(name.lowercase())
        explicitDeny.remove(name.lowercase())
    }

    /**
     * Deny a specific command by its base name (e.g. "git", "curl").
     * This adds the command to a per-instance deny-list. The deny list
     * takes priority over everything — even SAFE_COMMANDS and explicit
     * allows — so you can lock down a command entirely.
     *
     * Example:
     * ```kotlin
     * shell.denyCommand("git")        // block git entirely
     * shell.denyCommand("curl")       // block curl even if allowNetwork=true
     * ```
     */
    fun denyCommand(name: String) {
        explicitDeny.add(name.lowercase())
        explicitAllow.remove(name.lowercase())
    }

    /**
     * Remove a command from both the allow and deny lists, reverting
     * to the default category-based policy.
     */
    fun resetCommand(name: String) {
        explicitAllow.remove(name.lowercase())
        explicitDeny.remove(name.lowercase())
    }

    /**
     * Allow commands matching a regex pattern. The full command string
     * (e.g. "pip install requests") is tested against the pattern.
     *
     * Example:
     * ```kotlin
     * shell.allowPattern(Regex("pip\\s+install.*"))   // allow pip install
     * shell.allowPattern(Regex("npm\\s+run\\s+.*"))   // allow npm run <script>
     * ```
     */
    fun allowPattern(pattern: Regex) {
        allowPatterns.add(pattern)
    }

    /**
     * Deny commands matching a regex pattern. Deny patterns take priority
     * over allow patterns — if a command matches both, it is denied.
     *
     * Example:
     * ```kotlin
     * shell.denyPattern(Regex("git\\s+push.*--force"))  // block force push
     * shell.denyPattern(Regex("rm\\s+-rf\\s+/"))         // block recursive root delete
     * ```
     */
    fun denyPattern(pattern: Regex) {
        denyPatterns.add(pattern)
    }

    /**
     * Explain the current effective policy for a command — why it is allowed or
     * denied.
     *
     * Delegates to [validateCommand] so the explanation can never disagree with
     * the decision: the two drifted apart once (the explanation still reported
     * the head token of a chained command as allowed while the validator
     * rejected the chain).
     */
    fun explain(command: String): String {
        val violation = validateCommand(command)
        if (violation != null) {
            return "'$command' is DENIED — $violation"
        }
        // The decomposer gave up on this shape, so the line is handed to the platform shell. Say so
        // instead of explaining the first token of a command line that was never decomposed.
        if (unparsedMarker(command) != null) {
            return "'$command' is ALLOWED — the shell cannot decompose this shape, so the line is " +
                "handed to the platform shell verbatim; the runtime vetoes (backend kill, bootstrap, " +
                "workspace links) and a disabled network/destructive tier still apply"
        }

        val base = extractBaseCommand(command)
        val reason = when {
            base in explicitAllow -> "'$base' is in the explicit allow list"
            base in SAFE_COMMANDS -> "'$base' is a safe command"
            devToolAllowed(base) ->
                if (buildOnly) "'$base' is a build tool (build-only profile)"
                else "'$base' is a dev tool (allowDevTools=true)"
            networkAllowed() && base in NETWORK_COMMANDS ->
                "'$base' is a network command (network access enabled)"
            allowDestructive && base in DESTRUCTIVE_COMMANDS -> "'$base' is a destructive command (allowDestructive=true)"
            unrestricted -> "'$base' is outside the tier allow-list, and this task is unrestricted"
            else -> "every command in the chain is allowed (pattern / path / PATH rule)"
        }
        return "'$command' is ALLOWED — $reason"
    }

    /**
     * Check whether a command would be allowed without actually executing it.
     */
    fun isAllowed(command: String): Boolean {
        return validateCommand(command) == null
    }

    // ------------------------------------------------------------------
    // Working directory resolution (v0.3 rewrite for lexical containment)
    // ------------------------------------------------------------------

    /** Resolve the effective working directory for one shell invocation.
     *
     * v0.3 replaces the previous `File?` return with typed exceptions so
     * callers (S1) can translate them to `[fail]` without matching on strings.
     * The checks mirror [CodingAgentFileSystem.resolvePath] so the two tools
     * stay on the same containment policy.
     *
     * Rules:
     * - No argument → canonicalBaseDir (always valid, auto-creates).
     * - Absolute path → must already exist AND must lie lexically inside canonicalBaseDir, inside a
     *   folder granted with `agent run --add-dir`, or anywhere when allowExternalAccess=true.
     * - Relative path → resolved against canonicalBaseDir; the resulting
     *   path must be lexically inside canonicalBaseDir; non-parent directories
     *   are auto-created (agent is expected to want a new subdir); absolute
     *   existence is still required for external callers' invariants.
     *
     * We do NOT use `toRealPath()`: workspace module roots are junctions whose
     * real path lands in the source checkout, so a lexical check against
     * canonicalBaseDir is the only stable one.
     */
    private fun resolveWorkingDir(workingDir: String?): File {
        val raw = workingDir?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val candidate: Path = when {
            raw == null -> canonicalBaseDir
            raw.isAbsolute -> raw.normalize()
            else -> canonicalBaseDir.resolve(raw).normalize()
        }
        if (!allowExternalAccess && !candidate.startsWith(canonicalBaseDir) && !isInAddedDir(candidate)) {
            throw WorkspaceEscapeException(candidate, canonicalBaseDir)
        }
        // A working directory inside a protected subtree would make every relative path in the
        // command a protected write (`cd .browser4 && echo forged > agent-tasks.jsonl`).
        protectedRoots.firstOrNull { candidate.startsWith(it) }?.let { root ->
            throw WorkspaceProtectedPathException(
                candidate, canonicalBaseDir.relativize(root).toString().replace('\\', '/')
            )
        }
        if (!java.nio.file.Files.exists(candidate)) {
            // Only auto-create a relative subdirectory — absolute external
            // paths (and anything rejected above) must already exist.
            if (raw == null || raw.isAbsolute) {
                throw WorkspaceDirMissingException(candidate)
            }
            runCatching { java.nio.file.Files.createDirectories(candidate) }
                .onFailure { throw WorkspaceDirMissingException(candidate, it.message) }
        }
        if (!java.nio.file.Files.isDirectory(candidate)) {
            throw WorkspaceDirMissingException(candidate, "path is not a directory")
        }
        return candidate.toFile()
    }

    // ------------------------------------------------------------------
    // Command validation
    // ------------------------------------------------------------------

    /**
     * Structured refusal for a command the tiered allow-list refused.
     *
     * The reason itself is kept verbatim, because tools outside this module match on it (for example
     * `bin/eval-devtask.ps1` recognises `Command blocked` and `is not allowed (safe=`), but it is
     * wrapped in a structured envelope so the model, the CLI and `FailureCategory` can all tell a
     * structural refusal from a command that simply failed.
     *
     * Only policy verdicts arrive here: a shape the decomposer cannot parse is handed to the
     * platform shell by [validateCommand], never reported as a refusal.
     */
    private fun commandRefusal(violation: String): String {
        // A veto that no flag, preset or approval can lift gets its own hint. Sending the model to
        // `coding.requestAccess` for one of these is a dead end it will retry: the refusal is
        // permanent by design, so the hint names what is permanent and the route that stays open.
        permanentVetoHint(violation)?.let { vetoHint ->
            return PermissionAudit.denied(
                tool = SHELL_TOOL,
                code = PermissionAudit.CODE_COMMAND_NOT_ALLOWED,
                detail = "Command blocked — $violation",
                hint = vetoHint,
            )
        }
        // `contains`, not `startsWith`: a violation can be wrapped by a nested-command layer
        // (`command substitution rejected — path guard: …`) and the specific audit code is still
        // the one the inner guard produced.
        val leaf = leafViolation(violation)
        val (code, hint, needed) = when {
            violation.contains(PATH_GUARD_PREFIX) ->
                Triple(
                    PermissionAudit.CODE_COMMAND_PATH_DENIED,
                    // Without a workspace boundary (`--access full` with external access, or an
                    // `--unrestricted` task) telling the model to use a workspace-relative path
                    // sends it down a wall that is not there: the credential rail is the whole
                    // reason for this refusal, so name only that.
                    if (allowExternalAccess) {
                        "credential locations are never valid arguments — a hard rail even where the " +
                            "workspace boundary is off; the agent must not read credentials into its context"
                    } else {
                        "pass a path inside the workspace; credential locations are never valid arguments"
                    },
                    null,
                )
            violation.contains(PROTECTED_PATH_PREFIX) ->
                Triple(
                    PermissionAudit.CODE_PROTECTED_PATH,
                    "use the coding.* tools for the agent's own bookkeeping (.browser4) and CI " +
                        "definitions (.github/workflows); the shell may not touch them",
                    null,
                )
            leaf.contains(VETO_NETWORK_DISABLED) ->
                Triple(
                    PermissionAudit.CODE_COMMAND_NOT_ALLOWED,
                    "network access is off for this task — ask for it with " +
                        "coding.requestAccess(effect=\"net.connect\", target=\"<host>\"), or run the " +
                        "command from outside the task",
                    Effect.NET_CONNECT.id,
                )
            leaf.contains(VETO_DESTRUCTIVE_DISABLED) ->
                Triple(
                    PermissionAudit.CODE_COMMAND_NOT_ALLOWED,
                    "destructive commands are off under this access preset — re-run the task with " +
                        "`--access write`/`full`, or use a non-destructive command; an approval does " +
                        "not widen a preset",
                    null,
                )
            else ->
                // The tier refusal. No approval widens it (the allow-list is fixed by the
                // embedding), so the hint names the routes that exist and never offers
                // `coding.requestAccess` — the old hint did, and a refusal whose fix does not
                // exist is a round the model wastes (2026-11-14 run).
                Triple(
                    PermissionAudit.CODE_COMMAND_NOT_ALLOWED,
                    "the shell's allow-list does not cover this command and no approval widens it — " +
                        "use an allowed equivalent (type/cat/head/dir/grep) or a coding.* tool " +
                        "(coding.read, coding.stat, coding.glob), run the build through " +
                        "coding.mvnBuild/coding.verify, or put the step in a script file and run it " +
                        "by path; a task that genuinely needs arbitrary programs runs with " +
                        "`--unrestricted`",
                    null,
                )
        }
        return PermissionAudit.denied(
            tool = SHELL_TOOL,
            code = code,
            detail = "Command blocked — $violation",
            hint = hint,
            needed = needed,
        )
    }

    /**
     * The hint for a refusal **no** flag, preset or operator approval can lift.
     *
     * These are the promises the access model makes outside its presets: the workspace topology the
     * framework provisions, the runtime executing the task, and an operator's explicit deny. A hint
     * that offers `coding.requestAccess` for them is worse than none — the model files a request
     * that can never be granted and retries the step (observed in the 2026-11-14 supervision run,
     * where a `COMMAND_NOT_ALLOWED` hint suggested an approval for a permanent veto).
     *
     * @param violation the refusal text, possibly wrapped (`inline script via 'sh' rejected — …`)
     * @return the hint, or null when the refusal is an ordinary policy decision
     */
    private fun permanentVetoHint(violation: String): String? {
        val leaf = leafViolation(violation)
        return when {
            leaf.contains(VETO_WORKSPACE_LINK) ->
                "workspace links are provisioned by the framework — no approval or wider preset " +
                    "lifts this; use the workspace paths as they are"
            leaf.contains(VETO_BACKEND_KILL) ->
                "a task must not terminate the runtime it runs inside — no approval lifts this; " +
                    "stop the process from outside the task, or name a pid that is not the backend"
            leaf.contains(VETO_BOOTSTRAP) || leaf.contains(VETO_BOOTSTRAP_SCRIPT) ->
                "rebuilding or restarting this backend is operator work — no approval lifts it " +
                    "from a task; run the build outside the task (`b4w build`) or use " +
                    "coding.mvnBuild/coding.verify"
            leaf.contains(VETO_EXPLICIT_DENY) || leaf.contains(VETO_DENY_PATTERN) ->
                "this command is excluded by the task's policy — no approval lifts it; choose a " +
                    "different route, or report the step in system.taskComplete(problems=…)"
            else -> null
        }
    }

    /** Whether execution is allowed right now (live when a provider was supplied, plan P4-1). */
    private fun isExecutionAllowed(): Boolean = allowExecutionProvider?.invoke() ?: allowExecution

    /** Whether network-capable commands are allowed right now (live when a provider was supplied). */
    private fun networkAllowed(): Boolean = allowNetworkProvider?.invoke() ?: allowNetwork

    /**
     * Structured refusal when the preset does not grant execution and no active operator grant
     * covers this command.
     *
     * Every simple command in the chain is checked, so a grant for `echo` cannot smuggle a
     * different program in through `&&`. A preset that grants execution returns `true` from the
     * provider immediately, so its behaviour is unchanged.
     */
    private fun operatorScopeViolation(command: String): String? {
        val provider = commandAllowedProvider ?: return null
        val segments = splitSimpleCommands(command).ifEmpty { listOf(command) }
        val unapproved = segments.firstOrNull { !provider(it) } ?: return null
        val program = extractBaseCommand(unapproved).ifEmpty { unapproved }
        return PermissionAudit.denied(
            tool = SHELL_TOOL,
            code = PermissionAudit.CODE_COMMAND_NOT_ALLOWED,
            detail = "command '$program' is not covered by an active operator grant",
            hint = "request operator approval with " +
                "coding.requestAccess(effect=\"fs.exec\", target=\"$program\") — the grant covers " +
                "that program only; commands the preset already allows stay unaffected",
            needed = Effect.FS_EXEC.id,
        )
    }

    /** Structured refusal for the `allowExecution=false` presets. */
    private fun execDisabledRefusal(): String = PermissionAudit.denied(
        tool = SHELL_TOOL,
        code = PermissionAudit.CODE_EXEC_DISABLED,
        detail = "shell execution is disabled by the access policy",
        hint = "request operator approval with " +
            "coding.requestAccess(effect=\"fs.exec\", target=\"<command>\") — it returns " +
            "immediately with a pending requestId, and the task keeps running meanwhile",
        needed = Effect.FS_EXEC.id,
    )

    /** True when the host is Windows, where commands run through `cmd.exe /c`. */
    private fun isWindowsHost(): Boolean =
        System.getProperty("os.name", "").lowercase().contains("win")

    /**
     * Platform-specific command normalization, applied before validation AND execution.
     *
     * `cmd.exe` spells some idioms differently from the command name the allow-list
     * checks: `echo.` (print a blank line) is not `echo`, so it was rejected as an
     * unknown command and the model had no clean way to emit a separator (2026-10-28:
     * agents worked around it with `python -c` and lost time). The same normalization
     * runs on the executed text, so the validated command and the executed command
     * cannot diverge.
     */
    internal fun normalizeShellCommand(command: String): String {
        if (!isWindowsHost()) return command
        return CMD_ECHO_DOT.replace(command) { match ->
            match.groupValues[1] + "echo"
        }
    }

    /** `echo.` / `echo. text` at a command position → `echo` (cmd's blank-line idiom). */
    private val CMD_ECHO_DOT = Regex("""(^|[&|;]\s*)echo\.(?=\s|$)""", RegexOption.IGNORE_CASE)

    /** cmd.exe flow-control keywords: valid in a script, never as a standalone command. */
    private val CMD_CONTROL_KEYWORDS = setOf("if", "for")

    /**
     * Tokens after which a program starts inside a body that is handed to the platform shell
     * verbatim (`for … do <program>`, `( <program> )`, `cmd1 & <program>`, POSIX `then`/`else`).
     *
     * Used by [handOffVetoViolation] to keep its scan from reading ordinary arguments as programs.
     */
    private val HAND_OFF_PROGRAM_PREFIXES =
        setOf("do", "then", "else", "(", ")", "&", "&&", "|", "||", ";")

    /**
     * Directory-creation builtins, always allowed (the workspace boundary still applies).
     *
     * `mkdir`/`md` are cmd.exe builtins, so on Windows there is no `mkdir.exe` for the PATH
     * fallback to find and the command was refused with the generic "not allowed". It is
     * ordinary workspace work — `coding.mkdir` does exactly this, and the CLI's own docs
     * recommend `mkdir -p ./downloads` — so a live run lost a round to the refusal for no
     * security gain (2026-10-29 Amazon supervision run).
     */
    private val CREATE_DIR_COMMANDS = setOf("mkdir", "md")

    /**
     * Validate a whole command line, not just its first token.
     *
     * A shell command is a *pipeline* of simple commands: `&&`, `||`, `;`, `|`,
     * `&`, newlines, command substitution (`$(...)`, backticks) and inline
     * scripts (`sh -c '...'`, `pwsh -Command '...'`, `cmd /c ...`) each carry
     * more commands. Validating only the head token let `ls && rm -rf x` and
     * `sh -c "curl http://evil"` through — the historical sandbox hole (plan
     * P0.7). Every base command found is validated against the same tiered
     * allow-list; nested inline scripts are validated recursively.
     *
     * @return a human-readable violation reason, or null when allowed
     */
    private fun validateCommand(command: String): String? {
        val violation = validateCommandRaw(command) ?: return null
        // There is no syntax gate: a shape the decomposer cannot parse is handed to the platform
        // shell verbatim, in every mode, and the shell reports its own parse error. The vetoes that
        // protect the runtime this task runs *inside* are not grammar rules and are not dropped
        // with the gate: a `for` loop's body can still hold `taskkill /PID <backend>`, and the OS
        // cannot refuse that for us. See [handOffVetoViolation].
        if (!isUnparsed(violation)) return violation
        return handOffVetoViolation(command)
    }

    /**
     * Whether [violation] is the decomposer's own marker — "this shape cannot be parsed" — rather
     * than a policy verdict.
     *
     * Such a marker never reaches the model: [validateCommand] hands the line to the platform shell
     * ([handOffVetoViolation]) instead. `empty or invalid command` is deliberately **not** in the
     * family — there is nothing to hand over, so it stays an ordinary refusal.
     */
    private fun isUnparsed(violation: String): Boolean {
        val leaf = leafViolation(violation)
        return leaf.startsWith(UNPARSED_PREFIX) ||
            leaf.startsWith(SUBSTITUTION_PREFIX) ||
            leaf.startsWith(INLINE_SCRIPT_PREFIX) ||
            leaf.startsWith(NESTED_SCRIPT_PREFIX)
    }

    /** The decomposer's marker for [command], or null when the line parsed. */
    private fun unparsedMarker(command: String): String? =
        validateCommandRaw(command)?.takeIf { isUnparsed(it) }

    /**
     * The innermost reason of a wrapped violation (`inline script via 'sh' rejected — …`).
     *
     * The wrapper carries the inner reason after an em-dash; classifying *that* one keeps a policy
     * veto nested inside `$(...)` or `sh -c "..."` from being handed over by accident
     * (`echo $(rm -rf /)` must stay refused under `--unrestricted`, too). One helper for every
     * classifier, so the hand-off and the hint family always agree about which rule produced the
     * refusal.
     */
    private fun leafViolation(violation: String): String {
        var current = violation
        while (current.startsWith(SUBSTITUTION_PREFIX) || current.startsWith(INLINE_SCRIPT_PREFIX)) {
            val inner = current.substringAfter(INNER_REASON_SEPARATOR, "")
            // No separator: the wrapper did not compose an inner reason, so it is the leaf.
            if (inner.isEmpty()) return current
            current = inner
        }
        return current
    }

    /**
     * The vetoes that apply to a line handed to the platform shell undecomposed.
     *
     * When the decomposer cannot parse a shape (`for` loops, `else if`, nested blocks, `goto`/`call`
     * payloads), the line is handed over verbatim and the platform shell reports its own parse
     * error. The command tier cannot judge such a line — it never names the programs it runs — but
     * the vetoes that do not need the grammar still apply, and they are checked at every *program
     * position* (the first token of a segment, or the token after `do`/`then`/`else`/`(`/`&`/`|`/
     * `;`), plus every inline-script body recursively:
     *
     * - [FORBIDDEN_COMMANDS] — link/mount tools rewrite the workspace topology the framework
     *   provisions (and a normal command never gets them approved away either);
     * - [processKillViolation] — killing the backend (or a JVM that may be it) ends the very run
     *   that submitted the loop; `for %f in (1) do taskkill /PID <backend> /F` is exactly the line
     *   the per-command validator never sees;
     * - [bootstrapViolation] — `b4w build/stop/...` and `bin/build.ps1` rewrite or restart the
     *   runtime the task is executing inside;
     * - the network and destructive tier vetoes, when those tiers are disabled — `curl` or `rm`
     *   hidden behind `do` is still a network/destructive command;
     * - the credential/protected-path guards and the workspace boundary, applied to every segment's
     *   tokens — a `for` body is not a licence to name `~/.ssh` or `.browser4`.
     *
     * Block parentheses are stripped from the program token, so a veto cannot hide behind `( … )`.
     * An ordinary argument is not a program, so `echo b4w build failed` stays allowed. Past
     * [MAX_INLINE_SCRIPT_DEPTH] the scan can no longer prove the line safe and refuses it.
     *
     * @param command the line that is about to be handed to the platform shell
     * @param depth inline-script recursion depth, capped by [MAX_INLINE_SCRIPT_DEPTH]
     * @return a violation that must still refuse the line, or null when nothing protected is reachable
     */
    internal fun handOffVetoViolation(command: String, depth: Int = 0): String? {
        if (depth > MAX_INLINE_SCRIPT_DEPTH) {
            return "nested inline shell script exceeds $MAX_INLINE_SCRIPT_DEPTH levels — " +
                "refusing to hand over unverified"
        }
        // Already applied to the line by [validateCommandRaw]; re-applied here so an inline-script
        // body — which the hand-off would otherwise pass over unexamined — gets the same vetoes.
        blockedPatternViolation(command)?.let { return it }
        for (segment in splitSimpleCommands(command)) {
            val tokens = tokenize(segment)
            if (tokens.isEmpty()) continue
            // The decomposer returned at the offending segment, so anything after it was never
            // checked by the tier: run the guards that do not need the grammar over every segment.
            argumentPathViolation(tokens)?.let { return it }
            protectedPathViolation(tokens)?.let { return it }
            workspaceBoundaryViolation(tokens)?.let { return it }
            for (index in tokens.indices) {
                if (!isHandOffProgramPosition(tokens, index)) continue
                val programTokens = programTokensAt(tokens, index)
                val base = normalizeCommandName(programTokens[index])
                if (base in FORBIDDEN_COMMANDS) return forbiddenCommandViolation(base)
                inlineScript(programTokens.subList(index, programTokens.size))?.let { inner ->
                    handOffVetoViolation(inner, depth + 1)?.let { return it }
                }
                processKillViolation(command, programTokens.subList(index, programTokens.size))?.let { return it }
                bootstrapViolation(command, programTokens, index)?.let { return it }
                if (base in NETWORK_COMMANDS && !networkAllowed()) {
                    return "'$base' $VETO_NETWORK_DISABLED (inside a shape coding.shell cannot decompose)"
                }
                if (base in DESTRUCTIVE_COMMANDS && !allowDestructive) {
                    return "'$base' $VETO_DESTRUCTIVE_DISABLED (inside a shape coding.shell cannot decompose)"
                }
            }
        }
        return null
    }

    /**
     * [tokens] with the program token at [index] stripped of block parentheses.
     *
     * `for %f in (1) do (mklink …)` tokenizes the program as `(mklink`, and `(taskkill … /F)` as
     * `(taskkill`: without this, a veto inside a block would be invisible to the guard — the one
     * place a handed-off line can hide a program. The caller's list is returned unchanged when there
     * is nothing to strip, so the common path does not copy.
     */
    private fun programTokensAt(tokens: List<String>, index: Int): List<String> {
        val raw = tokens[index]
        val program = raw.trim('(', ')')
        if (program == raw || program.isEmpty()) return tokens
        return tokens.toMutableList().also { it[index] = program }
    }

    /**
     * Whether [index] in [tokens] is a place where a program can start.
     *
     * The guard must not read an ordinary argument as a program (`echo b4w build failed` is not a
     * bootstrap step), so only the first token of a segment and the token after a control-flow
     * keyword or a separator counts.
     */
    private fun isHandOffProgramPosition(tokens: List<String>, index: Int): Boolean {
        if (index == 0) return true
        val previous = tokens[index - 1].trim('"', '\'').lowercase()
        return previous in HAND_OFF_PROGRAM_PREFIXES
    }

    /**
     * The unfiltered command validator — see [validateCommand] for the hand-off of shapes the
     * decomposer cannot parse.
     *
     * @return a human-readable violation reason, or null when allowed
     */
    private fun validateCommandRaw(command: String): String? {
        // Normalize first so a policy query (`isAllowed` / `explain`) agrees with what
        // would actually be executed: `echo.` is cmd's blank-line idiom for `echo`.
        val normalizedCommand = normalizeShellCommand(command)
        if (normalizedCommand.isBlank()) return EMPTY_COMMAND_VIOLATION

        // 1. Hardcoded blocked patterns — whole string, cannot be overridden
        blockedPatternViolation(normalizedCommand)?.let { return it }

        // 2. Explicit deny / deny patterns — whole-string vetoes (they are
        //    authored as full-command regexes), re-checked per segment below
        extractBaseCommand(normalizedCommand).lowercase().let { base ->
            if (base.isNotEmpty() && base in explicitDeny) return "'$base' $VETO_EXPLICIT_DENY"
        }
        for (pattern in denyPatterns) {
            if (pattern.containsMatchIn(normalizedCommand)) return "$VETO_DENY_PATTERN${pattern.pattern}"
        }

        // 3. Explicit allow / allow patterns — whole-string opt-ins bypass the
        //    per-token checks (the operator authored a trust rule for this shape)
        if (extractBaseCommand(normalizedCommand).lowercase() in explicitAllow) return null
        for (pattern in allowPatterns) {
            if (pattern.containsMatchIn(normalizedCommand)) return null
        }

        // 4. Every simple command in the pipeline / substitution / inline script
        return validateSegments(normalizedCommand, depth = 0)
    }

    private fun blockedPatternViolation(command: String): String? {
        for (pattern in BLOCKED_PATTERNS) {
            if (pattern.containsMatchIn(command)) {
                return "matches blocked pattern: ${pattern.pattern}"
            }
        }
        return null
    }

    /** Recursively validate every simple command inside [command]. */
    private fun validateSegments(command: String, depth: Int): String? {
        if (depth > MAX_INLINE_SCRIPT_DEPTH) {
            return "nested inline shell script exceeds $MAX_INLINE_SCRIPT_DEPTH levels — refusing to guess"
        }
        blockedPatternViolation(command)?.let { return it }

        val segments = splitSimpleCommands(command)
        if (segments.isEmpty()) return EMPTY_COMMAND_VIOLATION
        for (segment in segments) {
            validateSimpleCommand(segment, depth)?.let { return it }
        }
        return null
    }

    /** Home-expanded credential roots, shared with [CodingAgentFileSystem]. */
    private val sensitiveRoots: List<Path> by lazy { SensitivePaths.roots(sensitivePaths) }

    /** [readableRoots] as absolute normalised paths; resolved once per instance. */
    private val resolvedReadableRoots: List<Path> by lazy {
        readableRoots.map { it.toAbsolutePath().normalize() }
    }

    /** [additionalDirs] as absolute normalised paths; resolved once per instance. */
    private val resolvedAdditionalDirs: List<Path> by lazy {
        additionalDirs.map { it.toAbsolutePath().normalize() }
    }

    /**
     * Whether [candidate] lies inside a folder granted with `agent run --add-dir`.
     *
     * Such a folder is usable as a shell working directory — that is the "executable" part of the
     * grant: the command's cwd may be there, and the OS sandbox was given the same folder (see
     * [runCommand]). The workspace boundary check in [workspaceBoundaryViolation] already consults
     * the shared [boundaryAuthorizer], which has these folders in scope.
     */
    private fun isInAddedDir(candidate: Path): Boolean =
        resolvedAdditionalDirs.any { candidate.startsWith(it) }

    /** Absolute roots of [protectedDirs] under the workspace base. */
    private val protectedRoots: List<Path> by lazy {
        protectedDirs.map { canonicalBaseDir.resolve(it).normalize() }
    }

    /**
     * Rejects path arguments that resolve into a credential location.
     *
     * The shell already contains the *working directory* to [canonicalBaseDir], but arguments were
     * never inspected: `type %USERPROFILE%\.ssh\id_rsa` (Windows), `cat ~/.ssh/id_rsa` or
     * `ssh -i $env:USERPROFILE\.ssh\id_rsa` all passed. Paths inside the workspace and ordinary
     * absolute paths outside it stay allowed — this guard is only about credentials, so builds
     * that reference `~/.m2` or an external SDK are untouched.
     *
     * An undefined variable makes the token unresolvable; such a token is allowed unless it also
     * names a credential artefact, so `%JAVA_HOME%\bin\mvn` keeps working while
     * `%SOMETHING%\.ssh\id_rsa` is still refused.
     */
    private fun argumentPathViolation(tokens: List<String>): String? {
        if (!pathGuardEnabled) return null
        for (token in tokens) {
            val candidate = pathCandidate(token) ?: continue
            val expanded = expandVariables(candidate)
            if (expanded == null) {
                if (SENSITIVE_PATH_KEYWORDS.any { candidate.lowercase().contains(it) }) {
                    return PATH_GUARD_PREFIX +
                        "'$token' names a credential location through an undefined variable"
                }
                continue
            }
            val ported = portableSeparators(expanded)
            val path = runCatching { Path.of(ported) }.getOrNull() ?: continue
            val resolved = if (path.isAbsolute) path.normalize()
            else canonicalBaseDir.resolve(ported).normalize()
            if (resolved.startsWith(canonicalBaseDir)) {
                // The lexical path is inside the workspace, but a link inside it may still resolve
                // into a credential location (module junctions are links too, so only the sensitive
                // roots are checked here, not "outside the workspace").
                if (!isWorkspaceOwned(resolved) &&
                    SensitivePaths.isRefused(resolved, sensitiveRoots, resolvedReadableRoots)
                ) {
                    return PATH_GUARD_PREFIX + "'$token' links to a credential location ($resolved); " +
                        ADVISORY_NOTE + "pass a workspace-relative path instead"
                }
                continue
            }
            if (SensitivePaths.isRefused(resolved, sensitiveRoots, resolvedReadableRoots)) {
                return PATH_GUARD_PREFIX + "'$token' resolves to a credential location ($resolved); " +
                    ADVISORY_NOTE + "pass a workspace-relative path instead"
            }
        }
        return null
    }

    /**
     * Rejects a literal absolute path argument that leaves the workspace while
     * [allowExternalAccess] is off.
     *
     * `coding.*` file tools cannot read outside the workspace, but the shell could: a
     * live run used `dir /s /b "D:\workspace\…"` and `git -C "D:\repo" show …` to hunt
     * for a fixture the file tools refused to read (2026-10-28). The boundary therefore
     * applies to arguments too, with deliberate exceptions so ordinary builds keep
     * working:
     * - standard toolchain locations (JDK, `~/.m2`, `~/.gradle`, `~/.cache`, temp,
     *   `%SystemRoot%`) stay readable;
     * - the readable roots ([ReadablePaths] — Browser4's data/checkout folders and any
     *   `browser4.agent.readPaths` entry) stay readable, because that is where the
     *   agent's own logs and data live;
     * - a token whose variables cannot be expanded is left alone (it is not a path we
     *   can judge);
     * - Windows `/s`, `/b` … switches are not paths.
     *
     * The decision asks for `fs.read`: an argument is where a command *reaches*, and only the
     * readable roots are in the read scope. A command that writes is still governed by its tier
     * (`allowDestructive`) and by the OS sandbox.
     *
     * Inline scripts (`python -c "…"`) are not parsed — this guard is about the shell's
     * own arguments, and a script is free to compute whatever it likes.
     */
    private fun workspaceBoundaryViolation(tokens: List<String>, advisory: Boolean = false): String? {
        if (!workspaceBoundaryEnabled) return null
        // The hard check is skipped when external access is on; the advisory pass runs *only* then,
        // because that is exactly the case where the boundary is bypassed and a fact is worth
        // recording. Neither pass ever records a write conclusion (see
        // [PermissionAudit.CODE_SCOPE_UNCERTAIN]).
        if (advisory) {
            if (!allowExternalAccess) return null
        } else if (allowExternalAccess) {
            return null
        }
        for (token in tokens) {
            if (isWindowsSwitch(token)) continue
            val candidate = pathCandidate(token) ?: continue
            val expanded = expandVariables(candidate) ?: continue
            val ported = portableSeparators(expanded)
            val path = runCatching { Path.of(ported) }.getOrNull() ?: continue
            val relativeEscape = !path.isAbsolute && ported.contains("..")
            if (!path.isAbsolute && !relativeEscape) continue
            // Separators are normalised before resolution: a token written for the other platform
            // must still name the location it means (see [portableSeparators]).
            val resolved = if (path.isAbsolute) path.normalize() else canonicalBaseDir.resolve(ported).normalize()
            // Credential locations stay the credential guard's business (so its switch
            // still governs them); this guard is about ordinary paths. The readable-root
            // relaxation is therefore applied here too: a path the credential guard allows to be
            // read must not be refused by the boundary.
            if (SensitivePaths.isRefused(resolved, sensitiveRoots, resolvedReadableRoots)) continue
            // The boundary decision is made by the shared Authorizer: workspace + mirrored
            // module roots + toolchain locations (and, for reads, the readable roots) are in
            // scope, and launchers are exempt (B3/B4).
            val authorizer = boundaryAuthorizer ?: fallbackBoundaryAuthorizer
            val decision = authorizer.decide(
                Request(
                    tool = SHELL_TOOL,
                    effects = setOf(Effect.FS_READ),
                    targets = listOf(Target.PathTarget(resolved)),
                    capabilityId = CodingComponents.CAP_SHELL_BOUNDARY,
                ),
            )
            if (decision is Decision.Deny) {
                if (advisory) {
                    PermissionAudit.writeEvent(
                        tool = SHELL_TOOL,
                        code = PermissionAudit.CODE_SCOPE_UNCERTAIN,
                        detail = "command argument '$token' resolves outside the workspace ($resolved) while " +
                            "external access is enabled; the string layer cannot tell whether it writes there",
                    )
                    return null
                }
                return PATH_GUARD_PREFIX + "'$token' resolves outside the workspace ($resolved); " +
                    ADVISORY_NOTE + "use a workspace-relative path or copy the input into the workspace"
            }
        }
        return null
    }

    /**
     * Resolves both separator styles, so a token written for the *other* platform still names the
     * location it means.
     *
     * The guard deliberately judges one command line for both shells — `%USERPROFILE%\.ssh\id_rsa`
     * is refused on Linux too — so a `\`-spelled path has to resolve like a path while the host is
     * POSIX as well. Without this, `"%JAVA_HOME%\bin\java"` expanded to `…/x64\bin\java`: one odd
     * path element, not under a toolchain root and not a launcher (its "extension" was
     * `x64\bin\java`), so the JDK itself was refused as "outside the workspace" on the Ubuntu CI
     * runner (2026-10-31 nightly). `/` is accepted on every platform, so the other direction needs
     * no special case.
     */
    private fun portableSeparators(path: String): String = path.replace('\\', '/')

    /**
     * Records, at execution time only, what an allowed command actually reached (B3/B5).
     *
     * Kept out of [validateCommand] on purpose: `coding.explain` and `isAllowed` are dry runs and
     * must not move a counter. Nothing here claims a write happened — the shell only knows how a
     * path was spelled.
     */
    private fun recordScopeAdvisories(command: String) {
        // Default configuration: both switches off, so there is nothing to observe and no reason to
        // tokenize every command.
        val network = networkAllowed()
        if (!allowExternalAccess && !network) return
        val tokens = tokenize(command)
        workspaceBoundaryViolation(tokens, advisory = true)
        if (!network) return
        val networkCommands = tokens.map { normalizeCommandName(it) }.filter { it in NETWORK_COMMANDS }.distinct()
        if (networkCommands.isEmpty()) return
        PermissionAudit.writeEvent(
            tool = SHELL_TOOL,
            code = PermissionAudit.CODE_NETWORK_TARGET,
            detail = "network command(s) executed: " + networkCommands.joinToString(", "),
        )
    }

    /** `/s`, `/b`, `/i` are switches on Windows, not POSIX paths. */
    private fun isWindowsSwitch(token: String): Boolean =
        IS_WINDOWS && WINDOWS_SWITCH.matches(token)

    /**
     * Fallback boundary [Authorizer] used when none was injected. Holds a single lexical-scope
     * grant over the workspace, its mirrored module roots ([MirrorRoots]) and the toolchain
     * locations (B4), with launchers exempt (B3) — plus the readable roots ([ReadablePaths]) for
     * reads only. The credential and protected-dir rails stay separate
     * ([argumentPathViolation], [protectedPathViolation]) — they are orthogonal guardrails,
     * not capability scope.
     *
     * Built lazily so the mirror scan runs once and only when a command is actually validated.
     */
    private val fallbackBoundaryAuthorizer: Authorizer by lazy {
        val roots = (
            listOf(canonicalBaseDir) + CodingComponents.toolchainRoots() + MirrorRoots.derive(baseDir) +
                additionalDirs.map { it.toAbsolutePath().normalize() }
            ).toSet()
        Authorizer(
            listOf(
                Capability(
                    id = CodingComponents.CAP_SHELL_BOUNDARY,
                    effects = setOf(Effect.FS_READ, Effect.FS_WRITE),
                    scope = Scope(
                        readPaths = roots + resolvedReadableRoots,
                        writePaths = roots,
                        launcherBasenames = LAUNCHER_BASENAMES,
                        executableExtensions = EXECUTABLE_EXTENSIONS,
                        canonicalize = false,
                    ),
                    source = CapabilitySource.PRESET,
                ),
            ),
        )
    }

    /**
     * Rejects any argument that names a workspace-protected directory (`.browser4`,
     * `.github/workflows`).
     *
     * The file tools already refuse to write there; the shell did not, so
     * `echo forged > .browser4/data/agent/agent-tasks.jsonl` rewrote the agent's own task ledger
     * and `cp pom.xml .github/workflows/ci.yml` replaced a CI definition without ever touching a
     * `coding.*` file tool. Redirection targets are inspected like any other argument, and both
     * reads and writes are refused: the shell has no business in the bookkeeping tree, and the
     * read tools are still available for it.
     */
    private fun protectedPathViolation(tokens: List<String>): String? {
        if (protectedRoots.isEmpty()) return null
        for (token in tokens) {
            val candidate = pathLikeValue(token) ?: continue
            val hit = protectedRootOf(candidate) ?: continue
            return PROTECTED_PATH_PREFIX +
                "'$token' is inside protected directory '${canonicalBaseDir.relativize(hit).toString().replace('\\', '/')}' — " +
                "the shell may not read or write it; use the coding.* file tools instead"
        }
        return null
    }

    /**
     * The protected root [candidate] lands in, or null.
     *
     * Checks the lexical form first so glob forms (`rm -rf .browser4/` plus a wildcard) and other
     * values that [Path.of] rejects are still caught, then the resolved absolute path for absolute
     * forms.
     */
    private fun protectedRootOf(candidate: String): Path? {
        val lexical = candidate.replace('\\', '/').removePrefix("./").trimStart('/')
        protectedDirs.firstOrNull { dir ->
            val d = dir.trim('/')
            lexical == d || lexical.startsWith("$d/") || lexical.startsWith("$d*") || lexical.startsWith("$d?")
        }?.let { dir -> return canonicalBaseDir.resolve(dir).normalize() }

        val path = runCatching { Path.of(candidate) }.getOrNull() ?: return null
        val resolved = if (path.isAbsolute) path.normalize() else canonicalBaseDir.resolve(candidate).normalize()
        return protectedRoots.firstOrNull { resolved.startsWith(it) }
    }

    /** Whether [candidate] resolves to a location inside the workspace (a real path check). */
    private fun isWorkspaceOwned(candidate: Path): Boolean = runCatching {
        if (Files.exists(candidate)) candidate.toRealPath().startsWith(canonicalBaseDir) else true
    }.getOrDefault(true)

    /**
     * The path-like part of [token], or null when the token cannot be a path
     * (bare flags, plain words, option names without a value).
     */
    private fun pathCandidate(token: String): String? {
        val value = pathLikeValue(token) ?: return null
        val looksLikePath = value.startsWith("~") || value.startsWith("%") || value.startsWith("$") ||
            value.contains('/') || value.contains('\\')
        return if (looksLikePath) value else null
    }

    /**
     * The value part of [token]: strips a shell redirection prefix (`>file`, `2>>file`), the
     * `--key=value` wrapper and surrounding quotes.
     */
    private fun pathLikeValue(token: String): String? {
        if (token.isEmpty()) return null
        var t = token
        REDIRECTION_PREFIX.find(t)?.let { m -> t = t.substring(m.range.last + 1) }
        if (t.startsWith("-")) {
            // `-Dkey=value`, `--file=value`: only the value can be a path.
            val eq = t.indexOf('=')
            if (eq < 0) return null
            t = t.substring(eq + 1)
        }
        t = t.trim('"', '\'').trimStart('>', '<', '&').trim()
        return t.ifEmpty { null }
    }

    /**
     * Expands `~`, `$VAR`, `${VAR}`, `$env:VAR` and `%VAR%`; returns null when any variable is
     * undefined (the result would be a guess).
     */
    private fun expandVariables(raw: String): String? {
        var unresolved = false
        var s = raw
        s = PS_ENV_VAR.replace(s) { m ->
            val v = System.getenv(m.groupValues[1])
            if (v == null) { unresolved = true; m.value } else v
        }
        s = WIN_ENV_VAR.replace(s) { m ->
            val v = System.getenv(m.groupValues[1])
            if (v == null) { unresolved = true; m.value } else v
        }
        s = POSIX_ENV_VAR.replace(s) { m ->
            val name = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val v = System.getenv(name)
            if (v == null) { unresolved = true; m.value } else v
        }
        if (s.startsWith("~")) s = System.getProperty("user.home") + s.substring(1)
        return if (unresolved) null else s
    }

    /**
     * Validates the command substitutions that survive segmentation.
     *
     * [splitSimpleCommands] lifts an *unquoted* `$(...)` / backtick body into its own segment, but
     * one inside double quotes stays in the segment text, so
     * `echo "$(cat ~/.ssh/id_rsa)"` was invisible to both the allow-list and the credential guard —
     * and the platform shell still runs it. Quoting follows the platform rules: a single-quoted
     * body is literal text, a double-quoted or unquoted one is a command.
     *
     * The check is applied on every platform because the same command line runs through
     * `cmd.exe` on Windows and `sh -c` elsewhere; refusing what `sh` would execute keeps the
     * decision portable.
     */
    private fun substitutionViolation(segment: String, depth: Int): String? {
        if (depth > MAX_INLINE_SCRIPT_DEPTH) {
            return "nested command substitution exceeds $MAX_INLINE_SCRIPT_DEPTH levels — refusing to guess"
        }
        var i = 0
        var inSingleQuote = false
        while (i < segment.length) {
            val c = segment[i]
            when {
                c == '\'' -> {
                    inSingleQuote = !inSingleQuote
                    i++
                }
                inSingleQuote -> i++
                c == '\\' -> i += 2
                // Arithmetic expansion is not a command: `$((x + 1))`.
                c == '$' && segment.startsWith("$((", i) -> {
                    val end = segment.indexOf("))", i)
                    i = if (end < 0) segment.length else end + 2
                }
                c == '$' && i + 1 < segment.length && segment[i + 1] == '(' -> {
                    val end = matchingParen(segment, i + 1)
                    val inner = if (end < 0) segment.substring(i + 2) else segment.substring(i + 2, end)
                    validateSegments(inner, depth + 1)?.let { return "command substitution rejected — $it" }
                    i = if (end < 0) segment.length else end + 1
                }
                c == '`' -> {
                    val end = segment.indexOf('`', i + 1)
                    val inner = if (end < 0) segment.substring(i + 1) else segment.substring(i + 1, end)
                    validateSegments(inner, depth + 1)?.let { return "command substitution rejected — $it" }
                    i = if (end < 0) segment.length else end + 1
                }
                else -> i++
            }
        }
        return null
    }

    /**
     * Refuse a process-termination command that would kill the runtime executing this task.
     *
     * The backend JVM is the process running this shell, and the CLI/agent children are
     * part of the same runtime: a task that kills them ends itself with no result. A live
     * run did exactly that — after a navigation failure it ran
     * `taskkill /PID <backend> /F /T`, the backend died, and the task had no report
     * (2026-10-29 Gate, F12). `b4.run` already refuses the CLI's lifecycle commands, so
     * this closes the `coding.shell` route.
     *
     * Only runtime targets are refused: an explicit pid that is not the backend, or a
     * non-runtime image, still passes — killing a stray dev server is legitimate.
     */
    /**
     * A task must not rebuild or restart the backend it is running inside
     * (self-development isolation plan §D8): those commands rewrite the runtime
     * bundle that is executing this very task. `coding.mvnBuild`/`coding.verify`
     * compile and test in the workspace and stay allowed.
     *
     * @return a human-readable refusal, or null when the command is fine
     */
    private fun bootstrapViolation(command: String, tokens: List<String>, index: Int): String? {
        if (!forbidBootstrap) return null
        val base = normalizeCommandName(tokens[index])
        if (base == "b4w") {
            val sub = tokens.getOrNull(index + 1)?.trim('\'', '"')?.lowercase()
            if (sub in setOf("build", "stop", "install", "uninstall", "upgrade", "recover")) {
                return "'b4w $sub' $VETO_BOOTSTRAP — " +
                    "operator work, not a task step"
            }
        }
        val flat = command.replace('\\', '/').lowercase()
        val scripts = listOf(
            "bin/build.ps1", "bin/build/build.ps1", "build-runtime-bundle.ps1", "bin/recover.ps1",
        )
        if (scripts.any { flat.contains(it) }) {
            return "$VETO_BOOTSTRAP_SCRIPT from a task — " +
                "it rewrites the runtime this task is running inside"
        }
        return null
    }

    private fun processKillViolation(command: String, tokens: List<String>): String? {
        if (tokens.isEmpty()) return null
        val base = normalizeCommandName(tokens[0])
        if (base !in PROCESS_KILL_COMMANDS) return null

        val ownPid = ProcessHandle.current().pid().toString()
        val lower = command.lowercase()
        val pidArguments = PID_ARGUMENT.findAll(command).map { it.groupValues[1] }.toSet()
        val targetsOwnPid = pidArguments.contains(ownPid) ||
            tokens.drop(1).any { it.trim('"', '\'').trimStart('-', '/') == ownPid }
        val targetsRuntimeName = lower.contains("browser4") ||
            Regex("""(?<![\w-])java(?:w)?(?:\.exe)?(?![\w-])""").containsMatchIn(lower)
        if (!targetsOwnPid && !targetsRuntimeName) return null

        return "'$base' $VETO_BACKEND_KILL (pid $ownPid) or a JVM that may be " +
            "it — coding.shell refuses to kill the runtime executing this task. Stop stray " +
            "processes from outside the task, or name an explicit pid that is not the backend."
    }

    /**
     * The refusal for a program this shell never runs, whatever the flags say.
     *
     * Shared by [validateSimpleCommandTier] and [handOffVetoViolation], so the message a
     * handed-off line gets is byte-for-byte the one a normal command gets.
     */
    private fun forbiddenCommandViolation(baseCommand: String): String =
        "'$baseCommand' is disabled — $VETO_WORKSPACE_LINK"

    private fun validateSimpleCommand(segment: String, depth: Int): String? {
        val tierViolation = validateSimpleCommandTier(segment, depth)
        if (tierViolation != null) return tierViolation
        // The command itself is permitted: the workspace boundary now applies to its
        // arguments. Checking after the tier rules keeps their specific messages
        // (`mklink` is disabled, credential/protected paths) authoritative.
        return workspaceBoundaryViolation(tokenize(segment))
    }

    private fun validateSimpleCommandTier(segment: String, depth: Int): String? {
        val tokens = tokenize(segment)
        if (tokens.isEmpty()) return EMPTY_COMMAND_VIOLATION

        // Argument-path guard: the working directory is contained, but nothing stopped an
        // argument like `~/.ssh/id_rsa` from naming a credential location (plan P0-3).
        argumentPathViolation(tokens)?.let { return it }
        // Protected-subtree guard: the file tools refuse `.browser4` / `.github/workflows`, and the
        // shell must not be the way around them.
        protectedPathViolation(tokens)?.let { return it }
        // Command substitutions inside quotes are commands too.
        substitutionViolation(segment, depth)?.let { return it }

        // Skip leading env assignments (VAR=value cmd ...).
        var index = 0
        while (index < tokens.size && ENV_ASSIGNMENT.matches(tokens[index])) index++
        if (index >= tokens.size) return EMPTY_COMMAND_VIOLATION

        val rawToken = tokens[index]
        val baseCommand = normalizeCommandName(rawToken)
        if (baseCommand.isEmpty()) return EMPTY_COMMAND_VIOLATION

        // Inline script (`sh -c "..."`, `pwsh -Command "..."`, `cmd /c ...`):
        // the wrapper and the inner script must both be validated. Checking only the
        // body let `sh -c "mvn ..."` through the build-only profile, where `sh` is
        // precisely the arbitrary-execution surface BUILD_COMMANDS removes.
        inlineScript(tokens.subList(index, tokens.size))?.let { inner ->
            if (buildOnly && !devToolAllowed(baseCommand)) {
                return "'$baseCommand' is not allowed in the build-only profile " +
                    "(inline-script hosts are not build tools)"
            }
            validateSegments(inner, depth + 1)?.let { violation ->
                return "inline script via '$baseCommand' rejected — $violation"
            }
            return null
        }

        // Absolute vetoes that no flag, allow-list entry or PATH lookup can lift.
        if (baseCommand in FORBIDDEN_COMMANDS) {
            return forbiddenCommandViolation(baseCommand)
        }
        // A task must not destroy the runtime that executes it. `b4.run` already refuses the
        // CLI's own lifecycle commands; `coding.shell` was the remaining way in — a live run
        // ended itself with `taskkill /PID <backend> /F /T` (2026-10-29 Gate, F12).
        processKillViolation(segment, tokens.subList(index, tokens.size))?.let { return it }
        // …and it must not rebuild that runtime either (isolation plan §D8).
        bootstrapViolation(segment, tokens, index)?.let { return it }
        // Explicit deny — absolute veto
        if (baseCommand in explicitDeny) return "'$baseCommand' $VETO_EXPLICIT_DENY"
        for (pattern in denyPatterns) {
            if (pattern.containsMatchIn(segment)) return "$VETO_DENY_PATTERN${pattern.pattern}"
        }
        if (baseCommand in explicitAllow) return null
        for (pattern in allowPatterns) {
            if (pattern.containsMatchIn(segment)) return null
        }

        // cmd.exe flow control. `if` is parsed as a CONDITION PREFIX: the payload behind it is
        // re-validated by the same tier and boundary rules a top-level command gets, so supporting
        // the shape does not create a first-token blind spot. Only the shallow forms are parsed
        // (one command, optionally in parentheses with one `else`) — anything deeper needs a cmd
        // grammar, and a half-parser that validates the wrong token is worse than handing the line
        // to the platform shell, which is what [validateCommand] does with these markers.
        // `for` is not parsed: its body is a loop over a command substitution, which the string
        // layer cannot judge.
        if (isWindowsHost()) {
            if (baseCommand == "if") {
                return conditionalViolation(tokens.subList(index, tokens.size), depth)
            }
            if (baseCommand in CMD_CONTROL_KEYWORDS) {
                return UNPARSED_PREFIX +
                    "'$baseCommand' is cmd.exe flow control coding.shell does not parse"
            }
        }
        // `set` (cmd built-in): allowed without arguments or with NAME=value
        // assignments only — `set /p`, `set /a` and other forms are not.
        if (isWindowsHost() && baseCommand == "set") {
            val setArgs = tokens.drop(index + 1)
            val assignmentsOnly = setArgs.isNotEmpty() &&
                setArgs.all { ENV_ASSIGNMENT.matches(it.trim('"')) }
            if (setArgs.isEmpty() || assignmentsOnly) return null
            return "'set' is only allowed with no arguments or with NAME=value assignments " +
                "(e.g. `set PYTHONIOENCODING=utf-8`)"
        }

        // `mkdir`/`md`: creating a directory inside the workspace is ordinary work (the file
        // tools offer `coding.mkdir` for it), and the path boundary below still applies to
        // the argument. Guarded before the category checks because the Windows builtin has no
        // PATH entry and would otherwise fall through to the generic refusal.
        if (baseCommand in CREATE_DIR_COMMANDS) return null

        // Category checks use the normalized name, so platform launcher
        // suffixes resolve to their tool: `mvnw.cmd` → `mvnw`, `python3.exe` →
        // `python3`. Without this, Windows builds were rejected while the same
        // command passed on Linux (plan P0.4 / eval K4).
        if (baseCommand in SAFE_COMMANDS) return null
        if (devToolAllowed(baseCommand)) return null
        if (networkAllowed() && baseCommand in NETWORK_COMMANDS) return null
        if (allowDestructive && baseCommand in DESTRUCTIVE_COMMANDS) return null

        // Network and destructive tools are VETOES, not fall-throughs: the PATH
        // fallback below must not resurrect a tool whose category was disabled.
        // Without this, `curl` (installed on almost every host) was allowed even
        // with network access off, which made the whole network tier cosmetic.
        if (baseCommand in NETWORK_COMMANDS) {
            return "'$baseCommand' $VETO_NETWORK_DISABLED"
        }
        if (baseCommand in DESTRUCTIVE_COMMANDS) {
            return "'$baseCommand' $VETO_DESTRUCTIVE_DISABLED"
        }

        // Explicit path form (e.g. /usr/bin/git, ./script.sh, C:\repo\mvnw.cmd):
        // historically allowed with dev tools; an absolute path the operator
        // spelled out is not the injection vector — the *unexpected* token is.
        // Under the build-only profile this fallback is closed, otherwise
        // `./anything.sh` would bypass BUILD_COMMANDS.
        if (isPathLike(rawToken) && allowDevTools && !buildOnly) return null

        // PATH fallback — command not in any set but found on PATH
        if (allowDevTools && !buildOnly && isCommandOnPath(baseCommand)) return null

        // `agent run --unrestricted`: the tier is the shell's *spelling* rule, not a rail this flag
        // keeps. A program outside SAFE/DEV that is not an executable on PATH — a PowerShell cmdlet
        // such as `Get-Content`, or `wmic` — is handed to the platform shell like any other: the
        // promise is that the task runs with the caller's own OS permissions, and the same body
        // already runs unexamined through `powershell -File`. Keeping it refused also contradicted
        // the model-facing prompt, which says inline scripts run as written (2026-11-14 run).
        //
        // Nothing is skipped by this: every veto above (blocked patterns, explicit deny, deny
        // patterns, credential/protected paths, FORBIDDEN commands, the runtime vetoes) already ran,
        // and [validateSimpleCommand] still applies the workspace boundary afterwards. The
        // build-only profile is exempt — that shell belongs to `coding.mvnBuild`/`verify`, not to
        // the model, so unrestricted must not widen it.
        if (unrestricted && !buildOnly) return null

        return "command '$baseCommand' is not allowed (safe=${baseCommand in SAFE_COMMANDS}, " +
            "dev=${devToolAllowed(baseCommand)}, " +
            "network=${baseCommand in NETWORK_COMMANDS && networkAllowed()}, " +
            "destructive=${baseCommand in DESTRUCTIVE_COMMANDS && allowDestructive})"
    }

    // ------------------------------------------------------------------
    // cmd.exe conditionals (`if ...`)
    // ------------------------------------------------------------------

    /**
     * Validate a cmd.exe conditional as a **condition prefix** plus its payload.
     *
     * The tier model answers "is the first token an allowed program?", which has no answer for a
     * control-flow keyword: whitelisting `if` as a *program* would let its payload through
     * unchecked (`if exist x (del …)` is the classic hole). Parsing it as a prefix keeps the model
     * intact — the condition's own paths were already checked by the caller's argument and
     * protected-path guards (they run over every token), and each payload command is re-validated
     * by the same tier and boundary rules a top-level command gets.
     *
     * Supported shapes, one command per group:
     *
     * ```
     * if [not] exist <path> <cmd>
     * if [/i] [not] defined <var> <cmd>
     * if [not] errorlevel <n> <cmd>
     * if [/i] [not] <lhs>==<rhs> <cmd>
     * if <condition> (<cmd>)
     * if <condition> (<cmd>) else (<cmd>)
     * ```
     *
     * Everything else — nested blocks, `else if`, `&`/`|` inside a block, `goto`/`call` payloads —
     * is an [UNPARSED_PREFIX] marker: those are exactly the shapes that would need a cmd grammar,
     * and guessing at them would validate the wrong token while looking successful. [validateCommand]
     * hands such a line to the platform shell instead.
     *
     * @return null when the statement is supported and every payload passed, else a violation
     */
    private fun conditionalViolation(tokens: List<String>, depth: Int): String? {
        if (depth > MAX_INLINE_SCRIPT_DEPTH) {
            return UNPARSED_PREFIX + "nested conditionals exceed $MAX_INLINE_SCRIPT_DEPTH levels"
        }

        var i = 1 // skip `if`
        if (tokens.getOrNull(i)?.equals("/i", ignoreCase = true) == true) i++
        if (tokens.getOrNull(i)?.equals("not", ignoreCase = true) == true) i++

        val keyword = tokens.getOrNull(i)?.lowercase()
        when (keyword) {
            "exist", "defined", "errorlevel" -> {
                val operand = tokens.getOrNull(i + 1)
                    ?: return UNPARSED_PREFIX + "'if $keyword' is missing its operand"
                if (keyword == "errorlevel" && operand.any { !it.isDigit() }) {
                    return UNPARSED_PREFIX + "'if errorlevel' expects a number, not '$operand'"
                }
                i += 2
            }
            else -> {
                val expr = keyword ?: return UNPARSED_PREFIX + "'if' is missing its condition"
                // `<a>==<b>` is the remaining supported condition form.
                if (!expr.contains("==")) {
                    return UNPARSED_PREFIX + "unsupported 'if' condition '$expr' — supported: exist, " +
                        "defined, errorlevel and <a>==<b>"
                }
                i += 1
            }
        }

        val body = tokens.subList(i, tokens.size)
        if (body.isEmpty()) {
            return UNPARSED_PREFIX + "'if' needs a command after its condition"
        }
        val groups = splitConditionalBody(body) ?: return UNPARSED_PREFIX +
            "unsupported 'if' body — one command, optionally `<cond> (<cmd>)` with one " +
            "`else (<cmd>)`; nested blocks, 'else if' and '&'/'|' inside a block are not parsed"

        for (group in groups) {
            val command = renderCommand(group)
            val base = extractBaseCommand(command)
            // A nested conditional re-enters the first-token blind spot one level down, and a label
            // jump leaves the linear check entirely (`goto` lands on a line this validator never
            // saw), so both are refused rather than validated half-way.
            if (base in CMD_CONTROL_KEYWORDS || base == "goto" || base == "call") {
                return UNPARSED_PREFIX + "'if' body starts with '$base' — control-flow and label-jump " +
                    "payloads are not parsed; put the block in a script file run by path"
            }
            validateSimpleCommand(command, depth + 1)?.let { return it }
        }
        return null
    }

    /**
     * The commands an `if` body may run, or null when the body is not one of the supported shapes.
     *
     * Parentheses are separated from their neighbours first (`(echo x)` tokenizes as `(echo` and
     * `x)`), and a token with a parenthesis in the middle — a path such as `a(b).txt` — makes the
     * body unsupported rather than guessed at.
     */
    private fun splitConditionalBody(body: List<String>): List<List<String>>? {
        val expanded = expandParentheses(body) ?: return null
        if (expanded.none { it == "(" || it == ")" }) {
            // Bare form: exactly one command. A bare `else` has no block to attach to.
            if (expanded.any { it.equals("else", ignoreCase = true) }) return null
            return listOf(expanded)
        }
        if (expanded.first() != "(") return null
        val firstEnd = matchingParenToken(expanded, 0) ?: return null
        val first = expanded.subList(1, firstEnd)
        if (first.isEmpty()) return null
        val rest = expanded.subList(firstEnd + 1, expanded.size)
        if (rest.isEmpty()) return listOf(first)
        if (!rest[0].equals("else", ignoreCase = true)) return null
        // `else if …` has no parenthesis here; a trailing `else (<cmd>)` has one.
        if (rest.size < 4 || rest[1] != "(") return null
        val secondEnd = matchingParenToken(rest, 1) ?: return null
        if (secondEnd != rest.size - 1) return null
        val second = rest.subList(2, secondEnd)
        if (second.isEmpty()) return null
        return listOf(first, second)
    }

    /**
     * Split leading `(` and trailing `)` off each token so `(echo x)` and `( echo x )` parse the
     * same. Returns null when a parenthesis sits in the middle of a token — that is a path or an
     * expression this parser refuses to guess about.
     */
    private fun expandParentheses(tokens: List<String>): List<String>? {
        val out = mutableListOf<String>()
        for (token in tokens) {
            var value = token
            while (value.startsWith("(")) {
                out += "("
                value = value.substring(1)
            }
            val closers = value.takeLastWhile { it == ')' }.length
            if (closers > 0) value = value.dropLast(closers)
            if (value.contains('(') || value.contains(')')) return null
            if (value.isNotEmpty()) out += value
            repeat(closers) { out += ")" }
        }
        return out
    }

    /** Index of the `)` matching the `(` at [openIndex], or null when the group is unbalanced. */
    private fun matchingParenToken(tokens: List<String>, openIndex: Int): Int? {
        if (tokens.getOrNull(openIndex) != "(") return null
        var depth = 0
        for (index in openIndex until tokens.size) {
            when (tokens[index]) {
                "(" -> depth++
                ")" -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    /**
     * Rebuild a command line from already-tokenized words so it can go through [validateSimpleCommand].
     *
     * A word that held whitespace (a quoted argument) is quoted again — otherwise
     * `echo "a b"` would replay as three words and a path guard could judge the wrong token.
     */
    private fun renderCommand(tokens: List<String>): String =
        tokens.joinToString(" ") { token ->
            if (token.any { it.isWhitespace() }) "\"$token\"" else token
        }

    /**
     * Split a command line into the simple commands it executes, in order.
     *
     * Handles `&&`, `||`, `;`, `|`, `&`, newlines and command substitution
     * (`$(...)` and backticks, whose bodies are emitted as their own segments).
     * Quoting is respected, so `grep "a|b" file` stays one segment.
     */
    internal fun splitSimpleCommands(command: String): List<String> {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var i = 0

        fun flush() {
            val text = current.toString().trim()
            if (text.isNotEmpty()) segments += text
            current.setLength(0)
        }

        while (i < command.length) {
            val c = command[i]
            if (quote != null) {
                if (c == quote) quote = null
                current.append(c)
                i++
                continue
            }
            when {
                c == '\'' || c == '"' -> {
                    quote = c
                    current.append(c)
                    i++
                }
                // Arithmetic expansion is not a command: keep it as text.
                c == '$' && command.startsWith("$((", i) -> {
                    val end = command.indexOf("))", i)
                    val stop = if (end < 0) command.length else end + 2
                    current.append(command, i, stop)
                    i = stop
                }
                // Command substitution — the body is a command of its own.
                c == '$' && i + 1 < command.length && command[i + 1] == '(' -> {
                    val end = matchingParen(command, i + 1)
                    val inner = if (end < 0) command.substring(i + 2) else command.substring(i + 2, end)
                    segments += splitSimpleCommands(inner)
                    current.append(' ')
                    i = if (end < 0) command.length else end + 1
                }
                c == '`' -> {
                    val end = command.indexOf('`', i + 1)
                    val inner = if (end < 0) command.substring(i + 1) else command.substring(i + 1, end)
                    segments += splitSimpleCommands(inner)
                    current.append(' ')
                    i = if (end < 0) command.length else end + 1
                }
                c == '\n' || c == '\r' -> {
                    flush()
                    i++
                }
                c == '&' && i + 1 < command.length && command[i + 1] == '&' -> {
                    flush(); i += 2
                }
                c == '|' && i + 1 < command.length && command[i + 1] == '|' -> {
                    flush(); i += 2
                }
                c == '|' && i + 1 < command.length && command[i + 1] == '&' -> {
                    flush(); i += 2
                }
                // `2>&1`, `1>&2`, `>&1`: the ampersand belongs to a redirection, not to
                // command chaining. Splitting on it turned the descriptor into a command —
                // `python x.py > out.txt 2>&1` was refused with "command '1' is not
                // allowed" (observed 2026-10-28 rerun, 9 times in one run).
                c == '&' && isRedirectionAmpersand(command, i, current) -> {
                    current.append(c)
                    i++
                }
                c == '|' || c == ';' || c == '&' -> {
                    flush(); i++
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        flush()
        return segments.filter { it.isNotBlank() }
    }

    /**
     * True when the `&` at [index] continues a descriptor redirection (`2>&1`, `>&2`).
     *
     * The splitter treats `&` as command chaining, so `python x.py > out.txt 2>&1` used to
     * become the segments `python x.py > out.txt 2>` and `1` — and `1` is not an allowed
     * command (observed 2026-10-28 rerun: 9 such refusals). A redirection `&` follows a
     * `>`/`<` (optionally with a descriptor digit) and is followed by a descriptor digit
     * or `-`.
     */
    private fun isRedirectionAmpersand(command: String, index: Int, pending: StringBuilder): Boolean {
        val next = command.getOrNull(index + 1) ?: return false
        if (!next.isDigit() && next != '-') return false
        val text = pending.toString().trimEnd()
        if (!text.endsWith(">") && !text.endsWith("<")) return false
        val before = text.dropLast(1)
        // `2>&1` (digit descriptor) or `>&1` (no descriptor); `a > b` has no `&` here anyway.
        return before.isEmpty() || before.last().isDigit() || before.last().isWhitespace()
    }

    /** Index of the `)` matching the `(` at [openIndex], honoring nesting and quotes. */    private fun matchingParen(text: String, openIndex: Int): Int {
        var depth = 0
        var quote: Char? = null
        var i = openIndex
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else {
                when (c) {
                    '\'', '"' -> quote = c
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return -1
    }

    /**
     * Tokenize one simple command, respecting single/double quotes.
     * Quotes are stripped; the contents become a single token.
     */
    internal fun tokenize(command: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (c in command) {
            when {
                quote != null && c == quote -> quote = null
                quote != null -> current.append(c)
                c == '\'' || c == '"' -> quote = c
                c.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.setLength(0)
                    }
                }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    /**
     * Inner script of an inline shell invocation, or null when [tokens] is not
     * one. `sh|bash|zsh|dash -c <script>`, `pwsh|powershell -c|-Command <script>`,
     * `cmd /c|/k <script>`.
     */
    internal fun inlineScript(tokens: List<String>): String? {
        if (tokens.isEmpty()) return null
        val base = normalizeCommandName(tokens[0])
        val flags = when (base) {
            "sh", "bash", "zsh", "dash" -> setOf("-c")
            "pwsh", "powershell" -> setOf("-c", "-command", "/c", "/command")
            "cmd" -> setOf("/c", "/k")
            else -> return null
        }
        val flagIndex = tokens.indexOfFirst { it.lowercase() in flags }
        if (flagIndex < 0 || flagIndex + 1 >= tokens.size) return null
        return tokens.subList(flagIndex + 1, tokens.size).joinToString(" ")
    }

    /**
     * Normalized base-command name used for allow-list membership:
     * basename, lowercase, without a Windows launcher / script suffix
     * (`mvnw.cmd` → `mvnw`, `python3.exe` → `python3`, `/usr/bin/git` → `git`).
     */
    internal fun normalizeCommandName(token: String): String {
        var name = token.trim().trim('\'', '"', '\\')
        if (name.isEmpty()) return ""
        name = name.substringAfterLast('/').substringAfterLast('\\').lowercase()
        for (suffix in EXECUTABLE_SUFFIXES) {
            if (name.endsWith(suffix) && name.length > suffix.length) {
                name = name.removeSuffix(suffix)
                break
            }
        }
        return name
    }

    /** True when the raw token is spelled as a path rather than a bare name. */
    private fun isPathLike(token: String): Boolean {
        val t = token.trim().trim('\'', '"')
        return t.startsWith("/") || t.startsWith("./") || t.startsWith("../") ||
            t.startsWith(".\\") || t.startsWith("..\\") || t.isAbsolutePath() ||
            t.contains('/') || t.contains('\\')
    }

    private fun String.isAbsolutePath(): Boolean =
        length >= 3 && this[1] == ':' && (this[2] == '\\' || this[2] == '/')

    private fun extractBaseCommand(command: String): String {
        val segments = splitSimpleCommands(command)
        val first = segments.firstOrNull() ?: return ""
        val tokens = tokenize(first)
        var index = 0
        while (index < tokens.size && ENV_ASSIGNMENT.matches(tokens[index])) index++
        if (index >= tokens.size) return ""
        return normalizeCommandName(tokens[index])
    }

    private fun isCommandOnPath(command: String): Boolean {
        return try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val checkCmd = if (isWindows) "where $command" else "which $command"
            val pb = if (isWindows) ProcessBuilder("cmd.exe", "/c", checkCmd)
            else ProcessBuilder("sh", "-c", checkCmd)
            pb.redirectErrorStream(true)
            val process = pb.start()
            process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    // Command execution
    // ------------------------------------------------------------------

    private fun runCommand(
        sessionId: String,
        command: String,
        timeoutSeconds: Long,
        workDir: File,
        extraEnv: Map<String, String>,
    ): ShellResult {
        val startTime = System.currentTimeMillis()
        val isWindows = System.getProperty("os.name").lowercase().contains("win")

        val commandLine = if (isWindows) {
            listOf("cmd.exe", "/c", command)
        } else {
            listOf("sh", "-c", command)
        }

        // C1/C2: both execution paths share one environment construction and one sandbox seam.
        // Explicitly declared variables (per-command env + session env) survive the whitelist;
        // nothing else from the backend process does, so long-lived credentials are not inherited.
        val environment = SandboxEnvironment.build(System.getenv(), sessionEnv + extraEnv)
        val workspace = runCatching { baseDir.toRealPath() }
            .getOrElse { baseDir.toAbsolutePath() }
        val spec = (sandbox ?: ExecutionSandboxes.current()).prepare(
            ProcessSpec(commandLine, workDir.toPath(), environment),
            workspace,
            networkAllowed(),
            additionalDirs.map { it.toAbsolutePath().normalize() },
        )

        val process = ProcessLauncher.start(spec, redirectErrorStream = false)

        // Decode the raw bytes ourselves: the stream's default charset is the JVM
        // default (UTF-8), which turns cmd.exe's code-page output into U+FFFD.
        val stdoutFuture = CompletableFuture.supplyAsync {
            process.inputStream.use { decodeProcessOutput(it.readBytes()) }
        }
        val stderrFuture = CompletableFuture.supplyAsync {
            process.errorStream.use { decodeProcessOutput(it.readBytes()) }
        }

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        val durationMs = System.currentTimeMillis() - startTime

        if (!completed) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
            val stdout = stdoutFuture.getNow("")
            val stderr = stderrFuture.getNow("")
            return ShellResult(
                sessionId = sessionId,
                command = command,
                exitCode = -1,
                stdout = truncateOutput(stdout, command),
                stderr = truncateOutput(stderr, command),
                durationMs = durationMs,
                timedOut = true,
            )
        }

        val stdout = awaitCapturedOutput(stdoutFuture, "stdout", command)
        val stderr = awaitCapturedOutput(stderrFuture, "stderr", command)
        val exitCode = process.exitValue()
        val out = truncateOutput(stdout.text, command)
        var err = truncateOutput(stderr.text, command)
        val openStreams = buildList {
            if (stdout.incomplete) add("stdout")
            if (stderr.incomplete) add("stderr")
        }
        if (openStreams.isNotEmpty()) {
            // The command itself exited, so the verdict stands; only the capture is partial.
            // Name the cause instead of reporting an empty stream as an empty answer. The note
            // leads because truncateOutput keeps the head of non-diagnostic output.
            err = truncateOutput(
                "[browser4] ${openStreams.joinToString("/")} stayed open after the command " +
                    "exited — a detached child process still holds the pipe; captured output " +
                    "may be incomplete.\n" + err,
                command,
            )
        }

        // Plan P3-3: an OS-level denial becomes a structured refusal for the model; the raw bytes
        // were logged by the translator, so they stay available for the audit trail.
        val denial = if (exitCode != 0) SandboxDenials.envelope("coding.shell", command, err + "\n" + out) else null

        return ShellResult(
            sessionId = sessionId,
            command = command,
            exitCode = exitCode,
            stdout = out,
            stderr = denial ?: err,
            durationMs = durationMs,
        )
    }

    /** One captured stream: its text plus whether the pipe reached EOF. */
    private data class CapturedStream(val text: String, val incomplete: Boolean)

    /**
     * Collect one captured stream after the process has exited.
     *
     * An unbounded [CompletableFuture.get] blocks until the pipe reports EOF, and EOF only
     * arrives when *every* writer closes it. A detached grandchild that inherited the pipe keeps
     * the write end open after the command itself exits — a `browser4-cli` that auto-started a
     * backend server, an AOT-training JVM, or any `start`ed helper. A live run hung on exactly
     * that: `browser4-cli eval ... > out.json 2>&1` finished (the redirect file was complete),
     * yet the shell never returned because the spawned server still held its inherited pipe.
     *
     * The process has already exited here, so a short grace period covers normal flushing; past
     * it the capture is marked [CapturedStream.incomplete] instead of blocking the agent forever.
     */
    private fun awaitCapturedOutput(
        future: CompletableFuture<String>,
        stream: String,
        command: String,
    ): CapturedStream {
        return try {
            CapturedStream(future.get(OUTPUT_CLOSE_GRACE_SECONDS, TimeUnit.SECONDS), false)
        } catch (e: TimeoutException) {
            // cancel() cannot interrupt a reader blocked on a native pipe read; the capture
            // thread is a common-pool daemon and will finish when the holder finally exits.
            future.cancel(true)
            logger.warn(
                "coding.shell {} stayed open after '{}' exited — a detached child process " +
                    "still holds the pipe; captured output may be incomplete",
                stream,
                command,
            )
            CapturedStream(future.getNow(""), true)
        }
    }

    private fun isDiagnosticOutputCommand(command: String): Boolean {
        for (segment in splitSimpleCommands(command)) {
            val tokens = tokenize(segment)
            var index = 0
            while (index < tokens.size && ENV_ASSIGNMENT.matches(tokens[index])) index++
            if (index >= tokens.size) continue
            if (DIAGNOSTIC_COMMAND.containsMatchIn(tokens[index])) return true
        }
        return false
    }

    /**
     * Bound the captured output.
     *
     * Cut on code-point boundaries ([SafeText]): a plain `take`/`takeLast` can
     * split a surrogate pair, and the resulting lone surrogate is not encodable
     * as UTF-8 — it used to poison every later prompt/trace write of the run.
     */
    internal fun truncateOutput(output: String, command: String): String {
        if (output.length <= MAX_OUTPUT_CHARS) return output
        // Compilers, tests and linters put the verdict — and the errors that
        // matter — at the END of the output. Head-only truncation kept the
        // banner and dropped the diagnostics (plan P1.5).
        if (isDiagnosticOutputCommand(command)) {
            val headLen = DIAGNOSTIC_HEAD_CHARS.coerceAtMost(MAX_OUTPUT_CHARS / 4)
            val tailLen = MAX_OUTPUT_CHARS - headLen
            return SafeText.take(output, headLen) +
                "\n... (output truncated: kept the first $headLen and the last $tailLen of ${output.length} chars) ...\n" +
                SafeText.takeLast(output, tailLen)
        }
        return SafeText.take(output, MAX_OUTPUT_CHARS) + "\n... (output truncated at $MAX_OUTPUT_CHARS chars) ..."
    }

    private fun formatResult(result: ShellResult): String {
        val status = if (result.success) "SUCCESS" else "FAILED"
        return buildString {
            appendLine("Session: ${result.sessionId}")
            appendLine("Status: $status")
            appendLine("Exit Code: ${result.exitCode}")
            appendLine("Duration: ${result.durationMs}ms")
            if (result.timedOut) appendLine("⚠️ Command timed out")
            if (result.stdout.isNotBlank()) {
                appendLine("--- stdout ---")
                appendLine(result.stdout)
            }
            if (result.stderr.isNotBlank()) {
                appendLine("--- stderr ---")
                appendLine(result.stderr)
            }
        }.trimEnd()
    }

    // ------------------------------------------------------------------
    // Convenience methods
    // ------------------------------------------------------------------

    /**
     * Execute a git command in the workspace.
     */
    suspend fun git(args: String, timeoutSeconds: Long = 60): String =
        execute("git $args", timeoutSeconds)

    /**
     * Check if a command/program is available.
     */
    fun isAvailable(command: String): Boolean = isCommandOnPath(command)

    /**
     * List available development tools detected on PATH.
     *
     * Reports the tier this instance actually runs at, so `coding.toolsDetect` cannot advertise
     * interpreters to a build-only shell.
     */
    fun detectAvailableTools(): Set<String> {
        val tier = if (buildOnly) BUILD_COMMANDS else DEV_COMMANDS
        return tier.filter { isCommandOnPath(it) }.toSet()
    }

    /**
     * Detect the project type in the workspace by looking for build files.
     */
    fun detectProjectType(): String {
        val files = mutableListOf<String>()
        try {
            java.nio.file.Files.walk(canonicalBaseDir, 2).use { stream ->
                stream.filter { !java.nio.file.Files.isDirectory(it) }
                    .map { it.fileName.toString() }
                    .forEach { files.add(it) }
            }
        } catch (_: Exception) {}

        return buildString {
            if (files.any { it == "Cargo.toml" }) append("rust, ")
            if (files.any { it == "pom.xml" }) append("maven, ")
            if (files.any { it.endsWith(".gradle") || it.endsWith(".gradle.kts") }) append("gradle, ")
            if (files.any { it == "package.json" }) append("node, ")
            if (files.any { it == "pyproject.toml" || it == "setup.py" }) append("python, ")
            if (files.any { it == "go.mod" }) append("go, ")
            if (files.any { it.endsWith(".sln") || it.endsWith(".csproj") }) append("dotnet, ")
            if (files.any { it == "CMakeLists.txt" }) append("cmake, ")
            if (files.any { it == "Makefile" }) append("make, ")
            if (files.any { it == "Dockerfile" }) append("docker, ")
        }.removeSuffix(", ").ifEmpty { "unknown" }
    }
}

// ---------------------------------------------------------------------------
// v0.3 structured exceptions — the shell's typed answer to "where am I allowed
// to write?" Replaces the previous `null` / silent-fallback ambiguity so
// S1/S2 callers (CodingToolExecutor, ToolExecutionCoordinator) can emit a
// stable "[fail]" without string-matching.
// ---------------------------------------------------------------------------

/** The requested working directory is lexically outside the shell's allowed
 *  root (and [allowExternalAccess] is false). The message carries both paths
 *  so the caller can surface a actionable hint. */
class WorkspaceEscapeException(
    val requested: Path,
    val allowedRoot: Path,
) : IllegalArgumentException(
    "working directory ${requested.toAbsolutePath().normalize()} is outside the allowed workspace " +
    "${allowedRoot.toAbsolutePath().normalize()}; external access is disabled for this task"
)

/** The requested working directory does not exist, is not a directory, or
 *  could not be created. */
class WorkspaceDirMissingException(
    val path: Path,
    detail: String? = null,
) : IllegalArgumentException(
    buildString {
        append("working directory does not exist: ")
        append(path.toAbsolutePath().normalize())
        if (detail != null) append(" (").append(detail).append(')')
    }
)

/**
 * The requested working directory is inside a workspace-protected subtree
 * ([CodingAgentFileSystem.DEFAULT_PROTECTED_DIRS]).
 *
 * The message is a structured refusal so the caller can surface the same
 * `PERMISSION_DENIED | code=…` contract the rest of the policy uses.
 */
class WorkspaceProtectedPathException(
    val path: Path,
    val protectedDir: String,
) : IllegalArgumentException(
    PermissionAudit.format(
        tool = "coding.shell",
        code = PermissionAudit.CODE_PROTECTED_PATH,
        detail = "working directory ${path.toAbsolutePath().normalize()} is inside protected " +
            "directory '$protectedDir'",
        hint = "use the workspace root or a subdirectory outside '$protectedDir'",
    )
)



