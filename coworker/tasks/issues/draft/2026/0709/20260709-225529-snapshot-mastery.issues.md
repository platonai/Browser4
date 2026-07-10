# Issues: snapshot-mastery

> **Source:** `20260709-225529-snapshot-mastery.full.md` | **Date:** 20260709-225529 | **Mode:** dev

## Scenario Background

### Task

All 10 task steps were completed, though two steps required workarounds:

| Step | Description | Status |
|------|-------------|--------|
| 1 | Navigate to Wikipedia page | ✅ Success |
| 2 | Full-page snapshot (`-v 0`) | ✅ Success — 701 nodes, 48 KB |
| 3 | Interactive-only snapshot (`-i`) | ✅ Success — 1015 nodes, 106 KB |
| 4 | CSS selector-scoped snapshot | ⚠️ Workaround required — `-s` fails; used `--selector` instead, but scoping had no effect |
| 5 | Limited depth snapshot (`-d 3`) | ✅ Success — condensed view |
| 6 | Snapshot with URLs (`-u`) | ✅ Success — `/url:` entries visible |
| 7 | Click a link to navigate | ✅ Success — clicked "Pattern languages" link |
| 8 | Auto-diff snapshot | ✅ Success — showed 39 added, 81 removed, 15 modified |
| 9 | Snapshot grep (6 variants) | ✅ Success — all grep options tested |
| 10 | Snapshot to stdout | ✅ Success — YAML format reviewed |

**Task completion:** 10/10 steps completed (2 with workarounds)

---

### Execution Context

**Key Commands:**

```
# Step 0: Preparation
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot --help
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot grep --help

# Step 1: Navigation
cargo run ... -- goto "https://en.wikipedia.org/wiki/Christopher_Alexander"

# Step 2: Full-page snapshot
cargo run ... -- snapshot -v 0

# Step 3: Interactive-only snapshot
cargo run ... -- snapshot -i

# Step 4: Scoped snapshot (workaround: --selector instead of -s)
cargo run ... -- snapshot --selector "#bodyContent" -v 0    # --selector works, -s fails

# Step 5: Limited depth
cargo run ... -- snapshot -d 3 -v 0 --stdout

# Step 6: Snapshot with URLs
cargo run ... -- snapshot -v 0 -u --stdout

# Step 7: Click a link
cargo run ... -- click e14355   # "Pattern languages"

# Step 8: Auto-diff
cargo run ... -- snapshot -v 0 --auto-diff

# Step 9: Snapshot grep variants
cargo run ... -- snapshot grep -i "pattern"
cargo run ... -- snapshot grep -C 3 "language"
cargo run ... -- snapshot grep -v "generic"
cargo run ... -- snapshot grep -c -i "pattern"
cargo run ... -- snapshot grep -F "pattern language"
cargo run ... -- snapshot grep -w "pattern"
cargo run ... -- snapshot grep -i "pattern" --selector "main"

# Step 10: Snapshot to stdout
cargo run ... -- snapshot -v 0 --stdout
```

**Workarounds Applied During Task:**

1. `-s` flag conflict: Used `--selector` long-form instead
2. CSS selector scoping non-functional: Accepted full snapshot output
3. Auto-diff baseline management: Manually took explicit baseline snapshot before navigation

---

---

## Issues Found (8 issues)

### Issue 1: Short flag `-s` conflicts with global `-s <session>` flag

**Severity:** Critical
**Category:** Product

#### Reproduction

```bash
cargo run -- -- snapshot -s "#bodyContent" -v 0
```

#### Expected Behavior

Captures a snapshot scoped to the `#bodyContent` CSS selector.

#### Actual Behavior

Error: `🔐 Session required — No active session is currently stored for this CLI context.`

#### Root Cause Analysis

The snapshot command defines `-s, --selector` as a short flag for CSS selector scoping, but `-s` is also a global option for `-s <name>` (named session label). When `-s "#bodyContent"` is used, the CLI parser interprets `-s` as the global session option and treats `#bodyContent` as a session name, then fails because no session named `#bodyContent` exists. The long form `--selector` works because it is unambiguous.

#### Code Pointer

