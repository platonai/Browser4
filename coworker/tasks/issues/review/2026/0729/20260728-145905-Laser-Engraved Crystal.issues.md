# Issues: Laser-Engraved Crystal

> **Source:** `20260728-145905-Laser-Engraved Crystal.full.md` | **Date:** 20260728-145905 | **Mode:** dev

## Scenario Background

### Task

**Search completed, 10 products shortlisted, winner selected.**

I searched Amazon.com for "Laser-Engraved Crystal", extracted 30 search results using browser4-cli's `htmlsnapshot get all` commands, filtered out blank DIY blocks and adult/romantic items, shortlisted 10 products suitable for a 12-year-old boy, visited 8 detail pages to review features/ratings/prices, and picked the winner.

**Winner: YWHL 3D Solar System Crystal Ball 80mm with Light Base**
- Price: JPY 4,586 (~$31 USD)
- Rating: 4.9 / 5 stars (highest among all candidates)
- URL: `https://www.amazon.com/YWHL-Crystal-Engraved-Universe-Astronomy/dp/B0DPHQ818J/`
- Key reasons: Space/astronomy theme perfectly suits a 12-year-old boy; 80mm is larger than most competitors; 6 LED color modes with gift box; explicitly marketed for "teens, boys and girls"; K9 crystal that never fades; dual USB/battery power.

---

### Execution Context

**Key Commands:**

1. `./b4w.ps1 help` — learned available commands
2. `./b4w.ps1 goto "https://www.amazon.com"` — navigated to Amazon
3. `./b4w.ps1 snapshot -v 0 --stdout` — captured accessibility tree (viewport 0)
4. `./b4w.ps1 snapshot grep -i "search"` — found search box ref (e292)
5. `./b4w.ps1 fill e292 "Laser-Engraved Crystal"` — typed search query (first attempt failed with stale ref, re-snapshot fixed it)
6. `./b4w.ps1 press Enter` — submitted search
7. `./b4w.ps1 htmlsnapshot` — captured HTML snapshot of results page
8. `./b4w.ps1 htmlsnapshot inspect` — attempted CSS selector discovery (picked wrong pattern)
9. `./b4w.ps1 htmlsnapshot get all text "a.a-link-normal.s-line-clamp-4"` — extracted 30 product titles
10. `./b4w.ps1 htmlsnapshot get all text ".a-price .a-offscreen"` — extracted 30 prices
11. `./b4w.ps1 htmlsnapshot get all attr "a.a-link-normal.s-line-clamp-4" href` — extracted 30 product URLs
12. `./b4w.ps1 goto <product-url>` (8 times) — visited detail pages for DNA, Solar System, Wolf, Shark, Half Moon, Lightning Cloud, Dolphin, Owl
13. `./b4w.ps1 htmlsnapshot get text <selector>` (repeated) — extracted price, rating, features from each detail page

**Major steps:**
1. Navigation → Snapshot → Search interaction (fill + press)
2. HTML snapshot capture → Multi-field extraction (title, price, URL)
3. Manual filtering of 30 results to 10 suitable candidates
4. Detail page visits for 8 products to review features and ratings
5. Comparison and selection of winner

**Workarounds required:**
- Ref staleness after `snapshot grep`: Had to re-run `goto` and `snapshot grep` to get fresh refs before `fill`
- `htmlsnapshot inspect` auto-discovery found the wrong pattern (`div#a-page` instead of product cards). Used manual CSS selector discovery from the interactive elements list instead
- Correlating titles, prices, and URLs required manual index-matching across three separate `get all` calls (the documented limitation of unaligned arrays)

---

---

## Issues Found (8 issues)

### Issue 1: Short flag warnings create noise on every command

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 snapshot -v 0 --stdout
./b4w.ps1 snapshot grep -i "search"

#### Expected Behavior

Clean output with only the command result. Short flags like -v and -i should work silently.

#### Actual Behavior

