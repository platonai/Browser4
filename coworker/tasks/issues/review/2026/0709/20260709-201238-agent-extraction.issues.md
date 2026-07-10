# Issues: agent-extraction

> **Source:** `20260709-201238-agent-extraction.full.md` | **Date:** 20260709-201238 | **Mode:** dev

## Scenario Background

### Task

**Overall: Partially successful (6 of 9 sub-tasks completed fully, 1 partially, 1 failed)**

| # | Task | Result |
|---|------|--------|
| 1 | Navigate to Python Wikipedia page | ✅ Completed |
| 2 | `extract` with inline JSON schema | ✅ Completed — returned name, release year, developer, typing discipline, license |
| 3 | `extract` with custom schema file (`--filename`) | ✅ Completed — saved to file with 5 extracted fields |
| 4 | `summarize` full page | ✅ Completed — produced comprehensive, well-structured summary |
| 5 | `summarize --selector "#History"` | ✅ Completed — correctly scoped summary to History section only |
| 6 | Submit `agent run` task for Guido van Rossum | ✅ Task submitted, ID: `6b7a7598-9af3-4c01-8568-dbb595d435b2` |
| 7 | Poll agent status | ✅ Status polled; task transitioned Processing → OK (done) |
| 8 | Retrieve agent results | ❌ `agent result` returned `{}`; data inaccessible |
| 9 | Compare sync vs async approaches | ✅ Comparison completed (see below) |

### Key Data Extracted

- **Extract (inline schema):** `{"name":"Python","first_release_year":1991,"developer":"Python Software Foundation","typing_discipline":"Duck, dynamic, strong; optional type annotations","license":"Python Software Foundation License"}`
- **Extract (custom schema file):** `{"language_name":"Python","first_release_year":1991,"original_developer":"Guido van Rossum","typing_discipline":"Duck, dynamic, strong; optional type annotations","license":"Python Software Foundation License"}`
- **Summarize:** 4-section structured summary (What Python is, Key features, History, Significance)
- **Summarize --selector:** 4-paragraph History-only summary (conception → 0.9.0 → 2.0 → 3.0 → BDFL succession → present)
- **Agent task result:** Empty `{}` — bug: data extracted but not returned to user

---

### Execution Context

**Key Commands:**

| # | Command | Purpose |
|---|---------|---------|
| 1 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help` | Learned available commands |
| 2 | `cargo run -- ... -- goto "https://en.wikipedia.org/wiki/Python_(programming_language)"` | Navigated to target page |
| 3 | `cargo run -- ... -- extract --help` | Checked extract command options |
| 4 | `cargo run -- ... -- extract "programming language name..." --schema '{"type":"object",...}' --stdout` | Extracted with inline JSON Schema |
| 5 | `cargo run -- ... -- extract "extract Python..." --schema @python-extract-schema.json --filename python-extract-results.json` | **Failed** — `@file` not supported for `--schema` |
| 6 | `cargo run -- ... -- extract "extract Python..." --schema '{"fields":[...]}' --filename ...` | Extracted with custom ExtractionField schema format |
| 7 | `cargo run -- ... -- summarize --help` | Checked summarize command options |
| 8 | `cargo run -- ... -- summarize "Provide a concise summary..." --stdout` | Summarized full page |
| 9 | `cargo run -- ... -- htmlsnapshot` | Captured HTML snapshot for selector discovery |
| 10 | `cargo run -- ... -- summarize "Summarize the history..." --selector "#History" --stdout` | Summarized History section only |
| 11 | `cargo run -- ... -- agent run --help` | Checked agent run command options |
| 12 | `cargo run -- ... -- agent run "Navigate to https://en.wikipedia.org/wiki/Guido_van_Rossum..."` | Submitted autonomous agent task |
| 13 | `cargo run -- ... -- agent status 6b7a7598...` (×3) | Polled task status until completion |
| 14 | `cargo run -- ... -- agent result 6b7a7598...` | Retrieved task result (returned `{}`) |
| 15 | `cargo run -- ... -- agent list` | Listed tasks (returned "No tracked async tasks") |
| 16 | `curl -s http://localhost:18080/api/agent/task/.../result` | Attempted direct API access (404) |

**Workarounds Applied During Task:**

1. **Schema format**: The `--schema` flag requires a custom `{"fields": [...]}` ExtractionField format, not standard JSON Schema as the help suggests. Had to adapt after seeing the error message.
2. **Schema file loading**: No `@file` notation for `--schema`. Had to inline the entire schema as a command-line argument.
3. **Agent result retrieval**: No workaround found. The agent task completed but results are inaccessible.

---

---

## Issues Found (9 issues)

### Issue 1: `summarize`, `agent run`, `agent status`, and `agent result` missing from top-level `--help`

**Severity:** High
**Category:** Discoverability

