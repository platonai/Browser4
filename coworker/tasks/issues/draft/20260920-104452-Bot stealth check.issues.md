# Issues: Bot stealth check

> **Source:** `20260920-104452-Bot stealth check.full.md` | **Date:** 20260920-104452 | **Mode:** dev

## Scenario Background

### Task

**Bot-stealth report produced at `target/bot-stealth-report.md` (17 KB, 379 lines).**

Browser4's browser **does not pass as a human browser**. Of 5 services tested, it was positively flagged as a bot by 2, produced explicit failing sub-checks on a 3rd, and passed only the two legacy suites.

| # | Service | Verdict | Bot flagged? |
|---|---------|---------|--------------|
| 1 | bot.sannysoft.com | all 21 fpscanner + Intoli checks `ok`/`passed` | No |
| 2 | browserscan.net/bot-detection | **Normal** ("no bot detected") | No |
| 3 | bot.incolumitas.com | `inconsistentWebWorkerNavigatorPropery` **FAIL**, `inconsistentServiceWorkerNavigatorPropery` **FAIL**, fpscanner `WEBDRIVER` **FAIL** | Partly |
| 4 | deviceandbrowserinfo.com/are_you_a_bot | **`isBot: true` — "You are a bot!"** | **Yes** |
| 5 | pixelscan.net/bot-check | **"Bot Behavior Detected"** (verified by CSS opacity) | **Yes** |

**The signal that gives it away** — every classic automation marker is clean (`navigator.webdriver=false`, no Puppeteer/Playwright/Selenium/Phantom artifacts, `window.chrome` intact, WebGL and client hints consistent, headless checks clean). The detection is caused entirely by **inconsistent spoofing of the device profile**:

| Property | Page contexts (main + iframes) | Worker / ServiceWorker |
|---|---|---|
| `navigator.hardwareConcurrency` | **4** | **20** |
| `navigator.languages` | **`["en-US","en"]`** | **`["zh-CN","zh"]`** |

Reproduced independently on two vendors' pages via `eval --await`; it is exactly what deviceandbrowserinfo's sole failing flag `hasInconsistentWorkerValues` and incolumitas' two worker FAILs measure. A second independent leak: `navigator.language = "zh-CN"` is **not a member of** `navigator.languages = ["en-US","en"]` in the same context. Corroborating anomalies: `maxTouchPoints: 10` on a desktop with no touch-event support, and a window (`innerWidth 1920`) wider than the screen (`width 1680`).

Google additionally served a reCAPTCHA `/sorry/` interstitial in **both** headless and headed mode; the escalation path in SKILL.md was followed exactly and did not resolve it — the egress IP is a VPN range, so this needs a clean-IP control run before attributing it to the fingerprint.

### Execution Context

**Key Commands:**

**Phase 1 — discovery.** `goto https://www.google.com` succeeded; `fill e33 "…" --submit` navigated to `/sorry/index` (reCAPTCHA, "unusual traffic"). Followed the documented anti-bot escalation: `close` → `open --headed` with the same `-s stealth` → retried → blocked again, so stopped per the skill's own instruction. **Workaround:** substituted Bing (`goto "https://www.bing.com/search?q=…"`) and extracted 10 organic results — well over the 5 required. URLs came back as `bing.com/ck/a?…u=a1<base64>` redirect wrappers, so I decoded them with a small Python script to get the real destinations.

**Phase 2 — five services.** Flow per service: `goto` → `wait --load networkidle` → settle 10–25 s → `screenshot --full-page` → `htmlsnapshot get text "body"` → targeted extraction. I wrote a reusable `probe_site.sh` in the scratch directory after the second service.

Two workarounds were forced by tool defects:
- **`eval` executes in a child frame** on any page with iframes, returning plausible-but-wrong values with no error. Discovered on sannysoft (`document.title` → `""`, `tr` count → `0`, while `snapshot -v all` showed 58 rows). I diagnosed it with `eval "window.top === window"` → `false` and switched all extraction to `htmlsnapshot get all`.
- **`htmlsnapshot query` (X-SQL, live-page seeding) timed out after 120 s** on the Bing results page (`statusCode: 408`, `pageContentBytes: 0`); `--format table` printed a bare `null`. Fell back to `htmlsnapshot get all text` / `get all attr` and zipped the arrays.

