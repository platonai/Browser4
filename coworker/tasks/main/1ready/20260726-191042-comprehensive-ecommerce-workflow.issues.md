# Issues: comprehensive-ecommerce-workflow

> **Source:** `20260726-191042-comprehensive-ecommerce-workflow.full.md` | **Date:** 20260726-191042 | **Mode:** dev

## Scenario Background

### Task

## Product Detail Page (4K OLED TV 55)

| Field | Value |
|---|---|
| **Title** | 4K OLED TV 55 |
| **Price** | $899.99 |
| **Category** | Electronics |
| **Rating** | 4.6 (521 reviews) |
| **Features** | 55 inch, HDR10+ |
| **Panel** | OLED |
| **Resolution** | 3840x2160 |
| **Badge** | Bestseller |
| **Image** | /ec/static/img/placeholder.png |

## Electronics Listing Page (6 Products)

| # | Title | Price |
|---|---|---|
| 1 | 4K OLED TV 55 | $899.99 |
| 2 | Wireless Noise-Cancelling Headphones | $199.99 |
| 3 | Portable Bluetooth Speaker | $49.99 |
| 4 | Smartphone 128GB | $599.00 |
| 5 | USB-C Hub 7-in-1 | $29.95 |
| 6 | Wireless Mouse | $24.99 |

---

### Execution Context

**Key Commands:**

1. `./b4w.ps1 help` — verified CLI is functional
2. `./b4w.ps1 goto "http://localhost:18080/ec/"` — navigated to home page
3. `./b4w.sh -- snapshot -v 0 --stdout` — full-page snapshot (had to switch to `b4w.sh` wrapper due to PowerShell `-v` interception)
4. `./b4w.sh -- snapshot -i --stdout` — interactive-only snapshot
5. `./b4w.sh click e300` — clicked "Electronics" category (no product links on home page)
6. `./b4w.sh -- snapshot -v 0 --stdout` — captured listing page snapshot
7. `./b4w.sh click e575` — clicked first product link (navigated to broken URL with `%22`)
8. `./b4w.sh goto "http://localhost:18080/ec/dp/B0E000001"` — directly navigated to fix broken URL
9. `./b4w.sh htmlsnapshot` — captured HTML snapshot of detail page
10. `./b4w.sh -- htmlsnapshot inspect --max 3 --depth 2` — discovered spec table selectors
11. `./b4w.sh -- htmlsnapshot inspect "div" --max 20 --depth 3` — discovered product info selectors
12. `./b4w.sh -- htmlsnapshot get all text "#product-features"` — extracted features
13. `./b4w.sh -- htmlsnapshot query --sql @product_query.sql` — X-SQL extraction (title, price, image)
14. `./b4w.sh -- htmlsnapshot grep '\$[0-9.,]+'` — price grep (worked on detail page)
15. LLM `extract` — skipped (no API key configured)
16. `./b4w.sh tab-new "http://localhost:18080/ec/b?node=1292115012"` — opened listing in new tab
17. `./b4w.sh htmlsnapshot` — captured listing HTML snapshot
18. `./b4w.sh -- htmlsnapshot query --sql @listing_query.sql` — X-SQL extracted 6 titles + prices
19. `./b4w.sh -- eval --json "document.querySelectorAll('#product-list a[href]').length"` — counted 6 product links
20. `./b4w.sh -- screenshot --filename electronics-listing-6-products.png` — screenshot
21. `./b4w.sh tab-select 1` then `reload` — switched back to detail tab and verified
22. `./b4w.sh -- snapshot grep "OLED"` and `snapshot grep -i "4K|HDR|inch|3840"` — found product attributes
23. `./b4w.sh -- state-save session-backup.json` — saved browser state

**Key decisions:**
- Switched from `b4w.ps1` to `b4w.sh` wrapper after `-v` flag was intercepted by PowerShell
- Navigated directly to product URL after click produced broken URL with escaped quotes
- Used file-based X-SQL (`@file`) to avoid shell quoting issues with CSS selectors
- Used attribute selectors (`[class*="..."]`) to work around MockSite's escaped-quote class names

**Workarounds required:**
1. Must use `b4w.sh` or `--` passthrough to pass flags to CLI
2. Must write X-SQL to files to avoid shell mangling of CSS selectors
3. Must navigate directly to URLs when click produces corrupted URLs

---

---

## Issues Found (9 issues)

### Issue 1: `htmlsnapshot grep` panics on multi-byte UTF-8 characters (⭐ emoji)

