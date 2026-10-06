package ai.platon.pulsar.agentic.tools.advanced.format

import java.time.Duration

/**
 * When a plan step runs, and therefore what it is allowed to touch.
 *
 * The split is the engine's central guarantee: every [FROM_SNAPSHOT] step runs
 * before the first [LIVE_TAB] step, and only the latter may drive the browser.
 */
enum class FormatStage {
    /** Reads the single capture; never touches the live tab. */
    FROM_SNAPSHOT,

    /** Drives the live tab (screenshot, PDF). Runs last. */
    LIVE_TAB,
}

/**
 * What a step failure means for the request.
 *
 * @see PageFormatEngine for how each policy is applied.
 */
enum class StepPolicy {
    /** The caller asked for this format explicitly: failing it fails the request. */
    REQUIRED,

    /** The format is best-effort: omit the field, record a warning, keep going. */
    DEGRADABLE,
}

/**
 * One tool invocation in a [PageFormatPlan].
 *
 * @property format the requested format this step serves.
 * @property stage when it runs; see [FormatStage].
 * @property domain the tool domain to call, e.g. `html_snapshot`.
 * @property method the tool method to call, e.g. `export`.
 * @property args the tool arguments.
 * @property policy what a failure means; see [StepPolicy].
 */
data class FormatStep(
    val format: String,
    val stage: FormatStage,
    val domain: String,
    val method: String,
    val args: Map<String, Any?> = emptyMap(),
    val policy: StepPolicy = StepPolicy.DEGRADABLE,
) {
    /**
     * Identity used to run a step at most once per request.
     *
     * Two formats that need the same read (`markdown` and `html` both need the
     * cleaned export) share one execution, which is what keeps `capture-once`
     * true for the *whole* plan rather than per format.
     */
    fun key(): StepKey = StepKey(stage, domain, method, args)
}

/** Identity of a [FormatStep]; equal steps share one execution. */
data class StepKey(
    val stage: FormatStage,
    val domain: String,
    val method: String,
    val args: Map<String, Any?>,
)

/**
 * The compiled execution plan for one `page_scrape` request.
 *
 * Building it is pure: same formats, same plan. That is what lets the request be
 * validated (all-or-nothing) before any tool runs, and what makes "capture
 * exactly once" a property of the plan instead of a hope.
 *
 * @property requested the requested format ids, in request order.
 * @property steps the steps to execute, in stage order.
 * @property warnings notes to surface even when every step succeeds — an unknown
 *   or not-yet-implemented format, a deprecated one, a contributor that is not
 *   installed.
 */
data class PageFormatPlan(
    val requested: List<String>,
    val steps: List<FormatStep>,
    val warnings: List<String> = emptyList(),
) {
    /** True when the plan needs a capture at all. */
    val needsSnapshot: Boolean get() = steps.any { it.stage == FormatStage.FROM_SNAPSHOT }

    /** True when the plan needs the live tab (and so cannot run headless-of-tab). */
    val needsLiveTab: Boolean get() = steps.any { it.stage == FormatStage.LIVE_TAB }

    /** The steps of one stage, in plan order. */
    fun stepsOf(stage: FormatStage): List<FormatStep> = steps.filter { it.stage == stage }
}

/**
 * Request-level options the providers consult while planning.
 *
 * @property onlyMainContent derive markdown from the readable article region
 *   rather than the whole document; mirrors Firecrawl's `onlyMainContent`
 *   (default true).
 */
data class FormatOptions(
    val onlyMainContent: Boolean = true,
)

/**
 * The result of one executed step, handed back to the provider that asked for it.
 *
 * @property step the step that produced it.
 * @property output the tool output.
 */
data class StepResult(val step: FormatStep, val output: String)

/**
 * What a provider may consult while turning step outputs into document fields.
 *
 * @property snapshot the capture the outputs came from; null for a plan with no
 *   snapshot-scoped step.
 * @property options the request options.
 */
data class AssemblyContext(
    val snapshot: FormatSnapshot?,
    val options: FormatOptions,
)

/**
 * The per-request options a `page_scrape` caller supplies.
 *
 * @property formats the formats to produce, in request order.
 * @property expires reuse a stored capture younger than this; zero means capture
 *   the live page.
 * @property onlyMainContent see [FormatOptions.onlyMainContent].
 * @property url the requested URL, recorded in metadata and used as the base for
 *   relative links when the page redirects.
 */
data class PageScrapeRequest(
    val formats: List<ai.platon.pulsar.skeleton.workflow.format.PageFormat>,
    val expires: Duration = Duration.ZERO,
    val onlyMainContent: Boolean = true,
    val url: String? = null,
)
