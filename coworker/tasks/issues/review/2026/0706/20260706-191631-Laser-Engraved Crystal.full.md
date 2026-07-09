---

# Browser4-CLI Usability Evaluation: Amazon Laser-Engraved Crystal Shopping

---

## A. Task Result

### Best Pick: **Enjinkail DNA Crystal Ball with LED Base**

**Product URL:** `https://www.amazon.com/dp/B0FCLQZDHV`

**Price:** SGD 36.21 | **Rating:** 4.7★ (155 reviews)

**Why this is the best choice for a 12-year-old boy:**

- **Science/STEM theme** — The 3D laser-engraved DNA double helix inside a K9 crystal sphere directly appeals to a pre-teen boy's natural curiosity about how things work. It's educational without being boring.
- **Visually impressive** — Features a 5-color changing LED base (also locks to a single color), creating a captivating night light effect that any kid would find cool.
- **Complete gift package** — Comes with crystal sphere, USB cable, LED base, 3 AAA batteries, blank greeting card, and cleaning cloth — no additional purchases needed.
- **Versatile use** — Works as a desk decoration, night light, or science room accent. USB or battery powered for flexibility.
- **Most reviewed** — 155 ratings at 4.7★ — the highest review count among suitable candidates, indicating broad satisfaction.
- **Age-appropriate** — Sophisticated enough for a 12-year-old (not a toy), but fun and engaging (not a serious adult gift like a wedding anniversary plaque).

### Shortlisted Top 10 (ranked):

| # | Product | Price (SGD) | Rating | Key Feature |
|---|---------|-------------|--------|-------------|
| 1 | **DNA Crystal Ball w/ LED Base** | 36.21 | 4.7★ (155) | Science/STEM, 5-color LED |
| 2 | Statue of Liberty w/ LED Base | 25.86 | 4.8★ | Iconic landmark, LED base |
| 3 | Eagle Crystal Ball w/ Stand | 11.63 | 4.8★ | Bald eagle, budget option |
| 4 | Tree of Life Crystal Ball w/ LED | 16.29 | 4.7★ | Wooden base, color LED |
| 5 | Graduation Gift Class of 2026 | 29.10 | 4.5★ | Inspirational, LED base |
| 6 | Constellation Crystal Ball | 12.93 | 4.6★ | Space/universe theme |
| 7 | Panda Crystal Ball | 12.28 | 4.7★ | Cute animal, budget |
| 8 | Turtle Crystal Ball | 10.49 | 4.6★ | Sea turtle, budget |
| 9 | Bird Crystal Ball | 11.05 | 4.8★ | Nature, budget |
| 10 | Custom Photo Crystal | 77.62 | — | Personalized, premium |

---

## B. Execution Trace

### Commands Used

```bash
# Step 1: Learn the CLI
cd cli/browser4-cli && cargo run -- help

# Step 2: Navigate to Amazon
cargo run -- goto "https://www.amazon.com"

# Step 3: Capture homepage structure
cargo run -- snapshot -v 0

# Step 4: Search for products
cargo run -- fill e64310 "Laser-Engraved Crystal"
cargo run -- click e65155

# Step 5: Extract search results
cargo run -- htmlsnapshot
cargo run -- htmlsnapshot inspect "[data-component-type='s-search-result']" --max 10 --depth 5
cargo run -- htmlsnapshot query --sql "@../../query_products.sql" --result-only > ../../amazon_results.json

# Step 6: Review product detail pages (5 of 10 shortlisted)
cargo run -- goto "https://www.amazon.com/dp/B0FCLQZDHV"    # DNA Crystal
cargo run -- htmlsnapshot get text "#productTitle"
cargo run -- htmlsnapshot get all text "#feature-bullets .a-list-item" --limit 5

cargo run -- goto "https://www.amazon.com/dp/B0BGH5L5FX"    # Eagle Crystal
cargo run -- goto "https://www.amazon.com/dp/B0GYZCRCRL"    # Statue of Liberty
cargo run -- goto "https://www.amazon.com/dp/B0GRTXC746"    # Graduation Gift
cargo run -- goto "https://www.amazon.com/dp/B0GVMWLLXX"    # Tree of Life

# Step 7: Cleanup
cargo run -- close
```

