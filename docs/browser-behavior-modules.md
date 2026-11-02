# Browser behavior modules — replaceable stealth and interaction humanization

Browser4 splits the two "look like a human" layers out of the driver:

| Layer | Contract | Default implementation |
|---|---|---|
| **Browser stealth** — what the browser *is* (launch flags, user agent, fingerprint, document-start patches, CDP emulation) | `BrowserStealthProvider` | `browser4-behavior-default` (`DefaultBrowserStealthProvider`) |
| **Interaction humanization** — how input *behaves in time and space* (button hold, pointer trajectory, click jitter, typing pace, drag pacing) | `InteractionHumanizer` | `browser4-behavior-default` (`DefaultInteractionHumanizer`) |

The current behavior is the **default (community) implementation** and is unchanged. A
professional or custom edition is a separate module that implements the same SPI and replaces
the default module on the classpath.

## Module layout

```
browser4-core/
  browser4-behavior-api/        # SPI only: contracts, types, registry, no implementation
    ai.platon.browser4.behavior.BrowserBehavior            # provider registry / selection
    ai.platon.browser4.behavior.BrowserBehaviorEdition     # "default" | "professional" | "custom"
    ai.platon.browser4.behavior.stealth.BrowserStealthProvider
    ai.platon.browser4.behavior.humanization.InteractionHumanizer
  browser4-behavior-default/    # the current behavior, moved here verbatim
    DefaultBrowserStealthProvider + Browser4UserAgent + Browser4Extensions
    DefaultInteractionHumanizer
    META-INF/services/...                                  # ServiceLoader registration
  browser4-browser/             # driver: API (compile) + default (runtime) + js/stealth.js
  browser4-protocol/            # launch funnel: calls the stealth provider
```

`browser4-browser` compiles against the API only. The default implementation is a **runtime**
dependency, so no production class references it by name — replacing it is a classpath change,
not a code change.

The baseline page-world payload `js/stealth.js` deliberately stays in `browser4-browser`: the
base `DualWorldScriptLoader` resolves it from the classpath and shadows the base jar's copy, so
the module providing it must be a *direct* dependency of every product module (which the driver
is, while the default behavior module is transitive). It is still the page-world layer of the
default stealth policy; an edition adds its own layer with `documentStartScripts` (registered
after the baseline, so it wins), or shadows the resource itself when it is a direct dependency.

## Where the seams are

| Hook | Called from | What a provider can do |
|---|---|---|
| `applyLaunchOptions(context)` | `DefaultBrowserFactory.launch()` — the single funnel every session, pooled and legacy launch goes through | mutate `ChromeOptions` before Chrome starts: user agent, `--disable-extensions`, fingerprint flags, proxy consistency, arbitrary launch arguments. The context carries the resolved `browser.channel`, so a provider can leave a fingerprint browser alone |
| `baselinePageWorldScriptsEnabled(context)` | `Browser4WebDriver` constructor, before the tab's first document | allow or suppress the base library's `js/stealth.js` registration. The driver turns the answer into a `BrowserProtocol` decorator, which is the mechanism `browser.stealth.enabled=false` uses |
| `documentStartScripts(context)` | `Browser4WebDriver.ensureStealthApplied()`, before the first navigation | return scripts registered with `Page.addScriptToEvaluateOnNewDocument`; they run before any page script and *after* the base library's page-world payload, so they can override it |
| `onDriverReady(driver)` | same hook, once per driver with the tab attached | CDP emulation a page script cannot provide: `Emulation.setUserAgentOverride`, `setLocaleOverride`, `setTimezoneOverride`, `setDeviceMetricsOverride`, permission/WebRTC policy, … |
| `pointerMove`, `clickPoint`, `pressHoldMillis`, `multiClickGapMillis`, `keyPressDelayMillis`, `typeCharDelayMillis`, `dragPlan` | the click/type/press/drag overrides in `Browser4WebDriver` | pacing and pointer geometry. The driver still decides *which* CDP call to use and keeps the trusted-input verification, dialog handling, frame checks and DOM fallbacks |

Deliberately **not** in the SPI (they are correctness, not styling): trusted CDP input and its
fallbacks, dialog handling, frame resolution, focus emulation, and the base library's
`--disable-blink-features=AutomationControlled` launch flag.

## Selection

Providers are discovered with `java.util.ServiceLoader`. The registry resolves in this order:

1. the id configured in `browser.behavior.stealth.provider` /
   `browser.behavior.humanization.provider` (case-insensitive);
2. otherwise the installed provider with the highest `priority` (ties broken by `id`, so the
   choice is stable across JVM runs);
3. otherwise a neutral, fully functional fallback — the base library's defaults for stealth,
   upstream timing for interactions — and a warning.

```properties
# application.properties or -D JVM option; empty = highest priority
browser.behavior.stealth.provider=
browser.behavior.humanization.provider=
```

The active implementation is announced once at first use:

```
Browser behavior: stealth=default [default], humanization=default [default]
```

