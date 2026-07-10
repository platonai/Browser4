# Browser4-CLI Usability Evaluation Report — HTML Snapshot Data Extraction

**Date:** 2026-07-10  
**Evaluator:** Claude (AI agent acting as first-time user)  
**Task:** Extract data from books.toscrape.com using htmlsnapshot get (single/match-all/paginated), export, summary, and grep  
**CLI Invocation:** `cargo run --manifest-path cli/browser4-cli/Cargo.toml --`

---

## A. Task Result

All 10 sub-tasks completed successfully:

1. ✅ Navigated to `http://books.toscrape.com/` — reconnected to existing session, loaded correctly.
2. ✅ Captured HTML snapshot (64 KB, 20 images, 94 links, 100 interactive elements).
3. ✅ Extracted first book title via `htmlsnapshot get text "article.product_pod h3 a"` → `"A Light in the ..."` (CSS-truncated).
4. ✅ Extracted HTML of first product container via `htmlsnapshot get html "article.product_pod:first-child"` — full inner HTML returned.
5. ✅ Extracted `href` of first book link via `htmlsnapshot get attr "article.product_pod h3 a" href` → `catalogue/a-light-in-the-attic_1000/index.html`.
6. ✅ Extracted all 20 book titles via `htmlsnapshot get all text "article.product_pod h3 a"` — JSON array with all titles (some truncated).
7. ✅ Paginated titles 6–10 via `htmlsnapshot get all text "article.product_pod h3 a" --offset 5 --limit 5` — returned exactly 5 titles.
8. ✅ Exported HTML snapshot to `books-snapshot-export.html` (45,810 bytes, 476 lines).
9. ✅ Generated page summary (WPSI) — returned 4 link groups, 23 landmarks, 20 content nodes, 3 lists, and stats.
10. ✅ Grep counted "price" occurrences via `htmlsnapshot grep -c "price"` → 40 matching lines.

---

## B. Execution Trace

### Commands Used

| # | Command | Purpose |
|---|---------|---------|
| 1 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help` | Learned available commands |
| 2 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "http://books.toscrape.com/"` | Navigated to target site |
| 3 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot` | Captured static HTML snapshot |
| 4 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get text "article.product_pod h3 a"` | First book title (single) |
| 5 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get html "article.product_pod:first-child"` | First product container HTML |
| 6 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get attr "article.product_pod h3 a" href` | First book link href |
| 7 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get all text "article.product_pod h3 a"` | All 20 book titles |
| 8 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get all text "article.product_pod h3 a" --offset 5 --limit 5` | Titles 6–10 via pagination |
| 9 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot export --file ...books-snapshot-export.html` | Exported HTML to file |
| 10 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot summary` | Generated page summary |
| 11 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot grep -c "price"` | Counted "price" occurrences |
| 12 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot --help` | Verified subcommand help |
| 13 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get --help` | Verified get subcommand help |

### Important Decisions

- **Used `--quiet` flag** after the first few commands to suppress cargo build output lines (`Finished dev profile...`, `Running...`) that clutter the data output.
- **Selector discovery:** Used `.product_pod` selectors derived from prior knowledge (previous eval reports and SKILL.md examples). A genuine first-time user would need to run `htmlsnapshot inspect` first or consult documentation.
- **`--offset` semantics:** The `get all` command uses 0-based indexing for `--offset`. To get human-counted titles 6–10, used `--offset 5 --limit 5`. This is intuitive but worth documenting.

### Workarounds Required

- **Build output suppression:** Used `--quiet` to suppress `Finished dev profile...` and `Running...` lines. Without it, cargo build status lines intersperse with data output, making parsing harder.
- **Session reconnection:** The `goto` command reconnected to an existing session at a different URL (`/catalogue/soumission_998/index.html`). The browser then navigated to the correct homepage — functionally correct but the message was confusing.

---

## C. Issues Found

### Issue 1: `get all text` returns CSS-truncated visible text — full titles only available via `title` attribute

**Severity:** Medium

**Category:** UX / Documentation

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "http://books.toscrape.com/"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get all text "article.product_pod h3 a"
```
Output: `["A Light in the ...","Tipping the Velvet","Soumission",...]` — truncated titles.

