# Issues: agent-extraction

> **Source:** `20260727-231504-agent-extraction.full.md` | **Date:** 20260727-231504 | **Mode:** dev

## Scenario Background

### Task

The task was **partially successful**. Synchronous commands (`extract`, `summarize`) worked well, but the async `agent run` returned empty results despite completing successfully. Below is a summary of each step.

### Extracted Python Programming Language Details

| Field | Value |
|---|---|
| Language Name | Python |
| First Release Year | 1991 |
| Developer | Python Software Foundation |
| Typing Discipline | Duck, dynamic, strong; optional type annotations |
| License | Python Software Foundation License |

### Guido van Rossum Biographical Details

| Field | Value |
|---|---|
| Full Name | Guido van Rossum |
| Birth Date | 31 January 1956 |
| Birth Place | The Hague, Netherlands |
| Nationality | Dutch |
| Known For | Creating the Python programming language |
| Education | Master's in mathematics and computer science, University of Amsterdam (1982) |
| Current Employer | Microsoft |
| Notable Achievements | 2001 Award for the Advancement of Free Software |
| Children | 1 son |

**Agent task (step 6–8):** Task `3b27930e-48bc-4460-aae7-be521f0a9194` completed with status 200, but `agent result` returned `{}` — the extracted data was present in `instructResults` metadata but not surfaced through the result endpoint.

---

### Execution Context

1. **`./b4w.ps1 help`** — Displayed comprehensive help successfully (after fixing CRLF line endings in the script).
2. **`./b4w.ps1 goto "https://en.wikipedia.org/wiki/Python_(programming_language)"`** — Server auto-started (first run: 12s build + 12s JVM startup). Navigation succeeded.
3. **`./b4w.sh snapshot -v 0 --stdout`** — Captured the page accessibility tree. Worked via `b4w.sh` wrapper. Direct `./b4w.ps1 snapshot -v 0` failed because `-v` was consumed by PowerShell.
4. **`./b4w.sh extract "..." --stdout`** — Synchronous extraction worked, returned structured data.
5. **`./b4w.sh extract "..." --schema "..." --stdout`** — Failed through `b4w.sh` due to JSON quoting. **Workaround:** Used `pwsh -Command` directly with a pwsh variable to pass the schema correctly. Discovered `--schema`...

(truncated — see full.md for complete trace)

---

## Issues Found (12 issues)

### Issue 1: agent result returns empty {} despite successful extraction (critical data loss)

**Severity:** Critical
**Category:** Reliability

#### Reproduction

1. ./b4w.sh agent run 'Navigate to https://en.wikipedia.org/wiki/Guido_van_Rossum, extract key biographical details'
2. Wait for completion
3. ./b4w.sh agent result <task-id>

#### Expected Behavior

The extracted biographical details are returned as structured data.

#### Actual Behavior

agent result returns {}. The agent status --json shows instructResults: [{name: 'fields', resultType: 'map', statusCode: 200}] confirming extraction occurred, but commandResult is {}.

#### Root Cause Analysis

The agent task's LLM-extracted data is stored in instructResults but the agent result CLI endpoint reads from commandResult, which is empty. There appears to be a disconnect between where the agent writes results and where agent result reads them. Investigation needed: check whether this is a serialization issue in the agent pipeline or a result-merging bug.

#### AI Suggested Improvement

- Investigate the agent result pipeline: trace how instructResults data flows into commandResult
- Add a test that verifies agent run → agent result round-trips extracted data correctly
- If instructResults is the correct data source, update agent result to surface it
- Document the result output format and where extracted data appears

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: b4w.ps1 has CRLF line endings, breaking Linux execution

**Severity:** High
**Category:** Product

#### Reproduction

./b4w.ps1 help  # on Linux with pwsh installed

#### Expected Behavior

Help output displays.

#### Actual Behavior

/usr/bin/env: 'pwsh\r': No such file or directory

#### Root Cause Analysis

The b4w.ps1 script is committed with Windows CRLF line endings. On Linux, the shebang line '#!/usr/bin/env pwsh\r' includes a carriage return in the interpreter name, causing 'pwsh\r' (not 'pwsh') to be looked up.

#### Code Pointer

`b4w.ps1:1 — line endings; CI should enforce LF or the repo should use .gitattributes`

#### AI Suggested Improvement

