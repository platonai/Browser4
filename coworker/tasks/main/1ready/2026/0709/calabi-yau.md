# Issues: Calabi-Yau

> **Source:** `20260708-162517-Calabi-Yau.full.md` | **Date:** 20260708-162517 | **Mode:** dev

## Scenario Background

### Task

**Best Pick: "Knights of the Calabi-Yau: Metaphysical Sci-Fi Fantasy Adventure" by Brian White**

- **Format:** Paperback ($14.99) — ASIN 194483012X
- **Also available:** Kindle ($4.99 with Kindle Unlimited, or $4.99 to buy — 67% off $14.99 list)
- **Rating:** 5.0 out of 5 stars (15 ratings)
- **Publisher:** Dark Revelations Media, LLC
- **URL:** https://www.amazon.com/dp/B0C6VT75C6

**Why this is the best choice:** This is the **only** result in the entire Calabi-Yau search that is an accessible, entertaining work of fiction — a sci-fi/fantasy adventure novel. Every other meaningful result is a graduate-level mathematics/physics textbook ($46–$138) intended for researchers, or an MP3 music track. At $14.99 in paperback (or $4.99 on Kindle), it's affordable, age-accessible, and genuinely giftable for a 12-year-old boy interested in science or speculative fiction.

### The 10-Item Shortlist (ranked by gift suitability for a 12-year-old boy)

| Rank | Product | Format | Price | Rating | Suitability |
|------|---------|--------|-------|--------|-------------|
| 1 | **Knights of the Calabi-Yau: Metaphysical Sci-Fi Fantasy Adventure** | Paperback | $14.99 | 5.0★ (15) | ✅ Fiction novel, age-appropriate |
| 2 | The Shape of a Life: One Mathematician's Search… | Hardcover | $36.23 | 4.3★ (148) | ⚠️ Biography, dense for 12yo, Editor's Pick |
| 3 | Of Reality - Calabi-Yau | MP3 Music | $120.00 | — | ⚠️ Only if kid likes ambient/electronic |
| 4 | Calabi-Yau (B0CHB79PLJ) | MP3 Music | $13.99 | — | ⚠️ Music, niche appeal |
| 5 | Calabi-Yau (B0C6HVDH7W) | MP3 Music | $79.99 | — | ⚠️ Music, expensive |
| 6 | Calabi-Yau (B0BC4PMZQT) | MP3 Music | $20.00 | — | ⚠️ Music |
| 7 | Calabi-Yau (B000TPUTTS) | MP3 Music | $28.00 | — | ⚠️ Music |
| 8 | Calabi-Yau (B00FRNLDW4) | MP3 Music | — | — | ⚠️ Music |
| 9 | calabi-yau (B0GP985VL5) | MP3 Music | — | — | ⚠️ Music |
| 10 | 6-Dimensional Calabi-Yau (B0FVVXCRF9) | MP3 Music | — | — | ⚠️ Music |

**Note:** Items 17-19 (cherry pitter, soup plates, tealight holder) are sponsored ads unrelated to Calabi-Yau and were excluded. Items 1, 3, 4, and 16 are advanced university-level math/physics textbooks ($46–$138) — completely inappropriate for a 12-year-old and excluded from the gift shortlist.

---

### Execution Context

**Key Commands:**

1. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- help` — learned available commands
2. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- status` — verified server was UP
3. `cargo run ... -- goto "https://www.amazon.com/s?k=Calabi-Yau"` — URL injection search per documented Amazon pattern
4. `cargo run ... -- htmlsnapshot` — captured static HTML snapshot of search results
5. `cargo run ... -- htmlsnapshot summary` — got WPSI for structural overview
6. `cargo run ... -- htmlsnapshot inspect` — attempted auto-discovery of selectors (matched nav instead of products)
7. `cargo run ... -- htmlsnapshot grep -i "Calabi-Yau" --selector ".s-main-slot"` — found product HTML structure
8. `cargo run ... -- htmlsnapshot get all text "h2.a-size-medium span"` — extracted 19 product titles
9. `cargo run ... -- htmlsnapshot get all text "span.a-offscreen"` — extracted prices
10. `cargo run ... -- htmlsnapshot get all attr "a.a-link-normal.s-line-clamp-2" href` — extracted product URLs
11. `cargo run ... -- goto "https://www.amazon.com/dp/0300235909"` — reviewed "The Shape of a Life" detail page
12. `cargo run ... -- htmlsnapshot grep -i "price\|rating\|about this item" --selector "#centerCol"` — extracted detail info
13. `cargo run ... -- goto "https://www.amazon.com/dp/B0C6VT75C6"` — reviewed "Knights of the Calabi-Yau" detail page
14. `cargo run ... -- snapshot -v 0 --stdout` — viewed interactive accessibility snapshots
15. `cargo run ... -- snapshot grep -i "price\|rating\|paperback\|science fiction"` — searched for product details

