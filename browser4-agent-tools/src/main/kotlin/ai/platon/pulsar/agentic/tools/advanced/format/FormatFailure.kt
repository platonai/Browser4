package ai.platon.pulsar.agentic.tools.advanced.format

import ai.platon.pulsar.agentic.tools.CodedFailure
import ai.platon.pulsar.agentic.tools.ToolErrorCode

/**
 * One requested format the request did not get, why, and the code strict mode reports.
 *
 * This is the structured form of a degradation note. A non-strict response renders it
 * into [ScrapedDocument.warning] exactly as before ([warning]); a strict request turns it
 * into a failure with the code attached.
 *
 * Why the pair and not just the sentence: the two cases the contract distinguishes —
 * "this deployment cannot deliver the format" (503) and "the tool ran and failed" (502 /
 * 504) — produce equally plausible prose, so a status code derived from the text would be
 * a guess. Here the layer that knows the cause records it once, and the wording is
 * derived from it rather than parsed back out of it.
 *
 * @property format the requested format id.
 * @property message why it was not delivered, without the `id: ` prefix.
 * @property code the [ToolErrorCode] a strict request reports for this degradation.
 */
data class FormatDegradation(
    val format: String,
    val message: String,
    val code: ToolErrorCode,
) {
    /** The wording a non-strict document carries in its `warning`. */
    val warning: String get() = "$format: $message"
}

/**
 * What `strict` turns a degradation into: a request that could not be satisfied.
 *
 * Thrown instead of returning a document with missing fields, so a caller who asked for
 * all-or-nothing gets an error it can act on rather than a document it has to inspect.
 *
 * ## Which code, when several formats degraded
 *
 * [code] is the **first** degradation's, in request order. One status has to be chosen,
 * and request order is the only ordering that is both deterministic and visible to the
 * caller (it is the order they wrote). The message names **every** degraded format, so
 * one round trip is enough to see all of them — failing at the first one would cost a
 * round trip per format.
 *
 * @property degradations every format that was not delivered; never empty.
 */
class FormatFailureException(
    val degradations: List<FormatDegradation>,
) : IllegalStateException(describeStrictFailure(degradations)), CodedFailure {

    init {
        require(degradations.isNotEmpty()) { "a strict format failure needs at least one degradation" }
    }

    override val code: ToolErrorCode get() = degradations.first().code
}

private fun describeStrictFailure(degradations: List<FormatDegradation>): String {
    if (degradations.isEmpty()) return "strict: the requested formats could not be delivered"
    return "strict: ${degradations.size} requested format(s) could not be delivered — " +
        degradations.joinToString("; ") { it.warning }
}
