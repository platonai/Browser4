# Issues: comprehensive-ecommerce-workflow

> **Source:** `20260727-060654-comprehensive-ecommerce-workflow.full.md` | **Date:** 20260727-060654 | **Mode:** dev

## Scenario Background

### Task

### Product Detail Page (B0E000001)
| Field | Value |
|---|---|
| **Title** | 4K OLED TV 55 |
| **Price** | $899.99 |
| **Category** | Electronics |
| **Rating** | 4.6 (521 ratings) |
| **Badge** | Bestseller |
| **Features** | 55 inch, HDR10+ |
| **Panel Type** | OLED |
| **Resolution** | 3840x2160 |
| **Image** | /ec/static/img/placeholder.png |

### Electronics Listing Page (6 products)
| # | Product | Price | Rating |
|---|---------|-------|--------|
| 1 | 4K OLED TV 55 | $899.99 | 4.6 (521) |
| 2 | Wireless Noise-Cancelling Headphones | $199.99 | 4.4 (312) |
| 3 | Portable Bluetooth Speaker | $49.99 | 4.3 (901) |
| 4 | Smartphone 128GB | $599.00 | 4.5 (210) |
| 5 | USB-C Hub 7-in-1 | $29.95 | 4.2 (77) |
| 6 | Wireless Mouse | $24.99 | 4.1 (156) |

---

### Execution Context

**Key Commands:**

1. `goto "http://localhost:18080/ec/"` — navigate to home page
2. `snapshot --viewport 0 --stdout` — full-page snapshot (used `--viewport` because `-v` intercepted by PowerShell)
3. `snapshot --interactive --stdout` — interactive snapshot (used `--interactive` because `-i` intercepted by PowerShell)
4. `click e39` — attempted to click Electronics link → **navigated to about:blank (reliability issue)**
5. `goto "http://localhost:18080/ec/b?node=1292115012"` — direct navigation workaround
6. `snapshot --viewport 0 --stdout` — listing page snapshot
7. `click e168` — clicked first product → **URL contained encoded quotes (%22)**
8. `goto "http://localhost:18080/ec/dp/B0E000001"` — direct navigation to fix broken URL
9. `htmlsnapshot` — capture static HTML snapshot
10. `htmlsnapshot inspect --max 3 --depth 2` — discover CSS selectors
11. `htmlsnapshot get text "main h1"` → "4K OLED TV 55"
12. `htmlsnapshot get all text "li"` → features list
13. `htmlsnapshot get all text ".product-info"` → full product description text
14. Wrote `product_query.sql` and ran `htmlsnapshot query --sql @product_query.sql` → structured extraction
15. `htmlsnapshot grep 'price'` / `'product-price'` / `'OLED'` / `'HDR10'` / `'resolution|3840'` — all found
16. `doctor --verbose` → LLM not configured, skipped `extract`
17. `tab-new "http://localhost:18080/ec/b?node=1292115012"` → opened listing page in new tab
18. `htmlsnapshot` + `htmlsnapshot get all text "#product-list a"` → 6 product titles
19. `eval --json "document.querySelectorAll(...).length"` → 6 product links confirmed
20. `screenshot --filename electronics-listing-2026-07-27.png`
21. `tab-select 1` → switched back to product detail
22. `reload` → page reloaded correctly
23. `htmlsnapshot` recapture + grep for attributes
24. `state-save ecommerce-session.json`

**Key decisions/workarounds:**
- Used `--viewport` / `--interactive` long-form flags everywhere because short flags `-v`/`-i`/`-e` are intercepted by PowerShell
- Navigated by URL (`goto`) instead of clicking links twice — click navigation produced broken URLs (about:blank, encoded quotes)
- Used `htmlsnapshot summary` (WPSI) as a scout to discover `#product-list a` selector
- Could not use `-i` flag for case-insensitive grep; pivoted to fewer, more specific patterns

---

---

## Issues Found (10 issues)

### Issue 1: Click on category link navigates to about:blank

**Severity:** Critical
**Category:** Reliability

#### Reproduction

```bash
./b4w.ps1 goto "http://localhost:18080/ec/"
./b4w.ps1 snapshot --viewport 0 --stdout
# Note ref for "Electronics" link (e39)
./b4w.ps1 click e39
# Output: ✓ Clicked e39 — but Page URL is about:blank
```

