# Issues: visual-screenshot-controls

> **Source:** `20260706-215254-visual-screenshot-controls.full.md` | **Date:** 20260706-215254 | **Mode:** dev

## Scenario Background

### Task

All 10 steps completed successfully:

| # | Step | Command | Result |
|---|------|---------|--------|
| 1 | Resize to 1280×900 | `resize 1280 900` | ✅ No error (no dimension confirmation) |
| 2 | Go to Wikipedia LLM | `goto "https://en.wikipedia.org/wiki/Large_language_model"` | ✅ Page loaded |
| 3 | Wait network idle | `wait --load networkidle` | ✅ "✓ Wait complete" |
| 4 | Default screenshot | `screenshot` | ✅ 423KB saved |
| 5 | Scroll down ×3 | `scroll down 700` (×3) | ✅ Scrolled to 2100px |
| 6 | Wait for content | `wait --text "History"` | ✅ Text found |
| 7 | Full-page screenshot | `screenshot --full-page --filename "wikipedia-llm-fullpage.png"` | ✅ 7.6MB saved |
| 8 | Scroll to top | `scroll up 2100` | ✅ Position 0.0 |
| 9 | Wait 2s + screenshot | `wait 2000` → `screenshot` | ✅ 552KB saved |
| 10 | Save PDF | `pdf --filename "wikipedia-llm-article.pdf"` | ✅ 2.6MB saved |

**Output files:**
- `screenshot-2026-07-06T21-48-10-513Z.png` — initial viewport (423KB)
- `wikipedia-llm-fullpage.png` — full-page (7.6MB)
- `screenshot-2026-07-06T21-51-16-573Z.png` — top after scroll-back (552KB)
- `wikipedia-llm-article.pdf` — PDF (2.6MB)

---

### Execution Context

**Key Commands:**

1. `cargo run -- --help` — initial discovery
2. `cargo run -- screenshot --help` — inspect screenshot flags
3. `cargo run -- pdf --help` — inspect pdf flags
4. `cargo run -- wait --help` — inspect wait flags
5. `cargo run -- scroll --help` — inspect scroll flags
6. `cargo run -- resize --help` — inspect resize flags
7. `cargo run -- resize 1280 900`
8. `cargo run -- goto "https://en.wikipedia.org/wiki/Large_language_model"`
9. `cargo run -- wait --load networkidle`
10. `cargo run -- screenshot`
11. `cargo run -- scroll down 700` (×3)
12. `cargo run -- wait --text "History" --timeout 15000`
13. `cargo run -- screenshot --full-page --filename "wikipedia-llm-fullpage.png"`
14. `cargo run -- scroll up 2100`
15. `cargo run -- wait 2000`
16. `cargo run -- screenshot`
17. `cargo run -- pdf --filename "wikipedia-llm-article.pdf"`

**Key decisions:**
- Used development-mode invocation (`cd cli/browser4-cli && cargo run --`) per SKILL.md
- Scrolled 700px × 3 (2100px total) to move past the lead section into "History"
- Scrolled exactly 2100px back up to return to position 0.0
- Used `--text "History"` for the content-wait check since that heading appears in the first scrollable section

**Workarounds required:** None — all commands worked as documented.

---

---

## Issues Found (7 issues)

### Issue 1: `resize` command does not confirm the new viewport dimensions

**Severity:** Medium
**Category:** UX

#### Reproduction

`browser4-cli resize 1280 900`

#### Expected Behavior

Output confirms the new dimensions, e.g. "Resized to 1280×900" or "Viewport: 1280×900".

#### Actual Behavior

The command outputs the current page URL, title, and a snapshot file — but no indication of whether or what dimensions were applied. The word "resize" does not appear in the output at all.

#### Root Cause Analysis

The resize command appears to delegate to a general page-interaction handler that returns page state + snapshot after every command. The resize-specific success/dimension information is not surfaced in the output. The implementation likely calls `Page.setViewport` via CDP but doesn't echo back the applied dimensions.

#### Code Pointer

``cli/browser4-cli/src/commands.rs` — the resize command handler; also potentially the server-side MCP tool handler for `resize`.`

#### AI Suggested Improvement

- After a successful resize, output "Resized to {w}×{h}" on stdout (or under a `### Viewport` section in the output)
- Consider suppressing the full page snapshot on resize — it's an unnecessary side effect for a viewport-only operation
- If the browser wasn't open yet and resize auto-opened one, note that explicitly ("Session auto-opened; resized to 1280×900")

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: `scroll` and `resize` commands silently produce full snapshot files as side effects

**Severity:** Medium
**Category:** Product

#### Reproduction

`browser4-cli scroll down 700` or `browser4-cli resize 1280 900`

