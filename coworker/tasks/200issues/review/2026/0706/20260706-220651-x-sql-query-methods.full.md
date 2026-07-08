---

# Browser4-CLI Usability Evaluation Report

## A. Task Result

**Task:** Navigate to `http://books.toscrape.com/`, discover CSS selectors with `htmlsnapshot inspect`, and extract book titles + prices using X-SQL via all four input methods (`--sql`, `--sql @file`, `--sql-stdin`, `--sql-base64`), then test `--result-only`.

**Outcome:** Successfully completed, with one workaround required.

**Extracted Data:** 20 books with titles and prices, e.g.:
| Title | Price |
|-------|-------|
| A Light in the Attic | £51.77 |
| Tipping the Velvet | £53.74 |
| ... | ... |
| It's Only the Himalayas | £45.17 |

---

## B. Execution Trace

### Commands Used

```
cargo run -- --help
cargo run -- goto "http://books.toscrape.com/"
cargo run -- htmlsnapshot
cargo run -- htmlsnapshot inspect
cargo run -- htmlsnapshot get all text "h3 a"
cargo run -- htmlsnapshot get all attr "h3 a" title
cargo run -- htmlsnapshot get all text "p.price_color"
cargo run -- htmlsnapshot query --sql "SELECT DOM_FIRST_ATTR(DOM, 'h3 a', 'title') AS title, DOM_FIRST_TEXT(DOM, 'p.price_color') AS price FROM DOM_LOAD_AND_SELECT(@url, 'article.product_pod')"
# Wrote extract_books.sql, then:
cargo run -- htmlsnapshot query --sql @extract_books.sql
cargo run -- htmlsnapshot query --sql-stdin < extract_books.sql
# base64 workaround required:
cargo run -- htmlsnapshot query --sql "dummy" "--sql-base64=$(base64 -w0 extract_books.sql)"
cargo run -- htmlsnapshot query --sql @extract_books.sql --result-only
cargo run -- close
```

### Major Steps
1. **Preparation:** Read `SKILL.md` and `cli/README.md` to learn command conventions
2. **Navigation:** `goto` to books.toscrape.com (HTTP auto-upgraded to HTTPS)
3. **Snapshot:** Captured HTML snapshot with `htmlsnapshot`
4. **Inspect:** `htmlsnapshot inspect` auto-discovered `article.product_pod` (20 matches) with suggested selectors for price, title, etc.
5. **Selector verification:** Used `htmlsnapshot get all` to confirm `h3 a[title]` and `p.price_color` selectors
6. **X-SQL inline:** Successfully ran query with `--sql` (single quotes inside double quotes worked on bash)
7. **X-SQL from file:** Wrote `extract_books.sql`, ran via `--sql @extract_books.sql` — cleanest approach
8. **X-SQL from stdin:** Piped file via `--sql-stdin` — worked perfectly
9. **X-SQL from base64:** Required workaround (`--sql "dummy" "--sql-base64=<value>"`) due to bugs
10. **Result-only:** `--result-only` returned clean JSON array without metadata wrapper
11. **Cleanup:** Deleted temp file, closed session

### Workarounds Required
- **`--sql-base64`:** Two bugs prevent both documented usage modes. Workaround: pass a dummy `--sql` value with `--sql-base64=<actual-b64>` to bypass validation.

---

## C. Issues Found

### Issue 1: `--sql-base64` standalone (Mode 1) fails — validation rejects it before base64 decode

**Severity:** High

**Category:** Product

**Reproduction:**
```bash
cargo run -- htmlsnapshot query --sql-base64 "$(base64 -w0 query.sql)"
cargo run -- htmlsnapshot query "--sql-base64=$(base64 -w0 query.sql)"
```

**Expected:** The CLI should decode the base64 value and execute the query without requiring `--sql`.

**Actual:** Error: `--sql is required. Provide an inline X-SQL query, @file.sql, --sql-stdin, or --sql-base64.`

**Root Cause:** In `main.rs:3819`, the empty-check on `sql_raw` runs before the base64 decode at line 3835. When `--sql-base64 <value>` is used without `--sql`, `sql_raw` is empty and the early return fires. The check doesn't account for `sqlBase64` providing the SQL independently.

**Code Pointer:** `cli/browser4-cli/src/main.rs:3819` — `handle_html_snapshot_query()` function, the `sql_raw.is_empty()` check should also consider `tool_params["sqlBase64"]`.

**AI Suggested Improvement:**
- Move the empty-SQL validation to after the base64 decode, or add a guard that checks `tool_params.get("sqlBase64").and_then(|v| v.as_str())` before returning the error
- The same bug exists at line 6260 for the `swarm query` handler

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: `--sql-base64` boolean flag mode (Mode 2) not captured in tool_params

**Severity:** High

**Category:** Product

**Reproduction:**
```bash
cargo run -- htmlsnapshot query --sql "$(base64 -w0 query.sql)" --sql-base64
```

**Expected:** The CLI should detect `--sql-base64` as a boolean flag, decode the `--sql` value as base64, and execute the decoded query.

