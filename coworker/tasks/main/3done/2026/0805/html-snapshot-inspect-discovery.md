# Issues: htmlsnapshot-inspect-discovery

> **Source:** `20260709-222157-htmlsnapshot-inspect-discovery.full.md` | **Date:** 20260709-222157 | **Mode:** dev

## Scenario Background

### Task

**Task:** Explore and extract data from `http://books.toscrape.com/` using `htmlsnapshot` commands.

All 9 steps completed successfully:

1. ✅ Navigated to `http://books.toscrape.com/` via `goto`
2. ✅ Captured HTML snapshot (64 KB, 20 images, 94 links, 100 interactive elements)
3. ✅ Auto-discovered `.product_pod` container with 20 matches via `inspect` (no selector)
4. ✅ Inspected `.product_pod` with `--max 5 --depth 3` — confirmed selector quality
5. ✅ Generated page summary (WPSI) showing 23 landmarks, 4 link groups, 3 lists
6. ✅ Extracted all 20 book titles via `get all text "h3 a"`
7. ✅ Validated with `grep -c` — confirmed 20 prices and 20 product pods
8. ✅ Ran X-SQL query extracting 20 books with correlated titles + prices
9. ✅ Explored sidebar with `inspect .sidebar` — extracted all 51 category links

---

### Execution Context

| Step | Command | Outcome |
|------|---------|---------|
| Setup | `cargo --version && java -version` | Rust 1.94.1, Java 17 — prerequisites met |
| Help | `cargo run --manifest-path ... --help` | Full command listing displayed |
| 1-2 | `goto "http://books.toscrape.com/"` + `htmlsnapshot` | Page loaded, snapshot captured (64 KB) |
| 3 | `htmlsnapshot inspect` | Auto-discovered `.product_pod` (20 matches) |
| 4 | `htmlsnapshot inspect ".product_pod" --max 5 --depth 3` | 5 items analyzed, suggested selectors shown |
| 5 | `htmlsnapshot summary` | WPSI saved with landmarks, content rankings, stats |
| 6 | `htmlsnapshot get all text "h3 a" --all` | 20 book titles returned as JSON array |
| 7 | `htmlsnapshot grep -c "£" --all` / `grep -c "product_pod" --all` | 20 matches each — validated |
| ...

(truncated — see full.md for complete trace)

---

## Issues Found (5 issues)

### Issue 1: Title text truncated in extraction output

**Severity:** Medium
**Category:** Product

