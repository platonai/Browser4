# Issues: extraction-method-routing

> **Source:** `20260804-184806-extraction-method-routing.full.md` | **Date:** 20260804-184806 | **Mode:** dev

## Scenario Background

### Task

| AC | Description | Status |
|----|-------------|--------|
| AC1 | Interact first, then extract form results | ✅ Success |
| AC2 | Static page, single field extraction | ✅ Success |
| AC3 | Static page, all-matches extraction | ✅ Success |
| AC4 | X-SQL correlated multi-field rows | ⚠️ Partial — X-SQL broken; `eval` workaround |
| AC5 | Dynamic page `eval --json` | ✅ Success |
| AC6 | Natural-language extraction | ✅ Success (backend LLM configured) |
| AC7 | High-volume crawl with X-SQL | ⚠️ Partial — 3/5 pages; flaky |

### Issues Found: 8

**Critical (1):** `htmlsnapshot query` (X-SQL) is **completely broken** — every attempt fails with "scrape session closed before query could execute." This blocks the primary documented path for structured single-page extraction.

**High (1):** Crawl extraction is **flaky** — 2/5 pages on first run, 3/5 on retry. Truncated content for some pages.

**Medium (3):** Misleading documentation about `htmlsnapshot` staleness, `inspect` discovers wrong patterns on detail pages, Windows shell quoting complexity.

**Low (3):** `extract` output format is Java serialization, excessive terminal noise from auto-snapshots, no per-page progress during crawl.

### Key Strengths Observed
- `htmlsnapshot inspect` is excellent for selector discovery on listing pages
- `eval --json` with `--file` is a reliable escape hatch for complex extraction
- The decision tree in SKILL.md §4a provides clear guidance
- Form interaction with refs works well when following the re-snapshot pattern
- `extract` worked even without explicit LLM key (backend-configured)

### Overall Usability Rating: **6/10**

The tool has a solid foundation with well-designed extraction primitives, but reliability issues with X-SQL and crawl scraping significantly undermine confidence. The documentation is comprehensive but contains one critical inaccuracy about `htmlsnapshot` behavior. The Windows shell quoting issue, while documented, adds friction to every complex query.

**Full findings written to:** `.test-sessions/findings.json` and `.test-sessions/evaluation-report.md`

---

## Issues Found (0)

No issues could be parsed from Section C of the agent output.

See `20260804-184806-extraction-method-routing.full.md` for the complete evaluation output.

