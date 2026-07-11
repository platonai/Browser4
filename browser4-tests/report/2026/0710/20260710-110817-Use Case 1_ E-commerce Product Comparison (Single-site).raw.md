---

# Evaluation Report: browser4-cli — Amazon Mechanical Keyboard Comparison

## A. Task Result

The task was completed successfully. The comparison table is written to `D:/workspace/Browser4/Browser4-4.11/keyboard-comparison.md` with data for all three products. A workaround was required for Product 3 (KiiBoom Phantom81 V4) where the standard Amazon CSS selectors for rating and review count did not match — `eval` with JavaScript was used to locate the data in a non-standard DOM structure.

## B. Execution Trace

| Step | Command | Result |
|------|---------|--------|
| Verify prerequisites | `cargo --version`, `java -version` | Rust 1.96.0, Java 17.0.14 |
| Help | `cargo run -- ... -- --help` | Comprehensive, well-organized output |
| Navigate to Amazon | `goto "https://www.amazon.com/"` | Backend auto-started in 6.8s, page loaded |
| Snapshot homepage | `snapshot -i -v 0` | Search box at e96, Go button at e784 |
| Search | `fill e96 "mechanical keyboard"` → `click e784` | Search results loaded |
| HTML snapshot | `htmlsnapshot` | 685 links, 100 interactive elements |
| Extract product URLs | `htmlsnapshot get all attr "a.a-link-normal.s-line-clamp-2" href --limit 3` | 3 product URLs retrieved |
| Product 1 (Logitech) | `goto` → `htmlsnapshot` → `get text #productTitle`, `get text span.a-price span.a-offscreen`, `get text span.a-icon-alt`, `get text #acrCustomerReviewText` | $56.97, 4.5★, 1,595 reviews |
| Product 2 (Redragon) | Same pattern | $36.99, 4.5★, 2,168 reviews |
| Product 3 (KiiBoom) | Standard selectors failed; used `eval` with JS to find rating (`.a-icon-row .a-icon-alt` → 4.3★) and review count (276 in `anonCarousel1`) | $199.99, 4.3★, 276 reviews |
| Write output | Wrote `keyboard-comparison.md` | Markdown comparison table |
| Close | `close` | Browser terminated cleanly |

**Workarounds required:**
1. Product 3 rating: `span.a-icon-alt` returned "Previous set of slides" (image carousel text); required switching to `.a-icon-row .a-icon-alt` via `eval`
2. Product 3 review count: `#acrCustomerReviewText` returned nothing; required `eval` with iterative DOM traversal to find "276" in the `anonCarousel1` comparison carousel
3. General: CSS selectors for Amazon required prior domain knowledge — no built-in site-specific extraction templates

## C. Issues Found

### Issue 1: CSS selector fragility across similar-looking Amazon product pages

**Severity:** High

**Category:** Reliability

**Reproduction:**
```bash
# Works for Products 1 and 2:
htmlsnapshot get text "span.a-icon-alt"       # → "4.5 out of 5 stars"
htmlsnapshot get text "#acrCustomerReviewText" # → "(1,595)"

# Fails for Product 3 (B0GSDZZPTW):
htmlsnapshot get text "span.a-icon-alt"       # → "Previous set of slides" (carousel text!)
htmlsnapshot get text "#acrCustomerReviewText" # → No elements matched
```

**Expected:** The same selectors should work consistently for the same data fields across Amazon product detail pages.

**Actual:** Product 3 had its rating in `.a-icon-row .a-icon-alt` (not a direct `span.a-icon-alt`) and its review count in a carousel widget (`anonCarousel1`) rather than the standard `#acrCustomerReviewText` element. The first `span.a-icon-alt` match was an image carousel label.

**Root Cause:** Amazon renders product pages with multiple layout variants depending on product category, available content, and A/B tests. The `span.a-icon-alt` selector is too broad — it matches image carousel star ratings as well as the main product rating. The CLI has no mechanism to scope extraction to the "main product area" vs. "related content areas."

**Code Pointer:** The issue is not in the CLI but in the extraction strategy. A potential mitigation would be in the `htmlsnapshot get` command to support scoping to a parent container, or a documentation note about selector specificity.

**AI Suggested Improvement:**
- Add a `--scope <selector>` flag to `htmlsnapshot get` that restricts queries to a parent container (e.g., `--scope "#ppd"` for the main product area on Amazon)
- Add a dedicated `htmlsnapshot get rating` / `htmlsnapshot get reviews` high-level command that auto-discovers common rating/review patterns using heuristics (e.g., find text matching "X.X out of 5 stars" near the product title area, find numbers near "ratings" or "reviews" text)
- Document the CSS selector fragility in the htmlsnapshot-scenarios.md Amazon recipe with alternative selectors and a fallback strategy (eval + JS)

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: `htmlsnapshot inspect` auto-discovery produces low-quality results on product pages

**Severity:** Medium

**Category:** UX

