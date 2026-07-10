# Issues: comprehensive-ecommerce-workflow

> **Source:** `20260709-211832-comprehensive-ecommerce-workflow.full.md` | **Date:** 20260709-211832 | **Mode:** dev

## Scenario Background

### Task

**Product Data Extracted from MockSite E-Commerce:**

| Field | Value |
|-------|-------|
| **Product Title** | 4K OLED TV 55 |
| **Price** | $899.99 |
| **Rating** | 4.6 (521 reviews) |
| **Category** | Electronics |
| **Image** | /ec/static/img/placeholder.png |
| **Features** | 55 inch, HDR10+ |
| **Specs** | Panel: OLED, Resolution: 3840x2160 |
| **Badge** | Bestseller |

**Electronics Listing Page Products (6 products):**
1. 4K OLED TV 55 — $899.99 (4.6★, 521)
2. Wireless Noise-Cancelling Headphones — $199.99 (4.4★, 312)
3. Portable Bluetooth Speaker — $49.99 (4.3★, 901)
4. Smartphone 128GB — $599.00 (4.5★, 210)
5. USB-C Hub 7-in-1 — $29.95 (4.2★, 77)
6. Wireless Mouse — $24.99 (4.1★, 156)

---

### Execution Context

**Key Commands:**

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "http://localhost:18080/ec/"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot -v 0
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot -i
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- click e39          # Electronics category
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- click e338         # First product
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "http://localhost:18080/ec/dp/B0E000001"  # Fix corrupted URL
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot inspect --max 3 --depth 2
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get text "#productTitle" --json
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot grep --pattern "899"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot query --sql @query.sql
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- tab-new "http://localhost:18080/ec/b?node=1292115012"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- eval --json "document.querySelectorAll('a').length"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- screenshot --filename electronics-listing-page.png --full-page
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- tab-select 0
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- reload
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot grep "OLED"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- state-save browser-session-state.json
```

**Workarounds Applied During Task:**

- **URL corruption from `click`:** After clicking a snapshot ref link, the URL contained literal escaped quotes. Had to re-navigate via `goto` with a clean URL.
- **`htmlsnapshot get` returning empty:** Output appears only with `--json` flag; non-JSON mode is silent. This was inconsistent (worked once then stopped).
- **`htmlsnapshot` capture failing on listing page:** JavaScript error `__pulsar_utils__ is not defined`. Used `eval` as a fallback.
- **`htmlsnapshot grep` with numeric patterns:** Numeric-only patterns like `899` are consumed as flag arguments. Required `--pattern "899"` to work.
- **Complex eval with JSON:** Shell escaping of nested quotes in complex JavaScript was nearly impossible. Simple expressions worked.

---

---

## Issues Found (10 issues)

### Issue 1: Snapshot ref `click` corrupts URLs containing escaped quotes

**Severity:** High
**Category:** Reliability

#### Reproduction

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot -v 0 --stdout  # observe URL: /url: \"/ec/dp/B0E000001\"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- click e338  # click product link
# Navigates to: http://localhost:18080/%22/ec/dp/B0E000001/%22 (404 error)
```

#### Expected Behavior

Clicking a link should navigate to the correct URL (`http://localhost:18080/ec/dp/B0E000001`), properly handling any escaped characters in the accessibility tree representation.

#### Actual Behavior

The URL contains literal `%22` (encoded double-quote characters), resulting in a 404 page. The snapshot output shows `\/ec/dp/B0E000001\"` with YAML-escaped quotes, and these escaped quotes are included in the navigation URL.

#### Root Cause Analysis

The snapshot YAML serializer escapes double quotes in URL attributes. When the `click` command resolves the ref's URL, it does not strip or unescape the YAML-level quote escaping, passing the literal escaped-quote characters into the browser's navigation. This affects any href attribute containing quote characters.

#### Code Pointer

``cli/browser4-cli/src/snapshot.rs` (URL extraction/resolution from snapshot refs)`

#### AI Suggested Improvement

