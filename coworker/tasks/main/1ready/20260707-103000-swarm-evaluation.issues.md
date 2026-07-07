# Issues: swarm-evaluation

> **Source:** `20260707-103000-swarm-evaluation.full.md` | **Date:** 20260707-103000 | **Mode:** dev

## Scenario Background

### Task

Create a swarm session, submit 10 X-SQL extraction jobs and 1 plain scrape job, poll status, retrieve results, list tasks, and close session. All with MockSite products running on localhost:18080.

### Execution Context

**Key Commands:**

- `swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4`
- `swarm query --sql @../../extract-query.sql --seed-file ../../seed-urls.txt --refresh`
- `swarm submit "http://localhost:18080/ec/dp/B0E000001" --refresh --store-content`
- `swarm status <uuid>` (x11)
- `swarm result <uuid>` (x2)
- `swarm list`
- `close`

**Workarounds Applied During Task:**

- Used `../../` prefix for file paths because `cargo run` CWD is `cli/browser4-cli`
- Used `DOM_FIRST_ATTR` instead of `DOM_FIRST_IMG` for image extraction (known issue)
- Manually polled 11 jobs individually; no `--wait` flag available
- Copy/pasted UUIDs for each status check since IDs are not sequential

---

---

## Issues Found (10 issues)
> **Review complete:** 8 approved, 2 deferred/rejected

### Issue 1: `swarm list` does not show tasks submitted via `swarm query`

**Severity:** High
**Category:** Product

#### Reproduction
```bash
swarm query --sql @query.sql --seed-file urls.txt --refresh
swarm list
# Query tasks missing from output
```

#### Expected Behavior
`swarm list` should show all swarm-submitted tasks including `swarm query` jobs.

#### Actual Behavior
Only `swarm submit` tasks and stale pre-existing tasks appear. The 10 `swarm query` jobs are completely absent.

#### Root Cause Analysis
`swarm query` tasks appear to use a different tracking mechanism than `swarm submit`. The list reads from a task registry that query tasks don't write to. Tasks are accessible only via UUID with `swarm status`/`swarm result` — impossible to rediscover if UUIDs are lost.

#### AI Suggested Improvement
- Register all `swarm query` tasks in the same tracking system used by `swarm list`
- If tasks are too ephemeral for full tracking, document that `swarm query` tasks won't appear in `swarm list` and recommend saving task IDs
- Add a `--name` option to `swarm query` for human-readable labels

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

---

### Issue 2: Task IDs are non-sequential UUIDs, making them hard to reference

**Severity:** Medium
**Category:** UX

#### Reproduction
```bash
swarm query --sql @query.sql --seed-file urls.txt --refresh
# Output: Task ID: ca40ced0-2239-4209-9d81-34bcd50e50c1
```

#### Expected Behavior
Human-readable or sequential task IDs as shown in documentation (`scrape-task-1`).

#### Actual Behavior
36-character UUIDs requiring copy/paste for every status/result call. Documentation examples don't match reality.

#### Root Cause Analysis
Backend generates UUIDs for `swarm query` jobs. Documentation uses placeholder `scrape-task-N` IDs that don't match actual output.

#### AI Suggested Improvement
- Use human-readable prefixes: `sq-1`, `sq-2` for swarm query, `ss-1` for swarm submit
- Update documentation to show actual UUID format
- Make `swarm list` work for all types so task IDs are always rediscoverable

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

---

### Issue 3: No `swarm close` command — session closed with generic `close`

**Severity:** Low
**Category:** Discoverability

#### Reproduction
1. `swarm create ...`
2. Try `swarm close` → command not found
3. Must use generic `close`

#### Expected Behavior
A `swarm close` subcommand mirroring `swarm create`, or a post-creation hint.

#### Actual Behavior
Asymmetric naming: `swarm create` but `close`. Users may try `swarm close` first.

#### Root Cause Analysis
`close` is session-level, not swarm-specific. While technically correct, the asymmetry violates user expectations.

#### Code Pointer
`cli/browser4-cli/src/commands.rs` — could add `swarm close` as alias.

#### AI Suggested Improvement
- Add `swarm close` as an alias for `close` when swarm session is active
- Add post-creation hint: "Use `close` to release resources when done"

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

---

### Issue 4: `swarm status` and `swarm result` return identical JSON for completed jobs

**Severity:** Low
**Category:** Product

#### Reproduction
```bash
swarm status <id>  # Returns full resultSet
swarm result <id>  # Returns identical JSON
```

#### Expected Behavior
`swarm status` returns metadata only; `swarm result` returns result payload only.

#### Actual Behavior
Both return the same full JSON including `resultSet`, `pageContentBytes`, `finishTime`, etc.

#### Root Cause Analysis
Backend likely returns same endpoint response for both queries. Functional but makes the two-command API feel redundant.

#### AI Suggested Improvement
- `swarm status`: metadata only (id, done, status, lastModifiedTime)
- `swarm result`: result payload only (resultSet), error if not done

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

---

### Issue 6: `swarm list` shows stale tasks from previous sessions

**Severity:** Medium
**Category:** Reliability

#### Reproduction
```bash
swarm create --display-mode HEADLESS
swarm submit "http://localhost:18080/ec/dp/B0E000001" --refresh --store-content
swarm list
# Shows tasks from previous sessions
```

