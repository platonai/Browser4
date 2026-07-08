# Issues: htmlsnapshot-inspect-discovery

> **Source:** `20260706-165414-htmlsnapshot-inspect-discovery.full.md` | **Date:** 20260706-165414 | **Mode:** dev

## Scenario Background

### Task

✅ **All 9 task steps completed successfully:**

| Step | Command | Result |
|------|---------|--------|
| 1. Navigate | `goto "http://books.toscrape.com/"` | Redirected to HTTPS, page loaded |
| 2. Capture HTML snapshot | `htmlsnapshot` | 65 KB, 20 images, 94 links, 100 interactive elements |
| 3. Inspect (no selector) | `htmlsnapshot inspect` | Auto-discovered `.product_pod` with 20 matches |
| 4. Inspect (selector, max, depth) | `htmlsnapshot inspect ".product_pod" --max 5 --depth 3` | 5 analyzed, structure confirmed |
| 5. Page summary | `htmlsnapshot summary` | WPSI with 23 landmarks, 4 link groups, 3 lists |
| 6. Extract book titles | `htmlsnapshot get all text "h3 a"` | 20 titles extracted |
| 7. Validate selector count | `htmlsnapshot grep -c` | 20 `price_color` matches confirmed |
| 8. X-SQL titles+prices | `htmlsnapshot query --sql @query_books.sql` | 20 correlated title+price rows |
| 9. Sidebar exploration | `htmlsnapshot inspect ".sidebar ul li" --max 5 --depth 2` | 51 category names extracted |

### Execution Context

**Key Commands:**

```
cargo run -- goto "http://books.toscrape.com/"
cargo run -- htmlsnapshot
cargo run -- htmlsnapshot inspect
cargo run -- htmlsnapshot inspect ".product_pod" --max 5 --depth 3
cargo run -- htmlsnapshot summary
cargo run -- htmlsnapshot get all text "h3 a"
cargo run -- htmlsnapshot grep -c "price_color" --selector ".product_pod"   # returned 1
cargo run -- htmlsnapshot grep -c "price_color" --all                        # returned 20
cargo run -- htmlsnapshot get all text "p.price_color"                       # validation
cargo run -- htmlsnapshot query "http://books.toscrape.com/" --sql @query_books.sql   # returned 201/task ID
cargo run -- htmlsnapshot query --sql @query_books.sql                       # returned data
cargo run -- htmlsnapshot inspect ".sidebar" --max 10 --depth 2              # overridden by auto-discovery
cargo run -- htmlsnapshot inspect ".sidebar ul li" --max 5 --depth 2         # worked
cargo run -- htmlsnapshot get all text ".sidebar ul li a"                    # 51 categories
```

**Key decisions:**
- Used `cargo run --` from repo root as `$cliInvocation` (per SKILL.md development instructions)
- Wrote X-SQL to a file (`@query_books.sql`) to avoid Windows shell quoting issues
- Discovered `h3 a` selector manually from DOM structure (not directly suggested by inspect)
- Switched from URL-based to URL-less `htmlsnapshot query` after encountering the async submission response

**Workarounds required:**
- Used `--all` flag on grep instead of `--selector` to get correct match count
- Omitted URL from `htmlsnapshot query` to get synchronous results
- Used `.sidebar ul li` instead of `.sidebar` for inspect to avoid auto-discovery override
- Used absolute paths for `cargo run` (relative `cd cli/browser4-cli` failed in bash)

---

---

---

## Issues Found (8 issues)
> **Review complete:** 3 approved, 5 deferred/rejected

### Issue 2: `htmlsnapshot query` with URL submits asynchronously without clear indication

**Severity:** High
**Category:** UX / Documentation

#### Reproduction

```
cargo run -- htmlsnapshot query "http://books.toscrape.com/" --sql @query.sql
```
Returns `{"statusCode":201,"status":"Created"}` with a task ID instead of the query result data.

#### Expected Behavior

Either (a) the command should block and return results directly, or (b) the output should clearly indicate the query was submitted asynchronously and provide instructions for retrieving results (e.g., "Query submitted. Use `swarm result <id>` to retrieve results.").