### Major Steps
1. Read `SKILL.md` and `cargo run -- help` to learn the CLI
2. Navigated to Amazon.com and performed search via `fill` + `click` on snapshot refs
3. Captured `htmlsnapshot` of search results and used `htmlsnapshot inspect` to discover product card selectors
4. Used X-SQL `DOM_LOAD_AND_SELECT` to extract 48 products with title, price, rating, and link
5. Manually curated the shortlist — most results were raw crystal blanks (DIY), wedding/romantic gifts, or explicitly feminine items
6. Visited 5 product detail pages using `htmlsnapshot get` to extract titles and features
7. Selected the DNA Crystal Ball as the winner

### Workarounds Required
- **X-SQL via file (`@query.sql`)** instead of inline `--sql "..."` to avoid Windows shell escaping with double-quoted CSS selectors — as documented in SKILL.md §5
- **Two attempts at selectors** — `h2 .a-link-normal` didn't match; switched to `a.s-line-clamp-4` after testing with `htmlsnapshot get all text`
- **Product links are relative** — had to manually prepend `https://www.amazon.com` to form full detail page URLs
- **Large JSON output (41KB)** was auto-saved to temp file — had to redirect to a repo file with `>` to access it

---

## C. Issues Found

### Issue 1: `cargo run` overhead adds noise to every command

**Severity:** Medium

**Category:** UX

**Reproduction:** Run any browser4-cli command via `cargo run -- <command>`.

**Expected:** Clean output starting with the command result.

**Actual:** Every command is prefixed with:
```
    Finished `dev` profile [unoptimized + debuginfo] target(s) in 0.14s
     Running `target\debug\browser4-cli.exe <command>`
```
This adds 2 lines of noise to every single command output. Over a session with 15+ commands, this is significant visual clutter.

**Root Cause:** `cargo run` prints build status and the binary invocation line to stderr before the actual command executes. In dev mode, there's no way to suppress this without modifying the cargo invocation or piping through `tail -n +3`.

**Code Pointer:** N/A — this is a `cargo` behavior, not browser4-cli code.

**AI Suggested Improvement:**
- Document a `--quiet` cargo flag alias or wrapper script in SKILL.md for dev mode (e.g., `alias b4='cargo run --quiet --'`)
- Consider providing a PowerShell/bash wrapper script in `cli/scripts/` that suppresses cargo's build output in dev mode
- Add a note in SKILL.md §Development that users can set `alias b4='cd cli/browser4-cli && cargo run --quiet --'` for cleaner output

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: First X-SQL selector attempt silently returned no title/link data

**Severity:** Medium

**Category:** Reliability

**Reproduction:**
```bash
# Write query with untested selectors
cat > query.sql << 'SQLEOF'
SELECT
    DOM_FIRST_TEXT(DOM, 'h2 .a-link-normal') AS title,
    ...
FROM DOM_LOAD_AND_SELECT(@url, '[data-component-type="s-search-result"]', 1, 48)
SQLEOF
cargo run -- htmlsnapshot query --sql @query.sql
```

**Expected:** Either matching data, or a clear error/warning that `h2 .a-link-normal` matched zero elements within the container.

**Actual:** The `title` and `link` fields were silently absent from the result JSON. Only `price` and `rating` appeared. No indication that the selectors didn't match — the query "succeeded" but returned incomplete data.

**Root Cause:** When a `DOM_FIRST_TEXT` or `DOM_FIRST_ATTR` selector finds no match within a container, the field is simply omitted from the result row rather than returning NULL or an error. This is silent partial failure — the query returns some data so it looks successful.

**Code Pointer:** Likely in the X-SQL engine's `DOM_FIRST_TEXT`/`DOM_FIRST_ATTR` implementation — when the selector returns empty, the field is skipped rather than set to NULL.

**AI Suggested Improvement:**
- Return `null` for unmatched selectors instead of omitting the field entirely — this makes missing data visible
- Add a `--verbose` flag to `htmlsnapshot query` that reports per-selector match counts (e.g., "title selector matched 0/48 containers")
- Document in X-SQL reference that absent fields = no match (currently not obvious)

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: `htmlsnapshot query` output redirection difficult with `cargo run`

