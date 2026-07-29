# Issues: javascript-evaluation

> **Source:** `20260727-072802-javascript-evaluation.full.md` | **Date:** 20260727-072802 | **Mode:** dev

## Scenario Background

### Task

All seven JavaScript evaluation methods in browser4-cli were tested and verified against `http://localhost:18080/generated/interactive-1.html`:

| Step | Method | Result |
|------|--------|--------|
| 3 | `eval "document.title"` | `Interactive Single Page` |
| 4 | `eval --json` (object) | URL, title, linkCount=0 |
| 5 | `eval --file` | images=0, links=0, forms=0 |
| 6 | `eval --stdin` (pipe) | 5 headings extracted |
| 7 | `eval --ref e4122` | Input tag, placeholder, id properties |

Cross-verification confirmed all methods produce internally consistent results.

### Issues Found — 6 total (1 High, 3 Medium, 2 Low)

**Critical finding — Issue 2 (High):** `eval --ref` silently returns `null` for simple expressions like `this.tagName`. The user must use function-declaration syntax (`function() { return this.tagName; }`) but this is completely undocumented. Root cause traced to `PulsarWebDriver.kt:407-414` where `.call(this, this)` is applied to the expression unconditionally.

**Other notable issues:**
- PowerShell `-i` flag interception requires using `--interactive` instead (Issue 1)
- The `--` passthrough pattern documented in SKILL.md doesn't work as advertised (Issue 4)
- `eval --help` examples omit all `--ref` usage (Issue 5)

### Key Workarounds Required
1. `snapshot -i` → `snapshot --interactive` (PowerShell collision)
2. `<cmd> --help` → `b4w.ps1 help <cmd>` (PowerShell collision)
3. `eval --ref e5 'this.tagName'` → `eval --ref e5 'function() { return this.tagName; }'` (undocumented)

Full report saved to `.test-sessions/eval-evaluation-report.md`.

---

## Issues Found (0)

No issues could be parsed from Section C of the agent output.

See `20260727-072802-javascript-evaluation.full.md` for the complete evaluation output.

