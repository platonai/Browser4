# Issues: Use Case 13: Ongoing Competitive Monitoring

> **Source:** `20260710-141207-Use Case 13_ Ongoing Competitive Monitoring.full.md` | **Date:** 20260710-141207 | **Mode:** dev

## Scenario Background

### Task

✅ **Task completed successfully.** All three pricing pages were captured, and a comprehensive competitive monitoring report was generated.

**Deliverables:**
- `competitive-monitoring-report.md` — Full comparison report with pricing tiers, feature matrices, and monitoring recommendations
- `competitive-snapshots/2026-07-10/notion-pricing.html` — Notion pricing page snapshot
- `competitive-snapshots/2026-07-10/confluence-pricing.html` — Confluence pricing page snapshot
- `competitive-snapshots/2026-07-10/coda-pricing.html` — Coda→Superhuman pricing page snapshot
- `competitive-snapshots/2026-07-10/pricing-data.json` — Structured JSON extract

**Key finding:** coda.io/pricing now redirects to superhuman.com — reflecting the Grammarly→Superhuman rebrand and Coda acquisition.

---

### Execution Context

**Key Commands:**

| # | Command | Purpose |
|---|---------|---------|
| 1 | `goto "https://www.notion.so/pricing"` | Navigate to Notion pricing (redirected to notion.com) |
| 2 | `snapshot -v 0` | Capture accessibility tree viewport |
| 3 | `htmlsnapshot` | Capture static DOM of Notion pricing |
| 4 | `htmlsnapshot get all text ".PricingPlanCard_pricingPlanCard__tn3aU"` | Extract plan card text |
| 5 | `htmlsnapshot export --file ...` | Save Notion HTML snapshot |
| 6 | `goto "https://www.atlassian.com/software/confluence/pricing"` | Navigate to Confluence |
| 7 | `htmlsnapshot` | Capture static DOM of Confluence |
| 8 | `htmlsnapshot get all text "h1, h2, h3"` + `get all text "th"` + `get all text "span._1bsb1osq._1reo15vq"` | Extract plan names and feature descriptions |
| 9 | `htmlsnapshot export --file ...` | Save Confluence HTML snapshot |
| 10 | `goto "https://coda.io/pricing"` | Navigate to Coda (redirected to superhuman.com) |
| 11 | `htmlsnapshot` (×2, after retry) | Capture static DOM after fixing session state |
| 12 | `htmlsnapshot export --file ...` | Save Superhuman HTML snapshot |

**Workarounds Applied During Task:**

1. **coda.io redirect + session state corruption:** After the redirect chain, `htmlsnapshot` failed with "Nil url is not allowed". Fixed by re-running `goto` to refresh the session state.
2. **Confluence JS-rendered prices:** Static `htmlsnapshot` could not extract dollar amounts. Read accessibility tree snapshot YAML file directly to find the pricing table structure.
3. **CSS module class names:** Inspect returned hashed class names like `._1bsb1osq._1reo15vq` — fragile for repeatable monitoring.

---

---

## Issues Found (7 issues)

### Issue 1: htmlsnapshot fails with "Nil url is not allowed" after redirect chains

**Severity:** High
**Category:** Reliability

#### Reproduction

```
cargo run -- ... -- goto "https://coda.io/pricing"
# Page redirects to https://superhuman.com/docs/pricing
cargo run -- ... -- htmlsnapshot
# → ERROR: html_snapshot_capture failed: Nil url is not allowed
```

#### Expected Behavior

`htmlsnapshot` should work after a redirect, using the resolved/final URL.

#### Actual Behavior

The backend reports a nil URL after certain redirect chains, making the static snapshot pipeline unusable until a fresh `goto` is issued.

#### Root Cause Analysis

The CLI's URL tracking for the session likely doesn't update to the final redirected URL in all code paths. When `coda.io/pricing` → `superhuman.com/docs/pricing`, the internal URL reference may be cleared or set to nil before `htmlsnapshot` can use it.

#### Code Pointer

``cli/browser4-cli/src/http.rs` or the backend's session URL tracking in `browser4-core` — the redirect-handling path that updates the session's current URL.`

#### AI Suggested Improvement

- After a redirect, update the session's tracked URL to the final resolved URL before returning control to the CLI
- Add a defensive check in `htmlsnapshot` capture: if the current URL is nil, attempt to retrieve it from the browser's `window.location.href` via CDP before failing
- Surface a clearer error message: "Session URL is not available — the page may have redirected. Try `goto <url>` again to re-establish the session."

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Static htmlsnapshot cannot extract dynamically rendered prices (Confluence)

**Severity:** High
**Category:** Product

#### Reproduction

```
cargo run -- ... -- goto "https://www.atlassian.com/software/confluence/pricing"
cargo run -- ... -- htmlsnapshot
cargo run -- ... -- htmlsnapshot grep -i '\$[0-9]'
# → 0 matches found (prices are JS-rendered, absent from static DOM)
```

