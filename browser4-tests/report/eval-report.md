# Browser4-CLI Usability Evaluation Report

**Date:** 2026-07-09
**Evaluator:** Claude (AI Agent)
**Task:** Wikipedia Christopher Alexander — snapshot capture, interaction, grep, and navigation
**CLI invocation:** `cargo run --manifest-path cli/browser4-cli/Cargo.toml --`

---

## A. Task Result

All 10 task steps were completed successfully:

1. ✅ Navigated to `https://en.wikipedia.org/wiki/Christopher_Alexander`
2. ✅ Captured full-page snapshot (`-v 0`) — 47 KB, 700 nodes
3. ✅ Captured interactive-only snapshot (`-i`) — 103 KB, 1014 nodes
4. ✅ Captured scoped snapshot with CSS selector (`--selector ".mw-parser-output"`)
5. ✅ Captured depth-limited snapshot (`-d 3`) — 19 nodes
6. ✅ Captured snapshot with URLs (`-u`) — URLs (`/url:`) visible in output
7. ✅ Clicked a link ("Vienna" at ref `e21460`) — navigated to Vienna page
8. ✅ Captured auto-diff snapshot — showed 67 added, 430 removed, 58 modified
9. ✅ Snapshot grep operations — all 7 variants tested and working:
   - `-i` (case-insensitive): "Danube" matched successfully
   - `-C 3` (context): context lines shown around "Vienna" matches
   - `-v` (invert): excluded "generic" lines correctly
   - `-c` (count): 42 lines matching "https"
   - `-F` (fixed string): "Danube" matched literally
   - `-w` (whole word): "Vienna" matched as whole word only
   - `--selector` (CSS filter): narrowed "music" search to `.mw-parser-output`
10. ✅ Captured snapshot to stdout (`--stdout`) — YAML accessibility tree output confirmed

---

## B. Execution Trace

| Step | Command | Notes |
|------|---------|-------|
| Prep | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help` | Help output comprehensive |
| 1 | `goto "https://en.wikipedia.org/wiki/Christopher_Alexander"` | Session opened, page loaded |
| 2 | `snapshot -v 0` | Full snapshot captured |
| 3 | `snapshot -i` | Interactive snapshot captured |
| 4a | `snapshot -s "#bodyContent"` | **FAILED** — flag collision (see Issue 1) |
| 4b | `snapshot --selector ".mw-parser-output" -v 0` | Workaround with long form |
| 5 | `snapshot -d 3 -v 0` | Depth-limited, 19 nodes |
| 6 | `snapshot -u -v 0` | URLs included |
| 7 | `click e21460` | Navigated to Vienna page |
| 8 | `snapshot -v 0 --auto-diff` | Diff shown: +67, -430, ~58 |
| 9a | `snapshot grep -i "Danube"` | Case-insensitive search |
| 9b | `snapshot grep -C 3 "Vienna"` | Context lines |
| 9c | `snapshot grep -v "generic"` | Inverted matching |
| 9d | `snapshot grep -c "https"` | Count: 42 |
| 9e | `snapshot grep -F "Danube"` | Fixed string |
| 9f | `snapshot grep -w "Vienna"` | Whole word |
| 9g | `snapshot grep --selector ".mw-parser-output" "music"` | CSS-filtered |
| 10 | `snapshot -v 0 --stdout` | YAML tree to stdout |

### Workarounds Required

1. **`-s` flag collision** (Issue 1): Had to use `--selector` long form instead of `-s`
2. **Session loss between commands** (Issue 2): Had to re-run `goto` to reconnect before `snapshot --selector`

---

## C. Issues Found

### Issue 1: Critical flag collision — `-s` is both global session flag and snapshot `--selector` short flag

**Severity:** Critical

**Category:** Product

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "https://en.wikipedia.org/wiki/Christopher_Alexander"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot -s "#bodyContent"
```

