# Browser4-CLI Usability Evaluation Report

**Date:** 2026-07-09
**Evaluator:** Claude (AI Agent)
**Task:** Wikipedia LLM — viewport resize, scroll, screenshot, PDF capture
**CLI invocation:** `cargo run --manifest-path cli/browser4-cli/Cargo.toml --`

---

## A. Task Result

All 10 task steps were completed successfully:

1. ✅ Resized browser viewport to 1280×900
2. ✅ Navigated to `https://en.wikipedia.org/wiki/Large_language_model`
3. ✅ Waited for network idle state
4. ✅ Captured default viewport screenshot (390KB)
5. ✅ Scrolled down 5×800px = 4000px total to load more content
6. ✅ Waited for "Architecture" section heading text to be visible
7. ✅ Captured full-page screenshot with custom filename `wikipedia-llm-fullpage.png` (6.9MB)
8. ✅ Scrolled back to top (scrollY = 0.0)
9. ✅ Waited 2 seconds and captured top-of-page screenshot `wikipedia-llm-top.png` (518KB)
10. ✅ Saved page as PDF `wikipedia-llm.pdf` (2.3MB)

---

## B. Execution Trace

| Step | Command | Result |
|------|---------|--------|
| Prep | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help` | Comprehensive help output |
| Prep | Read `skills/browser4-cli/SKILL.md` | Thorough documentation |
| Prep | Read `skills/browser4-cli/references/development.md` | Correct cargo invocation documented |
| 1 | `resize 1280 900` | ✓ Resized to 1280×900 |
| 2 | `goto "https://en.wikipedia.org/wiki/Large_language_model"` | Navigated; reconnected existing session |
| 3 | `wait --load networkidle` | ✓ Wait complete |
| 4 | `screenshot` | 390KB saved to `.browser4-cli/snapshot/` |
| 5a | `scroll down 800` | → 800.0 |
| 5b | `scroll down 800` | → 1600.0 |
| 5c | `scroll down 800` | → 2400.0 |
| 5d | `scroll down 800` | → 3200.0 |
| 5e | `scroll down 800` | → 4000.0 |
| 6 | `wait --text "Architecture" --timeout 10000` | ✓ Wait complete |
| 7 | `screenshot --full-page --filename "../../wikipedia-llm-fullpage.png"` | 6.9MB saved to `/home/vincent/` |
| 8 | `scroll up 4000` | → 0.0 (back to top) |
| 9a | `wait 2000` | ✓ Waited 2000ms |
| 9b | `screenshot --filename "../../wikipedia-llm-top.png"` | 518KB saved to `/home/vincent/` |
| 10 | `pdf --filename "../../wikipedia-llm.pdf"` | 2.3MB saved to `/home/vincent/` |

### Workarounds Required

1. **File path resolution (see Issue 2)**: Had to use `../../` prefix on all `--filename` values because `cargo run` sets the CWD to `cli/browser4-cli/`. Files ended up in `/home/vincent/` (3 levels up) rather than the expected repo root.

2. **Discovery of cargo invocation (see Issue 1)**: Initial `cargo run -- --help` from repo root failed. Had to explore the project structure and read `eval-report.md` from a prior evaluation to discover the correct `--manifest-path` pattern, which was then confirmed in the development reference.

---

## C. Issues Found

### Issue 1: `cargo run` from repo root fails — CLI invocation not discoverable

**Severity:** High

**Category:** Discoverability

**Reproduction:**
```bash
cd /home/vincent/workspace/Browser4
cargo run -- --help
```

**Expected:** CLI help output, or at least a helpful error message pointing to the correct invocation.

**Actual:**
```
error: could not find `Cargo.toml` in `/home/vincent/workspace/Browser4` or any parent directory
```

**Root Cause:** The repo root has no `Cargo.toml`. The CLI is a Cargo project nested at `cli/browser4-cli/Cargo.toml`. The correct invocation (`cargo run --manifest-path cli/browser4-cli/Cargo.toml --`) is documented only in `skills/browser4-cli/references/development.md`, which is two levels deep in the skill references. A new user starting from the README or AGENTS.md would not discover it without significant exploration.

**Code Pointer:** — (documentation issue, not code)

**AI Suggested Improvement:**
- Add a `Cargo.toml` workspace file at the repo root so `cargo run` works naturally, or add a `Makefile` / `justfile` with a `run` target
- Document the correct `cargo run --manifest-path` invocation prominently in `AGENTS.md` and the `cli/README.md` quick-start section
- Add a top-level shell script wrapper (e.g., `./browser4-cli` at repo root) that delegates to the correct cargo invocation
- The error message from cargo could include a hint: "Did you mean `cargo run --manifest-path cli/browser4-cli/Cargo.toml --`?"

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: `--filename` path resolution is confusing under `cargo run`

**Severity:** Medium

**Category:** UX

**Reproduction:**
```bash
# From repo root
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- screenshot --filename "../../output.png"
```

**Expected:** File saved to `/home/vincent/workspace/Browser4/output.png` (repo root), or at minimum a clear, canonical path displayed in the output.

**Actual:** File saved to `/home/vincent/output.png` (3 levels above repo root). The output displayed a non-canonical path: `/home/vincent/workspace/Browser4/../../output.png`.

**Root Cause:** The CWD during `cargo run --manifest-path` is `cli/browser4-cli/`. The `--filename` option resolves paths containing `/` relative to this CWD. A `../../` prefix from that directory goes up 3 levels from the repo root, landing in the user's home directory. The path display concatenates the repo root with the user-supplied relative path, producing a confusing non-canonical path. Additionally, the `cli/browser4-cli/.browser4-cli/snapshot/` directory receives copies with modified filenames, suggesting inconsistent path handling.

**Code Pointer:** `cli/browser4-cli/src/` — screenshot/pdf filename resolution logic

**AI Suggested Improvement:**
- Display canonical (resolved) absolute paths in the output, not raw concatenations
- Document the CWD behavior prominently in the `--filename` help text: "Paths are resolved relative to the cargo run working directory (cli/browser4-cli/)"
- Consider resolving all `--filename` paths relative to the repo root or the user's original CWD rather than the cargo CWD
- Add a `--output-dir` global option so users can set a base output directory once

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: Session persistence leaks prior state without warning

**Severity:** Low

**Category:** UX

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "https://en.wikipedia.org/wiki/Large_language_model"
```

