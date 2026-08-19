package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.coding.ArtifactScaffolds
import ai.platon.pulsar.coding.ArtifactValidator
import ai.platon.pulsar.coding.CdpTrapCheck
import ai.platon.pulsar.coding.CodeRunner
import ai.platon.pulsar.coding.CodingAgentFileSystem
import ai.platon.pulsar.coding.CodingAgentShell
import ai.platon.pulsar.coding.DevFlowScaffolds
import ai.platon.pulsar.coding.DevTaskPlanner
import ai.platon.pulsar.coding.LanguageServerManager
import ai.platon.pulsar.coding.KotlinSemanticIndexer
import ai.platon.pulsar.coding.MavenBuildSupport
import ai.platon.pulsar.coding.ModuleGraph
import ai.platon.pulsar.coding.ModuleMap
import ai.platon.pulsar.coding.RepoConsistencyCheck
import ai.platon.pulsar.coding.SkeletonExtractor
import ai.platon.pulsar.coding.TokenEstimator
import ai.platon.pulsar.coding.CodingTokenStats
import ai.platon.pulsar.coding.ValidationResult
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.model.TcEvaluate
import ai.platon.pulsar.agentic.tools.CustomToolRegistry
import ai.platon.pulsar.agentic.tools.specs.ToolCallSpecificationRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KClass

/**
 * Unified coding tool executor that provides an agent with shell access,
 * filesystem access, and code-oriented operations.
 *
 * Domain: `coding`
 *
 * This executor composes [CodingAgentShell] and [CodingAgentFileSystem] to give
 * the agent a complete development environment: run commands, read/write files,
 * search the codebase, manage git, and execute builds/tests.
 *
 * ## Supported Methods
 *
 * ### Shell (via CodingAgentShell)
 * - `shell(command, timeoutSeconds?, workingDir?)` — Execute any allowed dev command
 * - `shellOutput(sessionId)` — Read output of a previous command
 * - `shellStatus(sessionId)` — Get status of a previous command
 * - `shellList()` — List all shell sessions
 * - `shellSetEnv(name, value)` — Set persistent env var
 * - `toolsDetect()` — Report available dev tools
 * - `projectType()` — Detect project type
 *
 * ### File System (via CodingAgentFileSystem)
 * - `read(path)` — Read a text file
 * - `readLines(path, startLine, endLine)` — Read specific lines
 * - `write(path, content)` — Write/create a file
 * - `append(path, content)` — Append to a file
 * - `replace(path, oldStr, newStr)` — Replace text in a file
 * - `delete(path, recursive?)` — Delete a file or directory
 * - `mkdir(path)` — Create directory
 * - `copy(source, dest)` — Copy file/directory
 * - `move(source, dest)` — Move/rename file/directory
 * - `listDir(path?, maxDepth?)` — List directory contents
 * - `glob(pattern)` — Find files by glob pattern
 * - `grep(pattern, path?, filePattern?)` — Search file contents
 * - `stat(path)` — Get file/directory info
 * - `diff(path)` — Show changes since snapshot
 * - `changeSummary()` — Show all tracked changes
 * - `languages()` — Detect programming languages in workspace
 *
 * ### Artifact Scaffolding & Validation
 * - `scaffold(type, ...)` — Generate template for plugin/skill/js/script
 * - `validate(type, path)` — Validate a plugin dir, skill file, JS file, or script file
 *
 * ### Token Statistics
 * - `tokenStats(reset?)` — Report token usage of coding tool calls (per method)
 * - `estimateTokens(text)` — Estimate the token count of a text
 */
class CodingToolExecutor : AbstractToolExecutor() {

    override val domain = "coding"

    override val receiverClass: KClass<*> = CodingToolExecutor.Target::class

    /**
     * Lazily-created LSP client bound to the workspace root of the first fs
     * target it sees. Servers are started on demand and idle-reaped.
     */
    @Volatile
    private var languageServer: LanguageServerManager? = null

    private fun lsp(fs: CodingAgentFileSystem): LanguageServerManager {
        return languageServer ?: synchronized(this) {
            languageServer ?: LanguageServerManager(fs.workspaceRoot).also { languageServer = it }
        }
    }

    /** Sandboxed code runner for `coding.runCode`. Stateless, safe to share. */
    private val codeRunner = CodeRunner()

    /** Maven build wrapper for Browser4 self-development (`coding.mvnBuild`). */
    private val mavenBuild = MavenBuildSupport()

    /** Zero-dependency Kotlin symbol/reference extraction (`coding.ktSymbols`/`ktReferences`). */
    private val kotlinIndexer = KotlinSemanticIndexer()

    /**
     * Token statistics for all coding tool calls executed by this executor
     * (input = serialized arguments, output = result text). Exposed to the
     * agent via `coding.tokenStats`.
     */
    val tokenStats = CodingTokenStats()

    /** Meta tools that must not be recorded (they exist to report on the rest). */
    private val metaMethods = setOf("tokenStats", "estimateTokens")

    /**
     * Record token usage around every tool call, then delegate to the
     * abstract executor. Failed calls (exception carried in [TcEvaluate])
     * are counted with their error text as output.
     */
    override suspend fun callFunctionOn(tc: ToolCall, receiver: Any): TcEvaluate {
        if (tc.domain != domain || tc.method in metaMethods) {
            return super.callFunctionOn(tc, receiver)
        }
        val start = System.currentTimeMillis()
        val result = super.callFunctionOn(tc, receiver)
        tokenStats.record(
            method = tc.method,
            input = tc.arguments.toString(),
            output = result.value?.toString() ?: result.exception?.toString(),
            error = !result.success,
            millis = System.currentTimeMillis() - start,
        )
        return result
    }

    /**
     * Composite target that bundles the enhanced shell and filesystem.
     * Registered as a custom target in [AgentToolManager].
     */
    data class Target(
        val shell: CodingAgentShell,
        val fs: CodingAgentFileSystem,
    )

