# Issues: Laser-Engraved Crystal

> **Source:** `20260727-234324-Laser-Engraved Crystal.full.md` | **Date:** 20260727-234324 | **Mode:** dev

## Scenario Background

### Task

**Task:** Search for "Laser-Engraved Crystal" on Amazon.com, shortlist the 10 best options suitable as a gift for a 12-year-old boy, review their detail pages, and pick the best one.

**Best Pick: YWHL 3D Solar System Crystal Ball 80mm with Light Base**

| Attribute | Detail |
|---|---|
| **URL** | https://www.amazon.com/dp/B0DPHQ818J |
| **Price** | TWD 904.64 (~$28 USD) |
| **Rating** | 4.9 out of 5 stars (25 ratings) |
| **Size** | 80mm (3.15 inches) |
| **Features** | Laser-engraved solar system, LED light base, night light, educational model |

**Why it's the best:** Space and astronomy are universally appealing to 12-year-old boys. The 80mm crystal ball contains a detailed 3D laser-engraved solar system visible inside the glass sphere. The included LED light base illuminates the engraving, creating a stunning night light. At 4.9/5 stars, it has the highest customer satisfaction among all 30 search results. The product is explicitly marketed for "teens, boys and girls." At ~$28 USD, it offers excellent value — substantial enough to feel meaningful without being overly expensive. The educational solar system model provides lasting value beyond mere decoration.

**Top 10 Shortlist (ranked):**

| # | Product | Price (TWD) | Rating | Key Appeal |
|---|---|---|---|---|
| 1 | YWHL 3D Solar System Crystal Ball 80mm | ~904 | 4.9★ | Space theme, LED, educational |
| 2 | DNA Crystal Ball with LED Base | ~904 | 4.7★ | Science/biology, LED, STEM |
| 3 | 3D Wolf Crystal Night Light | ~743 | 4.5★ | Wolf theme, LED, affordable |
| 4 | Woodvine Cosmic Spiral Crystal Ball | ~969 | 4.8★ | Galaxy/cosmic, 80mm, LED |
| 5 | 3D Lightning Cloud Crystal Ball | ~290 | 4.6★ | Weather theme, budget-friendly |
| 6 | 3D Laser Engraved Bird Crystal Ball | ~276 | 4.7★ | Nature/animal, affordable |
| 7 | 3D Crystal Statue of Liberty (LED) | ~646 | 4.4★ | Landmark, LED |
| 8 | 3D Crystal Ball Engraved Tiger (LED) | ~1,712 | 3.8★ | Tiger theme, LED, premium |
| 9 | 2.36in 3D Crystal Guardian Angel | ~419 | 4.8★ | Guardian, high-rated |
| 10 | 3D Crystal Cube Tree of Life (LED) | ~646 | 4.4★ | Nature, LED, desk decor |

---

### Execution Context

**Key Commands:**

1. `./b4w.ps1 help` — Read CLI help (discovery)
2. `./b4w.ps1 goto "https://www.amazon.com"` — Navigate to Amazon
3. `./b4w.ps1 snapshot -v 0 --stdout` — **FAILED** (PowerShell flag interception)
4. `./b4w.ps1 -- snapshot -v 0 --stdout` — **FAILED** (PowerShell parameter ambiguity)
5. `./b4w.sh snapshot -v 0 --stdout` — Success (used b4w.sh workaround)
6. `./b4w.sh fill e37666 "Laser-Engraved Crystal"` — Fill search
7. `./b4w.sh press Enter` — Submit search → search results page
8. `./b4w.sh htmlsnapshot` — Capture HTML snapshot
9. `./b4w.sh htmlsnapshot inspect` — Auto-discover patterns (limited results)
10. `./b4w.sh reload` — **FAILED** (navigated to about:blank)
11. `./b4w.sh goto "https://www.amazon.com/s?k=Laser-Engraved+Crystal"` — **FAILED** (Amazon 503/anti-bot)
12. Recovered via Amazon homepage search form
13. `./b4w.sh htmlsnapshot capture` — **FAILED** ("Nil url is not allowed")
14. Multiple iterations to get working htmlsnapshot + data extraction
15. `./b4w.sh htmlsnapshot query --sql @query.sql` — X-SQL extraction (partial success)
16. `./b4w.sh htmlsnapshot get all text` + `get all attr` — Extracted titles, URLs, prices
17. `./b4w.sh goto "https://www.amazon.com/dp/B0FCLQZDHV"` — DNA Crystal detail page
18. `./b4w.sh goto "https://www.amazon.com/dp/B0DPHQ818J"` — Solar System detail page
19. `./b4w.sh goto "https://www.amazon.com/dp/B0D9KLS8KK"` — Wolf Crystal detail page

