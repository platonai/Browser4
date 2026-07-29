# Issues: htmlsnapshot-inspect-discovery

> **Source:** `20260728-130926-htmlsnapshot-inspect-discovery.full.md` | **Date:** 20260728-130926 | **Mode:** dev

## Scenario Background

### Task

Successfully extracted **20 books with titles and prices** from `http://books.toscrape.com/`:

| # | Title | Price |
|---|-------|-------|
| 1 | A Light in the Attic | £51.77 |
| 2 | Tipping the Velvet | £53.74 |
| 3 | Soumission | £50.10 |
| 4 | Sharp Objects | £47.82 |
| 5 | Sapiens: A Brief History of Humankind | £54.23 |
| 6 | The Requiem Red | £22.65 |
| 7 | The Dirty Little Secrets of Getting Your Dream Job | £33.34 |
| 8 | The Coming Woman: A Novel Based on the Life of the Infamous Feminist, Victoria Woodhull | £17.93 |
| 9 | The Boys in the Boat: Nine Americans and Their Epic Quest for Gold at the 1936 Berlin Olympics | £22.60 |
| 10 | The Black Maria | £52.15 |
| 11 | Starving Hearts (Triangular Trade Trilogy, #1) | £13.99 |
| 12 | Shakespeare's Sonnets | £20.66 |
| 13 | Set Me Free | £17.46 |
| 14 | Scott Pilgrim's Precious Little Life (Scott Pilgrim #1) | £52.29 |
| 15 | Rip it Up and Start Again | £35.02 |
| 16 | Our Band Could Be Your Life: Scenes from the American Indie Underground, 1981-1991 | £57.25 |
| 17 | Olio | £23.88 |
| 18 | Mesaerion: The Best Science Fiction Stories 1800-1849 | £37.59 |
| 19 | Libertarianism for Beginners | £51.33 |
| 20 | It's Only the Himalayas | £45.17 |

The X-SQL approach additionally provided **full, untruncated titles** via the `title` attribute, while `get all text` returned truncated display text.

---

### Execution Context

**Key Commands:**

1. `./b4w.sh help` — learned available commands
2. `./b4w.sh goto "http://books.toscrape.com/"` — navigated to target site
3. `./b4w.sh htmlsnapshot` / `htmlsnapshot capture` — captured HTML snapshots
4. `./b4w.sh htmlsnapshot inspect` — auto-discovered `.product_pod` repeating pattern (20 matches)
5. `./b4w.sh htmlsnapshot inspect "article.product_pod" --max 5 --depth 3` — inspected with limits
6. `./b4w.sh htmlsnapshot summary` — generated compressed page WPSI summary
7. `./b4w.sh htmlsnapshot get all text "article.product_pod h3 a" --all` — extracted 20 book titles
8. `./b4w.sh htmlsnapshot grep --selector-all "article.product_pod" "h3" --count` — validated selector → 20 matches
9. `./b4w.sh htmlsnapshot query --sql @.test-sessions/books_final.sql` — X-SQL query for titles+prices (after discovering URL arg issue)
10. `./b4w.sh htmlsnapshot inspect ".side_categories" --max 10 --depth 3` — explored sidebar categories
11. `./b4w.sh htmlsnapshot get all text ".side_categories li a" --all` — extracted 51 sidebar category links

**Workarounds Applied During Task:**

- Backend restart (kill-all + goto) to fix H2 session pool exhaustion
- Using `--json` flag to separate machine output from build log noise
- Parsing double-encoded JSON (result string inside JSON envelope)
- Running X-SQL without URL argument to work around scraper "No content" issue

---

```json
{
  "issues": [
    {
      "title": "b4w.ps1 has CRLF line endings breaking Linux execution",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "./b4w.ps1 help on Linux",
      "expected": "PowerShell wrapper executes browser4-cli",
      "actual": "/usr/bin/env: 'pwsh\\r': No such file or directory",
      "rootCause": "File b4w.ps1 checked into git with Windows CRLF line endings. The shebang line ends with \\r, causing env to try executing 'pwsh\\r' instead of 'pwsh'. Git's core.autocrlf setting or a missing .gitattributes entry likely causes this.",
      "codePointer": "./b4w.ps1 line endings; potentially .gitattributes or git config",
      "suggestion": "- Add a .gitattributes entry: `*.ps1 text eol=lf` to force LF line endings for PowerShell scripts\n- Alternatively, convert CRLF→LF at build time or in a pre-commit hook\n- Document the bash wrapper (b4w.sh) as the primary entry point for Linux/macOS users in the help output"
    },
    {
      "title": "X-SQL query with URL argument fails with HTTP 417 and 'Session is already closed' H2 error",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "./b4w.sh htmlsnapshot query \"https://books.toscrape.com/\" --sql @query.sql (after the backend has been running for some time)",
      "expected": "X-SQL query executes and returns results",
      "actual": "statusCode: 417, resultSet: [], error: 'Session is already closed | #2/53' — H2 database session pool has closed connections that aren't being refreshed",
      "rootCause": "In AbstractBrowser4SQLContext.getSession() (AbstractBrowser4SQLContext.kt:158), the H2 session pool returns already-closed sessions. The pool has 53 sessions but sessions can close over time without being properly invalidated/recreated. When a query tries to use a closed session, it fails with 'The object is already closed'.",
      "codePointer": "browser4-agentic/.../AbstractBrowser4SQLContext.kt:getSession() line 158; also H2SessionFactory.kt:getSession()",
      "suggestion": "- Validate H2 session before returning from pool; if closed, create a new one\n- Add session health check with retry in getSession()\n- Consider using H2 connection pooling with test-on-borrow semantics\n- Add a clear error message to the CLI output when a 417 occurs — currently the JSON response has no 'message' field"
    },
    {
      "title": "X-SQL scraper reports 'No content' for valid HTML pages when using URL argument",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "./b4w.sh htmlsnapshot query \"https://books.toscrape.com/\" --sql @simple.sql (with fresh backend)",
      "expected": "X-SQL returns h3 text content from the page",
      "actual": "statusCode: 200, resultSet: [] — page loads (200, 66KB) but log shows 'No content | Protocol Status: OK(200)'. The scraper's HTML parser finds no extractable content despite the page containing valid HTML with h3 elements.",
      "rootCause": "In XSQLHyperlink (XSQLScrapeHyperlink.kt), the HTML parser pipeline processes the page but produces no content nodes. The parser may be rejecting the page's HTML structure or the DOM_LOAD_AND_SELECT function fails to match elements. The same selectors work via htmlsnapshot get all and via the stored-snapshot X-SQL path, so the issue is specific to the live-fetch scraper path.",
      "codePointer": "browser4-agentic/.../XSQLScrapeHyperlink.kt; PrimerHtmlParser.kt; PageParser.kt",
      "suggestion": "- Investigate why the live-fetch scraper pipeline produces 'No content' when the browser-loaded snapshot works fine\n- Add a fallback: if scraper returns 0 results, try against the stored snapshot\n- Surface a clear warning to the CLI when resultSet is empty despite successful page load — currently output is silent (status 'OK' with empty results)"
    },
    {
      "title": "No error message in JSON response when X-SQL query fails with 417",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "Run any X-SQL query that returns statusCode 417",
      "expected": "JSON response includes a 'message' or 'error' field explaining the failure",
      "actual": "JSON response has statusCode: 417 and status: 'Expectation Failed' but no message or error field to explain what went wrong",
      "rootCause": "The backend error response builder doesn't include the exception message in the JSON envelope for 417 status codes. The error details are only available in server logs.",
      "codePointer": "MCPToolController.kt or response builder for html_snapshot_query",
      "suggestion": "- Always include a 'message' field in error JSON responses with human-readable error text\n- For 417 specifically, include what expectation failed (e.g., 'SQL session is closed', 'Page has no extractable content')"
    },
    {
      "title": "stdout/stderr mixing: build output leaks into stdout alongside JSON data",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "./b4w.sh htmlsnapshot get all text \"h3 a\" --all > output.json",
      "expected": "output.json contains only the JSON array of results",
      "actual": "output.json contains cargo build output ('Finished dev profile...', 'Running...') prepended before the JSON array, requiring manual filtering or --json flag",
      "rootCause": "The dev-mode wrapper (b4w.sh) runs `cargo run` which outputs build status to stderr, but the CLI binary also outputs some informational messages to stdout before emitting the actual result. When redirecting stdout to a file, these lines mix with the JSON data.",
      "codePointer": "cli/browser4-cli/src/main.rs or output formatting layer",
      "suggestion": "- When a dev-mode build is needed, emit build progress only to stderr\n- Document that --json is required for machine-readable output and redirect only stderr (2>/dev/null)\n- Add a --no-build-output flag or auto-detect when stdout is not a TTY"
    },
    {
      "title": "htmlsnapshot get all | --json returns double-encoded JSON (string inside envelope)",
      "severity": "Low",
      "category": "UX",
      "reproduction": "./b4w.sh --json htmlsnapshot get all text \"h3 a\" --all",
      "expected": "output.result contains a JSON array",
      "actual": "output.result is a JSON-encoded string that must be parsed a second time (JSON.parse(JSON.parse(response).output.result))",
      "rootCause": "The result data is serialized as a JSON string and then embedded inside the JSON envelope, creating a double-encoding. The caller must parse the outer envelope, then parse the inner result string.",
      "codePointer": "CLI output formatting or backend serialization layer",
      "suggestion": "- Embed the array directly as a JSON value in output.result, not as a string\n- Or document the double-encoding clearly in the --json help text"
    },
    {
      "title": "--selector vs --selector-all discoverability: grep uses querySelector by default",
      "severity": "Medium",
      "category": "Discoverability",
      "reproduction": "./b4w.sh htmlsnapshot grep --selector \"article.product_pod\" \"h3\"",
      "expected": "Grep searches all 20 product_pod elements for h3 matches",
      "actual": "Grep searches only the FIRST product_pod element (querySelector semantics), returning 1 match. User must know to use --selector-all for querySelectorAll semantics.",
      "rootCause": "The --selector flag uses querySelector (first match only) by default. This is documented in --help but the naming doesn't clearly distinguish between 'scope to a single element' vs 'scope to all matching elements'. Users familiar with JS would expect --selector to mean querySelectorAll.",
      "codePointer": "cli/browser4-cli/src/ commands for htmlsnapshot grep",
      "suggestion": "- Consider renaming: --selector → --scope (single) and --selector-all → --selector (all), matching user expectations\n- Add a tip/hint when --selector matches only 1 element but the selector matches many on the page: 'Found 1/20 matches. Use --selector-all to search all 20 elements.'\n- In the inspect output 'Try these next:' section, include --selector-all examples"
    },
    {
      "title": "X-SQL query requires browser session despite docs saying it's independent",
      "severity": "Medium",
      "category": "Documentation",
      "reproduction": "After kill-all, run ./b4w.sh htmlsnapshot query \"https://example.com\" --sql @query.sql",
      "expected": "Query runs against scraped page (per docs: 'independent of the stored snapshot')",
      "actual": "Error: No active browser session. Run 'browser4-cli open <url>' or 'browser4-cli goto <url>' first.",
      "rootCause": "Despite documentation claiming query 're-fetches the page fresh via the scrape API (independent of the stored snapshot)', the command still validates that a browser session exists before proceeding. The session check may be an unnecessary precondition when a URL is provided.",
      "codePointer": "MCPToolController.kt: html_snapshot_query handler — session validation logic",
      "suggestion": "- Remove the browser session requirement when a URL argument is provided (query uses scrape API, not browser)\n- Or update the docs to clarify that a browser session is required even for URL-based queries"
    },
    {
      "title": "Titles truncated in get all text output — attribute extraction needed for full text",
      "severity": "Low",
      "category": "UX",
      "reproduction": "./b4w.sh htmlsnapshot get all text \"article.product_pod h3 a\" --all",
      "expected": "Full book titles",
      "actual": "Truncated titles like 'A Light in the ...', 'Sapiens: A Brief History ...' — CSS text-overflow: ellipsis truncation is captured as visible text",
      "rootCause": "The page uses CSS text-overflow: ellipsis to truncate long titles in the grid layout. The 'text' extraction mode captures the visible/rendered text content, not the semantic text. The full title is available in the <a> tag's 'title' attribute.",
      "codePointer": "This is expected behavior for visible text extraction; could be addressed with documentation",
      "suggestion": "- Add a tip in the get all output: 'Titles appear truncated due to CSS text-overflow. Use get all attr \"h3 a\" title for full titles.'\n- htmlsnapshot inspect already captures this pattern (shows the title attribute value) — highlight it more prominently"
    },
    {
      "title": "No indication that htmlsnapshot query URL mode fails until server logs are checked",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "Run X-SQL query with URL argument that returns empty resultSet with status OK",
      "expected": "A warning or hint when 0 results are returned from a page that loaded successfully",
      "actual": "Silent empty resultSet with status 'OK' — user has no idea why the query returned nothing. Only server logs reveal 'No content'.",
      "rootCause": "The CLI/backend treats an empty resultSet as a valid result (status: 'OK') rather than a potential error condition. When pageStatusCode is 200 and pageContentBytes > 0 but resultSet is empty, something unusual happened that the user should know about.",
      "codePointer": "XSQLScrapeHyperlink.kt or MCPToolController.kt — result formatting for empty resultSets",
      "suggestion": "- When pageStatusCode is 200, contentBytes > 0, but resultSet is empty, emit a warning: 'Page loaded successfully but no elements matched the selector. Try htmlsnapshot inspect to discover valid selectors.'\n- Include a 'matchedCount' field in the response to distinguish '0 matches found' from 'query execution error'"
    }
  ],
  "assessment": {
    "completionStatus": "Successful — all 9 task steps completed. 20 books extracted with titles and prices. Sidebar categories explored. Multiple workarounds applied for reliability issues.",
    "successRate": "90% — 8 of 9 task steps worked smoothly on first attempt. X-SQL required debugging and workaround discovery (URL vs no-URL behavior).",
    "issuesFound": 10,
    "majorBlockers": "X-SQL query with URL argument is broken in two ways: (1) H2 session pool exhaustion causes 417 after backend uptime, (2) scraper parser returns 'No content' for valid pages even with fresh backend. Both required workarounds: backend restart + using stored-snapshot query mode.",
    "mostConfusingAspects": "1. X-SQL query silently returns empty results with status 'OK' — no indication of what went wrong without checking server logs. 2. The difference between --selector (querySelector, first match) and --selector-all (querySelectorAll, all matches) is not obvious from naming alone. 3. The command requires a browser session even when using the scrape API with URL argument. 4. Build output mixes with JSON data when redirecting stdout.",
    "mostValuableImprovements": "1. Fix the H2 session pool so X-SQL queries don't fail after backend uptime. 2. Fix the scraper parser so it can extract content from valid HTML pages. 3. Add clear error messages to JSON responses when queries fail or return empty results. 4. Add hints when --selector matches only 1 element to suggest --selector-all.",
    "usabilityRating": 6
  }
}
```

---

## Issues Found (10 issues)

### Issue 1: X-SQL query with URL argument fails with HTTP 417 and 'Session is already closed' H2 error

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.sh htmlsnapshot query "https://books.toscrape.com/" --sql @query.sql (after the backend has been running for some time)

#### Expected Behavior

X-SQL query executes and returns results

#### Actual Behavior

statusCode: 417, resultSet: [], error: 'Session is already closed | #2/53' — H2 database session pool has closed connections that aren't being refreshed

#### Root Cause Analysis

In AbstractBrowser4SQLContext.getSession() (AbstractBrowser4SQLContext.kt:158), the H2 session pool returns already-closed sessions. The pool has 53 sessions but sessions can close over time without being properly invalidated/recreated. When a query tries to use a closed session, it fails with 'The object is already closed'.

#### Code Pointer

`browser4-agentic/.../AbstractBrowser4SQLContext.kt:getSession() line 158; also H2SessionFactory.kt:getSession()`

#### AI Suggested Improvement

- Validate H2 session before returning from pool; if closed, create a new one
- Add session health check with retry in getSession()
- Consider using H2 connection pooling with test-on-borrow semantics
- Add a clear error message to the CLI output when a 417 occurs — currently the JSON response has no 'message' field

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: X-SQL scraper reports 'No content' for valid HTML pages when using URL argument

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.sh htmlsnapshot query "https://books.toscrape.com/" --sql @simple.sql (with fresh backend)

#### Expected Behavior

X-SQL returns h3 text content from the page

#### Actual Behavior

statusCode: 200, resultSet: [] — page loads (200, 66KB) but log shows 'No content | Protocol Status: OK(200)'. The scraper's HTML parser finds no extractable content despite the page containing valid HTML with h3 elements.

#### Root Cause Analysis

In XSQLHyperlink (XSQLScrapeHyperlink.kt), the HTML parser pipeline processes the page but produces no content nodes. The parser may be rejecting the page's HTML structure or the DOM_LOAD_AND_SELECT function fails to match elements. The same selectors work via htmlsnapshot get all and via the stored-snapshot X-SQL path, so the issue is specific to the live-fetch scraper path.

#### Code Pointer

`browser4-agentic/.../XSQLScrapeHyperlink.kt; PrimerHtmlParser.kt; PageParser.kt`

#### AI Suggested Improvement

- Investigate why the live-fetch scraper pipeline produces 'No content' when the browser-loaded snapshot works fine
- Add a fallback: if scraper returns 0 results, try against the stored snapshot
- Surface a clear warning to the CLI when resultSet is empty despite successful page load — currently output is silent (status 'OK' with empty results)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: b4w.ps1 has CRLF line endings breaking Linux execution

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 help on Linux

#### Expected Behavior

PowerShell wrapper executes browser4-cli

#### Actual Behavior

/usr/bin/env: 'pwsh\r': No such file or directory

#### Root Cause Analysis

File b4w.ps1 checked into git with Windows CRLF line endings. The shebang line ends with \r, causing env to try executing 'pwsh\r' instead of 'pwsh'. Git's core.autocrlf setting or a missing .gitattributes entry likely causes this.

#### Code Pointer

`./b4w.ps1 line endings; potentially .gitattributes or git config`

#### AI Suggested Improvement

- Add a .gitattributes entry: `*.ps1 text eol=lf` to force LF line endings for PowerShell scripts
- Alternatively, convert CRLF→LF at build time or in a pre-commit hook
- Document the bash wrapper (b4w.sh) as the primary entry point for Linux/macOS users in the help output

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: No error message in JSON response when X-SQL query fails with 417

**Severity:** Medium
**Category:** UX

#### Reproduction

Run any X-SQL query that returns statusCode 417

#### Expected Behavior

JSON response includes a 'message' or 'error' field explaining the failure

#### Actual Behavior

JSON response has statusCode: 417 and status: 'Expectation Failed' but no message or error field to explain what went wrong

#### Root Cause Analysis

The backend error response builder doesn't include the exception message in the JSON envelope for 417 status codes. The error details are only available in server logs.

#### Code Pointer

`MCPToolController.kt or response builder for html_snapshot_query`

#### AI Suggested Improvement

- Always include a 'message' field in error JSON responses with human-readable error text
- For 417 specifically, include what expectation failed (e.g., 'SQL session is closed', 'Page has no extractable content')

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: stdout/stderr mixing: build output leaks into stdout alongside JSON data

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.sh htmlsnapshot get all text "h3 a" --all > output.json

#### Expected Behavior

output.json contains only the JSON array of results

#### Actual Behavior

output.json contains cargo build output ('Finished dev profile...', 'Running...') prepended before the JSON array, requiring manual filtering or --json flag

#### Root Cause Analysis

The dev-mode wrapper (b4w.sh) runs `cargo run` which outputs build status to stderr, but the CLI binary also outputs some informational messages to stdout before emitting the actual result. When redirecting stdout to a file, these lines mix with the JSON data.

#### Code Pointer

`cli/browser4-cli/src/main.rs or output formatting layer`

#### AI Suggested Improvement

- When a dev-mode build is needed, emit build progress only to stderr
- Document that --json is required for machine-readable output and redirect only stderr (2>/dev/null)
- Add a --no-build-output flag or auto-detect when stdout is not a TTY

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: --selector vs --selector-all discoverability: grep uses querySelector by default

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

./b4w.sh htmlsnapshot grep --selector "article.product_pod" "h3"

#### Expected Behavior

Grep searches all 20 product_pod elements for h3 matches

#### Actual Behavior

Grep searches only the FIRST product_pod element (querySelector semantics), returning 1 match. User must know to use --selector-all for querySelectorAll semantics.

#### Root Cause Analysis

The --selector flag uses querySelector (first match only) by default. This is documented in --help but the naming doesn't clearly distinguish between 'scope to a single element' vs 'scope to all matching elements'. Users familiar with JS would expect --selector to mean querySelectorAll.

#### Code Pointer

`cli/browser4-cli/src/ commands for htmlsnapshot grep`

#### AI Suggested Improvement

- Consider renaming: --selector → --scope (single) and --selector-all → --selector (all), matching user expectations
- Add a tip/hint when --selector matches only 1 element but the selector matches many on the page: 'Found 1/20 matches. Use --selector-all to search all 20 elements.'
- In the inspect output 'Try these next:' section, include --selector-all examples

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: X-SQL query requires browser session despite docs saying it's independent

**Severity:** Medium
**Category:** Documentation

#### Reproduction

After kill-all, run ./b4w.sh htmlsnapshot query "https://example.com" --sql @query.sql

#### Expected Behavior

Query runs against scraped page (per docs: 'independent of the stored snapshot')

#### Actual Behavior

Error: No active browser session. Run 'browser4-cli open <url>' or 'browser4-cli goto <url>' first.

#### Root Cause Analysis

Despite documentation claiming query 're-fetches the page fresh via the scrape API (independent of the stored snapshot)', the command still validates that a browser session exists before proceeding. The session check may be an unnecessary precondition when a URL is provided.

#### Code Pointer

`MCPToolController.kt: html_snapshot_query handler — session validation logic`

#### AI Suggested Improvement

- Remove the browser session requirement when a URL argument is provided (query uses scrape API, not browser)
- Or update the docs to clarify that a browser session is required even for URL-based queries

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: No indication that htmlsnapshot query URL mode fails until server logs are checked

**Severity:** Medium
**Category:** UX

#### Reproduction

Run X-SQL query with URL argument that returns empty resultSet with status OK

#### Expected Behavior

A warning or hint when 0 results are returned from a page that loaded successfully

#### Actual Behavior

Silent empty resultSet with status 'OK' — user has no idea why the query returned nothing. Only server logs reveal 'No content'.

#### Root Cause Analysis

The CLI/backend treats an empty resultSet as a valid result (status: 'OK') rather than a potential error condition. When pageStatusCode is 200 and pageContentBytes > 0 but resultSet is empty, something unusual happened that the user should know about.

#### Code Pointer

`XSQLScrapeHyperlink.kt or MCPToolController.kt — result formatting for empty resultSets`

#### AI Suggested Improvement

- When pageStatusCode is 200, contentBytes > 0, but resultSet is empty, emit a warning: 'Page loaded successfully but no elements matched the selector. Try htmlsnapshot inspect to discover valid selectors.'
- Include a 'matchedCount' field in the response to distinguish '0 matches found' from 'query execution error'

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 9: htmlsnapshot get all | --json returns double-encoded JSON (string inside envelope)

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.sh --json htmlsnapshot get all text "h3 a" --all

#### Expected Behavior

output.result contains a JSON array

#### Actual Behavior

output.result is a JSON-encoded string that must be parsed a second time (JSON.parse(JSON.parse(response).output.result))

#### Root Cause Analysis

The result data is serialized as a JSON string and then embedded inside the JSON envelope, creating a double-encoding. The caller must parse the outer envelope, then parse the inner result string.

#### Code Pointer

`CLI output formatting or backend serialization layer`

#### AI Suggested Improvement

- Embed the array directly as a JSON value in output.result, not as a string
- Or document the double-encoding clearly in the --json help text

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 10: Titles truncated in get all text output — attribute extraction needed for full text

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.sh htmlsnapshot get all text "article.product_pod h3 a" --all

#### Expected Behavior

Full book titles

#### Actual Behavior

Truncated titles like 'A Light in the ...', 'Sapiens: A Brief History ...' — CSS text-overflow: ellipsis truncation is captured as visible text

#### Root Cause Analysis

The page uses CSS text-overflow: ellipsis to truncate long titles in the grid layout. The 'text' extraction mode captures the visible/rendered text content, not the semantic text. The full title is available in the <a> tag's 'title' attribute.

#### Code Pointer

`This is expected behavior for visible text extraction; could be addressed with documentation`

#### AI Suggested Improvement

- Add a tip in the get all output: 'Titles appear truncated due to CSS text-overflow. Use get all attr "h3 a" title for full titles.'
- htmlsnapshot inspect already captures this pattern (shows the title attribute value) — highlight it more prominently

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 9 task steps completed. 20 books extracted with titles and prices. Sidebar categories explored. Multiple workarounds applied for reliability issues.

**Success Rate:** 90% — 8 of 9 task steps worked smoothly on first attempt. X-SQL required debugging and workaround discovery (URL vs no-URL behavior).

**Issues Found:** 10

**Major Blockers:** X-SQL query with URL argument is broken in two ways: (1) H2 session pool exhaustion causes 417 after backend uptime, (2) scraper parser returns 'No content' for valid pages even with fresh backend. Both required workarounds: backend restart + using stored-snapshot query mode.

**Most Confusing Aspects:** 1. X-SQL query silently returns empty results with status 'OK' — no indication of what went wrong without checking server logs. 2. The difference between --selector (querySelector, first match) and --selector-all (querySelectorAll, all matches) is not obvious from naming alone. 3. The command requires a browser session even when using the scrape API with URL argument. 4. Build output mixes with JSON data when redirecting stdout.

**Most Valuable Improvements:** 1. Fix the H2 session pool so X-SQL queries don't fail after backend uptime. 2. Fix the scraper parser so it can extract content from valid HTML pages. 3. Add clear error messages to JSON responses when queries fail or return empty results. 4. Add hints when --selector matches only 1 element to suggest --selector-all.

**Usability Rating:** 6/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: X-SQL query with URL argument fails with HTTP 417 and 'Session is already closed' H2 error

./b4w.sh htmlsnapshot query "https://books.toscrape.com/" --sql @query.sql (after the backend has been running for some time)

#### Issue 2: X-SQL scraper reports 'No content' for valid HTML pages when using URL argument

./b4w.sh htmlsnapshot query "https://books.toscrape.com/" --sql @simple.sql (with fresh backend)

#### Issue 3: b4w.ps1 has CRLF line endings breaking Linux execution

./b4w.ps1 help on Linux

#### Issue 4: No error message in JSON response when X-SQL query fails with 417

Run any X-SQL query that returns statusCode 417

#### Issue 5: stdout/stderr mixing: build output leaks into stdout alongside JSON data

./b4w.sh htmlsnapshot get all text "h3 a" --all > output.json

#### Issue 6: --selector vs --selector-all discoverability: grep uses querySelector by default

./b4w.sh htmlsnapshot grep --selector "article.product_pod" "h3"

#### Issue 7: X-SQL query requires browser session despite docs saying it's independent

After kill-all, run ./b4w.sh htmlsnapshot query "https://example.com" --sql @query.sql

#### Issue 8: No indication that htmlsnapshot query URL mode fails until server logs are checked

Run X-SQL query with URL argument that returns empty resultSet with status OK

#### Issue 9: htmlsnapshot get all | --json returns double-encoded JSON (string inside envelope)

./b4w.sh --json htmlsnapshot get all text "h3 a" --all

#### Issue 10: Titles truncated in get all text output — attribute extraction needed for full text

./b4w.sh htmlsnapshot get all text "article.product_pod h3 a" --all

