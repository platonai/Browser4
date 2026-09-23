"deepseek-v4-flash" isn't described by this version's model catalog; update Claude Code, or map it with behavesAs on a modelPicker row (or modelOverrides, if it is a provider id of a model this version knows). Until then auto-compact keeps this session within 200k tokens (the context window it assumes); if the model accepts more, append [1m] to the model name for 1M, or set CLAUDE_CODE_MAX_CONTEXT_TOKENS to its real window; CLAUDE_CODE_DISABLE_UNKNOWN_MODEL_WINDOW_ENFORCEMENT=1 restores the previous wait-for-the-API behavior.
[claude-code:unrecognized_model] {"model":"deepseek-v4.1-flash-expires-on-0910","query_source":"generate_session_title"}
[claude-code:unrecognized_model] {"model":"deepseek-v4-flash","query_source":"sdk"}
# Task Result

**Report delivered:** `./target/bot-stealth-report.md` (14 KB, 189 lines)

**Browser4 does not pass as a human browser in its default headless mode** — it is flagged by 4 of 5 services.

| # | Service | Verdict |
|---|---------|---------|
| 1 | bot.sannysoft.com | Mostly pass — **2/27 FAIL** (`HEADCHR_UA`, `CHR_MEMORY`) |
| 2 | browserscan.net/bot-detection | **ROBOT** (all named-framework checks "Normal") |
| 3 | deviceandbrowserinfo.com/are_you_a_bot | ❌ **"You are a bot!"** |
| 4 | bot.incolumitas.com | New tests all OK; **4 legacy FAIL**; TCP/IP OS mismatch + relay 0.97 |
| 5 | abrahamjuliot.github.io/creepjs | **67% headless**, 60% stealth |

**Decisive signal:** the headless UA literally contains `HeadlessChrome/153.0.0.0`. At Device & Browser Info it was the *only* signal that fired — `isHeadlessChrome: false` and all 20 other checks were clean. `--headed` produces a clean `Chrome/153.0.0.0`, so the headless path is the outlier.

Other real defects found: `navigator.deviceMemory === 32` (Chrome caps at 8), and touch incoherence — `maxTouchPoints: 10` while `'ontouchstart' in window === false`, `pointer: fine`, `hover: hover`.

# Execution Trace

**Phase 1** — Google `/search` returned `/sorry/index` (IP-level block: *"unusual traffic from your computer network"*). Per SKILL §2 I did the documented headed escalation once; it was blocked too, so I stopped as instructed and fell back to DuckDuckGo's no-JS endpoint (`html.duckduckgo.com`), running 3 queries. Bing returned 10 completely irrelevant results (Japanese Google Help pages) — unusable. All 5 target services were confirmed **in search results**, plus 10 further candidates.

**Phase 2** — For each: `open --headless` → `wait --load networkidle` → `screenshot --full-page` → `eval` for body text + targeted DOM probes. Session `stealth`, viewport 1920×1080.

**Workarounds required:** Google block → alternate engine; DDG JS endpoint empty → HTML endpoint; BrowserScan verdict rendered as skeleton bars for ~14 s → re-capture; deviceandbrowserinfo needed ~90 s (its `cstlxp.js` took 56.7 s) before any result existed.

# Issues Found

