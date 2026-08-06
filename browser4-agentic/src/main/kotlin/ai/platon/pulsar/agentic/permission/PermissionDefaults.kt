package ai.platon.pulsar.agentic.permission

/**
 * Built-in permission policy profiles for common agent roles.
 *
 * These are provided as convenience factories for tests and quick setup.
 * Production use should define policies in YAML files loaded via [PermissionRuleLoader].
 */
object PermissionDefaults {

    /**
     * Read-only profile: allow read/search, deny write/destructive.
     * Suitable for code-analysis and researcher agents.
     */
    fun readOnlyProfile(): PermissionPolicy = PermissionPolicy(
        name = "read-only",
        defaultMode = PermissionMode.ALLOW,
        rules = listOf(
            // Block all write operations on coding domain
            PermissionRule(
                id = "readonly.no-write",
                domain = "coding",
                method = "write|append|replace|delete|mkdir|copy|move",
                mode = PermissionMode.DENY,
                reason = "Code-analysis agents are read-only",
            ),
            // Block destructive on fs domain
            PermissionRule(
                id = "readonly.no-fs-write",
                domain = "fs",
                method = "writeString|append|replaceContent|deleteFile|copyFile|moveFile",
                mode = PermissionMode.DENY,
                reason = "Code-analysis agents are read-only",
            ),
            // Ask for navigation
            PermissionRule(
                id = "readonly.navigate-ask",
                domain = "tab",
                method = "open|navigate",
                mode = PermissionMode.ASK,
                reason = "Navigation requires confirmation for read-only agents",
            ),
            // Allow read actions
            PermissionRule(
                id = "readonly.tab-allow",
                domain = "tab",
                method = "*",
                mode = PermissionMode.ALLOW,
                actionClass = ActionClass.READ,
            ),
        ),
    )

    /**
     * Full-access profile: allow everything (default policy).
     * Suitable for dev agents with full trust.
     */
    fun fullAccessProfile(): PermissionPolicy = PermissionPolicy(
        name = "full-access",
        defaultMode = PermissionMode.ALLOW,
        rules = listOf(
            // Deny dangerous destructive commands
            PermissionRule(
                id = "full.no-rm-root",
                domain = "coding",
                method = "shell",
                mode = PermissionMode.DENY,
                pattern = "rm -rf /*",
                resource = ResourceType.COMMAND,
                patternType = PatternType.GLOB,
                reason = "Deleting root filesystem is permanently blocked",
            ),
            PermissionRule(
                id = "full.no-shutdown",
                domain = "coding",
                method = "shell",
                mode = PermissionMode.DENY,
                pattern = "shutdown*",
                resource = ResourceType.COMMAND,
                patternType = PatternType.GLOB,
                reason = "System shutdown is blocked",
            ),
        ),
    )

    /**
     * Tab-risk profile: ask for navigation and eval, allow reads.
     * Suitable for browser-automation agents where you want to control page changes.
     */
    fun tabRiskProfile(): PermissionPolicy = PermissionPolicy(
        name = "tab-risk",
        defaultMode = PermissionMode.ALLOW,
        rules = listOf(
            PermissionRule(
                id = "tab.navigate-ask",
                domain = "tab",
                method = "open|navigate",
                mode = PermissionMode.ASK,
                reason = "Navigation requires confirmation",
            ),
            PermissionRule(
                id = "tab.eval-ask",
                domain = "tab",
                method = "eval|evaluateValue|evaluateValueDetail",
                mode = PermissionMode.ASK,
                reason = "JavaScript evaluation requires confirmation",
            ),
            PermissionRule(
                id = "tab.write-ask",
                domain = "tab",
                method = "*",
                mode = PermissionMode.ASK,
                actionClass = ActionClass.WRITE,
                reason = "DOM modifications require confirmation",
            ),
        ),
    )

    /**
     * Strict deny-by-default profile: only explicitly allowed operations pass.
     */
    fun strictProfile(): PermissionPolicy = PermissionPolicy(
        name = "strict",
        defaultMode = PermissionMode.DENY,
        rules = listOf(
            PermissionRule(
                id = "strict.allow-tab-read",
                domain = "tab",
                method = "*",
                mode = PermissionMode.ALLOW,
                actionClass = ActionClass.READ,
            ),
            PermissionRule(
                id = "strict.allow-coding-read",
                domain = "coding",
                method = "read|readLines|glob|grep|stat|diff|changeSummary|listDir|workspaceRoot",
                mode = PermissionMode.ALLOW,
            ),
            PermissionRule(
                id = "strict.allow-fs-read",
                domain = "fs",
                method = "readString|fileExists|getFileInfo|listFiles",
                mode = PermissionMode.ALLOW,
            ),
            PermissionRule(
                id = "strict.allow-system",
                domain = "system",
                method = "*",
                mode = PermissionMode.ALLOW,
            ),
        ),
    )
}
