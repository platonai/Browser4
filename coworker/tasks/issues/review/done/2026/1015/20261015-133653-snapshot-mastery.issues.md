# Issues: snapshot-mastery

> **Source:** `20261015-133653-snapshot-mastery.issues.json` | **Date:** 20261015-133653 | **Mode:** dev

## Scenario Background

### Task

I completed the 10-step snapshot workflow against `https://en.wikipedia.org/wiki/Christopher_Alexander` using `./b4w.ps1` exclusively. **9 of 10 steps succeeded; step 4 (scoped snapshot) is blocked by a genuine product bug.**

**Key outcome:** The documented CSS-scoping flags — `snapshot --selector <css>` and `snapshot grep --selector <css>` — are **completely non-functional**. They fail for every realistic CSS selector (`#id`, `.class`, bare element names), producing either a raw JavaScript `SyntaxError` surfaced as `[INTERNAL]` or a false "matched no element on the page". I traced this to a one-line defect in the backend and identified the exact fix. The same CSS selectors work correctly in every other command (`is visible "#mw-content-text"` → `true`, `get text "#firstHeading"` → `A Pattern Language`), so the failure is isolated to the AX-snapshot path.

**One premise in the task description is wrong:** `-v 0` is *not* a full-page snapshot. On this article it returned 428 lines and explicitly reported `viewportsTotal: 10`, `hiddenBottomHeight: 9571px` — it is a single screen-height chunk. The true full-page capture is `-v all` (612 lines, 499 refs). The help text documents this correctly, but the CLI's own output header actively reinforces the wrong mental model (Issue 3).

**Two steps could only be partially satisfied as literally specified:** step 4 via a workaround (`htmlsnapshot get text "#mw-content-text"`, 34 KB of scoped article text), and step 9's requirement to "grep the auto-diff output" — `snapshot grep` always re-captures the live page and can never see diff markers (verified: 0 matches for `## Added` / `+ button`). I completed it with an external `grep` on the saved capture.

### Execution Context

**Key Commands:**

| Step | Command | Result |
|---|---|---|
| 1 | `./b4w.ps1 goto "https://en.wikipedia.org/wiki/Christopher_Alexander"` | ✅ 10.5 s, auto-created session, page + title correct |
| 2 | `./b4w.ps1 snapshot -v 0 --stdout --all` | ⚠️ Worked, but 428 lines = **1 of 10 viewports**. Also ran `-v all` (612 lines) for the real full page |
| 3 | `./b4w.ps1 snapshot -i --stdout --all` | ✅ 347 lines, whole-page, interactive-only |
| 4 | `./b4w.ps1 snapshot --selector "#mw-content-text"` | ❌ **FAILED** — `[INTERNAL] SyntaxError`. Workaround: `htmlsnapshot get text "#mw-content-text"` |
| 5 | `./b4w.ps1 snapshot -d 3 --stdout --all` | ✅ 26 lines + clear stderr truncation warning |
| 6 | `./b4w.ps1 snapshot -u --stdout --all` | ✅ 130 `/url:` lines (vs 0 without `-u`) |
| 7 | `./b4w.ps1 click e550 --no-snapshot` | ✅ Navigated to *A Pattern Language - Wikipedia* |
| 8 | `./b4w.ps1 snapshot -v 0 --auto-diff --stdout --all` | ✅ 524 lines: full tree + `### Diff` / `## Removed` (151) / `## Added` (43) |
| 9 | `snapshot grep` variants | ✅ `-i`, `-C 3`, `-v`, `-c`, `-F`, `-w` all verified (with controls); ❌ `--selector` same bug; ⚠️ diff-grep impossible |
| 10 | `./b4w.ps1 snapshot -v 0 --stdout` / `--page-size 100` | ✅ Footer + pagination hint on stderr, truncation hint on stdout |

**Decisions and workarounds:**
- Established a *deliberate* baseline (`snapshot -v 0`, which saves a file) and used `click <ref> --no-snapshot` so the auto-diff had a clean pre-click baseline.
- Checked `-F` with a positive control (`-F 'Sara Ishikawa'` → 3) after `-F '[show]'` returned 0, to avoid falsely reporting `-F` as broken.
- Checked `-w` with a control: `-i "structure"` → 20 vs `-i -w "structure"` → 18 — the flag genuinely works; my first test was simply inconclusive.
- Verified `-v` inverted (52 link lines vs 383 non-link) and confirmed the diff-grep limitation by asserting 0 matches for `## Added`.

