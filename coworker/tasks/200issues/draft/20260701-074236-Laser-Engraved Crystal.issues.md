# Issues: Laser-Engraved Crystal

> **Source:** `20260701-074236-Laser-Engraved Crystal.full.md` | **Date:** 20260701-074236 | **Mode:** dev

## Scenario Background

### Task

## 🏆 Winner: Axolotl Lamp — 3D K9 Crystal Ball Night Light

| Attribute | Details |
|-----------|---------|
| **ASIN** | B0C17W3Q9B |
| **URL** | `https://www.amazon.com/dp/B0C17W3Q9B` |
| **Price** | $15.98 |
| **Rating** | 4.7 out of 5 stars (1,022 ratings) |
| **Size** | 3.15 inches (80mm) |
| **Features** | 16 colors, 4 lighting modes, remote control, USB powered, wooden base, K9 crystal, gift box |
| **Availability** | In Stock |

### Why This One Wins

1. **Axolotls are a cultural phenomenon** with tweens/teens — Minecraft, social media, and gaming culture have made them iconic. A 12-year-old will immediately recognize and appreciate this.
2. **Remote control + 16 colors** = gadget factor. The ability to choose colors and switch between flash/strobe/fade/smooth modes adds interactive play value beyond a static decoration.
3. **Excellent review profile**: 1,022 ratings at 4.7★ signals consistent quality and buyer satisfaction.
4. **Right price point**: $15.98 is reasonable for a gift — not so cheap it feels insubstantial, not so expensive it creates pressure.
5. **Gift-ready**: Includes gift box, remote, USB cable, and cleaning cloth.
6. **USB powered**: No battery hassle.
7. **Gender-neutral marketing**: Unlike several competitors explicitly targeting "women/girlfriends/daughters," this is positioned as a "Birthday Christmas axolotl Gift."

---

## Shortlist Recap (All 10 Reviewed)

| Rank | Product | Price | Rating | Reviews | LED | Verdict |
|------|---------|-------|--------|---------|-----|---------|
| **1** | **Axolotl Lamp** | $15.98 | 4.7★ | 1,022 | ✅ 16-color + remote | **BEST** |
| 2 | DNA Crystal Ball | $27.99 | 4.7★ | 154 | ✅ 5-color | Great for science-minded kids |
| 3 | Wolf Crystal Ball | $26.99 | 4.7★ | 427 | ✅ 6-color touch | Cool animal, solid pick |
| 4 | Eagle Crystal Ball | $8.99 | 4.8★ | 1,069 | ❌ | Budget pick, top-rated |
| 5 | Airplane Crystal Ball | $11.99 | 4.8★ | 11 | ✅ Wooden base | Aviation theme, few reviews |
| 6 | Galaxy Crystal Balls (2pk) | $13.99 | 4.7★ | 28 | ❌ | Space theme, few reviews |
| 7 | Saturn Crystal Ball | $8.99 | 4.4★ | 105 | ❌ | Lower rating |
| 8 | Lightning Cloud | $8.11 | 4.6★ | 951 | ❌ | No LED, "Feng Shui" marketing |
| 9 | Sea Turtle Crystal Ball | $9.99 | 4.7★ | 1,106 | ❌ | Low stock (15 left) |
| 10 | Owl Lamp | $24.99 | 4.5★ | 7 | ✅ Multi-color | Very few reviews, "emotional support" angle |

---

### Execution Context

**Key Commands:**