To prove the detection root cause I wrote `nav_probe2.js` and ran it with `eval --file … --await`, comparing top frame vs same-origin iframe vs worker — this produced the decisive table above. I also reproduced it a second time on a revisit to confirm it was not a one-off.

**Phase 3 — report.** Wrote `target/bot-stealth-report.md` with the summary table, per-service check-by-check detail, a ranked signal analysis, and remediation guidance.

**Friction encountered:** `click e46` hung past the 300 s tool timeout and the backgrounded command eventually surfaced a raw backend `{"error":"Service Unavailable","path":"/mcp/call-tool"}` 503. `goto` repeatedly reported `Navigated to about:blank (redirected from …)` while the real page loaded, and `page-info` then reported page identity that disagreed with `eval location.href`. `reload` printed nothing. All scratch files live under `.test-sessions/20260920T0355347004286Z/`.



**Overall usability rating: 6/10** — the CLI has genuinely strong bones: high-quality help, coherent command families, a well-designed snapshot/htmlsnapshot split, sensible session and escalation workflows, and documented anti-patterns that saved me real time (the `$(./b4w.ps1)` warning in particular). It is let down on reliability of the two commands an AI agent leans on hardest — `eval` and `htmlsnapshot query` — both of which failed in ways that produce *plausible wrong answers rather than errors*, and by navigation commands whose reported state can disagree with the browser's actual state.

---

## Issues Found (10 issues)

### Issue 1: Navigator spoofing is inconsistent between page and worker contexts — Browser4 is detected as a bot

**Severity:** Critical
**Category:** Product

#### Reproduction

./b4w.ps1 -s stealth goto "https://deviceandbrowserinfo.com/are_you_a_bot"
./b4w.ps1 -s stealth wait --load networkidle
(sleep 20, then read the page verdict)
-- or the direct probe --
./b4w.ps1 -s stealth goto "https://www.browserscan.net/bot-detection"
./b4w.ps1 -s stealth eval --file nav_probe2.js --await   # compares top frame vs iframe vs worker

#### Expected Behavior

Every JavaScript context belonging to one browser process reports identical navigator values, so no context can be used to cross-check another.

#### Actual Behavior

Page contexts (main frame AND same-origin about:blank iframes) report hardwareConcurrency=4, languages=["en-US","en"], maxTouchPoints=10; Web Worker and Service Worker global scopes report hardwareConcurrency=20, languages=["zh-CN","zh"] for the same browser. deviceandbrowserinfo.com returns {"isBot": true} with hasInconsistentWorkerValues=true as the ONLY true signal (all 20 other signals false) and displays "You are a bot!". bot.incolumitas.com returns inconsistentWebWorkerNavigatorPropery: FAIL and inconsistentServiceWorkerNavigatorPropery: FAIL. Reproduced on two separate vendor pages and on a repeat visit.

#### Root Cause Analysis

A synthetic device-profile/stealth patch is applied to document contexts (main frame plus its iframes) but is not propagated to dedicated/shared/service worker global scopes. The patched values (4 cores, en-US, 10 touch points) look like a fabricated profile layered over a real 20-core zh-CN host; the worker scope exposes the unpatched host values. Because Chrome constructs a Navigator inside each worker global scope independently, a main-world JavaScript patch cannot reach it — the override must be applied at a layer Chrome propagates (a launch flag or a CDP Emulation-domain call) or be injected into worker scopes too. Exact injection site needs locating: the ai.platon.pulsar.common.browser.fingerprint package (HardwareParameters/ScreenParameters) is not present in this checkout's main sources, only its tests.

#### Code Pointer

`browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/browser/privacy/PrivacyContext.kt (profile emulation entry point; injector itself is in the external ai.platon.pulsar.common.browser.fingerprint package)`

#### AI Suggested Improvement

