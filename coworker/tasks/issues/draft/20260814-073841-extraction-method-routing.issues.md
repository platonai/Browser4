# Issues: extraction-method-routing

> **Source:** `20260814-073841-extraction-method-routing.full.md` | **Date:** 20260814-084525 | **Mode:** production

## Scenario Background

### Task

All 7 acceptance criteria passed end-to-end against the released `browser4-cli` 4.13.4 (backend v4.13.4-SNAPSHOT at localhost:18182, MockSite on localhost:18080, DeepSeek configured on the backend for `extract`):

- **AC1 — Interact first, then extract:** Filled First Name, Last Name, Email, Country (jp), Experience level (advanced), checked the Testing and newsletter checkboxes, filled Comments, agreed to terms, and submitted. A fresh `htmlsnapshot` after submit followed by `htmlsnapshot get text "#result-data"` returned the post-submit JSON payload containing every entered value (`firstName: Ada`, `lastName: Lovelace`, `email: ada@example.com`, `country: jp`, `topics: [automation, testing]`, `newsletterOptIn: true`, `experienceLevel: advanced`, comments). ✓
- **AC2 — Static page, one field:** `htmlsnapshot get text "#productTitle"` on `/ec/dp/B0E000001` returned `4K OLED TV 55`. ✓
- **AC3 — One field, all matches:** `htmlsnapshot get all text "[class*='product-title']"` on the Electronics listing returned all 6 product titles, not just the first. ✓
- **AC4 — Correlated multi-field rows:** An X-SQL query using `DOM_LOAD_AND_SELECT(@url, 'div[class*="product-card"]')` with `DOM_BASE_URI`, `DOM_FIRST_TEXT` (title/price), and `DOM_FIRST_HREF` (detail link) returned 6 rows with title, price, and detail URL correctly aligned per card. ✓
- **AC5 — Dynamic/complex page logic:** `eval --file ac5-eval.js --json` returned a structured object (`pageTitle`, `buttonCount: 2`, `linkCount: 0`, `formCount: 0`, 5 headings), each value verified against the live page. ✓
- **AC6 — Natural-language extraction:** `extract "Return the product title, displayed price, rating, and the top three feature bullets as JSON." --stdout` returned title/price/rating matching the page exactly; the page only contains 2 feature bullets, so the extraction was faithful. ✓
- **AC7 — High-volume extraction:** `crawl --seed-file` (5 product URLs) `--depth 0 --sql @ac7-query.sql --format table --refresh` produced 5 rows, one per seed URL, each with correct URL/title/price. ✓

All scratch files (query SQL, JS, seed list, help JSON, SKILL copy) were created under `./.test-sessions/` (git-ignored). No repo files were modified; the worktree changes shown by `git status` (a modified `common.ps1` and coworker draft files) pre-date this session.

### Execution Context

**Preparation**
- Verified cwd = `D:\workspace\Browser4\Browser4-4.13`; created `./.test-sessions/`.
- Ran `browser4-cli help` (full command map) and saved `--help-json` to `.test-sessions/help-json.json`.
- Downloaded and read the complete `https://browser4.io/SKILL.md` (45 KB) plus the local reference docs that govern the scenario: `htmlsnapshot.md`, `x-sql.md`, `x-sql-dom-functions.md`, `crawl.md`, `agent.md`.
- `browser4-cli status` → backend UP; confirmed MockSite HTTP 200 on :18080; `browser4-cli doctor` → "LLM is configured" (DeepSeek via `~/.browser4/config/conf-enabled/application-private.properties`).