#### Expected Behavior

A way to extract pricing data from JS-rendered content without manually using `eval`.

#### Actual Behavior

The static `htmlsnapshot` contains only the server-rendered HTML. Confluence's pricing amounts (e.g., "$6.05 per user/month") are injected by client-side JavaScript and don't appear in the static snapshot. The accessibility tree `snapshot` does contain them, but there's no structured extraction pathway for accessibility tree data comparable to `htmlsnapshot get`.

#### Root Cause Analysis

`htmlsnapshot` captures the raw HTML as delivered by the server (before JS execution). For SPAs and JS-heavy pages, this misses any content rendered client-side. The accessibility tree (`snapshot`) captures the post-render state but lacks the CSS-selector-based extraction API that `htmlsnapshot` provides.

#### Code Pointer

`The feature gap is architectural — there's no "capture post-JS DOM as static snapshot" mode. The accessibility tree already has the data; a bridge from `snapshot` refs to structured extraction would address this.`

#### AI Suggested Improvement

- Add a `--wait-js` flag to `htmlsnapshot` that waits for JavaScript to finish rendering before capturing the static DOM (similar to `wait --load networkidle` but integrated)
- Document in SKILL.md that `snapshot` (accessibility tree) is the fallback for JS-heavy pages, with a clear recipe for reading the YAML output
- Consider an `htmlsnapshot get from-snapshot` subcommand that queries the accessibility tree YAML with CSS-selector-like patterns

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: Shell CWD resets after every cargo run, requiring absolute paths

**Severity:** Medium
**Category:** UX

#### Reproduction

```
cd "D:/workspace/Browser4/Browser4-4.11"
cargo run --manifest-path ... -- goto "https://example.com"
# After command: "Shell cwd was reset to C:\Users\pereg"
pwd  # → /c/Users/pereg
```

#### Expected Behavior

Working directory persists between commands, or the CLI provides a way to specify output directories relative to the project root.

#### Actual Behavior

Every `cargo run` invocation resets the shell CWD to the user's home directory. All file paths must be absolute, making scripting awkward.

#### Root Cause Analysis

The harness/sandbox resets the working directory after each command execution. This is a known sandbox behavior, not a browser4-cli issue per se, but it impacts the developer experience when running from source.

#### Code Pointer

`N/A — this is an environment/harness issue. However, browser4-cli could mitigate it with a `--project-root` global option.`

#### AI Suggested Improvement

- Document in `development.md` that `cargo run` resets CWD and absolute paths are required
- Add a `--output-dir <path>` global option to `htmlsnapshot export` and other file-output commands to reduce reliance on CWD
- Consider a `.browser4rc` or config file for project-level defaults (output directory, session names, server URL)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [x] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: CSS module class names from inspect are fragile for repeatable monitoring

**Severity:** Medium
**Category:** Reliability

#### Reproduction

```
cargo run -- ... -- htmlsnapshot inspect
# Returns selectors like: .PricingPlanCard_pricingPlanCard__tn3aU
# These CSS module hashes change with every site deployment
```

#### Expected Behavior

`inspect` should suggest stable selectors (e.g., `[data-testid]`, `[role]`, semantic elements) or warn when selectors appear to be generated/hashed.

#### Actual Behavior

`inspect` returns CSS module class names with content hashes. These will break on the next site deployment, making them unsuitable for repeatable monitoring workflows.

#### Root Cause Analysis

`inspect` prioritizes specificity over stability. It doesn't distinguish between hand-authored classes (stable) and auto-generated CSS module hashes (volatile). For monitoring workflows that run weekly, selector breakage is nearly guaranteed.

#### Code Pointer

``cli/browser4-cli/src/snapshot.rs` or the backend's inspect logic — wherever CSS selectors are scored and ranked.`

#### AI Suggested Improvement

- Add a stability heuristic: penalize selectors containing long random-looking suffixes (high entropy, mixed case+digits, underscored hashes)
- Add a `--stable` flag to `inspect` that only returns selectors using semantic elements, ARIA roles, data attributes, and IDs
- In the inspect output, annotate selectors with a stability warning: "⚠ This selector contains a CSS module hash and may change with the next site deployment"

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: No built-in support for recurring competitive monitoring with diff detection

**Severity:** Medium
**Category:** Discoverability / Product

#### Reproduction

Attempt to set up automated weekly competitive pricing monitoring using only documented browser4-cli commands. The `loop` command exists but has no built-in snapshot comparison or change detection features.

#### Expected Behavior

A documented workflow or subcommand for "compare current page state against a previous baseline and report differences."

#### Actual Behavior

The user must manually implement diff logic: store snapshots, run comparisons, classify changes. The `loop` command handles scheduling but the comparison logic is entirely manual.

#### Root Cause Analysis

browser4-cli is designed as a browser automation tool, not a monitoring/change-detection platform. However, the `loop` command and the monitoring use case are documented, creating an expectation of an end-to-end workflow.

#### Code Pointer

