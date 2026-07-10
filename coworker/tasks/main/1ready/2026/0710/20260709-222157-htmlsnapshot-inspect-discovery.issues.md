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
> **Review complete:** 0 approved, 5 deferred/rejected

### Issue 1: Title text truncated in extraction output

**Severity:** Medium
**Category:** Product

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add a `--full-text` or `--no-truncate` flag to `htmlsnapshot get` that retrieves `textContent` (unclipped full text) instead of rendered text

---

### Issue 2: @file path resolution assumes CWD is repo root, but cargo run changes CWD

**Severity:** Medium
**Category:** UX / Documentation

#### Review Result

**Decision:** WONTFIX

**Summary:** - Document this prominently: "When using `cargo run --manifest-path`, the CWD is `cli/browser4-cli/`. Use absolute paths or `../../filename` for `@file` references."

---

### Issue 3: X-SQL query output wraps results in verbose JSON envelope

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add a `--result-only` flag (like `crawl` has) to output only the `resultSet` array

---

### Issue 4: htmlsnapshot inspect --depth flag has no visible effect on shallow DOM structures

**Severity:** Low
**Category:** Documentation / UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add a note in the inspect output: "Depth limited to 3 (actual DOM depth under selector)"

---

### Issue 5: No command-level help for htmlsnapshot inspect

**Severity:** Low
**Category:** Discoverability

#### Review Result

**Decision:** WONTFIX

**Summary:** - Include one or two `inspect` examples in the main `--help` output for `htmlsnapshot`

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
