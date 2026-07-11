# Issues: x-sql-extraction-functions

> **Source:** `20260709-231931-x-sql-extraction-functions.full.md` | **Date:** 20260709-231931 | **Mode:** dev

## Scenario Background

### Task

The task was completed successfully. I:

1. Navigated to `http://localhost:18080/ec/b?node=1292115012` (6 Electronics products)
2. Captured an HTML snapshot
3. Ran `htmlsnapshot inspect` with `--max 3 --depth 3` to discover CSS selectors (using `[id*="product-B0E"]` as a workaround)
4. Wrote a comprehensive X-SQL query extracting 11 fields per product using all requested DOM, STR, and ARRAY functions
5. Applied PowerCSS `:expr()` visual filtering, WHERE/ORDER BY/LIMIT clauses
6. Ran the query successfully, retrieving 5 products sorted by price ascending ($24.99–$599.00)

The final query is at `comprehensive-query.sql` and the extracted data includes:
- `title` (STR_TRIM cleaned), `price` (float), `product_link` (abs URL), `image_url` (abs URL)
- `category_id` and `rating_data_attr` via `DOM_FIRST_ATTR`
- `title_upper` via `STR_UPPER_CASE`, `badge` via `STR_DEFAULT_IF_BLANK`
- `rating_numeric` via `STR_FIRST_FLOAT`, `title_short` via `STR_ABBREVIATE`
- `fallback_title` via `ARRAY_FIRST_NOT_BLANK` with multiple selector fallbacks
- `card_width` via `DOM_WIDTH` with `:expr(width > 150 && height > 100)` filtering

---

### Execution Context

**Key Commands:**

1. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help` — learned command structure
2. `curl` verification that MockSite was running on port 18080
3. `cargo run ... goto "http://localhost:18080/ec/b?node=1292115012"` — navigation (auto-reconnected session)
4. `cargo run ... htmlsnapshot` — captured static HTML snapshot with metadata
5. `cargo run ... htmlsnapshot inspect --max 3 --depth 3` — auto-discovery attempted, returned 0 matches
6. `cargo run ... htmlsnapshot export --file /tmp/mocksite-electronics.html` — exported HTML to diagnose structure
7. Inspected exported HTML, discovered class names contain literal `"` characters
8. `cargo run ... htmlsnapshot inspect '[id*="product-B0E"]' --max 3 --depth 3` — successful inspect with attribute selector
9. Wrote `test-basic.sql` — validated basic DOM functions work
10. Wrote `comprehensive-query.sql` — ran full query with all 15+ required features

**Key decisions:**
- Used attribute selectors (`[class*="..."]`) instead of class selectors due to MockSite quote-embedded class names
- Used `--sql @file.sql` pattern to avoid shell escaping issues
- Used `DOM_FIRST_ATTR(DOM, ':root', ...)` for data attributes on the product card itself
- Used `div[class*="product-card"]:expr(width > 150 && height > 100)` for PowerCSS filtering + robust class matching

**Workarounds required:**
- Had to export and read raw HTML to understand why auto-discovery failed
- Had to switch from `.class-name` to `[class*="text"]` attribute selectors throughout

---

---

## Issues Found (7 issues)

### Issue 1: htmlsnapshot inspect auto-discovery fails on MockSite due to quoted class names

**Severity:** Medium
**Category:** Reliability

#### Reproduction

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot inspect --max 3 --depth 3
```
Output: `### Inspect: ".\"product-card\"" (0 matches) ... No elements matched.`

#### Expected Behavior

Auto-discovery should find 6 product cards and suggest selectors.

#### Actual Behavior

Auto-discovery identified the selector `."product-card"` but reported 0 matches because MockSite class names contain literal `"` characters (`class="&quot;product-card&quot;"` resolves to `class='"product-card"'`). The CSS selector `."product-card"` doesn't match `class='"product-card"'`.

#### Root Cause Analysis

The MockSite HTML encodes class names with embedded HTML entities (`&quot;`) which decode to literal double-quote characters in the DOM. The auto-discovery algorithm generates a standard CSS class selector (`.product-card`) but the actual class value is `"product-card"` (with quotes). In CSS, `."product-card"` means "class exactly equals `"product-card"`" (escaped quote), but the DOM class is the literal string `"product-card"` including the quotes, so matching depends on the CSS parser's handling.

#### Code Pointer

``browser4-core` — CSS selector matching / DOM class attribute parsing`

#### AI Suggested Improvement

- Normalize class name values that contain HTML entities during DOM parsing so the CSS engine can match them with standard `.class-name` selectors
- Add a diagnostic message when auto-discovery finds a pattern but gets 0 matches: "Selector `X` discovered but yielded 0 matches. The page may use non-standard class names. Try `htmlsnapshot grep` to inspect the raw HTML, or use attribute selectors like `[class*="..."]`."
- Consider adding an `htmlsnapshot inspect --attribute-mode` flag that generates `[class*="..."]` selectors instead of `.class-name` selectors

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 2: No guidance when inspect returns 0 matches

**Severity:** Low
**Category:** UX