**Root-cause investigation.** Located the defect in the Kotlin backend, confirmed it by reading three sibling call sites that do it correctly, and verified with a byte-level check that the `-v 0` / footer issues are real.

---

---

## Issues Found (10 issues)

### Issue 1: snapshot --selector and snapshot grep --selector are completely broken (CSS selector interpolated unquoted into JavaScript)

**Severity:** Critical
**Category:** Product

#### Reproduction

cd D:/workspace/Browser4/Browser4-4.14
./b4w.ps1 goto "https://en.wikipedia.org/wiki/Christopher_Alexander"
./b4w.ps1 snapshot --selector "#mw-content-text"
./b4w.ps1 snapshot grep --selector "#mw-content-text" -c "pattern"

Variants tested (all fail):
  --selector "#mw-content-text"      -> SyntaxError (Private field '#mw' ...)
  --selector ".mw-parser-output"     -> SyntaxError (Unexpected token '.')
  --selector "main"                  -> ReferenceError: main is not defined
  --selector "div#mw-content-text"   -> SyntaxError (missing ) after argument list)
  --selector "[id=\"mw-content-text\"]" -> misleading 'matched no element on the page'
Control proving the selectors are valid:
  ./b4w.ps1 eval "JSON.stringify({mw: !!document.querySelector('#mw-content-text'), po: !!document.querySelector('.mw-parser-output'), main: !!document.querySelector('main')})" -> {"mw":true,"po":true,"main":true}

#### Expected Behavior

The accessibility tree scoped to the elements matching the CSS selector (with ancestor path context, as `snapshot --help` documents), and a CSS-scoped search for `snapshot grep --selector`.

#### Actual Behavior

