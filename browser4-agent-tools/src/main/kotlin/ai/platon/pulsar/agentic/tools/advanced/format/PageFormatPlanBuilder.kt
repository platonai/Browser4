package ai.platon.pulsar.agentic.tools.advanced.format

import ai.platon.pulsar.skeleton.workflow.format.PageFormat
import ai.platon.pulsar.skeleton.workflow.format.PageFormats

/**
 * Compiles a requested format list into an executable [PageFormatPlan].
 *
 * Pure and total: no tool runs here, nothing is captured, and the same request
 * always yields the same plan. Everything that can be decided up front — which
 * formats this build can deliver, which reads they need, what runs before what —
 * is decided here, so the engine only has to execute and assemble.
 */
object PageFormatPlanBuilder {

    /**
     * Build the plan for [formats].
     *
     * Formats this build cannot deliver produce a warning and no steps; the
     * request still runs for the formats that can. A format whose provider needs
     * an option it did not get (e.g. `deterministicJson` without `sql`) is
     * treated the same way here — the option-level rejection happens during
     * schema validation, before planning.
     *
     * @param formats the requested formats, in request order.
     * @param options request-level options providers consult while planning.
     */
    fun build(formats: List<PageFormat>, options: FormatOptions = FormatOptions()): PageFormatPlan {
        val warnings = mutableListOf<String>()
        val declared = mutableListOf<FormatStep>()

        formats.forEach { format ->
            val provider = FormatProviders.find(format.type)
            if (provider == null) {
                // A contributed id's availability is a runtime fact — which
                // plugins got wired and whether their service is configured — so
                // planning says nothing about it and the engine reports the
                // outcome. Everything else is a property of this build and is
                // decided here. (Planning stays pure: no registry lookup.)
                if (!PageFormats.isContributed(format.type)) {
                    warnings += unavailableWarning(format.type)
                }
                return@forEach
            }

            val steps = try {
                provider.steps(format, options)
            } catch (e: UnsupportedFormatOptionException) {
                // An option this build cannot honour is refused by name rather than
                // silently dropped — a 1280x800 request must not quietly become a
                // 1920x1080 answer. The rest of the request still runs.
                warnings += "${format.type}: ${e.message}"
                return@forEach
            }
            if (steps.isEmpty()) {
                warnings += "${format.type}: no steps to run (a required option is missing)"
                return@forEach
            }
            declared += steps
        }

        val merged = merge(declared)
        // Every snapshot read runs before any live-tab work: a format served
        // from the capture must not depend on the tab still being alive.
        val ordered = merged.values.sortedBy { if (it.stage == FormatStage.FROM_SNAPSHOT) 0 else 1 }

        return PageFormatPlan(
            requested = formats.map { it.type },
            steps = ordered,
            warnings = warnings,
        )
    }

    /**
     * Collapse identical steps and upgrade the shared policy.
     *
     * Two formats that need the same read run it once. When one of them needs it
     * as REQUIRED, the shared step is REQUIRED — "required for any format" is
     * required, and the opposite rule would let a best-effort format's failure
     * policy silently weaken a hard requirement.
     */
    internal fun merge(steps: List<FormatStep>): Map<StepKey, FormatStep> {
        val merged = LinkedHashMap<StepKey, FormatStep>()
        steps.forEach { step ->
            val key = step.key()
            val existing = merged[key]
            merged[key] = when {
                existing == null -> step
                existing.policy == StepPolicy.DEGRADABLE && step.policy == StepPolicy.REQUIRED -> step
                else -> existing
            }
        }
        return merged
    }

    /**
     * Why a valid-but-unavailable format produced no steps.
     *
     * The two cases need different fixes — stop using a deprecated id, or wait
     * for a later phase — so they must read differently. A contributed id is not
     * decided here: whether a plugin supplies it is a runtime fact, and
     * [PageFormatEngine] reports it.
     *
     * Public because the capability listing a caller reads
     * (`page.formats` / `GET /api/scrape/formats`) has to say the same words as the
     * per-request `warning`, and two copies of a user-facing sentence drift.
     */
    fun unavailableWarning(id: String): String = when {
        PageFormats.isDeprecated(id) -> "$id: deprecated and unavailable; use question or highlights"
        else -> "$id: not available in this build"
    }

    /**
     * Why a requested format that needs a plugin contributor has none.
     *
     * Lives next to [unavailableWarning] so the two "unavailable" texts a caller
     * can see stay in one place, even though only the engine and the capability
     * listing can decide when this one applies.
     */
    fun missingContributorWarning(id: String): String =
        "$id: unavailable (no plugin contributor installed)"
}
