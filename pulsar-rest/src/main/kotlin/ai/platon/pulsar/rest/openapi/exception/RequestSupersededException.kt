package ai.platon.pulsar.rest.openapi.exception

/**
 * Exception thrown when a request is superseded by a newer request
 * in the same session (last-request-wins strategy).
 */
class RequestSupersededException(
    val sessionId: String,
    val requestId: Long,
    message: String = "Request $requestId for session $sessionId was superseded by a newer request"
) : RuntimeException(message)
