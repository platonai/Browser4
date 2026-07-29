# Issues: html-snapshot-extraction

> **Source:** `20260728-153713-html-snapshot-extraction.full.md` | **Date:** 20260728-153713 | **Mode:** dev

## Scenario Background

### Task

All 10 steps completed. Here's the data extracted:

| Step | Task | Command | Result |
|------|------|---------|--------|
| 1 | Navigate | `goto "http://books.toscrape.com/"` | Navigated to `https://books.toscrape.com/` |
| 2 | Capture HTML snapshot | `htmlsnapshot` | Captured 64 KB snapshot, 516 nodes, 94 links, 20 images |
| 3 | First book title text | `htmlsnapshot get text "article.product_pod h3 a"` | `"A Light in the ..."` (truncated by site CSS) |
| — | Full title (recovery) | `htmlsnapshot get attr "article.product_pod h3 a" title` | `"A Light in the Attic"` |
| 4 | First product HTML | `htmlsnapshot get html "article.product_pod"` | Full `<article>` container HTML with image, rating, title, price, and button |
| 5 | First book link href | `htmlsnapshot get attr "article.product_pod h3 a" href` | `catalogue/a-light-in-the-attic_1000/index.html` |
| 6 | All 20 book titles | `htmlsnapshot get all text "article.product_pod h3 a"` | JSON array of 20 titles (long ones truncated with `...`) |
| 7 | Titles 6–10 | `htmlsnapshot get all text "article.product_pod h3 a" --offset 5 --limit 5` | `["The Requiem Red", "The Dirty Little Secrets ...", "The Coming Woman: A ...", "The Boys in the ...", "The Black Maria"]` |
| 8 | Export HTML | `htmlsnapshot export --file .test-sessions/books-toscrape-snapshot.html` | 45 KB, 475 lines exported |
| 9 | Page summary | `htmlsnapshot summary` | WPSI generated: 4 link groups, 23 landmarks, 20 content nodes, 3 lists detected |
| 10 | Grep for "price" | `htmlsnapshot grep -c "price"` | 40 matching lines (20 `product_price` + 20 `price_color`) |

**Step 8 workaround needed:** The first attempt with a positional path argument failed with `"too many arguments: expected 0, received 1"`. The correct syntax uses `--file <path>` (not a positional argument), discovered via `htmlsnapshot export --help`.

---

### Execution Context

**Key Commands:**

1. `./b4w.ps1 help` — learned available commands
2. `./b4w.ps1 goto "http://books.toscrape.com/"` — navigated to page (auto-started daemon + backend)
3. `./b4w.ps1 htmlsnapshot` — captured HTML snapshot for querying
4. `./b4w.ps1 htmlsnapshot inspect` — discovered CSS selectors (`article.product_pod`, `p.price_color`, etc.)
5. `./b4w.ps1 htmlsnapshot get text "article.product_pod h3 a"` — first title (truncated)
6. `./b4w.ps1 htmlsnapshot get attr "article.product_pod h3 a" title` — full title from attribute
7. `./b4w.ps1 htmlsnapshot get html "article.product_pod"` — first product container HTML
8. `./b4w.ps1 htmlsnapshot get attr "article.product_pod h3 a" href` — first book link href
9. `./b4w.ps1 htmlsnapshot get all text "article.product_pod h3 a"` — all 20 titles
10. `./b4w.ps1 htmlsnapshot get all text "article.product_pod h3 a" --offset 5 --limit 5` — paginated titles 6–10
11. `./b4w.ps1 htmlsnapshot export` (failed) → `./b4w.ps1 htmlsnapshot export --help` → `./b4w.ps1 htmlsnapshot export --file .test-sessions/books-toscrape-snapshot.html`
12. `./b4w.ps1 htmlsnapshot summary` — page structure summary
13. `./b4w.ps1 htmlsnapshot grep "price"` + `./b4w.ps1 htmlsnapshot grep -c "price"` — search and count

