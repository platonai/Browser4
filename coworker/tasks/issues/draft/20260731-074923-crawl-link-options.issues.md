# Issues: crawl-link-options

> **Source:** `20260731-074923-crawl-link-options.full.md` | **Date:** 20260731-074923 | **Mode:** dev

## Scenario Background

### Task

**Partially Successful.** Only AC1 (basic crawl, depth 0) passed fully. AC2 and AC3 are blocked by a critical server-side Spring bean missing error (`TaskLoops`). AC4 (seed file crawl) completed but with data quality issues.

### Summary:
- **AC1 (depth 0):** ✅ PASSED — 1 page found, correct title "Crawl Test Hub"
- **AC2 (link selector + pattern, depth 2):** ❌ BLOCKED — Server throws `NoSuchBeanDefinitionException: TaskLoops` for any depth >= 1 crawl
- **AC3 (deep crawl, depth 3):** ❌ BLOCKED — Same server-side `TaskLoops` bean issue
- **AC4 (seed file):** ⚠️ PARTIAL — 2 URLs resolved, 2 pages found, but page titles are empty and one page had consistent 0-byte fetch errors

### Execution Context

| Step | Command | Outcome |
|------|---------|---------|
| Prep | `./b4w.ps1 help` | Full help displayed, discovered crawl command and flags |
| Prep | Read `skills/browser4-cli/SKILL.md` | Learned workflows, command map, crawl reference |
| Prep | Read `skills/browser4-cli/references/crawl.md` | Learned crawl flags, modes, patterns |
| Prep | `./b4w.ps1 crawl --help` | Confirmed flag syntax |
| AC1 | `./b4w.ps1 crawl "http://localhost:18080/generated/crawl/index.html" --depth 0 --refresh` | ✅ 1 page found, "Crawl Test Hub" |
| AC2 | `./b4w.ps1 crawl "..." -d 2 -ol "a.product" -olp "/product/"` | Server error (TaskLoops bean missing) after misleading warning about link discovery being disabled |
| AC2 retry | Long-form flags `--out-link-selector`, `--out-link-pattern` | Same result |
| In...

(truncated — see full.md for complete trace)

---

## Issues Found (0)

No issues could be parsed from Section C of the agent output.

See `20260731-074923-crawl-link-options.full.md` for the complete evaluation output.

