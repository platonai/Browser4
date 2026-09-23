# Issues: comprehensive-ecommerce-workflow

> **Source:** `20260916-195119-comprehensive-ecommerce-workflow.full.md` | **Date:** 20260916-195119 | **Mode:** dev

## Scenario Background

### Task

Extracted from MockSite on `localhost:18080` (fixture was already running; backend auto-started immediately — first command returned in ~3 s).

**Product detail — `/ec/dp/B0E000001` (first featured product link):**

| Field | Value |
|---|---|
| Title | 4K OLED TV 55 |
| Price | $899.99 |
| Image URL | `https://picsum.photos/seed/1250857624/200/140` |
| Description | Premium 4K OLED TV |
| Features | 55 inch, HDR10+ |
| Specs | panel = OLED, resolution = 3840x2160 |
| Rating / stock | 4.6 (521) / In stock (35 available) |

**Electronics listing — `/ec/b?node=1292115012`:** 6 product links (confirmed by `eval --json`), titles and prices correlated in order: 4K OLED TV 55 `$899.99`, Wireless Noise-Cancelling Headphones `$199.99`, Portable Bluetooth Speaker `$49.99`, Smartphone 128GB `$599.00`, USB-C Hub 7-in-1 `$29.95`, Wireless Mouse `$24.99`.

Both extraction paths agreed: X-SQL (`--sql @file`), `htmlsnapshot get all`, `snapshot grep`, and LLM `extract --schema` all returned the same title/price/image. Browser state was saved to `browser-state-ec-run.json`; the empty cookies/localStorage is correct — the fixture sets none (verified against curl response headers, not assumed).

### Execution Context

18 steps, all invoked as `./b4w.ps1 <cmd>`. Navigation → `snapshot -v 0` → `snapshot -i` → `click e1091` → `htmlsnapshot` → `inspect --max 3 --depth 2` → `summary` → `get all text "#details"` → `htmlsnapshot query --sql @detail-product.sql` → `htmlsnapshot grep '[$][0-9]+\.[0-9]{2}'` → `extract --schema @product-schema.json --stdout` → `tab-new` → `htmlsnapshot get all` ×2 → `eval --json` → `screenshot --filename` (full-page) → `tab-select 1` + `reload` + `snapshot -v 0` → `snapshot grep -i "panel|resolution"` → `state-save`.

**Workarounds required (one):** step 6 failed to deliver its stated outcome. `htmlsnapshot inspect --max 3 --depth 2` on the product detail page auto-discovered `.recommendation-card` — the "Customers also viewed" side rail — and offered `span.recommendation-price` /...

(truncated — see full.md for complete trace)

---

## Issues Found (10 issues)

### Issue 1: Documented htmlsnapshot capture/read contract is wrong — reads silently serve the live DOM instead of the stored capture

**Severity:** High
**Category:** Documentation

#### Reproduction

1) ./b4w.ps1 goto "http://localhost:18080/ec/b?node=1292115012"
2) ./b4w.ps1 htmlsnapshot            # capture the listing page
3) ./b4w.ps1 htmlsnapshot get all text "#product-list .product-title"   # -> 6 titles
4) ./b4w.ps1 tab-select 1            # switch to the product detail tab
5) ./b4w.ps1 htmlsnapshot get all text "#product-list .product-title"   # -> []

Decisive proof that reads use the live DOM rather than any stored capture:
   ./b4w.ps1 htmlsnapshot get all text "#product-price-B0E000001"   # -> ["$899.99"]
   ./b4w.ps1 eval --file mutate-price.js                            # sets textContent to '$1234.56 MUTATED'
   ./b4w.ps1 htmlsnapshot get all text "#product-price-B0E000001"   # -> ["$1234.56 MUTATED"]  (no capture in between)

#### Expected Behavior

SKILL.md's 'htmlsnapshot capture requirements' table states that htmlsnapshot get / get all / inspect / summary / grep 'requires stored snapshot' and returns 'No HTML snapshot found' when one is missing. A read should therefore serve the page captured by the preceding `htmlsnapshot` command, and a miss should be diagnosed as a stale/absent capture.

#### Actual Behavior

