# Issues: form-filling

> **Source:** `20260706-203229-form-filling.full.md` | **Date:** 20260706-203229 | **Mode:** dev

## Scenario Background

### Task

**Form successfully filled and submitted.** All values were captured and confirmed:

| Field | Value |
|-------|-------|
| First Name | Jane |
| Last Name | Smith |
| Email | jane.smith@example.com |
| Country | United States (us) |
| Experience Level | Advanced |
| Comments | "I want to automate form testing and data extraction workflows." |
| Topics | automation, testing, AI agents |
| Agree to terms | Checked |
| Newsletter | Unchecked |

**Confirmation:** "Submission Result — Your form data was captured successfully." All validation checks passed (5/5 required fields completed).

---

### Execution Context

**Key Commands:**

1. `cargo run -- goto "http://localhost:18080/generated/form-filling.html"` — navigate to form
2. `cargo run -- snapshot -i` — interactive snapshot to discover form structure and refs
3. `Read` snapshot YAML file — inspected form fields, identified refs e63-e78, e230
4. `cargo run -- fill e63 "Jane"` — fill first name
5. `cargo run -- fill e64 "Smith"` — fill last name
6. `cargo run -- fill e65 "jane.smith@example.com"` — fill email
7. `cargo run -- fill e70 "I want to automate form testing and data extraction workflows."` — fill comments
8. `cargo run -- select e66 "United States"` — select country
9. `cargo run -- select e69 "Advanced"` — select experience level
10. `cargo run -- check e75 && check e76 && check e78` — check Testing, AI agents, agree-to-terms
11. `cargo run -- click e230` — submit form
12. `cargo run -- snapshot -i` — re-snapshot to verify result
13. `Read` result snapshot — verified submission success in result panel
14. `cargo run -- htmlsnapshot` — capture HTML snapshot for CSS selector queries
15. `cargo run -- htmlsnapshot get text "#result-panel"` — extract confirmation text: "Submission Result Your form data was captured successfully."
16. `cargo run -- snapshot -v 0 --stdout` — full-viewport snapshot of result page

**Important decisions:**
- Used `--stdout` for the final snapshot to get inline output rather than opening a file
- Used `#result-panel` CSS selector (from htmlsnapshot interactive elements list) instead of `.result-panel` (which failed with `get text`)
- Kept Email as contact method (didn't toggle to Phone/Business to reveal hidden dynamic fields, since they weren't required)

**Workarounds:**
- Had to use absolute path for `cd` (relative `cli/browser4-cli` failed in bash on Windows)
- Had to Read snapshot YAML files manually to see element refs (interactive snapshot output only shows metadata)
- Had to capture htmlsnapshot before using CSS selectors with `htmlsnapshot get text`

---

---

---

## Issues Found (6 issues)
> **Review complete:** 2 approved, 4 deferred/rejected

### Issue 2: `select` command confirmation message shows empty string

**Severity:** Low
**Category:** UX

#### Reproduction

`cargo run -- select e66 "United States"`

#### Expected Behavior

`✓ Selected 'United States' in e66`

#### Actual Behavior

`✓ Selected '' in e66` (the actual value `["us"]` appears on a separate line above)

#### Root Cause Analysis

The confirmation message likely reads back `element.value` after selection, which returns the `value` attribute (`"us"`) rather than the display text. The empty string suggests the post-selection value-read logic may be querying the wrong property or the element state hasn't settled.

#### Code Pointer

``cli/browser4-cli/src/commands.rs` — select command success message formatting`

#### AI Suggested Improvement

- Show the human-readable option text in the confirmation, or show both the value and text: `✓ Selected 'United States' (value: 'us') in e66`
- If the value is non-empty but the text is empty, at least show the value: `✓ Selected value 'us' in e66`

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

---

### Issue 3: `get text` requires prior htmlsnapshot capture — confusing error for new users

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

1. `cargo run -- goto "http://localhost:18080/generated/form-filling.html"`
2. `cargo run -- get text ".result-panel"`

#### Expected Behavior

Either (a) the command works directly, or (b) a clear error message explains the two-step workflow.

#### Actual Behavior

Returns `null` with message: `No elements matched ".result-panel". Try htmlsnapshot inspect ".result-panel" to discover valid selectors, or run htmlsnapshot to see the full DOM tree.` The user must first run `htmlsnapshot` to capture, then `htmlsnapshot get text` to query. The `get` command (without `htmlsnapshot` prefix) is a different code path that queries the live page differently.

#### Root Cause Analysis

Two separate extraction subsystems exist: `get` (live DOM via CDP) and `htmlsnapshot get` (stored HTML snapshot via CSS selectors). The `get` command uses a different selector engine that doesn't match the CSS selectors shown in htmlsnapshot output. The error message conflates these two paths.

#### Code Pointer

``cli/browser4-cli/src/commands.rs` — get command handler; error message generation`

#### AI Suggested Improvement

- When `get` returns no results, suggest running `htmlsnapshot` followed by `htmlsnapshot get` as an alternative path, distinguishing the two subsystems clearly.
- Consider unifying the two extraction paths or making `get` fall back to htmlsnapshot-based extraction automatically.
- The SKILL.md decision tree (§4a) covers this well but the error message doesn't reference it.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

---

### Issue 1: Interactive snapshot (`-i`) does not display element refs inline

**Severity:** Medium
**Category:** UX

#### Review Result

**Decision:** REJECT

**Notes:** no matter since ai agents can understand it easily

**Summary:** - Make `-i` imply inline ref output (or at minimum, automatically print refs alongside the metadata). Interactive mode's purpose is element discovery — hiding the elements defeats the purpose.

---

### Issue 4: `cargo run` invocation requires manual `cd` — no repo-root shortcut

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Document `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- <command>` as a single-command invocation from repo root.

---

### Issue 5: Snapshot YAML is verbose — hard to scan for interactive elements

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** DEFER

**Summary:** - Add a `--refs-only` or `--interactive-only` flag that outputs a flat table of interactive elements: `| ref | type | label | state |`

---

### Issue 6: SKILL.md documentation is comprehensive but difficult to navigate for first-time users

**Severity:** Medium
**Category:** Documentation

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add a 5-minute quick-start section with a single concrete end-to-end example (goto → snapshot → fill → click → verify) fully explained.

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Interactive snapshot (`-i`) does not display element refs inline

`cargo run -- snapshot -i`

#### Issue 2: `select` command confirmation message shows empty string

`cargo run -- select e66 "United States"`

#### Issue 3: `get text` requires prior htmlsnapshot capture — confusing error for new users

1. `cargo run -- goto "http://localhost:18080/generated/form-filling.html"`
2. `cargo run -- get text ".result-panel"`

#### Issue 4: `cargo run` invocation requires manual `cd` — no repo-root shortcut

Running `cargo run -- <command>` from the repo root fails because the Cargo project is in `cli/browser4-cli/`. Every command requires `cd "D:/workspace/Browser4/Browser4-4.11/cli/browser4-cli" && cargo run -- ...`

#### Issue 5: Snapshot YAML is verbose — hard to scan for interactive elements

`cargo run -- snapshot -v 0` on the form-filling page produces 100+ lines of nested YAML. Interactive elements (textboxes, checkboxes, buttons) are deeply nested inside generic containers and banner/main/complementary landmarks.

#### Issue 6: SKILL.md documentation is comprehensive but difficult to navigate for first-time users

Reading `skills/browser4-cli/SKILL.md` as a first-time user. The document is ~250 lines with 7 major sections, 4 decision trees, a command map table, and references to 15+ separate reference files.
