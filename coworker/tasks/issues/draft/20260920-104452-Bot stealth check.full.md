Ignoring 13 permissions.allow entries from .claude/settings.json: this workspace has not been trusted. Run Claude Code interactively here once and accept the trust dialog, or set projects["D:/workspace/Browser4/Browser4-4.13"].hasTrustDialogAccepted: true in C:\Users\pereg\.claude.json.
"deepseek-v4-flash" isn't described by this version's model catalog; update Claude Code, or map it with behavesAs on a modelPicker row (or modelOverrides, if it is a provider id of a model this version knows). Until then auto-compact keeps this session within 200k tokens (the context window it assumes); if the model accepts more, append [1m] to the model name for 1M, or set CLAUDE_CODE_MAX_CONTEXT_TOKENS to its real window; CLAUDE_CODE_DISABLE_UNKNOWN_MODEL_WINDOW_ENFORCEMENT=1 restores the previous wait-for-the-API behavior.
[claude-code:unrecognized_model] {"model":"deepseek-v4.1-flash-expires-on-0910","query_source":"generate_session_title"}
[claude-code:unrecognized_model] {"model":"deepseek-v4-flash","query_source":"sdk"}
# A. Task Result

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

# B. Execution Trace

**Setup.** Confirmed `pwd` = repo root; ran `./b4w.ps1 help` (~120 lines, high quality) and read `skills/browser4-cli/SKILL.md` in full. All commands used the required `./b4w.ps1 -s stealth <command>` form.

**Phase 1 — discovery.** `goto https://www.google.com` succeeded; `fill e33 "…" --submit` navigated to `/sorry/index` (reCAPTCHA, "unusual traffic"). Followed the documented anti-bot escalation: `close` → `open --headed` with the same `-s stealth` → retried → blocked again, so stopped per the skill's own instruction. **Workaround:** substituted Bing (`goto "https://www.bing.com/search?q=…"`) and extracted 10 organic results — well over the 5 required. URLs came back as `bing.com/ck/a?…u=a1<base64>` redirect wrappers, so I decoded them with a small Python script to get the real destinations.

**Phase 2 — five services.** Flow per service: `goto` → `wait --load networkidle` → settle 10–25 s → `screenshot --full-page` → `htmlsnapshot get text "body"` → targeted extraction. I wrote a reusable `probe_site.sh` in the scratch directory after the second service.

Two workarounds were forced by tool defects:
- **`eval` executes in a child frame** on any page with iframes, returning plausible-but-wrong values with no error. Discovered on sannysoft (`document.title` → `""`, `tr` count → `0`, while `snapshot -v all` showed 58 rows). I diagnosed it with `eval "window.top === window"` → `false` and switched all extraction to `htmlsnapshot get all`.
- **`htmlsnapshot query` (X-SQL, live-page seeding) timed out after 120 s** on the Bing results page (`statusCode: 408`, `pageContentBytes: 0`); `--format table` printed a bare `null`. Fell back to `htmlsnapshot get all text` / `get all attr` and zipped the arrays.

To prove the detection root cause I wrote `nav_probe2.js` and ran it with `eval --file … --await`, comparing top frame vs same-origin iframe vs worker — this produced the decisive table above. I also reproduced it a second time on a revisit to confirm it was not a one-off.

**Phase 3 — report.** Wrote `target/bot-stealth-report.md` with the summary table, per-service check-by-check detail, a ranked signal analysis, and remediation guidance.

**Friction encountered:** `click e46` hung past the 300 s tool timeout and the backgrounded command eventually surfaced a raw backend `{"error":"Service Unavailable","path":"/mcp/call-tool"}` 503. `goto` repeatedly reported `Navigated to about:blank (redirected from …)` while the real page loaded, and `page-info` then reported page identity that disagreed with `eval location.href`. `reload` printed nothing. All scratch files live under `.test-sessions/20260920T0355347004286Z/`.