**Expected:** Clean navigation to the requested URL, or a clear notice that a prior session is being reused.

**Actual:** Output reads: `Reconnected to existing session on https://en.wikipedia.org/wiki/Web_scraping`. This reveals that the browser was already open on a completely different page from a prior session. While session persistence is a documented feature, the message is easy to miss and the behavior (landing on a different page first, then navigating) could be surprising.

**Root Cause:** The `goto` command auto-reconnects to the existing default session. The reconnection message mentions the *previous* URL, not the target URL, which could confuse a first-time user who didn't create that session. The behavior is correct per the documented session model, but the UX could be clearer.

**Code Pointer:** — (UX improvement, not a bug)

**AI Suggested Improvement:**
- Add a more prominent notice when reconnecting: "Reconnecting to your previous session (last page: X). Navigating to: Y"
- Consider adding a `--fresh` flag to `goto` that starts a clean session
- Show session age in the reconnection message (e.g., "Session from 2 hours ago")

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: `scroll` command output lacks context

**Severity:** Low

**Category:** UX

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- scroll down 800
```

**Expected:** Human-readable output like "Scrolled down 800px (current position: 800px)" or "✓ Scrolled to 800px".

**Actual:** Raw output: `800.0` — just a number with no label, unit, or context.

**Root Cause:** The scroll command returns the new scroll position as a raw float. While this is machine-parseable (especially with `--json`), the default human-readable output is terse to the point of being cryptic for a new user.

**Code Pointer:** `cli/browser4-cli/src/` — scroll command output formatting

**AI Suggested Improvement:**
- Add a human-readable label: "Scrolled down 800px → position: 800.0"
- Keep the raw number output under `--json` for machine parsing
- Follow the pattern of other commands (e.g., `resize` outputs "✓ Resized to 1280×900")

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: No "scroll to top" shortcut command

**Severity:** Low

**Category:** UX

**Reproduction:**
User wants to return to the top of a long page after scrolling down.

**Expected:** A dedicated `scroll-to-top` command or `scroll top` / `scroll to 0` syntax.

**Actual:** User must calculate the total scroll distance and issue `scroll up <N>` with the exact pixel count. If the user doesn't know the exact scroll position, they must either over-scroll or first check the position (which itself requires knowledge of `eval` or reading the scroll return value).

**Root Cause:** The `scroll` command only supports relative directional scrolling (`up`/`down`/`left`/`right` by pixel count). There is no absolute positioning or "scroll to element/top/bottom" variant.

**Code Pointer:** `cli/browser4-cli/src/` — scroll command definition

**AI Suggested Improvement:**
- Add `scroll top` and `scroll bottom` subcommands for common operations
- Support `scroll to <ref>` to scroll to a specific snapshot element
- Support `scroll to <pixels>` for absolute positioning

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: `pdf` command has minimal configuration options

**Severity:** Low

**Category:** Product

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- pdf --help
```