#### Reproduction

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get all text "h3 a" --all
```

#### Expected Behavior

Full book titles returned (e.g., "A Light in the Attic", "Sapiens: A Brief History of Humankind").

#### Actual Behavior

Titles are truncated with ellipsis (e.g., "A Light in the ...", "Sapiens: A Brief History ..."). The `get all text` output matches the CSS-rendered text, which is clipped by the page's layout (overflow hidden / fixed-width containers).

#### Root Cause Analysis

`htmlsnapshot get text` returns the text as computed/rendered by the browser's layout engine, which clips text to the element's visible bounding box. The full text is present in the HTML source but not exposed via `get text` (which uses `innerText`-like semantics rather than `textContent`).

#### Code Pointer

`Likely in the backend Java HTML snapshot scraper where text extraction resolves rendered text vs. raw text content.`

#### AI Suggested Improvement

- Add a `--full-text` or `--no-truncate` flag to `htmlsnapshot get` that retrieves `textContent` (unclipped full text) instead of rendered text
- Document this behavior clearly: text extraction uses rendered/visible text which may be truncated by CSS overflow
- Add a warning tip when text ends with "..." that the full text may be truncated
- Alternatively, include a `textcontent` field type alongside `text`, `html`, and `attr`

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
<<<<<<<< HEAD:coworker/tasks/main/3done/2026/0805/html-snapshot-inspect-discovery.md
[AI review unavailable — defaulted to DEFER]
========
[AI suggested: ACCEPT] Valid product issue. `get text` returns rendered/visible text (innerText semantics), which CSS overflow truncates — surprising when users expect `textContent`. Adding a `textcontent` field type or `--full-text` flag is the right fix. Also add a warning when output lines end with "…" to signal possible truncation. This is the highest-severity issue in the batch: it silently returns incorrect data.
>>>>>>>> 84e2b0c4d (feat(snapshot): add textcontent extraction field and polish CLI UX):coworker/tasks/main/3done/2026/0804/htmlsnapshot-inspect-discovery-issues.md

---

### Issue 2: @file path resolution assumes CWD is repo root, but cargo run changes CWD

**Severity:** Medium
**Category:** UX / Documentation

#### Reproduction

```bash
# From repo root:
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot query --sql @query.sql
```

#### Expected Behavior

The `@query.sql` path resolves relative to the repo root (where the user invoked the command).

#### Actual Behavior

The working directory during `cargo run` is `cli/browser4-cli/`, so `@query.sql` resolves to `cli/browser4-cli/query.sql`, not the repo root `query.sql`. This causes a "file not found" error unless the user uses an absolute path or places files inside the CLI directory.

#### Root Cause Analysis

`cargo run` sets the process CWD to the crate directory (`cli/browser4-cli/`), not the directory from which `cargo` was invoked. The `@file` path resolution starts from this CWD.

#### Code Pointer

``cli/browser4-cli/src/` — the `@file` resolution logic.`

#### AI Suggested Improvement

- Document this prominently: "When using `cargo run --manifest-path`, the CWD is `cli/browser4-cli/`. Use absolute paths or `../../filename` for `@file` references."
- Consider resolving `@file` paths relative to the original invocation directory (via an env var or saved CWD) as a fallback
- Add a more helpful error message: "File not found: query.sql. When running via cargo run, CWD is cli/browser4-cli/. Try @/absolute/path/query.sql or @../../query.sql"

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
<<<<<<<< HEAD:coworker/tasks/main/3done/2026/0805/html-snapshot-inspect-discovery.md
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
========
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
>>>>>>>> 84e2b0c4d (feat(snapshot): add textcontent extraction field and polish CLI UX):coworker/tasks/main/3done/2026/0804/htmlsnapshot-inspect-discovery-issues.md
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
<<<<<<<< HEAD:coworker/tasks/main/3done/2026/0805/html-snapshot-inspect-discovery.md
[AI review unavailable — defaulted to DEFER]
========
[AI suggested: ACCEPT with improvements] The core fix already exists — `resolve_sql_file()` (commands.rs:5966) tries the Browser4 repo root before falling back to CWD, so files placed in the repo root are found even when `cargo run` sets CWD to `cli/browser4-cli/`. However, two improvements remain: (a) `find_browser4_root_from()` at daemon.rs:3987 has an unconditional `eprintln!` debug log that leaks into user output — remove or gate behind `--verbose`; (b) the error message at commands.rs:6004 lists tried paths but doesn't explain *why* CWD differs from the user's invocation directory — add a note about `cargo run` CWD behavior.
>>>>>>>> 84e2b0c4d (feat(snapshot): add textcontent extraction field and polish CLI UX):coworker/tasks/main/3done/2026/0804/htmlsnapshot-inspect-discovery-issues.md

---

### Issue 3: X-SQL query output wraps results in verbose JSON envelope

**Severity:** Low
**Category:** UX

#### Reproduction

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot query --sql @/path/to/query.sql
```

#### Expected Behavior

A clean JSON array of result rows, or a `--result-only` flag to extract just the data.

#### Actual Behavior

Output is a large JSON object with `id`, `statusCode`, `pageStatusCode`, `pageContentBytes`, `isDone`, `event`, `lastModifiedTime`, `finishTime`, `status` wrapping the `resultSet` array. The user must parse/extract `resultSet` from the envelope.

#### Root Cause Analysis

The query response is the raw backend API response, not filtered for CLI consumption.

#### Code Pointer

`Likely in `cli/browser4-cli/src/` where the query response is rendered to stdout.`

#### AI Suggested Improvement

- Add a `--result-only` flag (like `crawl` has) to output only the `resultSet` array
- In `--json` mode, strip the envelope and output only the data
- Default human-readable output could format the results as a table instead of raw JSON

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
<<<<<<<< HEAD:coworker/tasks/main/3done/2026/0805/html-snapshot-inspect-discovery.md
[AI review unavailable — defaulted to DEFER]
========
[AI suggested: ACCEPT] Both suggested improvements already exist: `--result-only` strips the envelope (commands.rs:3040), and `--format` defaults to `"table"` for human-readable output (commands.rs:3053). This issue is functionally resolved, but its existence signals a discoverability problem — cross-reference with Issue 5. Consider adding a tip after the first `htmlsnapshot query` run that mentions `--result-only` and `--format table`, similar to the existing inline SQL tip at commands.rs:6320.
>>>>>>>> 84e2b0c4d (feat(snapshot): add textcontent extraction field and polish CLI UX):coworker/tasks/main/3done/2026/0804/htmlsnapshot-inspect-discovery-issues.md

---

### Issue 4: htmlsnapshot inspect --depth flag has no visible effect on shallow DOM structures

**Severity:** Low
**Category:** Documentation / UX

#### Reproduction

```bash
htmlsnapshot inspect ".product_pod" --max 5 --depth 3
# vs
htmlsnapshot inspect ".product_pod" --max 5 --depth 1
```