Every read re-derives from the live document of the active tab; the stored capture is never the read source. A read issued after `tab-select` returns the *new* tab's data with no error and no warning, and line 4/5 above returns an empty result with only a generic 'may be stale / re-capture' hint. When selectors overlap between the two pages (e.g. 'h2', 'a', '.price'), the user silently receives the wrong page's data with a successful exit code. The `captured at` timestamp shown by `htmlsnapshot grep` is simply the current time (it advanced across three back-to-back greps and a 3-second sleep with no browser command in between), so it cannot be used to detect staleness either.

#### Root Cause Analysis

Confirmed in source, not inferred: HTMLSnapshotToolExecutor.scrape() (line 294-303), scrapeAll() (line 346), export() (line 532) and one further read path (line 671) all call liveDocumentOrNull(managed) FIRST and only fall back to the page-store copy when no live document exists. The comment states this is deliberate ('never silently serve a stale stored copy that predates session interactions'), so the implementation is intentional and it is SKILL.md section 4a's capture-requirements table (and the matching `--help` text) that is outdated. The remaining defect is that the deliberate behaviour is (a) undocumented and (b) indistinguishable to the user from the documented behaviour, because the stored capture is a per-session singleton that navigation and tab switches overwrite without notice.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/agent/tool/HTMLSnapshotToolExecutor.kt:liveDocumentOrNull() / scrape() (line 294) / scrapeAll() (line 346); docs: skills/browser4-cli/SKILL.md section 4a 'htmlsnapshot capture requirements' table`

#### AI Suggested Improvement

- Update the SKILL.md capture-requirements table and `htmlsnapshot --help` to state that get/get all/inspect/summary/grep read the LIVE document of the active tab and need no prior capture, and drop the 'No HTML snapshot found' claim if it is unreachable in this build
- Because the live-read is intentional, add a guard that surfaces the mismatch: when the live document's URL differs from the URL of the stored capture, print a warning naming both URLs instead of silently returning the other page's data
- Change the miss hint from 'The snapshot may be stale ... re-capture with htmlsnapshot' to name the page actually read (e.g. 'no match on the live page http://... ; stored capture is http://...'), so the user is not sent after the wrong remedy
- Make the `captured at` timestamp report the stored capture's real time rather than the read time, or remove it
- If pinning a capture is a supported use case, add an explicit opt-in flag (e.g. `--stored`) so the stored artifact can actually be read

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Raw internal tool signature leaked as the user-facing error on about:blank, with a remediation tip that cannot work

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 tab-new about:blank
./b4w.ps1 htmlsnapshot get all text "h1"
./b4w.ps1 htmlsnapshot get all text "h1" --json

#### Expected Behavior

A clear message such as 'The active tab has no page loaded (about:blank); navigate to a URL before extracting', with a non-zero exit code and no internal API surface.

#### Actual Behavior

ERROR: html_snapshot_scrape_all failed: Nil url is not allowed help: Extract text, textcontent, html, or attribute values from ALL elements matching a CSS selector.\nhtml_snapshot.scrape_all(Arg(name=sessionId, type=String, defaultValue=null), Arg(name=field, type=String, defaultValue=null), Arg(name=selector, type=String, defaultValue=:root), Arg(name=attrName, type=String, defaultValue=null), Arg(name=offset, type=Int, defaultValue=0), Arg(name=limit, type=Int, defaultValue=-1))\n\nTip: Run `htmlsnapshot` first to explicitly capture the page, then try again.\nExit code 1. The `--json` envelope reproduces the same raw text in error.message with status 'error'. The tip is actively misleading: capturing an about:blank page cannot produce a URL, so following the advice loops back to the same error.

#### Root Cause Analysis

The transport layer forwards the backend exception message verbatim (the message is the raw Java/MCP method signature) and then appends a blanket 'run htmlsnapshot first' hint from a generic miss handler. The underlying 'Nil url is not allowed' comes from the page-load component, which rejects a null URL. A quick grep shows this exact error class is a known recurring report (coworker/tasks/main/6git-pushed/2026/0711/review-competitive-monitoring-issues.md issue 1 documents it after redirect chains), so the generic tip has already been wrong once before.

#### Code Pointer

`browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/workflow/component/LoadComponent.kt:336 (throw IllegalArgumentException("Nil url is not allowed")); cli/browser4-cli/src/commands.rs:3288 (htmlsnapshot-get-all spec, tool_name_fn html_snapshot_scrape_all)`

#### AI Suggested Improvement

- Map the nil-URL case to a user-facing message that names the real condition (no page loaded / about:blank / session not attached) before the exception reaches the CLI
- Strip the internal `Arg(name=..., type=..., defaultValue=...)` signature from any message shown to users; log it at debug level instead
- Suppress the 'Run htmlsnapshot first' tip when the failure is a nil URL, since a capture cannot fix it
- Add a regression test that runs the htmlsnapshot read commands against about:blank and asserts on a friendly message (this class of failure has now been reported at least twice)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: htmlsnapshot inspect on a product detail page confidently reports a side rail as the page's content pattern

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

./b4w.ps1 goto "http://localhost:18080/ec/dp/B0E000001"
./b4w.ps1 htmlsnapshot
./b4w.ps1 htmlsnapshot inspect --max 3 --depth 2

#### Expected Behavior

Per the task at hand, selectors for the product title, price, description and image — or, if the page has no repeating block, the documented 'No recurring pattern found' notice pointing the user at `htmlsnapshot summary`.

#### Actual Behavior

Inspect reported 'Inspect: ".recommendation-card" (4 matches, 3 analyzed)' and 'Auto-discovered repeating pattern from ":root"', then listed high-quality (starred) selectors for the 'Customers also viewed' side rail — p.recommendation-copy, span.recommendation-price, span.recommendation-stock — with sample values. Nothing in the 77-line output says the pattern is a peripheral rail rather than the page's main content, and no 'No recurring pattern found' notice appears. `htmlsnapshot summary` on the same page correctly labels the page 'Product Detail' but its 'Suggested Commands' section still only emits `htmlsnapshot get all text ".recommendation-grid a"`. A user following either command would build an extractor for the recommendations rail and silently get the wrong data.

#### Root Cause Analysis

