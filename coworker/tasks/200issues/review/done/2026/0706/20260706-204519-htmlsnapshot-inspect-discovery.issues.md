# Issues: htmlsnapshot-inspect-discovery

> **Source:** `20260706-204519-htmlsnapshot-inspect-discovery.full.md` | **Date:** 20260706-204519 | **Mode:** dev

## Scenario Background

### Task

All 9 steps completed successfully:

| Step | Command | Result |
|------|---------|--------|
| 1. Navigate | `goto "http://books.toscrape.com/"` | ✅ Loaded (redirected to HTTPS) |
| 2. HTML Snapshot | `htmlsnapshot` | ✅ 65 KB, 20 images, 94 links |
| 3. Inspect (no selector) | `htmlsnapshot inspect` | ✅ Auto-discovered `.product_pod` (20 matches) |
| 4. Inspect (scoped) | `htmlsnapshot inspect ".product_pod" --max 5 --depth 3` | ✅ 5 of 20 analyzed, depth restricted |
| 5. Summary | `htmlsnapshot summary` | ✅ WPSI generated (516 nodes, 23 landmarks) |
| 6. Extract titles | `htmlsnapshot get all text "h3 a"` | ✅ 20 titles (truncated); full titles via `get all attr "h3 a" title` |
| 7. Grep validate | `htmlsnapshot grep -c "product_pod"` | ✅ Confirmed 20 matches |
| 8. X-SQL query | `htmlsnapshot query --sql @query_books_titles_prices.sql` | ✅ 20 rows: title + price, sorted by price |
| 9. Sidebar explore | `htmlsnapshot get all text ".side_categories ul li a"` | ✅ 50 categories extracted |

---

### Execution Context

**Key Commands:**

1. `cargo run -- goto "http://books.toscrape.com/"` — initial navigation
2. `cargo run -- htmlsnapshot` — capture static snapshot
3. `cargo run -- htmlsnapshot inspect` — auto-discovery (found `.product_pod`)
4. `cargo run -- htmlsnapshot inspect ".product_pod" --max 5 --depth 3` — scoped inspection
5. `cargo run -- htmlsnapshot summary` — WPSI page summary
6. `cargo run -- htmlsnapshot get all text "h3 a"` — extract titles (truncated)
7. `cargo run -- htmlsnapshot grep -c "product_pod"` — validate count
8. `cargo run -- htmlsnapshot grep --selector ".product_pod" "price_color"` — scoped grep
9. `cargo run -- htmlsnapshot query --sql @query_books_titles_prices.sql` — X-SQL extraction
10. `cargo run -- htmlsnapshot inspect ".sidebar"` — sidebar inspection
11. `cargo run -- htmlsnapshot get all text ".side_categories ul li a"` — category extraction
12. `cargo run -- htmlsnapshot inspect ".side_categories ul li" --max 5 --depth 2` — list inspection
13. `cargo run -- htmlsnapshot get all attr "h3 a" title` — workaround for full titles

**Key decisions:**
- Used `@file.sql` for X-SQL to avoid Windows shell escaping issues (as documented)
- Used `get all attr ... title` to work around text truncation
- Tried multiple selectors for sidebar to work around auto-discovery redirecting to product pods

**Workarounds required:**
1. Full titles only available via `title` attribute, not `text` extraction
2. Sidebar inspection required explicit `li` selector instead of container `.sidebar`
3. Must use `@file.sql` pattern to avoid Windows quoting issues with X-SQL

---

---

## Issues Found (7 issues)

### Issue 1: Text extraction silently truncates long content

**Severity:** High
**Category:** Product

#### Reproduction

```
cargo run -- htmlsnapshot get all text "h3 a"
```
on `http://books.toscrape.com/`.

#### Expected Behavior

Full book titles like "A Light in the Attic", "Sapiens: A Brief History of Humankind".

#### Actual Behavior

Truncated titles like "A Light in the ...", "Sapiens: A Brief History ...", "The Coming Woman: A ...". The `title` attribute on `<a>` elements contains the full text, but `text` extraction truncates it.

#### Root Cause Analysis

The HTML snapshot text extraction likely applies a character limit per text node (appears to be ~40-50 chars). This is not documented and silently loses data. The truncation is applied before the data reaches the user — there's no warning, flag, or indication that content was cut off.

#### Code Pointer

`Needs investigation in the backend Java snapshot/text extraction pipeline.`

#### AI Suggested Improvement

- Increase or remove the text truncation limit for `htmlsnapshot get text` — at minimum, document the limit and offer a `--no-truncate` flag
- Add a `TRUNCATED` indicator (e.g., append `…` differently from `...` in source data) so users can distinguish truncated text from literal ellipses
- Surface the `title` attribute hint in the inspect output when text nodes appear truncated (e.g., "Text may be truncated. Try `get attr 'h3 a' title` for full text")

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [x] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: `htmlsnapshot inspect` on a single container element silently redirects to auto-discovered pattern

