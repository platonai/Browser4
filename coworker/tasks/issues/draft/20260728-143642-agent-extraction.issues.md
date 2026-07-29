# Issues: agent-extraction

> **Source:** `20260728-143642-agent-extraction.full.md` | **Date:** 20260728-143642 | **Mode:** dev

## Scenario Background

### Task

All synchronous operations worked well:
- **Navigation**: Instant, session reuse was seamless
- **Extract (inline schema)**: Returned all 5 requested fields correctly
- **Extract (custom file schema)**: Returned all 6 fields including the bonus `paradigm` field
- **Summarize (full page)**: Well-structured 5-point summary
- **Summarize with `--selector`**: Worked after discovering `[data-mw-section-id="1"]`

Agent workflow was non-functional:
- **Agent task 1** (`9797b06b`): Completed HTTP 200 in ~52s, `agent result` returned `{}`
- **Agent task 2** (`beea1916`): Same outcome; `--wait` flag did not block

### B. Key Issues (10 found)

| # | Severity | Category | Title |
|---|----------|----------|-------|
| 1 | **Critical** | Product | Agent tasks complete successfully but return empty `{}` results |
| 2 | **High** | Product | `agent run --wait` flag does not block |
| 3 | Medium | UX | Extract/summarize results wrapped in non-standard Java envelope |
| 4 | Medium | Discoverability | CSS selector discovery for `summarize --selector` requires raw JS |
| 5 | Medium | UX | No `@file` syntax support for `--schema` flag |
| 6 | Low | UX | Version mismatch warning in dev mode |
| 7 | Low | Product | `htmlsnapshot get` doesn't support `:has()` pseudo-selector |
| 8 | Low | UX | Agent polling requires manual loop |
| 9 | Low | Documentation | `agent result` output format is undocumented |
| 10 | Low | UX | Extract file output contains wrapper envelope, not raw data |

### C. Comparison: Synchronous vs Asynchronous

| Aspect | Synchronous (`extract`/`summarize`) | Asynchronous (`agent run`) |
|--------|-------------------------------------|---------------------------|
| **Speed** | Fast (~17-36s for extraction, ~20s for summarization) | Slow (~52-90s for same task scope) |
| **Output quality** | Good structured data or summaries | Unknown — results were empty |
| **Error handling** | Clear success/failure in response | Status polling required; no indication when empty result is a bug |
| **Suitable for** | Single-page extraction, summarization, data that fits one page | Multi-step navigation, form interaction, tasks requiring statefulness |
| **Complexity** | Low — one command, immediate result | High — submit, poll, retrieve (3+ commands) |
| **Reliability** | 100% success in this evaluation | 0% success (empty results) |

**Recommendation:** For single-page extraction/summarization, the synchronous commands are clearly superior — faster, simpler, and more reliable. Agent tasks should be reserved for multi-step workflows (navigate + interact + extract across pages) where the autonomous planning capability justifies the overhead.

### D. Overall Rating: **5/10**

The synchronous tooling is solid and productive. The critical agent bugs and UX rough edges (envelope wrapping, selector discovery, `--wait` not working) prevent a higher score. Fixing the top 3 issues would bring it to ~8/10.

Full report saved to `.test-sessions/evaluation-report.md`.

---

## Issues Found (0)

No issues could be parsed from Section C of the agent output.

See `20260728-143642-agent-extraction.full.md` for the complete evaluation output.