#### Expected Behavior

Browser navigates to `http://localhost:18080/ec/b?node=1292115012` (the Electronics category page).

#### Actual Behavior

The click is reported as successful ("✓ Clicked e39"), but the browser ends up at `about:blank`. No error message indicates the navigation failed.

#### Root Cause Analysis

Unknown. The click action reports success but the navigation doesn't complete. Possible causes: (a) the CDP click event dispatches but the mock site's link handler doesn't fire correctly, (b) a race condition between the click action and navigation tracking, (c) the link's `href` has escaped-quote characters in the mock HTML that prevent proper navigation.

#### Code Pointer

``PulsarWebDriver.kt:click()` — needs investigation of the click → navigation pipeline`

#### AI Suggested Improvement

- After a click, verify the page URL changed; if it's still the same page (or blank), surface a warning
- Add a post-click URL check: if the clicked element is a link with an href, verify the navigation occurred
- Log the expected vs actual URL after click on `<a>` elements for debugging
- Consider using `Runtime.evaluate` with `element.click()` as a fallback when `Input.dispatchMouseEvent` click doesn't trigger navigation

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
need reproduce the bug

---

### Issue 2: PowerShell intercepts short flags (-v, -i, -e) when using b4w.ps1 from Git Bash

**Severity:** High
**Category:** UX / Reliability

#### Reproduction

```bash
./b4w.ps1 snapshot -v 0 --stdout
./b4w.ps1 snapshot -i --stdout
./b4w.ps1 htmlsnapshot grep -i 'pattern'
./b4w.ps1 htmlsnapshot grep -e 'pattern1' -e 'pattern2'
```

#### Expected Behavior

Short flags `-v`, `-i`, `-e` pass through to the Rust CLI binary.

#### Actual Behavior

PowerShell's parameter binder consumes `-v` (matches `-Verbose`), `-i` (matches `-InformationAction`), and `-e` (matches `-ErrorAction`). Errors like: `Parameter cannot be processed because the parameter name 'i' is ambiguous.`

#### Root Cause Analysis

The `b4w.ps1` script's `param()` block explicitly declares only `$Rebuild` and `$RemainingArgs`, but PowerShell implicitly adds common parameters (`-Verbose`, `-InformationAction`, `-ErrorAction`, etc.) to all scripts, even without `[CmdletBinding()]`. The short forms `-v`, `-i`, `-e` are ambiguous matches for these common parameters and are consumed before reaching `$RemainingArgs`. The documented workaround (`--` passthrough) doesn't work from Git Bash because PowerShell consumes `--` as its own end-of-params marker, and `$RemainingArgs[0]` is never `'--'`.

#### Code Pointer

``b4w.ps1:3-7` (param block and `--` detection logic on lines 47-50)`

#### AI Suggested Improvement

- Add `[CmdletBinding(DefaultParameterSetName='Default')]` with `[Parameter()]` attributes to prevent PowerShell from adding implicit parameter resolution for flags not in the param block
- Detect that `$RemainingArgs` contains a subcommand like `snapshot`/`htmlsnapshot` and pass everything verbatim without relying on `--` marker
- Add a `--` handler at the bash level (in `b4w.sh`) so the `--` token survives into `$RemainingArgs[0]` for the script's detection logic
- In `b4w.ps1`, try `$MyInvocation.Line` parsing as a fallback when `$RemainingArgs[0]` is not `'--'` but the original command line contains `--`

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
add script level tests to ensure short flags work fine with b4w

---

### Issue 3: Mock site product links contain escaped quotes causing malformed URLs

**Severity:** High
**Category:** Product / Reliability

#### Reproduction

```bash
./b4w.ps1 goto "http://localhost:18080/ec/b?node=1292115012"
./b4w.ps1 snapshot --viewport 0 --stdout
# Observe: /url: \"/ec/dp/B0E000001\"
./b4w.ps1 click e168
# Navigates to: http://localhost:18080/%22/ec/dp/B0E000001/%22
```

#### Expected Behavior

Clicking a product link navigates to `http://localhost:18080/ec/dp/B0E000001`.

#### Actual Behavior

The URL becomes `http://localhost:18080/%22/ec/dp/B0E000001/%22` — the literal quote characters (`"`) from the HTML attribute are URL-encoded as `%22` and included in the navigation URL.

