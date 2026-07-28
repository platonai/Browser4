# Issues: navigation-basics

> **Source:** `20260728-003401-navigation-basics.full.md` | **Date:** 20260728-003401 | **Mode:** dev

## Scenario Background

### Task

All 8 task steps completed successfully:
1. ✅ Navigated to `https://en.wikipedia.org/wiki/Web_scraping`
2. ✅ Captured page snapshots and identified accessible elements
3. ✅ Located "Data scraping" link in the "See also" section and clicked it (navigated to `https://en.wikipedia.org/wiki/Data_scraping`)
4. ✅ Used `go-back` to return to Web scraping, then `go-forward` to Data scraping
5. ✅ Reloaded the Data scraping page
6. ✅ Checked server status — server is UP (but with version mismatch: CLI 4.12.1 vs backend v4.11.15)
7. ✅ Listed active sessions — 1 DEFAULT session active
8. ✅ Closed the session cleanly

### Execution Context

**Key Commands:**

`help` → `goto` → `snapshot -v 0` (FAILED) → `-- snapshot -v 0` (FAILED) → `snapshot -v 0` (via b4w.sh) → `snapshot grep` ×3 → `scroll` → `snapshot -v 0` → `snapshot -v 7` → `snapshot grep` → `click` → `go-back` → `go-forward` → `reload` → `status` → `list` → `close`

**Key workarounds:** Switched from `b4w.ps1` to `b4w.sh` due to PowerShell flag interception; used regex-based `snapshot grep` instead of viewport pagination for deep page content; tried multiple regex patterns before matching the heading.

---

---

## Issues Found (9 issues)

### Issue 1: PowerShell intercepts `-v` flag on snapshot command

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 snapshot -v 0

#### Expected Behavior

Snapshot captures viewport 0 (top-of-page chunk).

#### Actual Behavior

PowerShell consumes `-v` as `-Verbose` common parameter. The CLI receives `snapshot 0` instead of `snapshot -v 0`, resulting in error: "Unknown command: 'snapshot-0'. Did you mean: 'snapshot'?"

#### Root Cause Analysis

b4w.ps1's param() block does not declare `-v`, so PowerShell's common parameter resolution matches `-v` to `-Verbose` before the `ValueFromRemainingArguments` parameter receives it. The `$SafeArgs` quoting in the script body (lines 442-446) quotes arguments after param binding, so it cannot prevent the interception.

#### Code Pointer

`b4w.ps1:param() block (lines 16-19) — need a defined parameter or explicit parameter attribute to prevent PowerShell from consuming `-v`.`

#### AI Suggested Improvement

- Add [switch]$v or [string]$Viewport parameter to b4w.ps1's param() block to capture `-v` and forward it as a raw string to the binary
- Alternatively, detect that $RemainingArgs contains 'snapshot' with '0' but no '-v', and emit a clear warning: "The -v flag was intercepted by PowerShell. Use b4w.sh or quote '-v' to prevent this."
- Update the error message to detect this specific case and suggest the correct invocation

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Real PowerShell bug — `-v` is consumed as `-Verbose` before `$RemainingArgs` binding. Adding `[switch]$v` or `[string]$Viewport` to `param()` would prevent interception. The suggested detect-and-warn fallback is a good defense-in-depth addition, but the primary fix should be adding the parameter so binding succeeds. Consider a general solution for ALL single-letter flags that collide with PowerShell common parameters (e.g., `-d`/`-Debug`, `-e`/`-ErrorAction`).

---

### Issue 2: Documented `--` passthrough does not work for b4w.ps1 flag interception

**Severity:** High
**Category:** Documentation

#### Reproduction

./b4w.ps1 -- snapshot -v 0

#### Expected Behavior

The `--` token should be stripped and remaining args passed to the CLI binary, as documented in SKILL.md and the b4w.ps1 top-level help.

#### Actual Behavior

PowerShell error: "Parameter cannot be processed because the parameter name '' is ambiguous. Possible matches include: -Rebuild -RemainingArgs -Verbose ..."