#### Expected Behavior

Scroll/resize are lightweight viewport operations. They should not produce snapshot files by default, or the behavior should be clearly documented.

#### Actual Behavior

Each `scroll` and `resize` invocation writes an 80–130KB `.yml` snapshot file to `.browser4-cli/snapshot/`. Over a session, this generates substantial disk clutter (5 extra snapshots in this 10-step task). The help text for neither command mentions this side effect.

#### Root Cause Analysis

The command dispatch pipeline appears to trigger a snapshot after every state-modifying command, regardless of whether the user needs one. This is a "capture-everything" default that trades disk space for convenience without the user's knowledge.

#### Code Pointer

``cli/browser4-cli/src/commands.rs` — the post-command snapshot logic, or the server-side response that always includes snapshot metadata.`

#### AI Suggested Improvement

- Decouple snapshot capture from resize/scroll — only snapshot when the user explicitly requests it or when a DOM-mutating interaction occurs
- Alternatively, add a `--no-snapshot` flag to suppress the auto-snapshot on lightweight commands
- Document the auto-snapshot behavior in `scroll --help` and `resize --help`
- Consider a lighter "status" response (URL + scroll position only, no full AX tree) for non-DOM-mutating commands

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**
Decouple snapshot capture from resize/scroll

---

### Issue 3: Time-based `wait` has no completion confirmation (inconsistent with other wait modes)

**Severity:** Low
**Category:** UX

#### Reproduction

`browser4-cli wait 2000`

#### Expected Behavior

Consistent output across all wait modes — e.g. "✓ Wait complete" or "Waited 2000ms".

#### Actual Behavior

`wait --load networkidle` outputs "✓ Wait complete". `wait --text "History"` outputs "✓ Wait complete". `wait 2000` outputs nothing at all — just the cargo build status line and then silence. The user cannot tell if the command finished successfully or hung.

#### Root Cause Analysis

The time-based wait path (`wait <milliseconds>`) likely has a different code path from the other wait modes that omits the success message. The discrepancy may be in how the positional numeric argument is routed vs. the named option arguments.

#### Code Pointer

``cli/browser4-cli/src/commands.rs` — the `wait` command handler, specifically the branch for a numeric positional argument.`

#### AI Suggested Improvement

- Add a completion message for time-based waits: "Waited {ms}ms" or "✓ Wait complete ({ms}ms)"
- Ensure all five wait modes (selector, time, text, url, load, fn) produce a consistent success indicator

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: Stale browser session from previous use — first command operates on an unexpected page

**Severity:** Medium
**Category:** Reliability

#### Reproduction

Run `browser4-cli resize 1280 900` as the first command in a new terminal session.

#### Expected Behavior

Either an error ("no active session — use `goto` or `open` first"), or the command auto-starts a clean session.

#### Actual Behavior

The command succeeded silently, but the output showed it was operating on `https://en.wikipedia.org/wiki/Web_scraping` — a page from a previous session. A new user would not expect their first command to be interacting with a page they didn't navigate to. If they had proceeded to take screenshots or interact with elements, they would get results from the wrong page.

#### Root Cause Analysis

The browser session and its CDP connection persist beyond individual CLI invocations. The daemon keeps the browser alive, and the CLI reconnects to the last-used session automatically. This is a feature (session persistence) but becomes a footgun when the user doesn't realize a session is already active.

#### Code Pointer

``cli/browser4-cli/src/main.rs` — session auto-reconnect logic; `cli/browser4-cli/src/state.rs` — persistent session state.`

#### AI Suggested Improvement

- When reconnecting to an existing session, print a notice: "Reconnected to existing session on {url}" so the user knows what page is active
- Add a `session-info` or `current-url` command that shows the active page without triggering any side effects
- Consider a `--fresh` flag on `goto`/`open` that explicitly starts a clean session
- For the `resize` command specifically: if no explicit navigation has happened in this CLI invocation, consider whether it should auto-navigate or warn about the stale page

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**
only accept the first: When reconnecting to an existing session, print a notice: "Reconnected to existing session on {url}" so the user knows what page is active

---

### Issue 5: No way to specify output directory for screenshots and PDFs — all outputs land in `.browser4-cli/snapshot/`

**Severity:** Low
**Category:** UX

#### Reproduction

`browser4-cli screenshot --filename "my-shot.png"` — the file is saved to `.browser4-cli/snapshot/my-shot.png`, not the current working directory.

#### Expected Behavior

`--filename` accepts a path, or there's a separate `--output-dir` flag. At minimum, the help text should state where files are saved.

#### Actual Behavior

