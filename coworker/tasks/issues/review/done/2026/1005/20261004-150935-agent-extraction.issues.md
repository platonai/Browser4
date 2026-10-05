# Issues: agent-extraction

> **Source:** `20261004-150935-agent-extraction.full.md` | **Date:** 20261004-150935 | **Mode:** dev

## Scenario Background

### Task

All nine steps were attempted; six fully succeeded, two produced empty results due to a backend bug (data recovered from server logs), and no step was abandoned.

**Step 1 — Navigation:** `goto https://en.wikipedia.org/wiki/Python_(programming_language)` succeeded (backend auto-started in 6 s after a required bundle rebuild).

**Step 2 — Inline schema extract:** Returned exactly the five requested fields:


**Step 3 — Custom schema file:** `--schema @schema-python.json` (standard draft-07 schema, array-typed `typing_discipline`, `required` list). First two runs returned `{}` with "Extraction produced no data" (exit 0). The same command later succeeded. Final saved results: `extract-8-array-exact-retry.json`:

(saved in `.test-sessions/20261004T1433038523034Z/`, along with a flat-schema variant and the failed raw responses as evidence).

**Step 4 — Full-page summarize:** Produced a coherent multi-topic summary (`summarize-full.md`).

**Step 5 — Scoped summarize:** The obvious `--selector "#History"` silently summarized only the heading (the model replied "no History text was included—only the heading"). `:has(...)` and `:nth-of-type(...)` selectors returned "(no text content)" (exit 0) even though the identical selector worked in `htmlsnapshot get`. A working selector (`section#mwzg` — section ids are stable across reloads) produced a genuinely scoped, high-quality History summary (`summarize-history-final.md`).

