---

# A. Task Result — Extracted Product Data Summary

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

# B. Execution Trace

**Commands used (in order):**
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

# C. Issues Found

### Issue 1: PowerShell intercepts `-v` and `-i` flags when using `b4w.ps1` directly

**Severity:** High

**Category:** UX / Reliability

**Reproduction:** `./b4w.ps1 snapshot -v 0` or `./b4w.ps1 snapshot -i`

**Expected:** Flags `-v` and `-i` are passed through to the browser4-cli binary.

**Actual:** PowerShell binds `-v` to `-Verbose` and `-i` to `-InformationAction`, stripping them from the CLI args. Result: `snapshot -v 0` becomes `snapshot 0`, producing "Unknown command: 'snapshot-0'" error. Even `./b4w.ps1 -- snapshot -v 0` fails with "Parameter cannot be processed because the parameter name '' is ambiguous."

**Root Cause:** The `b4w.ps1` script uses `param()` with `ValueFromRemainingArguments`, but PowerShell's parameter binder matches `-v`/`-i` against common parameters BEFORE `RemainingArgs` is populated. The `--` handling in the script (lines 48-50) strips `--` from `$RemainingArgs`, but PowerShell never reaches the script body because it fails during parameter binding. The `$SafeArgs` quoting approach (lines 147-151) only applies within the script body, not to the script's own parameter binding.

**Code Pointer:** `b4w.ps1:3-7` (`param()` block lacks `[CmdletBinding()]` to suppress common parameters)

**AI Suggested Improvement:**
- Add `[CmdletBinding(DefaultParameterSetName='Default')]` and explicitly disable common parameter matching, or use `--%` stop-parsing token
- Accept that `b4w.ps1` cannot pass through all flags and route all users to `b4w.sh` on non-Windows or `b4w.bat` on Windows
- Add a prominent warning in the help output when `-v`/`-i` are detected as consumed
- Document that `b4w.sh` is the **required** wrapper for all flag-bearing commands on all platforms

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: `htmlsnapshot grep` panics on multi-byte UTF-8 characters (⭐ emoji)

**Severity:** Critical

**Category:** Reliability

**Reproduction:** On the Electronics listing page (`/ec/b?node=1292115012`), capture an HTML snapshot, then run `./b4w.sh -- htmlsnapshot grep '\$[0-9.,]+'`

**Expected:** Grep results showing lines containing price patterns.

**Actual:** Rust panic: `thread 'main' panicked at src/main.rs:6545:35: byte index 2922 is not a char boundary; it is inside '⭐' (bytes 2920..2923)`. The CLI crashes.

**Root Cause:** The Rust code in `src/main.rs` at line 6545 uses byte-index slicing on a UTF-8 string without checking character boundaries. The page contains a ⭐ emoji (3 bytes in UTF-8: `0xE2 0xAD 0x90`), and the byte index operation lands in the middle of this multi-byte sequence.

**Code Pointer:** `cli/browser4-cli/src/main.rs:6545` — byte index slicing on a `&str` without using `.char_indices()` or `.chars()` iteration

**AI Suggested Improvement:**
- Use `.char_indices()` to iterate over character boundaries instead of raw byte indexing
- Add a unit test with multi-byte UTF-8 characters (emoji, CJK) in HTML content
- Consider using the `unicode-segmentation` crate or Rust's built-in char boundary methods
- Add a fuzz test specifically for `htmlsnapshot grep` with various Unicode inputs

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: Click on product link navigates to URL with escaped double quotes (`%22`)

**Severity:** High

**Category:** Reliability

**Reproduction:** 
1. Navigate to `http://localhost:18080/ec/b?node=1292115012`
2. Capture snapshot: `./b4w.sh -- snapshot -v 0 --stdout`
3. Click first product: `./b4w.sh click e575`

**Expected:** Browser navigates to `http://localhost:18080/ec/dp/B0E000001`.

**Actual:** Browser navigates to `http://localhost:18080/%22/ec/dp/B0E000001/%22` — the URL contains literal `%22` (URL-encoded `"`). The page loads with no title and likely broken content.

**Root Cause:** The accessibility tree snapshot serializes the `href` attribute with escaped quotes: `\"/ec/dp/B0E000001\"`. When the browser resolves this URL for navigation, the escaped quotes are treated as literal characters rather than being stripped/unescaped. The MockSite HTML has `href="\"/ec/dp/B0E000001\""` where the quotes are part of the attribute value, but the snapshot's YAML escaping adds another layer that the URL resolver doesn't properly handle.

**Code Pointer:** `PulsarWebDriver.kt` — likely in the URL resolution or href extraction logic used during click navigation

**AI Suggested Improvement:**
- Strip surrounding quotes from href values before resolving/navigating
- Add URL normalization after extracting href from accessibility tree to handle edge cases
- Validate URLs after resolution and log warnings for suspicious patterns (containing `%22`, `\"/`, etc.)
- Add a test case for href values containing escaped/surrounding quotes

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: `b4w.sh` prints deprecation-style warning on every invocation