`--filename` only changes the filename; the directory is always `.browser4-cli/snapshot/`. The help text (`screenshot --help`) says "File name to save the screenshot to" which implies the user controls the destination — but they can only control the name, not the directory.

#### Root Cause Analysis

The file-saving logic in snapshot.rs resolves paths relative to the snapshot directory rather than the current working directory or the path provided by the user.

#### Code Pointer

``cli/browser4-cli/src/snapshot.rs` — the screenshot/PDF file-saving logic that resolves paths against the snapshot directory.`

#### AI Suggested Improvement

- Allow `--filename` to accept relative or absolute paths (e.g. `../my-shot.png`, `/tmp/my-shot.png`)
- If only a bare filename is given (no path separators), keep the current behavior of saving to the snapshot directory
- Update the help text to clarify: "File name or path to save the screenshot to. Bare filenames are saved to the snapshot directory."

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: Development-mode invocation is verbose and error-prone

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

Following the SKILL.md dev instructions: `cd cli/browser4-cli && cargo run -- <command>`.

#### Expected Behavior

There should be a simpler way to invoke the local build — an alias, a wrapper script, or an environment variable. The `cd cli/browser4-cli && cargo run --` prefix is 60+ characters before the actual command begins.

#### Actual Behavior

Every command requires the full `cd cli/browser4-cli && cargo run --` prefix. This adds friction, makes command output harder to read (the cargo build lines mix with CLI output), and is error-prone (forgetting `--` passes flags to cargo instead of browser4-cli). A new developer reading SKILL.md would type this prefix 15+ times for a simple task.

#### Root Cause Analysis

No convenience wrapper (Makefile target, npm script, shell alias, or justfile) is provided in the repository for running the CLI from source. The SKILL.md documents the raw `cargo run --` pattern but doesn't suggest creating an alias.

#### Code Pointer

`Repository root — no `Makefile`, `justfile`, or npm scripts exist for `cli` invocation. `cli/browser4-cli/README.md` documents the build but not a convenience invocation pattern.`

#### AI Suggested Improvement

- Document a shell alias in SKILL.md: `alias b4='cd cli/browser4-cli && cargo run --'`
- Add a convenience script at the repo root: `./b4 <command>` (shell script or PowerShell `.ps1`)
- Or add a cargo alias: `cargo run --bin browser4-cli --` from the workspace root if using a Cargo workspace
- At minimum, add a tip in SKILL.md suggesting the user create an alias before starting

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [x] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: Help text uses inconsistent terminology between "screenshot" command and "save as" category

**Severity:** Low
**Category:** Documentation

#### Reproduction

Run `browser4-cli --help` and observe the category structure.

#### Expected Behavior

The category name "Save as" matches the command concepts — screenshot and pdf are visual exports.

#### Actual Behavior

The category is called "Save as" but neither command name uses that verb. The `screenshot` command help says "Screenshot of the current page or element" and `pdf` says "Save page as PDF". The verb changes between "Screenshot" (noun used as verb) and "Save" — inconsistent. Additionally, the category "Save as" suggests a file-save dialog metaphor, but neither `screenshot` nor `pdf` contain the word "save" in their name. A user scanning for "how to save a screenshot" might look for a `save` command.

#### Root Cause Analysis

The category naming was chosen for conceptual grouping but diverges from the actual command naming convention. Other categories are nouns ("Navigation", "Keyboard", "Mouse", "Tabs") while "Save as" is a verb phrase.

#### AI Suggested Improvement

- Rename the category to "Capture" or "Export" to match the noun-based convention of other categories
- Or rename commands to `save-screenshot` and `save-pdf` for consistency with the category name

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
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

#### Issue 1: `resize` command does not confirm the new viewport dimensions

`browser4-cli resize 1280 900`

#### Issue 2: `scroll` and `resize` commands silently produce full snapshot files as side effects

`browser4-cli scroll down 700` or `browser4-cli resize 1280 900`

#### Issue 3: Time-based `wait` has no completion confirmation (inconsistent with other wait modes)

`browser4-cli wait 2000`

#### Issue 4: Stale browser session from previous use — first command operates on an unexpected page

Run `browser4-cli resize 1280 900` as the first command in a new terminal session.

#### Issue 5: No way to specify output directory for screenshots and PDFs — all outputs land in `.browser4-cli/snapshot/`

`browser4-cli screenshot --filename "my-shot.png"` — the file is saved to `.browser4-cli/snapshot/my-shot.png`, not the current working directory.

#### Issue 6: Development-mode invocation is verbose and error-prone

Following the SKILL.md dev instructions: `cd cli/browser4-cli && cargo run -- <command>`.

#### Issue 7: Help text uses inconsistent terminology between "screenshot" command and "save as" category

Run `browser4-cli --help` and observe the category structure.