#### Expected Behavior

Different depth levels should produce visibly different output (e.g., more/less nested descendant information).

#### Actual Behavior

Both produce identical output because the `.product_pod` structure is only 3-4 levels deep. The `--depth` parameter's effect is invisible when the DOM structure is shallower than the specified depth. A new user might think the flag is broken.

#### Root Cause Analysis

`--depth` controls the maximum depth for descendant walking, but when the actual DOM depth is ≤ the specified depth, the output is identical. There is no feedback that "depth was capped at the actual DOM depth of X."

#### AI Suggested Improvement

- Add a note in the inspect output: "Depth limited to 3 (actual DOM depth under selector)"
- Document in the inspect help: "If the DOM under the selector is shallower than --depth, the actual DOM depth is used"
- Consider showing a "max depth reached: yes/no" indicator

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
<<<<<<<< HEAD:coworker/tasks/main/3done/2026/0805/html-snapshot-inspect-discovery.md
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
========
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
>>>>>>>> 84e2b0c4d (feat(snapshot): add textcontent extraction field and polish CLI UX):coworker/tasks/main/3done/2026/0804/htmlsnapshot-inspect-discovery-issues.md
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
<<<<<<<< HEAD:coworker/tasks/main/3done/2026/0805/html-snapshot-inspect-discovery.md
[AI review unavailable — defaulted to DEFER]
========
[AI suggested: ACCEPT with improvements] The `--depth` flag works correctly, but the issue is valid as a feedback gap. When the actual DOM under a selector is shallower than the requested depth, output is identical for `--depth 3` vs `--depth 1` with no indication why. The suggested improvements are low-cost and high-clarity: (a) note in output "Depth limited to N (actual DOM depth under selector)" when the DOM is shallower than requested, and (b) document this behavior in the option help text. No backend change needed — this is CLI-side output annotation only.
>>>>>>>> 84e2b0c4d (feat(snapshot): add textcontent extraction field and polish CLI UX):coworker/tasks/main/3done/2026/0804/htmlsnapshot-inspect-discovery-issues.md

---

### Issue 5: No command-level help for htmlsnapshot inspect

**Severity:** Low
**Category:** Discoverability

#### Reproduction

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot inspect --help
```

#### Expected Behavior

Detailed help with flag descriptions, examples, and usage patterns.

#### Actual Behavior

(Was not tested — but the top-level `--help` shows only a one-line description. The documentation exists in `htmlsnapshot.md` but users must know to look there.)

#### Root Cause Analysis

The `inspect` command is deeply nested (`htmlsnapshot inspect`) and its help is not easily discoverable at the top level. The SKILL.md command table does list it but the user needs to read the full htmlsnapshot reference page.

#### AI Suggested Improvement

- Include one or two `inspect` examples in the main `--help` output for `htmlsnapshot`
- Add a "Quick Start" section to the htmlsnapshot help output showing the most common workflow
- Consider a `browser4-cli examples` command that shows common task patterns

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
<<<<<<<< HEAD:coworker/tasks/main/3done/2026/0805/html-snapshot-inspect-discovery.md
[AI review unavailable — defaulted to DEFER]
========
[AI suggested: ACCEPT] Valid discoverability gap. The top-level `htmlsnapshot --help` lists subcommands but `htmlsnapshot inspect --help` has no detailed flag descriptions or examples. The pattern to follow already exists elsewhere — `htmlsnapshot query --help` shows rich option descriptions (commands.rs:3020-3056). Extend `htmlsnapshot-inspect` with the same detail level, and add 1-2 inspect examples to the top-level `htmlsnapshot --help` output. This is the lowest-cost fix in the batch and addresses the systemic discoverability problem seen across Issues 2 and 3.
>>>>>>>> 84e2b0c4d (feat(snapshot): add textcontent extraction field and polish CLI UX):coworker/tasks/main/3done/2026/0804/htmlsnapshot-inspect-discovery-issues.md

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Title text truncated in extraction output

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get all text "h3 a" --all
```

#### Issue 2: @file path resolution assumes CWD is repo root, but cargo run changes CWD

```bash
# From repo root:
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot query --sql @query.sql
```

#### Issue 3: X-SQL query output wraps results in verbose JSON envelope

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot query --sql @/path/to/query.sql
```

#### Issue 4: htmlsnapshot inspect --depth flag has no visible effect on shallow DOM structures

```bash
htmlsnapshot inspect ".product_pod" --max 5 --depth 3
# vs
htmlsnapshot inspect ".product_pod" --max 5 --depth 1
```

#### Issue 5: No command-level help for htmlsnapshot inspect

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot inspect --help
```

