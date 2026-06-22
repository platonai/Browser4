# Browser4-CLI Usability Evaluation Report

**Date:** 2026-06-22  
**Evaluator:** First-time user perspective  
**Task:** Search Amazon for "pens to draw on whiteboards" and compare the first 4 results  
**Task Status:** ✅ Completed (with workarounds)

---

## A. Task Result

The task was completed successfully. A markdown comparison file was written to `whiteboard-pens-comparison.md` containing detailed analysis of the top 4 search results (prices, ratings, popularity, features, per-unit cost, and recommendations). The direct URL navigation workaround was needed because form interaction (focus + type + submit) did not work as expected.

---

## B. Execution Trace

### Commands Used

| # | Command | Result |
|---|---|---|
| 1 | `browser4-cli help` | Full command listing displayed |
| 2 | `browser4-cli list` | Backend not running — informative output |
| 3 | `browser4-cli goto https://www.amazon.com/` | Backend auto-started; page loaded successfully |
| 4 | `browser4-cli snapshot` | Snapshot captured |
| 5 | `browser4-cli type "pens to draw on whiteboards"` | Succeeded but did NOT type into search box |
| 6 | `browser4-cli press Enter` | Executed but no search triggered |
| 7 | `browser4-cli click e39` | **FAILED** — HTTP timeout after 30s |
| 8 | `browser4-cli reload` | Page reloaded successfully |
| 9 | `browser4-cli fill e39 "pens to draw on whiteboards"` | Succeeded but ref was stale |
| 10 | `browser4-cli press Enter e39` | Succeeded but no search triggered |
| 11 | `browser4-cli goto "https://www.amazon.com/s?k=pens+to+draw+on+whiteboards"` | Workaround — search results loaded |
| 12 | `browser4-cli close` | Session closed cleanly |

### Major Steps

1. Learned commands from `help` and `SKILL.md`
2. Verified session state with `list`
3. Navigated to Amazon with `goto` (auto-started backend)
4. Attempted search via form interaction — failed
5. Attempted click on search box — timed out
6. Switched to direct URL navigation as workaround
7. Read snapshot YAML files to extract product data
8. Compiled comparison manually from snapshot data
9. Wrote markdown comparison file

### Workarounds Required

- **Search form interaction broken:** Had to navigate directly to the search URL (`/s?k=...`) instead of typing into the search box and submitting.
- **Ref staleness:** Refs changed between page reloads; had to re-read snapshots.
- **Data extraction:** Manually parsed YAML snapshot files rather than using structured extraction commands.

---

## C. Issues Found

---

### Issue 1: Form Interaction — `type` Command Does Not Target Elements Reliably

**Severity:** High  
**Category:** Reliability / UX  

**Reproduction Steps:**
1. Navigate to `https://www.amazon.com/`
2. Run `browser4-cli type "search query"`
3. Run `browser4-cli press Enter`
4. Observe that the search does not execute

**Expected Behavior:**  
`type` should focus the default input element on the page, or clearly indicate which element it is typing into. Alternatively, `type` with a ref should type into that specific element.

**Actual Behavior:**  
`type` appeared to succeed (no error, snapshot returned) but did not visibly type into the Amazon search box. `press Enter` also succeeded but did not trigger search. No warning was given about the typed text going nowhere.

**Suggested Improvement:**  
1. `type` without a ref should emit a warning like "No focused element found; text was not typed."
2. Add a `--focus` flag to `type` that clicks the target ref before typing.
3. Document that `type` requires a ref for reliable targeting.

---

### Issue 2: `click` Command Times Out on Some Elements

**Severity:** High  
**Category:** Reliability  

**Reproduction Steps:**
1. Navigate to `https://www.amazon.com/`
2. Take a snapshot to find the search box ref (e39)
3. Run `browser4-cli click e39`
4. Wait 30 seconds

**Expected Behavior:**  
Click succeeds within a reasonable time (<5 seconds).

**Actual Behavior:**  
`Error: HTTP request timed out [tool=browser_click, endpoint=http://localhost:8182/mcp/call-tool, timeout=30s, sessionId=DEFAULT]`