**Severity:** Critical
**Category:** Reliability

#### Reproduction

On the Electronics listing page (`/ec/b?node=1292115012`), capture an HTML snapshot, then run `./b4w.sh -- htmlsnapshot grep '\$[0-9.,]+'`

#### Expected Behavior

Grep results showing lines containing price patterns.

#### Actual Behavior

Rust panic: `thread 'main' panicked at src/main.rs:6545:35: byte index 2922 is not a char boundary; it is inside '⭐' (bytes 2920..2923)`. The CLI crashes.

#### Root Cause Analysis

The Rust code in `src/main.rs` at line 6545 uses byte-index slicing on a UTF-8 string without checking character boundaries. The page contains a ⭐ emoji (3 bytes in UTF-8: `0xE2 0xAD 0x90`), and the byte index operation lands in the middle of this multi-byte sequence.

#### Code Pointer

``cli/browser4-cli/src/main.rs:6545` — byte index slicing on a `&str` without using `.char_indices()` or `.chars()` iteration`

#### AI Suggested Improvement

- Use `.char_indices()` to iterate over character boundaries instead of raw byte indexing
- Add a unit test with multi-byte UTF-8 characters (emoji, CJK) in HTML content
- Consider using the `unicode-segmentation` crate or Rust's built-in char boundary methods
- Add a fuzz test specifically for `htmlsnapshot grep` with various Unicode inputs

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Clear crash bug — byte-index slicing on a `&str` at line 6545 without char-boundary checks. Critical severity is correct. Fix is straightforward (use `.char_indices()` or `.chars()`). Should include a regression test with multi-byte characters.

---

### Issue 2: PowerShell intercepts `-v` and `-i` flags when using `b4w.ps1` directly

**Severity:** High
**Category:** UX / Reliability

#### Reproduction

`./b4w.ps1 snapshot -v 0` or `./b4w.ps1 snapshot -i`

#### Expected Behavior

Flags `-v` and `-i` are passed through to the browser4-cli binary.

#### Actual Behavior

PowerShell binds `-v` to `-Verbose` and `-i` to `-InformationAction`, stripping them from the CLI args. Result: `snapshot -v 0` becomes `snapshot 0`, producing "Unknown command: 'snapshot-0'" error. Even `./b4w.ps1 -- snapshot -v 0` fails with "Parameter cannot be processed because the parameter name '' is ambiguous."

#### Root Cause Analysis

The `b4w.ps1` script uses `param()` with `ValueFromRemainingArguments`, but PowerShell's parameter binder matches `-v`/`-i` against common parameters BEFORE `RemainingArgs` is populated. The `--` handling in the script (lines 48-50) strips `--` from `$RemainingArgs`, but PowerShell never reaches the script body because it fails during parameter binding. The `$SafeArgs` quoting approach (lines 147-151) only applies within the script body, not to the script's own parameter binding.

#### Code Pointer

``b4w.ps1:3-7` (`param()` block lacks `[CmdletBinding()]` to suppress common parameters)`

#### AI Suggested Improvement

- Add `[CmdletBinding(DefaultParameterSetName='Default')]` and explicitly disable common parameter matching, or use `--%` stop-parsing token
- Accept that `b4w.ps1` cannot pass through all flags and route all users to `b4w.sh` on non-Windows or `b4w.bat` on Windows
- Add a prominent warning in the help output when `-v`/`-i` are detected as consumed
- Document that `b4w.sh` is the **required** wrapper for all flag-bearing commands on all platforms

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Real design flaw in `b4w.ps1` — PowerShell's common-parameter binder runs before `ValueFromRemainingArguments` populates, so `-v`/`-i` are irrecoverably stripped. Issue 8 (confusing error message) is a DUPLICATE of this root cause; fold Issue 8's "add a heuristic error" suggestion into this issue's fix plan. The `--%` stop-parsing token or `[CmdletBinding()]` suppression are the right long-term fixes.

---

### Issue 3: Click on product link navigates to URL with escaped double quotes (`%22`)

**Severity:** High
**Category:** Reliability

#### Reproduction

1. Navigate to `http://localhost:18080/ec/b?node=1292115012`
2. Capture snapshot: `./b4w.sh -- snapshot -v 0 --stdout`
3. Click first product: `./b4w.sh click e575`

#### Expected Behavior

Browser navigates to `http://localhost:18080/ec/dp/B0E000001`.

#### Actual Behavior