```json
{
  "issues": [
    {
      "title": "Headless mode advertises HeadlessChrome in the User-Agent",
      "severity": "Critical",
      "category": "Product",
      "reproduction": "./b4w.ps1 -s T open --headless \"https://bot.sannysoft.com/\"\n./b4w.ps1 -s T eval \"navigator.userAgent\"\n# then ./b4w.ps1 -s T close && ./b4w.ps1 -s T open --headed \"about:blank\" && repeat the eval",
      "expected": "Headless mode should present the same User-Agent as a normal Chrome installation (e.g. ...Chrome/153.0.0.0 Safari/537.36), as --headed already does.",
      "actual": "Headless: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/153.0.0.0 Safari/537.36'. Headed: identical but with 'Chrome/153.0.0.0' and no 'HeadlessChrome' token. Detector impact: SannySoft HEADCHR_UA FAIL; Incolumitas intoli.userAgent FAIL and fpscanner.HEADCHR_UA FAIL; BrowserScan overall verdict 'Robot'; deviceandbrowserinfo hasBotUserAgent:true -> 'You are a bot!'; CreepJS 67% headless.",
      "rootCause": "The Chrome instance launched for headless mode is started with the default new-headless UA, which appends the 'HeadlessChrome/<version>' product token. Headed mode is not affected, so the UA is being derived from the process launch flags rather than being normalised. Fix likely means launching with an overridden UA (--user-agent=) or applying a Network.setUserAgentOverride / Emulation.setUserAgentOverride CDP call at session creation for the headless path. Note this is a deliberate design decision on some CDP stacks, so confirm intent before changing.",
      "codePointer": "browser4-core/browser4-browser/ (PulsarWebDriver session/launch options; search for headless launch flags and userAgent override)",
      "suggestion": "- Apply a CDP Emulation.setUserAgentOverride (or --user-agent launch flag) in headless mode so navigator.userAgent, navigator.appVersion and navigator.userAgentData are all consistent and free of the 'HeadlessChrome' token\n- Also normalise the Sec-CH-UA request headers so client hints match the overridden UA\n- Add a regression test asserting navigator.userAgent does not contain 'Headless' for both --headless and --headed sessions\n- Document the expected UA string per display mode in skills/browser4-cli/references/browser-modes.md"
    },
    {
      "title": "screenshot --full-page ignores the requested extension and always writes JPEG",
      "severity": "Medium",
      "category": "Product",
      "reproduction": "./b4w.ps1 -s T screenshot -o out-viewport.png\n./b4w.ps1 -s T screenshot --full-page -o out-fullpage.png\n./b4w.ps1 -s T screenshot --full-page -o out-fullpage.jpg\n# inspect the first bytes of each file",
      "expected": "A file named *.png should contain PNG data; the CLI should either honour the extension or provide a format flag.",
      "actual": "out-viewport.png -> PNG (35,062 bytes). out-fullpage.png -> JPEG (magic ff d8 ff e0 ... JFIF), 334,361 bytes, but the CLI still prints '[Screenshot](...out-fullpage.png)'. out-fullpage.jpg -> JPEG. The extension is silently ignored for full-page captures, so consumers that trust the extension get a file they cannot decode.",
      "rootCause": "The full-page capture path uses a different encoder from the viewport path. `screenshot --help` exposes only -o/--full-page/-v and no format option, so the format is hard-coded per code path: the viewport branch emits PNG while the full-page branch (likely a captureBeyondViewport call or a resize-and-capture fallback) emits JPEG. The filename is passed through verbatim, so the mismatch is never reconciled.",
      "codePointer": "cli/browser4-cli/src/ (screenshot command handler); backend screenshot tool binder in browser4-rest / browser4-agentic AgentToolManager",
      "suggestion": "- Make the full-page path encode PNG to match the viewport path\n- If JPEG is required for size reasons on tall pages, add an explicit --format png|jpeg flag (default png) and rewrite the output extension to match the bytes actually written\n- At minimum, print the real written path with the correct extension in the [Screenshot](...) link instead of echoing the requested name"
    },
    {
      "title": "fill with a selector that matches nothing reports success and exits 0",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "./b4w.ps1 -s T fill \"#definitely-not-here-xyz\" \"hello\" --no-snapshot\necho \"exit=$?\"\n./b4w.ps1 -s T eval \"document.querySelectorAll('#definitely-not-here-xyz').length\"",
      "expected": "A non-matching selector should produce an error and a non-zero exit code (the `get` command already establishes this contract: 'null means the selector matched nothing', and an unresolvable ref 'errors explicitly').",
      "actual": "'✓ Filled 'hello' into #definitely-not-here-xyz' printed, exit code 0, while querySelectorAll returns 0 matches. The command silently did nothing. Encountered in the wild: './b4w.ps1 -s stealth fill \"combobox\" \"online bot detection...\" --submit' reported success on the Google homepage but no navigation occurred; re-running with the real ref (e9/e49) plus `press Enter` worked immediately.",
      "rootCause": "The fill path resolves the target (ref or CSS selector), but does not assert that resolution produced an element before reporting success. Any automation script that trusts the exit code will silently skip steps — this is the most dangerous class of failure for agent-driven workflows because it produces no error signal at all.",
      "codePointer": "cli/browser4-cli/src/commands.rs (fill command) and/or the backend fill tool binding in browser4-agentic AgentToolManager / PulsarWebDriver.fill()",
      "suggestion": "- Fail fast: if the selector matches zero elements, return an error with the selector echoed back and exit non-zero\n- Mirror the documented `get` value contract across all interaction commands (click, fill, type, hover, select, check) so 'matched nothing' is never reported as success\n- If a bare non-CSS token such as 'combobox' is accepted as a role/name shorthand, resolve it explicitly and error when it is ambiguous or unmatched, rather than reporting the raw string back as if it were a resolved target\n- Add a unit test asserting a non-zero exit and no '✓ Filled' line for an unmatched selector"
    },
    {
      "title": "wait --load networkidle returns complete while page content is still loading",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "./b4w.ps1 -s T goto \"https://www.browserscan.net/bot-detection\"\n./b4w.ps1 -s T wait --load networkidle      # prints '✓ Wait complete'\n./b4w.ps1 -s T screenshot --full-page -o a.png   # result card is skeleton bars\n# 8-14s later the real verdict appears",
      "expected": "After `wait --load networkidle` completes, the page should be settled enough to read, or the command should report that the page is still loading.",
      "actual": "networkidle fires while the detection results are still placeholders. On deviceandbrowserinfo.com it was far worse: `wait --load networkidle` completed and goto printed 'Page loaded', but the verdict did not exist for ~90 seconds (cstlxp.js took 56.7s, fingerprint-scan.com/v1/fp.js 15s and timed out on first load). Reading the page at any point before that yields an empty 'Raw detection details' box with no error and exit code 0 — indistinguishable from 'nothing to report'.",
      "rootCause": "`networkidle` is a network-idle heuristic that does not account for (a) scripts still downloading, (b) client-side JS that computes results after load, or (c) long-running XHRs. There is no 'wait for content' primitive beyond `wait --selector`, and the user gets no hint that a page which looks loaded may still be empty. `goto` additionally emits 'Page loaded. Use `wait --load networkidle` if content appears incomplete', which actively suggests networkidle as the remedy.",
      "codePointer": "cli/browser4-cli/src/ (wait command, --load handling); the 'Page loaded' hint text emitted by goto",
      "suggestion": "- Have `wait <selector>` be the recommended pattern in the goto hint for result-style pages, and mention `--wait-selector` on eval\n- Consider a `wait --load results` / `wait --text <pattern>` affordance for late-rendered content\n- When a read (eval / htmlsnapshot get) returns empty text on a page that is still fetching resources, print a stderr hint rather than returning success silently\n- Document in snapshot.md / quickstart.md that networkidle is not sufficient for JS-computed results and that polling for the result element is the reliable approach"
    },
    {
      "title": "No blocked-page detection even though the documented escalation list covers it",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "./b4w.ps1 -s T goto \"https://www.google.com\"\n./b4w.ps1 -s T fill <search-ref> \"online bot detection test browser automation stealth check\" --submit\n# lands on https://www.google.com/sorry/index?continue=... with a 671-char body",
      "expected": "SKILL §2 (Display Mode / anti-bot escalation) documents an explicit list of block signatures — 'Google /sorry/, CAPTCHA/verification widget, Cloudflare/DataDome/Akamai/PerimeterX challenge, \"unusual traffic\" / \"access denied\", or an implausibly empty body'. A user reasonably expects that list to be enforced somewhere, or at least surfaced automatically.",
      "actual": "goto printed a normal success result and 'Page loaded.' The only way to discover the block was to inspect the URL or body text manually. Nothing in the CLI output flags that the page is a challenge page; the user must already know the list in order to apply it. (The documented headed retry itself worked correctly and correctly concluded the block was IP-level.)",
      "rootCause": "The block-signature list exists only as prose for the agent to apply, not as a check in the CLI. Given the SKILL tells the agent to escalate automatically, encoding the same signatures as a post-navigation warning would close the loop between documentation and behaviour.",
      "codePointer": "cli/browser4-cli/src/ (post-navigation result printing; the same place that emits the 'Page loaded. Use `wait --load networkidle`' hint)",
      "suggestion": "- After goto/open, check URL patterns (/sorry/, /cdn-cgi/challenge, /captcha) and body markers (unusual traffic, access denied, verify you are human, enable JavaScript and cookies) and print a stderr warning naming the detected challenge\n- Suggest the documented next step in that warning (close + open --headed with the same -s, retry once)\n- Keep it advisory only — do not fail the command, since a legitimate page could match a substring\n- Surface block state in `page-info` so it can be asserted programmatically"
    },
    {
      "title": "Dev mode serves a stale backend runtime bundle without rebuilding",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "./b4w.ps1 help      # first launch of a dev checkout whose sources are newer than the existing bundle",
      "expected": "Dev mode should either build the runtime bundle from the checked-out sources, or refuse to start, since the entire premise of running from the source tree is that behaviour matches the code under test.",
      "actual": "On startup: '⚠  the checked-out sources are newer than the existing local Browser4 runtime bundle. ⚠  Serving the OLD backend build — behaviour may not match the checked-out code.' The server then starts normally and the session proceeds. Any testing done against that backend validates stale code — a silent correctness hazard for the exact use case (dev/CI from source) that the mode exists to serve. Environment variable to force a rebuild: BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1.",
      "rootCause": "Detecting that sources are newer than the bundle and then continuing anyway leaves the mismatch as a warning the user can ignore. There is no default that preserves the invariant 'dev mode runs the checked-out code'.",
      "codePointer": "b4w.ps1 / cli/browser4-cli/src/ (server autostart + runtime-bundle staleness check); browser4-apps/browser4-bundle/build-runtime-bundle.ps1",
      "suggestion": "- Default to rebuilding when sources are newer, or fail fast with the rebuild command rather than continuing on stale code\n- Require an explicit opt-out flag (e.g. --allow-stale-bundle) to run against an outdated bundle\n- Echo the bundle's build timestamp and git revision in the warning, and include the same info in `status` / `doctor` so a test run's provenance is auditable"
    },
    {
      "title": "Global-option misuse produces an unhelpful 'Unknown command' error",
      "severity": "Low",
      "category": "Discoverability",
      "reproduction": "./b4w.ps1 -s hd --headed open \"about:blank\"",
      "expected": "A hint that --headed is an option of `open`, not a global flag — e.g. 'Unknown option --headed; did you mean: open --headed <url>?' or at least 'Unknown option', distinguishing it from a mistyped command.",
      "actual": "'Error: Unknown command: '--headed'' followed by the dev-mode banner and the generic 'Run `browser4-cli help <command>`' footer. The wording frames a misplaced option as a nonexistent command, which sends the user looking for a command that does not exist rather than at flag placement. Help lists -s/--json/-q/--timeout/--server as global but gives no indication which flags are subcommand-scoped.",
      "rootCause": "Argument parsing treats any leading token that is not a known command as an unknown command, without first checking whether it is a known subcommand option. The error is emitted before the command-specific parser gets a chance to disambiguate.",
      "codePointer": "cli/browser4-cli/src/ (top-level argument dispatch / unknown-command error)",
      "suggestion": "- Before reporting 'Unknown command', check the token against the union of known subcommand options and, on a match, print 'Unknown option X; it belongs to: <command>'\n- Include a 'did you mean' suggestion using edit distance against known commands\n- Distinguish 'Unknown option' from 'Unknown command' in the error code/message so scripts can tell them apart"
    },
    {
      "title": "navigator.deviceMemory returns an impossible value of 32",
      "severity": "Medium",
      "category": "Product",
      "reproduction": "./b4w.ps1 -s T open --headless \"https://bot.sannysoft.com/\"\n./b4w.ps1 -s T eval \"navigator.deviceMemory\"",
      "expected": "navigator.deviceMemory should report a value Chrome could plausibly compute, i.e. one of the capped buckets (0.25/0.5/1/2/4/8). Real Chrome never exceeds 8.",
      "actual": "Returns 32. Flagged by SannySoft as CHR_MEMORY FAIL, and reported verbatim in BrowserScan's Native Navigator panel and CreepJS' Navigator block. Present in both --headless and --headed sessions, so it is not a display-mode artefact.",
      "rootCause": "Something in the launch path (a CDP Emulation.setDeviceMetricsOverride / device-memory override, or a spoofing layer) sets deviceMemory to a host-derived or hard-coded value rather than leaving it to Chrome's own bucketing. Because Chrome caps at 8, any value above 8 is a guaranteed tell. Confirm whether the override is intentional.",
      "codePointer": "browser4-core/browser4-browser/ (PulsarWebDriver launch/emulation options; search for deviceMemory / setDeviceMetricsOverride)",
      "suggestion": "- Remove the deviceMemory override and let Chrome compute it natively\n- If an override is genuinely required, clamp it to the legal bucket set (max 8) instead of passing a raw host value\n- Audit any other emulation overrides set at session creation for the same class of out-of-range value\n- Add a check to the smoke suite asserting navigator.deviceMemory is one of the legal Chrome buckets"
    },
    {
      "title": "Touch signals are internally inconsistent (maxTouchPoints 10 on a mouse-only device)",
      "severity": "Medium",
      "category": "Product",
      "reproduction": "./b4w.ps1 -s T eval \"JSON.stringify({mtp:navigator.maxTouchPoints,ots:('ontouchstart' in window),coarse:matchMedia('(pointer: coarse)').matches,fine:matchMedia('(pointer: fine)').matches,hover:matchMedia('(hover: hover)').matches,anyCoarse:matchMedia('(any-pointer: coarse)').matches})\"",
      "expected": "A device reporting maxTouchPoints > 0 should expose touch events and a coarse/any-coarse pointer. Real devices are internally consistent.",
      "actual": "navigator.maxTouchPoints = 10, but 'ontouchstart' in window = false, 'ontouchstart' in document.documentElement = false, 'ontouchstart' in navigator = false, matchMedia('(pointer: coarse)') = false, matchMedia('(pointer: fine)') = true, matchMedia('(hover: hover)') = true, matchMedia('(any-pointer: coarse)') = true. CreepJS' Screen block reports 'touch: false' while its Navigator block reports 'touch: 10'. BrowserScan surfaces maxTouchPoints: 10, and its CSS Media Queries section shows 'touch device: true'.",
      "rootCause": "maxTouchPoints appears to be set independently of the pointer/hover emulation and the touch-event surface, producing a combination no real device exhibits. The mismatch between pointer:fine + hover:hover and maxTouchPoints:10 is a strong automation tell precisely because the values are mutually exclusive on genuine hardware.",
      "codePointer": "browser4-core/browser4-browser/ (PulsarWebDriver emulation setup; maxTouchPoints / touch emulation override)",
      "suggestion": "- Derive maxTouchPoints from the same emulation profile that drives the pointer/hover media queries, so the two can never disagree\n- On a desktop profile, report maxTouchPoints 0 (or expose the full touch surface if non-zero is required)\n- Add a smoke assertion that maxTouchPoints, '(pointer: coarse)' and 'ontouchstart' in window are mutually consistent"
    }
  ],
  "assessment": {
    "completionStatus": "Successful — all three phases completed. Five distinct bot-detection services were identified from search results and each was navigated, waited for, fully captured and recorded; the markdown report was written to ./target/bot-stealth-report.md. The task result itself is a negative finding: Browser4 is detected as a bot by 4 of the 5 services in its default headless mode.",
    "successRate": "90% — the browser automation itself worked reliably for all 25+ commands once targets were resolved; the deductions are for the ~90s un-signalled wait on deviceandbrowserinfo.com, the ~14s skeleton state on BrowserScan, and the Google block that forced a search-engine substitution.",
    "issuesFound": 9,
    "majorBlockers": "Google search was blocked at IP level (/sorry/index, 'unusual traffic from your computer network') in both headless and the documented one-shot headed retry, forcing a switch to DuckDuckGo's no-JS endpoint. Bing returned 10 entirely irrelevant results and was unusable. No browser4-cli defect blocked the task itself.",
    "mostConfusingAspects": "1) Silence is ambiguous: an unmatched `fill` selector reports '✓ Filled ...' and exits 0, and a page whose results have not loaded yet reads as an empty string with no warning — both are indistinguishable from success. 2) `wait --load networkidle` returning '✓ Wait complete' while the page is still 90 seconds away from showing anything undermines the one synchronisation primitive a new user is told to trust. 3) `fill \"combobox\"` accepted a non-CSS token and reported success without doing anything, which cost several minutes before switching to a ref. 4) Files written as .png that are actually JPEG. 5) A dev checkout silently serving a backend build older than the sources, which casts doubt on every result obtained.",
    "mostValuableImprovements": "1) Strip the 'HeadlessChrome' token from the headless User-Agent — it is the single largest cause of detection, and --headed already proves the clean value is reachable. 2) Make every interaction command fail loudly when its selector matches nothing, mirroring the value contract `get` already documents. 3) Stop overriding navigator.deviceMemory to an impossible 32. 4) Make maxTouchPoints consistent with the pointer/hover media queries. 5) Name screenshot files according to the bytes actually written, or honour the requested extension.",
    "usabilityRating": 6
  }
}
```

**Scope note:** the report's headline result reflects `--headless`, the mode the skill instructs agents to use by default. `--headed` was spot-checked only for the User-Agent (clean) — it was not run against all five services, so this is not a full headless-vs-headed comparison. The two network-layer detections (Linux TCP/IP fingerprint vs a Windows UA, relay flagged at 0.97) are environmental and would persist regardless of client-side fixes.