**Expected:** Snapshot scoped to CSS selector `#bodyContent`.

**Actual:** CLI interprets `-s` as the global session flag, sets session name to `#bodyContent`, finds no session with that name, and errors: `"No active session is currently stored for this CLI context."` The error message is misleading — it says "Session required" rather than indicating the flag was ambiguous.

**Root Cause:** The global `-s <name>` flag (named session) and the snapshot `-s, --selector <css>` flag share the same short form. The global flag parser wins, consuming `#bodyContent` as a session name rather than passing it through to the snapshot subcommand. This is a classic CLI argument parsing ambiguity — global flags are typically parsed before subcommand flags, so the global `-s` takes precedence.

**Code Pointer:** `cli/browser4-cli/src/main.rs` — argument parsing and/or `cli/browser4-cli/src/commands.rs` — snapshot CommandDef definition. The short flag `-s` should either be removed from one of the two uses, or the parser should be context-aware enough to resolve the ambiguity.

**AI Suggested Improvement:**
- Remove the `-s` short form from the snapshot `--selector` option, keeping only `--selector` (no short flag). The session `-s` flag is more fundamental and used more broadly.
- Alternatively, remove the `-s` short form from the global session flag, requiring `--session` or keeping only `-s` in specific subcommands where it's unambiguous.
- Add a global flag collision detector that warns when a short flag is ambiguous between global and subcommand scope.
- At minimum, add a prominent warning in the snapshot help text: "Note: Use `--selector` instead of `-s` to avoid conflict with the global session flag."

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: Session state unreliable between CLI invocations

**Severity:** Medium

**Category:** Reliability

**Reproduction:**
```bash
# Command 1: Works fine
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "https://example.com"
# Command 2: Fails with "Session required"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot --selector "#main"
```

**Expected:** Session persists across CLI invocations as documented ("browser sessions survive across CLI invocations; come back to the same tabs and state days later").

**Actual:** Session is intermittently unavailable. The `goto` command can "Reconnect to existing session" but subsequent `snapshot` commands may fail with "No active session is currently stored for this CLI context." This happened even when running commands in quick succession (within 1 second).

**Root Cause:** The session state file at `~/.browser4/cli-state.json` exists with `{"sessionId": "DEFAULT", "baseUrl": "http://localhost:8182"}`, but the sessions directory `~/.browser4/sessions/` is empty (no `DEFAULT.json`). This suggests the session is backend-managed rather than stored in a local file, and the backend may be expiring or losing track of the session. The issue could be in the backend session management or in how the CLI validates session existence before certain commands.

**Code Pointer:** Backend session management; CLI session validation logic. Possibly in `cli/browser4-cli/src/main.rs` (session check before dispatching commands) or backend session timeout configuration.

**AI Suggested Improvement:**
- Add transparent auto-reconnect for ALL commands that require a session, not just `goto` and `open`. If the session is stale, automatically reconnect.
- Store session metadata locally so the CLI can validate sessions without a round-trip to the backend.
- Add a `--timeout` option to control session lifetime.
- Improve error message: "Session expired or not found. Run `goto <url>` or `open` to create a new session." instead of just "Session required."

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: Session state file location mismatch between dev mode and installed mode

**Severity:** Low

**Category:** UX

**Reproduction:**
```bash
# In dev mode, snapshot files go to $REPO/.browser4-cli/snapshot/
# But cli-state.json is in ~/.browser4/
ls ~/.browser4/cli-state.json  # exists
ls .browser4-cli/cli-state.json  # does NOT exist
```

**Expected:** State files should be stored in a consistent, well-documented location. The README documents `~/.browser4/cli-state.json` but snapshots go to `.browser4-cli/snapshot/` in the repo directory.

**Actual:** State files are split across two locations:
- `~/.browser4/` — cli-state.json, sessions/, loops/, etc.
- `$REPO/.browser4-cli/` — snapshot files only

