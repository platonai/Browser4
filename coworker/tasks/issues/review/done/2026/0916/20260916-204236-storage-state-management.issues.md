# Issues: storage-state-management

> **Source:** `20260916-204236-storage-state-management.full.md` | **Date:** 20260916-204236 | **Mode:** dev

## Scenario Background

### Task

All 17 steps succeeded, and I independently verified each state change through a second code path (`eval` / CDP `Runtime.evaluate`) rather than trusting the CLI's own reporting:

| Step | Result |
|---|---|
| 1. Navigate | ✅ Loaded `interactive-1.html` ("Interactive Single Page") |
| 2–3. Set cookies | ✅ `session_id=abc123` (domain=localhost, path=/, httpOnly, secure); `theme=dark` (sameSite=Lax, expires=1790195928 = exactly +7d) |
| 4–6. List/filter/get | ✅ Both present; `--domain localhost` filter verified genuine (non-matching `example.com` → `[]`); `cookie-get theme` → `dark` |
| 7–9. Delete/clear | ✅ `session_id` removed; `cookie-clear` → `[]`, confirmed via `document.cookie` = `""` |
| 10–12. localStorage | ✅ `user_prefs` JSON round-tripped **byte-exact** through bash→pwsh→CLI→CDP; deleted and cleared |
| 13. sessionStorage | ✅ set/list/get/delete/clear all correct |
| 14–16. State round-trip | ✅ `state-save` produced a Playwright-format file; after clearing, `state-load` restored cookie **and** localStorage with all attributes intact |
| 17. Cleanup | ✅ `browser_state.json` deleted |

Two documented claims I deliberately probed rather than assumed:
- **httpOnly is honored end-to-end** — after restore, `cookie-list` shows the cookie while `document.cookie` returns `""`.
- **`state-load` makes cookies genuinely live** — I loaded a probe file with a *JS-visible* cookie; `document.cookie` then returned `js_visible=yes`, proving restore reaches the page and not just the CLI's view. sessionStorage was correctly excluded from the saved state.

### Execution Context

**Key Commands:**

**Two quoting decisions:**
- `--path /` was run with `MSYS2_ARG_CONV_EXCL='*'`. I tested the unguarded form too: the CLI **detects** the MSYS rewrite and refuses with the exact remediation. The `cookie-set --help` text documents this trap itself — genuinely good work.
- `browser_state.json` was written to `.test-sessions/20260916T1722282030412Z/` per the scratch-dir rule (verified with `ls` that it landed there). `git status` confirms **no stray files** outside `.test-sessions/`; the `.browser4-cli/snapshot/` artifacts are the CLI's own documented default output location.

**Cross-checks performed:** `eval` used as an independent oracle for localStorage, sessionStorage, and cookie visibility; a non-matching domain filter to prove filtering isn't a no-op; a re-delete to test idempotency; `--json` compared against `tab-list`/`eval` as known-good controls.

**Key decisions:** Used an absolute Unix timestamp for `--expires` (matching the task literally) after computing it with `date +%s + 604800`. Added `roundtrip_key`/`ss_key` to the saved state so the round-trip proved more than just the cookie, and to confirm sessionStorage's documented non-persistence.

**Workarounds required:** None for the task itself — every documented command worked as documented. The only friction was the `--path /` MSYS guard, which the CLI documents and detects.



**Summary of the evaluation.** This is a well-built CLI, and the rating of 8 reflects that the task path itself was flawless. Several touches are genuinely above average: `cookie-set` echoes the attributes actually forwarded ("so a flag the backend ignored becomes visible without a cookie-list round trip"), `cookie-clear`/`localstorage-clear` report counts, `-tip` surfaces contextually relevant advice ("use `state-save`/`state-load` to persist cookies…"), and the Git Bash `--path /` trap is both documented and actively detected with the exact remediation rather than silently misbehaving. Error messages for invalid `--sameSite` and invalid domains are precise and correctly exit non-zero.