**Severity:** Medium
**Category:** UX

#### Reproduction

```
cargo run -- goto "http://books.toscrape.com/"
cargo run -- htmlsnapshot
cargo run -- htmlsnapshot inspect ".sidebar"
```

#### Expected Behavior

The sidebar's internal structure (category list items, nested ul/li hierarchy) should be analyzed, since the user explicitly provided `.sidebar` as the scoping selector.

#### Actual Behavior

Output shows `### Inspect: ".product_pod" (20 matches, 10 analyzed)` with `🔍 Auto-discovered repeating pattern from ".sidebar"`. The user's explicit selector is silently overridden by auto-discovery. The user must use `.side_categories ul li` instead to see sidebar structure.

#### Root Cause Analysis

When a selector matches only one element, `inspect` activates auto-discovery mode (per documentation: "When selector matches only 1 element... auto-discovery activates"). This is by design, but the design doesn't account for the case where a user intentionally wants to inspect a single container's internal structure. The auto-discovery hint ("The tool found that `.sidebar` repeats as a sibling group") is misleading — `.sidebar` is not repeating.

#### Code Pointer

`Likely in the backend Java inspection service that handles single-element selectors.`

#### AI Suggested Improvement

- When a user provides an explicit selector (not `:root` default), inspect should first show the single-element structure at the requested depth, THEN optionally suggest auto-discovered patterns as a "See also" hint
- Add a `--no-auto-discover` flag to suppress auto-discovery when the user wants to focus on their chosen selector
- The hint text "repeats as a sibling group" is inaccurate when the target is a single container — fix the messaging

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: `htmlsnapshot grep -c --selector` semantics are confusing

**Severity:** Medium
**Category:** UX

#### Reproduction

```
cargo run -- htmlsnapshot grep -c --selector ".product_pod" "price_color"
```

#### Expected Behavior

Count of elements matching `.product_pod` that contain `price_color`, or count of `.product_pod` elements on the page. Users coming from `grep` or jQuery expect `-c` to count matches at the selector level.

#### Actual Behavior

Returns `1` — counts the number of lines within the *first* `.product_pod` element that match the pattern. The `--selector` scopes to a single element (querySelector semantics), so `-c` counts lines within that one element.

#### Root Cause Analysis

The `--selector` flag uses `querySelector` (single element) semantics, not `querySelectorAll` (all elements). Combined with `-c`, this is counterintuitive — users naturally expect selector + count = "how many elements match". The documentation describes `--selector` as "Scope search to a specific CSS element", which correctly implies single-element scoping, but the interaction with `-c` is not explicitly documented.

#### Code Pointer

``cli/browser4-cli/src/` — the grep subcommand handler.`

#### AI Suggested Improvement

- Add a `--selector-all` flag that uses `querySelectorAll` semantics and counts across all matched elements
- Document the `--selector` / `-c` interaction explicitly in the help text and reference docs
- Consider changing `-c` behavior when `--selector` is present to report "N matches across 1 scoped element" to clarify what happened

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**
accept 1. Add a --selector-all flag that uses querySelectorAll semantics and counts across all matched elements

---

### Issue 4: Suggested selectors from inspect don't include the most obvious ones for link text

**Severity:** Low
**Category:** UX

#### Reproduction

```
cargo run -- htmlsnapshot inspect ".product_pod"
```

#### Expected Behavior

The suggested selectors would include `h3 a` as a high-quality selector for book titles, since every product card has an `h3 > a` structure for the title.

#### Actual Behavior

`h3 a` does not appear. The suggested selectors include `h3:expr(a>0)` (which matches `h3` elements containing an `a`) and `a:expr(a>0)` (which is too broad). Users must infer `h3 a` from the sample structure. Similarly, `p.price_color` IS suggested but `div.product_price p.price_color` (more specific) is not.

#### Root Cause Analysis

The inspect algorithm generates selectors at each DOM depth level independently. `h3 a` is a descendant combinator that crosses depth levels, which the current algorithm doesn't generate. It prefers single-element selectors with `:expr()` filters.

#### Code Pointer

`Backend Java inspection service — selector generation algorithm.`

#### AI Suggested Improvement

- Generate simple 2-level descendant selectors (parent > child, parent child) when the parent is itself a recurring element within the scoped container
- Prioritize selectors that produce unique, non-truncated text values (both `h3 a` and `p.price_color` produce clean values)
- Add a "Text-bearing selectors" subsection that groups selectors which yield visible text content

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: WPSI summary content section dominated by buttons — misses key content

**Severity:** Low
**Category:** Product

#### Reproduction

```
cargo run -- htmlsnapshot summary
```

#### Expected Behavior

The "Content" section (top 20 of 100 nodes) would show a mix of meaningful page content: headings, links, product titles, prices.

#### Actual Behavior

