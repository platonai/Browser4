package ai.platon.pulsar.agentic.tools.advanced.format.providers

import ai.platon.pulsar.agentic.tools.advanced.format.ArtifactSpec
import ai.platon.pulsar.agentic.tools.advanced.format.AssemblyContext
import ai.platon.pulsar.agentic.tools.advanced.format.FormatOptions
import ai.platon.pulsar.agentic.tools.advanced.format.FormatProvider
import ai.platon.pulsar.agentic.tools.advanced.format.FormatStage
import ai.platon.pulsar.agentic.tools.advanced.format.FormatStep
import ai.platon.pulsar.agentic.tools.advanced.format.LIVE_TAB_DOMAIN
import ai.platon.pulsar.agentic.tools.advanced.format.StepPolicy
import ai.platon.pulsar.agentic.tools.advanced.format.StepResult
import ai.platon.pulsar.agentic.tools.advanced.format.UnsupportedFormatOptionException
import ai.platon.pulsar.skeleton.workflow.format.PageFormat
import ai.platon.pulsar.skeleton.workflow.format.PageFormats
import ai.platon.pulsar.skeleton.workflow.format.ScrapedDocument

/** The image extension a capture is written with. */
private const val IMAGE_EXTENSION = "png"

/**
 * `screenshot` — the first format that needs the **live tab**, and therefore the
 * first real exercise of [FormatStage.LIVE_TAB].
 *
 * `tab.screenshot` returns the image as **base64**, not as a path. The engine writes
 * that to a file through [ai.platon.pulsar.agentic.tools.advanced.format.FormatStepRunner.persistArtifact]
 * and hands this provider the path, so the provider does no I/O and the document's
 * `screenshot` field holds a real path — the shape Firecrawl migrants expect, except
 * that it is a local path rather than a hosted URL.
 *
 * ## What it accepts, and what it refuses
 *
 * - no options → the visible viewport;
 * - `fullPage: true` → the whole scrollable page, PNG;
 * - `base64: true` → also put the bytes in `screenshotBase64`. The file is written
 *   either way, so this only decides whether the response also carries the bytes.
 *
 * `viewport` and `quality` are **refused** rather than ignored. Firecrawl's
 * `viewport` is a size (`{width, height}`) while `tab.screenshot`'s is a *scroll
 * index* — accepting one as the other would capture the wrong region and report
 * success. `quality` has no counterpart at all. Both raise
 * [UnsupportedFormatOptionException], which the plan builder turns into a warning
 * naming the format, so the rest of a mixed request still runs.
 *
 * The format is **DEGRADABLE**: a self-hosted engine that cannot capture (for
 * example a Playwright-backed engine with `screenshot: false`) must not fail a
 * request that also asked for markdown.
 */
object ScreenshotFormatProvider : FormatProvider {

    override val id: String = PageFormats.SCREENSHOT

    override val policy: StepPolicy = StepPolicy.DEGRADABLE

    override fun steps(format: PageFormat, options: FormatOptions): List<FormatStep> {
        format.viewport?.let {
            throw UnsupportedFormatOptionException(
                "viewport is not supported yet — `tab.screenshot`'s viewport is a scroll index, " +
                    "not a size, so accepting {width, height} would capture the wrong region"
            )
        }
        format.quality?.let {
            throw UnsupportedFormatOptionException(
                "quality is not supported yet — `tab.screenshot` has no quality setting"
            )
        }

        // At most one of selector/fullPage/viewport may be given, and `selector` has no
        // counterpart on PageFormat yet; `fullPage` is the only region selector there is.
        val args: Map<String, Any?> = if (format.fullPage) mapOf("fullPage" to true) else emptyMap()

        return listOf(
            FormatStep(
                format = id,
                stage = FormatStage.LIVE_TAB,
                domain = LIVE_TAB_DOMAIN,
                method = "screenshot",
                args = args,
                policy = policy,
                artifact = ArtifactSpec(extension = IMAGE_EXTENSION, nameHint = id),
            )
        )
    }

    override fun assemble(
        format: PageFormat,
        results: List<StepResult>,
        ctx: AssemblyContext,
        document: ScrapedDocument,
    ): ScrapedDocument {
        val result = results.firstOrNull() ?: return document
        // `output` is the path the host wrote to; `raw` is the base64 it wrote.
        val withPath = document.copy(screenshot = result.output.takeIf { it.isNotBlank() })
        if (!format.base64) return withPath
        return withPath.copy(screenshotBase64 = result.raw.takeIf { it.isNotBlank() })
    }
}