**Key Decisions:**
- Used URL injection (`amazon.com/s?k=...`) instead of form-filling per documented Amazon quirk
- Switched between `htmlsnapshot` (for structured data extraction) and `snapshot` (for quick live page viewing) as needed
- Had to discover actual CSS selectors (`h2.a-size-medium span`) because documented selectors from Amazon scenarios didn't match this page's DOM

**Workarounds Required:**
- The documented Amazon selectors (`h2 a.a-link-normal`, `span.a-icon-alt`) did not match the live page — had to discover working selectors through `grep` output analysis
- Backend timeouts occurred on the 2MB+ product detail pages when using `htmlsnapshot get`/`summary` — switched to lighter-weight `snapshot` commands
- Session unexpectedly showed `about:blank` after one navigation — required a re-`goto`

---

---

## Issues Found (7 issues)

### Issue 1: Documented Amazon selectors do not match live Amazon search results

**Severity:** High
**Category:** Documentation

#### Reproduction

```
cargo run ... -- goto "https://www.amazon.com/s?k=Calabi-Yau"
cargo run ... -- htmlsnapshot
cargo run ... -- htmlsnapshot get all text "h2 a.a-link-normal"
```
Returns: `[]` — "No elements matched."

#### Expected Behavior

Titles extracted using the documented selectors from `htmlsnapshot-scenarios-amazon.md` §15c (`h2 a.a-link-normal`).

#### Actual Behavior

Empty array. The documented selectors reference `a.a-link-normal` inside `h2`, but on the live page, the title link is a parent of `h2` (not a child), and the text is in `h2 > span`, not `h2 > a`.

#### Root Cause Analysis

Amazon changed their search result DOM structure. The documentation's selectors are stale. In the current DOM, product titles are in `<a class="a-link-normal s-line-clamp-2..."><h2 class="a-size-medium"><span>TITLE</span></h2></a>`. The working selector is `h2.a-size-medium span`, not `h2 a.a-link-normal`.

#### Code Pointer

``skills/browser4-cli/references/htmlsnapshot-scenarios-amazon.md` — §15b–§15d selectors`

#### AI Suggested Improvement

- Update §15 with current working selectors (`h2.a-size-medium span` for titles, `span.a-offscreen` for prices — which still works)
- Add a version/date stamp to the documentation noting when selectors were last verified
- Add a "selector fallback discovery" pattern: if documented selectors return empty, run `htmlsnapshot grep` to inspect the actual DOM structure

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT]

---

### Issue 2: Backend HTTP timeouts on large Amazon product detail pages

**Severity:** High
**Category:** Reliability

#### Reproduction

```
cargo run ... -- goto "https://www.amazon.com/dp/0300235909"
cargo run ... -- htmlsnapshot                     # succeeds (2.1 MB)
cargo run ... -- htmlsnapshot summary             # times out after 30s
cargo run ... -- htmlsnapshot get text "#productTitle"  # times out after 30s
```

#### Expected Behavior

`htmlsnapshot get`, `summary`, and `grep` commands complete within the 30-second timeout on 2MB+ pages.

#### Actual Behavior

Multiple commands timed out with `HTTP request timed out [timeout=30s]` errors. The server was confirmed UP via `status` command, but the backend could not process the snapshot within the timeout window.

#### Root Cause Analysis

The product detail page is 2139 KB of HTML (Amazon is notoriously heavy). The backend's HTML snapshot scrape endpoint (`html_snapshot_scrape`) likely has O(n) or worse complexity on DOM size, causing processing to exceed the 30-second CLI timeout. The initial `htmlsnapshot` capture succeeded because it's a different (lighter) code path, but subsequent queries against the stored snapshot hit the heavy path.

#### Code Pointer

`Backend: `MCPToolController.kt` — `html_snapshot_scrape` tool handler; CLI: timeout configuration in HTTP client`

#### AI Suggested Improvement

- Increase the CLI HTTP timeout for `htmlsnapshot`-family commands to 60–90 seconds (or make it configurable)
- Optimize the backend scrape path for large DOMs (streaming SAX parser instead of full DOM tree, or chunked processing)
- Add a progress indicator or streaming response so the CLI doesn't hit a fixed timeout
- Consider adding a `--timeout` flag to override the default per-command

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT]

---

### Issue 3: `snapshot -v` flag reports wrong `processingViewport` in output

**Severity:** Medium
**Category:** Product

#### Reproduction

