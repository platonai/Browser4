# Issues: comprehensive-ecommerce-workflow

> **Source:** `20260726-192910-comprehensive-ecommerce-workflow.full.md` | **Date:** 20260726-192910 | **Mode:** dev

## Scenario Background

### Task

Successfully completed the e-commerce product browsing and data extraction workflow using browser4-cli. Key data extracted:

| Product Detail Page |
|---|
| **Title:** 4K OLED TV 55 |
| **Price:** $899.99 |
| **Image:** /ec/static/img/placeholder.png |
| **Rating:** 4.6 (521 reviews) |
| **Features:** 55 inch, HDR10+ |
| **Specs:** Panel=OLED, Resolution=3840x2160 |
| **Category:** Electronics |
| **Badge:** Bestseller |

| Electronics Listing Page (6 products) |
|---|
| 1. 4K OLED TV 55 — $899.99 |
| 2. Wireless Noise-Cancelling Headphones — $199.99 |
| 3. Portable Bluetooth Speaker — $49.99 |
| 4. Smartphone 128GB — $599.00 |
| 5. USB-C Hub 7-in-1 — $29.95 |
| 6. Wireless Mouse — $24.99 |

Session state saved to `ecommerce-session-2026-07-27.json`, screenshot saved to `.browser4-cli/snapshot/electronics-listing-6-products-2026-07-27.png`.

**Skipped step:** Step 10 (LLM `extract`) — no LLM API key configured.

---

### Execution Context

| # | Command | Purpose | Outcome |
|---|---------|---------|---------|
| 1 | `goto "http://localhost:18080/ec/"` | Navigate to home page | ✅ Loaded Mock EC Home |
| 2 | `snapshot --viewport 0` | Full-page snapshot | ✅ After working around PowerShell `-v` interception |
| 3 | `snapshot --interactive --stdout` | Interactive-only snapshot | ✅ Showed category links only — no product links on home |
| 4 | `click e282` (Electronics) → `goto .../B0E000001` | Click category, navigate to product | ⚠️ Click produced malformed URL (`%22` encoding); had to `goto` directly |
| 5 | `htmlsnapshot` | Capture HTML snapshot | ✅ |
| 6 | `htmlsnapshot inspect --max 3 --depth 2` | Discover CSS selectors | ✅ Found `tr` specs table; didn't surface semantic class names |
| 7 | `htmlsnapshot get all text "article...

(truncated — see full.md for complete trace)

---

## Issues Found (9 issues)

### Issue 1: PowerShell short-flag interception breaks `-i` and `-v`

**Severity:** High
**Category:** UX

#### Reproduction

```powershell
./b4w.ps1 snapshot -i        # Fails: ambiguous parameter name
./b4w.ps1 snapshot -v 0      # Fails: ambiguous parameter name
./b4w.ps1 htmlsnapshot grep -i "pattern"  # Fails: ambiguous parameter name
./b4w.ps1 -- snapshot -v 0   # Also fails
```

#### Expected Behavior

Short flags work seamlessly from PowerShell like they do from bash/cmd.

#### Actual Behavior

PowerShell's parameter binder intercepts `-i` (matches `-InformationAction`) and `-v` (matches `-Verbose`). The `--` separator doesn't reliably shield them. Quoting `"-v"` merges it with the next argument into one token.

#### Root Cause Analysis

`b4w.ps1` is a PowerShell script wrapper. PowerShell parses arguments before forwarding them to the Rust binary. Short flags that overlap with PowerShell common parameters (`-i` ↔ `-InformationAction`, `-v` ↔ `-Verbose`, `-c` ↔ `-ErrorAction`) are consumed by PowerShell before reaching the CLI.

#### Code Pointer

`The `b4w.ps1` wrapper script. Options: (a) use `b4w.bat`/`b4w.sh` instead for these flags, (b) add PowerShell-specific argument escaping in `b4w.ps1`, (c) document long-form alternatives prominently.`

#### AI Suggested Improvement

- Add a `--%` (stop-parsing) token handling in `b4w.ps1` so `./b4w.ps1 --% snapshot -v 0` works
- In help output, surface long-form flags first when running under PowerShell (detectable via `$PSVersionTable`)
- Add a prominent warning in help about PowerShell flag interception at the top of every `--help` output
- Consider adding `-I` (uppercase) as an alias for `--interactive` since it wouldn't clash with `-InformationAction`

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 2: Clicking product link navigates to malformed URL with encoded quotes

**Severity:** High
**Category:** Reliability

#### Reproduction

```bash
./b4w.ps1 goto "http://localhost:18080/ec/b?node=1292115012"
./b4w.ps1 snapshot --interactive --stdout
# Observe: link href shows \"/ec/dp/B0E000001\" with escaped quotes
./b4w.ps1 click e561   # Or whichever ref points to a product link
# Result: navigates to http://localhost:18080/%22/ec/dp/B0E000001/%22
```

#### Expected Behavior

Click navigates to `http://localhost:18080/ec/dp/B0E000001`.

