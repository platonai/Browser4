package ai.platon.pulsar.rest.api.entities

import ai.platon.pulsar.agentic.tools.advanced.crawl.PageVisitRequest

typealias CommandRequest = PageVisitRequest

/**
 * The acknowledgement of an asynchronous command submission.
 *
 * `POST /api/commands` and `POST /api/commands/json` answer an `async=true` request with
 * `202 Accepted` and this object. It is a real JSON object (`{"id": "..."}`) rather than a
 * bare id string, so the body stays parseable under the endpoint's declared
 * `application/json` content type.
 *
 * @property id The task id to poll with `GET /api/commands/{id}/status`, read with
 *   `GET /api/commands/{id}/result`, and stream with `GET /api/commands/{id}/stream`.
 * */
data class CommandSubmitResponse(
    val id: String,
)
