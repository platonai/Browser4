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

## Issues Found (7 issues)
> **Review complete:** 2 approved, 5 deferred/rejected

### Issue 1: htmlsnapshot fails with "Nil url is not allowed" after redirect chains

**Severity:** High
**Category:** Reliability

#### Overview

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

- [x] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- [ ] **DUPLICATE**
- **Notes:**

---

### Issue 6: `--help` directory on disk is confusing (EISDIR error when read as file)

**Severity:** Low
**Category:** Discoverability

#### Overview

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

- [x] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- [ ] **DUPLICATE**
- **Notes:**

---

### Issue 2: Static htmlsnapshot cannot extract dynamically rendered prices (Confluence)

**Severity:** High
**Category:** Product

#### Review Result

**Decision:** DEFER

**Summary:** - Add a `--wait-js` flag to `htmlsnapshot` that waits for JavaScript to finish rendering before capturing the static DOM (similar to `wait --load networkidle` but integrated)

---

### Issue 3: Shell CWD resets after every cargo run, requiring absolute paths

**Severity:** Medium
**Category:** UX

#### Review Result

**Decision:** DUPLICATE

**Summary:** - Document in `development.md` that `cargo run` resets CWD and absolute paths are required

---

### Issue 4: CSS module class names from inspect are fragile for repeatable monitoring

**Severity:** Medium
**Category:** Reliability

#### Review Result

**Decision:** DEFER

**Summary:** - Add a stability heuristic: penalize selectors containing long random-looking suffixes (high entropy, mixed case+digits, underscored hashes)

---

### Issue 5: No built-in support for recurring competitive monitoring with diff detection

**Severity:** Medium
**Category:** Discoverability / Product

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add an `htmlsnapshot diff <baseline-dir>` command that compares current snapshot against a previous one and outputs a structured diff (JSON with change types: added/removed/modified)

---

### Issue 7: SKILL.md shows `browser4-cli` commands but source invocation requires `cargo run --manifest-path ...`

**Severity:** Low
**Category:** Documentation

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add a prominent note at the top of SKILL.md: "**Running from source?** Use `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- <command>` instead of `browser4-cli`."

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

#auto-approve