**Severity:** Medium

**Category:** UX

**Reproduction:** Run any `./b4w.sh` command. Every invocation prints: `"It is strongly recommended to launch \`pwsh\` and run the .ps1 commands directly within the \`pwsh\` terminal."`

**Expected:** Either no message, or the message should appear only once per session, or it should be on stderr and skippable via `--quiet`.

**Actual:** The message appears before every single command output, adding noise and making `b4w.sh` feel like a second-class citizen — yet it's the only reliable way to pass flags to the CLI.

**Root Cause:** The `b4w.sh` script unconditionally echoes this message at line 16. It appears to be designed to discourage `b4w.sh` usage, but since `b4w.ps1` cannot handle flags, `b4w.sh` is effectively the required wrapper.

**Code Pointer:** `b4w.sh:16` — unconditional `echo` of the discouragement message

**AI Suggested Improvement:**
- Remove the message entirely, or print it to stderr so `--quiet`/`2>/dev/null` can suppress it
- Alternatively, print it only once per shell session (e.g., check for an env var like `B4W_SH_WARNED`)
- Update `b4w.ps1` to detect the flag-interception case and suggest `b4w.sh` only when needed, rather than always discouraging its use
- Consider making `b4w.sh` the recommended/default wrapper for all platforms

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: `$(./b4w.ps1)` invocation pattern in task instructions is non-functional

**Severity:** Medium

**Category:** Documentation

**Reproduction:** The task instructions say to invoke all commands as `$(./b4w.ps1) <command>`. In bash, `$(./b4w.ps1)` runs the script with no arguments (which prints help text to stdout), captures that output, and tries to execute it as a command — which fails.

**Expected:** Clear, working invocation instructions that match the actual CLI usage.

**Actual:** The `$(...)` pattern doesn't work. The correct invocation is `./b4w.sh <command>` (or `./b4w.ps1 <command>` for commands without short flags).

**Root Cause:** The `$(./b4w.ps1)` syntax implies command substitution, but `b4w.ps1` with no arguments outputs help text rather than a command path. This appears to be a documentation bug in the task/evaluation template itself.

**Code Pointer:** N/A — documentation issue in the evaluation task template

**AI Suggested Improvement:**
- Fix to `./b4w.sh <command>` as the recommended cross-platform invocation
- Or, create a shell function/alias that makes `$(./b4w.ps1)` resolve to the correct binary path
- Document the correct invocation prominently at the top of SKILL.md with platform-specific notes

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: `htmlsnapshot inspect` auto-discovery fails on product listing grid

**Severity:** Medium

**Category:** Reliability / Discoverability

**Reproduction:** On the Electronics listing page, capture `htmlsnapshot`, then run `./b4w.sh -- htmlsnapshot inspect --max 6 --depth 3`.

**Expected:** Auto-discovery finds the repeating product card pattern with selectors for titles and prices.

**Actual:** Returns `### Inspect: ".\"product-card\"" (0 matches)`. The auto-discovered selector `".\"product-card\""` (with escaped quotes) finds no matches. Had to manually inspect `div` elements and read the raw HTML export to discover the correct selectors.

**Root Cause:** The MockSite generates class names with literal double quotes in them (`class="\"product-card\""`). The auto-discovery picks up the literal class name (including quotes) but constructs a CSS selector that doesn't match because the CSS engine can't parse the escaped quotes correctly in this context. Even when the correct class names are discovered, they can't be used directly in CSS selectors without workarounds like `[class*="product-card"]`.

**Code Pointer:** The inspect/discovery logic that generates CSS selectors from class names — needs to handle class names containing special characters by escaping them or falling back to attribute selectors.

