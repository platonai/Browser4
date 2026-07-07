# Issues: attach-remote-debug

> **Source:** `20260706-193037-attach-remote-debug.full.md` | **Date:** 20260706-193037 | **Mode:** dev

## Scenario Background

### Task

**Outcome:** ✅ **Task completed successfully** with workarounds.

- Successfully attached to a running Chrome browser via CDP URL (`http://localhost:9222`)
- Combined `--endpoint` + `--cdp` flags worked as documented
- Listed tabs (1 initially, then 2 after creating a new one)
- Took screenshots of both tabs, confirming visual tab switching
- Captured accessibility tree snapshots
- Saved browser state (cookies + storage) to `attached-state.json`
- Switched between multiple tabs and screenshotted each
- Closed the attached session cleanly (Chrome browser remained running, as expected)

**Workaround required:** Could not use `--cdp chrome` (channel name); had to use `--cdp http://localhost:9222` (URL) instead due to a Rust runtime panic.

---

### Execution Context

| # | Command | Result |
|---|---------|--------|
| 1 | `cargo run -- --help` | Top-level help displayed (well-structured) |
| 2 | `cargo run -- attach --help` | Attach-specific help shown |
| 3 | `cargo run -- attach --cdp chrome` | ❌ Panic: tokio runtime drop error |
| 4 | `cargo run -- attach --cdp http://localhost:9222` | ✅ Attached successfully |
| 5 | `cargo run -- attach --endpoint http://localhost:8182 --cdp http://localhost:9222` | ✅ Combined flags worked |
| 6 | `cargo run -- tab-list` | 1 tab: Amazon search page |
| 7 | `cargo run -- screenshot --filename attached-chrome-screenshot.png` | ✅ Screenshot saved |
| 8 | `cargo run -- snapshot -v 0` | ✅ 690 nodes, 66 KB |
| 9 | `cargo run -- state-save attached-state.json` | ✅ Cookies + storage saved |
| 10 | `cargo run -- tab-new htt...

(truncated — see full.md for complete trace)

---

## Issues Found (7 issues)

### Issue 1: `--cdp <channel-name>` panics with tokio runtime error

**Severity:** Critical
**Category:** Reliability

#### Reproduction

```
cargo run -- attach --cdp chrome
cargo run -- attach --cdp msedge
```

#### Expected Behavior

Channel name should resolve to the browser's CDP endpoint and attach successfully, same as `--cdp http://localhost:9222`.

#### Actual Behavior

Process panics with:
```
thread 'main' panicked at tokio-1.52.3\src\runtime\blocking\shutdown.rs:51:21:
Cannot drop a runtime in a context where blocking is not allowed.
This happens when a runtime is dropped from within an asynchronous context.
```

#### Root Cause Analysis

The channel name code path (scanning running processes, probing ports) likely creates and drops a Tokio runtime inside an async context, violating tokio's constraint that runtimes cannot be dropped from async contexts. The URL path (`--cdp http://localhost:9222`) avoids this code path entirely, which is why it works.

#### Code Pointer

``cli/browser4-cli/src/` — the attach command handler that resolves channel names to CDP endpoints. Likely in the channel-discovery logic that spawns/drops a runtime for process scanning or port probing within an already-running async context.`

#### AI Suggested Improvement

- Move the channel-name resolution logic (process scanning, port probing) outside of the async context, or use `tokio::runtime::Handle::current()` to reuse the existing runtime instead of creating a new one
- Add proper error handling around the channel discovery path so a failure returns a user-friendly error message instead of a Rust panic
- Add an integration test for `attach --cdp chrome` and `attach --cdp msedge` on all platforms to catch regressions

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 2: `tab-new` does not auto-switch to the newly created tab

**Severity:** Medium
**Category:** UX

#### Reproduction

```
cargo run -- attach --cdp http://localhost:9222
cargo run -- tab-new https://example.com
cargo run -- screenshot    # Still screenshots the OLD tab
```

#### Expected Behavior

After creating a new tab, subsequent commands should operate on the new tab, or the CLI should clearly indicate that the user must run `tab-select` to switch.