**AC1** — `goto form-filling.html` → `snapshot -i --stdout` (obtained refs e2–e9, e14, e16, e17, e172) → `fill e2 "Ada"`, `fill e3 "Lovelace"`, `fill e4 "ada@example.com"`, `select...

(truncated — see full.md for complete trace)

---

## Issues Found (7 issues)

### Issue 1: SKILL.md copy-paste template `htmlsnapshot get text <css> --all` returns only the first match, not all matches

**Severity:** Medium
**Category:** Documentation

#### Reproduction

On a listing page with multiple matches run: browser4-cli htmlsnapshot; then browser4-cli htmlsnapshot get text "[class*='product-title']" --all. Compare with browser4-cli htmlsnapshot get all text "[class*='product-title']".

#### Expected Behavior

Following the SKILL.md §1 Copy-Paste Template (`htmlsnapshot get text "<css-selector>" --all`) should return all matching product titles, as a first-time user would reasonably infer from the template.

#### Actual Behavior

`get text ... --all` returns only the first match (e.g. "4K OLED TV 55"); the full list of 6 titles is only returned by `get all text ...`. `--all` merely disables output pagination; it does not switch to querySelectorAll semantics.

#### Root Cause Analysis

The SKILL.md quick-start template conflates the `--all` pagination flag with the `get all` variant. The underlying command semantics are correct and correctly documented in references/htmlsnapshot.md; only the top-level SKILL.md template (and web copy) is wrong. A user copying the template gets silently incomplete data.

#### Code Pointer

`skills/browser4-cli/SKILL.md (section 1 Copy-Paste Template); web SKILL.md at browser4.io/SKILL.md`

#### AI Suggested Improvement

- Replace `htmlsnapshot get text "<css-selector>" --all` in the template with `htmlsnapshot get all text "<css-selector>"`
- Add a one-line note under the template distinguishing `get` (first match) from `get all` (querySelectorAll)
- Consider making the CLI print a hint when `--all` is used with `get` alone (e.g. 'Use get all <field> for all matches')

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: `htmlsnapshot get` exits 0 when the selector matches nothing, contradicting the documented non-zero exit

**Severity:** Medium
**Category:** Reliability

#### Reproduction

browser4-cli htmlsnapshot get text "zzz-no-such-element-xyz"; echo $LASTEXITCODE  (also verifiable with `&& echo PASS` in bash)

#### Expected Behavior

Non-zero exit code when the CSS selector matches nothing, as stated in references/htmlsnapshot.md Error Handling: "htmlsnapshot get exits non-zero when the CSS selector matches nothing or an element ref (e5) is passed."

#### Actual Behavior

Exit code is 0. The CLI prints a helpful "No elements matched" message but returns success, so scripts/CI pipelines treat empty extraction as success (silent failure). Control commands do propagate exit codes correctly (e.g. `config set timeout 0` exits 1, unknown command exits 2), so this is specific to the empty-result path.

#### Root Cause Analysis

handle_html_snapshot_get computes `empty_result` and prints guidance but returns Ok(()) from the command handler; no error/exit code is produced for the no-match case. Only invalid selectors/refs and backend failures produce non-zero exits.

#### Code Pointer

`cli/browser4-cli/src/main.rs:5913 (handle_html_snapshot_get, empty_result branch around line 5996)`

#### AI Suggested Improvement

- Return a non-zero exit (e.g. 2) when `empty_result` is true for `get`/`get all`, while still printing the guidance text
- Add a machine-readable `--json` field like `"matched": false` so scripts can distinguish no-match from success
- Update references/htmlsnapshot.md only if the behavior is intentionally kept as exit 0, and document the discrepancy

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: `extract --stdout` prints the raw internal ExtractResult envelope instead of the extracted content

**Severity:** Medium
**Category:** Product

#### Reproduction

browser4-cli extract "Return the product title, displayed price, rating, and the top three feature bullets as JSON." --stdout

#### Expected Behavior

Per `browser4-cli help extract`: "--stdout Print extracted content directly to stdout." The output should be the extracted JSON (`{"title": ..., "price": ..., ...}`), directly consumable by scripts.

#### Actual Behavior

Stdout contains the backend envelope: {"type":"ai.platon.pulsar.agentic.ExtractResult","description":"<escaped JSON>","inputToken":...,"outputToken":...,"inferenceTimeMillis":...}. The requested JSON is double-encoded inside `description`, and the embedded metadata reports `"completed":false` even though extraction succeeded — confusing and wrong for direct consumption.

#### Root Cause Analysis

handle_extract passes the raw server response through as `content` and, in raw mode, `println!("{}", content)` prints that envelope verbatim. No client-side unwrapping of the ExtractResult `description` field is performed, and the empty-extraction detector looks for `completed:false` at paths that this envelope shape does not match.

#### Code Pointer

`cli/browser4-cli/src/main.rs:5363 (handle_extract, raw branch around line 5478)`

#### AI Suggested Improvement

- In `--stdout`/`--raw` mode, unwrap `ExtractResult.description` and print the actual extracted payload
- Optionally also print a one-line metadata summary to stderr instead of stdout so stdout stays clean for piping
- Fix the empty-extraction detector to recognize the ExtractResult envelope shape (description containing metadata.completed=false)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: Snapshot/screenshot/extract outputs go to a hardcoded workspace-relative `.browser4-cli/snapshot/` dir that is undocumented and not redirected by BROWSER4_CLI_STATE_DIR

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Run any `snapshot`, `screenshot`, or `extract` command from a repo checkout; observe files created under <cwd>/.browser4-cli/snapshot/ even though session state lives in ~/.browser4. Attempt to redirect with $env:BROWSER4_CLI_STATE_DIR — snapshot output location does not change.

#### Expected Behavior

The documentation (SKILL.md, `browser4-cli help`) explains where command outputs are stored and provides a way to redirect them; the described fallback is `./.browser4-cli-state`, but outputs should follow the state/runtime dir or an env override.

#### Actual Behavior

The snapshot/screenshot/extract output directory is hardcoded to `./.browser4-cli/snapshot/` relative to the current working directory (snapshot.rs SNAPSHOT_DIR), independent of BROWSER4_CLI_STATE_DIR. The docs only mention `.browser4-cli-state` as the workspace fallback for state, and never document `.browser4-cli/snapshot/`. In a source checkout this accumulates snapshot/extract files in the repo tree (mitigated only by .gitignore lines 131–134).

#### Root Cause Analysis

snapshot_dir() builds the path from a hardcoded constant [".browser4-cli", "snapshot"] relative to CWD; no env var or config key overrides it, and the SKILL.md/help docs don't mention it.

#### Code Pointer

`cli/browser4-cli/src/snapshot.rs:16 (snapshot_dir(), SNAPSHOT_DIR at line 9)`

#### AI Suggested Improvement

- Document `.browser4-cli/snapshot/` in SKILL.md and `help` (alongside `snapshot clean`), and/or honor BROWSER4_CLI_STATE_DIR for snapshot outputs
- Add an env var (e.g. BROWSER4_CLI_SNAPSHOT_DIR) or `config set snapshot-dir <path>` override
- Consider defaulting outputs to the state dir (~/.browser4/snapshot) instead of CWD-relative paths, which is safer for source-tree usage

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: `extract --stdout` still writes a timestamped file into the workspace `.browser4-cli/snapshot/` directory

**Severity:** Low
**Category:** UX

#### Reproduction

Run browser4-cli extract "..." --stdout, then check Get-ChildItem .browser4-cli/snapshot/extract-*.txt (a new file with the same content is created).

#### Expected Behavior

With `--stdout`, no file should be written — help text says "Output is saved to a timestamped file by default. Use --stdout (or --raw) to print to stdout instead."

#### Actual Behavior

A file `.browser4-cli/snapshot/extract-<timestamp>.txt` is always created regardless of `--stdout`/`--raw`, because `save_snapshot` is called unconditionally. This wrote to the repository working tree during the evaluation (git-ignored, but still workspace pollution).

#### Root Cause Analysis

handle_extract always calls `save_snapshot(&out_path, content)` before checking the raw flag; the raw flag only controls whether content is also printed to stdout.

#### Code Pointer

`cli/browser4-cli/src/main.rs:5448 (handle_extract, unconditional save_snapshot call)`

#### AI Suggested Improvement

- Skip `save_snapshot` when `--stdout`/`--raw`/`--filename` is provided (or document that extraction always archives)
- When `--filename` is provided, write only to that path instead of also writing to the snapshot dir
- Surface the saved path in non-stdout mode so users know where archives land

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: SKILL.md §4a claims `htmlsnapshot get text article` "auto-discovers content", but `article` is a plain tag selector

**Severity:** Low
**Category:** Documentation

#### Reproduction

On a page without an <article> tag (e.g. MockSite product page): browser4-cli htmlsnapshot; browser4-cli htmlsnapshot get text "article" → "No elements matched".

#### Expected Behavior

Either the command should auto-discover the main content as the decision tree implies, or the docs should not describe `article` as auto-discovery.

#### Actual Behavior

The command simply runs querySelector('article'); on MockSite pages there are no <article> elements, so it returns an empty result with exit 0. Auto-discovery is a separate feature of `htmlsnapshot inspect`, not of `get text article`.

#### Root Cause Analysis

The §4a decision tree line "Don't know the right CSS selector? → htmlsnapshot get text article (auto-discovers content)" is a misleading simplification; no auto-discovery exists in the get path.

#### Code Pointer

`skills/browser4-cli/SKILL.md (section 4a decision tree); references/htmlsnapshot.md (get command docs)`

#### AI Suggested Improvement

- Replace the decision-tree line with `htmlsnapshot inspect` (which genuinely auto-discovers recurring patterns) or clarify that `article` is just an example tag selector
- Add a note that `get text` accepts any CSS selector and that `inspect`/`summary` are the discovery tools

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: `htmlsnapshot query` default output is a raw JSON envelope and the human-readable `--format table` / `--result-only` flags are absent from the main documentation

**Severity:** Low
**Category:** Discoverability

#### Reproduction

browser4-cli htmlsnapshot query "http://localhost:18080/ec/b?node=1292115012" --sql "@query.sql" → full JSON envelope with id/statusCode/timestamps; only `browser4-cli htmlsnapshot query --help` reveals --format table and --result-only.

#### Expected Behavior

The primary docs (SKILL.md §4e, references/htmlsnapshot.md Query section) should mention the output format options so a first-time user knows a readable table output exists; or the default should be human-readable.

#### Actual Behavior

SKILL.md and htmlsnapshot.md describe `query` without any output-format flags; the user gets a noisy machine envelope by default and must discover `--help` to find `--format table`. The task instructions themselves only specified the envelope path, so the scenario still succeeded, but the readable option was effectively hidden.

#### Root Cause Analysis

Documentation lag: the `--format`/`--result-only` options exist in help.rs/commands.rs but were not added to SKILL.md or the htmlsnapshot reference.

#### Code Pointer

`skills/browser4-cli/references/htmlsnapshot.md (Query section); skills/browser4-cli/SKILL.md (4e X-SQL Quickstart)`

#### AI Suggested Improvement

- Document `--format json|csv|table` and `--result-only` in the htmlsnapshot.md Query section and SKILL.md §4e
- Add a table-format example to the X-SQL quickstart so the default envelope doesn't surprise first-time users
- Consider making `--format table` the default when stdout is a TTY

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Install browser4-cli: `cargo install --path cli/browser4-cli`
3. Ensure the backend server is running.
4. All commands: `browser4-cli <command>`

### Per-Issue Reproduction Steps

#### Issue 1: SKILL.md copy-paste template `htmlsnapshot get text <css> --all` returns only the first match, not all matches

On a listing page with multiple matches run: browser4-cli htmlsnapshot; then browser4-cli htmlsnapshot get text "[class*='product-title']" --all. Compare with browser4-cli htmlsnapshot get all text "[class*='product-title']".

#### Issue 2: `htmlsnapshot get` exits 0 when the selector matches nothing, contradicting the documented non-zero exit

browser4-cli htmlsnapshot get text "zzz-no-such-element-xyz"; echo $LASTEXITCODE  (also verifiable with `&& echo PASS` in bash)

#### Issue 3: `extract --stdout` prints the raw internal ExtractResult envelope instead of the extracted content

browser4-cli extract "Return the product title, displayed price, rating, and the top three feature bullets as JSON." --stdout

#### Issue 4: Snapshot/screenshot/extract outputs go to a hardcoded workspace-relative `.browser4-cli/snapshot/` dir that is undocumented and not redirected by BROWSER4_CLI_STATE_DIR

Run any `snapshot`, `screenshot`, or `extract` command from a repo checkout; observe files created under <cwd>/.browser4-cli/snapshot/ even though session state lives in ~/.browser4. Attempt to redirect with $env:BROWSER4_CLI_STATE_DIR — snapshot output location does not change.

#### Issue 5: `extract --stdout` still writes a timestamped file into the workspace `.browser4-cli/snapshot/` directory

Run browser4-cli extract "..." --stdout, then check Get-ChildItem .browser4-cli/snapshot/extract-*.txt (a new file with the same content is created).

#### Issue 6: SKILL.md §4a claims `htmlsnapshot get text article` "auto-discovers content", but `article` is a plain tag selector

On a page without an <article> tag (e.g. MockSite product page): browser4-cli htmlsnapshot; browser4-cli htmlsnapshot get text "article" → "No elements matched".

#### Issue 7: `htmlsnapshot query` default output is a raw JSON envelope and the human-readable `--format table` / `--result-only` flags are absent from the main documentation

browser4-cli htmlsnapshot query "http://localhost:18080/ec/b?node=1292115012" --sql "@query.sql" → full JSON envelope with id/statusCode/timestamps; only `browser4-cli htmlsnapshot query --help` reveals --format table and --result-only.