    init {
        // --- Shell methods ---
        toolSpec["shell"] = ToolSpec(
            domain = domain, method = "shell",
            arguments = listOf(
                ToolSpec.Arg("command", "String"),
                ToolSpec.Arg("timeoutSeconds", "Long", "120"),
                ToolSpec.Arg("workingDir", "String", "null"),
            ),
            returnType = "String",
            description = "Execute a shell command. Supports git, cargo, mvn, npm, python, node, javac, and hundreds more dev tools. Use for building, testing, running scripts, and version control."
        )
        toolSpec["shellOutput"] = ToolSpec(
            domain = domain, method = "shellOutput",
            arguments = listOf(ToolSpec.Arg("sessionId", "String")),
            returnType = "String",
            description = "Read output of a previous shell command"
        )
        toolSpec["shellStatus"] = ToolSpec(
            domain = domain, method = "shellStatus",
            arguments = listOf(ToolSpec.Arg("sessionId", "String")),
            returnType = "String",
            description = "Get status of a previous shell command"
        )
        toolSpec["shellList"] = ToolSpec(
            domain = domain, method = "shellList",
            arguments = emptyList(),
            returnType = "String",
            description = "List all shell command sessions"
        )
        toolSpec["shellSetEnv"] = ToolSpec(
            domain = domain, method = "shellSetEnv",
            arguments = listOf(
                ToolSpec.Arg("name", "String"),
                ToolSpec.Arg("value", "String"),
            ),
            returnType = "String",
            description = "Set a persistent environment variable for shell commands"
        )
        toolSpec["toolsDetect"] = ToolSpec(
            domain = domain, method = "toolsDetect",
            arguments = emptyList(),
            returnType = "String",
            description = "Detect available development tools on PATH"
        )
        toolSpec["projectType"] = ToolSpec(
            domain = domain, method = "projectType",
            arguments = emptyList(),
            returnType = "String",
            description = "Detect the project type (rust, maven, node, etc.)"
        )

        // --- File system methods ---
        toolSpec["read"] = ToolSpec(
            domain = domain, method = "read",
            arguments = listOf(ToolSpec.Arg("path", "String")),
            returnType = "String",
            description = "Read a file's content. Supports all text file types. Binary files show metadata only."
        )
        toolSpec["readLines"] = ToolSpec(
            domain = domain, method = "readLines",
            arguments = listOf(
                ToolSpec.Arg("path", "String"),
                ToolSpec.Arg("startLine", "Int", "1"),
                ToolSpec.Arg("endLine", "Int", "-1"),
            ),
            returnType = "String",
            description = "Read specific line range from a file"
        )
        toolSpec["write"] = ToolSpec(
            domain = domain, method = "write",
            arguments = listOf(
                ToolSpec.Arg("path", "String"),
                ToolSpec.Arg("content", "String"),
            ),
            returnType = "String",
            description = "Write content to a file (creates parent directories, overwrites existing)"
        )
        toolSpec["append"] = ToolSpec(
            domain = domain, method = "append",
            arguments = listOf(
                ToolSpec.Arg("path", "String"),
                ToolSpec.Arg("content", "String"),
            ),
            returnType = "String",
            description = "Append content to a file"
        )
        toolSpec["replace"] = ToolSpec(
            domain = domain, method = "replace",
            arguments = listOf(
                ToolSpec.Arg("path", "String"),
                ToolSpec.Arg("oldStr", "String"),
                ToolSpec.Arg("newStr", "String"),
                ToolSpec.Arg("count", "Int", "-1"),
            ),
            returnType = "String",
            description = "Replace text occurrences in a file. Use count to limit replacements (-1 = replace all)."
        )
        toolSpec["replaceRegex"] = ToolSpec(
            domain = domain, method = "replaceRegex",
            arguments = listOf(
                ToolSpec.Arg("path", "String"),
                ToolSpec.Arg("regex", "String"),
                ToolSpec.Arg("replacement", "String"),
                ToolSpec.Arg("count", "Int", "-1"),
            ),
            returnType = "String",
            description = "Replace all matches of a regular expression in a file. Supports capture groups via \$1/\${name} in replacement. Use count to limit (-1 = replace all)."
        )
        toolSpec["editLines"] = ToolSpec(
            domain = domain, method = "editLines",
            arguments = listOf(
                ToolSpec.Arg("path", "String"),
                ToolSpec.Arg("startLine", "Int"),
                ToolSpec.Arg("endLine", "Int"),
                ToolSpec.Arg("content", "String"),
            ),
            returnType = "String",
            description = "Replace lines startLine..endLine (1-based, inclusive) with new content. Preferred over replace for whole-block edits."
        )
        toolSpec["insertAfter"] = ToolSpec(
            domain = domain, method = "insertAfter",
            arguments = listOf(
                ToolSpec.Arg("path", "String"),
                ToolSpec.Arg("anchor", "String"),
                ToolSpec.Arg("content", "String"),
            ),
            returnType = "String",
            description = "Insert content after the first line containing anchor (substring match)."
        )
        toolSpec["revert"] = ToolSpec(
            domain = domain, method = "revert",
            arguments = listOf(
                ToolSpec.Arg("path", "String"),
            ),
            returnType = "String",
            description = "Restore a file to its snapshot (state before the first tracked write in this session)."
        )
        toolSpec["delete"] = ToolSpec(
            domain = domain, method = "delete",
            arguments = listOf(
                ToolSpec.Arg("path", "String"),
                ToolSpec.Arg("recursive", "Boolean", "false"),
            ),
            returnType = "String",
            description = "Delete a file or directory"
        )
        toolSpec["mkdir"] = ToolSpec(
            domain = domain, method = "mkdir",
            arguments = listOf(ToolSpec.Arg("path", "String")),
            returnType = "String",
            description = "Create a directory and any missing parents"
        )
        toolSpec["copy"] = ToolSpec(
            domain = domain, method = "copy",
            arguments = listOf(
                ToolSpec.Arg("source", "String"),
                ToolSpec.Arg("dest", "String"),
            ),
            returnType = "String",
            description = "Copy a file or directory"
        )
        toolSpec["move"] = ToolSpec(
            domain = domain, method = "move",
            arguments = listOf(
                ToolSpec.Arg("source", "String"),
                ToolSpec.Arg("dest", "String"),
            ),
            returnType = "String",
            description = "Move or rename a file or directory"
        )
        toolSpec["listDir"] = ToolSpec(
            domain = domain, method = "listDir",
            arguments = listOf(
                ToolSpec.Arg("path", "String", "\".\""),
                ToolSpec.Arg("maxDepth", "Int", "1"),
            ),
            returnType = "String",
            description = "List directory contents"
        )
        toolSpec["glob"] = ToolSpec(
            domain = domain, method = "glob",
            arguments = listOf(ToolSpec.Arg("pattern", "String")),
            returnType = "String",
            description = "Find files matching a glob pattern (e.g., 'src/**/*.kt')"
        )
        toolSpec["grep"] = ToolSpec(
            domain = domain, method = "grep",
            arguments = listOf(
                ToolSpec.Arg("pattern", "String"),
                ToolSpec.Arg("path", "String", "\".\""),
                ToolSpec.Arg("filePattern", "String", "*"),
                ToolSpec.Arg("ignoreCase", "Boolean", "false"),
            ),
            returnType = "String",
            description = "Search file contents for a regex pattern (like grep -r)"
        )
        toolSpec["stat"] = ToolSpec(
            domain = domain, method = "stat",
            arguments = listOf(ToolSpec.Arg("path", "String")),
            returnType = "String",
            description = "Get file/directory metadata"
        )
        toolSpec["diff"] = ToolSpec(
            domain = domain, method = "diff",
            arguments = listOf(
                ToolSpec.Arg("path", "String"),
                ToolSpec.Arg("algorithm", "String", "myers"),
            ),
            returnType = "String",
            description = "Show unified diff between snapshot and current content of a file. " +
                "algorithm: 'myers' (default, fastest) or 'patience' (better for code moves)."
        )
        toolSpec["changeSummary"] = ToolSpec(
            domain = domain, method = "changeSummary",
            arguments = emptyList(),
            returnType = "String",
            description = "Show summary of all file changes since tracking started"
        )
        toolSpec["languages"] = ToolSpec(
            domain = domain, method = "languages",
            arguments = emptyList(),
            returnType = "String",
            description = "Detect programming languages used in the workspace"
        )
        toolSpec["workspaceRoot"] = ToolSpec(
            domain = domain, method = "workspaceRoot",
            arguments = emptyList(),
            returnType = "String",
            description = "Get the workspace root directory path"
        )

        // --- Language server (LSP) tools ---
        toolSpec["diagnostics"] = ToolSpec(
            domain = domain, method = "diagnostics",
            arguments = listOf(ToolSpec.Arg("path", "String")),
            returnType = "String",
            description = "Get compiler/linter diagnostics for a file via its language server (ts, js, py, rs). " +
                "Returns structured errors/warnings with line numbers. Requires the language server to be installed."
        )
        toolSpec["symbols"] = ToolSpec(
            domain = domain, method = "symbols",
            arguments = listOf(
                ToolSpec.Arg("pattern", "String", "null"),
            ),
            returnType = "String",
            description = "Search for symbol definitions (classes, functions, variables) across open documents " +
                "via language servers. pattern filters by name substring."
        )
        toolSpec["references"] = ToolSpec(
            domain = domain, method = "references",
            arguments = listOf(
                ToolSpec.Arg("path", "String"),
                ToolSpec.Arg("symbol", "String"),
            ),
            returnType = "String",
            description = "Find all references to a symbol in a file via its language server. " +
                "Use before refactoring to assess impact."
        )
        toolSpec["lspServers"] = ToolSpec(
            domain = domain, method = "lspServers",
            arguments = emptyList(),
            returnType = "String",
            description = "Report which language servers are installed/available for diagnostics, symbols, and references."
        )

        // --- Browser4 self-development: Maven build with structured diagnostics ---
        toolSpec["mvnBuild"] = ToolSpec(
            domain = domain, method = "mvnBuild",
            arguments = listOf(
                ToolSpec.Arg("module", "String"),
                ToolSpec.Arg("goals", "String", "compile"),
                ToolSpec.Arg("skipTests", "Boolean", "true"),
                ToolSpec.Arg("timeoutSeconds", "Long", "300"),
            ),
            returnType = "String",
            description = "Build a Browser4 Maven module (-pl <module> -am <goals>) and return structured " +
                "Kotlin/Java compiler diagnostics (file:line:col — message) instead of raw logs. " +
                "module e.g. 'browser4-rest' or 'browser4-plugins/browser4-seo'; goals default 'compile'. " +
                "Use this to check Kotlin code before/after edits — the fast alternative to a JDTLS server."
        )

        // --- Kotlin semantic layer (zero-dependency symbol/reference extraction) ---
        toolSpec["ktSymbols"] = ToolSpec(
            domain = domain, method = "ktSymbols",
            arguments = listOf(
                ToolSpec.Arg("path", "String"),
                ToolSpec.Arg("pattern", "String", "null"),
            ),
            returnType = "String",
            description = "List Kotlin symbol definitions (classes, objects, interfaces, functions, properties) " +
                "in a .kt file via lightweight language-structure analysis (zero dependencies). " +
                "pattern filters by name substring. For Browser4 self-development."
        )
        toolSpec["ktReferences"] = ToolSpec(
            domain = domain, method = "ktReferences",
            arguments = listOf(
                ToolSpec.Arg("path", "String"),
                ToolSpec.Arg("symbol", "String"),
                ToolSpec.Arg("scope", "String", "file"),
            ),
            returnType = "String",
            description = "Find references to a Kotlin symbol in a .kt file — call sites and property usages — " +
                "via lightweight language-structure analysis (zero dependencies). scope='file' (default) scans " +
                "the given file; scope='module' scans all .kt files under the owning module for cross-file " +
                "impact (excludes the declaring file). Use before refactoring Browser4 code to assess impact."
        )
        toolSpec["ktInheritance"] = ToolSpec(
            domain = domain, method = "ktInheritance",
            arguments = listOf(
                ToolSpec.Arg("path", "String"),
                ToolSpec.Arg("className", "String", "null"),
            ),
            returnType = "String",
            description = "Walk the inheritance chain of a Kotlin class across the owning module's files " +
                "(class X : AbstractToolExecutor → AbstractToolExecutor → ...). className defaults to the " +
                "file's main class. Zero-dependency text analysis; generic/interface noise is ignored. " +
                "Use to understand a class hierarchy before editing."
        )

        // --- Extract skeleton from real code (anti-staleness scaffold) ---
        toolSpec["scaffoldFromExample"] = ToolSpec(
            domain = domain, method = "scaffoldFromExample",
            arguments = listOf(
                ToolSpec.Arg("path", "String"),
                ToolSpec.Arg("basePackage", "String", "null"),
                ToolSpec.Arg("className", "String", "null"),
                ToolSpec.Arg("domain", "String", "null"),
                ToolSpec.Arg("toolMethod", "String", "null"),
                ToolSpec.Arg("stem", "String", "null"),
            ),
            returnType = "String",
            description = "Generate a skeleton from EXISTING real code — a single file OR a whole directory " +
                "(plugin/module). For a file: parameterizes package/class/domain/tool method into placeholders. " +
                "For a directory: extracts a MULTI-FILE skeleton set and parameterizes volatile identifiers " +
                "consistently ACROSS files (className renames the executor AND its references in the " +
                "AutoConfiguration/Service files; sibling classes sharing the detected STEM follow the rename — " +
                "renaming SeoToolExecutor → WeatherToolExecutor also derives WeatherAutoConfiguration/WeatherService; " +
                "pass stem=<new-stem> to override; explicit per-class keys always win). " +
                "artifactId from pom.xml, pluginName from plugin.json. " +
                "Provide path (file or directory) plus any of basePackage/className/domain/toolMethod/stem to rename; " +
                "omit to see the discovered parameters."
        )

        // --- Browser4 development-flow scaffolds (multi-file) ---
        toolSpec["scaffoldFlow"] = ToolSpec(
            domain = domain, method = "scaffoldFlow",
            arguments = listOf(
                ToolSpec.Arg("type", "String"),
                ToolSpec.Arg("name", "String"),
                ToolSpec.Arg("description", "String", "null"),
                ToolSpec.Arg("category", "String", "null"),
                ToolSpec.Arg("toolName", "String", "null"),
                ToolSpec.Arg("domain", "String", "null"),
                ToolSpec.Arg("basePackage", "String", "null"),
                ToolSpec.Arg("toolMethod", "String", "null"),
                ToolSpec.Arg("verify", "Boolean", "false"),
            ),
            returnType = "String",
            description = "Generate a multi-file development-flow skeleton for Browser4 self-development. " +
                "type: 'b4-cli-command' (new CLI command → commands.rs CommandDef + MCPToolController alias + " +
                "backend method + test) or 'agent-tool' (new tool domain → ToolExecutor + ToolMount auto-config). " +
                "Identifiers derive from name; cross-file consistency is automatic. " +
                "For b4-cli-command: name=kebab-case, description, category (e.g. Extract/Swarm), toolName (snake). " +
                "For agent-tool: name=plugin name, domain, basePackage, toolMethod, description."
        )

        // --- Impact analysis for Browser4 self-development ---
        toolSpec["impact"] = ToolSpec(
            domain = domain, method = "impact",
            arguments = listOf(ToolSpec.Arg("path", "String")),
            returnType = "String",
            description = "Analyze the impact of changing a file in the Browser4 repo: which Maven module owns " +
                "it, which modules depend on it (transitively, from the LIVE pom graph), and the suggested test " +
                "commands (Rust CLI: cargo test --bin browser4-cli; Kotlin: mvn test -pl <module> -am). " +
                "Use before modifying Browser4's own code."
        )

        // --- Live module graph (anti-staleness: rebuilt from real poms) ---
        toolSpec["moduleGraph"] = ToolSpec(
            domain = domain, method = "moduleGraph",
            arguments = listOf(ToolSpec.Arg("module", "String", "null")),
            returnType = "String",
            description = "Scan the repository's real pom.xml files and report the LIVE module graph: module " +
                "path, artifactId, parent, and internal dependencies. Warns when the graph drifted from the " +
                "static ModuleMap snapshot (new modules the snapshot missed). " +
                "With module=<path> (e.g. browser4-coding): reports its transitive dependents and suggested " +
                "test commands. The same graph powers coding.impact."
        )

        // --- Sandboxed code execution ---
        toolSpec["runCode"] = ToolSpec(
            domain = domain, method = "runCode",
            arguments = listOf(
                ToolSpec.Arg("language", "String"),
                ToolSpec.Arg("code", "String"),
                ToolSpec.Arg("timeoutSeconds", "Long", "30"),
            ),
            returnType = "String",
            description = "Run code in a sandboxed subprocess (private temp dir, hard timeout, output truncation). " +
                "languages: kotlin, js/javascript, ts, python/python3, bash/sh. " +
                "Use for quick snippets; for workspace builds/tests use coding.shell."
        )
        toolSpec["runCodeLanguages"] = ToolSpec(
            domain = domain, method = "runCodeLanguages",
            arguments = emptyList(),
            returnType = "String",
            description = "List languages supported by coding.runCode."
        )

        // --- Artifact scaffolding & validation ---
        toolSpec["scaffold"] = ToolSpec(
            domain = domain, method = "scaffold",
            arguments = listOf(
                ToolSpec.Arg("type", "String"),
                ToolSpec.Arg("pluginName", "String", "null"),
                ToolSpec.Arg("domain", "String", "null"),
                ToolSpec.Arg("basePackage", "String", "null"),
                ToolSpec.Arg("toolMethod", "String", "null"),
                ToolSpec.Arg("toolDescription", "String", "null"),
                ToolSpec.Arg("pdkVersion", "String", "null"),
                ToolSpec.Arg("name", "String", "null"),
                ToolSpec.Arg("description", "String", "null"),
                ToolSpec.Arg("triggers", "String", "null"),
                ToolSpec.Arg("tools", "String", "null"),
                ToolSpec.Arg("purpose", "String", "null"),
                ToolSpec.Arg("scriptType", "String", "null"),
                ToolSpec.Arg("shell", "String", "null"),
            ),
            returnType = "String",
            description = "Generate a scaffold template for a Browser4 plugin, skill, JS script, or shell script. " +
                "type: 'plugin' | 'skill' | 'js' | 'script'. " +
                "For plugin: provide pluginName, domain, basePackage, toolMethod, toolDescription (pdkVersion optional, defaults to current project version). " +
                "For skill: provide name (must match the directory name), description (1-1024 chars), triggers (comma-separated), tools (comma-separated). " +
                "For js: provide name, purpose ('extract'|'inject'|'interact'). " +
                "For script: provide name, scriptType ('build'|'deploy'|'run'), shell ('ps1'|'bash')."
        )
        toolSpec["scaffoldToDir"] = ToolSpec(
            domain = domain, method = "scaffoldToDir",
            arguments = listOf(
                ToolSpec.Arg("type", "String"),
                ToolSpec.Arg("dir", "String"),
                ToolSpec.Arg("pluginName", "String", "null"),
                ToolSpec.Arg("name", "String", "null"),
                ToolSpec.Arg("domain", "String", "null"),
                ToolSpec.Arg("basePackage", "String", "null"),
                ToolSpec.Arg("toolMethod", "String", "null"),
                ToolSpec.Arg("toolDescription", "String", "null"),
                ToolSpec.Arg("description", "String", "null"),
                ToolSpec.Arg("triggers", "String", "null"),
                ToolSpec.Arg("tools", "String", "null"),
                ToolSpec.Arg("purpose", "String", "null"),
                ToolSpec.Arg("scriptType", "String", "null"),
                ToolSpec.Arg("shell", "String", "null"),
            ),
            returnType = "String",
            description = "Generate a scaffold (see scaffold) AND write every generated file directly into the " +
                "workspace under the given dir (relative to the workspace root, e.g. dir=\"browser4-plugins/" +
                "browser4-wordcount\"). Multi-file types (plugin) are fully supported; for single-content types " +
                "use scaffold. This is the reliable way for agents to materialize a new plugin without " +
                "copy-pasting scaffold output."
        )
        toolSpec["validate"] = ToolSpec(
            domain = domain, method = "validate",
            arguments = listOf(
                ToolSpec.Arg("type", "String"),
                ToolSpec.Arg("path", "String", "null"),
            ),
            returnType = "String",
            description = "Validate a Browser4 plugin directory, skill file, JS file, script file, or repo governance. " +
                "type: 'plugin' (path=plugin dir) | 'skill' | 'js' | 'script' (path=file path) | " +
                "'repo-consistency' (no path — checks VERSION vs root pom vs BOM vs module registration). " +
                "Returns a list of issues with severity (error/warning/info)."
        )

        // --- Browser4 self-development: CDP pitfall awareness ---
        toolSpec["trapCheck"] = ToolSpec(
            domain = domain, method = "trapCheck",
            arguments = listOf(ToolSpec.Arg("path", "String")),
            returnType = "String",
            description = "Scan a file for known Browser4 CDP pitfalls (AGENTS.md): mouseWheel race " +
                "(crbug.com/444929150), cursor positioning after focus+click, and Input.insertText " +
                "racing. Reminds the agent of the documented fix for each trap. Use before editing " +
                "browser-driver code (PulsarWebDriver.kt and friends)."
        )

        // --- Session-level dynamic file protection ---
        toolSpec["protect"] = ToolSpec(
            domain = domain, method = "protect",
            arguments = listOf(
                ToolSpec.Arg("path", "String", "null"),
                ToolSpec.Arg("on", "Boolean", "true"),
            ),
            returnType = "String",
            description = "Manage session-level file protections: coding.protect(path=\"src/Foo.kt\", on=true) " +
                "blocks delete/replace/editLines/insertAfter on that exact file; on=false removes it. " +
                "Without path: lists the dynamic protections. Repo-governance files (VERSION/AGENTS.md/CLAUDE.md/" +
                "root pom/BOM/CI) are always protected and cannot be unprotected."
        )

        // --- Browser4 self-development: high-level dev task entry ---
        toolSpec["devTask"] = ToolSpec(
            domain = domain, method = "devTask",
            arguments = listOf(
                ToolSpec.Arg("task", "String"),
                ToolSpec.Arg("verify", "Boolean", "false"),
                ToolSpec.Arg("runTests", "Boolean", "false"),
                ToolSpec.Arg("module", "String", "null"),
            ),
            returnType = "String",
            description = "High-level entry for a Browser4 self-development task: parse a natural-language task " +
                "into an executable plan following the AGENTS.md dev flow — locate the affected files, impact " +
                "analysis, compile the owning module, smallest-scope tests, CDP trap check for driver code, " +
                "repo-consistency validation, commit guidance. Module mentions resolve against the LIVE pom " +
                "graph (coding.moduleGraph) when available. verify=true additionally RUNS the fast checks " +
                "(mvnBuild compile of the affected module, trapCheck, repo-consistency); runTests=true (with " +
                "verify) also runs the module's test suite (mvn test -pl <module> -am / cargo test). " +
                "module overrides the inferred module."
        )

        // --- Token usage statistics ---
        toolSpec["tokenStats"] = ToolSpec(
            domain = domain, method = "tokenStats",
            arguments = listOf(
                ToolSpec.Arg("reset", "Boolean", "false"),
            ),
            returnType = "String",
            description = "Report token usage of coding tool calls so far (per method: calls, errors, " +
                "input/output tokens, avg/max output). reset=true clears the counters after reporting. " +
                "Use to audit which coding tools consume the most context window."
        )
        toolSpec["estimateTokens"] = ToolSpec(
            domain = domain, method = "estimateTokens",
            arguments = listOf(ToolSpec.Arg("text", "String")),
            returnType = "String",
            description = "Estimate the LLM token count of a text (heuristic, ±25%). Use to check a message " +
                "or file chunk before sending it to the model."
        )
    }

