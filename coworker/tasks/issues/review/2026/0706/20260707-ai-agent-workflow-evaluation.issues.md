# Issues: AI Agent Workflow Evaluation

### Issue 1: `summarize` command not listed in `--help` output

**Severity:** High

**Category:** Discoverability

**Reproduction:** Run `cargo run -- --help` and observe the command listing. The `summarize` command exists and works but is completely absent from the main help output.

**Expected:** All top-level commands should be listed in `--help`. `summarize` should appear under the `Agent:` section alongside `extract`.

**Actual:** Only `extract` and `agent list` appear under `Agent:`. `summarize --help` works when invoked directly, proving the command exists. Users who only read `--help` would never discover the summarize feature.

**Root Cause:** The command registration in the CLI argument parser likely omits `summarize` from the help listing, or it's categorized under a hidden/non-listed group.

**Code Pointer:** (CLI argument parser — command registration / help text generation)

**AI Suggested Improvement:**
- Add `summarize` to the `Agent:` section of the main `--help` output
- Ensure all top-level commands appear in `--help` with consistent categorization

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: `agent run`, `agent status`, `agent result` invisible in CLI help

**Severity:** High

**Category:** Discoverability

**Reproduction:**
```
cargo run -- --help        # No agent run/status/result shown
cargo run -- agent --help  # Only shows "agent list"
```

**Expected:** `agent --help` should list all available subcommands: `run`, `status`, `result`, and `list`. The main `--help` should also list `agent` as having subcommands.

**Actual:** Only `agent list` is discoverable. Users must read `references/agent.md` to learn about `agent run`, `agent status`, and `agent result`. These critical commands are completely hidden from CLI discovery.

**Root Cause:** The agent subcommands (`run`, `status`, `result`) are likely registered differently from `agent list` in the CLI argument parser, or they're registered at a level that doesn't participate in help generation.

**Code Pointer:** (CLI argument parser — agent subcommand registration)

**AI Suggested Improvement:**
- Register `agent run`, `agent status`, and `agent result` as proper subcommands visible in `agent --help`
- Add a brief `Agent:` section in main `--help` showing: `agent run|status|result|list`
- Alternatively, make `agent` in `--help` link to `agent --help` for subcommand discovery

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: No `--schema-file` option for `extract` command

**Severity:** Medium

**Category:** UX / Documentation

**Reproduction:**
```
cargo run -- extract "..." --schema @schema.json   # Fails: '@' not valid JSON
cargo run -- extract "..." --schema-file schema.json  # Flag doesn't exist
```

**Expected:** Like `--sql @file.sql`, there should be a way to reference a schema file: either `--schema @file.json` or a dedicated `--schema-file file.json` flag.

**Actual:** Only inline `--schema '{"type":"object",...}'` is supported. Users with complex schemas must inline the entire JSON, causing shell quoting issues on Windows (as warned in SKILL.md §5).

**Root Cause:** The `--schema` parameter is parsed as a raw JSON string with no `@file` prefix detection. Unlike `--sql`, which has `@file`, `--stdin`, and `--base64` variants, schema input has only inline mode.

**Code Pointer:** (CLI argument handler for `extract --schema`)

**AI Suggested Improvement:**
- Support `--schema @file.json` syntax (consistent with `--sql @file.sql`)
- Or add a `--schema-file <path>` option
- Document both inline and file-based schema input in `extract --help`

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: Two conflicting schema formats documented for `extract`

**Severity:** Medium

**Category:** Documentation / UX

**Reproduction:**
1. Run `extract --help` — shows standard JSON Schema format: `{"type":"object","properties":{"name":{"type":"string"}}}`
2. Pass an invalid value (e.g., `--schema @file.json`) — error message shows a completely different format: `ExtractionField` with `fields` array and `ExtractionSchema` wrapper
3. Both formats appear to work (standard JSON Schema was accepted in the first extract test)

**Expected:** Consistent schema format documentation. If both formats are supported, both should be documented. If only one works, the other should not appear anywhere.

**Actual:** Users see standard JSON Schema in `--help` but get shown a proprietary `ExtractionField` format in the error message. This creates confusion about which format to use.

**Root Cause:** The `extract --help` text documents one schema format, while the error-handling code path renders help text with a different format. These may represent different code paths (perhaps a legacy and a current format) that diverged.

**Code Pointer:** (extract command handler — schema validation and error message generation)

**AI Suggested Improvement:**
- Unify on a single schema format and document it consistently in both `--help` and error messages
- If both formats are intentionally supported, document both and explain when to use each
- The `ExtractionField` format with `fields` array appears more powerful (supports nested objects/arrays) — consider making it the primary documented format

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: `--filename` flag ignores relative path components

**Severity:** Low

**Category:** Reliability

**Reproduction:**
```
cargo run -- extract "..." --filename ../../result.json
# File saved to: cli/browser4-cli/result.json (not ../../result.json)
```

**Expected:** `--filename ../../result.json` should save to the specified relative path, resolved from the current working directory.

**Actual:** The file was saved to `cli/browser4-cli/result.json` — the relative path `../../` prefix was ignored, and only the basename was used. The file was placed in the working directory or a default location.

**Root Cause:** The `--filename` handler may strip directory components from the path, treat the value as a bare filename, or resolve relative to a fixed directory (e.g., the snapshot directory) rather than the CWD.