- Stop overriding hardwareConcurrency/languages/maxTouchPoints in document contexts, or apply the identical override inside dedicated, shared and service worker global scopes so all contexts agree.
- Prefer an override layer Chrome itself propagates (launch flag or CDP Emulation domain) over a main-world JS patch, since a main-world patch structurally cannot reach worker scopes.
- Add a regression test that spawns a Worker, a SharedWorker and a ServiceWorker and asserts their navigator values equal the main frame's.
- Re-run deviceandbrowserinfo.com and bot.incolumitas.com in CI as the acceptance gate; both are deterministic and cheap.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: navigator.language is not a member of navigator.languages (internal inconsistency)

**Severity:** High
**Category:** Product

#### Reproduction

./b4w.ps1 -s stealth goto "https://www.browserscan.net/bot-detection"
./b4w.ps1 -s stealth htmlsnapshot get text "body"   # see the Native Navigator panel
-- or on bot.sannysoft.com --
./b4w.ps1 -s stealth htmlsnapshot get all text "tr"  # PHANTOM_LANGUAGE / languages rows

#### Expected Behavior

navigator.language is always an element of navigator.languages (e.g. language="zh-CN", languages=["zh-CN","zh"]), as real Chrome reports and as the worker scope on this same browser correctly does.

#### Actual Behavior

In a single context: navigator.language = "zh-CN" while navigator.languages = ["en-US","en"]. The declared UI language is absent from the advertised language list. Observed on sannysoft, browserscan and incolumitas. The worker scope reports the consistent pair ["zh-CN","zh"].

#### Root Cause Analysis

The locale override sets navigator.languages from one source (an en-US profile) while navigator.language passes through from the host locale (zh-CN), or the two properties are patched independently. Any detector comparing the scalar to the list flags it.

#### Code Pointer

`browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/browser/privacy/PrivacyContext.kt`

#### AI Suggested Improvement

- Derive navigator.language from navigator.languages[0] rather than leaving it host-derived; patch both from a single locale value.
- If the intended profile is zh-CN, set languages to ["zh-CN","zh","en-US","en"]; if it is en-US, also override navigator.language.
- Add a unit test asserting navigator.languages.includes(navigator.language) inside the injected context.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: eval silently executes in a child frame and returns plausible-but-wrong results

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s stealth goto "https://bot.sannysoft.com/"
./b4w.ps1 -s stealth eval "document.title"                      # returns "" (expected "Antibot")
./b4w.ps1 -s stealth eval "document.querySelectorAll('tr').length"  # returns 0 (expected 58)
./b4w.ps1 -s stealth eval "window.top === window"               # returns false
./b4w.ps1 -s stealth htmlsnapshot get all text "tr"             # returns the correct 58 rows at the same moment

#### Expected Behavior

eval evaluates in the top-level document of the active tab; if it cannot, it fails loudly rather than returning a value.

#### Actual Behavior

eval evaluates inside a child about:blank frame belonging to the page (window.top !== window, frames.length 0, documentElement.outerHTML.length 39). It returns "" and 0 with exit code 0 and no error or warning — a wrong answer indistinguishable from a correct empty answer. The CLI's own hint suggests 'the page context is stale (try: goto <url>)', which is misleading. Repro is page-dependent: eval works on example.com, incolumitas and deviceandbrowserinfo (no early child frames) but is broken on sannysoft (5 about:blank iframes) and browserscan (GTM iframe).

#### Root Cause Analysis

