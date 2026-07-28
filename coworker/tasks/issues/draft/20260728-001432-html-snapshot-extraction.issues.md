# Issues: html-snapshot-extraction

> **Source:** `20260728-001432-html-snapshot-extraction.full.md` | **Date:** 20260728-001432 | **Mode:** dev

## Scenario Background

### Task

All 10 steps of the task completed successfully:

| Step | Description | Result |
|------|-------------|--------|
| 1 | Navigate to books.toscrape.com | ✅ Page loaded, title "All products \| Books to Scrape - Sandbox" |
| 2 | Capture HTML snapshot | ✅ 64 KB snapshot captured with 516 nodes, 94 links, 20 images |
| 3 | Extract first book title text | ✅ `A Light in the ...` |
| 4 | Extract HTML of first product container | ✅ Full `<article class="product_pod">` HTML with all children |
| 5 | Extract href of first book link | ✅ `catalogue/a-light-in-the-attic_1000/index.html` |
| 6 | Extract all book titles (20 total) | ✅ JSON array of 20 titles |
| 7 | Paginate titles 6–10 via `--offset 5 --limit 5` | ✅ Titles 6–10: The Requiem Red through The Black Maria |
| 8 | Export snapshot to HTML file | ✅ 45 KB exported to `.test-sessions/books_toscrape.html` (after correcting `--file` flag) |
| 9 | Generate summary (WPSI) | ✅ Full structural summary with landmarks, link groups, content nodes |
| 10 | Grep for "price", count occurrences | ✅ 40 occurrences (20 `product_price` + 20 `price_color`) |

**Key workaround required:** `htmlsnapshot export` rejects a positional file path argument — must use `--file <path>` flag. Discovered via `--help`.

### Execution Context

**Key Commands:**

1. `./b4w.sh goto "http://books.toscrape.com/"` — navigate
2. `./b4w.sh htmlsnapshot capture` — capture HTML snapshot
3. `./b4w.sh htmlsnapshot inspect` — discover CSS selectors (`.product_pod` pattern found, 20 matches)
4. `./b4w.sh htmlsnapshot get text "article.product_pod h3 a"` — first book title
5. `./b4w.sh htmlsnapshot get html "article.product_pod"` — first product HTML
6. `./b4w.sh htmlsnapshot get attr "article.product_pod h3 a" href` — first book link href
7. `./b4w.sh htmlsnapshot get all text "article.product_pod h3 a"` — all 20 titles
8. `./b4w.sh htmlsnapshot get all text "article.product_pod h3 a" --offset 5 --limit 5` — titles 6–10
9. `./b4w.sh htmlsnapshot export --file .test-sessions/books_toscrape.html` — export (required `--file` flag)
10. `./b4w.sh htmlsnapshot summary` — WPSI summary
11. `./b4w.sh htmlsnapshot grep -c price` — count "price" occurrences (40)

**Key decisions:**
- Used `./b4w.sh` instead of `$(./b4w.ps1)` because `$(...)` is bash command substitution and doesn't work as an invocation prefix on Linux. The `.sh` wrapper properly quotes arguments.
- Used `htmlsnapshot inspect` to discover CSS selectors before extracting, following the documentation's recommendation.
- Used `h3 a` selector for titles rather than bare `h3` because the full title text is on the `<a>` element (the `h3` text is truncated with `...`).

**Workarounds:**
- `htmlsnapshot export` rejected positional path; used `--file` flag after consulting `--help`.
- The `$(./b4w.ps1)` invocation format from task instructions is not valid bash syntax — used `./b4w.sh` wrapper.

---

## Issues Found (8 issues)

### Issue 1: b4w.ps1 invocation incompatible with bash $(...) command substitution

**Severity:** High
**Category:** UX

#### Reproduction

Run `$(./b4w.ps1) goto "http://example.com"` in bash. The $(...) executes b4w.ps1 with no arguments, captures its stdout (help text), then tries to execute that help text as a command with 'goto' appended.

