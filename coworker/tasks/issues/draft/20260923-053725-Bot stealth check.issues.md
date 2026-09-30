# Issues: Bot stealth check

> **Source:** `20260923-053725-Bot stealth check.full.md` | **Date:** 20260923-053725 | **Mode:** dev

## Scenario Background

### Task

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



**Scope note:** the report's headline result reflects `--headless`, the mode the skill instructs agents to use by default. `--headed` was spot-checked only for the User-Agent (clean) — it was not run against all five services, so this is not a full headless-vs-headed comparison. The two network-layer detections (Linux TCP/IP fingerprint vs a Windows UA, relay flagged at 0.97) are environmental and would persist regardless of client-side fixes.

---

## Issues Found (9 issues)

### Issue 1: Headless mode advertises HeadlessChrome in the User-Agent

**Severity:** Critical
**Category:** Product

#### Reproduction

./b4w.ps1 -s T open --headless "https://bot.sannysoft.com/"
./b4w.ps1 -s T eval "navigator.userAgent"
# then ./b4w.ps1 -s T close && ./b4w.ps1 -s T open --headed "about:blank" && repeat the eval

#### Expected Behavior

Headless mode should present the same User-Agent as a normal Chrome installation (e.g. ...Chrome/153.0.0.0 Safari/537.36), as --headed already does.

#### Actual Behavior

Headless: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/153.0.0.0 Safari/537.36'. Headed: identical but with 'Chrome/153.0.0.0' and no 'HeadlessChrome' token. Detector impact: SannySoft HEADCHR_UA FAIL; Incolumitas intoli.userAgent FAIL and fpscanner.HEADCHR_UA FAIL; BrowserScan overall verdict 'Robot'; deviceandbrowserinfo hasBotUserAgent:true -> 'You are a bot!'; CreepJS 67% headless.

#### Root Cause Analysis

The Chrome instance launched for headless mode is started with the default new-headless UA, which appends the 'HeadlessChrome/<version>' product token. Headed mode is not affected, so the UA is being derived from the process launch flags rather than being normalised. Fix likely means launching with an overridden UA (--user-agent=) or applying a Network.setUserAgentOverride / Emulation.setUserAgentOverride CDP call at session creation for the headless path. Note this is a deliberate design decision on some CDP stacks, so confirm intent before changing.

#### Code Pointer

`browser4-core/browser4-browser/ (PulsarWebDriver session/launch options; search for headless launch flags and userAgent override)`

#### AI Suggested Improvement

- Apply a CDP Emulation.setUserAgentOverride (or --user-agent launch flag) in headless mode so navigator.userAgent, navigator.appVersion and navigator.userAgentData are all consistent and free of the 'HeadlessChrome' token
- Also normalise the Sec-CH-UA request headers so client hints match the overridden UA
- Add a regression test asserting navigator.userAgent does not contain 'Headless' for both --headless and --headed sessions
- Document the expected UA string per display mode in skills/browser4-cli/references/browser-modes.md

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: fill with a selector that matches nothing reports success and exits 0

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s T fill "#definitely-not-here-xyz" "hello" --no-snapshot
echo "exit=$?"
./b4w.ps1 -s T eval "document.querySelectorAll('#definitely-not-here-xyz').length"

#### Expected Behavior

A non-matching selector should produce an error and a non-zero exit code (the `get` command already establishes this contract: 'null means the selector matched nothing', and an unresolvable ref 'errors explicitly').

#### Actual Behavior

'✓ Filled 'hello' into #definitely-not-here-xyz' printed, exit code 0, while querySelectorAll returns 0 matches. The command silently did nothing. Encountered in the wild: './b4w.ps1 -s stealth fill "combobox" "online bot detection..." --submit' reported success on the Google homepage but no navigation occurred; re-running with the real ref (e9/e49) plus `press Enter` worked immediately.

#### Root Cause Analysis

The fill path resolves the target (ref or CSS selector), but does not assert that resolution produced an element before reporting success. Any automation script that trusts the exit code will silently skip steps — this is the most dangerous class of failure for agent-driven workflows because it produces no error signal at all.

#### Code Pointer

`cli/browser4-cli/src/commands.rs (fill command) and/or the backend fill tool binding in browser4-agentic AgentToolManager / PulsarWebDriver.fill()`

#### AI Suggested Improvement

- Fail fast: if the selector matches zero elements, return an error with the selector echoed back and exit non-zero
- Mirror the documented `get` value contract across all interaction commands (click, fill, type, hover, select, check) so 'matched nothing' is never reported as success
- If a bare non-CSS token such as 'combobox' is accepted as a role/name shorthand, resolve it explicitly and error when it is ambiguous or unmatched, rather than reporting the raw string back as if it were a resolved target
- Add a unit test asserting a non-zero exit and no '✓ Filled' line for an unmatched selector

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: screenshot --full-page ignores the requested extension and always writes JPEG

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 -s T screenshot -o out-viewport.png
./b4w.ps1 -s T screenshot --full-page -o out-fullpage.png
./b4w.ps1 -s T screenshot --full-page -o out-fullpage.jpg
# inspect the first bytes of each file

#### Expected Behavior

A file named *.png should contain PNG data; the CLI should either honour the extension or provide a format flag.

#### Actual Behavior

out-viewport.png -> PNG (35,062 bytes). out-fullpage.png -> JPEG (magic ff d8 ff e0 ... JFIF), 334,361 bytes, but the CLI still prints '[Screenshot](...out-fullpage.png)'. out-fullpage.jpg -> JPEG. The extension is silently ignored for full-page captures, so consumers that trust the extension get a file they cannot decode.

#### Root Cause Analysis