The one issue worth fixing first is the `--json` payload gap for storage commands (Issue 1) — it is the only silent-data-loss defect, and because `status` still reads `"ok"`, it would be easy to ship a broken script on top of it. The remaining six are low-severity polish, with the trailing-flag inconsistency (Issue 2) being the most user-visible.

One judgment call worth flagging for you: I reported the cookie schema instability (Issue 5) and the "1 entries" pluralization (Issue 6) as Low, but both may be deliberate — conditional field omission is defensible, and the grammar nit may not be worth a code change. I included them for completeness per the brief, and each carries a "no change needed" path in its suggestions.

---

## Issues Found (7 issues)

### Issue 1: --json returns an empty output object for the entire storage command family

**Severity:** High
**Category:** Product

#### Reproduction

./b4w.ps1 -s <session> cookie-list --json
./b4w.ps1 -s <session> cookie-get test_cookie --json
./b4w.ps1 -s <session> localstorage-list --json
./b4w.ps1 -s <session> sessionstorage-list --json
./b4w.ps1 -s <session> cookie-set zz yy --json
./b4w.ps1 -s <session> state-save --json
Compare against a control that works: ./b4w.ps1 -s <session> eval "1+1" --json

#### Expected Behavior

The envelope should carry the payload, as tab-list does: {"status":"ok","command":"cookie-list","output":{"cookies":[...]}}. cookie-get should expose the value; localstorage-list should expose the entries.

#### Actual Behavior

Every storage command emits {"status":"ok","command":"cookie-list","output":{}} — an empty output object. The human-readable path works fine (pretty-printed JSON), and --json correctly suppresses it, but the machine-readable payload is gone. All 14 storage commands are affected (cookie-list/get/set/delete/clear, localstorage-*, sessionstorage-*, state-save/load). Because status is reported as "ok", a script cannot distinguish "no cookies" from "the CLI dropped the data" — this is silent data loss, not a visible failure. The control (eval --json) correctly returns output:{"result":2,"expression":"1+1"}.

#### Root Cause Analysis

The JSON envelope is built at the end of run() from a thread-local accumulator (JSON_OUTPUT, populated only via json_field(), taken by json_finish() at main.rs:~21820). The storage handlers bypass this entirely: handle_cookie_list (main.rs:5074) and handle_storage_list (main.rs:5383) compute the data, serialize it, and print it straight to stdout with cli_println! without ever calling json_field(). json_finish() therefore returns Some(empty map), and the envelope is emitted with status "ok" and output {}. Commands like tab-list and eval populate the accumulator, which is why they work.

#### Code Pointer

`cli/browser4-cli/src/main.rs:5074 (handle_cookie_list), cli/browser4-cli/src/main.rs:5383 (handle_storage_list), plus handle_cookie_get (main.rs:5107) and handle_storage_get; envelope emission at cli/browser4-cli/src/main.rs:21824`

#### AI Suggested Improvement

- In each storage handler, call json_field("cookies", json!(cookies)) / json_field("entries", parsed) / json_field("value", ...) in addition to (or gated on json_active() instead of) the cli_println! call, mirroring how tab-list populates the accumulator.
- Gate the human-readable cli_println! on !json_active() so the two modes never double-emit.
- Add a regression test asserting output is non-empty for every storage command under --json — the empty-object case is easy to miss because status is still "ok".
- Consider making the envelope emitter fail loudly (or warn) when output is an empty object while status is "ok", to catch this class of bug for future command families.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Global flags after the command are silently ignored (long form) or hard-error (short form)

**Severity:** Medium
**Category:** Reliability

#### Reproduction

Trailing long form (silently ignored, exit 0):
  ./b4w.ps1 -s <s> cookie-list --show-tip     # no tip printed
  ./b4w.ps1 -s <s> cookie-list --quiet        # output still printed
Trailing short form (hard error, exit 1):
  ./b4w.ps1 -s <s> cookie-list -tip
  ./b4w.ps1 -s <s> cookie-list -q
Working forms for comparison:
  ./b4w.ps1 -s <s> -tip cookie-list           # tip printed
  ./b4w.ps1 -s <s> -q cookie-list             # output suppressed
  ./b4w.ps1 -s <s> tab-list --json            # honored