**Reproduction:**
```bash
# Navigate to any Amazon product page, then:
htmlsnapshot                  # capture
htmlsnapshot inspect          # auto-discover
```

**Expected:** `htmlsnapshot inspect` should discover the product-centric CSS selectors (price, title, rating elements) since that's the primary content on a product detail page.

**Actual:** It analyzed `div` as the repeating pattern from `:root` and suggested selectors like `span.shortcut-key.nav-assistant-card-font` (keyboard shortcut hints) — navigation chrome, not product data.

**Root Cause:** The auto-discovery algorithm picks the most numerous repeating element pattern. On Amazon product pages, the nav elements repeat more often than product-specific elements. The algorithm does not weight content by visual prominence or proximity to the center of the page.

**Code Pointer:** Likely in the `htmlsnapshot inspect` implementation's pattern-discovery logic.

**AI Suggested Improvement:**
- Weight discovered patterns by viewport position (elements near the center/top of the visible area score higher)
- Exclude known "chrome" selectors (nav, header, footer, sidebar patterns) from auto-discovery results
- When run without a selector argument on a page with a single prominent product, suggest trying `htmlsnapshot summary` as an alternative

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: No built-in, documented Amazon/e-commerce extraction pattern

**Severity:** Medium

**Category:** Discoverability

**Reproduction:**
A first-time user searching for "how to extract Amazon price" would find:
1. `--help` — lists commands but not domain-specific patterns
2. SKILL.md — references `htmlsnapshot-scenarios.md` with "16 end-to-end recipes (e-commerce, Amazon...)" but this file is a reference link, not inline
3. No `browser4-cli amazon-extract` or `browser4-cli ecommerce` command exists

**Expected:** A discoverable, documented path for common e-commerce extraction tasks. Either a dedicated subcommand, an example in `--help` output, or a prominent quick-start pattern.

**Actual:** The user must read the full SKILL.md reference map to find `htmlsnapshot-scenarios.md`, navigate to that file, and read the Amazon recipe. The `--help` output and README examples don't mention Amazon or e-commerce at all.

**Root Cause:** Domain-specific recipes are buried in reference documentation that requires multiple file navigations to reach. There's no progressive disclosure from the CLI help output to these recipes.

**Code Pointer:** Documentation structure in `skills/browser4-cli/` and the CLI help text generation.

**AI Suggested Improvement:**
- Add a "Common Recipes" section to the `--help` output with 2-3 high-value patterns (Amazon extraction, form login, etc.)
- Add a `browser4-cli recipes` or `browser4-cli examples` command that lists available scenario documents
- Include the Amazon e-commerce recipe inline in the SKILL.md quick patterns section rather than only in a separate reference file
- Add a `--recipe <name>` flag to relevant commands that applies known-good selectors for popular sites

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: `scroll` command output is uninformative

**Severity:** Low

**Category:** UX

**Reproduction:**
```bash
browser4-cli scroll down 1000
```

**Expected:** Output confirming the scroll action with before/after scroll position or viewport state, similar to how `goto` and `click` show the new page URL and title.

**Actual:** Output is simply `1000.0` — just the scroll amount, with no context about where the page is now or whether scrolling actually occurred.

**Root Cause:** The scroll command returns only the scroll delta value. It does not report the new scroll position or viewport state after scrolling.

**Code Pointer:** CLI scroll command handler — the return value formatting.

**AI Suggested Improvement:**
- Show the new scroll position after scrolling (e.g., `Scrolled down 1000px → now at y=1000`)
- Add the current viewport information (visible range) to help users understand what's on screen
- Consider showing a brief snapshot preview after scroll, similar to how `click` and `goto` show a tip about running `snapshot -v 0`

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: Running multiple commands is slow due to per-invocation Rust compilation check

**Severity:** Low