    @Throws(IllegalArgumentException::class)
    override suspend fun callFunctionOn(
        domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
    ): Any? {
        require(domain == this.domain) { "Unsupported domain: $domain" }
        require(functionName.isNotBlank()) { "Function name must not be blank" }
        require(receiver is Target) { "Target must be CodingToolExecutor.Target" }

        val shell = receiver.shell
        val fs = receiver.fs

        return when (functionName) {
            // --- Shell ---
            "shell" -> {
                validateArgs(args, allowed = setOf("command", "timeoutSeconds", "workingDir"), required = setOf("command"), functionName)
                shell.execute(
                    command = paramString(args, "command", functionName)!!,
                    timeoutSeconds = paramLong(args, "timeoutSeconds", functionName, required = false, default = 120L) ?: 120L,
                    workingDir = paramString(args, "workingDir", functionName, required = false, default = null),
                )
            }
            "shellOutput" -> {
                validateArgs(args, allowed = setOf("sessionId"), required = setOf("sessionId"), functionName)
                shell.readOutput(paramString(args, "sessionId", functionName)!!)
            }
            "shellStatus" -> {
                validateArgs(args, allowed = setOf("sessionId"), required = setOf("sessionId"), functionName)
                shell.getStatus(paramString(args, "sessionId", functionName)!!)
            }
            "shellList" -> {
                validateArgs(args, allowed = emptySet(), required = emptySet(), functionName)
                shell.listSessions()
            }
            "shellSetEnv" -> {
                validateArgs(args, allowed = setOf("name", "value"), required = setOf("name", "value"), functionName)
                shell.setEnv(paramString(args, "name", functionName)!!, paramString(args, "value", functionName)!!)
                "✓ Environment variable set: ${args["name"]}"
            }
            "toolsDetect" -> {
                validateArgs(args, allowed = emptySet(), required = emptySet(), functionName)
                val tools = shell.detectAvailableTools()
                if (tools.isEmpty()) "No dev tools detected on PATH" else "Available tools: ${tools.sorted().joinToString(", ")}"
            }
            "projectType" -> {
                validateArgs(args, allowed = emptySet(), required = emptySet(), functionName)
                "Project type: ${shell.detectProjectType()}"
            }

            // --- File System ---
            "read" -> {
                validateArgs(args, allowed = setOf("path", "startLine", "endLine"), required = setOf("path"), functionName)
                val path = paramString(args, "path", functionName)!!
                val startLine = paramInt(args, "startLine", functionName, required = false, default = null)
                val endLine = paramInt(args, "endLine", functionName, required = false, default = null)
                if (startLine != null || endLine != null) {
                    fs.readFileLines(
                        path = path,
                        startLine = startLine ?: 1,
                        endLine = endLine ?: -1,
                    )
                } else {
                    fs.readFile(path)
                }
            }
            "readLines" -> {
                validateArgs(args, allowed = setOf("path", "startLine", "endLine"), required = setOf("path"), functionName)
                fs.readFileLines(
                    path = paramString(args, "path", functionName)!!,
                    startLine = paramInt(args, "startLine", functionName, required = false, default = 1) ?: 1,
                    endLine = paramInt(args, "endLine", functionName, required = false, default = -1) ?: -1,
                )
            }
            "write" -> {
                validateArgs(args, allowed = setOf("path", "content"), required = setOf("path", "content"), functionName)
                fs.writeFile(
                    path = paramString(args, "path", functionName)!!,
                    content = paramString(args, "content", functionName)!!,
                )
            }
            "append" -> {
                validateArgs(args, allowed = setOf("path", "content"), required = setOf("path", "content"), functionName)
                fs.appendFile(
                    path = paramString(args, "path", functionName)!!,
                    content = paramString(args, "content", functionName)!!,
                )
            }
            "replace" -> {
                validateArgs(args, allowed = setOf("path", "oldStr", "newStr", "count"), required = setOf("path", "oldStr", "newStr"), functionName)
                fs.replaceInFile(
                    path = paramString(args, "path", functionName)!!,
                    oldStr = paramString(args, "oldStr", functionName)!!,
                    newStr = paramString(args, "newStr", functionName)!!,
                    count = paramInt(args, "count", functionName, required = false, default = -1) ?: -1,
                )
            }
            "replaceRegex" -> {
                validateArgs(args, allowed = setOf("path", "regex", "replacement", "count"), required = setOf("path", "regex", "replacement"), functionName)
                fs.replaceRegexInFile(
                    path = paramString(args, "path", functionName)!!,
                    regex = paramString(args, "regex", functionName)!!,
                    replacement = paramString(args, "replacement", functionName)!!,
                    count = paramInt(args, "count", functionName, required = false, default = -1) ?: -1,
                )
            }
            "editLines" -> {
                validateArgs(args, allowed = setOf("path", "startLine", "endLine", "content"), required = setOf("path", "startLine", "endLine", "content"), functionName)
                fs.editLinesInFile(
                    path = paramString(args, "path", functionName)!!,
                    startLine = paramInt(args, "startLine", functionName)!!,
                    endLine = paramInt(args, "endLine", functionName)!!,
                    content = paramString(args, "content", functionName)!!,
                )
            }
            "insertAfter" -> {
                validateArgs(args, allowed = setOf("path", "anchor", "content"), required = setOf("path", "anchor", "content"), functionName)
                fs.insertAfterInFile(
                    path = paramString(args, "path", functionName)!!,
                    anchor = paramString(args, "anchor", functionName)!!,
                    content = paramString(args, "content", functionName)!!,
                )
            }
            "revert" -> {
                validateArgs(args, allowed = setOf("path"), required = setOf("path"), functionName)
                fs.revert(paramString(args, "path", functionName)!!)
            }
            "delete" -> {
                validateArgs(args, allowed = setOf("path", "recursive"), required = setOf("path"), functionName)
                fs.delete(
                    path = paramString(args, "path", functionName)!!,
                    recursive = paramBool(args, "recursive", functionName, required = false, default = false) ?: false,
                )
            }
            "mkdir" -> {
                validateArgs(args, allowed = setOf("path"), required = setOf("path"), functionName)
                fs.mkdir(paramString(args, "path", functionName)!!)
            }
            "copy" -> {
                validateArgs(args, allowed = setOf("source", "dest"), required = setOf("source", "dest"), functionName)
                fs.copy(
                    source = paramString(args, "source", functionName)!!,
                    dest = paramString(args, "dest", functionName)!!,
                )
            }
            "move" -> {
                validateArgs(args, allowed = setOf("source", "dest"), required = setOf("source", "dest"), functionName)
                fs.move(
                    source = paramString(args, "source", functionName)!!,
                    dest = paramString(args, "dest", functionName)!!,
                )
            }
            "listDir" -> {
                validateArgs(args, allowed = setOf("path", "maxDepth"), required = emptySet(), functionName)
                fs.listDir(
                    path = paramString(args, "path", functionName, required = false, default = ".") ?: ".",
                    maxDepth = paramInt(args, "maxDepth", functionName, required = false, default = 1) ?: 1,
                )
            }
            "glob" -> {
                validateArgs(args, allowed = setOf("pattern"), required = setOf("pattern"), functionName)
                fs.glob(paramString(args, "pattern", functionName)!!)
            }
            "grep" -> {
                validateArgs(args, allowed = setOf("pattern", "path", "filePattern", "ignoreCase"), required = setOf("pattern"), functionName)
                fs.grep(
                    pattern = paramString(args, "pattern", functionName)!!,
                    path = paramString(args, "path", functionName, required = false, default = ".") ?: ".",
                    filePattern = paramString(args, "filePattern", functionName, required = false, default = "*") ?: "*",
                    ignoreCase = paramBool(args, "ignoreCase", functionName, required = false, default = false) ?: false,
                )
            }
            "stat" -> {
                validateArgs(args, allowed = setOf("path"), required = setOf("path"), functionName)
                fs.fileInfo(paramString(args, "path", functionName)!!)
            }
            "diff" -> {
                validateArgs(args, allowed = setOf("path"), required = setOf("path"), functionName)
                fs.diff(
                    path = paramString(args, "path", functionName)!!,
                    algorithm = paramString(args, "algorithm", functionName, required = false, default = "myers") ?: "myers",
                )
            }
            "changeSummary" -> {
                validateArgs(args, allowed = emptySet(), required = emptySet(), functionName)
                fs.changeSummary()
            }
            "languages" -> {
                validateArgs(args, allowed = emptySet(), required = emptySet(), functionName)
                val langs = fs.detectLanguages()
                if (langs.isEmpty()) "No source files detected" else langs.entries.joinToString("\n") { "${it.key}: ${it.value} files" }
            }
            "workspaceRoot" -> {
                validateArgs(args, allowed = emptySet(), required = emptySet(), functionName)
                fs.getWorkspaceRoot()
            }

            // --- Language server (LSP) tools ---
            "diagnostics" -> {
                validateArgs(args, allowed = setOf("path"), required = setOf("path"), functionName)
                val path = paramString(args, "path", functionName)!!
                val resolved = fs.resolvePathString(path)
                    ?: throw IllegalArgumentException("Path not allowed: $path")
                // Kotlin/Java have no lightweight LSP server configured — route to the
                // Maven compiler passthrough so the agent still gets file:line:col
                // diagnostics (Browser4 self-development path).
                val ext = path.substringAfterLast('.', "").lowercase()
                if (ext in setOf("kt", "kts", "java")) {
                    val module = inferModule(resolved)
                    "Kotlin/Java diagnostics via compiler passthrough: run " +
                        "coding.mvnBuild(module=\"$module\", goals=\"compile\") — no JDTLS server is " +
                        "configured for lightweight diagnostics. (inferred module from path: $module)"
                } else {
                    val diags = lsp(fs).diagnostics(resolved)
                    if (diags.isEmpty()) "✓ No diagnostics reported for $path (or no language server available)"
                    else diags.joinToString("\n") { "[${it.severity}] ${it.file}:${it.line} — ${it.message}" }
                }
            }
            "symbols" -> {
                validateArgs(args, allowed = setOf("pattern"), required = emptySet(), functionName)
                val pattern = paramString(args, "pattern", functionName, required = false, default = "") ?: ""
                val symbols = lsp(fs).symbols(pattern)
                if (symbols.isEmpty()) "No symbols found${if (pattern.isNotBlank()) " for '$pattern'" else ""} (or no language server available)"
                else symbols.joinToString("\n") { "${it.kind} ${it.name} — ${it.file}:${it.line}" }
            }
            "references" -> {
                validateArgs(args, allowed = setOf("path", "symbol"), required = setOf("path", "symbol"), functionName)
                val path = paramString(args, "path", functionName)!!
                val symbol = paramString(args, "symbol", functionName)!!
                val resolved = fs.resolvePathString(path)
                    ?: throw IllegalArgumentException("Path not allowed: $path")
                val refs = lsp(fs).references(resolved, symbol)
                if (refs.isEmpty()) "No references to '$symbol' found in $path"
                else refs.joinToString("\n") { "${it.file}:${it.line}" }
            }
            "lspServers" -> {
                validateArgs(args, allowed = emptySet(), required = emptySet(), functionName)
                val servers = lsp(fs).availableServers()
                servers.entries.joinToString("\n") { "${it.key}: ${if (it.value) "available" else "NOT installed"}" }
            }
            "mvnBuild" -> {
                validateArgs(args, allowed = setOf("module", "goals", "skipTests", "timeoutSeconds"),
                    required = setOf("module"), functionName)
                val module = paramString(args, "module", functionName)!!
                val goals = paramString(args, "goals", functionName, required = false, default = "compile") ?: "compile"
                val skipTests = paramBool(args, "skipTests", functionName, required = false, default = true) ?: true
                val timeout = paramLong(args, "timeoutSeconds", functionName, required = false, default = 300L) ?: 300L
                val result = mavenBuild.build(
                    shell = shell,
                    module = module,
                    goals = goals,
                    skipTests = skipTests,
                    timeoutSeconds = timeout,
                )
                mavenBuild.format(result)
            }
            "ktSymbols" -> {
                validateArgs(args, allowed = setOf("path", "pattern"), required = setOf("path"), functionName)
                val path = paramString(args, "path", functionName)!!
                val resolved = fs.resolvePathString(path)
                    ?: throw IllegalArgumentException("Path not allowed: $path")
                val content = fs.readFile(resolved)
                val pattern = paramString(args, "pattern", functionName, required = false, default = "") ?: ""
                val symbols = kotlinIndexer.symbols(content, resolved.substringAfterLast('/').substringAfterLast('\\'))
                    .filter { pattern.isBlank() || it.name.contains(pattern, ignoreCase = true) }
                if (symbols.isEmpty()) "No Kotlin symbols found${if (pattern.isNotBlank()) " for '$pattern'" else ""} in $path"
                else symbols.joinToString("\n") { "${it.kind} ${it.name} — line ${it.line}" }
            }
            "ktReferences" -> {
                validateArgs(args, allowed = setOf("path", "symbol", "scope"), required = setOf("path", "symbol"), functionName)
                val path = paramString(args, "path", functionName)!!
                val symbol = paramString(args, "symbol", functionName)!!
                val scope = paramString(args, "scope", functionName, required = false, default = "file") ?: "file"
                val resolved = fs.resolvePathString(path)
                    ?: throw IllegalArgumentException("Path not allowed: $path")

                if (scope == "module") {
                    // Cross-file scan: all .kt files under the owning module
                    // (fall back to the whole workspace when the module dir
                    // cannot be resolved, e.g. non-Browser4 layouts).
                    val module = inferModule(resolved)
                    val files = fs.collectTextFiles(if (module.isBlank()) "." else module)
                        .filterKeys { it.endsWith(".kt") }
                        .takeIf { it.isNotEmpty() }
                        ?: fs.collectTextFiles(".").filterKeys { it.endsWith(".kt") }
                    if (files.isEmpty()) return "No Kotlin files found${if (module.isBlank()) "" else " under module $module"}"
                    val refs = kotlinIndexer.referencesInFiles(files, symbol)
                    if (refs.isEmpty()) "No references to '$symbol' outside its declaring file${if (module.isBlank()) "" else " in module $module"}"
                    else refs.joinToString("\n") { "${it.path}:${it.line}: ${it.snippet}" }
                } else {
                    val content = fs.readFile(resolved)
                    val refs = kotlinIndexer.references(content, symbol, resolved.substringAfterLast('/').substringAfterLast('\\'))
                    if (refs.isEmpty()) "No references to '$symbol' found in $path"
                    else refs.joinToString("\n") { "line ${it.line}: ${it.snippet}" }
                }
            }
            "ktInheritance" -> {
                validateArgs(args, allowed = setOf("path", "className"), required = setOf("path"), functionName)
                val path = paramString(args, "path", functionName)!!
                val resolved = fs.resolvePathString(path)
                    ?: throw IllegalArgumentException("Path not allowed: $path")
                val module = inferModule(resolved)
                val fileName = resolved.substringAfterLast('/').substringAfterLast('\\')
                val fileContent = fs.readFile(resolved)
                val files = if (module.isBlank()) mapOf(fileName to fileContent)
                else fs.collectTextFiles(module).filterKeys { it.endsWith(".kt") }
                    .takeIf { it.isNotEmpty() }
                    ?: fs.collectTextFiles(".").filterKeys { it.endsWith(".kt") }
                if (files.isEmpty()) return "No Kotlin files found for $path"

                val className = paramString(args, "className", functionName, required = false, default = null)
                    ?: kotlinIndexer.symbols(fileContent, fileName)
                        .firstOrNull { it.kind == "class" || it.kind == "interface" }?.name
                    ?: return "Cannot infer the class of $path — pass className explicitly"

                val chain = kotlinIndexer.inheritanceChain(files, className)
                chain.joinToString(" → ")
            }
            "scaffoldFromExample" -> {
                validateArgs(args, allowed = setOf("path", "basePackage", "className", "domain", "toolMethod", "stem"),
                    required = setOf("path"), functionName)
                val path = paramString(args, "path", functionName)!!
                val resolved = fs.resolvePathString(path)
                    ?: throw IllegalArgumentException("Path not allowed: $path")
                val renameParams: Map<String, String> = mapOf(
                    "basePackage" to paramString(args, "basePackage", functionName, required = false, default = null),
                    "className" to paramString(args, "className", functionName, required = false, default = null),
                    "domain" to paramString(args, "domain", functionName, required = false, default = null),
                    "toolMethod" to paramString(args, "toolMethod", functionName, required = false, default = null),
                    "stem" to paramString(args, "stem", functionName, required = false, default = null),
                ).filterValues { !it.isNullOrBlank() }.mapValues { it.value!! }

                // Directory path → multi-file live template (cross-file consistent).
                if (Files.isDirectory(Path.of(resolved))) {
                    val files = fs.collectTextFiles(resolved)
                    if (files.isEmpty()) return "No text files found under $path"
                    val set = SkeletonExtractor.extractDir(files)

                    if (renameParams.isEmpty()) {
                        // Discovery mode: report parameters found across the directory.
                        buildString {
                            appendLine("Multi-file skeleton extracted from $path (${files.size} files) — " +
                                "discovered parameters:")
                            set.parameters.forEach { (k, v) -> appendLine("  $k = $v") }
                            appendLine("Re-instantiate with: coding.scaffoldFromExample(path=..., " +
                                set.parameters.keys.joinToString(", ") { "$it=<new-value>" } + ")")
                        }
                    } else {
                        val generated = SkeletonExtractor.instantiate(set, renameParams)
                        buildString {
                            appendLine("=== Multi-file skeleton generated from $path (${generated.size} files) ===")
                            generated.forEach { (relPath, content) ->
                                appendLine("\n=== File: $relPath ===")
                                append(content)
                            }
                        }
                    }
                } else {
                    val content = fs.readFile(resolved)
                    val skeleton = SkeletonExtractor.extract(content, resolved.substringAfterLast('/').substringAfterLast('\\'))

                    if (renameParams.isEmpty()) {
                        // Discovery mode: report the parameters found in the reference file.
                        buildString {
                            appendLine("Skeleton extracted from $path — discovered parameters:")
                            skeleton.parameters.forEach { (k, v) -> appendLine("  $k = $v") }
                            appendLine("Re-instantiate with: coding.scaffoldFromExample(path=..., " +
                                skeleton.parameters.keys.joinToString(", ") { "$it=<new-value>" } + ")")
                        }
                    } else {
                        val generated = SkeletonExtractor.instantiate(skeleton, renameParams)
                        buildString {
                            appendLine("=== Generated from $path (skeleton) ===")
                            append(generated)
                        }
                    }
                }
            }
            "scaffoldFlow" -> {
                validateArgs(args, allowed = setOf("type", "name", "description", "category",
                    "toolName", "domain", "basePackage", "toolMethod", "verify"),
                    required = setOf("type", "name"), functionName)
                val type = paramString(args, "type", functionName)!!
                val name = paramString(args, "name", functionName)!!
                val description = paramString(args, "description", functionName, required = false, default = "") ?: ""
                val category = paramString(args, "category", functionName, required = false, default = null)
                val toolName = paramString(args, "toolName", functionName, required = false, default = null)
                val domain = paramString(args, "domain", functionName, required = false, default = null)
                val basePackage = paramString(args, "basePackage", functionName, required = false, default = null)
                val toolMethod = paramString(args, "toolMethod", functionName, required = false, default = null)
                val verify = paramBool(args, "verify", functionName, required = false, default = false) ?: false

                val files: Map<String, String> = when (type) {
                    "b4-cli-command" -> DevFlowScaffolds.b4CliCommand(
                        name = name,
                        description = description,
                        category = category ?: "Extract",
                        toolName = toolName ?: name.replace('-', '_'),
                    )
                    "agent-tool" -> DevFlowScaffolds.agentTool(
                        pluginName = name,
                        domain = domain ?: name.removePrefix("browser4-").replace("-", "_"),
                        basePackage = basePackage ?: "ai.platon.pulsar.${name.removePrefix("browser4-").replace("-", "")}",
                        toolMethod = toolMethod ?: "doAction",
                        toolDescription = description,
                    )
                    "rest-endpoint" -> DevFlowScaffolds.restEndpoint(
                        resource = name,
                        description = description,
                    )
                    "test-class" -> DevFlowScaffolds.testClass(
                        packageName = basePackage ?: "ai.platon.pulsar.agentic.tools",
                        testClass = toolName ?: "${toTestClassName(name)}Test",
                        targetClass = toolName ?: toTestClassName(name),
                        description = description,
                    )
                    "skill" -> DevFlowScaffolds.skill(
                        name = name,
                        description = description,
                        triggers = if (category.isNullOrBlank()) emptyList() else listOf(category),
                        tools = if (toolName.isNullOrBlank()) emptyList() else listOf(toolName),
                    )
                    else -> throw IllegalArgumentException(
                        "Unknown scaffoldFlow type: $type. Supported: b4-cli-command, agent-tool, rest-endpoint, test-class, skill")
                }
                val generated = files.entries.joinToString("\n\n") { (path, content) ->
                    "=== File: $path ===\n$content"
                }
                if (!verify) return generated

                // verify=true: run the type-appropriate build/test check after generating.
                val verification: String = when (type) {
                    "b4-cli-command" -> {
                        // Rust CLI side first (fast), then the Kotlin backend compiles.
                        val cargo = shell.executeRaw("cargo test --bin browser4-cli", timeoutSeconds = 300)
                        val cargoLine = if (cargo.exitCode == 0) "✓ cargo test --bin browser4-cli" else
                            "✗ cargo test failed (exit ${cargo.exitCode})"
                        "$cargoLine\n${mavenBuild.format(mavenBuild.build(shell, "browser4-rest", "compile", true, 300))}"
                    }
                    "agent-tool" -> {
                        val module = name.removePrefix("browser4-").let { "browser4-plugins/browser4-$it" }
                        val pluginDir = if (name.startsWith("browser4-")) "browser4-plugins/$name" else ""
                        val buildLine = mavenBuild.format(mavenBuild.build(shell, module, "compile", true, 300))
                        val validateLine = if (pluginDir.isNotEmpty()) {
                            ArtifactValidator.validatePlugin(pluginDir).format()
                        } else "plugin dir not resolved"
                        "$buildLine\n$validateLine"
                    }
                    "rest-endpoint" -> mavenBuild.format(mavenBuild.build(shell, "browser4-rest", "compile", true, 300))
                    else -> "No automated verification for type '$type' — verify manually."
                }
                generated + "\n\n--- Verification ---\n$verification"
            }
            "impact" -> {
                validateArgs(args, allowed = setOf("path"), required = setOf("path"), functionName)
                val path = paramString(args, "path", functionName)!!
                val resolved = fs.resolvePathString(path)
                    ?: throw IllegalArgumentException("Path not allowed: $path")
                val module = inferModule(resolved)
                if (module.isBlank()) return "Cannot determine module for $path"

                buildString {
                    appendLine("Impact analysis for $path")
                    appendLine("  module: $module")
                    if (module == ModuleMap.CLI_CRATE || path.contains("/cli/")) {
                        appendLine("  Rust CLI: ${ModuleMap.cargoTestCommand()}")
                        appendLine("  Note: CLI changes may require the Kotlin backend to accept new tools — " +
                            "also run coding.mvnBuild(module=\"browser4-rest\")")
                    } else {
                        // Prefer the LIVE pom graph (anti-staleness); fall back to the static snapshot.
                        val graph = scanModuleGraph(fs)
                        val affected = if (module in graph.nodes) {
                            ModuleGraph.transitiveDependents(graph, module)
                        } else {
                            ModuleMap.transitiveDependents(module)
                        }
                        appendLine("  affects (transitively): ${affected.joinToString(", ")}")
                        appendLine("  test: ${ModuleMap.mavenTestCommand(module)}")
                        appendLine("  also run for dependents: ${affected.drop(1).joinToString(", ")}")
                        if (module !in graph.nodes) {
                            appendLine("  ⚠ module not found in the live pom graph — static snapshot used; " +
                                "run coding.moduleGraph() to check for drift")
                        }
                    }
                }.trimEnd()
            }
            "moduleGraph" -> {
                validateArgs(args, allowed = setOf("module"), required = emptySet(), functionName)
                val module = paramString(args, "module", functionName, required = false, default = null)
                val graph = scanModuleGraph(fs)
                if (module.isNullOrBlank()) {
                    ModuleGraph.format(graph, ModuleMap.MODULES)
                } else {
                    if (module !in graph.nodes) {
                        "Module '$module' not found in the live pom graph. Known: ${graph.nodes.keys.sorted().joinToString(", ")}"
                    } else {
                        val affected = ModuleGraph.transitiveDependents(graph, module)
                        buildString {
                            appendLine("Module: $module [${graph.nodes[module]!!.artifactId}]")
                            appendLine("  directly depends on: ${graph.nodes[module]!!.dependencies.joinToString(", ")}")
                            appendLine("  affects (transitively): ${affected.joinToString(", ")}")
                            appendLine("  test: ${ModuleMap.mavenTestCommand(module)}")
                            val missing = ModuleGraph.drift(graph, ModuleMap.MODULES)
                            if (missing.isNotEmpty()) {
                                appendLine("⚠ static ModuleMap snapshot is missing these real modules: ${missing.joinToString(", ")}")
                            }
                        }.trimEnd()
                    }
                }
            }
            "runCode" -> {
                validateArgs(args, allowed = setOf("language", "code", "timeoutSeconds"), required = setOf("language", "code"), functionName)
                val language = paramString(args, "language", functionName)!!
                val code = paramString(args, "code", functionName)!!
                val timeout = paramLong(args, "timeoutSeconds", functionName, required = false, default = 30L) ?: 30L
                val result = codeRunner.run(language, code, timeoutSeconds = timeout.coerceIn(1, 120))
                buildString {
                    if (result.timedOut) appendLine("⏱ Timed out after ${timeout}s — process killed")
                    if (result.stdout.isNotBlank()) appendLine(result.stdout.trimEnd())
                    if (result.stderr.isNotBlank()) appendLine("stderr: ${result.stderr.trimEnd()}")
                    appendLine("exit code: ${result.exitCode}")
                }.trimEnd()
            }
            "runCodeLanguages" -> {
                validateArgs(args, allowed = emptySet(), required = emptySet(), functionName)
                "Supported: ${codeRunner.supportedLanguages().joinToString(", ")}"
            }

            // --- Artifact scaffolding & validation ---
            "scaffold" -> {
                val allowed = setOf("type", "pluginName", "domain", "basePackage",
                    "toolMethod", "toolDescription", "pdkVersion", "name", "description",
                    "triggers", "tools", "purpose", "scriptType", "shell", "verify")
                validateArgs(args, allowed = allowed, required = setOf("type"), functionName)
                val type = paramString(args, "type", functionName)!!
                val params = args.filterKeys { it !in setOf("type", "verify") }
                    .mapValues { it.value?.toString() ?: "" }
                    .filterValues { it.isNotEmpty() }
                    .toMutableMap()
                // Default the plugin's pdk parent version from the repo VERSION file when available
                if (type == "plugin" && !params.containsKey("pdkVersion")) {
                    val versionFile = fs.readFile("VERSION")
                    if (!versionFile.startsWith("Error:") && versionFile.isNotBlank()) {
                        params["pdkVersion"] = versionFile.trim()
                    }
                }
                // The CLI (and the LLM prompt) use "name"; the plugin template reads
                // "pluginName". Map it so `code scaffold plugin --name X` names the plugin.
                if (type == "plugin" && !params.containsKey("pluginName") && params.containsKey("name")) {
                    params["pluginName"] = params["name"]!!
                }
                val result = ArtifactScaffolds.scaffold(type, params)
                if (result.size == 1 && result.containsKey("_content")) {
                    result["_content"]!!
                } else {
                    result.entries.joinToString("\n\n") { (path, content) ->
                        "=== File: $path ===\n$content"
                    }
                }
            }
            "scaffoldToDir" -> {
                val allowed = setOf("type", "dir", "pluginName", "name", "domain", "basePackage",
                    "toolMethod", "toolDescription", "description",
                    "triggers", "tools", "purpose", "scriptType", "shell", "verify")
                validateArgs(args, allowed = allowed, required = setOf("type", "dir"), functionName)
                val type = paramString(args, "type", functionName)!!
                val dir = paramString(args, "dir", functionName)!!
                val verify = paramBool(args, "verify", functionName, required = false, default = false) ?: false
                val params = args.filterKeys { it !in setOf("type", "dir", "verify") }
                    .mapValues { it.value?.toString() ?: "" }
                    .filterValues { it.isNotEmpty() }
                    .toMutableMap()
                // Same defaults as scaffold: pdk version from VERSION, name → pluginName.
                if (type == "plugin" && !params.containsKey("pdkVersion")) {
                    val versionFile = fs.readFile("VERSION")
                    if (!versionFile.startsWith("Error:") && versionFile.isNotBlank()) {
                        params["pdkVersion"] = versionFile.trim()
                    }
                }
                if (type == "plugin" && !params.containsKey("pluginName") && params.containsKey("name")) {
                    params["pluginName"] = params["name"]!!
                }
                val files = ArtifactScaffolds.scaffold(type, params)
                if (files.size == 1 && files.containsKey("_content")) {
                    "scaffoldToDir materializes multi-file artifacts only (plugin). " +
                        "For type '$type' use scaffold to get the content."
                } else {
                    val written = mutableListOf<String>()
                    for ((relPath, content) in files) {
                        val target = "$dir/${relPath.removePrefix("/")}"
                        fs.writeFile(target, content)
                        written += target
                    }
                    val sb = StringBuilder(
                        "✓ Scaffolded $type into $dir (${written.size} files):\n${written.joinToString("\n")}"
                    )
                    if (type == "plugin") {
                        // Register the new module in the browser4-plugins aggregator
                        // pom so `mvn -pl browser4-plugins/<name> ...` resolves it.
                        val normalizedDir = dir.replace('\\', '/').trimEnd('/')
                        val module = normalizedDir.substringAfterLast('/')
                        if (normalizedDir.startsWith("browser4-plugins/") && module.isNotBlank()) {
                            val aggregator = fs.readFile("browser4-plugins/pom.xml")
                            if (!aggregator.startsWith("Error:") && !aggregator.contains("<module>$module</module>")) {
                                val updated = aggregator.replace(
                                    "</modules>",
                                    "        <module>$module</module>\n    </modules>"
                                )
                                if (updated != aggregator) {
                                    val writeResult = fs.writeFile("browser4-plugins/pom.xml", updated)
                                    if (writeResult.startsWith("✓")) {
                                        sb.append("\n✓ Registered module $module in browser4-plugins/pom.xml")
                                    }
                                }
                            }
                        }
                        if (verify) {
                            val resolved = fs.resolvePathString(dir)
                                ?: throw IllegalArgumentException("Path not allowed: $dir")
                            sb.append("\n\n--- Plugin validation ---\n")
                            sb.append(ArtifactValidator.validatePlugin(resolved).format())
                        }
                    }
                    sb.toString()
                }
            }
            "validate" -> {
                validateArgs(args, allowed = setOf("type", "path"), required = setOf("type"), functionName)
                val type = paramString(args, "type", functionName)!!
                val path = paramString(args, "path", functionName, required = false, default = null)
                when (type) {
                    "plugin" -> {
                        // Resolve through the fs sandbox before handing the raw path to the validator
                        val resolved = fs.resolvePathString(path ?: throw IllegalArgumentException("path is required for type 'plugin'"))
                            ?: throw IllegalArgumentException("Path not allowed: $path")
                        ArtifactValidator.validatePlugin(resolved).format()
                    }
                    "skill" -> {
                        val content = fs.readFile(path ?: throw IllegalArgumentException("path is required for type 'skill'"))
                        val result = ArtifactValidator.validateSkill(content, path)
                        // Cross-check every domain.method( reference in the skill body
                        // against the tools the agent can actually see/call.
                        val refIssues = ArtifactValidator.validateToolReferences(content, knownTools(), path)
                        ValidationResult.of(result.issues + refIssues).format()
                    }
                    "js" -> {
                        val content = fs.readFile(path ?: throw IllegalArgumentException("path is required for type 'js'"))
                        ArtifactValidator.validateJs(content, path).format()
                    }
                    "script" -> {
                        val content = fs.readFile(path ?: throw IllegalArgumentException("path is required for type 'script'"))
                        ArtifactValidator.validateScript(content, path).format()
                    }
                    "repo-consistency" -> repoConsistencyReport(fs)
                    else -> throw IllegalArgumentException(
                        "Unknown validate type: $type. Supported: plugin, skill, js, script, repo-consistency")
                }
            }
            "trapCheck" -> {
                validateArgs(args, allowed = setOf("path"), required = setOf("path"), functionName)
                val path = paramString(args, "path", functionName)!!
                val content = fs.readFile(path)
                if (content.startsWith("Error:")) return content
                CdpTrapCheck.format(content)
            }
            "protect" -> {
                validateArgs(args, allowed = setOf("path", "on"), required = emptySet(), functionName)
                val path = paramString(args, "path", functionName, required = false, default = null)
                if (path.isNullOrBlank()) return fs.protectedList()
                val on = paramBool(args, "on", functionName, required = false, default = true) ?: true
                fs.protect(path, on)
            }
            "devTask" -> {
                validateArgs(args, allowed = setOf("task", "verify", "runTests", "module"), required = setOf("task"), functionName)
                val task = paramString(args, "task", functionName)!!
                val verify = paramBool(args, "verify", functionName, required = false, default = false) ?: false
                val runTests = paramBool(args, "runTests", functionName, required = false, default = false) ?: false
                val moduleOverride = paramString(args, "module", functionName, required = false, default = null)

                // Resolve module mentions against the LIVE pom graph when possible.
                val graph = runCatching { scanModuleGraph(fs) }.getOrNull()
                val knownModules = graph?.nodes?.keys?.toList()?.takeIf { it.isNotEmpty() }
                    ?: ModuleMap.MODULES
                val plan = DevTaskPlanner.plan(task, knownModules)
                val modules = if (!moduleOverride.isNullOrBlank()) listOf(moduleOverride) else plan.modules
                val planText = buildString {
                    appendLine("Dev task plan (${plan.steps.size} steps):")
                    plan.steps.forEach { s ->
                        appendLine("  ${s.order}. [${s.tool}] ${s.purpose}")
                        appendLine("      ${s.command}")
                    }
                    appendLine("Signals: ${plan.summary}")
                    if (modules.isNotEmpty() && moduleOverride.isNullOrBlank()) {
                        appendLine("Inferred modules: ${modules.joinToString(", ")}")
                    }
                    if (moduleOverride != null) {
                        appendLine("Module override: $moduleOverride")
                    }
                    if (graph == null) {
                        appendLine("⚠ live pom graph unavailable — static ModuleMap used for normalization")
                    }
                }.trimEnd()

                if (!verify) return planText

                // verify=true: run the fast checks against the live workspace.
                val results = mutableListOf<String>()
                val mavenModule = modules.filter { it != ModuleMap.CLI_CRATE }
                    .maxByOrNull { it.count { c -> c == '/' } }
                if (mavenModule != null) {
                    results += "mvnBuild compile of $mavenModule:\n" +
                        mavenBuild.format(mavenBuild.build(shell, mavenModule, "compile", true, 300))
                }
                plan.driverFiles.take(1).forEach { file ->
                    val content = fs.readFile(file)
                    if (!content.startsWith("Error:")) {
                        results += "trapCheck on $file:\n${CdpTrapCheck.format(content)}"
                    }
                }
                results += "repo-consistency:\n${repoConsistencyReport(fs)}"

                // runTests=true: execute the module's test suite (the AGENTS.md
                // "smallest scope" step) after the compile check passed. When the
                // task named test classes, scope with -Dtest=... (smallest scope).
                if (runTests) {
                    if (mavenModule != null) {
                        val testArg = plan.testClasses.joinToString(",").ifBlank { null }
                        val cmd = ModuleMap.mavenTestCommand(mavenModule, testArg)
                        val r = shell.executeRaw(cmd, timeoutSeconds = 600)
                        results += "tests on $mavenModule${testArg?.let { " [-Dtest=$it]" } ?: ""} (exit ${r.exitCode}):\n" +
                            listOf(r.stdout, r.stderr).filter { it.isNotBlank() }.joinToString("\n").takeLast(3000)
                    }
                    if (ModuleMap.CLI_CRATE in modules) {
                        val cmd = ModuleMap.cargoTestCommand()
                        val r = shell.executeRaw(cmd, timeoutSeconds = 600)
                        results += "cargo tests (exit ${r.exitCode}):\n" +
                            listOf(r.stdout, r.stderr).filter { it.isNotBlank() }.joinToString("\n").takeLast(3000)
                    }
                }

                planText + "\n\n--- Verification ---\n" + results.joinToString("\n\n")
            }

            "tokenStats" -> {
                validateArgs(args, allowed = setOf("reset"), required = emptySet(), functionName)
                val report = tokenStats.report()
                if (paramBool(args, "reset", functionName, required = false, default = false) == true) {
                    tokenStats.reset()
                }
                report
            }
            "estimateTokens" -> {
                validateArgs(args, allowed = setOf("text"), required = setOf("text"), functionName)
                val text = paramString(args, "text", functionName)!!
                "≈ ${TokenEstimator.estimateTokens(text)} tokens (${text.length} chars)"
            }

            else -> throw IllegalArgumentException("Unsupported coding method: $functionName(${args.keys})")
        }
    }

