# Issues: x-sql-query-methods

> **Source:** `20260702-033651-x-sql-query-methods.full.md` | **Date:** 20260702-033651 | **Mode:** dev

## Scenario Background

### Task

Successfully extracted 20 book titles and prices from `http://books.toscrape.com/` using all four X-SQL input methods:

| # | Title | Price |
|---|-------|-------|
| 1 | A Light in the Attic | £51.77 |
| 2 | Tipping the Velvet | £53.74 |
| 3 | Soumission | £50.10 |
| ... | *(all 20 books extracted)* | ... |
| 20 | It's Only the Himalayas | £45.17 |

### Execution Context

| Step | Command | Result |
|------|---------|--------|
| 0 | Verified CWD was repo root, then `cli/browser4-cli` | Already in `cli/browser4-cli` |
| 1 | `cargo run -- help` | Help output displayed; learned command structure |
| 1a | Read `skills/browser4-cli/SKILL.md`, `skills/browser4-cli/references/x-sql.md`, `skills/browser4-cli/references/domsnapshot.md` | Learned workflows, X-SQL syntax, input methods |
| 2 | `cargo run -- goto "http://books.toscrape.com/"` | Page loaded (Session: DEFAULT) |
| 3 | `cargo run -- domsnapshot` | Captured: 50KB, 20 images, 94 links, 100 interactive elements |
| 4 | `cargo run -- domsnapshot inspect` (root) | Only 1 match (`:root`), too broad |
| 4a | `cargo run -- domsnapshot inspect ".product_pod" --max 5 --depth 5` | Found 20 matches, discovered: `p.price_color` (100%), `h3 a` (title), `articl...

(truncated — see full.md for complete trace)

---

## Issues Found (6 issues)

### Issue 1: `DOM_FIRST_TEXT` returns truncated titles for elements with CSS text-overflow

**Severity:** Medium
**Category:** UX / Discoverability

#### Reproduction

```bash
cargo run -- domsnapshot get all text "h3 a" --limit 5
# Returns: ["A Light in the ...","Tipping the Velvet","Soumission",...]
# "A Light in the ..." should be "A Light in the Attic"
```
Same truncation occurs in X-SQL with `DOM_FIRST_TEXT(DOM, 'h3 a')`.

#### Expected Behavior

`DOM_FIRST_TEXT` should return the full text content of the DOM element, not the CSS-rendered truncated text.

#### Actual Behavior

Returns CSS `text-overflow: ellipsis` rendered text ("A Light in the ...") instead of the full DOM text.

#### Root Cause Analysis

The DOM snapshot appears to capture computed/rendered text rather than raw DOM text content. The `title` attribute retains the full text, but a first-time user would naturally reach for `DOM_FIRST_TEXT` and get truncated results without understanding why.

#### Code Pointer

`Investigate the DOM text extraction layer that resolves `DOM_FIRST_TEXT` — likely using rendered text rather than `Node.textContent`.`

#### AI Suggested Improvement

- `DOM_FIRST_TEXT` should return the raw DOM `textContent`, not the CSS-rendered/truncated text
- If the current behavior is intentional (e.g., for visual fidelity), add a prominent note in the X-SQL docs warning that `DOM_FIRST_TEXT` may return visually truncated text and recommending `DOM_FIRST_ATTR(DOM, selector, 'title')` as a fallback
- Add a `DOM_FULL_TEXT` or `DOM_RAW_TEXT` function that always returns untruncated text

#### Human Review

---

---

### Issue 2: `domsnapshot inspect` without a selector provides no actionable guidance

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

```bash
cargo run -- domsnapshot inspect --max 5
# Output: 1 match (:root), shows html.no-js > head > body#default
# Tip: "Try narrowing the scope with a more specific CSS selector"
```

#### Expected Behavior

Root-level inspection should either auto-detect repeating container patterns (like `.product_pod`), or at minimum suggest common container class names found on the page.

#### Actual Behavior

Shows only the single `:root` match with no hints about what selectors to try next. The user must guess or already know the container class names.

#### Root Cause Analysis

The `inspect` command requires a CSS selector to scope to repeated elements. Without one, it only inspects `:root` (a single element), which contains no patterns to compare. The algorithm only detects patterns across *multiple matches*, so it cannot help when given a single-element scope.

#### Code Pointer

``cli/browser4-cli/src/` — the inspect command implementation. Consider adding a pre-scan phase.`

#### AI Suggested Improvement

- Without a selector, run a heuristic pre-scan to find candidate repeating containers (elements sharing the same class with ≥3 instances on the page), and suggest them: `"Try: domsnapshot inspect '.product_pod', domsnapshot inspect '.s-result-item'"`
- Add a `--auto` flag that automatically detects the most likely content container and inspects it
- Include a one-line hint in the output: `"Run domsnapshot summary to see page landmarks, then inspect a repeating class"`

#### Human Review

---

---

### Issue 3: `--result-only` flag not discoverable from top-level help or workflow documentation

**Severity:** Low
**Category:** Discoverability

#### Reproduction

```bash
cargo run -- help
# Search for "result-only" — not present in main help output
# Must run: cargo run -- domsnapshot query --help
```

#### Expected Behavior

Common output-formatting flags should be discoverable from the top-level help or mentioned in the X-SQL documentation examples.

#### Actual Behavior

`--result-only` is only documented in the `domsnapshot query --help` subcommand output and briefly in the `domsnapshot.md` reference. The SKILL.md quick patterns and X-SQL documentation examples all show the full JSON wrapper output without mentioning `--result-only`.

#### Root Cause Analysis

The flag is documented at the subcommand level but not surfaced in the primary workflow documentation (SKILL.md) or quick-reference examples. Users extracting data will almost always want clean output; the verbose default creates unnecessary friction.

#### Code Pointer

``skills/browser4-cli/references/domsnapshot.md` and `skills/browser4-cli/references/x-sql.md` — add `--result-only` to examples.`

#### AI Suggested Improvement

- Add `--result-only` to the quick-pattern examples in SKILL.md §6 (Bulk Extraction)
- Add a `--result-only` example to `domsnapshot.md` query section
- Consider making `--result-only` the default for `domsnapshot query` and adding `--verbose` for the full metadata output

#### Human Review

---

---

### Issue 4: SKILL.md installation instructions don't cover source-build (cargo) workflow

**Severity:** Low
**Category:** Documentation

#### Reproduction

Read `skills/browser4-cli/SKILL.md` — installation section only covers `npm install -g browser4-cli` and the Windows PowerShell installer. No mention of building from source with `cargo run`.

#### Expected Behavior

Documentation should cover all supported installation methods, including `cargo build` / `cargo run` for developers working from source.

#### Actual Behavior

Only npm and Windows PowerShell installation methods are documented.

#### Root Cause Analysis

The documentation assumes end-user installation via package managers. Developer/contributor workflows are not addressed.

#### Code Pointer

``skills/browser4-cli/SKILL.md` — Installation section.`

#### AI Suggested Improvement

- Add a "Development" section: `cargo build` / `cargo run -- <command>`
- Document the expected directory structure for source builds
- Note that `cargo run` compiles on first invocation (expected delay)

#### Human Review

---

---

### Issue 5: Inline `--sql` quoting fragility on Windows/Git Bash

**Severity:** Low
**Category:** UX

#### Reproduction

```bash
# The shell rewrites quotes in complex ways:
cargo run -- domsnapshot query --sql "SELECT DOM_FIRST_TEXT(DOM, 'h3 a')"
# Observed in process listing: 'SELECT DOM_FIRST_TEXT(DOM, '\''h3 a'\'') ...'
```

#### Expected Behavior

Quoting should be straightforward and predictable.

#### Actual Behavior

Git Bash applies complex quote escaping that makes the actual command hard to read. The query worked, but the escaping in the process output was intimidating (`'\''` for inner single quotes). On Windows cmd.exe, this would be even worse.

#### Root Cause Analysis

Shell quoting across cargo → clap → CDP backend layers. The documentation already warns about this and recommends file/stdin/base64 — which is the correct mitigation.

#### Code Pointer

`N/A — this is a known limitation with documented workarounds. The docs at `skills/browser4-cli/references/domsnapshot.md` and `skills/browser4-cli/SKILL.md` §5 already warn about this.`

#### AI Suggested Improvement

- The existing documentation warning is adequate — no code change needed
- Consider adding a `--sql-file` alias for `--sql @file` to make the file-based path even more obvious
- Add a `--sql-safe` mode that applies base64 internally so users never need to think about encoding

#### Human Review

---

---

### Issue 6: Interactive elements list in `domsnapshot` output shows no CSS class info for bare `<a>` tags

**Severity:** Low
**Category:** UX

#### Reproduction

```bash
cargo run -- domsnapshot
# Output shows 100 interactive elements, but 51 of them are just "a" with no class/href info
```

#### Expected Behavior

The interactive elements list should show distinguishing attributes (class, href text, aria-label) to help users identify which element is which.

#### Actual Behavior

Most `<a>` tags appear as bare `a` with no distinguishing information, making the list nearly useless for element identification.

#### Root Cause Analysis

The metadata extraction for interactive elements doesn't include key distinguishing attributes like `href`, inner text, or `class` for anchor elements that lack explicit class attributes.

#### Code Pointer

``cli/browser4-cli/src/` — the `domsnapshot` command handler that computes the interactive elements list.`

#### AI Suggested Improvement

- Include `href` value for anchor elements in the interactive elements list
- Show inner text (first 30 chars) for elements without class/id
- Add a `--verbose` flag to `domsnapshot` that shows full attribute details for each interactive element

#### Human Review

---

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: `DOM_FIRST_TEXT` returns truncated titles for elements with CSS text-overflow

```bash
cargo run -- domsnapshot get all text "h3 a" --limit 5
# Returns: ["A Light in the ...","Tipping the Velvet","Soumission",...]
# "A Light in the ..." should be "A Light in the Attic"
```
Same truncation occurs in X-SQL with `DOM_FIRST_TEXT(DOM, 'h3 a')`.

#### Issue 2: `domsnapshot inspect` without a selector provides no actionable guidance

```bash
cargo run -- domsnapshot inspect --max 5
# Output: 1 match (:root), shows html.no-js > head > body#default
# Tip: "Try narrowing the scope with a more specific CSS selector"
```

#### Issue 3: `--result-only` flag not discoverable from top-level help or workflow documentation

```bash
cargo run -- help
# Search for "result-only" — not present in main help output
# Must run: cargo run -- domsnapshot query --help
```

#### Issue 4: SKILL.md installation instructions don't cover source-build (cargo) workflow

Read `skills/browser4-cli/SKILL.md` — installation section only covers `npm install -g browser4-cli` and the Windows PowerShell installer. No mention of building from source with `cargo run`.

#### Issue 5: Inline `--sql` quoting fragility on Windows/Git Bash

```bash
# The shell rewrites quotes in complex ways:
cargo run -- domsnapshot query --sql "SELECT DOM_FIRST_TEXT(DOM, 'h3 a')"
# Observed in process listing: 'SELECT DOM_FIRST_TEXT(DOM, '\''h3 a'\'') ...'
```

#### Issue 6: Interactive elements list in `domsnapshot` output shows no CSS class info for bare `<a>` tags

```bash
cargo run -- domsnapshot
# Output shows 100 interactive elements, but 51 of them are just "a" with no class/href info
```