**Actual:** Server returns HTTP 400 Bad Request because the raw base64 string is sent as SQL without decoding.

**Root Cause:** In `commands.rs:2029`, the `tool_params_fn` for `htmlsnapshot query` only captures `sql-base64` via `get_opt_str()` which returns `None` for boolean values. When `--sql-base64` is passed as a boolean flag (no value), the args parser sets it to `Value::Bool(true)`, but `get_opt_str` uses `v.as_str()` which returns `None` for booleans. The boolean is silently dropped from `tool_params`, so `maybe_decode_base64_sql` never activates Mode 2. Compare with `sql-stdin` handling at line 2028 which correctly uses `get_bool()`.

**Code Pointer:** `cli/browser4-cli/src/commands.rs:2029` — `htmlsnapshot query` tool_params_fn; also line 2125 for `swarm query`, line 2330 for `crawl`.

**AI Suggested Improvement:**
- Add a boolean fallback after the string check: `if let Some(v) = get_opt_str(args, "sql-base64") { p["sqlBase64"] = json!(v); } else if get_bool(args, "sql-base64").unwrap_or(false) { p["sqlBase64"] = json!(true); }`
- Apply the same fix to all three occurrences (htmlsnapshot query, swarm query, crawl)

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: `htmlsnapshot query --help` and `help htmlsnapshot query` omit usage examples

**Severity:** Medium

**Category:** Documentation

**Reproduction:**
```bash
cargo run -- htmlsnapshot query --help
cargo run -- help htmlsnapshot query
```

**Expected:** Both help outputs should include usage examples showing all four SQL input methods (`--sql`, `--sql @file`, `--sql-stdin`, `--sql-base64`) and `--result-only`.

**Actual:** Both show only the option listing (name + description) with no examples. Users must discover usage patterns from `cli/README.md` or the extended help text which is only accessible through a separate code path.

**Root Cause:** The `--help` flag for subcommands doesn't include examples. The extended examples only appear in `browser4-cli help htmlsnapshot query` (the `help` command, not the `--help` flag), but even that variant doesn't show all four input methods with concrete examples.

**Code Pointer:** `cli/browser4-cli/src/help.rs` — `htmlsnapshot_query_extended_help()` at approximately line 920.

**AI Suggested Improvement:**
- Add a concise "Examples:" section at the bottom of the `--help` output showing one example per input method
- Ensure the extended help covers all four input methods with working command lines
- Fix the documented base64 example at help.rs:935 which shows a mode that doesn't currently work

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: Inline `--sql` shell quoting on Windows is fragile

**Severity:** Medium

**Category:** UX

**Reproduction:** Using `--sql "SELECT ... 'selector' ..."` on Windows (bash or cmd/PowerShell).

**Expected:** Consistent, predictable quoting behavior regardless of shell.

**Actual:** On Windows bash (Git Bash/MSYS2), single quotes inside double quotes work but the shell may mangle them — the `cargo run` output showed convoluted escaping: `'\''h3 a'\'', '\''title'\''`. On cmd.exe or PowerShell, the quoting would be even more problematic. The documentation (SKILL.md) correctly warns about this, but first-time users may still hit it.

**Root Cause:** Shell quoting is inherently platform/shell-specific. The CLI handles the SQL string correctly once it arrives, but getting it through the shell is the challenge.

**Code Pointer:** N/A — this is a documentation/UX issue, not a code bug.

**AI Suggested Improvement:**
- Add a prominent tip after any `--sql` inline usage that succeeds but could be fragile: "💡 Tip: Use --sql @file.sql to avoid shell quoting issues"
- Consider adding a `--sql-stdin` autodetection: if `--sql` is not provided but stdin has data, automatically read from stdin
- Consider adding a `browser4-cli htmlsnapshot query --interactive-sql` that opens a temporary editor for composing queries

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: `htmlsnapshot inspect` suggests `DOM_FIRST_TEXT` for title but `title` attribute is more complete

**Severity:** Low

**Category:** Documentation

**Reproduction:**
```bash
cargo run -- htmlsnapshot get all text "h3 a"    # truncated titles: "A Light in the ..."
cargo run -- htmlsnapshot get all attr "h3 a" title  # full titles
```

**Expected:** The `htmlsnapshot inspect` output should note that `h3 a` text content is truncated and recommend using the `title` attribute for full values.

**Actual:** `inspect` shows `h3` with truncated text like `"A Light in the ..."` but doesn't suggest checking the `title` attribute of the child `<a>` tag for full text. Users might extract truncated data and not realize full titles are available.

**Root Cause:** `inspect` displays the visible text content of elements, which may be CSS-truncated. It doesn't analyze attribute-based alternatives for completeness.

**Code Pointer:** Server-side inspect logic, possibly in `DomInspectService` or similar.

**AI Suggested Improvement:**
- When inspect detects text truncation (ellipsis), add a note suggesting attribute-based alternatives
- In the suggested selectors list, show both `DOM_FIRST_TEXT` and `DOM_FIRST_ATTR` options when attributes contain richer data

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: `goto` silently upgrades HTTP to HTTPS without notification