**Code Pointer:** (extract command handler — filename resolution logic)

**AI Suggested Improvement:**
- Resolve `--filename` paths relative to the current working directory
- Or document clearly where files are saved (e.g., "saved to .browser4-cli/output/")
- Consider adding `--output-dir` to control the output directory separately

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: `--selector` for section-scoped summarization only captures the target element, not its content section

**Severity:** Medium

**Category:** UX / Product

**Reproduction:**
```
cargo run -- summarize "summarize this section" --selector "#History" --stdout
# Returns: "only the word 'History' was included without any accompanying text..."
```

**Expected:** `--selector "#History"` on a Wikipedia page should capture the entire History section (heading + all content paragraphs until the next section heading), or at minimum, the heading's parent container with its content siblings.

**Actual:** Only the `<span id="History">History</span>` heading text was extracted. The LLM received "History" as the sole input and couldn't summarize it.

**Root Cause:** The `--selector` implementation extracts only the HTML of the matched element(s) themselves, not their following siblings. On Wikipedia, `#History` is an ID on a `<span>` inside an `<h2>` — the actual section content (paragraphs, lists) are sibling elements after the heading, not children of it.

**Code Pointer:** (summarize command — selector content extraction logic)

**AI Suggested Improvement:**
- When `--selector` matches a heading element, automatically include following siblings until the next same-level heading (section-scoped extraction)
- Or document clearly that `--selector` only captures the matched element, and recommend using broader selectors (`.mw-parser-output`) with targeted NL instructions for section-specific summaries
- Add a `--selector-container` option that includes the matched element AND its next siblings

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: `agent list` shows stale/inconsistent task data after task completion

**Severity:** Medium

**Category:** Reliability

**Reproduction:**
1. Submit an agent task: `agent run "..."` → returns task ID `74a90a38-...`
2. Poll until completion: `agent status 74a90a38-...` → shows `isDone: true, status: OK`
3. Run `agent list` → shows a DIFFERENT task ID with status `running`

**Expected:** `agent list` should show the completed task with correct status (`OK`/`COMPLETED`) and consistent task IDs.

**Actual:** `agent list` showed a different UUID and listed the status as `running` even after our task had definitively completed.

**Root Cause:** Possible causes: (a) the list is populated from a different data store than the one used for status/result lookups; (b) task IDs are not being correctly tracked in the list registry; (c) the list is showing stale data from a previous session or run; (d) the `agent list` endpoint doesn't update status after initial registration.

**Code Pointer:** (agent task tracking — list/registry vs status/result data source)

**AI Suggested Improvement:**
- Ensure `agent list` reads from the same data store as `agent status` and `agent result`
- Update task status in the list registry when a task transitions to COMPLETED or FAILED
- Add a `--all` flag to show both running and completed tasks (if completed tasks are intentionally hidden)

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: Extract/agent output wraps data in non-standard JSON envelope requiring double-parsing

**Severity:** Low

**Category:** UX

**Reproduction:**
```
cargo run -- extract "..." --schema '{...}' --stdout
# Returns: {"type":"ai.platon.pulsar.agentic.ExtractResult","description":"success: true message: OK data: {\"name\":\"Python\",...}"}
```
The actual extracted data is an escaped JSON string embedded inside the `description` field.

**Expected:** With `--stdout` (or `--raw`), the output should be clean JSON matching the requested schema: `{"name":"Python","first_release_year":1991,...}`.

**Actual:** The data is triple-wrapped: outer JSON envelope → `description` string containing status text + escaped inner JSON. Machine consumers must parse the outer JSON, extract the `description` string, regex-extract the `data: {...}` portion, then parse the inner JSON. This is fragile and non-standard.

**Root Cause:** The `ExtractResult` serialization embeds the LLM response as an opaque description string rather than separating status/metadata from the structured data payload.

**Code Pointer:** (server-side `ExtractResult` serialization — `ai.platon.pulsar.agentic.ExtractResult`)

**AI Suggested Improvement:**
- Add a `data` field to the JSON envelope containing the parsed extraction result as a JSON object (not an escaped string)
- Structure output as: `{"status":"OK","data":{"name":"Python",...},"metadata":{"tokens":13597,"timeMs":32365}}`
- `--raw`/`--stdout` should output only the `data` object (no envelope)
- Keep the current envelope format for non-`--stdout` output or add `--envelope` flag for consumers that need metadata

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 9: `--schema` doesn't support `@file` syntax (inconsistency with `--sql @file`)

**Severity:** Low

**Category:** UX / Consistency

**Reproduction:**
```
cargo run -- extract "..." --schema @schema.json   # Fails with JSON parse error on '@'
```
Compare with:
```
cargo run -- htmlsnapshot query --sql @query.sql   # Works
```

**Expected:** Consistent file-reference syntax across all options that accept structured input. If `--sql` supports `@file`, `--stdin`, and `--base64`, then `--schema` should too.

**Actual:** Only inline JSON is supported for `--schema`. Users with complex schemas must deal with shell escaping.

**Root Cause:** The `@file` prefix detection is implemented for `--sql` but not generalized to `--schema` or other options.

**Code Pointer:** (CLI argument parser — `@file` prefix detection should be applied to `--schema`)

**AI Suggested Improvement:**
- Generalize the `@file` prefix detection to all options that accept structured text input
- At minimum, support `--schema @file.json` consistent with `--sql @file.sql`

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**
