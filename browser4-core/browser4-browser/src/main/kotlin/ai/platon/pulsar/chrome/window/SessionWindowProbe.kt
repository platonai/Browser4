package ai.platon.pulsar.chrome.window

import ai.platon.pulsar.api.WebDriver
import ai.platon.pulsar.chrome.PulsarWebDriver
import ai.platon.pulsar.chrome.session.BrowserProcessReaper
import ai.platon.pulsar.chrome.session.JdkProcessTable
import ai.platon.pulsar.chrome.session.ProcessTable
import ai.platon.pulsar.common.getLogger
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path

/**
 * What a window check concluded.
 *
 * [UNKNOWN] is a first-class answer, not a failure: "the check could not be
 * performed" is different from both "a window is visible" and "no window is
 * visible", and a caller must be able to say so. The previous machine-wide
 * process scan had no such state — it reported a warning whenever it could not
 * see the session's browser, which is how a demonstrably visible headed window
 * collected two different false-positive warnings.
 *
 * The wire vocabulary is the enum name (`VISIBLE` / `HIDDEN` / `UNKNOWN`).
 */
enum class WindowVisibility {
    /** A window was OBSERVED: a non-headless process and a non-minimized window agree. */
    VISIBLE,

    /** No window, on evidence: launched headless, requested headless, or minimized. */
    HIDDEN,

    /** The check could not be performed — never render this as success or as a problem. */
    UNKNOWN,
}

/**
 * Where the conclusion came from, so a caller can judge how strong it is.
 */
enum class WindowEvidenceSource {
    /** CDP `Browser.getWindowForTarget`: the browser itself reported its window. */
    CDP_WINDOW,

    /** The live process command line: headed or `--headless` (the launch that really happened). */
    PROCESS_COMMAND_LINE,

    /** The session's own display-mode request; no live browser was consulted. */
    DISPLAY_MODE,

    /** Nothing could be observed. */
    NONE,
}

/** A window's screen geometry, as the browser reported it. */
data class WindowBounds(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    /** Map form for JSON responses. */
    fun toMap(): Map<String, Any?> = mapOf(
        "left" to left,
        "top" to top,
        "width" to width,
        "height" to height,
    )
}

/**
 * Everything the policy is allowed to look at.
 *
 * Every field is nullable because "not observed" and "observed to be false" are
 * different facts and the policy must be able to tell them apart — a `false`
 * where the truth is "unknown" is exactly how the old check produced warnings
 * about a browser it had never seen.
 *
 * @property requestedDisplayMode what the SESSION asked for (`GUI`, `HEADLESS`,
 *   `SUPERVISED`), or null when the session never pinned one
 * @property launchedHeadless whether the LIVE process carries `--headless`;
 *   null when its command line could not be read
 * @property processAlive whether the session's browser process is running; null
 *   when there is no pid marker to check
 * @property browserPid the pid the session's own launcher recorded
 * @property debugPort the CDP port this session's browser listens on
 * @property windowState `normal` / `minimized` / `maximized` / `fullscreen` as
 *   reported by `Browser.getWindowForTarget`; null when not observed
 * @property windowId the CDP window id, when observed
 * @property bounds the window geometry, when observed
 * @property pageTargetUrls the page targets the CDP endpoint advertises
 */
data class WindowObservation(
    val requestedDisplayMode: String? = null,
    val launchedHeadless: Boolean? = null,
    val processAlive: Boolean? = null,
    val browserPid: Long? = null,
    val debugPort: Int = 0,
    val windowState: String? = null,
    val windowId: Long? = null,
    val bounds: WindowBounds? = null,
    val pageTargetUrls: List<String> = emptyList(),
) {
    /** JSON form of the observation, for diagnostics on the wire. */
    fun toMap(): Map<String, Any?> = mapOf(
        "requestedDisplayMode" to (requestedDisplayMode ?: ""),
        "launchedHeadless" to launchedHeadless,
        "processAlive" to processAlive,
        "browserPid" to (browserPid ?: 0L),
        "debugPort" to debugPort,
        "windowState" to (windowState ?: ""),
        "windowId" to (windowId ?: 0L),
        "bounds" to bounds?.toMap(),
        "pageTargetUrls" to pageTargetUrls,
    )
}

/**
 * The verdict: what was concluded, on what evidence, and why.
 *
 * @property visibility the tri-state conclusion
 * @property source where the conclusion came from
 * @property reason the observation in plain words — this is what a client shows
 *   a user instead of the old claim about "every Browser4-managed browser
 *   detected for this workspace"
 * @property observation the raw facts, so a client can show them too
 */
