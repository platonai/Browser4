# Issues: Use Case 1: E-commerce Product Comparison (Single-site)

> **Source:** `20260710-111948-Use Case 1_ E-commerce Product Comparison (Single-site).full.md` | **Date:** 20260710-111948 | **Mode:** dev

## Scenario Background

### Task

The task was completed successfully. The comparison table is written to `D:/workspace/Browser4/Browser4-4.11/keyboard-comparison.md` with data for all three products. A workaround was required for Product 3 (KiiBoom Phantom81 V4) where the standard Amazon CSS selectors for rating and review count did not match — `eval` with JavaScript was used to locate the data in a non-standard DOM structure.

### Execution Context

| Step | Command | Result |
|------|---------|--------|
| Verify prerequisites | `cargo --version`, `java -version` | Rust 1.96.0, Java 17.0.14 |
| Help | `cargo run -- ... -- --help` | Comprehensive, well-organized output |
| Navigate to Amazon | `goto "https://www.amazon.com/"` | Backend auto-started in 6.8s, page loaded |
| Snapshot homepage | `snapshot -i -v 0` | Search box at e96, Go button at e784 |
| Search | `fill e96 "mechanical keyboard"` → `click e784` | Search results loaded |
| HTML snapshot | `htmlsnapshot` | 685 links, 100 interactive elements |
| Extract product URLs | `htmlsnapshot get all attr "a.a-link-normal.s-line-clamp-2" href --limit 3` | 3 product URLs retrieved |
| Product 1 (Logitech) | `goto` → `htmlsnapshot` → `get text #productTitle`, `get text span.a-price spa...

(truncated — see full.md for complete trace)

---

## Issues Found (7 issues)
> **Review complete:** 3 approved, 4 deferred/rejected

### Issue 2: `htmlsnapshot inspect` auto-discovery produces low-quality results on product pages

**Severity:** Medium
**Category:** UX

#### Overview

**Severity:** Medium
**Category:** UX

#### Reproduction

```bash
# Navigate to any Amazon product page, then:
htmlsnapshot                  # capture
htmlsnapshot inspect          # auto-discover
```

#### Expected Behavior

`htmlsnapshot inspect` should discover the product-centric CSS selectors (price, title, rating elements) since that's the primary content on a product detail page.

#### Actual Behavior

It analyzed `div` as the repeating pattern from `:root` and suggested selectors like `span.shortcut-key.nav-assistant-card-font` (keyboard shortcut hints) — navigation chrome, not product data.

#### Root Cause Analysis

The auto-discovery algorithm picks the most numerous repeating element pattern. On Amazon product pages, the nav elements repeat more often than product-specific elements. The algorithm does not weight content by visual prominence or proximity to the center of the page.

#### Code Pointer

`Likely in the `htmlsnapshot inspect` implementation's pattern-discovery logic.`

#### AI Suggested Improvement

- Weight discovered patterns by viewport position (elements near the center/top of the visible area score higher)
- Exclude known "chrome" selectors (nav, header, footer, sidebar patterns) from auto-discovery results
- When run without a selector argument on a page with a single prominent product, suggest trying `htmlsnapshot summary` as an alternative

#### Human Review

- [x] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- [ ] **DUPLICATE**
- **Notes:**

✅ **Fixed (2026-07-11):** Three improvements to `autoDiscoverRepeatingSelector()` in `MCPToolController.kt`:
1. **Chrome exclusion** — 0.3x penalty for patterns inside `<nav>`, `<header>`, `<footer>`, `<aside>` ancestors or elements with ARIA roles ("navigation", "banner", "contentinfo", "complementary")
2. **Viewport position weighting** — 1.3x boost for elements in prime content band (y: 100–800px), 0.7x penalty for deep page (y > 2500px), 0.8x for very top (y < 100px), using `vi` attribute position data
3. **Content-area bonus** — 1.5x boost for items inside `<main>`, `<article>`, or `role="main"` containers
CLI-side: Added `htmlsnapshot summary` suggestion when auto-discovery was triggered.

---

### Issue 4: `scroll` command output is uninformative

**Severity:** Low
**Category:** UX

#### Overview

**Severity:** Low
**Category:** UX

#### Reproduction

```bash
browser4-cli scroll down 1000
```

#### Expected Behavior

Output confirming the scroll action with before/after scroll position or viewport state, similar to how `goto` and `click` show the new page URL and title.

#### Actual Behavior

Output is simply `1000.0` — just the scroll amount, with no context about where the page is now or whether scrolling actually occurred.

#### Root Cause Analysis

The scroll command returns only the scroll delta value. It does not report the new scroll position or viewport state after scrolling.

#### Code Pointer

`CLI scroll command handler — the return value formatting.`

#### AI Suggested Improvement

- Show the new scroll position after scrolling (e.g., `Scrolled down 1000px → now at y=1000`)
- Add the current viewport information (visible range) to help users understand what's on screen
- Consider showing a brief snapshot preview after scroll, similar to how `click` and `goto` show a tip about running `snapshot -v 0`

#### Human Review

- [x] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- [ ] **DUPLICATE**
- **Notes:**

✅ **Already fixed (2026-07-11):** Previously addressed in the ecommerce-category-deep-analysis session. `handle_tool_command_with_options()` in `main.rs` already formats scroll output as "Scrolled down 1000px (position: 8000)" for both `scroll_by` and `browser_mouse_wheel` tools. The scroll command description in `commands.rs` already states "Output shows direction, amount, and new scroll position."

---

### Issue 7: `batch` command limitations prevent efficient multi-step extraction

**Severity:** Medium
**Category:** Product