**Category:** UX

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot 2>&1
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get text "#title" 2>&1
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get text ".price" 2>&1
```

**Expected:** Near-instant execution of subsequent commands since the binary hasn't changed.

**Actual:** Each invocation incurs a ~0.2-0.3s "Finished dev profile" compilation check, adding up across many commands. The initial build took 17s.

**Root Cause:** `cargo run` always checks whether the binary needs recompilation, even when source files are unchanged. In dev mode, there's no way to skip this check.

**Code Pointer:** This is inherent to `cargo run` workflow. Not a browser4-cli bug per se, but a development-mode UX friction.

**AI Suggested Improvement:**
- Document `cargo build` followed by direct binary invocation (`./target/debug/browser4-cli`) as a faster alternative for repeated commands
- Consider adding a shell alias suggestion in the development docs: `alias b4='cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet --'`
- The `--quiet` flag helps reduce noise but doesn't speed up execution

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: grep-style alternation syntax mismatch between docs and Rust regex

**Severity:** Low

**Category:** Documentation

**Reproduction:**
```bash
browser4-cli snapshot grep -i "rating\|star\|review"
```

**Actual output includes:**
```
Note: Converted grep-style alternation `\\|` to `|` in pattern. Rust regex uses bare `|` for alternation (like ERE/egrep). Use `snapshot grep -F` for literal matching.
```

**Expected:** Either the docs should use the Rust regex syntax (`rating|star|review`), or the conversion should be silent.

**Actual:** The CLI silently converts the pattern and emits a note, which is helpful but indicates a design tension — the "grep" name implies grep syntax, but the implementation uses Rust regex.

**Root Cause:** The `snapshot grep` and `htmlsnapshot grep` commands use Rust's regex engine which uses `|` for alternation (ERE syntax), while traditional `grep` uses `\|` (BRE syntax). The CLI bridges this with auto-conversion but the note adds noise.

**Code Pointer:** The grep command handlers in the CLI source.

**AI Suggested Improvement:**
- Document the Rust regex syntax in the grep command help (`browser4-cli snapshot grep --help`) with examples using `|` instead of `\|`
- Consider making the conversion silent (suppress the note) since it's an implementation detail
- Or: use `--help` to show the correct syntax: `snapshot grep -i "rating|star|review"` (no backslash)

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: `batch` command limitations prevent efficient multi-step extraction

**Severity:** Medium

**Category:** Product

**Reproduction:**
Attempting to extract data from 3 products efficiently:
```bash
browser4-cli batch \
  "goto https://amazon.com/dp/ASIN1" "htmlsnapshot" "htmlsnapshot get text '#productTitle'" \
  "goto https://amazon.com/dp/ASIN2" "htmlsnapshot" "htmlsnapshot get text '#productTitle'"
```

**Expected:** Batch should be able to handle the full workflow: navigate → capture → extract → navigate → capture → extract.

**Actual:** Documentation states `batch` "only supports DOM operations (navigation, keyboard, mouse, core interactions, screenshots, tabs). Session lifecycle commands (`open`, `close`) must run separately." It's unclear whether `htmlsnapshot` and `htmlsnapshot get` (extraction commands) are supported in batch mode.

**Root Cause:** The batch command has a restricted command set. The documentation doesn't clearly enumerate which commands work in batch mode beyond the high-level categories listed.

**Code Pointer:** Batch command handler in CLI source — command allowlist.

**AI Suggested Improvement:**
- Expand batch mode to support `htmlsnapshot` capture and `htmlsnapshot get` (extraction from stored snapshots is a read operation, not a DOM mutation)
- Clearly document the exact command allowlist for batch mode in `batch --help` and the README
- Consider adding a `script` mode that supports the full command set for multi-page workflows

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## D. Overall Assessment

### Task Completion Status
**✅ Completed.** All three products were successfully compared and written to `keyboard-comparison.md`.

### Estimated Task Success Rate
**85%** — 2 of 3 products extracted cleanly with standard CSS selectors; Product 3 required a workaround (JavaScript `eval`) to handle non-standard DOM layout.

### Number of Issues Found
**7 issues** (1 High, 3 Medium, 3 Low)

### Major Blockers
None that prevented task completion. The main friction was:
1. CSS selector fragility on Amazon Product 3 — required falling back to `eval` with custom JS
2. No progressive discovery path from `--help` to domain-specific extraction recipes

### Most Confusing Aspects
1. **Which extraction method to use when**: The SKILL.md decision tree is excellent but requires reading a separate file. The `htmlsnapshot` output's "Try these next" hints are helpful but generic — they don't adapt to the page type.
2. **Selector discovery workflow**: `htmlsnapshot inspect` auto-discovered nav elements instead of product data. `htmlsnapshot summary` might have been better but wasn't suggested.
3. **When to use `get` vs `get all` vs `query`**: The distinction is documented but the user must remember to check the docs.

### Most Valuable Improvements
1. **Session persistence** — browser state survived across all CLI invocations seamlessly
2. **Auto-start backend** — zero-config, first command just worked
3. **HTML snapshot + extraction combo** — capturing once and querying multiple times is efficient and well-designed
4. **Element refs with bounding boxes** — makes interactive targeting precise
5. **Comprehensive `--help` output** — well-organized, discoverable command structure
6. **Error messages** — "No elements matched" errors include actionable suggestions (`Try htmlsnapshot inspect`)

### Overall Usability Rating: **7.5 / 10**

**Strengths:** Excellent architecture (session persistence, snapshot-then-query model), comprehensive documentation (SKILL.md is thorough), clean auto-start, good error messages with actionable hints.

**Weaknesses:** CSS selector fragility on real-world sites, `inspect` auto-discovery doesn't prioritize content over chrome, domain-specific recipes are buried in reference docs, batch mode limitations prevent efficient multi-page workflows, per-invocation compilation overhead in dev mode.

The tool is production-quality for users who invest time in learning the extraction model and are comfortable with CSS selectors. It would benefit from higher-level extraction primitives, better auto-discovery on product pages, and more prominent domain-specific recipes.
