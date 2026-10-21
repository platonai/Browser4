# Issues: crawl-sql-formats

> **Source:** `20261013-142336-crawl-sql-formats.issues.json` | **Date:** 20261013-142336 | **Mode:** dev

## Scenario Background

### Task

Both acceptance criteria pass, plus the step-4 cross-check.

**AC5 — SQL from file with CSV output: PASS.** The crawl run with `--seed-file … --sql @extract.sql --format csv -o results.csv` exited 0. Output confirmed the crawl finished and extraction was active (`X-SQL extraction: enabled`, `2 pages crawled, 2 rows extracted.`, `Results written to …/results.csv`). The CSV is non-empty with a header row and one data row per seed URL:

```
url,title,price
http://localhost:18080/generated/crawl/product/1.html,Widget Alpha,$10.00
http://localhost:18080/generated/crawl/product/2.html,Widget Beta,$20.00
```

**AC6 — SQL from stdin with table format: PASS.** `--sql-stdin --format table < extract.sql` exited 0 and printed an aligned table on **stdout** (progress messages correctly went to stderr):

```
  url                                                   | title        | price
  ------------------------------------------------------+--------------+-------
  http://localhost:18080/generated/crawl/product/1.html | Widget Alpha | $10.00
  http://localhost:18080/generated/crawl/product/2.html | Widget Beta  | $20.00
```

**Step 4 — comparison: PASS.** The re-run (`results2.csv`) is byte-identical to `results.csv`; the table output normalized to CSV is byte-identical to `results.csv` as well. A third run using relative paths (`results3.csv`) is also identical, confirming the extraction result does not depend on how the SQL is provided.

### Execution Context

**Preparation** — Confirmed CWD was the repo root (`pwd`); ran `./b4w.ps1 help`, read `skills/browser4-cli/SKILL.md` fully, then `./b4w.ps1 help crawl` and `skills/browser4-cli/references/crawl.md` for the crawl/X-SQL contract. Read `AGENTS.md` as instructed by the task note. MockSite was already up (`curl` to `http://localhost:18080/generated/crawl/product/1.html` → HTTP 200), so no start was needed; I verified both fixture pages contain `#productTitle` / `#product-price` before relying on them.

