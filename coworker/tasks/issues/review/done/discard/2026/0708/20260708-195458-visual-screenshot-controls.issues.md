# Issues: visual-screenshot-controls

> **Source:** `20260708-195458-visual-screenshot-controls.full.md` | **Date:** 20260708-195458 | **Mode:** dev

## Scenario Background

### Task

All browser automation operations executed correctly on the first attempt with zero retries. The task was completed efficiently using 18 CLI invocations.

### Output Files Produced

| File | Size | Description |
|------|------|-------------|
| `.browser4-cli/snapshot/screenshot-*.png` | 390 KB | Default viewport screenshot (step 4) |
| `wikipedia-llm-fullpage.png` | 6.9 MB | Full-page screenshot (step 7) |
| `wikipedia-llm-top.png` | 518 KB | Top-of-page screenshot after scroll-back (step 9) |
| `wikipedia-llm.pdf` | 2.3 MB | PDF export (step 10) |

### Issues Found: 6

| # | Severity | Category | Summary |
|---|----------|----------|---------|
| 1 | High | Discoverability | `cargo run` from repo root fails — correct invocation is buried in nested docs |
| 2 | Medium | UX | `--filename` path resolution under cargo run is confusing and unintuitive |
| 3 | Low | UX | Session persistence leaks prior state without clear warning |
| 4 | Low | UX | `scroll` command outputs raw number without context |
| 5 | Low | UX | No "scroll to top/bottom" shortcut |
| 6 | Low | Product | `pdf` command has minimal configuration options |

### Overall Rating: **7/10**

The core engine is solid — fast, reliable, and well-documented. The main friction is around the developer onboarding experience: discovering the correct cargo invocation and dealing with CWD path issues. The full evaluation report is at `eval-report-viewport-pdf.md`.

---

## Issues Found (0)

No issues could be parsed from Section C of the agent output.

See `20260708-195458-visual-screenshot-controls.full.md` for the complete evaluation output.