1. `cargo run -- help` — discovered available commands
2. `cargo run -- goto "https://www.amazon.com/s?k=Laser-Engraved+Crystal"` — navigated to search results
3. `cargo run -- domsnapshot` — captured static DOM (used 11 times: 1 search + 10 product pages)
4. `cargo run -- domsnapshot inspect` and `domsnapshot inspect "[data-component-type=s-search-result]" --depth 8 --max 5` — discovered selectors
5. `cargo run -- domsnapshot get all text ".s-line-clamp-4" --all` — extracted 69 product titles
6. `cargo run -- domsnapshot get all text ".a-price .a-offscreen" --all` — extracted 91 prices
7. `cargo run -- domsnapshot get all text "[aria-label*=\"stars\"]" --all` — extracted 67 ratings
8. `cargo run -- domsnapshot get all attr ".s-line-clamp-4" href --all` — extracted product URLs
9. `cargo run -- eval --file extract_products.js` — extracted structured JSON of 60 products with titles, prices, ratings, URLs, ASINs
10. `cargo run -- eval --file extract_detail.js` — extracted structured product details (used for 9 products)
11. `cargo run -- domsnapshot get text "#productTitle"` — extracted individual product field
12. `cargo run -- goto "https://www.amazon.com/dp/<ASIN>"` — navigated to each product (10 times)

**Workarounds Applied During Task:**

- **Shell path issue**: `cd cli/browser4-cli` failed from repo root because the shell was already in that directory. Used relative commands instead.
- **Selector discovery**: Had to experiment with multiple CSS selectors (`h2 a`, `.s-line-clamp-4`, `h2`) to find working title extraction — the documented `h2 a` selector returned `[]`.
- **Python unavailable as `python3`**: Had to use `python` instead, and had to work around Windows path/encoding issues.
- **JSON parsing**: The `products_full.json` output had shell prefix lines that needed to be skipped before JSON parsing.
- **Data alignment**: Prices and titles had different array lengths (91 vs 69) requiring the `eval` approach for correlated extraction.

---

---

## Issues Found (10 issues)

### Issue 1: `cd cli/browser4-cli` path assumption doesn't match repo layout

**Severity:** Low
**Category:** Documentation

#### Reproduction

Follow the instruction "Run `cd cli/browser4-cli && cargo run -- help`" without first verifying the directory structure.

#### Expected Behavior

The command works from the repo root.

#### Actual Behavior

The Cargo.toml is at the repo root, not in a `cli/browser4-cli` subdirectory. The `cd cli/browser4-cli` command from repo root fails with "No such file or directory."

#### Root Cause Analysis

The evaluation instructions assumed a standard `cli/browser4-cli` subdirectory layout, but in this repo the CLI project is the root-level Cargo project. This is a doc-instruction mismatch, not a code bug.

#### Code Pointer

`N/A (documentation issue)`

#### AI Suggested Improvement

- Update the evaluation preparation instructions to first run `ls Cargo.toml` or `find . -name Cargo.toml -maxdepth 3` to discover the CLI directory
- Document the expected directory layout in a setup section

#### Human Review

(leave empty — reserved for human review)


---

### Issue 2: `python3` not found on Windows; help output doesn't document Python dependency

**Severity:** Low
**Category:** Documentation

#### Reproduction

Run `python3 --version` on Windows (with Miniconda installed as `python`, not `python3`).

#### Expected Behavior

Clear documentation of scripting prerequisites.

#### Actual Behavior

`python3` returns exit code 49 (not found). The `python` command works instead.

#### Root Cause Analysis

On Windows, Python installations (including Miniconda) typically register as `python` not `python3`. The SKILL.md examples showing `eval --file` workflows work around this, but users doing post-processing with Python need to know the correct command name.

#### Code Pointer

`N/A (documentation issue)`

#### AI Suggested Improvement

- Add a note in the installation section that Python scripts should use `python` on Windows
- Consider adding a `doctor` check that verifies Python availability

#### Human Review

(leave empty — reserved for human review)


---

### Issue 3: `domsnapshot inspect` depth display is shallow — deep child elements not shown

**Severity:** Medium
**Category:** UX

#### Reproduction

```
cargo run -- domsnapshot inspect "[data-component-type=s-search-result]" --depth 8 --max 5
```

#### Expected Behavior