**Important decisions:**
- Used `htmlsnapshot inspect` to discover selectors rather than guessing — the tool auto-discovered `article.product_pod` as the repeating pattern and suggested `h3:expr(a>0)` for titles
- For the full book title, switched from `get text` (which returns CSS-truncated text) to `get attr ... title` after noticing the ellipsis
- Used `--file` flag for export after the positional-argument error, discovered via `--help`

**Workarounds required:**
- Had to use `get attr ... title` instead of `get text` for full book titles (site uses text-overflow ellipsis)
- Had to discover `--file` flag for export via `--help` after the initial error message didn't hint at it
- Copied exported file from default path to `.test-sessions/` for the deliverable

---

```json
{
  "issues": [
    {
      "title": "htmlsnapshot export rejects positional path argument with unhelpful error",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "./b4w.ps1 htmlsnapshot export .test-sessions/output.html",
      "expected": "The file should be saved to the specified path, or the error should suggest using --file <path>.",
      "actual": "Error: error: too many arguments: expected 0, received 1",
      "rootCause": "The export command uses --file as a named flag rather than accepting a positional argument. The error message comes from the CLI argument parser and does not include a hint about the correct syntax.",
      "codePointer": "cli/browser4-cli/src/ — the argument parser that validates positional argument count for the export subcommand",
      "suggestion": "- Improve the error message to suggest the correct syntax: \"Did you mean `--file <path>`?\" or \"Use --file to specify the output path.\"\n- Consider accepting an optional positional argument as a shortcut for --file, since most CLI tools (git, curl, etc.) accept output paths as positional arguments"
    },
    {
      "title": "get text returns CSS-truncated text with ellipsis — full text requires knowing about title attribute",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "./b4w.ps1 htmlsnapshot get text \"article.product_pod h3 a\" on books.toscrape.com",
      "expected": "The full book title \"A Light in the Attic\" should be returned, or at least a hint about the title attribute.",
      "actual": "Returns \"A Light in the ...\" — the CSS text-overflow:ellipsis truncated rendering.",
      "rootCause": "The page HTML itself contains the truncated text (the server renders truncated text for visual layout). The full title is in the <a> tag's title attribute. browser4-cli correctly returns the DOM text content — the issue is that users may not know the full text is only in the title attribute. The inspect output also shows truncated titles, providing no hint.",
      "codePointer": "",
      "suggestion": "- In htmlsnapshot inspect output, when a text node ends with '...' and the element has a title attribute with longer content, add a note or show the full title\n- Consider adding a note to the get text output when the returned text ends with ellipsis and a title attribute is available\n- Document this pitfall in the htmlsnapshot reference docs with a clear example of text-overflow:ellipsis scenarios"
    },
    {
      "title": "Pound sterling (£) rendered as garbled characters in grep output",
      "severity": "Low",
      "category": "Reliability",
      "reproduction": "Go to books.toscrape.com, capture htmlsnapshot, run `htmlsnapshot grep price`",
      "expected": "£51.77, £53.74, etc.",
      "actual": "Â£51.77, Â£53.74 — the UTF-8 £ character (0xC2 0xA3) is being interpreted as two separate characters.",
      "rootCause": "The HTML snapshot storage or grep rendering pipeline is not preserving UTF-8 encoding correctly. The byte sequence for £ (U+00A3) in UTF-8 is 0xC2 0xA3, and these bytes are being output as individual Latin-1 characters (Â and £). This could be a terminal encoding issue or a pipeline encoding issue in how the snapshot is serialized/deserialized.",
      "codePointer": "",
      "suggestion": "- Ensure the HTML snapshot is stored and read back with UTF-8 encoding throughout the pipeline\n- Verify that the grep output path uses a UTF-8 aware writer\n- Add a UTF-8 encoding smoke test for non-ASCII currency symbols (£, €, ¥) in htmlsnapshot output"
    },
    {
      "title": "htmlsnapshot grep lacks --only-matching (-o) flag for counting individual occurrences",
      "severity": "Low",
      "category": "Product",
      "reproduction": "Attempt to count individual word occurrences: check `htmlsnapshot grep --help` for -o flag.",
      "expected": "An -o/--only-matching flag to print only the matched portions, enabling `htmlsnapshot grep -o price | wc -l` to count individual occurrences.",
      "actual": "No -o flag exists. The -c flag counts matching lines, not individual occurrences. To count actual word occurrences, users must export HTML and use external tools.",
      "rootCause": "The grep implementation supports a subset of standard grep flags. -o (only-matching) was not included in the feature set.",
      "codePointer": "cli/browser4-cli/src/ — htmlsnapshot grep implementation; the flag parsing and matching logic",
      "suggestion": "- Add -o/--only-matching flag that prints each match on its own line, like GNU grep\n- This enables `htmlsnapshot grep -o price | wc -l` for true occurrence counting\n- Consider adding --count-matches for total match count across all lines (like grep -o ... | wc -l)"
    },
    {
      "title": "No one-command workflow for static page extraction — requires learning snapshot vs htmlsnapshot distinction",
      "severity": "Low",
      "category": "Discoverability",
      "reproduction": "As a new user, read the help output and try to figure out how to extract data from a page.",
      "expected": "Clear guidance that accessibility snapshots (snapshot) give refs for interaction, while htmlsnapshot gives CSS-selector-based extraction.",
      "actual": "The help output lists both `snapshot` and `htmlsnapshot` commands but does not clearly distinguish when to use each. The user must read the full SKILL.md to understand the difference. The `goto` tip says 'Try htmlsnapshot get text \"h1\"' which helps, but the conceptual distinction between the two snapshot types is not immediately obvious.",
      "rootCause": "The CLI has two snapshot systems: accessibility-tree snapshots (for interaction via refs) and HTML snapshots (for data extraction via CSS selectors). The help output doesn't explain this distinction, and `snapshot` doesn't link to `htmlsnapshot` in its description.",
      "codePointer": "cli/browser4-cli/src/ — help text generation for the snapshot and htmlsnapshot commands",
      "suggestion": "- Add a brief explanation in the snapshot help: \"For data extraction with CSS selectors, use htmlsnapshot instead\"\n- Add a one-line note in htmlsnapshot help: \"For interactive element targeting with refs, use snapshot\"\n- Consider renaming: 'snapshot' → 'ax-snapshot' and 'htmlsnapshot' → 'snapshot' (would be clearer but is a breaking change)"
    },
    {
      "title": "build-from-source step compiles on every command invocation",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run any two ./b4w.ps1 commands back-to-back.",
      "expected": "The second command should reuse the previously compiled binary.",
      "actual": "Each command prints 'Finished dev profile [unoptimized + debuginfo] target(s) in 0.4Xs' — indicating a rebuild step even when nothing changed. The time is small (~0.4s) but adds up across many commands.",
      "rootCause": "The b4w.ps1 wrapper runs `cargo run` which checks dependencies and recompiles if needed. Even when nothing changed, cargo performs a dependency check that takes ~400ms. This is inherent to `cargo run` behavior.",
      "codePointer": "b4w.ps1 — the PowerShell wrapper script that invokes cargo run",
      "suggestion": "- Document in SKILL.md that the ~0.4s overhead per command is cargo's dependency check, not a real rebuild\n- Consider adding a `--release` or pre-built mode option for faster iteration during development\n- The cargo check overhead is unavoidable — but users should know it's expected behavior"
    },
    {
      "title": "htmlsnapshot inspect shows truncated titles with no warning about full text in attributes",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Capture a htmlsnapshot of books.toscrape.com, run `htmlsnapshot inspect`.",
      "expected": "The text field should note when a title attribute contains the full text, e.g. 'A Light in the ...' (full: 'A Light in the Attic').",
      "actual": "Element 1 shows text: \"A Light in the ...\" with no indication that the full title exists as a title attribute on the child <a> element.",
      "rootCause": "The inspect output renders the textContent of matched elements, which is naturally truncated by the page's HTML. It doesn't cross-reference with title attributes of child elements.",
      "codePointer": "browser4-rest/ — htmlsnapshot inspect rendering logic",
      "suggestion": "- When a text value ends with '...' (or Unicode ellipsis …), check if child elements have title attributes with longer content\n- Show the full text from title attribute in parentheses: \"A Light in the ...\" (title: \"A Light in the Attic\")\n- Add a tooltip/hint in the output explaining that '...' may indicate text-overflow truncation"
    }
  ],
  "assessment": {
    "completionStatus": "Successful — All 10 task steps completed successfully. One step (export) required a single retry to discover the correct --file flag syntax.",
    "successRate": "90% — 9 of 10 steps worked on first attempt. Step 8 (export) needed one retry after discovering --file via --help.",
    "issuesFound": 7,
    "majorBlockers": "None. No command failures prevented task completion. All issues found were quality-of-life, discoverability, or output-fidelity concerns rather than blockers.",
    "mostConfusingAspects": "1) The distinction between 'snapshot' (accessibility tree) and 'htmlsnapshot' (HTML capture) — two snapshot systems with different purposes and different query commands. 2) The export command rejecting a positional path with a cryptic error, when most CLI tools accept output paths as positional arguments. 3) Book titles appearing truncated in extraction output because the site uses CSS text-overflow:ellipsis — it wasn't obvious that the full text was in the title attribute.",
    "mostValuableImprovements": "1) Better error messages that suggest the correct syntax (e.g., 'try --file <path>' instead of 'too many arguments'). 2) UTF-8 encoding fix for the grep output pipeline (Â£ → £). 3) Hints in inspect/get output when text appears truncated and a title attribute is available. 4) Clarifying the snapshot vs htmlsnapshot distinction in the top-level help.",
    "usabilityRating": 7
  }
}
```