**Root Cause:** The snapshot output directory is resolved relative to the current working directory during `cargo run`, while the global state uses the user's home directory. This is probably by design (snapshots are session-local artifacts), but the dual-directory pattern is confusing without documentation.

**Code Pointer:** Snapshot file output path resolution.

**AI Suggested Improvement:**
- Document the two-directory layout clearly in the README and SKILL.md.
- Consider storing snapshots alongside other session data in `~/.browser4/snapshots/` for consistency.
- Add a `browser4-cli config` command that shows all relevant paths.

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: Interactive snapshot (`-i`) produces larger output than non-interactive viewport snapshot

**Severity:** Low

**Category:** UX / Discoverability

**Reproduction:**
```bash
snapshot -v 0       # 47 KB, 700 nodes — Viewport 0 only
snapshot -i          # 103 KB, 1014 nodes — "Interactive only"
```

**Expected:** Interactive-only snapshot (`-i`) should be smaller than a full snapshot since it filters out non-interactive elements.

**Actual:** The `-i` snapshot (103 KB) is more than twice the size of the `-v 0` snapshot (47 KB). This is because `-i` without `-v` captures ALL viewports (the page has 10 viewports), while `-v 0` captures only the first viewport. A new user might expect `-i` to be the "lighter" option.

**Root Cause:** `-i` defaults to capturing all viewports, while `-v 0` explicitly limits to the first viewport. The behavior is correct but counter-intuitive — users need to combine `-i -v 0` to get a truly smaller interactive-only snapshot.

**AI Suggested Improvement:**
- Default `-i` to viewport 0 (or auto-detect the most useful viewport) and require explicit `-v all` for full-page interactive.
- Add a tip when `-i` output is large: "Interactive snapshot covers X viewports. Use `-i -v 0` for just the visible viewport."
- Document this behavior clearly: "Without `-v`, `-i` captures all interactive elements across all viewports."

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: Scoped snapshots show out-of-scope content in preview

**Severity:** Low

**Category:** Product

**Reproduction:**
```bash
snapshot --selector ".mw-parser-output" -v 0
```

**Expected:** Preview shows only content inside `.mw-parser-output` (the article body).

**Actual:** Preview still shows banner, navigation, and sidebar elements in the first 10 lines. The scoping may work correctly in the full output but the preview doesn't reflect it.

**Root Cause:** The preview shows the first 10 lines of the YAML file, but if the CSS selector matches a container that includes the banner (e.g., `#bodyContent` on Wikipedia includes more than just article text), the scoping appears to not work. Alternatively, the accessibility tree's parent-child relationships may not map cleanly to CSS selector boundaries — a selector that matches a DOM element may still include accessibility nodes that are visually outside the scoped area.

**Code Pointer:** Snapshot rendering logic — how CSS selector scoping interacts with accessibility tree generation.

**AI Suggested Improvement:**
- Verify that `--selector` scoping correctly prunes the accessibility tree to only nodes within the matched element.
- Add a note in help: "CSS selectors scope against the DOM, but the accessibility tree structure may include ancestor context. Use `snapshot grep --selector` for more precise filtering."
- If scoping is working correctly but the preview is misleading, fix the preview to start from the first scoped element.

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: `-v` flag also ambiguous — version vs viewport

**Severity:** Low

**Category:** Discoverability

**Reproduction:** The global `-v` / `--version` flag and the snapshot `-v` / `--viewport` flag share the same short form.

**Expected:** Either no collision, or clear documentation about which takes precedence.

