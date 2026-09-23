Ignoring 13 permissions.allow entries from .claude/settings.json: this workspace has not been trusted. Run Claude Code interactively here once and accept the trust dialog, or set projects["D:/workspace/Browser4/Browser4-4.13"].hasTrustDialogAccepted: true in C:\Users\pereg\.claude.json.
"deepseek-v4-flash" isn't described by this version's model catalog; update Claude Code, or map it with behavesAs on a modelPicker row (or modelOverrides, if it is a provider id of a model this version knows). Until then auto-compact keeps this session within 200k tokens (the context window it assumes); if the model accepts more, append [1m] to the model name for 1M, or set CLAUDE_CODE_MAX_CONTEXT_TOKENS to its real window; CLAUDE_CODE_DISABLE_UNKNOWN_MODEL_WINDOW_ENFORCEMENT=1 restores the previous wait-for-the-API behavior.
[claude-code:unrecognized_model] {"model":"deepseek-v4.1-flash-expires-on-0910","query_source":"generate_session_title"}
[claude-code:unrecognized_model] {"model":"deepseek-v4-flash","query_source":"sdk"}
## A. Task Result

**Task completed.** The report is at `target/bot-stealth-report.md` (280 lines, 13.4 KB).

**Bottom line: Browser4 failed all 6 bot-detection services tested — 0/6 accepted it as human.**

| # | Service | Verdict |
|---|---|---|
| 1 | bot.sannysoft.com | ❌ FAIL — 28 pass / **3 fail** |
| 2 | browserscan.net/bot-detection | ❌ FAIL — badge **"Robot"** |
| 3 | bot.incolumitas.com | ❌ FAIL — `intoli.userAgent = FAIL` |
| 4 | deviceandbrowserinfo.com/are_you_a_bot | ❌ FAIL — **"You are a bot!"** |
| 5 | pixelscan.net/bot-check | ❌ FAIL — **"Bot Behavior Detected"** |
| 6 | creepjs (bonus) | ❌ FAIL — headless 80%, `puppeteer-extra` detected |

Two root causes dominate:

1. **`navigator.userAgent` contains `HeadlessChrome/153.0.0.0`** and is never masked. This alone explains the Sannysoft, Incolumitas and Device&BrowserInfo verdicts, and is the most plausible driver of BrowserScan's aggregate "Robot" badge *despite* all 17 of its named sub-checks reporting "Normal".

2. **The evasion layer spoofs only the page's main thread.** Verified with a local probe page whose own inline script wrote `navigator` readings into DOM attributes (readable from any JS world). The page sees `hardwareConcurrency: 4`, `languages: ["en-US","en"]`, `outerHeight: 1165`; the worker/eval world sees the raw `20`, `["zh-CN","zh"]`, `1080`. CreepJS confirmed it from the worker side (`cores: 20`), and Device&BrowserInfo flags it directly as `hasInconsistentWorkerValues: true`. The spoof is also internally contradictory — `navigator.language` stays `zh-CN` while `navigator.languages` is rewritten to US English.

## B. Execution Trace

**Setup.** `./b4w.ps1 help`, read `skills/browser4-cli/SKILL.md` in full, `status` (backend down), then `open --headless https://www.google.com` — daemon auto-started the backend in 9 s. First warning: the runtime bundle is built from **4.13.18-SNAPSHOT** while the checkout is **4.13.20-SNAPSHOT**, so results reflect the older build.

**Phase 1 — search (the hard part).** Google redirected to google.com.hk, then served its `/sorry/` CAPTCHA on both `fill … --submit` and a direct `/search` URL. I then tried, in order: DuckDuckGo HTML (CAPTCHA tile grid), Mojeek (`403 Forbidden`), Bing (poisoned results — results for the single word "online" plus unrelated adult pages), Brave (`Captcha - Brave Search`), Ecosia ("请稍候…" interstitial that never resolved), Startpage (empty), searx.be ("Verifying your browser…"), Yandex ("Are you not a robot?"). I re-tested **Bing and Google in headed mode** (`open --headed`, separate `-s gsearch` session) — both still blocked, so this is IP reputation plus the `HeadlessChrome` UA, not headless alone.

Only **Marginalia** returned real results, which surfaced `pixelscan.net`. I then harvested Incolumitas' "More Sources and Links" section, which legitimately yielded `deviceandbrowserinfo.com` and `bot.sannysoft.com`. The remaining services came from the task's sanctioned fallback list.