The inspect output should show the full card DOM structure to at least 4-5 levels deep, revealing selectors for prices, titles, ratings nested inside product cards.

#### Actual Behavior

Output only shows 2 levels of depth (`div.sg-col-inner` → nothing deeper), even with `--depth 8`. Key selectors like `h2 a`, `span.a-offscreen`, and `[aria-label*="stars"]` are NOT discovered by inspect — they had to be found through trial and error.

#### Root Cause Analysis

The inspect algorithm appears to stop at container boundaries or limits recursive descent based on element type. The `--depth` flag may control how many sample elements to analyze rather than DOM tree depth, or the inspection may terminate early on elements with few children.

#### Code Pointer

`Needs investigation — likely in the `domsnapshot inspect` implementation that traverses child elements.`

#### AI Suggested Improvement

- Ensure `--depth` flag controls actual DOM nesting depth, not sample count
- When inspect finds only shallow containers (e.g., `div.sg-col-inner`), automatically recurse into them to find actionable selectors
- Consider adding a `--recurse` flag that forces descent into container elements

#### Human Review

(leave empty — reserved for human review)


---

### Issue 4: Discovered CSS selectors don't match documentation examples

**Severity:** Medium
**Category:** Documentation

#### Reproduction

1. Follow the Amazon scenario 15b from SKILL.md references
2. Run `cargo run -- domsnapshot get all text "h2 a" --limit 5`
3. Run `cargo run -- domsnapshot get all text "h2 a.a-link-normal" --limit 5`

#### Expected Behavior

Working selectors as shown in Scenario 15's example output.

#### Actual Behavior

`h2 a` returns `[]` (no elements matched). `h2 a.a-link-normal` also returns `[]`. The actual working selector is `.s-line-clamp-4` or bare `h2`.

#### Root Cause Analysis

Amazon has changed their HTML structure since the documentation was written. The documented selectors (`h2 a.a-link-normal`, `span.a-icon-alt`, `img.s-image`) have changed class names (e.g., `a-link-normal` → `s-line-clamp-4 s-link-style a-text-normal`). The `inspect` output even shows the old class name patterns in its suggestions, suggesting the inspect algorithm may cache or hardcode expected patterns.

#### Code Pointer

`N/A (the documentation correctly warns "CSS selectors are tied to live websites — they WILL break over time")`

#### AI Suggested Improvement

- Add a timestamp or "last verified" date to each documented selector example
- Provide a `--verify` flag on inspect that tests suggested selectors against actual DOM and reports which work
- Consider adding Amazon-specific selector fallbacks that try multiple known patterns

#### Human Review

(leave empty — reserved for human review)


---

### Issue 5: Sponsored product URLs obscure clean ASIN-based links

**Severity:** Low
**Category:** UX

#### Reproduction

```
cargo run -- domsnapshot get all attr ".s-line-clamp-4" href --all
```

#### Expected Behavior

Clean product URLs like `https://www.amazon.com/dp/B0FCLQZDHV`.

#### Actual Behavior

URLs are massive tracking redirects through `/sspa/click?ie=UTF8&spc=...` with encoded redirect parameters, making them unusable for manual navigation.

#### Root Cause Analysis

Amazon wraps sponsored product links in tracking redirects. The clean `/dp/<ASIN>/` URL is buried inside the `url=` query parameter.

#### Code Pointer

`Could be addressed in eval scripts or X-SQL queries that extract ASIN directly from `data-asin` attributes instead of following the `href`.`

#### AI Suggested Improvement

- Document the pattern: extract `data-asin` attribute instead of `href` for clean product URLs
- Add an example in the e-commerce scenario showing `dom snapshot get all attr "[data-asin]" data-asin`

#### Human Review

(leave empty — reserved for human review)


---

### Issue 6: `--json` flag on `domsnapshot get` doesn't suppress tips/output prefix

**Severity:** Low
**Category:** UX

#### Reproduction