- Add a .gitattributes file with `*.ps1 text eol=lf` or `b4w.ps1 text eol=lf`
- In CI, run `dos2unix` or equivalent on .ps1 files before packaging
- Consider shipping a separate b4w.sh for Linux (already exists but needs to be primary for Linux users)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: b4w.sh fails to pass JSON arguments containing double quotes to pwsh

**Severity:** High
**Category:** Reliability

#### Reproduction

SCHEMA='{"fields":[{"name":"x","type":"string"}]}' && ./b4w.sh extract "test" --schema "$SCHEMA" --stdout

#### Expected Behavior

Schema is passed as a single JSON argument.

#### Actual Behavior

The JSON is split at spaces and passed as ~12+ separate arguments. pwsh receives garbled text with escaped quotes misinterpreted.

#### Root Cause Analysis

b4w.sh's escaping logic replaces `"` with `\"` inside each argument, then wraps in double quotes. When pwsh's -Command parser receives this, the backslash-escaped quotes are parsed differently than expected (pwsh uses backtick for escaping, not backslash). Additionally, bash expands the variable before b4w.sh sees it, causing space-splitting before the wrapper's argument-loop runs.

#### Code Pointer

`b4w.sh:35-38 — argument escaping logic`

#### AI Suggested Improvement

- Pass arguments through stdin or a temp file instead of command-line for JSON payloads
- Use base64 encoding for JSON arguments (add --schema-base64 flag)
- Support `--schema @file.json` syntax to avoid inline JSON entirely
- Rewrite b4w.sh to use pwsh -File with quoted arguments passed through an env var

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: extract --schema uses a custom ExtractionField format, not JSON Schema as documented

**Severity:** High
**Category:** Documentation

#### Reproduction

cat the extract --help output and attempt to use a standard JSON Schema: ./b4w.sh extract 'test' --schema '{"type":"object","properties":{"name":{"type":"string"}}}' --stdout

#### Expected Behavior

Standard JSON Schema is accepted.

#### Actual Behavior

The standard JSON Schema is rejected. The actual format requires `{"fields": [{"name": "...", "type": "...", "description": "...", "required": true/false}]}` using a custom ExtractionField model. This is NOT JSON Schema — it's a bespoke format with no industry standard.

#### Root Cause Analysis

The --schema flag name and help text say 'JSON schema' but the backend expects a custom `ExtractionSchema` Java/Kotlin class serialization format. The correct format is only described in the error message (partially in Chinese), not in the help text.

#### Code Pointer

`cli/browser4-cli/src/ and browser4-rest/src/ — the --schema help text and the ExtractionSchema class`

#### AI Suggested Improvement

- Rename the flag to `--fields` or `--extraction-template` to avoid confusion with JSON Schema
- OR accept both standard JSON Schema (with $schema, type, properties) AND the custom fields format
- Update --help to show the actual format with a working example
- Add a link from --help to a reference doc explaining the ExtractionField format

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: PowerShell wrapper silently consumes -v and -i flags

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 snapshot -v 0 --stdout  # from bash, not pwsh

#### Expected Behavior

Snapshot captured with viewport 0.

#### Actual Behavior

Error: Unknown command: 'snapshot-0'. Did you mean: 'snapshot'?

#### Root Cause Analysis

When b4w.ps1 is invoked via pwsh -File from bash, short flags like -v and -i are intercepted by PowerShell's parameter binder (matching -Verbose and -InformationAction). The flag value '0' gets appended to the preceding argument. The SKILL.md documents this for direct pwsh users but mentions using b4w.sh or -- as workarounds. However, the `--` workaround does not work from bash (produces ambiguous parameter error).

#### Code Pointer

`b4w.ps1: param() block — PowerShell parameter binding`

#### AI Suggested Improvement

- Make b4w.sh the recommended invocation for Linux/macOS users (update SKILL.md)
- Add a `--` passthrough handler in b4w.ps1 that works from both pwsh and bash
- Document the b4w.sh wrapper prominently near the top of SKILL.md for non-Windows users
- Consider detecting the calling shell and auto-switching behavior

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: extract --schema does not support @file references unlike --sql

**Severity:** Medium
**Category:** Product

#### Reproduction

Create a schema JSON file, attempt: ./b4w.sh extract 'test' --schema @schema.json --stdout

#### Expected Behavior

Schema is read from the file.

#### Actual Behavior

The literal string '@schema.json' is parsed as JSON by the backend, which fails.

#### Root Cause Analysis

