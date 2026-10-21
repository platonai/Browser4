# Issues: device-and-network-emulation

> **Source:** `20261013-143933-device-and-network-emulation.issues.json` | **Date:** 20261013-143933 | **Mode:** dev

## Scenario Background

### Task

All 11 scenario steps were executed against MockSite on `localhost:18080` (already running — a re-launch attempt reported the port occupied by PID 222248, so the pre-existing instance was used after verifying all four fixtures returned HTTP 200). Every command was invoked as `./b4w.ps1 <command>` from the repo root.

**Per acceptance criterion:**

| AC | Result | Evidence |
|---|---|---|
| AC1 — geo | ❌ **Fails without an undocumented extra step** | After `set geo --lat 48.8584 --lon 2.2945 --accuracy 10` + reload, `window.geolocationPromise` **never settles** (still pending after 3 s). Chrome requires the geolocation *permission*; `set geo` only issues `Emulation.setGeolocationOverride`. After granting permission via `cdp Browser.grantPermissions --json '{"permissions":["geolocation"],"origin":"http://localhost:18080"}'` the promise resolves to exactly `{"latitude":48.8584,"longitude":2.2945}`. Neither the CLI help nor the skill documents the permission step (repo-wide grep for `grantPermissions` = 0 hits). |
| AC2 — offline | ⚠️ **Half** | `set offline on` → `goto` **did not fail**: exit 0, "Navigated to chrome-error://chromewebdata/ (redirected from …)", "Page loaded." No non-zero exit, no message naming the network failure. `set offline off` → navigation restored ✓. |
| AC3 — headers | ✅ | `X-B4-Test: "1"` present in `requestHeaders` (field is camelCase `requestHeaders`) from both `network requests` and `network request <id>`. |
| AC4 — colour scheme | ✅ | `set media --color-scheme dark` → `matchMedia('(prefers-color-scheme: dark)').matches` = `true`, live (no reload); `light` flips it to `false`. |
| AC5 — device metrics | ✅ | `set device --width 390 --height 844 --dpr 2 --mobile` → `innerWidth=390`, `innerHeight=844`, `devicePixelRatio=2`. |
| AC6 — dpr reaches CSS | ✅ | On `highdpi.html` with dpr 2: `matchMedia('(-webkit-min-device-pixel-ratio: 1.5)').matches` = `true` and the computed `background-image` is `image2x.jpg`; with dpr 1 it is `image1x.jpg`. |
| AC7 — resize vs set device | ✅ (with caveat) | `resize 1280 900` → `✓ Resized to 1280×900`, `innerWidth/innerHeight` = 1280/900. Help wording: `resize` = "Resize the **browser window**" vs `set device` = "Viewport width in px … Device scale factor … Enable mobile (touch) emulation". Caveat: `resize` silently discarded the active device emulation (dpr 2 → 1). |
| AC8 — delete-data | ✅ | `delete-data` → "User data deleted for session", exit 0, session still usable; after reload `document.cookie` = `""` and `localStorage.length` = 0. The message does not say *what* was deleted. |
| AC9 — invalid mode | ⚠️ **Half** | `set bogus` → exit **1** ✓, but the message is `Error: [CDP_ERROR] execute_cdp_command failed: Message must have string 'method' property` — it does **not** list valid modes. |

**Headline finding:** the documented `eval` command **cannot see page-defined JavaScript globals** — it executes in Browser4's isolated world (`typeof __pulsar_utils__ === "function"` there), so `eval "typeof window.geolocationPromise"` returns `undefined` while `cdp Runtime.evaluate` returns `"object"` on the same page at the same moment. The authoritative promise readings in this report were taken from the page's main world using an injected-`<script>`/DOM-channel technique and cross-checked with `cdp Runtime.evaluate`.

Two further behaviours were discovered that the help does not mention: **a cross-URL `goto` silently clears device-metrics emulation** (it survives `reload`, `go-back`, and raw `cdp Page.navigate`), and `resize` clears it too.

---

### Execution Context

**Key Commands:**

```
./b4w.ps1 -s emu open --headless http://localhost:18080/assets/geolocation.html
./b4w.ps1 -s emu eval --file <probe.js> [--await]
./b4w.ps1 -s emu reload | goto <url> | go-back
./b4w.ps1 -s emu set geo --lat 48.8584 --lon 2.2945 --accuracy 10
./b4w.ps1 -s emu set offline on|off
./b4w.ps1 -s emu set headers --json '{"X-B4-Test":"1"}'
./b4w.ps1 -s emu set media --color-scheme dark|light
./b4w.ps1 -s emu set device --width 390 --height 844 --dpr 2 --mobile
./b4w.ps1 -s emu network requests --filter network-fixture [--clear]
./b4w.ps1 -s emu network request <requestId>
./b4w.ps1 -s emu resize 1280 900
./b4w.ps1 -s emu cookie-set b4test cookievalue ; localstorage-set b4key b4value
./b4w.ps1 -s emu delete-data ; reload
./b4w.ps1 -s emu cdp Browser.grantPermissions --json '...'   # workaround
./b4w.ps1 -s emu cdp Runtime.evaluate --file <params.json>   # workaround
./b4w.ps1 -s emu cdp Page.navigate --json '{"url":"..."}'    # diagnostic
./b4w.ps1 -s emu close ; ./b4w.ps1 list
```