#### Actual Behavior

Navigates to `http://localhost:18080/%22/ec/dp/B0E000001/%22` (URL-encoded quotes in path). The page title is empty.

#### Root Cause Analysis

The MockSite HTML has literal escaped quotes in href attributes (`href=\"...\"`), which get URL-encoded when the browser resolves the relative URL. The CDP `Input.dispatchMouseEvent` click dispatches on the correct coordinates, but the underlying href is malformed. This could be a MockSite data issue, but browser4-cli doesn't surface any warning about the unusual href format.

#### Code Pointer

`Likely a MockSite fixture issue in `browser4-tests/pulsar-tests-common/src/main/resources/static/b4/`. However, browser4-cli could detect suspicious href patterns (containing `\"` or `%22`) and emit a warning.`

#### AI Suggested Improvement

- Add href validation in the snapshot output — warn when link URLs contain suspicious characters (escaped quotes, percent-encoded quotes)
- In `htmlsnapshot capture`, add a `urlWarnings` field to the metadata when links have malformed hrefs
- Consider adding a `goto` validation step that detects redirects to URLs containing `%22` and suggests the corrected URL

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 3: `htmlsnapshot inspect` auto-discovery didn't surface semantic CSS class names

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

```bash
./b4w.ps1 goto "http://localhost:18080/ec/dp/B0E000001"
./b4w.ps1 htmlsnapshot
./b4w.ps1 htmlsnapshot inspect --max 3 --depth 2
# Shows: auto-discovered "tr" (specs table)
# Does NOT show: .productTitle, .product-price, .product-features, .product-specs
```

#### Expected Behavior

`inspect` should discover and suggest the semantic CSS class names that the page author intentionally used: `.productTitle`, `.product-price`, `.product-rating`, `.product-features`, `.product-specs`, `.product-category-link`.

#### Actual Behavior

Auto-discovery picked `tr` elements from the specs table as the most repeating pattern. The product page's semantic class names went undiscovered.

#### Root Cause Analysis

The inspect auto-discovery algorithm ranks patterns by size × specificity × content-variance × structural-richness. On a single-product detail page, the specs table rows (`tr`) are the "most repeating" pattern because the product container only appears once. The algorithm prioritizes repetition count over semantic value. Meanwhile, `snapshot grep` revealed these class names are embedded in the accessibility tree annotations — a powerful feature that `inspect` doesn't leverage.

#### Code Pointer

`The inspect algorithm in the backend (Java/Kotlin, likely in `browser4-core` or `browser4-rest`). The auto-discovery scoring function could be enhanced to consider class name semantics.`

#### AI Suggested Improvement

