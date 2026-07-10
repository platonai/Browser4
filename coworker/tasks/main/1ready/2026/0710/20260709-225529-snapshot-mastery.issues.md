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

## Issues Found (8 issues)
> **Review complete:** 0 approved, 8 deferred/rejected

### Issue 1: Short flag `-s` conflicts with global `-s <session>` flag

**Severity:** Critical
**Category:** Product

#### Review Result

**Decision:** WONTFIX

**Summary:** - Remove the `-s` short flag from the snapshot `--selector` option to eliminate the collision with the global session `-s` flag

---

### Issue 2: `--selector` CSS selector filtering has no effect on AX tree snapshot output

**Severity:** High
**Category:** Reliability

#### Review Result

**Decision:** WONTFIX

**Summary:** - Verify the `--selector` flag is properly forwarded to the backend API call

---

### Issue 3: `--auto-diff` diff markers not visible in `--stdout` mode

**Severity:** Medium
**Category:** UX / Discoverability

#### Review Result

**Decision:** WONTFIX

**Summary:** - Append the `### Diff` section to stdout output when `--auto-diff` and `--stdout` are combined

---

### Issue 4: `goto` auto-captures a snapshot, interfering with `--auto-diff` baseline

**Severity:** Medium
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add a `--no-snapshot` flag to `goto` to suppress automatic snapshot capture

---

### Issue 5: Session disconnects intermittently between commands

**Severity:** Medium
**Category:** Reliability

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add retry logic for session validation with a brief delay before failing

---

### Issue 6: SKILL.md doesn't document `--selector`/`-s` limitation for AX tree snapshots

**Severity:** Low
**Category:** Documentation

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add a note in SKILL.md clarifying that `--selector` applies to HTML/DOM snapshots but may not filter AX tree output

---

### Issue 7: `snapshot grep --selector` appears to have no filtering effect (same as Issue 2)

**Severity:** Low
**Category:** Reliability

#### Review Result

**Decision:** WONTFIX

**Summary:** - Same suggestions as Issue 2

---

### Issue 8: No `htmlsnapshot` commands tested per task — documentation gap for when to use snapshot vs htmlsnapshot

**Severity:** Low
**Category:** Discoverability

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add a one-sentence rule at the top of the Snapshot section: "Use `snapshot` to get element refs for interaction; use `htmlsnapshot` to extract content via CSS selectors"

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
