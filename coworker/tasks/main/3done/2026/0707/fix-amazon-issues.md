# Issues: amazon

> **Source:** `20260706-192330-amazon.full.md` | **Date:** 20260706-192330 | **Mode:** dev

## Scenario Background

### Task

The comparison markdown file has been written to:
**`D:\workspace\Browser4\Browser4-4.11\amazon-pens-comparison.md`**

It contains a detailed comparison of 4 whiteboard marker pen products from Amazon, including side-by-side feature tables, pricing, ratings, popularity metrics, and a final recommendation (Product #4: maxtek Magnetic Whiteboard Markers).

---

### Execution Context

**Key Commands:**

| # | Command | Purpose |
|---|---------|---------|
| 1 | `cargo run -- --help` | Discover available commands |
| 2 | `cargo run -- goto "https://www.amazon.com/"` | Navigate to Amazon |
| 3 | `cargo run -- snapshot -v 0` | Capture accessibility snapshot to find search box |
| 4 | `Read` snapshot YAML file | Manually read snapshot to find searchbox ref (e36) |
| 5 | `cargo run -- fill e36 "pens to draw on whiteboards"` | Fill search box |
| 6 | `cargo run -- press Enter` | Submit search |
| 7 | `cargo run -- wait --load networkidle` | Wait for results to load |
| 8 | `cargo run -- snapshot -v 0` | Capture search results snapshot |
| 9 | `cargo run -- htmlsnapshot` | Capture static HTML snapshot for extraction |
| 10 | `cargo run -- htmlsnapshot inspect` | Auto-discover page structure |
| 11 | `cargo run -- htmlsnapshot inspect ".s-result-item[data-component-type='s-search-result']"` | Targeted product card discovery |
| 12 | `cargo run -- htmlsnapshot get all text "a.a-link-normal.s-line-clamp-4" --limit 4` | Extract product titles |
| 13 | `cargo run -- htmlsnapshot get all text "span.a-offscreen" --limit 8` | Extract prices |
| 14 | `cargo run -- htmlsnapshot get all text "span.a-icon-alt" --limit 4` | Extract ratings |
| 15 | `cargo run -- htmlsnapshot get all attr "img.s-image" src --limit 4` | Extract images |
| 16 | `cargo run -- htmlsnapshot get all attr "a.a-link-normal.s-line-clamp-4" href --limit 4` | Extract product URLs |
| 17 | `cargo run -- htmlsnapshot query --sql @../../query_amazon_pens.sql` | X-SQL correlated extraction (2 runs) |

**Workarounds Applied During Task:**

- Manual snapshot file reading to find search box ref (e36) — snapshot grep might have been faster
- Trial selector changes: `h2 a.a-link-normal` (failed) → `a.a-link-normal.s-line-clamp-4` (worked)
- Multiple `get all` attempts to find review counts (settled on X-SQL enriched query)

---

---

---

## Issues Found (7 issues)
> **Review complete:** 1 approved, 6 deferred/rejected

### Issue 4: Relative SQL file path resolution from CLI directory is confusing

**Severity:** Low
**Category:** UX

#### Reproduction

```
cd cli/browser4-cli
cargo run -- htmlsnapshot query --sql @../../query.sql
```

#### Expected Behavior

The `@file` path should resolve relative to the user's working directory (the repo root), not relative to the CLI binary directory.

#### Actual Behavior

The `@file` path appears to resolve relative to the current working directory (which is `cli/browser4-cli`), requiring `../../` prefix to reference files in the repo root.

#### Root Cause Analysis

The `@file` path resolution uses the process working directory, which is `cli/browser4-cli` when using `cargo run`. Users naturally expect paths to resolve from where they ran the command.

#### Code Pointer

``cli/browser4-cli/src/` — SQL file loading logic.`

#### AI Suggested Improvement

- Document the path resolution behavior clearly in the help output and SKILL.md
- Consider resolving `@file` paths relative to the original invocation directory rather than the binary's working directory
- Add a `--sql-file <absolute-path>` alternative that accepts absolute paths

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**
should resolving @file paths relative to the original invocation directory

---

---

### Issue 1: Auto-discover (htmlsnapshot inspect) defaults to generic selectors, not product cards

**Severity:** Medium
**Category:** UX

#### Review Result

**Decision:** DEFER

**Summary:** - Prioritize containers with consistent child structure (image + heading + price pattern) over navigation elements

---

### Issue 2: Documentation's recommended CSS selectors fail on non-English Amazon locale

**Severity:** Medium
**Category:** Documentation

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add a warning in scenario 15b that CSS selectors vary by Amazon locale, and show fallback selectors for common locale variants

---

### Issue 3: Snapshot is YAML-only; no option for JSON machine-readable output

**Severity:** Low
**Category:** Product

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add `--json` output format for `snapshot` to emit the accessibility tree as JSON

---

### Issue 5: No built-in command to extract review/rating counts from search results

**Severity:** Low
**Category:** Product

#### Review Result

**Decision:** WONTFIX

**Summary:** - Document known Amazon review count selectors and their locale variants

---

### Issue 6: `htmlsnapshot` output is very verbose — hard to find key info in interactive elements list

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** DEFER

**Summary:** - Group interactive elements by type/section (e.g., "Navigation", "Search", "Product Cards", "Footer")

---

### Issue 7: `snapshot -v 0` truncation loses product data — products only appear in later viewports

**Severity:** Medium
**Category:** Discoverability

#### Review Result

**Decision:** WONTFIX

**Summary:** - When `snapshot` detects a search results page (URL contains `/s?k=`), automatically capture enough viewports to include the first few results, or suggest `-v 1` in the output

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Auto-discover (htmlsnapshot inspect) defaults to generic selectors, not product cards

```
cargo run -- htmlsnapshot inspect
```
On an Amazon search results page.

#### Issue 2: Documentation's recommended CSS selectors fail on non-English Amazon locale

1. Navigate to amazon.com (which auto-detects locale and may serve Chinese UI to Singapore visitors)
2. Follow scenario 15b from `htmlsnapshot-scenarios-amazon.md`
3. Run `htmlsnapshot get all text "h2 a.a-link-normal"`

#### Issue 3: Snapshot is YAML-only; no option for JSON machine-readable output

```
cargo run -- snapshot -v 0
```
The output references a YAML file path. There is no `--json` flag for `snapshot` to emit the accessibility tree as JSON.

#### Issue 4: Relative SQL file path resolution from CLI directory is confusing

```
cd cli/browser4-cli
cargo run -- htmlsnapshot query --sql @../../query.sql
```

#### Issue 5: No built-in command to extract review/rating counts from search results

Attempt to extract the number of ratings/reviews for each product on the search results page.

#### Issue 6: `htmlsnapshot` output is very verbose — hard to find key info in interactive elements list

```
cargo run -- htmlsnapshot
```
The output lists 100 interactive elements with full CSS paths, box coordinates, and internal IDs.

#### Issue 7: `snapshot -v 0` truncation loses product data — products only appear in later viewports

1. Search Amazon for a product
2. Run `snapshot -v 0` to view the top of the page