The full-page capture path uses a different encoder from the viewport path. `screenshot --help` exposes only -o/--full-page/-v and no format option, so the format is hard-coded per code path: the viewport branch emits PNG while the full-page branch (likely a captureBeyondViewport call or a resize-and-capture fallback) emits JPEG. The filename is passed through verbatim, so the mismatch is never reconciled.

#### Code Pointer

`cli/browser4-cli/src/ (screenshot command handler); backend screenshot tool binder in browser4-rest / browser4-agentic AgentToolManager`

#### AI Suggested Improvement

- Make the full-page path encode PNG to match the viewport path
- If JPEG is required for size reasons on tall pages, add an explicit --format png|jpeg flag (default png) and rewrite the output extension to match the bytes actually written
- At minimum, print the real written path with the correct extension in the [Screenshot](...) link instead of echoing the requested name

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: wait --load networkidle returns complete while page content is still loading

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 -s T goto "https://www.browserscan.net/bot-detection"
./b4w.ps1 -s T wait --load networkidle      # prints '✓ Wait complete'
./b4w.ps1 -s T screenshot --full-page -o a.png   # result card is skeleton bars
# 8-14s later the real verdict appears

#### Expected Behavior

After `wait --load networkidle` completes, the page should be settled enough to read, or the command should report that the page is still loading.

#### Actual Behavior

networkidle fires while the detection results are still placeholders. On deviceandbrowserinfo.com it was far worse: `wait --load networkidle` completed and goto printed 'Page loaded', but the verdict did not exist for ~90 seconds (cstlxp.js took 56.7s, fingerprint-scan.com/v1/fp.js 15s and timed out on first load). Reading the page at any point before that yields an empty 'Raw detection details' box with no error and exit code 0 — indistinguishable from 'nothing to report'.

#### Root Cause Analysis

`networkidle` is a network-idle heuristic that does not account for (a) scripts still downloading, (b) client-side JS that computes results after load, or (c) long-running XHRs. There is no 'wait for content' primitive beyond `wait --selector`, and the user gets no hint that a page which looks loaded may still be empty. `goto` additionally emits 'Page loaded. Use `wait --load networkidle` if content appears incomplete', which actively suggests networkidle as the remedy.

#### Code Pointer

`cli/browser4-cli/src/ (wait command, --load handling); the 'Page loaded' hint text emitted by goto`

#### AI Suggested Improvement

- Have `wait <selector>` be the recommended pattern in the goto hint for result-style pages, and mention `--wait-selector` on eval
- Consider a `wait --load results` / `wait --text <pattern>` affordance for late-rendered content
- When a read (eval / htmlsnapshot get) returns empty text on a page that is still fetching resources, print a stderr hint rather than returning success silently
- Document in snapshot.md / quickstart.md that networkidle is not sufficient for JS-computed results and that polling for the result element is the reliable approach

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: No blocked-page detection even though the documented escalation list covers it

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 -s T goto "https://www.google.com"
./b4w.ps1 -s T fill <search-ref> "online bot detection test browser automation stealth check" --submit
# lands on https://www.google.com/sorry/index?continue=... with a 671-char body

#### Expected Behavior

SKILL §2 (Display Mode / anti-bot escalation) documents an explicit list of block signatures — 'Google /sorry/, CAPTCHA/verification widget, Cloudflare/DataDome/Akamai/PerimeterX challenge, "unusual traffic" / "access denied", or an implausibly empty body'. A user reasonably expects that list to be enforced somewhere, or at least surfaced automatically.

#### Actual Behavior

goto printed a normal success result and 'Page loaded.' The only way to discover the block was to inspect the URL or body text manually. Nothing in the CLI output flags that the page is a challenge page; the user must already know the list in order to apply it. (The documented headed retry itself worked correctly and correctly concluded the block was IP-level.)

#### Root Cause Analysis

The block-signature list exists only as prose for the agent to apply, not as a check in the CLI. Given the SKILL tells the agent to escalate automatically, encoding the same signatures as a post-navigation warning would close the loop between documentation and behaviour.

#### Code Pointer

`cli/browser4-cli/src/ (post-navigation result printing; the same place that emits the 'Page loaded. Use `wait --load networkidle`' hint)`

#### AI Suggested Improvement

- After goto/open, check URL patterns (/sorry/, /cdn-cgi/challenge, /captcha) and body markers (unusual traffic, access denied, verify you are human, enable JavaScript and cookies) and print a stderr warning naming the detected challenge
- Suggest the documented next step in that warning (close + open --headed with the same -s, retry once)
- Keep it advisory only — do not fail the command, since a legitimate page could match a substring
- Surface block state in `page-info` so it can be asserted programmatically

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: Dev mode serves a stale backend runtime bundle without rebuilding

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 help      # first launch of a dev checkout whose sources are newer than the existing bundle

#### Expected Behavior

Dev mode should either build the runtime bundle from the checked-out sources, or refuse to start, since the entire premise of running from the source tree is that behaviour matches the code under test.

#### Actual Behavior

On startup: '⚠  the checked-out sources are newer than the existing local Browser4 runtime bundle. ⚠  Serving the OLD backend build — behaviour may not match the checked-out code.' The server then starts normally and the session proceeds. Any testing done against that backend validates stale code — a silent correctness hazard for the exact use case (dev/CI from source) that the mode exists to serve. Environment variable to force a rebuild: BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1.

#### Root Cause Analysis

Detecting that sources are newer than the bundle and then continuing anyway leaves the mismatch as a warning the user can ignore. There is no default that preserves the invariant 'dev mode runs the checked-out code'.

#### Code Pointer

`b4w.ps1 / cli/browser4-cli/src/ (server autostart + runtime-bundle staleness check); browser4-apps/browser4-bundle/build-runtime-bundle.ps1`