data class WindowVisibilityReport(
    val visibility: WindowVisibility,
    val source: WindowEvidenceSource,
    val reason: String,
    val observation: WindowObservation = WindowObservation(),
) {
    /** True when the check actually happened (VISIBLE or HIDDEN); false for UNKNOWN. */
    val verified: Boolean get() = visibility != WindowVisibility.UNKNOWN

    /** JSON form: the contract clients consume. */
    fun toMap(): Map<String, Any?> = mapOf(
        "visibility" to visibility.name,
        "verified" to verified,
        "source" to source.name,
        "reason" to reason,
        "observation" to observation.toMap(),
    )
}

/**
 * The window-visibility decision, with no browser and no I/O in it.
 *
 * Keeping the rule pure is what makes it testable: the interesting cases
 * (a headed session launched headless, a minimized window, an unreadable command
 * line) are decided here from facts, and the probe below only collects them.
 */
object WindowVisibilityPolicy {

    private val NON_MINIMIZED_STATES = setOf("normal", "maximized", "fullscreen")

    /**
     * Decide what [observation] proves.
     *
     * The order matters and is deliberate: a *positive* observation that no
     * window exists wins over everything (it is the bug this check exists for),
     * then a visible window requires TWO independent facts, and anything left
     * over is [WindowVisibility.UNKNOWN] rather than a guess.
     */
    fun classify(observation: WindowObservation): WindowVisibilityReport {
        val requested = observation.requestedDisplayMode?.uppercase()
        val windowState = observation.windowState?.lowercase()

        // 1. The launch that really happened is headless. This is the display-mode
        //    regression the check was written for: `open --headed` silently
        //    producing a headless browser.
        if (observation.launchedHeadless == true) {
            val reason = if (requested != null && requested != "HEADLESS") {
                "the browser of this session was launched with --headless although the session " +
                    "requested a visible window (display mode $requested)"
            } else {
                "the browser of this session was launched with --headless, so it has no window"
            }
            return WindowVisibilityReport(WindowVisibility.HIDDEN, WindowEvidenceSource.PROCESS_COMMAND_LINE, reason, observation)
        }

        // 2. A window exists but is minimized — not visible to the user.
        if (windowState == "minimized") {
            return WindowVisibilityReport(
                WindowVisibility.HIDDEN,
                WindowEvidenceSource.CDP_WINDOW,
                "the window of this session exists but is minimized (windowState=minimized)",
                observation,
            )
        }

        // 3. Positive proof of a visible window: the process is NOT headless and
        //    the browser reports a non-minimized window. Both facts are required —
        //    headless Chrome also answers getWindowForTarget with a synthetic
        //    window, so either fact alone proves nothing.
        if (observation.launchedHeadless == false && windowState in NON_MINIMIZED_STATES) {
            return WindowVisibilityReport(
                WindowVisibility.VISIBLE,
                WindowEvidenceSource.CDP_WINDOW,
                "the browser of this session is not headless and its window reports " +
                    "windowState=$windowState (pid ${observation.browserPid ?: "?"}, port ${observation.debugPort})",
                observation,
            )
        }

        // 4. A session that asked for headless has no window by construction. This
        //    is only reached when the live process could not be read.
        if (requested == "HEADLESS") {
            return WindowVisibilityReport(
                WindowVisibility.HIDDEN,
                WindowEvidenceSource.DISPLAY_MODE,
                "this session requested headless mode, so it has no window",
                observation,
            )
        }

        // 5. The browser is gone: nothing to verify, and nothing to warn about.
        if (observation.processAlive == false) {
            return WindowVisibilityReport(
                WindowVisibility.UNKNOWN,
                WindowEvidenceSource.PROCESS_COMMAND_LINE,
                "the browser process of this session (pid ${observation.browserPid ?: "?"}) is no longer running",
                observation,
            )
        }

        // 6. Could not check. State what was missing instead of guessing.
        return WindowVisibilityReport(
            WindowVisibility.UNKNOWN,
            WindowEvidenceSource.NONE,
            unverifiableReason(observation, requested),
            observation,
        )
    }

    private fun unverifiableReason(observation: WindowObservation, requested: String?): String = when {
        observation.processAlive == null && observation.browserPid == null ->
            "this session's browser could not be identified: no launcher pid marker was found, " +
                "so no window could be observed"

        observation.launchedHeadless == null && observation.windowState == null ->
            "neither the browser process command line nor its window state could be read; " +
                "the window of this session was not verified"

        observation.launchedHeadless == null ->
            "the window reports windowState=${observation.windowState}, but the browser process launch " +
                "mode could not be read, so a headless browser cannot be ruled out"

        else ->
            "the browser process of this session is not headless, but its window state could not be " +
                "read${if (requested != null) " (display mode $requested)" else ""}; the window was not verified"
    }
}

/**
 * Observes a session's own browser window.
 *
 * The check is **session-scoped** by construction: it reads the pid from the
 * session's own context directory, asks that browser about its window over CDP,
 * and never enumerates other processes. That is the difference from the
 * CLI-side process scan this replaces — which could only ever describe *other*
 * browsers, because a session's browser is launched with a temp context-group
 * profile that carries no workspace token.
 *
 * Every step is best-effort and contributes `null` when it fails; the policy
 * turns missing facts into [WindowVisibility.UNKNOWN], never into a warning.
 */
