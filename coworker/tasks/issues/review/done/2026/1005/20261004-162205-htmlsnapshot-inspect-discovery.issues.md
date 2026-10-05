# Issues: htmlsnapshot-inspect-discovery

> **Source:** `20261004-162205-htmlsnapshot-inspect-discovery.full.md` | **Date:** 20261004-162205 | **Mode:** dev

## Scenario Background

### Task

All nine task steps completed against `https://books.toscrape.com/` (the `http://` URL 301-redirects to HTTPS, which the CLI reported).

**Book titles and prices (step 8, X-SQL over `article.product_pod`, 20 rows):**

| title | price |
|---|---|
| A Light in the Attic | £51.77 |
| Tipping the Velvet | £53.74 |
| Soumission | £50.10 |
| Sharp Objects | £47.82 |
| Sapiens: A Brief History of Humankind | £54.23 |
| The Requiem Red | £22.65 |
| The Dirty Little Secrets of Getting Your Dream Job | £33.34 |
| The Coming Woman: A Novel Based on the Life of the Infamous Feminist, Victoria Woodhull | £17.93 |
| The Boys in the Boat: Nine Americans and Their Epic Quest for Gold at the 1936 Berlin Olympics | £22.60 |
| The Black Maria | £52.15 |
| Starving Hearts (Triangular Trade Trilogy, #1) | £13.99 |
| Shakespeare's Sonnets | £20.66 |
| Set Me Free | £17.46 |
| Scott Pilgrim's Precious Little Life (Scott Pilgrim #1) | £52.29 |
| Rip it Up and Start Again | £35.02 |
| Our Band Could Be Your Life: Scenes from the American Indie Underground, 1981-1991 | £57.25 |
| Olio | £23.88 |
| Mesaerion: The Best Science Fiction Stories 1800-1849 | £37.59 |
| Libertarianism for Beginners | £51.33 |
| It's Only the Himalayas | £45.17 |

Key structural findings: the listing is `ol.row > li > article.product_pod` (20 cards, 4-column grid); the price is `p.price_color` (".price_color" is unique to the listing); full book titles are **only** in the `h3 a[title]` attribute — the visible anchor text is truncated in the site's own markup, so `text` (and any `textcontent`) can never return it.

### Execution Context

| # | Command | Result |
|---|---|---|
| 1 | `./b4w.ps1 goto "http://books.toscrape.com/"` | OK — auto-opened session, redirected to HTTPS |
| 2 | `./b4w.ps1 htmlsnapshot` | OK — 62 KB, 20 images, 94 links, 100 interactive elements |
| 3 | `./b4w.ps1 htmlsnapshot inspect` | OK — auto-discovered `.product_pod` (20 matches) instead of page landmarks |
| 4 | `./b4w.ps1 htmlsnapshot inspect ".product_pod" --max 5 --depth 3` | OK — 20 matches / 5 analyzed; sample tree is only 1 level deep |
| 5 | `./b4w.ps1 htmlsnapshot summary` | OK — 23 landmarks, 4 link groups, 3 lists, stats (516 nodes) |
| 6 | `./b4w.ps1 htmlsnapshot get all text "h3:expr(a>0)" --limit 20` | OK but **truncated** titles (`"A Light in the ..."`) |
| 7 | `./b4w.ps1 htmlsnapshot grep --selector "ol.row" -c "<article"` | OK — `...

(truncated — see full.md for complete trace)

---

## Issues Found (8 issues)

### Issue 1: textcontent field documented for htmlsnapshot get but rejected by the CLI

**Severity:** High
**Category:** Documentation

#### Reproduction

./b4w.ps1 htmlsnapshot get all textcontent "h3 a"
Also documented in: ./b4w.ps1 help htmlsnapshot (notes), skills/browser4-cli/SKILL.md section 4b, skills/browser4-cli/references/htmlsnapshot.md (field table).

#### Expected Behavior

Returns the full text content of the matched elements, as documented in three places, including the CLI's own help: 'The get subcommand supports four fields: text (inner text, may be clipped by CSS overflow), textcontent (full text content), html (inner HTML), and attr (attribute value).'

#### Actual Behavior

Error: Unknown field 'textcontent'. Use text, html, or attr.  (exit code 1). The top-level `get textcontent "h3 a"` command DOES work, so the same field name behaves differently in the two get families. This is worst exactly when it is recommended: the docs tell users to reach for textcontent when `text` looks truncated, and that is precisely the moment it fails.

#### Root Cause Analysis

The CLI validates the htmlsnapshot `get` field against a hard-coded allow-list of three values while the help metadata, the ArgDef descriptions, and the skill reference all advertise a fourth (textcontent). Either the backend/CLI supports 'textcontent' and the allow-list was never updated, or the docs were written ahead of the implementation. The error message repeats the same stale three-value list.

#### Code Pointer

`cli/browser4-cli/src/main.rs:7585 (handle_html_snapshot_get — allow-list `!["text","html","attr"].contains(&field)` and the error at :7587); help text at cli/browser4-cli/src/help.rs:1902 and cli/browser4-cli/src/commands.rs:3309,3335`

#### AI Suggested Improvement

- Add 'textcontent' to the field allow-list in handle_html_snapshot_get and map it to the full (non-clipped) text extraction path used by the top-level `get textcontent`.
- Update the error message at main.rs:7587 to name all supported fields.
- If the backend cannot supply unclipped text, remove 'textcontent' from help.rs:1902, commands.rs:3309/3335 and the SKILL.md/htmlsnapshot.md tables instead of advertising a field that errors.
- Add a CLI test asserting every field named in the help text is accepted by the parser (help.rs already has string assertions but nothing exercises the parser).
- Note in the docs that truncation is not always CSS clipping: sites like books.toscrape.com truncate the anchor text in the source markup and keep the full value in a title attribute, where no text field can recover it — suggest `get all attr <sel> title`.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: inspect 'Try these next' suggestions silently return an empty result in PowerShell

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 htmlsnapshot inspect ".product_pod"
# then paste the emitted line into PowerShell (the documented primary Windows shell):
pwsh -NoProfile -Command '& ./b4w.ps1 htmlsnapshot get all text "[data-loading-text="Adding..."]" --limit 3'

#### Expected Behavior

The copy-paste-ready suggestion returns the 20 'Add to basket' texts it was generated from.

#### Actual Behavior

PowerShell splits the selector on the embedded double quotes; the CLI receives the truncated selector `[data-loading-text=` and prints `[]` plus 'No elements matched "[data-loading-text=".' with exit code 0. The failure is silent — no error, no nonzero exit — so a script or agent consuming the result sees a valid empty answer instead of the expected 20 values. The same class-name selector happens to survive Git Bash by accidental quote concatenation, so the bug is shell-dependent and intermittent-looking.

#### Root Cause Analysis

The suggestion generator interpolates the discovered selector verbatim into an escaped double-quoted shell argument without any shell-safe quoting. Selectors containing double quotes (common for attribute values with spaces, e.g. [data-loading-text="Adding..."]) are the trigger. The same interpolation pattern is used one line below for the --sql suggestion, which embeds the selector inside an already double-quoted SQL string and is even more fragile.

#### Code Pointer

`cli/browser4-cli/src/main.rs:9174 (`cli_println!("     htmlsnapshot get all text \"{}\" --limit 20", sel)`) and cli/browser4-cli/src/main.rs:9180 (SQL variant)`

#### AI Suggested Improvement

- Emit the selector with a quoting strategy that survives both PowerShell and POSIX shells, or
- Skip selectors containing characters that cannot be safely displayed inline and instead point at the established escape hatch `--selector-base64` / `--stdin` (inspect already supports these; htmlsnapshot get all does not — adding them there would be a robust general fix).
- Alternatively render the suggestion with single quotes for bash users and print a PowerShell-safe variant when the CLI detects Windows.
- Add a regression test that feeds a discovered selector containing double quotes through the suggestion formatter and asserts it round-trips as a single argument.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: inspect --max default contradicts the reference documentation (10 vs 20)

**Severity:** Medium
**Category:** Documentation

#### Reproduction

./b4w.ps1 help htmlsnapshot inspect   # reports: --max <n>  Max matching elements to analyze (default: 20)
./b4w.ps1 htmlsnapshot inspect ".product_pod"   # prints: (20 matches, 20 analyzed)
skills/browser4-cli/references/htmlsnapshot.md (inspect parameter table)  # reports default 10

#### Expected Behavior

One consistent default. The SKILL.md/htmlsnapshot.md parameter table states `--max N | 10 | Max matching elements to analyze.`

#### Actual Behavior

The CLI help and observed behaviour use 20 (a 20-card page is analyzed in full with no flag). The reference table says 10. A user budgeting cost or reproducing a documented walkthrough gets a different analysis sample than the docs promise.

#### Root Cause Analysis

The default was changed in code (or the docs were written against an older default) and the reference file was not updated. The value now appears in at least two doc surfaces with different numbers.

#### Code Pointer

`skills/browser4-cli/references/htmlsnapshot.md (inspect parameter table); defaults defined in the inspect command parsing in cli/browser4-cli/src/main.rs`

#### AI Suggested Improvement

- Update the htmlsnapshot.md parameter table to 20, or change the code default to 10 — pick one and make the help text, the reference table and the SKILL.md summary agree.
- Add the default to the SKILL.md command map entry so only one source needs editing.
- Consider asserting documented defaults in a docs-lint test.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: help htmlsnapshot contradicts itself on whether a prior capture is required

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 help htmlsnapshot

#### Expected Behavior

A single, consistent statement of the preconditions for read commands.

#### Actual Behavior

The same help page says both 'Capturing is optional: every read subcommand serves the LIVE page.' and, in the Notes section, 'For live page queries (AXTree-based), use `get text <ref>`. For CSS extraction, capture with `htmlsnapshot` first.' The second note is stale and steers users into an unnecessary capture round-trip. Verified false: in a fresh session (`-s nocap-eval goto ...`) with no htmlsnapshot capture at all, `htmlsnapshot get all`, `get all attr` and `inspect` all returned correct results.

#### Root Cause Analysis

The notes block predates the switch of all read commands to the live DOM and was not revised when capture became optional; it also muddles htmlsnapshot get (CSS/live) with the top-level get (AXTree/refs).

#### Code Pointer

`cli/browser4-cli/src/help.rs (htmlsnapshot notes block, the line beginning 'Unlike top-level `get` (accessibility tree), `htmlsnapshot get` uses CSS selectors on stored HTML...')`

#### AI Suggested Improvement

- Delete or rewrite the stale 'capture with htmlsnapshot first' note to match the live-DOM behaviour.
- Keep the one-line distinction between top-level `get` (refs) and `htmlsnapshot get` (CSS) without reintroducing the capture claim.
- Grep the help and skill files for other 'capture first' / 'stored HTML' phrasing and align it.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: --depth does not change the 'Sample structure' tree, only the selector suggestions

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 htmlsnapshot inspect ".product_pod" --max 5 --depth 1 > d1.txt
./b4w.ps1 htmlsnapshot inspect ".product_pod" --max 5 --depth 3 > d3.txt
diff d1.txt d3.txt

#### Expected Behavior

Per the task flow and the natural reading of the parameter name and the 'Sample structure ... Indented lines are child elements found inside it' block, --depth 3 should reveal nested structure (e.g. p.price_color and p.instock.availability inside div.product_price).

#### Actual Behavior

The 'Sample structure' sections are byte-identical for --depth 1 and --depth 3; the sample tree is always one level deep. Only the 'Suggested selectors' list grows with depth (i.star-rating/p.instock.availability appear at depth 3). The reference does document --depth as 'Max descendant depth for selector suggestions', so this is a clarity problem rather than a defect — but it costs a user a round-trip, because nothing in the output says the tree will not deepen.

#### Root Cause Analysis

Two independent renderers: the sample tree is capped at the root's direct children, while --depth is only consulted when computing relative descendant selectors. Documentation covers the second and not the first.

#### Code Pointer

`cli/browser4-cli/src/main.rs (inspect sample-structure renderer vs the selector-suggestion walker); docs at skills/browser4-cli/references/htmlsnapshot.md (inspect parameter table)`

#### AI Suggested Improvement

- Either honour --depth in the printed sample tree, or state in the output header that the sample shows direct children only ('Sample structure (root children; --depth affects selector suggestions)').
- Repeat the 'affects selector suggestions only' wording in `help htmlsnapshot inspect` (currently the option description says 'Max descendant depth for selector suggestions' but the Notes repeat it without the sample-tree caveat).
- Consider renaming to --suggest-depth for symmetry with the documented effect.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: inspect without a selector returns one repeating pattern, not the page's top-level landmarks

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 htmlsnapshot inspect

#### Expected Behavior

The natural first step on an unfamiliar page ('inspect the page structure') is a view of top-level landmarks — header, sidebar/nav, main, footer — so the user can orient before drilling in.

#### Actual Behavior

Auto-discovery immediately jumps to the single most prominent repeating pattern ('### Inspect: ".product_pod" (20 matches, 20 analyzed)') and never shows the header/aside/section landmarks. Landmarks are only available from the separate `htmlsnapshot summary` command (which does surface 'header .header container-fluid', 'aside .sidebar col-sm-4 col-md-3', etc.). The inspect output's footer tip does point at summary, but frames it as a fallback for when auto-discovery 'picks up navigation elements' — not as the landmarks view.

#### Root Cause Analysis

Bare inspect always runs the single-match auto-discovery path; there is no 'landmarks only' or 'no auto-discovery' mode, and the tip does not describe summary as the structure/landmarks view.

#### Code Pointer

`cli/browser4-cli/src/main.rs (inspect auto-discovery branch and the closing 'Tip: htmlsnapshot summary uses visual clustering' block at ~:9191); skills/browser4-cli/references/htmlsnapshot.md (inspect Tips section)`

#### AI Suggested Improvement

- Have bare `inspect` print a short landmark outline (top-level header/nav/main/aside/footer with match counts) before the auto-discovery result, or offer `--landmarks`.
- Reword the footer tip to state plainly: 'For page landmarks and structure use htmlsnapshot summary.'
- Document in the inspect reference that bare inspect answers "what repeats?", not "what is on this page?".

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: No direct element-count validation; grep --selector -c counts HTML lines, so the advertised check is coincidental

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 htmlsnapshot grep --selector "ol.row" -c "<article"     # prints 20
./b4w.ps1 htmlsnapshot grep --selector "ol.row" -c "article.product_pod"   # different number again

#### Expected Behavior

A documented way to confirm 'this selector matches exactly N elements' before trusting it in an extraction.

#### Actual Behavior

grep -c counts matching lines in the exported HTML, not matched elements. It returns 20 here only because the snapshot is pretty-printed one card per line; against minified or single-line HTML the same check would return 1, and the pattern choice ('<article' vs 'article.product_pod') silently changes the count. The reliable count (length of the `get all` JSON array) is not documented as a validation technique, and the no-match hint actively suggests the fragile path ('Verify the selector with `htmlsnapshot grep ".does-not-exist"`' — which also passes a CSS selector where a regex is expected, with '.' as a wildcard).

#### Root Cause Analysis

grep is line-oriented by design and cannot count elements; no dedicated count affordance exists, so users are left to infer counts from outerHTML text. The remediation hint conflates CSS-selector syntax with the Rust regex dialect.

#### Code Pointer

`cli/browser4-cli/src/main.rs (no-match diagnostic hint text near the htmlsnapshot get handler); skills/browser4-cli/references/htmlsnapshot.md (grep section)`

#### AI Suggested Improvement

- Add an explicit element count to `htmlsnapshot get all` output (or a `--count` flag) so N can be verified without parsing JSON arrays.
- Reword the no-match hint to 'Confirm the selector returns elements with `htmlsnapshot get all text "<selector>"`', keeping CSS-selector and regex guidance separate.
- In the grep docs, call out that -c counts lines and must not be used as an element count.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: inspect's suggested extraction commands surface truncated values with no warning and no attribute-based alternative

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 htmlsnapshot inspect ".product_pod"
# follow the top suggestion:
./b4w.ps1 htmlsnapshot get all text "h3:expr(a>0)" --limit 20

#### Expected Behavior

Following the tool's own top-ranked suggestion for the title element yields usable book titles.

#### Actual Behavior

Returns 20 values of the form "A Light in the ..." — the visible anchor text is truncated in the page's own markup while the full title sits in the h3 a[title] attribute. The inspect sample line already shows the truncated value (→ "A Light in the..."), and the 'Try these next' block only suggests text/attr for img[src] and a[href], never for title attributes, so a first-time user has no in-band signal that the extraction is lossy or how to fix it. The documented remedy (textcontent) does not exist (see issue 1).

#### Root Cause Analysis

The suggestion generator is selector-driven, not value-quality-driven: it always emits `get all text` regardless of whether the sample values it just printed look truncated. No generic hint points at attribute-valued alternatives when text samples end in an ellipsis.

#### Code Pointer

`cli/browser4-cli/src/main.rs:9170-9181 (the 'Try these next' block, which prints `get all text` for every actionable selector)`

#### AI Suggested Improvement

- When a sample value shown in the inspect output ends with an ellipsis (or is materially shorter than a sibling attribute value), append a hint such as `htmlsnapshot get all attr "h3 a" title` for that selector.
- Add a generic line to the 'Try these next' block demonstrating attribute extraction for non-image elements (title/aria-label/alt) rather than only img[src] and a[href].
- After fixing issue 1, advertise textcontent as the first remedy when the truncation is genuinely CSS-driven.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all nine task steps were completed and the requested data (20 book titles with prices, plus selector structure for the listing and sidebar) was extracted correctly from the live page.

**Success Rate:** 100% of steps produced usable output; step 6 required a workaround (truncated `text` values replaced by an attribute read) and step 4's stated expectation (nested structure from --depth 3) was not met by the tool.

**Issues Found:** 8

**Major Blockers:** None. Every command succeeded; no retries or server restarts were needed. The only functional dead end was the documented `textcontent` field, which errors out and blocked the recommended remedy for truncated text.

**Most Confusing Aspects:** The bare `inspect` command is described as showing page landmarks but actually returns a single auto-discovered repeating pattern. `--depth` looks like it should deepen the printed sample tree and does nothing of the sort. The help page for htmlsnapshot simultaneously says capture is optional and that you should capture first. The `textcontent` field exists in the docs, in the CLI's own help, and in the sibling top-level `get` command, yet is rejected by `htmlsnapshot get`. And the tool's own copy-paste suggestions are not reliably copy-pasteable on Windows.

**Most Valuable Improvements:** Implement (or stop advertising) `textcontent` in htmlsnapshot get; make the generated 'Try these next' commands shell-safe or route them through --selector-base64/--stdin; make the three documented values (--max default, capture-optional wording, --depth effect) consistent across help, SKILL.md and the reference tables; add a first-class element-count affordance so selector validation does not depend on grep line counting.

**Usability Rating:** 7/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` (PowerShell) or `./b4w.sh` (Bash / Git Bash), which auto-build from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root:

   - **PowerShell:** `./b4w.ps1 <command>`
   - **Bash / Git Bash:** `./b4w.sh <command>`
   - **Direct:** `browser4-cli <command>` (if installed globally)

   > **Note:** `$(./b4w.ps1)` is command substitution in bash — do NOT use it.

### Per-Issue Reproduction Steps

#### Issue 1: textcontent field documented for htmlsnapshot get but rejected by the CLI

./b4w.ps1 htmlsnapshot get all textcontent "h3 a"
Also documented in: ./b4w.ps1 help htmlsnapshot (notes), skills/browser4-cli/SKILL.md section 4b, skills/browser4-cli/references/htmlsnapshot.md (field table).

#### Issue 2: inspect 'Try these next' suggestions silently return an empty result in PowerShell

./b4w.ps1 htmlsnapshot inspect ".product_pod"
# then paste the emitted line into PowerShell (the documented primary Windows shell):
pwsh -NoProfile -Command '& ./b4w.ps1 htmlsnapshot get all text "[data-loading-text="Adding..."]" --limit 3'

#### Issue 3: inspect --max default contradicts the reference documentation (10 vs 20)

./b4w.ps1 help htmlsnapshot inspect   # reports: --max <n>  Max matching elements to analyze (default: 20)
./b4w.ps1 htmlsnapshot inspect ".product_pod"   # prints: (20 matches, 20 analyzed)
skills/browser4-cli/references/htmlsnapshot.md (inspect parameter table)  # reports default 10

#### Issue 4: help htmlsnapshot contradicts itself on whether a prior capture is required

./b4w.ps1 help htmlsnapshot

#### Issue 5: --depth does not change the 'Sample structure' tree, only the selector suggestions

./b4w.ps1 htmlsnapshot inspect ".product_pod" --max 5 --depth 1 > d1.txt
./b4w.ps1 htmlsnapshot inspect ".product_pod" --max 5 --depth 3 > d3.txt
diff d1.txt d3.txt

#### Issue 6: inspect without a selector returns one repeating pattern, not the page's top-level landmarks

./b4w.ps1 htmlsnapshot inspect

#### Issue 7: No direct element-count validation; grep --selector -c counts HTML lines, so the advertised check is coincidental

./b4w.ps1 htmlsnapshot grep --selector "ol.row" -c "<article"     # prints 20
./b4w.ps1 htmlsnapshot grep --selector "ol.row" -c "article.product_pod"   # different number again

#### Issue 8: inspect's suggested extraction commands surface truncated values with no warning and no attribute-based alternative

./b4w.ps1 htmlsnapshot inspect ".product_pod"
# follow the top suggestion:
./b4w.ps1 htmlsnapshot get all text "h3:expr(a>0)" --limit 20