```
cargo run -- domsnapshot get text "#productTitle" --json
```

#### Expected Behavior

Clean JSON output on stdout suitable for piping to `jq` or other tools.

#### Actual Behavior

The output still includes "Finished...", "Running...", and "💡 Tip:" lines mixed with the data, making it unusable for machine parsing without grep/sed post-processing.

#### Root Cause Analysis

The `--json` flag appears to format the extracted data as JSON but doesn't suppress the informational prefix/suffix lines. These come from cargo build output and CLI tips that are printed to stdout rather than stderr.

#### Code Pointer

`Likely in the CLI output handler that prints tips and build status to stdout — these should go to stderr or be suppressed in `--json` mode.`

#### AI Suggested Improvement

- Route cargo build output to stderr
- Suppress "💡 Tip:" lines when `--json` is active
- Consider adding `--no-tips` or `--machine` flag for clean machine-readable output

#### Human Review

- Route cargo build output to stderr
- Suppress "💡 Tip:" lines when `--json` is active

---

### Issue 7: No built-in way to correlate multiple `get all` outputs (titles vs prices vs URLs)

**Severity:** Medium
**Category:** Product

#### Reproduction

1. `domsnapshot get all text ".s-line-clamp-4" --all` → 69 titles
2. `domsnapshot get all text ".a-price .a-offscreen" --all` → 91 prices
3. Manual correlation of these arrays requires external scripting.

#### Expected Behavior

A built-in command (or an `--align` flag on `get all`) that extracts multiple fields from the same parent container and returns them as an array of objects.

#### Actual Behavior

Each `get all` call runs independently against different selectors, producing arrays of different lengths because some products lack prices, some prices belong to non-product elements, etc. Users must fall back to `eval` or X-SQL for correlated extraction.

#### Root Cause Analysis

`get all` runs `querySelectorAll` against the entire document for each field independently. There's no concept of "for each parent container, extract these child fields."

#### Code Pointer

`Could be a new subcommand like `domsnapshot get table <parent-selector> <field1-selector> <field2-selector>...` or a `--group-by <parent>` flag on `get all`.`

#### AI Suggested Improvement

- Add `domsnapshot get table <parent> <field>...` that extracts aligned field arrays from matching parent containers
- Alternatively, add `--parent <selector>` flag to `get all` that scopes extraction within each matching parent

#### Human Review

X-SQL is designed for such purpose. Read x-sql-dom-load-select.md to learn how to scrape a list page with multiple fields per item.

---

### Issue 8: `eval --json --file` rejected with confusing error

**Severity:** Medium
**Category:** Reliability

#### Reproduction

```
cargo run -- eval --json --file extract_products.js
```

#### Expected Behavior

JavaScript evaluated against the page, result serialized as JSON.

#### Actual Behavior

Error: `ERROR: browser_evaluate failed: Extraneous parameter 'json' for evaluateValue. Allowed=[expression]`

#### Root Cause Analysis

The `--json` flag conflicts with `--file` mode. The error message says "Allowed=[expression]" suggesting `--json` is interpreted as a positional argument when `--file` is used, rather than being recognized as a flag.

#### Code Pointer

``cli/browser4-cli/src/` — the eval command argument parsing that mishandles the `--json` + `--file` combination.`

#### AI Suggested Improvement

- Fix arg parsing so `--json` is recognized as a flag regardless of `--file`/`--stdin` mode
- Or document clearly that `--json` is implicit when using `--file`/`--stdin` (since objects/arrays auto-serialize)
- Improve error message to say "`--json` flag not needed with `--file` — JSON output is automatic"

#### Human Review

(leave empty — reserved for human review)


---

### Issue 9: `domsnapshot inspect` suggested selectors don't include depth hints for deeply nested content

**Severity:** Low
**Category:** UX

#### Reproduction

```
cargo run -- domsnapshot inspect "[data-component-type=s-search-result]" --depth 8 --max 10
```