---

---

## Issues Found (7 issues)

### Issue 1: htmlsnapshot export rejects positional path argument with unhelpful error

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 htmlsnapshot export .test-sessions/output.html

#### Expected Behavior

The file should be saved to the specified path, or the error should suggest using --file <path>.

#### Actual Behavior

Error: error: too many arguments: expected 0, received 1

#### Root Cause Analysis

The export command uses --file as a named flag rather than accepting a positional argument. The error message comes from the CLI argument parser and does not include a hint about the correct syntax.

#### Code Pointer

`cli/browser4-cli/src/ — the argument parser that validates positional argument count for the export subcommand`

#### AI Suggested Improvement

- Improve the error message to suggest the correct syntax: "Did you mean `--file <path>`?" or "Use --file to specify the output path."
- Consider accepting an optional positional argument as a shortcut for --file, since most CLI tools (git, curl, etc.) accept output paths as positional arguments

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: get text returns CSS-truncated text with ellipsis — full text requires knowing about title attribute

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 htmlsnapshot get text "article.product_pod h3 a" on books.toscrape.com

#### Expected Behavior

The full book title "A Light in the Attic" should be returned, or at least a hint about the title attribute.

#### Actual Behavior

Returns "A Light in the ..." — the CSS text-overflow:ellipsis truncated rendering.