**Expected:** Full text of each book title, or a clear hint that `textContent` reflects CSS-rendered text and an attribute-based alternative exists.

**Actual:** `get all text` returns `textContent` which is truncated by the website's CSS `text-overflow: ellipsis`. The full text is in the `<a>` tag's `title` attribute, but the user must discover this independently.

**Root Cause:** `get all text` extracts `textContent` from the DOM, which reflects CSS-rendered text. The site uses `text-overflow: ellipsis` in narrow columns. Users need to use `get all attr <selector> title` instead.

**Code Pointer:**

**AI Suggested Improvement:**
- When `htmlsnapshot get all text` returns values ending in "...", emit a tip on stderr suggesting `get all attr <selector> title` as an alternative.
- In `htmlsnapshot inspect` output, add a note when text nodes appear truncated: "💡 Text appears truncated; check for a `title` attribute."
- Document the `textContent` vs `title` attribute distinction prominently in the htmlsnapshot reference.

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: `goto` reconnection message shows a different URL than requested — confusing for first-time users

**Severity:** Low

**Category:** UX

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "http://books.toscrape.com/"
```
Output:
```
Reconnected to existing session on https://books.toscrape.com/catalogue/soumission_998/index.html
### Page
- Page URL: https://books.toscrape.com/
```

**Expected:** The reconnection message should reference the requested URL or the homepage, not a detail page from a previous session.

**Actual:** The reconnection message shows the URL of the previously active page (`/catalogue/soumission_998/index.html`), then the browser navigates to the requested homepage. The final `Page URL` is correct, but the intermediate message is misleading — it looks like the command went to the wrong page.

**Root Cause:** `goto` first reconnects to the existing browser session (which was left on a detail page), then navigates to the requested URL. The reconnection message reflects the browser's state *before* navigation completes.

**Code Pointer:** Likely in `cli/browser4-cli/src/main.rs` or the goto command handler — the session reconnection and URL display logic.

**AI Suggested Improvement:**
- Change the message to: "Reconnected to existing session (was on: <previous_url>). Navigating to: <requested_url>..."
- Or suppress the previous URL entirely and only show the final destination.
- Add a `--new-session` flag to force a fresh session instead of reconnecting.

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: Cargo build output lines clutter command output — `--quiet` is needed but not advertised in main help

**Severity:** Low

**Category:** Discoverability / UX

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get text "h1"
```
Output:
```
    Finished `dev` profile [unoptimized + debuginfo] target(s) in 0.45s
     Running `cli/browser4-cli/target/debug/browser4-cli htmlsnapshot get text h1`
All products
```

**Expected:** Either no build-status lines mixed with data output, or clear documentation that `--quiet` suppresses them.

**Actual:** Two cargo build-status lines appear before every command's actual output. These are harmless for human reading but problematic when piping output to files or `jq`. The `--quiet` flag suppresses them, but it's only documented in `development.md`, not in the main `--help` output.

**Root Cause:** `cargo run` always prints build status to stderr. The `--quiet` flag passes through to cargo. In dev mode this is expected behavior, but new users won't discover `--quiet` from the main help.

**Code Pointer:** Help text in `cli/browser4-cli/src/commands.rs` or `main.rs` — the `--quiet` flag description.

**AI Suggested Improvement:**
- Add a note about `--quiet` to the main `--help` output, especially in the "Global options" section.
- In `development.md`, add a more prominent callout about `--quiet` for output redirection.
- Consider adding a `--no-build-output` flag alias for clarity.

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: `get html` returns innerHTML, not outerHTML — behavior differs from user expectation

**Severity:** Low