#### Actual Behavior

The output looks like a successful HTTP response (201 Created) with `"isDone":true` and `"status":"OK"`, but contains no result data. The user sees what appears to be a success but gets no data. Running the same query without a URL returns data synchronously.

#### Root Cause Analysis

When a URL is provided, `htmlsnapshot query` submits the job through the swarm/scrape API which is asynchronous by design. Without a URL, it queries the already-cached page synchronously. The CLI does not communicate this behavioral difference to the user. Additionally, the response includes `"isDone":true` which is misleading — the HTTP submission is done, but the query results are not included.

#### Code Pointer

``cli/browser4-cli/src/` — the `htmlsnapshot query` command handler needs to detect the async submission case and either poll for results or inform the user how to retrieve them.`

#### AI Suggested Improvement

- When a URL is provided: block and poll for results automatically (synchronous UX), or print a clear message like "Query submitted as task `<id>`. Run `browser4-cli swarm result <id>` to retrieve results."
- Remove or clarify the misleading `"isDone":true` in the response — it refers to submission, not query completion
- Document the URL vs. no-URL behavior difference in the help text and reference docs
- Consider adding a `--wait` flag to make async submission block until results are ready

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

---

### Issue 3: `htmlsnapshot inspect` auto-discovery ignores explicit single-element container selectors

**Severity:** Medium
**Category:** Product / UX

#### Reproduction

```
cargo run -- htmlsnapshot inspect ".sidebar" --max 10 --depth 2
```
The output title is `### Inspect: ".product_pod"` with `🔍 Auto-discovered repeating pattern from ".sidebar"`. The user's explicit `.sidebar` selector is effectively ignored.

#### Expected Behavior

When a user explicitly provides a CSS selector, inspect should analyze the element(s) matching that selector, not override the user's intent with auto-discovery. Auto-discovery should only activate when no selector is given (or `:root` is the default).

#### Actual Behavior

The auto-discovery algorithm treats any single-element match (including explicitly provided selectors like `.sidebar`) as a trigger to find a "better" repeating pattern elsewhere on the page.

#### Root Cause Analysis

The inspect logic (in `DomInspectService` or equivalent) checks if the selector matches exactly one element and, if so, activates auto-discovery to find repeating sibling groups. This doesn't distinguish between the default `:root` (where auto-discovery is helpful) and an explicitly user-provided selector (where the user's intent should be respected).

#### Code Pointer

`Backend service handling `htmlsnapshot inspect` — the auto-discovery gating logic needs to check whether the selector was explicitly provided or is the default.`

#### AI Suggested Improvement

- Only trigger auto-discovery when the selector is the default (`:root`) or was not explicitly provided
- When an explicit selector is given, inspect the matched element(s) regardless of whether they're single or multiple
- If the explicitly-provided selector yields a single element, show its internal structure (child elements, descendant selectors) rather than redirecting to a different pattern
- Add a `--no-auto-discover` flag to suppress auto-discovery when desired

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

---

### Issue 4: `htmlsnapshot grep --selector` uses querySelector (first-match) semantics without documentation

**Severity:** Medium
**Category:** Documentation / UX

#### Reproduction

```
cargo run -- htmlsnapshot grep -c "price_color" --selector ".product_pod"
```
Returns `1` (only the first `.product_pod` match is searched), when the user expects it to search across all 20 `.product_pod` elements.

#### Expected Behavior

The documentation should explicitly state that `--selector` scopes to the **first matching element only** (querySelector semantics), not all matching elements (querySelectorAll semantics). Alternatively, the flag should use querySelectorAll and search across all matches.

#### Actual Behavior

The docs say: "Scope search to a specific CSS element (fetches inner HTML via `html_snapshot_scrape`)" — no mention of single-match-only behavior. Users familiar with CSS selectors naturally think in terms of all matching elements.

#### Root Cause Analysis

The `--selector` flag is implemented using `querySelector` (single element) on the backend's `html_snapshot_scrape` endpoint. The documentation doesn't explain this limitation.

#### Code Pointer