**Artifacts** (all under `.test-sessions/20261013T0154334271610Z/ac5-ac6/`): `seed-urls.txt`, `extract.sql` (`SELECT DOM_BASE_URI(dom) AS url, DOM_FIRST_TEXT(dom, '#productTitle') AS title, DOM_FIRST_TEXT(dom, '#product-price') AS price FROM DOM_LOAD_AND_SELECT(@url, 'body')`), `results.csv`, `re...

(truncated — see 20261013-142336-crawl-sql-formats.issues.json for the complete execution trace)

---

## Issues Found (4 issues)

### Issue 1: `--help --examples` reports "is the backend running?" on a healthy backend, and tool-level examples can never be fetched for core commands

**Severity:** Medium
**Category:** Reliability

#### Reproduction

With the dev backend healthy (`./b4w.ps1 status` shows `Server health: UP`; crawls succeed), run:
  ./b4w.ps1 snapshot --help --examples
  ./b4w.ps1 crawl --help --examples
Confirm the endpoint directly:
  curl -s http://127.0.0.1:8283/mcp/tools/specs   # -> HTTP 200 {"tools": []}
  curl -s http://127.0.0.1:8283/mcp/tools         # -> full tool-name list (open_session, browser_navigate, ...)

#### Expected Behavior

Tool-level examples fetched from the backend (the flag's own help says: "Print runnable usage examples of the tool the command maps to (needs the backend)"), or a truthful reason why none are available.

#### Actual Behavior

Both commands print: `Tool-level examples are unavailable: is the backend running?` — even though the backend is up and the commands themselves work. The only examples ever shown are the static built-in ones; the advertised tool-level examples never appear.

#### Root Cause Analysis

Two sides disagree. CLI: build_tool_examples() fetches GET {base}/mcp/tools/specs via fetch_tool_spec_values_within(), which is deliberately best-effort — connection error, timeout, malformed body and a successful-but-empty `tools` array all collapse into an empty Vec; an empty list is then mapped to the single reason "is the backend running?" (main.rs:21333-21334), so a healthy backend that simply returns no specs is misreported as down. Backend: MCPToolController.listCliToolSpecs() (KDoc: "List plugin tool specs that declare a named CLI command") filters specs to `!it.cliName.isNullOrBlank()` — i.e. only plugin-declared commands — so core tools (browser_snapshot, click, ...) are never in this payload; empirically the endpoint returns `{"tools": []}` while /mcp/tools returns the full list. Net effect: the tool-level-examples capability is dead in practice and its failure is attributed to a backend outage.

#### Code Pointer

`cli/browser4-cli/src/main.rs:21276 (build_command_examples) / cli/browser4-cli/src/main.rs:21327-21334 (build_tool_examples) / cli/browser4-cli/src/main.rs:20996-21017 (fetch_tool_spec_values_within); browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt:640 (listCliToolSpecs)`

#### AI Suggested Improvement

- Distinguish cause from effect: only use "is the backend running?" when the HTTP request actually failed; when the request succeeded but the specs list is empty, say "the backend advertises no example specs for '<tool>'" (the distinct wording already exists for "the backend does not advertise 'browser_click'").
- Provide the tool-level examples the flag promises — e.g. serve example-bearing specs for core MCP tools from a dedicated endpoint (the CLI tests at main.rs:32070 already assume such a payload, so tests and backend currently disagree).
- Add an e2e check that runs `--help --examples` against a live backend and fails if a healthy run reports "is the backend running?".

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: `--sql-base64` is documented inconsistently: value-taking option vs boolean flag

**Severity:** Low
**Category:** Documentation

#### Reproduction

Compare the two official surfaces:
  ./b4w.ps1 crawl --help            # "--sql-base64 <base64>  Base64-encoded X-SQL query (avoid shell quoting issues on Windows)"
  skills/browser4-cli/references/crawl.md (Flags > X-SQL and output flags)  # "| `--sql-base64` | bool | — | Base64-decode the query value before execution |"

#### Expected Behavior

One documented contract for the flag, matching between `--help` and the skill reference.

#### Actual Behavior

The CLI help presents `--sql-base64` as taking a base64 value; the reference table presents it as a boolean modifier that decodes the `--sql` value. The CLI in fact accepts both shapes (string value -> `sqlBase64` = that value; bare flag -> `sqlBase64` = true, decoded against the `--sql`/stdin text), so each document describes only half of the behavior and users cannot tell which form is correct.

#### Root Cause Analysis

The option is intentionally dual-shaped at the parser level (commands.rs tool_params_fn: `get_opt_str(...)` first, `else get_bool(...)`), but doc drift left the OptionDef (`sql-base64 <base64>`, value form) and the reference table (bool form) out of sync. This directly shadows the task's own feature area — the three documented ways to feed X-SQL (--sql @file, --sql-stdin, --sql-base64).

#### Code Pointer

`cli/browser4-cli/src/commands.rs:3841 (crawl CommandDef, sql-base64 option + tool_params_fn) and skills/browser4-cli/references/crawl.md (X-SQL and output flags table)`

#### AI Suggested Improvement

- Pick one canonical form for crawl and align both surfaces; the boolean modifier style also exists for `htmlsnapshot query --sql-base64`, which argues for standardizing on it.
- If both forms are intentionally supported, document both explicitly in `--help` and the reference (e.g. "--sql-base64 <base64>, or as a bare flag to decode the --sql/stdin value") and add a unit test pinning each shape.
- Check the sibling surfaces (swarm submit/query, htmlsnapshot query) for the same drift.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: Help alias line advertises `crawl=swarm`, but the alias can never fire

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 help            # "Short aliases: ...  crawl=swarm  ..." in the categories section
  ./b4w.ps1 --help crawl    # prints the crawl COMMAND page (browser4-cli crawl [url] ...)
  ./b4w.ps1 --help ss       # contrast: a working alias prints the Snapshot CATEGORY

#### Expected Behavior

Either `--help crawl` shows the Swarm category as the alias line claims, or the alias is not advertised.

#### Actual Behavior

`--help crawl` prints the crawl command page; the advertised alias is dead. The line also reads as if `crawl` were a synonym/alias of `swarm`, blurring two distinct commands (crawl = recursive crawling; swarm = parallel contexts) for a first-time user scanning the alias list.

#### Root Cause Analysis

CATEGORY_ALIASES contains ("crawl", "swarm") in help.rs:208, even though the same table's own comment (help.rs:195-198) documents the rule that an alias whose name is an exact command can never fire — that reasoning removed the `extract` alias but was not applied to `crawl`. Because `crawl` is a CommandDef (commands.rs:3841), help-target resolution matches the command before any category alias is consulted.

#### Code Pointer

`cli/browser4-cli/src/help.rs:208 (CATEGORY_ALIASES) and the alias rendering in generate_help() at cli/browser4-cli/src/help.rs:455-465`

#### AI Suggested Improvement

- Remove ("crawl", "swarm") from CATEGORY_ALIASES (same fix rationale as `extract`), or rename the alias to a token that is not a command (e.g. `crawling`).
- Consider adding a short note where crawl is listed (e.g. "the crawl family is listed under the Swarm category") so the grouping stays discoverable without a fake alias.
- Add a unit test asserting that every CATEGORY_ALIASES entry resolves to its canonical category via the `--help <alias>` path, so dead aliases cannot be reintroduced.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: Conflicting Git Bash invocation guidance between ./b4w.ps1 and ./b4w.sh

**Severity:** Low
**Category:** Documentation

#### Reproduction

Read AGENTS.md > "Invocation wrappers" (Git Bash row: `./b4w.sh <command>` — "Quotes args automatically for pwsh safety") and the b4w.sh header ("Running `./b4w.ps1` directly from Git Bash may cause the shell working directory to reset to the user home directory after each command ... This wrapper avoids that"), then compare with the run/scenario instructions that mandate `./b4w.ps1 <command>` from Git Bash.

#### Expected Behavior

One authoritative answer for Git Bash users (or an explicit statement of when each wrapper applies), especially since file arguments like --seed-file/--sql @file/-o resolve against the CLI working directory.

#### Actual Behavior

Repo docs steer Git Bash users to ./b4w.sh and warn that the direct ./b4w.ps1 form can silently change the working directory, while other instructions require ./b4w.ps1. In this environment every ./b4w.ps1 invocation from Git Bash worked correctly — including a relative-path crawl whose output file was written exactly where expected (`Results written to .test-sessions/.../results3.csv`) — so the warning may be stale, but the documents contradict each other and a first-time user cannot tell which guidance is current.

#### Root Cause Analysis

b4w.sh carries a historical MSYS/PowerShell CWD caveat in its header that was never reconciled with AGENTS.md (which lists both wrappers without stating precedence) or with current b4w.ps1 behavior; no test or doc update has retired the caveat. Root-causing whether the reset still reproduces needs a matrix of Git Bash + PowerShell versions (was not reproducible here).

#### Code Pointer

`b4w.sh (header comment) / AGENTS.md ("Invocation wrappers" table)`

#### AI Suggested Improvement

- Re-test the claimed CWD reset on current Git Bash + PowerShell 7; either delete the warning or scope it to the exact versions/shells affected.
- Make AGENTS.md and the b4w.sh header agree on a single recommended Git Bash invocation, and state that relative --seed-file/--sql/-o paths resolve against the invoking shell's working directory.
- Add a smoke check (or e2e scenario) that runs one relative-path file-writing command via ./b4w.ps1 from a Git Bash context so doc claims stay pinned to behavior.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — AC5 (SQL from file, CSV output), AC6 (SQL from stdin, table output) and the step-4 cross-check all passed; no retries or workarounds were needed for the task itself.

**Success Rate:** 100% of task steps (5/5 crawl invocations, including the missing-file error probe and relative-path re-run, behaved as documented and exited with the correct codes)

**Issues Found:** 4

**Most Confusing Aspects:** For a first-time user: (1) which wrapper to trust from Git Bash — AGENTS.md/b4w.sh point to ./b4w.sh while other instructions mandate ./b4w.ps1 and warn the latter may relocate the working directory; (2) the help alias line `crawl=swarm`, which suggests crawl is a swarm alias and then does not behave that way; (3) `--help --examples` answering with "is the backend running?" while the backend is healthy — a natural cue to start debugging the wrong thing.

**Most Valuable Improvements:** Fix the `--help --examples` false "is the backend running?" message and either serve the promised tool-level examples or say truthfully that none are advertised; reconcile the `--sql-base64` contract between `crawl --help` and the skill reference; drop or repair the dead `crawl=swarm` help alias. The core crawl/X-SQL experience needed no improvement — progress reporting, stream separation (results on stdout, progress on stderr), exit codes and seed/SQL validation errors were all clear and correct.

**Usability Rating:** 8/10

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

#### Issue 1: `--help --examples` reports "is the backend running?" on a healthy backend, and tool-level examples can never be fetched for core commands

With the dev backend healthy (`./b4w.ps1 status` shows `Server health: UP`; crawls succeed), run:
  ./b4w.ps1 snapshot --help --examples
  ./b4w.ps1 crawl --help --examples
Confirm the endpoint directly:
  curl -s http://127.0.0.1:8283/mcp/tools/specs   # -> HTTP 200 {"tools": []}
  curl -s http://127.0.0.1:8283/mcp/tools         # -> full tool-name list (open_session, browser_navigate, ...)

#### Issue 2: `--sql-base64` is documented inconsistently: value-taking option vs boolean flag

Compare the two official surfaces:
  ./b4w.ps1 crawl --help            # "--sql-base64 <base64>  Base64-encoded X-SQL query (avoid shell quoting issues on Windows)"
  skills/browser4-cli/references/crawl.md (Flags > X-SQL and output flags)  # "| `--sql-base64` | bool | — | Base64-decode the query value before execution |"

#### Issue 3: Help alias line advertises `crawl=swarm`, but the alias can never fire

./b4w.ps1 help            # "Short aliases: ...  crawl=swarm  ..." in the categories section
  ./b4w.ps1 --help crawl    # prints the crawl COMMAND page (browser4-cli crawl [url] ...)
  ./b4w.ps1 --help ss       # contrast: a working alias prints the Snapshot CATEGORY

#### Issue 4: Conflicting Git Bash invocation guidance between ./b4w.ps1 and ./b4w.sh

Read AGENTS.md > "Invocation wrappers" (Git Bash row: `./b4w.sh <command>` — "Quotes args automatically for pwsh safety") and the b4w.sh header ("Running `./b4w.ps1` directly from Git Bash may cause the shell working directory to reset to the user home directory after each command ... This wrapper avoids that"), then compare with the run/scenario instructions that mandate `./b4w.ps1 <command>` from Git Bash.

