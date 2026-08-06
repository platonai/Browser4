package ai.platon.pulsar.agentic.permission

import ai.platon.pulsar.agentic.model.ToolCall

/**
 * The scope at which a permission rule applies.
 */
enum class RuleScope {
    /** Applies to all agents and sessions. */
    GLOBAL,
    /** Applies only to a specific agent (matched by agent UUID). */
    AGENT,
    /** Applies only to a specific session (matched by session ID). */
    SESSION
}

/**
 * How a rule's [pattern] is interpreted.
 */
enum class PatternType {
    /** Literal string equality (case-insensitive for commands). */
    EXACT,
    /** Glob pattern with `*`, `**`, `?` wildcards. */
    GLOB,
    /** Full-string regular expression match. */
    REGEX
}

/**
 * The type of resource a rule's [pattern] matches against.
 */
enum class ResourceType {
    /** No resource pattern — rule matches on domain+method+actionClass alone. */
    NONE,
    /** Shell command string. */
    COMMAND,
    /** File system path. */
    PATH,
    /** URL string. */
    URL,
    /** JavaScript / evaluation script. */
    SCRIPT
}

/**
 * Broad classification of a tool action by risk category.
 *
 * Used as an additional gating dimension beyond domain+method matching.
 */
enum class ActionClass {
    /** Matches any action (no filter). */
    ANY,
    /** Read-only operations: read, glob, grep, stat, listDir, etc. */
    READ,
    /** Write operations: write, append, replace, mkdir, etc. */
    WRITE,
    /** Destructive operations: delete, rm, clear, uninstall, etc. */
    DESTRUCTIVE,
    /** Page navigation: open, navigate, goBack, goForward, reload. */
    NAVIGATE,
    /** Code search: glob, grep, find. */
    SEARCH,
    /** Git operations. */
    GIT,
    /** Dev tool invocations: mvn, cargo, npm, etc. */
    DEV_TOOL,
    /** Access to paths outside the agent workspace. */
    EXTERNAL_ACCESS
}

/**
 * A single permission rule that controls whether a tool call is allowed, denied, or
 * requires user confirmation.
 *
 * Rules are evaluated by [PermissionEvaluator] with a precedence model:
 * scoped > global, exact domain+method > wildcard, DENY > ASK > ALLOW on tie.
 *
 * @param id unique rule identifier (e.g. "no-force-push")
 * @param domain normalized top domain ("tab", "coding", "fs", etc.) or "*" for any
 * @param method tool method name, "*" for any, or "|"-separated alternation
 * @param mode the permission outcome when this rule matches
 * @param actionClass optional action-class filter; [ActionClass.ANY] matches everything
 * @param pattern optional value to match against the resource (command, path, url, script)
 * @param resource what the pattern matches against; [ResourceType.NONE] when pattern is unused
 * @param patternType how to interpret [pattern]
 * @param priority tie-breaker; higher wins. Default 0.
 * @param scope who this rule applies to
 * @param scopeValue agent UUID / session ID when scope is not GLOBAL, or agent-name glob pattern
 * @param reason human-readable explanation shown in deny/ask messages
 */
data class PermissionRule(
    val id: String,
    val domain: String,
    val method: String = "*",
    val mode: PermissionMode,
    val actionClass: ActionClass = ActionClass.ANY,
    val pattern: String? = null,
    val resource: ResourceType = ResourceType.NONE,
    val patternType: PatternType = PatternType.GLOB,
    val priority: Int = 0,
    val scope: RuleScope = RuleScope.GLOBAL,
    val scopeValue: String? = null,
    val reason: String? = null,
)

/**
 * A named collection of permission rules with a fallback default mode.
 *
 * @param version schema version (currently 1)
 * @param name human-readable policy name
 * @param defaultMode fallback when no rule matches a request
 * @param rules ordered list of rules; evaluated by specificity, not list order
 */
data class PermissionPolicy(
    val version: Int = 1,
    val name: String = "default",
    val defaultMode: PermissionMode = PermissionMode.ALLOW,
    val rules: List<PermissionRule> = emptyList(),
)

/**
 * The outcome of evaluating a permission request against a policy.
 */
sealed interface PermissionDecision {
    /** The operation is permitted. */
    data class Allowed(
        val rule: PermissionRule?,
        val reason: String,
    ) : PermissionDecision

    /** The operation requires user confirmation before proceeding. */
    data class Ask(
        val rule: PermissionRule,
        val request: PermissionRequest,
    ) : PermissionDecision

    /** The operation is blocked. */
    data class Denied(
        val rule: PermissionRule?,
        val reason: String,
    ) : PermissionDecision
}

/**
 * A permission check request produced by [ToolCallPermissionAnalyzer] from a [ToolCall].
 *
 * @param toolCall the original (normalized) tool call being checked
 * @param agentId the agent's UUID ([ai.platon.pulsar.agentic.agents.BasicBrowserAgent.uuid])
 * @param sessionId the session identifier
 * @param domain the normalized top domain after alias resolution
 * @param method the tool method name
 * @param actionClass broad classification of the action
 * @param command extracted shell command (for shell/coding/cli domains)
 * @param path extracted file path (for fs/coding file operations)
 * @param url extracted URL (for tab navigation operations)
 * @param script extracted JavaScript (for tab eval operations)
 * @param message human-readable summary of what is being requested
 */
data class PermissionRequest(
    val toolCall: ToolCall,
    val agentId: String,
    val sessionId: String,
    val domain: String,
    val method: String,
    val actionClass: ActionClass,
    val command: String? = null,
    val path: String? = null,
    val url: String? = null,
    val script: String? = null,
    val message: String = "permission required: $domain.$method",
)

/**
 * Response from a [PermissionAskHandler] callback.
 *
 * @param granted true to allow, false to deny
 * @param note optional user-provided note attached to the decision
 */
data class PermissionResponse(
    val granted: Boolean,
    val note: String? = null,
)