```
cargo run ... -- goto "https://www.amazon.com/dp/B0C6VT75C6"
cargo run ... -- snapshot -v 1 --stdout
```
Output shows `# - processingViewport: 0` even though `-v 1` was requested.

#### Expected Behavior

`processingViewport: 1` when `-v 1` is specified.

#### Actual Behavior

Output always shows `processingViewport: 0` regardless of the `-v` flag value. The actual content rendered appears to change but the metadata header is wrong.

#### Root Cause Analysis

The viewport state annotation likely reads from a variable that isn't updated to reflect the user-requested viewport index — it may be hardcoded or reading a default value. This is a cosmetic bug in the `--stdout` header generation but is confusing for users trying to orient themselves in multi-viewport pages.

#### Code Pointer

``cli/browser4-cli/src/snapshot.rs` — viewport state header generation`

#### AI Suggested Improvement

- Ensure `processingViewport` in the `--stdout` YAML header reflects the actual viewport being rendered
- If multiple viewports are requested (e.g., `-v 0-6`), annotate each section with its viewport number

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT]

---

### Issue 4: `htmlsnapshot inspect` auto-discovery fails to find product cards on search results

**Severity:** Medium
**Category:** Reliability

#### Reproduction

```
cargo run ... -- goto "https://www.amazon.com/s?k=Calabi-Yau"
cargo run ... -- htmlsnapshot
cargo run ... -- htmlsnapshot inspect
```
Output shows navigation shortcut elements, not product cards.

#### Expected Behavior

`inspect` should auto-discover the `.s-result-item` product cards as the most prominent repeating pattern (90 items in the main slot).

#### Actual Behavior

Auto-discovery analyzed from `:root` and identified `div#a-page` and its navigation children as the repeating pattern — completely missing the 90 product result cards in `.s-main-slot`.

#### Root Cause Analysis

The auto-discovery algorithm appears to prefer breadth/depth-first traversal of the top of the DOM tree rather than identifying the most structurally repetitive subtrees. The `.s-main-slot > div` pattern (90 items) is the most repetitive structure on the page but is nested deeper than `#a-page > div` hierarchy.

#### Code Pointer

`Backend: `MCPToolController.kt` — `html_snapshot_inspect` handler logic for auto-discovery pattern selection`

#### AI Suggested Improvement

- Prioritize container elements with the highest child count (> N children of similar structure) over top-level structural divs
- Score candidate containers by `childCount × structuralSimilarity` rather than DOM depth
- If the top-level analysis finds only low-specificity patterns (bare `div`, `span`), automatically recurse into high-cardinality children

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] Same root cause as Example 3 from historical decisions: the inspect algorithm picks the first repeating element from :root, which on e-commerce pages is navigation, not products. Fixing requires refactoring the container-priority heuristic — postpone until inspect gets dedicated attention. The suggested scoring change (childCount × structuralSimilarity) is the right direction but needs careful design to avoid regressions on other page types.

---

### Issue 5: Session state silently resets to `about:blank` between navigations

**Severity:** Medium
**Category:** Reliability

#### Reproduction

```
cargo run ... -- goto "https://www.amazon.com/dp/0300235909"
cargo run ... -- htmlsnapshot get text "#productTitle"   # times out
cargo run ... -- snapshot -v 0 --stdout                   # shows about:blank
```

#### Expected Behavior

After a `goto` to a product page, the current page should persist until the next explicit navigation.

#### Actual Behavior

After backend timeouts from `htmlsnapshot get`, the session's current page reverted to `about:blank`. The `goto` output showed "Reconnected to existing session on about:blank", suggesting the session was reset rather than preserved.

#### Root Cause Analysis

Uncertain — possibly the backend timed out during the heavy `htmlsnapshot get` call and reset the session state as a side effect, or the session was garbage-collected. The `goto` reconnect logic may interpret a stale/timed-out session as needing a fresh connection, discarding the previous page.

#### Code Pointer

`Backend session management — `AgenticSession` or `PulsarSession` lifecycle; CLI `main.rs` — `goto` command's session reconnect logic`

#### AI Suggested Improvement

- Session state should be resilient to read-operation timeouts — a timed-out extraction should not invalidate the page
- The reconnect message should distinguish between "session was still valid, reusing" vs "session was lost, starting fresh" so the user knows what happened
- Consider a session heartbeat or keepalive mechanism to prevent premature teardown during long operations

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT]

---

### Issue 6: `htmlsnapshot grep` produces confusing regex alternation conversion note

**Severity:** Low
**Category:** UX

#### Reproduction