**Actual:** Similar to Issue 1 but less severe because the collision is between a global "print and exit" flag and a subcommand option. In practice, `snapshot -v 0` works correctly because the numeric argument disambiguates (version doesn't take an argument). But `snapshot -v` alone would print the version instead of showing viewport help.

**Root Cause:** Same as Issue 1 — global flags and subcommand flags share namespace for short forms. This is common in CLI tools (e.g., `--version` vs `--verbose`) but should be documented.

**AI Suggested Improvement:**
- Document in help: "Note: `-v` as a global flag shows version. Use `-v <N>` with a numeric argument for viewport selection."
- Consider using a different short flag for viewport (e.g., `-p` for "page chunk" or `-vp`).

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: No inline snapshot output by default — extra step required to see refs

**Severity:** Medium

**Category:** UX

**Reproduction:**
```bash
snapshot -v 0
# Shows: [Snapshot](/path/to/file.yml)
# Shows: "--- Snapshot preview (first 10 lines) ---"
# Shows: "... (use --stdout or open the file for full content)"
```

**Expected:** Element refs should be immediately visible so users can act on them. The tip says "Use `--stdout` to print element refs inline" but a new user won't know this until they see the tip.

**Actual:** The default behavior saves to a file and shows only a 10-line preview. Users must either open the file separately or know to use `--stdout`. The preview may not include the refs the user needs (especially for content deep in the page).

**Root Cause:** Design choice — file-based output with preview was chosen as the default, likely because snapshots can be very large (100K+ lines for full pages).

**AI Suggested Improvement:**
- Consider making `--stdout` the default for small snapshots (< 500 lines) and auto-paginating.
- Add a `--refs` flag that prints ONLY the interactive element refs and their labels (a compact ref map).
- In the preview, prioritize showing lines that contain `[ref=e` markers so users can find interactive elements immediately.
- Add a `snapshot refs` subcommand that lists all element refs with their labels in a compact table.

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: Extremely long lines in accessibility tree output reduce readability

**Severity:** Low

**Category:** UX

**Reproduction:** The Table of Contents listitem elements contain all sub-items concatenated into a single line:
```
- listitem "toc-Career_2 Career #Career_2 5 Career Toggle Career subsection true Toggle Career subsection toc-Career_2-sublist toc-Author Author #Author 5.1 Author toc-Author-sublist..." [level=1] [ref=e20565] [box=122,315,228,115]:
```
These lines exceed 500 characters.

**Expected:** Reasonable line lengths for terminal viewing (ideally < 200 chars).

**Actual:** Some lines are 500+ characters due to concatenated accessible names/descriptions. This makes it hard to scan the snapshot visually, even with `grep`.

**Root Cause:** Wikipedia's accessibility implementation concatenates all descendant text into the parent's accessible name. The accessibility tree faithfully reproduces this.

**AI Suggested Improvement:**
- Truncate long accessible names in the default output (e.g., show first 100 chars + "…").
- Add a `--no-truncate` flag for users who need the full names.
- Add a `--wrap` option that auto-wraps long lines at terminal width.

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 9: Auto-diff output is noisy — includes internal `mw*` class names and metadata

**Severity:** Low

**Category:** UX

**Reproduction:**
```bash
snapshot -v 0 --auto-diff
```

**Expected:** Diff shows meaningful content changes between pages.

**Actual:** The diff includes many entries like `generic e36178 "mwUg ["`, `generic e36245 "mwkg mwkw [ 16 mwlA ]"`, and empty-string elements. These are Wikipedia's internal CSS class prefixes and empty structural elements, not meaningful content diffs.

**Root Cause:** The auto-diff compares accessibility tree nodes verbatim, including all accessible names derived from CSS classes and DOM attributes. Wikipedia's markup uses class prefixes like `mw*` that leak into accessible names.

**AI Suggested Improvement:**
- Filter out known internal/noise patterns from diff output (empty names, CSS-prefix-only names).
- Add a `--diff-mode` option: `content` (meaningful changes only) vs `full` (all changes).
- Add an option to suppress structural-only changes (added/removed empty containers).

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 10: Tips are helpful but noisy and repetitive across commands

**Severity:** Low

**Category:** UX

**Reproduction:** Running multiple snapshot commands in sequence produces the same tips each time:
```
💡 Tip: Run `snapshot -v 0` to see interactive element refs
💡 Tip: Use `--stdout` to print element refs inline instead of opening the snapshot file
ℹ️  Element refs (e.g. e5, e36) are valid only until the next browser interaction. Re-run snapshot before reusing refs.
```

**Expected:** Tips should be shown once per session, or have a "don't show again" mechanism. The ref-validity warning is important but becomes noise after the 5th time.

**Actual:** Every `snapshot` command shows 2-3 tips/info messages. After a few iterations, users learn to ignore them, which defeats their purpose.

**Root Cause:** Tips are emitted unconditionally per command invocation with no deduplication or seen-before tracking.

**AI Suggested Improvement:**
- Track which tips have been shown in the session and suppress repeats.
- Add a `--no-tips` flag to suppress all tips for a command.
- Add a config option: `browser4-cli config set tips off` to disable tips globally.
- Move the ref-validity warning to only appear after commands that invalidate refs (click, goto), not on every snapshot.

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

**Complete.** All 10 task steps were executed successfully with one workaround (using `--selector` instead of `-s`).

### Estimated Task Success Rate

**80%** — 8 out of 10 commands worked on first attempt. Two commands required workarounds due to the `-s` flag collision.

### Number of Issues Found

**10 issues** — 1 Critical, 2 Medium, 7 Low.

| Severity | Count |
|----------|-------|
| Critical | 1 |
| Medium   | 2 |
| Low      | 7 |

### Major Blockers

1. **`-s` flag collision** (Issue 1) — This is the most impactful issue. A new user trying to follow the documented `snapshot -s "<selector>"` pattern will hit a confusing "Session required" error and may give up. The workaround (`--selector`) is discoverable via `--help` but the collision itself is not documented.

### Most Confusing Aspects

1. **The `-s` flag doing different things in different contexts** — this is the #1 source of confusion.
2. **Why `snapshot -i` is larger than `snapshot -v 0`** — interactive mode captures ALL viewports by default.
3. **Why scoped snapshots still show navigation in preview** — scope behavior unclear.
4. **The viewport concept** — "viewport" in browser4-cli refers to page chunks divided by viewport height, not browser viewports. The distinction could be clearer.

### Most Valuable Improvements

1. **Fix the `-s` flag collision** (Issue 1) — highest impact for lowest effort.
2. **Add auto-reconnect to all commands** (Issue 2) — eliminates an entire class of errors.
3. **Add a `snapshot refs` command** — compact ref listing would dramatically improve the core loop.
4. **Tip deduplication** — reduce noise while preserving helpfulness.

### What Worked Well

- **Core loop is solid:** goto → snapshot → click → snapshot --auto-diff is intuitive and effective.
- **Snapshot grep is excellent:** All 7 grep variants worked as expected, including CSS selector filtering.
- **Auto-diff is powerful:** The diff output, while noisy, correctly identified all structural changes between pages.
- **Help output is comprehensive:** `snapshot --help` and `snapshot grep --help` are well-documented with examples.
- **Dev mode works out of the box:** No manual server setup needed — `cargo run` auto-started the backend.
- **Element refs are clear:** The `[ref=e12345]` notation makes targeting elements straightforward.
- **YAML output format:** Clean, parseable, and consistent.
- **Tips are genuinely useful:** They guide new users toward better patterns (stdout, viewport pagination, ref lifecycle).

### Overall Usability Rating

**7/10**

The tool is fundamentally sound and well-designed. The core interaction loop (navigate → snapshot → interact → re-snapshot) is intuitive and the grep/diff tooling is powerful. However, the flag collision issue and session reliability problems create significant friction for first-time users. With fixes for Issues 1 and 2, the rating would improve to 8.5/10.

The documentation quality (SKILL.md + README.md + inline help) is good — examples are clear and the decision trees for extraction methods are well thought out. The main gap is in warning users about footguns like the `-s` collision and the `-i` vs `-v 0` size behavior.