#### Reproduction

```bash
cargo run ... htmlsnapshot inspect --max 3 --depth 3
```

#### Expected Behavior

When auto-discovery or a user-provided selector returns 0 matches, the tool should suggest troubleshooting steps.

#### Actual Behavior

Output is: `"No elements matched. Check the CSS selector and ensure a HTML snapshot has been captured."` This is generic and doesn't help diagnose *why* the selector failed.

#### Root Cause Analysis

The error message is a catch-all that doesn't analyze *why* there are no matches. It doesn't check if similar selectors would work, if the snapshot is stale, or if the page structure is unusual.

#### Code Pointer

``cli/browser4-cli/src/` — inspect command output formatting`

#### AI Suggested Improvement

- Add a "Did you know?" hint suggesting the user run `htmlsnapshot grep` to search for partial class names in the raw HTML
- Suggest trying `htmlsnapshot export` to inspect the actual HTML structure
- When the selector looks like a class selector (`.something`), suggest trying `[class*="something"]` as an alternative
- Check if a snapshot exists and if it's stale, and surface that information

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 3: `$cliInvocation` / dev-mode invocation pattern not discoverable from help

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

A new developer reading only `--help` output, `README.md`, or `SKILL.md` sees all examples using `browser4-cli` as the command. The dev-mode `cargo run --manifest-path cli/browser4-cli/Cargo.toml --` pattern is buried in `cli/browser4-cli/README.md` and `skills/browser4-cli/references/development.md`. The main `README.md` doesn't mention running from source at all.

#### Expected Behavior

The `--help` output or a prominent section in README.md should tell developers how to run from source.

#### Actual Behavior

The `--help` output shows `Usage: browser4-cli <command>`. The main README.md only mentions `npm install -g browser4-cli` for installation. The developer needs to read two layers deep (`cli/browser4-cli/README.md` → `development.md`) to find the `cargo run --manifest-path` pattern.

#### Root Cause Analysis

The documentation is written for end users of the installed binary first, with development instructions nested in CLI-specific docs. The `--help` output assumes a globally installed binary.

#### Code Pointer

``cli/browser4-cli/src/help.rs` — help text generation; `README.md` — main project README`

#### AI Suggested Improvement

- Add a "Running from source" section to the main `README.md` near the top, similar to what's in `cli/browser4-cli/README.md`
- Add a brief note in `--help` output footer: "Dev mode: cargo run --manifest-path cli/browser4-cli/Cargo.toml -- <command>"
- Include a `Makefile` or `justfile` with a `browser4` target that wraps the cargo invocation

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 4: `htmlsnapshot` capture footer tip suggests wrong workflow

**Severity:** Low
**Category:** UX

#### Reproduction

```bash
cargo run ... htmlsnapshot
```
Output footer includes: `💡 Tip: Run snapshot -v 0 to see interactive element refs`

#### Expected Behavior

After capturing an HTML snapshot, tips should guide the user toward the HTML snapshot workflow (`get`, `query`, `inspect`), not the accessibility snapshot workflow.

#### Actual Behavior

The tip points to `snapshot -v 0`, which is the accessibility-tree workflow. The HTML snapshot workflow (which the user just initiated) uses CSS selectors and X-SQL, not refs. The user might be confused about which path to follow.

#### Root Cause Analysis

The tip is likely shared between `snapshot` and `htmlsnapshot` output handlers, or the `htmlsnapshot` capture output reuses a generic tip that's more appropriate for the accessibility snapshot path.

#### Code Pointer

``cli/browser4-cli/src/` — tip/hint generation for htmlsnapshot command output`

#### AI Suggested Improvement

- Show HTML-snapshot-relevant tips after `htmlsnapshot` capture, such as: "Try `htmlsnapshot get text 'h1'` to extract the page heading" or "Try `htmlsnapshot inspect` to discover CSS selectors"
- Consider showing the existing helpful tips that already appear in the capture output (the "Try these next" block with `get all text`, `get attr`, etc.) more prominently, as those are actually appropriate
- Move the `snapshot -v 0` tip to appear only after `snapshot` commands

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 5: `DOM_ATTR` listed in function index but not documented in detail reference

**Severity:** Low
**Category:** Documentation

#### Reproduction

Looking at `skills/browser4-cli/references/x-sql.md`, the function index lists `DOM_ATTR | String | Element property`. However, `x-sql-dom-select-functions.md` (the detailed reference for selector-based functions) only documents `DOM_FIRST_ATTR`/`DOM_NTH_ATTR`/`DOM_ALL_ATTRS`. The simpler `DOM_ATTR(DOM, name)` for the element itself is not documented.

#### Expected Behavior

Every function in the index should have a usage example in the detailed reference.

#### Actual Behavior

`DOM_ATTR` appears in the index but has no documentation in the detail files. The user must guess its signature or fall back to `DOM_FIRST_ATTR(DOM, ':root', name)`.

#### Root Cause Analysis

