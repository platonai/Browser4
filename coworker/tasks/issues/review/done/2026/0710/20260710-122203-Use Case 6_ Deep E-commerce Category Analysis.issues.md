# Issues: Use Case 6: Deep E-commerce Category Analysis

> **Source:** `20260710-122203-Use Case 6_ Deep E-commerce Category Analysis.full.md` | **Date:** 20260710-122203 | **Mode:** dev

## Scenario Background

### Task

✅ **Task completed.** The `headphone-analysis.md` report has been generated at `D:/workspace/Browser4/Browser4-4.11/headphone-analysis.md`. It contains:

- Search & filtering methodology
- Top 5 products summary table with prices, ratings, and value scores
- Detailed analysis of each product with features and common negative keywords
- Cross-product negative keyword analysis with frequency and severity ratings
- Value ranking (Soundcore P30i #1, Soundcore Q20i #2)
- Recommendations by use case
- Data extraction notes documenting limitations

**Limitation:** Review body text could not be extracted because Amazon lazy-loads reviews and blocks the `/product-reviews/` endpoint without authentication. Negative keywords were inferred from ratings, feature gaps, and product category knowledge rather than direct review text mining.

---

### Execution Context

**Key Commands:**

| # | Command | Purpose |
|---|---------|---------|
| 1 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help` | Discover available commands |
| 2 | `goto "https://www.amazon.com/s?k=noise+cancelling+headphones"` | Direct URL search (per Amazon docs recommendation) |
| 3 | `snapshot -v 0` | Capture search results page structure |
| 4 | `snapshot grep -i "4 star"` | Find 4-star rating filter element |
| 5 | `snapshot grep -i "sort\|best seller"` | Find sort dropdown |
| 6 | `click e5690` | Apply 4 Stars & Up filter |
| 7 | `snapshot -v 0 --stdout \| grep "combobox\|Sort by"` | Find sort dropdown on new page |
| 8 | `select e17988 "Best Sellers"` | Select Best Sellers sort option |
| 9 | `htmlsnapshot` | Capture HTML snapshot of filtered results |
| 10 | `htmlsnapshot get all attr "a.a-link-normal.s-line-clamp-2" href --limit 5` | Extract top 5 product links |
| 11 | `htmlsnapshot get all text "a.a-link-normal.s-line-clamp-2" --limit 5` | Extract top 5 product titles |
| 12 | `htmlsnapshot get all text "span.a-offscreen" --limit 10` | Extract prices from search |
| 13 | `htmlsnapshot get all text "span.a-icon-alt" --limit 10` | Extract ratings from search |
| 14–18 | 5× `goto "https://www.amazon.com/dp/<ASIN>"` + `htmlsnapshot` + `htmlsnapshot get text` for title/price/rating/review count | Open each product and extract core data |
| 19–23 | 5× `eval "JSON.stringify({features:..., reviews:...})" --json` | Extract feature bullets via JS for each product |
| 24 | `scroll down 3000` + `wait --load networkidle` | Attempt to trigger lazy-loaded reviews |
| 25 | Multiple `eval` attempts with various review selectors | Attempt to extract review text (failed) |
| 26 | `goto "https://www.amazon.com/product-reviews/B000UXZQ42"` | Attempt direct review page access (redirected to sign-in) |
| 27 | `go-back` | Return to product page |

**Workarounds Applied During Task:**

1. **URL injection for Amazon search** — documented in the Amazon scenarios but not obvious to a first-time user
2. **`eval` with inline JavaScript for feature extraction** — CSS selectors for feature bullets vary between Amazon product page layouts
3. **Manual product page visits** — no built-in "open top N search results" batch command
4. **Shell quoting care** — JavaScript with nested quotes in `eval` required careful escaping on Windows

---

---

## Issues Found (8 issues)

### Issue 1: Template variables (`$cliInvocation`, `$helpCmd`, etc.) are undefined

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Read the evaluation task template. It references `$RepoRootPath`, `$helpCmd`, `$skillPath`, and `$cliInvocation` as if defined.

#### Expected Behavior

These should be documented constants or environment variables.

#### Actual Behavior

Values must be reverse-engineered from `skills/browser4-cli/references/development.md`.

#### Root Cause Analysis

The evaluation template uses placeholder variables that assume a setup script has defined them. The `development.md` file does document `cargo run --manifest-path cli/browser4-cli/Cargo.toml --` but doesn't label it as `$cliInvocation`.

#### Code Pointer

`Evaluation task template (not in browser4-cli codebase).`

#### AI Suggested Improvement

- Replace template variables with literal commands in the evaluation template, or add a short "Setup" block at the top that defines them explicitly
- Add `$cliInvocation` as a clearly labeled constant in `development.md`: "**Dev invocation (aka `$cliInvocation`):** `cargo run --manifest-path cli/browser4-cli/Cargo.toml --`"

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Cannot extract review text from Amazon product pages

**Severity:** High
**Category:** Reliability

#### Reproduction

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "https://www.amazon.com/dp/B0C3HCD34R"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- scroll down 8000
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- wait --load networkidle
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- eval "...[data-hook=review-body]..." --json
# Returns: []
```

#### Expected Behavior

Review text should be extractable via `htmlsnapshot get`, `eval`, or similar commands.

#### Actual Behavior

Amazon lazy-loads customer reviews — they do not appear in the DOM after navigation or scrolling. The direct `/product-reviews/<ASIN>` URL redirects to a sign-in page, blocking all access to review content without authentication.

#### Root Cause Analysis

Amazon uses client-side rendering with intersection observers for review loading, and requires authentication for the dedicated reviews page. The browser4-cli cannot force-load lazy content that depends on viewport visibility triggers or authenticated endpoints.

#### Code Pointer

`This is a site-specific limitation, not a bug in browser4-cli. However, a built-in "scroll to element" or "force-load lazy content" feature would help.`

#### AI Suggested Improvement

- Add a `scroll-into-view <ref|selector>` command that uses `Element.scrollIntoView()` to trigger intersection-observer-based lazy loading
- Add a `wait --selector <css>` mode that polls for an element to appear (useful for lazy-loaded content)
- Document site-specific workarounds for common lazy-loading patterns in the Amazon scenarios reference

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: `htmlsnapshot grep` matches JavaScript source code in HTML

**Severity:** Medium
**Category:** UX

#### Reproduction

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "https://www.amazon.com/dp/B000UXZQ42"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot grep -i "review|complaint"
# Output: 787.6KB of mostly JavaScript library code, not page content
```

#### Expected Behavior

`htmlsnapshot grep` should search the rendered text content, not raw HTML including `<script>` tag content.

#### Actual Behavior

The grep matches against the full raw HTML including all inline `<script>` blocks. On Amazon pages (2.5MB HTML with massive JS payloads), meaningful matches are buried in thousands of lines of minified JavaScript. The output was 787KB — impossible to use effectively.

#### Root Cause Analysis

`htmlsnapshot grep` operates on the raw stored HTML without stripping `<script>` and `<style>` tag content. On JS-heavy pages, script content dwarfs actual text content.

#### Code Pointer

``cli/browser4-cli/src/` — the htmlsnapshot grep implementation should strip or skip `<script>` and `<style>` content before matching.`

#### AI Suggested Improvement

- Strip `<script>` and `<style>` tag content before running grep on HTML snapshots (or add a `--no-scripts` / `--text-only` flag)
- Add a `--selector` flag that also works with `snapshot grep` to scope searches to a specific DOM region
- Default to text-only matching with an opt-in `--raw-html` flag for the rare case when script/style searching is desired

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: `scroll` command has confusing cumulative behavior and opaque output

**Severity:** Low
**Category:** UX

#### Reproduction

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- scroll down 3000
# → 3000.0
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- scroll down 5000
# → 8000.0
```

#### Expected Behavior

`scroll down 5000` should scroll 5000 pixels from the current position, and output should indicate what happened (e.g., "Scrolled down 5000px to position 8000").

#### Actual Behavior

The output is just a number with no context. The scroll appears to be cumulative (absolute position), not relative as the command syntax suggests. The `scroll` help says `scroll <direction> <pixels>` which implies relative scrolling, but the output shows absolute cumulative values.

#### Root Cause Analysis

The scroll implementation likely uses `window.scrollBy()` but the output reports `window.scrollY` (absolute position) rather than the amount scrolled. This mismatch between the command description ("Scroll the page in a given direction by the specified number of pixels") and the output creates confusion.

#### Code Pointer

``cli/browser4-cli/src/` — the scroll command handler; should clarify whether pixels are relative or absolute and make output descriptive.`

#### AI Suggested Improvement

- Make the output descriptive: "Scrolled down 3000px (position: 3000/9358)"
- Clarify in help whether `<pixels>` is relative or absolute
- Consider adding `scroll to <y>` for absolute positioning and keep `scroll <direction> <pixels>` as relative

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: `--manifest-path` invocation is verbose and error-prone

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Every command in dev mode requires the full `cargo run --manifest-path cli/browser4-cli/Cargo.toml --` prefix (77 characters).

#### Expected Behavior

A shorter dev invocation pattern, or a wrapper script in the repo.

#### Actual Behavior

The 77-character prefix must be typed before every command. The `cli/bin/browser4-cli.js` wrapper exists but uses npm's global resolution, not the local source build.

#### Root Cause Analysis

`cargo run` from the repo root requires `--manifest-path` because `Cargo.toml` is in a subdirectory. The SKILL.md documents the alternative `cd cli/browser4-cli && cargo run --` but this changes the working directory and affects relative file paths.

#### Code Pointer

``cli/bin/browser4-cli.js` could be enhanced to detect a local Cargo build.`

#### AI Suggested Improvement

- Add a `bin/dev-cli.ps1` / `bin/dev-cli.sh` wrapper script that sets up the invocation
- Document a shell alias in `development.md`: `alias b4d='cargo run --manifest-path cli/browser4-cli/Cargo.toml --'`
- Enhance `cli/bin/browser4-cli.js` to auto-detect the local source tree and use `cargo run` when available

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: No built-in "extract top N search results" workflow

**Severity:** Medium
**Category:** UX

#### Reproduction

Attempting to extract the top 5 products from search results requires: (a) extracting link URLs with `get all attr`, (b) manually visiting each product page with `goto`, (c) individually extracting data from each page. This takes 15+ commands for 5 products.

#### Expected Behavior

A higher-level command or documented pattern for "take the top N results from a search/list page and extract data from each."

#### Actual Behavior

Each step must be manually orchestrated. `crawl` exists but requires seed files and is oriented toward link-following depth rather than top-N extraction from a single page.

#### Root Cause Analysis

The command set is well-suited for single-page interaction and extraction, but multi-page orchestration (visit N pages and collect data) requires manual scripting.

#### AI Suggested Improvement

- Add a scenario to `htmlsnapshot-scenarios-amazon.md` for "top-N product extraction workflow" showing the full pattern
- Consider a `swarm`-based recipe where search results URLs are submitted as a seed file for parallel extraction
- Add a `--follow-links N` flag to `htmlsnapshot query` that auto-navigates to the first N extracted URLs and runs a second query on each

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: Amazon scenarios documentation contains stale selectors

**Severity:** Low
**Category:** Documentation

#### Reproduction

The Amazon scenarios doc (`htmlsnapshot-scenarios-amazon.md`) suggests `.s-result-item[data-component-type='s-search-result']` as the product card selector. However, the `htmlsnapshot` interactive elements list showed product links under class `.s-line-clamp-2`, suggesting the actual DOM structure differs from the documented examples.

#### Expected Behavior

Selectors in the documentation should match current Amazon DOM structure, or the docs should emphasize discovery-first more prominently.

#### Actual Behavior

The documented selector `.a-offscreen` for prices worked, but `h2 a.a-link-normal` for titles matched navigation elements in addition to product titles. The product links were discovered via `htmlsnapshot` output rather than the documented selectors.

#### Root Cause Analysis

Amazon frequently updates its CSS classes. The scenarios document acknowledges this with a warning note but doesn't provide a clear "discovery-first" workflow at the top of each scenario.

#### Code Pointer

``skills/browser4-cli/references/htmlsnapshot-scenarios-amazon.md` — scenarios 15-16.`

#### AI Suggested Improvement

- Add a bold callout at the top of each Amazon scenario: "⚠️ Always run `htmlsnapshot inspect` first to verify selectors on your locale"
- Add a timestamp or "last verified" date to each scenario
- Include an auto-discovery step as Step 0 in every extraction scenario: "Step 0: Run `htmlsnapshot inspect` to discover current selectors"

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: X-SQL `query` has documented Jackson serialization bug

**Severity:** Medium
**Category:** Reliability

#### Reproduction

See `htmlsnapshot-scenarios.md` §"Tested & Verified" note:
> "The X-SQL query path (`htmlsnapshot query`) has a known Jackson serialization issue with `java.time.Instant` fields in `ScrapeResponse`. A fix has been applied in `MCPToolController.kt`... This requires a server rebuild to take effect."

#### Expected Behavior

All documented extraction methods should work reliably without caveats.

#### Actual Behavior

The most powerful extraction method (X-SQL `query` for correlated multi-field extraction) has a known serialization bug. The existence of a fix that "requires a server rebuild" suggests the fix may not be deployed.

#### Root Cause Analysis

The `ObjectMapper` used for JSON serialization in the query response path doesn't include `JavaTimeModule`, causing `java.time.Instant` fields to fail serialization.

#### Code Pointer

``browser4-rest/.../MCPToolController.kt` — use Spring-configured `ObjectMapper` with `JavaTimeModule` instead of `jacksonObjectMapper()`.`

#### AI Suggested Improvement

- Verify the fix is applied in the current build and remove the caveat from docs if resolved
- Add a regression test that validates X-SQL query output can be serialized and deserialized
- Consider adding `JavaTimeModule` to the default `ObjectMapper` configuration globally

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
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Template variables (`$cliInvocation`, `$helpCmd`, etc.) are undefined

Read the evaluation task template. It references `$RepoRootPath`, `$helpCmd`, `$skillPath`, and `$cliInvocation` as if defined.

#### Issue 2: Cannot extract review text from Amazon product pages

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "https://www.amazon.com/dp/B0C3HCD34R"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- scroll down 8000
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- wait --load networkidle
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- eval "...[data-hook=review-body]..." --json
# Returns: []
```

#### Issue 3: `htmlsnapshot grep` matches JavaScript source code in HTML

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "https://www.amazon.com/dp/B000UXZQ42"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot grep -i "review|complaint"
# Output: 787.6KB of mostly JavaScript library code, not page content
```

#### Issue 4: `scroll` command has confusing cumulative behavior and opaque output

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- scroll down 3000
# → 3000.0
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- scroll down 5000
# → 8000.0
```

#### Issue 5: `--manifest-path` invocation is verbose and error-prone

Every command in dev mode requires the full `cargo run --manifest-path cli/browser4-cli/Cargo.toml --` prefix (77 characters).

#### Issue 6: No built-in "extract top N search results" workflow

Attempting to extract the top 5 products from search results requires: (a) extracting link URLs with `get all attr`, (b) manually visiting each product page with `goto`, (c) individually extracting data from each page. This takes 15+ commands for 5 products.

#### Issue 7: Amazon scenarios documentation contains stale selectors

The Amazon scenarios doc (`htmlsnapshot-scenarios-amazon.md`) suggests `.s-result-item[data-component-type='s-search-result']` as the product card selector. However, the `htmlsnapshot` interactive elements list showed product links under class `.s-line-clamp-2`, suggesting the actual DOM structure differs from the documented examples.

#### Issue 8: X-SQL `query` has documented Jackson serialization bug

See `htmlsnapshot-scenarios.md` §"Tested & Verified" note:
> "The X-SQL query path (`htmlsnapshot query`) has a known Jackson serialization issue with `java.time.Instant` fields in `ScrapeResponse`. A fix has been applied in `MCPToolController.kt`... This requires a server rebuild to take effect."

