# Issues: swarm-parallel-scraping

> **Source:** `20260722-033935-swarm-parallel-scraping.full.md` | **Date:** 20260722-033935 | **Mode:** dev

## Scenario Background

### Task

All 10 X-SQL extraction jobs completed successfully, extracting product title, price, and image URL from MockSite product pages. The plain `swarm submit` job also completed successfully (page fetched, but `resultSet` empty as expected since no X-SQL query was provided).

**Sample extracted data:**

| URL | Title | Price | Image |
|-----|-------|-------|-------|
| B0E000001 | 4K OLED TV 55 | $899.99 | /ec/static/img/placeholder.png |
| B0E000002 | Wireless Noise-Cancelling Headphones | $199.99 | /ec/static/img/placeholder.png |
| B0E000005 | USB-C Hub 7-in-1 | $29.95 | /ec/static/img/placeholder.png |
| B0H000001 | Vacuum Cleaner Smart | $159.99 | /ec/static/img/placeholder.png |
| B0H000004 | LED Desk Lamp | $35.99 | /ec/static/img/placeholder.png |

---

### Execution Context

| Step | Command(s) |
|------|-----------|
| Verify MockSite | `curl localhost:18080` — already running |
| Learn CLI | `./b4w.ps1 help`, read `skills/browser4-cli/SKILL.md` and `references/swarm.md`, `references/x-sql.md` |
| Explore page structure | `goto`, `htmlsnapshot`, `htmlsnapshot summary`, `htmlsnapshot get text/attr`, `htmlsnapshot inspect` |
| Discover CSS selectors | Found `#productTitle` (title), `#product-price` (price), `.product-images img` (image) |
| Cross-category validation | Tested selectors on B0E000001, B0E000002, B0H000001 — consistent |
| Create seed file | 10 URLs: 6 Electronics + 4 Home |
| Write X-SQL query | `DOM_FIRST_TEXT(DOM, '#productTitle')`, `DOM_FIRST_TEXT(DOM, '#product-price')`, `DOM_FIRST_ATTR(DOM, 'img', 'src')` |
| Swarm create | `--display-mode HEA...

(truncated — see full.md for complete trace)

---

## Issues Found (8 issues)

### Issue 1: Swarm jobs stuck in "Created" status after initial submission

**Severity:** High
**Category:** Reliability

#### Reproduction

```bash
./b4w.ps1 swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4
./b4w.ps1 swarm query --sql @extract_query.sql --seed-file ./seed_urls.txt --refresh
# All jobs show "Created" status indefinitely; swarm status shows isDone: false, statusCode: 201
```

#### Expected Behavior

Jobs should transition from "Created" to "completed" (or "failed") within a reasonable time. `swarm list` should reflect the current state.

#### Actual Behavior

All 11 jobs remained in "Created" status for over 30 seconds with no progress. `statusCode: 201` and `isDone: false` persisted. The `status` field showed "Created" (a non-standard lifecycle state not documented).

#### Root Cause Analysis

Likely a race condition where the swarm task store had stale state from prior sessions (22 old completed tasks). The `swarm list --clear` → recreate → resubmit sequence resolved it. This suggests the task queue processing may be blocked by conflicts with tasks that were already in the store, or the swarm backend worker pool failed to pick up new tasks when old task references existed.

#### Code Pointer

``browser4-rest` — swarm task dispatcher / job queue logic that picks up "Created" tasks and assigns them to browser contexts.`

#### AI Suggested Improvement

- Add a `statusCode` → human-readable status mapping in `swarm list` output (e.g., "201 Created → waiting for worker") so users understand what's happening
- Add a timeout for "Created" tasks: if a task stays "Created" for >60s, auto-fail it with a diagnostic message
- Add a `swarm status` subcommand that shows the swarm session health (active workers, queue depth, browser contexts running) for debugging stuck jobs
- In `swarm list`, distinguish between tasks from the current swarm session and orphaned tasks from prior sessions
- Auto-clean or warn about stale completed tasks at swarm creation time

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: `swarm submit` result has empty `resultSet` — user expectations unclear

**Severity:** Medium
**Category:** Documentation / UX

#### Reproduction

```bash
./b4w.ps1 swarm submit http://localhost:18080/ec/dp/B0E000001 --refresh
# Wait for completion, then:
./b4w.ps1 swarm result <task-id>
# Returns: {"resultSet": [], "pageContentBytes": 4626, "error": null}
```

#### Expected Behavior

Either (a) the documentation should clearly state that `swarm submit` without `--sql` only fetches the page and does not extract data, and explain how to get the page content; or (b) the result should include the page content in an accessible form.

#### Actual Behavior

`resultSet` is empty. The only evidence the page was fetched is `pageContentBytes: 4626`. A first-time user would wonder "where is my data?" and may not understand that they need `swarm query` with `--sql` for extraction.

#### Root Cause Analysis

The swarm.md docs note "Prefer `swarm query` over `swarm submit --sql`" but don't clearly explain what `swarm submit` WITHOUT `--sql` actually returns. The `resultSet` being empty is technically correct (no X-SQL query = no extraction) but confusing.

#### Code Pointer

``browser4-rest` — `swarm result` response builder`

#### AI Suggested Improvement

- In the `swarm submit` help text, explicitly state: "Without `--sql`, no data is extracted — use `swarm query` for structured extraction"
- Add a `"note"` field to the result JSON when `resultSet` is empty: `"note": "No X-SQL query was provided. Use swarm query --sql to extract data."`
- The `swarm.md` quick start should show a `swarm submit` without `--sql` example and explain the expected output
- Add a `--parse` flag to `swarm submit` that extracts page metadata (title, description, canonical URL) even without X-SQL, giving users a useful baseline

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
Add a "note" field to the result JSON when resultSet is empty

---

### Issue 3: PowerShell argument mangling on Windows — `-v`, `-i`, and `-Ex*` flags

**Severity:** High
**Category:** UX / Reliability

#### Reproduction

```bash
./b4w.ps1 snapshot -v 0 --stdout
# Error: Unknown command: 'snapshot-0'. Did you mean: 'snapshot'?