#### Root Cause Analysis

PowerShell treats `--` as a parameter name attempt (since it starts with `-`) and tries to bind it to the script's param() block before the script body runs. The script's `--` handling at lines 61-67 never executes because PowerShell rejects the invocation at the parameter binding stage. This is a fundamental PowerShell limitation — `--` is NOT a native stop-parsing token for script/function calls.

#### Code Pointer

`SKILL.md line 421 and b4w.ps1 help text line 150 — documentation incorrectly claims `--` works as a passthrough for b4w.ps1.`

#### AI Suggested Improvement

- Update documentation to remove the `./b4w.ps1 -- snapshot -i` recommendation; it doesn't work
- Instead document: use `b4w.sh` on Linux/macOS/Git Bash, or quote individual flags: `./b4w.ps1 snapshot '-v' '0'`
- Add a `b4w.ps1 snapshot` wrapper subcommand that explicitly handles snapshot flags to avoid the interception entirely

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Confirmed — `--` is not a stop-parsing token for PowerShell scripts/functions (only for external executables). The `--%` stop-parsing symbol works in `pwsh` but has severe limitations (no variable expansion after it) and doesn't help `b4w.sh` users. The fix is documentation-only: remove the broken `./b4w.ps1 -- snapshot -i` recommendation. Document `b4w.sh` or `pwsh -c './b4w.ps1 snapshot ''-v'' ''0'''` as the workaround. Strongly related to Issue 1 (same underlying cause) but distinct — Issue 1 fixes a specific flag, Issue 2 fixes the documentation that claims a broken workaround.

---

### Issue 3: Version mismatch: CLI 4.12.1 but backend is v4.11.15

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.sh status

#### Expected Behavior

The locally-built CLI should pair with a locally-built backend of the same version, or the status command should clearly indicate how to run the matching backend.

#### Actual Behavior

Status shows: "CLI version: 4.12.1" but "Installed version: v4.11.15" with warning: "Version mismatch: CLI is 4.12.1 but installed backend is v4.11.15." The suggestion to run `mvn spring-boot:run` requires manual setup.

#### Root Cause Analysis

The dev-mode daemon auto-starts a pre-installed backend JAR (from a prior `browser4-cli install`) rather than building and running the backend from the local source tree. The CLI is built from local Rust sources via cargo, but the backend JAR is from the installed bundle.

#### Code Pointer

`b4w.ps1: daemon/backend startup logic — should prefer locally-built JAR over installed bundle.`

#### AI Suggested Improvement

- Auto-detect a locally-built backend JAR (e.g., browser4-rest/target/*.jar) and prefer it over the installed bundle
- If no local JAR exists, offer to build it with `mvn package -pl browser4-rest -am -DskipTests`
- Make the version mismatch warning more prominent and actionable for dev-mode users
- Consider a `--dev-backend` flag that explicitly builds and runs the backend from source

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Legitimate dev-experience friction. The auto-start daemon preferring the installed bundle over a locally-built JAR breaks the expectation that a dev checkout is self-contained. The suggested fix (auto-detect `browser4-rest/target/*.jar`) is correct but should also check the JAR's manifest version to confirm it matches the CLI. Consider making this dev-mode-only behavior — production installs should still use the installed bundle.

---

### Issue 4: `snapshot -v 0` captures absolute page position, not current scroll viewport

**Severity:** Medium
**Category:** UX

#### Reproduction

1. Scroll down 5000px with `scroll down 5000`
2. Run `snapshot -v 0`
3. Observe the output shows page header elements (y=0-66), not content at scroll position ~5000

#### Expected Behavior

After scrolling, `snapshot -v 0` should capture the currently visible viewport (around y=5000). Or the documentation should clearly state that `-v` captures absolute page chunks regardless of scroll position.

#### Actual Behavior

The snapshot shows banner/navigation elements from the absolute top of the page (y=0), even though the browser viewport is scrolled to y=5000. The user must calculate which viewport chunk corresponds to their desired content (e.g., `-v 6` for y=6198-7231).

#### Root Cause Analysis

The viewport pagination (`-v N`) uses absolute page coordinates rather than the browser's current scroll offset. This is confusing because `-v 0` intuitively means "current viewport" to most users, not "absolute top chunk."

#### Code Pointer

`cli/browser4-cli/src/ — snapshot command viewport logic should consider current scroll position as an offset.`

#### AI Suggested Improvement

- Add a `--current` flag that captures the currently visible viewport (using scroll position as base offset)
- Rename or clarify: `-v` = "viewport chunk N from top of page", add `-c` = "current visible viewport"
- Document the absolute-position behavior prominently in `snapshot --help`

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The absolute-coordinate behavior for `-v` is arguably correct (it's viewport chunk N from page top), but it's unintuitive and undocumented. Rather than changing the semantics of `-v` (which would break scripts), add a separate `-c`/`--current` flag that captures the currently visible viewport using scroll offset. The core fix is documentation: `snapshot --help` must state "`-v N` captures viewport chunk N from the absolute top of the page, regardless of scroll position." This issue is distinct from Issue 5 — Issue 4 is about coordinate semantics, Issue 5 is about tree population.

---

### Issue 5: Viewport pagination fails after scrolling due to accessibility tree limitation

**Severity:** Medium
**Category:** Reliability

#### Reproduction

1. Navigate to a long page
2. `scroll down 5000`
3. `snapshot -v 7`

#### Expected Behavior

Viewport 7 (y=7231-8264) should contain page content including the "See also" section at y=6721.

#### Actual Behavior

Snapshot shows only 15 nodes with warning: "Viewport snapshot for '7' contains only 15 lines (15 nodes). The accessibility tree may not have been re-expanded after scrolling. This is a known server-side limitation."

#### Root Cause Analysis

The Chromium accessibility tree is lazily expanded — only nodes near the visible viewport are populated. After programmatic scrolling, the tree may not have re-expanded to cover the new viewport area. The backend doesn't force a full tree expansion before capturing viewport chunks.

#### Code Pointer

`browser4-core/ — PulsarWebDriver accessibility tree expansion logic.`

#### AI Suggested Improvement

- After scrolling, force a full accessibility tree expansion (e.g., via `DOM.getDocument` with depth=-1 or repeated `Accessibility.getPartialAXTree` calls)
- Detect when the tree is sparse for the requested viewport and automatically re-expand it
- Add a `snapshot --full-tree` flag that forces complete accessibility tree population before capturing
- At minimum, improve the warning to suggest actionable workarounds

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Real CDP limitation — Chromium's accessibility tree is lazily populated around the visible viewport. After programmatic scrolling, nodes outside the new viewport area may not be expanded. The suggested fix (force full tree expansion via `DOM.getDocument` with `depth=-1` or iterative `Accessibility.getPartialAXTree`) is the right approach. This is the server-side counterpart to Issue 4 — fixing both together would make scroll+snapshot workflows reliable. The warning message should include a concrete workaround (e.g., "try `snapshot --full-tree` or scroll to the target area and wait 500ms before snapshotting").

---

### Issue 6: Default help output is overwhelming and poorly organized for first-time users

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

./b4w.ps1 help

#### Expected Behavior

Concise overview of major command categories with examples, and clear pointers to drill down. A new user should be able to find the key commands (goto, snapshot, click) within seconds.

#### Actual Behavior

The default help dumps ALL commands in a flat list spanning ~120+ lines. Category filtering (`--help nav`, `--help extract`) is mentioned only in a single line buried among the command list. The "Common workflows" section at the top is helpful but easily missed in the wall of text. There are no usage examples inline with command descriptions.

#### Root Cause Analysis

The help system is organized as a monolithic dump with category filtering as an afterthought. There is no progressive disclosure — everything is shown at once.

#### Code Pointer

`cli/browser4-cli/src/main.rs or cli/browser4-cli/src/cli.rs — help text generation and command categorization.`

#### AI Suggested Improvement

- Default help should show only the most common commands (goto, snapshot, click, fill, extract, status, close) with one-line examples
- End default help with: "Run `--help all` for the full command list, or `--help <category>` for specific areas"
- Make category filtering more prominent: show available categories at the top of default help
- Add a `quickstart` command that walks through the core loop interactively

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Valid discoverability concern, but the proposed solution should preserve power-user workflows. Default help should show: (1) a 5-8 line quickstart with the core loop (goto → snapshot → click/fill → extract), (2) top-level command categories with counts, (3) "Run `--help <category>` for details" and "Run `--help all` for the full list." Category filtering should NOT be buried — it should be the primary drill-down mechanism. The proposed `quickstart` interactive command is higher-effort; defer that to a follow-up unless it's trivial to implement.

---

### Issue 7: b4w.sh emits noisy 'use pwsh' warning on every invocation

**Severity:** Low
**Category:** UX

#### Reproduction

Run any command via `./b4w.sh`.

#### Expected Behavior

The command runs silently (aside from its normal output).

#### Actual Behavior

Every invocation prints: "It is strongly recommended to launch `pwsh` and run the .ps1 commands directly within the `pwsh` terminal." This adds noise and makes scanning command output harder.

#### Root Cause Analysis

b4w.sh likely includes this warning unconditionally as a preamble, possibly to steer users away from the bash wrapper due to known quoting issues.

#### Code Pointer

`b4w.sh — warning preamble logic.`

#### AI Suggested Improvement

- Show the warning only once per session (e.g., via a marker file or env var)
- Or suppress it entirely when inside the repo (dev-mode context)
- Or make it a stderr message that can be silenced with `-q`

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Straightforward fix. Show once per session via a sentinel file in `$TMPDIR` or an env var (`B4W_SH_WARNED=1`). Alternatively, suppress it when `$BROWSER4_DEV` is set (already in-repo context). The warning has value for new users who don't know about the PowerShell wrapper's quoting advantages, so removing it entirely would be wrong — but every-invocation is excessive.

---

### Issue 8: snapshot grep regex $ anchor does not match line endings as expected

**Severity:** Low
**Category:** Reliability

#### Reproduction

./b4w.sh snapshot grep -A 30 'See also$'

#### Expected Behavior

Matches lines ending with 'See also' — should find the heading line.

#### Actual Behavior

0 matches found, even though 'See also' appears at the end of lines in the snapshot. Using '.See.also' (dots as wildcards) works instead.

#### Root Cause Analysis

The snapshot YAML may have trailing whitespace, invisible characters, or the grep implementation may not support `$` anchor semantics the same way as standard grep. Alternatively, the snapshot content may have additional text on the same line (like the heading level or ref) that prevents `$` from matching 'See also' as a line-ending.

#### Code Pointer

`cli/browser4-cli/src/ — snapshot grep implementation.`

#### AI Suggested Improvement

- Document the regex flavor/dialect supported by `snapshot grep` (is it PCRE? Rust regex?)
- Add a `--fixed-string` flag for literal substring matching without regex interpretation
- Trim trailing whitespace from snapshot lines before writing

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Likely cause is trailing whitespace in snapshot YAML lines — `See also$` won't match `See also  ` (trailing spaces). The fix should trim trailing whitespace from snapshot lines before writing (low-risk, fixes the root cause). The suggested `--fixed-string` flag is good for discoverability but doesn't address the root cause. Note: if using Rust's `regex` crate, `$` does match end-of-string (including before `\n`), so trailing whitespace is the most likely culprit. Related to Issue 9 — both are about snapshot output format friction.

---

### Issue 9: No inline element refs by default — extra step needed to see refs

**Severity:** Low
**Category:** UX

#### Reproduction

Run `snapshot -v 0` without `--stdout`.

#### Expected Behavior

Element refs should be immediately visible so the user can compose the next command (e.g., `click e123`).

#### Actual Behavior

The output shows: "[Snapshot](/path/to/file.yml)" with a 10-line preview, followed by a tip: "Use `--stdout` to print element refs inline." The user must either open the file or re-run with `--stdout` to see refs.

#### Root Cause Analysis

The snapshot file can be very large (42KB+), so the default behavior writes to a file to avoid flooding stdout. However, for small pages or viewport-chunked snapshots, inline output would be more usable.

#### Code Pointer

`cli/browser4-cli/src/snapshot.rs — snapshot output rendering.`

#### AI Suggested Improvement

- Default to `--stdout` when the snapshot is under a certain size threshold (e.g., <100 lines)
- Show element refs in the preview section (currently the preview shows only structure)
- Add a summary line: "Interactive elements: e1343 (button), e1595 (link), e1611 (button), ..." listing available refs

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The size-threshold heuristic (inline if <100 lines) is a good approach but has edge cases — a 95-line snapshot with 80 interactive elements could still be overwhelming. Better: always show a compact "Interactive elements" summary line after the file path, even when writing to file. Something like: `[Snapshot saved] …/snapshot.yml (42KB, 847 nodes, 23 interactive: e1343=button, e1595=link, e1611=button, …)`. This gives the user actionable refs immediately without forcing `--stdout` re-runs. The 10-line structural preview is good but doesn't surface refs, which is the user's primary need.

---

## Overall Assessment

**Completion Status:** Successful — All 8 task steps completed. The core browser automation workflow (navigate → snapshot → interact → navigate history → reload → manage sessions) works correctly.

**Success Rate:** 85% — 16 of 19 command invocations succeeded on first attempt. The 3 failures were all related to PowerShell flag interception when using b4w.ps1 directly. Switching to b4w.sh resolved all invocation issues.

**Issues Found:** 9

**Major Blockers:** PowerShell flag interception makes b4w.ps1 unusable for snapshot commands with -v flag. The documented -- workaround does not work. Users must discover b4w.sh or quote arguments individually. This is the single biggest friction point for a new user on this platform.

**Most Confusing Aspects:** 1. The viewport pagination model (-v N) captures absolute page positions, not scroll-relative viewports — users expect -v 0 to show what they currently see. 2. The accessibility tree limitation after scrolling makes viewport pagination unreliable for long pages. 3. The version mismatch between CLI and backend is confusing in a dev-mode context — is the code being tested actually running?

**Most Valuable Improvements:** 1. Fix b4w.ps1 to handle -v/-i flags natively without PowerShell interception. 2. Default to locally-built backend JAR in dev mode instead of the installed bundle. 3. Add a --current flag to snapshot that captures the currently visible viewport. 4. Redesign default help to be progressive (common commands first, categories for drilling down). 5. Force accessibility tree re-expansion after scrolling.

**Usability Rating:** 6/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: PowerShell intercepts `-v` flag on snapshot command

./b4w.ps1 snapshot -v 0

#### Issue 2: Documented `--` passthrough does not work for b4w.ps1 flag interception

./b4w.ps1 -- snapshot -v 0

#### Issue 3: Version mismatch: CLI 4.12.1 but backend is v4.11.15

./b4w.sh status

#### Issue 4: `snapshot -v 0` captures absolute page position, not current scroll viewport

1. Scroll down 5000px with `scroll down 5000`
2. Run `snapshot -v 0`
3. Observe the output shows page header elements (y=0-66), not content at scroll position ~5000

#### Issue 5: Viewport pagination fails after scrolling due to accessibility tree limitation

1. Navigate to a long page
2. `scroll down 5000`
3. `snapshot -v 7`

#### Issue 6: Default help output is overwhelming and poorly organized for first-time users

./b4w.ps1 help

#### Issue 7: b4w.sh emits noisy 'use pwsh' warning on every invocation

Run any command via `./b4w.sh`.

#### Issue 8: snapshot grep regex $ anchor does not match line endings as expected

./b4w.sh snapshot grep -A 30 'See also$'

#### Issue 9: No inline element refs by default — extra step needed to see refs

Run `snapshot -v 0` without `--stdout`.