Every command using -v or -i prints a prominent warning block: '⚠ Short flags detected: -v / PowerShell may intercept these in other contexts... Prefer long-form equivalents...' This adds 4-5 lines of noise before every command output.

#### Root Cause Analysis

The b4w.ps1 wrapper has been updated to handle short flags via manual $args parsing, but the warning is still emitted unconditionally. The warning was added as a safety measure but hasn't been removed even though the underlying parameter-binding issue was fixed. This creates a worse experience: the issue is fixed but the scary warning remains.

#### Code Pointer

`b4w.ps1 — the short-flag detection and warning block`

#### AI Suggested Improvement

- Remove the short-flag warning now that manual $args parsing handles the parameter-binding issue
- If keeping the warning, show it only once per session or only when a flag conflict actually occurs
- The help output and SKILL.md examples still use -v 0 — if short flags are discouraged, update documentation to use --viewport consistently

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
Duplicate of Calabi-Yau #9 and amazon #5. The b4w.ps1 wrapper now handles short flags safely via manual `$args` parsing (no `param()` block), but the educational warning fires unconditionally. Fix: show at most once per session (env var), or update SKILL.md examples to use `--viewport` instead of `-v` as the primary documented form. Consolidate with the other short-flag issues.

**Severity:** Medium
**Category:** Reliability

#### Reproduction

1. ./b4w.ps1 goto "https://www.amazon.com"
2. ./b4w.ps1 snapshot -v 0 --stdout
3. ./b4w.ps1 snapshot grep -i "search"
4. ./b4w.ps1 fill e292 "Laser-Engraved Crystal"  # fails with 'element not found'

#### Expected Behavior

snapshot grep is a read-only search of the last snapshot file. It should not invalidate element refs. Fill should succeed using refs from the most recent snapshot.

#### Actual Behavior

fill failed with 'browser_type failed: fill failed: element not found or driver unavailable'. The ref e292 (which was clearly valid in the snapshot) was rejected. Re-running goto + snapshot grep + fill with the new ref fixed it, but this wastes time and is confusing.

#### Root Cause Analysis

snapshot grep may be triggering a re-snapshot internally (generating new backend node IDs) rather than purely searching the existing snapshot file on disk. Alternatively, the fill command may be resolving refs against a different snapshot than expected. Investigation needed: check whether snapshot grep calls the CDP snapshot API or reads from the cached YAML file.

#### Code Pointer

`cli/browser4-cli/src/ — the snapshot grep implementation; needs investigation into whether it triggers a new CDP snapshot`

#### AI Suggested Improvement

- snapshot grep should be truly read-only — search the last cached snapshot file without touching the browser
- If a re-snapshot is unavoidable, document it clearly in the 'Unsafe' category of the ref lifecycle table
- Better yet: separate 'disk search' (snapshot grep on cached file) from 'live search' (re-snapshot + search) into distinct commands

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
  **CONFIRMED BUG.** Code investigation shows `snapshot grep` calls `browser_snapshot` via HTTP → daemon → CDP, taking a fresh live snapshot every time. It does NOT search the cached YAML file on disk. This means: (1) it's slower than necessary, (2) it can generate different backend node IDs if the DOM changed, invalidating refs from the previous snapshot, (3) users reasonably expect a "grep" to be read-only. Fix: search the last cached snapshot YAML file on disk instead of taking a new CDP snapshot. Code pointer: `cli/browser4-cli/src/main.rs` line 4294 `handle_snapshot_grep` → `call_tool("browser_snapshot", ...)`.

---

### Issue 3: htmlsnapshot inspect auto-discovery finds wrong repeating pattern

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

./b4w.ps1 htmlsnapshot inspect
# On Amazon search results page

#### Expected Behavior

Auto-discovery should identify product card containers (e.g., div[data-component-type='s-search-result']) as the repeating pattern and suggest CSS selectors for titles, prices, ratings, and images within each card.