- Strip YAML-level escaping from URL values before navigation (unescape `\"` → `"`, `\\` → `\`, etc.)
- Add validation for resolved URLs: reject or fix URLs containing `%22` or other encoded quote artifacts
- Consider using the raw DOM href attribute directly instead of the YAML-serialized version for link navigation

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 2: `htmlsnapshot get` returns empty output in non-JSON mode

**Severity:** High
**Category:** Reliability

#### Reproduction

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get text "#productTitle"    # empty
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get text "h1"               # empty
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get text "#productTitle" --json  # works (sometimes)
```

#### Expected Behavior

`htmlsnapshot get text "<valid-selector>"` should consistently return the matched text content, with or without `--json`.

#### Actual Behavior

In non-JSON mode, the command completes silently with no output even when the selector matches (confirmed by both `htmlsnapshot grep` and `--json` mode). The `--json` mode works inconsistently — it returned data once then returned empty on subsequent invocations with the same selector. The `--quiet` flag appears to interact badly with the output rendering.

#### Root Cause Analysis

Likely an output buffering or pagination issue in the text-mode rendering path. The `get text` path may be paginating output that should not be paginated, or the output may be going to stderr instead of stdout. The documentation states "`get text` and `get all text` are not paginated by default", suggesting this case should work but has a regression.

#### Code Pointer

``cli/browser4-cli/src/snapshot.rs` or `cli/browser4-cli/src/commands.rs` (htmlsnapshot get output rendering)`

#### AI Suggested Improvement

- Ensure `get text` and `get all text` output is always written to stdout (not stderr) in both JSON and human-readable modes
- Add a diagnostic message when a valid selector matches 0 elements vs when the page hasn't been snapshotted
- Add an integration test for `htmlsnapshot get text "h1"` with a known-good page to catch regressions
- Consider flushing stdout explicitly after writing result text

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 3: `htmlsnapshot` capture fails with `__pulsar_utils__ is not defined` on listing page

**Severity:** High
**Category:** Reliability

#### Reproduction

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "http://localhost:18080/ec/b?node=1292115012"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot
# ERROR: html_snapshot_capture failed: ReferenceError: __pulsar_utils__ is not defined
```

#### Expected Behavior

`htmlsnapshot` capture should work on any valid HTML page without requiring specific JavaScript globals.

#### Actual Behavior

The capture fails with a JavaScript ReferenceError indicating a missing `__pulsar_utils__` global. This suggests the HTML snapshot capture mechanism injects JavaScript that depends on a utility object that isn't being loaded or initialized before the injection runs.

#### Root Cause Analysis

The `htmlsnapshot` capture injects JavaScript (`__pulsar_utils__.getAnnotatedHTML()`) into the page to extract the annotated HTML. The `__pulsar_utils__` global is not defined on this page, likely because the injection script loads asynchronously or a race condition prevents it from initializing before `getAnnotatedHTML()` is called. This may be page-specific (works on the product detail page but not the listing page).

#### Code Pointer

`Backend JAR — the `__pulsar_utils__` injection and `getAnnotatedHTML()` call site in the HTML snapshot capture flow.`

#### AI Suggested Improvement

- Add a retry mechanism with exponential backoff if `__pulsar_utils__` is not yet defined
- Ensure the utility script is injected and executed synchronously before `getAnnotatedHTML()` is called
- Add a fallback: if `__pulsar_utils__` is unavailable, fall back to `document.documentElement.outerHTML` without annotations
- Provide a more descriptive error message explaining that the page may have restricted script injection

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 4: `htmlsnapshot grep` consumes numeric patterns as flag arguments

**Severity:** Medium
**Category:** CLI Experience

#### Reproduction

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot grep 899
# Error: Pattern is required. Provide a positional pattern, or use -e PATTERN (repeatable) for multiple patterns.
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot grep -F 899
# Error: Pattern is required. (same error)
```

#### Expected Behavior

Numeric strings should be accepted as valid positional pattern arguments for grep, just like any other string.

#### Actual Behavior

Numeric patterns like `899` are silently consumed by the argument parser (possibly as a flag value for a preceding boolean flag), leaving the pattern position empty. The workaround is to use `--pattern "899"` or `-e "899"`.

#### Root Cause Analysis

The CLI argument parser (likely `clap`) may interpret numeric positional arguments as potential values for flags that accept numeric arguments. When combined with `-F` (which shouldn't take a value), the number `899` is consumed as if it were `-F`'s value, clearing the positional pattern slot.

#### Code Pointer

``cli/browser4-cli/src/args.rs` (argument parsing configuration)`

#### AI Suggested Improvement

- Configure clap to not allow `-F`/`--fixed-strings` to consume the next positional argument
- Add explicit `num_args(0)` or equivalent to boolean flags in the grep subcommand
- Add an example in the grep help text showing how to search for numeric patterns

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 5: `eval` complex JavaScript has severe shell escaping problems

**Severity:** Medium
**Category:** UX / CLI Experience

#### Reproduction

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- eval "JSON.stringify({title: document.querySelector('#productTitle')?.textContent})" --json
# Returns empty or garbled output due to shell quote escaping
```

#### Expected Behavior

The `eval` command should support complex JavaScript expressions without requiring manual shell escaping gymnastics. The documentation mentions `--stdin` and `--file` options for this reason.

#### Actual Behavior

Complex JavaScript with nested quotes, template literals, or JSON is extremely difficult to pass inline. Each shell layer (bash → cargo → CLI) requires different escaping. Simple expressions like `document.title` work; anything with single quotes, double quotes, or special characters fails or returns silently. The `--file` and `--stdin` workarounds exist but require creating temporary files, adding friction.

#### Root Cause Analysis

The CLI uses shell string quoting for the JavaScript expression, which requires escaping quote characters for the shell, then potentially for cargo's argument parsing, and then for the CLI's own parsing. Each layer adds escaping complexity. The documentation warns about this for Windows but it's also painful on Linux.

#### Code Pointer

``cli/browser4-cli/src/args.rs` (eval command argument handling)`

#### AI Suggested Improvement

- Add `eval --file <path>` to the help output as the recommended approach for complex expressions
- Add `eval --stdin` as a first-class option (pipe JS from stdin)
- Consider adding a heredoc-friendly input mode that reads until a delimiter
- Add copy-paste examples with correct shell escaping for common patterns (extracting attributes, arrays of objects)
- Document that `--json` should always be used with eval for structured data extraction

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 6: Home page has no product links — e-commerce task requires category navigation first

**Severity:** Low
**Category:** Documentation / Task Design

#### Reproduction

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "http://localhost:18080/ec/"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot -v 0 --stdout
# No product links visible; only category navigation links
```

#### Expected Behavior

The task description assumes the home page has product links to click. Step 4 says "Click on the first product link to navigate to a product detail page."

#### Actual Behavior

The MockSite home page (`/ec/`) only contains a header and category navigation links (Electronics, Home, Garden, etc.). There are no individual product cards or product links. The first actual "product link" only appears after navigating into a category.

#### Root Cause Analysis

The task scenario was designed assuming the home page has featured products, but the MockSite's home page only shows category navigation. Either the MockSite is missing expected content or the task scenario doesn't match the actual page structure.

#### AI Suggested Improvement

- Either update the MockSite home page to include featured/recommended product cards
- Or update the task scenario to acknowledge the two-step navigation (home → category → product)
- The SKILL.md warning about `snapshot -i` stripping generic divs was prescient: "Many e-commerce product cards use generic divs, not semantic elements."

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 7: `--quiet` flag suppresses essential output including `tab-list` results

**Severity:** Medium
**Category:** CLI Experience

#### Reproduction

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --quiet tab-list
# No output — no tab list displayed
```

#### Expected Behavior

`--quiet` should suppress tips/hints/warnings but still show the command's primary output (the tab list). Documentation says `--quiet` "suppress normal output, only show errors" — but the tab list is normal output that users need.

#### Actual Behavior

`--quiet` suppresses the tab list entirely. Users need to run without `--quiet` to see tab information, which also shows tips and hints they may not want.

#### Root Cause Analysis

The `--quiet` flag implementation appears to suppress all stdout, not just tips/hints/warnings. Commands whose primary purpose is to display information (`tab-list`, `list`, `cookie-list`, etc.) should be exempt from full output suppression or should have a separate output channel for primary data.

#### Code Pointer

``cli/browser4-cli/src/main.rs` or `cli/browser4-cli/src/commands.rs` (output routing logic for --quiet)`

#### AI Suggested Improvement

- Distinguish between "tips/hints/warnings" (suppressed by `--quiet`) and "command results" (always shown)
- Alternatively, make `--quiet` suppress tips but add `--json` as the machine-readable alternative for commands like `tab-list`
- Document which commands are affected by `--quiet` output suppression

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 8: `screenshot` positional filename argument silently ignored; requires `--filename` flag

**Severity:** Low
**Category:** Discoverability

#### Reproduction

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- screenshot electronics-listing-page.png
# No error, but file is not saved to the expected location
```

#### Expected Behavior

Either the positional argument should work as a filename (as the help text implies with `screenshot [ref]`), or an error should be raised when a non-ref string is passed as a positional argument.

#### Actual Behavior

The positional argument is silently interpreted as an element ref (and ignored if no matching ref exists). The user must discover the `--filename` flag through `--help` to save screenshots with custom names. The help output shows `[ref]` as the only positional argument, but a new user might reasonably pass a filename.

#### Root Cause Analysis

The `screenshot` command accepts an optional positional `[ref]` argument. Any string passed positionally is treated as a ref selector, not a filename. There's no validation that the ref exists or warning when it doesn't match.

#### AI Suggested Improvement

- If the positional argument doesn't match any existing ref pattern (e.g., not starting with `e` followed by digits), warn the user and suggest `--filename`
- Add a note in the `--help` output: "Use --filename to specify output path"
- Consider accepting a bare filename positional argument when there's no ref argument ambiguity

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 9: `htmlsnapshot get attr` returns empty with valid selectors in non-JSON mode

**Severity:** Medium
**Category:** Reliability

#### Reproduction

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get attr "#product-image" src
# Returns empty (no output)
```

#### Expected Behavior

Should return the `src` attribute value (`/ec/static/img/placeholder.png`) of the matched image element.

#### Actual Behavior

Returns empty, same as Issue 2 — seems to be the same root cause affecting both `get text` and `get attr` in non-JSON mode. The `--json` flag inconsistently resolves the issue.

#### Root Cause Analysis

Same as Issue 2 — output rendering path issue in non-JSON mode.

#### AI Suggested Improvement

- Same fixes as Issue 2

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 10: X-SQL query requires trial-and-error for correct CSS selectors

**Severity:** Low
**Category:** Discoverability / Documentation

#### Reproduction

```
# First attempt with wrong selectors:
SELECT DOM_FIRST_FLOAT(DOM, '.product-price', 0.0) AS price ...  # returns 0.0
# Corrected:
SELECT DOM_FIRST_TEXT(DOM, '#product-price') AS price ...         # returns "$899.99"
```

#### Expected Behavior

The `htmlsnapshot inspect` output should help users discover correct selectors, or the documentation should more clearly explain how to map from snapshot refs to CSS selectors.

#### Actual Behavior

`htmlsnapshot inspect` on the product detail page auto-discovered `tr` elements (specs table) but not the product title, price, or image selectors. Users must resort to `htmlsnapshot grep` to find elements by their text content and then manually construct CSS selectors from the HTML. The SKILL.md references `css-selector-bridge.md` for "bridging snapshot refs to CSS selectors" but this path requires extra reading.

#### Root Cause Analysis

The auto-discovery algorithm in `inspect` prioritizes repeating patterns (tables, lists) over singleton elements (title, price, image). On a product detail page, the most prominent repeating pattern is the specs table rows, not the product metadata. The algorithm correctly finds what it's designed to find, but the use case (extracting product metadata) needs different guidance.

#### AI Suggested Improvement

- Add an `inspect` mode that lists all elements with IDs and unique classes (not just repeating patterns)
- Enhance the `htmlsnapshot` capture metadata to include prominent CSS selectors (elements with IDs, headings, elements with class names containing "price", "title", etc.)
- Add a quick-start recipe: "After `htmlsnapshot`, run `htmlsnapshot grep 'price\|title\|image'` to discover selectors"

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

#### Issue 1: Snapshot ref `click` corrupts URLs containing escaped quotes

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot -v 0 --stdout  # observe URL: /url: \"/ec/dp/B0E000001\"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- click e338  # click product link
# Navigates to: http://localhost:18080/%22/ec/dp/B0E000001/%22 (404 error)
```

#### Issue 2: `htmlsnapshot get` returns empty output in non-JSON mode

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get text "#productTitle"    # empty
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get text "h1"               # empty
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get text "#productTitle" --json  # works (sometimes)
```

#### Issue 3: `htmlsnapshot` capture fails with `__pulsar_utils__ is not defined` on listing page

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "http://localhost:18080/ec/b?node=1292115012"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot
# ERROR: html_snapshot_capture failed: ReferenceError: __pulsar_utils__ is not defined
```

#### Issue 4: `htmlsnapshot grep` consumes numeric patterns as flag arguments

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot grep 899
# Error: Pattern is required. Provide a positional pattern, or use -e PATTERN (repeatable) for multiple patterns.
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot grep -F 899
# Error: Pattern is required. (same error)
```

#### Issue 5: `eval` complex JavaScript has severe shell escaping problems

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- eval "JSON.stringify({title: document.querySelector('#productTitle')?.textContent})" --json
# Returns empty or garbled output due to shell quote escaping
```

#### Issue 6: Home page has no product links — e-commerce task requires category navigation first

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "http://localhost:18080/ec/"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot -v 0 --stdout
# No product links visible; only category navigation links
```

#### Issue 7: `--quiet` flag suppresses essential output including `tab-list` results

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --quiet tab-list
# No output — no tab list displayed
```

#### Issue 8: `screenshot` positional filename argument silently ignored; requires `--filename` flag

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- screenshot electronics-listing-page.png
# No error, but file is not saved to the expected location
```

#### Issue 9: `htmlsnapshot get attr` returns empty with valid selectors in non-JSON mode

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get attr "#product-image" src
# Returns empty (no output)
```

#### Issue 10: X-SQL query requires trial-and-error for correct CSS selectors

```
# First attempt with wrong selectors:
SELECT DOM_FIRST_FLOAT(DOM, '.product-price', 0.0) AS price ...  # returns 0.0
# Corrected:
SELECT DOM_FIRST_TEXT(DOM, '#product-price') AS price ...         # returns "$899.99"
```