The CDP Runtime.evaluate call is not pinned to the main frame's execution context. When a page creates child frames, a child frame's execution context is selected; because that context evaluates successfully, no error surfaces. The result is then attributed to the page. Mitigating factor for the user: htmlsnapshot/htmlsnapshot get are unaffected and read the live top-level DOM correctly, so this is isolated to the eval path.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt (browser_evaluate dispatch); execution-context selection lives in the CDP driver layer`

#### AI Suggested Improvement

- Resolve the main frame via Page.getFrameTree and pass its executionContextId to Runtime.evaluate, instead of letting CDP pick a default context.
- Add a guard: if the chosen context's frame is not the main frame, either re-target or fail with an explicit 'evaluated in child frame' error.
- Make the existing 'stale page context' hint check window.top === window before printing, so it stops misdirecting users.
- Add a regression test that evals document.title on a fixture page containing an about:blank iframe.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: htmlsnapshot query with live-page seeding times out after 120s and --format table prints a bare null

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s stealth goto "https://www.bing.com/search?q=online+bot+detection+test+browser+automation+stealth+check"
./b4w.ps1 -s stealth htmlsnapshot query --sql "@bing-results.sql" --format table   # prints: null
./b4w.ps1 -s stealth htmlsnapshot query --sql "@bing-results.sql"                  # times out

#### Expected Behavior

The query is seeded from the live page and returns the matching rows (the page had 10 li.b_algo elements, verified by eval), or fails immediately with an actionable error.

#### Actual Behavior

After 120s: {"statusCode":408,"pageStatusCode":408,"pageContentBytes":0,"isDone":false,"resultSet":null,"message":"X-SQL query timed out after 120s. The page may be too large or the session may be unresponsive."}. With --format table the same failure prints the single word null and nothing else. htmlsnapshot get all text/attr on the same page returned correct data instantly.

#### Root Cause Analysis

The live-page seeding path appears to attempt a fresh page fetch/capture (pageContentBytes 0) instead of reusing the already-serialized live DOM, and then blocks until the 120s budget expires. Separately, the --format table renderer does not surface the error envelope when resultSet is null, converting an explicit timeout into a silent null.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt (htmlsnapshot query path) and cli/browser4-cli/src/commands.rs (table result formatting)`

#### AI Suggested Improvement

- Reuse the live DOM snapshot already produced for the session rather than re-fetching when the query URL equals the current page URL.
- Surface the error envelope in --format table: print the statusCode and message instead of null, and exit nonzero on a timeout.
- Lower the live-seeding budget and fail fast, or make it configurable, so callers are not blocked for two minutes.
- Note in the help text that htmlsnapshot get all is the immediate fallback when live-page seeding is slow.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: goto reports 'Navigated to about:blank' for a page that loaded, and page-info then reports stale page identity

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s stealth goto "https://bot.sannysoft.com/"
# => Navigated to about:blank (redirected from https://bot.sannysoft.com/)
./b4w.ps1 -s stealth page-info
# => Title: Antibot / URL: https://bot.sannysoft.com/
./b4w.ps1 -s stealth eval "location.href"
# => about:blank
./b4w.ps1 -s stealth screenshot --full-page   # the sannysoft page IS actually rendered

#### Expected Behavior

goto reports the final URL of the navigation, and page-info reflects the live document of the active tab.

#### Actual Behavior