inspect is a repeating-pattern detector by design, and a detail page has no repeating main block, so the largest sibling group wins by default — here the 4-card recommendation grid. SKILL.md section 4e documents this caveat ('inspect may surface nothing or an unrelated side rail'), but the tool does not detect or announce the situation, and the ':root' phrasing names the selector base, not the discovered pattern, which reads as a bug. The 'Try these next' block recommends `get all` on the rail selectors without any confidence caveat.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/agent/tool/HTMLSnapshotToolExecutor.kt:140 (inspect tool spec 'suggest CSS selectors for recurring patterns')`

#### AI Suggested Improvement

- When the top recurring pattern is not inside the page's largest content container (or the page type is 'Product Detail'), print an explicit notice that the pattern looks like a side rail and that detail pages should use explicit selectors or `summary`
- Replace 'Auto-discovered repeating pattern from ":root"' with the selector actually discovered ('.recommendation-card') so the header is not self-contradictory
- Move the detail-page branch into the tool rather than the skill doc: emit the `summary` / explicit-selector recommendation as part of the inspect output
- Have `htmlsnapshot summary` suggest selectors for the element that made it classify the page as 'Product Detail' (here #productTitle / #product-price / #product-image), not just the largest link group

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: snapshot grep searches the full in-memory tree, not the file you just saved — undocumented, and line numbers do not line up

**Severity:** Medium
**Category:** Documentation

#### Reproduction

./b4w.ps1 snapshot -v 0            # writes .browser4-cli/snapshot/snapshot-<ts>.yml
./b4w.ps1 snapshot grep -i "OLED|3840x2160"

#### Expected Behavior

Either grep should search the snapshot artifact the user just produced (so hits can be located in the file), or `snapshot grep --help` should say plainly that it searches the live in-memory tree instead.

#### Actual Behavior

grep reported '- article "product-page product-image 4K OLED TV 55 https://picsum.photos/seed/1250857624/200/140 productTitle 4K OLED TV 55 ... button Add to cart button Buy now" [ref=e1930]' at line 26 — a ~1000-character line. The saved snapshot file has `- article [ref=e1930]` at line 31 with NO name, and contains zero occurrences of the string 'product-page'. So the hit cannot be found in the saved file, its line number does not match, and the rendered name includes CSS class names and attribute values that are not accessible names. `snapshot grep --help` lists 18 options but never states which tree is searched; the only place this is mentioned is a comment inside the generated YAML ('Use `browser4-cli snapshot grep <pattern>` to search the full in-memory tree'), which is not visible unless the file is opened.

#### Root Cause Analysis

`snapshot grep` renders the live AX tree with the interactive-oriented name-aggregation pass applied, while the saved .yml is written from a different (viewport-limited, unaggregated) rendering. Because the two views are produced by different code paths, names, line numbers and element scope all diverge. For a long article element the aggregated name pulls in every descendant's text plus class/attribute tokens, producing the very long line.

#### Code Pointer

`cli/browser4-cli/src/commands.rs (snapshot grep command); cli/browser4-cli/src/snapshot.rs:render_snapshot() if the aggregated renderer lives there`

#### AI Suggested Improvement

- State in `snapshot grep --help` that the search runs over the full in-memory tree and is not limited by a previous `-v` capture, and that reported line numbers refer to that rendering
- Print an advisory when grep has to search beyond the viewport(s) in the saved file, so the user knows the hits may be off-screen relative to what was captured
- Bound the aggregated name length (or suppress class/attribute tokens) so a single grep hit stays readable
- Prefer searching the same artifact the user saved, or note the saved file path in the grep header so the two can be reconciled

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: snapshot prints a Viewport State header claiming viewport 0 even when no viewport was requested

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 tab-select 1
./b4w.ps1 snapshot --stdout | head -7
./b4w.ps1 snapshot -v 0 --stdout | head -7
./b4w.ps1 snapshot --stdout | wc -l
./b4w.ps1 snapshot -v 0 --stdout | wc -l

#### Expected Behavior

The header should describe what the output actually contains, so a user can confirm at a glance whether a bounded capture took effect.

#### Actual Behavior

Both invocations print a byte-identical header ('processingViewport: 0 / viewportHeight: 1080px / viewportsTotal: 2 / hiddenTopHeight: 0px / hiddenBottomHeight: 540px') yet contain different trees — 135 lines versus 96 on the detail page. The unbounded run's body includes elements at y=1099, i.e. below the fold, while the header still says processingViewport: 0. On the home page `snapshot -i` showed elements at y=1838 with a header claiming hiddenBottomHeight: 798px. Since SKILL.md repeatedly warns to bound snapshot size with -v to avoid multi-megabyte dumps, the inability to tell a bounded capture from a full-page one from the output itself is a practical hazard.

#### Root Cause Analysis

The viewport block is a page-level summary emitted unconditionally, whereas the tree body respects the -v filter. Nothing in the header records whether a filter was applied or what portion of the page the body covers.

#### Code Pointer

`cli/browser4-cli/src/snapshot.rs:render_snapshot()`

#### AI Suggested Improvement

- Emit the '# Viewport State' block only when a viewport filter is in effect, or add a line such as '# Rendered: viewport 0 of 2 (y 0-1080px)' versus '# Rendered: full page'
- Include the flag that produced the capture in the header so a pasted snapshot is self-describing
- For the unbounded case, state the captured range explicitly (e.g. '# Rendered: all 2 viewports, 0-1620px')

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: htmlsnapshot get all --json returns the payload as a JSON-encoded string instead of a native array

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 htmlsnapshot get all text "#productTitle" --json
./b4w.ps1 eval "document.querySelectorAll('a.product-link').length" --json

#### Expected Behavior

A consistent envelope where the payload is native JSON, matching `eval --json`.

#### Actual Behavior

htmlsnapshot get all emits {"status":"ok","command":"htmlsnapshot-get-all","output":{"result":"[\"4K OLED TV 55\"]","mode":"text","selector":"#productTitle"}} — output.result is a string containing JSON, so a consumer must parse twice (jq '.output.result | fromjson'). The sibling command `eval --json` emits {"status":"ok","command":"eval","output":{"result":6,...}} with a native number. The inconsistency is invisible from the docs and only surfaces at parse time.

#### Root Cause Analysis

The get-all handler serialises the extraction result to a string before placing it in the envelope, while eval places its typed value directly. The envelope is otherwise uniform, so this looks like a per-command serialisation choice rather than an intentional contract.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:3288 (htmlsnapshot-get-all spec) and the response-envelope assembly`