#### AI Suggested Improvement

- Default to rebuilding when sources are newer, or fail fast with the rebuild command rather than continuing on stale code
- Require an explicit opt-out flag (e.g. --allow-stale-bundle) to run against an outdated bundle
- Echo the bundle's build timestamp and git revision in the warning, and include the same info in `status` / `doctor` so a test run's provenance is auditable

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: navigator.deviceMemory returns an impossible value of 32

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 -s T open --headless "https://bot.sannysoft.com/"
./b4w.ps1 -s T eval "navigator.deviceMemory"

#### Expected Behavior

navigator.deviceMemory should report a value Chrome could plausibly compute, i.e. one of the capped buckets (0.25/0.5/1/2/4/8). Real Chrome never exceeds 8.

#### Actual Behavior

Returns 32. Flagged by SannySoft as CHR_MEMORY FAIL, and reported verbatim in BrowserScan's Native Navigator panel and CreepJS' Navigator block. Present in both --headless and --headed sessions, so it is not a display-mode artefact.

#### Root Cause Analysis

Something in the launch path (a CDP Emulation.setDeviceMetricsOverride / device-memory override, or a spoofing layer) sets deviceMemory to a host-derived or hard-coded value rather than leaving it to Chrome's own bucketing. Because Chrome caps at 8, any value above 8 is a guaranteed tell. Confirm whether the override is intentional.

#### Code Pointer

`browser4-core/browser4-browser/ (PulsarWebDriver launch/emulation options; search for deviceMemory / setDeviceMetricsOverride)`

#### AI Suggested Improvement

- Remove the deviceMemory override and let Chrome compute it natively
- If an override is genuinely required, clamp it to the legal bucket set (max 8) instead of passing a raw host value
- Audit any other emulation overrides set at session creation for the same class of out-of-range value
- Add a check to the smoke suite asserting navigator.deviceMemory is one of the legal Chrome buckets

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: Touch signals are internally inconsistent (maxTouchPoints 10 on a mouse-only device)

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 -s T eval "JSON.stringify({mtp:navigator.maxTouchPoints,ots:('ontouchstart' in window),coarse:matchMedia('(pointer: coarse)').matches,fine:matchMedia('(pointer: fine)').matches,hover:matchMedia('(hover: hover)').matches,anyCoarse:matchMedia('(any-pointer: coarse)').matches})"

#### Expected Behavior

A device reporting maxTouchPoints > 0 should expose touch events and a coarse/any-coarse pointer. Real devices are internally consistent.

#### Actual Behavior

navigator.maxTouchPoints = 10, but 'ontouchstart' in window = false, 'ontouchstart' in document.documentElement = false, 'ontouchstart' in navigator = false, matchMedia('(pointer: coarse)') = false, matchMedia('(pointer: fine)') = true, matchMedia('(hover: hover)') = true, matchMedia('(any-pointer: coarse)') = true. CreepJS' Screen block reports 'touch: false' while its Navigator block reports 'touch: 10'. BrowserScan surfaces maxTouchPoints: 10, and its CSS Media Queries section shows 'touch device: true'.

#### Root Cause Analysis

maxTouchPoints appears to be set independently of the pointer/hover emulation and the touch-event surface, producing a combination no real device exhibits. The mismatch between pointer:fine + hover:hover and maxTouchPoints:10 is a strong automation tell precisely because the values are mutually exclusive on genuine hardware.

#### Code Pointer

`browser4-core/browser4-browser/ (PulsarWebDriver emulation setup; maxTouchPoints / touch emulation override)`

#### AI Suggested Improvement

- Derive maxTouchPoints from the same emulation profile that drives the pointer/hover media queries, so the two can never disagree
- On a desktop profile, report maxTouchPoints 0 (or expose the full touch surface if non-zero is required)
- Add a smoke assertion that maxTouchPoints, '(pointer: coarse)' and 'ontouchstart' in window are mutually consistent

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: Global-option misuse produces an unhelpful 'Unknown command' error

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 -s hd --headed open "about:blank"

#### Expected Behavior

A hint that --headed is an option of `open`, not a global flag — e.g. 'Unknown option --headed; did you mean: open --headed <url>?' or at least 'Unknown option', distinguishing it from a mistyped command.

#### Actual Behavior

'Error: Unknown command: '--headed'' followed by the dev-mode banner and the generic 'Run `browser4-cli help <command>`' footer. The wording frames a misplaced option as a nonexistent command, which sends the user looking for a command that does not exist rather than at flag placement. Help lists -s/--json/-q/--timeout/--server as global but gives no indication which flags are subcommand-scoped.

#### Root Cause Analysis

Argument parsing treats any leading token that is not a known command as an unknown command, without first checking whether it is a known subcommand option. The error is emitted before the command-specific parser gets a chance to disambiguate.

#### Code Pointer

`cli/browser4-cli/src/ (top-level argument dispatch / unknown-command error)`

#### AI Suggested Improvement

- Before reporting 'Unknown command', check the token against the union of known subcommand options and, on a match, print 'Unknown option X; it belongs to: <command>'
- Include a 'did you mean' suggestion using edit distance against known commands
- Distinguish 'Unknown option' from 'Unknown command' in the error code/message so scripts can tell them apart

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

**Completion Status:** Successful — all three phases completed. Five distinct bot-detection services were identified from search results and each was navigated, waited for, fully captured and recorded; the markdown report was written to ./target/bot-stealth-report.md. The task result itself is a negative finding: Browser4 is detected as a bot by 4 of the 5 services in its default headless mode.

**Success Rate:** 90% — the browser automation itself worked reliably for all 25+ commands once targets were resolved; the deductions are for the ~90s un-signalled wait on deviceandbrowserinfo.com, the ~14s skeleton state on BrowserScan, and the Google block that forced a search-engine substitution.

