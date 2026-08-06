package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.common.CodingAgentFileSystem
import ai.platon.pulsar.agentic.common.CodingAgentShell
import ai.platon.pulsar.agentic.model.ToolSpec
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
 */
class CodingToolExecutor : AbstractToolExecutor() {

    override val domain = "coding"

    override val receiverClass: KClass<*> = CodingToolExecutor.Target::class

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
            arguments = listOf(ToolSpec.Arg("path", "String")),
            returnType = "String",
            description = "Show diff between snapshot and current content of a file"
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
                fs.diff(paramString(args, "path", functionName)!!)
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

            else -> throw IllegalArgumentException("Unsupported coding method: $functionName(${args.keys})")
        }
    }
}