**AI Suggested Improvement:**
- When class names contain special characters (`"`, `\`, spaces), automatically generate attribute-based selectors (`[class*="..."]`) as alternatives
- Add a warning when auto-discovered selectors fail to match any elements
- Sanitize class names extracted from the DOM before using them in CSS selectors
- Fall back to structural selectors (`:nth-child`, tag hierarchy) when class-based selectors fail

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: CSS selectors with brackets/quotes are mangled by shell quoting

**Severity:** Medium

**Category:** UX

**Reproduction:** `./b4w.sh -- htmlsnapshot get all text '[class*="product-title"]'`

**Expected:** Selector `[class*="product-title"]` is passed intact to the CLI.

**Actual:** Shell quoting splits or mangles the selector, producing errors like `No elements matched "[class*=\"`.

**Root Cause:** CSS attribute selectors use brackets and quotes, which conflict with shell quoting. The `b4w.sh` wrapper applies its own quoting layer, and the combination of bash → pwsh → CLI argument parsing creates multiple layers where quoting can break.

**Code Pointer:** The argument passing chain: `b4w.sh` → `pwsh` → `b4w.ps1` → `$SafeArgs` → `Invoke-Expression` → CLI binary. Each layer transforms quoting.

**AI Suggested Improvement:**
- Promote `--sql @file.sql` and `--file` patterns as the primary/recommended approach for complex selectors (already documented but needs more emphasis)
- Add a `--selector-base64` flag for passing CSS selectors that contain shell-special characters (mentioned in SKILL.md but not tested)
- Add `--stdin` support for CSS selectors to match the existing `eval --stdin` pattern
- Add a "quoting guide" section to the getting-started / quickstart documentation showing the file-based workaround as step 1

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: Confusing error message when PowerShell consumes flags

**Severity:** Medium

**Category:** UX

**Reproduction:** `./b4w.ps1 snapshot -v 0`

**Expected:** A clear error like: "Flag '-v' was intercepted by PowerShell. Use './b4w.sh snapshot -v 0' instead, or pass flags after '--'."

**Actual:** `Error: Unknown command: 'snapshot-0'. Did you mean: 'snapshot'?`

**Root Cause:** When PowerShell strips `-v`, the remaining args `snapshot` and `0` are concatenated into `snapshot-0` by the CLI's argument parser, producing a misleading "unknown command" error that doesn't hint at the real cause.

**Code Pointer:** `b4w.ps1:3-7` (param binding) and `cli/browser4-cli/src/main.rs` (command parsing produces `snapshot-0` from `snapshot 0`)

**AI Suggested Improvement:**
- Add a heuristic in the CLI that detects when a "command" looks like it might be a concatenation of a valid command + a flag value (e.g., `snapshot-0` → flag `-v 0` was likely consumed)
- In `b4w.ps1`, detect if `-v`, `-i`, or other common flags are present in `$RemainingArgs` and warn if they're missing (indicating PowerShell consumed them)
- Add a `--help troubleshooting` section documenting this specific error and the `b4w.sh` workaround

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 9: No product links on e-commerce home page — forced to navigate through categories

**Severity:** Low

**Category:** UX (task design / MockSite)

**Reproduction:** Navigate to `http://localhost:18080/ec/`, capture snapshot — only category links are visible, no product links.

**Expected:** Either product links on the home page, or the task should acknowledge this is a two-step navigation (home → category → product detail).

**Actual:** Had to click "Electronics" category first, then find and click a product. The task says "Click on the first product link" which implies products are on the home page.

**Root Cause:** The MockSite home page only renders category navigation. The task description doesn't match the test fixture structure.

**Code Pointer:** N/A — this is a task design / MockSite data issue

**AI Suggested Improvement:**
- Update the task description to specify the two-step navigation: "Click a category, then click the first product"
- Alternatively, add a "Featured Products" section to the MockSite home page
- Add a note in the SKILL.md that some home pages only show categories, requiring navigation before product interaction

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

# D. Overall Assessment

| Metric | Value |
|---|---|
| **Task completion status** | 17/18 steps completed (step 10 skipped — no LLM key) |
| **Estimated task success rate** | ~85% (one step unavailable, multiple workarounds required) |
| **Number of issues found** | 9 |
| **Major blockers** | PowerShell flag interception (forced wrapper switch), UTF-8 grep panic (crash), broken URL navigation from escaped quotes |

**Most confusing aspects:**
1. The `$(./b4w.ps1)` invocation instruction in the task template doesn't work — had to figure out the correct invocation from reading source code
2. PowerShell silently consuming `-v`/`-i` flags produces a completely misleading error ("Unknown command: 'snapshot-0'") that took time to diagnose
3. The `b4w.sh` wrapper works but prints a discouraging message on every invocation, creating confusion about which wrapper to use
4. Shell quoting for CSS selectors with brackets/quotes requires either file-based workarounds or deep understanding of the quoting chain (bash → pwsh → CLI)

**Most valuable improvements:**
1. Fix the `htmlsnapshot grep` UTF-8 panic — any page with emoji or non-ASCII content will crash
2. Fix the URL resolution for href values containing escaped quotes — this silently navigates to broken pages
3. Resolve the PowerShell flag interception issue — it's the very first friction point for new Linux/macOS users
4. Add `--selector-base64` or file-based selector input for all commands that take CSS selectors
5. Quiet the `b4w.sh` warning message or make it suppressible

**Overall usability rating: 5/10**

The core CLI design is solid — the command structure is logical, the extraction tools (X-SQL, htmlsnapshot, eval) are powerful, and the documentation (SKILL.md) is comprehensive. However, the first-time experience is undermined by a chain of friction points: the wrapper selection confusion, flag interception, shell quoting battles, and an outright crash on a common operation (`htmlsnapshot grep` on a page with emoji). A new user would likely spend more time debugging invocation issues than actually completing their task. The most impactful fixes would be: (1) a single, reliable, quiet wrapper that works on all platforms, (2) the UTF-8 grep fix, and (3) making file-based input (`@file`, `--file`) the documented default for all CSS selectors.
