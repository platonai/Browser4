package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.coding.ArtifactScaffolds
import ai.platon.pulsar.coding.ArtifactValidator
import ai.platon.pulsar.coding.CodeRunner
import ai.platon.pulsar.coding.CodingAgentFileSystem
import ai.platon.pulsar.coding.CodingAgentShell
import ai.platon.pulsar.coding.DevFlowScaffolds
import ai.platon.pulsar.coding.LanguageServerManager
import ai.platon.pulsar.coding.KotlinSemanticIndexer
import ai.platon.pulsar.coding.MavenBuildSupport
import ai.platon.pulsar.coding.SkeletonExtractor
import ai.platon.pulsar.coding.ValidationResult
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.CustomToolRegistry
import ai.platon.pulsar.agentic.tools.specs.ToolCallSpecificationRenderer
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
            ),
            returnType = "String",
            description = "Find references to a Kotlin symbol in a .kt file — call sites and property usages — " +
                "via lightweight language-structure analysis (zero dependencies). " +
                "Use before refactoring Browser4 code to assess impact."
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
            ),
            returnType = "String",
            description = "Generate a skeleton from an EXISTING reference file (e.g. an installed plugin's " +
                "ToolExecutor or Service): reads the real code, parameterizes volatile identifiers " +
                "(package, class name, domain, tool method) into placeholders, then instantiates with new " +
                "values. Because the template comes from the repository's own code, it never goes stale — " +
                "unlike hand-written scaffolds. Provide path (reference file) plus any of basePackage/className/" +
                "domain/toolMethod to rename; omit to see the discovered parameters."
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
            ),
            returnType = "String",
            description = "Generate a multi-file development-flow skeleton for Browser4 self-development. " +
                "type: 'b4-cli-command' (new CLI command → commands.rs CommandDef + MCPToolController alias + " +
                "backend method + test) or 'agent-tool' (new tool domain → ToolExecutor + ToolMount auto-config). " +
                "Identifiers derive from name; cross-file consistency is automatic. " +
                "For b4-cli-command: name=kebab-case, description, category (e.g. Extract/Swarm), toolName (snake). " +
                "For agent-tool: name=plugin name, domain, basePackage, toolMethod, description."
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
        toolSpec["validate"] = ToolSpec(
            domain = domain, method = "validate",
            arguments = listOf(
                ToolSpec.Arg("type", "String"),
                ToolSpec.Arg("path", "String"),
            ),
            returnType = "String",
            description = "Validate a Browser4 plugin directory, skill file, JS file, or script file. " +
                "type: 'plugin' (path=plugin dir) | 'skill' | 'js' | 'script' (path=file path). " +
                "Returns a list of issues with severity (error/warning/info)."
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
                validateArgs(args, allowed = setOf("path"), required = setOf("path"), functionName)
                fs.readFile(paramString(args, "path", functionName)!!)
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
                validateArgs(args, allowed = setOf("path", "symbol"), required = setOf("path", "symbol"), functionName)
                val path = paramString(args, "path", functionName)!!
                val symbol = paramString(args, "symbol", functionName)!!
                val resolved = fs.resolvePathString(path)
                    ?: throw IllegalArgumentException("Path not allowed: $path")
                val content = fs.readFile(resolved)
                val refs = kotlinIndexer.references(content, symbol, resolved.substringAfterLast('/').substringAfterLast('\\'))
                if (refs.isEmpty()) "No references to '$symbol' found in $path"
                else refs.joinToString("\n") { "line ${it.line}: ${it.snippet}" }
            }
            "scaffoldFromExample" -> {
                validateArgs(args, allowed = setOf("path", "basePackage", "className", "domain", "toolMethod"),
                    required = setOf("path"), functionName)
                val path = paramString(args, "path", functionName)!!
                val resolved = fs.resolvePathString(path)
                    ?: throw IllegalArgumentException("Path not allowed: $path")
                val content = fs.readFile(resolved)
                val skeleton = SkeletonExtractor.extract(content, resolved.substringAfterLast('/').substringAfterLast('\\'))
                val renameParams: Map<String, String> = mapOf(
                    "basePackage" to paramString(args, "basePackage", functionName, required = false, default = null),
                    "className" to paramString(args, "className", functionName, required = false, default = null),
                    "domain" to paramString(args, "domain", functionName, required = false, default = null),
                    "toolMethod" to paramString(args, "toolMethod", functionName, required = false, default = null),
                ).filterValues { !it.isNullOrBlank() }.mapValues { it.value!! }

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
            "scaffoldFlow" -> {
                validateArgs(args, allowed = setOf("type", "name", "description", "category",
                    "toolName", "domain", "basePackage", "toolMethod"),
                    required = setOf("type", "name"), functionName)
                val type = paramString(args, "type", functionName)!!
                val name = paramString(args, "name", functionName)!!
                val description = paramString(args, "description", functionName, required = false, default = "") ?: ""
                val category = paramString(args, "category", functionName, required = false, default = null)
                val toolName = paramString(args, "toolName", functionName, required = false, default = null)
                val domain = paramString(args, "domain", functionName, required = false, default = null)
                val basePackage = paramString(args, "basePackage", functionName, required = false, default = null)
                val toolMethod = paramString(args, "toolMethod", functionName, required = false, default = null)

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
                files.entries.joinToString("\n\n") { (path, content) ->
                    "=== File: $path ===\n$content"
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
                    "triggers", "tools", "purpose", "scriptType", "shell")
                validateArgs(args, allowed = allowed, required = setOf("type"), functionName)
                val type = paramString(args, "type", functionName)!!
                val params = args.filterKeys { it != "type" }
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
                val result = ArtifactScaffolds.scaffold(type, params)
                if (result.size == 1 && result.containsKey("_content")) {
                    result["_content"]!!
                } else {
                    result.entries.joinToString("\n\n") { (path, content) ->
                        "=== File: $path ===\n$content"
                    }
                }
            }
            "validate" -> {
                validateArgs(args, allowed = setOf("type", "path"), required = setOf("type", "path"), functionName)
                val type = paramString(args, "type", functionName)!!
                val path = paramString(args, "path", functionName)!!
                when (type) {
                    "plugin" -> {
                        // Resolve through the fs sandbox before handing the raw path to the validator
                        val resolved = fs.resolvePathString(path)
                            ?: throw IllegalArgumentException("Path not allowed: $path")
                        ArtifactValidator.validatePlugin(resolved).format()
                    }
                    "skill" -> {
                        val content = fs.readFile(path)
                        val result = ArtifactValidator.validateSkill(content, path)
                        // Cross-check every domain.method( reference in the skill body
                        // against the tools the agent can actually see/call.
                        val refIssues = ArtifactValidator.validateToolReferences(content, knownTools(), path)
                        ValidationResult.of(result.issues + refIssues).format()
                    }
                    "js" -> {
                        val content = fs.readFile(path)
                        ArtifactValidator.validateJs(content, path).format()
                    }
                    "script" -> {
                        val content = fs.readFile(path)
                        ArtifactValidator.validateScript(content, path).format()
                    }
                    else -> throw IllegalArgumentException("Unknown validate type: $type. Supported: plugin, skill, js, script")
                }
            }

            else -> throw IllegalArgumentException("Unsupported coding method: $functionName(${args.keys})")
        }
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