./b4w.ps1 doctor log pulsar.s grep -i "error"
# PowerShell error: AmbiguousParameter -i
```

#### Expected Behavior

All documented flags should work regardless of the shell boundary between bash and PowerShell on Windows.

#### Actual Behavior

The bash→PowerShell boundary mangles arguments containing dashes. `-v 0` becomes `-v` (interpreted by PowerShell) and `0` appends to the previous argument `snapshot` → `snapshot-0`. The `-i` flag for case-insensitive grep clashes with PowerShell's `-InformationAction` parameter. The swarm.md documentation warns about this specifically for Git Bash users.

#### Root Cause Analysis

The `b4w.ps1` script passes through arguments to the Rust binary via `$ScriptArgs`, but PowerShell itself processes leading dashes as its own parameters before forwarding. Windows Git Bash users are double-penalized: bash first processes the command line, then PowerShell processes it again.

#### Code Pointer

``b4w.ps1` — the `param($ScriptArgs)` forwarding; could use `--%` (stop-parsing) token or explicit quoting approach.`

#### AI Suggested Improvement

- Add `--%` (PowerShell stop-parsing token) in `b4w.ps1` to prevent PowerShell from interpreting CLI flags: `& $Exe --% @ScriptArgs`
- Add a `b4w.sh` bash wrapper (as mentioned in swarm.md) that handles quoting automatically
- In `b4w.ps1`, detect when running from bash/WSL and automatically apply the quoting workaround
- Add a `--pwsh-safe` mode that takes a single JSON argument and deserializes it, bypassing all shell parsing
- Document the quoting workaround more prominently: it's currently buried in the swarm.md "Notes" section but applies to many more commands (snapshot -v, grep -i, etc.)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
Add --% (PowerShell stop-parsing token) in b4w.ps1 to prevent PowerShell from interpreting CLI flags: & $Exe --% @ScriptArgs

Add a b4w.sh bash wrapper (as mentioned in swarm.md) that handles quoting automatically

---

### Issue 4: `installed version` (v4.11.22) lags behind `CLI version` (4.12.0)

**Severity:** Medium
**Category:** Product / Reliability

#### Reproduction

```bash
./b4w.ps1 status
# CLI version: 4.12.0
# Installed version: v4.11.22
```

#### Expected Behavior

When running from local source (via `b4w.ps1`), the backend should also run from the local source tree, or the versions should match.

#### Actual Behavior

The CLI binary is built from local source (4.12.0) but the backend JAR/server runs from a previously-installed bundle (v4.11.22). This means testing exercises the CLI from 4.12.0 against a backend from v4.11.22, making it unclear which version's behavior is being observed.

#### Root Cause Analysis

The `b4w.ps1` script auto-starts the daemon, which launches the installed bundled backend JAR (v4.11.22) rather than building and running the backend from the local source tree. The task instructions state "the CLI daemon auto-starts the locally-built backend JAR," but the CLI and backend are at different versions.

#### Code Pointer

`Daemon startup logic — needs to check if running from source and use the locally-built JAR (`browser4-rest/target/`) instead of the installed bundle.`

#### AI Suggested Improvement

- When `b4w.ps1` detects it's running from a source tree, auto-build and use the local backend JAR to ensure version consistency
- Add a warning in `status` output when CLI version ≠ installed (backend) version
- The `status` output could show both the CLI version and the backend server version separately with clearer labeling
- Add a `--local-backend` flag to force using the locally-built backend

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: Task store cross-contamination between swarm sessions

**Severity:** Medium
**Category:** UX / Reliability

#### Reproduction

```bash
./b4w.ps1 swarm create ...
./b4w.ps1 swarm query ... # submit 10 jobs
# 22 tasks shown, including 11 from a completely different prior session
```

#### Expected Behavior

`swarm list` should show only tasks relevant to the current swarm session by default, or clearly separate tasks from different sessions.

#### Actual Behavior

The task store retains ALL tasks across sessions. After recreating the swarm, 22 tasks appeared (11 old + 11 new). Users must know to run `swarm list --clear` to clean up. The old tasks had different UUIDs but the same URLs, creating confusion about which results are current.

#### Root Cause Analysis

The task store is persistent across swarm sessions with no automatic cleanup or session-scoping. `swarm list --clear` is an explicit manual step.

#### Code Pointer

``browser4-rest` — swarm task store lifecycle management`

#### AI Suggested Improvement

- Auto-clear completed tasks from the task store when a new swarm session is created (or offer a prompt)
- Add a `--session` filter to `swarm list` to show only tasks from the current swarm session
- Show a session creation timestamp or session ID in `swarm list` output so users can distinguish task batches
- Add `swarm create --clear` flag to atomically clear old tasks when creating a new session
- The `swarm close` flow could ask: "Clear tracked tasks from this session?"

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: `htmlsnapshot get all attr` argument order is unintuitive

**Severity:** Low
**Category:** UX / Discoverability

#### Reproduction

```bash
./b4w.ps1 htmlsnapshot get all attr div class    # works
./b4w.ps1 htmlsnapshot get attr div class        # fails — need "all" keyword
./b4w.ps1 htmlsnapshot get all attr "div" "class" # works
```

#### Expected Behavior

The argument ordering should be documented clearly in the help output, and `get attr` without `all` should give a helpful error suggesting `get all attr`.

#### Actual Behavior

The command format is `htmlsnapshot get all <field> <selector> [name]`, where `attr` is the field, then the selector, then the attribute name. The syntax `get all attr div class` reads oddly — it's not obvious that `attr` is a field type and `class` is the attribute name parameter. The `get` (single, no `all`) variant has a different argument layout: `get <field> <selector> [name]`.

#### Root Cause Analysis

The positional argument structure mixes field type, selector, and attribute name into sequential positional args without clear delimiters. The `all` keyword being between `get` and the field name breaks the natural reading order.

#### Code Pointer

``cli/browser4-cli/` — argument parser for `htmlsnapshot get` subcommands`

#### AI Suggested Improvement

- Add named flags for clarity: `htmlsnapshot get --field attr --selector "div" --name "class" --all`
- In the help text, show the argument order visually: `get all <field: text|html|attr> <css-selector> [attribute-name]`
- Add `htmlsnapshot get attrs` as a dedicated subcommand: `htmlsnapshot attrs "div" class --all`
- The error message for wrong argument count should show the expected format with examples

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: No `--help` per subcommand in help overview; discoverability gap

**Severity:** Low
**Category:** Discoverability

#### Reproduction

```bash
./b4w.ps1 help
# Shows all commands but no indication of per-command --help
./b4w.ps1 swarm --help   # This works but isn't advertised
./b4w.ps1 swarm query --help  # Detailed help exists but is non-obvious
```

#### Expected Behavior

The main `help` output should mention that `--help` works per subcommand (e.g., `swarm query --help`), and/or the help output should be organized to make it clear which commands have detailed help available.

#### Actual Behavior

The main help lists all commands in a flat structure. The user must guess or already know that `--help` can be appended to any subcommand. The `swarm query --help` output exists and has good detail, but nothing in the top-level help points users toward it.

#### Root Cause Analysis

The top-level help output lacks a hint like "Run `<command> --help` for detailed options" or a `--help-extract` / `--help-swarm` category filter hint.

#### Code Pointer

`Help text generation in CLI binary`

#### AI Suggested Improvement

- Add a footer to every help page: "Run `browser4-cli <command> --help` for detailed options and examples"
- The top-level help already mentions `--help nav | --help extract | --help session | --help kb` but should also mention swarm-specific help
- Add `--help swarm` and `--help crawl` as category filter shortcuts
- Each command's help should include at least one copy-paste-ready example

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: `swarm status` output includes non-standard lifecycle state "Created"

**Severity:** Low
**Category:** Documentation / UX

#### Reproduction

```bash
./b4w.ps1 swarm status <task-id>
# {"status": "Created", "statusCode": 201, "isDone": false, ...}
```

#### Expected Behavior

The `status` field should use documented lifecycle states (e.g., "pending", "running", "completed", "failed") or the states should be documented in swarm.md.

#### Actual Behavior

The `status` field shows "Created" (mapped to HTTP 201) which is not documented in swarm.md. The "Errors & Recovery" section mentions `isDone: false` and checking `statusCode` but doesn't document the full set of possible `status` string values.

#### Root Cause Analysis

The `status` field appears to reflect the HTTP status name (201 Created), which is unusual for a task lifecycle. Users expect task states like "queued", "processing", "done", "error", not HTTP semantics.

#### Code Pointer

`Backend swarm task status mapping`

#### AI Suggested Improvement

- Document all possible `status` values in swarm.md (e.g., "Created", "Pending", "Processing", "Completed", "Failed")
- Consider renaming the lifecycle states to be task-oriented rather than HTTP-oriented: "queued" instead of "Created"
- Add a `"lifecycleState"` field alongside `statusCode` for clarity
- In `swarm list`, map internal states to user-friendly labels in the STATUS column

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
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
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Swarm jobs stuck in "Created" status after initial submission

```bash
./b4w.ps1 swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4
./b4w.ps1 swarm query --sql @extract_query.sql --seed-file ./seed_urls.txt --refresh
# All jobs show "Created" status indefinitely; swarm status shows isDone: false, statusCode: 201
```

#### Issue 2: `swarm submit` result has empty `resultSet` — user expectations unclear

```bash
./b4w.ps1 swarm submit http://localhost:18080/ec/dp/B0E000001 --refresh
# Wait for completion, then:
./b4w.ps1 swarm result <task-id>
# Returns: {"resultSet": [], "pageContentBytes": 4626, "error": null}
```

#### Issue 3: PowerShell argument mangling on Windows — `-v`, `-i`, and `-Ex*` flags

```bash
./b4w.ps1 snapshot -v 0 --stdout
# Error: Unknown command: 'snapshot-0'. Did you mean: 'snapshot'?

