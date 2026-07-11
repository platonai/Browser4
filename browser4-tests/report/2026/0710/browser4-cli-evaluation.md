# Browser4-CLI Usability Evaluation — Reddit Sentiment Task

**Date:** 2026-07-10
**Evaluator:** AI agent acting as first-time user
**Task:** Scan Reddit r/programming for "browser automation" sentiment

---

## A. Task Result

✅ **Task completed successfully.** The `browser-automation-sentiment.md` file was produced with analysis of all 5 top posts, including post metadata, key opinions, comment sentiment, and an overall sentiment summary table.

---

## B. Execution Trace

### Commands Used (in order)

| # | Command | Purpose |
|---|---------|---------|
| 1 | `goto "https://www.reddit.com/r/programming/"` | Navigate to r/programming |
| 2 | `snapshot -v 0` | Capture page structure |
| 3 | `snapshot grep -C 3 "search"` | Find search box ref |
| 4 | `fill e247 "browser automation" --submit` | ❌ Failed: `--submit` not supported |
| 5 | `fill e247 "browser automation"` | ❌ Failed: element not focusable |
| 6 | `click e247` | Focus the search box |
| 7 | `type "browser automation"` | Type query (no ref — used focused element) |
| 8 | `press Enter` | Submit search (did not navigate — JS-based search) |
| 9 | `goto "https://...search/?q=browser+automation"` | 🔧 Workaround: direct URL navigation |
| 10 | `snapshot -v 0` | Capture search results |
| 11 | `snapshot grep -i -C 1 "link.*post"` | Find post links/refs |
| 12–21 | 5× `goto` + `wait --load networkidle` + `eval` | Visit each post, extract title + comments via JS |

### Major Decisions

- **Skipped `htmlsnapshot get` for post body extraction** after `[slot='text-body']` returned no matches — Reddit's web component DOM made CSS selectors unreliable. Used `eval` with JavaScript `querySelector` instead.
- **Bypassed the interactive search flow** after discovering Reddit uses client-side rendering for search submission. Navigated directly to the search results URL.
- **Used `type` without a ref** after `click` focused the search box — the docs warn against this but it worked since the element had just been focused.

### Workarounds Required

