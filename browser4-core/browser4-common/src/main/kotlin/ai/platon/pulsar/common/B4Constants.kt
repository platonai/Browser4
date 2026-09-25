package ai.platon.pulsar.common

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


    const val SESSION_ID_CAPABILITY = "sessionId"
    const val PROFILE_MODE_CAPABILITY = "profileMode"

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

    const val BROWSER4_CONTEXT_CONFIG_LOCATION = "classpath:browser4-beans/app-context.xml"
}
