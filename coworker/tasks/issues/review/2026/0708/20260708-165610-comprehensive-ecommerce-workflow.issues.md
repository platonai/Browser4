# Issues: comprehensive-ecommerce-workflow

> **Source:** `20260708-165610-comprehensive-ecommerce-workflow.full.md` | **Date:** 20260708-165610 | **Mode:** dev

## Scenario Background

### Task

The evaluation report has been written to `eval-report.md`. The task successfully extracted product data from the MockSite e-commerce pages using multiple browser4-cli extraction methods:

- **Product detail (4K OLED TV 55):** title, price ($899.99), rating (4.6/521), category, features, specs extracted via htmlsnapshot, X-SQL, extract, and snapshot grep
- **Electronics listing:** 6 products with titles, prices, ratings, and review counts extracted via eval JavaScript
- **Session state** saved to `eval-session-state.json`

---

### Execution Context

| Step | Command | Result |
|------|---------|--------|
| 0 | Read `--help`, `SKILL.md`, `development.md`, `htmlsnapshot.md`, `storage-state.md`, `agent.md`, `x-sql.md` | Documentation reviewed |
| 1 | `goto "http://localhost:18080/ec/"` | Home page loaded (Session opened: DEFAULT) |
| 2 | `snapshot -v 0 --stdout` | Full snapshot — only category links, no products |
| 3 | `snapshot -i` | Interactive snapshot — same structure |
| 4 | `click e41` (Electronics) → `goto "/ec/dp/B0E000001"` directly | **Workaround:** Home page had no product links; had to navigate to category first, then product detail. Also, clicking the product link navigated to a malformed URL due to escaped quotes in href |
| 5 | `htmlsnapshot` (capture) | HTML snapshot captured (4 KB, 1 image, 1 link) |
| 6 | `htmlsnapshot...

(truncated — see full.md for complete trace)

---

## Issues Found (9 issues)

### Issue 1: Home page has no product links — task instructions assume products on home page

**Severity:** Medium
**Category:** Documentation

#### Reproduction

1. `goto "http://localhost:18080/ec/"`
2. `snapshot -v 0 --stdout`
3. Observe: only category navigation links (Electronics, Home, Garden…), no product cards or product links

#### Expected Behavior

The task says "Click on the first product link to navigate to a product detail page." The home page should have visible product links, or the task should instruct navigating to a category first.

#### Actual Behavior

Only 20 category links. No product elements. Must navigate to a category listing page first to find products.

#### Root Cause Analysis

MockSite's `/ec/` home page renders only a category navigation bar and footer. Product cards only appear on category listing pages (`/ec/b?node=...`). The task scenario was likely designed for a different page structure or an earlier version of MockSite.

#### AI Suggested Improvement

- Update MockSite home page to include featured/bestseller product cards, or update the task scenario to explicitly navigate to a category first
- Add a "Featured Products" or "Today's Deals" section to the home page (matching real e-commerce sites)

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT]

---

### Issue 2: MockSite generates malformed HTML with literal double-quote characters in attributes

**Severity:** High
**Category:** Product

#### Reproduction

1. `goto "http://localhost:18080/ec/b?node=1292115012"`
2. `snapshot -v 0 --stdout` — observe href values: `\"/ec/dp/B0E000001\"`
3. `click e338` (first product link)
4. Observe navigation to: `http://localhost:18080/%22/ec/dp/B0E000001/%22`

#### Expected Behavior

Clean URLs like `/ec/dp/B0E000001` and CSS classes like `product-card`, `product-link`.

#### Actual Behavior

HTML contains literal `"` characters in class names (`class="\"product-card\""`), IDs (`id="\"product-B0E000001\""`), and hrefs (`href="\"/ec/dp/B0E000001\""`). This breaks:
- Click navigation (URL-encoded quotes in path)
- CSS selector matching (`.product-link` matches nothing — the class is `"product-link"` with quotes)
- htmlsnapshot inspect output (selectors show escaped quotes)