Browser navigates to `http://localhost:18080/%22/ec/dp/B0E000001/%22` — the URL contains literal `%22` (URL-encoded `"`). The page loads with no title and likely broken content.

#### Root Cause Analysis

The accessibility tree snapshot serializes the `href` attribute with escaped quotes: `\"/ec/dp/B0E000001\"`. When the browser resolves this URL for navigation, the escaped quotes are treated as literal characters rather than being stripped/unescaped. The MockSite HTML has `href="\"/ec/dp/B0E000001\""` where the quotes are part of the attribute value, but the snapshot's YAML escaping adds another layer that the URL resolver doesn't properly handle.

#### Code Pointer

``PulsarWebDriver.kt` — likely in the URL resolution or href extraction logic used during click navigation`

#### AI Suggested Improvement

- Strip surrounding quotes from href values before resolving/navigating
- Add URL normalization after extracting href from accessibility tree to handle edge cases
- Validate URLs after resolution and log warnings for suspicious patterns (containing `%22`, `\"/`, etc.)
- Add a test case for href values containing escaped/surrounding quotes

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] URL resolution doesn't strip surrounding escaped quotes from href values extracted from the accessibility tree, producing `%22`-polluted URLs. Shares root cause with Issue 6 (MockSite embeds literal `"` in attributes), but the fix belongs in the URL resolver, not the MockSite — the resolver should be robust to malformed input regardless.

---

### Issue 4: `b4w.sh` prints deprecation-style warning on every invocation

**Severity:** Medium
**Category:** UX

#### Reproduction

Run any `./b4w.sh` command. Every invocation prints: `"It is strongly recommended to launch \`pwsh\` and run the .ps1 commands directly within the \`pwsh\` terminal."`

#### Expected Behavior

Either no message, or the message should appear only once per session, or it should be on stderr and skippable via `--quiet`.

#### Actual Behavior

The message appears before every single command output, adding noise and making `b4w.sh` feel like a second-class citizen — yet it's the only reliable way to pass flags to the CLI.

#### Root Cause Analysis

The `b4w.sh` script unconditionally echoes this message at line 16. It appears to be designed to discourage `b4w.sh` usage, but since `b4w.ps1` cannot handle flags, `b4w.sh` is effectively the required wrapper.

#### Code Pointer

``b4w.sh:16` — unconditional `echo` of the discouragement message`

#### AI Suggested Improvement

- Remove the message entirely, or print it to stderr so `--quiet`/`2>/dev/null` can suppress it
- Alternatively, print it only once per shell session (e.g., check for an env var like `B4W_SH_WARNED`)
- Update `b4w.ps1` to detect the flag-interception case and suggest `b4w.sh` only when needed, rather than always discouraging its use
- Consider making `b4w.sh` the recommended/default wrapper for all platforms

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The unconditional "use pwsh instead" echo on every `b4w.sh` invocation is counterproductive given that `b4w.sh` is the *only* reliable flag-passing wrapper (per Issue 2). Improvement: remove the message entirely, or gate it behind a `B4W_SH_WARNED` env-var check so it prints once per session. The suggested "make b4w.sh the recommended wrapper" framing is the correct strategic direction.

---

### Issue 5: `$(./b4w.ps1)` invocation pattern in task instructions is non-functional

**Severity:** Medium
**Category:** Documentation

#### Reproduction

The task instructions say to invoke all commands as `$(./b4w.ps1) <command>`. In bash, `$(./b4w.ps1)` runs the script with no arguments (which prints help text to stdout), captures that output, and tries to execute it as a command — which fails.

#### Expected Behavior

Clear, working invocation instructions that match the actual CLI usage.

#### Actual Behavior

The `$(...)` pattern doesn't work. The correct invocation is `./b4w.sh <command>` (or `./b4w.ps1 <command>` for commands without short flags).

#### Root Cause Analysis

The `$(./b4w.ps1)` syntax implies command substitution, but `b4w.ps1` with no arguments outputs help text rather than a command path. This appears to be a documentation bug in the task/evaluation template itself.

#### Code Pointer

`N/A — documentation issue in the evaluation task template`

#### AI Suggested Improvement

- Fix to `./b4w.sh <command>` as the recommended cross-platform invocation
- Or, create a shell function/alias that makes `$(./b4w.ps1)` resolve to the correct binary path
- Document the correct invocation prominently at the top of SKILL.md with platform-specific notes

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Documentation bug — `$(./b4w.ps1)` is command substitution that executes the help text as a command, which fails. The fix is trivial: replace with `./b4w.sh` (or `./b4w.ps1` for flag-free commands) in the evaluation template.