#### Root Cause Analysis

The page HTML itself contains the truncated text (the server renders truncated text for visual layout). The full title is in the <a> tag's title attribute. browser4-cli correctly returns the DOM text content — the issue is that users may not know the full text is only in the title attribute. The inspect output also shows truncated titles, providing no hint.

#### AI Suggested Improvement

- In htmlsnapshot inspect output, when a text node ends with '...' and the element has a title attribute with longer content, add a note or show the full title
- Consider adding a note to the get text output when the returned text ends with ellipsis and a title attribute is available
- Document this pitfall in the htmlsnapshot reference docs with a clear example of text-overflow:ellipsis scenarios

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: Pound sterling (£) rendered as garbled characters in grep output

**Severity:** Low
**Category:** Reliability

#### Reproduction

Go to books.toscrape.com, capture htmlsnapshot, run `htmlsnapshot grep price`

#### Expected Behavior

£51.77, £53.74, etc.

#### Actual Behavior

Â£51.77, Â£53.74 — the UTF-8 £ character (0xC2 0xA3) is being interpreted as two separate characters.

#### Root Cause Analysis

The HTML snapshot storage or grep rendering pipeline is not preserving UTF-8 encoding correctly. The byte sequence for £ (U+00A3) in UTF-8 is 0xC2 0xA3, and these bytes are being output as individual Latin-1 characters (Â and £). This could be a terminal encoding issue or a pipeline encoding issue in how the snapshot is serialized/deserialized.