`DOM_ATTR` likely belongs to `DomFunctions` (core element property), not `DomSelectFunctions` (CSS selector-based), but the `DomFunctions` reference file is not linked as clearly. The index doesn't distinguish which detailed file covers each function.

#### Code Pointer

``skills/browser4-cli/references/x-sql-dom-functions.md` — missing `DOM_ATTR` documentation; `skills/browser4-cli/references/x-sql.md` — index organization`

#### AI Suggested Improvement

- Add `DOM_ATTR` documentation to `x-sql-dom-functions.md` with signature and example
- Add a "(see DomFunctions)" annotation next to Element Property category functions in the index
- Consider adding a column to the index table indicating which reference file documents each function

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 6: `cargo run` build-status output adds noise to every command

**Severity:** Low
**Category:** UX

#### Reproduction

Every `cargo run` invocation prints `Finished dev profile [unoptimized + debuginfo] target(s) in 0.XXs` and `Running ...` lines even when no code has changed.

#### Expected Behavior

Fast, clean output. The `--quiet` flag exists but must be added to every command.

#### Actual Behavior

Two extra lines of build output precede every command's actual output, adding visual noise and ~400ms perceived latency.

#### Root Cause Analysis

`cargo run` always prints build status to stderr, even when the binary is already up to date. This is standard Cargo behavior, not a browser4-cli bug.

#### Code Pointer

`N/A (Cargo behavior). Workaround documented in `development.md`: use `cargo run --quiet`.`

#### AI Suggested Improvement

- Document the `--quiet` pattern more prominently in the Quick Start / Development sections
- Consider a shell alias or wrapper script suggestion: `alias b4='cargo run --quiet --manifest-path cli/browser4-cli/Cargo.toml --'`
- Add a `just` or `make` target for common commands

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 7: MockSite URLs embed quote characters in href/src values

**Severity:** Low
**Category:** Product (test infrastructure)

#### Reproduction

The href values in MockSite product links are `"/ec/dp/B0E000001"` (with literal quote characters). When `DOM_FIRST_HREF` resolves them to absolute URLs, the result is `http://localhost:18080/ec/"/ec/dp/B0E000001"` which is malformed.

#### Expected Behavior

Clean relative URLs like `/ec/dp/B0E000001` without embedded quote characters.

#### Actual Behavior

URLs include literal `"` characters, making absolute URL resolution produce invalid results.

#### Root Cause Analysis

The MockSite HTML generator wraps attribute values in `&quot;` entities, which decode to literal `"` characters in the DOM. This affects `href`, `src`, and `data-*` attributes on product cards.

#### Code Pointer

``browser4-tests/browser4-tests-common/src/main/resources/` — MockSite HTML generation templates`

#### AI Suggested Improvement

- Fix the MockSite HTML generation to not wrap class names, IDs, hrefs, and srcs in quote entities
- If the embedded quotes are intentional (testing edge cases), document this in the MockSite README and provide example workarounds (e.g., using `DOM_FIRST_ATTR` with `abs:href` and string cleaning)
- Add a note in the inspect documentation about this MockSite quirk

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: htmlsnapshot inspect auto-discovery fails on MockSite due to quoted class names

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot inspect --max 3 --depth 3
```
Output: `### Inspect: ".\"product-card\"" (0 matches) ... No elements matched.`

#### Issue 2: No guidance when inspect returns 0 matches

```bash
cargo run ... htmlsnapshot inspect --max 3 --depth 3
```

#### Issue 3: `$cliInvocation` / dev-mode invocation pattern not discoverable from help

A new developer reading only `--help` output, `README.md`, or `SKILL.md` sees all examples using `browser4-cli` as the command. The dev-mode `cargo run --manifest-path cli/browser4-cli/Cargo.toml --` pattern is buried in `cli/browser4-cli/README.md` and `skills/browser4-cli/references/development.md`. The main `README.md` doesn't mention running from source at all.

#### Issue 4: `htmlsnapshot` capture footer tip suggests wrong workflow

```bash
cargo run ... htmlsnapshot
```
Output footer includes: `💡 Tip: Run snapshot -v 0 to see interactive element refs`

#### Issue 5: `DOM_ATTR` listed in function index but not documented in detail reference

Looking at `skills/browser4-cli/references/x-sql.md`, the function index lists `DOM_ATTR | String | Element property`. However, `x-sql-dom-select-functions.md` (the detailed reference for selector-based functions) only documents `DOM_FIRST_ATTR`/`DOM_NTH_ATTR`/`DOM_ALL_ATTRS`. The simpler `DOM_ATTR(DOM, name)` for the element itself is not documented.

#### Issue 6: `cargo run` build-status output adds noise to every command

Every `cargo run` invocation prints `Finished dev profile [unoptimized + debuginfo] target(s) in 0.XXs` and `Running ...` lines even when no code has changed.

#### Issue 7: MockSite URLs embed quote characters in href/src values

The href values in MockSite product links are `"/ec/dp/B0E000001"` (with literal quote characters). When `DOM_FIRST_HREF` resolves them to absolute URLs, the result is `http://localhost:18080/ec/"/ec/dp/B0E000001"` which is malformed.