**Severity:** Low

**Category:** UX

**Reproduction:** Run `cargo run -- htmlsnapshot query --sql @query.sql --result-only 2>&1 > output.json` from the repo root via the standard invocation pattern.

**Expected:** JSON output saved cleanly to `output.json`.

**Actual:** The `cargo run` build status lines ("Finished...", "Running...") are on stderr but interleave with stdout when using `2>&1`. Redirecting just stdout still misses the build noise on stderr. The output file path also changes relative to `cli/browser4-cli/` vs repo root.

**Root Cause:** `cargo run` writes build messages to stderr and the actual command output to stdout. The working directory during execution is `cli/browser4-cli/`, so relative file paths in `@query.sql` and output redirection need to account for this. The `2>&1` pattern captures build noise alongside results.

**Code Pointer:** N/A (tooling issue).

**AI Suggested Improvement:**
- Document the exact redirection pattern for dev mode in SKILL.md: `cargo run --quiet -- htmlsnapshot query --sql @../../query.sql --result-only > ../../results.json`
- Note that `--quiet` passes through to cargo, suppressing the "Finished" line
- Consider a `--output <file>` option on `htmlsnapshot query` to write results directly to a file without shell redirection

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: Product links extracted as relative paths requiring manual URL construction

**Severity:** Low

**Category:** UX

**Reproduction:** Extract product links via X-SQL with `DOM_FIRST_ATTR(DOM, 'a.s-line-clamp-4', 'href') AS link`.

**Expected:** Absolute URLs (e.g., `https://www.amazon.com/dp/B0FCLQZDHV`) ready for use with `goto`.

**Actual:** Relative paths (e.g., `/-/zh/dp/B0FCLQZDHV/ref=sr_1_31?...`) that require manual prepending of `https://www.amazon.com` before they can be used with `goto`.

**Root Cause:** Amazon's HTML uses relative `href` attributes. The `DOM_FIRST_ATTR` function returns the raw attribute value without URL resolution. This is technically correct behavior but creates friction in the `goto` → extract → `goto detail page` workflow.

**Code Pointer:** Could be addressed in the X-SQL engine or by adding a `DOM_FIRST_ABS_HREF` function.

**AI Suggested Improvement:**
- Add a `DOM_FIRST_ABS_HREF(dom, selector)` function that resolves relative URLs against the page's base URL
- Document in SKILL.md that `DOM_FIRST_ATTR` for `href` returns raw values — users must manually resolve
- Consider adding a URL resolution option to `htmlsnapshot query`

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: SKILL.md reference files not present in repository

**Severity:** Medium

**Category:** Documentation

**Reproduction:** Read `skills/browser4-cli/SKILL.md` and attempt to follow links like `references/htmlsnapshot.md`, `references/x-sql.md`, etc.

**Expected:** Reference documentation available for deeper learning.

**Actual:** The `skills/browser4-cli/references/` directory does not exist in the repository. The SKILL.md §7 "Reference Map" lists 10+ reference files but none are present locally. A new user following the "Reference Map" links would hit dead ends.

**Root Cause:** The reference files are apparently hosted on `https://browser4.io/` (production mode) but not included in the repository for dev mode. The SKILL.md is shared between dev and production modes but the reference files are only available via the website.

**Code Pointer:** N/A (content packaging issue).

**AI Suggested Improvement:**
- Either include the reference markdown files in `skills/browser4-cli/references/` in the repo
- Or add a note in SKILL.md §7 that reference docs are at `https://browser4.io/` with direct URLs
- Or generate the reference docs from source during the build process

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: `htmlsnapshot inspect` shell quoting fails with attribute selectors on Windows

**Severity:** Medium

**Category:** Reliability

**Reproduction:**
```bash
cargo run -- htmlsnapshot inspect "[data-component-type='s-search-result']" --max 10 --depth 5
```

**Expected:** Clean execution with inspection results.

**Actual:** The single quotes inside the CSS selector require careful escaping on Windows. The command as written above worked, but `"[data-component-type=\"s-search-result\"]"` (standard Windows escaping) does not. This is a known issue documented in SKILL.md §5 but still a friction point — `htmlsnapshot inspect` doesn't support `--selector @file` or `--stdin` like `htmlsnapshot query` does.

