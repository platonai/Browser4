package ai.platon.pulsar.rest.api.entities

import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskEvent

/**
 * One page of an agent task's live step log: `GET /api/commands/{id}/events`.
 *
 * The cursor model (rather than SSE) is deliberate — the CLI polls with
 * `?after=<nextSeq>`, which reconnects naturally, survives a proxy that buffers
 * responses, and needs no stream parsing on the client.
 *
 * @property taskId the task the events belong to
 * @property status the task's status word (`queued|running|done|failed|cancelled`)
 * @property done true when the task reached a terminal state; a follower stops
 *   polling (and reads the result) once it sees this
 * @property nextSeq cursor to pass as `after` on the next call: the sequence
 *   number of the last returned event, or the requested cursor when the page is
 *   empty
 * @property events the steps after the requested cursor, oldest first
 * */
data class CommandEventsResponse(
    val taskId: String,
    val status: String,
    val done: Boolean,
    val nextSeq: Long,
    val events: List<AgentTaskEvent> = emptyList(),
)

/**
 * Body of `POST /api/commands/{id}/input`: a steering message typed while the
 * task runs.
 *
 * @property text what the user wants the agent to do from now on; injected as a
 *   user message at the agent's next model round-trip
 * */
data class CommandInputRequest(
    val text: String = "",
)

/**
 * Answer of `POST /api/commands/{id}/input`.
 *
 * `accepted=false` is not an HTTP error: "the task already finished" is a
 * normal race for a human typing into a terminal, and the CLI reports it inline
 * instead of failing the session.
 *
 * @property taskId the task the message was aimed at
 * @property accepted true when the message was queued for a live task
 * @property message why it was or was not accepted
 * */
data class CommandInputResponse(
    val taskId: String,
    val accepted: Boolean,
    val message: String? = null,
)