**Important decisions:**
- Switched from `b4w.ps1` to `b4w.sh` after flag interception issues
- Abandoned `eval` approach after it returned empty results
- Used `htmlsnapshot get all` (individual queries) instead of X-SQL for correlated data extraction
- Visited only 3 of 10 detail pages due to time constraints, relying on search-result data for the remaining 7

**Workarounds required:**
- `b4w.sh` instead of `b4w.ps1` for commands with short flags
- Re-searching from homepage instead of direct URL navigation (anti-bot)
- Multiple re-attempts of `htmlsnapshot` capture after "Nil url" errors

---

---

## Issues Found (13 issues)

### Issue 1: reload command navigates to about:blank instead of refreshing

**Severity:** Critical
**Category:** Reliability

#### Reproduction

Navigate to any page, then run: ./b4w.sh reload

#### Expected Behavior

The current page should reload/refresh with its existing URL.

#### Actual Behavior

Page URL changes to 'about:blank' and all page state is lost.

#### Root Cause Analysis

The reload implementation appears to navigate to about:blank instead of calling Page.reload or the WebDriver refresh command. This may be a CDP implementation bug where the reload triggers a navigation to the blank default page.

#### Code Pointer

`PulsarWebDriver.kt or the reload command handler — needs investigation into why refresh navigates to about:blank`

#### AI Suggested Improvement

- Fix the reload command to use CDP Page.reload() or the equivalent WebDriver refresh method
- Add an integration test that verifies reload preserves the current URL
- If about:blank is sometimes expected (e.g., new tabs), add a warning to the reload output

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: b4w.ps1 PowerShell flag interception breaks snapshot -v flag

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 snapshot -v 0 --stdout

#### Expected Behavior

Snapshot with viewport 0 is captured and printed to stdout.

#### Actual Behavior

Error: Unknown command: 'snapshot-0'. Did you mean: 'snapshot'? The -v flag is consumed by PowerShell's -Verbose parameter, turning 'snapshot -v 0 --stdout' into 'snapshot 0 --stdout' which maps to 'snapshot-0'.

#### Root Cause Analysis

When b4w.ps1 is invoked from bash via pwsh, PowerShell's parameter binder intercepts short flags that match its common parameters (-v → -Verbose, -i → -InformationAction) before they reach the ValueFromRemainingArguments collection. The script's SafeArgs quoting happens too late — the param() block already consumed the flags.

#### Code Pointer

`b4w.ps1:param() block — the -v flag is interpreted by PowerShell before reaching $RemainingArgs`

#### AI Suggested Improvement

- Document the '--' passthrough as the primary workaround, but first fix the '--' approach so it actually works (currently errors with ambiguous parameter)
- Add explicit parameter aliases to prevent PowerShell from matching common CLI flags: [Alias('_v')][switch]$V, etc.
- Consider always requiring b4w.sh on Linux/macOS to avoid the PowerShell parameter-binding problem entirely
- Update the help output to show the correct invocation method for the user's platform

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: b4w.ps1 '--' passthrough separator is broken

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 -- snapshot -v 0 --stdout

#### Expected Behavior

The '--' separator should pass all subsequent arguments directly to the browser4-cli binary.

