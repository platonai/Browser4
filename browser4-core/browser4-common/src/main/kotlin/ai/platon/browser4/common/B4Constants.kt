package ai.platon.browser4.common

object B4Constants {
    /**
     * The mode of browser profile, case-insensitive.
     * default, system_default, prototype, sequential, temporary
     *
     * A replacement to BROWSER_CONTEXT_MODE
     */
    const val BROWSER_PROFILE_MODE = "browser.profile.mode"

    /**
     * Whether `console` captures messages over CDP (`true`, the default) or with the historical
     * page-side buffer (`false`).
     *
     * `true` enables the CDP `Console` domain for the session — the domain that delivers
     * `Console.messageAdded` — and leaves the page itself untouched. `false` never touches the
     * protocol and instead replaces `console.*` in the page world, which any page can see through
     * `String(console.log)`.
     *
     * The domain enable belongs to the same family of side effect that made the base library stop
     * sending `Runtime.enable` by default (`browser.launch.runtime.enable`, see
     * docs-dev/research/2026-09-10-cdp-antidetect-and-ai-native-browsers.md §1.5), so it is measured
     * rather than assumed: on Chrome 153.0.8010.52 the getter, inherited-getter and prototype-Proxy
     * probes on a logged object stay silent with this domain off and on
     * (`test_e2e_console_serialization_probe`), while the page-side patch fails the same page's
     * `String(console.log)` check outright. The switch stays as the escape hatch for a transport or
     * a deployment that judges the trade-off differently.
     */
    const val CONSOLE_CAPTURE_CDP = "browser.console.capture"

    /** Default of [CONSOLE_CAPTURE_CDP]; pinned by B4ConstantsTest. */
    const val CONSOLE_CAPTURE_CDP_DEFAULT = true

    /**
     * Whether each driven tab is told it is focused and active over CDP (`true`, the default) or
     * keeps the real window/tab visibility state (`false`).
     *
     * A tab created over CDP is never made the selected tab of its window, so a page driven by
     * Browser4 reports `document.hasFocus() == false`, and a driven tab that is not the window's
     * selected tab additionally reports `document.visibilityState == "hidden"` and
     * `document.hidden == true`. A bot detector reads those as a headless browser
     * (`document.hasFocus()` is checked by, for example, ipfighter's `windowFocus` rule), and they
     * are not what a plainly launched headless Chrome reports on the same host: measured on Chrome
     * 153.0.8010.53 against the same profile, plain Chrome reports `visible / false / true`, while a
     * Browser4-driven tab reports `visible / false / false` when it is the window's selected tab and
     * `hidden / true / false` when it is not (measured with two driven tabs in one browser).
     *
     * `true` sends `Emulation.setFocusEmulationEnabled {enabled: true}` once per tab, which makes the
     * tab report itself visible and focused without activating it — the mechanism Playwright uses.
     * Because the emulation is per target and never activates the tab, concurrent sessions in the
     * same browser do not steal each other's focus; `Target.activateTarget` / `Page.bringToFront`
     * would, so they are deliberately not used. The switch stays as the escape hatch for a
     * deployment that prefers the real window state (it is also the only way to keep background-tab
     * throttling and lazy-loading behaviour identical to a real user's inactive tab).
     */
    const val FOCUS_EMULATION = "browser.focus.emulation"

    /** Default of [FOCUS_EMULATION]; pinned by B4ConstantsTest. */
    const val FOCUS_EMULATION_DEFAULT = true

    /**
     * Whether the user's own Chrome extensions are inherited by a Browser4-launched browser
     * (`true`, the default) or disabled (`false`).
     *
     * Extensions arrive through the browser profile: a privacy context copies the prototype
     * data dir, so the user's extensions live in `Default/Extensions` of every copied
     * profile. A live extension runs its content script in the page, and Chromium then
     * installs the `chrome.runtime` skeleton in the page's main world — a shape bot
     * detectors sample (ipfighter reports `chrome.runtime.sendMessage` as a prototype leak;
     * a plain Chrome 155 with a clean profile and `--disable-extensions` never exposes it).
     *
     * `false` adds `--disable-extensions` at the single launch funnel and leaves every
     * automation-facing feature untouched (it is not a stealth switch).
     */
    const val EXTENSIONS_INHERIT = "browser.extensions.inherit"

    /** Default of [EXTENSIONS_INHERIT]; pinned by B4ConstantsTest. */
    const val EXTENSIONS_INHERIT_DEFAULT = true

