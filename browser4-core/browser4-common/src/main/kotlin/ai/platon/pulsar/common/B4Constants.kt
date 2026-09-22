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