#### Root Cause Analysis

The mock site HTML contains literal double-quote characters within attribute values, e.g., `href=""/ec/dp/B0E000001""`. The browser treats the inner quotes as part of the attribute value. Browser4's snapshot correctly shows this as `/url: \"/ec/dp/B0E000001\"` (with escaped quotes). When the click dispatches, the browser resolves the href relative to the page URL, including the embedded quote characters.

#### Code Pointer

`The root cause is in the MockSite HTML fixtures — likely in `browser4-tests/pulsar-tests-common/src/main/resources/static/b4/` or wherever the MockSite e-commerce templates are defined.`

#### AI Suggested Improvement

- Fix the mock site HTML templates to use properly formatted `href` attributes without embedded quotes
- Browser4 could detect and sanitize href values that contain literal quote characters before resolving the URL
- Add a validation step in the snapshot that warns when element URLs contain suspicious characters like embedded quotes

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: `htmlsnapshot inspect` auto-discovery fails with escaped-quote CSS selectors

**Severity:** Medium
**Category:** Reliability

#### Reproduction

```bash
./b4w.ps1 goto "http://localhost:18080/ec/b?node=1292115012"
./b4w.ps1 htmlsnapshot
./b4w.ps1 htmlsnapshot inspect --max 6 --depth 3
```

#### Expected Behavior

Auto-discovery finds the repeating product card pattern and suggests CSS selectors.

#### Actual Behavior

The output shows `Auto-discovered selector ".\"product-card\"" from ":root" also had no matches.` — the selector contains escaped quotes from the mock HTML and fails to match anything. Falls back to `- No elements matched.`

#### Root Cause Analysis

The mock site uses class names with embedded double-quote characters (e.g., `class=""product-card""`). When `htmlsnapshot inspect` auto-discovers this class, it constructs a CSS selector `."\"product-card\""` which is not valid CSS. The algorithm does not sanitize or escape special characters from discovered class names before constructing selectors.

#### Code Pointer

`The `htmlsnapshot inspect` auto-discovery algorithm — likely in the Kotlin backend's DOM analysis code. The selector construction should strip or escape literal quote characters from class names.`

#### AI Suggested Improvement

- Strip or escape literal quote characters (`"`, `'`) from class names and IDs before constructing CSS selectors
- If a discovered selector fails to match, fall back to alternative structural selectors (e.g., `div > div > a[href*="/ec/dp/"]`)
- Add a selector validity check before reporting auto-discovered selectors

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: b4w.sh fails — PowerShell cannot resolve Unix-style paths on Windows

**Severity:** Medium
**Category:** Reliability

#### Reproduction

```bash
./b4w.sh snapshot --viewport 0
```

#### Expected Behavior

b4w.sh acts as a drop-in replacement for b4w.ps1, handling the flag-escaping correctly.

#### Actual Behavior

Error: `The term '/d/workspace/Browser4/Browser4-4.12/b4w.ps1' is not recognized as a name of a cmdlet, function, script file, or executable program.` PowerShell's `-File` and `-Command` parameters require Windows-style paths (`D:\...`), but the script computes `$SCRIPT_DIR` using `$(cd "$(dirname "$0")" && pwd)` which returns `/d/workspace/...` in Git Bash.

#### Root Cause Analysis

Git Bash's `pwd` outputs Unix-style paths (`/d/...`). PowerShell on Windows requires Windows-style paths (`D:\...`). The `b4w.sh` script passes the Unix path to `pwsh -File`, which fails because PowerShell cannot resolve `/d/` as a drive.

#### Code Pointer

``b4w.sh:33` — `SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"` — needs path conversion`

#### AI Suggested Improvement

- Convert the path to Windows format using `cygpath -w` or `cmd //c cd` before passing to `pwsh`
- Add a fallback that detects when the path fails and retries with a Windows-formatted path
- Document that `b4w.sh` is only for WSL/Linux and recommend `b4w.bat` via `cmd.exe` for Git Bash users

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: Task design assumes product links on home page; home page has only category navigation

**Severity:** Low
**Category:** Documentation

#### Reproduction

Following the task instructions: "Click on the first product link to navigate to a product detail page" from `http://localhost:18080/ec/`.

#### Expected Behavior

The home page should contain product links/cards that can be clicked to reach a product detail page.