    /**
     * Whether Browser4 injects its own anti-detection layer into driven pages
     * (`true`, the default) or leaves every page untouched (`false`).
     *
     * Browser4 ships a page-world stealth payload (the guarded `js/stealth.js`
     * registered through `Page.addScriptToEvaluateOnNewDocument`) and, on
     * headless launches, a reduced user agent. Both are automation-facing
     * patches. When a session is attached to a fingerprint browser that
     * already manages its own evasion (ego-lite, AdsPower, Multilogin, ...),
     * stacking Browser4's patches on top only adds footprints without
     * improving the verdict, and can even conflict with the fingerprint
     * browser's own patches.
     *
     * `false` skips the page-world payload registration entirely and also
     * suppresses the launch-time UA reduction. Human-like input behaviour
     * (trusted click cadence, segmented mouse moves, inter-character typing
     * delays) is **not** affected by this switch — that is the automation
     * style, not a stealth patch, and the fingerprint browser still needs it.
     *
     * A session launched through a **fingerprint-browser channel**
     * ([BROWSER_CHANNEL_EGO_LITE], i.e. ego lite) skips Browser4's stealth layer
     * automatically: ego lite owns the fingerprint and its own anti-detection
     * behaviour, so this switch is only consulted for ordinary channels. See
     * [stealthAppliesTo].
     */
    const val STEALTH_ENABLED = "browser.stealth.enabled"

    /** Default of [STEALTH_ENABLED]; pinned by B4ConstantsTest. */
    const val STEALTH_ENABLED_DEFAULT = true

    /**
     * True when [channel] names a **fingerprint browser** Browser4 drives without
     * injecting its own stealth layer. ego lite is such a browser: it ships its own
     * fingerprint (user agent, canvas/WebGL noise, …) and owns its anti-detection
     * behaviour, so Browser4 neither registers the page-world `js/stealth.js`
     * payload nor rewrites its user agent for a session on this channel.
     *
     * Accepts the same spellings the CLI accepts: `ego-lite`, `egolite`,
     * `ego lite`, `ego`, case-insensitive.
     */
    fun isEgoLiteChannel(channel: String?): Boolean {
        val normalized = channel?.trim()?.lowercase() ?: return false
        return normalized in EGO_LITE_CHANNEL_ALIASES
    }

    /**
     * True when Browser4's stealth layer applies to a session on [channel].
     *
     * Fingerprint-browser channels ([isEgoLiteChannel]) own their evasion, so
     * Browser4 stays out of the way regardless of [STEALTH_ENABLED].
     */
    fun stealthAppliesTo(channel: String?): Boolean = !isEgoLiteChannel(channel)

    private val EGO_LITE_CHANNEL_ALIASES = setOf(BROWSER_CHANNEL_EGO_LITE, "egolite", "ego lite", "ego")

    /**
     * Selects which [ai.platon.browser4.behavior.stealth.BrowserStealthProvider] handles
     * browser stealth, by provider id (case-insensitive).
     *
     * Absent/blank (the default) selects the highest-priority provider discovered on the
     * classpath, which is the community default (`browser4-behavior-default`, id `default`)
     * unless a professional or custom edition module is installed. The value is read from the
     * browser configuration, so it can be set in `application.properties` or passed as a
     * `-Dbrowser.behavior.stealth.provider=<id>` JVM option.
     *
     * An id that is not installed is not fatal: the highest-priority provider is used and a
     * warning names the configured and the effective provider once.
     */
    const val BEHAVIOR_STEALTH_PROVIDER = "browser.behavior.stealth.provider"

    /**
     * Selects which [ai.platon.browser4.behavior.humanization.InteractionHumanizer] paces
     * browser interactions, by provider id (case-insensitive).
     *
     * Same resolution rules as [BEHAVIOR_STEALTH_PROVIDER]: absent/blank selects the
     * highest-priority provider on the classpath (the community `default` implementation),
     * and an unknown id falls back to that provider with one warning.
     */
    const val BEHAVIOR_HUMANIZATION_PROVIDER = "browser.behavior.humanization.provider"

    const val SESSION_ID_CAPABILITY = "sessionId"
    const val PROFILE_MODE_CAPABILITY = "profileMode"
    const val PROFILE_PATH_CAPABILITY = "profilePath"

    /**
     * The capability that names the browser channel a session launches
     * (`channel`, e.g. `open --channel ego-lite`). Absent means the default
     * Chrome. See [BROWSER_CHANNEL].
     */
    const val CHANNEL_CAPABILITY = "channel"