#### Expected Behavior

The command should navigate to the URL.

#### Actual Behavior

bash tries to execute the help text output as a shell command, producing 'command not found' errors. Using `./b4w.sh goto "..."` works correctly.

#### Root Cause Analysis

The task template uses $(./b4w.ps1) as an invocation prefix, but in bash this is command substitution syntax. On Linux, there is no native .ps1 handler — the shebang delegates to pwsh, but $(...) captures stdout instead of forwarding it. The b4w.sh wrapper properly handles argument quoting for bash.

#### Code Pointer

`b4w.ps1 — the script could detect bash invocation context and provide a helpful error message directing users to b4w.sh.`

#### AI Suggested Improvement

- Add a detection check in b4w.ps1 for when stdout is being captured (e.g., [ -t 1 ] check) and print a warning to stderr directing non-pwsh users to b4w.sh
- The task template's invocation instructions should differentiate between pwsh and bash: use `./b4w.sh` on Linux/macOS/Git Bash, `./b4w.ps1` only in pwsh
- Provide `./b4w` as a symlink or alias on Linux so the invocation path matches user expectations

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: htmlsnapshot export requires --file flag, rejects positional argument

**Severity:** Medium
**Category:** UX

#### Reproduction

Run `./b4w.sh htmlsnapshot export .test-sessions/output.html`.

#### Expected Behavior

Snapshot should be exported to the specified file path.

#### Actual Behavior

Error: too many arguments: expected 0, received 1. The command requires `--file .test-sessions/output.html`.

#### Root Cause Analysis

The `htmlsnapshot export` subcommand uses a named flag (`--file`) rather than a positional argument for the output path. The help text says 'Export snapshot HTML from Browser4's page storage to a local file' which implies the file path is an argument, but the actual API only accepts `--file <path>`.

#### Code Pointer

`cli/browser4-cli/src/ — the CLI argument parser for htmlsnapshot export should accept a positional file argument or improve the error message to suggest --file.`

#### AI Suggested Improvement

- Accept a positional argument as the file path (most natural for 'export <file>')
- Or at minimum improve the error message: 'Use --file <path> to specify the output file' instead of 'too many arguments'
- Update the help text example to show the --file flag explicitly

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: Book titles truncated with ellipsis in h3 element text

**Severity:** Medium
**Category:** Product

#### Reproduction

Run `./b4w.sh htmlsnapshot get all text "article.product_pod h3"`. Compare with `./b4w.sh htmlsnapshot get all text "article.product_pod h3 a"`.

#### Expected Behavior

Full book titles should be extractable from the primary heading element.

#### Actual Behavior

h3 text returns truncated titles like 'A Light in the ...' while h3 a returns the same truncation. The full title is only available via the `title` attribute on the `<a>` element.

#### Root Cause Analysis

The HTML snapshot captures the rendered text content which may be visually truncated by CSS (text-overflow: ellipsis). The snapshot stores what the browser renders, not the full DOM text content. The full title exists only in the `<a title="...">` attribute.

#### AI Suggested Improvement

- The HTML snapshot capture should store full DOM textContent in addition to (or instead of) rendered visible text
- Or document this limitation clearly and show the workaround: use `get attr … title` for full titles
- The `htmlsnapshot inspect` output could flag truncated text and suggest the title attribute alternative

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: htmlsnapshot inspect discoverability: critical selector-discovery step buried in subcommand

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

Try to extract data from a page without knowing the CSS selectors. The natural workflow goes: goto → htmlsnapshot → ??? → extract.

#### Expected Behavior

The snapshot output should prominently suggest running `htmlsnapshot inspect` to discover selectors.

#### Actual Behavior

The `htmlsnapshot capture` output shows a '💡 Try these next:' section but does not prominently mention `htmlsnapshot inspect`. A new user might guess selectors manually rather than discovering them automatically.

