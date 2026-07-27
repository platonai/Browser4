# Issues: crawl-sql-formats

> **Source:** `20260727-070605-crawl-sql-formats.full.md` | **Date:** 20260727-070605 | **Mode:** dev

## Scenario Background

### Task

Both acceptance criteria passed after fixing a critical bug and applying a workaround:

| Criteria | Status | Output |
|----------|--------|--------|
| **AC5** — `--sql @file --format csv` | ✅ PASS | `$10.00,Widget Alpha` / `$20.00,Widget Beta` |
| **AC6** — `--sql-stdin --format table` | ✅ PASS | Identical content, table-format display |

### Key Finding: 2 Bugs Block Crawl X-SQL

1. **Critical Bug — `CrawlToolExecutor` drops `sql`/`urls` params** (`browser4-rest/.../CrawlToolExecutor.kt:70`): The MCP tool submit handler never extracts `sql` or `urls` from args, so `CrawlRequest` always has `sql=null` and only processes the positional URL. **Fix:** Added `paramString("sql")` and `paramStringList("urls")` extraction. Patch saved to `.test-sessions/crawl-tool-executor-fix.patch`.

2. **High — `load_and_select` requires cached pages**: The H2 UDF can't fetch pages that haven't been loaded into the WebDB cache via `goto` + `htmlsnapshot`. Without pre-loading, all X-SQL rows are empty. The `-parse -refresh` options in the SQL URL help but aren't sufficient alone.

### 8 Issues Found

Critical: 1 | High: 4 | Medium: 2 | Low: 1

**Biggest issues:** Broken crawl X-SQL (MCP tool bug), bash invocation friction (PowerShell arg parsing), silent failures in both CLI and backend.

### Overall Rating: **3/10**

The crawl X-SQL feature is functionally broken out of the box. With fixes applied, it works but requires undocumented pre-loading steps and careful argument quoting. Full report: `.test-sessions/evaluation-report.md`.

---

## Issues Found (0)

No issues could be parsed from Section C of the agent output.

See `20260727-070605-crawl-sql-formats.full.md` for the complete evaluation output.