**Steps 6–8 — Agent task:** Task `5c2a7000-3262-4c78-b644-53f6feabeb20` submitted at 15:03:08Z. The backend navigated to the Guido van Rossum page (HTTP 200, 467 KiB) and the LLM extracted a perfect result — which the backend then **discarded** (`FlatJSONExtractor ... Cannot deserialize String from Array value ... ["notable awards"]` → `fields: {}`). `agent result` returned `{}`. Recovered from backend logs: *full name Guido van Rossum; born 31 January 1956 in The Hague, Netherlands; Dutch; University of Amsterdam (BS; master's 1982); known for creating Python; awards — Dr. Dobb's Excellence in Programming (1999), FSF Award for the Advancement of Free Software (2001), NLUUG Award (2003), ACM Distinguished Engineer.* A control task without a URL (full autonomous agent path) completed correctly in ~90 s: `{"summary":"Guido van Rossum was born on 31 January 1956."}`. Note: `agent list` panicked (exit 101) when checking task tracking.

**Step 9 — Sync vs async comparison (evidence-based):**
- **`extract`/`summarize` (sync):** operate on the *current* page of a session, complete in ~5–15 s, return one-shot payloads. `extract` enforces schemas (use the compact `{fields:[...]}` format; standard JSON Schema mostly works but returned an intermittent empty result). Best for: known page, fixed fields, tight loops, pipeline steps. Weakness: cannot navigate, cannot interact; silent empty results are indistinguishable from "no data".
- **`agent run` (async):** 33–90 s per task, plans and executes multiple browser steps (it used Wikipedia's search box: step 1 click, step 2 fill, … 4 steps), returns natural-language summaries. Necessary when the page must be discovered/navigated/interacted with, or when the target isn't known up front. Weaknesses: results are unstructured (no schema), completion is gated by an internal evaluation LLM, and one bad field type (array) silently voids the entire result.
- **Rule of thumb:** use sync extract/summarize when you already have the page open and know what you want (cheap, deterministic-ish, schema-shaped); use `agent run` when the task needs navigation, search, or adaptation — and treat its output as best-effort prose, not structured data.

### Execution Context

**Key Commands:**

- `doctor` (before/after startup), `goto <wikipedia url>`, `reload`, `page-info`, `tab-list`, `list`
- `extract ... --schema '<inline>' --filename …`, `extract ... --schema @file --filename …` (×6 runs)
- `summarize ... --filename …` and `summarize ... --selector <css> --filename …` (×4 selector variants)
- `eval --file …` (DOM probes), `eval --file … --await` (raw-HTML fetch), `htmlsnapshot get text <css>`
- `agent run …` (×2), `agent status <id>` (×4), `agent result <id>` (×2), `agent list`, `agent list --json`
- Diagnostic: `powershell -File browser4-apps/browser4-bundle/build-runtime-bundle.ps1`; greps of backend `pulsar.log`; source inspection of `state.rs`, `main.rs`, `PromptBuilder.kt`, `StatefulPageVisitor.kt`

**Major steps / decisions:**
1. **Setup blocker:** first command refused to start — local bundle built from 4.13.23-SNAPSHOT vs checkout 4.13.27-SNAPSHOT. Rebuilt the runtime bundle (~5 min, exit 0), then the backend started in 6 s. The refusal message was exemplary (version, build time, exact rebuild command).
2. Chose a named session (`-s pywiki`) to avoid clobbering concurrent runs; verified DOM structure with `eval --file` before picking selectors (session/`-s` is a global flag placed before the command).
3. Step-3 empty results investigated by bisecting schema formats and re-running; discovered the empty result is **intermittent**, not schema-caused (an initial array-type hypothesis was disproven by a later success with the identical schema) — real cause visible in backend logs (fenced JSON response + extraction-evaluation round answering `completion:false`; result discarded).
4. Agent task empty result investigated through backend log (`session=DEFAULT`, page visit 200/467 KiB, LLM result, `FlatJSONExtractor` Jackson error on array value → `fields: {}`). Ran a second, scalar-only agent task as a control (succeeded) to isolate the trigger.
5. `agent list` crash root-caused to a byte-index UTF-8 slice in `state.rs:1209`; `--json` variant silently returns `{}`.

**Workarounds required:** manual bundle rebuild; writing outputs to the scratch dir with `--filename` (default saves under `.browser4-cli/snapshot/` in the repo); avoiding `agent list` (it crashes); phrasing agent tasks without arrays; using `section#mwzg`/`section:nth-of-type(2)`-free selectors for `summarize`; reading the backend log to recover an agent result the CLI reported as empty.

**Evidence files in `.test-sessions/20261004T1433038523034Z/`:** `extract-*.json` (8 runs incl. failures), `summarize-*.md` (4), `schema-*.json` (3), `evidence-agent-task-5c2a7000.log`, `evidence-agent-task-control.log`, `bundle-rebuild.log`, `raw-page.html` (fetch attempt, timed out), `probe-*.js`, `check-history*.js`.

json markdown fence (len 272), and (2) the extraction-evaluation round (PromptBuilder.buildExtractionEvaluationSystemPrompt, viewport-completion check) answered {\"completion\": false}; the pipeline then returned an empty result instead of the already-extracted data. Which stage actually drops it (fenced-JSON parsing vs viewport-evaluation gating with no continuation extraction) needs one more check — the log has both events at the same timestamp. Note: consecutive identical retries can be masked by the response cache (CachedBrowserChatModel).",
      "codePointer": "browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/inference/PromptBuilder.kt:558 (buildExtractionEvaluationSystemPrompt) and the extract result-merge path in InferenceEngine.kt / BasicBrowserAgent.kt:300",
      "suggestion": "- Robustly strip markdown code fences before parsing any LLM JSON payload\n- When the evaluation returns completion=false but no further viewport extraction is performed, keep and return the data already extracted instead of discarding it\n- Write the model's raw response (not the parsed result) to the '[Raw response]' file, and make the CLI warning distinguish 'model returned nothing' from 'pipeline dropped a result'\n- Retry extraction once automatically on parse/evaluation failure before returning {}"
    },
    {
      "title": "summarize --selector silently fails for selectors that htmlsnapshot resolves, and can silently scope to the wrong element",
      "severity": "Medium",
      "category": "Product",
      "reproduction": "./b4w.ps1 -s pywiki summarize \"Summarize the History section of the Python article\" --selector \"section:nth-of-type(2)\"  -> file contains '(no text content)', exit 0\n./b4w.ps1 -s pywiki htmlsnapshot get text \"section:nth-of-type(2)\"  -> returns the full ~3 KB History section text\n./b4w.ps1 -s pywiki summarize \"Summarize the History section\" --selector \"#History\"  -> LLM receives only the word 'History' (backend log shows the prompt containing just 'History') and answers that no section text was included\n:has(> div > h2#History) also returns '(no text content)' while document.querySelector resolves it in eval.",
      "expected": "The same CSS selector support as the documented 'CSS selector' contract (htmlsnapshot/eval), or an explicit error/warning naming the unsupported selector construct; scoping to an element whose text is trivially short should warn instead of silently summarizing the wrong thing.",
      "actual": "Pseudo-class selectors (:nth-of-type, :has) silently match nothing and yield '(no text content)' with exit 0; the common container-id selector silently matches only the heading. Users get a plausible-looking 'summary' file that is useless or wrong, with no signal that scoping failed.",
      "rootCause": "summarize scopes content via activeDriver.textContent(selector) -> selectFirstTextOrNull, a tree-scoped selection engine with a limited CSS subset (no pseudo-classes), while htmlsnapshot get uses the live-DOM querySelector path (full modern CSS, Chrome). The two engines diverge for the same selector string, and the summarize path has no zero-match diagnostic (the backend just returns null -> '(no text content)'). Wikipedia's 2025 section wrapping makes this acute: section wrappers carry only stable-looking but non-obvious ids (section#mwzg) with no classes, so the obvious #History scopes only the heading.",
      "codePointer": "browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/agents/BasicBrowserAgent.kt:313-318 (summarize -> driver.textContent(selector) -> AbstractWebDriver.textContent/selectFirstTextOrNull)",
      "suggestion": "- Route summarize --selector through the same live-DOM engine as htmlsnapshot get (or document the supported selector subset and reject unsupported syntax explicitly)\n- Emit a warning/error when the selector matches 0 elements or when the matched element's text is implausibly short, instead of returning '(no text content)' with exit 0\n- Make selectors that match multiple elements either error or report which element was chosen (first-match is currently silent)"
    },
    {
      "title": "agent list --json returns an empty object instead of the task list",
      "severity": "Medium",
      "category": "Product",
      "reproduction": "./b4w.ps1 agent list --json\nObserved: {\"status\":\"ok\",\"command\":\"agent-list\",\"output\":{}} — exit 0 (while plain `agent list` crashes with the UTF-8 panic).",
      "expected": "A JSON payload containing the tracked agent tasks (analogous to tab-list's {output:{tabs:[...],count}}), suitable for scripts.",
      "actual": "output is an empty object; scripts see 'no tasks' even when 65 tasks are tracked, silently contradicting the text mode.",
      "rootCause": "handle_agent_list() renders the entries with cli_println!(format_async_task_list(...)) and summarize_async_tasks(); those human-readable lines are suppressed under --json, and the function never calls json_field() for the list payload (only for the --clear branch), so the JSON envelope stays empty.",
      "codePointer": "cli/browser4-cli/src/main.rs:11053 (handle_agent_list; text output via cli_println!, no json_field for tasks)",
      "suggestion": "- Emit json_field(\"tasks\", serde_json::to_value(&filtered)?) plus total/limit/offset before the human-readable output, mirroring tab-list\n- Add a test asserting `agent list --json` output contains a non-empty tasks array when tracked tasks exist\n- Decide and document the error contract: with --json, report failures as an error field instead of ok+empty"
    },
    {
      "title": "agent run: undocumented routing by task phrasing and session scoping (-s is ignored; tasks run in session=DEFAULT)",
      "severity": "Medium",
      "category": "Documentation",
      "reproduction": "./b4w.ps1 -s pywiki agent run \"Navigate to https://en.wikipedia.org/wiki/Guido_van_Rossum and extract ...\"\nBackend log: 'Submitting page visit task 5c2a7000-... (session=DEFAULT, url=https://en.wikipedia.org/wiki/Guido_van_Rossum)' — the pywiki session's tab never moved (page-info/tab-list still show the Python article).\n./b4w.ps1 -s pywiki agent run \"What is the birth date of Guido van Rossum? ...\"\nBackend log: 'Submitting agent task 21b5e1ea-... (session=DEFAULT)' — full PerceptiveAgent lifecycle (agentHistory steps: click e21, fill e21 with a search query, ...).",
      "expected": "One documented execution model for agent run; the -s session (its browser profile, cookies, login state) should apply to the task's browser work, or the docs should say it does not.",
      "actual": "Two different pipelines are chosen by whether the task text contains a URL (fetch+rule-extraction 'page visit task' vs autonomous PerceptiveAgent), with different result shapes and failure modes; both run in the backend's DEFAULT privacy context (C:\\Users\\pereg\\.browser4\\browser\\chrome\\default), not the named session, so auth state prepared in a named session would not be visible to the agent.",
      "rootCause": "UserCommandExecutor (backend) parses the natural-language task: when a URL is present it converts the task into a page visit with dataExtractionRules (no agent planning, no agentHistory); otherwise it creates a PerceptiveAgent task. The task's session id is not propagated from the CLI -s flag; work happens in the backend default privacy context. The CLI help/docs describe only the autonomous model ('The agent reasons about the page, discovers elements, and adapts').",
      "codePointer": "Backend routing: ai.platon.pulsar.agentic.tools.UserCommandExecutor ('Submitting page visit task' / 'Submitting agent task' branches); CLI side: handle_agent_run in cli/browser4-cli/src/main.rs",
      "suggestion": "- Document the two routing modes in the CLI help for agent run, including that a URL in the task text selects the page-visit/extraction pipeline\n- Propagate the -s/--session identity to the backend agent task so the intended browser profile is used (or print a warning that the task will use the DEFAULT context)\n- Consider exposing the URL/ex extraction rules as explicit flags (e.g. --url/--extract) so the routing is deterministic rather than inferred from prose"
    },
    {
      "title": "agent status reports contradictory state: message 'Task is in progress' alongside processState completed / isDone true",
      "severity": "Low",
      "category": "Reliability",
      "reproduction": "./b4w.ps1 -s pywiki agent status 5c2a7000-3262-4c78-b644-53f6feabeb20\nPayload contains: \"processState\":\"completed\", \"isDone\":true, \"finishTime\":\"...\", \"message\":\"Task is in progress\".",
      "expected": "message reflects the terminal state (or an explicit note about why the result is empty).",
      "actual": "A user or script that reads message (rather than isDone) believes the task is still running; the docs acknowledge the isDone/completed-vs-done inconsistency but not this message contradiction.",
      "rootCause": "The status message carries the last agent progress event text emitted before completion and is not overwritten on the terminal transition; for the control task the message later held the actual answer, so its semantics vary by path.",
      "codePointer": "Backend command_status payload assembly (a.p.p.r.m.c.MCPToolController command_status / task state model)",
      "suggestion": "- Set message to a terminal description ('completed', 'failed: <reason>') when the task reaches a terminal state, or drop message from terminal payloads\n- Have the CLI render a clear one-line status (COMPLETED/FAILED + result summary) rather than relaying raw JSON for human-readable invocations"
    },
    {
      "title": "Misleading page metadata in agent status for successful tasks (pageStatusCode 201, pageContentBytes 0)",
      "severity": "Low",
      "category": "Reliability",
      "reproduction": "./b4w.ps1 -s pywiki agent status 21b5e1ea-8ea4-4f79-9b7b-89dbe625274b (completed successfully)\nPayload shows \"pageStatusCode\": 201, \"pageContentBytes\": 0 although the run fetched/parsed pages.",
      "expected": "Real page status/counts or the fields omitted when not applicable.",
      "actual": "201/'Created' and 0 bytes are reported for a task that fetched real content; monitoring scripts keying on pageStatusCode will misclassify.",
      "rootCause": "The agent (PerceptiveAgent) path does not populate the page-visit bookkeeping fields used by the page-visit pipeline; placeholder/default values are serialized anyway. Needs confirmation in the status assembly code.",
      "codePointer": "",
      "suggestion": "- Populate pageStatusCode/pageContentBytes from the actual last page visit on the agent path, or omit both fields when unknown\n- If placeholders are intentional, document their meaning in the agent-status help"
    },
    {
      "title": "Server-start spinner floods non-interactive output with raw ANSI escape frames",
      "severity": "Low",
      "category": "UX",
      "reproduction": "First ./b4w.ps1 command from a non-TTY context (Git Bash capture): output contains hundreds of lines like \"[K  ⠋ Starting server... (0s) — JVM loading, waiting for TCP port...[K  ⠙ Starting server...\" before 'Server ready in 6.0s'.",
      "expected": "A single 'Starting server...' line (or a live spinner only on a TTY).",
      "actual": "Every spinner frame (with ESC[K erase codes) is emitted into pipes/logs, garbling captured output and bloating logs.",
      "rootCause": "The startup spinner unconditionally redraws with ANSI erase sequences and does not detect that stdout/stderr are not a terminal (no isatty check, or it is bypassed through the pwsh child-process pipeline).",
      "codePointer": "CLI server-start path (spinner rendering, likely near the 'Starting Browser4 server' message in cli/browser4-cli/src/main.rs)",
      "suggestion": "- Detect TTY (isatty) and fall back to a single-line or periodic (every 5-10 s) status message when not interactive\n- Honor a BROWSER4_CLI_NO_SPINNER / --no-spinner opt-out; strip control codes when not a TTY"
    },
    {
      "title": "Printed output-file links mix path separators",
      "severity": "Low",
      "category": "UX",
      "reproduction": "./b4w.ps1 -s pywiki extract ... --filename \".test-sessions/20261004T1433038523034Z/extract-1-inline-schema.json\"\nPrinted link: [Extracted content](D:\\workspace\\Browser4\\Browser4-4.13\\.test-sessions/20261004T1433038523034Z/extract-1-inline-schema.json)",
      "expected": "A normalized path (all \\ or all /) or a file:// URL that editors/terminals can open reliably.",
      "actual": "Mixed separators in the same path; some terminals/tools fail to resolve such links.",
      "rootCause": "The Windows absolute path is joined with the user-supplied relative path without canonicalization before display.",
      "codePointer": "CLI file-output link formatting shared by extract/summarize/htmlsnapshot export",
      "suggestion": "- Canonicalize the path for display (e.g. PathBuf::canonicalize or manual normalization) before printing the link\n- Optionally emit an OSC 8 hyperlink with a file:// URL when the terminal supports it"
    }
  ],
  "assessment": {
    "completionStatus": "Successful with interruptions — all 9 steps were performed. Steps 1-2 fully succeeded; step 3 succeeded after two silent empty results (exit 0) on the same command; step 4 succeeded; step 5 succeeded only after working around an undocumented selector-engine limitation (the obvious selector silently summarized just the section heading); steps 6-8 completed but the required agent result came back empty because the backend discarded a correct extraction (array-valued field parse failure) — the data was recovered from the backend log, and a control agent task proved the autonomous path works; step 9 analysis delivered.",
    "successRate": "85% — 8 of 9 task steps produced their intended outcome; step 6-8's user-visible result was empty (data recovered via logs), and step 3 needed a retry after two empty results.",
    "issuesFound": 10,
    "majorBlockers": "1) First command hard-failed: the local runtime bundle (4.13.23-SNAPSHOT) predated the checkout (4.13.27-SNAPSHOT) and dev mode refused to start anything until a ~5-minute manual Maven rebuild (error message itself was excellent). 2) The agent task result was silently discarded server-side (FlatJSONExtractor cannot handle array fields) and the CLI's empty-result hint points at a recovery path (instructResults) that is also empty. 3) agent list is unusable: it panics (exit 101) on CJK descriptions and its --json mode returns {}.",
    "mostConfusingAspects": "Silent empty outputs are indistinguishable from 'no data on the page' (extract returned {} on a valid page twice, with a warning that blames the model/page); summarize --selector resolves different CSS than htmlsnapshot get and returns '(no text content)' with exit 0 for selectors that demonstrably match; a plain '#History' selector 'succeeds' while summarizing only the heading; agent run silently switches execution models depending on whether the task text contains a URL, and ignores -s (runs in session=DEFAULT); agent status simultaneously says completed and 'Task is in progress'.",
    "mostValuableImprovements": "Never return an empty success: always surface the model's raw output (or the pipeline's discard reason) and add one automatic retry when parsing/evaluation fails. Fix the UTF-8 byte-slice panic and emit a real JSON task list so agent tracking is scriptable. Unify (or clearly bound and document) the selector engine used by summarize --selector so a selector behaves the same across commands, and warn whenever a selector matches zero elements or only trivially short text. Propagate -s to agent tasks and document the URL-vs-agent routing. Normalize first-run in a dev checkout (auto-rebuild the stale bundle with consent, or say the rebuild will run).",
    "usabilityRating": 5
  }
}
```

---

## Issues Found (2 issues)

### Issue 1: agent list panics (exit 101) on non-ASCII task descriptions

**Severity:** Critical
**Category:** Reliability

#### Reproduction

./b4w.ps1 agent list  (deterministic on this machine: 64 tracked tasks exist, at least one description contains CJK text)
Observed: thread 'main' panicked at src\state.rs:1209:40: end byte index 39 is not a char boundary; it is inside '到' (bytes 38..41 of string) — exit code 101.

#### Expected Behavior

The tracked agent task table is printed (descriptions truncated for width), exit 0.

#### Actual Behavior

The CLI panics and prints a Rust backtrace hint; exit code 101. The documented command is completely unusable, and the crashing task entry can never be inspected or cleared through the CLI.

#### Root Cause Analysis

In format_async_task_list(), the description is truncated with byte semantics: `if desc.len() > desc_w { desc = format!("{}…", &desc[..desc_w - 1]); }`. desc.len() counts bytes and the slice index is a byte offset; when that offset falls inside a multi-byte UTF-8 character (any CJK character, e.g. '到' at bytes 38..41), the slice panics. ASCII-only descriptions never trigger it, so it hides on English test data.

#### Code Pointer

`cli/browser4-cli/src/state.rs:1209 (format_async_task_list)`

#### AI Suggested Improvement

- Replace byte slicing with char-aware truncation: `if desc.chars().count() > desc_w { desc = desc.chars().take(desc_w.saturating_sub(1)).collect::<String>() + "…"; }`
- Add a regression test with a CJK/emoji description longer than desc_w (and a tested guard for desc_w == 0)
- Consider char-width-aware padding (unicode-width) so CJK rows align, since `{:<w$}` pads by chars not display width

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: Agent task result silently discarded when the model returns an array-valued field

**Severity:** Critical
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s pywiki agent run "Navigate to https://en.wikipedia.org/wiki/Guido_van_Rossum and extract key biographical details (full name, birth date and place, nationality, education, known for, notable awards), then return them as a concise list"
# -> task id 5c2a7000-3262-4c78-b644-53f6feabeb20; poll agent status until isDone; then agent result <id>
Backend log (pulsar.log): FlatJSONExtractor - Failed to parse flat JSON block length=686 error=Cannot deserialize value of type `java.lang.String` from Array value (token `JsonToken.START_ARRAY`) ... (through reference chain: java.util.LinkedHashMap["notable awards"]) ; then "fields: {}"

#### Expected Behavior

agent result returns the extracted biographical details (the model produced a complete, correct object including a list of awards).

#### Actual Behavior

agent result prints `{}` with 'The result is empty ({}). The agent task may have completed successfully but the extracted data was not serialized into commandResult'. The full extraction is present in the backend log but never surfaced to the user; instructResults is also empty, so the CLI's suggested recovery path shows nothing.

#### Root Cause Analysis

The page-visit extraction path deserializes the model's JSON into Map<String,String>. A JSON array value ("notable awards": [...]) raises a Jackson MismatchedInputException; FlatJSONExtractor catches it, logs a warning, and returns an empty map, so the entire (otherwise valid) result becomes {}. Any list-valued field — e.g. the docs' own example 'top 5 product titles and prices' — triggers total data loss.

#### Code Pointer

`browser4-agent-tools/src/main/kotlin/ai/platon/pulsar/agentic/tools/advanced/crawl/StatefulPageVisitor.kt:282 (FlatJSONExtractor.extract call; FlatJSONExtractor lives in ai.platon.pulsar.common.serialize.json)`

#### AI Suggested Improvement

- Deserialize into Map<String, Any?> / JsonNode instead of Map<String,String> and stringify non-scalar values (or keep them as nested JSON)
- On parse failure, retry once with a repair instruction ('return scalar fields only') and, if it still fails, put the raw model output and the error into commandResult so `agent result` can show it
- Add a test covering a schema/field whose value is a JSON array, and make the CLI's empty-result hint name the real cause when the backend reports a parse failure

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
2. The CLI is invoked via `./b4w.ps1` (PowerShell) or `./b4w.sh` (Bash / Git Bash), which auto-build from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root:

   - **PowerShell:** `./b4w.ps1 <command>`
   - **Bash / Git Bash:** `./b4w.sh <command>`
   - **Direct:** `browser4-cli <command>` (if installed globally)

   > **Note:** `$(./b4w.ps1)` is command substitution in bash — do NOT use it.

### Per-Issue Reproduction Steps

#### Issue 1: agent list panics (exit 101) on non-ASCII task descriptions

./b4w.ps1 agent list  (deterministic on this machine: 64 tracked tasks exist, at least one description contains CJK text)
Observed: thread 'main' panicked at src\state.rs:1209:40: end byte index 39 is not a char boundary; it is inside '到' (bytes 38..41 of string) — exit code 101.

#### Issue 2: Agent task result silently discarded when the model returns an array-valued field

./b4w.ps1 -s pywiki agent run "Navigate to https://en.wikipedia.org/wiki/Guido_van_Rossum and extract key biographical details (full name, birth date and place, nationality, education, known for, notable awards), then return them as a concise list"
# -> task id 5c2a7000-3262-4c78-b644-53f6feabeb20; poll agent status until isDone; then agent result <id>
Backend log (pulsar.log): FlatJSONExtractor - Failed to parse flat JSON block length=686 error=Cannot deserialize value of type `java.lang.String` from Array value (token `JsonToken.START_ARRAY`) ... (through reference chain: java.util.LinkedHashMap["notable awards"]) ; then "fields: {}"