#### Expected Behavior

A documented global option (help lists '-tip, --show-tip' and '-q, --quiet') should behave identically before and after the command. SKILL.md already establishes the both-positions convention for --json: "Use --json either before or after the command".

#### Actual Behavior

Behavior depends on both position and spelling, giving four different outcomes: --show-tip trailing is silently ignored (exit 0, zero tips emitted — verified by counting 💡 occurrences: 0 trailing vs 1 leading); --quiet trailing is silently ignored (the cookie list still printed); -tip and -q trailing hard-error with 'unexpected positional arguments'. The silent cases are the dangerous ones: a user adding --quiet to suppress output gets full output and no warning, and a script adding --show-tip believes tips are enabled when they are not. --json trailing works, so the convention is established and the asymmetry is not intentional.

#### Root Cause Analysis

args.rs:108 registers these flags only in the prefix parser, guarded by !seen_command: `else if !seen_command && (arg == "--show-tip" || arg == "-tip")`. Once the command token is consumed, the guard is false, so the flag falls through. Long-form --... tokens are then treated as unknown options and dropped without complaint, whereas single-dash -tip/-q tokens are classified as positional arguments and rejected by the arity check at args.rs:421. Trailing --json works because it is separately and explicitly supported.

#### Code Pointer

`cli/browser4-cli/src/args.rs:108 (the !seen_command guard on global flag recognition)`

#### AI Suggested Improvement

- Either recognize -tip/-q/--show-tip/--quiet in the trailing position too, or reject them explicitly with a message naming the correct position (e.g. "--quiet must appear before the command").
- Never silently swallow an unrecognized global flag: unknown --flags should produce a warning or error rather than being dropped.
- Apply one consistent rule to all global flags so --json, --quiet, and --show-tip share the same both-positions behavior the docs already promise for --json.
- Add tests covering each global flag in both positions.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: --help accepts an unknown category and exits 0

**Severity:** Low
**Category:** Reliability

#### Reproduction

./b4w.ps1 --help bogus
echo $?   # prints 0

#### Expected Behavior

An unknown help category should exit non-zero (the CLI already uses exit 2 for usage errors, as cookie-set with an invalid domain does), so callers and scripts can tell a real topic from a typo.

#### Actual Behavior

Prints 'Unknown command: bogus' to output but exits 0. A script using `--help <topic>` to test whether a command exists will treat any typo as a valid command.

#### Root Cause Analysis

The help dispatch path (resolve_help_target + the --help branch in run(), main.rs:~20440) prints the unknown-command message through the normal help printer and returns Ok(()), never constructing a CliError with a non-zero ExitCode.

#### Code Pointer

`cli/browser4-cli/src/main.rs (help dispatch in run(), resolve_help_target) and cli/browser4-cli/src/help.rs`

#### AI Suggested Improvement

- Return CliError(ExitCode::Usage, ...) when the help target resolves to neither a command nor a category.
- Print the near-miss suggestions already computed by the 'did you mean' helper alongside the error, and list the valid categories.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: --help advertises an incomplete list of help categories

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 help | tail -20          # footer says: try categories: nav, extract, session, kb, agent, swarm
./b4w.ps1 --help storage           # works, exit 0
./b4w.ps1 --help tabs              # works, exit 0
./b4w.ps1 --help config            # works, exit 0

#### Expected Behavior

The advertised category list should include the categories that actually resolve, or the help should state that the names are examples.

#### Actual Behavior

The footer names only 6 categories, but at least storage, tabs, and config also resolve (verified exit 0), and the full help lists 18 bracketed section headers ([Core], [Navigation], [Storage], [Tabs], [Config], [Mouse], [Capture], ...). A first-time user looking for cookie/localStorage help has no way to know '--help storage' exists, and the word 'storage' appears in neither the footer nor the category list.

#### Root Cause Analysis

The category hint string is a hardcoded literal in help.rs:263 rather than being derived from the same category registry that --help <topic> resolves against, so it drifts as categories are added.

#### Code Pointer

`cli/browser4-cli/src/help.rs:263`