#### Actual Behavior

Auto-discovery picked 'div' as the repeating pattern with 'div#a-page' as the root container. The suggested selectors were for navigation shortcut elements (span.shortcut-key, div.shortcut-keys-container) rather than product data. No product-related selectors were suggested.

#### Root Cause Analysis

The inspect algorithm analyzes the first N elements of a broad selector (div) and looks for structural repetition. On Amazon, the #a-page container has deeply nested navigation elements that repeat before the product cards. The algorithm likely needs to exclude navigation/header/footer regions or use visual clustering (like htmlsnapshot summary does) to find the content area.

#### Code Pointer

`browser4-rest/ — htmlsnapshot inspect implementation (likely in the HTML snapshot analysis service)`

#### AI Suggested Improvement

- Prioritize content-area analysis over navigation — exclude elements in <nav>, <header>, <footer> regions
- Use the same visual clustering approach as htmlsnapshot summary to identify the main content grid
- If auto-discovery confidence is low, print a hint suggesting 'htmlsnapshot summary' as an alternative
- Consider analyzing elements by their CSS class patterns (e.g., common prefixes like 's-result-item') to find product cards

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
Same core need as Calabi-Yau #6 (DEFER) and amazon #1 (ACCEPT with improvements). The SKILL.md already documents X-SQL via `DOM_LOAD_AND_SELECT` for correlated extraction, but the learning curve is steep. Prefer the lower-risk approach from amazon #1: provide preset X-SQL templates (amazon-search, ebay-search) + prominently document the interactive-elements-list-as-selector-discovery pattern. A magic `extract-products` command with heuristics would be brittle.

---

### Issue 4: htmlsnapshot get all produces unaligned arrays — no built-in correlation

**Severity:** Medium
**Category:** UX

#### Reproduction

1. htmlsnapshot get all text '.title' → [30 titles]
2. htmlsnapshot get all text '.price' → [30 prices]
3. htmlsnapshot get all attr 'a' href → [30 URLs]
# Arrays may be different lengths or different order

#### Expected Behavior

A way to extract correlated fields (title, price, URL per item) in a single command without writing X-SQL.

#### Actual Behavior

The SKILL.md warns: 'Multiple get all calls produce unaligned arrays (different lengths, different order). For correlated fields, use query with DOM_LOAD_AND_SELECT.' This is documented but X-SQL requires knowing the parent container selector, which htmlsnapshot inspect failed to discover.

#### Root Cause Analysis

get all uses querySelectorAll which returns elements in document order. Different selectors (.title, .price) may return different numbers of elements because not every card has every field. There's no built-in 'extract these N fields for each match of this container' shortcut.

#### AI Suggested Improvement

- Add a 'htmlsnapshot get all table' or 'htmlsnapshot extract' command that takes a container selector + child field selectors and returns aligned rows
- Or enhance htmlsnapshot inspect to output a ready-to-use X-SQL template when it finds a repeating pattern
- The current workflow (inspect → manually write X-SQL → test) has too many steps for simple extraction tasks

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
Duplicate of Calabi-Yau #8. The `--file` approach is already the documented escape hatch. ACCEPT the documentation improvement: add a prominent "@file is the safest cross-platform pattern" recommendation at the top of the extraction section. The `eval --json` suggestion from Calabi-Yau #8 is also worth considering.

---

### Issue 5: Shell quoting fragility for complex selectors and --sql on bash

**Severity:** Low
**Category:** Documentation

#### Reproduction

Any command with CSS selectors containing special characters, e.g.:
./b4w.ps1 htmlsnapshot get all attr "a[href]" href

#### Expected Behavior

Clear documentation showing exact quoting patterns for each shell (bash, PowerShell, cmd).

#### Actual Behavior

The SKILL.md has extensive warnings about Windows shell quoting but the guidance for bash/Linux is less prominent. The @file approach (--sql @query.sql) is documented but not emphasized as the default recommendation for all platforms.

