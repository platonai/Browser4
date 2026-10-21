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
   members it names are **not** patched in any realm these probes can read, and
   `Function.prototype.toString` is untouched. The label is creepjs' nearest matching
   *known-extension template*, not evidence of puppeteer-extra.
3. Stock Chrome never reaches that analysis: it is already 100% "headless", and reports
   `extension: unknown`. So part of "Browser4 is flagged where plain Chrome is not" is an
   artifact of Browser4 *passing* the earlier checks and being subjected to the deeper ones.
4. The puppeteer-extra line predates the 2026-09-23 fix round (the `20260916-174704-Bot
   stealth check` report already saw it), so it is not a regression.