#### AI Suggested Improvement

- Emit the extracted array as native JSON in output.result (or add a parallel structured field such as output.items) so `--json` output is uniformly single-parse
- If the string form must be kept for backward compatibility, document the double-parse requirement in the htmlsnapshot reference and in `htmlsnapshot get all --help`
- Add a test asserting that `--json` output for get all parses to an array in one pass

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: Plain-mode htmlsnapshot get all writes prose diagnostics to stdout, breaking pipelines

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 htmlsnapshot get all text "#productTitle" 2>/dev/null
./b4w.ps1 htmlsnapshot get all text "#nonexistent-selector" 2>/dev/null

#### Expected Behavior

Machine-readable data on stdout, human diagnostics on stderr — so `./b4w.ps1 htmlsnapshot get all text "#productTitle" | jq` works without extra flags.

#### Actual Behavior

Success case prints the array AND a prose line on stdout: '["4K OLED TV 55"]' followed by '1 result found for "#productTitle". If you expected more, the selector may be too narrow - try `htmlsnapshot inspect "#productTitle"` to discover alternatives.' Empty case prints '[]' followed by three lines of prose. Piping into jq fails in both cases; only --json gives a clean single line.

#### Root Cause Analysis

The result and its human-readable annotation are both written to stdout in the non-JSON branch, rather than routing the annotation to stderr as the CLI already does for tips and pagination footers.

#### Code Pointer

`cli/browser4-cli/src/commands.rs (htmlsnapshot get / get all output path)`

#### AI Suggested Improvement

- Route the 'N result(s) found' and 'No elements matched' annotations to stderr, matching how tips and pagination footers are already handled
- Keep stdout strictly the data payload in every mode, so the default mode is pipe-safe
- Add a regression test asserting that stdout alone parses as JSON for both a hit and a miss

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: doctor reports '✓ CLI and runtime versions match' while printing two different versions

**Severity:** Low
**Category:** Reliability

#### Reproduction

./b4w.ps1 doctor

#### Expected Behavior

Either a genuine comparison, or wording that reflects what was actually checked (compatibility), with no success glyph next to contradictory numbers.

#### Actual Behavior

Prints 'CLI version: 4.13.20', 'Installed runtime: v4.13.19', then '✓ CLI and runtime versions match.' The two versions are plainly different, so the checkmark invites a user to dismiss a real version skew — in this run the backend was additionally a 4.13.18-SNAPSHOT dev build.

#### Root Cause Analysis