#### Actual Behavior

The home page (`/ec/`) shows only a category navigation bar (Electronics, Home, Garden, Sports, etc.) and a footer. No individual product links are present. The user must click a category first, then find products on the listing page.

#### Root Cause Analysis

The task scenario was written under the assumption that the MockSite home page renders product cards. The actual mock site only shows category links on the home page.

#### Code Pointer

`MockSite home page template — needs product cards or the task instructions need updating.`

#### AI Suggested Improvement

- Update the task instructions to reflect the actual MockSite structure (category navigation → product listing → product detail)
- Or update the MockSite home page to include a featured/bestsellers section with product links

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
update the MockSite home page to include a featured/bestsellers section with product links

---

### Issue 7: No price-specific CSS selectors on mock product pages

**Severity:** Low
**Category:** Discoverability / Documentation

#### Reproduction

Try `htmlsnapshot get text ".price"` or `htmlsnapshot get text "[class*='price']"` on the product detail page.

#### Expected Behavior

Semantic CSS classes like `.price`, `.product-price-value` should exist for prices.

#### Actual Behavior

Prices are rendered in plain `<div>` or `<generic>` elements with no CSS class. The only identifiable attribute is `id="product-price"` on the detail page. On the listing page, there are no price-specific selectors at all — prices appear as bare text nodes in generic divs. This forces users to use JavaScript `eval` or X-SQL with structural selectors.

#### Root Cause Analysis

The mock site HTML uses minimal semantic markup. This is realistic for some real sites but makes the mock site harder to extract from — a discoverability challenge for new users learning the tool.

#### Code Pointer

`MockSite templates`

#### AI Suggested Improvement

- Add CSS classes to price elements in the mock site (e.g., `.price`, `.product-price`, `.price-value`)
- Document in the skill reference that `htmlsnapshot summary` (WPSI) and `inspect` auto-discovery are the recommended ways to discover selectors on unfamiliar pages, especially when semantic classes are absent

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
mock site should contain noisy.

---

### Issue 8: `snapshot grep` outputs snapshot YAML path but not grep results without `--stdout`

**Severity:** Low
**Category:** Discoverability

#### Reproduction

```bash
./b4w.ps1 goto "http://localhost:18080/ec/"
./b4w.ps1 snapshot --viewport 0   # no --stdout
./b4w.ps1 snapshot grep "Electronics"
```

#### Expected Behavior

Grep results appear inline in the terminal.

#### Actual Behavior

The output says `[Snapshot](path\to\snapshot.yml)` without showing the matched lines. The user must add `--stdout` to the snapshot step, but the grep documentation doesn't explicitly state that the snapshot needs `--stdout` for grep to work on the most recent capture.

#### Root Cause Analysis

`snapshot grep` searches the YAML file content. When `--stdout` is not used, the snapshot is saved to a file and only the path is shown. `snapshot grep` reads from the saved file, but the initial impression is that the command silently succeeded without showing matches.

#### Code Pointer

`N/A — this is a UX/documentation gap`

#### AI Suggested Improvement

- When `snapshot grep` finds matches, print them inline even if the snapshot was captured without `--stdout`
- Add a tip: "Run `snapshot --viewport 0 --stdout` before `snapshot grep` if you want to see matched lines inline" 
- Clarify in the help text that `snapshot grep` always reads from the saved snapshot file, not stdout

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: `htmlsnapshot grep '899'` returns "Pattern is required" — numeric-only pattern silently dropped

**Severity:** Low
**Category:** Reliability

#### Reproduction

```bash
./b4w.ps1 goto "http://localhost:18080/ec/dp/B0E000001"
./b4w.ps1 htmlsnapshot
./b4w.ps1 htmlsnapshot grep '899'
```

#### Expected Behavior

Returns lines containing the number 899.

#### Actual Behavior

Error: `Pattern is required. Provide a positional pattern, or use -e PATTERN (repeatable) for multiple patterns.`

#### Root Cause Analysis

Unknown. Previous `htmlsnapshot grep 'price'` worked fine. The number `899` may be incorrectly parsed by clap or the argument may be dropped somewhere in the toolchain. Needs investigation.

#### Code Pointer

`CLI grep command argument parsing`

#### AI Suggested Improvement