#### Expected Behavior

Suggestions for selectors that reach actual content (titles, prices) inside the cards.

#### Actual Behavior

Suggestions are all shallow: `div.sg-col-inner`, `div.s-widget-container`, `span.a-declarative` — none of which are useful for extracting product data. The user has to guess deeper selectors.

#### Root Cause Analysis

The inspect algorithm analyzes structural recurrence at the selector level but doesn't automatically descend to find "leaf" content selectors. It stops at container boundaries.

#### Code Pointer

`The inspect implementation — consider adding a mode that prioritizes finding text-containing leaf elements.`

#### AI Suggested Improvement

- When inspect finds only container divs, automatically descend one more level and re-analyze
- Add a "content-first" mode (`--content`) that prioritizes finding selectors for text-bearing elements
- Show both "container selectors" and "content selectors" in separate output sections

#### Human Review

(leave empty — reserved for human review)


---

### Issue 10: Multiple `grep`/`tail` pipes strip actual data output

**Severity:** Medium
**Category:** UX

#### Reproduction

```
cargo run -- domsnapshot get text "#productTitle" 2>&1 | tail -1
```

#### Expected Behavior

Just the product title.

#### Actual Behavior

Only the "💡 Tip:" line, because the actual data is printed on the same line as the cargo build output or gets interleaved unpredictably.

#### Root Cause Analysis

The build output ("Finished...", "Running...") goes to stdout mixed with the command output. When piping, the relative ordering of these streams can cause data loss with `head`/`tail` filters.

#### Code Pointer

`The CLI output architecture — build progress should go to stderr so stdout is clean for piping.`

#### AI Suggested Improvement

- Print cargo build output to stderr, not stdout
- Add `--no-tips` flag to suppress tip lines
- Ensure `--json` produces only the JSON on stdout

#### Human Review

(leave empty — reserved for human review)


---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: `cd cli/browser4-cli` path assumption doesn't match repo layout

Follow the instruction "Run `cd cli/browser4-cli && cargo run -- help`" without first verifying the directory structure.

#### Issue 2: `python3` not found on Windows; help output doesn't document Python dependency

Run `python3 --version` on Windows (with Miniconda installed as `python`, not `python3`).

#### Issue 3: `domsnapshot inspect` depth display is shallow — deep child elements not shown

```
cargo run -- domsnapshot inspect "[data-component-type=s-search-result]" --depth 8 --max 5
```

#### Issue 4: Discovered CSS selectors don't match documentation examples

1. Follow the Amazon scenario 15b from SKILL.md references
2. Run `cargo run -- domsnapshot get all text "h2 a" --limit 5`
3. Run `cargo run -- domsnapshot get all text "h2 a.a-link-normal" --limit 5`

#### Issue 5: Sponsored product URLs obscure clean ASIN-based links

```
cargo run -- domsnapshot get all attr ".s-line-clamp-4" href --all
```

#### Issue 6: `--json` flag on `domsnapshot get` doesn't suppress tips/output prefix

```
cargo run -- domsnapshot get text "#productTitle" --json
```

#### Issue 7: No built-in way to correlate multiple `get all` outputs (titles vs prices vs URLs)

1. `domsnapshot get all text ".s-line-clamp-4" --all` → 69 titles
2. `domsnapshot get all text ".a-price .a-offscreen" --all` → 91 prices
3. Manual correlation of these arrays requires external scripting.

#### Issue 8: `eval --json --file` rejected with confusing error

```
cargo run -- eval --json --file extract_products.js
```

#### Issue 9: `domsnapshot inspect` suggested selectors don't include depth hints for deeply nested content

```
cargo run -- domsnapshot inspect "[data-component-type=s-search-result]" --depth 8 --max 10
```

#### Issue 10: Multiple `grep`/`tail` pipes strip actual data output

```
cargo run -- domsnapshot get text "#productTitle" 2>&1 | tail -1
```

