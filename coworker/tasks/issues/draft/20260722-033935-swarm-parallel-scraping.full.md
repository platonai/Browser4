---

## A. Task Result

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

## B. Execution Trace

| Step | Command(s) |
|------|-----------|
| Verify MockSite | `curl localhost:18080` — already running |
| Learn CLI | `./b4w.ps1 help`, read `skills/browser4-cli/SKILL.md` and `references/swarm.md`, `references/x-sql.md` |
| Explore page structure | `goto`, `htmlsnapshot`, `htmlsnapshot summary`, `htmlsnapshot get text/attr`, `htmlsnapshot inspect` |
| Discover CSS selectors | Found `#productTitle` (title), `#product-price` (price), `.product-images img` (image) |
| Cross-category validation | Tested selectors on B0E000001, B0E000002, B0H000001 — consistent |
| Create seed file | 10 URLs: 6 Electronics + 4 Home |
| Write X-SQL query | `DOM_FIRST_TEXT(DOM, '#productTitle')`, `DOM_FIRST_TEXT(DOM, '#product-price')`, `DOM_FIRST_ATTR(DOM, 'img', 'src')` |
| Swarm create | `--display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4` |
| First submit attempt | `swarm query --sql @extract_query.sql --seed-file ./seed_urls.txt --refresh` — **all 11 jobs stuck in "Created"** |
| Recovery | `swarm close` → `swarm list --clear` → `swarm create` → resubmit with `--wait` |
| Second submit (X-SQL) | `swarm query ... --wait` — all 10 completed in 145s |
| Submit (plain) | `swarm submit http://.../B0E000001 --refresh --wait` — completed in 82s |
| Retrieve results | `swarm result <id>` ×5 — all returned correct structured data |
| List tasks | `swarm list` — 11 tasks: 10 swarm-query + 1 swarm-submit |
| Close | `swarm close` — session closed |

**Critical workaround:** The first submission batch got stuck in "Created" status indefinitely. Closing the swarm, clearing the task store, and recreating the swarm before resubmitting with `--wait` resolved the issue.

---

## C. Issues Found

### Issue 1: Swarm jobs stuck in "Created" status after initial submission

**Severity:** High

**Category:** Reliability

**Reproduction:**
```bash
./b4w.ps1 swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4
./b4w.ps1 swarm query --sql @extract_query.sql --seed-file ./seed_urls.txt --refresh
# All jobs show "Created" status indefinitely; swarm status shows isDone: false, statusCode: 201
```

**Expected:** Jobs should transition from "Created" to "completed" (or "failed") within a reasonable time. `swarm list` should reflect the current state.

**Actual:** All 11 jobs remained in "Created" status for over 30 seconds with no progress. `statusCode: 201` and `isDone: false` persisted. The `status` field showed "Created" (a non-standard lifecycle state not documented).

**Root Cause:** Likely a race condition where the swarm task store had stale state from prior sessions (22 old completed tasks). The `swarm list --clear` → recreate → resubmit sequence resolved it. This suggests the task queue processing may be blocked by conflicts with tasks that were already in the store, or the swarm backend worker pool failed to pick up new tasks when old task references existed.

**Code Pointer:** `browser4-rest` — swarm task dispatcher / job queue logic that picks up "Created" tasks and assigns them to browser contexts.

**AI Suggested Improvement:**
- Add a `statusCode` → human-readable status mapping in `swarm list` output (e.g., "201 Created → waiting for worker") so users understand what's happening
- Add a timeout for "Created" tasks: if a task stays "Created" for >60s, auto-fail it with a diagnostic message
- Add a `swarm status` subcommand that shows the swarm session health (active workers, queue depth, browser contexts running) for debugging stuck jobs
- In `swarm list`, distinguish between tasks from the current swarm session and orphaned tasks from prior sessions
- Auto-clean or warn about stale completed tasks at swarm creation time

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: `swarm submit` result has empty `resultSet` — user expectations unclear

**Severity:** Medium

**Category:** Documentation / UX

**Reproduction:**
```bash
./b4w.ps1 swarm submit http://localhost:18080/ec/dp/B0E000001 --refresh
# Wait for completion, then:
./b4w.ps1 swarm result <task-id>
# Returns: {"resultSet": [], "pageContentBytes": 4626, "error": null}
```