#### AI Suggested Improvement

- Generate the category list from the Category enum/registry so it cannot drift.
- If the list must stay short, phrase it as 'e.g. nav, extract, session, …' and add a line pointing at the bracketed section headers in the full help as the authoritative list.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: Cookie JSON omits expires/sameSite but always emits httpOnly/secure, giving an unstable schema

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 -s <s> cookie-set session_id abc123 --domain localhost --path / --httpOnly --secure
./b4w.ps1 -s <s> cookie-set theme dark --sameSite Lax --expires 7d
./b4w.ps1 -s <s> cookie-list

#### Expected Behavior

A stable per-cookie schema, matching the format documented in references/storage-state.md, which shows name/value/domain/path/expires/httpOnly/secure/sameSite on every entry.

#### Actual Behavior

Field presence varies by cookie: session_id returns {name,value,domain,path,httpOnly,secure} with no expires and no sameSite; theme returns all eight fields. Consumers cannot rely on a fixed shape — a strict deserializer (Rust serde struct without defaults, or a typed schema) fails on the reduced form, and a user cannot tell whether sameSite is genuinely absent or merely unreported.

#### Root Cause Analysis

The cookie maps are built by conditionally inserting only the attributes that were set, rather than emitting the full attribute set with null/default values. sorted_cookies_for_display normalizes ordering and integer expires but not field presence.

#### Code Pointer

`cli/browser4-cli/src/main.rs:5074 (handle_cookie_list) and sorted_cookies_for_display (main.rs:~4878)`

#### AI Suggested Improvement

- Emit all documented attributes on every cookie, using null (or the effective default, e.g. sameSite "Lax") when unset.
- Alternatively document the conditional-omission rule explicitly in references/storage-state.md so consumers know which fields are optional.
- The same normalization should apply to cookie-get output for consistency.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: Pluralization bug: 'cleared: 1 entries'

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s <s> localstorage-set only_one v
./b4w.ps1 -s <s> localstorage-clear    # prints: localStorage cleared: 1 entries.

#### Expected Behavior

localStorage cleared: 1 entry.  (and 'N entries' when N != 1) — matching how the rest of the CLI takes care with user-facing text.

#### Actual Behavior

Prints 'localStorage cleared: 1 entries.' — the count is correctly pluralized nowhere. Same message shape is used by sessionstorage-clear.

#### Root Cause Analysis

A single format string hardcodes the plural noun: "{} cleared: {} entries." at main.rs:5537, with no singular branch.

#### Code Pointer

`cli/browser4-cli/src/main.rs:5537`

#### AI Suggested Improvement

- Add a singular/plural branch (entry/entries) based on the count.
- Centralize the count-phrase formatting so cookie-clear and other 'cleared: N' messages stay consistent.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: Error output is double-prefixed as 'Error: error:'

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s <s> cookie-list -tip
# stderr: Error: error: unexpected positional arguments (this command accepts none): ["-tip"]

#### Expected Behavior

A single, clean prefix: 'Error: unexpected positional arguments (this command accepts none): ["-tip"]'.

#### Actual Behavior

The message renders as 'Error: error: unexpected positional arguments …'. The doubled prefix looks like a bug to a user and makes the CLI appear unpolished on its most common failure path.

#### Root Cause Analysis

The message literal at args.rs:421 already begins with 'error: ', and the top-level error printer in main.rs prepends 'Error: ' before displaying it. The two layers disagree on whether they own the prefix.

#### Code Pointer

`cli/browser4-cli/src/args.rs:421`

#### AI Suggested Improvement

- Drop the leading 'error: ' from the literal at args.rs:421 (and the sibling at args.rs:464), letting the top-level printer own the prefix as it does for every other error.
- Audit other error-string literals for the same duplication.

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

**Completion Status:** Successful — all 17 steps completed and verified; every documented storage command worked as documented on the first attempt, with no blocked steps and no workarounds needed for the task itself.

**Success Rate:** 100% of the 17 task steps succeeded. Of the commands exercised, 100% of the task-path commands behaved as documented; the defects found are in adjacent behaviour (machine-readable output, trailing-flag parsing, help metadata) rather than in the documented happy path.

