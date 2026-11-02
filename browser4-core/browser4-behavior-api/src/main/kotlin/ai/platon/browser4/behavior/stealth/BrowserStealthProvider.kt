package ai.platon.browser4.behavior.stealth

import ai.platon.browser4.behavior.BrowserBehaviorEdition
import ai.platon.pulsar.api.ChromeOptions
import ai.platon.pulsar.api.WebDriver
import ai.platon.pulsar.api.model.BrowserSettings

/**
 * The replaceable browser-stealth contract.
 *
 * A provider owns everything Browser4 does to make its browser look like the browser a real
 * user runs, and nothing else. The driver deliberately splits this from the mechanisms it
 * must keep for correctness (trusted CDP input, dialog handling, focus emulation, the base
 * library's launch flags), so a profile can be replaced without forking the driver.
 *
 * ## What a provider controls
 *
 * 1. **Launch-time hardening** — [applyLaunchOptions] runs from the single funnel every
 *    browser launch goes through (`DefaultBrowserFactory.launch`), before the process starts.
 *    The default edition applies the reduced (headless-free) `--user-agent` and the
 *    `--disable-extensions` environment-purity switch. A professional edition can add launch
 *    arguments, a fingerprint seed, a proxy-consistency policy, and so on.
 * 2. **Document-start scripts** — [documentStartScripts] is injected by the driver with
 *    `Page.addScriptToEvaluateOnNewDocument` before the first navigation of the session, so
 *    the scripts run before any page script. Registrations are additive and run *after* the
 *    base library's page-world payload (`js/stealth.js`), which means a provider can override
 *    a value the baseline sets. The community implementation returns an empty list: its
 *    page-world layer is the baseline payload shipped by the driver module as the classpath
 *    resource `js/stealth.js`.
 * 3. **Attach-time hardening** — [onDriverReady] runs once per driver, before the first
 *    navigation, with the driver already connected to its tab. This is the hook for CDP-level
 *    emulation a page script cannot provide (`Emulation.setUserAgentOverride`,
 *    `Emulation.setLocaleOverride`, `Emulation.setTimezoneOverride`,
 *    `Emulation.setDeviceMetricsOverride`, permission overrides, WebRTC policy, ...).
 *
 * ## What a provider must not do
 *
 * - It must not change the **semantics** of an interaction: a click must still click the
 *   element the caller selected, a type must still deliver the text the caller passed.
 * - It must not make a failure silent. A throw from [applyLaunchOptions] fails the launch with
 *   that error — Chrome has not started yet, so failing loudly is the correct outcome. The
 *   driver-level hooks are different: a throw from [documentStartScripts] or [onDriverReady] is
 *   logged and the navigation continues, because a browser without its extra hardening layer
 *   still works while a browser that refuses to navigate does not.
 * - It must be **idempotent-safe**: [applyLaunchOptions] may be called for every launch,
 *   [onDriverReady] once per driver, and [documentStartScripts] once per driver. None of the
 *   hooks may assume it is the only provider installed in the JVM.
 * - It must be **thread-safe**: one provider instance is shared by every browser in the JVM,
 *   and launches (and driver-ready hooks) of different sessions can run concurrently.
 *
 * ## Discovering and selecting a provider
 *
 * Implementations are discovered with [java.util.ServiceLoader]: register the FQCN in
 * `META-INF/services/ai.platon.browser4.behavior.stealth.BrowserStealthProvider` (or the
 * `@AutoService` equivalent). Exactly one implementation module should be on the runtime
 * classpath — the default module, or one edition module. When several are present the
 * highest [priority] wins and [BrowserBehavior][ai.platon.browser4.behavior.BrowserBehavior]
 * logs which one it selected; [ai.platon.browser4.common.B4Constants.BEHAVIOR_STEALTH_PROVIDER]
 * pins a specific [id].
 *
 * @see ai.platon.browser4.behavior.BrowserBehavior
 * @see ai.platon.browser4.behavior.humanization.InteractionHumanizer
 */
interface BrowserStealthProvider {
    /**
     * Stable provider id, used to select this provider from configuration
     * ([ai.platon.browser4.common.B4Constants.BEHAVIOR_STEALTH_PROVIDER]).
     *
     * Treat it as public API: renaming it breaks existing deployments. Use kebab-case for
     * edition providers (`pro`, `my-company`) and keep `default` for the community one.
     */
    val id: String

    /**
     * The product edition this provider belongs to, one of [BrowserBehaviorEdition].
     *
     * Informational only — it labels the diagnostic log, it does not participate in selection.
     */
    val edition: String
        get() = BrowserBehaviorEdition.DEFAULT

    /**
     * Selection order when several providers are installed: a larger value wins.
     *
     * The community implementation uses `0`; an edition module that is installed *next to*
     * the default one uses a positive value so it wins without configuration. Negative values
     * are reserved for special-purpose providers that are enabled explicitly by id.
     */
    val priority: Int
        get() = 0

    /**
     * Apply stealth policy to the launch options of the browser that is about to start.
     *
     * Called from `DefaultBrowserFactory.launch`, the single funnel every session launch,
     * pooled launch and legacy display-mode launch goes through, so an implementation never
     * needs a second integration point. The method must not start the browser or read CDP —
     * it may only inspect and modify [StealthLaunchContext.options] and
     * [StealthLaunchContext.settings].
     *
     * A user or caller decision already present in the options (an explicit `--user-agent`,
     * an argument the caller added) must win over a stealth default; report only what this
     * call actually changed in the returned [StealthLaunchReport].
     *
     * A throw fails the browser launch with that error (Chrome has not started yet), so a
     * misconfigured edition surfaces its real cause instead of silently launching a browser
     * without the policy.
     *
     * @return what was applied, for diagnostics and tests. Returning an empty report is valid;
     *   the launch continues regardless.
     */
    fun applyLaunchOptions(context: StealthLaunchContext): StealthLaunchReport