#### Reproduction

Run `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help` and observe the Agent section.

#### Expected Behavior

All available agent subcommands (`agent run`, `agent status`, `agent result`) and the `summarize` command should be listed in the top-level help output.

#### Actual Behavior

The help output shows only `extract` and `agent list` under the "Agent:" section. `summarize` and the `agent run/status/result` subcommands are entirely absent from the top-level help, making them undiscoverable without reading external documentation (SKILL.md or agent.md). A new user running `--help` would never know these commands exist.

#### Root Cause Analysis

These commands are likely implemented as standalone CLI commands (`summarize`) or subcommands (`agent run/status/result`) but are not registered in the top-level `CommandDef` help generation. The help generation likely uses a manual or incomplete command registry.

#### Code Pointer

``cli/browser4-cli/src/commands.rs` — command registration / help generation`

#### AI Suggested Improvement

- Add `summarize` command definition to the Agent section of the help output
- Add `agent run`, `agent status`, and `agent result` as subcommands under the Agent section
- Consider auto-generating the help output from the full command registry to prevent future omissions

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 2: `--schema` flag does not support file path input (`@file` pattern)

**Severity:** Medium
**Category:** UX

#### Reproduction

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- extract "extract data" --schema @schema.json
```

#### Expected Behavior

The `--schema` flag should accept a file path (e.g., `@schema.json`), similar to how `--sql @query.sql` works for `htmlsnapshot query`. This is a consistent pattern users would expect.

#### Actual Behavior

The command fails with `Unexpected character ('@' (code 64)): expected a valid value (JSON String, Number, Array, Object or token 'null', 'true' or 'false')`. Users must inline the entire JSON schema on the command line, which is unwieldy for complex schemas.

#### Root Cause Analysis

The `--schema` argument parser expects inline JSON only. Unlike `--sql` which supports `@file` notation, `--schema` does not implement file path resolution.

#### Code Pointer

``cli/browser4-cli/src/commands.rs` — extract command argument parsing`

#### AI Suggested Improvement

- Add `@file` path resolution for `--schema`, consistent with `--sql @file` pattern
- Fall back to parsing as inline JSON if the value doesn't start with `@`
- Document the `@file` pattern in both `extract --help` and the agent.md reference

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 3: `--schema` uses non-standard ExtractionField format instead of JSON Schema

**Severity:** High
**Category:** Documentation / UX

#### Reproduction

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- extract "data" --schema '{"type":"object","properties":{"name":{"type":"string"}}}'
```

#### Expected Behavior

The `--schema` flag should accept standard JSON Schema (as implied by the help text: "JSON schema to constrain the extracted data structure").

#### Actual Behavior

The `--schema` flag requires a custom `{"fields": [{"name":"...","type":"...","description":"..."}]}` format (ExtractionField schema). Standard JSON Schema is silently accepted but produces incorrect or unexpected results. The format difference is only discoverable through error messages or reading source code. The help text says "JSON schema" which misleadingly suggests standard JSON Schema support.

#### Root Cause Analysis

The backend's extraction engine uses a custom `ExtractionField` schema format tailored to its field-based extraction model, but the CLI documentation describes it as a generic "JSON schema" without disclosing the specific format requirements.

#### Code Pointer

``cli/browser4-cli/src/commands.rs` — extract command help text; backend extraction schema validator`

#### AI Suggested Improvement

- Update the `extract --help` text to clearly document the ExtractionField format with a link to the schema reference
- Either: (a) add an example showing the `{"fields": [...]}` format in the help, or (b) support standard JSON Schema as an alternative input format and auto-convert
- Add a `--schema-help` or `--schema-example` flag that prints the expected schema format
- Update agent.md to include the schema format reference

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 4: Error messages displayed in Chinese

**Severity:** Low
**Category:** UX / Documentation

#### Reproduction

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- extract "data" --schema @file.json
```

#### Expected Behavior

Error messages should be in English (or follow the user's locale), with an option to switch languages.

#### Actual Behavior

The error message includes Chinese text: `help: 使用 agent.extract 满足高级数据提取要求...` and parameter descriptions in Chinese. This is problematic for non-Chinese-speaking users who cannot understand the recovery guidance.

#### Root Cause Analysis

The backend (Kotlin/Java) generates error messages with Chinese help text, likely from a hardcoded or configuration-driven localization that defaults to Chinese.

#### Code Pointer

`Backend error message generation — likely in `browser4-rest` or `browser4-agentic` module's error handling`

#### AI Suggested Improvement