**Suggested Improvement:**  
1. Investigate why clicks on standard `<input type="search">` elements timeout.
2. Implement retry with fallback (e.g., try JS click if CDP click fails).
3. Reduce default timeout with a clearer error message.

---

### Issue 3: Stale Element Refs After Reload — No Warning

**Severity:** Medium  
**Category:** UX / Discoverability  

**Reproduction Steps:**
1. Take snapshot — note ref e39 for search box
2. `browser4-cli reload`
3. `browser4-cli fill e39 "text"` — command succeeds
4. The ref e39 no longer points to the search box (new refs assigned)

**Expected Behavior:**  
Either the command fails with a "stale ref" error, or the snapshot output includes ref validation information.

**Actual Behavior:**  
`fill e39 "text"` succeeded silently, suggesting the ref was still valid. But the search box had been re-assigned to e5461, so the fill likely targeted a different (or non-existent) element.

**Suggested Improvement:**  
1. Validate refs before executing commands and error clearly if stale.
2. Include ref stability metadata in snapshot output (e.g., "Refs valid until page navigation or reload").
3. Add a `--validate` flag to check ref freshness before acting.

---

### Issue 4: No Built-in Search Workflow

**Severity:** Medium  
**Category:** Discoverability / Documentation  

**Reproduction Steps:**
1. Read `help` output and `SKILL.md`
2. Look for guidance on how to perform a web search (focus field → type → submit)

**Expected Behavior:**  
A clear, documented pattern for search interaction: "To search on a website, take a snapshot, click the search box ref, type your query, then press Enter on the search box or click the search button."

**Actual Behavior:**  
The documentation shows `type`, `fill`, and `press` individually but never connects them into a search workflow. The "Example: Form submission" in SKILL.md shows a login form but not a search form.

**Suggested Improvement:**  
1. Add a "Search" example to the SKILL.md Quick Start section.
2. Add a high-level `browser4-cli search "<query>"` command that auto-detects search boxes and performs the full workflow.
3. Include a search workflow in the examples section.

---

### Issue 5: Snapshot Refs Not Shown Inline in CLI Output

**Severity:** Low  
**Category:** UX / Efficiency  

**Reproduction Steps:**
1. Run any interactive command (`goto`, `click`, `fill`)
2. Observe that output shows `[Snapshot](path-to-file)` but not the refs

**Expected Behavior:**  
Key interactive refs (search boxes, buttons, links) shown inline in CLI output for quick chaining, with the full snapshot file for detailed inspection.

**Actual Behavior:**  
Must read the snapshot YAML file separately to discover element refs. This adds a manual step between every interaction.

**Suggested Improvement:**  
1. Add `--show-refs` flag to display top-level interactive elements inline.
2. Auto-detect and display common patterns: "Search box: e39, Search button: e340".
3. Consider a compact inline snapshot mode with just interactive elements.

---

### Issue 6: `cargo run -- help` Instructions Are Misleading

**Severity:** Medium  
**Category:** Documentation  

**Reproduction Steps:**
1. Follow the evaluation instructions: "Run `cargo run -- help`"
2. Observe error: `could not find Cargo.toml`

**Expected Behavior:**  
Instructions should reference the actual CLI tool: `browser4-cli help`.

**Actual Behavior:**  
`cargo run` fails because browser4-cli is not a Rust/Cargo project (it uses npm for CLI distribution and Java for the backend).

**Suggested Improvement:**  
Update evaluation instructions to reference `browser4-cli help` instead of `cargo run -- help`. This is a meta-issue with the evaluation framework, not browser4-cli itself.

---

### Issue 7: Backend Auto-start is Excellent but Undocumented Latency

**Severity:** Low  
**Category:** Documentation / UX  

**Reproduction Steps:**
1. Run `browser4-cli goto <url>` when backend is not running

**Expected Behavior:**  
Clear indication of what's happening and expected wait time.

**Actual Behavior:**  
The auto-start worked perfectly (great UX!) but took ~30 seconds with progress updates every 10 seconds. A first-time user might think it's stuck. The countdown format ("7s/120s") is good but the reason for the delay isn't explained.

**Suggested Improvement:**  
1. Add a brief message: "Starting Java backend (first launch may take 30-60s)..."
2. Explain the 120s timeout: "Giving the server up to 2 minutes to start."
3. Consider showing the startup log tail on timeout for debugging.