#### Root Cause Analysis

Server-side template rendering in MockSite is double-escaping or injecting literal quote characters into HTML attribute values. The `\"` sequences in the output suggest JSON string escaping leaking into HTML generation.

#### Code Pointer

`MockSite template or HTML generation code — likely in `browser4-tests/browser4-rest-tests/` where MockSiteBoot is defined.`

#### AI Suggested Improvement

- Fix MockSite template to emit clean HTML attribute values without literal quote characters
- Add a validation test that parses MockSite output HTML and asserts no `\"` inside attribute values
- This is a critical fix — it blocks basic CSS selector workflows and click navigation on the listing page

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: WONTFIX] MockSite is test infrastructure, not part of browser4-cli. The malformed HTML only affects this specific test fixture — real websites don't have this issue. The evaluation agent successfully completed the scenario using workarounds (direct goto navigation, attribute-substring selectors). MockSite template fixes belong in the browser4-tests component, not browser4-cli.

---

### Issue 3: htmlsnapshot capture fails with `ReferenceError: __pulsar_utils__ is not defined` when page loaded via tab-new

**Severity:** Medium
**Category:** Reliability

#### Reproduction

1. `goto "http://localhost:18080/ec/dp/B0E000001"` (product detail page)
2. `tab-new "http://localhost:18080/ec/b?node=1292115012"` (listing page in new tab)
3. `htmlsnapshot` → fails with `ERROR: html_snapshot_capture failed: ReferenceError: __pulsar_utils__ is not defined`
4. `reload` → `htmlsnapshot` → still fails
5. `goto "http://localhost:18080/ec/b?node=1292115012"` → `htmlsnapshot` → succeeds

#### Expected Behavior

htmlsnapshot should work regardless of how the page was loaded (goto, tab-new, or reload).

#### Actual Behavior

htmlsnapshot capture fails after tab-new or reload, only works after a fresh goto. The error indicates the `__pulsar_utils__` script injection didn't occur or was lost.

#### Root Cause Analysis

The `tab-new` command may not inject the `__pulsar_utils__` script that htmlsnapshot depends on. The reload case is more concerning — it suggests the script injection is not surviving page reloads. The script may only be injected during the initial `goto` navigation flow.

#### Code Pointer

`The script injection logic — likely in the page loading/navigation pipeline where `__pulsar_utils__` is injected. Check `WebDriver.kt` or the page load handler for conditional script injection that only runs during goto.`

#### AI Suggested Improvement

- Ensure `__pulsar_utils__` script injection happens for all navigation methods (goto, tab-new, reload)
- Add a check in htmlsnapshot capture to retry with script injection if `__pulsar_utils__` is missing
- Improve the error message to suggest using `goto` as a workaround

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT]

---

### Issue 4: screenshot command has no filename parameter — argument is misinterpreted as element ref

**Severity:** Low
**Category:** UX

#### Reproduction

1. `screenshot my-descriptive-name.png`
2. Observe: file saved as `screenshot-2026-07-08T16-53-50-643Z.png` — the argument is treated as an element ref, not a filename

#### Expected Behavior

A way to specify a custom output filename for screenshots, e.g., `screenshot --output my-name.png` or `screenshot e5 --file my-name.png`.

#### Actual Behavior

The `[ref]` argument is interpreted as an element ref. There's no CLI flag for specifying an output filename. Screenshots are always saved with auto-generated timestamped names.

#### Root Cause Analysis

The `screenshot` command's argument is defined as `[ref]` (element reference for element-level screenshots). There's no `--file` or `--output` option to control the destination filename.

#### Code Pointer

``cli/browser4-cli/src/commands.rs` — the screenshot CommandDef and its parameter definitions.`

#### AI Suggested Improvement

- Add a `--file` / `-o` option to screenshot command for specifying output filename
- Keep the current auto-generated filename as default when no `--file` is given
- Update help text to clarify that the positional argument is an element ref, not a filename

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [x] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: REJECT] AI agents get the file path from command output and don't need custom filenames. Auto-generated timestamped names are actually better for agents — predictable pattern, no collisions. This is purely a human UX concern.