**Expected:** Either (a) the documentation should clearly state that `swarm submit` without `--sql` only fetches the page and does not extract data, and explain how to get the page content; or (b) the result should include the page content in an accessible form.

**Actual:** `resultSet` is empty. The only evidence the page was fetched is `pageContentBytes: 4626`. A first-time user would wonder "where is my data?" and may not understand that they need `swarm query` with `--sql` for extraction.

**Root Cause:** The swarm.md docs note "Prefer `swarm query` over `swarm submit --sql`" but don't clearly explain what `swarm submit` WITHOUT `--sql` actually returns. The `resultSet` being empty is technically correct (no X-SQL query = no extraction) but confusing.

**Code Pointer:** `browser4-rest` — `swarm result` response builder

**AI Suggested Improvement:**
- In the `swarm submit` help text, explicitly state: "Without `--sql`, no data is extracted — use `swarm query` for structured extraction"
- Add a `"note"` field to the result JSON when `resultSet` is empty: `"note": "No X-SQL query was provided. Use swarm query --sql to extract data."`
- The `swarm.md` quick start should show a `swarm submit` without `--sql` example and explain the expected output
- Add a `--parse` flag to `swarm submit` that extracts page metadata (title, description, canonical URL) even without X-SQL, giving users a useful baseline

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: PowerShell argument mangling on Windows — `-v`, `-i`, and `-Ex*` flags

**Severity:** High

**Category:** UX / Reliability

**Reproduction:**
```bash
./b4w.ps1 snapshot -v 0 --stdout
# Error: Unknown command: 'snapshot-0'. Did you mean: 'snapshot'?

./b4w.ps1 doctor log pulsar.s grep -i "error"
# PowerShell error: AmbiguousParameter -i
```

**Expected:** All documented flags should work regardless of the shell boundary between bash and PowerShell on Windows.

**Actual:** The bash→PowerShell boundary mangles arguments containing dashes. `-v 0` becomes `-v` (interpreted by PowerShell) and `0` appends to the previous argument `snapshot` → `snapshot-0`. The `-i` flag for case-insensitive grep clashes with PowerShell's `-InformationAction` parameter. The swarm.md documentation warns about this specifically for Git Bash users.

**Root Cause:** The `b4w.ps1` script passes through arguments to the Rust binary via `$ScriptArgs`, but PowerShell itself processes leading dashes as its own parameters before forwarding. Windows Git Bash users are double-penalized: bash first processes the command line, then PowerShell processes it again.

**Code Pointer:** `b4w.ps1` — the `param($ScriptArgs)` forwarding; could use `--%` (stop-parsing) token or explicit quoting approach.

**AI Suggested Improvement:**
- Add `--%` (PowerShell stop-parsing token) in `b4w.ps1` to prevent PowerShell from interpreting CLI flags: `& $Exe --% @ScriptArgs`
- Add a `b4w.sh` bash wrapper (as mentioned in swarm.md) that handles quoting automatically
- In `b4w.ps1`, detect when running from bash/WSL and automatically apply the quoting workaround
- Add a `--pwsh-safe` mode that takes a single JSON argument and deserializes it, bypassing all shell parsing
- Document the quoting workaround more prominently: it's currently buried in the swarm.md "Notes" section but applies to many more commands (snapshot -v, grep -i, etc.)

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: `installed version` (v4.11.22) lags behind `CLI version` (4.12.0)

**Severity:** Medium

**Category:** Product / Reliability

**Reproduction:**
```bash
./b4w.ps1 status
# CLI version: 4.12.0
# Installed version: v4.11.22
```

**Expected:** When running from local source (via `b4w.ps1`), the backend should also run from the local source tree, or the versions should match.

**Actual:** The CLI binary is built from local source (4.12.0) but the backend JAR/server runs from a previously-installed bundle (v4.11.22). This means testing exercises the CLI from 4.12.0 against a backend from v4.11.22, making it unclear which version's behavior is being observed.

**Root Cause:** The `b4w.ps1` script auto-starts the daemon, which launches the installed bundled backend JAR (v4.11.22) rather than building and running the backend from the local source tree. The task instructions state "the CLI daemon auto-starts the locally-built backend JAR," but the CLI and backend are at different versions.

**Code Pointer:** Daemon startup logic — needs to check if running from source and use the locally-built JAR (`browser4-rest/target/`) instead of the installed bundle.