- Default error messages to English (the CLI's primary language)
- Add i18n support with language detection from `LANG`/`LC_ALL` environment variables
- Include a `--lang` global option for language override

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 5: `--filename` saves to `.browser4-cli/snapshot/` directory, not current working directory

**Severity:** Medium
**Category:** UX

#### Reproduction

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- extract "data" --filename my-results.json
ls my-results.json  # File not found in current directory
ls .browser4-cli/snapshot/my-results.json  # File is here
```

#### Expected Behavior

`--filename my-results.json` should save the file to the current working directory, consistent with standard CLI behavior.

#### Actual Behavior

The file is saved to `.browser4-cli/snapshot/my-results.json` without any indication in the output that the path is relative to the snapshot directory rather than the current working directory. Users must read the output path carefully to discover where the file actually went.

#### Root Cause Analysis

The `--filename` flag resolves relative paths against the snapshot directory (`.browser4-cli/snapshot/`) rather than the current working directory.

#### Code Pointer

``cli/browser4-cli/src/commands.rs` — extract command filename resolution; `cli/browser4-cli/src/main.rs` — output path handling`

#### AI Suggested Improvement

- Resolve relative `--filename` paths against the current working directory (standard CLI convention)
- If saving to snapshot directory is intentional, accept absolute paths for custom locations and clearly indicate the save path in the output
- Consider adding `--output-dir` for specifying an alternative output directory

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 6: `agent result` returns empty object despite successful task completion

**Severity:** Critical
**Category:** Reliability

#### Reproduction

```bash
# Submit task
cargo run -- ... -- agent run "Navigate to https://en.wikipedia.org/wiki/Guido_van_Rossum, extract key biographical details..."
# → Task submitted: 6b7a7598-9af3-4c01-8568-dbb595d435b2
# Wait for completion, then:
cargo run -- ... -- agent status 6b7a7598-9af3-4c01-8568-dbb595d435b2
# → {"status":"OK","isDone":true,"instructResults":[{"name":"fields","statusCode":200,"resultType":"map"}]}
cargo run -- ... -- agent result 6b7a7598-9af3-4c01-8568-dbb595d435b2
# → {}
```

#### Expected Behavior

`agent result` should return the extracted biographical data from the agent task, consistent with the task having completed successfully.

#### Actual Behavior

`agent result` returns `{}` (empty JSON object). The `agent status` response shows `"commandResult":{}` and `"instructResults":[{"name":"fields","statusCode":200,"resultType":"map"}]` — the extraction ran successfully but the extracted data is surfaced in `instructResults` (which has `resultType: "map"` but no visible data payload), while `commandResult` (what `agent result` returns) is empty.

#### Root Cause Analysis

The agent task framework stores extraction results in `instructResults` (a per-instruction result list) but `agent result` reads from `commandResult` (the top-level task result). When the agent's main action is an extraction instruction (not a direct command), the data flows into `instructResults` and is never propagated to `commandResult`, making it invisible to the `agent result` CLI command.

#### Code Pointer

`Backend: `browser4-agentic` module — `AgentTaskResult` serialization, specifically the mapping of `instructResults` to the result endpoint response. CLI: `cli/browser4-cli/src/commands.rs` — `agent result` command handler.`

#### AI Suggested Improvement

- Merge `instructResults` data into `commandResult` when `commandResult` is empty and `instructResults` has data
- Alternatively, make `agent result` return the full task status object (including `instructResults`) rather than only `commandResult`
- Add a dedicated `agent instruct-result <task-id> <instruction-name>` command to retrieve individual instruction results
- Add validation in `agent result` that warns when `commandResult` is empty but `instructResults` contains data

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 7: `agent list` shows "No tracked async tasks" for tasks that completed successfully

**Severity:** High
**Category:** Reliability

#### Reproduction

```bash
cargo run -- ... -- agent run "Navigate to example.com and extract..."
# Note the task ID from output
cargo run -- ... -- agent status <task-id>  # Works — shows task status
cargo run -- ... -- agent list  # → "No tracked async tasks."
```

#### Expected Behavior

`agent list` should show all submitted and tracked agent tasks, including their IDs, status, and submission times.

#### Actual Behavior

`agent list` returns "No tracked async tasks." even though a task was successfully submitted, tracked (via `agent status`), and completed. The task ID works with `agent status` but `agent list` doesn't recognize it.

#### Root Cause Analysis

`agent list` and `agent status`/`agent result` likely query different task stores. `agent list` may only show tasks tracked in a local CLI-side store, while `agent status` queries the backend directly by ID. Tasks submitted via `agent run` are stored on the backend but not registered in the CLI's local task registry.

#### Code Pointer

``cli/browser4-cli/src/commands.rs` — `agent list` handler likely reads from a local store; `agent status`/`agent result` handlers likely query the backend REST API directly.`

#### AI Suggested Improvement

- Unify task tracking: either have `agent list` query the backend for all active/completed tasks, or register tasks in the local store when `agent run` returns a task ID
- Consider adding a `--all` flag to `agent list` to show both running and completed tasks
- Add task metadata (submission time, description) to the list output

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 8: Extract result wrapped in Java class serialization rather than clean JSON

**Severity:** Low
**Category:** UX

#### Reproduction

```bash
cargo run -- ... -- extract "product name, price" --stdout
```

#### Expected Behavior

Clean JSON output like `{"name":"Python","first_release_year":1991,...}`

#### Actual Behavior

The output wraps the result in a Java class description:
```json
{"type":"ai.platon.pulsar.agentic.ExtractResult","description":"success: true message: OK data: {\"name\":\"Python\",...}"}
```
The actual data is embedded as an escaped JSON string inside the `description` field rather than being returned as a direct JSON object. This requires additional parsing by the user.

#### Root Cause Analysis

The backend serializes the `ExtractResult` Java/Kotlin object directly to JSON, including its class name and string-serialized description field. The CLI does not unwrap this before presenting it to the user.

#### Code Pointer

``browser4-rest` module — `ExtractResult` serialization; `cli/browser4-cli/src/commands.rs` — extract command output formatting`

#### AI Suggested Improvement

- Unwrap the `data` field from `ExtractResult` and return it directly as the JSON output (already done for `summarize` which returns clean markdown)
- Use `--json` to control whether the raw API response or the unwrapped data is returned
- Consider a `--raw` flag for users who want the full API response including metadata (token counts, inference time)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 9: Session persistence creates confusing initial state for new users

**Severity:** Low
**Category:** UX / Discoverability

#### Reproduction

Run `goto` on a fresh evaluation. The CLI reconnects to a pre-existing session from a previous evaluation with a different page loaded, showing "Reconnected to existing session on http://localhost:18080/generated/interactive-5.html".

#### Expected Behavior

On first use, the CLI should clearly indicate whether a session is new or being reused, and ideally offer an option to start fresh.

#### Actual Behavior

The CLI silently reconnects to an existing session without indicating this is a persistent session feature. The message "Reconnected to existing session" appears but may be confusing to new users who expect a clean slate. The session's current page may not match the `goto` target until after navigation completes.

#### Root Cause Analysis

Sessions persist across CLI invocations as a feature. The `goto` command auto-reconnects to existing sessions without prompting.

#### Code Pointer

``cli/browser4-cli/src/main.rs` — session auto-reconnect logic`

#### AI Suggested Improvement

- Add a `--new-session` flag to `goto` to force a fresh session
- Add `open --new` or `goto --fresh` to explicitly start a new browser context
- Document session persistence behavior prominently in the `--help` output and quick start

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: `summarize`, `agent run`, `agent status`, and `agent result` missing from top-level `--help`

Run `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help` and observe the Agent section.

#### Issue 2: `--schema` flag does not support file path input (`@file` pattern)

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- extract "extract data" --schema @schema.json
```

#### Issue 3: `--schema` uses non-standard ExtractionField format instead of JSON Schema

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- extract "data" --schema '{"type":"object","properties":{"name":{"type":"string"}}}'
```

#### Issue 4: Error messages displayed in Chinese

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- extract "data" --schema @file.json
```

#### Issue 5: `--filename` saves to `.browser4-cli/snapshot/` directory, not current working directory

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- extract "data" --filename my-results.json
ls my-results.json  # File not found in current directory
ls .browser4-cli/snapshot/my-results.json  # File is here
```

#### Issue 6: `agent result` returns empty object despite successful task completion

```bash
# Submit task
cargo run -- ... -- agent run "Navigate to https://en.wikipedia.org/wiki/Guido_van_Rossum, extract key biographical details..."
# → Task submitted: 6b7a7598-9af3-4c01-8568-dbb595d435b2
# Wait for completion, then:
cargo run -- ... -- agent status 6b7a7598-9af3-4c01-8568-dbb595d435b2
# → {"status":"OK","isDone":true,"instructResults":[{"name":"fields","statusCode":200,"resultType":"map"}]}
cargo run -- ... -- agent result 6b7a7598-9af3-4c01-8568-dbb595d435b2
# → {}
```

#### Issue 7: `agent list` shows "No tracked async tasks" for tasks that completed successfully

```bash
cargo run -- ... -- agent run "Navigate to example.com and extract..."
# Note the task ID from output
cargo run -- ... -- agent status <task-id>  # Works — shows task status
cargo run -- ... -- agent list  # → "No tracked async tasks."
```

#### Issue 8: Extract result wrapped in Java class serialization rather than clean JSON

```bash
cargo run -- ... -- extract "product name, price" --stdout
```

#### Issue 9: Session persistence creates confusing initial state for new users

Run `goto` on a fresh evaluation. The CLI reconnects to a pre-existing session from a previous evaluation with a different page loaded, showing "Reconnected to existing session on http://localhost:18080/generated/interactive-5.html".

