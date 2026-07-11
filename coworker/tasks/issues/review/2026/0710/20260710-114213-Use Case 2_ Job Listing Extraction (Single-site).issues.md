# Issues: Use Case 2: Job Listing Extraction (Single-site)

> **Source:** `20260710-114213-Use Case 2_ Job Listing Extraction (Single-site).full.md` | **Date:** 20260710-114213 | **Mode:** dev

## Scenario Background

### Task

The file `kotlin-backend-jobs.json` has been created at `D:/workspace/Browser4/Browser4-4.11/kotlin-backend-jobs.json` containing 5 Kotlin backend engineer job listings sourced from Wellfound.com. 

**Important caveat:** Wellfound.com aggressively blocks automated browser access via DataDome CAPTCHA. Direct navigation to `wellfound.com/jobs`, search interaction, and individual job listing pages all failed. Jobs were discovered via a DuckDuckGo site-specific search (`site:wellfound.com/jobs kotlin backend engineer`) and extracted from the search result snippets. Skills data was partially inferred from job titles and snippets since individual job pages could not be loaded.

---

### Execution Context

**Key Commands:**

| # | Command | Purpose |
|---|---------|---------|
| 1 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help` | Learn available commands |
| 2 | `goto "https://wellfound.com/jobs"` | Initial navigation (worked 1st time) |
| 3 | `snapshot -v 0` | Capture accessibility tree (used ~15 times) |
| 4 | `fill e220 "Kotlin backend engineer"` | Fill job title search box |
| 5 | `click e247` | Click Search button (no effect) |
| 6 | `press Enter e220` | Try Enter key (no effect) |
| 7 | `goto "https://wellfound.com/search?q=..."` | Direct search URL (CAPTCHA) |
| 8 | `goto "https://www.google.com"` | Try Google referrer chain |
| 9 | `fill e73 "..."` → `type "..."` → `press Enter` | Google search (Google also blocked) |
| 10 | `kill-all` | Reset backend (used 4 times) |
| 11 | `goto "https://wellfound.com"` | Homepage (worked consistently) |
| 12 | `eval "window.location.href = '...'"` | JS-based navigation (used 4 times) |
| 13 | `goto "https://duckduckgo.com"` | Alternative search engine |
| 14 | `fill e986 "site:wellfound.com/jobs..."` | DuckDuckGo site search |
| 15 | `htmlsnapshot` | Capture static HTML snapshot |
| 16 | `htmlsnapshot get all attr "a[href*='wellfound.com/jobs']" href` | Extract job URLs |
| 17 | `eval --json "JSON.stringify(...)"` | Extract structured job data (used 6 times) |
| 18 | `crawl --seed-file ... --depth 0 --refresh --format json` | Attempt bulk fetch (returned empty CAPTCHA pages) |
| 19 | `screenshot` | Visual state check |
| 20 | `wait --load networkidle` | Wait for page load |

**Workarounds Applied During Task:**

- **DuckDuckGo site search** instead of Wellfound's own search
- **`eval`-based JavaScript extraction** from search snippets instead of `htmlsnapshot query`
- **Manual company/location parsing** from unstructured title text
- **Inferred skills** from job titles since detail pages were inaccessible

---

---

## Issues Found (10 issues)

### Issue 1: DataDome CAPTCHA blocks Wellfound completely — no stealth/bot-detection bypass

**Severity:** Critical
**Category:** Product

#### Reproduction

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "https://wellfound.com/jobs"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot -v 0 --stdout
```
Every Wellfound URL under `/jobs/*`, `/role/*`, and `/search*` returns a DataDome CAPTCHA page. Only the marketing homepage (`wellfound.com`) loads successfully.

#### Expected Behavior

The browser should load Wellfound pages without triggering bot detection, or the CLI should provide stealth/anti-detection configuration options.

#### Actual Behavior

DataDome CAPTCHA blocks all functional pages. Once flagged, even the homepage becomes inaccessible in subsequent sessions.

#### Root Cause Analysis

The Chrome instance launched by browser4-cli uses detectable automation flags (likely `--enable-automation`, detectable WebDriver properties, or a known user-agent) that sophisticated bot detection services like DataDome check. The CLI provides no mechanism to configure stealth settings, custom user agents, proxy rotation, or CAPTCHA handling.

#### Code Pointer

``browser4-core` — the WebDriver/Chrome launch configuration; `cli/browser4-cli/src/main.rs` — `goto` command dispatch.`