#### Actual Behavior

b4w.ps1: Parameter cannot be processed because the parameter name '' is ambiguous. Possible matches include: -Rebuild -RemainingArgs -Verbose...

#### Root Cause Analysis

The '--' token is consumed by bash before reaching PowerShell, or PowerShell's own '--' handling conflicts with the script's param() block. The script attempts to strip '--' from $RemainingArgs (line 61-68), but by that point PowerShell has already attempted to bind '--' as a parameter name, triggering the ambiguity error.

#### Code Pointer

`b4w.ps1:16-19 (param block) and line 61-68 (-- stripping logic). The -- is parsed by PowerShell as a parameter name before the script body runs.`

#### AI Suggested Improvement

- Add explicit passthrough parameter handling in the param() block: [switch]$Passthrough or override PowerShell's -- behavior
- The SKILL.md documents '--' as the solution for flag interception, but it doesn't work — this creates a dead end for users
- Fix or remove the -- documentation in SKILL.md until the passthrough is working

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: htmlsnapshot capture fails with 'Nil url is not allowed' after session state loss

**Severity:** High
**Category:** Reliability

#### Reproduction

1. Navigate to a URL, 2. Run reload (triggers about:blank bug), 3. Navigate to a new URL, 4. Run htmlsnapshot capture

#### Expected Behavior

HTML snapshot is captured for the current page.

#### Actual Behavior

ERROR: html_snapshot_capture failed: Nil url is not allowed

#### Root Cause Analysis

After the about:blank bug invalidates the session state, the backend's URL tracking for the session appears to become corrupted or null. Subsequent navigation doesn't properly restore the URL tracking, so htmlsnapshot_capture fails because it can't determine the page URL. This is a cascading failure from the reload bug.

#### Code Pointer

`MCPToolController.kt or the htmlsnapshot capture handler — the URL resolution logic should fall back to reading the current page URL from the browser if the tracked URL is nil`

#### AI Suggested Improvement

- Fix the root cause (reload → about:blank bug)
- Add defensive fallback: if tracked URL is nil, read current URL from browser via CDP Runtime.evaluate('window.location.href')
- Add a clear error message suggesting the user run 'goto' again to re-establish session state

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: eval returns empty string for document.title even after network idle

**Severity:** High
**Category:** Reliability

#### Reproduction

Navigate to any page, wait --load networkidle, then eval 'document.title' --json

#### Expected Behavior

The page title as a JSON string.

#### Actual Behavior

{"command":"eval","output":{"expression":"document.title","result":""},"status":"ok"}

#### Root Cause Analysis

