package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.rest.api.service.AgentSessionContextView
import ai.platon.pulsar.rest.api.service.AgentSessionsService
import ai.platon.pulsar.rest.api.service.AgentSessionsView
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The agent-session index the web console's sidebar draws (`agent.html`).
 *
 * Two endpoints, because the two views have different sizes:
 *
 * - `GET api/agent/sessions` — the workspace → session tree, cheap enough to poll
 *   (`refresh=true` bypasses the short reuse cache on an explicit refresh click);
 * - `GET api/agent/sessions/{sessionId}/context` — one session's remembered conversation
 *   turns plus the tasks it ran, fetched when the operator clicks it.
 *
 * ## Security
 *
 * CORS is enabled for the same reason [CommandController] enables it: the console page must
 * also work when opened from disk (`file://`), and it is the intended client. This grants no
 * capability — it exposes the transcript of sessions that the same page can already submit
 * to and read through the command endpoints — while [AgentAccessController] (approvals) stays
 * closed on purpose.
 *
 * ## Unknown sessions
 *
 * `404` is a real answer: the backend knows a session only while a task row or a
 * persisted conversation mentions it, and both are aged out by their TTLs. A client should
 * drop the row rather than retry.
 */
@CrossOrigin
@RestController
@RequestMapping("api/agent/sessions", produces = [MediaType.APPLICATION_JSON_VALUE])
class AgentSessionsController(
    private val sessionsService: AgentSessionsService,
) {

    /**
     * Every workspace with the sessions that ran in it, newest activity first.
     *
     * Sessions whose tasks never named a workspace (`agent run --workspace`) are grouped
     * under the temporary workspace; a session's directory is the one its **newest**
     * explicit workspace task used, so one conversation is never scattered across groups.
     */
    @GetMapping("")
    fun sessions(@RequestParam(required = false) refresh: Boolean?): AgentSessionsView =
        sessionsService.sessions(refresh == true)

    /**
     * One session's restored context: remembered conversation turns and task history.
     */
    @GetMapping("/{sessionId}/context")
    fun context(@PathVariable sessionId: String): ResponseEntity<AgentSessionContextView> =
        sessionsService.context(sessionId)?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
}