```json
{
  "issues": [
    {
      "title": "Navigator spoofing is inconsistent between page and worker contexts — Browser4 is detected as a bot",
      "severity": "Critical",
      "category": "Product",
      "reproduction": "./b4w.ps1 -s stealth goto \"https://deviceandbrowserinfo.com/are_you_a_bot\"\n./b4w.ps1 -s stealth wait --load networkidle\n(sleep 20, then read the page verdict)\n-- or the direct probe --\n./b4w.ps1 -s stealth goto \"https://www.browserscan.net/bot-detection\"\n./b4w.ps1 -s stealth eval --file nav_probe2.js --await   # compares top frame vs iframe vs worker",
      "expected": "Every JavaScript context belonging to one browser process reports identical navigator values, so no context can be used to cross-check another.",
      "actual": "Page contexts (main frame AND same-origin about:blank iframes) report hardwareConcurrency=4, languages=[\"en-US\",\"en\"], maxTouchPoints=10; Web Worker and Service Worker global scopes report hardwareConcurrency=20, languages=[\"zh-CN\",\"zh\"] for the same browser. deviceandbrowserinfo.com returns {\"isBot\": true} with hasInconsistentWorkerValues=true as the ONLY true signal (all 20 other signals false) and displays \"You are a bot!\". bot.incolumitas.com returns inconsistentWebWorkerNavigatorPropery: FAIL and inconsistentServiceWorkerNavigatorPropery: FAIL. Reproduced on two separate vendor pages and on a repeat visit.",
      "rootCause": "A synthetic device-profile/stealth patch is applied to document contexts (main frame plus its iframes) but is not propagated to dedicated/shared/service worker global scopes. The patched values (4 cores, en-US, 10 touch points) look like a fabricated profile layered over a real 20-core zh-CN host; the worker scope exposes the unpatched host values. Because Chrome constructs a Navigator inside each worker global scope independently, a main-world JavaScript patch cannot reach it — the override must be applied at a layer Chrome propagates (a launch flag or a CDP Emulation-domain call) or be injected into worker scopes too. Exact injection site needs locating: the ai.platon.pulsar.common.browser.fingerprint package (HardwareParameters/ScreenParameters) is not present in this checkout's main sources, only its tests.",
      "codePointer": "browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/browser/privacy/PrivacyContext.kt (profile emulation entry point; injector itself is in the external ai.platon.pulsar.common.browser.fingerprint package)",
      "suggestion": "- Stop overriding hardwareConcurrency/languages/maxTouchPoints in document contexts, or apply the identical override inside dedicated, shared and service worker global scopes so all contexts agree.\n- Prefer an override layer Chrome itself propagates (launch flag or CDP Emulation domain) over a main-world JS patch, since a main-world patch structurally cannot reach worker scopes.\n- Add a regression test that spawns a Worker, a SharedWorker and a ServiceWorker and asserts their navigator values equal the main frame's.\n- Re-run deviceandbrowserinfo.com and bot.incolumitas.com in CI as the acceptance gate; both are deterministic and cheap."
    },
    {
      "title": "navigator.language is not a member of navigator.languages (internal inconsistency)",
      "severity": "High",
      "category": "Product",
      "reproduction": "./b4w.ps1 -s stealth goto \"https://www.browserscan.net/bot-detection\"\n./b4w.ps1 -s stealth htmlsnapshot get text \"body\"   # see the Native Navigator panel\n-- or on bot.sannysoft.com --\n./b4w.ps1 -s stealth htmlsnapshot get all text \"tr\"  # PHANTOM_LANGUAGE / languages rows",
      "expected": "navigator.language is always an element of navigator.languages (e.g. language=\"zh-CN\", languages=[\"zh-CN\",\"zh\"]), as real Chrome reports and as the worker scope on this same browser correctly does.",
      "actual": "In a single context: navigator.language = \"zh-CN\" while navigator.languages = [\"en-US\",\"en\"]. The declared UI language is absent from the advertised language list. Observed on sannysoft, browserscan and incolumitas. The worker scope reports the consistent pair [\"zh-CN\",\"zh\"].",
      "rootCause": "The locale override sets navigator.languages from one source (an en-US profile) while navigator.language passes through from the host locale (zh-CN), or the two properties are patched independently. Any detector comparing the scalar to the list flags it.",
      "codePointer": "browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/browser/privacy/PrivacyContext.kt",
      "suggestion": "- Derive navigator.language from navigator.languages[0] rather than leaving it host-derived; patch both from a single locale value.\n- If the intended profile is zh-CN, set languages to [\"zh-CN\",\"zh\",\"en-US\",\"en\"]; if it is en-US, also override navigator.language.\n- Add a unit test asserting navigator.languages.includes(navigator.language) inside the injected context."
    },
    {
      "title": "eval silently executes in a child frame and returns plausible-but-wrong results",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "./b4w.ps1 -s stealth goto \"https://bot.sannysoft.com/\"\n./b4w.ps1 -s stealth eval \"document.title\"                      # returns \"\" (expected \"Antibot\")\n./b4w.ps1 -s stealth eval \"document.querySelectorAll('tr').length\"  # returns 0 (expected 58)\n./b4w.ps1 -s stealth eval \"window.top === window\"               # returns false\n./b4w.ps1 -s stealth htmlsnapshot get all text \"tr\"             # returns the correct 58 rows at the same moment",
      "expected": "eval evaluates in the top-level document of the active tab; if it cannot, it fails loudly rather than returning a value.",
      "actual": "eval evaluates inside a child about:blank frame belonging to the page (window.top !== window, frames.length 0, documentElement.outerHTML.length 39). It returns \"\" and 0 with exit code 0 and no error or warning — a wrong answer indistinguishable from a correct empty answer. The CLI's own hint suggests 'the page context is stale (try: goto <url>)', which is misleading. Repro is page-dependent: eval works on example.com, incolumitas and deviceandbrowserinfo (no early child frames) but is broken on sannysoft (5 about:blank iframes) and browserscan (GTM iframe).",
      "rootCause": "The CDP Runtime.evaluate call is not pinned to the main frame's execution context. When a page creates child frames, a child frame's execution context is selected; because that context evaluates successfully, no error surfaces. The result is then attributed to the page. Mitigating factor for the user: htmlsnapshot/htmlsnapshot get are unaffected and read the live top-level DOM correctly, so this is isolated to the eval path.",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt (browser_evaluate dispatch); execution-context selection lives in the CDP driver layer",
      "suggestion": "- Resolve the main frame via Page.getFrameTree and pass its executionContextId to Runtime.evaluate, instead of letting CDP pick a default context.\n- Add a guard: if the chosen context's frame is not the main frame, either re-target or fail with an explicit 'evaluated in child frame' error.\n- Make the existing 'stale page context' hint check window.top === window before printing, so it stops misdirecting users.\n- Add a regression test that evals document.title on a fixture page containing an about:blank iframe."
    },
    {
      "title": "htmlsnapshot query with live-page seeding times out after 120s and --format table prints a bare null",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "./b4w.ps1 -s stealth goto \"https://www.bing.com/search?q=online+bot+detection+test+browser+automation+stealth+check\"\n./b4w.ps1 -s stealth htmlsnapshot query --sql \"@bing-results.sql\" --format table   # prints: null\n./b4w.ps1 -s stealth htmlsnapshot query --sql \"@bing-results.sql\"                  # times out",
      "expected": "The query is seeded from the live page and returns the matching rows (the page had 10 li.b_algo elements, verified by eval), or fails immediately with an actionable error.",
      "actual": "After 120s: {\"statusCode\":408,\"pageStatusCode\":408,\"pageContentBytes\":0,\"isDone\":false,\"resultSet\":null,\"message\":\"X-SQL query timed out after 120s. The page may be too large or the session may be unresponsive.\"}. With --format table the same failure prints the single word null and nothing else. htmlsnapshot get all text/attr on the same page returned correct data instantly.",
      "rootCause": "The live-page seeding path appears to attempt a fresh page fetch/capture (pageContentBytes 0) instead of reusing the already-serialized live DOM, and then blocks until the 120s budget expires. Separately, the --format table renderer does not surface the error envelope when resultSet is null, converting an explicit timeout into a silent null.",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt (htmlsnapshot query path) and cli/browser4-cli/src/commands.rs (table result formatting)",
      "suggestion": "- Reuse the live DOM snapshot already produced for the session rather than re-fetching when the query URL equals the current page URL.\n- Surface the error envelope in --format table: print the statusCode and message instead of null, and exit nonzero on a timeout.\n- Lower the live-seeding budget and fail fast, or make it configurable, so callers are not blocked for two minutes.\n- Note in the help text that htmlsnapshot get all is the immediate fallback when live-page seeding is slow."
    },
    {
      "title": "goto reports 'Navigated to about:blank' for a page that loaded, and page-info then reports stale page identity",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "./b4w.ps1 -s stealth goto \"https://bot.sannysoft.com/\"\n# => Navigated to about:blank (redirected from https://bot.sannysoft.com/)\n./b4w.ps1 -s stealth page-info\n# => Title: Antibot / URL: https://bot.sannysoft.com/\n./b4w.ps1 -s stealth eval \"location.href\"\n# => about:blank\n./b4w.ps1 -s stealth screenshot --full-page   # the sannysoft page IS actually rendered",
      "expected": "goto reports the final URL of the navigation, and page-info reflects the live document of the active tab.",
      "actual": "goto claims the navigation ended at about:blank; page-info reports sannysoft's title and URL; eval reports about:blank; a full-page screenshot proves the sannysoft page is really rendered. Three commands disagree about which page is loaded, and the session's tracked 'current page' stays about:blank (visible in the next goto's '(current page: about:blank)' preamble). Reproduced on sannysoft and browserscan.",
      "rootCause": "An intermediate about:blank commit during navigation is recorded as the final URL instead of being superseded by the real commit, while page-info/snapshot serve identity from a cached page record rather than the live document. The same intermediate-commit window is the likely trigger for the eval child-frame selection in Issue 3, since the affected pages are exactly those that report about:blank.",
      "codePointer": "cli/browser4-cli/src/commands.rs (goto result handling and session current-page update)",
      "suggestion": "- Discard an about:blank commit when a real navigation is in flight and report the final committed URL instead.\n- Have page-info read the live document (as htmlsnapshot already does) rather than a cached page record, so it can never disagree with eval.\n- Warn explicitly when the final URL is about:blank but the page has content."
    },
    {
      "title": "click returns a raw backend 503 and hangs for minutes without a bounded deadline",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "./b4w.ps1 -s stealth goto \"https://pixelscan.net/bot-check\"\n./b4w.ps1 -s stealth click e46      # tab element; no output for >300s, then an error envelope",
      "expected": "The click either succeeds quickly or fails fast with an actionable message.",
      "actual": "No output for more than 300s (the command had to be backgrounded). It eventually returned {\"error\": \"Service Unavailable\", \"path\": \"/mcp/call-tool\"} — a raw backend envelope with no command context, no retry guidance and no indication of whether the click was applied. The subsequent htmlsnapshot read showed the tab had in fact changed state.",
      "rootCause": "A transient backend 503 during the post-command snapshot round-trip; the CLI neither bounds the wait with a deadline nor retries, and passes the raw envelope through instead of translating it. Because the interaction may have succeeded, the failure mode is ambiguous.",
      "codePointer": "cli/browser4-cli/src/commands.rs (interaction dispatch and error rendering)",
      "suggestion": "- Apply a bounded deadline to interaction round-trips and fail with a clear timeout message rather than hanging.\n- Retry once on a 503 from /mcp/call-tool, since the README documents the server as transiently unready.\n- Translate the raw envelope into a human message stating which command failed and whether the browser state changed."
    },
    {
      "title": "Google search is blocked by reCAPTCHA in both headless and headed mode",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "./b4w.ps1 -s stealth goto \"https://www.google.com\"\n./b4w.ps1 -s stealth fill e33 \"online bot detection test browser automation stealth check\" --submit\n# => redirected to https://www.google.com/sorry/index\n# documented escalation:\n./b4w.ps1 -s stealth close\n./b4w.ps1 -s stealth open --headed \"https://www.google.com\"\n./b4w.ps1 -s stealth goto \"https://www.google.com/search?q=...\"\n# => /sorry/index again",
      "expected": "Per SKILL.md the headed retry resolves anti-bot blocks; search results should be returned.",
      "actual": "Both attempts land on the reCAPTCHA interstitial: '我们的系统检测到您的计算机网络中存在异常流量' (our systems detected unusual traffic from your computer network), with the block page showing IP 2a09:bac5:590a:323::50:12c — a known VPN/datacenter range. The escalation path itself behaved exactly as documented (close, reopen headed with the same -s, warning shown), so this is not a CLI bug. Separate evidence suggests an IP-level block rather than a fingerprint block: bot.incolumitas.com reported os_mismatch: true and relay.flagged: true with probability 0.9496, and saw a different IPv4 egress (220.130.58.19) than Google saw over IPv6.",
      "rootCause": "Most likely IP reputation of the VPN/datacenter egress, not the browser fingerprint — the same browser passed Google's own WebDriver checks and unscreened pages loaded fine. Attribution is not yet proven: a controlled test with a residential/clean IP is required. If the block persists on a clean IP, the cause moves to the fingerprint layer (Issue 1), since Google also uses the same consistency heuristics.",
      "codePointer": "",
      "suggestion": "- Do not change fingerprint code on the basis of this result until a clean-IP control run is done; record the egress IP in the test environment so results are attributable.\n- Add a documented search fallback (the docs could name Bing/DuckDuckGo as alternates) so agents blocked at Google have a first-class path instead of improvising.\n- Consider surfacing the detected egress IP in doctor/status output, since IP reputation silently invalidates stealth measurements."
    },
    {
      "title": "fill --submit reports neither the navigation nor the documented automatic post-command snapshot",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "./b4w.ps1 -s stealth fill e33 \"some query\" --submit\n./b4w.ps1 -s stealth page-info",
      "expected": "SKILL.md section 1 states 'Interactions capture an automatic post-command snapshot'; a --submit fill should also make it visible that the form was submitted and the page navigated.",
      "actual": "Output is a single line: '✓ Filled 'some query' into e33'. No snapshot reference, no preview, no navigation notice. The submission did occur (the page navigated to Google's /sorry/index), but the only way to discover that was a separate page-info call. The same silence occurs on plain (non-submit) fill.",
      "rootCause": "Either the automatic post-command snapshot is suppressed for fill in this build, or its output is filtered; either way the observable behaviour contradicts the documented behaviour and removes the cue users rely on to know the page changed.",
      "codePointer": "cli/browser4-cli/src/commands.rs (fill/type post-command snapshot emission)",
      "suggestion": "- Emit the documented post-command snapshot for fill/type, or correct SKILL.md if the behaviour is intentional.\n- When --submit causes a navigation, print the new URL (or at least 'navigated') in the command output.\n- Refs are invalidated by that navigation, so mentioning it in-place prevents the very common 'ref no longer valid' follow-up error."
    },
    {
      "title": "reload produces no output at all",
      "severity": "Low",
      "category": "UX",
      "reproduction": "./b4w.ps1 -s stealth reload",
      "expected": "A one-line confirmation, consistent with goto ('Navigated to ...') and wait ('✓ Wait complete').",
      "actual": "Completely empty output, exit code 0. Indistinguishable from a silent failure; the user cannot tell whether the reload happened, and since reload invalidates refs, silence is especially costly here.",
      "rootCause": "The reload command path does not print a success message, unlike its sibling navigation commands.",
      "codePointer": "cli/browser4-cli/src/commands.rs (reload handler)",
      "suggestion": "- Print a confirmation line mirroring goto, e.g. 'Reloaded https://example.com'.\n- Include the standard ref-invalidation reminder, as goto does."
    },
    {
      "title": "snapshot -i is named for 'interactive' but returns the full tree",
      "severity": "Low",
      "category": "Discoverability",
      "reproduction": "./b4w.ps1 help                     # read the snapshot -i entry\n./b4w.ps1 -s stealth snapshot -i    # on a content-heavy page",
      "expected": "By CLI convention -i means 'interactive only', so a first-time user expects only clickable/fillable elements and plans their output budget accordingly.",
      "actual": "The full accessibility tree is returned with text merged into ref names. The help text does disclose this ('an interactive-oriented layout, not an interactive-only filter'), but the disclosure is buried in a parenthetical and the flag name actively suggests the opposite. On a content-heavy page this is a real cost, since the docs also warn that snapshot trees can exceed 256KB and that --stdout can dump 63KB+.",
      "rootCause": "Flag naming chosen for a layout variant that collides with the well-established meaning of -i as a filter. The documentation is accurate; the name is the problem.",
      "codePointer": "cli/browser4-cli/src/help.rs (snapshot help text) and cli/browser4-cli/src/commands.rs (snapshot flag handling)",
      "suggestion": "- Rename to a layout-oriented flag such as --interactive-names or --merge-text, keeping -i as a deprecated alias.\n- Alternatively make -i actually filter to interactive elements, which is what users expect, and expose the current behaviour under a different name.\n- Lead the help line with the effect ('merges text into ref names; not a filter') rather than burying it after the term it contradicts."
    }
  ],
  "assessment": {
    "completionStatus": "Successful with workarounds — all 5 bot-detection services were evaluated, per-check results were recorded, and the report was written to target/bot-stealth-report.md. Two deviations were forced: Google search (the requested discovery step) was blocked by reCAPTCHA in both headless and headed mode, so Bing was substituted as the search engine; and eval was unusable on iframe-bearing pages, so all extraction was done with htmlsnapshot get all.",
    "successRate": "85% — the search step and 5/5 service evaluations completed; the only hard failure was the Google search, which was worked around. Extraction required a fallback path on 2 of 5 services.",
    "issuesFound": 10,
    "majorBlockers": "Google search blocked by reCAPTCHA in both headless and headed mode, with evidence pointing to IP reputation (VPN/datacenter egress) rather than the browser fingerprint — worked around with Bing. eval silently evaluates in a child frame on pages containing iframes, returning wrong answers with no error; this invalidated early extraction attempts until diagnosed and worked around with htmlsnapshot. htmlsnapshot query with live-page seeding timed out after 120s, forcing a switch to get all text/attr.",
    "mostConfusingAspects": "Three commands disagreed about which page was loaded at the same moment: goto said 'Navigated to about:blank', page-info said 'Antibot / https://bot.sannysoft.com/', and eval said about:blank — while a screenshot proved the real page was rendered. eval returning a confident wrong answer (0 elements, empty title) with exit code 0 and a hint blaming a 'stale page context' was the hardest failure to recognise, because a wrong answer looks exactly like a correct empty one. The split between snapshot and htmlsnapshot, and between get and htmlsnapshot get, is powerful but takes several readings of the docs to internalise. --submit producing no feedback about the navigation it caused was an unexpectedly sharp edge.",
    "mostValuableImprovements": "1. Fix the page-vs-worker navigator divergence — it is the single change that flips deviceandbrowserinfo.com, incolumitas and (likely) pixelscan from detected to clean, and it is a scoping bug rather than a fundamental detection-resistance problem. 2. Make eval target the main frame explicitly and fail loudly when it cannot, since silently wrong results are worse than errors. 3. Make navigator.language a member of navigator.languages from a single locale source. 4. Fall back to the already-serialized live DOM in htmlsnapshot query instead of re-fetching and timing out, and surface error envelopes in --format table instead of printing null. 5. Report the final URL and navigation state honestly in goto/page-info/fill --submit/reload so users can tell what actually happened.",
    "usabilityRating": 6
  }
}
```

**Overall usability rating: 6/10** — the CLI has genuinely strong bones: high-quality help, coherent command families, a well-designed snapshot/htmlsnapshot split, sensible session and escalation workflows, and documented anti-patterns that saved me real time (the `$(./b4w.ps1)` warning in particular). It is let down on reliability of the two commands an AI agent leans on hardest — `eval` and `htmlsnapshot query` — both of which failed in ways that produce *plausible wrong answers rather than errors*, and by navigation commands whose reported state can disagree with the browser's actual state.