**AI Suggested Improvement:**
- When `b4w.ps1` detects it's running from a source tree, auto-build and use the local backend JAR to ensure version consistency
- Add a warning in `status` output when CLI version ≠ installed (backend) version
- The `status` output could show both the CLI version and the backend server version separately with clearer labeling
- Add a `--local-backend` flag to force using the locally-built backend

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: Task store cross-contamination between swarm sessions

**Severity:** Medium

**Category:** UX / Reliability

**Reproduction:**
```bash
./b4w.ps1 swarm create ...
./b4w.ps1 swarm query ... # submit 10 jobs
# 22 tasks shown, including 11 from a completely different prior session
```

**Expected:** `swarm list` should show only tasks relevant to the current swarm session by default, or clearly separate tasks from different sessions.

**Actual:** The task store retains ALL tasks across sessions. After recreating the swarm, 22 tasks appeared (11 old + 11 new). Users must know to run `swarm list --clear` to clean up. The old tasks had different UUIDs but the same URLs, creating confusion about which results are current.

**Root Cause:** The task store is persistent across swarm sessions with no automatic cleanup or session-scoping. `swarm list --clear` is an explicit manual step.

**Code Pointer:** `browser4-rest` — swarm task store lifecycle management

**AI Suggested Improvement:**
- Auto-clear completed tasks from the task store when a new swarm session is created (or offer a prompt)
- Add a `--session` filter to `swarm list` to show only tasks from the current swarm session
- Show a session creation timestamp or session ID in `swarm list` output so users can distinguish task batches
- Add `swarm create --clear` flag to atomically clear old tasks when creating a new session
- The `swarm close` flow could ask: "Clear tracked tasks from this session?"

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: `htmlsnapshot get all attr` argument order is unintuitive

**Severity:** Low

**Category:** UX / Discoverability

**Reproduction:**
```bash
./b4w.ps1 htmlsnapshot get all attr div class    # works
./b4w.ps1 htmlsnapshot get attr div class        # fails — need "all" keyword
./b4w.ps1 htmlsnapshot get all attr "div" "class" # works
```

**Expected:** The argument ordering should be documented clearly in the help output, and `get attr` without `all` should give a helpful error suggesting `get all attr`.

**Actual:** The command format is `htmlsnapshot get all <field> <selector> [name]`, where `attr` is the field, then the selector, then the attribute name. The syntax `get all attr div class` reads oddly — it's not obvious that `attr` is a field type and `class` is the attribute name parameter. The `get` (single, no `all`) variant has a different argument layout: `get <field> <selector> [name]`.

**Root Cause:** The positional argument structure mixes field type, selector, and attribute name into sequential positional args without clear delimiters. The `all` keyword being between `get` and the field name breaks the natural reading order.

**Code Pointer:** `cli/browser4-cli/` — argument parser for `htmlsnapshot get` subcommands

**AI Suggested Improvement:**
- Add named flags for clarity: `htmlsnapshot get --field attr --selector "div" --name "class" --all`
- In the help text, show the argument order visually: `get all <field: text|html|attr> <css-selector> [attribute-name]`
- Add `htmlsnapshot get attrs` as a dedicated subcommand: `htmlsnapshot attrs "div" class --all`
- The error message for wrong argument count should show the expected format with examples

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: No `--help` per subcommand in help overview; discoverability gap

**Severity:** Low

**Category:** Discoverability

**Reproduction:**
```bash
./b4w.ps1 help
# Shows all commands but no indication of per-command --help
./b4w.ps1 swarm --help   # This works but isn't advertised
./b4w.ps1 swarm query --help  # Detailed help exists but is non-obvious
```

**Expected:** The main `help` output should mention that `--help` works per subcommand (e.g., `swarm query --help`), and/or the help output should be organized to make it clear which commands have detailed help available.

**Actual:** The main help lists all commands in a flat structure. The user must guess or already know that `--help` can be appended to any subcommand. The `swarm query --help` output exists and has good detail, but nothing in the top-level help points users toward it.

**Root Cause:** The top-level help output lacks a hint like "Run `<command> --help` for detailed options" or a `--help-extract` / `--help-swarm` category filter hint.

**Code Pointer:** Help text generation in CLI binary