#### Actual Behavior

`tab-new` creates the tab but keeps the active focus on the previous tab. The output shows the new tab's GUID and URL but the "### Page" section still reflects the old tab. This is confusing — the output suggests the new tab was created but the page info contradicts it.

#### Root Cause Analysis

`tab-new` creates a new tab via CDP but does not call the equivalent of `Page.bringToFront()` or activate the new target. The CLI's session state is not updated to reflect the new active tab.

#### Code Pointer

``cli/browser4-cli/src/` — the `tab_new` command handler. Should activate the new tab after creation or at least warn the user.`

#### AI Suggested Improvement

- After creating a new tab, automatically activate it (call `tab-select` internally) so subsequent commands operate on the new tab
- Update the displayed "### Page" section to show the new tab's URL/title, not the old one
- If auto-switching is intentionally avoided, add a tip on stderr: "Tip: use `tab-select <index>` to switch to the new tab"

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 3: `tab-select` output shows stale page info from previous tab

**Severity:** Low
**Category:** UX

#### Reproduction

```
cargo run -- attach --cdp http://localhost:9222
cargo run -- tab-new https://example.com
cargo run -- tab-select 0    # Switch to the new tab
```

#### Expected Behavior

The "### Page" section in the output should show the newly selected tab's URL and title.

#### Actual Behavior

The JSON return value confirms the tab switch (`{"type":"...PulsarWebDriver","description":"Driver#5"}`), but the "### Page" section still shows the *old* tab's URL and title. The correct info only appears after the *next* command (e.g., `screenshot` or `snapshot`). This is misleading — users might think the switch failed.

#### Root Cause Analysis

The "### Page" display section is populated from cached session state that is only refreshed on page-loading commands (`goto`, `snapshot`, `screenshot`), not on `tab-select`. The tab switch succeeds at the CDP level but the CLI's cached metadata is stale.

#### Code Pointer

``cli/browser4-cli/src/` — the output formatting code that renders the "### Page" section. Should refresh page metadata after `tab-select`.`

#### AI Suggested Improvement

- After `tab-select`, query the new active tab's URL and title from the browser and display them in the "### Page" section
- Alternatively, add a `--page-info` flag to `tab-select` that fetches and displays the current page metadata
- At minimum, add a note in the output like "Tab switched to index 0. Run `snapshot` to see the new page structure."

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 4: Supported channel names not listed in `attach --help`

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

```
cargo run -- attach --help
```

#### Expected Behavior

The help output should list the supported channel names (chrome, chrome-beta, chrome-dev, chrome-canary, msedge, msedge-beta, msedge-dev, msedge-canary) so users can discover them without reading the separate reference doc.

#### Actual Behavior

The help output says `--cdp` accepts a "channel name (chrome, msedge, chrome-canary, ...)" but doesn't enumerate the full list. Users have to read the separate `attach.md` reference doc to find all supported channels. The `--help` is the first place a new user would look.

#### Root Cause Analysis

The help text was written with an abbreviated summary instead of the full list.

#### Code Pointer

``cli/browser4-cli/src/` — the clap/structopt argument definition for `--cdp` in the attach subcommand.`

#### AI Suggested Improvement

- Add the full list of supported channel names to the `--cdp` option description in the help output: `--cdp <channel>` where channel is one of: `chrome`, `chrome-beta`, `chrome-dev`, `chrome-canary`, `msedge`, `msedge-beta`, `msedge-dev`, `msedge-canary`
- Or add a sub-section in `--help` output: "Supported channels: chrome, chrome-beta, chrome-dev, chrome-canary, msedge, msedge-beta, msedge-dev, msedge-canary"

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 5: `attach --help` examples don't include `--endpoint` usage

**Severity:** Low
**Category:** Documentation

#### Reproduction

```
cargo run -- attach --help
```

#### Expected Behavior

The Examples section should demonstrate `--endpoint` usage, especially the combined `--endpoint` + `--cdp` pattern documented in the reference guide.

#### Actual Behavior