Every realistic CSS selector fails with exit code 1. Representative output:
  Error: [INTERNAL] browser_snapshot failed: SyntaxError: Private field '#mw' must be declared in an enclosing class	>>>!!document.querySelector(#mw-content-text)<<<
  Error: [INTERNAL] browser_snapshot failed: SyntaxError: Unexpected token '.'	>>>!!document.querySelector(.mw-parser-output)<<<
  Error: [INTERNAL] browser_snapshot failed: ReferenceError: main is not defined
  [INVALID_ARGUMENT] browser_snapshot failed: ariaSnapshot: CSS selector '[id="mw-content-text"]' matched no element on the page

The literal JS in the first error message (document.querySelector(#mw-content-text), unquoted) is the smoking gun. The same selectors succeed in every other command (is visible "#mw-content-text" -> true; get text "#firstHeading" -> 'A Pattern Language'; htmlsnapshot get text "#mw-content-text" -> 34 KB of scoped text), so only the AX-snapshot path is affected. The documented step-4 workflow has no working form; quoting the selector (--selector "'#mw-content-text'") also fails because escapeJsSelector escapes the quotes into \'.

#### Root Cause Analysis

The probe that validates the selector builds a JavaScript expression without wrapping the selector in a string literal. BrowserTabToolExecutor.kt:1906 reads:
    "!!document.querySelector(${Browser4WebDriver.escapeJsSelector(selector)})"
But escapeJsSelector is documented (and unit-tested) as escaping content to sit INSIDE a single-quoted literal: its KDoc says 'Escape [selector] for embedding inside a single-quoted JavaScript string literal (e.g. document.querySelector('<selector>'))', and Browser4WebDriverTest asserts assertEquals("#input", escapeJsSelector("#input")) — i.e. it returns the selector unchanged and the caller must supply the quotes. The three other call sites all do so correctly (Browser4WebDriver.kt:473, Browser4WebDriver.kt:2206, BrowserTabToolExecutor.kt:1588 — all of the form document.querySelector('${escapeJsSelector(selector)}')). Only line 1906 omits them, so the raw selector text is parsed as JavaScript: '#id' and '.class' are syntax errors, a bare tag like 'main' is an undefined identifier, and '[id="x"]' silently evaluates a bogus expression. Consequently the selector never even reaches the server-side AX renderer, so the documented scoping feature is entirely unreachable. Because `snapshot grep` shares this code path, `--selector` and `--selector-all` are broken there too.

#### Code Pointer

`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/BrowserTabToolExecutor.kt:1906 (in `callFunctionOn`, the `"ariaSnapshot" ->` branch, line 1884); helper at browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/Browser4WebDriver.kt:245 (`escapeJsSelector`)`

#### AI Suggested Improvement

- Add the missing quotes at line 1906 so it matches its three sibling call sites: "!!document.querySelector('${Browser4WebDriver.escapeJsSelector(selector)}')"
- Add a regression test that uses a selector containing '#' and '.' (the existing scoped-snapshot coverage evidently never exercised one, or this would have failed); prefer an e2e scenario against a real fixture page over a mock
- Verify `snapshot grep --selector` and `--selector-all` in the same test, since they share this probe
- Consider renaming escapeJsSelector to escapeJsStringInSingleQuotes (or having it emit the quotes) so the contract cannot be misread again

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Misleading 'matched no element on the page' error swallows the real JavaScript exception

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 goto "https://en.wikipedia.org/wiki/Christopher_Alexander"
./b4w.ps1 snapshot --selector '[id="mw-content-text"]'
# contrast:
./b4w.ps1 is visible "#mw-content-text"   # -> true

#### Expected Behavior

Either a correct scoped snapshot, or an error that names the real cause (the evaluation threw / the selector was mis-quoted).

#### Actual Behavior

[INVALID_ARGUMENT] browser_snapshot failed: ariaSnapshot: CSS selector '[id="mw-content-text"]' matched no element on the page — while the element demonstrably exists (is visible returns true). The message sends the user hunting for a selector problem that does not exist and hides the actual bug.

#### Root Cause Analysis

At BrowserTabToolExecutor.kt:1905-1910 the evaluation result is coerced with `as? Boolean ?: false`. When the injected JavaScript throws a RUNTIME exception (as '[id="x"]' does: it evaluates to an array, whose stringification is an invalid selector, so querySelector throws a DOMException), evaluateValue does not return a Boolean, so the coercion turns the failure into `false`, and the subsequent require(found) reports a match failure that never happened. Compile-time SyntaxErrors take a different path and surface as [INTERNAL] with the raw JS, which is why the same defect presents as two unrelated-looking errors. This violates the repository's own review gate in AGENTS.md ('No silent failures ... Error messages must surface the real cause to the CLI/user instead of being swallowed').

#### Code Pointer

`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/BrowserTabToolExecutor.kt:1905-1910`

#### AI Suggested Improvement

- Distinguish 'the probe threw' from 'the probe returned false': inspect the evaluation's exceptionDetails and rethrow with the JS error text instead of coercing to false
- Replace `as? Boolean ?: false` with an explicit when(result) that fails loudly on an unexpected result type
- Include the generated JS expression in the error message so a mis-quoted selector is self-evident
- Once the quoting bug above is fixed, add a test asserting that an intentionally invalid selector reports a syntax/selector error, not 'matched no element'

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: Snapshot header claims 'You are viewing viewport 0' even for whole-page captures

**Severity:** Medium
**Category:** Documentation

#### Reproduction

./b4w.ps1 goto "https://en.wikipedia.org/wiki/Christopher_Alexander"
./b4w.ps1 snapshot -i --stdout --all | head -7
./b4w.ps1 snapshot -i --stdout --all | tail -8
./b4w.ps1 snapshot -d 3 --stdout --all | head -7

#### Expected Behavior

A capture with no --viewport flag should be labelled as whole-page, or carry no viewport metadata at all.

#### Actual Behavior

Every capture — including plain `snapshot`, `-i`, and `-d 3`, none of which were given -v — prints:
  # Viewport State
  # - processingViewport: 0
  # - viewportHeight: 1080px
  # - viewportsTotal: 10
  # - hiddenTopHeight: 0px
  # - hiddenBottomHeight: 9571px
and closes with 'This page has 10 viewports ... You are currently viewing viewport 0 (absolute).' Yet the body contains elements at box y=9858, i.e. the whole page. The header is therefore factually wrong and directly caused the incorrect premise in this evaluation task ('full-page snapshot (-v 0)').

#### Root Cause Analysis

The Viewport State header/footer block appears to be emitted with a hardcoded viewport index 0 whenever viewport metadata is attached to the response, rather than reflecting whether a --viewport filter was actually applied. With no -v passed, the AX tree is unfiltered (whole page) while the metadata still describes 'viewport 0'. The hiddenBottomHeight value (9571px) additionally suggests the height was computed against an unfiltered capture, producing metadata that mixes two different scopes.

#### Code Pointer

`Snapshot rendering/reporting path for the 'Viewport State' block — likely in the CLI's snapshot output formatting (cli/browser4-cli/src/) or the backend's ariaSnapshot response builder in browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/BrowserTabToolExecutor.kt (the `"ariaSnapshot" ->` branch)`

#### AI Suggested Improvement

- Omit processingViewport/hiddenTopHeight/hiddenBottomHeight and the 'You are viewing viewport N' footer when the request carried no viewport filter
- When viewport filtering IS active, state it explicitly ('scoped to viewport 0 of 10') and keep the total honest
- Have the default (unfiltered) capture print one line saying the whole page is included, so `-v 0` vs no flag is unambiguous

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: --auto-diff help says 'show only what changed' but the output is the full tree plus a diff appendix, in an inconsistent line format

**Severity:** Medium
**Category:** Documentation

#### Reproduction

./b4w.ps1 goto "https://en.wikipedia.org/wiki/Christopher_Alexander"
./b4w.ps1 snapshot -v 0                    # deliberate baseline
./b4w.ps1 snapshot grep "Pattern Language"  # locate link ref e550
./b4w.ps1 click e550 --no-snapshot          # navigate to A Pattern Language
./b4w.ps1 snapshot -v 0 --auto-diff --stdout --all

Grep for the diff markers:
  grep -n '^##' <output>              # -> ### Diff (322), ## Removed (326), ## Added (479)
  grep -c '^+ ' <output>              # -> 43 added
  grep -c '^- ' <output>              # -> 151 removed
  grep -c '^~ ' <output>              # -> 0 modified

#### Expected Behavior

Per `browser4-cli snapshot --help`: 'Diff against the previous snapshot — show only what changed since the last capture' (the reference doc likewise says 'show only what changed').

#### Actual Behavior

524 lines: roughly the first 321 lines are the complete current-page tree with no diff marking whatsoever (including chrome that did not change, e.g. the search box), followed by the `### Diff` section. The diff lines also use a different, non-YAML shape — `+ button e7078 "Toggle Structure subsection"` — with no [ref=] and no [box=], and the leading '+'/'-' collides visually with YAML list markers ('- link e550 "A Pattern Language"' appears under ## Removed where '-' is easy to misread as a YAML item). Some added lines render oddly, e.g. `+ "table e7305 ""` and `+ "generic e7306 ""`.

#### Root Cause Analysis

The auto-diff response is the full snapshot document with two appended sections (Removed/Added) rendered by a different formatter than the tree, rather than a filtered diff view. Because refs are CDP backend-node IDs, a navigation reassigns every ref, so a naive element-identity diff would indeed mark the whole page as changed — the implementation appears to sidestep that by emitting the full tree plus name-based deltas, which is why the counts (151/43) do not correspond to the tree size (499 refs). The help text was not updated to match the actual output contract.

#### Code Pointer

`Auto-diff rendering in the snapshot output path (backend ariaSnapshot/diff response builder and the CLI's snapshot printer); see skills/browser4-cli/references/snapshot.md 'Auto-Diff' section for the documented contract that needs correcting`

#### AI Suggested Improvement

- Fix the help text and reference doc to state exactly what is printed (full current tree + '### Diff' with '## Removed'/'## Added' name-delta sections), or make the output match the current wording by printing only the diff
- Give diff lines an unambiguous, machine-readable marker (e.g. a '## Added' section with real YAML list items, or a '+ ' prefix reserved for diffs and never emitted inside tree lines)
- Emit [ref=] on diff lines so changed elements can be acted on directly
- Investigate the malformed `+ "table e7305 ""` rendering

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: snapshot grep cannot search the auto-diff output, and --help does not say so

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

# The documented/task-expected workflow:
./b4w.ps1 snapshot -v 0 --auto-diff --stdout --all > diff.txt
./b4w.ps1 snapshot grep -c -F "## Added"     # -> 0
./b4w.ps1 snapshot grep -c -F "+ button"     # -> 0

# Workaround that works:
grep -i -C 3 "pattern language" diff.txt

#### Expected Behavior

A way to run grep-style searches (with -i/-C/-v/-c/-F/-w) over the changed elements produced by --auto-diff.

#### Actual Behavior

`snapshot grep` always captures a fresh full-page AX tree from the live page; it never reads the last saved snapshot file and can never see diff markers (+/-/~), which exist only in the --auto-diff response text. Both probe searches return 0 matches with exit 1. The only way to search the diff is to redirect --auto-diff --stdout to a file and use an external grep.

#### Root Cause Analysis

By design: snapshot grep is implemented as a server-side search over a freshly captured tree, not as a search over persisted snapshot artifacts, and the diff is a computed view rather than a stored document. The limitation is documented only in skills/browser4-cli/references/snapshot.md ('it cannot see auto-diff markers ... because those exist only in the --auto-diff response text, never in a persisted file'); `browser4-cli snapshot --help` describes grep as searching 'snapshot YAML content' without mentioning that the auto-diff output is out of scope, so the gap is invisible to anyone working from --help alone.

#### Code Pointer

`cli/browser4-cli/src/ snapshot grep implementation (help text and dispatch) — see the `snapshot grep` subcommand help in the snapshot command definition`

#### AI Suggested Improvement

- Add a --diff flag to `snapshot grep` that searches the current auto-diff response instead of the live tree
- Alternatively persist the --auto-diff output as a snapshot file so existing tools (snapshot grep, diff snapshot) can read it
- State the limitation in `snapshot --help` itself, next to the grep options, not only in the deep reference doc
- Point users at the workaround in the help text: 'redirect --auto-diff --stdout to a file and grep it'

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: snapshot -i merged accessible names are extremely noisy, concatenating internal ids, classes and paths

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 goto "https://en.wikipedia.org/wiki/Christopher_Alexander"
./b4w.ps1 snapshot -i --stdout --all | head -25

#### Expected Behavior

Interactive elements with readable, self-contained labels (the documented promise: 'inner text merged into their names so each surviving ref line reads as a self-contained target').

#### Actual Behavior

Names are flooded with internal identifiers and fragments, e.g.:
  search "p-search search Search Wikipedia [alt-f] /wiki/Special:Search Search searchform /w/index.php simpleSearch Search Wikipedia [alt-f] search searchInput Search Wikipedia Search Wikipedia hidden title Special:Search Search"
  button "p-lang-btn-sticky-header 23 languages"
  generic "ca-talk-sticky-header # ca-subject-sticky-header # ca-history-sticky-header # ca-watchstar-sticky-header # ca-edit-sticky-header # ca-ve-edit-sticky-header # ca-viewsource-sticky-header # p-lang-btn-sticky-header 23 languages ca-addsection-sticky-header # Add topic"
The genuinely useful text ('Search Wikipedia', '23 languages') is buried in noise, making -i harder to read than the plain tree it is meant to simplify.

#### Root Cause Analysis

The interactive-only renderer concatenates the accessible name with every descendant text node (and, apparently, id/class attributes surfaced by the extraction path) without any length cap, deduplication, or filtering of internal tokens. On a modern skin like Wikipedia Vector 2022 this produces very long names.

#### Code Pointer

`Interactive-only snapshot rendering (`-i` merge logic) in the backend AX rendering path used by the ariaSnapshot branch, BrowserTabToolExecutor.kt:1884+`

#### AI Suggested Improvement

- Cap merged names at a sane length (e.g. ~120 chars) and elide the remainder
- Drop tokens that look like internal identifiers (kebab-case prefixed ids/classes such as vector-*, mw-*, ca-*, toc-*; '#', '/w/index.php' fragments; bare hash strings) before merging
- De-duplicate repeated fragments within a single merged name
- Consider emitting the merged text as a child `text:` node instead of inlining it, so the role line stays scannable

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: Snapshot footer is appended to the last element line without a newline, corrupting the final YAML node

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 snapshot -v 0 --stdout > out.txt
tail -8 out.txt
grep -n '1897,1072,0,0' out.txt

#### Expected Behavior

The trailing '# ---' footer block starts on its own line after the last YAML node.

#### Actual Behavior

The last tree line and the footer share one line:
      - list [ref=e8185] [box=1897,1072,0,0]# ---
so the final node is unparseable by any line-based reader. Reproduced in every --stdout capture tested (plain, -i, -v 0). Byte-level check confirms no newline before the '# ---' marker.

#### Root Cause Analysis

The footer is concatenated to the rendered snapshot string without first ensuring the body ends with a line terminator (the tree body evidently has no trailing newline when it ends with a leaf node).

#### Code Pointer

`CLI snapshot stdout/footer assembly (cli/browser4-cli/src/ snapshot output path) — the same string that emits the '# ---' / 'This page has N viewports' block`

#### AI Suggested Improvement

- Emit '\n' before the footer block when the body does not already end with one
- Add a small unit test asserting the last body line and the first footer line are distinct lines (and that the captured output ends with exactly one newline)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: Element names containing a double quote are serialized in a different, quoted-scalar YAML shape

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 goto "https://en.wikipedia.org/wiki/Christopher_Alexander"
./b4w.ps1 snapshot -v all --stdout --all | grep -n '\\"' | head -3
# 22 occurrences on this page; also 8 (-i), 22 (-u), 5 (plain -v 0) across separate captures

#### Expected Behavior

Every node line follows the same shape as all the others: - role "name" [ref=…] [box=…].

#### Actual Behavior

Any element whose accessible name contains a double quote is emitted as a quoted YAML scalar with backslash escapes for the whole node line:
  - "generic \"A Pattern Language: Towns, Buildings, Construction\" [ref=e7306] [box=1116,216,309,49]"
The output is valid YAML but structurally inconsistent with the surrounding lines (roughly 15-20% of nodes on this page switch shape).

#### Root Cause Analysis

The YAML serializer escapes the name and, when the escaping introduces a quote character, falls back to quoting the entire line as a scalar rather than quoting only the name value. Line-based consumers and humans skimming for '- role "..." [ref=' therefore see a differently shaped node.

#### Code Pointer

`Accessibility-tree YAML serialization (name escaping / scalar style selection) in the snapshot rendering path`

#### AI Suggested Improvement

- Keep the node shape uniform: always emit - <role> "<escaped-name>" [ref=…] [box=…], quoting only the name
- Prefer single-quoted names (YAML escapes ' by doubling) when the name contains a double quote, avoiding backslashes entirely
- Add a serialization test with names containing ", ', :, #, and leading/trailing spaces

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: Short flags collide between snapshot and snapshot grep (-i, -v, -c mean different things in the same command family)

**Severity:** Low
**Category:** Discoverability

#### Reproduction

# In `snapshot`:           -i = interactive, -v <n> = viewport index, -c = compact
./b4w.ps1 help snapshot
# In `snapshot grep`:      -i = case-insensitive, -v = invert match, -c = count
./b4w.ps1 snapshot grep -i "pattern"
./b4w.ps1 snapshot grep -c -v "link"

#### Expected Behavior

Flags with the same letter should mean compatible things, or the divergence should be called out where the subcommand is introduced.

#### Actual Behavior

The same letters carry unrelated meanings depending on whether you are in the `snapshot` command or its `grep` subcommand. The sharpest trap is -v: `snapshot -v 0` selects a viewport, while `snapshot grep -v "link"` inverts the match — a user who just learned -v as 'viewport' will silently get an inverted search instead of an error. `-c` similarly flips from 'compact' to 'count'.

#### Root Cause Analysis

The grep subcommand adopts GNU grep conventions (-i/-v/-c) while the parent snapshot command uses -i for interactive-only rendering, -v for viewport, and -c for compact mode. Both are individually defensible, but they are documented in separate help sections with no cross-reference, so the collision is only discoverable by reading both.

#### Code Pointer

`cli/browser4-cli/src/ snapshot command + snapshot grep subcommand flag definitions and their help text`

#### AI Suggested Improvement

- Add a short note in the `snapshot grep` help stating that its flags follow grep semantics and deliberately differ from `snapshot`'s -i/-v/-c
- Consider accepting long forms (--invert-match, --count, --ignore-case) as the primary spellings in the grep subcommand
- If feasible, reject the ambiguous short forms in the grep subcommand with a hint pointing at the long form

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 10: Snapshot captures accumulate in the working directory with no capture-time warning

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 snapshot list
ls .browser4-cli/snapshot/ | wc -l ; du -sh .browser4-cli/snapshot/

#### Expected Behavior

Some signal at capture time (count/size threshold plus a hint) or a retention policy, so a new user does not silently grow the working directory.

#### Actual Behavior

After one session: 'Snapshots in .browser4-cli\snapshot (showing 20 of 100 total)', with 115 files / 9.3 MB on disk. Every goto/click/snapshot writes a new timestamped file into the current working directory, and the help for `snapshot list` would only be found by a user already looking for it. The growth is disk-visible but never surfaced during normal use.

#### Root Cause Analysis

Captures are written unconditionally to <cwd>/.browser4-cli/snapshot/ with no size/count accounting at write time and no automatic pruning. The behavior is documented in references/snapshot.md (including `snapshot clean`) but the CLI prints only the file path, never a retention hint.

#### Code Pointer

`CLI snapshot file writer (cli/browser4-cli/src/ snapshot capture path) and the snapshot list/clean implementation`

#### AI Suggested Improvement

- Print a one-line hint on stderr when the snapshot directory exceeds a threshold (e.g. 'snapshot dir holds 115 files / 9.3 MB — run snapshot clean to prune')
- Add a config key for retention (max age / max count) and apply it automatically
- Consider pruning on `close`/`stop`, or at least report the total on every capture

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

**Completion Status:** Partially Successful — 9 of 10 task steps completed end-to-end. Step 4 (CSS-scoped snapshot) could not be completed as documented because both documented entry points (`snapshot --selector` and `snapshot grep --selector`) are non-functional; it was satisfied only via the unrelated `htmlsnapshot get text "#mw-content-text"` path. Step 9 was completed for all six grep flags with verified controls, but its `--selector` filter failed with the same defect and its premise (grep the auto-diff output) is unsupported by the tool and required an external-grep workaround. Step 2 also required clarification: `-v 0` is one viewport of ten, not the full page.

**Success Rate:** 80% — all navigation, interaction, capture, diff and grep-flag work succeeded; the two selector-scoped requirements and the literal auto-diff-grep requirement did not.

**Issues Found:** 10

**Major Blockers:** `snapshot --selector <css>` and `snapshot grep --selector <css>` fail for every realistic CSS selector (a missing pair of quotes around the selector in the JavaScript probe at BrowserTabToolExecutor.kt:1906), making the documented snapshot-scoping feature unreachable and blocking task step 4. The second-order blocker is diagnostic quality: the same defect reports either a raw `[INTERNAL] SyntaxError` or a false 'CSS selector matched no element on the page', which actively misdirects debugging.

**Most Confusing Aspects:** 1) `-v 0` looks like 'full snapshot' but is one viewport — and every capture, including whole-page ones, prints a header insisting 'You are viewing viewport 0', so the output itself teaches the wrong mental model. 2) The same short flags mean different things in `snapshot` and `snapshot grep` (-v is viewport vs invert-match), so a just-learned flag silently changes meaning one subcommand later. 3) `--auto-diff` announces 'show only what changed' but prints the entire current tree plus an appendix in a different line format. 4) `snapshot grep` cannot search the auto-diff output, and only the deep reference doc says so. 5) `-i` output, advertised as the concise interactive view, is dominated by concatenated internal CSS class names ('p-lang-btn-sticky-header 23 languages').

