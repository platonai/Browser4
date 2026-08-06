package ai.platon.pulsar.agentic.permission

/**
 * Classifies tool domain+method pairs into broad [ActionClass] categories.
 *
 * The classification mirrors the existing private sets in
 * [ai.platon.pulsar.agentic.tools.builtin.BrowserTabToolExecutor]
 * and [ai.platon.pulsar.agentic.common.CodingAgentShell] so that permission rules
 * can gate on action risk tiers without duplicating per-method lists in every policy.
 */
class ToolActionClassifier {

    /**
     * Returns the [ActionClass] for a given domain and method.
     */
    fun classify(domain: String, method: String): ActionClass {
        return when (domain) {
            "tab" -> classifyTabAction(method)
            "browser" -> classifyBrowserAction(method)
            "coding" -> classifyCodingAction(method)
            "fs" -> classifyFsAction(method)
            "shell" -> classifyShellAction(method)
            "cli" -> classifyCliAction(method)
            "agent" -> classifyAgentAction(method)
            else -> ActionClass.ANY
        }
    }

    // ---- tab domain ----

    private fun classifyTabAction(method: String): ActionClass = when (method) {
        // Read-only state inspection
        in TAB_READ_ACTIONS -> ActionClass.READ
        // Navigation
        in TAB_NAVIGATE_ACTIONS -> ActionClass.NAVIGATE
        // JS evaluation
        in TAB_EVAL_ACTIONS -> ActionClass.WRITE
        // DOM-affecting write actions
        in TAB_WRITE_ACTIONS -> ActionClass.WRITE
        // Destructive
        in TAB_DESTRUCTIVE_ACTIONS -> ActionClass.DESTRUCTIVE
        // Unknown
        else -> ActionClass.ANY
    }

    // ---- coding domain ----

    private fun classifyCodingAction(method: String): ActionClass = when (method) {
        // File read / search
        "read", "readLines", "glob", "grep", "stat", "diff",
        "changeSummary", "listDir", "languages", "workspaceRoot" -> ActionClass.READ
        // File write
        "write", "append", "replace", "mkdir", "copy", "move" -> ActionClass.WRITE
        // File delete
        "delete" -> ActionClass.DESTRUCTIVE
        // Shell — classification depends on the command string, caller must refine
        "shell", "shellOutput", "shellStatus", "shellList" -> ActionClass.ANY
        else -> ActionClass.ANY
    }

    // ---- fs domain (deprecated, but still supported) ----

    private fun classifyFsAction(method: String): ActionClass = when (method) {
        "readString", "fileExists", "getFileInfo", "listFiles" -> ActionClass.READ
        "writeString", "append", "replaceContent", "copyFile", "moveFile" -> ActionClass.WRITE
        "deleteFile" -> ActionClass.DESTRUCTIVE
        else -> ActionClass.ANY
    }

    // ---- shell domain (AgentShell — read-only whitelist) ----

    private fun classifyShellAction(method: String): ActionClass = when (method) {
        "execute", "readOutput", "getStatus", "listSessions" -> ActionClass.READ
        else -> ActionClass.ANY
    }

    // ---- cli domain ----

    private fun classifyCliAction(method: String): ActionClass = when (method) {
        "run" -> ActionClass.ANY   // depends on the command string
        "version", "help" -> ActionClass.READ
        else -> ActionClass.ANY
    }

    // ---- browser domain ----

    private fun classifyBrowserAction(method: String): ActionClass = when (method) {
        "listTabs" -> ActionClass.READ
        "switchTab" -> ActionClass.ANY       // navigation-like but no URL change
        "newTab", "closeTab" -> ActionClass.WRITE
        else -> ActionClass.ANY
    }

    // ---- agent domain ----

    private fun classifyAgentAction(method: String): ActionClass = when (method) {
        "observe", "extract" -> ActionClass.READ
        "act", "run", "summarize", "done" -> ActionClass.WRITE
        else -> ActionClass.ANY
    }

    // ---- further refinement for commands ----