1. Direct URL navigation for search (Reddit's client-side search didn't respond to `press Enter`)
2. `eval` with inline JavaScript for content extraction (CSS selectors on Reddit's web components were unreliable)
3. Manual command ordering (click → type → press Enter) instead of the single `fill --submit` the docs suggest
4. Careful shell quoting for JavaScript with nested quotes in `eval`

---

## C. Issues Found

### Issue 1: `$cliInvocation`, `$helpCmd`, and `$skillPath` are undefined template variables

**Severity:** Medium

**Category:** Documentation

**Reproduction:** Read the evaluation task template. It references `$RepoRootPath`, `$helpCmd`, `$skillPath`, and `$cliInvocation` as if they are defined environment variables or documented constants. They are not set anywhere in the repository or .claude configuration.

**Expected:** These variables should be either (a) defined as environment variables in the project setup, (b) documented with explicit values in a setup guide, or (c) replaced with literal commands in the evaluation template.

**Actual:** The evaluator had to reverse-engineer the intended values by reading `skills/browser4-cli/references/development.md` to discover `cargo run --manifest-path cli/browser4-cli/Cargo.toml --` and finding the skill path by globbing.

**Root Cause:** The evaluation template uses placeholder variables that assume a setup script or environment has already defined them. For a first-time user, these are undefined and must be discovered through documentation archaeology.

**Code Pointer:** Evaluation task template / setup scripts (not in the browser4-cli codebase itself).

**AI Suggested Improvement:**
- Document the exact invocation command in a quick-start section of `SKILL.md` as a clearly labeled constant (e.g., "**Dev invocation:** `cargo run --manifest-path cli/browser4-cli/Cargo.toml --`")
- Add a `.env.example` or setup script that exports these variables
- Replace template variables with explicit commands in the evaluation instructions

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: `fill --submit` flag documented but rejected at runtime

**Severity:** High

**Category:** Documentation / Product

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- fill e247 "browser automation" --submit
```

**Expected:** The `fill` command accepts `--submit` to press Enter after filling, as documented in `cli/README.md` line 176.

**Actual:** Command fails with: `ERROR: browser_type failed: Extraneous parameter 'submit' for fill. Allowed=[selector, text]`

**Root Cause:** Documentation mismatch — the README lists `--submit` as a supported flag for `fill`, but the actual command implementation (in the backend MCP tool) does not accept it. The `fill` MCP tool only accepts `selector` and `text` parameters.

**Code Pointer:** `cli/README.md:176` (documentation); backend `browser_type` MCP tool definition (implementation).

**AI Suggested Improvement:**
- Either add `--submit` support to the backend `fill`/`browser_type` tool, or remove it from the documentation
- Add a test that verifies every documented flag is actually accepted by the corresponding command
- Run a doc-vs-implementation consistency check as part of CI

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: `fill` fails on non-focusable elements with unclear error recovery path

**Severity:** Medium

**Category:** UX / Reliability

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- fill e247 "browser automation"
```
Where `e247` is a Reddit search textbox that hasn't been clicked yet.

**Expected:** Either (a) the command auto-focuses the element before filling, or (b) the error message suggests a concrete next step ("Try clicking the element first: `click e247`").

**Actual:** Error says `Element is not focusable` followed by Kotlin API documentation for `driver.fill()`. The error message is backend-oriented (showing Kotlin code) rather than user-oriented (showing the CLI command to fix the issue).

**Root Cause:** The `fill` command maps to `browser_type` which requires a focusable element. The error message comes from the backend Kotlin exception and includes implementation-level details rather than user-facing guidance.

**Code Pointer:** Backend `browser_type` MCP tool error handling; CLI error formatting layer.

**AI Suggested Improvement:**
- Add auto-focus (click) before fill when the element is not already focusable
- Improve the error message to suggest: "Try `click e247` first to focus the element, then retry `fill`"
- Strip Kotlin API documentation from user-facing CLI error output

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: Interactive search on JS-heavy sites (Reddit) silently fails

**Severity:** High

**Category:** Reliability

**Reproduction:**
```bash
goto "https://www.reddit.com/r/programming/"
snapshot -v 0                          # find search box ref
click <search-ref>
type "browser automation"
press Enter
# Page URL stays the same — search never submitted
```

**Expected:** Pressing Enter on a focused search input navigates to search results, or the CLI reports that the page did not navigate.

**Actual:** `press Enter` reports success (`✓ Pressed 'Enter'`) but the page URL remains unchanged because Reddit uses client-side JavaScript for search rather than a traditional form submission. The user receives a misleading success indicator.

**Root Cause:** `press Enter` dispatches a keyboard event to the browser, but Reddit's React-based UI intercepts this event and performs navigation via the History API rather than a full page load. `press Enter` reports success based on event dispatch, not on observable page state change.

**Code Pointer:** CLI interaction command result handling; backend keyboard event dispatch.

**AI Suggested Improvement:**
- After `press Enter` (and other submit-like interactions), compare the page URL before/after and warn if it didn't change
- Add a `--expect-navigation` flag to interaction commands that verifies URL change
- Consider adding a `search <query>` convenience command that handles the full search flow (find search box, fill, submit, wait for results) across common search patterns

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: CSS selector extraction fails on web component-based sites (Reddit)

**Severity:** Medium

**Category:** Reliability / UX

**Reproduction:**
```bash
htmlsnapshot get text "[slot='text-body']"
# → "No elements matched '[slot='text-body']'"
```

**Expected:** Either (a) CSS selectors penetrate Shadow DOM to find slotted content, or (b) documentation clearly states that web components with Shadow DOM are not supported by `htmlsnapshot get`.

**Actual:** Reddit's modern UI uses `<shreddit-post>` and `<shreddit-comment>` web components with Shadow DOM. Standard CSS selectors cannot cross Shadow DOM boundaries, making the primary extraction method (`htmlsnapshot get`) ineffective on many modern websites. Users must fall back to `eval` with JavaScript.

**Root Cause:** `htmlsnapshot get` uses CSS selectors against the light DOM / flattened DOM tree, but slotted content inside web components is not reachable via standard CSS selector matching.

**Code Pointer:** Backend HTML snapshot storage and query layer; CSS selector evaluation.

**AI Suggested Improvement:**
- Document Shadow DOM limitations in `SKILL.md` and `htmlsnapshot.md`
- Add a `--deep` flag that pierces Shadow DOM when querying
- Consider adding a `querySelectorDeep` polyfill or walking the composed tree
- Add an example in the docs showing how to use `eval` as a fallback for web component sites

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: Shell quoting for `eval` with JSON on Windows is error-prone

**Severity:** Medium

**Category:** UX / Documentation

**Reproduction:** Any `eval` command that uses JavaScript with nested quotes, template literals, or JSON.stringify on Windows bash:
```bash
eval "JSON.stringify({title: document.querySelector('h1')?.textContent})" --json
```

**Expected:** A straightforward way to pass JavaScript expressions without worrying about shell escaping.

**Actual:** The expression requires manual escaping of single quotes, double quotes, and special characters. The documented workaround (`--file`, `--stdin`, `--base64`) requires writing to a temporary file first, which adds friction for one-off queries. This is a known issue documented in SKILL.md §5 ("Critical Warnings") but the workaround is high-friction.

**Root Cause:** Windows shell (bash via Git Bash) and the Rust CLI argument parser interact poorly with nested quotes in JavaScript. The `eval` command accepts the expression as a positional string argument, which goes through shell parsing.

**Code Pointer:** `cli/browser4-cli/src/commands.rs` — eval command definition and argument handling.

**AI Suggested Improvement:**
- Support `eval --stdin` as default when no expression is provided (read JS from stdin, avoiding shell entirely)
- Add an interactive `eval` mode that opens $EDITOR for multi-line JS input
- Auto-detect when the expression looks like a file path and read from it
- Add a `eval --interactive` mode for REPL-style JS evaluation

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: `cargo run` compilation overhead on every command invocation

**Severity:** Low

**Category:** UX

**Reproduction:** Run any `cargo run --manifest-path ... -- <command>`. Every invocation prints:
```
    Finished `dev` profile [unoptimized + debuginfo] target(s) in 0.24s
     Running `cli\browser4-cli\target\debug\browser4-cli.exe ...`
```

**Expected:** Either (a) the output is suppressed by default in non-verbose mode, or (b) a faster invocation pattern is documented (e.g., build once, then invoke the binary directly).

**Actual:** Even with `--quiet`, the cargo build status lines appear. For a task requiring 20+ commands, this adds ~5 seconds of noise and visual clutter. The development.md mentions `--quiet` but it only suppresses some cargo output.

**Root Cause:** `cargo run` always checks compilation status before execution. The "Finished" line comes from cargo and is not suppressed by the CLI's `--quiet` flag. Cargo's `-q` flag suppresses it, but this adds another layer of flags.

**Code Pointer:** `skills/browser4-cli/references/development.md` (documentation of dev invocation).

**AI Suggested Improvement:**
- Document a two-step pattern in development.md: `cargo build --manifest-path ...` once, then use `./cli/browser4-cli/target/debug/browser4-cli.exe <command>` for subsequent commands
- Add a `--quiet` passthrough to the cargo invocation in the recommended command
- Consider a `justfile` or `Makefile` with a `b4` alias that builds once and invokes the binary

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: No built-in search command for common search-box interaction pattern

**Severity:** Low

**Category:** Discoverability / UX

**Reproduction:** To search a website, a user must: (1) snapshot to find search box ref, (2) click the ref, (3) type the query, (4) press Enter, (5) verify navigation occurred. This is a 5-step manual process for one of the most common web interactions.

**Expected:** A `search <query>` convenience command or at least a documented pattern/recipe for searching.

**Actual:** No search command exists. The interaction commands are low-level primitives. The SKILL.md provides form-fill patterns but no search-specific pattern.

**Root Cause:** The CLI is designed around low-level CDP primitives. Higher-level interaction patterns (search, login, pagination) are left to the user/agent to compose.

**Code Pointer:** N/A — feature request.

**AI Suggested Improvement:**
- Add a `search` command: `browser4-cli search "browser automation"` that auto-discovers the search input, fills it, submits, and waits for results
- At minimum, add a "Search Pattern" recipe in SKILL.md §6 ("Quick Patterns")
- Consider detecting common search input selectors (`input[type=search]`, `input[name=q]`, `[role=searchbox]`)

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 9: Error messages include internal Kotlin API documentation

**Severity:** Medium

**Category:** UX

**Reproduction:**
```bash
fill e247 "browser automation"
# ERROR: browser_type failed: Element is not focusable
# help: This method emulates inserting text that doesn't come from a key press.
# Unlike [type], this method clears the existing value before typing.
# ```kotlin driver.fill("input[name='q']", "Hello, World!") ```
# then filled with text. If there are multiple matching elements, the first will be focused.
```

**Expected:** A concise, user-facing error message with actionable CLI-level guidance.

**Actual:** The error includes Kotlin code snippets (`driver.fill(...)`) and internal API documentation that is irrelevant to a CLI user. This is backend documentation leaking into the user interface.

**Root Cause:** The backend MCP tool throws an exception whose message includes KDoc/implementation details. The CLI passes this message through to the user without filtering or reformatting.

**Code Pointer:** Backend `browser_type` tool implementation; CLI error rendering in `cli/browser4-cli/src/`.

**AI Suggested Improvement:**
- Strip code blocks and API documentation from error messages before displaying to CLI users
- Add a `--debug` flag that shows full backend errors; default to clean user-facing messages
- Map common backend errors to concise CLI guidance (e.g., "Element is not focusable" → "Click the element first, then retry")

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 10: Documentation is scattered across multiple files with overlapping content

**Severity:** Medium

**Category:** Documentation / Discoverability

**Reproduction:** A new user searches for command documentation. They find:
- `cli/README.md` — comprehensive CLI reference
- `skills/browser4-cli/SKILL.md` — AI agent skill guide with command map
- `skills/browser4-cli/references/htmlsnapshot.md` — htmlsnapshot details
- `skills/browser4-cli/references/development.md` — dev invocation
- Inline `--help` output

**Expected:** One authoritative source per topic, with cross-references. A new user should be able to find the answer without checking 3+ files.

**Actual:** Information is duplicated across files (e.g., both SKILL.md and README.md list the command map; both have installation instructions). Some details differ between sources (e.g., `fill --submit` in README.md but not in SKILL.md). The user must read multiple files to get a complete picture, and inconsistencies create confusion.

**Root Cause:** The documentation grew organically — README.md for human CLI users, SKILL.md for AI agents, references/ for deep dives. Content is duplicated rather than linked.

**Code Pointer:** Documentation files: `cli/README.md`, `skills/browser4-cli/SKILL.md`, `skills/browser4-cli/references/*.md`.

**AI Suggested Improvement:**
- Designate `cli/README.md` as the single source of truth for command reference
- Have `SKILL.md` link to `cli/README.md` for command details rather than duplicating the command table
- Run a link checker and consistency validator across the docs
- Consider generating `SKILL.md` command tables from the same source as `--help` output

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## D. Overall Assessment

### Task Completion Status
✅ **Task completed.** All 5 posts were visited, content was extracted, and a comprehensive sentiment summary was produced.

### Estimated Task Success Rate
**85%** — The task succeeded but required 3 workarounds (direct URL navigation, eval fallback, manual click-type-press sequence). A less persistent user might have given up at the search interaction failure.

### Number of Issues Found
**10 issues** — 1 High, 6 Medium, 3 Low severity.

### Major Blockers
1. **Search interaction failure (Issue #4)** — Reddit's client-side search made the basic "search for X" flow non-functional. Required URL crafting workaround.
2. **`fill --submit` rejected (Issue #2)** — Docs said it would work, it didn't. Forced a multi-step manual workaround.

### Most Confusing Aspects
1. **Template variables undefined** — `$cliInvocation`, `$helpCmd`, etc. were not defined anywhere obvious
2. **Documentation fragmentation** — Had to read 3+ files to understand the full command surface
3. **Error messages** — Backend Kotlin internals leaking into CLI output created confusion about what layer was failing

### Most Valuable Improvements
1. **Add a `search` convenience command** — single biggest UX win for common web tasks
2. **Unify and deduplicate documentation** — one source of truth per topic
3. **Fix doc-vs-implementation mismatches** — `fill --submit` being the most glaring
4. **Improve error messages** — strip internal API docs, add actionable CLI guidance
5. **Document the fast-path for dev mode** — `cargo build` once, invoke binary directly

### Overall Usability Rating: **6.5 / 10**

**Strengths:**
- `goto` with auto-session management is excellent
- `snapshot grep` is genuinely useful for element discovery
- `eval` is powerful when quoting works
- Warning system (ref lifecycle, shell quoting) shows good UX awareness
- Auto-daemon/backend startup worked flawlessly

**Weaknesses:**
- Search/interaction reliability on modern JS-heavy sites is poor
- Content extraction on web component sites requires JS fallback
- Documentation inconsistencies create trust issues
- Error messages are backend-oriented, not user-oriented
- Dev mode invocation is verbose and noisy

**Bottom line:** browser4-cli has a solid foundation with powerful primitives, but the gap between documented capability and real-world reliability on modern websites (Reddit, SPAs, web components) creates significant friction for first-time users. The tool would benefit from higher-level convenience commands, documentation consolidation, and better error guidance.
