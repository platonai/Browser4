# Issues: swarm-parallel-scraping

> **Source:** `20260727-075247-swarm-parallel-scraping.full.md` | **Date:** 20260727-075247 | **Mode:** dev

## Scenario Background

### Task

**Partially Successful.** The swarm X-SQL extraction path failed entirely (12/13 tasks failed with HTTP 417), but the task objective was achieved by pivoting to `crawl`, which extracted all 10 products correctly.

| Product | Price | 
|---------|-------|
| 4K OLED TV 55 | $899.99 |
| Wireless Noise-Cancelling Headphones | $199.99 |
| Portable Bluetooth Speaker | $49.99 |
| Smartphone 128GB | $599.00 |
| USB-C Hub 7-in-1 | $29.95 |
| Wireless Mouse | $24.99 |
| Vacuum Cleaner Smart | $159.99 |
| Air Purifier | $129.50 |
| Essential Oil Diffuser | $22.99 |
| LED Desk Lamp | $35.99 |

## Issues Found: 8 (1 Critical, 2 High, 3 Medium, 2 Low)

| # | Severity | Title |
|---|----------|-------|
| 1 | **Critical** | swarm query fails with HTTP 417 on X-SQL `load_and_select` — known bug, 100% failure rate |
| 2 | High | `b4w.sh` fails on Windows Git Bash — Unix→Windows path conversion missing |
| 3 | High | `b4w.ps1 --` passthrough fails with ambiguous parameter error from bash |
| 4 | Medium | PowerShell `@` splatting breaks `--sql @file` syntax |
| 5 | Medium | Swarm status shows empty `message` on failure — no debugging info |
| 6 | Medium | Stale tasks from prior sessions block new swarm jobs |
| 7 | Low | Help output lacks worked examples for swarm workflow |
| 8 | Low | `isDone: false` for failed tasks contradicts `lifecycleState: "failed"` |

## Overall Rating: **5/10**

The documentation and core design are strong, but shell integration on Windows/Git Bash is fragile and the critical swarm X-SQL bug makes the documented workflow unusable. The full report is at `.test-sessions/evaluation-report.md`.

---

## Issues Found (0)

No issues could be parsed from Section C of the agent output.

See `20260727-075247-swarm-parallel-scraping.full.md` for the complete evaluation output.