``cli/browser4-cli/src/args.rs` — argument parsing where the snapshot `-s` flag is defined alongside the global `-s` flag.`

#### AI Suggested Improvement

- Remove the `-s` short flag from the snapshot `--selector` option to eliminate the collision with the global session `-s` flag
- Alternatively, rename the snapshot short flag to a different letter (e.g., `-S` for uppercase) 
- Add a test case that exercises `cargo run -- snapshot -s "selector"` to catch flag collisions
- Document this known limitation in the SKILL.md until the code fix is deployed

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 2: `--selector` CSS selector filtering has no effect on AX tree snapshot output

**Severity:** High
**Category:** Reliability

#### Reproduction

```bash
cargo run -- -- snapshot -v 0 --stdout > baseline.yml
cargo run -- -- snapshot --selector "#bodyContent" -v 0 --stdout > scoped.yml
diff baseline.yml scoped.yml  # no differences
cargo run -- -- snapshot --selector "#mw-content-text" -v 0 --stdout > scoped2.yml
diff baseline.yml scoped2.yml  # no differences
```

All three snapshots are identical (verified via `diff`). The `--selector` flag is accepted without error but produces no filtering.

#### Expected Behavior

The snapshot should only include elements within the DOM subtree of the element matching the CSS selector.

#### Actual Behavior

The output is identical to a non-scoped snapshot.

#### Root Cause Analysis

Uncertain — the selector may be applied at a different layer (DOM serialization) that doesn't affect the AX tree. Or the flag may be parsed correctly but not passed through to the backend's AX tree generation. The `#bodyContent` and `#mw-content-text` selectors are valid elements on Wikipedia pages, so selector resolution failure is unlikely. Investigation needed to determine whether this is a backend issue, a flag-passing issue, or a design limitation where selectors only affect other snapshot formats.

#### Code Pointer

`Unknown — requires investigation to determine whether the issue is in the CLI (flag not forwarded) or the backend (selector ignored during AX tree generation).`

#### AI Suggested Improvement

- Verify the `--selector` flag is properly forwarded to the backend API call
- Add integration tests that verify scoped snapshots are smaller than full snapshots on a known page
- If CSS selector scoping is not supported for AX tree snapshots, the help text should clearly state this limitation
- Consider adding a distinct output mode or warning when the selector matches no elements

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 3: `--auto-diff` diff markers not visible in `--stdout` mode

**Severity:** Medium
**Category:** UX / Discoverability

#### Reproduction

```bash
cargo run -- -- snapshot -v 0 --auto-diff --stdout
# Output shows full snapshot YAML with no diff annotations
# Compare with:
cargo run -- -- snapshot -v 0 --auto-diff
# Output shows `### Diff` section with added/removed/modified lists
```

#### Expected Behavior

The diff output (added, removed, modified elements) should appear either in stdout mode or in both modes.

#### Actual Behavior

In `--stdout` mode, the diff section is omitted entirely. Only in file-based output mode does the `### Diff` section appear.

#### Root Cause Analysis

The diff computation is likely a post-processing step that only attaches to the file-based output path. In `--stdout` mode, the raw snapshot YAML is printed directly without diff processing.

#### Code Pointer

``cli/browser4-cli/src/snapshot.rs` — likely in the snapshot output routing where `--stdout` bypasses the diff computation.`

#### AI Suggested Improvement

- Append the `### Diff` section to stdout output when `--auto-diff` and `--stdout` are combined
- Alternatively, add a `--diff-only` flag that outputs only the diff in stdout mode
- Document the current limitation in the `--auto-diff` help text until fixed

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 4: `goto` auto-captures a snapshot, interfering with `--auto-diff` baseline

**Severity:** Medium
**Category:** UX

#### Reproduction

```bash
cargo run -- -- snapshot -v 0          # baseline: page A
cargo run -- -- goto "https://page-b"  # auto-captures snapshot of page B
cargo run -- -- snapshot -v 0 --auto-diff  # diffs page B vs page B (no meaningful changes)
```

#### Expected Behavior

The auto-diff should compare page B against page A (the pre-navigation baseline).

#### Actual Behavior

The auto-diff compares against the auto-captured snapshot from `goto`, which is also page B. The meaningful cross-page diff is lost.