---

### Issue 6: `htmlsnapshot inspect` auto-discovery fails on product listing grid

**Severity:** Medium
**Category:** Reliability / Discoverability

#### Reproduction

On the Electronics listing page, capture `htmlsnapshot`, then run `./b4w.sh -- htmlsnapshot inspect --max 6 --depth 3`.

#### Expected Behavior

Auto-discovery finds the repeating product card pattern with selectors for titles and prices.

#### Actual Behavior

Returns `### Inspect: ".\"product-card\"" (0 matches)`. The auto-discovered selector `".\"product-card\""` (with escaped quotes) finds no matches. Had to manually inspect `div` elements and read the raw HTML export to discover the correct selectors.

#### Root Cause Analysis

The MockSite generates class names with literal double quotes in them (`class="\"product-card\""`). The auto-discovery picks up the literal class name (including quotes) but constructs a CSS selector that doesn't match because the CSS engine can't parse the escaped quotes correctly in this context. Even when the correct class names are discovered, they can't be used directly in CSS selectors without workarounds like `[class*="product-card"]`.

#### Code Pointer

`The inspect/discovery logic that generates CSS selectors from class names — needs to handle class names containing special characters by escaping them or falling back to attribute selectors.`

#### AI Suggested Improvement

- When class names contain special characters (`"`, `\`, spaces), automatically generate attribute-based selectors (`[class*="..."]`) as alternatives
- Add a warning when auto-discovered selectors fail to match any elements
- Sanitize class names extracted from the DOM before using them in CSS selectors
- Fall back to structural selectors (`:nth-child`, tag hierarchy) when class-based selectors fail

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Auto-discovered CSS selectors from class names containing literal `"` characters are unusable. Shares root cause with Issue 3 (MockSite's quoted attributes), but the fix is in the discovery/inspect logic: sanitize class names before selector construction, and fall back to `[class*="..."]` attribute selectors when class names contain characters illegal in CSS identifiers. Cross-reference Issue 3.

---

### Issue 7: CSS selectors with brackets/quotes are mangled by shell quoting

**Severity:** Medium
**Category:** UX

#### Reproduction

`./b4w.sh -- htmlsnapshot get all text '[class*="product-title"]'`

#### Expected Behavior

Selector `[class*="product-title"]` is passed intact to the CLI.

#### Actual Behavior

Shell quoting splits or mangles the selector, producing errors like `No elements matched "[class*=\"`.

#### Root Cause Analysis

CSS attribute selectors use brackets and quotes, which conflict with shell quoting. The `b4w.sh` wrapper applies its own quoting layer, and the combination of bash → pwsh → CLI argument parsing creates multiple layers where quoting can break.

#### Code Pointer

`The argument passing chain: `b4w.sh` → `pwsh` → `b4w.ps1` → `$SafeArgs` → `Invoke-Expression` → CLI binary. Each layer transforms quoting.`

#### AI Suggested Improvement

- Promote `--sql @file.sql` and `--file` patterns as the primary/recommended approach for complex selectors (already documented but needs more emphasis)
- Add a `--selector-base64` flag for passing CSS selectors that contain shell-special characters (mentioned in SKILL.md but not tested)
- Add `--stdin` support for CSS selectors to match the existing `eval --stdin` pattern
- Add a "quoting guide" section to the getting-started / quickstart documentation showing the file-based workaround as step 1

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] CSS attribute selectors like `[class*="product-title"]` conflict with shell quoting across the bash→pwsh→CLI chain. The suggested improvements (promote `--file`/`--sql @file` patterns, add `--selector-base64`, add `--stdin` for selectors) are all reasonable. Since Issues 2/4/7 all touch the wrapper experience, consider addressing them together in a "shell wrapper hardening" pass.

---

### Issue 8: Confusing error message when PowerShell consumes flags

**Severity:** Medium
**Category:** UX

#### Reproduction

`./b4w.ps1 snapshot -v 0`

#### Expected Behavior

A clear error like: "Flag '-v' was intercepted by PowerShell. Use './b4w.sh snapshot -v 0' instead, or pass flags after '--'."

#### Actual Behavior

`Error: Unknown command: 'snapshot-0'. Did you mean: 'snapshot'?`

#### Root Cause Analysis