#### AI Suggested Improvement

- Add a `--stealth` flag to `goto` that launches Chrome with anti-detection flags (disable `--enable-automation`, override `navigator.webdriver`, use a realistic user-agent)
- Support custom user-agent strings via `--user-agent` option
- Add a `--proxy` option to enable IP rotation for CAPTCHA-heavy sites
- Integrate a CAPTCHA-solving service (e.g., 2captcha) as an optional plugin
- Document which sites are known to block automation and provide workarounds

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 2: `fill` command fails on Google search box with misleading error

**Severity:** Medium
**Category:** Reliability

#### Reproduction

```
goto "https://www.google.com"
snapshot -v 0
fill e73 "search query"   # where e73 is the Google search textbox
```

#### Expected Behavior

The `fill` command should work on standard HTML text inputs, or the error message should clearly explain why it failed and what to do instead.

#### Actual Behavior

Error: `browser_type failed: Element is not focusable` with a help message referencing Kotlin driver code (`driver.fill("input[name='q']", "Hello, World!")`). This is confusing for CLI users who don't know Kotlin.

#### Root Cause Analysis

Google's search page uses a complex focus management system where the search box may not be directly focusable via the mechanism that `fill` uses. The `fill` tool wraps Playwright's `fill` which first clears and then types — it requires element focus, which Google's search box doesn't grant through standard focus APIs.

#### Code Pointer

`Backend `WebDriver.kt` — the `fill`/`browser_type` implementation; CLI error formatting in `cli/browser4-cli/src/main.rs`.`

#### AI Suggested Improvement

- Improve the error message to suggest: "Try `click <ref>` first to focus the element, then use `type <text>`"
- Make `fill` automatically attempt a `click` before filling when the element is not focusable
- Remove Kotlin-specific code examples from CLI error messages — use CLI-specific syntax instead

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 3: `eval` cannot handle async JavaScript (Promise results lost)

**Severity:** Medium
**Category:** Product

#### Reproduction

```
eval --json "fetch('/api/data').then(r => r.json()).then(d => JSON.stringify(d))"
```

#### Expected Behavior

The eval command should support async JavaScript, either by awaiting the result or by providing a mechanism to handle promises.

#### Actual Behavior

Returns `{}` (empty object) because the promise hasn't resolved when the eval result is captured. This silently fails with no error.

#### Root Cause Analysis

The `eval` command executes JavaScript synchronously and captures the immediate return value. Promises created by `fetch()` and similar async APIs resolve after the eval result has already been captured. There's no `await` support or timeout mechanism.

#### Code Pointer

`Backend `WebDriver.kt` — the `evaluate`/`eval` method; CLI in `cli/browser4-cli/src/main.rs`.`

#### AI Suggested Improvement

- Add an `--await` flag to `eval` that wraps the expression in an async IIFE and waits for promise resolution
- Auto-detect when the eval expression returns a Promise and wait for it
- Document the async limitation clearly in the `eval --help` output
- Add a `--timeout` option for async eval operations

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 4: Windows shell quoting makes `eval` and `htmlsnapshot` commands extremely painful

**Severity:** High
**Category:** UX

#### Reproduction

Any `eval --json` command with nested quotes, e.g.:
```
eval --json "JSON.stringify(document.querySelector('a[href*="wellfound"]'))"
```

#### Expected Behavior

Commands with quotes, brackets, and special characters should work reliably on Windows with straightforward syntax.

#### Actual Behavior

Requires extreme escaping (`'\''` for single quotes, careful double-quote nesting). Multiple attempts needed to get the quoting right. The SKILL.md warns about this but the workarounds (`--file`, `--stdin`, `--base64`) require creating temporary files.

#### Root Cause Analysis

Windows shell (both cmd and PowerShell) handles quotes differently from bash. The CLI uses standard argument parsing that doesn't account for Windows-specific escaping challenges.

#### Code Pointer

``cli/browser4-cli/src/args.rs` — argument parsing; `cli/browser4-cli/src/main.rs` — eval dispatch.`

#### AI Suggested Improvement

- Add a `--file` option to `eval` (like `htmlsnapshot` has) for reading JS from a file
- Add `--stdin` and `--base64` options to `eval` to match the patterns already used by `htmlsnapshot` and `crawl`
- Add a `--browser-console` mode that opens an interactive JS REPL against the page
- Default to `--json` for all `eval` output to reduce the need for JSON.stringify wrapping

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 5: Wellfound search form interaction doesn't trigger SPA navigation

