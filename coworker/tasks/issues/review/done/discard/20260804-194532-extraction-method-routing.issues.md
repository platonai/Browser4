# Issues: extraction-method-routing

> **Source:** `20260804-194532-extraction-method-routing.full.md` | **Date:** 20260804-194532 | **Mode:** dev

## Scenario Background

### Task

All 7 acceptance criteria covering every branch of SKILL.md §4a (Choosing an Extraction Method) were evaluated:

- **AC1–AC6: ✅ Fully successful** — Using the documented tools as specified (snapshot+eval, htmlsnapshot get, htmlsnapshot get all, htmlsnapshot query with X-SQL, eval --json, extract)
- **AC7: ⚠️ Partial** — Both `crawl` and `swarm` failed with reliability issues; bulk extraction demonstrated successfully via the documented shell-loop fallback

### Key Findings

| Severity | Count | Most Critical |
|----------|-------|---------------|
| Critical | 1 | `crawl` hangs indefinitely — 0 pages processed |
| High | 2 | `swarm` ~100x too slow; CLI invocation docs conflict |
| Medium | 5 | Stale htmlsnapshot no runtime warning; extract double-JSON-encoded; CSS quoting broken; missing staleness guard |
| Low | 4 | Compile warnings; selector variability; --quiet ineffective; price concatenation |

### What Worked Well
- **htmlsnapshot inspect** — excellent selector discovery tool, correctly identified `div.product-title` at 100% coverage
- **X-SQL DOM_LOAD_AND_SELECT** — powerful correlated extraction, 6 rows perfectly aligned
- **eval --json --file** — reliable live-DOM access when htmlsnapshot is stale
- **extract** — LLM extraction worked with correct product data (just needs output format cleanup)
- **Decision tree §4a** — accurately predicted every extraction path; the documentation was correct in all cases
- **Shell-loop fallback** — worked perfectly as documented alternative when bulk tools failed

### Deliverables

- Full report: `.test-sessions/evaluation-report.md`
- Machine-readable issues: `.test-sessions/issues.json`
- All test artifacts in `.test-sessions/`

---

## Issues Found (0)

No issues could be parsed from Section C of the agent output.

See `20260804-194532-extraction-method-routing.full.md` for the complete evaluation output.