`N/A — this is a feature gap, not a bug. A potential implementation could be a new `diff` subcommand under `htmlsnapshot`.`

#### AI Suggested Improvement

- Add an `htmlsnapshot diff <baseline-dir>` command that compares current snapshot against a previous one and outputs a structured diff (JSON with change types: added/removed/modified)
- Document a complete monitoring recipe in SKILL.md: `goto → htmlsnapshot → htmlsnapshot diff → alert on changes`
- Integrate with `loop` so that `loop --diff-dir competitive-snapshots/` automatically compares and reports

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: `--help` directory on disk is confusing (EISDIR error when read as file)

**Severity:** Low
**Category:** Discoverability

#### Reproduction

```
Read("D:/workspace/Browser4/Browser4-4.11/cli/browser4-cli/--help")
# → EISDIR: illegal operation on a directory
```

#### Expected Behavior

Either the `--help` flag output is only available at runtime, or a readable help text file exists.

#### Actual Behavior

A directory named `--help` exists at `cli/browser4-cli/--help/` containing only a `state` subdirectory. This is misleading — it looks like a flag but is actually runtime state storage.

#### Root Cause Analysis

The `--help` directory is created by the CLI at runtime (likely for storing state related to the `--help` flag or command). Having it checked into the source tree or left from a previous run is confusing.

#### Code Pointer

``cli/browser4-cli/src/state.rs` or wherever the `--help` directory is created at runtime.`

#### AI Suggested Improvement

- Use a different directory name for runtime state (e.g., `.browser4-cli-state` or `runtime/`) instead of `--help`
- Add `--help/` to `.gitignore` to prevent it from being checked in
- If `--help` state is intentional, document its purpose in the README

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: SKILL.md shows `browser4-cli` commands but source invocation requires `cargo run --manifest-path ...`

**Severity:** Low
**Category:** Documentation

#### Reproduction

Read SKILL.md — all examples use `browser4-cli <command>`. Attempt to use that — fails because the CLI isn't globally installed. The development.md reference correctly documents `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- <command>`.

#### Expected Behavior

SKILL.md examples should work as-is when running from source, or the development guide should be prominently linked from the quick-start section.

#### Actual Behavior

New users following SKILL.md verbatim will get "command not found" unless they've globally installed the CLI. The `cargo run` pattern is in the development reference but not mentioned in the main skill document.

#### Root Cause Analysis

SKILL.md is written for the installed CLI user, but the evaluation requires running from source. The documentation assumes installation.

#### Code Pointer

``skills/browser4-cli/SKILL.md` — the "Installation" section at the bottom.`

#### AI Suggested Improvement

- Add a prominent note at the top of SKILL.md: "**Running from source?** Use `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- <command>` instead of `browser4-cli`."
- Include `cargo run` examples alongside the `browser4-cli` examples in the copy-paste template
- Make the "Development" reference more discoverable from the main command map

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: htmlsnapshot fails with "Nil url is not allowed" after redirect chains

```
cargo run -- ... -- goto "https://coda.io/pricing"
# Page redirects to https://superhuman.com/docs/pricing
cargo run -- ... -- htmlsnapshot
# → ERROR: html_snapshot_capture failed: Nil url is not allowed
```

#### Issue 2: Static htmlsnapshot cannot extract dynamically rendered prices (Confluence)

```
cargo run -- ... -- goto "https://www.atlassian.com/software/confluence/pricing"
cargo run -- ... -- htmlsnapshot
cargo run -- ... -- htmlsnapshot grep -i '\$[0-9]'
# → 0 matches found (prices are JS-rendered, absent from static DOM)
```

#### Issue 3: Shell CWD resets after every cargo run, requiring absolute paths

```
cd "D:/workspace/Browser4/Browser4-4.11"
cargo run --manifest-path ... -- goto "https://example.com"
# After command: "Shell cwd was reset to C:\Users\pereg"
pwd  # → /c/Users/pereg
```

#### Issue 4: CSS module class names from inspect are fragile for repeatable monitoring

```
cargo run -- ... -- htmlsnapshot inspect
# Returns selectors like: .PricingPlanCard_pricingPlanCard__tn3aU
# These CSS module hashes change with every site deployment
```

#### Issue 5: No built-in support for recurring competitive monitoring with diff detection

Attempt to set up automated weekly competitive pricing monitoring using only documented browser4-cli commands. The `loop` command exists but has no built-in snapshot comparison or change detection features.

#### Issue 6: `--help` directory on disk is confusing (EISDIR error when read as file)

```
Read("D:/workspace/Browser4/Browser4-4.11/cli/browser4-cli/--help")
# → EISDIR: illegal operation on a directory
```

#### Issue 7: SKILL.md shows `browser4-cli` commands but source invocation requires `cargo run --manifest-path ...`

Read SKILL.md — all examples use `browser4-cli <command>`. Attempt to use that — fails because the CLI isn't globally installed. The development.md reference correctly documents `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- <command>`.