#### Expected Behavior
Show only current-session tasks, or clearly separate active vs. historical.

#### Actual Behavior
7 stale tasks from earlier sessions (books.toscrape.com, older localhost) all showing `pending` status. Clutters output and misleads about what's running.

#### Root Cause Analysis
Task tracking persists across sessions without cleanup. No garbage collection for stale entries.

#### AI Suggested Improvement
- Scope `swarm list` to current session by default; add `--all` for history
- Auto-expire tasks after session close
- Show task age or session origin
- Add `swarm list --clear` to remove stale entries

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

---

### Issue 8: No `--wait` flag for swarm job submission to block until completion

**Severity:** Medium
**Category:** UX

#### Reproduction
```bash
swarm query --sql @query.sql --seed-file urls.txt --refresh
# Returns immediately; must manually poll each job
```

#### Expected Behavior
Option to wait for all jobs to complete before returning, or `swarm wait --all` convenience command.

#### Actual Behavior
Jobs submitted asynchronously. Users must script their own polling loops for 10+ jobs.

#### Root Cause Analysis
Swarm is designed for async operation. No convenience mechanism for short jobs where synchronous behavior is preferred.

#### AI Suggested Improvement
- Add `--wait` flag to `swarm query` and `swarm submit` that blocks until completion
- Add `swarm wait [task-id|--all]` command
- Show summary table after `--wait` completes

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

---

### Issue 9: Documentation example task IDs don't match reality

**Severity:** Low
**Category:** Documentation

#### Reproduction
Read swarm.md: `swarm status scrape-task-4`. Real output: `Task ID: ca40ced0-2239-4209-9d81-34bcd50e50c1`.

#### Expected Behavior
Documentation examples use realistic task ID format matching actual output.

#### Actual Behavior
All examples use sequential `scrape-task-N` IDs; actual IDs are UUIDs.

#### Root Cause Analysis
Documentation placeholder IDs don't match backend ID generation scheme.

#### AI Suggested Improvement
- Update examples to use UUID-format IDs or `<task-id>` placeholders
- Or change backend to generate documented `scrape-task-N` format

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

---

### Issue 10: `swarm query` help output is minimal — key options not shown

**Severity:** Low
**Category:** Discoverability

#### Reproduction
```bash
cargo run -- help | grep "swarm query"
# Output: one-line description only
```

#### Expected Behavior
Help summary should list critical options like `--sql` (required) and `--seed-file`.

#### Actual Behavior
Only a one-line description. Users must know to run `swarm query --help` for options.

#### Root Cause Analysis
Main help intentionally shows summaries only. But for commands with required options, surfacing them improves discoverability.

#### AI Suggested Improvement
- Include `--sql <query>` in main help summary for `swarm query`
- Add `[options]` hint: `swarm query <url> [--sql, --seed-file, --refresh]`

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

---

### Issue 5: File path resolution is confusing with `cargo run` working directory

**Severity:** Medium
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Show absolute path tried when file is not found

---

### Issue 7: Extracted image URLs are relative, not absolute

**Severity:** Low
**Category:** Product

#### Review Result

**Decision:** WONTFIX

**Summary:** - Fix `DOM_FIRST_IMG` to return absolute URLs as documented

---

## How to Reproduce

### Common Setup
1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: `swarm list` does not show tasks submitted via `swarm query`
```bash
swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4
swarm query --sql @query.sql --seed-file urls.txt --refresh
swarm list
# Note: query tasks missing from output
```

#### Issue 2: Task IDs are non-sequential UUIDs, making them hard to reference
```bash
swarm query --sql @query.sql --seed-file urls.txt --refresh
# Note the ID format in output
```

#### Issue 3: No `swarm close` command — session closed with generic `close`
```bash
swarm create --display-mode HEADLESS
swarm close  # fails
close        # works
```

#### Issue 4: `swarm status` and `swarm result` return identical JSON for completed jobs
```bash
swarm status <task-id>
swarm result <task-id>
# Compare output — identical
```

#### Issue 5: File path resolution is confusing with `cargo run` working directory
```bash
# From repo root
swarm query --sql @./query.sql --seed-file ./urls.txt --refresh
# vs
swarm query --sql @../../query.sql --seed-file ../../urls.txt --refresh
```

#### Issue 6: `swarm list` shows stale tasks from previous sessions
```bash
swarm create --display-mode HEADLESS
swarm list
# Note pre-existing tasks from earlier sessions
```

#### Issue 7: Extracted image URLs are relative, not absolute
```sql
SELECT DOM_FIRST_ATTR(DOM, '#product-image', 'src') AS image_url
FROM DOM_LOAD_AND_SELECT(@url, ':root')
-- Result: "/ec/static/img/placeholder.png" (relative)
```

#### Issue 8: No `--wait` flag for swarm job submission to block until completion
```bash
swarm query --sql @query.sql --seed-file urls.txt --refresh
# Returns immediately; must manually poll
```

#### Issue 9: Documentation example task IDs don't match reality
Read `skills/browser4-cli/references/swarm.md` — all examples use `scrape-task-N` format.

#### Issue 10: `swarm query` help output is minimal — key options not shown
```bash
cargo run -- help | grep "swarm query"
# Only one-line description
cargo run -- swarm query --help
# Full options shown here only
```

#auto-approve