#### Root Cause Analysis

The tips section after capture suggests `get all text` and `get attr` examples but doesn't lead with `htmlsnapshot inspect` as the recommended next step for selector discovery. The SKILL.md documentation does warn: 'Always discover selectors with htmlsnapshot inspect or htmlsnapshot summary before extraction' — but the CLI output doesn't reinforce this.

#### Code Pointer

`browser4-rest/ or browser4-agentic/ — the tips generation logic after htmlsnapshot capture could prioritize inspect as the recommended next command.`

#### AI Suggested Improvement

- In the tips after `htmlsnapshot capture`, add a prominent first tip: 'Run htmlsnapshot inspect to discover CSS selectors for your target data'
- The inspect output itself is excellent — the issue is getting users to discover it exists

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: Character encoding issue: £ sign displayed as Â£ in grep output

**Severity:** Low
**Category:** Reliability

#### Reproduction

Run `./b4w.sh htmlsnapshot grep price` on a page containing £ symbols.

#### Expected Behavior

Pound sterling sign (£) should render correctly.

#### Actual Behavior

The £ sign appears as 'Â£' in the grep output (e.g., 'Â£51.77').

#### Root Cause Analysis

The HTML snapshot stores UTF-8 content, but the grep output path passes through a byte-level rendering that doesn't properly decode multi-byte UTF-8 sequences. The £ character (U+00A3) encoded as UTF-8 is 0xC2 0xA3, which when interpreted as Latin-1 becomes 'Â£'.

#### Code Pointer

`cli/browser4-cli/src/ — the htmlsnapshot grep output rendering should ensure proper UTF-8 encoding in stdout.`

#### AI Suggested Improvement

- Ensure the grep output uses proper UTF-8 encoding when writing to stdout
- Add a test for non-ASCII character handling in grep output

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: Every command prints 'recommended pwsh' noise on stdout

**Severity:** Low
**Category:** UX

#### Reproduction

Run any `./b4w.sh ...` command.

#### Expected Behavior

Command output should contain only relevant results.

#### Actual Behavior

Every invocation prints: 'It is strongly recommended to launch `pwsh` and run the .ps1 commands directly within the `pwsh` terminal.' followed by a blank line.

#### Root Cause Analysis

b4w.sh line 17 unconditionally echoes this recommendation to stdout before executing the actual command. On Linux, where pwsh may not even be the preferred shell, this message is noise that clutters every command output.

#### Code Pointer

`b4w.sh:17 — the echo statement should go to stderr, be printed only once per session, or be suppressed on non-Windows platforms.`

#### AI Suggested Improvement

- Move the recommendation to stderr so it doesn't pollute stdout (important for --json / machine-readable output)
- Print it only on the first invocation (track via a marker file or env var)
- On Linux/macOS, either suppress entirely or print a different message recommending ./b4w.sh

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: htmlsnapshot and htmlsnapshot capture are aliases with identical help and behavior

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Compare `./b4w.sh htmlsnapshot --help` with `./b4w.sh htmlsnapshot capture --help`.

#### Expected Behavior

The distinction between the two forms should be clear from the help text.

#### Actual Behavior

Both show identical output. The help text says htmlsnapshot is the 'Short form of htmlsnapshot capture' but gives no indication of when one form vs the other is preferred. Having two commands that do the same thing increases cognitive load without benefit.

#### Root Cause Analysis

htmlsnapshot is intentionally a shorthand for htmlsnapshot capture, but the help text doesn't make the equivalence clear enough. Users may wonder if there's a semantic difference.

#### Code Pointer

`cli/browser4-cli/src/ — the help text for htmlsnapshot could clarify it is strictly equivalent to htmlsnapshot capture.`

#### AI Suggested Improvement

