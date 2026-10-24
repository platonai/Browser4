# Bot-stealth probes

Diagnostic scripts used to attribute anti-bot findings to Browser4 or to the host, instead of
guessing. They live here (not under `target/`) so a reviewer can re-run them after a
`mvn clean`.

Everything here is read-only with respect to the repository: it launches browsers, reads
pages, and prints JSON.

## `plain-creepjs.mjs` — plain-Chrome baseline over raw CDP

The problem it solves: when a detector flags a Browser4 session, the first question is
"would *stock* Chrome on this host be flagged too?". Answering it needs a browser with no
Browser4 code, no Browser4 launch flags and no page-world injection — but still driven by
CDP, so the reading is produced the same way.

```bash
# Requires Node >= 21 (global WebSocket) and Chrome at the default Windows path.
node docs-dev/copilot/bot-stealth-probes/plain-creepjs.mjs \
     "https://abrahamjuliot.github.io/creepjs/" 90000 > /tmp/plain.json
```

It spawns `chrome.exe --headless=new --disable-gpu --no-sandbox --remote-debugging-port=0`
with a throwaway profile (`stdio: 'ignore'`, port read back from `DevToolsActivePort` — no
pipes), connects to the page target, polls until creepjs' percentages stop being
placeholders, and prints:

- `creepjs` — the headless / stealth / resistance blocks, read by `creep-blocks.js`
- `nativeToString` — the `Function.prototype.toString` reading from `native-tostring.js`

Edit the `chrome` constant for another platform or Chrome location.

## `plain-rebrowser.mjs` — stock-browser baseline for `bot-detector.rebrowser.net`

The same attribution question as `plain-creepjs.mjs`, for rebrowser's detector: it launches a
throwaway-profile Edge or Chrome over raw CDP (no Browser4, no extension), reads the detector's own
result table, and additionally reports the two constructs the detector calls `nativeTampering` —
`Function.prototype.toString` being a `Proxy`, and a `srcdoc` iframe returning the parent realm
from `contentWindow`:

```bash
node docs-dev/copilot/bot-stealth-probes/plain-rebrowser.mjs edge 40000
HEADED=1 node docs-dev/copilot/bot-stealth-probes/plain-rebrowser.mjs edge 40000   # headed window
```

Compare its output with the same page driven through Browser4: anything red in both runs belongs to
the host or the detector, anything red only in Browser4's run belongs to Browser4.

## The probe expressions

| File | Reads |
|---|---|
| `creep-blocks.js` | creepjs' own `.col-six` blocks (`Headless`, `Resistance`) through the DOM, so the numbers keep their labels |
| `native-tostring.js` | `Function.prototype.toString` of `Function.prototype.toString` plus six native members, and whether they all collapse to one source |
| `page-world-probe.js` | the **page's main world**, by appending an inline `<script>` and handing the result back through a `data-pw-probe` attribute — an isolated world has its own `window`, so a DOM attribute is the only shared channel |
| `iframe-world-probe.js` | the same digest for the top document **and** a child iframe realm (creepjs names iframe members first and reports `hasIframeProxy`) |

`page-world-probe.js` and `iframe-world-probe.js` paste an expression into the page, so
they must be handed to the CLI as a file: `browser4-cli eval --file <path>`.

## Running a probe through Browser4

```bash
./b4w.ps1 -s probe open --headless "https://example.com"
./b4w.ps1 -s probe eval --file docs-dev/copilot/bot-stealth-probes/page-world-probe.js
```

## What these probes established (2026-09-23, Chrome 153.0.8010.53, Windows 11)

| Reading | plain Chrome (raw CDP) | Browser4 session |
|---|---|---|
| `navigator.userAgent` | `HeadlessChrome/153.0.0.0` | `Chrome/153.0.0.0` |
| `navigator.webdriver` | `true` | `false` |
| creepjs `like headless` | 31% | 31% |
| creepjs `headless` | **100%** (`webDriverIsOn`, `hasHeadlessUA`, `hasHeadlessWorkerUA` all true) | **0%** (all three false) |
| creepjs `stealth` | **0%** | **60%** |
| creepjs `resistance.extension` | `unknown` | `puppeteer-extra × Pattern` |
| top-document realm natives | 10 distinct sources, all `[native code]` | **same: 10 distinct, all `[native code]`** |
| child-iframe realm natives | — | **same: 10 distinct, all `[native code]`** |

Conclusions:

1. Browser4 is strictly better than stock Chrome on creepjs' two headline scores
   (`headless` 100% → 0%, `stealth` 0% → 60%); the residual `31% like headless` is identical
   in both and is therefore the host and the headless mode itself, not Browser4.
2. The `extension: puppeteer-extra × Pattern` line fires only for a Browser4 session, but the
   members it names are **not** patched in any realm these probes can read. The label is creepjs'
   nearest matching *known-extension template*. (Corrected 2026-10-24: the page world *did* carry
   the puppeteer-extra bundle — see the rebrowser section below, which catches it through
   `Function.prototype.toString` and `iframe.contentWindow` rather than through the members
   creepjs names.)
3. Stock Chrome never reaches that analysis: it is already 100% "headless", and reports
   `extension: unknown`. So part of "Browser4 is flagged where plain Chrome is not" is an
   artifact of Browser4 *passing* the earlier checks and being subjected to the deeper ones.
4. The puppeteer-extra line predates the 2026-09-23 fix round (the `20260916-174704-Bot
   stealth check` report already saw it), so it is not a regression.

