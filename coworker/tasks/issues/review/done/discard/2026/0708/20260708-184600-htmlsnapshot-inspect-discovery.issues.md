# Issues: htmlsnapshot-inspect-discovery

> **Source:** `20260708-184600-htmlsnapshot-inspect-discovery.full.md` | **Date:** 20260708-184600 | **Mode:** dev

## Scenario Background

### Task

All 9 sub-tasks completed successfully. The htmlsnapshot workflow on books.toscrape.com produced:

| Extraction | Count | Method |
|---|---|---|
| Book titles (full) | 20 | `get all attr "h3 a" title` |
| Book prices | 20 | `get all text "p.price_color"` |
| Sidebar categories | 51 | `get all text ".sidebar li a"` |
| X-SQL correlated | 20 rows | `query --sql @file.sql` with `DOM_LOAD_AND_SELECT` |

## Issues Found: 7 (0 Critical, 0 High, 2 Medium, 5 Low)

**Medium severity:**
1. `get all text` returns CSS-truncated text; users must independently discover `title` attributes
2. `grep --selector` uses querySelector (single-element) scope, which is surprising

**Low severity:**
3. Session reconnect message references old URL, confusing on first use
4. Inspect sample structure prunes nested children at display time
5. X-SQL query output wraps results in verbose API envelope
6. `cargo run` has ~0.5s compile-check overhead per invocation
7. `:expr()` PowerCSS selectors in inspect output lack inline explanation

## Overall Rating: **8/10**

The htmlsnapshot inspect → get → query pipeline is well-designed, reliable, and well-documented. The auto-discovery feature is excellent. Primary improvement areas are UX polish (proactive tips, clearer scope documentation, cleaner query output).

The full evaluation report is at `eval-report-htmlsnapshot.md`.

---

## Issues Found (0)

No issues could be parsed from Section C of the agent output.

See `20260708-184600-htmlsnapshot-inspect-discovery.full.md` for the complete evaluation output.