#### Root Cause Analysis

`goto` automatically captures a snapshot on successful navigation. This snapshot becomes the most recent one and therefore the baseline for `--auto-diff`. There is no way to suppress this behavior.

#### Code Pointer

``cli/browser4-cli/src/commands.rs` or `src/main.rs` — where `goto` triggers automatic snapshot capture.`

#### AI Suggested Improvement

- Add a `--no-snapshot` flag to `goto` to suppress automatic snapshot capture
- Alternatively, have `--auto-diff` compare against the last *user-initiated* snapshot (not auto-captured ones)
- Document the auto-snapshot behavior and its impact on `--auto-diff` in both the help text and SKILL.md
- Add a Core Loop example showing the correct pattern: snapshot → goto → auto-diff (not: goto → auto-diff)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 5: Session disconnects intermittently between commands

**Severity:** Medium
**Category:** Reliability

#### Reproduction

```bash
cargo run -- -- goto "https://en.wikipedia.org/wiki/Christopher_Alexander"
cargo run -- -- snapshot -v 0         # works
cargo run -- -- snapshot -i           # works
cargo run -- -- snapshot -s "#bodyContent" -v 0  # fails: "No active session"
cargo run -- -- list                  # shows session as "Active"
cargo run -- -- goto "<same-url>"     # "Reconnected to existing session"
```

#### Expected Behavior

Session should persist reliably across consecutive commands without disconnecting.

#### Actual Behavior

Session state occasionally reads as missing (`No active session`) even though `list` shows it as Active and `goto` can reconnect to it.

#### Root Cause Analysis

Possible race condition in state persistence or session validation. The state file at `~/.browser4/cli-state.json` tracks the session, but there may be a timing issue where the session check happens before the state is fully written, or the backend session may have a shorter TTL than expected.

#### Code Pointer

``cli/browser4-cli/src/state.rs` — CLI state management; `src/main.rs` — session validation at command dispatch.`

#### AI Suggested Improvement

- Add retry logic for session validation with a brief delay before failing
- Investigate whether the backend session TTL is too short for interactive CLI usage
- Add a `--session-check` flag to explicitly verify session health before commands
- Improve error message to distinguish "session never existed" from "session expired"

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 6: SKILL.md doesn't document `--selector`/`-s` limitation for AX tree snapshots

**Severity:** Low
**Category:** Documentation

#### Reproduction

Read `skills/browser4-cli/SKILL.md` — the `-s, --selector` flag is listed under snapshot options without noting any constraints on when scoping applies.

#### Expected Behavior

Documentation should clarify whether `--selector` works for AX tree snapshots, HTML snapshots, or both.

#### Actual Behavior

No mention of this distinction. Users may waste time trying to scope AX tree snapshots with CSS selectors.

#### Root Cause Analysis

The SKILL.md describes `--selector` in the snapshot flags list but doesn't provide a detailed explanation of its behavior. The help text says "Scope snapshot to a CSS selector" without clarifying which snapshot type this applies to.

#### Code Pointer

``skills/browser4-cli/SKILL.md` — snapshot flags documentation; `cli/browser4-cli/src/help.rs` — help text generation.`

#### AI Suggested Improvement

- Add a note in SKILL.md clarifying that `--selector` applies to HTML/DOM snapshots but may not filter AX tree output
- Add an example in the snapshot section showing selector usage with expected output
- Update the help text to say "Scope snapshot to a CSS selector (for HTML snapshots)" if the limitation is by design

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 7: `snapshot grep --selector` appears to have no filtering effect (same as Issue 2)

**Severity:** Low
**Category:** Reliability

#### Reproduction

```bash
cargo run -- -- snapshot grep -i "pattern" --selector "main"
# Output still includes matches from outside <main> (e.g., header user menu)
```

#### Expected Behavior

Grep results should be limited to elements within the `main` element.

#### Actual Behavior

Results include elements from the entire page, including the site header. Same root cause as Issue 2 — CSS selector scoping doesn't filter the AX tree.

#### Root Cause Analysis

Same as Issue 2. The selector is either not forwarded to the grep operation or the backend doesn't apply it.

#### AI Suggested Improvement

