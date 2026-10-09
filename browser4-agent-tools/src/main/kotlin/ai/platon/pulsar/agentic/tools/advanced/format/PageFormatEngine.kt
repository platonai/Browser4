package ai.platon.pulsar.agentic.tools.advanced.format

import ai.platon.pulsar.agentic.tools.ToolErrorCode
import ai.platon.pulsar.agentic.tools.ToolErrorMapper
import ai.platon.pulsar.skeleton.workflow.format.FormatContext
import ai.platon.pulsar.skeleton.workflow.format.FormatInput
import ai.platon.pulsar.skeleton.workflow.format.PageFormat
import ai.platon.pulsar.skeleton.workflow.format.PageFormatContributor
import ai.platon.pulsar.skeleton.workflow.format.PageFormatContributorRegistry
import ai.platon.pulsar.skeleton.workflow.format.PageFormats
import ai.platon.pulsar.skeleton.workflow.format.ScrapeMetadata
import ai.platon.pulsar.skeleton.workflow.format.ScrapedDocument
import java.time.Duration

/**
 * Runs a [PageFormatPlan]: capture once, read many, assemble one document.
 *
 * ## What this class guarantees
 *
 * 1. **Capture once.** The plan has at most one capture; every `FROM_SNAPSHOT`
 *    step reads that capture through [FormatStepRunner.readOnSnapshot], which
 *    never touches the tab. Requesting eight formats costs one page load, not
 *    eight.
 * 2. **Consistency.** Because every format is derived from the same capture, the
 *    fields in one response describe one page state — the property that makes
 *    `markdown` and `links` agree with each other.
 * 3. **Stage isolation.** All snapshot reads complete before the first
 *    [FormatStage.LIVE_TAB] step runs. A format that needs the live tab (a
 *    screenshot) therefore cannot change what a snapshot-scoped format sees, and
 *    its failure cannot take the snapshot formats down with it.
 * 4. **Loud degradation.** A format this build cannot deliver, a step whose tool
 *    is unsupported, and a failed best-effort step all end up in
 *    [ScrapedDocument.warning] plus `metadata.formatsDelivered` — the caller can
 *    always tell "not requested" from "requested but missing", and why.
 * 5. **No silent failure.** A step that the caller's request depends on
 *    ([StepPolicy.REQUIRED]) rethrows its original exception, so the failure
 *    keeps its real type and message instead of being flattened into a generic
 *    error by this layer.
 * 6. **Plugins extend it.** Formats no core provider implements are offered to
 *    the registered [PageFormatContributor]s, against the very same capture the
 *    core formats used. A contributor that is missing, unavailable, missing an
 *    input, silent, failing or misdeclared produces a warning naming the format.
 *
 * Nothing here is browser-specific: the host supplies [FormatStepRunner], which
 * is why the engine is unit-testable against a fake runner.
 *
 * @property runner the host bridge that executes tool steps.
 */
class PageFormatEngine(private val runner: FormatStepRunner) {

    /**
     * Produce a document for [request].
     *
     * @param request the formats to produce and the request-level options.
     * @return one document carrying every format that was delivered; the
     *   requested-but-missing ones are named in [ScrapedDocument.warning] and
     *   absent from `metadata.formatsDelivered`.
     * @throws Exception the original failure of a REQUIRED step.
     */
    suspend fun scrape(request: PageScrapeRequest): ScrapedDocument {
        val plan = PageFormatPlanBuilder.build(
            request.formats,
            FormatOptions(onlyMainContent = request.onlyMainContent),
        )
        return scrape(request, plan)
    }