---

### Issue 5: DOM_FIRST_IMG returns empty string for small/placeholder images

**Severity:** Medium
**Category:** Product

#### Reproduction

1. Navigate to a product page with a small placeholder image (132×20px): `goto "http://localhost:18080/ec/dp/B0E000001"`
2. `htmlsnapshot`
3. Run X-SQL: `SELECT DOM_FIRST_IMG(DOM, '#product-image') AS image_url FROM DOM_LOAD_AND_SELECT(@url, ':root')`
4. Observe: `"image_url":""` (empty string)

#### Expected Behavior

Should return the image src URL (`/ec/static/img/placeholder.png`) regardless of image dimensions.

#### Actual Behavior

Returns empty string. The function appears to filter out images below a minimum size threshold.

#### Root Cause Analysis

`DOM_FIRST_IMG` likely has a built-in minimum dimension filter (e.g., width > 50 && height > 50) to skip icons and tracking pixels. This is reasonable for scraping real sites but silently drops small legitimate product images. No warning or error is emitted.

#### Code Pointer

``DomSelectFunctions.kt` — where `DOM_FIRST_IMG` is implemented, likely with dimension filtering logic.`

#### AI Suggested Improvement

- Document the minimum size threshold for `DOM_FIRST_IMG` / `DOM_ALL_IMGS` in the X-SQL reference
- Consider adding a `DOM_FIRST_IMG_ATTR` or parameter to disable size filtering
- Alternatively, document that `DOM_FIRST_ATTR(DOM, '#product-image', 'abs:src')` is the correct approach for arbitrary images

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The minimum-size filter (to skip icons/tracking pixels) is a reasonable design choice, but it's undocumented. Fix should: (1) document the size threshold in the X-SQL reference, (2) document DOM_FIRST_ATTR(DOM, '#selector', 'abs:src') as the workaround for arbitrary images. Do NOT add a new DOM_FIRST_IMG_ATTR function — that's over-engineering when DOM_FIRST_ATTR already covers this case.

---

### Issue 6: extract command works without LLM API key — undocumented behavior

**Severity:** Low
**Category:** Documentation

#### Reproduction

1. Ensure no LLM API keys are set: `unset DEEPSEEK_API_KEY OPENAI_API_KEY OPENROUTER_API_KEY VOLCENGINE_API_KEY`
2. `goto "http://localhost:18080/ec/dp/B0E000001"`
3. `extract "product title, price, description, features"`
4. Observe: Command succeeds and returns structured JSON

#### Expected Behavior

Either the extract command should fail with a clear "no LLM key configured" error (as documented in agent.md: "If no valid LLM key is configured, agent run fails fast with a clear error"), or the documentation should explain that extract works without an external API key.

#### Actual Behavior

Extract completed successfully (exit code 0), returning JSON with title, content, and features. The response metadata showed `"inputToken":1310,"outputToken":1518,"totalToken":2828,"inferenceTimeMillis":31475` — suggesting a built-in LLM or fallback extraction engine is being used.

#### Root Cause Analysis

Browser4's backend likely has a built-in extraction engine (possibly the "Machine Learning Agent" mentioned in the README) that doesn't require an external LLM API key. The documentation in agent.md only addresses `agent run`, not `extract`, but the implication is that all agent commands need an API key.

#### Code Pointer

``skills/browser4-cli/references/agent.md` — the Prerequisites section should clarify which commands need an LLM key and which don't.`

#### AI Suggested Improvement

- Update agent.md to clarify that `extract` and `summarize` work with a built-in extraction engine and do NOT require an external LLM API key
- Document the built-in extraction capabilities and their limitations vs. external LLM providers
- Add a note about the token usage and inference time shown in extract results

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT]

---

### Issue 7: Output files (screenshots, state-save, snapshots) scattered across repo root — no output directory concept