**Issues Found:** 9

**Major Blockers:** Google search was blocked at IP level (/sorry/index, 'unusual traffic from your computer network') in both headless and the documented one-shot headed retry, forcing a switch to DuckDuckGo's no-JS endpoint. Bing returned 10 entirely irrelevant results and was unusable. No browser4-cli defect blocked the task itself.

**Most Confusing Aspects:** 1) Silence is ambiguous: an unmatched `fill` selector reports '✓ Filled ...' and exits 0, and a page whose results have not loaded yet reads as an empty string with no warning — both are indistinguishable from success. 2) `wait --load networkidle` returning '✓ Wait complete' while the page is still 90 seconds away from showing anything undermines the one synchronisation primitive a new user is told to trust. 3) `fill "combobox"` accepted a non-CSS token and reported success without doing anything, which cost several minutes before switching to a ref. 4) Files written as .png that are actually JPEG. 5) A dev checkout silently serving a backend build older than the sources, which casts doubt on every result obtained.

**Most Valuable Improvements:** 1) Strip the 'HeadlessChrome' token from the headless User-Agent — it is the single largest cause of detection, and --headed already proves the clean value is reachable. 2) Make every interaction command fail loudly when its selector matches nothing, mirroring the value contract `get` already documents. 3) Stop overriding navigator.deviceMemory to an impossible 32. 4) Make maxTouchPoints consistent with the pointer/hover media queries. 5) Name screenshot files according to the bytes actually written, or honour the requested extension.

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

#### Issue 1: Headless mode advertises HeadlessChrome in the User-Agent

./b4w.ps1 -s T open --headless "https://bot.sannysoft.com/"
./b4w.ps1 -s T eval "navigator.userAgent"
# then ./b4w.ps1 -s T close && ./b4w.ps1 -s T open --headed "about:blank" && repeat the eval

#### Issue 2: fill with a selector that matches nothing reports success and exits 0

./b4w.ps1 -s T fill "#definitely-not-here-xyz" "hello" --no-snapshot
echo "exit=$?"
./b4w.ps1 -s T eval "document.querySelectorAll('#definitely-not-here-xyz').length"

#### Issue 3: screenshot --full-page ignores the requested extension and always writes JPEG

./b4w.ps1 -s T screenshot -o out-viewport.png
./b4w.ps1 -s T screenshot --full-page -o out-fullpage.png
./b4w.ps1 -s T screenshot --full-page -o out-fullpage.jpg
# inspect the first bytes of each file

#### Issue 4: wait --load networkidle returns complete while page content is still loading

./b4w.ps1 -s T goto "https://www.browserscan.net/bot-detection"
./b4w.ps1 -s T wait --load networkidle      # prints '✓ Wait complete'
./b4w.ps1 -s T screenshot --full-page -o a.png   # result card is skeleton bars
# 8-14s later the real verdict appears

#### Issue 5: No blocked-page detection even though the documented escalation list covers it

./b4w.ps1 -s T goto "https://www.google.com"
./b4w.ps1 -s T fill <search-ref> "online bot detection test browser automation stealth check" --submit
# lands on https://www.google.com/sorry/index?continue=... with a 671-char body

#### Issue 6: Dev mode serves a stale backend runtime bundle without rebuilding

./b4w.ps1 help      # first launch of a dev checkout whose sources are newer than the existing bundle

#### Issue 7: navigator.deviceMemory returns an impossible value of 32

./b4w.ps1 -s T open --headless "https://bot.sannysoft.com/"
./b4w.ps1 -s T eval "navigator.deviceMemory"

#### Issue 8: Touch signals are internally inconsistent (maxTouchPoints 10 on a mouse-only device)

./b4w.ps1 -s T eval "JSON.stringify({mtp:navigator.maxTouchPoints,ots:('ontouchstart' in window),coarse:matchMedia('(pointer: coarse)').matches,fine:matchMedia('(pointer: fine)').matches,hover:matchMedia('(hover: hover)').matches,anyCoarse:matchMedia('(any-pointer: coarse)').matches})"

#### Issue 9: Global-option misuse produces an unhelpful 'Unknown command' error

./b4w.ps1 -s hd --headed open "about:blank"


---

# Fix Round — 2026-09-23

All nine issues were re-checked against the tree, reproduced where they were real, and
addressed. Each entry states the verdict, the fix, and the evidence produced on this
machine (Chrome 153.0.8010.53, Windows 11, 32 GB RAM, 20 logical cores).

**Verdict summary:** 7 fixed, 2 proven not to be Browser4 defects (reproduced with a
plain `chrome --headless` launch, i.e. with no Browser4 code involved at all).

| # | Issue | Verdict |
|---|-------|---------|
| 1 | Headless UA advertises `HeadlessChrome` | **Fixed** |
| 2 | `fill` with an unmatched selector reports success | **Fixed** |
| 3 | `screenshot --full-page` ignores the extension (always JPEG) | **Fixed** |
| 4 | `wait --load networkidle` overstates what it proved | **Fixed** |
| 5 | No blocked-page detection | **Fixed** |
| 6 | Dev mode serves a stale backend bundle | **Fixed** |
| 7 | `navigator.deviceMemory === 32` | **Not a defect** — stock Chrome 153 on a 32 GB host |
| 8 | `maxTouchPoints: 10` with `pointer: fine` | **Not a defect** — stock Chrome on this host's touch digitizer |
| 9 | Misplaced global option reported as `Unknown command` | **Fixed** |

---

## Issue 1 — Headless mode advertises HeadlessChrome in the User-Agent — FIXED