```
cargo run ... -- htmlsnapshot grep -i "price\|rating\|about" --selector "#centerCol"
```
Output includes:
```
Note: Converted grep-style alternation `\\|` to `|` in pattern. Rust regex uses bare `|` for alternation (like ERE/egrep). Use `snapshot grep -F` for literal matching.
```

#### Expected Behavior

Either accept standard grep syntax without a conversion note, or document the Rust regex syntax upfront.

#### Actual Behavior

The note appears every time `\|` is used, creating noise in the output. A new user who knows standard `grep` syntax will be confused; a user who reads the tip is told to use `snapshot grep -F` which doesn't apply to `htmlsnapshot grep`.

#### Root Cause Analysis

The regex alternation conversion is applied to both `snapshot grep` and `htmlsnapshot grep`, but the tip message references `snapshot grep -F` even when the user is running `htmlsnapshot grep`. The `-F` flag may not exist or behave differently on `htmlsnapshot grep`.

#### Code Pointer

`CLI grep command handler — regex pattern preprocessing and tip generation`

#### AI Suggested Improvement

- Make the conversion silent (don't print a note — just do the right thing)
- If a note is shown, correct the command reference: show `htmlsnapshot grep -F` when the user ran `htmlsnapshot grep`, not `snapshot grep`
- Alternatively, accept standard `grep` `\|` syntax natively without conversion

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT]

---

### Issue 7: No `--timeout` flag to control per-command HTTP timeout

**Severity:** Low
**Category:** Discoverability / UX

#### Reproduction

When a command times out after 30s on a large page, there is no documented way to increase the timeout.

#### Expected Behavior

A `--timeout <seconds>` global flag or a documented way to increase the HTTP timeout for commands that process large pages.

#### Actual Behavior

No timeout control is available. The only workaround is retrying (which may or may not help).

#### Root Cause Analysis

The CLI HTTP client has a hardcoded 30-second timeout. There is no user-facing configuration for this.

#### Code Pointer

``cli/browser4-cli/src/` — HTTP client initialization, likely in `http.rs` or command dispatch`

#### AI Suggested Improvement

- Add a `--timeout <seconds>` global option that overrides the default 30s HTTP timeout
- Document large-page timeout risks in the Amazon scenarios documentation
- Consider automatically increasing timeouts for operations on pages above a certain size threshold

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] Feature request for a configurable timeout flag. The core problem (timeouts on large pages) is addressed by Issue 2 — increasing the default timeout or optimizing the backend path. A per-command --timeout flag is useful but lower priority; the real fix is making the default behavior work reliably for large pages first.

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Documented Amazon selectors do not match live Amazon search results

```
cargo run ... -- goto "https://www.amazon.com/s?k=Calabi-Yau"
cargo run ... -- htmlsnapshot
cargo run ... -- htmlsnapshot get all text "h2 a.a-link-normal"
```
Returns: `[]` — "No elements matched."

#### Issue 2: Backend HTTP timeouts on large Amazon product detail pages

```
cargo run ... -- goto "https://www.amazon.com/dp/0300235909"
cargo run ... -- htmlsnapshot                     # succeeds (2.1 MB)
cargo run ... -- htmlsnapshot summary             # times out after 30s
cargo run ... -- htmlsnapshot get text "#productTitle"  # times out after 30s
```

#### Issue 3: `snapshot -v` flag reports wrong `processingViewport` in output

```
cargo run ... -- goto "https://www.amazon.com/dp/B0C6VT75C6"
cargo run ... -- snapshot -v 1 --stdout
```
Output shows `# - processingViewport: 0` even though `-v 1` was requested.

#### Issue 4: `htmlsnapshot inspect` auto-discovery fails to find product cards on search results

```
cargo run ... -- goto "https://www.amazon.com/s?k=Calabi-Yau"
cargo run ... -- htmlsnapshot
cargo run ... -- htmlsnapshot inspect
```
Output shows navigation shortcut elements, not product cards.

#### Issue 5: Session state silently resets to `about:blank` between navigations

```
cargo run ... -- goto "https://www.amazon.com/dp/0300235909"
cargo run ... -- htmlsnapshot get text "#productTitle"   # times out
cargo run ... -- snapshot -v 0 --stdout                   # shows about:blank
```

#### Issue 6: `htmlsnapshot grep` produces confusing regex alternation conversion note

```
cargo run ... -- htmlsnapshot grep -i "price\|rating\|about" --selector "#centerCol"
```
Output includes:
```
Note: Converted grep-style alternation `\\|` to `|` in pattern. Rust regex uses bare `|` for alternation (like ERE/egrep). Use `snapshot grep -F` for literal matching.
```

#### Issue 7: No `--timeout` flag to control per-command HTTP timeout

When a command times out after 30s on a large page, there is no documented way to increase the timeout.