**Severity:** Medium
**Category:** Reliability

#### Reproduction

```
goto "https://wellfound.com/jobs"
snapshot -v 0
fill e220 "Kotlin backend engineer"
click e247   # Search button
# Page URL stays at /jobs, no search results appear
```

#### Expected Behavior

Filling the search box and clicking Search should navigate to a search results page or update the current page with results.

#### Actual Behavior

The page did not change. The search button click produced no visible effect. URL remained `/jobs`. Page content unchanged.

#### Root Cause Analysis

Wellfound is a React SPA. The `fill` command sets the input value but may not trigger React's synthetic `onChange` event. The `click` on the search button may also not trigger the expected React handler. SPA frameworks often need specific event sequences that native DOM manipulation doesn't produce.

#### Code Pointer

`Backend `WebDriver.kt` — the `fill` and `click` implementations; they may need to dispatch React-compatible synthetic events.`

#### AI Suggested Improvement

- Add a `--react` or `--spa` flag to `fill`/`click` that dispatches React-compatible events (both `input` + `change` events with proper `nativeEvent` simulation)
- Add an `interact` command that combines fill + synthetic event dispatch for SPA compatibility
- Document known SPA limitations and workarounds (e.g., `eval` to trigger React state changes)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 6: `goto` reconnects to existing session on a different URL without warning

**Severity:** Low
**Category:** UX

#### Reproduction

```
goto "https://wellfound.com/jobs"    # opens session
goto "https://wellfound.com"         # later
# Output: "Reconnected to existing session on https://wellfound.com/jobs"
```

#### Expected Behavior

Clear indication of what's happening — whether a new page is loading in the existing tab, or a new tab is being created. The "Reconnected to existing session on <different URL>" message is confusing.

#### Actual Behavior

The "Reconnected" message references the PREVIOUS URL, not the target URL. It then shows the new URL under "Page URL", but the reconnection message creates confusion about what's actually happening.

#### Root Cause Analysis

The session management layer reports reconnection before navigation completes. The message format `Reconnected to existing session on <previous_url>` should clearly separate session state from navigation target.

#### Code Pointer

``cli/browser4-cli/src/main.rs` — `goto` command handler.`

#### AI Suggested Improvement

- Change message to: "Using existing session. Navigating to: <new_url>"
- Or: "Reconnected to session DEFAULT. Loading: <new_url>"
- Add a `--new-tab` flag to `goto` to open the URL in a new tab instead of reusing

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 7: `crawl` command returns empty content for CAPTCHA-blocked pages with no error

**Severity:** Medium
**Category:** Reliability

#### Reproduction

```
crawl --seed-file urls.txt --depth 0 --refresh --format json
# where urls.txt contains Wellfound job URLs
```

#### Expected Behavior

Either fetch the content successfully, or report a clear error that the pages returned CAPTCHA/non-job content.

#### Actual Behavior

"Crawl completed. 5 pages found." with empty content after each URL. No indication that the fetched pages are CAPTCHA blocks, not real content.

#### Root Cause Analysis

The crawl backend fetches HTML successfully (HTTP 200), but doesn't validate that the content is meaningful job data. The DataDome CAPTCHA page is valid HTML with HTTP 200, so the crawler sees it as a successful fetch.

#### Code Pointer

``browser4-rest` — crawl service implementation; should add content validation or size checks.`

#### AI Suggested Improvement

- Add content validation: if fetched HTML is <5KB or matches known CAPTCHA patterns, report it as a fetch error rather than success
- Include page size and content snippet in crawl output so users can verify content quality
- Add a `--min-page-size` option to filter out CAPTCHA/error pages

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 8: `snapshot` default behavior doesn't show element refs

**Severity:** Low
**Category:** Discoverability

#### Reproduction

```
goto "https://wellfound.com/jobs"
snapshot   # no flags
```

#### Expected Behavior

The default `snapshot` output should show interactive element refs, since that's the primary use case.

#### Actual Behavior

The default snapshot saves to a file and shows a tip: "Run `snapshot -v 0` to see interactive element refs". Users must discover the `-v 0` flag through a tip message or documentation.

#### Root Cause Analysis

`snapshot` without flags captures all viewports and saves to disk, which is useful for full-page capture but less useful for the interactive workflow where refs are needed. The `-v 0` flag is not discoverable without reading the tip or docs.

#### Code Pointer

``cli/browser4-cli/src/snapshot.rs` — default behavior configuration.`

