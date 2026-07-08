# Issues: Laser-Engraved Crystal

> **Source:** `20260706-191631-Laser-Engraved Crystal.full.md` | **Date:** 20260706-191631 | **Mode:** dev

## Scenario Background

### Task

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

### Execution Context

**Key Commands:**

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

**Workarounds Applied During Task:**

- **X-SQL via file (`@query.sql`)** instead of inline `--sql "..."` to avoid Windows shell escaping with double-quoted CSS selectors — as documented in SKILL.md §5
- **Two attempts at selectors** — `h2 .a-link-normal` didn't match; switched to `a.s-line-clamp-4` after testing with `htmlsnapshot get all text`
- **Product links are relative** — had to manually prepend `https://www.amazon.com` to form full detail page URLs
- **Large JSON output (41KB)** was auto-saved to temp file — had to redirect to a repo file with `>` to access it

---

---

---

## Issues Found (7 issues)
> **Review complete:** 4 approved, 3 deferred/rejected

### Issue 2: First X-SQL selector attempt silently returned no title/link data

**Severity:** Medium
**Category:** Reliability

#### Reproduction

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

#### Expected Behavior

Either matching data, or a clear error/warning that `h2 .a-link-normal` matched zero elements within the container.

#### Actual Behavior

The `title` and `link` fields were silently absent from the result JSON. Only `price` and `rating` appeared. No indication that the selectors didn't match — the query "succeeded" but returned incomplete data.

#### Root Cause Analysis

When a `DOM_FIRST_TEXT` or `DOM_FIRST_ATTR` selector finds no match within a container, the field is simply omitted from the result row rather than returning NULL or an error. This is silent partial failure — the query returns some data so it looks successful.

#### Code Pointer

`Likely in the X-SQL engine's `DOM_FIRST_TEXT`/`DOM_FIRST_ATTR` implementation — when the selector returns empty, the field is skipped rather than set to NULL.`

#### AI Suggested Improvement

- Return `null` for unmatched selectors instead of omitting the field entirely — this makes missing data visible
- Add a `--verbose` flag to `htmlsnapshot query` that reports per-selector match counts (e.g., "title selector matched 0/48 containers")
- Document in X-SQL reference that absent fields = no match (currently not obvious)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**
Return null for unmatched selectors instead of omitting the field entirely — this makes missing data visible. Only apply this fix.

---

---

### Issue 3: `htmlsnapshot query` output redirection difficult with `cargo run`

**Severity:** Low
**Category:** UX

#### Reproduction

Run `cargo run -- htmlsnapshot query --sql @query.sql --result-only 2>&1 > output.json` from the repo root via the standard invocation pattern.

#### Expected Behavior

JSON output saved cleanly to `output.json`.

#### Actual Behavior

The `cargo run` build status lines ("Finished...", "Running...") are on stderr but interleave with stdout when using `2>&1`. Redirecting just stdout still misses the build noise on stderr. The output file path also changes relative to `cli/browser4-cli/` vs repo root.

#### Root Cause Analysis

`cargo run` writes build messages to stderr and the actual command output to stdout. The working directory during execution is `cli/browser4-cli/`, so relative file paths in `@query.sql` and output redirection need to account for this. The `2>&1` pattern captures build noise alongside results.

#### Code Pointer

`N/A (tooling issue).`

#### AI Suggested Improvement

- Document the exact redirection pattern for dev mode in SKILL.md: `cargo run --quiet -- htmlsnapshot query --sql @../../query.sql --result-only > ../../results.json`
- Note that `--quiet` passes through to cargo, suppressing the "Finished" line
- Consider a `--output <file>` option on `htmlsnapshot query` to write results directly to a file without shell redirection

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

---

### Issue 4: Product links extracted as relative paths requiring manual URL construction

**Severity:** Low
**Category:** UX

#### Reproduction

Extract product links via X-SQL with `DOM_FIRST_ATTR(DOM, 'a.s-line-clamp-4', 'href') AS link`.

#### Expected Behavior

Absolute URLs (e.g., `https://www.amazon.com/dp/B0FCLQZDHV`) ready for use with `goto`.

#### Actual Behavior

Relative paths (e.g., `/-/zh/dp/B0FCLQZDHV/ref=sr_1_31?...`) that require manual prepending of `https://www.amazon.com` before they can be used with `goto`.