20 of 20 displayed content nodes are `button "Add to basket"` (score: 55). The `h1 "All products"` appears at position 1 (score: 100), followed by 19 identical buttons. Product titles, prices, sidebar links, and the "next" pagination link are all absent from the top 20.

#### Root Cause Analysis

The scoring formula (h1=100, button=50, a=15, etc.) means all 20 "Add to basket" buttons outscore any link text (max score ~15). With 20 buttons on the page, they crowd out all other content. The score doesn't penalize repetition — identical buttons should be deduplicated in the summary.

#### Code Pointer

`Backend Java WPSI/summary generation service — scoring and deduplication logic.`

#### AI Suggested Improvement

- Deduplicate identical content nodes in the summary display (one "Add to basket" button is enough to know they exist)
- Apply a diversity bonus or repetition penalty so the top 20 nodes show content variety
- Show a count indicator for repeated elements: `button "Add to basket" (×20)` instead of 20 separate entries

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: Windows shell escaping friction for X-SQL queries

**Severity:** Medium
**Category:** Documentation / UX

#### Reproduction

Attempting inline `--sql` with CSS selectors containing quotes on Windows (Git Bash or PowerShell).

#### Expected Behavior

A straightforward way to pass SQL with quoted selectors.

#### Actual Behavior

The SKILL.md warns against inline SQL on Windows and recommends `--sql @file.sql`, `--sql-stdin`, or `--sql-base64`. These workarounds are effective, but they require creating temporary files or base64-encoding, adding friction to what should be a one-liner. The `@file.sql` approach works but requires the file to be at a discoverable path (repo root or CWD).

#### Root Cause Analysis

Windows shell quoting differs from Unix. Nested quotes (SQL strings inside shell strings) break on Windows. The `@file` workaround is well-documented but adds an extra step for every ad-hoc query.

#### Code Pointer

``cli/browser4-cli/src/args.rs` — argument parsing; documentation in `skills/browser4-cli/SKILL.md` and `skills/browser4-cli/references/htmlsnapshot.md`.`

#### AI Suggested Improvement

- Add a `--sql-literal` flag that accepts a single-line SQL string with a different quoting convention (e.g., use backticks for internal strings instead of single quotes), parsed and converted before sending
- Add a `--sql-file` alias that is more discoverable than the `@` prefix convention (some users may miss that `@` means "read from file")
- For ad-hoc use, support a heredoc-style input: `browser4-cli htmlsnapshot query --sql-stdin <<< "SELECT ..."` — document this pattern explicitly for bash-on-Windows users

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [x] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**
there are already ways to load sql from file, stdin, and base64 strings, no need to introduce other ways to address such issues.

---

### Issue 7: No discoverable way to see `htmlsnapshot inspect` sub-options from CLI help

**Severity:** Low
**Category:** Discoverability

#### Reproduction

```
cargo run -- htmlsnapshot inspect --help
```

#### Expected Behavior

Help output showing `--max`, `--depth` flags and their defaults.

#### Actual Behavior

(Not tested, but the main `--help` output shows `htmlsnapshot inspect [selector]` with no mention of `--max` or `--depth`. Users must read the reference docs to discover these options.)

#### Root Cause Analysis

The CLI help text for `htmlsnapshot inspect` in the main `--help` output is a single line with no sub-options listed. The `--max` and `--depth` flags are documented only in the reference markdown files.

#### Code Pointer

``cli/browser4-cli/src/help.rs` or `cli/browser4-cli/src/commands.rs` — help text generation.`

#### AI Suggested Improvement

- Add `--max` and `--depth` to the inline help text: `htmlsnapshot inspect [selector] [--max N] [--depth D]`
- Ensure `browser4-cli htmlsnapshot inspect --help` shows full flag documentation

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
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

#### Issue 1: Text extraction silently truncates long content

```
cargo run -- htmlsnapshot get all text "h3 a"
```
on `http://books.toscrape.com/`.

#### Issue 2: `htmlsnapshot inspect` on a single container element silently redirects to auto-discovered pattern

```
cargo run -- goto "http://books.toscrape.com/"
cargo run -- htmlsnapshot
cargo run -- htmlsnapshot inspect ".sidebar"
```

#### Issue 3: `htmlsnapshot grep -c --selector` semantics are confusing

```
cargo run -- htmlsnapshot grep -c --selector ".product_pod" "price_color"
```

#### Issue 4: Suggested selectors from inspect don't include the most obvious ones for link text

```
cargo run -- htmlsnapshot inspect ".product_pod"
```

#### Issue 5: WPSI summary content section dominated by buttons — misses key content

```
cargo run -- htmlsnapshot summary
```

#### Issue 6: Windows shell escaping friction for X-SQL queries

Attempting inline `--sql` with CSS selectors containing quotes on Windows (Git Bash or PowerShell).

#### Issue 7: No discoverable way to see `htmlsnapshot inspect` sub-options from CLI help

```
cargo run -- htmlsnapshot inspect --help
```