#### AI Suggested Improvement

- Make `snapshot` default to `-v 0` (showing the first viewport with refs), and add a `--full` flag for full captures
- Or add a `snapshot refs` alias that's equivalent to `snapshot -v 0`
- Highlight the tip about `-v 0` more prominently (e.g., in the command output header)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 9: Help output references `browser4-cli` but source-based invocation uses `cargo run --`

**Severity:** Low
**Category:** Discoverability

#### Reproduction

All help output and tips reference `browser4-cli <command>` but when running from source, users must use `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- <command>`.

#### Expected Behavior

The dev workflow should be mentioned in help output or a quick start should be more visible.

#### Actual Behavior

Users must read the development.md reference to discover the correct invocation pattern. The help output shows `browser4-cli` which doesn't work from source.

#### Root Cause Analysis

The CLI binary name is hardcoded in help output and tips, with no awareness of whether it's running from source or as an installed binary.

#### Code Pointer

``cli/browser4-cli/src/help.rs` — help text generation.`

#### AI Suggested Improvement

- Add a "Running from source" section at the top of `--help` output when the binary detects it's running via `cargo run`
- Add a `--dev` global flag that shows dev-specific tips
- Include the `cargo run --manifest-path ...` pattern in the first-run experience

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 10: `htmlsnapshot get all` returns duplicate URLs requiring manual deduplication

**Severity:** Low
**Category:** UX

#### Reproduction

```
htmlsnapshot get all attr "a[href*='wellfound.com/jobs']" href
```

#### Expected Behavior

Each unique matching element should appear once in the result array.

#### Actual Behavior

Every URL appears twice because the search result page has two links for each job (title link + URL breadcrumb link), both matching the same selector.

#### Root Cause Analysis

The `get all` command returns ALL matches without deduplication. This is the correct behavior, but the output format (flat JSON array) makes it hard to distinguish duplicates from distinct but similar results.

#### Code Pointer

`Backend HTML snapshot query engine.`

#### AI Suggested Improvement

- Add a `--dedup` flag to `get all` to deduplicate results by value
- Or add a `--unique` flag
- Document in the `get all --help` that duplicates are expected when selectors match multiple link formats

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

#### Issue 1: DataDome CAPTCHA blocks Wellfound completely — no stealth/bot-detection bypass

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "https://wellfound.com/jobs"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot -v 0 --stdout
```
Every Wellfound URL under `/jobs/*`, `/role/*`, and `/search*` returns a DataDome CAPTCHA page. Only the marketing homepage (`wellfound.com`) loads successfully.

#### Issue 2: `fill` command fails on Google search box with misleading error

```
goto "https://www.google.com"
snapshot -v 0
fill e73 "search query"   # where e73 is the Google search textbox
```

#### Issue 3: `eval` cannot handle async JavaScript (Promise results lost)

```
eval --json "fetch('/api/data').then(r => r.json()).then(d => JSON.stringify(d))"
```

#### Issue 4: Windows shell quoting makes `eval` and `htmlsnapshot` commands extremely painful

Any `eval --json` command with nested quotes, e.g.:
```
eval --json "JSON.stringify(document.querySelector('a[href*="wellfound"]'))"
```

#### Issue 5: Wellfound search form interaction doesn't trigger SPA navigation

```
goto "https://wellfound.com/jobs"
snapshot -v 0
fill e220 "Kotlin backend engineer"
click e247   # Search button
# Page URL stays at /jobs, no search results appear
```

#### Issue 6: `goto` reconnects to existing session on a different URL without warning

```
goto "https://wellfound.com/jobs"    # opens session
goto "https://wellfound.com"         # later
# Output: "Reconnected to existing session on https://wellfound.com/jobs"
```

#### Issue 7: `crawl` command returns empty content for CAPTCHA-blocked pages with no error

```
crawl --seed-file urls.txt --depth 0 --refresh --format json
# where urls.txt contains Wellfound job URLs
```

#### Issue 8: `snapshot` default behavior doesn't show element refs

```
goto "https://wellfound.com/jobs"
snapshot   # no flags
```

#### Issue 9: Help output references `browser4-cli` but source-based invocation uses `cargo run --`

All help output and tips reference `browser4-cli <command>` but when running from source, users must use `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- <command>`.

#### Issue 10: `htmlsnapshot get all` returns duplicate URLs requiring manual deduplication

```
htmlsnapshot get all attr "a[href*='wellfound.com/jobs']" href
```