The comparison appears to test compatibility or major/minor alignment rather than equality, but the printed claim says 'match'. (Uncertain which predicate is used — worth checking whether the runtime version is read from the bundle manifest or from the backend's reported version.)

#### Code Pointer

`cli/browser4-cli/src/main.rs (doctor build-info section)`

#### AI Suggested Improvement

- Reword to '✓ CLI and runtime are compatible' when the check is a compatibility predicate, reserving 'match' for exact equality
- Print the comparison basis (e.g. 'major.minor compatible: 4.13 == 4.13') so the checkmark is verifiable
- When versions differ, show which component is behind and whether an upgrade is needed

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: Interactive-element listings label element area as 'w=' next to a box that already contains a width

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 htmlsnapshot
./b4w.ps1 tab-new "http://localhost:18080/ec/b?node=1292115012"

#### Expected Behavior

An unambiguous label for whatever the value represents.

#### Actual Behavior

Lines read e.g. '#product-page button.add-cart "Add to cart"  [box=1325 334 281 41; w=1011521]' and 'a.product-link "USB-C Hub 7-in-1" [box=1105 320 169 171; w=28899]'. The box already reads x y width height, so 'w=' immediately after a width of 281 is naturally read as width — but the value is the element's area: 169 x 171 = 28899 and 39 x 16 = 624 in the 'Home' link, both exact products of the box dimensions. The meaning is never explained in the output or in help.

#### Root Cause Analysis

A weight/area score is rendered with the single-letter label 'w', colliding with the conventional abbreviation for width in the adjacent box tuple, and the score's derivation (width x height) is undocumented.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/agent/tool/HTMLSnapshotToolExecutor.kt (interactive-element listing section)`

#### AI Suggested Improvement

- Rename the field to 'area=' or 'score=' so it cannot be confused with width
- Document the score and its derivation in the htmlsnapshot reference
- Consider dropping it from the default listing, since the bounding box already carries the geometry a user needs

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 10: No strict interactive-only snapshot mode exists; -i is a layout change, not a filter

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 goto "http://localhost:18080/ec/"
./b4w.ps1 snapshot -i --stdout | head -60

#### Expected Behavior

A way to list just the clickable/actionable elements — product links, search box, navigation — as the workflow 'capture an interactive-only snapshot to see clickable elements' implies.

#### Actual Behavior

`snapshot -i` returns 151 lines on the home page, including non-interactive nodes rendered as '- text: $49.99', '- text: 4.8 (1200)', '- text: Ready to ship'. The CLI's own help is candid that `-i` is 'an interactive-oriented layout, not an interactive-only filter' and SKILL.md repeats that '-i does not shrink the tree', so the documentation is accurate — but a user who wants the filtered view has no command that provides it and must post-filter with `snapshot grep`.

#### Root Cause Analysis

The -i pass aggregates inner text into enclosing elements' names (and in doing so also merges unrelated values, e.g. rendering the search input as 'generic "text wireless headphones, air purifier, running shoes Search products"'). It was designed to improve target self-containment for ref-based interaction, not to filter roles; no role-based filter flag was added alongside it.

#### Code Pointer

`cli/browser4-cli/src/snapshot.rs:render_snapshot() (interactive rendering path)`

#### AI Suggested Improvement

- Add a true interactive-only filter (e.g. `--only-interactive` / a role filter limited to link, button, textbox, checkbox, radio, combobox) so the common 'show me what I can click' request has a direct answer
- Until then, document the post-filter recipe in the -i section of SKILL.md (e.g. `snapshot grep` for link|button|textbox) so users are not left to discover it
- Consider trimming standalone non-interactive text rows from the -i rendering, since they are the main source of the residual noise

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 18 steps completed and the extracted data was cross-validated across four independent methods (X-SQL, htmlsnapshot get all, snapshot grep, LLM extract) with agreeing results. One step (htmlsnapshot inspect on the detail page) did not deliver its stated outcome and required the documented workaround.

**Success Rate:** 88% — 16 of 18 steps produced their stated outcome directly; step 6 required a fallback to explicit selectors, and step 16 produced a usable but hard-to-read result.

**Issues Found:** 10

**Major Blockers:** None. The environment was fully working: MockSite was already listening on 18080 and the backend auto-started, so the first command returned in about 3 seconds. The only step that did not deliver as specified was htmlsnapshot inspect on the product detail page, which reported the 'Customers also viewed' side rail as the page's recurring pattern instead of the product's title/price/description/image selectors; explicit CSS selectors were used instead.

**Most Confusing Aspects:** The htmlsnapshot read model. The documentation's capture-requirements table says get/get all/inspect/summary/grep all require a prior `htmlsnapshot` capture, but in this build every read re-derives from the live document of the active tab — so a capture cannot be pinned, and switching tabs silently changes what a read returns. Reading the table, then watching a read return a different page's data (or an unexplained empty array), is the single most disorienting experience in the workflow. Second is metadata that contradicts itself or the body: a Viewport State header claiming viewport 0 on a full-page dump, a 'captured at' timestamp that is always the current time, and '✓ versions match' printed beside two different version numbers.

**Most Valuable Improvements:** 1) Reconcile the htmlsnapshot read contract with the implementation and, because the live-DOM read is deliberate, make it visible — warn when the live URL differs from the stored capture's URL and name the page actually read on a miss, instead of the misleading 're-capture with htmlsnapshot' tip. 2) Replace the raw 'Nil url is not allowed' internal signature and its unworkable remediation tip with a plain message naming the real condition (no page loaded). 3) Make htmlsnapshot inspect announce when it has found a side rail rather than main content, and have summary suggest selectors for the element that made it classify the page as a product detail page. 4) Small but high-leverage polish: emit the Viewport State header only when a viewport filter is in effect, keep stdout pipe-clean in non-JSON mode, and return native JSON from htmlsnapshot get all --json to match eval --json.