**Issues Found:** 7

**Major Blockers:** None. Two hazards were anticipated and avoided rather than hit: the Git Bash MSYS rewrite of '--path /' was pre-empted with MSYS2_ARG_CONV_EXCL='*' (and the CLI's own guard was then verified to catch it), and the shared DEFAULT session was avoided by using a named session, which mattered because three other runs were concurrently active on the same backend.

**Most Confusing Aspects:** 1) The --json contract: SKILL.md calls it 'the clean machine-readable mode', but for every storage command it returns a success envelope with an empty payload — a silent failure mode with no signal that anything went wrong. 2) Global-flag placement: identical options behave four different ways depending on position and spelling, and the silent-ignore cases give no feedback at all. 3) Discoverability of the storage help: the commands are well documented once found (help cookie-set is excellent, even documenting the Git Bash trap), but '--help storage' is not hinted anywhere in the help footer.

**Most Valuable Improvements:** 1) Fix the storage family's --json payload — it is the only defect that causes silent data loss, and it breaks the documented scripting contract for 14 commands. 2) Make global-flag handling position-independent, and never silently swallow an unrecognized flag. 3) Consolidate the storage docs: the four cookie/storage option families are implemented consistently and documented consistently, but the --json gap shows the envelope path is easy to bypass — a shared helper for storage output would prevent both the JSON bug and the schema inconsistency. 4) Derive the help category list from the registry so it cannot drift.

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

#### Issue 1: --json returns an empty output object for the entire storage command family

./b4w.ps1 -s <session> cookie-list --json
./b4w.ps1 -s <session> cookie-get test_cookie --json
./b4w.ps1 -s <session> localstorage-list --json
./b4w.ps1 -s <session> sessionstorage-list --json
./b4w.ps1 -s <session> cookie-set zz yy --json
./b4w.ps1 -s <session> state-save --json
Compare against a control that works: ./b4w.ps1 -s <session> eval "1+1" --json

#### Issue 2: Global flags after the command are silently ignored (long form) or hard-error (short form)

Trailing long form (silently ignored, exit 0):
  ./b4w.ps1 -s <s> cookie-list --show-tip     # no tip printed
  ./b4w.ps1 -s <s> cookie-list --quiet        # output still printed
Trailing short form (hard error, exit 1):
  ./b4w.ps1 -s <s> cookie-list -tip
  ./b4w.ps1 -s <s> cookie-list -q
Working forms for comparison:
  ./b4w.ps1 -s <s> -tip cookie-list           # tip printed
  ./b4w.ps1 -s <s> -q cookie-list             # output suppressed
  ./b4w.ps1 -s <s> tab-list --json            # honored

#### Issue 3: --help accepts an unknown category and exits 0

./b4w.ps1 --help bogus
echo $?   # prints 0

#### Issue 4: --help advertises an incomplete list of help categories

./b4w.ps1 help | tail -20          # footer says: try categories: nav, extract, session, kb, agent, swarm
./b4w.ps1 --help storage           # works, exit 0
./b4w.ps1 --help tabs              # works, exit 0
./b4w.ps1 --help config            # works, exit 0

#### Issue 5: Cookie JSON omits expires/sameSite but always emits httpOnly/secure, giving an unstable schema

./b4w.ps1 -s <s> cookie-set session_id abc123 --domain localhost --path / --httpOnly --secure
./b4w.ps1 -s <s> cookie-set theme dark --sameSite Lax --expires 7d
./b4w.ps1 -s <s> cookie-list

#### Issue 6: Pluralization bug: 'cleared: 1 entries'

./b4w.ps1 -s <s> localstorage-set only_one v
./b4w.ps1 -s <s> localstorage-clear    # prints: localStorage cleared: 1 entries.

#### Issue 7: Error output is double-prefixed as 'Error: error:'

./b4w.ps1 -s <s> cookie-list -tip
# stderr: Error: error: unexpected positional arguments (this command accepts none): ["-tip"]

