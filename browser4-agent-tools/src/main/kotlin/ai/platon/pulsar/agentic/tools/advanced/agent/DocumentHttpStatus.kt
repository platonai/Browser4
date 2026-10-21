package ai.platon.pulsar.agentic.tools.advanced.agent

import ai.platon.browser4.common.B4Constants.PAGE_STATUS_UNKNOWN
import ai.platon.pulsar.api.WebDriver
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The HTTP status of the document a driver currently has loaded — when the page
 * can report it.
 *
 * The page status fields on the command/task envelopes (`pageStatusCode`)
 * describe the page, so an agent or loop record must answer one of two things:
 * the status the page *actually returned*, or "not observed". It used to answer
 * a third thing that was simply false — `201 / SC_CREATED`, the model default,
 * next to a message saying the page loaded with HTTP 200.
 *
 * The source of truth used here is the document itself:
 * `performance.getEntriesByType('navigation')[0].responseStatus` is the HTTP
 * status of the main-document response, read from the live page of the session's
 * own driver. It needs no CDP domain to be enabled (the `Network` observer only
 * tracks responses once something enables it), it reflects the page the agent
 * actually ended on, and it reports nothing rather than guessing when the browser
 * does not expose it (Chrome < 109, a non-navigation document, a cross-origin
 * restriction, a wedged page).
 */
object DocumentHttpStatus {

    /**
     * Reads the loaded document's HTTP status, or null when it cannot be observed.
     *
     * Never throws and never blocks a task's completion: a driver that is gone, a
     * page that does not answer within [timeoutMillis], a value that is not a
     * plausible HTTP status — every one of those is "not observed" (`null`), which
     * callers turn into [PAGE_STATUS_UNKNOWN].
     *
     * @param driver the session's driver (the page it currently has loaded)
     * @param timeoutMillis how long the page may take to answer; the read is a
     *   single cheap `performance` lookup, so a page that has not answered by then
     *   is wedged rather than slow
     * @return the HTTP status (100..599), or null when it was not observed
     */
    suspend fun of(driver: WebDriver?, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Int? {
        if (driver == null) return null
        val value = runCatching {
            withTimeoutOrNull(timeoutMillis) { driver.evaluateValue(RESPONSE_STATUS_EXPRESSION) }
        }.getOrNull() ?: return null
        val status = (value as? Number)?.toInt() ?: return null
        return status.takeIf { it in HTTP_STATUS_RANGE }
    }

    /**
     * The single expression read from the page: the main-document response status,
     * or `null` when the page cannot report one.
     *
     * Kept as one expression so the read cannot leave the page in a partial state
     * and so tests can pin the exact probe.
     */
    const val RESPONSE_STATUS_EXPRESSION: String =
        "(() => { const nav = performance.getEntriesByType('navigation')[0]; " +
            "if (!nav) return null; const status = nav.responseStatus; " +
            "return (typeof status === 'number' && status >= 100 && status <= 599) ? status : null; })()"

    /** How long the page gets to answer the navigation-timing lookup. */
    const val DEFAULT_TIMEOUT_MILLIS: Long = 2_000

    /** The only values that count as an observed HTTP status. */
    private val HTTP_STATUS_RANGE = 100..599
}
