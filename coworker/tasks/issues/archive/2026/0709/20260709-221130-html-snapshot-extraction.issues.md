# Issues: html-snapshot-extraction

> **Source:** `20260709-221130-html-snapshot-extraction.full.md` | **Date:** 20260709-221130 | **Mode:** dev

## Scenario Background

### Task

| # | Task | Result |
|---|------|--------|
| 1 | Navigate to books.toscrape.com | ✅ Loaded successfully |
| 2 | Capture HTML snapshot | ✅ 64 KB, 20 images, 94 links |
| 3 | Get first book title | ✅ `"A Light in the ..."` |
| 4 | Get first product container HTML | ✅ Full inner HTML returned |
| 5 | Get first book link href | ✅ `catalogue/a-light-in-the-attic_1000/index.html` |
| 6 | Get all book titles | ✅ 20 titles as JSON array |
| 7 | Paginate titles 6–10 | ✅ `--offset 5 --limit 5` worked correctly |
| 8 | Export to HTML file | ✅ 45,810 bytes, 476 lines |
| 9 | Generate page summary | ✅ WPSI with landmarks, link groups, stats |
| 10 | Grep count "price" | ✅ 40 matching lines |

## Issues Found: 7

- **2 Medium**: Text truncation (known, persists), overwhelming main `--help`
- **5 Low**: Goto reconnection message confusing, cargo output clutter, `get html` = innerHTML ambiguity, selector discovery friction, `grep -c` line-count vs occurrence-count semantics

## Key Strengths
- Subcommand help (`htmlsnapshot --help`) is **excellent** — thorough examples, clear notes
- All commands worked reliably on first attempt, no retries needed
- `--offset`/`--limit` pagination is intuitive
- `htmlsnapshot summary` (WPSI) provides genuinely useful page structure overview
- Documentation (SKILL.md, htmlsnapshot.md) is well-structured and actionable

## Overall Rating: **8/10**

The full evaluation report is saved to `eval-report-htmlsnapshot-extraction.md`.

---

## Issues Found (0)

No issues could be parsed from Section C of the agent output.

See `20260709-221130-html-snapshot-extraction.full.md` for the complete evaluation output.