    /**
     * Execute an already-built [plan].
     *
     * Split from [scrape] so the stage machinery — capture-once, snapshot reads
     * before tab work, per-policy failure handling — is testable with a
     * hand-built plan, including plans for formats no provider implements yet.
     */
    internal suspend fun scrape(request: PageScrapeRequest, plan: PageFormatPlan): ScrapedDocument {
        val options = FormatOptions(onlyMainContent = request.onlyMainContent)
        val degradations = plan.degradations.toMutableList()

        // Everything the plan already knows is decided *before* a page is loaded: a strict
        // request that cannot be satisfied must not pay for a capture. This is the common
        // case (an unavailable format id), so it is worth failing early rather than after
        // the work.
        if (request.strict && degradations.isNotEmpty()) throw FormatFailureException(degradations)

        var document = ScrapedDocument(
            metadata = ScrapeMetadata(formatsRequested = plan.requested),
        )
        plan.warnings.forEach { document = document.withWarning(it) }

        val snapshot = acquireSnapshot(plan)?.also { captured ->
            document = document.copy(
                url = captured.href,
                metadata = document.metadata.copy(
                    url = captured.key,
                    sourceURL = captured.href,
                    captureId = captured.key,
                    captureTime = captured.capturedAt,
                    cacheState = captured.cacheState,
                ),
            )
        }

        val results = LinkedHashMap<StepKey, String>()
        val rawOutputs = LinkedHashMap<StepKey, String>()
        document = runSnapshotSteps(plan, snapshot, results, rawOutputs, document, degradations)
        document = runLiveSteps(plan, results, rawOutputs, document, degradations)

        request.formats.forEach { format ->
            val provider = FormatProviders.find(format.type) ?: return@forEach
            // Re-planning here is how a provider recovers the steps it declared. An
            // option it refuses was already recorded as a degradation while the plan was
            // built, so it must skip the format, not fail the request.
            val steps = try {
                provider.steps(format, options)
            } catch (e: UnsupportedFormatOptionException) {
                return@forEach
            }
            val stepResults = steps.mapNotNull { step ->
                results[step.key()]?.let { output ->
                    StepResult(step, output, raw = rawOutputs[step.key()] ?: output)
                }
            }
            if (stepResults.isEmpty()) return@forEach
            document = provider.assemble(format, stepResults, AssemblyContext(snapshot, options), document)
        }

        document = runContributors(request, snapshot, document, degradations)

        // Every degradation is reported, not only the first: `strict` means all-or-nothing,
        // and one round trip should be enough for the caller to see everything that is
        // wrong rather than discovering it one format at a time.
        if (request.strict && degradations.isNotEmpty()) throw FormatFailureException(degradations)

        return document.retainRequested(plan.requested).withDeliveredFormats(plan.requested)
    }

    /**
     * The one capture of the request.
     *
     * A plan with no snapshot-scoped step (a hypothetical tab-only request) does
     * not capture at all — capture-once also means "never more than the plan
     * needs".
     *
     * [Duration.ZERO] is not a placeholder: it is the honest window, and it means
     * "do not reuse a stored capture". A positive window (Firecrawl's `maxAge`)
     * cannot be honoured until some method reports a *stored* snapshot's identity —
     * see [FormatStepRunner.acquireSnapshot].
     */
    private suspend fun acquireSnapshot(plan: PageFormatPlan): FormatSnapshot? {
        if (!plan.needsSnapshot) return null
        return runner.acquireSnapshot(Duration.ZERO)
    }

    private suspend fun runSnapshotSteps(
        plan: PageFormatPlan,
        snapshot: FormatSnapshot?,
        results: MutableMap<StepKey, String>,
        rawOutputs: MutableMap<StepKey, String>,
        document: ScrapedDocument,
        degradations: MutableList<FormatDegradation>,
    ): ScrapedDocument {
        var result = document
        plan.stepsOf(FormatStage.FROM_SNAPSHOT).forEach { step ->
            val key = step.key()
            if (results.containsKey(key)) return@forEach
            if (!runner.supports(step.domain, step.method)) {
                result = degrade(
                    result, degradations, step.format, unsupportedReason(step), ToolErrorCode.TARGET_UNAVAILABLE,
                )
                return@forEach
            }
            val target = snapshot ?: return@forEach
            try {
                record(step, rawOutputs) { runner.readOnSnapshot(target, step.domain, step.method, step.args) }
                    ?.let { results[key] = it }
            } catch (e: Exception) {
                if (step.policy == StepPolicy.REQUIRED) throw e
                result = degrade(result, degradations, step.format, failureReason(e), failureCode(e))
            }
        }
        return result
    }

    /**
     * Turn one step's raw output into what the provider consumes.
     *
     * A text step passes through. A step carrying an [ArtifactSpec] has the host write
     * the bytes and the provider receives the **path**, with the raw base64 kept in
     * [rawOutputs] for a format that also returns the bytes. Persisting is part of the
     * step's success: a host that cannot write files fails here, and the step's policy
     * decides whether that degrades the format or the request.
     *
     * @return the value to hand the provider, or null when the tool produced nothing.
     */
    private suspend fun record(
        step: FormatStep,
        rawOutputs: MutableMap<StepKey, String>,
        call: suspend () -> String,
    ): String? {
        val raw = call()
        val artifact = step.artifact ?: return raw
        rawOutputs[step.key()] = raw
        return runner.persistArtifact(artifact.nameHint, raw, artifact.extension)
    }

