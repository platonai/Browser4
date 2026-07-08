# Issues: x-sql-query-methods

> **Source:** `20260706-220651-x-sql-query-methods.full.md` | **Date:** 20260706-220651 | **Mode:** dev

## Scenario Background

### Task

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

### Execution Context

**Key Commands:**

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

**Workarounds Applied During Task:**

```
cargo run -- htmlsnapshot query --sql "dummy" "--sql-base64=$(base64 -w0 extract_books.sql)"
cargo run -- htmlsnapshot query --sql @extract_books.sql --result-only
cargo run -- close
```

---

---

## Issues Found (7 issues)
> **Review complete:** 5 approved, 2 deferred/rejected

### Issue 1: `--sql-base64` standalone (Mode 1) fails — validation rejects it before base64 decode

**Severity:** High
**Category:** Product

#### Reproduction

```bash
cargo run -- htmlsnapshot query --sql-base64 "$(base64 -w0 query.sql)"
cargo run -- htmlsnapshot query "--sql-base64=$(base64 -w0 query.sql)"
```

#### Expected Behavior

The CLI should decode the base64 value and execute the query without requiring `--sql`.

#### Actual Behavior

Error: `--sql is required. Provide an inline X-SQL query, @file.sql, --sql-stdin, or --sql-base64.`

#### Root Cause Analysis

In `main.rs:3819`, the empty-check on `sql_raw` runs before the base64 decode at line 3835. When `--sql-base64 <value>` is used without `--sql`, `sql_raw` is empty and the early return fires. The check doesn't account for `sqlBase64` providing the SQL independently.

#### Code Pointer

``cli/browser4-cli/src/main.rs:3819` — `handle_html_snapshot_query()` function, the `sql_raw.is_empty()` check should also consider `tool_params["sqlBase64"]`.`

#### AI Suggested Improvement

- Move the empty-SQL validation to after the base64 decode, or add a guard that checks `tool_params.get("sqlBase64").and_then(|v| v.as_str())` before returning the error
- The same bug exists at line 6260 for the `swarm query` handler

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

---

### Issue 2: `--sql-base64` boolean flag mode (Mode 2) not captured in tool_params

**Severity:** High
**Category:** Product

#### Reproduction

```bash
cargo run -- htmlsnapshot query --sql "$(base64 -w0 query.sql)" --sql-base64
```

#### Expected Behavior

The CLI should detect `--sql-base64` as a boolean flag, decode the `--sql` value as base64, and execute the decoded query.

#### Actual Behavior

Server returns HTTP 400 Bad Request because the raw base64 string is sent as SQL without decoding.

#### Root Cause Analysis

In `commands.rs:2029`, the `tool_params_fn` for `htmlsnapshot query` only captures `sql-base64` via `get_opt_str()` which returns `None` for boolean values. When `--sql-base64` is passed as a boolean flag (no value), the args parser sets it to `Value::Bool(true)`, but `get_opt_str` uses `v.as_str()` which returns `None` for booleans. The boolean is silently dropped from `tool_params`, so `maybe_decode_base64_sql` never activates Mode 2. Compare with `sql-stdin` handling at line 2028 which correctly uses `get_bool()`.

#### Code Pointer

``cli/browser4-cli/src/commands.rs:2029` — `htmlsnapshot query` tool_params_fn; also line 2125 for `swarm query`, line 2330 for `crawl`.`

#### AI Suggested Improvement

- Add a boolean fallback after the string check: `if let Some(v) = get_opt_str(args, "sql-base64") { p["sqlBase64"] = json!(v); } else if get_bool(args, "sql-base64").unwrap_or(false) { p["sqlBase64"] = json!(true); }`
- Apply the same fix to all three occurrences (htmlsnapshot query, swarm query, crawl)

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

---

### Issue 3: `htmlsnapshot query --help` and `help htmlsnapshot query` omit usage examples

**Severity:** Medium
**Category:** Documentation

#### Reproduction

```bash
cargo run -- htmlsnapshot query --help
cargo run -- help htmlsnapshot query
```

#### Expected Behavior

Both help outputs should include usage examples showing all four SQL input methods (`--sql`, `--sql @file`, `--sql-stdin`, `--sql-base64`) and `--result-only`.

#### Actual Behavior

Both show only the option listing (name + description) with no examples. Users must discover usage patterns from `cli/README.md` or the extended help text which is only accessible through a separate code path.

#### Root Cause Analysis

The `--help` flag for subcommands doesn't include examples. The extended examples only appear in `browser4-cli help htmlsnapshot query` (the `help` command, not the `--help` flag), but even that variant doesn't show all four input methods with concrete examples.

#### Code Pointer

``cli/browser4-cli/src/help.rs` — `htmlsnapshot_query_extended_help()` at approximately line 920.`

#### AI Suggested Improvement