**Severity:** Low
**Category:** UX

#### Reproduction

1. Run `screenshot`, `state-save my-state.json`, `htmlsnapshot export`
2. Observe: files land in the repository root directory, mixed with source code

#### Expected Behavior

Output files should default to a designated output directory (e.g., `./output/`, `./browser4-output/`, or a configurable path), or at minimum be clearly grouped.

#### Actual Behavior

State files, screenshots, exported HTML land directly in the repo root. Over time this clutters the working directory.

#### Root Cause Analysis

No configurable output directory concept in the CLI. Each command writes to its own path resolution logic, defaulting to CWD.

#### Code Pointer

`CLI output path resolution — likely in `cli/browser4-cli/src/` where file paths are constructed for each command.`

#### AI Suggested Improvement

- Add a global `--output-dir` option or `BROWSER4_OUTPUT_DIR` environment variable
- Default to `./browser4-output/` or similar directory
- Group files by type: `screenshots/`, `snapshots/`, `state/`

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] Requires a global configuration mechanism (--output-dir flag or env var) that doesn't exist yet. This should be designed holistically alongside other configuration needs rather than added ad hoc. AI agents can track file paths from command output in the meantime — the current behavior doesn't block agent workflows.

---

### Issue 8: Shell escaping required for eval JavaScript with double quotes on Linux

**Severity:** Low
**Category:** UX

#### Reproduction

1. Attempt: `eval --json "document.querySelector('[class*="product-link"]').length"`
2. Observe: Shell interprets inner double quotes, breaking the JS expression
3. Workaround: wrap in single quotes and escape: `eval --json 'document.querySelectorAll("[class*=\"product-link\"]").length'`

#### Expected Behavior

Documentation should warn about shell escaping or provide `--file` / `--stdin` options for eval (similar to `--sql @file` for X-SQL).

#### Actual Behavior

The SKILL.md warns about Windows quoting issues for SQL but doesn't mention similar challenges with eval JavaScript on Linux. The eval command does not appear to support `--file` or `--stdin` in the help output.

#### Root Cause Analysis

The eval command lacks `--file`/`--stdin`/`--base64` input methods that htmlsnapshot query provides for SQL. Shell escaping of JS expressions with quotes is error-prone on all platforms.

#### Code Pointer

``cli/browser4-cli/src/commands.rs` — the eval CommandDef, which likely doesn't define `--file`/`--stdin` options.`

#### AI Suggested Improvement

- Add `--file`, `--stdin`, and `--base64` options to eval command (matching the pattern used by htmlsnapshot query)
- Update documentation to mention shell escaping as a general concern, not just Windows-specific
- Add a quick-start tip: "For complex JS, write to a file and use eval --file script.js"

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT]

---

### Issue 9: snapshot grep pattern note about `\|` conversion is confusing for new users

**Severity:** Low
**Category:** Discoverability

#### Reproduction

1. Run: `snapshot grep -i "3840\|HDR\|Bestseller"`
2. Observe the note: `Note: Converted grep-style alternation \| to | in pattern. Rust regex uses bare | for alternation (like ERE/egrep). Use snapshot grep -F for literal matching.`

#### Expected Behavior

Either accept standard grep alternation syntax, or document the regex dialect clearly in the help output.

#### Actual Behavior

The command auto-converts `\|` to `|` and prints a note. While helpful, this suggests the tool is trying to paper over a design inconsistency rather than picking one convention and documenting it. A new user trying `grep -E` style alternation would be confused.

#### Root Cause Analysis

The snapshot grep uses Rust regex (which uses `|` for alternation), but many users expect grep-style `\|` from GNU grep BRE syntax. The auto-conversion is a compatibility shim.

#### Code Pointer

``cli/browser4-cli/src/` — the snapshot grep pattern preprocessing logic.`

#### AI Suggested Improvement