**Key decisions / investigations:**
1. Geolocation reading returned `NO_PROMISE`; rather than assume a broken fixture, I proved *where* `eval` runs: (a) an injected main-world `<script>` set `document.title` (seen by `page-info`) and `window.__mw` (seen by a second main-world script, round-tripped through a DOM attribute) while `eval` saw `undefined`; (b) `eval` sees `__pulsar_utils__`. ⇒ `eval` is isolated-world.
2. To still satisfy AC1 objectively, I read the page's own promise from the main world via an injected-script/DOM channel and independently via `cdp Runtime.evaluate`.
3. Isolated the device-metrics reset by comparing `goto` vs `reload` vs `go-back` vs raw `cdp Page.navigate` (reset only on `goto`) and by testing whether `snapshot`/`page-info` reset it (they don't).
4. Grepped the source for root causes: `set` is a CLI-side CDP builder (`cli/browser4-cli/src/commands.rs:2326-2402`); `evaluate()` and the dual-world runtime live in `Browser4WebDriver.kt`; `setDeviceMetricsOverride` appears in this repo only inside `screenshotFullPage`, so the `goto`-reset is server-side (pulsar-browser dependency).

**Workarounds required:** `cdp Browser.grantPermissions` for geolocation (AC1); `cdp Runtime.evaluate` for reading any page-defined global; `goto` avoided when device emulation must persist (used `reload`/`go-back`/`cdp Page.navigate` instead).

**State checks:** all scratch artifacts are under `.test-sessions/20261013T0154334271610Z/`; `git status` shows no new files in the repo (only the pre-existing modifications from before this session).

---

---

## Issues Found (15 issues)

### Issue 1: eval cannot read page-defined JS globals — it executes in Browser4's isolated world, not the page's main world

**Severity:** Critical
**Category:** Product

#### Reproduction

./b4w.ps1 -s emu open --headless http://localhost:18080/assets/geolocation.html
./b4w.ps1 -s emu eval "typeof window.geolocationPromise"        # -> undefined
./b4w.ps1 -s emu cdp Runtime.evaluate --json '{"expression":"typeof window.geolocationPromise","returnByValue":true}'
# -> {"result":{"type":"string","value":"object"}}  (same page, same moment)
# Proof of world separation: inject a <script> that sets window.__mw + document.title; a second injected script reads window.__mw into a DOM attribute; eval sees the DOM but not the global.

#### Expected Behavior

eval runs JavaScript in the page (help: 'Evaluate JavaScript expression on page or element'; SKILL.md: 'Execute JavaScript in the page'), so page-created globals are readable — the documented way to read window.geolocationPromise.

#### Actual Behavior

eval lands in a separate JS world: window.geolocationPromise is undefined, an injected main-world script's window.__mw is undefined to eval while a second main-world script reads it fine (round-tripped via a DOM attribute), and typeof __pulsar_utils__ === 'function' inside eval. A user following the documented workflow concludes the page never defined the promise. No warning or documentation mentions this.

#### Root Cause Analysis

Dual-world evaluation: the driver's evaluation path resolves to the Browser4 isolated world (the one the runtime __pulsar_utils__ is injected into) instead of the page's main world. Browser4WebDriver.evaluate()/ensurePulsarUtilsInjected() re-establish a cached isolated-world context id, and the eval tool resolves through that; the actual world selection lives in the upstream pulsar-browser dependency (IsolatedWorldManager / DualWorldScriptLoader). DOM access and standard browser APIs are shared/re-implemented per world, which is why everything except page-defined globals appears to work.

#### Code Pointer

`browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/Browser4WebDriver.kt:evaluate (line 2465) / ensurePulsarUtilsInjected (line ~2508); eval tool branch browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/BrowserTabToolExecutor.kt (line 2254); CLI CommandDef 'eval' in cli/browser4-cli/src/commands.rs`

#### AI Suggested Improvement

- Make the eval tool evaluate in the page's main world by default (CDP Runtime.evaluate with no contextId / the main-frame default context), as Puppeteer/Playwright do; keep the isolated world for the internal runtime and any helper that needs it.
- If the isolated world is deliberate (anti-bot stealth), expose an explicit switch (e.g. --world main|isolated, default main) and document the limitation loudly in help eval and skills/browser4-cli/references/eval.md.
- Detect the failure mode: when the expression evaluates to undefined for a bare page-global reference, warn that the page's JS globals are not visible from eval and point at the cdp Runtime.evaluate escape hatch.
- Add an e2e scenario that navigates to a fixture defining window.<x> and asserts eval can read it (the gap is invisible to DOM-only tests).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: set geo emulation is invisible to the page: geolocation permission is never granted, so getCurrentPosition never settles

**Severity:** High
**Category:** Product

#### Reproduction

./b4w.ps1 -s emu open --headless http://localhost:18080/assets/geolocation.html
./b4w.ps1 -s emu set geo --lat 48.8584 --lon 2.2945 --accuracy 10
./b4w.ps1 -s emu reload
# read window.geolocationPromise from the page's main world -> stays 'pending' forever
# Workaround that fixes it:
./b4w.ps1 -s emu cdp Browser.grantPermissions --json '{"permissions":["geolocation"],"origin":"http://localhost:18080"}'
./b4w.ps1 -s emu reload   # now resolves to {"latitude":48.8584,"longitude":2.2945}

#### Expected Behavior

After set geo --lat .. --lon .. --accuracy .., a reload of the fixture resolves window.geolocationPromise to the emulated coordinates (AC1).

#### Actual Behavior

The promise never settles (checked 3 s after load, and again after reload). Chrome requires the geolocation permission to be granted for getCurrentPosition to return a position; Emulation.setGeolocationOverride alone is not sufficient. The fixture supplies no error callback, so a denied/prompt permission produces an eternally pending promise rather than an error. Neither help set nor SKILL.md mentions granting permission; repo-wide grep finds no Browser.grantPermissions call anywhere in the product.

#### Root Cause Analysis

The geo branch of the CLI's set command issues exactly one CDP call — Emulation.setGeolocationOverride {latitude, longitude, accuracy} — and nothing calls Browser.grantPermissions for the page origin. Chrome's geolocation API gates on the permission, so the override is never observable to page script.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:2352-2360 (CommandDef set, tool_params_fn, "geo" branch)`

#### AI Suggested Improvement

- Issue Browser.grantPermissions {permissions:['geolocation'], origin:<current page origin>} as part of set geo (the origin is available from the session's current URL), or add an explicit --grant-permission flag documented in help set.
- Document the requirement in help set and skills/browser4-cli/SKILL.md (the set row currently has an empty 'Full reference' column).
- Consider clearing the permission on a documented reset path so emulation is reversible.
- Note for the fixture authors: a fixture promise without an error callback cannot distinguish 'denied' from 'still pending' — adding an error handler would make this failure self-evident.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: goto under set offline on reports success (exit 0, 'Page loaded.') for a network failure

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s emu set offline on
./b4w.ps1 -s emu goto http://localhost:18080/assets/mobile.html ; echo $?

#### Expected Behavior

AC2: the goto fails with a network error — non-zero exit and a message naming the failure (e.g. ERR_INTERNET_DISCONNECTED).

#### Actual Behavior

Exit code 0. Output: 'Navigated to chrome-error://chromewebdata/ (redirected from http://localhost:18080/assets/mobile.html)' followed by 'Page loaded. If results render asynchronously, wait for the result element ...'. No network-error text anywhere in the output; the only hint is the chrome-error:// URL embedded in the 'Navigated to' line (no 'Possible blocked/challenge page' warning either). Reproduced twice; full output captured to .test-sessions/20261013T0154334271610Z/offline_goto_full.txt.

#### Root Cause Analysis

Chrome commits an error interstitial (chrome-error://chromewebdata/) for a failed navigation, so the backend navigate tool returns success; handle_goto maps only tool-level errors to CLI failures and never inspects the landed URL (or the offline emulation state) after navigation. The post-navigation 'Page loaded' hint is printed unconditionally on the success path.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_goto (~line 2832) — post-navigation validation / message selection; the backend navigate handler may also need to surface the net error.`

#### AI Suggested Improvement

- After navigation, treat a final URL starting with chrome-error:// (or a document.URL of chrome-error) as a failure: return Err with the page's own error text (Chrome exposes it under #main-message) so the message names ERR_INTERNET_DISCONNECTED / ERR_NAME_NOT_RESOLVED etc.
- Suppress the 'Page loaded' hint when the landed document is an error page.
- When offline emulation is active (set offline on was the last emulation command for the tab), append the likely cause to the error, e.g. 'navigation failed while offline emulation is enabled (set offline off to restore)'.
- Add an e2e assertion that goto under offline returns a non-zero exit code.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: Device-metrics emulation is silently discarded by goto (but survives reload, go-back and raw Page.navigate)

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s emu set device --width 390 --height 844 --dpr 2 --mobile
./b4w.ps1 -s emu eval "window.innerWidth+'x'+window.innerHeight+' dpr '+window.devicePixelRatio"   # 390x844 dpr 2
./b4w.ps1 -s emu goto http://localhost:18080/assets/mobile.html
./b4w.ps1 -s emu eval "window.innerWidth+'x'+window.innerHeight+' dpr '+window.devicePixelRatio"   # 1920x1080 dpr 1
# control: reload, go-back and 'cdp Page.navigate' all PRESERVE 390x844 dpr 2

#### Expected Behavior

Emulation applied by set device stays in effect until it is explicitly changed or the session closes (or, if navigation is meant to reset it, the CLI says so and warns when it drops an active emulation).

#### Actual Behavior

A cross-URL goto silently restores the native window metrics (1920x1080, dpr 1); no message, warning or exit-code change. The same override survives reload, go-back, snapshot, page-info and a raw cdp Page.navigate to a different URL — so it is the goto path, not navigation itself. media/geo/headers emulation all survive the same goto, making the behaviour inconsistent across modes.

#### Root Cause Analysis

The reset happens in the goto/navigate pipeline (CLI goto -> MCP navigate tool). It is not in this repository's Kotlin: the only setDeviceMetricsOverride call sites here are in Browser4WebDriver.screenshotFullPage (which temporarily resizes and restores), and grep finds no clearDeviceMetricsOverride/resetViewport in the REST or agentic layers. The likely location is the upstream pulsar-browser dependency's navigate/load pipeline normalizing the viewport to the window size. Needs backend-side confirmation (log around Emulation.setDeviceMetricsOverride / clearDeviceMetricsOverride during navigate).

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_goto (~2832) for the CLI path; server side not in this repo — suspect PulsarWebDriver.navigate in the pulsar-browser dependency (see browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/Browser4WebDriver.kt for the subclassing pattern)`

#### AI Suggested Improvement

- Stop clearing/re-setting device metrics during navigation, or re-apply the tab's active override after the new document commits.
- If a reset is intentional, warn on stderr when a goto drops an active device emulation ('device emulation cleared by navigation — re-run set device ...'), and document the persistence rules per mode in help set.
- Add e2e coverage: set device -> goto different URL -> assert innerWidth/devicePixelRatio survived (mirrors the AC5/AC6 checks).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: resize silently cancels device emulation (dpr 2 -> 1) with a success message

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 -s emu set device --width 390 --height 844 --dpr 2 --mobile
./b4w.ps1 -s emu eval "window.devicePixelRatio"      # 2
./b4w.ps1 -s emu resize 1280 900                     # ✓ Resized to 1280×900
./b4w.ps1 -s emu eval "window.devicePixelRatio"      # 1 (emulation gone)

#### Expected Behavior

Either the resize preserves the emulated device scale factor, or the output states that the device emulation was cleared.

#### Actual Behavior

The viewport becomes 1280x900 as requested, but devicePixelRatio silently returns to 1. The success message '✓ Resized to 1280×900' implies nothing was lost.

#### Root Cause Analysis

resize (browser window resize) replaces/clears the CDP device-metrics override; the command reports only the dimensions it set. Related to the goto reset (issue above) — both are paths where the viewport is re-derived from the window without re-applying the tab's emulation.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:2403-2422 (CommandDef resize); backend browser_resize handler`

#### AI Suggested Improvement

- Keep the emulated deviceScaleFactor across resize when one is active, or explicitly clear-and-report.
- Document the interaction in both help resize and help set device — today the only difference stated is 'browser window' vs 'viewport', which does not imply that one cancels the other.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: set bogus exits non-zero but the error is a leaked CDP internal message that never lists the valid modes

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 -s emu set bogus ; echo $?

#### Expected Behavior

AC9: non-zero exit AND a message listing the valid modes (geo, offline, headers, media, device).

#### Actual Behavior

Exit 1, message: 'Error: [CDP_ERROR] execute_cdp_command failed: Message must have string \'method\' property'. Nothing tells the user which modes exist; the wording describes the CLI's own sentinel, not the user's mistake.

#### Root Cause Analysis

The unknown-mode branch in the CLI emits a deliberately unusable CDP call ({method: "", params:{mode}} with the comment 'emit an unusable call so the CLI errors early'), so the failure surfaces from the backend CDP layer as a protocol-validation error instead of a CLI-side argument error. The mode list is known statically in the same function.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:2396-2399 (CommandDef set, tool_params_fn, `other =>` branch)`

#### AI Suggested Improvement

- Validate the mode in the CLI before building the CDP payload and fail with: "Unknown set mode 'bogus'. Valid modes: geo, offline, headers, media, device (see `browser4-cli help set`)."
- Include a nearest-match suggestion for typos (e.g. 'geoo' -> 'geo').
- Also reject mode-specific options that do not belong to the given mode (e.g. --dpr with set geo) instead of silently ignoring them.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: set commands print a bare {} on success — no confirmation of what was applied

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 -s emu set media --color-scheme dark   # prints: {}
./b4w.ps1 -s emu set geo --lat 48.8584 --lon 2.2945
./b4w.ps1 -s emu set headers --json '{"X-B4-Test":"1"}'

#### Expected Behavior

A short human-readable confirmation naming the mode and the applied values (like resize's '✓ Resized to 1280×900' or cookie-set's 'Cookie set: b4test'), so the user can tell which emulation is currently active.

#### Actual Behavior

Every set invocation prints exactly '{}' (the raw execute_cdp_command result). Nothing identifies the mode, the values, or the target tab/session — a user cannot tell a successful call from a no-op, and there is no read-back command to check the active emulation state.

#### Root Cause Analysis

The set CommandDef maps to the generic execute_cdp_command tool and prints its raw JSON envelope verbatim; the CDP method returns an empty object, so '{}' is the whole output. There is no CLI-side formatting layer for this command family.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:2326-2402 (CommandDef set) and the generic execute_cdp_command print path in cli/browser4-cli/src/main.rs`

#### AI Suggested Improvement

- Print a mode-specific confirmation from the CLI, e.g. '✓ Geolocation emulated: 48.8584, 2.2945 (±10 m)', '✓ Offline mode ON for session emu'.
- Suppress the raw envelope (keep it for --json).
- Consider a read-back command (e.g. `set status` / `emulation` ) that lists the active overrides for the current tab — today there is no way to query them.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: Missing arguments silently apply undocumented defaults: set geo -> (0,0), set device -> 390x844

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 -s emu set geo            # exit 0, {} ; coordinates become 0.0000,0.0000
./b4w.ps1 -s emu set device         # exit 0, {} ; viewport becomes 390x844 dpr 1

#### Expected Behavior

An error when required values are missing — the help itself says for --lat 'give --lat together with --lon', implying they belong together — or at minimum a warning plus documented defaults.

#### Actual Behavior

Both commands succeed silently. set geo pins the simulated position to Null Island (0,0); set device shrinks the viewport to 390x844 (dpr 1, mobile off). Neither help nor SKILL.md documents the 390/844 fallback for --width/--height (help only mentions dpr default 1).

#### Root Cause Analysis

The tool_params_fn uses unwrap_or defaults: lat/lon/accuracy -> 0.0/0.0/1.0 (commands.rs 2353-2355) and width/height/dpr/mobile -> 390/844/1.0/false (commands.rs 2387-2390). No required-argument validation is performed for these option combinations.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:2352-2360 (geo) and 2386-2394 (device)`

#### AI Suggested Improvement

- Treat --lat/--lon as required for set geo and --width/--height as required for set device; error with the exact missing flag.
- If defaults are kept deliberately, state them in help ("--width ... (default: 390)") and echo them in the success confirmation (see the confirmation issue above).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: set headers --json with invalid JSON silently clears all extra headers (exit 0)

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s emu set headers --json 'not-json-at-all' ; echo $?
# exit 0, prints {}
./b4w.ps1 -s emu goto http://localhost:18080/b4/network-fixture.html
./b4w.ps1 -s emu network requests --filter network-fixture   # no X-B4-* header present

#### Expected Behavior

A parse error naming the malformed JSON, with no change to the currently active header set.

#### Actual Behavior

Exit 0 and '{}'. The malformed payload is replaced by an empty object, which Network.setExtraHTTPHeaders applies as a full replacement — so the command both misreports success and destroys previously configured headers.

#### Root Cause Analysis

serde_json::from_str(...).unwrap_or_else(|_| json!({})) swallows the parse error and substitutes an empty header map.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:2369-2373 (CommandDef set, tool_params_fn, "headers" branch)`

#### AI Suggested Improvement

- Propagate the serde error (or emit a CLI-side error) instead of defaulting to {} — a destructive no-op is worse than a failure.
- Only send Network.setExtraHTTPHeaders when parsing succeeded; add a unit test for the malformed-JSON case (commands.rs already has set-related unit tests to extend).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 10: Emulation scope, persistence across navigation, and how to undo are undocumented

**Severity:** Medium
**Category:** Documentation

#### Reproduction

./b4w.ps1 help set            # zero mentions of session/tab scope, persistence, navigation, clear/reset/undo
./b4w.ps1 help                # DevTools section lists 'set <mode> [state]' with a truncated summary
# SKILL.md has one row: 'set geo|offline|headers|media|device ... Emulate locations, networks, devices, and media features' with an empty Full reference column

#### Expected Behavior

Documentation stating (a) whether emulation is per-tab/per-session, (b) what survives navigation, and (c) how to clear each mode.

#### Actual Behavior

None of this exists. Measured behaviour that users must guess: media/geo/headers survive goto and reload; device metrics survive reload/go-back but are cleared by goto (and by resize); offline is undone by 'set offline off'; media can be reset with --color-scheme no-preference; geo and device have NO CLI undo path at all (only 'cdp Emulation.setGeolocationOverride --json {}' / 'cdp Emulation.clearDeviceMetricsOverride').

#### Root Cause Analysis

The set command family was documented as a one-line summary plus option list; the persistence semantics implemented by the backend/CDP were never captured in help text, SKILL.md, or a references/ page.

#### Code Pointer

`cli/browser4-cli/src/help.rs (set help text derives from the CommandDef) and skills/browser4-cli/SKILL.md (command-map row for set)`

#### AI Suggested Improvement

- Add an 'Emulation' notes block to help set: scope (current tab of the session), persistence per mode, and the exact undo for each mode.
- Add a short section to skills/browser4-cli/SKILL.md (and optionally references/emulation.md) with a persistence matrix: geo/media/headers survive navigation; device metrics are dropped by goto and resize; declare the intended behaviour once issue 4 is fixed.
- Provide symmetrical reset syntax (e.g. set geo --clear, set device --clear) so no mode requires the cdp escape hatch.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 11: --help --examples for set claims the backend may be down instead of 'no examples yet'

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 set --help --examples
# Output: "No examples available for 'set' — is the backend running?"  (exit 0)

#### Expected Behavior

Either the examples advertised by `help set` ('Runnable examples: browser4-cli set --help --examples') are shown, or a plain 'No examples available for set'.

#### Actual Behavior

The message blames a possibly-stopped backend even though the backend was running (every other command in the same session worked), and the command exits 0. It also contradicts help set, which advertises runnable examples.

#### Root Cause Analysis

The examples lookup has no static list for set and falls back to a generic message that assumes backend-provided examples; the exit code is not propagated.

#### Code Pointer

`cli/browser4-cli/src/help.rs (examples lookup / fallback message)`

#### AI Suggested Improvement

- Ship a static example list for set (the set CommandDef's option set makes good examples trivial: 'set geo --lat 48.8584 --lon 2.2945', 'set offline on', 'set headers --json ...', 'set media --color-scheme dark', 'set device --width 390 --height 844 --dpr 2 --mobile').
- Drop the 'is the backend running?' hint for commands with no backend-provided examples.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 12: Top-level and DevTools help truncate the set summary to a dangling '`set …' — media and device modes are invisible

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 help
./b4w.ps1 help devtools

#### Expected Behavior

The one-line summary names all five modes (or at least reads as a complete sentence).

#### Actual Behavior

Both listings end with: 'Emulate browser capabilities via Chrome DevTools Protocol: `set geo`, `set offline`, `set headers`, `set …' — the ellipsis lands mid-item inside an unclosed backtick, hiding 'set media' and 'set device' from the primary discovery surface.

#### Root Cause Analysis

The description string is truncated at a fixed column for the command table, cutting inside the enumerated mode list rather than at a word/item boundary.

#### Code Pointer

`cli/browser4-cli/src/help.rs (command-table description truncation)`

#### AI Suggested Improvement

- Reword the summary to lead with the mode list so truncation cannot hide modes, e.g. 'Emulate geo, offline, headers, media, or device (CDP emulation)'.
- Or make the truncation item-aware for backtick-separated lists.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 13: delete-data reports only 'User data deleted for session' — no session name, no list of what was deleted

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s emu cookie-set b4test cookievalue
./b4w.ps1 -s emu localstorage-set b4key b4value
./b4w.ps1 -s emu delete-data

#### Expected Behavior

AC8: the command reports what it deleted (which stores, for which session).

#### Actual Behavior

'User data deleted for session' (exit 0) — the session is unnamed, and it is not stated whether cookies, localStorage, sessionStorage, IndexedDB and/or cache were cleared. The deletion itself works (verified: after reload document.cookie is empty and localStorage.length is 0, session still usable).

#### Root Cause Analysis

A static success message; the underlying handler knows the session id but the message is not parameterized with it or with the cleared store list.

#### Code Pointer

`backend handler for the delete-data tool (MCPToolController / AgentToolManager) and the CLI print path in cli/browser4-cli/src/main.rs`

#### AI Suggested Improvement

- Include the session name/id and the cleared stores in the result, e.g. 'Deleted cookies (3), localStorage (1 entry) for session emu'.
- Mention explicitly that emulation overrides are NOT cleared by delete-data (they are tab state, not session data) — users may reasonably expect otherwise.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 14: set device --mobile does not change the user agent (desktop UA under mobile emulation)

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 -s emu set device --width 390 --height 844 --dpr 2 --mobile
./b4w.ps1 -s emu eval "navigator.userAgent"

#### Expected Behavior

A 'mobile device' emulation either presents a mobile UA or documents that the UA is left untouched.

#### Actual Behavior

navigator.userAgent stays 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) ... Chrome/155.0.0.0 Safari/537.36' while maxTouchPoints becomes 10. Any site that branches on the UA sees a desktop Windows browser on a 390x844 touch device — the classic inconsistent-fingerprint combination.

#### Root Cause Analysis

The device branch issues only Emulation.setDeviceMetricsOverride (width/height/deviceScaleFactor/mobile); Emulation.setUserAgentOverride is never sent, and the --mobile option only drives the mobile flag of the metrics override.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:2386-2394 (CommandDef set, tool_params_fn, "device" branch) and the option definition for --mobile at commands.rs:2345`

#### AI Suggested Improvement

- Either set a matching mobile UA when --mobile is given (add --ua for an explicit override, and a way to restore the original UA),
- or state in help that --mobile enables touch metrics only and the user agent is unchanged, so nobody assumes a full device profile.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 15: network requests dumps tens of KB of raw JSON for the whole session with no default limit or human-readable view

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s emu network requests
# -> 48.2 KB single-line JSON array (requests from every page visited in the tab), truncated by the terminal harness; --filter/--type/--status exist but the default view is unfiltered and unbounded

#### Expected Behavior

A compact default listing (id, method, status, type, URL) with the full envelope behind --json, plus a limit/paging option.

#### Actual Behavior

A raw JSON blob including full request/response headers and timing for every tracked request; on the first call it even contained the previous page's document request. There is no --limit and no summary line, so the signal (which request do I care about?) is buried.

#### Root Cause Analysis

network requests prints the backend's raw JSON array without CLI-side formatting, and the tracked buffer spans the tab's history rather than the last navigation.

#### Code Pointer

`cli/browser4-cli/src/commands.rs (network requests CommandDef) and the backend networkRequests handler`

#### AI Suggested Improvement

- Print a table by default (short request id, method, status, resource type, URL) and reserve raw JSON for --json.
- Add --limit <n> (newest first) and indicate the total count and buffer window in the header.
- Consider scoping the default view to the current document unless --all is passed.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## Overall Assessment

**Completion Status:** Partially Successful — all 11 scenario steps were executed and 6.5 of 9 acceptance criteria pass. AC3, AC4, AC5, AC6, AC7 and AC8 pass; AC2 and AC9 pass only the 'restore' half / exit-code half; AC1 fails out of the box because set geo never grants the geolocation permission (verified fixable via an undocumented cdp workaround). A Critical evaluator-side blocker (eval running in an isolated JS world) forced main-world read-back techniques for every page-global measurement.

**Success Rate:** 70% — emulation mechanics (headers, media, device metrics, dpr-to-CSS, delete-data, resize) are solid; the failures are in error reporting, permission handling, and emulation lifetime.

**Issues Found:** 15

**Major Blockers:** 1) eval cannot see page-defined JS globals (isolated world) — the documented way to read window.geolocationPromise returns undefined, making AC1 unverifiable as written; worked around with cdp Runtime.evaluate and injected-main-world-script/DOM channel probes. 2) set geo does not grant the geolocation permission, so the emulated position is invisible to the page until an undocumented CDP call is made. 3) goto under offline emulation exits 0 with 'Page loaded.', so scripts and agents cannot detect the failure.

**Most Confusing Aspects:** The eval world mismatch is the most damaging surprise: eval reports a page global as undefined with no warning, and nothing in help, SKILL.md or references/eval.md mentions it — a first-time user would blame the page. Second is emulation lifetime: nothing documents that a cross-URL goto silently drops device metrics (while media/geo/headers survive the same navigation, and reload/go-back preserve device metrics), so emulation appears to work and then randomly reverts. Third is discoverability of the set family itself: the top-level summary truncates to '`set …', hiding media/device, and 'help set --help --examples' answers 'is the backend running?'. Fourth is the total lack of feedback — every set call prints '{}', there is no way to query the active emulation state, and undo only exists for offline and media.

**Most Valuable Improvements:** 1) Make eval evaluate in the page's main world by default (or expose --world and document the limitation). 2) Grant the geolocation permission inside set geo and document it. 3) Detect chrome-error:// after navigation and fail loudly with the real network error. 4) Keep device-metrics emulation alive across goto/resize, or warn when it is dropped. 5) Print mode-specific confirmations for set ('✓ Geolocation emulated: 48.8584, 2.2945 ±10 m') and validate arguments/mode locally with actionable errors listing the valid modes. 6) Document the emulation persistence matrix and per-mode reset paths (and add reset flags for geo/device).

