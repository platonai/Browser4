package ai.platon.pulsar.agentic.permission

/**
 * The three permission modes for the Browser4 Permission System.
 *
 * - [ALLOW]: automatically execute the operation
 * - [ASK]: request user confirmation before executing
 * - [DENY]: block the operation entirely
 */
enum class PermissionMode {
    ALLOW,
    ASK,
    DENY
}
