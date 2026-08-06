package ai.platon.pulsar.agentic.permission

import ai.platon.pulsar.agentic.model.ToolCall

/**
 * Analyzes a (normalized) [ToolCall] to produce a [PermissionRequest] with extracted
 * resource data (command, path, url, script) and an action classification.
 *
 * @param classifier the action classifier used to determine [ActionClass] per domain+method
 */
class ToolCallPermissionAnalyzer(
    private val classifier: ToolActionClassifier = ToolActionClassifier(),
) {

    /**
     * Analyzes [tc] within [topDomain] and returns a [PermissionRequest] ready for evaluation.
     *
     * @param tc the normalized tool call
     * @param topDomain the resolved top-level domain after alias normalization
     * @param agentId the agent UUID
     * @param sessionId the session identifier
     */
    fun analyze(
        tc: ToolCall,
        topDomain: String,
        agentId: String = "",
        sessionId: String = "",
    ): PermissionRequest {
        val method = tc.method
        var actionClass = classifier.classify(topDomain, method)

        val command = extractCommand(topDomain, method, tc)
        val path = extractPath(topDomain, method, tc)
        val url = extractUrl(topDomain, method, tc)
        val script = extractScript(topDomain, method, tc)

        // Refine classification when a command string is available
        if (command != null) {
            actionClass = classifier.classifyCommand(command, actionClass)
        }

        // Detect external access from paths outside the workspace
        if (path != null && isExternalPath(path)) {
            if (actionClass == ActionClass.READ || actionClass == ActionClass.ANY) {
                actionClass = ActionClass.EXTERNAL_ACCESS
            }
        }

        val message = buildMessage(topDomain, method, actionClass, command, path, url)

        return PermissionRequest(
            toolCall = tc,
            agentId = agentId,
            sessionId = sessionId,
            domain = topDomain,
            method = method,
            actionClass = actionClass,
            command = command,
            path = path,
            url = url,
            script = script,
            message = message,
        )
    }

    // ---- resource extractors ----

    private fun extractCommand(domain: String, method: String, tc: ToolCall): String? {
        val args = tc.arguments
        return when {
            domain == "coding" && method == "shell" -> args["command"]?.toString()
            domain == "cli" && method == "run" -> args["command"]?.toString()
            else -> null
        }
    }

    private fun extractPath(domain: String, method: String, tc: ToolCall): String? {
        val args = tc.arguments
        return when (domain) {
            "coding" -> when (method) {
                "read", "readLines", "write", "append", "replace",
                "delete", "glob", "grep", "stat", "diff", "changeSummary" -> args["path"]?.toString()
                "copy", "move" -> args["source"]?.toString() ?: args["dest"]?.toString()
                "mkdir" -> args["path"]?.toString()
                else -> null
            }
            "fs" -> when (method) {
                "readString", "writeString", "append", "replaceContent",
                "deleteFile", "getFileInfo" -> args["fullFileName"]?.toString()
                "copyFile", "moveFile" -> args["source"]?.toString() ?: args["target"]?.toString()
                "listFiles" -> args["path"]?.toString()
                else -> null
            }
            else -> null
        }
    }

    private fun extractUrl(domain: String, method: String, tc: ToolCall): String? {
        val args = tc.arguments
        return when (domain) {
            "tab" -> when (method) {
                "open", "navigate" -> args["url"]?.toString()
                    ?: args["rawUrl"]?.toString()
                    ?: args["pageUrl"]?.toString()
                else -> null
            }
            else -> null
        }
    }

    private fun extractScript(domain: String, method: String, tc: ToolCall): String? {
        val args = tc.arguments
        return when (domain) {
            "tab" -> when (method) {
                "eval", "evaluateValue", "evaluateValueDetail",
                "evaluate", "evaluateDetail" ->
                    args["expression"]?.toString()
                        ?: args["functionDeclaration"]?.toString()
                else -> null
            }
            else -> null
        }
    }

    // ---- helpers ----

    /**
     * Heuristic check for paths outside the agent's workspace directory.
     * Absolute paths, parent-directory traversal, and system directories are flagged.
     */
    private fun isExternalPath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return when {
            normalized.startsWith("/") && !normalized.startsWith("/tmp/") -> true
            normalized.startsWith("~/") -> true
            normalized.contains("../") -> true
            normalized.startsWith("C:") || normalized.startsWith("D:") -> true
            else -> false
        }
    }

    private fun buildMessage(
        domain: String,
        method: String,
        actionClass: ActionClass,
        command: String?,
        path: String?,
        url: String?,
    ): String {
        val sb = StringBuilder("$domain.$method")
        when {
            command != null -> sb.append(" '${command.take(80)}'")
            path != null -> sb.append(" path='${path.take(80)}'")
            url != null -> sb.append(" url='${url.take(80)}'")
        }
        if (actionClass != ActionClass.ANY) {
            sb.append(" [${actionClass.name.lowercase()}]")
        }
        return sb.toString()
    }
}