The Examples section shows only three examples:
```
browser4-cli attach --cdp http://localhost:9222
browser4-cli attach --cdp chrome
browser4-cli attach --cdp msedge
```
No `--endpoint` example is shown, even though the Options section documents it. Users must read the separate `attach.md` reference to discover this capability.

#### Root Cause Analysis

The examples in the clap definition were not updated when `--endpoint` was added.

#### Code Pointer

``cli/browser4-cli/src/` — the clap `#[command(after_help = "...")]` or examples section for the attach subcommand.`

#### AI Suggested Improvement

- Add examples for `--endpoint` usage:
  - `browser4-cli attach --endpoint http://browser4-server:8182` (switch to remote server)
  - `browser4-cli attach --endpoint http://browser4-server:8182 --cdp chrome` (remote + CDP together)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 6: `close` on attached sessions provides no warning that the browser stays open

**Severity:** Low
**Category:** UX

#### Reproduction

```
cargo run -- attach --cdp http://localhost:9222
cargo run -- close
```

#### Expected Behavior

Closing an attached session should inform the user that only the Browser4 session is disconnected — the browser itself remains running. New users might expect `close` to close the browser.

#### Actual Behavior

Output is simply `Session closed.` No distinction between closing a Browser4-launched browser (which would kill the browser) vs. disconnecting from an externally-attached browser.

#### Root Cause Analysis

The `close` command treats all sessions uniformly and doesn't communicate the attached vs. owned distinction.

#### Code Pointer

``cli/browser4-cli/src/` — the `close` command handler. Should differentiate between attached and owned sessions.`

#### AI Suggested Improvement

- For attached sessions, output: `Disconnected from attached browser. The browser remains running.`
- For owned (Browser4-launched) sessions, output: `Session closed. Browser terminated.`
- Add a `--force` flag to `close` for attached sessions that also attempts to close the browser via CDP

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 7: `tab-list` output format is JSON only — no human-readable table

**Severity:** Low
**Category:** UX

#### Reproduction

```
cargo run -- tab-list
```

#### Expected Behavior

A formatted table showing tabs with their index, title, and URL, similar to how `list` shows sessions in a table format.

#### Actual Behavior

Output is a raw JSON array:
```json
[{"index":"0","guid":"...","title":"...","url":"..."}]
```
The `list` command uses a nice columnar table format (`Name | Session ID | Status | Next open`), but `tab-list` does not. This is inconsistent and harder to read.

#### Root Cause Analysis

`tab-list` uses JSON serialization directly without a table formatter, unlike the `list` command.

#### Code Pointer

``cli/browser4-cli/src/` — the `tab_list` command output formatting.`

#### AI Suggested Improvement

- Format `tab-list` output as a table: `Index | Title | URL` (or `GUID | Title | URL`)
- Use `--json` flag to switch to machine-readable JSON output (consistent with other commands)
- Keep the JSON format when `--json` is passed

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

#### Issue 1: `--cdp <channel-name>` panics with tokio runtime error

```
cargo run -- attach --cdp chrome
cargo run -- attach --cdp msedge
```

#### Issue 2: `tab-new` does not auto-switch to the newly created tab

```
cargo run -- attach --cdp http://localhost:9222
cargo run -- tab-new https://example.com
cargo run -- screenshot    # Still screenshots the OLD tab
```

#### Issue 3: `tab-select` output shows stale page info from previous tab

```
cargo run -- attach --cdp http://localhost:9222
cargo run -- tab-new https://example.com
cargo run -- tab-select 0    # Switch to the new tab
```

#### Issue 4: Supported channel names not listed in `attach --help`

```
cargo run -- attach --help
```

#### Issue 5: `attach --help` examples don't include `--endpoint` usage

```
cargo run -- attach --help
```

#### Issue 6: `close` on attached sessions provides no warning that the browser stays open

```
cargo run -- attach --cdp http://localhost:9222
cargo run -- close
```

#### Issue 7: `tab-list` output format is JSON only — no human-readable table

```
cargo run -- tab-list
```

