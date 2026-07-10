# Issues: crawl-advanced-extraction

> **Source:** `20260708-173325-crawl-advanced-extraction.full.md` | **Date:** 20260708-173325 | **Mode:** dev

## Scenario Background

### Task

- **6/10 steps** completed successfully
- **4 steps** degraded by empty results, stuck tasks, or misleading status messages

### Key Findings (13 issues)

| # | Severity | Issue |
|---|----------|-------|
| 1 | **High** | Each page load takes ~100s — 3 pages = 5+ minutes |
| 2 | Medium | "Still waiting for crawl to start" message is misleading (crawl HAS started) |
| 3 | **High** | X-SQL extraction returns empty results (417 Expectation Failed) |
| 4 | **High** | Page titles are empty in all crawl output |
| 5 | **High** | CLI task list persists stale data across backend restarts |
| 6 | Medium | No `crawl cancel` or `crawl clear` command |
| 7 | Medium | `--readonly` crawls complete instantly while normal crawls take 5+ minutes |
| 8 | Medium | Old tasks from previous sessions never cleaned up |
| 9 | Low | X-SQL function name casing inconsistent across docs |
| 10 | Low | Shell quoting issues with inline X-SQL |
| 11 | Low | `crawl list` shows confusing individual page tasks |
| 12 | Medium | Background tasks can't complete after backend restart |
| 13 | Low | Cargo file lock contention in dev mode |

### Overall Rating: **4/10**

The CLI has a solid conceptual design and good documentation structure, but the implementation has critical reliability issues:
- **X-SQL extraction is broken** — returns 417 on simple localhost pages
- **Page titles are missing** — basic crawl metadata is empty
- **Performance is prohibitive** — 100s per page makes crawl unusable
- **State management is fragile** — CLI and backend diverge after restarts

The full evaluation report is at `eval-report.md`.

---

## Issues Found (0)

No issues could be parsed from Section C of the agent output.

See `20260708-173325-crawl-advanced-extraction.full.md` for the complete evaluation output.