**Usability Rating:** 5/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` (PowerShell) or `./b4w.sh` (Bash / Git Bash), which auto-build from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root:

   - **PowerShell:** `./b4w.ps1 <command>`
   - **Bash / Git Bash:** `./b4w.sh <command>`
   - **Direct:** `browser4-cli <command>` (if installed globally)

   > **Note:** `$(./b4w.ps1)` is command substitution in bash — do NOT use it.

### Per-Issue Reproduction Steps

#### Issue 1: eval cannot read page-defined JS globals — it executes in Browser4's isolated world, not the page's main world

./b4w.ps1 -s emu open --headless http://localhost:18080/assets/geolocation.html
./b4w.ps1 -s emu eval "typeof window.geolocationPromise"        # -> undefined
./b4w.ps1 -s emu cdp Runtime.evaluate --json '{"expression":"typeof window.geolocationPromise","returnByValue":true}'
# -> {"result":{"type":"string","value":"object"}}  (same page, same moment)
# Proof of world separation: inject a <script> that sets window.__mw + document.title; a second injected script reads window.__mw into a DOM attribute; eval sees the DOM but not the global.

#### Issue 2: set geo emulation is invisible to the page: geolocation permission is never granted, so getCurrentPosition never settles

./b4w.ps1 -s emu open --headless http://localhost:18080/assets/geolocation.html
./b4w.ps1 -s emu set geo --lat 48.8584 --lon 2.2945 --accuracy 10
./b4w.ps1 -s emu reload
# read window.geolocationPromise from the page's main world -> stays 'pending' forever
# Workaround that fixes it:
./b4w.ps1 -s emu cdp Browser.grantPermissions --json '{"permissions":["geolocation"],"origin":"http://localhost:18080"}'
./b4w.ps1 -s emu reload   # now resolves to {"latitude":48.8584,"longitude":2.2945}

#### Issue 3: goto under set offline on reports success (exit 0, 'Page loaded.') for a network failure

./b4w.ps1 -s emu set offline on
./b4w.ps1 -s emu goto http://localhost:18080/assets/mobile.html ; echo $?

#### Issue 4: Device-metrics emulation is silently discarded by goto (but survives reload, go-back and raw Page.navigate)

./b4w.ps1 -s emu set device --width 390 --height 844 --dpr 2 --mobile
./b4w.ps1 -s emu eval "window.innerWidth+'x'+window.innerHeight+' dpr '+window.devicePixelRatio"   # 390x844 dpr 2
./b4w.ps1 -s emu goto http://localhost:18080/assets/mobile.html
./b4w.ps1 -s emu eval "window.innerWidth+'x'+window.innerHeight+' dpr '+window.devicePixelRatio"   # 1920x1080 dpr 1
# control: reload, go-back and 'cdp Page.navigate' all PRESERVE 390x844 dpr 2

#### Issue 5: resize silently cancels device emulation (dpr 2 -> 1) with a success message

./b4w.ps1 -s emu set device --width 390 --height 844 --dpr 2 --mobile
./b4w.ps1 -s emu eval "window.devicePixelRatio"      # 2
./b4w.ps1 -s emu resize 1280 900                     # ✓ Resized to 1280×900
./b4w.ps1 -s emu eval "window.devicePixelRatio"      # 1 (emulation gone)

#### Issue 6: set bogus exits non-zero but the error is a leaked CDP internal message that never lists the valid modes

./b4w.ps1 -s emu set bogus ; echo $?

#### Issue 7: set commands print a bare {} on success — no confirmation of what was applied

./b4w.ps1 -s emu set media --color-scheme dark   # prints: {}
./b4w.ps1 -s emu set geo --lat 48.8584 --lon 2.2945
./b4w.ps1 -s emu set headers --json '{"X-B4-Test":"1"}'

#### Issue 8: Missing arguments silently apply undocumented defaults: set geo -> (0,0), set device -> 390x844

./b4w.ps1 -s emu set geo            # exit 0, {} ; coordinates become 0.0000,0.0000
./b4w.ps1 -s emu set device         # exit 0, {} ; viewport becomes 390x844 dpr 1

#### Issue 9: set headers --json with invalid JSON silently clears all extra headers (exit 0)

./b4w.ps1 -s emu set headers --json 'not-json-at-all' ; echo $?
# exit 0, prints {}
./b4w.ps1 -s emu goto http://localhost:18080/b4/network-fixture.html
./b4w.ps1 -s emu network requests --filter network-fixture   # no X-B4-* header present

#### Issue 10: Emulation scope, persistence across navigation, and how to undo are undocumented

./b4w.ps1 help set            # zero mentions of session/tab scope, persistence, navigation, clear/reset/undo
./b4w.ps1 help                # DevTools section lists 'set <mode> [state]' with a truncated summary
# SKILL.md has one row: 'set geo|offline|headers|media|device ... Emulate locations, networks, devices, and media features' with an empty Full reference column

#### Issue 11: --help --examples for set claims the backend may be down instead of 'no examples yet'

./b4w.ps1 set --help --examples
# Output: "No examples available for 'set' — is the backend running?"  (exit 0)

#### Issue 12: Top-level and DevTools help truncate the set summary to a dangling '`set …' — media and device modes are invisible

./b4w.ps1 help
./b4w.ps1 help devtools

#### Issue 13: delete-data reports only 'User data deleted for session' — no session name, no list of what was deleted

./b4w.ps1 -s emu cookie-set b4test cookievalue
./b4w.ps1 -s emu localstorage-set b4key b4value
./b4w.ps1 -s emu delete-data

#### Issue 14: set device --mobile does not change the user agent (desktop UA under mobile emulation)

./b4w.ps1 -s emu set device --width 390 --height 844 --dpr 2 --mobile
./b4w.ps1 -s emu eval "navigator.userAgent"

#### Issue 15: network requests dumps tens of KB of raw JSON for the whole session with no default limit or human-readable view

./b4w.ps1 -s emu network requests
# -> 48.2 KB single-line JSON array (requests from every page visited in the tab), truncated by the terminal harness; --filter/--type/--status exist but the default view is unfiltered and unbounded