**Most Valuable Improvements:** Fix the one-line selector-interpolation bug (Critical) and add a regression test with a '#'/'.' selector; make the selector probe surface the real JavaScript error instead of coercing to 'matched no element'; correct the viewport header/footer so a whole-page capture never claims to be viewport 0; align the --auto-diff help text with what it actually prints and give diff lines unambiguous, ref-bearing syntax; give `snapshot grep` a --diff mode (or persist diff output) and mention the limitation in --help; trim the noisy -i merged names; and emit the trailing footer on its own line.

**Usability Rating:** 6/10

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

#### Issue 1: snapshot --selector and snapshot grep --selector are completely broken (CSS selector interpolated unquoted into JavaScript)

cd D:/workspace/Browser4/Browser4-4.14
./b4w.ps1 goto "https://en.wikipedia.org/wiki/Christopher_Alexander"
./b4w.ps1 snapshot --selector "#mw-content-text"
./b4w.ps1 snapshot grep --selector "#mw-content-text" -c "pattern"

Variants tested (all fail):
  --selector "#mw-content-text"      -> SyntaxError (Private field '#mw' ...)
  --selector ".mw-parser-output"     -> SyntaxError (Unexpected token '.')
  --selector "main"                  -> ReferenceError: main is not defined
  --selector "div#mw-content-text"   -> SyntaxError (missing ) after argument list)
  --selector "[id=\"mw-content-text\"]" -> misleading 'matched no element on the page'