#### Root Cause Analysis

Amazon's HTML uses relative `href` attributes. The `DOM_FIRST_ATTR` function returns the raw attribute value without URL resolution. This is technically correct behavior but creates friction in the `goto` → extract → `goto detail page` workflow.

#### Code Pointer

`Could be addressed in the X-SQL engine or by adding a `DOM_FIRST_ABS_HREF` function.`

#### AI Suggested Improvement

- Add a `DOM_FIRST_ABS_HREF(dom, selector)` function that resolves relative URLs against the page's base URL
- Document in SKILL.md that `DOM_FIRST_ATTR` for `href` returns raw values — users must manually resolve
- Consider adding a URL resolution option to `htmlsnapshot query`

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**
use DOM_FIRST_ATTR(dom, "a", "abs:href") for the absolute url after resolving against the document's base URI.

---

---

### Issue 6: `htmlsnapshot inspect` shell quoting fails with attribute selectors on Windows

**Severity:** Medium
**Category:** Reliability

#### Reproduction

```bash
cargo run -- htmlsnapshot inspect "[data-component-type='s-search-result']" --max 10 --depth 5
```

#### Expected Behavior

Clean execution with inspection results.

#### Actual Behavior

The single quotes inside the CSS selector require careful escaping on Windows. The command as written above worked, but `"[data-component-type=\"s-search-result\"]"` (standard Windows escaping) does not. This is a known issue documented in SKILL.md §5 but still a friction point — `htmlsnapshot inspect` doesn't support `--selector @file` or `--stdin` like `htmlsnapshot query` does.

#### Root Cause Analysis

`htmlsnapshot inspect` only accepts the selector as a positional argument, with no file/stdin alternative. Unlike `htmlsnapshot query --sql @file`, there's no escape hatch for complex selectors.

#### Code Pointer

``cli/browser4-cli/src/commands.rs` — the inspect command definition.`

#### AI Suggested Improvement

- Add `--selector @file` support to `htmlsnapshot inspect` (read selector from file, mirroring `--sql @file`)
- Or support `--stdin` to read the selector from stdin
- Document the exact Windows escaping pattern for attribute selectors with single quotes

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**
support three format: read selector from file, from stdin, and inline with base64

---

---

### Issue 1: `cargo run` overhead adds noise to every command

**Severity:** Medium
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Document a `--quiet` cargo flag alias or wrapper script in SKILL.md for dev mode (e.g., `alias b4='cargo run --quiet --'`)

---

### Issue 5: SKILL.md reference files not present in repository

**Severity:** Medium
**Category:** Documentation

#### Review Result

**Decision:** WONTFIX

**Notes:** It does exist

**Summary:** - Either include the reference markdown files in `skills/browser4-cli/references/` in the repo

---

### Issue 7: Amazon locale auto-detection shows prices in non-USD currency

**Severity:** Low
**Category:** Task Execution

#### Review Result

**Decision:** WONTFIX

**Summary:** - Document in SKILL.md that Amazon.com may show localized prices; users can use `--cdp` with a US-based profile or set delivery address to a US zip code before searching

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: `cargo run` overhead adds noise to every command

Run any browser4-cli command via `cargo run -- <command>`.

#### Issue 2: First X-SQL selector attempt silently returned no title/link data

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

#### Issue 3: `htmlsnapshot query` output redirection difficult with `cargo run`

Run `cargo run -- htmlsnapshot query --sql @query.sql --result-only 2>&1 > output.json` from the repo root via the standard invocation pattern.

#### Issue 4: Product links extracted as relative paths requiring manual URL construction

Extract product links via X-SQL with `DOM_FIRST_ATTR(DOM, 'a.s-line-clamp-4', 'href') AS link`.

#### Issue 5: SKILL.md reference files not present in repository

Read `skills/browser4-cli/SKILL.md` and attempt to follow links like `references/htmlsnapshot.md`, `references/x-sql.md`, etc.

#### Issue 6: `htmlsnapshot inspect` shell quoting fails with attribute selectors on Windows

```bash
cargo run -- htmlsnapshot inspect "[data-component-type='s-search-result']" --max 10 --depth 5
```

#### Issue 7: Amazon locale auto-detection shows prices in non-USD currency

Navigate to `https://www.amazon.com` and search for products. The page detects IP location and shows prices in Singapore Dollars (SGD).
