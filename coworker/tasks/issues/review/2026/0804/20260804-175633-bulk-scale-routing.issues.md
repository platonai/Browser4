# Issues: bulk-scale-routing

> **Source:** `20260804-175633-bulk-scale-routing.full.md` | **Date:** 20260804-175633 | **Mode:** dev

## Scenario Background

### Task

All 6 acceptance criteria from SKILL.md §4b were completed, though 3 required workarounds:

| AC | Branch | Result |
|----|--------|--------|
| AC1 | Single list page → X-SQL | ✅ 6 product cards extracted with title+price+link |
| AC2 | Multiple known URLs → crawl | ✅ 3 product detail pages extracted correctly |
| AC3 | Crawl from start URL → link discovery | ⚠️ **Blocked by backend bug** — used manual link extraction workaround |
| AC4 | Parallel execution → swarm | ⚠️ **3 server crashes** — worked after restarts + query fix |
| AC5 | Repeated monitoring → loop | ⚠️ **-s flag dropped** — worked via session-default |
| AC6 | Few URLs → shell script | ✅ PowerShell loop over 3 URLs, correct titles extracted |

### Execution Context

**Key Commands:**

**Key workarounds:**
- AC3: Manually extracted links with `htmlsnapshot get all attr "a.product" href` → wrote seed file → used `crawl --depth 0`
- AC4: Removed `DOM_BASE_URI(DOM)` from X-SQL (incompatible with swarm); restarted server 3 times
- AC5: Used `session-default price-watch` instead of `-s price-watch` in loop subcommand

---

## Issues Found (0)

No issues could be parsed from Section C of the agent output.

See `20260804-175633-bulk-scale-routing.full.md` for the complete evaluation output.