#### AI Suggested Improvement

- Ensure the HTML snapshot is stored and read back with UTF-8 encoding throughout the pipeline
- Verify that the grep output path uses a UTF-8 aware writer
- Add a UTF-8 encoding smoke test for non-ASCII currency symbols (£, €, ¥) in htmlsnapshot output

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: htmlsnapshot grep lacks --only-matching (-o) flag for counting individual occurrences

**Severity:** Low
**Category:** Product

#### Reproduction

Attempt to count individual word occurrences: check `htmlsnapshot grep --help` for -o flag.

#### Expected Behavior

An -o/--only-matching flag to print only the matched portions, enabling `htmlsnapshot grep -o price | wc -l` to count individual occurrences.

#### Actual Behavior

No -o flag exists. The -c flag counts matching lines, not individual occurrences. To count actual word occurrences, users must export HTML and use external tools.

#### Root Cause Analysis

The grep implementation supports a subset of standard grep flags. -o (only-matching) was not included in the feature set.

#### Code Pointer

`cli/browser4-cli/src/ — htmlsnapshot grep implementation; the flag parsing and matching logic`

#### AI Suggested Improvement

- Add -o/--only-matching flag that prints each match on its own line, like GNU grep
- This enables `htmlsnapshot grep -o price | wc -l` for true occurrence counting
- Consider adding --count-matches for total match count across all lines (like grep -o ... | wc -l)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: No one-command workflow for static page extraction — requires learning snapshot vs htmlsnapshot distinction

**Severity:** Low
**Category:** Discoverability

#### Reproduction

As a new user, read the help output and try to figure out how to extract data from a page.

#### Expected Behavior

Clear guidance that accessibility snapshots (snapshot) give refs for interaction, while htmlsnapshot gives CSS-selector-based extraction.

#### Actual Behavior

The help output lists both `snapshot` and `htmlsnapshot` commands but does not clearly distinguish when to use each. The user must read the full SKILL.md to understand the difference. The `goto` tip says 'Try htmlsnapshot get text "h1"' which helps, but the conceptual distinction between the two snapshot types is not immediately obvious.

#### Root Cause Analysis

The CLI has two snapshot systems: accessibility-tree snapshots (for interaction via refs) and HTML snapshots (for data extraction via CSS selectors). The help output doesn't explain this distinction, and `snapshot` doesn't link to `htmlsnapshot` in its description.

#### Code Pointer

`cli/browser4-cli/src/ — help text generation for the snapshot and htmlsnapshot commands`

#### AI Suggested Improvement

- Add a brief explanation in the snapshot help: "For data extraction with CSS selectors, use htmlsnapshot instead"
- Add a one-line note in htmlsnapshot help: "For interactive element targeting with refs, use snapshot"
- Consider renaming: 'snapshot' → 'ax-snapshot' and 'htmlsnapshot' → 'snapshot' (would be clearer but is a breaking change)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: build-from-source step compiles on every command invocation

**Severity:** Low
**Category:** UX

#### Reproduction

Run any two ./b4w.ps1 commands back-to-back.

#### Expected Behavior

The second command should reuse the previously compiled binary.

#### Actual Behavior

Each command prints 'Finished dev profile [unoptimized + debuginfo] target(s) in 0.4Xs' — indicating a rebuild step even when nothing changed. The time is small (~0.4s) but adds up across many commands.

#### Root Cause Analysis

The b4w.ps1 wrapper runs `cargo run` which checks dependencies and recompiles if needed. Even when nothing changed, cargo performs a dependency check that takes ~400ms. This is inherent to `cargo run` behavior.