An unknown id is not fatal: the highest-priority provider is used and one warning names the
configured and the effective id. `BrowserBehavior.installedStealthProviders()` /
`installedHumanizers()` expose the discovered providers for diagnostics.

## Building a professional / custom edition

1. Create a module that depends on `browser4-behavior-api`.
2. Implement `BrowserStealthProvider` and/or `InteractionHumanizer`.
   - `id` is public API (it is what deployments pin in configuration).
   - `edition` is informational — use `BrowserBehaviorEdition.PROFESSIONAL` / `CUSTOM`.
   - `priority` must be > 0 if the module is installed *next to* the default one.
   - `applyLaunchOptions` must never override a decision the caller/configuration already made.
   - A humanizer must keep interactions semantically identical: stay inside the element box,
     return `steps >= 1` and non-negative delays, and return exactly 4 drag delays for a center
     drop and 6 for a positioned one.
3. Register the implementation:

   ```
   src/main/resources/META-INF/services/ai.platon.browser4.behavior.stealth.BrowserStealthProvider
   src/main/resources/META-INF/services/ai.platon.browser4.behavior.humanization.InteractionHumanizer
   ```

   (one FQCN per line; both SPI types are independent, so an edition may replace only one).
4. For the page-world layer, return scripts from `documentStartScripts()` — they are registered
   after the baseline `js/stealth.js` payload, so they can override it. (Shadowing the classpath
   resource with a `js/stealth.js` of your own only works from a module that is a direct
   dependency of the product, because the base loader takes the first classpath occurrence.)
5. In the product build, **exclude `browser4-behavior-default`** so only one policy module is on
   the classpath. The registry warns when it sees an edition provider next to the community one.

A skeleton:

```kotlin
class ProHumanizer : InteractionHumanizer {
    override val id = "pro"
    override val edition = BrowserBehaviorEdition.PROFESSIONAL
    override val priority = 100

    override fun pointerMove(request: PointerMoveRequest, delays: DelaySource) =
        PointerMovePlan(steps = bezierSteps(request), delayMillis = delays.delayMillis("move"))

    // ... the remaining decisions
}
```

## Behavior contract of the default edition

The default module is the historical behavior, moved — not retuned:

- **Launch:** headless launches get the reduced `--user-agent` (no `HeadlessChrome` token);
  `browser.extensions.inherit=false` adds `--disable-extensions`. Headed launches and an explicit
  user agent are left alone. The master switch `browser.stealth.enabled=false` and a
  fingerprint-browser channel (`browser.channel=ego-lite` / `open --channel ego-lite`) suppress
  the user-agent rewrite; the extension policy still applies.
- **Page world:** the conditional payload in `browser4-browser`'s `js/stealth.js` — the vendored
  puppeteer-extra-plugin-stealth bundle runs only when `navigator.webdriver === true` (a
  CDP-driven browser), never on a launch that reports `false`. The baseline registration is
  suppressed by `browser.stealth.enabled=false` and by a fingerprint-browser channel
  (`baselinePageWorldScriptsEnabled`), and the default provider adds no document-start scripts
  of its own.
- **Pointer:** ~20 px per step, 3–30 steps, 8–16 ms per step; click points nudged up to ±2 px,
  clamped inside the element box.
- **Pacing:** `click` bucket for button hold and multi-click gaps, `press` bucket for key presses,
  `type` bucket per character and doubled for a surrogate pair.
- **Drag:** press/release points jittered ±2 px (center drops only), 120–300 ms between events,
  4 delays for a center drop and 6 for a positioned one.

Human-like input (the whole humanization contract below) is deliberately **not** gated by
`browser.stealth.enabled`: it is the automation style, not a stealth patch, and a fingerprint
browser still needs it.

The defaults are pinned by unit tests in each module:

```bash
mvn test -pl browser4-core/browser4-behavior-api
mvn test -pl browser4-core/browser4-behavior-default
mvn test -pl browser4-core/browser4-browser -Dtest='Browser4WebDriverTest,PageWorldStealthPayloadTest'
```

`PageWorldStealthPayloadTest` (in `browser4-browser`) pins the baseline payload: it reads
`/js/stealth.js` back from the classpath and fails if the guard, the vendored bundle or the
provenance disappears.

## Migration note

The two launch-time stealth policies moved with their implementation:

| Before | After |
|---|---|
| `ai.platon.pulsar.chrome.Browser4UserAgent` | `ai.platon.browser4.behavior.defaults.stealth.Browser4UserAgent` |
| `ai.platon.pulsar.chrome.Browser4Extensions` | `ai.platon.browser4.behavior.defaults.stealth.Browser4Extensions` |

They are implementation details of the default edition now; product code goes through
`BrowserBehavior.stealth(...)` instead. Code that called them directly must depend on
`browser4-behavior-default` explicitly (it is a *runtime* dependency of `browser4-browser`, so
it is not on a consumer's compile classpath).