    private suspend fun runLiveSteps(
        plan: PageFormatPlan,
        results: MutableMap<StepKey, String>,
        rawOutputs: MutableMap<StepKey, String>,
        document: ScrapedDocument,
        degradations: MutableList<FormatDegradation>,
    ): ScrapedDocument {
        var result = document
        plan.stepsOf(FormatStage.LIVE_TAB).forEach { step ->
            val key = step.key()
            if (results.containsKey(key)) return@forEach
            if (!runner.supports(step.domain, step.method)) {
                result = degrade(
                    result, degradations, step.format, unsupportedReason(step), ToolErrorCode.TARGET_UNAVAILABLE,
                )
                return@forEach
            }
            try {
                record(step, rawOutputs) { runner.runOnTab(step.domain, step.method, step.args) }
                    ?.let { results[key] = it }
            } catch (e: Exception) {
                if (step.policy == StepPolicy.REQUIRED) throw e
                result = degrade(result, degradations, step.format, failureReason(e), failureCode(e))
            }
        }
        return result
    }

    /**
     * Record a degradation and render it into the document's `warning`.
     *
     * One call so the sentence a non-strict caller reads and the code a strict caller
     * gets cannot disagree: both come from the same [FormatDegradation].
     */
    private fun degrade(
        document: ScrapedDocument,
        degradations: MutableList<FormatDegradation>,
        format: String,
        message: String,
        code: ToolErrorCode,
    ): ScrapedDocument {
        degradations += FormatDegradation(format, message, code)
        return document.withWarning("$format: $message")
    }

    /**
     * "asked for something this deployment cannot run" — a configuration fix.
     *
     * 503 under strict: nothing the caller can change about their request would help,
     * which is exactly what separates this from a refused option or a deprecated id.
     */
    private fun unsupportedReason(step: FormatStep): String =
        "unavailable (${step.domain}.${step.method} is not supported in this deployment)"

    /** "the tool ran and failed" — a page/timing/service problem. */
    private fun failureReason(error: Exception): String = error.message ?: error.javaClass.simpleName

    /**
     * 504 when the failure was a timeout, 502 for everything else — the design's mapping
     * for an execution failure.
     *
     * Only the timeout case is singled out, and it is read from [ToolErrorMapper] rather
     * than from the message text: a step that failed for an *internal* reason must not be
     * reported as 400 just because its message happened to look like a bad argument. A
     * retryable 502 is the honest default for "the tool ran and did not deliver".
     */
    private fun failureCode(error: Exception): ToolErrorCode =
        if (ToolErrorMapper.classify(error) == ToolErrorCode.TIMEOUT) ToolErrorCode.TIMEOUT
        else ToolErrorCode.UPSTREAM_ERROR

    /**
     * Let plugin contributors fill the formats no core provider implements.
     *
     * Runs last, after every core format is assembled, because a contributor is
     * handed the same capture the core formats used — including the `rawHtml` /
     * `html` / `markdown` they produced — so `branding` reads the page `markdown`
     * read rather than capturing anything of its own.
     *
     * Availability is a runtime fact (which plugins were wired, whether their
     * service is configured), which is why it is decided here and not in
     * [PageFormatPlanBuilder]. Every way a contributor can decline ends up as a
     * warning naming the format — absent, self-reported unavailable, missing an
     * input, reporting nothing, throwing, or writing a field the document does
     * not have. None of them fails the request, and none of them disappears
     * silently.
     */
    private suspend fun runContributors(
        request: PageScrapeRequest,
        snapshot: FormatSnapshot?,
        document: ScrapedDocument,
        degradations: MutableList<FormatDegradation>,
    ): ScrapedDocument {
        var result = document
        request.formats.filter { PageFormats.isContributed(it.type) }.forEach { format ->
            val id = format.type
            val contributor = PageFormatContributorRegistry.instance.get(id)
            if (contributor == null) {
                val missing = PageFormatPlanBuilder.missingContributorDegradation(id)
                result = degrade(result, degradations, missing.format, missing.message, missing.code)
                return@forEach
            }
            if (!contributor.isAvailable()) {
                result = degrade(
                    result, degradations, id,
                    contributorUnavailableReason(contributor), ToolErrorCode.TARGET_UNAVAILABLE,
                )
                return@forEach
            }

            val context = contributorContext(format, snapshot, result)
            val missing = contributor.requires.filterNot { isProvided(it, context) }
            if (missing.isNotEmpty()) {
                result = degrade(
                    result, degradations, id,
                    "unavailable (needs ${missing.joinToString(", ") { inputName(it) }})",
                    missingInputCode(missing),
                )
                return@forEach
            }

            val value = try {
                contributor.contribute(context)
            } catch (e: Exception) {
                result = degrade(result, degradations, id, failureReason(e), failureCode(e))
                return@forEach
            }
            if (value == null) {
                // The format ran and had nothing to report — the design's "not a product
                // page" case, which it maps to an upstream failure rather than to
                // something the caller or the deployment could have fixed.
                result = degrade(result, degradations, id, "nothing to report", ToolErrorCode.UPSTREAM_ERROR)
                return@forEach
            }

            val field = contributor.outputField
            if (field !in PageFormats.contributedFields()) {
                // Unreachable through the registry — registration refuses a foreign field —
                // so reaching it means a contributor was registered around the registry:
                // a bug in the plugin, not a request or deployment problem.
                result = degrade(
                    result, degradations, id,
                    "contributor writes '$field', which is not a contributor field",
                    ToolErrorCode.INTERNAL,
                )
                return@forEach
            }
            result = result.withContributedField(field, value)
        }
        return result
    }