- Add a concise "Examples:" section at the bottom of the `--help` output showing one example per input method
- Ensure the extended help covers all four input methods with working command lines
- Fix the documented base64 example at help.rs:935 which shows a mode that doesn't currently work

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

---

### Issue 4: Inline `--sql` shell quoting on Windows is fragile

**Severity:** Medium
**Category:** UX

#### Reproduction

Using `--sql "SELECT ... 'selector' ..."` on Windows (bash or cmd/PowerShell).

#### Expected Behavior

Consistent, predictable quoting behavior regardless of shell.

#### Actual Behavior

On Windows bash (Git Bash/MSYS2), single quotes inside double quotes work but the shell may mangle them — the `cargo run` output showed convoluted escaping: `'\''h3 a'\'', '\''title'\''`. On cmd.exe or PowerShell, the quoting would be even more problematic. The documentation (SKILL.md) correctly warns about this, but first-time users may still hit it.

#### Root Cause Analysis

Shell quoting is inherently platform/shell-specific. The CLI handles the SQL string correctly once it arrives, but getting it through the shell is the challenge.

#### Code Pointer

`N/A — this is a documentation/UX issue, not a code bug.`

#### AI Suggested Improvement

- Add a prominent tip after any `--sql` inline usage that succeeds but could be fragile: "💡 Tip: Use --sql @file.sql to avoid shell quoting issues"
- Consider adding a `--sql-stdin` autodetection: if `--sql` is not provided but stdin has data, automatically read from stdin
- Consider adding a `browser4-cli htmlsnapshot query --interactive-sql` that opens a temporary editor for composing queries

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

---

### Issue 7: `$cliInvocation` pattern is verbose and not discoverable

**Severity:** Low
**Category:** Discoverability

#### Reproduction

A new developer cloning the repo needs to figure out how to run the CLI from source.

#### Expected Behavior

The repo root `README.md` or a `CONTRIBUTING.md` should prominently explain how to run from source.

#### Actual Behavior

The invocation pattern (`cd cli/browser4-cli && cargo run -- <command>`) is documented in `skills/browser4-cli/SKILL.md` under "Development" but not in the repo root. A developer starting from the repo root has no obvious entry point. The `cli/browser4-cli/README.md` mentions `cargo build --release` and `cargo install --path .` but the "quick start" examples all use `browser4-cli` (the installed binary).

#### Root Cause Analysis

Documentation is scattered across multiple files with no clear "development quick start" at the repo root.

#### AI Suggested Improvement

- Add a `CONTRIBUTING.md` or `DEVELOPMENT.md` at the repo root with a "Running from source" section
- Add a comment at the top of `cli/browser4-cli/README.md` directing source-based users to use `cargo run --`
- Consider adding a root-level script/alias (`./browser4-cli-dev` or `just b4`) for development convenience

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

---

### Issue 5: `htmlsnapshot inspect` suggests `DOM_FIRST_TEXT` for title but `title` attribute is more complete

**Severity:** Low
**Category:** Documentation

#### Review Result

**Decision:** DEFER

**Summary:** - When inspect detects text truncation (ellipsis), add a note suggesting attribute-based alternatives

---

### Issue 6: `goto` silently upgrades HTTP to HTTPS without notification

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add a log/stderr message: "Upgraded http:// to https:// for security"

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: `--sql-base64` standalone (Mode 1) fails — validation rejects it before base64 decode

```bash
cargo run -- htmlsnapshot query --sql-base64 "$(base64 -w0 query.sql)"
cargo run -- htmlsnapshot query "--sql-base64=$(base64 -w0 query.sql)"
```

#### Issue 2: `--sql-base64` boolean flag mode (Mode 2) not captured in tool_params

```bash
cargo run -- htmlsnapshot query --sql "$(base64 -w0 query.sql)" --sql-base64
```

#### Issue 3: `htmlsnapshot query --help` and `help htmlsnapshot query` omit usage examples

```bash
cargo run -- htmlsnapshot query --help
cargo run -- help htmlsnapshot query
```

#### Issue 4: Inline `--sql` shell quoting on Windows is fragile

Using `--sql "SELECT ... 'selector' ..."` on Windows (bash or cmd/PowerShell).

#### Issue 5: `htmlsnapshot inspect` suggests `DOM_FIRST_TEXT` for title but `title` attribute is more complete

```bash
cargo run -- htmlsnapshot get all text "h3 a"    # truncated titles: "A Light in the ..."
cargo run -- htmlsnapshot get all attr "h3 a" title  # full titles
```

#### Issue 6: `goto` silently upgrades HTTP to HTTPS without notification

```bash
cargo run -- goto "http://books.toscrape.com/"
```

#### Issue 7: `$cliInvocation` pattern is verbose and not discoverable

A new developer cloning the repo needs to figure out how to run the CLI from source.

#auto-approve