**Usability Rating:** 7/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` (PowerShell) or `./b4w.sh` (Bash / Git Bash), which auto-build from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root:

   - **PowerShell:** `./b4w.ps1 <command>`
   - **Bash / Git Bash:** `./b4w.sh <command>`
   - **Direct:** `browser4-cli <command>` (if installed globally)

   > **Note:** `$(./b4w.ps1)` is command substitution in bash — do NOT use it.

### Per-Issue Reproduction Steps

#### Issue 1: Documented htmlsnapshot capture/read contract is wrong — reads silently serve the live DOM instead of the stored capture

1) ./b4w.ps1 goto "http://localhost:18080/ec/b?node=1292115012"
2) ./b4w.ps1 htmlsnapshot            # capture the listing page
3) ./b4w.ps1 htmlsnapshot get all text "#product-list .product-title"   # -> 6 titles
4) ./b4w.ps1 tab-select 1            # switch to the product detail tab
5) ./b4w.ps1 htmlsnapshot get all text "#product-list .product-title"   # -> []

Decisive proof that reads use the live DOM rather than any stored capture:
   ./b4w.ps1 htmlsnapshot get all text "#product-price-B0E000001"   # -> ["$899.99"]
   ./b4w.ps1 eval --file mutate-price.js                            # sets textContent to '$1234.56 MUTATED'
   ./b4w.ps1 htmlsnapshot get all text "#product-price-B0E000001"   # -> ["$1234.56 MUTATED"]  (no capture in between)

#### Issue 2: Raw internal tool signature leaked as the user-facing error on about:blank, with a remediation tip that cannot work

./b4w.ps1 tab-new about:blank
./b4w.ps1 htmlsnapshot get all text "h1"
./b4w.ps1 htmlsnapshot get all text "h1" --json

#### Issue 3: htmlsnapshot inspect on a product detail page confidently reports a side rail as the page's content pattern

./b4w.ps1 goto "http://localhost:18080/ec/dp/B0E000001"
./b4w.ps1 htmlsnapshot
./b4w.ps1 htmlsnapshot inspect --max 3 --depth 2

#### Issue 4: snapshot grep searches the full in-memory tree, not the file you just saved — undocumented, and line numbers do not line up

./b4w.ps1 snapshot -v 0            # writes .browser4-cli/snapshot/snapshot-<ts>.yml
./b4w.ps1 snapshot grep -i "OLED|3840x2160"

#### Issue 5: snapshot prints a Viewport State header claiming viewport 0 even when no viewport was requested

./b4w.ps1 tab-select 1
./b4w.ps1 snapshot --stdout | head -7
./b4w.ps1 snapshot -v 0 --stdout | head -7
./b4w.ps1 snapshot --stdout | wc -l
./b4w.ps1 snapshot -v 0 --stdout | wc -l

#### Issue 6: htmlsnapshot get all --json returns the payload as a JSON-encoded string instead of a native array

./b4w.ps1 htmlsnapshot get all text "#productTitle" --json
./b4w.ps1 eval "document.querySelectorAll('a.product-link').length" --json

#### Issue 7: Plain-mode htmlsnapshot get all writes prose diagnostics to stdout, breaking pipelines

./b4w.ps1 htmlsnapshot get all text "#productTitle" 2>/dev/null
./b4w.ps1 htmlsnapshot get all text "#nonexistent-selector" 2>/dev/null

#### Issue 8: doctor reports '✓ CLI and runtime versions match' while printing two different versions

./b4w.ps1 doctor

#### Issue 9: Interactive-element listings label element area as 'w=' next to a box that already contains a width

./b4w.ps1 htmlsnapshot
./b4w.ps1 tab-new "http://localhost:18080/ec/b?node=1292115012"

#### Issue 10: No strict interactive-only snapshot mode exists; -i is a layout change, not a filter

./b4w.ps1 goto "http://localhost:18080/ec/"
./b4w.ps1 snapshot -i --stdout | head -60