Control proving the selectors are valid:
  ./b4w.ps1 eval "JSON.stringify({mw: !!document.querySelector('#mw-content-text'), po: !!document.querySelector('.mw-parser-output'), main: !!document.querySelector('main')})" -> {"mw":true,"po":true,"main":true}

#### Issue 2: Misleading 'matched no element on the page' error swallows the real JavaScript exception

./b4w.ps1 goto "https://en.wikipedia.org/wiki/Christopher_Alexander"
./b4w.ps1 snapshot --selector '[id="mw-content-text"]'
# contrast:
./b4w.ps1 is visible "#mw-content-text"   # -> true

#### Issue 3: Snapshot header claims 'You are viewing viewport 0' even for whole-page captures

./b4w.ps1 goto "https://en.wikipedia.org/wiki/Christopher_Alexander"
./b4w.ps1 snapshot -i --stdout --all | head -7
./b4w.ps1 snapshot -i --stdout --all | tail -8
./b4w.ps1 snapshot -d 3 --stdout --all | head -7

#### Issue 4: --auto-diff help says 'show only what changed' but the output is the full tree plus a diff appendix, in an inconsistent line format

./b4w.ps1 goto "https://en.wikipedia.org/wiki/Christopher_Alexander"
./b4w.ps1 snapshot -v 0                    # deliberate baseline
./b4w.ps1 snapshot grep "Pattern Language"  # locate link ref e550
./b4w.ps1 click e550 --no-snapshot          # navigate to A Pattern Language
./b4w.ps1 snapshot -v 0 --auto-diff --stdout --all