When PowerShell strips `-v`, the remaining args `snapshot` and `0` are concatenated into `snapshot-0` by the CLI's argument parser, producing a misleading "unknown command" error that doesn't hint at the real cause.

#### Code Pointer

``b4w.ps1:3-7` (param binding) and `cli/browser4-cli/src/main.rs` (command parsing produces `snapshot-0` from `snapshot 0`)`

#### AI Suggested Improvement

- Add a heuristic in the CLI that detects when a "command" looks like it might be a concatenation of a valid command + a flag value (e.g., `snapshot-0` → flag `-v 0` was likely consumed)
- In `b4w.ps1`, detect if `-v`, `-i`, or other common flags are present in `$RemainingArgs` and warn if they're missing (indicating PowerShell consumed them)
- Add a `--help troubleshooting` section documenting this specific error and the `b4w.sh` workaround

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [x] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DUPLICATE] This is a symptom of Issue 2's root cause (PowerShell consumes `-v`/`-i`). When PowerShell strips `-v 0`, the remaining args `snapshot` and `0` concatenate into `snapshot-0`, producing a misleading error. Merge this into Issue 2 — the fix for Issue 2 (preventing flag consumption) eliminates this symptom. If a defense-in-depth heuristic error is desired, track it as a sub-task under Issue 2.

---

### Issue 9: No product links on e-commerce home page — forced to navigate through categories

**Severity:** Low
**Category:** UX (task design / MockSite)

#### Reproduction

Navigate to `http://localhost:18080/ec/`, capture snapshot — only category links are visible, no product links.

#### Expected Behavior

Either product links on the home page, or the task should acknowledge this is a two-step navigation (home → category → product detail).

#### Actual Behavior

Had to click "Electronics" category first, then find and click a product. The task says "Click on the first product link" which implies products are on the home page.

#### Root Cause Analysis

The MockSite home page only renders category navigation. The task description doesn't match the test fixture structure.

#### Code Pointer

`N/A — this is a task design / MockSite data issue`

#### AI Suggested Improvement

- Update the task description to specify the two-step navigation: "Click a category, then click the first product"
- Alternatively, add a "Featured Products" section to the MockSite home page
- Add a note in the SKILL.md that some home pages only show categories, requiring navigation before product interaction

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Valid mismatch between task instructions ("Click on the first product link") and MockSite structure (home page has only category links). Low severity is correct — this is a task-design polish item. Fix the task description to acknowledge the two-step navigation, or add featured products to the MockSite home page.

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: `htmlsnapshot grep` panics on multi-byte UTF-8 characters (⭐ emoji)

On the Electronics listing page (`/ec/b?node=1292115012`), capture an HTML snapshot, then run `./b4w.sh -- htmlsnapshot grep '\$[0-9.,]+'`

#### Issue 2: PowerShell intercepts `-v` and `-i` flags when using `b4w.ps1` directly

`./b4w.ps1 snapshot -v 0` or `./b4w.ps1 snapshot -i`

#### Issue 3: Click on product link navigates to URL with escaped double quotes (`%22`)

1. Navigate to `http://localhost:18080/ec/b?node=1292115012`
2. Capture snapshot: `./b4w.sh -- snapshot -v 0 --stdout`
3. Click first product: `./b4w.sh click e575`

#### Issue 4: `b4w.sh` prints deprecation-style warning on every invocation

Run any `./b4w.sh` command. Every invocation prints: `"It is strongly recommended to launch \`pwsh\` and run the .ps1 commands directly within the \`pwsh\` terminal."`

#### Issue 5: `$(./b4w.ps1)` invocation pattern in task instructions is non-functional

The task instructions say to invoke all commands as `$(./b4w.ps1) <command>`. In bash, `$(./b4w.ps1)` runs the script with no arguments (which prints help text to stdout), captures that output, and tries to execute it as a command — which fails.

#### Issue 6: `htmlsnapshot inspect` auto-discovery fails on product listing grid

On the Electronics listing page, capture `htmlsnapshot`, then run `./b4w.sh -- htmlsnapshot inspect --max 6 --depth 3`.

#### Issue 7: CSS selectors with brackets/quotes are mangled by shell quoting

`./b4w.sh -- htmlsnapshot get all text '[class*="product-title"]'`

#### Issue 8: Confusing error message when PowerShell consumes flags

`./b4w.ps1 snapshot -v 0`

#### Issue 9: No product links on e-commerce home page — forced to navigate through categories

Navigate to `http://localhost:18080/ec/`, capture snapshot — only category links are visible, no product links.