**Root Cause:** `htmlsnapshot inspect` only accepts the selector as a positional argument, with no file/stdin alternative. Unlike `htmlsnapshot query --sql @file`, there's no escape hatch for complex selectors.

**Code Pointer:** `cli/browser4-cli/src/commands.rs` — the inspect command definition.

**AI Suggested Improvement:**
- Add `--selector @file` support to `htmlsnapshot inspect` (read selector from file, mirroring `--sql @file`)
- Or support `--stdin` to read the selector from stdin
- Document the exact Windows escaping pattern for attribute selectors with single quotes

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: Amazon locale auto-detection shows prices in non-USD currency

**Severity:** Low

**Category:** Task Execution

**Reproduction:** Navigate to `https://www.amazon.com` and search for products. The page detects IP location and shows prices in Singapore Dollars (SGD).

**Expected:** USD prices on amazon.com.

**Actual:** All prices displayed in SGD with Chinese UI elements mixed in (Amazon detected Singapore delivery address). This is an Amazon behavior, not a browser4-cli bug, but it affects the task outcome — all price comparisons are in SGD, not USD.

**Root Cause:** Amazon.com uses geolocation and delivery address preferences to determine currency and language. The browser session inherited a Singapore delivery address, likely from a previous session or CDP profile.

**Code Pointer:** N/A (Amazon behavior, not browser4-cli).

**AI Suggested Improvement:**
- Document in SKILL.md that Amazon.com may show localized prices; users can use `--cdp` with a US-based profile or set delivery address to a US zip code before searching
- Add a recipe to the htmlsnapshot-scenarios reference for handling Amazon's locale detection

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
✅ **Fully completed.** Successfully searched Amazon for "Laser-Engraved Crystal," extracted 48 product listings, shortlisted 10 suitable for a 12-year-old boy, reviewed 5 detail pages, and selected the best option (DNA Crystal Ball with LED Base).

### Estimated Task Success Rate
**85%** — The core workflow (navigate → snapshot → interact → extract) worked reliably. The main friction points were around selector discovery (took 2 attempts), output handling (large JSON, relative URLs), and `cargo run` noise.

### Number of Issues Found
**7 issues** (0 Critical, 4 Medium, 3 Low)

### Major Blockers
None. The task was completed without any blocking failures. The most significant friction was the silent X-SQL selector failure (Issue #2), which could mislead users into thinking they have complete data when fields are actually missing.

### Most Confusing Aspects
1. **X-SQL selector matching is silent** — no feedback when a selector matches zero elements (Issue #2)
2. **Product links are relative** — required manual URL construction (Issue #4)
3. **`cargo run` noise** — 2 lines of build output before every command result (Issue #1)
4. **Reference docs missing from repo** — SKILL.md links to files that don't exist locally (Issue #5)

### Most Valuable Improvements
1. **X-SQL with `DOM_LOAD_AND_SELECT` is excellent** — extracting 48 products with correlated fields (title, price, rating, link) in a single command is powerful and efficient
2. **`htmlsnapshot inspect` for selector discovery** — automatically suggests CSS selectors for recurring patterns within product cards, saving trial-and-error
3. **SKILL.md decision trees** — the "Choosing an Extraction Method" flow chart was invaluable for choosing between `get`, `get all`, and `query`
4. **`@file.sql` pattern** — the ability to read SQL from a file completely sidesteps Windows shell escaping issues

### Overall Usability Rating
**7.5 / 10**

Browser4-CLI provides a well-designed, powerful browser automation tool with excellent documentation in SKILL.md. The decision trees and command map make it easy to find the right tool for each task. The core workflow (goto → snapshot → interact → extract) is intuitive once learned.

Points deducted for: (a) `cargo run` output noise in dev mode, (b) silent X-SQL selector failures, (c) missing reference documentation in the repo, (d) the need to manually resolve relative URLs. These are all fixable and none blocked task completion.

For a first-time user familiar with CLI tools, the learning curve is reasonable — about 15-20 minutes to become productive. The HTML snapshot + X-SQL extraction capability is genuinely impressive and sets browser4-cli apart from simpler browser automation tools.
