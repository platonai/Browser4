# Issues: html-snapshot-extraction

> **Source:** `20260706-202635-html-snapshot-extraction.full.md` | **Date:** 20260706-202635 | **Mode:** dev

## Scenario Background

### Task

| Step | Command | Result |
|------|---------|--------|
| 1-2 | `goto` + `htmlsnapshot` | Page loaded, 65KB snapshot with 20 images, 94 links, 100 interactive elements |
| 3 | `get text "h3 a"` | "A Light in the ..." (⚠️ truncated) |
| 4 | `get html "article.product_pod"` | Full product card HTML with `vi` bounding boxes |
| 5 | `get attr "h3 a" href` | `catalogue/a-light-in-the-attic_1000/index.html` |
| 6 | `get all text "h3 a"` | 20 titles as JSON array |
| 7 | `get all text "h3 a" --offset 5 --limit 5` | Titles 6-10 returned correctly |
| 8 | `export --file books-snapshot.html` | Valid HTML exported |
| 9 | `summary` | WPSI with 23 landmarks, 4 link groups, 3 lists |
| 10 | `grep -c "price"` | 40 matching lines |

### Issues Found: 8

| # | Severity | Category | Summary |
|---|----------|----------|---------|
| 1 | **High** | Product | Text extraction truncates content with ellipsis — "A Light in the Attic" becomes "A Light in the ..." |
| 2 | Medium | UX | Shell CWD drift breaks subsequent commands when using relative `cd` paths |
| 3 | Medium | Product | WPSI summary fills content slots with 20 boilerplate buttons instead of book titles |
| 4 | Low | Docs/UX | `grep -c` counts lines not occurrences — no flag for total match count |
| 5 | Low | Documentation | Reference doc paths don't match actual filesystem layout |
| 6 | Low | UX | `goto` auto-snapshot vs `htmlsnapshot` prerequisite is confusing for new users |
| 7 | Low | Discoverability | Help output missing pagination examples for `get all` |
| 8 | Low | Discoverability | `--json` behavior with `get all` not documented |

### Overall Rating: **7/10**

**Strong points:** `htmlsnapshot inspect` auto-discovery is excellent, the `get` → `get all` → `query` pipeline is coherent, documentation is thorough. **Main weakness:** the text truncation issue (Issue 1) is a data-integrity problem that silently returns incomplete data.

Full reports written to:
- `coworker/tasks/issues/draft/20260707-htmlsnapshot-evaluation.full.md`
- `coworker/tasks/issues/draft/20260707-htmlsnapshot-evaluation.issues.md`

---

## Issues Found (0)

No issues could be parsed from Section C of the agent output.

See `20260706-202635-html-snapshot-extraction.full.md` for the complete evaluation output.