#### Root Cause Analysis

Documentation was written primarily with Windows/PowerShell pain points in mind. Bash users need less quoting but the @file approach is still the safest cross-platform pattern.

#### AI Suggested Improvement

- Add a prominent 'Always use --sql @file.sql or --file for complex selectors' recommendation at the top of the extraction section
- Include bash-specific examples alongside the Windows ones
- The decision tree in §4a could recommend @file as the default for multi-field queries

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
The browser session inherits IP-based geolocation from the host. Adding `--locale`/`--country` flags to `goto` is a feature request for CDP geolocation override, not a bug fix. DEFER for now. Workaround: document how to use `cookie-set` or navigate to Amazon's delivery address page to change locale.

---

### Issue 6: No obvious way to switch Amazon locale/currency from the CLI

**Severity:** Low
**Category:** Product

#### Reproduction

Run goto on amazon.com — session was geo-located to Japan, showing JPY prices and 'Deliver to Japan'.

#### Expected Behavior

A documented way to set delivery country or currency preference, or at least a warning that session locale may differ from the requested URL.

#### Actual Behavior

Prices appeared in JPY. For a US-focused shopping task, this required mental USD conversion. The Amazon 'Deliver to Japan' banner was visible but changing it requires interacting with Amazon's delivery address UI, which is complex.

#### Root Cause Analysis

The browser session inherits the IP geolocation and any existing cookies. The session had visited Wikipedia earlier and Amazon detected a Japan IP/location. There's no browser4-cli command to set geolocation or override Accept-Language headers.

#### AI Suggested Improvement

- Consider adding a --locale or --country flag to goto/open for setting geolocation/headers
- Document how to use cookie-set or state-load to persist delivery preferences
- Add a tip when prices appear in non-USD currency on .com domains

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
Amazon wraps sponsored links in `/sspa/click?...` tracking redirects. The tool correctly extracts what's in the DOM — this is Amazon's behavior, not a tool defect. Document the URL pattern difference (clean `/dp/ASIN` for organic vs `/sspa/click?...` for sponsored) in SKILL.md.

---

### Issue 7: Clicking sponsored product links could trigger redirect tracking URLs

**Severity:** Low
**Category:** UX

#### Reproduction

Extracting href from sponsored product links returned long sspa/click redirect URLs instead of clean /dp/ paths.

#### Expected Behavior

Either clean /dp/ASIN URLs or a note that some links are sponsored redirects.

#### Actual Behavior

Sponsored product href attributes contained /sspa/click?... redirect URLs with encoded /dp/ paths inside. These are harder to parse and navigate to directly. Non-sponsored links had clean /Cloudray-Crystal-.../dp/B0G7BQ3Q72/ paths.

#### Root Cause Analysis

Amazon wraps sponsored result links in a click-tracking redirect. The actual product path is URL-encoded inside the 'url' parameter.

#### AI Suggested Improvement

- Document that sponsored results may have redirect URLs and show how to extract the clean ASIN
- Consider adding a --follow-redirects or --resolve-sponsored flag to htmlsnapshot query

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
Valid UX concern. 6+ lines of output per `goto` becomes noise in multi-step workflows (this session had 8 detail page visits). A `--terse`/`--brief` flag showing only URL + title is a reasonable feature. The snapshot file path is rarely actionable in the terminal. Consider a persistent verbosity preference (`minimal`/`normal`/`verbose`).

---

### Issue 8: goto output mixes page status with snapshot file path — information overload

**Severity:** Low
**Category:** UX

#### Reproduction

Any goto command — output includes session reuse note, navigation status, page URL, page title, snapshot file path, AND a usage tip.

#### Expected Behavior

Concise output: navigated URL, page title, and optionally element refs if a snapshot was taken.

#### Actual Behavior

Output is 6+ lines including a full filesystem path to the snapshot YAML file and a verbose tip. For repeated commands (8 product page visits), this creates significant noise.