class SessionWindowProbe(
    private val processTable: ProcessTable = JdkProcessTable,
    private val cdpTimeoutMillis: Long = DEFAULT_CDP_TIMEOUT_MILLIS,
) {
    private val logger = getLogger(SessionWindowProbe::class)

    /**
     * Observe the window of the browser behind [driver].
     *
     * @param driver the session's bound driver (used for the CDP window call);
     *   null is fine — the process evidence is still collected
     * @param contextDir the session's chrome context dir (holds `launcher.pid`)
     * @param debugPort the CDP port the session's browser listens on
     * @param requestedDisplayMode `GUI` / `HEADLESS` / `SUPERVISED` as the session
     *   requested it, or null when it never pinned one
     * @param pageTargetUrls page targets observed at the endpoint, for the report
     * @return the verdict; [WindowVisibilityReport.verified] is false when the
     *   check could not be completed
     */
    suspend fun probe(
        driver: WebDriver?,
        contextDir: Path?,
        debugPort: Int,
        requestedDisplayMode: String?,
        pageTargetUrls: List<String> = emptyList(),
    ): WindowVisibilityReport {
        val pid = BrowserProcessReaper.readLauncherPid(contextDir)
        val facts = pid?.let { runCatching { processTable.facts(it) }.getOrNull() }
        val launchedHeadless = facts?.commandLine
            ?.takeIf { it.isNotBlank() }
            ?.let { commandLineIndicatesHeadless(it) }

        val window = probeWindow(driver)

        val observation = WindowObservation(
            requestedDisplayMode = requestedDisplayMode,
            launchedHeadless = launchedHeadless,
            // A pid marker whose process cannot be observed at all counts as gone.
            processAlive = when {
                pid == null -> null
                facts == null -> false
                else -> facts.alive
            },
            browserPid = pid,
            debugPort = debugPort,
            windowState = window?.state,
            windowId = window?.windowId,
            bounds = window?.bounds,
            pageTargetUrls = pageTargetUrls,
        )

        val report = WindowVisibilityPolicy.classify(observation)
        logger.debug(
            "Window check for session browser (pid={}, port={}): {} ({}) — {}",
            pid, debugPort, report.visibility, report.source, report.reason
        )
        return report
    }

    /**
     * Ask the browser itself for its window:
     * `Browser.getWindowForTarget` returns the window id, its bounds and its
     * state (`normal` / `minimized` / `maximized` / `fullscreen`).
     *
     * Returns null when the browser is unreachable, the command is unavailable,
     * or it does not answer in time — a missing observation, never a default.
     */
    private suspend fun probeWindow(driver: WebDriver?): CdpWindow? {
        val protocol = (driver as? PulsarWebDriver)?.browserProtocol ?: return null
        return runCatching {
            withTimeoutOrNull(cdpTimeoutMillis) {
                val result = protocol.executeCdpCommand(CDP_WINDOW_FOR_TARGET, null) as? Map<*, *>
                    ?: return@withTimeoutOrNull null
                val bounds = result["bounds"] as? Map<*, *>
                CdpWindow(
                    windowId = (result["windowId"] as? Number)?.toLong() ?: 0L,
                    state = bounds?.get("windowState")?.toString()?.lowercase(),
                    bounds = bounds?.let {
                        WindowBounds(
                            left = (it["left"] as? Number)?.toInt() ?: 0,
                            top = (it["top"] as? Number)?.toInt() ?: 0,
                            width = (it["width"] as? Number)?.toInt() ?: 0,
                            height = (it["height"] as? Number)?.toInt() ?: 0,
                        )
                    },
                )
            }
        }.onFailure {
            logger.debug("Browser.getWindowForTarget failed: {}", it.message)
        }.getOrNull()
    }

    private data class CdpWindow(
        val windowId: Long,
        val state: String?,
        val bounds: WindowBounds?,
    )

    companion object {
        /** How long the browser gets to answer the window query. */
        const val DEFAULT_CDP_TIMEOUT_MILLIS: Long = 2_000

        /** The CDP command that reports a window's identity, geometry and state. */
        private const val CDP_WINDOW_FOR_TARGET = "Browser.getWindowForTarget"

        /**
         * Whether a Chrome command line was launched in headless mode.
         *
         * Matches `--headless`, `--headless=new`, `--headless=old` and the
         * `--headless-shell` variant, ignoring case, and does not match unrelated
         * flags that merely contain the word.
         */
        fun commandLineIndicatesHeadless(commandLine: String): Boolean =
            HEADLESS_FLAG.containsMatchIn(commandLine)

        private val HEADLESS_FLAG = Regex("""(^|\s)--headless(=[^\s]*)?(\s|$)""", RegexOption.IGNORE_CASE)
    }
}