#### Overview

**Severity:** Medium
**Category:** Product

#### Reproduction

Attempting to extract data from 3 products efficiently:
```bash
browser4-cli batch \
  "goto https://amazon.com/dp/ASIN1" "htmlsnapshot" "htmlsnapshot get text '#productTitle'" \
  "goto https://amazon.com/dp/ASIN2" "htmlsnapshot" "htmlsnapshot get text '#productTitle'"
```

#### Expected Behavior

Batch should be able to handle the full workflow: navigate → capture → extract → navigate → capture → extract.

#### Actual Behavior

Documentation states `batch` "only supports DOM operations (navigation, keyboard, mouse, core interactions, screenshots, tabs). Session lifecycle commands (`open`, `close`) must run separately." It's unclear whether `htmlsnapshot` and `htmlsnapshot get` (extraction commands) are supported in batch mode.

#### Root Cause Analysis

The batch command has a restricted command set. The documentation doesn't clearly enumerate which commands work in batch mode beyond the high-level categories listed.

#### Code Pointer

`Batch command handler in CLI source — command allowlist.`

#### AI Suggested Improvement

- Expand batch mode to support `htmlsnapshot` capture and `htmlsnapshot get` (extraction from stored snapshots is a read operation, not a DOM mutation)
- Clearly document the exact command allowlist for batch mode in `batch --help` and the README
- Consider adding a `script` mode that supports the full command set for multi-page workflows

#### Human Review

- [x] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- [ ] **DUPLICATE**
- **Notes:**

✅ **Fixed (2026-07-11):** Set `batch_supported: true` for `htmlsnapshot`, `htmlsnapshot-capture`, `htmlsnapshot-get`, and `htmlsnapshot-get-all` in `commands.rs`. These commands now work in batch mode through the default `op: "tool"` path in `compile_batch_request()`, using their existing tool names (`html_snapshot_capture`, `html_snapshot_scrape`, `html_snapshot_scrape_all`). Users can chain goto → htmlsnapshot → htmlsnapshot-get in batch mode for multi-page extraction workflows.

---

### Issue 1: CSS selector fragility across similar-looking Amazon product pages

**Severity:** High
**Category:** Reliability

#### Review Result

**Decision:** REJECT

**Summary:** - Add a `--scope <selector>` flag to `htmlsnapshot get` that restricts queries to a parent container (e.g., `--scope "#ppd"` for the main product area on Amazon)

---

### Issue 3: No built-in, documented Amazon/e-commerce extraction pattern

**Severity:** Medium
**Category:** Discoverability

#### Review Result

**Decision:** REJECT

**Summary:** - Add a "Common Recipes" section to the `--help` output with 2-3 high-value patterns (Amazon extraction, form login, etc.)

---

### Issue 5: Running multiple commands is slow due to per-invocation Rust compilation check

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** REJECT

**Summary:** - Document `cargo build` followed by direct binary invocation (`./target/debug/browser4-cli`) as a faster alternative for repeated commands

---

### Issue 6: grep-style alternation syntax mismatch between docs and Rust regex

**Severity:** Low
**Category:** Documentation

#### Review Result

**Decision:** DEFER

**Summary:** - Document the Rust regex syntax in the grep command help (`browser4-cli snapshot grep --help`) with examples using `|` instead of `\|`

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: CSS selector fragility across similar-looking Amazon product pages

```bash
# Works for Products 1 and 2:
htmlsnapshot get text "span.a-icon-alt"       # → "4.5 out of 5 stars"
htmlsnapshot get text "#acrCustomerReviewText" # → "(1,595)"

# Fails for Product 3 (B0GSDZZPTW):
htmlsnapshot get text "span.a-icon-alt"       # → "Previous set of slides" (carousel text!)
htmlsnapshot get text "#acrCustomerReviewText" # → No elements matched
```

#### Issue 2: `htmlsnapshot inspect` auto-discovery produces low-quality results on product pages

```bash
# Navigate to any Amazon product page, then:
htmlsnapshot                  # capture
htmlsnapshot inspect          # auto-discover
```

#### Issue 3: No built-in, documented Amazon/e-commerce extraction pattern

A first-time user searching for "how to extract Amazon price" would find:
1. `--help` — lists commands but not domain-specific patterns
2. SKILL.md — references `htmlsnapshot-scenarios.md` with "16 end-to-end recipes (e-commerce, Amazon...)" but this file is a reference link, not inline
3. No `browser4-cli amazon-extract` or `browser4-cli ecommerce` command exists

#### Issue 4: `scroll` command output is uninformative

```bash
browser4-cli scroll down 1000
```

#### Issue 5: Running multiple commands is slow due to per-invocation Rust compilation check

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot 2>&1
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get text "#title" 2>&1
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get text ".price" 2>&1
```

#### Issue 6: grep-style alternation syntax mismatch between docs and Rust regex

```bash
browser4-cli snapshot grep -i "rating\|star\|review"
```

**Actual output includes:**
```
Note: Converted grep-style alternation `\\|` to `|` in pattern. Rust regex uses bare `|` for alternation (like ERE/egrep). Use `snapshot grep -F` for literal matching.
```

#### Issue 7: `batch` command limitations prevent efficient multi-step extraction

Attempting to extract data from 3 products efficiently:
```bash
browser4-cli batch \
  "goto https://amazon.com/dp/ASIN1" "htmlsnapshot" "htmlsnapshot get text '#productTitle'" \
  "goto https://amazon.com/dp/ASIN2" "htmlsnapshot" "htmlsnapshot get text '#productTitle'"
```

#auto-approve