#### Root Cause Analysis

The default output mode includes all available information. The snapshot path and tips are useful for new users but become noise in multi-step workflows.

#### Code Pointer

`cli/browser4-cli/src/ — command output formatting`

#### AI Suggested Improvement

- Add a --terse or --brief flag that shows only URL + title + key data
- Consider suppressing the snapshot file path in default output (it's rarely actionable in the terminal)
- Allow users to set an output verbosity preference (minimal/normal/verbose) that persists across commands

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
  Valid UX concern. 6+ lines of output per `goto` becomes noise in multi-step workflows (this session had 8 detail page visits). A `--terse`/`--brief` flag showing only URL + title is a reasonable feature. The snapshot file path is rarely actionable in the terminal. Consider a persistent verbosity preference (`minimal`/`normal`/`verbose`).

---

## Overall Assessment

**Completion Status:** Successful — task was completed: 30 products searched, 10 shortlisted, 8 detail pages reviewed, best pick selected with clear rationale.

**Success Rate:** 85% — 17 of ~20 command invocations succeeded on first attempt. Two failures: one stale ref required re-snapshot, one htmlsnapshot inspect returned unhelpful results.

**Issues Found:** 8

**Major Blockers:** None that prevented task completion. The stale ref issue (#2) and inspect failure (#3) slowed progress but had workarounds.

**Most Confusing Aspects:** 1) Ref lifecycle — snapshot grep invalidated refs unexpectedly despite being a read-only search. 2) htmlsnapshot inspect produced useless results on Amazon, forcing manual CSS selector discovery. 3) The short-flag warnings on every command made the tool feel unstable even when commands succeeded. 4) Correlating multi-field extraction required index-matching across unaligned arrays.

**Most Valuable Improvements:** 1) Fix snapshot grep to not invalidate refs (#2). 2) Improve htmlsnapshot inspect to find product-card patterns on e-commerce sites (#3). 3) Add a 'correlated extract' command for multi-field extraction without writing X-SQL (#5). 4) Remove or suppress the short-flag warning when it's no longer applicable (#1).

**Usability Rating:** 6/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Short flag warnings create noise on every command

./b4w.ps1 snapshot -v 0 --stdout
./b4w.ps1 snapshot grep -i "search"

#### Issue 2: Ref became stale after snapshot grep (read-only command invalidated refs)

1. ./b4w.ps1 goto "https://www.amazon.com"
2. ./b4w.ps1 snapshot -v 0 --stdout
3. ./b4w.ps1 snapshot grep -i "search"
4. ./b4w.ps1 fill e292 "Laser-Engraved Crystal"  # fails with 'element not found'

#### Issue 3: htmlsnapshot inspect auto-discovery finds wrong repeating pattern

./b4w.ps1 htmlsnapshot inspect
# On Amazon search results page

#### Issue 4: htmlsnapshot get all produces unaligned arrays — no built-in correlation

1. htmlsnapshot get all text '.title' → [30 titles]
2. htmlsnapshot get all text '.price' → [30 prices]
3. htmlsnapshot get all attr 'a' href → [30 URLs]
# Arrays may be different lengths or different order

#### Issue 5: Shell quoting fragility for complex selectors and --sql on bash

Any command with CSS selectors containing special characters, e.g.:
./b4w.ps1 htmlsnapshot get all attr "a[href]" href

#### Issue 6: No obvious way to switch Amazon locale/currency from the CLI

Run goto on amazon.com — session was geo-located to Japan, showing JPY prices and 'Deliver to Japan'.

#### Issue 7: Clicking sponsored product links could trigger redirect tracking URLs

Extracting href from sponsored product links returned long sspa/click redirect URLs instead of clean /dp/ paths.

#### Issue 8: goto output mixes page status with snapshot file path — information overload

Any goto command — output includes session reuse note, navigation status, page URL, page title, snapshot file path, AND a usage tip.