The eval command may be executing JavaScript in the wrong execution context (e.g., an empty iframe, extension context, or a frame that hasn't loaded). The status is 'ok' but the result is empty, suggesting the JS ran but against the wrong target.

#### Code Pointer

`Likely in PulsarWebDriver.kt or the eval tool executor — the Runtime.evaluate call may not be targeting the main frame`

#### AI Suggested Improvement

- Ensure eval targets the main/top-level frame, not a child iframe
- Add --frame or --context option for explicit frame targeting
- The empty result should produce a warning, not silently return ''
- Add integration tests: eval 'document.title' should return the page title shown in goto output

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: htmlsnapshot summary times out on moderately large pages

**Severity:** Medium
**Category:** Reliability

#### Reproduction

Capture a 1.4MB HTML snapshot of Amazon search results, then run htmlsnapshot summary

#### Expected Behavior

A compressed Web Page Summary Index is produced.

#### Actual Behavior

Error: HTTP request timed out [tool=html_snapshot_summary, endpoint=http://localhost:8182/mcp/call-tool, timeout=60s]

#### Root Cause Analysis

The summary analysis (visual clustering) appears to be computationally expensive for pages with many elements. The 60-second HTTP timeout on the MCP endpoint is insufficient for processing a 1441KB snapshot with thousands of DOM nodes.

#### Code Pointer

`The htmlsnapshot summary backend handler — likely needs optimization or a longer timeout for large pages`

#### AI Suggested Improvement

- Increase the HTTP timeout for computationally expensive operations
- Add pagination or chunked processing for large snapshots
- Warn users when a snapshot exceeds a size threshold: 'Snapshot is 1.4MB — summary may take longer'
- Fall back to a faster/coarser analysis if the full summary is taking too long

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: htmlsnapshot inspect default search doesn't find e-commerce product patterns

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

Capture an Amazon search results page, then run htmlsnapshot inspect (default: div)

#### Expected Behavior

The tool discovers product card selectors like [data-component-type='s-search-result'].

#### Actual Behavior

The tool returns navigation shortcut elements (div.keyboard-shortcut-container, span.shortcut-key) — not useful for product extraction.

#### Root Cause Analysis

The auto-discovery analyzes the first child of :root which happens to be a navigation element on the Amazon page. The algorithm favors elements with high repetition at the top of the DOM, which on many modern e-commerce sites are navigation menus, not product grids.

#### Code Pointer

`The htmlsnapshot inspect algorithm — needs to prioritize main content area elements or try multiple root-level candidates`

#### AI Suggested Improvement

- Skip navigation elements (nav, header, footer) in the auto-discovery
- Start analysis from main content area (main, #main, [role=main]) if available
- Add a --deep flag that searches for repeating patterns at any depth
- Integrate with PowerCSS :expr() to find visually similar card-like elements (similar width/height)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: X-SQL DOM_FIRST_TEXT fails to extract product titles from Amazon search results

**Severity:** Medium
**Category:** Reliability

#### Reproduction

Run X-SQL: SELECT DOM_FIRST_TEXT(DOM, 'h2 a span') AS title FROM DOM_LOAD_AND_SELECT(@url, '[data-component-type="s-search-result"]')

#### Expected Behavior

Product titles extracted from h2 a span elements inside each search result card.

#### Actual Behavior

Title field is empty string for all results. Prices and ratings are correctly extracted.

#### Root Cause Analysis

Amazon's h2 elements inside search result cards may not contain a span child matching 'h2 a span'. The actual DOM structure may use different nesting (e.g., h2 > a > span or h2 > a with text directly). The selector 'h2 a span' doesn't match Amazon's actual markup.

#### Code Pointer

`The X-SQL DOM_FIRST_TEXT function or the CSS selector needs adjustment. Amazon product titles use a.a-link-normal.s-line-clamp-4 rather than h2 a span.`

#### AI Suggested Improvement

- Add a Debug mode for X-SQL that shows which elements matched each DOM_ function
- Improve the htmlsnapshot inspect output to suggest selectors for product titles specifically
- Document common Amazon/e-commerce selectors in the htmlsnapshot-scenarios.md reference

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: No auto-HTML-snapshot after goto — extra step required before extraction

**Severity:** Medium
**Category:** UX

#### Reproduction

Run goto to navigate, then immediately run htmlsnapshot get text 'h1'

#### Expected Behavior

The HTML snapshot is implicitly captured or the get command works against the live page.

#### Actual Behavior

ERROR: 'Nil url is not allowed'. User must explicitly run htmlsnapshot capture first.

#### Root Cause Analysis

goto auto-captures an AX-tree snapshot (for click/fill refs) but does not auto-capture an HTML snapshot (for css selector extraction). Users must always run htmlsnapshot as a separate explicit step.

#### Code Pointer

`MCPToolController.kt goto handler — consider auto-capturing an HTML snapshot alongside the AX-tree snapshot`

#### AI Suggested Improvement

- Auto-capture HTML snapshot on goto (like AX snapshot is auto-captured)
- Or make htmlsnapshot get fall back to capturing if no snapshot exists
- Document this requirement more prominently in the Quick Patterns section
- The 'Nil url is not allowed' error message should include a hint: 'Did you forget to run htmlsnapshot first?'

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 10: Amazon anti-bot detection causes silent failure when navigating to search URLs directly

**Severity:** Medium
**Category:** Product

#### Reproduction

Navigate directly to https://www.amazon.com/s?k=Laser-Engraved+Crystal in a fresh or stale session.

#### Expected Behavior

Search results page loads normally.

#### Actual Behavior

Amazon shows 'Sorry! Something went wrong!' (503-type error page). The CLI reports success with the error page URL.

#### Root Cause Analysis

Amazon's anti-bot detection flags direct navigation to search URLs without a proper session/cookie context. The homepage → search form flow establishes a valid session that direct URL navigation doesn't.

#### AI Suggested Improvement

- Add a status code check after goto and warn if the page is an error page (status != 200 or title contains 'Sorry')
- Consider adding a cookie/header preset for common e-commerce sites
- Document the homepage-search-form flow as the recommended approach for Amazon in the scenario docs

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 11: Ref lifecycle and snapshot frequency create cognitive load for simple data extraction tasks

**Severity:** Medium
**Category:** UX

#### Reproduction

As a new user, attempt to extract product data from search results: navigate → snapshot → extract.

#### Expected Behavior

Straightforward: navigate to the page, extract data.

#### Actual Behavior

User must understand: (1) AX snapshot vs HTML snapshot distinction, (2) refs are ephemeral, (3) htmlsnapshot must be explicitly captured before get/query, (4) viewport pagination for large pages, (5) interactive mode strips generic divs (bad for e-commerce). This is a lot of conceptual overhead for a simple extraction task.

#### Root Cause Analysis

The design separates page interaction (snapshot/refs) from data extraction (htmlsnapshot/css selectors) into two distinct workflows with different state management. While this separation is architecturally sound, it's not obvious to new users.

#### AI Suggested Improvement

- Implement a unified 'scrape' command that handles htmlsnapshot capture + extraction in one step
- Add a 'quick-extract' workflow that auto-discovers selectors on Amazon/e-commerce pages
- In the Core Loop (SKILL.md line 17-24), add a parallel 'Extraction Loop' showing the htmlsnapshot workflow
- The tip after 'goto' could mention: 'For data extraction, run htmlsnapshot next'

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 12: b4w.sh prints noisy 'strongly recommended' message on every invocation

**Severity:** Low
**Category:** UX

#### Reproduction

Run any ./b4w.sh command

#### Expected Behavior

Clean output without repeated warnings.

#### Actual Behavior

Every command prints: 'It is strongly recommended to launch `pwsh` and run the .ps1 commands directly within the `pwsh` terminal.' This noise accumulates across many commands.

#### Root Cause Analysis

b4w.sh line 17: hardcoded echo statement before every invocation.

#### Code Pointer

`b4w.sh:17 — remove or guard the echo statement behind an environment variable check`

#### AI Suggested Improvement

- Print the warning only on first invocation per session (track via a temp file or env var)
- Or suppress it entirely when b4w.sh is the only working option (-- passthrough is broken)
- Add a --quiet flag to b4w.sh to suppress this banner

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 13: SKILL.md documentation hard to scan for critical warnings (flag interception buried in Installation section)

**Severity:** Low
**Category:** Documentation

#### Reproduction

Read SKILL.md from top to bottom as a first-time user.

#### Expected Behavior

Critical platform-specific gotchas are prominently placed at the top of the file.

#### Actual Behavior

The PowerShell flag interception warning is buried in the Installation section at line 420-421, far from where users first encounter snapshot -v commands. Users must have already encountered the error and scrolled 400+ lines to find the workaround.

#### Code Pointer

`skills/browser4-cli/SKILL.md:420-421 — move to a prominent 'Platform Notes' section near the top`

#### AI Suggested Improvement

- Add a 'Platform Notes' or 'Before You Start' section at the top of SKILL.md (after the Core Loop)
- Include a prominent callout: 'Linux/macOS users: use b4w.sh. Windows users: use b4w.bat.'
- The Copy-Paste Template should use b4w.sh syntax when appropriate for the platform

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

**Completion Status:** Partially Successful — The task was completed (best pick identified with justification) but required significant workarounds. The reload→about:blank bug caused a cascade of failures that consumed substantial time. Only 3 of 10 detail pages were visited due to reliability issues.

**Success Rate:** 65% — Core navigation and search worked. Data extraction required multiple attempts and alternative approaches. Detail page review was cut short. The overall goal was achieved but with friction.

**Issues Found:** 13

**Major Blockers:** The reload→about:blank bug and subsequent 'Nil url is not allowed' cascade was the most significant blocker, requiring full session recovery. The b4w.ps1 flag interception (with broken -- workaround) forced use of b4w.sh which adds noise to every command. The eval command reliably returned empty results, blocking programmatic data extraction.

**Most Confusing Aspects:** 1. The distinction between AX-tree snapshots (for refs) and HTML snapshots (for CSS extraction) — not clearly communicated to new users. 2. Why htmlsnapshot get works sometimes without explicit capture but fails other times. 3. The b4w.ps1 vs b4w.sh choice: documentation says use b4w.ps1 but it doesn't work with common flags. 4. Refs being ephemeral across different command types (fill is safe, click is not) requires memorizing rules.

**Most Valuable Improvements:** 1. Fix the reload→about:blank bug (Critical reliability). 2. Fix the b4w.ps1 '--' passthrough to work reliably. 3. Auto-capture HTML snapshot on goto (reduce friction). 4. Fix eval command to work on all pages. 5. Add a unified 'scrape' workflow for e-commerce extraction. 6. Improve htmlsnapshot inspect to find product patterns on e-commerce pages.

**Usability Rating:** 5/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: reload command navigates to about:blank instead of refreshing

Navigate to any page, then run: ./b4w.sh reload

#### Issue 2: b4w.ps1 PowerShell flag interception breaks snapshot -v flag

./b4w.ps1 snapshot -v 0 --stdout

#### Issue 3: b4w.ps1 '--' passthrough separator is broken

./b4w.ps1 -- snapshot -v 0 --stdout

#### Issue 4: htmlsnapshot capture fails with 'Nil url is not allowed' after session state loss

1. Navigate to a URL, 2. Run reload (triggers about:blank bug), 3. Navigate to a new URL, 4. Run htmlsnapshot capture

#### Issue 5: eval returns empty string for document.title even after network idle

Navigate to any page, wait --load networkidle, then eval 'document.title' --json

#### Issue 6: htmlsnapshot summary times out on moderately large pages

Capture a 1.4MB HTML snapshot of Amazon search results, then run htmlsnapshot summary

#### Issue 7: htmlsnapshot inspect default search doesn't find e-commerce product patterns

Capture an Amazon search results page, then run htmlsnapshot inspect (default: div)

#### Issue 8: X-SQL DOM_FIRST_TEXT fails to extract product titles from Amazon search results

Run X-SQL: SELECT DOM_FIRST_TEXT(DOM, 'h2 a span') AS title FROM DOM_LOAD_AND_SELECT(@url, '[data-component-type="s-search-result"]')

#### Issue 9: No auto-HTML-snapshot after goto — extra step required before extraction

Run goto to navigate, then immediately run htmlsnapshot get text 'h1'

#### Issue 10: Amazon anti-bot detection causes silent failure when navigating to search URLs directly

Navigate directly to https://www.amazon.com/s?k=Laser-Engraved+Crystal in a fresh or stale session.

#### Issue 11: Ref lifecycle and snapshot frequency create cognitive load for simple data extraction tasks

As a new user, attempt to extract product data from search results: navigate → snapshot → extract.

#### Issue 12: b4w.sh prints noisy 'strongly recommended' message on every invocation

Run any ./b4w.sh command

#### Issue 13: SKILL.md documentation hard to scan for critical warnings (flag interception buried in Installation section)

Read SKILL.md from top to bottom as a first-time user.