## What the rebrowser detector established (2026-10-24, Edge 155.0.8059.40, Windows 11)

Three runs of `https://bot-detector.rebrowser.net/`: `plain-rebrowser.mjs edge` on a throwaway
profile (headless and `HEADED=1`), and the same page driven by Browser4 — an
`attach --extension msedge` session on the real, logged-in Edge, plus a Browser4-launched headed
Chrome for comparison.

| Reading | stock Edge (headed, no extensions) | Browser4 `attach --extension` to the real Edge | Browser4-launched Chrome (headed) |
|---|---|---|---|
| `nativeTampering` | 🟢 untouched | 🔴 `Function.toString` Proxy **+** iframe realm **+** two `navigator` own props | 🔴 `Function.toString` Proxy + iframe realm |
| `navigatorWebdriver` | `webdriver = true` under raw CDP | 🔴 `getBattery`, `privateAttribution` own properties | 🟢 |
| `useragent` | 🔴 "Google Chrome is not presented" | 🔴 same | 🟡 "Cannot detect Chrome version" (`fullVersionList: []`) |
| every other row | 🟢 | 🟢 | 🟢 |

Conclusions:

1. **The page-world stealth payload was the Browser4-caused flag.** rebrowser's two rating-1
   findings are the payload's `Function.prototype.toString` Proxy
   (`at newHandler.<computed> [as apply] (eval at <anonymous> …)`) and its
   `iframe.contentWindow` evasion, and a stock browser on the same host scores green on the same
   page. The bundle cannot be trimmed evasion by evasion — every evasion's plausible deniability
   is the same global `toString` Proxy, so keeping any of them keeps the signal. Fixed by making
   the payload **conditional** in the shadow resource
   (`browser4-core/browser4-browser/src/main/resources/js/stealth.js`, pinned by
   `PageWorldStealthPayloadTest`): the vendored bundle now runs only behind
   `if (navigator.webdriver === true) { … }`. That flag is exactly the admission of a CDP-driven
   browser — `attach --cdp`, a browser started with a remote-debugging port, Puppeteer/Playwright
   — and it is `false` for a real browser reached through the extension *and* for every Browser4
   launch (they pass `--disable-blink-features=AutomationControlled`). A browser that does not
   claim to be automated has nothing to hide, so it is not patched; a browser that does keeps the
   evasions that hide it, at the known native-tampering cost.
2. **The two `navigator` own properties are not Browser4's.** They appear only in that Edge
   profile: the same payload adds none in the Chrome session, and a throwaway Edge profile has
   none. Both are synthesized forwarding accessors (`function A() { return G.get(); }`) and
   `Navigator.prototype.privateAttribution` does not exist, so something in that profile injects
   a generic navigator patch; no installed extension's files contain it, i.e. it is injected
   dynamically (a `chrome.debugger` user, not a declared content script). Until that profile is
   cleaned up, `navigatorWebdriver` and part of `nativeTampering` stay red for extension-attached
   sessions regardless of what Browser4 does.
3. **`useragent` is a detector false positive on Edge.** Its check filters `fullVersionList` for
   the `Google Chrome` brand; Edge advertises `Microsoft Edge` + `Chromium`, and a stock headed
   Edge fails it too. Only Chrome sessions can pass that row.
4. **The `--user-agent` launch switch costs the client hints.** Measured with
   `plain-rebrowser.mjs` on a clean profile: with `--user-agent` on the command line, Chrome
   returns `fullVersionList: []` (low-entropy `brands` stays) even when the string is
   byte-identical to the native one; without the switch the list is populated. That is the 🟡 in
   the Chrome column. `Browser4UserAgent.applyTo` now adds the switch to headless launches only,
   where it buys the `HeadlessChrome` token removal; a headed launch keeps the metadata.

### Verified after the payload was guarded (same host, same Edge, same detector)

The runtime bundle was rebuilt and the same real Edge was re-attached
(`attach --extension msedge`, fresh tab on `https://bot-detector.rebrowser.net/`; the browser
reports `navigator.webdriver === false`, so the guarded bundle does not run):

| Reading | before (unguarded bundle) | after (guarded bundle) |
|---|---|---|
| `nativeTampering` | 🔴 three rating-1 notes + own props | 🟡 own props only |
| `tampered["Function.toString"]` | `Proxy (cyclic __proto__ not detected)` | absent |
| `automationFrames` | `Function.toString: at newHandler.<computed> [as apply] (eval …)` | `{}` |
| `iframeSharesRealm` | `true` | `false` |
| every other row | as in the table above | unchanged |

An independent main-world read through `cdp Runtime.evaluate` (not the page's own test) agrees:
`functionToString: "function toString() { [native code] }"`, `toStringIsProxy: false`,
`iframeSharesRealm: false`, `navigatorOwnProperties: ["getBattery","privateAttribution"]` — the
only remaining finding is the third-party patch described in conclusion 2.

The same guard explains why a **managed headless** Browser4 Chrome is clean without the bundle
(`navigatorWebdriver` 🟢 "No webdriver presented", `headless` 🟢, `nativeTampering` 🟢): the launch
passes `--disable-blink-features=AutomationControlled`, so the browser never claims to be
automated and there is nothing for the page world to hide. A CDP-*attached* browser has no such
launch flag — it reports `webdriver = true` — and that is the case the bundle still serves, at the
cost documented in conclusion 1.