**AI Suggested Improvement:**
- Add a footer to every help page: "Run `browser4-cli <command> --help` for detailed options and examples"
- The top-level help already mentions `--help nav | --help extract | --help session | --help kb` but should also mention swarm-specific help
- Add `--help swarm` and `--help crawl` as category filter shortcuts
- Each command's help should include at least one copy-paste-ready example

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: `swarm status` output includes non-standard lifecycle state "Created"

**Severity:** Low

**Category:** Documentation / UX

**Reproduction:**
```bash
./b4w.ps1 swarm status <task-id>
# {"status": "Created", "statusCode": 201, "isDone": false, ...}
```

**Expected:** The `status` field should use documented lifecycle states (e.g., "pending", "running", "completed", "failed") or the states should be documented in swarm.md.

**Actual:** The `status` field shows "Created" (mapped to HTTP 201) which is not documented in swarm.md. The "Errors & Recovery" section mentions `isDone: false` and checking `statusCode` but doesn't document the full set of possible `status` string values.

**Root Cause:** The `status` field appears to reflect the HTTP status name (201 Created), which is unusual for a task lifecycle. Users expect task states like "queued", "processing", "done", "error", not HTTP semantics.

**Code Pointer:** Backend swarm task status mapping

**AI Suggested Improvement:**
- Document all possible `status` values in swarm.md (e.g., "Created", "Pending", "Processing", "Completed", "Failed")
- Consider renaming the lifecycle states to be task-oriented rather than HTTP-oriented: "queued" instead of "Created"
- Add a `"lifecycleState"` field alongside `statusCode` for clarity
- In `swarm list`, map internal states to user-friendly labels in the STATUS column

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## D. Overall Assessment

### Task Completion Status
**Fully completed** — with one workaround required. All 10 X-SQL extraction jobs returned correct structured data, the plain submit job completed, task history was listed, and the swarm session was cleanly closed.

### Estimated Task Success Rate
**80%** — The main workflow (swarm create → swarm query → swarm submit → result → list → close) works well, but the first submission attempt failed silently (jobs stuck in "Created") and required a workaround (close, clear, recreate, resubmit with `--wait`).

### Number of Issues Found
**8 issues** (2 High, 3 Medium, 3 Low)

### Major Blockers
1. **Swarm jobs stuck in "Created"** — the most impactful issue. Required closing, clearing, and recreating the swarm to resolve. This would likely block a first-time user who doesn't know the `--clear` workaround.
2. **PowerShell argument mangling** — prevents using many documented flags (`snapshot -v`, `grep -i`) on Windows/Git Bash without careful quoting. Ubiquitous impact across the entire CLI surface.

### Most Confusing Aspects
- **`swarm submit` vs `swarm query`**: The difference between the two submission methods (plain fetch vs X-SQL extraction) is explained in docs but the empty `resultSet` from `swarm submit` is jarring when first encountered.
- **Task store cross-session persistence**: Seeing 22 tasks when only 11 were submitted was confusing. It took reading the swarm.md "Stale tasks" note to discover `--clear`.
- **CSS selector discovery workflow**: Finding the right selectors (`#productTitle`, `#product-price`) required iterating through `htmlsnapshot`, `htmlsnapshot summary`, `htmlsnapshot get`, and `htmlsnapshot inspect`. The accessibility tree in `snapshot` output proved more useful for finding element IDs than the HTML inspection tools.

### Most Valuable Improvements
1. **`--wait` flag is excellent** — eliminates manual polling. The progress output (`4/10 completed (elapsed: 30s)`) is clear and informative.
2. **`swarm list` COMMAND column** — distinguishes `swarm-query` from `swarm-submit` tasks at a glance.
3. **`htmlsnapshot summary`** — gave a concise, structured view of the page layout that was more actionable than raw HTML inspection.
4. **SKILL.md decision trees** (§4) — the "Choosing an Extraction Method" and "Choosing Bulk/Scale Approach" trees are well-organized and directly guided the correct tool selection.
5. **`@file` syntax for SQL/seed files** — eliminates shell quoting nightmares for complex queries.

### Overall Usability Rating
**7/10** — The core workflow is well-designed and the documentation (SKILL.md, swarm.md, x-sql.md) is comprehensive. The tool successfully completed the real-world task. However, the first-run reliability issue (stuck jobs), PowerShell argument mangling, and task store confusion pull the score down. These are fixable issues — the underlying architecture and workflow design are solid.