    /**
     * `validate(type="repo-consistency")`: check the repo-governance invariants
     * against the live workspace — VERSION vs root pom vs BOM versions, module
     * registration (registered modules exist; on-disk module dirs are
     * registered), and plugin SDK versions (every in-repo plugin manifest
     * declares sdkVersion == VERSION). Zero dependencies; reads a few files +
     * directory listings.
     */
    private suspend fun repoConsistencyReport(fs: CodingAgentFileSystem): String {
        val versionContent = fs.readFile("VERSION").takeUnless { it.startsWith("Error:") }
        val rootPom = fs.readFile("pom.xml").takeUnless { it.startsWith("Error:") }
        val bomPom = fs.readFile("browser4-dependencies/pom.xml").takeUnless { it.startsWith("Error:") }

        val root = fs.workspaceRoot
        val (onDiskModuleDirs, pluginManifestContents) = withContext(Dispatchers.IO) {
            val moduleDirs = Files.list(root).use { stream ->
                stream.filter { Files.isDirectory(it) }
                    .filter { !it.fileName.toString().startsWith(".") }
                    .filter { Files.isRegularFile(it.resolve("pom.xml")) }
                    .map { root.relativize(it).toString().replace('\\', '/') }
                    .sorted()
                    .toList()
            }
            // Every in-repo plugin manifest outside build output (target/) and
            // hidden dirs (.git, .worktrees, .claude, ...) — other branches'
            // checkouts must not be scanned.
            val manifests = runCatching {
                Files.walk(root).use { stream ->
                    stream.filter { RepoConsistencyCheck.isPluginManifestPath(it) }
                        .map { Files.readString(it) }
                        .toList()
                }
            }.getOrDefault(emptyList())
            moduleDirs to manifests
        }

        val result = RepoConsistencyCheck.check(
            versionContent = versionContent,
            rootPom = rootPom,
            bomPom = bomPom,
            moduleExists = { m -> fs.exists(m) },
            onDiskModuleDirs = onDiskModuleDirs,
            pluginManifestContents = pluginManifestContents,
        )
        return result.format()
    }

