package ai.platon.pulsar.agentic.permission

/**
 * Callback invoked when a permission rule with mode [PermissionMode.ASK] matches.
 *
 * Implementations can prompt the user (CLI, REST API, WebSocket) and return a
 * [PermissionResponse] to allow or deny the operation.
 *
 * Returning `null` signals that no handler is available; the [PermissionManager]
 * will throw [PermissionRequestedException] instead.
 */
fun interface PermissionAskHandler {
    /**
     * Called when a tool call requires user confirmation.
     *
     * @param request the permission request with all extracted context
     * @return a response granting or denying the request, or null to fall through to exception
     */
    suspend fun onPermissionRequest(request: PermissionRequest): PermissionResponse?
}