goto claims the navigation ended at about:blank; page-info reports sannysoft's title and URL; eval reports about:blank; a full-page screenshot proves the sannysoft page is really rendered. Three commands disagree about which page is loaded, and the session's tracked 'current page' stays about:blank (visible in the next goto's '(current page: about:blank)' preamble). Reproduced on sannysoft and browserscan.

#### Root Cause Analysis

An intermediate about:blank commit during navigation is recorded as the final URL instead of being superseded by the real commit, while page-info/snapshot serve identity from a cached page record rather than the live document. The same intermediate-commit window is the likely trigger for the eval child-frame selection in Issue 3, since the affected pages are exactly those that report about:blank.

#### Code Pointer

`cli/browser4-cli/src/commands.rs (goto result handling and session current-page update)`

#### AI Suggested Improvement

- Discard an about:blank commit when a real navigation is in flight and report the final committed URL instead.
- Have page-info read the live document (as htmlsnapshot already does) rather than a cached page record, so it can never disagree with eval.
- Warn explicitly when the final URL is about:blank but the page has content.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: click returns a raw backend 503 and hangs for minutes without a bounded deadline

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s stealth goto "https://pixelscan.net/bot-check"
./b4w.ps1 -s stealth click e46      # tab element; no output for >300s, then an error envelope

#### Expected Behavior

The click either succeeds quickly or fails fast with an actionable message.

#### Actual Behavior

No output for more than 300s (the command had to be backgrounded). It eventually returned {"error": "Service Unavailable", "path": "/mcp/call-tool"} — a raw backend envelope with no command context, no retry guidance and no indication of whether the click was applied. The subsequent htmlsnapshot read showed the tab had in fact changed state.

#### Root Cause Analysis

A transient backend 503 during the post-command snapshot round-trip; the CLI neither bounds the wait with a deadline nor retries, and passes the raw envelope through instead of translating it. Because the interaction may have succeeded, the failure mode is ambiguous.

#### Code Pointer

`cli/browser4-cli/src/commands.rs (interaction dispatch and error rendering)`

#### AI Suggested Improvement

- Apply a bounded deadline to interaction round-trips and fail with a clear timeout message rather than hanging.
- Retry once on a 503 from /mcp/call-tool, since the README documents the server as transiently unready.
- Translate the raw envelope into a human message stating which command failed and whether the browser state changed.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: Google search is blocked by reCAPTCHA in both headless and headed mode

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s stealth goto "https://www.google.com"
./b4w.ps1 -s stealth fill e33 "online bot detection test browser automation stealth check" --submit
# => redirected to https://www.google.com/sorry/index
# documented escalation:
./b4w.ps1 -s stealth close
./b4w.ps1 -s stealth open --headed "https://www.google.com"
./b4w.ps1 -s stealth goto "https://www.google.com/search?q=..."
# => /sorry/index again

#### Expected Behavior

Per SKILL.md the headed retry resolves anti-bot blocks; search results should be returned.

#### Actual Behavior

Both attempts land on the reCAPTCHA interstitial: '我们的系统检测到您的计算机网络中存在异常流量' (our systems detected unusual traffic from your computer network), with the block page showing IP 2a09:bac5:590a:323::50:12c — a known VPN/datacenter range. The escalation path itself behaved exactly as documented (close, reopen headed with the same -s, warning shown), so this is not a CLI bug. Separate evidence suggests an IP-level block rather than a fingerprint block: bot.incolumitas.com reported os_mismatch: true and relay.flagged: true with probability 0.9496, and saw a different IPv4 egress (220.130.58.19) than Google saw over IPv6.

#### Root Cause Analysis

Most likely IP reputation of the VPN/datacenter egress, not the browser fingerprint — the same browser passed Google's own WebDriver checks and unscreened pages loaded fine. Attribution is not yet proven: a controlled test with a residential/clean IP is required. If the block persists on a clean IP, the cause moves to the fingerprint layer (Issue 1), since Google also uses the same consistency heuristics.

#### AI Suggested Improvement

- Do not change fingerprint code on the basis of this result until a clean-IP control run is done; record the egress IP in the test environment so results are attributable.
- Add a documented search fallback (the docs could name Bing/DuckDuckGo as alternates) so agents blocked at Google have a first-class path instead of improvising.
- Consider surfacing the detected egress IP in doctor/status output, since IP reputation silently invalidates stealth measurements.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: fill --submit reports neither the navigation nor the documented automatic post-command snapshot

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 -s stealth fill e33 "some query" --submit
./b4w.ps1 -s stealth page-info

#### Expected Behavior

SKILL.md section 1 states 'Interactions capture an automatic post-command snapshot'; a --submit fill should also make it visible that the form was submitted and the page navigated.

#### Actual Behavior

Output is a single line: '✓ Filled 'some query' into e33'. No snapshot reference, no preview, no navigation notice. The submission did occur (the page navigated to Google's /sorry/index), but the only way to discover that was a separate page-info call. The same silence occurs on plain (non-submit) fill.

#### Root Cause Analysis

Either the automatic post-command snapshot is suppressed for fill in this build, or its output is filtered; either way the observable behaviour contradicts the documented behaviour and removes the cue users rely on to know the page changed.

#### Code Pointer

`cli/browser4-cli/src/commands.rs (fill/type post-command snapshot emission)`

#### AI Suggested Improvement

- Emit the documented post-command snapshot for fill/type, or correct SKILL.md if the behaviour is intentional.
- When --submit causes a navigation, print the new URL (or at least 'navigated') in the command output.
- Refs are invalidated by that navigation, so mentioning it in-place prevents the very common 'ref no longer valid' follow-up error.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 9: reload produces no output at all

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s stealth reload

#### Expected Behavior

A one-line confirmation, consistent with goto ('Navigated to ...') and wait ('✓ Wait complete').

#### Actual Behavior

Completely empty output, exit code 0. Indistinguishable from a silent failure; the user cannot tell whether the reload happened, and since reload invalidates refs, silence is especially costly here.

#### Root Cause Analysis

The reload command path does not print a success message, unlike its sibling navigation commands.

#### Code Pointer

`cli/browser4-cli/src/commands.rs (reload handler)`

#### AI Suggested Improvement

- Print a confirmation line mirroring goto, e.g. 'Reloaded https://example.com'.
- Include the standard ref-invalidation reminder, as goto does.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 10: snapshot -i is named for 'interactive' but returns the full tree

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 help                     # read the snapshot -i entry
./b4w.ps1 -s stealth snapshot -i    # on a content-heavy page

#### Expected Behavior

By CLI convention -i means 'interactive only', so a first-time user expects only clickable/fillable elements and plans their output budget accordingly.

#### Actual Behavior

The full accessibility tree is returned with text merged into ref names. The help text does disclose this ('an interactive-oriented layout, not an interactive-only filter'), but the disclosure is buried in a parenthetical and the flag name actively suggests the opposite. On a content-heavy page this is a real cost, since the docs also warn that snapshot trees can exceed 256KB and that --stdout can dump 63KB+.

#### Root Cause Analysis

Flag naming chosen for a layout variant that collides with the well-established meaning of -i as a filter. The documentation is accurate; the name is the problem.

#### Code Pointer

`cli/browser4-cli/src/help.rs (snapshot help text) and cli/browser4-cli/src/commands.rs (snapshot flag handling)`

#### AI Suggested Improvement

- Rename to a layout-oriented flag such as --interactive-names or --merge-text, keeping -i as a deprecated alias.
- Alternatively make -i actually filter to interactive elements, which is what users expect, and expose the current behaviour under a different name.
- Lead the help line with the effect ('merges text into ref names; not a filter') rather than burying it after the term it contradicts.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful with workarounds — all 5 bot-detection services were evaluated, per-check results were recorded, and the report was written to target/bot-stealth-report.md. Two deviations were forced: Google search (the requested discovery step) was blocked by reCAPTCHA in both headless and headed mode, so Bing was substituted as the search engine; and eval was unusable on iframe-bearing pages, so all extraction was done with htmlsnapshot get all.

**Success Rate:** 85% — the search step and 5/5 service evaluations completed; the only hard failure was the Google search, which was worked around. Extraction required a fallback path on 2 of 5 services.

**Issues Found:** 10

**Major Blockers:** Google search blocked by reCAPTCHA in both headless and headed mode, with evidence pointing to IP reputation (VPN/datacenter egress) rather than the browser fingerprint — worked around with Bing. eval silently evaluates in a child frame on pages containing iframes, returning wrong answers with no error; this invalidated early extraction attempts until diagnosed and worked around with htmlsnapshot. htmlsnapshot query with live-page seeding timed out after 120s, forcing a switch to get all text/attr.

**Most Confusing Aspects:** Three commands disagreed about which page was loaded at the same moment: goto said 'Navigated to about:blank', page-info said 'Antibot / https://bot.sannysoft.com/', and eval said about:blank — while a screenshot proved the real page was rendered. eval returning a confident wrong answer (0 elements, empty title) with exit code 0 and a hint blaming a 'stale page context' was the hardest failure to recognise, because a wrong answer looks exactly like a correct empty one. The split between snapshot and htmlsnapshot, and between get and htmlsnapshot get, is powerful but takes several readings of the docs to internalise. --submit producing no feedback about the navigation it caused was an unexpectedly sharp edge.

**Most Valuable Improvements:** 1. Fix the page-vs-worker navigator divergence — it is the single change that flips deviceandbrowserinfo.com, incolumitas and (likely) pixelscan from detected to clean, and it is a scoping bug rather than a fundamental detection-resistance problem. 2. Make eval target the main frame explicitly and fail loudly when it cannot, since silently wrong results are worse than errors. 3. Make navigator.language a member of navigator.languages from a single locale source. 4. Fall back to the already-serialized live DOM in htmlsnapshot query instead of re-fetching and timing out, and surface error envelopes in --format table instead of printing null. 5. Report the final URL and navigation state honestly in goto/page-info/fill --submit/reload so users can tell what actually happened.

**Usability Rating:** 6/10

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

#### Issue 1: Navigator spoofing is inconsistent between page and worker contexts — Browser4 is detected as a bot

./b4w.ps1 -s stealth goto "https://deviceandbrowserinfo.com/are_you_a_bot"
./b4w.ps1 -s stealth wait --load networkidle
(sleep 20, then read the page verdict)
-- or the direct probe --
./b4w.ps1 -s stealth goto "https://www.browserscan.net/bot-detection"
./b4w.ps1 -s stealth eval --file nav_probe2.js --await   # compares top frame vs iframe vs worker

#### Issue 2: navigator.language is not a member of navigator.languages (internal inconsistency)

./b4w.ps1 -s stealth goto "https://www.browserscan.net/bot-detection"
./b4w.ps1 -s stealth htmlsnapshot get text "body"   # see the Native Navigator panel
-- or on bot.sannysoft.com --
./b4w.ps1 -s stealth htmlsnapshot get all text "tr"  # PHANTOM_LANGUAGE / languages rows

#### Issue 3: eval silently executes in a child frame and returns plausible-but-wrong results

./b4w.ps1 -s stealth goto "https://bot.sannysoft.com/"
./b4w.ps1 -s stealth eval "document.title"                      # returns "" (expected "Antibot")
./b4w.ps1 -s stealth eval "document.querySelectorAll('tr').length"  # returns 0 (expected 58)
./b4w.ps1 -s stealth eval "window.top === window"               # returns false
./b4w.ps1 -s stealth htmlsnapshot get all text "tr"             # returns the correct 58 rows at the same moment

#### Issue 4: htmlsnapshot query with live-page seeding times out after 120s and --format table prints a bare null

./b4w.ps1 -s stealth goto "https://www.bing.com/search?q=online+bot+detection+test+browser+automation+stealth+check"
./b4w.ps1 -s stealth htmlsnapshot query --sql "@bing-results.sql" --format table   # prints: null
./b4w.ps1 -s stealth htmlsnapshot query --sql "@bing-results.sql"                  # times out

#### Issue 5: goto reports 'Navigated to about:blank' for a page that loaded, and page-info then reports stale page identity

./b4w.ps1 -s stealth goto "https://bot.sannysoft.com/"
# => Navigated to about:blank (redirected from https://bot.sannysoft.com/)
./b4w.ps1 -s stealth page-info
# => Title: Antibot / URL: https://bot.sannysoft.com/
./b4w.ps1 -s stealth eval "location.href"
# => about:blank
./b4w.ps1 -s stealth screenshot --full-page   # the sannysoft page IS actually rendered

#### Issue 6: click returns a raw backend 503 and hangs for minutes without a bounded deadline

./b4w.ps1 -s stealth goto "https://pixelscan.net/bot-check"
./b4w.ps1 -s stealth click e46      # tab element; no output for >300s, then an error envelope

#### Issue 7: Google search is blocked by reCAPTCHA in both headless and headed mode

./b4w.ps1 -s stealth goto "https://www.google.com"
./b4w.ps1 -s stealth fill e33 "online bot detection test browser automation stealth check" --submit
# => redirected to https://www.google.com/sorry/index
# documented escalation:
./b4w.ps1 -s stealth close
./b4w.ps1 -s stealth open --headed "https://www.google.com"
./b4w.ps1 -s stealth goto "https://www.google.com/search?q=..."
# => /sorry/index again

#### Issue 8: fill --submit reports neither the navigation nor the documented automatic post-command snapshot

./b4w.ps1 -s stealth fill e33 "some query" --submit
./b4w.ps1 -s stealth page-info

#### Issue 9: reload produces no output at all

./b4w.ps1 -s stealth reload

#### Issue 10: snapshot -i is named for 'interactive' but returns the full tree

./b4w.ps1 help                     # read the snapshot -i entry
./b4w.ps1 -s stealth snapshot -i    # on a content-heavy page

