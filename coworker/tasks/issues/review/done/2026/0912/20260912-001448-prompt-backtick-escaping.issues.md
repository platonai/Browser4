# Issues: prompt-backtick-escaping

> **Source:** self-observed during the `.test-sessions/` per-run directory refactor (no scenario run) | **Date:** 20260912-001448 | **Mode:** dev

## Scenario Background

### Task

Not a scenario evaluation. These issues were found while editing the two agent-prompt
builders as part of the `.test-sessions/<run-id>/` restructuring:

- `browser4-tests/real-world-scenarios/scripts/common.ps1` (`$generalPrompt`)
- `coworker/scripts/workers/browser4-eval-prompt.ps1` (`New-Browser4EvalPrompt`)

Both assemble the agent-facing prompt inside a **double-quoted here-string** (`@"..."@`).
In a double-quoted PowerShell string the backtick is the escape character, so markdown
code spans written with a *single* backtick are consumed by PowerShell before the agent
ever sees the text. The two files already contain the correct idioms in most places
(doubled ` `` ` and backslash-escaped `` \` ``), which makes the remaining single-backtick
sites look like oversights rather than a deliberate choice.

### Execution Context

| Step | Command | Outcome |
|---|---|---|
| Discover | Edited the "Temporary files" line of `$generalPrompt` | Some lines use ` ``x`` `, others `` `x` `` — inconsistent |
| Verify semantics | `"inside `./.test-sessions/` (not the repo root)"` | → `inside ./.test-sessions/ (not the repo root)` — backticks silently dropped |
| Enumerate | Line scan of the here-string regions in both files | 16 backtick-bearing lines: 12 fine (doubled or `` \` ``-escaped), 4 defective |
| Confirm corruption | `"Do NOT use a plain `browser4-cli` command"` | → codepoints contain `8` (backspace); rendered text loses the `b`: `plain <0x08>rowser4-cli command` |
| Sanity check | Same pattern via `display` of a working line (`` ``$RepoRootPath`` ``) | Renders correctly — confirms the doubling convention works and the single-backtick sites are the defect |
| Scope sweep | Heuristic scan of 154 `.ps1`/`.psm1` files under `bin/`, `coworker/scripts/`, `browser4-tests/real-world-scenarios/` | 61 candidate lines flagged for manual triage (see Issue 2, "Known limitation") |

---

## Issues Found (2 issues)

### Issue 1: Single-backtick markdown span injects a control character into the coworker evaluation prompt

**Severity:** High
**Category:** Reliability

#### Reproduction

```powershell
# coworker/scripts/workers/browser4-eval-prompt.ps1:158  (inside the @"..."@ here-string)
"Do NOT use a plain `browser4-cli` command unless the invocation above fails ..."
```

Render it and inspect the code points:

```powershell
$t = "Do NOT use a plain `browser4-cli` command"
$t
$t.ToCharArray() | ForEach-Object { [int]$_ }
```

#### Expected Behavior

The prompt text should contain a literal backtick-delimited code span:
`Do NOT use a plain ` + "`browser4-cli`" + ` command ...`.

#### Actual Behavior

`` `b `` is parsed as the PowerShell **backspace** escape, so a raw `0x08` byte is
injected into the instruction and the `b` is consumed:

```
Do NOT use a plain <0x08>rowser4-cli command unless ...
codepoints: 68,111,32,78,79,84,32,117,115,101,32,97,32,112,108,97,105,110,32,8,114,...
                                                                              ^^ 0x08
```

Every coworker-worker-driven browser4-cli evaluation receives a corrupted instruction
where the product name is mangled. The agent still completes tasks, so the defect is
invisible in evaluation reports — it silently degrades the prompt.

#### Root Cause Analysis

`New-Browser4EvalPrompt` returns a double-quoted here-string. PowerShell processes
backtick escapes at parse time, so:

- `` `b `` → `0x08` (backspace) — an *accidental* valid escape, hence corruption
- `` `x `` where `x` is not an escape character → the backtick is dropped and `x` kept —
  hence plain formatting loss (Issue 2)

The author's intent was a markdown code span. The correct idiom in this context is a
doubled backtick (`` ``browser4-cli`` ``), which the same file already uses correctly on
lines 140 and 141.

#### Code Pointer

`coworker/scripts/workers/browser4-eval-prompt.ps1:158` (inside the here-string returned by
`New-Browser4EvalPrompt`, which starts at line 112).

#### AI Suggested Improvement

- Replace the single backticks on line 158 with doubled backticks: `` ``browser4-cli`` ``.
- Add a regression assertion to the coworker script tests that renders the prompt and
  asserts it contains no control characters (`[\x00-\x08\x0B\x0C\x0E-\x1F]`). A prompt
  containing raw control bytes is never legitimate.
- Consider a shared helper (`ConvertTo-AgentPromptText`) that escapes `$`, backtick and
  `"` in prompt fragments so callers never have to reason about PS escaping rules.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Markdown code spans are silently stripped from both agent prompts

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Inspect the here-string regions of the two prompt builders and look for un-doubled,
un-escaped backticks that form a pair:

- `browser4-tests/real-world-scenarios/scripts/common.ps1:780` — 4 backticks / 2 spans
  (`` `### Issue N: <title>` `` and `` `**Bold Label:**` ``)
- `browser4-tests/real-world-scenarios/scripts/common.ps1:802` — 1 span
  (`` `cli/browser4-cli/src/snapshot.rs:render_snapshot()` ``)
- `browser4-tests/real-world-scenarios/scripts/common.ps1:820` — 1 span (`` `---` ``)
- `coworker/scripts/workers/browser4-eval-prompt.ps1:158` — 1 span
  (`` `browser4-cli` `` — same line as Issue 1)

Verify with:

```powershell
"Each issue MUST begin with an `### Issue N: <title>` header"
# => Each issue MUST begin with an ### Issue N: <title> header   (backticks gone)
```

#### Expected Behavior

The agent receives the prompt with intact markdown code spans, so the output-format
instructions it must follow are visually delimited.

#### Actual Behavior

The backticks are removed by PowerShell before the text reaches the agent. The
instructions read as ordinary prose. This matters most for `common.ps1:780`, which
specifies the **required output format** for every issue the agent reports
(`### Issue N: <title>`, `**Bold Label:**`) — the delimiters the agent is told to
reproduce are exactly the characters that got eaten.

#### Root Cause Analysis

Same mechanism as Issue 1: single backticks inside a double-quoted here-string. Where the
following character is not a PowerShell escape character, the backtick is dropped and the
text survives; where it *is* an escape character (Issue 1) the text is actively corrupted.

Correct idioms already present in the same files: doubled backticks
(`common.ps1` 695–698, 826, 851, 881; `browser4-eval-prompt.ps1` 140–141) and
backslash-escaped backticks (`common.ps1` 722, 724).

#### Code Pointer

`browser4-tests/real-world-scenarios/scripts/common.ps1:780, :802, :820`;
`coworker/scripts/workers/browser4-eval-prompt.ps1:158`.

#### AI Suggested Improvement

- Normalise both prompt builders to one idiom — doubling (`` `` ``) is the least noisy in a
  here-string; keep `\`` only where the backtick sits inside a nested double-quoted string
  within a `$( )` sub-expression (as on lines 722/724).
- Add an assertion to `browser4-tests/real-world-scenarios/scripts/common.tests.ps1` that
  the rendered `$generalPrompt` contains no raw control characters, and that the required
  format markers (`### Issue N:`, `**Bold Label:**`) still appear verbatim.
- **Known limitation of the audit:** a heuristic sweep of 154 `.ps1`/`.psm1` files found 61
  further candidate lines, but the heuristic cannot distinguish an *intentional* escape
  (`` `$ ``, `` `" ``, `` `n ``) from a swallowed markdown span. Those files — notably
  `browser4-tests/real-world-scenarios/tasks/workflow/agent.ps1`,
  `coworker/scripts/workers/git-sync.ps1`, `coworker/scripts/workers/merge-prs.ps1`,
  `bin/build/build.ps1` — need manual triage before any bulk edit. Do **not** apply an
  automated fix across them.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