#### Code Pointer

`b4w.ps1 — the PowerShell wrapper script that invokes cargo run`

#### AI Suggested Improvement

- Document in SKILL.md that the ~0.4s overhead per command is cargo's dependency check, not a real rebuild
- Consider adding a `--release` or pre-built mode option for faster iteration during development
- The cargo check overhead is unavoidable — but users should know it's expected behavior

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: htmlsnapshot inspect shows truncated titles with no warning about full text in attributes

**Severity:** Low
**Category:** UX

#### Reproduction

Capture a htmlsnapshot of books.toscrape.com, run `htmlsnapshot inspect`.

#### Expected Behavior

The text field should note when a title attribute contains the full text, e.g. 'A Light in the ...' (full: 'A Light in the Attic').

#### Actual Behavior

Element 1 shows text: "A Light in the ..." with no indication that the full title exists as a title attribute on the child <a> element.

#### Root Cause Analysis

The inspect output renders the textContent of matched elements, which is naturally truncated by the page's HTML. It doesn't cross-reference with title attributes of child elements.

#### Code Pointer

`browser4-rest/ — htmlsnapshot inspect rendering logic`

#### AI Suggested Improvement

- When a text value ends with '...' (or Unicode ellipsis …), check if child elements have title attributes with longer content
- Show the full text from title attribute in parentheses: "A Light in the ..." (title: "A Light in the Attic")
- Add a tooltip/hint in the output explaining that '...' may indicate text-overflow truncation

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — All 10 task steps completed successfully. One step (export) required a single retry to discover the correct --file flag syntax.

**Success Rate:** 90% — 9 of 10 steps worked on first attempt. Step 8 (export) needed one retry after discovering --file via --help.

**Issues Found:** 7

**Major Blockers:** None. No command failures prevented task completion. All issues found were quality-of-life, discoverability, or output-fidelity concerns rather than blockers.

**Most Confusing Aspects:** 1) The distinction between 'snapshot' (accessibility tree) and 'htmlsnapshot' (HTML capture) — two snapshot systems with different purposes and different query commands. 2) The export command rejecting a positional path with a cryptic error, when most CLI tools accept output paths as positional arguments. 3) Book titles appearing truncated in extraction output because the site uses CSS text-overflow:ellipsis — it wasn't obvious that the full text was in the title attribute.

**Most Valuable Improvements:** 1) Better error messages that suggest the correct syntax (e.g., 'try --file <path>' instead of 'too many arguments'). 2) UTF-8 encoding fix for the grep output pipeline (Â£ → £). 3) Hints in inspect/get output when text appears truncated and a title attribute is available. 4) Clarifying the snapshot vs htmlsnapshot distinction in the top-level help.

**Usability Rating:** 7/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: htmlsnapshot export rejects positional path argument with unhelpful error

./b4w.ps1 htmlsnapshot export .test-sessions/output.html

#### Issue 2: get text returns CSS-truncated text with ellipsis — full text requires knowing about title attribute

./b4w.ps1 htmlsnapshot get text "article.product_pod h3 a" on books.toscrape.com

#### Issue 3: Pound sterling (£) rendered as garbled characters in grep output

Go to books.toscrape.com, capture htmlsnapshot, run `htmlsnapshot grep price`

#### Issue 4: htmlsnapshot grep lacks --only-matching (-o) flag for counting individual occurrences

Attempt to count individual word occurrences: check `htmlsnapshot grep --help` for -o flag.

#### Issue 5: No one-command workflow for static page extraction — requires learning snapshot vs htmlsnapshot distinction

As a new user, read the help output and try to figure out how to extract data from a page.

#### Issue 6: build-from-source step compiles on every command invocation

Run any two ./b4w.ps1 commands back-to-back.

#### Issue 7: htmlsnapshot inspect shows truncated titles with no warning about full text in attributes

Capture a htmlsnapshot of books.toscrape.com, run `htmlsnapshot inspect`.

