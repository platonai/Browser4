# Browser4-CLI Usability Evaluation — Multi-Site Weather Comparison Task

**Date:** 2026-07-10
**Evaluator:** AI agent acting as first-time user
**Task:** Query Shanghai weather from two different websites (weather.com.cn and tianqi.qq.com), extract key fields, and produce a markdown comparison table.

---

## A. Task Result

✅ **Task completed successfully.** The `shanghai-weather-compare.md` file was produced with side-by-side weather data comparison, including current temperature, weather conditions, humidity, wind, and 3-day forecasts from both weather.com.cn and tianqi.qq.com.

---

## B. Execution Trace

### Commands Used (in order)

| # | Command | Purpose | Result |
|---|---------|---------|--------|
| 1 | `--help` | Verify CLI works | ✅ Success |
| 2 | `goto "https://www.weather.com.cn/"` | Navigate to weather.com.cn | ✅ Page loaded |
| 3 | `snapshot -v 0` | Capture page structure | ✅ 350 nodes |
| 4 | `snapshot grep -i "搜索\|textbox"` | Find search box | ✅ Found e454 |
| 5 | `fill e454 "上海"` | Fill search box | ✅ Filled |
| 6 | `press Enter e454` | Submit search | ❌ No navigation (JS autocomplete) |
| 7 | `click e421` | Click search icon | ❌ No navigation |
| 8 | `goto "https://www.weather.com.cn/weather/101020100.shtml"` | 🔧 Workaround: direct URL | ✅ Shanghai page loaded |
| 9 | `snapshot -v 0` | Capture weather page | ✅ |
| 10 | `htmlsnapshot` | Capture HTML snapshot | ✅ 128 KB captured |
| 11 | `htmlsnapshot inspect` | Discover page structure | ✅ Found repeating patterns |
| 12 | `htmlsnapshot get all text ".sky"` | Extract 7-day summary | ✅ 7 entries |
| 13 | `htmlsnapshot get all text ".tem"` | Extract temperatures | ✅ 7 entries |
| 14 | `htmlsnapshot get all text ".wea"` | Extract conditions | ✅ 7 entries |
| 15 | `htmlsnapshot get all text ".win"` | Extract wind | ✅ 7 entries |
| 16 | `eval ...` (multiple) | Extract specific fields | ✅ Mixed results |
| 17 | `goto "https://tianqi.qq.com/"` | Navigate to tianqi.qq.com | ✅ Page loaded |
| 18 | `snapshot -v 0` | Capture page structure | ✅ 211 nodes |
| 19 | `snapshot grep -i "textbox\|搜索"` | Find search box | ✅ Found e53 |
| 20 | `click e567` | Click search container | ✅ |
| 21 | `snapshot -v 0 --auto-diff` | Check what changed | ❌ **PANIC**: Unicode boundary bug |
| 22 | `fill e53 "上海"` | Fill search box | ✅ Filled |
| 23 | `snapshot grep` (multiple) | Find suggestions | ✅ Found "上海, 上海" |
| 24 | `click e1395` | Click suggestion | ✅ Selected |
| 25 | `wait --load networkidle` | Wait for update | ✅ |
| 26 | `snapshot grep` (extensive) | Parse weather data from AX tree | ✅ Data found |
| 27 | `htmlsnapshot` | Capture HTML snapshot | ✅ |
| 28 | `eval ...` (multiple attempts) | Extract via DOM selectors | ⚠️ Mixed — many "not found" |
| 29 | `get text "[class*=cur-loc]"` | Try live-page extraction | ❌ No matches (AX vs DOM mismatch) |

### Major Decisions