**Category:** Documentation / UX

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get html "article.product_pod"
```
Output: Inner HTML of the first `.product_pod` — starts with `<div class="image_container">`, does NOT include the `<article class="product_pod">` wrapper.

**Expected:** "get html" could reasonably be interpreted as returning the full HTML including the matched element itself (outerHTML). Users wanting the container structure may be surprised to find the container tag missing.

**Actual:** Returns innerHTML (content between the opening and closing tags of the matched element). This is correct for `innerHTML` semantics but the command name "get html" doesn't distinguish between innerHTML and outerHTML.

**Root Cause:** The backend uses `element.innerHTML` (or equivalent DOM property). The command documentation says "Get the HTML of a specific element" which is ambiguous.

**Code Pointer:**

**AI Suggested Improvement:**
- Rename to `get innerhtml` or add an `outerhtml` field option.
- Update documentation to explicitly state "inner HTML (content inside the matched element, excluding the element's own tags)."
- Add an example that shows the difference: "Note: `get html` returns inner HTML. To include the container, use a parent selector."

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: Selector required for extraction — no shorthand for "first product" or common page patterns

**Severity:** Low

**Category:** Discoverability / UX

**Reproduction:**
Task instructions say "extract the text of the first book title" and "extract the HTML of the first product container" — but no CSS selector is provided.

**Expected:** A new user should be able to discover the right CSS selectors quickly. The workflow `htmlsnapshot inspect` → find selectors → `get` works but adds extra steps for simple tasks.

**Actual:** The user must either: (a) know the page's HTML structure beforehand, (b) run `htmlsnapshot inspect` first to discover selectors, (c) consult documentation for example selectors. For the books.toscrape.com page, the selectors `.product_pod`, `h3 a` are not discoverable from the `htmlsnapshot` capture output alone (which shows interactive elements but not their CSS classes in a queryable way beyond the flat list).

**Root Cause:** The `htmlsnapshot` capture output lists interactive elements with tag and class info (e.g., `button.btn.btn-primary`), but the document structure (which elements are children of which) requires `htmlsnapshot inspect` or `htmlsnapshot summary` to discover. The workflow is correct but requires an extra step that first-time users might not know about.

**Code Pointer:**

**AI Suggested Improvement:**
- After `htmlsnapshot` capture, add a tip: "💡 Run `htmlsnapshot inspect` to auto-discover CSS selectors for recurring content patterns."
- The existing tip "Use `get all text` to extract visible text..." is good but could also mention `inspect`.
- Consider adding a `--quick` flag to `htmlsnapshot` that auto-runs inspect and suggests the top selectors.

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: Main `--help` output is overwhelming — 80+ commands in a flat list

**Severity:** Medium

**Category:** Discoverability / UX

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help
```
Output: ~150 lines of help text with all commands listed alphabetically within categories. The command list spans several screenfuls.

**Expected:** A concise overview with the most common commands first, or a tiered help system where `--help` shows common commands and `--help --all` shows everything.

**Actual:** The help output is comprehensive but overwhelming. A new user scanning for "how to extract data from a page" must read through navigation, keyboard, mouse, capture, tabs, storage, htmlsnapshot, agent, swarm, and install sections before finding the right commands. The SKILL.md provides a much better entry point with its "Core Loop" and "Decision Trees" sections.

**Root Cause:** The help text aims for completeness rather than discoverability. All commands are given equal visual weight regardless of how commonly they're used.

**Code Pointer:** Help generation in `cli/browser4-cli/src/commands.rs` or `main.rs`.

**AI Suggested Improvement:**
- Add a "Quick Start" section at the top of `--help` with the 5 most common commands (goto, snapshot, click, fill, htmlsnapshot get).
- Consider `--help` = common commands only; `--help --all` = full list.
- Group related commands with clearer headers and add a "See also: `htmlsnapshot --help`" cross-reference.
- Add a "First time? Try: goto <url>, snapshot -v 0, htmlsnapshot get text 'h1'" line at the top.

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: `htmlsnapshot grep -c` counts matching *lines*, not matching *occurrences*

