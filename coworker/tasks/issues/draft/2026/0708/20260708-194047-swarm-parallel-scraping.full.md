---

## Evaluation Complete

The swarm evaluation is done. Here's a summary of what happened:

### Task Summary

| Step | Description | Outcome |
|------|-------------|---------|
| 1 | Create swarm session (HEADLESS, 2 contexts, 4 tabs) | ✅ Success |
| 2 | Create seed file (10 URLs) | ✅ Success |
| 3 | Write X-SQL extraction query | ✅ Success (fixed `DOM_FIRST_IMG`→`DOM_FIRST_ATTR`) |
| 4 | Submit extraction jobs via `swarm query` | ✅ 10 jobs submitted |
| 5 | Submit plain scrape via `swarm submit` | ✅ 1 job submitted |
| 6 | Poll statuses | ⚠️ `isDone` always `null`, `--wait` times out |
| 7 | Retrieve results | ⚠️ First batch: 8/10 had partial data; second batch: 0/10 |
| 8 | List swarm tasks | ✅ 21 tasks shown, all "pending" |
| 9 | Close swarm session | ✅ Released |

### Key Findings

**3 bugs that block the intended workflow:**
1. **`isDone` never becomes `true`** — breaks polling and the `--wait` flag
2. **X-SQL extraction is flaky in swarm** — same query works in `htmlsnapshot query` but produces empty results inconsistently in `swarm query`
3. **`swarm list` always shows "pending"** — even for completed jobs with retrievable results

**2 documentation/UX gaps:**
4. `DOM_FIRST_IMG` doesn't extract `<img src>` — need `DOM_FIRST_ATTR` instead (undocumented gotcha)
5. No batch status/result commands — must poll each of 21 tasks individually

Full report with 8 structured issues, execution trace, and root cause analysis is at `eval-report-swarm.md`.