./b4w.ps1 doctor log pulsar.s grep -i "error"
# PowerShell error: AmbiguousParameter -i
```

#### Issue 4: `installed version` (v4.11.22) lags behind `CLI version` (4.12.0)

```bash
./b4w.ps1 status
# CLI version: 4.12.0
# Installed version: v4.11.22
```

#### Issue 5: Task store cross-contamination between swarm sessions

```bash
./b4w.ps1 swarm create ...
./b4w.ps1 swarm query ... # submit 10 jobs
# 22 tasks shown, including 11 from a completely different prior session
```

#### Issue 6: `htmlsnapshot get all attr` argument order is unintuitive

```bash
./b4w.ps1 htmlsnapshot get all attr div class    # works
./b4w.ps1 htmlsnapshot get attr div class        # fails — need "all" keyword
./b4w.ps1 htmlsnapshot get all attr "div" "class" # works
```

#### Issue 7: No `--help` per subcommand in help overview; discoverability gap

```bash
./b4w.ps1 help
# Shows all commands but no indication of per-command --help
./b4w.ps1 swarm --help   # This works but isn't advertised
./b4w.ps1 swarm query --help  # Detailed help exists but is non-obvious
```

#### Issue 8: `swarm status` output includes non-standard lifecycle state "Created"

```bash
./b4w.ps1 swarm status <task-id>
# {"status": "Created", "statusCode": 201, "isDone": false, ...}
```

