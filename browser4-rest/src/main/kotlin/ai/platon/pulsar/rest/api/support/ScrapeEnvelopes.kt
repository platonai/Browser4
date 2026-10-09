package ai.platon.pulsar.rest.api.support

/**
 * The `{success, …}` envelope the Firecrawl-compatible scrape face answers in.
 *
 * Four sites in `PageScrapeController` build this shape: the two success answers (`data`),
 * and the three refusals — a bad request (400), a `strict` request that could not be
 * satisfied (the failure's own status), and an artifact that is not on this host (404).
 * They share the key order and one rule a caller depends on: **the message is the empty
 * string, never null**, so a reader that prints `message` never has to test for it.
 *
 * `/api/x` is deliberately not built here. Its refusal has no `success` key at all — that
 * endpoint predates the envelope — so folding the two together would either change a
 * published body or hide the difference behind a flag. The shape that exists twice is the
 * one that lives here.
 */
object ScrapeEnvelopes {

    /** The `error` value for a payload the caller has to fix. */
    const val BAD_REQUEST = "Bad Request"

    /** The `error` value for a name this host cannot serve. */
    const val NOT_FOUND = "Not Found"

    /**
     * The success answer: the document (or the capability listing) under `data`.
     *
     * @param data the payload; the only field a successful caller reads.
     * @return `{success: true, data: …}`.
     */
    fun success(data: Any?): Map<String, Any?> = linkedMapOf(
        "success" to true,
        "data" to data,
    )

    /**
     * A refusal, in the order a caller reads it: `success`, `error`, the details that decide
     * what the caller does next (`retryable`, `hint`), then the message.
     *
     * The details travel with the code because the caller's next move depends on them —
     * retrying a 503 is pointless, retrying a 502 is not — and they are inserted as given,
     * including a `null` hint: absence and null are not the same answer on the wire.
     *
     * @param error the machine-readable code, e.g. [BAD_REQUEST] or a `ToolErrorCode.wire`.
     * @param message the human-readable reason; `null` becomes the empty string.
     * @param details extra fields, inserted between `error` and `message`.
     * @return the envelope, its key order fixed.
     */
    fun failure(error: String, message: String?, vararg details: Pair<String, Any?>): Map<String, Any?> {
        val body = linkedMapOf<String, Any?>("success" to false, "error" to error)
        details.forEach { (key, value) -> body[key] = value }
        body["message"] = message ?: ""
        return body
    }
}