- **Bypassed weather.com.cn search** after discovering it uses a JS autocomplete component that doesn't respond to `press Enter` or search icon clicks. Used direct URL navigation to the known Shanghai weather page (`/weather/101020100.shtml`).
- **Parsed data from accessibility-tree snapshot** for tianqi.qq.com after discovering that the AX tree's class-name annotations (`txt-temperature`, `txt-name`, `txt-humidity`) don't correspond to real CSS classes in the live DOM.
- **Used the `--auto-diff` feature** on tianqi.qq.com but hit a Unicode panic, then reverted to manual comparison.
- **Extracted weather.com.cn data via `htmlsnapshot get all text`** using `.sky`, `.tem`, `.wea`, `.win` selectors — this worked well for structured data.

### Workarounds Required

1. **Direct URL navigation** instead of interactive search on weather.com.cn (JS autocomplete prevented form submission)
2. **Manual AX tree parsing** for tianqi.qq.com data extraction (CSS selectors from AX tree didn't match live DOM)
3. **Multiple iteration** on eval commands — tried 5+ different selector strategies before finding working patterns
4. **Avoided `--auto-diff`** after encountering a Unicode panic

---

## C. Issues Found

### Issue 1: `--auto-diff` panics on Unicode character boundary

**Severity:** High

**Category:** Reliability

**Reproduction:**
```bash
cargo run --quiet --manifest-path cli/browser4-cli/Cargo.toml -- snapshot -v 0 --auto-diff
```
On a page containing multi-byte UTF-8 characters (Chinese text like "率").

**Expected:** `--auto-diff` produces a diff between the current and previous snapshot.

**Actual:** The CLI panics with:
```
thread 'main' panicked at src\snapshot_diff.rs:547:26:
end byte index 59 is not a char boundary; it is inside '率' (bytes 57..60 of string)
```

**Root Cause:** The snapshot diff implementation in `src/snapshot_diff.rs` at line 547 performs byte-level indexing on a UTF-8 string without checking character boundaries. When the byte offset lands inside a multi-byte character (common with Chinese text), Rust panics rather than handling the boundary gracefully.

**Code Pointer:** `cli/browser4-cli/src/snapshot_diff.rs:547`

**AI Suggested Improvement:**
- Use `.char_indices()` or `.floor_char_boundary()` to find safe UTF-8 boundaries before indexing
- Add a unit test with multi-byte characters (Chinese, Japanese, emoji) to prevent regression
- Consider using string slicing via `.chars()` instead of byte-index slicing

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: Interactive search on JS autocomplete sites silently fails (weather.com.cn)

**Severity:** High

**Category:** Reliability

**Reproduction:**
```bash
goto "https://www.weather.com.cn/"
snapshot -v 0                              # find search textbox
fill <search-ref> "上海"
press Enter <search-ref>
# Page URL stays the same — no navigation
```

**Expected:** Either (a) `press Enter` triggers the search and navigates to results, or (b) the CLI reports that no navigation occurred after the Enter key.

**Actual:** `press Enter` reports `✓ Pressed 'Enter' on e454` but the page URL remains unchanged because weather.com.cn uses a JavaScript autocomplete component that requires selecting from a dropdown. The success indicator is misleading.

**Root Cause:** The website's search is implemented as a client-side autocomplete widget (not a traditional HTML form). `press Enter` dispatches a key event, but the widget intercepts it for dropdown navigation rather than form submission. The CLI reports success based on event dispatch, not on observable result.

**Code Pointer:** CLI interaction result handling; backend keyboard event dispatch.

**AI Suggested Improvement:**
- After `press Enter` and similar submit-like interactions, compare the page URL and/or DOM state before/after and warn if nothing changed
- Add an `--expect-navigation` flag that verifies page state changed after an action
- Document known limitations with JS autocomplete/search widgets in SKILL.md
- Consider adding a `search` convenience command that handles common search patterns

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: AX tree class-name annotations don't match live DOM CSS classes

**Severity:** Medium

**Category:** UX / Reliability

**Reproduction:**
1. Take a `snapshot -v 0` on tianqi.qq.com
2. Observe class-like names in the AX tree: `txt-temperature`, `txt-name`, `txt-humidity`, `txt-wind`
3. Try to use those as CSS selectors with `get text`, `htmlsnapshot get text`, or `eval`:
```bash
get text ".txt-temperature"
eval "document.querySelector('.txt-temperature')?.textContent" --json
# All return "not found" or null
```

**Expected:** Class names visible in the accessibility tree snapshot correspond to usable CSS selectors in the DOM, or documentation clearly explains the distinction.

**Actual:** The AX tree annotates generic elements with what appear to be class names (e.g., `generic "ct-search ... txt-temperature 32° txt-name 阴"`), but these are internal accessibility annotations, not CSS classes. Users naturally try to use them as selectors and get silent failures.

**Root Cause:** The accessibility tree YAML format embeds implementation-level strings (like HTML class names mixed with computed text) in a way that looks like CSS selector targets. There's no clear visual distinction between what's a CSS class, an ARIA role, or an internal annotation.

**Code Pointer:** Snapshot rendering format in the CLI; accessibility tree serialization.

**AI Suggested Improvement:**
- Visually distinguish CSS classes from other annotations in snapshot output (e.g., prefix with `css:` or use different formatting)
- Add a note in the snapshot output header: "Class-like strings in the AX tree may not match DOM CSS classes. Use `htmlsnapshot` for CSS selector-based extraction."
- Provide a `css-selector-bridge` command (referenced in SKILL.md §7) that maps AX tree refs to their actual DOM CSS selectors
- Add an example in the docs showing the proper workflow for DOM-based extraction

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: AJAX-based city switching makes success/failure ambiguous (tianqi.qq.com)

**Severity:** Medium

**Category:** UX / Reliability

**Reproduction:**
```bash
goto "https://tianqi.qq.com/"
fill <search-ref> "上海"
click <suggestion-ref>
# URL remains "https://tianqi.qq.com/"
# No clear indicator that the weather data changed
```

**Expected:** After selecting a new city, the CLI provides a clear indication that the page content changed (URL change, title change, or explicit "weather data updated" confirmation).

**Actual:** tianqi.qq.com updates weather data via AJAX without changing the URL or page title. The CLI reports `✓ Clicked e1395` and `✓ Wait complete` but there's no confirmation that the city actually changed. The user must manually inspect the page content to verify.

**Root Cause:** The CLI's success reporting is based on the action (click dispatched, wait condition met) rather than on the semantic outcome (did the city actually change?). AJAX-based SPAs don't trigger traditional navigation events that the CLI can detect.

**Code Pointer:** CLI command result handling; wait condition verification.

**AI Suggested Improvement:**
- After city/location change interactions, auto-detect location-related DOM elements and report what changed
- Add a `--verify` flag (already documented for some commands but not universally available) that checks DOM state post-action
- The `--auto-diff` feature (once the unicode bug is fixed) partially addresses this — document it as the recommended approach for AJAX-heavy sites
- Consider a `wait --dom-change <selector>` mode that waits for a specific element's content to change

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: Existing session silently reconnected without user awareness

**Severity:** Low

**Category:** UX

**Reproduction:**
```bash
goto "https://www.weather.com.cn/"
# Output: "Reconnected to existing session on https://www.zhihu.com/knowledge-plan/hot-question/hot/0/hour"
```

**Expected:** The `goto` command either (a) starts a fresh session for the new URL, or (b) clearly warns that it's reusing a session with leftover state from a previous website.

**Actual:** The CLI reconnects to an existing browser session that was on a completely different website (Zhihu). The message "Reconnected to existing session on <previous-url>" is printed but easily missed, and there's no warning about potential state contamination (cookies, localStorage, etc.).

**Root Cause:** The CLI's session persistence model keeps browser sessions alive across invocations. `goto` auto-reuses the active session rather than creating a fresh one. This is a documented feature (session persistence is in the README) but the implications for cross-site state leakage are not called out.

**Code Pointer:** CLI session management; `goto` command implementation.

**AI Suggested Improvement:**
- When reconnecting to a session on a different domain, print a more prominent warning about state carryover
- Add a `--fresh` flag to `goto` that creates a new browser context
- Document cross-site state implications in the session management section of SKILL.md
- Consider defaulting to a fresh context when the target URL domain differs from the existing session

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: `snapshot grep` regex alternation note is confusing for new users

**Severity:** Low

**Category:** UX / Discoverability

**Reproduction:**
```bash
snapshot grep -i "搜索\|search\|textbox"
# Output: "Note: Converted grep-style alternation `\\|` to `|` in pattern.
# Rust regex uses bare `|` for alternation (like ERE/egrep).
# Use `snapshot grep -F` for literal matching."
```

**Expected:** Either (a) the CLI accepts grep-style `\|` without comment, or (b) the --help output documents that the regex flavor is Rust-style (ERE).

**Actual:** Every invocation with `\|` prints a multi-line note explaining the conversion. While helpful the first time, this becomes noise on subsequent uses. The message also assumes familiarity with grep dialects (ERE vs BRE), which not all users have.

**Root Cause:** The CLI tries to be helpful by auto-converting BRE-style escaped alternation to Rust/ERE-style, but the note format is verbose and uses terminology ("ERE/egrep") that may not be familiar to all users.

**Code Pointer:** CLI snapshot grep argument parsing and preprocessing.

**AI Suggested Improvement:**
- Show the conversion note only once per session (track with a flag)
- Or: add a `--help` note about the regex flavor and suppress the runtime note
- Simplify the note: "Tip: use `|` instead of `\|` for alternation in this tool"
- Support `--no-tips` to suppress these notes globally

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: `get` vs `htmlsnapshot get` distinction is hard to discover

**Severity:** Medium

**Category:** Discoverability / Documentation

**Reproduction:**
```bash
get text "[class*=cur-loc]"
# Output: "No elements matched '[class*=cur-loc]'.
#   The `get` command queries the live page through the accessibility tree —
#   CSS selectors from htmlsnapshot may not apply here.
#   For CSS selector-based extraction, capture the DOM first with `htmlsnapshot`,
#   then use `htmlsnapshot get text '[class*=cur-loc]'."
```

**Expected:** A user should easily understand when to use `get` (live AX tree) vs `htmlsnapshot get` (stored HTML DOM). The distinction should be clear from the help output and documentation.

**Actual:** The distinction is only explained in an error message (after the command already failed). The `--help` output lists both under "Element inspection" without clearly differentiating their data sources. Users naturally try `get` first (shorter command) and only learn the difference after it fails.

**Root Cause:** The CLI has two parallel extraction systems: live-page accessibility tree (`get`) and stored HTML DOM (`htmlsnapshot get`). They have similar command names, similar selector syntax, but operate on fundamentally different data representations.

**Code Pointer:** Help text for `get` and `htmlsnapshot` commands; SKILL.md §4 (Decision Trees).

**AI Suggested Improvement:**
- Add clear labeling in help output: `get` = "Live page (accessibility tree)", `htmlsnapshot get` = "Stored HTML (DOM)"
- In the SKILL.md decision tree (§4a), add a step that helps users choose: "Is the data visible in the AX tree? → Use `get`. Do you need CSS selector precision? → Use `htmlsnapshot get`."
- Consider renaming `get` to `ax-get` or `tree-get` to make the distinction explicit
- Add a `get --help` example that shows the full workflow (snapshot → get vs htmlsnapshot → htmlsnapshot get)

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: Humidity extraction from weather.com.cn is unreliable

**Severity:** Low

**Category:** Reliability

**Reproduction:**
```bash
htmlsnapshot get text ".hum"
# Returns empty — no `.hum` elements in the HTML snapshot
eval "document.querySelector('.hum')?.textContent"
# Returns null
eval "document.querySelector('[class*=hum]')?.textContent"
# Returns unrelated text about AQI
```

**Expected:** A consistent way to extract humidity data from weather pages. At minimum, the `htmlsnapshot inspect` output should reveal a reliable selector for humidity.

**Actual:** The `.hum` CSS class that might contain humidity data doesn't exist in weather.com.cn's DOM. The humidity data ("过去24小时最大相对湿度: 90%") was found through a lucky eval query but the selector wasn't reproducible across calls. The `htmlsnapshot get all text ".tem"` returned an unexpected humidity-like entry as element 9 of the temperature array.

**Root Cause:** weather.com.cn embeds humidity as an inline text element without a consistently queryable CSS class. The data exists in the page but isn't structurally marked up in a way that's easy to target with CSS selectors.

**Code Pointer:** N/A — this is a website structure issue, not a CLI bug.

**AI Suggested Improvement:**
- Document in SKILL.md that some data points may require `eval` with JavaScript text traversal when CSS selectors are unavailable
- Add a "resilient extraction" pattern in the docs showing how to use parent-container text search as a fallback
- The `extract` (AI-powered) command could handle these cases — document it as the recommended approach for irregularly-structured pages

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 9: No built-in multi-site comparison or tab management convenience

**Severity:** Low

**Category:** UX / Discoverability

**Reproduction:** To compare data from two websites, the user must manually:
1. Navigate to site A, extract data
2. Navigate to site B (losing site A's state), extract data
3. Manually compile the comparison

**Expected:** Tab support and state persistence should make multi-site workflows straightforward. The `tab-new` command exists but isn't prominently featured in the "Quick Patterns" section.

**Actual:** The CLI has `tab-new`, `tab-select`, and `tab-list` commands that would allow both sites to be open simultaneously (one per tab). However, the SKILL.md "Quick Patterns" don't include a multi-tab workflow example, and `htmlsnapshot` appears to be tied to the current tab rather than tab-specific.

**Root Cause:** The tab management commands are documented in the command reference but not integrated into the common workflow patterns in SKILL.md.

**Code Pointer:** Documentation: SKILL.md §6 (Quick Patterns).

**AI Suggested Improvement:**
- Add a "Multi-Site Comparison" pattern to SKILL.md §6 showing tab-based workflow
- Clarify in docs whether `htmlsnapshot` captures are per-tab or session-wide
- Consider a `compare` convenience command: `browser4-cli compare <url1> <url2> --extract "<selector>"`

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 10: `eval` with `--json` on Windows returns empty results for complex objects

**Severity:** Medium

**Category:** Reliability / UX

**Reproduction:**
```bash
eval "JSON.stringify({...complex object with nested queries...})" --json
# Returns: null
```
or returns empty results when JavaScript expressions are complex or contain errors.

**Expected:** JavaScript errors should be reported clearly. `--json` should wrap all results, including errors, in valid JSON.

**Actual:** Complex `eval` expressions that encounter DOM query failures (null references, undefined properties) return `null` or empty results without indicating which part of the expression failed. Debugging requires iterative simplification of the expression.

**Root Cause:** JavaScript errors in `eval` are caught but the error reporting doesn't distinguish between "expression returned null" and "expression threw an error". Combined with the Windows shell quoting issue (documented in SKILL.md §5), complex one-liners are fragile.

**Code Pointer:** CLI eval command error handling; backend JavaScript execution.

**AI Suggested Improvement:**
- Report JavaScript errors distinctly from null/undefined returns
- Add `--debug` flag to eval that prints the full error stack trace
- Encourage `--file` usage for complex expressions — add a "Complex eval" pattern to Quick Patterns
- Consider a `--safe` mode that wraps the expression in try/catch and always returns structured error info

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 11: `<command> --help` output is missing for some subcommands (regression check)

**Severity:** Low

**Category:** Discoverability

**Reproduction:**
```bash
snapshot grep --help
# Works, shows grep flags
get --help
# Shows basic help
fill --help
# Works
```

**Expected:** Every command and subcommand supports `--help` with detailed option listings and examples.

**Actual:** Basic help works for most commands, but the `--help` output is inconsistent in formatting and depth. Some commands show all flags with descriptions; others show only a one-line summary. There's no `EXAMPLES` section in any `--help` output (examples are only in README.md).

**Root Cause:** The CLI help text is generated from the command definitions in the Rust source. Some commands have more detailed docstrings than others.

**Code Pointer:** `cli/browser4-cli/src/` — command definition docstrings.

**AI Suggested Improvement:**
- Standardize `--help` output format across all commands: USAGE → OPTIONS → EXAMPLES
- Add at least one practical example to each command's --help output
- Consider generating help examples from the README.md to keep them in sync

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## D. Overall Assessment

### Task Completion Status
✅ **Task completed.** The `shanghai-weather-compare.md` file was produced with weather data comparison from both sites. However, some fields (humidity, exact temperature values on tianqi.qq.com) could not be reliably extracted via CSS selectors and required AX tree parsing.

### Estimated Task Success Rate
**75%** — The core comparison was produced, but extraction reliability was inconsistent. 2 out of 8 requested data points (humidity from weather.com.cn, temperature numbers from tianqi.qq.com) required workarounds or could not be precisely extracted.

### Number of Issues Found
**11 issues** — 2 High, 5 Medium, 4 Low severity.

### Major Blockers
1. **`--auto-diff` Unicode panic (Issue #1)** — prevents using the change-detection feature on Chinese-language websites
2. **JS autocomplete search silently failing (Issue #2)** — same issue as previous evaluation, now on a different site
3. **AX tree class names not matching DOM (Issue #3)** — makes extraction confusing and error-prone, especially on complex SPAs

### Most Confusing Aspects
1. **AX tree vs DOM duality** — the CLI has two parallel extraction systems (`get` vs `htmlsnapshot get`) that look similar but operate on different data representations. The AX tree shows class-like annotations that aren't real CSS classes.
2. **Search interaction inconsistency** — some sites respond to `fill` + `press Enter`, others need clicking dropdown suggestions, others need direct URL navigation. No way to predict which pattern will work.
3. **Silent failures** — commands often report success even when the semantic action didn't complete (JS search didn't navigate, city didn't change).

### Most Valuable Improvements
1. **Fix the `--auto-diff` Unicode panic** — blocking feature for any Chinese/Japanese/Korean/emoji content
2. **Add a `search` convenience command** — single biggest UX win (same finding as previous evaluation)
3. **Visually distinguish AX tree annotations from CSS classes** in snapshot output
4. **Improve post-action verification** — warn when URL/DOM doesn't change after submit-like actions
5. **Add a "Complex eval" pattern to Quick Patterns** — showing `--file` usage for multi-line JS

### Overall Usability Rating: **6.0 / 10**

**Strengths:**
- `goto` with auto-session management works smoothly
- `htmlsnapshot get all text` with CSS selectors is powerful when selectors exist
- `snapshot grep` is excellent for element discovery
- The `.sky` compound selector on weather.com.cn returned perfectly formatted 7-day summaries
- Auto-daemon/backend startup worked without issues
- Documentation (SKILL.md + README.md) is comprehensive

**Weaknesses:**
- Unicode handling bug crashes `--auto-diff` on Chinese content
- Extraction reliability on AJAX-heavy sites is poor
- Two parallel extraction systems (`get` vs `htmlsnapshot get`) create confusion
- No post-action verification for JS-driven interactions
- Search/convenience commands still missing (same gap as previous evaluation)

**Bottom line:** browser4-cli handles traditional server-rendered pages (weather.com.cn) well — CSS selectors worked, data extraction was clean. But on AJAX-driven SPAs (tianqi.qq.com), extraction becomes significantly harder due to the AX/DOM mismatch and lack of post-action state verification. The Unicode panic is a critical reliability issue for any non-ASCII content. The tool is usable but requires persistence and workaround knowledge for real-world tasks.