- Add a clear note: 'htmlsnapshot is identical to htmlsnapshot capture — use whichever you prefer'
- Consider removing the bare `htmlsnapshot` form or hiding it from --help to reduce surface area
- The SKILL.md documentation consistently uses `htmlsnapshot` without 'capture', which is fine but should note the equivalence

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: No --count flag documented for htmlsnapshot grep

**Severity:** Low
**Category:** Documentation

#### Reproduction

Run `./b4w.sh htmlsnapshot grep --help`. The -c flag works but isn't documented.

#### Expected Behavior

All supported flags should be listed in --help output.

#### Actual Behavior

The `-c` flag works (returns count: 40) but is not listed in the help text. A user wouldn't know about it without guessing or reading source code.

#### Root Cause Analysis

The grep subcommand supports `-c` (count-only mode, like standard grep) but the CLI argument definition didn't include it in the help text generation.

#### Code Pointer

`cli/browser4-cli/src/ — the clap/arg definition for htmlsnapshot grep should document the -c flag.`

#### AI Suggested Improvement

- Add -c/--count flag to htmlsnapshot grep help output
- Consider also adding -i (case-insensitive), -n (line numbers), and other standard grep flags that may already be implemented

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 10 task steps completed. One workaround required (htmlsnapshot export --file flag).

**Success Rate:** 90% — 9 of 10 steps worked on first attempt; step 8 required consulting --help to discover the --file flag.

**Issues Found:** 8

**Major Blockers:** None. All tasks were completable. The main friction point was the b4w.sh vs $(./b4w.ps1) invocation confusion on Linux — but ./b4w.sh works reliably once discovered.

**Most Confusing Aspects:** 1. The $(./b4w.ps1) invocation format is incompatible with bash — a Linux user wouldn't know to use ./b4w.sh instead. 2. htmlsnapshot export rejects a positional file path with a cryptic 'too many arguments' error — the user must guess or --help to find --file. 3. Book titles appear truncated in h3 text, which is misleading — the full title is buried in a title attribute.

**Most Valuable Improvements:** 1. Make htmlsnapshot export accept a positional file argument (most intuitive UX). 2. Detect non-pwsh invocation in b4w.ps1 and direct users to b4w.sh. 3. Move the 'recommended pwsh' message to stderr or print it only once. 4. In capture output tips, lead with htmlsnapshot inspect for selector discovery.

**Usability Rating:** 7/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: b4w.ps1 invocation incompatible with bash $(...) command substitution

Run `$(./b4w.ps1) goto "http://example.com"` in bash. The $(...) executes b4w.ps1 with no arguments, captures its stdout (help text), then tries to execute that help text as a command with 'goto' appended.

#### Issue 2: htmlsnapshot export requires --file flag, rejects positional argument

Run `./b4w.sh htmlsnapshot export .test-sessions/output.html`.

#### Issue 3: Book titles truncated with ellipsis in h3 element text

Run `./b4w.sh htmlsnapshot get all text "article.product_pod h3"`. Compare with `./b4w.sh htmlsnapshot get all text "article.product_pod h3 a"`.

#### Issue 4: htmlsnapshot inspect discoverability: critical selector-discovery step buried in subcommand

Try to extract data from a page without knowing the CSS selectors. The natural workflow goes: goto → htmlsnapshot → ??? → extract.

#### Issue 5: Character encoding issue: £ sign displayed as Â£ in grep output

Run `./b4w.sh htmlsnapshot grep price` on a page containing £ symbols.

#### Issue 6: Every command prints 'recommended pwsh' noise on stdout

Run any `./b4w.sh ...` command.

#### Issue 7: htmlsnapshot and htmlsnapshot capture are aliases with identical help and behavior

Compare `./b4w.sh htmlsnapshot --help` with `./b4w.sh htmlsnapshot capture --help`.

#### Issue 8: No --count flag documented for htmlsnapshot grep

Run `./b4w.sh htmlsnapshot grep --help`. The -c flag works but isn't documented.