``skills/browser4-cli/references/htmlsnapshot.md:192` — the `--selector` flag description table. Also the CLI-side implementation that maps `--selector` to the scrape API call.`

#### AI Suggested Improvement

- Update documentation: "Scope search to the **first** matching CSS element" with a note that querySelector (single-match) semantics are used
- Consider adding a `--selector-all` flag that uses querySelectorAll semantics and concatenates inner HTML from all matches
- Provide an example showing the difference and directing users to use `--all` for full-page searches when they need all matches

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

---

### Issue 1: Template variables in task specification are undefined

**Severity:** Medium
**Category:** Documentation

#### Review Result

**Decision:** WONTFIX

**Summary:** - Pre-substitute template variables before presenting the task to the evaluator, or provide a variables legend at the top of the task spec

---

### Issue 5: `inspect` does not directly surface all useful child selectors

**Severity:** Low
**Category:** Discoverability

#### Review Result

**Decision:** WONTFIX

**Summary:** - In the sample structure display, annotate text-bearing elements with their full CSS path (e.g., `h3 > a "A Light in the..."` instead of just `h3 "A Light in the..."`)

---

### Issue 6: `htmlsnapshot summary` content section dominated by repetitive buttons

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Deduplicate identical content nodes in the summary (e.g., collapse 20 identical "Add to basket" buttons into one entry with `×20`)

---

### Issue 7: Leftover swarm tasks accumulate across sessions without cleanup

**Severity:** Low
**Category:** Reliability

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add a `swarm clean` or `swarm purge` command to remove completed/failed/stale tasks

---

### Issue 8: Two distinct snapshot systems cause confusion for new users

**Severity:** Medium
**Category:** UX / Discoverability

#### Review Result

**Decision:** WONTFIX

**Summary:** - After `goto`, add a tip: "💡 Run `htmlsnapshot` to capture a static DOM snapshot for data extraction."

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Template variables in task specification are undefined

Read the task specification. It references `$cliInvocation`, `$helpCmd`, `$skillPath`, and `$RepoRootPath` as if they are defined variables, but they are literal strings with no substitution.

#### Issue 2: `htmlsnapshot query` with URL submits asynchronously without clear indication

```
cargo run -- htmlsnapshot query "http://books.toscrape.com/" --sql @query.sql
```
Returns `{"statusCode":201,"status":"Created"}` with a task ID instead of the query result data.

#### Issue 3: `htmlsnapshot inspect` auto-discovery ignores explicit single-element container selectors

```
cargo run -- htmlsnapshot inspect ".sidebar" --max 10 --depth 2
```
The output title is `### Inspect: ".product_pod"` with `🔍 Auto-discovered repeating pattern from ".sidebar"`. The user's explicit `.sidebar` selector is effectively ignored.

#### Issue 4: `htmlsnapshot grep --selector` uses querySelector (first-match) semantics without documentation

```
cargo run -- htmlsnapshot grep -c "price_color" --selector ".product_pod"
```
Returns `1` (only the first `.product_pod` match is searched), when the user expects it to search across all 20 `.product_pod` elements.

#### Issue 5: `inspect` does not directly surface all useful child selectors

Run `htmlsnapshot inspect` on the books.toscrape.com page. The suggested selectors include `h3:expr(a>0)` but do not explicitly list `h3 a` as a selector for extracting book titles.

#### Issue 6: `htmlsnapshot summary` content section dominated by repetitive buttons

Run `htmlsnapshot summary` on the books.toscrape.com page. The "Content" section shows 20 "Add to basket" buttons and 1 H1 heading. Product titles — the actual page content — are absent.

#### Issue 7: Leftover swarm tasks accumulate across sessions without cleanup

Run `swarm list`. Observed 6 pending swarm tasks from previous sessions, some targeting unrelated URLs (`http://localhost:18080/ec/dp/B0E000001`).

#### Issue 8: Two distinct snapshot systems cause confusion for new users

A new user runs `goto` (which auto-captures a snapshot) then tries `htmlsnapshot inspect` — which fails if `htmlsnapshot` wasn't explicitly run first. The "Snapshot" output from `goto` looks similar to what `htmlsnapshot` produces but they are different systems.