Grep for the diff markers:
  grep -n '^##' <output>              # -> ### Diff (322), ## Removed (326), ## Added (479)
  grep -c '^+ ' <output>              # -> 43 added
  grep -c '^- ' <output>              # -> 151 removed
  grep -c '^~ ' <output>              # -> 0 modified

#### Issue 5: snapshot grep cannot search the auto-diff output, and --help does not say so

# The documented/task-expected workflow:
./b4w.ps1 snapshot -v 0 --auto-diff --stdout --all > diff.txt
./b4w.ps1 snapshot grep -c -F "## Added"     # -> 0
./b4w.ps1 snapshot grep -c -F "+ button"     # -> 0

# Workaround that works:
grep -i -C 3 "pattern language" diff.txt

#### Issue 6: snapshot -i merged accessible names are extremely noisy, concatenating internal ids, classes and paths

./b4w.ps1 goto "https://en.wikipedia.org/wiki/Christopher_Alexander"
./b4w.ps1 snapshot -i --stdout --all | head -25

#### Issue 7: Snapshot footer is appended to the last element line without a newline, corrupting the final YAML node

./b4w.ps1 snapshot -v 0 --stdout > out.txt
tail -8 out.txt
grep -n '1897,1072,0,0' out.txt

#### Issue 8: Element names containing a double quote are serialized in a different, quoted-scalar YAML shape

./b4w.ps1 goto "https://en.wikipedia.org/wiki/Christopher_Alexander"
./b4w.ps1 snapshot -v all --stdout --all | grep -n '\\"' | head -3
# 22 occurrences on this page; also 8 (-i), 22 (-u), 5 (plain -v 0) across separate captures

#### Issue 9: Short flags collide between snapshot and snapshot grep (-i, -v, -c mean different things in the same command family)

# In `snapshot`:           -i = interactive, -v <n> = viewport index, -c = compact
./b4w.ps1 help snapshot
# In `snapshot grep`:      -i = case-insensitive, -v = invert match, -c = count
./b4w.ps1 snapshot grep -i "pattern"
./b4w.ps1 snapshot grep -c -v "link"

#### Issue 10: Snapshot captures accumulate in the working directory with no capture-time warning

./b4w.ps1 snapshot list
ls .browser4-cli/snapshot/ | wc -l ; du -sh .browser4-cli/snapshot/