**Phase 2 — six services.** Per service: `goto` → `wait --load networkidle` → 10–25 s settle → `eval --file` extraction. Verdicts were read from the DOM rather than the accessibility tree, because these dashboards render scores as styled elements: BrowserScan's `#status_fail` SVG, Pixelscan's `.state-success`/`.state-error` banners disambiguated by `opacity` (both render "visible", only one has opacity 1), Incolumitas' `#detection-tests` JSON blob.

**Workarounds required:** (a) a local Python HTTP server plus a purpose-built probe page to separate main-world from eval-world readings — `file://` pages didn't execute scripts at all; (b) `github.com/.../table_colors.js`-style DOM probes because `snapshot` AX trees don't carry pass/fail semantics; (c) `eval --file` throughout to avoid Windows shell quoting.

**Notable:** Sannysoft's result was **non-deterministic** — its first load failed WebDriver and Chrome checks that passed on every subsequent load.

---

```json
{
  "issues": [
    {
      "title": "HeadlessChrome is never removed from navigator.userAgent",
      "severity": "Critical",
      "category": "Product",
      "reproduction": "./b4w.ps1 goto \"https://bot.sannysoft.com/\"  (or any of the 5 services)\nthen: ./b4w.ps1 eval \"navigator.userAgent\"",
      "expected": "A stealth-capable browser driver should present a User-Agent indistinguishable from a normal desktop Chrome, i.e. 'Chrome/153.0.0.0' with no 'Headless' token.",
      "actual": "navigator.userAgent returns 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/153.0.0.0 Safari/537.36'. Sannysoft fails it twice (User Agent (Old), HEADCHR_UA); Incolumitas reports intoli.userAgent=FAIL; Device&BrowserInfo sets hasBotUserAgent=true; CreepJS reports headless:80%. Google, Bing, DuckDuckGo, Brave, Yandex, Ecosia and Mojeek all refused or poisoned search results for this session.",
      "rootCause": "Chrome is launched over CDP without rewriting the UA string. CDP-driven Chrome appends 'Headless' to the Chrome token in headless mode. The evasion layer evidently patches several navigator properties (hardwareConcurrency, languages, outerHeight) but never overrides navigator.userAgent, and no --user-agent override is exposed anywhere in the CLI, so the caller cannot fix it either.",
      "codePointer": "Browser launch path in browser4-core/browser4-browser (PulsarWebDriver) where the Chrome/CDP options are assembled; the UA should be set via Emulation.setUserAgentOverride or the equivalent launch-time browser option.",
      "suggestion": "- Apply a launch-time UA override (or CDP Emulation.setUserAgentOverride) that strips the 'Headless' token in headless mode.\n- Keep navigator.userAgentData.brands consistent with the spoofed UA string (today UA says HeadlessChrome while UA-CH says 'Google Chrome 153' — a directly detectable contradiction).\n- Expose an explicit --user-agent flag on open/goto so callers can supply a full fingerprint profile.\n- Add a regression test asserting the UA never matches /Headless/i in headless mode."
    },
    {
      "title": "Stealth spoofing covers the main thread only; workers and other JS worlds see raw values",
      "severity": "Critical",
      "category": "Product",
      "reproduction": "1. Serve a page whose own inline <script> writes navigator readings into DOM attributes at head-parse time (e.g. document.documentElement.setAttribute('data-hwc', navigator.hardwareConcurrency)).\n2. ./b4w.ps1 goto <that page>\n3. ./b4w.ps1 eval \"document.documentElement.getAttribute('data-hwc')\"  vs  ./b4w.ps1 eval \"navigator.hardwareConcurrency\"",
      "expected": "Every JS execution context in the page (main thread, workers, iframes) should observe the same spoofed fingerprint, so no cross-context inconsistency is observable.",
      "actual": "The page's main world reads hardwareConcurrency=4, languages=[\"en-US\",\"en\"], window.outerHeight=1165. The eval/worker world reads hardwareConcurrency=20, languages=[\"zh-CN\",\"zh\"], window.outerHeight=1080. CreepJS independently reports cores:20 from the worker while the main thread reports 4. Device&BrowserInfo detects this exactly, returning isBot:true with hasInconsistentWorkerValues:true as one of only two fired signals.",
      "rootCause": "The fingerprint overrides are injected into the main world only (e.g. Page.addScriptToEvaluateOnNewDocument without a worldName/matching worker coverage). Web Workers have their own Navigator instance that never sees the injection, so any value rewritten on the main thread diverges from the worker's genuine value. Detectors specifically compare main-thread vs worker readings because legitimate spoofing is symmetric.",
      "codePointer": "The stealth/fingerprint injection site in Browser4's browser driver; extend coverage to worker contexts and verify with a cross-context consistency test.",
      "suggestion": "- Spoof consistently across all worlds, or do not spoof at all — a partially spoofed fingerprint is easier to detect than an unspoofed one because it creates impossible value pairs.\n- Cover dedicated workers, shared workers and service workers (CreepJS inspects ServiceWorkerGlobalScope).\n- Add an automated cross-context consistency assertion to the test suite comparing main-thread, worker and iframe readings."
    },
    {
      "title": "Main-world fingerprint is internally inconsistent (language vs languages vs timezone)",
      "severity": "High",
      "category": "Product",
      "reproduction": "./b4w.ps1 goto \"https://browserscan.net/bot-detection\" and read the 'Native Navigator' panel; or run the DOM-marker probe page and read data-lang / data-langs / data-tz.",
      "expected": "A coherent locale fingerprint: navigator.language should equal navigator.languages[0], and the timezone should match the locale.",
      "actual": "navigator.language = 'zh-CN' while navigator.languages = [\"en-US\",\"en\"]. Timezone remains Asia/Shanghai (-480) while the locale claims US English. CreepJS shows the same split ('美国英语' alongside '中国标准时间' / zh-CN). No genuine browser can produce this combination.",
      "rootCause": "The locale spoof overrides navigator.languages (and probably Accept-Language) without updating navigator.language, Navigator.language's Intl-related siblings, or the ICU/timezone-derived locale, leaving the original system locale visible in the unsynchronised properties.",
      "codePointer": "Same fingerprint-injection module as the previous issue — the navigator.language / languages override pair.",
      "suggestion": "- Treat locale as one atomic unit: set navigator.language, navigator.languages, Intl.DateTimeFormat().resolvedOptions().locale and the timezone together, or leave all of them untouched.\n- Add a consistency assertion (language === languages[0]) to the stealth test suite."
    },
    {
      "title": "eval runs in an isolated JS world: cannot read page globals and reports unspoofed navigator values",
      "severity": "High",
      "category": "Documentation",
      "reproduction": "./b4w.ps1 goto \"https://abrahamjuliot.github.io/creepjs/\"  (or any SPA)\n./b4w.ps1 eval \"typeof window.__NEXT_DATA__\"   # or any framework/page global\nCompare ./b4w.ps1 eval \"navigator.hardwareConcurrency\" against what the page's own script sees.",
      "expected": "Per the skill docs, 'eval — Execute JavaScript in the page — Live DOM access, complex transforms'. A user expects to read page-defined JavaScript state (window.<app> globals, framework state objects) and to see the same values the page sees.",
      "actual": "A page global set by the page's own <head> script reads back as undefined from eval, verified over http:// and https:// after a fresh navigation while DOM markers prove the script executed. eval also returns different navigator values than the page (hardwareConcurrency 20 vs 4, languages zh-CN vs en-US, outerHeight 1080 vs 1165). A user debugging 'why does this site flag me' with eval gets misleading readings and may wrongly conclude the stealth layer is inactive.",
      "rootCause": "eval is executed in an isolated world (a separate JS context sharing the DOM but not the page's window object). This is a common CDP design for automation, but it is undocumented here, so it silently breaks the most obvious debugging workflow for anti-bot work.",
      "codePointer": "cli/browser4-cli/src/ (eval command) and the backend tool that backs it — wherever Runtime.evaluate is issued without an explicit contextId, or with an isolated-world context.",
      "suggestion": "- Document the isolated-world semantics prominently in the eval reference and in SKILL.md §5 Critical Warnings, alongside the existing arrow-function rule.\n- Provide an opt-in flag (e.g. --world main) to evaluate in the page's main world for the cases where page globals matter.\n- Note in the docs that eval readings may differ from what the page observes, so eval must not be used to verify fingerprint spoofing."
    },
    {
      "title": "Stealth layer is applied non-deterministically across navigations",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "1. ./b4w.ps1 goto \"https://bot.sannysoft.com/\"  (first load in a session)\n2. Read the results table.\n3. Navigate away and back, then read it again.",
      "expected": "The same page under the same session should report the same verdict on every load.",
      "actual": "The first load reported WebDriver (New) => 'present (failed)', WebDriver Advanced => 'failed', Chrome (New) => 'missing (failed)'. After navigating away and back, the same page consistently reported all three as passed (reproduced over 2 further loads).",
      "rootCause": "Not conclusively determined. The injection appears not to be guaranteed to be registered before the first navigation of a session/tab, so a page that samples navigator.webdriver / window.chrome at document-start on that first load sees the unpatched state. Needs tracing of the injection registration order relative to tab creation.",
      "codePointer": "Browser session/tab creation path in browser4-core/browser4-browser — verify Page.addScriptToEvaluateOnNewDocument is registered before the first navigation of any new tab or session.",
      "suggestion": "- Guarantee injection registration happens before the first page load in every new tab/session.\n- Add a self-check that samples navigator.webdriver and window.chrome at document_start on a known page and fails loudly if the patch is absent.\n- Surface the stealth-layer status in `status` or `doctor` so users can tell whether it is active."
    },
    {
      "title": "All major search engines block or poison the automated browser",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "./b4w.ps1 goto \"https://www.google.com\"  then search, or ./b4w.ps1 goto \"https://www.bing.com/search?q=...\"",
      "expected": "A browser automation tool should be able to load an ordinary search results page, as it is the entry point for most research and scraping workflows.",
      "actual": "Google -> /sorry/ CAPTCHA ('我们的系统检测到您的计算机网络中存在异常流量'). DuckDuckGo HTML -> CAPTCHA tile grid. Brave -> 'Captcha - Brave Search'. Yandex -> 'Are you not a robot?'. Mojeek -> 403 Forbidden. Ecosia -> unresolving '请稍候…' interstitial. Bing -> deliberately poisoned results (pages about the single word 'online' plus unrelated adult sites) while claiming 'About 1,230,000 results'. Reproduced in BOTH headless and headed mode.",
      "rootCause": "Two compounding factors. (a) The 'HeadlessChrome' UA token is an unconditional bot signal that most engines act on immediately. (b) Google's interstitial explicitly cited 'unusual traffic from your computer network', indicating the egress IP (203.198.34.181) carries bad reputation — so even a correct fingerprint may not be sufficient here. Headed mode fixed the UA but not the IP, and Bing remained poisoned.",
      "codePointer": "Same UA override site as Issue 1.",
      "suggestion": "- Fix the UA (Issue 1) first; it is a prerequisite for any search workflow.\n- Document the constraint prominently: a 'Web scraping / search' troubleshooting note in SKILL.md stating that managed-IP egress may be blocked regardless of fingerprint, and that a residential/rotating proxy is typically required.\n- Consider making `crawl`/`swarm` surface a clear diagnostic when a fetch returns a CAPTCHA/interstitial rather than silently returning the challenge page as content."
    },
    {
      "title": "wait --load networkidle reports 'Wait complete' while the page is still loading",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "./b4w.ps1 goto \"https://bot.incolumitas.com/\"\n./b4w.ps1 wait --load networkidle     # prints '✓ Wait complete'\n./b4w.ps1 eval \"document.readyState\"  # returns 'loading'",
      "expected": "networkidle should only report completion once the page has finished loading (readyState 'complete'), or should fail/return a distinct status if the condition was not met.",
      "actual": "wait reported '✓ Wait complete' while document.readyState was still 'loading' and document.body.innerHTML.length was 0. Content only appeared ~20 s later. A caller that trusts the success signal scrapes an empty page.",
      "rootCause": "The networkidle heuristic appears to settle on a quiet network window without confirming document readiness, so a page whose main bundle has not yet started fetching looks 'idle'.",
      "codePointer": "The wait implementation behind the `wait --load` command (cli/browser4-cli/src/ and its backend tool).",
      "suggestion": "- Gate networkidle completion on document.readyState === 'complete' in addition to the network-quiet window.\n- Print the observed readyState when reporting completion, so a premature success is visible to the caller.\n- Add a --wait-ready state option, or document that callers should poll readyState for SPA pages."
    },
    {
      "title": "htmlsnapshot query intermittently fails with 417 'The page was never fetched'",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "./b4w.ps1 htmlsnapshot query \"https://www.google.com/search?q=bot+detection+test\" --sql \"@.test-sessions/.../q.sql\" --format table\nThe identical query against https://example.com returns 1 row.",
      "expected": "The query either returns rows or reports a fetch error attributable to the target URL.",
      "actual": "417 Expectation Failed: 'The scrape session closed before the query could execute. This is a known backend race condition.' Raw envelope: pageStatusCode 408, pageContentBytes 0, 'The page was never fetched. The task may have been dropped or evicted.'",
      "rootCause": "Acknowledged in the CLI's own error text as a known backend race between scrape-session teardown and query execution. Here it coincided with the target returning 408, so the page-status failure and the session race are conflated into one opaque 417.",
      "codePointer": "Backend scrape-session lifecycle / X-SQL query dispatch in browser4-rest.",
      "suggestion": "- Distinguish the two failure modes in the message: 'the target page did not load (status 408)' vs 'the scrape session closed early'.\n- Auto-retry once on the session race before surfacing 417, since the CLI already knows it is transient.\n- The existing error text and workaround list are genuinely good — keep them."
    },
    {
      "title": "Runtime bundle served is stale relative to the checked-out source",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "./b4w.ps1 open --headless \"https://www.google.com\"",
      "expected": "Per the task and dev-mode contract, the daemon starts the backend built from the current source tree (4.13.20-SNAPSHOT).",
      "actual": "Warning on every command: 'the existing local Browser4 runtime bundle was built from 4.13.18-SNAPSHOT sources, but the checked-out sources are 4.13.20-SNAPSHOT' and 'Serving the OLD backend build — behaviour may not match the checked-out code.' The daemon then serves the stale bundle.",
      "rootCause": "The runtime bundle under browser4-apps/browser4-bundle/target/runtime-bundle is reused when present, and is only rebuilt on explicit request (BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1 or the build script). Nothing invalidates it when the source version moves.",
      "codePointer": "The bundle-detection/version-compare logic in the CLI startup path (cli/browser4-cli/src/) and browser4-apps/browser4-bundle/build-runtime-bundle.ps1.",
      "suggestion": "- Consider auto-rebuilding (or prompting) when the bundle's source version differs from the checkout in dev mode, rather than only warning.\n- The warning itself is exemplary — version mismatch, consequences, and two concrete remediation commands. No change needed there."
    },
    {
      "title": "No way to override the User-Agent from the CLI",
      "severity": "Medium",
      "category": "Discoverability",
      "reproduction": "./b4w.ps1 help open\n./b4w.ps1 --help-json | grep -i 'user.agent'   # no matches",
      "expected": "For a browser-automation tool used for scraping and anti-bot work, a --user-agent option (on open, or a config key) is a baseline capability.",
      "actual": "`open` exposes only --headed/--headless/--fresh/--profile/--profile-mode/--interact-level. Neither --help-json nor `config` lists any user-agent key. Combined with Issue 1, the caller has no workaround.",
      "rootCause": "Not implemented; the UA is whatever Chrome derives from its launch flags and mode.",
      "codePointer": "cli/browser4-cli/src/ (open/goto option parsing) and the corresponding backend browser-launch options; `config` key table in SKILL.md §Configuration.",
      "suggestion": "- Add --user-agent <string> to open (and persist it via `config set user-agent`).\n- Document the UA-is-spoofed relationship with navigator.userAgentData so callers change both consistently.\n- Advertise it in the Quick Start block, since it is the single highest-leverage knob for this class of task."
    },
    {
      "title": "window.chrome is present but incomplete (chrome.runtime undefined)",
      "severity": "Low",
      "category": "Product",
      "reproduction": "./b4w.ps1 eval \"JSON.stringify(Object.keys(window.chrome))\"  -> [\"loadTimes\",\"csi\"]\n./b4w.ps1 eval \"typeof window.chrome.runtime\"            -> \"undefined\"",
      "expected": "A real desktop Chrome exposes chrome.runtime (and typically chrome.app) on ordinary pages.",
      "actual": "window.chrome exists but exposes only loadTimes and csi; chrome.runtime is undefined. Sannysoft's 'Chrome (New)' test flagged this as 'missing (failed)' on one load, but reported 'present (passed)' on later loads and none of the other services flagged it in the final runs.",
      "rootCause": "Likely a genuine Chromium headless/default-build property rather than something Browser4 sets. It was not reproduced as a failure across services, so it is a latent tell rather than a confirmed one.",
      "codePointer": "",
      "suggestion": "- Confirm against a real headed Chrome baseline whether chrome.runtime is expected on ordinary pages; if so, patch it as part of the chrome-object normalisation.\n- Worth tracking, but lower priority than the UA and worker-consistency issues, since no tested service flagged it consistently."
    },
    {
      "title": "Detector dashboards are hard to read via the documented extraction commands",
      "severity": "Low",
      "category": "UX",
      "reproduction": "./b4w.ps1 goto \"https://browserscan.net/bot-detection\"\n./b4w.ps1 snapshot -v 0 --stdout       # no pass/fail semantics\n./b4w.ps1 htmlsnapshot get text \".price\"  # selectors must be discovered first",
      "expected": "Reading 'the verdict and which checks passed' from a results dashboard should be achievable with the documented extraction workflow.",
      "actual": "Every verdict in this report had to be recovered with hand-written JavaScript via eval --file: reading an SVG use xlink:href=\"#status_fail\", disambiguating two stacked banners by computed opacity, and parsing a JSON blob out of a div. snapshot's accessibility tree carries the text but not the pass/fail signal (which lives in classes/SVG), and htmlsnapshot get requires knowing selectors up front.",
      "rootCause": "The extraction commands target text and attributes, while these dashboards encode their verdict in CSS classes and inline SVG references, which need computed-style or class inspection to interpret.",
      "codePointer": "",
      "suggestion": "- Document this pattern in the htmlsnapshot/eval references: how to read class-based state and computed styles with eval --file (a short 'reading dashboards' recipe would have saved most of the effort here).\n- Consider a convenience for reading computed styles of a selector, extending the existing `get styles` mode, so class/SVG-encoded verdicts do not require bespoke JS."
    }
  ],
  "assessment": {
    "completionStatus": "Successful — the report was produced at target/bot-stealth-report.md with verdicts for 6 services and a root-cause analysis, plus a full usability evaluation. The task's Phase 1 could not be completed as literally specified: Google blocked the session outright, and all 8 search engines attempted were blocked or served poisoned results, so services were identified from the one working engine (Marginalia), from Incolumitas' own 'More Sources' list, and from the task's sanctioned fallback candidates.",
    "successRate": "75% — all navigation, waiting, interaction and report-writing steps succeeded; the search-results step of Phase 1 failed for environmental/reliability reasons, and one definitive per-service verdict (Incolumitas' behavioral score) never resolved.",
    "issuesFound": 12,
    "majorBlockers": "Two. (1) Search-engine access: Google, DuckDuckGo, Brave, Yandex and Ecosia served CAPTCHAs, Mojeek returned 403, Bing returned poisoned results, and Startpage/searx returned nothing — in both headless AND headed mode. This forced the fallback service-identification path. (2) The eval isolated-world semantics: eval silently returns different values than the page sees and cannot read page globals, which initially made the stealth layer look more broken than it is and had to be unravelled with a purpose-built local probe page before any verdict could be trusted.",
    "mostConfusingAspects": "For a first-time user: (a) eval looks like ordinary in-page JavaScript but is not — page globals read as undefined with no warning, and navigator values differ from the page's; (b) read-only commands still need a long list of hand-written JS because snapshot AX trees do not carry pass/fail semantics that styled dashboards encode in CSS/SVG; (c) wait --load networkidle reporting success on a still-loading page is silently misleading; (d) the sheer breadth of the command surface (snapshot vs htmlsnapshot vs get vs eval vs query) has a good decision table in SKILL.md but little in-CLI guidance, so the documented path is not obvious from --help alone; (e) both outcome banners rendering as 'visible' in computed styles made the Pixelscan verdict ambiguous until ancestor opacity was inspected.",
    "mostValuableImprovements": "1. Strip 'Headless' from the User-Agent and keep userAgentData in sync — this single fix would likely flip several verdicts. 2. Stop spoofing the main thread unless workers/iframes can be spoofed identically; the current half-spoofing makes the fingerprint more detectable than leaving it alone. 3. Document (or provide an opt-in for) eval's isolated-world behaviour, since it silently breaks the primary debugging workflow for exactly this use case. 4. Make wait --load networkidle confirm document readiness. 5. Expose a --user-agent flag. 6. Add a 'reading dashboards' recipe showing how to read class/computed-style-encoded state with eval --file.",
    "usabilityRating": 6
  }
}
```