- Pick one convention and document it: either "this is Rust regex (use `|`)" or "this is grep-compatible (use `\|`)"
- Add a `-E` flag for extended regex (even if it's the default) for familiarity with GNU grep users
- Add regex syntax documentation to `snapshot grep --help` and the SKILL.md reference

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [x] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: REJECT] The auto-conversion is an intentional compatibility shim for users coming from GNU grep BRE syntax. The note is informative: it tells agents exactly which regex dialect (Rust regex with bare |) is being used, so they can construct patterns correctly. Removing the shim would break backward compatibility; removing the note would confuse users. The current behavior is correct and helps both humans and agents.

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Home page has no product links — task instructions assume products on home page

1. `goto "http://localhost:18080/ec/"`
2. `snapshot -v 0 --stdout`
3. Observe: only category navigation links (Electronics, Home, Garden…), no product cards or product links

#### Issue 2: MockSite generates malformed HTML with literal double-quote characters in attributes

1. `goto "http://localhost:18080/ec/b?node=1292115012"`
2. `snapshot -v 0 --stdout` — observe href values: `\"/ec/dp/B0E000001\"`
3. `click e338` (first product link)
4. Observe navigation to: `http://localhost:18080/%22/ec/dp/B0E000001/%22`

#### Issue 3: htmlsnapshot capture fails with `ReferenceError: __pulsar_utils__ is not defined` when page loaded via tab-new

1. `goto "http://localhost:18080/ec/dp/B0E000001"` (product detail page)
2. `tab-new "http://localhost:18080/ec/b?node=1292115012"` (listing page in new tab)
3. `htmlsnapshot` → fails with `ERROR: html_snapshot_capture failed: ReferenceError: __pulsar_utils__ is not defined`
4. `reload` → `htmlsnapshot` → still fails
5. `goto "http://localhost:18080/ec/b?node=1292115012"` → `htmlsnapshot` → succeeds

#### Issue 4: screenshot command has no filename parameter — argument is misinterpreted as element ref

1. `screenshot my-descriptive-name.png`
2. Observe: file saved as `screenshot-2026-07-08T16-53-50-643Z.png` — the argument is treated as an element ref, not a filename

#### Issue 5: DOM_FIRST_IMG returns empty string for small/placeholder images

1. Navigate to a product page with a small placeholder image (132×20px): `goto "http://localhost:18080/ec/dp/B0E000001"`
2. `htmlsnapshot`
3. Run X-SQL: `SELECT DOM_FIRST_IMG(DOM, '#product-image') AS image_url FROM DOM_LOAD_AND_SELECT(@url, ':root')`
4. Observe: `"image_url":""` (empty string)

#### Issue 6: extract command works without LLM API key — undocumented behavior

1. Ensure no LLM API keys are set: `unset DEEPSEEK_API_KEY OPENAI_API_KEY OPENROUTER_API_KEY VOLCENGINE_API_KEY`
2. `goto "http://localhost:18080/ec/dp/B0E000001"`
3. `extract "product title, price, description, features"`
4. Observe: Command succeeds and returns structured JSON

#### Issue 7: Output files (screenshots, state-save, snapshots) scattered across repo root — no output directory concept

1. Run `screenshot`, `state-save my-state.json`, `htmlsnapshot export`
2. Observe: files land in the repository root directory, mixed with source code

#### Issue 8: Shell escaping required for eval JavaScript with double quotes on Linux

1. Attempt: `eval --json "document.querySelector('[class*="product-link"]').length"`
2. Observe: Shell interprets inner double quotes, breaking the JS expression
3. Workaround: wrap in single quotes and escape: `eval --json 'document.querySelectorAll("[class*=\"product-link\"]").length'`

#### Issue 9: snapshot grep pattern note about `\|` conversion is confusing for new users

1. Run: `snapshot grep -i "3840\|HDR\|Bestseller"`
2. Observe the note: `Note: Converted grep-style alternation \| to | in pattern. Rust regex uses bare | for alternation (like ERE/egrep). Use snapshot grep -F for literal matching.`