- When `inspect` is run without a selector on a page with ≤ 3 repeating-group candidates, also report **non-repeating semantic classes** found on structural elements (article, main, section) — these are the product detail page equivalent of repeating patterns
- Leverage the accessibility tree's class-name annotations (already present!) as a signal for discoverability — add a `Semantic classes found` section to inspect output
- Add an `inspect --mode detail` flag that optimizes for single-entity pages (product detail, article) vs. `--mode list` for repeating-item pages (search results)
- Document that for single-product pages, `snapshot grep` of the accessibility tree is the best way to discover class names

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 4: X-SQL price extraction requires regex fallback when semantic classes exist but are unknown

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

```sql
-- This returned empty price:
SELECT DOM_FIRST_TEXT(DOM, '.price, [class*="price"]') AS price ...

-- Required regex workaround:
SELECT DOM_FIRST_RE1(DOM, 'article', '\$([\d,]+\.?\d*)') AS price ...
```

#### Expected Behavior

After running `htmlsnapshot inspect`, the user should know that `.product-price` is the selector for the price element, and the X-SQL query should work with it.

#### Actual Behavior

`inspect` didn't surface the `.product-price` class name. The user had to discover it via `snapshot grep` on the accessibility tree, which is an indirect path. Then `DOM_FIRST_TEXT(DOM, '.product-price')` should have worked — but this was only discoverable through the AX tree grep, not through inspect.

#### Root Cause Analysis