    /**
     * Scan the live module graph from the workspace's real pom.xml files.
     * Powers `coding.moduleGraph` and `coding.impact` — anti-staleness: the
     * graph is rebuilt from the poms instead of a hand-maintained snapshot.
     */
    private suspend fun scanModuleGraph(fs: CodingAgentFileSystem): ModuleGraph.Graph {
        return ModuleGraph.build(ModuleGraph.scanPoms(fs.workspaceRoot))
    }

    /**
     * Assemble the map of tools the LLM agent can actually see/call, used for
     * cross-referencing tool names inside skills and other artifacts.
     *
     * Sources:
     * - [ToolCallSpecificationRenderer.collectAllToolSpecs] — hardcoded builtin
     *   domains (tab/browser/fs/agent/system) + dynamically registered builtin
     *   domains (coding/cli).
     * - [CustomToolRegistry] — executors registered by plugins/mounts (seo,
     *   captcha, image, markdown, media, pptx, command, crawl, html_snapshot,
     *   skill, swarm, webdb, ...).
     */
    private fun knownTools(): Map<String, Set<String>> {
        val tools = mutableMapOf<String, MutableSet<String>>()

        ToolCallSpecificationRenderer.collectAllToolSpecs().forEach { spec ->
            tools.getOrPut(spec.domain) { mutableSetOf() }.add(spec.method)
        }

        CustomToolRegistry.instance.getAllExecutors().forEach { executor ->
            val methods = executor.getToolSpecs().keys
            tools.getOrPut(executor.domain) { mutableSetOf() }.addAll(methods)
        }

        return tools.mapValues { it.value.toSet() }
    }

    /**
     * Infer the Maven module that owns a file path, for `coding.mvnBuild`.
     *
     * Browser4 layout: `<module>/src/...` for top-level modules (browser4-rest,
     * browser4-coding, browser4-boot, ...) and `<parent>/<module>/src/...` for
     * nested ones (browser4-core/browser4-common, browser4-plugins/browser4-seo,
     * browser4-apps/browser4-standalone). Best-effort: returns a module path the
     * agent can pass to `-pl`.
     */
    private fun inferModule(absolutePath: String): String {
        val norm = absolutePath.replace('\\', '/')
        val idx = norm.indexOf("/src/")
        if (idx <= 0) return norm.substringAfterLast('/')
        val before = norm.substring(0, idx)
        val segments = before.split('/').filter { it.isNotEmpty() }
        // Top-level module dir (browser4-rest) OR nested (browser4-core/browser4-common)
        return when {
            segments.size >= 2 && segments[segments.size - 2].startsWith("browser4-") ->
                segments.takeLast(2).joinToString("/")
            else -> segments.lastOrNull() ?: ""
        }
    }

    /** kebab/snake → PascalCase (my-tool → MyTool), for test-class names. */
    private fun toTestClassName(name: String): String =
        name.split('-', '_').filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
}