The --sql flag supports @file.sql references but the --schema flag parser does not implement the same file-reference logic. Combined with the shell quoting issues, this makes passing complex schemas extremely difficult.

#### Code Pointer

`cli/browser4-cli/src/ — argument parsing for --schema, and browser4-rest/src/ — ExtractionSchema parser`

#### AI Suggested Improvement

- Add @file support to --schema, mirroring --sql's behavior
- Add --schema-file as an explicit alternative flag
- Document @file support in --help once implemented

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: Error messages contain mixed-language content (Chinese + English)

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Trigger an extract --schema error.

#### Expected Behavior

Error messages in English consistent with the CLI language.

#### Actual Behavior

Error starts with '帮助: 使用 agent.extract 满足高级数据提取要求：' (Chinese), followed by English code examples and JSON snippets.

#### Root Cause Analysis

The error message template in the backend contains hardcoded Chinese text for the help prefix, but the technical description is in English. This appears to be a localization gap.

#### Code Pointer

`browser4-rest/src/ or browser4-agentic/src/ — error message templates for extract/agent failures`

#### AI Suggested Improvement

- Localize all error messages to English (match the CLI language) or implement proper i18n
- Ensure error message language is consistent end-to-end

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: CLI version mismatch between source build and installed runtime creates confusion

**Severity:** Medium
**Category:** Product

#### Reproduction

Run ./b4w.ps1 status or ./b4w.ps1 doctor

#### Expected Behavior

Consistent version information.

#### Actual Behavior

CLI version 4.12.1 vs installed version v4.11.15 with warning about possible behavioral differences. The message says to 'run cd browser4-rest && mvn spring-boot:run' but the auto-start already builds from source.

#### Root Cause Analysis

The CLI is built from the local source tree (4.12.x branch), while a pre-installed runtime (v4.11.15) exists from a previous installation. The auto-start builds a new bundle from source, but the status/doctor commands report the stale installed version.

#### Code Pointer

`cli/browser4-cli/src/ — version/status reporting`

#### AI Suggested Improvement

- When running from a local build, report only the local source version
- Suppress the version mismatch warning when in dev mode (auto-starting from local source)
- Clarify the advice: 'cd browser4-rest && mvn spring-boot:run' contradicts the auto-start behavior

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 9: b4w.sh shows distracting nag message on every command

**Severity:** Low
**Category:** UX

#### Reproduction

Run any command via ./b4w.sh

#### Expected Behavior

Clean command output without warnings.

#### Actual Behavior

Every command is prefixed with: 'It is strongly recommended to launch `pwsh` and run the .ps1 commands directly within the `pwsh` terminal.'

#### Root Cause Analysis

b4w.sh has a hardcoded echo statement on line 17 that prints this message unconditionally.

#### Code Pointer

`b4w.sh:17 — unconditional echo statement`

#### AI Suggested Improvement

- Remove the nag message or show it only once by checking an environment variable or state file
- If pwsh is the recommended way, reconsider whether b4w.sh should exist at all; if it exists, it should work silently

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 10: agent.md documentation shows sequential task IDs but actual IDs are UUIDs

**Severity:** Low
**Category:** Documentation

#### Reproduction

Run agent run, observe returned task ID format.

#### Expected Behavior

Task IDs like 'agent-task-1' as shown in agent.md examples.

#### Actual Behavior

Task IDs are UUIDs like '3b27930e-48bc-4460-aae7-be521f0a9194'.

#### Root Cause Analysis

The agent.md reference was written for an older version that used sequential IDs. The current implementation uses UUIDs but the docs were not updated.

#### Code Pointer

`skills/browser4-cli/references/agent.md:26-28 — example commands using agent-task-1`

#### AI Suggested Improvement

- Update all agent.md examples to use UUID-format task IDs
- Or add a note that task IDs take the UUID format shown in the actual output

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 11: Server auto-start requires Maven build on first command, causing ~24s cold start latency

**Severity:** Low
**Category:** UX

#### Reproduction

Run the first b4w command in a fresh checkout.

#### Expected Behavior

Quick startup within a few seconds.

#### Actual Behavior

12s Maven bundle build + 12s JVM startup = ~24s before first command completes.

#### Root Cause Analysis

The dev-mode auto-start builds the runtime bundle from source using Maven, then starts the JVM. Both steps add significant latency for first-time users who expect a CLI tool to respond quickly.

#### Code Pointer

`b4w.ps1 / b4w.sh — server startup logic`

#### AI Suggested Improvement