**Root cause (different from the report's hypothesis).** `pulsar-browser` already ships
the fix: `BrowserSettings.createChromeOptions()` sets `chromeOptions.userAgent` from
`resolveUserAgent()`, which builds a reduced UA through `ReducedUserAgent`. That call
never produced a value:

```kotlin
// ReducedUserAgent.buildOrNull()
fun buildOrNull(configuredUserAgent: String? = null, binary: Path? = null): String? {
    ...
    val major = chromeMajorVersion(binary) ?: return null   // <-- binary is an explicit null
}

fun chromeMajorVersion(binary: Path? = Browsers.searchChromeBinaryOrNull()): Int? {
    if (binary == null) return null                          // <-- default never evaluated
```

Because the caller passes `binary` *explicitly* as `null`, Kotlin's default-argument
mechanism does not run, the installed-Chrome lookup is skipped, and `resolveUserAgent()`
returns `null` for every session that does not name a user agent in a config file. The
result is a launch line with no `--user-agent` switch (confirmed on the live process) and
`HeadlessChrome/<major>.0.0.0` on the wire. The headed session looked clean only because
Chrome's user-agent reduction reports `Chrome/153.0.0.0` natively.

**Fix.** `browser4-core/browser4-browser/.../chrome/Browser4UserAgent.kt` re-derives the
value with the binary resolved explicitly, and
`browser4-core/browser4-protocol/.../impl/DefaultBrowserFactory.kt` applies it in
`launch(browserId, launcherOptions, launchOptions)` — the single funnel every browser
launch in the application goes through (session launches, pooled launches and the legacy
display-mode path alike). A user agent already decided by the caller or by
`browser.launch.user.agent` is never overridden, and `browser.launch.user.agent.stealth=false`
still disables the replacement. A launch-time switch is used deliberately: it is the only
mechanism that reaches every JavaScript scope (page, iframes, dedicated/shared/service
workers) while leaving `Sec-CH-UA*` intact — matching the reasoning already documented in
`ReducedUserAgent`.

**Evidence.**

```
$ ./b4w.ps1 -s v3 eval "navigator.userAgent"
Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36

$ (chrome process command line)
--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36
```

Regression gate: **`NavigatorStealthIT`** (new, `browser4-tests/pulsar-it-tests`) asserts on a
real browser that `navigator.userAgent` carries no `Headless` token, that
`navigator.userAgentData.brands` agrees, that `deviceMemory` is a plausible bucket and that
the touch signals are coherent. It passes through the independent
`launchRandomTempBrowser()` path — 4 tests, 0 failures.

> **4.14.x 落地位置**：`pulsar-it-tests` 模块（连同 `WebDriverTestBase`）在 4.14.x 已删除，所以这份
> 回归门禁现挂在 `browser4-core/browser4-browser/src/test/kotlin/ai/platon/pulsar/chrome/NavigatorStealthE2ETest.kt`
> —— 同样 4 个真浏览器测试，tag `E2E`/`RequiresBrowser`/`ManualOnly`，用
> `-D"surefire.excludedGroups="` 手动运行（4.14.x 合并后的实测：4 例 0 失败 / 39 s）。

## Issue 2 — fill with a selector that matches nothing reports success — FIXED

`Browser4WebDriver.fillSafe()` wrote the value through `evaluateValue(selector, fillValueJs(text))`
without resolving the target first; the JS body is a no-op when `this` is nothing, and the
CLI printed `✓ Filled ...` for the successful-looking call. `typeAuto()` already refused
unresolvable targets, so `fill` was the outlier.

**Fix.** `inputTargetProbeJs()` (shared by `typeAuto`, which already used it) plus
`inputTargetError()`, which `fillSafe()` now runs before writing: a locator that resolves to
nothing, or to an element whose user input is blocked (disabled / read-only), throws with
the selector echoed back. The executor's non-Browser4 fallback probes the same way. No
retry can turn the failure into a silent success — the error surfaces through the MCP
`isError` path, which the CLI already maps to a non-zero exit.

**Evidence.**

```
$ ./b4w.ps1 -s v1 fill "#definitely-not-here-xyz" "hello" --no-snapshot ; echo $?
ERROR: browser_type failed: fill: no element found for selector [#definitely-not-here-xyz].
       The selector may be stale — re-run `snapshot` to refresh refs.
1
$ ./b4w.ps1 -s v1 fill "body" "x" --no-snapshot ; echo $?
✓ Filled 'x' into body
0
```

`type` with the same selector also exits 1. Unit tests: `inputTargetError*` and
`inputTargetProbeJs*` in `Browser4WebDriverTest`, and the executor test now asserts the
probe-then-fill call order.

## Issue 3 — screenshot --full-page ignores the extension — FIXED

`ScreenshotHandler` (in `pulsar-browser`) hard-codes `CaptureScreenshotFormat.JPEG` in both
the full-page branch and the element/clip branch, while the plain viewport path uses the
PNG default. `screenshot --help` had no format option, so the mismatch was never reconciled.

**Fix, in two layers.**

1. `Browser4WebDriver.screenshotFullPage(format)` re-implements the base full-page capture
   with a selectable format (PNG by default, matching the viewport capture) using the
   documented `executeCdpCommand`/`BrowserProtocol` extension surface. It keeps the base
   algorithm — temporarily resize the device metrics to the content size, capture beyond the
   viewport, restore the metrics — but moves the restore into a `finally` block so a failed
   capture cannot leave the page resized, and it mirrors the base's
   `ChromeDriverException → null` error contract.
2. The CLI now treats the output extension as the format request (sending `format=jpeg` for
   `*.jpg`/`*.jpeg` full-page captures) and reconciles the written path with the actual bytes
   **by file signature**, so the `[Screenshot](...)` link can never name a PNG file holding
   JFIF data. That covers the element/viewport paths, which remain JPEG-only in the library.

**Evidence.**

```
viewport.png    17276 bytes  magic=89 50 4e 47  PNG
fullpage.png    17276 bytes  magic=89 50 4e 47  PNG    (was JPEG before)
fullpage.jpg    19412 bytes  magic=ff d8 ff e0  JPEG   (explicit request honoured)
```

## Issue 4 — wait --load networkidle returns complete while content is still loading — FIXED

`networkidle` is a network-quiet heuristic and the CLI reported it as `✓ Wait complete`,
while `goto` printed a hint that recommended exactly that primitive for incomplete content.

**Fix.** `wait --load networkidle` now says what it proved —
`✓ Wait complete (network idle for 500ms; page JS may still be rendering results)` — and,
when the page still has resources in flight (images, fonts, unfinished resource-timing
entries), prints a one-line stderr hint pointing at `wait <result-selector>`. The
post-navigation hint now recommends waiting for the result element first and describes
`networkidle` as a network-settling extra step. The limitation is documented in
`SKILL.md`, `references/quickstart.md`, `references/snapshot.md`, `references/quick-patterns.md`,
`cli/README.md`, both root READMEs, `docs/skill-audit-methodology.md`, `help.rs` and `tips.rs`.
Both advisories are advisory only: no exit-code change, no polling, one cheap round trip, and
a failed probe stays silent.

## Issue 5 — No blocked-page detection — FIXED

The block-signature list existed only as prose in `SKILL.md` §2.

**Fix.** After a successful navigation, `goto`/`open` run one `browser_evaluate` probe that
returns the landed URL and its visible text, score it against the documented signatures
(URL: `/sorry`, `/cdn-cgi/challenge`, `/challenge`, `/captcha`, `__cf_chl`, `/verify`, plus
`continue=` only alongside a "sorry" path; body: `unusual traffic`, `access denied`,
`verify you are human`, `are you a robot`, `enable JavaScript and cookies to continue`,
`Attention Required!`, `Just a moment...`, `Checking your browser before accessing`, `請稍候`;
and an implausibly empty body on an http(s) page as a separate, weaker signal), and print an
**advisory stderr warning** naming the signature and the documented escalation. The command
still succeeds; `--json` reports `challenge_detected` / `challenge_signature`, and
`page-info` exposes the same two fields so the state can be asserted for a page this CLI did
not navigate to itself.

**Evidence.**

```
$ ./b4w.ps1 -s v1 goto "https://example.com/sorry/index"
Navigated to https://example.com/sorry/index
⚠  Possible blocked/challenge page — detected URL matches the block/challenge marker `/sorry`.
Next step: `close` and re-open once with `--headed` on the same `-s <session>` …

$ ./b4w.ps1 -s v1 --json page-info
{"…","challenge_detected":false}          # ordinary page
```

## Issue 6 — Dev mode serves a stale backend runtime bundle — FIXED

Previously the launcher detected the mismatch and then served the stale bundle anyway.

**Fix.** The policy is now explicit and, crucially, only refuses when the signal is
trustworthy:

- **Different project version** — hard refusal, non-zero exit, nothing started. The message
  names the detected reason, the bundled/checked-out versions, the bundle's build time, the
  exact rebuild command and the single opt-out `BROWSER4_CLI_ALLOW_STALE_BUNDLE=1`.
- **Sources look newer than the bundle jars** — rebuild from source, then start. This signal
  cannot be verified: Maven skips a repackage whose result is byte-identical, so a tree that
  was merely checked out or re-saved looks exactly like one that really changed (measured
  here: `browser4-rest` sources dated 19:35 against a jar dated 18:22, yet deleting and
  rebuilding the jar produced a byte-identical archive). Refusing on it would block dev-mode
  startup on a false positive; rebuilding is correct in the real case and a no-op repackage
  in the harmless one.
- Both build paths now pass `-Dmaven.jar.forceCreation=true`, because the jar plugin
  otherwise skips a repackage when it thinks nothing changed.
- A small `.browser4-bundle-build-stamp` is written into `lib/` whenever Maven ran, and the
  staleness check takes the newer of a module jar and the stamp. Without it, a rebuild whose
  content is identical could never clear the reading and every command would rebuild forever.
- Staleness is also compared **per module** (each module's `src/main` against its own jar,
  test sources excluded), so an incremental build that refreshes one module cannot make the
  whole checkout look stale.
- `status` / `doctor` report the same provenance in text and in `--json` (`local_bundle`).

**Evidence.**

```
$ ./b4w.ps1 -s v9 open --headless about:blank        # bundle renamed to 4.13.20
✖  Refusing to start the Browser4 server: the existing local Browser4 runtime bundle was
   built from 4.13.20 sources, but the checked-out sources are 4.13.21-SNAPSHOT.
✖  Nothing was started — dev mode does not serve a backend that predates the checked-out sources.
…  exit=1

$ ./b4w.ps1 -s v3 open --headless about:blank        # sources look newer
⚠  Rebuilding from source before starting, so dev mode cannot serve a backend older than the
   checked-out code.
Building local Browser4 runtime bundle … / Assembling … / Local Browser4 runtime bundle built successfully
Server ready in 8.9s                                   # elapsed 297s, stamp written

$ ./b4w.ps1 -s v3 page-info                          # converged: no rebuild
elapsed=6.2s exit=0
```

## Issue 7 — navigator.deviceMemory returns an impossible value of 32 — NOT A DEFECT

Reproduced **without Browser4** on the same host:

```
$ chrome.exe --headless=new --disable-gpu --no-sandbox --dump-dom file:///probe.html
{"dm":32,"hc":20,"ua":"… HeadlessChrome/153.0.0.0 …","mtp":10,"touch":false,"coarse":false,"anyCoarse":true}
```

Chrome 153 derives `deviceMemory` from the host's RAM (31.7 GB here) and no longer clamps it
at 8 — the clamp the report assumes was the specification's, and SannySoft's `CHR_MEMORY`
check predates the change. Browser4 sets no `navigator.deviceMemory` override anywhere (nor
does `pulsar-browser`; it only *reads* the value when building a snapshot), and a Browser4
session on an https page reports the identical `32`. The suggested "fix" — clamping to 8 —
would be a new spoof, and would itself be inconsistent with every real 32 GB machine.

**What was done instead:** `NavigatorStealthIT` pins the contract that Chrome's own bucketing
satisfies (`deviceMemory` must be a power of two, never a raw host value), so a future
override that produced e.g. `31.7` would fail; the finding is documented in
`skills/browser4-cli/references/browser-modes.md`.

## Issue 8 — Touch signals are internally inconsistent — NOT A DEFECT

Same measurement, same conclusion: stock Chrome on this host reports
`maxTouchPoints: 10` **with** `'ontouchstart' in window === false`,
`(pointer: fine) === true` and `(any-pointer: coarse) === true`, because the host has a
touch digitizer but a fine primary pointer. A Browser4 session reports the same tuple
character for character. Browser4 sets no touch or pointer override.

`NavigatorStealthIT` pins the coherence rules real Chrome satisfies (touch points ⇒ a coarse
pointer somewhere; coarse primary pointer ⇒ coarse *any* pointer) and the finding is
documented alongside Issue 7. Note that the report's own reading already contains the
consistency signal: `(any-pointer: coarse)` is `true`.

## Issue 9 — Global-option misuse produces an unhelpful 'Unknown command' error — FIXED

The top-level command lookup treated any leading token that was not a command as an unknown
command, without checking the union of subcommand options.

**Fix.** `commands::commands_defining_option()` maps a token (long or short form) to the
commands that define it, and `misplaced_option_message()` builds the error:

```
$ ./b4w.ps1 -s hd --headed open "about:blank" ; echo $?
Unknown option: '--headed' — it is an option of `open` (as `--headed`), not a global flag or a command.
Try: browser4-cli open --headed <url>
Run `browser4-cli open --help` for its options.
2
```

A genuinely mistyped command keeps the previous behaviour
(`Unknown command: 'opne'. Did you mean: 'open', 'pdf', 'type'?`), a nonsense option reports
`Unknown option` rather than `Unknown command`, and `-o` resolves to `--filename`. Exit code
2 (`ExitCode::Usage`) in all cases.

---

## Files changed

**Backend (Kotlin)**

| File | Change |
|---|---|
| `browser4-core/browser4-browser/.../chrome/Browser4UserAgent.kt` | **new** — re-derives the reduced user agent the library's default path never produces |
| `browser4-core/browser4-browser/.../chrome/Browser4WebDriver.kt` | apply the stealth UA is done in the factory; here: shared input-target probe + `inputTargetError`, `fillSafe` refuses an unresolvable/blocked target, new format-aware `screenshotFullPage` |
| `browser4-core/browser4-protocol/.../impl/DefaultBrowserFactory.kt` | apply the stealth user agent at the single launch funnel |
| `browser4-agentic/.../builtin/BrowserTabToolExecutor.kt` | `fill` fallback probes its target first; `fullPage` routes the requested `format` to the browser4 capture |

**CLI (Rust)**

| File | Change |
|---|---|
| `cli/browser4-cli/src/main.rs` | navigation hint, network-idle honesty + in-flight hint, block/challenge detection, `page-info` challenge fields, screenshot format/reconcile, misplaced-option message |
| `cli/browser4-cli/src/commands.rs` | `NETWORK_IDLE_MARKER`, `commands_defining_option`, screenshot option docs |
| `cli/browser4-cli/src/snapshot.rs` | `ImageFormat` / `requested_screenshot_format` / `reconcile_screenshot_path` |
| `cli/browser4-cli/src/daemon.rs` | stale-bundle policy (refuse / rebuild / opt-out), per-module staleness, build stamp, `maven.jar.forceCreation`, provenance for `status`/`doctor` |
| `cli/browser4-cli/src/help.rs`, `src/tips.rs` | user-visible wording |
| `browser4-apps/browser4-bundle/build-runtime-bundle.ps1` | `-Dmaven.jar.forceCreation=true`, build stamp |

**Tests**

| File | Change |
|---|---|
| `browser4-core/browser4-browser/src/test/.../Browser4UserAgentTest.kt` | **new** — 10 tests |
| `browser4-core/browser4-browser/src/test/.../Browser4WebDriverTest.kt` | +7 input-target tests |
| `browser4-tests/pulsar-it-tests/src/test/.../NavigatorStealthIT.kt` | **new** — 4 real-browser regression tests（4.14.x 已移至 `browser4-core/browser4-browser/src/test/.../NavigatorStealthE2ETest.kt`） |
| `cli/browser4-cli/src/main.rs` (tests) | +20 tests (wait message, in-flight counters, block signatures, probe parsing, misplaced options) |
| `cli/browser4-cli/src/commands.rs`, `src/snapshot.rs`, `src/daemon.rs` (tests) | +18 tests (marker sync, image format/reconcile, staleness policy, per-module staleness, build stamp, provenance) |
| `browser4-agentic/src/test/.../BrowserTabToolExecutorTest.kt` | fill probe-then-write order, full-page format routing |

## Verification performed

| Suite | Result |
|---|---|
| `cargo test --bin browser4-cli` | **1251 passed, 0 failed** (2 ignored) |
| `mvn -pl browser4-core/browser4-browser test -Dtest=Browser4UserAgentTest,Browser4WebDriverTest` | **90 passed, 0 failed** |
| `mvn -pl browser4-tests/pulsar-it-tests -DrunITs=true -Dtest=NavigatorStealthIT test` | **4 passed, 0 failed** (real Chrome) |
| `mvn -pl browser4-agentic -Dtest=BrowserTabToolExecutorTest test` | **46 passed, 0 failed** |
| End-to-end CLI checks (Issues 1–6, 9) | reproduced fixed behaviour on a freshly built backend |

## Not changed, and why

- **No `Emulation.setUserAgentOverride` / `Network.setUserAgentOverride`.** A launch-time
  `--user-agent` is what reaches workers and service workers while keeping the client hints
  intact; a CDP override misses those scopes and wipes `Sec-CH-UA*` unless metadata is
  supplied — which is why the library chose the launch switch in the first place.
- **No `deviceMemory` / `maxTouchPoints` spoof.** Both values are Chrome's own reading of
  this host, reproduced without Browser4; adding an override would create the very
  inconsistency the issues complain about.
- **`--format` was not added as a screenshot flag.** The output extension already is the
  request; adding a flag would need option plumbing through three layers for no new
  capability. `*.png` now yields PNG, `*.jpg`/`*.jpeg` yields JPEG, and the printed link
  always matches the bytes.
- **Element/viewport captures remain JPEG** (library behaviour, ~20× smaller for element
  shots); the CLI reconciles the file name with the bytes rather than re-encoding.

---

# Real-site re-verification (same machine and network as the original report)

Ran the five services from the report again through a headless session on the rebuilt
backend (Chrome 153.0.8010.53, Windows 11, 32 GB RAM, 20 cores).

| Service | Report (before) | Now |
|---|---|---|
| `bot.sannysoft.com` | 2/27 FAIL — `HEADCHR_UA`, `CHR_MEMORY` | **0 failed** — `HEADCHR_UA ok`, `CHR_MEMORY ok`, and no "failed" text anywhere in the 58-row table |
| `deviceandbrowserinfo.com/are_you_a_bot` | "You are a bot!" (`hasBotUserAgent: true`) | **"✅ You are human!"** — `isBot: false`, `hasBotUserAgent: false`, `isHeadlessChrome: false`, `hasWebdriverTrue: false` |
| `bot.incolumitas.com` | `intoli.userAgent` FAIL, `fpscanner.HEADCHR_UA` FAIL (+2 more legacy FAIL) | `intoli.userAgent` **OK**, `fpscanner.HEADCHR_UA` **OK**, `fpscanner.CHR_MEMORY` **OK**; one legacy FAIL remains: `fpscanner.WEBDRIVER` |
| `browserscan.net/bot-detection` | ROBOT | **"Test Results: Normal"** (Webdriver / User-Agent / CDP / Navigator all Normal) |
| `abrahamjuliot.github.io/creepjs` | 67% headless, 60% stealth | **31% like headless, 0% headless, 60% stealth**; its Resistance block still lists `extension: puppeteer-extra` |
| `httpbin.org/headers` (added) | — | request headers agree with the user agent: `User-Agent: …Chrome/153.0.0.0…`, `Sec-Ch-Ua: "Google Chrome";v="153", "Not_A Brand";v="8", "Chromium";v="153"`, `Sec-Ch-Ua-Platform: "Windows"`, `Sec-Ch-Ua-Mobile: ?0` |

## The two remaining signals, and what they are

**`fpscanner.WEBDRIVER` (incolumitas, legacy block) — not Browser4's doing.** Measured with a
plain, non-automated Chrome on the same host (`chrome.exe --headless=new --disable-gpu
--no-sandbox --dump-dom file:///probe.html`), `navigator.webdriver` reads
`{"v":"false","has":true,"ownProto":"undefined"}` — the identical triple the Browser4 session
reports. `'webdriver' in navigator` is `true` for *every* current Chrome, so the legacy
fpscanner check is stale in the same way as `CHR_MEMORY`. (SannySoft's and Intoli's own
WebDriver checks both pass: "WebDriver (New) missing (passed)", `intoli.webDriver: OK`.)

**creepjs `extension: puppeteer-extra` — attributed, and not a regression.** A raw-CDP
plain-Chrome baseline settled it. The probes live in `docs-dev/copilot/bot-stealth-probes/`
(`plain-creepjs.mjs` drives a stock Chrome with no Browser4 code, flags or page-world
injection; `page-world-probe.js` / `iframe-world-probe.js` read the page's **main world** and a
child iframe realm, since an isolated world has its own `window`).

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

So:

- Browser4 is strictly **better** than stock Chrome on creepjs' two headline scores
  (`headless` 100% → 0%, `stealth` 0% → 60%). The residual `31% like headless` is identical
  in both, i.e. it is the host plus the headless mode itself, not Browser4.
- The `puppeteer-extra` label is creepjs' nearest matching *known-extension template*, not
  evidence of puppeteer-extra: none of the members it lists is patched in either realm the
  probes can read, and `Function.prototype.toString` is untouched.
- Stock Chrome never reaches that analysis — it is already 100% "headless" and reports
  `extension: unknown`. Part of "Browser4 is flagged where plain Chrome is not" is therefore an
  artifact of Browser4 *passing* the earlier checks and being subjected to the deeper ones.
- The line predates this fix round (the `20260916-174704-Bot stealth check` report already saw
  it), so it is not a regression.
- The 40% of `stealth` that is still lost is accounted for by creepjs' own three flags —
  `hasIframeProxy`, `hasBadChromeRuntime`, `hasToStringProxy`, all `false` for plain Chrome only
  because it never gets that far. If a follow-up wants to chase them, these probes are the
  starting point.