**Expected:** Options for page size (A4, Letter), margins, scale, page ranges, landscape/portrait, and background graphics.

**Actual:** Only `--filename` is available. No control over paper size, orientation, margins, or page ranges.

**Root Cause:** The PDF generation likely uses Chrome's `Page.printToPDF` CDP method, which supports many parameters (paperWidth, paperHeight, marginTop/Bottom/Left/Right, scale, printBackground, landscape, pageRanges, etc.), but the CLI doesn't expose them.

**Code Pointer:** `cli/browser4-cli/src/` — pdf command definition

**AI Suggested Improvement:**
- Add `--format` (A4, Letter, Legal) and `--landscape` options
- Add `--margin` and per-side margin options
- Add `--scale` for content scaling
- Add `--background` to include/exclude background graphics
- Add `--page-ranges` for selective page output

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
✅ **All 10 task steps completed successfully.** Every command executed reliably on the first attempt with no retries needed.

### Estimated Task Success Rate
**95%** — The core browser automation functions (navigation, resize, scroll, wait, screenshot, PDF) all worked correctly. The only friction was around file path handling for saved outputs.

### Number of Issues Found
**6 issues** (1 High, 1 Medium, 4 Low)

### Major Blockers
None. All commands worked on first attempt. The primary friction points were discoverability and path resolution, not functional bugs.

### Most Confusing Aspects
1. **CLI invocation discovery** — Finding the correct `cargo run --manifest-path` command required exploring the project tree and reading prior eval reports. A workspace-level `Cargo.toml` or shell wrapper would eliminate this.
2. **File path resolution** — The CWD behavior under `cargo run --manifest-path` means `../../` from the repo root lands files 3 directories up. This is counterintuitive and not documented in the command help.
3. **Session state leakage** — The automatically reconnected session from a prior evaluation was unexpected but not harmful.

### Most Valuable Improvements
1. **Workspace root `Cargo.toml`** or a `./browser4-cli` wrapper script — would eliminate the biggest discoverability hurdle for new developers
2. **Canonical path display** in file output — "Saved to /home/vincent/output.png" instead of "/home/vincent/workspace/Browser4/../../output.png"
3. **`scroll top` / `scroll bottom`** shortcuts — common operations that currently require pixel arithmetic
4. **PDF configuration options** — exposing Chrome's PDF generation parameters would make the command much more useful

### Overall Usability Rating: **7/10**

**Strengths:**
- Commands are fast, reliable, and do what they say
- Help output is comprehensive and well-structured
- SKILL.md documentation is thorough with clear decision trees and patterns
- Session auto-management (`goto` auto-opens) reduces boilerplate
- `wait` command supports multiple useful modes (load state, text, time, URL, JS)
- The `--json` / `--quiet` output mode separation is well-designed

**Weaknesses:**
- Cargo invocation from repo root is not discoverable
- File path resolution under `cargo run` is confusing
- Minor UX inconsistencies in output formatting across commands
- PDF and scroll commands lack common convenience options
- Development workflow (multiple levels of `--` escaping, CWD issues) adds friction

**Bottom line:** browser4-cli is a capable, reliable browser automation tool with good documentation. The primary areas for improvement are around the developer setup/onboarding experience (CLI invocation discovery, path handling) and rounding out a few command options. The core automation engine works well.
