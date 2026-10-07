package ai.platon.pulsar.rest.api.service

import ai.platon.browser4.common.B4Constants.DEFAULT_SESSION_ID
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.common.getLogger
import org.springframework.stereotype.Service

/**
 * Translates natural language descriptions of browser actions into
 * executable webdriver commands via the agentic [ai.platon.pulsar.agentic.AgenticSession.act]
 * infrastructure, then executes them synchronously.
 */
@Service
class ActService(
    private val sessionManager: PulsarSessionManager,
) {
    private val logger = getLogger(ActService::class)

    /**
     * Translates a natural language description to browser actions and
     * executes them against the current session.
     *
     * @param description Natural language description of the browser action.
     * @return Human-readable summary of the executed actions.
     */
    suspend fun translateAndExecute(description: String): String {
        val session = sessionManager.getOrCreateSession(DEFAULT_SESSION_ID).agenticSession

        logger.info("Executing act: {}", description)
        val results = session.act(description)

        if (results.isEmpty()) {
            return "(no actions performed)"
        }

        return results.joinToString("\n") { result ->
            val desc = result.evaluate.description
            val value = result.evaluate.value
            val message = result.message
            when {
                !desc.isNullOrBlank() -> desc
                value != null -> value.toString()
                !message.isNullOrBlank() -> message
                else -> "(done)"
            }
        }
    }
}