Compound of Issue 3 (inspect doesn't surface semantic classes) + the fact that CSS class names are embedded in accessibility tree annotations but not exposed through the inspect tool.

#### AI Suggested Improvement

- Fix inspect to surface semantic class names (see Issue 3)
- Add `htmlsnapshot classes` or `htmlsnapshot inspect --classes` command that lists all CSS classes found on the page sorted by specificity/semantic-value
- In `htmlsnapshot` capture output, list "semantic class hints" discovered from the accessibility tree annotations

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 5: No built-in way to discover that the accessibility tree carries CSS class annotations

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

The accessibility tree's accessible names include CSS class names as semantic markers (e.g., `main "product-page 4K OLED TV... product-price $899.99"`), but this is an undocumented implementation detail that users stumble upon accidentally.

#### Expected Behavior

This powerful feature should be documented and surfaced. Users should know they can use `snapshot grep` to find CSS class names embedded in the accessibility tree.

#### Actual Behavior

No documentation mentions this. The SKILL.md says "Interactive mode (`snapshot -i`) strips generic `<div>` containers. Many e-commerce product cards use generic divs, not semantic elements. Prefer `--viewport 0` or `htmlsnapshot` for shopping/search pages." — but doesn't mention that the full snapshot's accessibility names contain class-name annotations that solve exactly this problem.

#### Root Cause Analysis

This is likely an intentional feature (embedding class names in accessible names for debugging/discoverability) but it's undocumented.

#### Code Pointer

`The accessibility tree builder that constructs accessible names, likely appending class names as part of the name computation. Documentation gap in SKILL.md and htmlsnapshot.md.`

#### AI Suggested Improvement

- Document this behavior in SKILL.md under "Key Concepts" → "Element Refs" or a new "Discovering CSS Selectors" section
- Add a tip after `snapshot --stdout` output: "💡 Tip: Accessibility names include CSS class annotations. Use `snapshot grep 'product-'` to discover semantic class names."
- Consider a dedicated command like `htmlsnapshot classes` that extracts and lists all CSS classes from the cached HTML snapshot

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 6: `htmlsnapshot get text "h1"` returns ambiguous result on pages with multiple h1 elements

**Severity:** Low
**Category:** UX

#### Reproduction

```bash
./b4w.ps1 goto "http://localhost:18080/ec/dp/B0E000001"
./b4w.ps1 htmlsnapshot
./b4w.ps1 htmlsnapshot get text "h1"
# Returns: "Mock Ecommerce" (the banner heading, NOT the product title)
```

#### Expected Behavior

Either return the most semantically relevant h1 (main content) or warn that multiple h1 elements exist and show all candidates.

#### Actual Behavior

Silently returned the first h1 in document order (banner), which is not the product title.

#### Root Cause Analysis

`querySelector` semantics return the first match in document order. The banner `h1` ("Mock Ecommerce") appears before the product title `h1` ("4K OLED TV 55") in the DOM. No warning is issued about the ambiguity.

#### Code Pointer

`CLI output formatting — could add an ambiguity warning when a bare tag selector matches multiple elements with different content.`

#### AI Suggested Improvement

- When `get text` with a bare tag selector matches multiple elements, emit a hint on stderr: "ℹ️  h1 matches 2 elements. Use a more specific selector (e.g., 'main h1') to target the product title."
- In the "💡 Try these next" suggestions after `htmlsnapshot capture`, recommend scoped selectors like `main h1` instead of bare `h1`
- Consider a `--warn-ambiguous` flag or make it default behavior

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 7: E-commerce home page has no product links — forces category drill-down

**Severity:** Low
**Category:** Product

#### Reproduction

```bash
./b4w.ps1 goto "http://localhost:18080/ec/"
./b4w.ps1 snapshot --interactive --stdout
# Shows only category links (Electronics, Home, Garden...), no product links
```

#### Expected Behavior

The e-commerce home page should feature product links (e.g., "Best Sellers", "Featured Products") for testing product-detail navigation.

#### Actual Behavior

Only category navigation links are present. A task that says "click on the first product link" on the home page cannot be completed directly — the user must navigate through a category listing first.

#### Root Cause Analysis

MockSite home page template (`/ec/`) only renders category navigation. Product cards appear only on category/browse pages (`/ec/b?node=...`).

#### Code Pointer

`MockSite fixture template — `browser4-tests/pulsar-tests-common/src/main/resources/static/b4/` or the template that serves `/ec/`.`

#### AI Suggested Improvement

- Add a "Featured Products" or "Best Sellers" section to the `/ec/` home page with at least 4 product cards
- Or update the task description to account for category drill-down: "Navigate to the Electronics category, then click on the first product"

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 8: `--interactive` snapshot still shows non-interactive structural elements

**Severity:** Low
**Category:** Product

#### Reproduction

```bash
./b4w.ps1 snapshot --interactive --stdout
# Output includes: banner, heading, main, generic, contentinfo — non-interactive structural elements
```

#### Expected Behavior

Per documentation: "Only show interactive elements (buttons, links, inputs)." The output should filter to only buttons, links, inputs, selects, checkboxes, etc.

#### Actual Behavior

The `--interactive` flag appears to show the same tree structure, just with interactive element annotations. Headings, banners, contentinfo, and generic containers are still present.

#### Root Cause Analysis

The `--interactive` flag may be filtering descendants but not restructuring the tree to remove non-interactive ancestors. The behavior is more like "highlight interactive elements" than "show only interactive elements."

#### Code Pointer

`Snapshot rendering logic — likely in the backend's accessibility tree formatter. Check the `interactive` filter in snapshot generation.`

#### AI Suggested Improvement

- Either make `--interactive` truly filter to only interactive elements (prune non-interactive ancestors)
- Or rename it to `--highlight-interactive` and document that structural elements are retained for context
- Add an `--interactive-only` flag that strips all non-interactive elements for maximum brevity

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 9: `tab-new` "Switched to tab 0" message is confusing

**Severity:** Low
**Category:** UX

#### Reproduction

```bash
./b4w.ps1 tab-new "http://localhost:18080/ec/b?node=1292115012"
# Output: "Created tab with GUID: 2E69... (URL)"
#         "Switched to tab 0 (URL)"
```

#### Expected Behavior

Message should indicate the newly created tab's index and make clear this is the *new* tab, e.g., "Switched to new tab 0" or "Now on tab 0 (new)".

#### Actual Behavior

"Switched to tab 0" reads as if you switched to a pre-existing tab 0, not the tab you just created. For a first-time user, it's unclear whether a new tab was created or an existing one was reused.

#### Root Cause Analysis

The output message format is the same for `tab-new` and `tab-select` — both say "Switched to tab N". There's no distinction between creating-and-switching vs. just switching.

#### Code Pointer

`CLI output formatting in the Rust CLI code for tab commands.`

#### AI Suggested Improvement

- Change `tab-new` output to: "Created tab 0 and switched to it: <URL>" or "Tab 0 created: <URL>"
- Keep the GUID in output for reference
- Show tab count after creation: "Now 2 tabs open"

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
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: PowerShell short-flag interception breaks `-i` and `-v`

```powershell
./b4w.ps1 snapshot -i        # Fails: ambiguous parameter name
./b4w.ps1 snapshot -v 0      # Fails: ambiguous parameter name
./b4w.ps1 htmlsnapshot grep -i "pattern"  # Fails: ambiguous parameter name
./b4w.ps1 -- snapshot -v 0   # Also fails
```

#### Issue 2: Clicking product link navigates to malformed URL with encoded quotes

```bash
./b4w.ps1 goto "http://localhost:18080/ec/b?node=1292115012"
./b4w.ps1 snapshot --interactive --stdout
# Observe: link href shows \"/ec/dp/B0E000001\" with escaped quotes
./b4w.ps1 click e561   # Or whichever ref points to a product link
# Result: navigates to http://localhost:18080/%22/ec/dp/B0E000001/%22
```

#### Issue 3: `htmlsnapshot inspect` auto-discovery didn't surface semantic CSS class names

```bash
./b4w.ps1 goto "http://localhost:18080/ec/dp/B0E000001"
./b4w.ps1 htmlsnapshot
./b4w.ps1 htmlsnapshot inspect --max 3 --depth 2
# Shows: auto-discovered "tr" (specs table)
# Does NOT show: .productTitle, .product-price, .product-features, .product-specs
```

#### Issue 4: X-SQL price extraction requires regex fallback when semantic classes exist but are unknown

```sql
-- This returned empty price:
SELECT DOM_FIRST_TEXT(DOM, '.price, [class*="price"]') AS price ...

-- Required regex workaround:
SELECT DOM_FIRST_RE1(DOM, 'article', '\$([\d,]+\.?\d*)') AS price ...
```

#### Issue 5: No built-in way to discover that the accessibility tree carries CSS class annotations

The accessibility tree's accessible names include CSS class names as semantic markers (e.g., `main "product-page 4K OLED TV... product-price $899.99"`), but this is an undocumented implementation detail that users stumble upon accidentally.

#### Issue 6: `htmlsnapshot get text "h1"` returns ambiguous result on pages with multiple h1 elements

```bash
./b4w.ps1 goto "http://localhost:18080/ec/dp/B0E000001"
./b4w.ps1 htmlsnapshot
./b4w.ps1 htmlsnapshot get text "h1"
# Returns: "Mock Ecommerce" (the banner heading, NOT the product title)
```

#### Issue 7: E-commerce home page has no product links — forces category drill-down

```bash
./b4w.ps1 goto "http://localhost:18080/ec/"
./b4w.ps1 snapshot --interactive --stdout
# Shows only category links (Electronics, Home, Garden...), no product links
```

#### Issue 8: `--interactive` snapshot still shows non-interactive structural elements

```bash
./b4w.ps1 snapshot --interactive --stdout
# Output includes: banner, heading, main, generic, contentinfo — non-interactive structural elements
```

#### Issue 9: `tab-new` "Switched to tab 0" message is confusing

```bash
./b4w.ps1 tab-new "http://localhost:18080/ec/b?node=1292115012"
# Output: "Created tab with GUID: 2E69... (URL)"
#         "Switched to tab 0 (URL)"
```