**Severity:** Low

**Category:** UX

**Reproduction:**
```bash
cargo run -- goto "http://books.toscrape.com/"
```

**Expected:** Either preserve the user's protocol choice or clearly notify when an upgrade occurs.

**Actual:** `http://` is silently upgraded to `https://`. The output shows `Page URL: https://books.toscrape.com/` with no indication that the protocol was changed.

**Root Cause:** The navigation/load pipeline performs HTTP→HTTPS upgrade, likely as a security best practice. But the upgrade is not communicated to the user.

**Code Pointer:** Server-side URL handling or CDP navigation logic.

**AI Suggested Improvement:**
- Add a log/stderr message: "Upgraded http:// to https:// for security"
- Or add a `--no-upgrade` / `--allow-http` flag for users who explicitly need plain HTTP

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: `$cliInvocation` pattern is verbose and not discoverable

**Severity:** Low

**Category:** Discoverability

**Reproduction:** A new developer cloning the repo needs to figure out how to run the CLI from source.

**Expected:** The repo root `README.md` or a `CONTRIBUTING.md` should prominently explain how to run from source.

**Actual:** The invocation pattern (`cd cli/browser4-cli && cargo run -- <command>`) is documented in `skills/browser4-cli/SKILL.md` under "Development" but not in the repo root. A developer starting from the repo root has no obvious entry point. The `cli/browser4-cli/README.md` mentions `cargo build --release` and `cargo install --path .` but the "quick start" examples all use `browser4-cli` (the installed binary).

**Root Cause:** Documentation is scattered across multiple files with no clear "development quick start" at the repo root.

**AI Suggested Improvement:**
- Add a `CONTRIBUTING.md` or `DEVELOPMENT.md` at the repo root with a "Running from source" section
- Add a comment at the top of `cli/browser4-cli/README.md` directing source-based users to use `cargo run --`
- Consider adding a root-level script/alias (`./browser4-cli-dev` or `just b4`) for development convenience

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
**Fully completed** — all 8 task steps were successfully executed, with one workaround required (base64 input mode).

### Estimated Task Success Rate
**85%** — 7 of 8 steps worked on first attempt; 1 step (base64) required bug investigation and workaround discovery.

### Number of Issues Found
**7 issues:** 2 High (product bugs), 2 Medium (docs/UX), 3 Low (UX/discoverability)

### Major Blockers
1. **`--sql-base64` is completely broken** — neither documented usage mode (standalone value or boolean flag) works without a workaround. This is the most impactful bug as it affects transport-safe query delivery, the primary use case for base64 encoding.
2. **Boolean arg capture bug** — affects not just `--sql-base64` but also `swarm query` and `crawl` (same pattern at lines 2125 and 2330).

### Most Confusing Aspects
- Figuring out the `cargo run --` invocation pattern required reading multiple documentation files
- Understanding the four SQL input methods and when to use each required cross-referencing `cli/README.md` with `SKILL.md`
- The `--sql-base64` failure was especially confusing because the error message says "`--sql` is required" while also listing `--sql-base64` as a valid alternative — the error message contradicts itself

### What Worked Well
- **`htmlsnapshot inspect`** — auto-discovery of `.product_pod` was excellent. Zero manual CSS investigation needed
- **`htmlsnapshot get all`** — quick selector verification before writing X-SQL
- **`--sql @file.sql`** — by far the cleanest approach, no quoting issues
- **`--sql-stdin`** — seamless piping, works exactly as documented
- **`--result-only`** — clean JSON output, perfect for scripting
- **X-SQL `DOM_LOAD_AND_SELECT`** — powerful abstraction for correlated multi-field extraction
- **Session persistence** — `goto` auto-opened the session, `close` cleaned it up cleanly

### Most Valuable Improvements
1. **Fix `--sql-base64`** (both modes) — this is the only genuine bug blocking documented functionality
2. **Add examples to `--help` output** — a one-time user shouldn't need to find the README to see how to use a flag
3. **Add `DOM_FIRST_ATTR` tip to inspect output** — when text is truncated, suggest attribute extraction
4. **Unify "Running from source" documentation** — one clear section in one accessible location

### Overall Usability Rating: **7/10**

**Strengths:** The core workflow (navigate → snapshot → inspect → query) is intuitive and well-designed. `htmlsnapshot inspect` is a standout feature that eliminates the most painful part of web scraping (selector discovery). X-SQL with `DOM_LOAD_AND_SELECT` is elegantly simple for correlated extraction. Three of four SQL input methods work flawlessly.

**Weaknesses:** The broken `--sql-base64` is a significant reliability issue that undermines trust in the "transport-safe" input method. Documentation is thorough but fragmented across multiple files. The development workflow from source is under-documented at the repo root. Shell quoting on Windows remains a genuine friction point for inline queries, though the file/stdin/base64 alternatives are excellent mitigations when they work.