- Same suggestions as Issue 2

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 8: No `htmlsnapshot` commands tested per task — documentation gap for when to use snapshot vs htmlsnapshot

**Severity:** Low
**Category:** Discoverability

#### Reproduction

A new user reading the SKILL.md Quick Start pattern sees both `snapshot` and `htmlsnapshot` but the distinction between them is spread across multiple sections. The Decision Tree (§4a) helps but doesn't explicitly say when to prefer one over the other for basic AX tree navigation.

#### Expected Behavior

New users should immediately understand that `snapshot` is for interactive element discovery (AX tree with refs) and `htmlsnapshot` is for content extraction (DOM-based, CSS selectors).

#### Actual Behavior

This distinction is implied through examples but never stated as an explicit rule. The Concepts section explains snapshots but doesn't contrast them with htmlsnapshot.

#### Root Cause Analysis

The SKILL.md is comprehensive but the snapshot-vs-htmlsnapshot distinction is distributed across multiple sections (Core Loop, Key Concepts, Decision Trees).

#### AI Suggested Improvement

- Add a one-sentence rule at the top of the Snapshot section: "Use `snapshot` to get element refs for interaction; use `htmlsnapshot` to extract content via CSS selectors"
- Add a comparison table in the Key Concepts section showing when to use each
- In the Quick Start template, show a pattern that includes both snapshot and htmlsnapshot usage

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

#### Issue 1: Short flag `-s` conflicts with global `-s <session>` flag

```bash
cargo run -- -- snapshot -s "#bodyContent" -v 0
```

#### Issue 2: `--selector` CSS selector filtering has no effect on AX tree snapshot output

```bash
cargo run -- -- snapshot -v 0 --stdout > baseline.yml
cargo run -- -- snapshot --selector "#bodyContent" -v 0 --stdout > scoped.yml
diff baseline.yml scoped.yml  # no differences
cargo run -- -- snapshot --selector "#mw-content-text" -v 0 --stdout > scoped2.yml
diff baseline.yml scoped2.yml  # no differences
```

All three snapshots are identical (verified via `diff`). The `--selector` flag is accepted without error but produces no filtering.

#### Issue 3: `--auto-diff` diff markers not visible in `--stdout` mode

```bash
cargo run -- -- snapshot -v 0 --auto-diff --stdout
# Output shows full snapshot YAML with no diff annotations
# Compare with:
cargo run -- -- snapshot -v 0 --auto-diff
# Output shows `### Diff` section with added/removed/modified lists
```

#### Issue 4: `goto` auto-captures a snapshot, interfering with `--auto-diff` baseline

```bash
cargo run -- -- snapshot -v 0          # baseline: page A
cargo run -- -- goto "https://page-b"  # auto-captures snapshot of page B
cargo run -- -- snapshot -v 0 --auto-diff  # diffs page B vs page B (no meaningful changes)
```

#### Issue 5: Session disconnects intermittently between commands

```bash
cargo run -- -- goto "https://en.wikipedia.org/wiki/Christopher_Alexander"
cargo run -- -- snapshot -v 0         # works
cargo run -- -- snapshot -i           # works
cargo run -- -- snapshot -s "#bodyContent" -v 0  # fails: "No active session"
cargo run -- -- list                  # shows session as "Active"
cargo run -- -- goto "<same-url>"     # "Reconnected to existing session"
```

#### Issue 6: SKILL.md doesn't document `--selector`/`-s` limitation for AX tree snapshots

Read `skills/browser4-cli/SKILL.md` — the `-s, --selector` flag is listed under snapshot options without noting any constraints on when scoping applies.

#### Issue 7: `snapshot grep --selector` appears to have no filtering effect (same as Issue 2)

```bash
cargo run -- -- snapshot grep -i "pattern" --selector "main"
# Output still includes matches from outside <main> (e.g., header user menu)
```

#### Issue 8: No `htmlsnapshot` commands tested per task — documentation gap for when to use snapshot vs htmlsnapshot

A new user reading the SKILL.md Quick Start pattern sees both `snapshot` and `htmlsnapshot` but the distinction between them is spread across multiple sections. The Decision Tree (§4a) helps but doesn't explicitly say when to prefer one over the other for basic AX tree navigation.