- Investigate why certain positional pattern values are dropped
- Add a debug/trace mode to show what arguments the CLI receives vs. what it passes to the grep logic

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 10: WPSI summary shows escaped-quote selectors that can't be copy-pasted directly

**Severity:** Low
**Category:** UX

#### Reproduction

```bash
./b4w.ps1 htmlsnapshot summary
```
Observe the "Suggested Commands" section shows selectors with backslash-escaped quotes like `#\"product-B0E000001\"`.

#### Expected Behavior

The suggested commands should be directly copy-pasteable into the terminal.

#### Actual Behavior

The escaped-quote selectors (`#\"product-B0E000001\"`) are valid YAML representations of the CSS selectors but can't be copy-pasted directly — they need manual un-escaping. The suggestion `htmlsnapshot get all text "#product-list a"` *is* copy-pasteable, but selectors containing escaped quotes from the mock site are not.

#### Root Cause Analysis

The YAML output correctly escapes special characters. When these selectors are from a mock site with literal quote characters in attribute values, the escaping makes them non-copyable. This is more of a mock-site issue than a tool issue, but the tool could be more helpful.

#### AI Suggested Improvement

- Add a note in the summary output: "Selectors with backslash-escaped characters may need manual un-escaping before use. Try `eval` or `htmlsnapshot get` with simplified selectors like `#product-list a`."
- Auto-detect when suggested selectors contain escape sequences and provide simplified alternatives

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Click on category link navigates to about:blank

```bash
./b4w.ps1 goto "http://localhost:18080/ec/"
./b4w.ps1 snapshot --viewport 0 --stdout
# Note ref for "Electronics" link (e39)
./b4w.ps1 click e39
# Output: ✓ Clicked e39 — but Page URL is about:blank
```

#### Issue 2: PowerShell intercepts short flags (-v, -i, -e) when using b4w.ps1 from Git Bash

```bash
./b4w.ps1 snapshot -v 0 --stdout
./b4w.ps1 snapshot -i --stdout
./b4w.ps1 htmlsnapshot grep -i 'pattern'
./b4w.ps1 htmlsnapshot grep -e 'pattern1' -e 'pattern2'
```

#### Issue 3: Mock site product links contain escaped quotes causing malformed URLs

```bash
./b4w.ps1 goto "http://localhost:18080/ec/b?node=1292115012"
./b4w.ps1 snapshot --viewport 0 --stdout
# Observe: /url: \"/ec/dp/B0E000001\"
./b4w.ps1 click e168
# Navigates to: http://localhost:18080/%22/ec/dp/B0E000001/%22
```

#### Issue 4: `htmlsnapshot inspect` auto-discovery fails with escaped-quote CSS selectors

```bash
./b4w.ps1 goto "http://localhost:18080/ec/b?node=1292115012"
./b4w.ps1 htmlsnapshot
./b4w.ps1 htmlsnapshot inspect --max 6 --depth 3
```

#### Issue 5: b4w.sh fails — PowerShell cannot resolve Unix-style paths on Windows

```bash
./b4w.sh snapshot --viewport 0
```

#### Issue 6: Task design assumes product links on home page; home page has only category navigation

Following the task instructions: "Click on the first product link to navigate to a product detail page" from `http://localhost:18080/ec/`.

#### Issue 7: No price-specific CSS selectors on mock product pages

Try `htmlsnapshot get text ".price"` or `htmlsnapshot get text "[class*='price']"` on the product detail page.

#### Issue 8: `snapshot grep` outputs snapshot YAML path but not grep results without `--stdout`

```bash
./b4w.ps1 goto "http://localhost:18080/ec/"
./b4w.ps1 snapshot --viewport 0   # no --stdout
./b4w.ps1 snapshot grep "Electronics"
```

#### Issue 9: `htmlsnapshot grep '899'` returns "Pattern is required" — numeric-only pattern silently dropped

```bash
./b4w.ps1 goto "http://localhost:18080/ec/dp/B0E000001"
./b4w.ps1 htmlsnapshot
./b4w.ps1 htmlsnapshot grep '899'
```

#### Issue 10: WPSI summary shows escaped-quote selectors that can't be copy-pasted directly

```bash
./b4w.ps1 htmlsnapshot summary
```
Observe the "Suggested Commands" section shows selectors with backslash-escaped quotes like `#\"product-B0E000001\"`.