    /**
     * The capability that tells a session **whether it may launch a browser**.
     *
     * Absent means "own browser" — the session may launch (lazily) when a page is
     * first needed. See [BROWSER_LAUNCH_POLICY_ATTACHED] for the other case.
     */
    const val ATTACHED_CAPABILITY = "attached"

    /**
     * The session-level browser launch policy (`browser.launch.policy`), derived
     * from the [ATTACHED_CAPABILITY] capability.
     *
     * Sessions attached to an external browser over CDP or the extension relay
     * ([BROWSER_LAUNCH_POLICY_ATTACHED]) must **never** launch a browser of their
     * own: the browser belongs to the user, and a silently launched Chrome would
     * be a second, unrelated browser next to the one the user attached. When no
     * browser is bound yet, the session fails loudly instead (the attach is still
     * connecting, or it disconnected).
     */
    const val BROWSER_LAUNCH_POLICY = "browser.launch.policy"

    /** [BROWSER_LAUNCH_POLICY] value for a session bound to an external browser. */
    const val BROWSER_LAUNCH_POLICY_ATTACHED = "attached"

    /**
     * The session-level browser channel (`browser.channel`), set from the
     * [CHANNEL_CAPABILITY] capability. Absent or blank means the default Chrome;
     * [BROWSER_CHANNEL_EGO_LITE] launches the installed ego (lite) browser
     * instead. The channel decides which browser binary the launch resolves
     * (see `EgoLiteBrowserLauncher`); profile mode, display mode and every
     * other launch setting apply unchanged.
     */
    const val BROWSER_CHANNEL = "browser.channel"

    /** [BROWSER_CHANNEL] value selecting the ego (lite) browser. */
    const val BROWSER_CHANNEL_EGO_LITE = "ego-lite"

    /**
     * The session-level browser profile path, set from the `profilePath`
     * capability (e.g. `open --profile <path>`). When present, the session's
     * browser launches with this directory as the Chrome user data dir
     * instead of a Browser4-managed profile directory.
     */
    const val BROWSER_PROFILE_PATH = "browser.profile.path"

    /**
     * The session-level browser context directory capability. Set by the
     * backend for CLI named sessions so the same session always binds the
     * same dedicated chrome user data dir instead of rotating through the
     * SEQUENTIAL pool on every launch.
     */
    const val CONTEXT_DIR_CAPABILITY = "contextDir"

    /**
     * The session-level browser context directory, set from the `contextDir`
     * capability. When present, the session's browser launches with a
     * Browser4-managed context directory dedicated to this session
     * (e.g. .../context/groups/named/PULSAR_CHROME/cx.<sessionUuid>).
     */
    const val BROWSER_CONTEXT_DIR = "browser.context.dir"

    /**
     * The REST level session id - DEFAULT
     * */
    const val DEFAULT_SESSION_ID = "DEFAULT"
    /**
     * The REST level session id - SWARM
     * */
    const val SWARM_SESSION_ID = "SWARM"
    /**
     * The SWARM session label
     * */
    const val SWARM_SESSION_LABEL = "SWARM"

    const val VAR_IS_SCRAPE = "IS_SCRAPE"

    /**
     * The value of a page-level HTTP status field when **no page status was
     * observed** (`0`).
     *
     * Page status fields (`pageStatusCode` on `CommandStatus`, `ScrapeResponse`,
     * `PageVisitStatus`, `AgentTaskStatus`) describe the *page*, not the task, so
     * they must not be defaulted to a task-lifecycle code: `201 / SC_CREATED`
     * ("created") was exactly that mistake — every agent/loop record reported
     * `"pageStatusCode": 201` next to a message saying the page loaded with HTTP
     * 200, and a monitoring script keying on the field misclassified the task.
     *
     * `0` is the honest representation: HTTP status codes start at 100, so no real
     * response can be confused with it, it keeps the field an `Int` (the MCP
     * result schemas, the swarm persistence JSONL and every existing consumer stay
     * valid), and it matches the codebase's existing "absent" convention for
     * `NetworkResourceResponse.httpStatusCode`. A real `201` from a page is
     * therefore still distinguishable from "not observed".
     *
     * Producers must set the field from an observation when they have one (see
     * `DocumentHttpStatus`), and leave it at this value when they do not.
     */
    const val PAGE_STATUS_UNKNOWN = 0

    const val BROWSER4_CONTEXT_CONFIG_LOCATION = "classpath:browser4-beans/app-context.xml"
}