**Severity:** Low

**Category:** Documentation

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot grep -c "price"
```
Output: `40`

**Expected:** The documentation says `-c` prints "the count of matching lines." The task asks to "count the occurrences" of "price." The output of 40 is actually the count of *lines* containing "price," not the total number of "price" substrings. If a single line contains "price" multiple times (e.g., `class="price_color"` and `price` in the same line), it still counts as 1.

**Actual:** The output of 40 is the line count. This is consistent with grep semantics, and the documentation correctly says "matching lines." The task's phrasing "count the occurrences" is ambiguous — 40 is a reasonable answer for this page, but a user expecting substring-count semantics might be confused.

**Root Cause:** This is standard grep behavior (matching lines, not substrings), and the documentation accurately describes it. The issue is more about the natural-language ambiguity of "count occurrences" vs "count matching lines."

**Code Pointer:**

**AI Suggested Improvement:**
- Add a note in the grep docs: "`-c` counts matching *lines*, not total substring occurrences. Use `-c` for presence checks (does this page mention X?) rather than precise frequency counts."
- Consider adding a `--count-all` flag that counts every match (not just one per line) if there's demand.

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
**All 10 sub-tasks completed successfully.** Every command executed without errors on the first attempt. No retries or error recovery were needed.

### Estimated Task Success Rate
**95%** — The task was straightforward once the documentation was read. The only friction points were:
- Knowing which CSS selectors to use (required prior knowledge or running `htmlsnapshot inspect`)
- Understanding that `get all text` returns CSS-truncated text
- The session reconnection message being momentarily confusing

### Number of Issues Found
**7 issues** (0 Critical, 2 Medium, 5 Low)

### Major Blockers
None. All commands executed successfully. The task completion path was clear.

### Most Confusing Aspects
1. **Text truncation** — `get all text` returns truncated titles; the `title` attribute workaround requires user discovery.
2. **Session reconnection message** — seeing a different URL than requested is momentarily alarming.
3. **Understanding which selectors to use** — the task says "extract the first book title" but doesn't say how. The user must know to use `htmlsnapshot inspect` or read documentation.

### Most Valuable Improvements
1. **Add truncation detection** to `get all text` — emit a tip when extracted values end with "..."
2. **Add a "first time user" section** to `--help` with the 5 most common commands
3. **Improve `goto` reconnection message** — show both previous and target URLs
4. **Post-capture tips** — after `htmlsnapshot`, suggest `inspect` for selector discovery

### Strengths Observed
- **Excellent subcommand help**: `htmlsnapshot --help` and `htmlsnapshot get --help` are thorough with clear examples
- **Rich metadata**: The `htmlsnapshot` capture returns comprehensive page info (image/link counts, interactive elements with bounding boxes)
- **Pagination support**: `--offset`/`--limit` on `get all` works intuitively and correctly
- **Grep integration**: `htmlsnapshot grep` with standard grep flags (-c, -i, -v, etc.) is powerful and familiar
- **Summary quality**: The WPSI summary is genuinely useful for understanding page structure at a glance
- **Export works reliably**: The exported HTML is well-formatted and usable for offline analysis
- **Documentation quality**: SKILL.md, htmlsnapshot.md, and development.md are clear, well-structured, and provide actionable workflows

### Overall Usability Rating
**8/10**

The `htmlsnapshot` command family is well-designed and worked flawlessly for this extraction task. The documentation is thorough and the subcommand help is excellent. Points are deducted for: text truncation surprise (known issue), the overwhelming main `--help` output, and the need to read external documentation to discover the right CSS selectors (unavoidable to some degree, but `htmlsnapshot inspect` could be more prominently suggested after capture). For a first-time user who reads the SKILL.md, the experience is smooth. For someone who tries to figure it out from `--help` alone, it would be more challenging.

---
*Generated with Claude Code*