    /**
     * An unsatisfied contributor input is 400 when the caller could have supplied it, and
     * 503 when no request could.
     *
     * `needs markdown` is the caller's to fix — asking for `markdown` in the same request
     * satisfies it — so a 503 would blame the deployment for a request shape. `LIVE_TAB` is
     * the opposite case: no request can hand a contributor tab control, which is a property
     * of this build (and the reason A1 has it slated for removal).
     */
    private fun missingInputCode(missing: List<FormatInput>): ToolErrorCode =
        if (missing.all { it == FormatInput.LIVE_TAB }) ToolErrorCode.TARGET_UNAVAILABLE
        else ToolErrorCode.INVALID_ARGUMENT

    /**
     * The inputs and options a contributor gets.
     *
     * The HTML/markdown members are read from the assembled [document], so a
     * contributor sees exactly what the caller would — if `html` was not
     * requested, it is absent here too, which is what makes a `HTML` requirement
     * meaningful.
     */
    private fun contributorContext(
        format: PageFormat,
        snapshot: FormatSnapshot?,
        document: ScrapedDocument,
    ): FormatContext = FormatContext(
        snapshotKey = snapshot?.key.orEmpty(),
        url = document.url.orEmpty(),
        rawHtml = document.rawHtml,
        html = document.html,
        markdown = document.markdown,
        metadata = document.metadata,
        options = contributorOptions(format),
    )

    /**
     * The request options a contributor may consult, keyed by their JSON names.
     *
     * Deliberately a short allowlist rather than every [PageFormat] knob: these
     * are the ones the contributed formats are specified to take (`mode` for
     * branding, `query` for highlights, `tag` for change tracking).
     */
    private fun contributorOptions(format: PageFormat): Map<String, Any?> {
        val options = LinkedHashMap<String, Any?>()
        format.mode?.let { options["mode"] = it }
        if (format.modes.isNotEmpty()) options["modes"] = format.modes
        format.tag?.let { options["tag"] = it }
        format.query?.let { options["query"] = it }
        format.algorithm?.let { options["algorithm"] = it }
        format.prompt?.let { options["prompt"] = it }
        return options
    }

    /**
     * Whether [input] is populated in [ctx].
     *
     * [FormatInput.LIVE_TAB] is never satisfied: a contributor receives values,
     * not a driver, and [FormatContext] has no way to carry tab control. Saying
     * so is the point — a contributor that declares it gets a warning naming the
     * input instead of being called with a context it cannot use.
     */
    private fun isProvided(input: FormatInput, ctx: FormatContext): Boolean = when (input) {
        FormatInput.SNAPSHOT -> ctx.snapshotKey.isNotEmpty()
        FormatInput.RAW_HTML -> ctx.rawHtml != null
        FormatInput.HTML -> ctx.html != null
        FormatInput.MARKDOWN -> ctx.markdown != null
        FormatInput.URL -> ctx.url.isNotEmpty()
        FormatInput.METADATA -> true
        FormatInput.LIVE_TAB -> false
    }

    /** How an unsatisfied [FormatInput] is named to the caller. */
    private fun inputName(input: FormatInput): String = when (input) {
        FormatInput.SNAPSHOT -> "a page snapshot"
        FormatInput.RAW_HTML -> "rawHtml"
        FormatInput.HTML -> "html"
        FormatInput.MARKDOWN -> "markdown"
        FormatInput.LIVE_TAB -> "a live tab (contributors are handed values, not a driver)"
        FormatInput.URL -> "a URL"
        FormatInput.METADATA -> "metadata"
    }

    /** A registered contributor that says it cannot run right now. */
    private fun contributorUnavailableReason(contributor: PageFormatContributor): String {
        val reason = contributor.unavailableReason()?.takeIf { it.isNotBlank() }
            ?: "the contributor reports itself unavailable"
        return "unavailable ($reason)"
    }
}