    /**
     * Additional scripts to run at document start, injected before the first navigation.
     *
     * The driver registers each script with `Page.addScriptToEvaluateOnNewDocument`, so it
     * reaches every new document (navigations, iframes, newly opened tabs of this target)
     * before any page script. Scripts run after the base library's page-world payload, which
     * lets a provider override baseline values.
     *
     * The default implementation returns an empty list; it is not the mechanism that ships
     * the baseline `js/stealth.js` payload (the base library loads that from the classpath).
     *
     * @return scripts in execution order; an empty list (the default) means "nothing to add".
     */
    fun documentStartScripts(context: StealthSessionContext): List<DocumentStartScript> = emptyList()

    /**
     * Whether the base library may register its **baseline** page-world stealth payload
     * (`js/stealth.js`) for the session.
     *
     * The base library registers that payload unconditionally through
     * `Page.addScriptToEvaluateOnNewDocument`; the driver wraps its `BrowserProtocol` so this
     * answer decides whether the registration is delegated or turned into a no-op (the same
     * mechanism that implements `browser.stealth.enabled=false`). Returning `false` leaves the
     * page completely untouched by Browser4's anti-detection layer, which is what a
     * fingerprint browser that owns its own evasion needs.
     *
     * This hook gates the *baseline* only. Scripts returned by [documentStartScripts] are a
     * provider's own layer and stay the provider's decision.
     *
     * Called once per driver, before the tab's first navigation.
     *
     * @return `true` (the default) to keep the baseline payload; `false` to suppress it.
     */
    fun baselinePageWorldScriptsEnabled(context: StealthSessionContext): Boolean = true

    /**
     * One-time per-driver hardening hook, called with the driver attached to its tab and
     * before the first navigation of that driver.
     *
     * Use it for CDP emulation that must exist before the page loads. It is called at most
     * once per driver; a throw is logged and never fails the navigation.
     *
     * The default implementation does nothing.
     */
    suspend fun onDriverReady(driver: WebDriver) {
        // No driver-level hardening in the default contract.
    }
}

/**
 * Everything a [BrowserStealthProvider.applyLaunchOptions] call may inspect or change.
 *
 * @param options the launch options of the browser that is about to start; mutable, the
 *   provider's changes are what reaches the Chrome command line.
 * @param settings the browser settings the launch was resolved from (configuration, display
 *   mode, user-agent policy). Read-only from the provider's point of view.
 * @param channel the resolved session-level browser channel (`browser.channel`, e.g.
 *   `ego-lite`), or `null` for the default Chrome. A fingerprint-browser channel owns its own
 *   user agent and anti-detection behaviour, so a provider must not rewrite it
 *   (`B4Constants.stealthAppliesTo(channel)`).
 */
data class StealthLaunchContext(
    val options: ChromeOptions,
    val settings: BrowserSettings,
    val channel: String? = null,
)

/**
 * What [BrowserStealthProvider.applyLaunchOptions] changed, for diagnostics and tests.
 *
 * @param providerId the [BrowserStealthProvider.id] that produced the report.
 * @param edition the provider's [BrowserStealthProvider.edition].
 * @param userAgent the user agent this call put on the launch options, or `null` when the
 *   user agent was left exactly as the caller/configuration decided it.
 * @param extensionsDisabled whether this call added `--disable-extensions`.
 * @param addedArguments the launch arguments this call appended, in order.
 */
data class StealthLaunchReport(
    val providerId: String,
    val edition: String = BrowserBehaviorEdition.DEFAULT,
    val userAgent: String? = null,
    val extensionsDisabled: Boolean = false,
    val addedArguments: List<String> = emptyList(),
) {
    /** Whether this call changed the launch at all. */
    val modified: Boolean
        get() = userAgent != null || extensionsDisabled || addedArguments.isNotEmpty()
}

/**
 * Everything a [BrowserStealthProvider.documentStartScripts] call may inspect.
 *
 * @param settings the browser settings of the session the driver belongs to.
 */
data class StealthSessionContext(
    val settings: BrowserSettings,
) {
    /** The display mode (GUI / HEADLESS / SUPERVISED) the session was launched with. */
    val displayMode get() = settings.displayMode

    /** Whether the browser is running headless. */
    val headless get() = settings.isHeadless
}

/**
 * One page-world script a [BrowserStealthProvider] wants injected at document start.
 *
 * @param name a short, stable name used in diagnostics only.
 * @param content a plain script (no ESM, no `require`/`import`, no top-level `await`) — the
 *   same contract as the base library's page-world resources.
 */
data class DocumentStartScript(
    val name: String,
    val content: String,
)

/**
 * The no-op stealth provider used when no provider is installed on the classpath.
 *
 * It leaves the launch options untouched, so the base library's own defaults (its launch
 * flags) still apply and the browser stays fully functional — it only loses the Browser4
 * hardening layers. The registry logs a warning in that situation, because a product build
 * that dropped the behavior module by accident should be visible.
 */
internal object NeutralBrowserStealthProvider : BrowserStealthProvider {
    const val ID: String = "none"

    override val id: String = ID
    override val priority: Int = Int.MIN_VALUE

    override fun applyLaunchOptions(context: StealthLaunchContext): StealthLaunchReport =
        StealthLaunchReport(providerId = id)
}
