# Issues: javascript-evaluation

> **Source:** `20260709-222538-javascript-evaluation.full.md` | **Date:** 20260709-222538 | **Mode:** dev

## Scenario Background

### Task

All 8 task steps completed successfully:

| Step | Command | Result |
|------|---------|--------|
| 1 | `goto "http://localhost:18080/generated/interactive-1.html"` | Page loaded: "Interactive Single Page" |
| 2 | `snapshot -i` | Discovered element refs: e21 (textbox), e22 (combobox), e23/e24 (spinbuttons), e34 (heading), e37 (heading), e78 (button), etc. |
| 3 | `eval "document.title"` | `Interactive Single Page` ✓ |
| 4 | `eval --json "({url:..., title:..., links:...})"` | `{"links":0,"title":"Interactive Single Page","url":"http://localhost:18080/generated/interactive-1.html"}` ✓ |
| 5 | `eval --file page_info.js` | `{"images":0,"links":0,"forms":0}` ✓ |
| 6 | `echo '...' \| cargo run -- eval --stdin` | `["Welcome to the Interactive Page","📋 User Information","📊 Preferences","🧮 Quick Calculator","🎯 Dynamic Toggle"]` ✓ |
| 7 | `eval "element => element.textContent" --ref e34` | `Welcome to the Interactive Page` ✓ |
| 8 | Cross-verification | All eval methods produce mutually consistent results ✓ |

**Key finding:** All six eval invocation modes (`eval`, `--json`, `--file`, `--stdin`, `--base64`, `--ref`) work correctly and produce consistent output. The page (interactive-1.html) genuinely has 0 links, 0 images, and 0 forms — it uses non-link interactive elements (textboxes, comboboxes, spinbuttons, buttons) instead.

### Execution Context

**Key Commands:**

1. `cd cli/browser4-cli && cargo run -- help` — learned available commands
2. Read `skills/browser4-cli/SKILL.md` — learned CLI conventions
3. `cargo run -- eval --help` — discovered all eval flags (`--json`, `--file`, `--stdin`, `--base64`, `--ref`)
4. `cargo run -- goto "http://localhost:18080/generated/interactive-1.html"` — navigated to page
5. `cargo run -- snapshot -i` — captured interactive snapshot, discovered element refs
6. `cargo run -- eval "document.title"` — basic JS eval
7. `cargo run -- eval --json "({url:..., title:..., links:...})"` — structured JSON eval
8. Created `/home/vincent/workspace/Browser4/page_info.js` → `cargo run -- eval --file /home/vincent/workspace/Browser4/page_info.js` — file-based eval
9. `echo '...' | cargo run -- eval --stdin` — stdin-based eval
10. `cargo run -- eval "element => ..." --ref e34` — ref-scoped eval (also tested positional ref syntax)

**Key decisions:**
- Resolved `$cliInvocation` to `cd <repo> && cd cli/browser4-cli && cargo run --` by reading `browser4-tests/real-world-scenarios/scripts/common.ps1`
- Used absolute paths for `--file` after observing CWD sensitivity
- Used `(cd cli/browser4-cli && cargo run -- ...)` subshell workaround for piped stdin

**Workarounds required:**
- Variable substitution: `$cliInvocation`, `$helpCmd`, `$skillPath` are literal strings in prompt — resolved by reading common.ps1
- Pipe + CWD: `echo ... | cd cli/browser4-cli && cargo run -- eval --stdin` fails because `|` binds tighter than `&&` — worked around by being in `cli/browser4-cli/` directory first
- `console.log` output from `eval --file` scripts is silently discarded — only return values appear

---

## Issues Found (6 issues)
> **Review complete:** 0 approved, 6 deferred/rejected

### Issue 1: `console.log` output from `eval --file` scripts is silently discarded

**Severity:** Medium
**Category:** Reliability

#### Review Result

**Decision:** WONTFIX

**Summary:** - Document in `eval --help` that `console.log` output is not captured; only `return` values are shown

---

### Issue 2: Pipe + `cd` CWD conflict breaks `eval --stdin` from repo root

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add a note to `eval --help` and SKILL.md about the pipe precedence pitfall

---

### Issue 3: Prompt template variables are literal strings, not substituted

**Severity:** Medium
**Category:** Documentation

#### Review Result

**Decision:** WONTFIX

**Summary:** - Perform variable substitution in the PowerShell template before emitting the prompt (resolve `$cliInvocation`, `$helpCmd`, `$skillPath`, `$RepoRootPath` to their actual values)

---

### Issue 4: `eval --file` requires absolute path or path relative to `cli/browser4-cli/`

**Severity:** Low
**Category:** Discoverability

#### Review Result

**Decision:** WONTFIX

**Summary:** - Document in `eval --help` that `--file` paths resolve relative to the current working directory

---

### Issue 5: Interactive snapshot preview truncation requires re-running with `--stdout`

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - When `-i`/`--interactive` is passed, default to `--stdout` behavior (the user explicitly asked for interactive discovery)

---

### Issue 6: `--base64` mode requires external base64 encoding — no built-in helper

**Severity:** Low
**Category:** Discoverability

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add a tip to `eval --help`: "Generate base64: `echo -n 'document.title' | base64`"

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: `console.log` output from `eval --file` scripts is silently discarded

```bash
# Create a script with console.log
cat > page_info.js << 'EOF'
console.log("IMAGES:", document.querySelectorAll('img').length);
({images: document.querySelectorAll('img').length})
EOF
cd cli/browser4-cli && cargo run -- eval --file page_info.js
```
Only `{"images":0}` appears. The `console.log("IMAGES:", ...)` output is silently discarded.

#### Issue 2: Pipe + `cd` CWD conflict breaks `eval --stdin` from repo root

```bash
cd /home/vincent/workspace/Browser4 && echo 'document.title' | cd cli/browser4-cli && cargo run -- eval --stdin
```
Produces: `error: could not find Cargo.toml in /home/vincent/workspace/Browser4`

#### Issue 3: Prompt template variables are literal strings, not substituted

Read the task specification. It references `$cliInvocation`, `$helpCmd`, `$skillPath`, and `$RepoRootPath` as if they are defined variables, but they are literal strings with no substitution. A first-time evaluator must reverse-engineer their values from `common.ps1`.

#### Issue 4: `eval --file` requires absolute path or path relative to `cli/browser4-cli/`

```bash
cd /home/vincent/workspace/Browser4 && cd cli/browser4-cli && cargo run -- eval --file page_info.js
```
If `page_info.js` is at the repo root (`/home/vincent/workspace/Browser4/page_info.js`), this fails because `cargo run` resolves relative paths from `cli/browser4-cli/`, not the repo root.

#### Issue 5: Interactive snapshot preview truncation requires re-running with `--stdout`

```bash
cd cli/browser4-cli && cargo run -- snapshot -i
```
Output shows only 10 lines with `... (use --stdout or open the file for full content)`. The user must re-run `snapshot -i --stdout` to see all element refs inline.

#### Issue 6: `--base64` mode requires external base64 encoding — no built-in helper

```bash
cd cli/browser4-cli && cargo run -- eval --base64 "document.title"
```
Without pre-encoding to base64, the raw string is interpreted as base64, yielding garbage or errors.
