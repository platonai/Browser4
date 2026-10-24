package ai.platon.pulsar.rest.api.entities

import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskEvent
import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskRunStats

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
 * The aggregate run statistics of an agent task:
 * `GET /api/commands/{id}/stats`.
 *
 * The question this answers is "what did this run cost me": how many LLM calls,
 * how many tool calls (and how many failed), and how many tokens the provider
 * billed. Counters come from the task runner's event log, which counts every
 * step as it is appended — some steps may already have been evicted from the
 * bounded buffer, so a client cannot reconstruct these numbers by counting the
 * events it received.
 *
 * @property taskId the task the statistics belong to
 * @property status the task's status word (`queued|running|done|failed|cancelled`,
 *   or `unknown` when the status row expired while the counters survived)
 * @property processState raw process state (`created|in_progress|done`)
 * @property isDone true when the task reached a terminal state
 * @property elapsedMs wall-clock run time so far (or the final duration); `null`
 *   when the task never started
 * @property stats the counters
 * */
data class CommandStatsResponse(
    val taskId: String,
    val status: String,
    val processState: String,
    val isDone: Boolean,
    val elapsedMs: Long? = null,
    val stats: AgentTaskRunStats = AgentTaskRunStats(),
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
