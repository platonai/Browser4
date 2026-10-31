package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agent.tool.UserCommandExecutor
import ai.platon.pulsar.coding.AccessRequest
import ai.platon.pulsar.coding.AccessState
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Operator approval endpoints (plan P4-1, decision #3).
 *
 * The queue belongs to one agent task: `coding.requestAccess` records a pending item, and the
 * answer comes from whichever channel the operator uses. This controller is the REST channel; the
 * CLI slash commands and the strategy file are the other two, and all three issue the same single
 * `OPERATOR` capability through [ai.platon.pulsar.coding.AccessApprovals].
 *
 * A request that a rail forbids (`denyTools`, protected/credential paths, the workspace boundary,
 * the `DEV_COMMANDS` whitelist) is reported as `409 Conflict` with `state=denied`, so a client can
 * tell "not decided" from "cannot be decided".
 *
 * No CORS is enabled on purpose: approving grants file-write/command-execution capability, so a
 * web page the agent visits must never be able to read this queue or answer it cross-origin.
 */
@RestController
@RequestMapping("api/agent", produces = [MediaType.APPLICATION_JSON_VALUE])
class AgentAccessController(
    private val commandExecutor: UserCommandExecutor,
) {

    /**
     * The task's approval view: pending requests and operator grants in force.
     *
     * `404` means this backend has no live task with that id (it finished, or never belonged to
     * this process). Expired grants are dropped lazily by this call, like every decision.
     */
    @GetMapping("/{taskId}/access")
    fun access(@PathVariable taskId: String): ResponseEntity<Map<String, Any>> {
        val approvals = commandExecutor.accessApprovals(taskId)
            ?: return ResponseEntity.notFound().build()
        val pending = approvals.pending()
        val grants = approvals.activeCapabilities()
        return ResponseEntity.ok(
            mapOf(
                "taskId" to taskId,
                "state" to if (pending.isEmpty()) "idle" else "pending",
                "pending" to pending.map {
                    mapOf(
                        "requestId" to it.id,
                        "effect" to it.effect.id,
                        "target" to it.target.describe(),
                        "reason" to (it.reason ?: ""),
                    )
                },
                "grants" to grants.map {
                    mapOf(
                        "id" to it.id.value,
                        "effects" to it.effects.sortedBy { e -> e.id }.map { e -> e.id },
                        "expiresAt" to (it.expiresAt?.toString() ?: "task-end"),
                    )
                },
                "report" to approvals.describe(),
            )
        )
    }

    /** Approves one pending request, issuing an `OPERATOR` capability for the task. */
    @PostMapping("/{taskId}/access/{requestId}/approve")
    fun approve(
        @PathVariable taskId: String,
        @PathVariable requestId: String,
    ): ResponseEntity<Map<String, Any>> {
        val request = commandExecutor.approveAccess(taskId, requestId)
            ?: return ResponseEntity.notFound().build()
        return decision(request)
    }

    /**
     * Denies one pending request.
     *
     * `reason` is recorded in the audit entry and defaults to the model's own stated reason.
     */
    @PostMapping("/{taskId}/access/{requestId}/deny")
    fun deny(
        @PathVariable taskId: String,
        @PathVariable requestId: String,
        @RequestParam(name = "reason", required = false) reason: String? = null,
    ): ResponseEntity<Map<String, Any>> {
        val request = commandExecutor.denyAccess(taskId, requestId, reason)
            ?: return ResponseEntity.notFound().build()
        return decision(request)
    }

    private fun decision(request: AccessRequest): ResponseEntity<Map<String, Any>> {
        val body = mapOf(
            "requestId" to request.id,
            "state" to request.state.name.lowercase(),
            "effect" to request.effect.id,
            "target" to request.target.describe(),
            "expiresAt" to (request.expiresAt?.toString() ?: "task-end"),
        )
        return if (request.state == AccessState.DENIED) {
            ResponseEntity.status(HttpStatus.CONFLICT).body(body)
        } else {
            ResponseEntity.ok(body)
        }
    }
}