- Display a loading spinner or progress bar during the first startup rather than silent waiting
- Pre-build the bundle during `cargo build`/`cargo run` so it's ready
- Cache the built bundle and only rebuild when source files change
- Show estimated time remaining for the first build

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 12: agent list table formatting breaks on narrow terminals

**Severity:** Low
**Category:** UX

#### Reproduction

Run agent list after submitting a task with a long description.

#### Expected Behavior

Readable table output.

#### Actual Behavior

The DESCRIPTION column has very long text that causes the table to wrap badly. Columns are misaligned and the STATUS column value appears on a separate row for the queued task.

#### Root Cause Analysis

The agent list output formatter doesn't truncate long field values or adapt to terminal width.

#### Code Pointer

`cli/browser4-cli/src/ — agent list output formatting`

#### AI Suggested Improvement

- Truncate the DESCRIPTION column to a reasonable width (e.g., 60 chars) with '...'
- Auto-detect terminal width and adjust column widths
- Use --json flag for programmatic access (already works); improve the human-readable default

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## Overall Assessment

**Completion Status:** Partially Successful — Steps 1-5 (sync commands) all succeeded. Steps 6-8 (async agent) failed due to empty result. Step 9 (comparison) completed with analysis.

**Success Rate:** 78% — 7 of 9 major steps succeeded; 2 steps (agent result retrieval + schema file passing) required workarounds or partially failed.

**Issues Found:** 12

**Major Blockers:** Critical: agent run returns empty result {} despite successful extraction, making the async agent approach unreliable for data extraction. High: b4w.ps1 CRLF breaks Linux; b4w.sh cannot pass JSON arguments; --schema format is undocumented custom format rather than JSON Schema.

**Most Confusing Aspects:** 1. Which wrapper to use (b4w.ps1 vs b4w.sh) is unclear for Linux users. 2. The --schema flag says 'JSON schema' but uses a custom {fields: [...]} format that isn't documented in --help. 3. Shell quoting through bash→pwsh requires trial and error. 4. agent result returning {} without any error is silent data loss.

**Most Valuable Improvements:** 1. Fix agent result to surface extracted data (Critical). 2. Add @file support to --schema and document the ExtractionField format. 3. Fix b4w.ps1 CRLF for cross-platform use. 4. Unify b4w.sh/b4w.ps1 behavior or make b4w.sh the primary Linux entry point. 5. Support standard JSON Schema in --schema.

**Usability Rating:** 5/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: agent result returns empty {} despite successful extraction (critical data loss)

1. ./b4w.sh agent run 'Navigate to https://en.wikipedia.org/wiki/Guido_van_Rossum, extract key biographical details'
2. Wait for completion
3. ./b4w.sh agent result <task-id>

#### Issue 2: b4w.ps1 has CRLF line endings, breaking Linux execution

./b4w.ps1 help  # on Linux with pwsh installed

#### Issue 3: b4w.sh fails to pass JSON arguments containing double quotes to pwsh

SCHEMA='{"fields":[{"name":"x","type":"string"}]}' && ./b4w.sh extract "test" --schema "$SCHEMA" --stdout

#### Issue 4: extract --schema uses a custom ExtractionField format, not JSON Schema as documented

cat the extract --help output and attempt to use a standard JSON Schema: ./b4w.sh extract 'test' --schema '{"type":"object","properties":{"name":{"type":"string"}}}' --stdout

#### Issue 5: PowerShell wrapper silently consumes -v and -i flags

./b4w.ps1 snapshot -v 0 --stdout  # from bash, not pwsh

#### Issue 6: extract --schema does not support @file references unlike --sql

Create a schema JSON file, attempt: ./b4w.sh extract 'test' --schema @schema.json --stdout

#### Issue 7: Error messages contain mixed-language content (Chinese + English)

Trigger an extract --schema error.

#### Issue 8: CLI version mismatch between source build and installed runtime creates confusion

Run ./b4w.ps1 status or ./b4w.ps1 doctor

#### Issue 9: b4w.sh shows distracting nag message on every command

Run any command via ./b4w.sh

#### Issue 10: agent.md documentation shows sequential task IDs but actual IDs are UUIDs

Run agent run, observe returned task ID format.

#### Issue 11: Server auto-start requires Maven build on first command, causing ~24s cold start latency

Run the first b4w command in a fresh checkout.

#### Issue 12: agent list table formatting breaks on narrow terminals

Run agent list after submitting a task with a long description.