    /**
     * Refine the action class for a shell/coding command by inspecting the command string.
     *
     * This mirrors [ai.platon.pulsar.agentic.common.CodingAgentShell]'s tiered
     * category system (SAFE_COMMANDS, DEV_COMMANDS, NETWORK_COMMANDS, DESTRUCTIVE_COMMANDS).
     */
    fun classifyCommand(command: String?, baseAction: ActionClass): ActionClass {
        if (command.isNullOrBlank()) return baseAction

        val baseCommand = command.trimStart()
            .split(Regex("\\s+")).firstOrNull()?.lowercase() ?: return baseAction

        return when {
            baseCommand in DESTRUCTIVE_COMMANDS -> ActionClass.DESTRUCTIVE
            baseCommand in GIT_COMMANDS -> ActionClass.GIT
            baseCommand in DEV_COMMANDS -> ActionClass.DEV_TOOL
            baseCommand in NETWORK_COMMANDS -> ActionClass.WRITE   // network = write side effect
            else -> baseAction
        }
    }

    companion object {
        // Mirrors BrowserTabToolExecutor READ_PAGE_STATE_ACTIONS (public superset)
        private val TAB_READ_ACTIONS = setOf(
            "waitForSelector", "waitForNavigation", "waitForPage",
            "exists", "isVisible", "visible", "isHidden", "isChecked",
            "ariaSnapshot", "title", "screenshot",
            "outerHTML", "textContent", "nanoDOMTree",
            "selectFirstTextOrNull", "selectTextAll",
            "selectFirstAttributeOrNull", "selectAttributes", "selectAttributeAll",
            "selectFirstPropertyValueOrNull", "selectPropertyValueAll",
            "clickablePoint", "boundingBox",
            "getCookies", "saveStorageState",
            "currentUrl", "url", "documentURI", "baseURI", "referrer", "pageSource",
            "select", "matches", "querySelector",
        )

        // Mirrors BrowserTabToolExecutor NAVIGATION_TRIGGERING_ACTIONS + navigation methods
        private val TAB_NAVIGATE_ACTIONS = setOf(
            "open", "navigate", "goBack", "goForward", "reload",
        )

        // Mirrors BrowserTabToolExecutor DOM_AFFECTING_ACTIONS (write subset)
        private val TAB_WRITE_ACTIONS = setOf(
            "focus", "hover", "type", "fill", "press", "click", "dblclick",
            "upload", "selectOption", "dialogAccept", "dialogDismiss",
            "keydown", "keyDown", "keyup", "keyUp",
            "mousedown", "mouseDown", "mouseup", "mouseUp",
            "mouseWheel", "mouseWheelDown", "mouseWheelUp",
            "scrollDown", "scrollUp", "scrollBy", "scrollTo", "scrollToTop", "scrollToBottom",
            "scrollToMiddle", "scrollToViewport",
            "dragAndDrop", "drag", "clickTextMatches", "clickMatches",
            "check", "uncheck", "setAttribute", "setAttributeAll", "setProperty", "setPropertyAll",
            "loadStorageState",
        )

        private val TAB_EVAL_ACTIONS = setOf(
            "evaluate", "evaluateDetail", "eval", "evaluateValue", "evaluateValueDetail",
        )

        private val TAB_DESTRUCTIVE_ACTIONS = setOf(
            "clearBrowserCookies", "deleteCookies",
        )

        // Mirrors CodingAgentShell command categories
        private val DESTRUCTIVE_COMMANDS = setOf(
            "rm", "del", "rmdir", "mv", "move", "cp", "copy", "xcopy",
            "chmod", "chown", "icacls", "ln", "mklink", "mount",
            "dd", "mkfs", "fdisk", "kill", "killall", "pkill", "taskkill",
            "shutdown", "reboot", "systemctl", "service", "sc",
        )

        private val DEV_COMMANDS = setOf(
            "git", "svn", "hg", "java", "javac", "mvn", "mvnw", "gradle", "gradlew",
            "kotlin", "kotlinc", "cargo", "rustc", "rustup", "rustfmt",
            "node", "npm", "npx", "yarn", "pnpm", "tsc", "webpack", "vite", "esbuild",
            "python", "python3", "pip", "pip3", "poetry", "uv", "pytest", "black", "ruff",
            "gcc", "g++", "clang", "clang++", "make", "cmake", "ninja",
            "go", "gofmt", "dotnet", "bash", "sh", "zsh", "powershell", "pwsh",
            "apt", "apt-get", "brew", "choco", "winget", "yum", "dnf", "pacman",
            "sqlite3", "psql", "mysql", "docker", "kubectl", "helm",
            "terraform", "tofu", "aws", "gcloud", "az", "ssh", "scp",
        )

        private val GIT_COMMANDS = setOf("git")

        private val NETWORK_COMMANDS = setOf(
            "curl", "wget", "nc", "telnet", "nslookup", "dig",
        )
    }
}