---

### Issue 8: Search Results Show HKD Pricing (Regional Mismatch)

**Severity:** Low  
**Category:** UX  

**Reproduction Steps:**
1. Navigate to `amazon.com` (US site)
2. Search for products

**Expected Behavior:**  
Prices shown in USD on amazon.com.

**Actual Behavior:**  
Prices shown in HKD (Hong Kong Dollars) because the session's delivery address was set to South Korea, and the site auto-detected Chinese language preference. This is an Amazon behavior, not a browser4-cli issue, but it could confuse users comparing prices.

**Suggested Improvement:**  
Document that browser4-cli uses a clean session with no cookies/localStorage by default, so Amazon may show regional pricing based on IP geolocation. Mention `state-load` for restoring saved preferences.

---

### Issue 9: No `--json` Output Used for Structured Data

**Severity:** Low  
**Category:** Discoverability  

**Reproduction Steps:**
1. Notice `--json` in global options
2. Try to use it with `snapshot` or `get text`

**Expected Behavior:**  
`--json` flag produces machine-parseable output for snapshots and get commands.

**Actual Behavior:**  
The `--json` flag is listed in help but its interaction with specific commands is not documented. I didn't use it because I wasn't sure which commands supported it.

**Suggested Improvement:**  
1. Document which commands support `--json`.
2. Add examples of `snapshot --json` and `get text e5 --json` in SKILL.md.
3. Show a sample JSON output format.

---

## D. Overall Assessment

### Task Completion Status
✅ **Completed** — The comparison markdown file was produced successfully, but required the direct URL navigation workaround to bypass broken form interaction.

### Estimated Task Success Rate
**60%** — A new user following the documented workflow (snapshot → click → type → press Enter) would likely fail at the search step. The direct URL workaround is discoverable but not obvious.

### Number of Issues Found: 9
- High: 2 (form interaction reliability, click timeout)
- Medium: 3 (stale refs no warning, no search workflow docs, misleading cargo instruction)
- Low: 4 (no inline refs, backend latency docs, regional pricing, JSON output docs)

### Major Blockers
1. **Form interaction is unreliable** — the `type` + `press Enter` pattern didn't work on Amazon's search form
2. **`click` command timeout** on certain elements is a hard failure

### Most Confusing Aspects
1. The distinction between `type`, `fill`, and when to use each. The docs show both but don't explain the differences clearly.
2. Refs changing between page loads without warning — the commands succeed silently even with stale refs.
3. The disconnect between `type` (which needs a focused element) and the lack of a `focus` command or automatic focus-on-type behavior.

### Most Valuable Improvements
1. **Auto-focus on type:** When `type` is used without a ref, auto-click the first visible input/search element
2. **Inline ref display:** Show key interactive refs in CLI output alongside the snapshot path
3. **Search command:** A high-level `search "<query>"` that auto-finds search box, types, and submits
4. **Stale ref detection:** Validate refs before executing and give clear errors

### What Worked Well
1. **Backend auto-start:** `goto` seamlessly started the backend, created a session, and navigated — outstanding UX
2. **Auto-snapshots:** Every command returns a snapshot, making chaining easy
3. **Snapshot YAML format:** Clear, well-structured accessibility tree
4. **Direct URL navigation:** Reliable, fast, well-documented
5. **Session management:** `list`, `close` worked perfectly
6. **Documentation structure:** SKILL.md is comprehensive with clear command tables and examples
7. **Global installation:** `npm install -g browser4-cli` + `browser4-cli install` worked without issues

### Overall Usability Rating: **6.5 / 10**

**Strengths:** Excellent auto-setup, good documentation structure, clean snapshot format, powerful command set.

**Weaknesses:** Core interaction patterns (form typing, clicking) unreliable on real-world sites; ref management is fragile; search/discovery of correct workflow requires trial-and-error.

**Verdict:** browser4-cli is a capable tool with thoughtful automation (auto-start, auto-snapshots) but needs reliability improvements for the basic type-click-submit loop that is the foundation of web interaction. With fixes to form interaction and better inline feedback, it could be a 8-9/10 tool.
