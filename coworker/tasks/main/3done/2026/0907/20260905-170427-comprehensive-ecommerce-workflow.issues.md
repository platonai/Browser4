# Issues: comprehensive-ecommerce-workflow

> **Source:** `20260905-170427-comprehensive-ecommerce-workflow.full.md` | **Date:** 20260905-170427 | **Mode:** dev

## Scenario Background

### Task

All 18 scenario steps were completed against MockSite (localhost:18080) with browser4-cli 4.13.14 built from the local source tree. Two steps required recovery workarounds (see trace). No temporary files were left in the repo root — all artifacts are under `.test-sessions/` (plus the CLI's own `.browser4-cli/snapshot/` state directory).

**Extracted product data (summary):**

*Product detail — 4K OLED TV 55 (`/ec/dp/B0E000001`):*
- **Title:** 4K OLED TV 55 (`#productTitle`) · **Price:** $899.99 (`#product-price`) · **Description:** "Premium 4K OLED TV" (`p.description`) · **Features:** 55 inch, HDR10+ (`#product-features li`) · **Image URL:** `https://picsum.photos/seed/1250857624/200/140` (`#product-image`)
- X-SQL from file: `{"title":"4K OLED TV 55","price":"$899.99","image_url":"https://picsum.photos/seed/1250857624/200/140"}`
- AI extract (DEEPSEEK key, `--schema`, JSON): `{"title":"4K OLED TV 55","price":"$899.99","description":"Premium 4K OLED TV","features":["55 inch","HDR10+"]}`
- Verification after tab switch + reload: heading/price/features/specs all present; `snapshot grep` hits for "HDR10+", "OLED TV", "In stock (35 available)".

*Electronics listing (`/ec/b?node=1292115012`) — 6 products (title · price · URL):*
1. 4K OLED TV 55 · $899.99 · /ec/dp/B0E000001
2. Wireless Noise-Cancelling Headphones · $199.99 · /ec/dp/B0E000002
3. Portable Bluetooth Speaker · $49.99 · /ec/dp/B0E000003
4. Smartphone 128GB · $599.00 · /ec/dp/B0E000004
5. USB-C Hub 7-in-1 · $29.95 · /ec/dp/B0E000005
6. Wireless Mouse · $24.99 · /ec/dp/B0E000006

`eval --json` product-link count = **6**; screenshot saved to `.test-sessions/electronics-listing-node1292115012.png`; state saved to `.test-sessions/storage-state-2026-09-05T17-00-30-800Z.json` and `.test-sessions/ec-final-state.json`. Full report: `.test-sessions/product-data-report.md`.

### Execution Context

1. **Prep:** Verified cwd, ran `./b4w.ps1 help`, read `skills/browser4-cli/SKILL.md` and the htmlsnapshot/snapshot help; confirmed MockSite up (HTTP 200). Backend/daemon auto-started on first `goto` without issue.
2. **Home page (steps 1–3):** `goto http://localhost:18080/ec/` (reconnected to a pre-existing DEFAULT session), `snapshot -v 0` and `snapshot -i` — read the YAML files from `.browser4-cli/snapshot/` to see viewport 0 and the interactive layout; noted the first product link ref `e1986` (4K OLED TV 55) was consistent across both snapshots.
3. **Detail page (steps 4–10):** `click e1986` → `/ec/dp/B0E000001`; `htmlsnapshot` capture; `htmlsnapshot inspect --max 3 --depth 2` — **returned only the "Customers also viewed" recommendation rail** (`.recommendation-card`), not the main prod...

(truncated — see full.md for complete trace)

---

## Issues Found (6 issues)

### Issue 1: htmlsnapshot capture fails with __pulsar_utils__ ReferenceError on every tab after tab-new until the session is closed

**Severity:** High
**Category:** Reliability

#### Reproduction

1) ./b4w.ps1 goto http://localhost:18080/ec/dp/B0E000001 2) ./b4w.ps1 htmlsnapshot  (works) 3) ./b4w.ps1 tab-new 'http://localhost:18080/ec/b?node=1292115012' 4) ./b4w.ps1 htmlsnapshot  → ERROR: html_snapshot_capture failed: ReferenceError: __pulsar_utils__ is not defined at <anonymous>:1:1 5) ./b4w.ps1 reload then ./b4w.ps1 htmlsnapshot, or tab-select back to the original tab then ./b4w.ps1 htmlsnapshot → same error every time.

#### Expected Behavior

Capture should succeed on the tab-new target and on every tab afterwards. The codebase has an explicit self-heal mechanism and regression test for this exact scenario (HtmlSnapshotScenariosE2ETest '1e — Capture survives tab-new and tab-select back (runtime self-heal)').

#### Actual Behavior

htmlsnapshot capture fails persistently on ALL tabs of the session after the first tab-new. The error is a raw JS ReferenceError with no recovery hint; retrying, reloading, and switching tabs do not help. AX snapshot, eval, htmlsnapshot query and screenshot keep working. Closing the session and re-running goto (fresh session) restores capture. Matches the in-code comment in Browser4WebDriver.kt: 'breaks capture until the session is closed'.

#### Root Cause Analysis

The Browser4 dual-world runtime (__pulsar_utils__) is registered into a tab's isolated world only by navigation hooks (onFrameNavigated0). A tab opened via tab-new commits its document before the driver binds to it, so no hook fires, evaluations fall back to the main world, and capture helpers dereferencing __pulsar_utils__ throw. A lazy recovery (ensurePulsarUtilsInjected) is called best-effort from HTMLSnapshotToolExecutor.captureLiveDocumentSnapshot and AgentToolManager.bindSwappedDriver, but in this run the ReferenceError still escaped as a hard tool error — the failing dereference likely happens in an unguarded path (e.g. the archival fallback pulsarSession.capture(driver) in HTMLSnapshotToolExecutor.capture(), reached when the guarded live-serialization path returns null). Possible contributing factor: the DEFAULT session pre-existed this backend process (its tab predated the daemon), a scenario the code comments call out separately. Investigation needed: reproduce in a fully fresh session and trace which helper throws past the recovery.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/agent/tool/HTMLSnapshotToolExecutor.kt:capture() (line 165) and captureLiveDocumentSnapshot() (line 224); browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/Browser4WebDriver.kt:ensurePulsarUtilsInjected() (line 964); regression test browser4-tests/browser4-rest-tests/src/test/kotlin/ai/platon/pulsar/rest/api/controller/HtmlSnapshotScenariosE2ETest.kt:test1e_captureSurvivesTabNewAndTabSelectBack (line 329)`

#### AI Suggested Improvement

- Make capture never surface a raw ReferenceError: when ensurePulsarUtilsInjected() returns false or the live serialization fails, retry the annotated capture once after an explicit re-injection attempt, then degrade to the plain outerHTML serialization the code comments already promise instead of falling into an unguarded archival path.
- Add CLI-shaped e2e coverage: tab-new + immediate capture on the SAME-ORIGIN page, capture on the previously-good tab after switching back, and a session whose tab predates the backend process.
- On failure, print an actionable message (e.g. 'close the session and reopen, or use htmlsnapshot query which does not require capture') instead of the raw ReferenceError.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: X-SQL query file starting with a SQL comment line fails with misleading 500 'Only select statements are supported'

**Severity:** Medium
**Category:** Product

#### Reproduction

Write a .sql file whose first line is a comment, then run it: printf '%s\n' '-- Extract product fields' 'SELECT DOM_FIRST_TEXT(DOM, ''h1'') AS t FROM DOM_LOAD_AND_SELECT(@url, '':root'')' > .test-sessions/q.sql; ./b4w.ps1 htmlsnapshot query --sql '@.test-sessions/q.sql' → HTTP 500, message 'Only select statements are supported', resultSet null. Removing the comment line makes the identical query succeed.

#### Expected Behavior

SQL comments are valid in SQL files; the comment should be ignored and the SELECT executed (the backend sanitizer already strips comment lines before execution — just too late).

#### Actual Behavior

Any .sql file that begins with a '--' comment (a very natural way to annotate a query file) fails with a 500 and a misleading 'Only select statements are supported' error. The CLI error wrapper adds no clue that the file's leading comment is the cause.

#### Root Cause Analysis

APISQLUtils.sanitize() lowercases and trims the SQL and checks startsWith('select') BEFORE removing '--' comment lines; comment stripping happens only at the end of the function. A leading comment therefore fails the statement-type guard even though the file contains a valid single SELECT.

#### Code Pointer

`browser4-agent-tools/src/main/kotlin/ai/platon/pulsar/agentic/tools/advanced/crawl/common/APISQLUtils.kt:sanitize() (line 10)`

#### AI Suggested Improvement

- Move the comment-line filter (lines 31-33) above the select-statement guard so comments never affect statement-type detection.
- Add a unit test: sanitize('-- header\nSELECT 1') must not throw.
- Optionally have the CLI/backend report a friendlier error ('query must start with SELECT') when the guard does fire.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: htmlsnapshot export --clean is a silent no-op: output byte-identical to a non-clean export

**Severity:** Medium
**Category:** Product

#### Reproduction

1) ./b4w.ps1 goto a page with inline <style>/<script> (e.g. http://localhost:18080/ec/dp/B0E000001) 2) ./b4w.ps1 htmlsnapshot 3) ./b4w.ps1 htmlsnapshot export --file .test-sessions/clean.html --clean 4) ./b4w.ps1 htmlsnapshot export --file .test-sessions/raw.html 5) diff the two files → identical (15750 bytes both), still containing <style>, the injected <script id=PulsarScriptSection>, and vi= attributes.

#### Expected Behavior

Per command docs ('Add --clean to strip scripts, styles, and non-standard attributes'), the --clean export should contain no <script>/<style> and no non-standard attributes (except the documented vi keep).

#### Actual Behavior

The --clean export is byte-identical to the full export. The cleaning code exists (cleanDocument removes script/style/noscript/comments/non-standard attrs) but its mutations never reach the serialized output — a silent no-op with no warning.

#### Root Cause Analysis

HTMLSnapshotToolExecutor.export() calls cleanDocument(document) on the parsed FeaturedDocument and then returns document.outerHtml. The observed byte-identical output (raw vs clean, on a page with <style> and a <script> plus vi attributes) indicates outerHtml is not re-serialized from the mutated jsoup tree — likely a cached/original annotated serialization held by the FeaturedDocument (an upstream type, ai.platon.pulsar.dom.FeaturedDocument). Cleaning must be applied to the string actually returned, or the document must be re-serialized after cleaning. Needs verification of FeaturedDocument.outerHtml semantics.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/agent/tool/HTMLSnapshotToolExecutor.kt:export() (line 518) and cleanDocument() (line 549)`

#### AI Suggested Improvement

- Apply cleanDocument to a fresh parse of the document that export actually returns (or serialize the cleaned tree explicitly) so --clean changes the output.
- Add a regression test asserting clean export lacks <script>/<style>/non-standard attributes and differs from the raw export.
- If clean output is intentionally deferred for some document kinds, print a warning instead of silently ignoring the flag.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: htmlsnapshot inspect on a detail page surfaces only the 'recommendations' side rail and never the documented container-structure fallback, so main product selectors are undiscoverable via inspect

**Severity:** Medium
**Category:** Product

#### Reproduction

1) ./b4w.ps1 goto http://localhost:18080/ec/dp/B0E000001 2) ./b4w.ps1 htmlsnapshot 3) ./b4w.ps1 htmlsnapshot inspect --max 3 --depth 2 → returns only '.recommendation-card' selectors (Customers also viewed rail). 4) ./b4w.ps1 htmlsnapshot inspect '#product-page' → auto-narrows to '#product-page span.info-pill', never listing the container's children (#productTitle, #product-price, .description, #product-image).

#### Expected Behavior

htmlsnapshot --help documents: 'For detail pages (single product, article) where no repeating patterns exist, inspect falls back to showing the page's top-level container structure.' Passing the main container selector should show its child structure with working selectors for title/price/description/image.

#### Actual Behavior

inspect always auto-discovers some recurring sub-pattern (recommendation cards from ':root'; info pills from '#product-page') and never shows the top-level structure. The task's selector-discovery step therefore returned selectors for the WRONG data region; discovering the main product fields required reading an HTML export by hand. Risk: an automated user could silently extract recommendation-card prices instead of product data.

#### Root Cause Analysis

The inspect pattern-discovery engine always finds the most-recurring sibling pattern under the given root instead of honoring the documented no-recurring-pattern fallback for single-container pages. Either the fallback is not implemented for the selector case or recurrence detection outranks it.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/agent/tool/HTMLSnapshotToolExecutor.kt:inspect() (dispatch at line 156); pattern discovery in browser4-core/browser4-protocol/src/main/kotlin/ai/platon/pulsar/protocol/browser/emulator/impl/InteractiveBrowserEmulator.kt (html_snapshot_inspect path)`

#### AI Suggested Improvement

- Implement the documented fallback: when a selector matches a single element (or ':root' has no sibling-group repeats), print the container's one/two-level child structure with per-child selectors and sample text so title/price/image selectors are directly discoverable.
- Label the auto-discovered pattern with its context (e.g. 'recurring pattern found in a recommendations rail — this may not be the page's main content').
- Cross-reference the SKILL.md guidance ('detail pages → use summary or get with explicit selectors') in inspect's own tip output when the discovered pattern is outside the main content region.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: state-save with no filename writes a timestamped JSON dump into the current working directory (repo root in dev), unlike other capture outputs

**Severity:** Low
**Category:** UX

#### Reproduction

From the repo root: ./b4w.ps1 state-save → prints 'Storage state saved: D:/workspace/Browser4/Browser4-4.13/storage-state-2026-09-05T17-00-30-800Z.json' — the file lands in the repository root, next to source files. (Passing an explicit path, e.g. state-save .test-sessions/state.json, works correctly.)

#### Expected Behavior

Bare state-save should default into the CLI's snapshot/state output directory (like extract/screenshot/snapshot outputs, which go to .browser4-cli/snapshot/) or an otherwise documented location, keeping the working tree clean.

#### Actual Behavior

A bare state-save pollutes the user's working directory with a timestamped JSON file. Nothing in the storage-state reference or help text states where the file will be written when no filename is given.

#### Root Cause Analysis

resolve_storage_state_path() joins the (missing) filename with std::env::current_dir(), producing timestamped files in the CWD instead of the CLI snapshot directory used by other file-producing commands.

#### Code Pointer

`cli/browser4-cli/src/main.rs:resolve_storage_state_path() (line 4095) and handle_state_save() (line 4117)`

#### AI Suggested Improvement

- Default bare state-save to the CLI snapshot directory (.browser4-cli/snapshot/) or document the CWD default prominently in help text and storage-state.md.
- Print the absolute path (already done) plus a one-line 'saved to current directory' hint when no filename was supplied.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: 'Interactive' snapshot mode (-i) name implies an interactive-only filter; top-level help does not explain the flag, and output can look like a full-page dump

**Severity:** Low
**Category:** Discoverability

#### Reproduction

1) ./b4w.ps1 help  (top-level list only says 'snapshot Capture page snapshot to obtain element refs. See flags below...' — no -i semantics) 2) ./b4w.ps1 goto http://localhost:18080/ec/ 3) ./b4w.ps1 snapshot -i → the YAML contains headings, paragraphs, generic containers and banner/footer nodes in addition to links/buttons; a user expecting 'only clickable elements (product links, search, navigation)' must page through the whole aggregated tree.

#### Expected Behavior

A first-time user asked for an 'interactive-only snapshot' should either get a genuinely interactive-only view or a clear upfront note that -i only re-renders the tree (text merged into names) and still contains non-interactive nodes, so they can pair it with -v 0 / --selector.

#### Actual Behavior

snapshot --help and SKILL.md explain the semantics precisely ('interactive-oriented rendering... not a strict interactive-only filter'), but the top-level help (the primary surface a new user reads) does not, and the -i output is indistinguishable from a full-tree dump with concatenated names. The mismatch between the flag's name/mental model and its output cost extra reading of large files during the evaluation.

#### Root Cause Analysis

Discoverability gap: the succinct top-level help for the snapshot family omits flag semantics, and the -i flag name invites an interactive-only reading that the implementation intentionally does not provide.

#### Code Pointer

`cli/browser4-cli/src/main.rs (help text for the snapshot command family)`

#### AI Suggested Improvement

- In the top-level help snapshot line, append a short parenthetical: '-i merges inner text into ref names (interactive-oriented layout, not an interactive-only filter); pair with -v 0 or --selector to bound output'.
- Consider adding an actual interactive-only filter mode (links/buttons/inputs/navigation only) since that is what the flag name and common user intent expect.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 18 task steps completed. Two steps required workarounds: (a) selector discovery on the detail page used htmlsnapshot export + reading the markup after inspect returned only the recommendation rail; (b) after tab-new broke htmlsnapshot capture session-wide, the session was closed and reopened fresh (documented recovery) to complete listing capture/get-all, and state-save was performed on the original session beforehand to preserve it.

**Success Rate:** 90

**Issues Found:** 6

**Major Blockers:** One high-severity blocker: htmlsnapshot capture permanently fails with 'ReferenceError: __pulsar_utils__ is not defined' on every tab after a tab-new, until the session is closed (close + reopen recovered it). All other steps completed on first or second attempt.

**Most Confusing Aspects:** For a first-time user: (1) the raw __pulsar_utils__ ReferenceError with no recovery guidance after tab-new; (2) a valid X-SQL file starting with a '--' comment fails with a misleading 500 'Only select statements are supported'; (3) htmlsnapshot inspect on a product detail page returns selectors for the 'Customers also viewed' rail instead of the product title/price/image; (4) snapshot -i output includes far more than interactive elements despite its name; (5) bare state-save writes into the current working directory.

**Most Valuable Improvements:** Fix the capture-after-tab-new self-heal so it degrades to plain outerHTML or retries injection instead of surfacing a ReferenceError; strip '--' comments in APISQLUtils before the SELECT guard; make export --clean actually clean; implement inspect's documented container-structure fallback for detail pages.

**Usability Rating:** 6/10

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

#### Issue 1: htmlsnapshot capture fails with __pulsar_utils__ ReferenceError on every tab after tab-new until the session is closed

1) ./b4w.ps1 goto http://localhost:18080/ec/dp/B0E000001 2) ./b4w.ps1 htmlsnapshot  (works) 3) ./b4w.ps1 tab-new 'http://localhost:18080/ec/b?node=1292115012' 4) ./b4w.ps1 htmlsnapshot  → ERROR: html_snapshot_capture failed: ReferenceError: __pulsar_utils__ is not defined at <anonymous>:1:1 5) ./b4w.ps1 reload then ./b4w.ps1 htmlsnapshot, or tab-select back to the original tab then ./b4w.ps1 htmlsnapshot → same error every time.

#### Issue 2: X-SQL query file starting with a SQL comment line fails with misleading 500 'Only select statements are supported'

Write a .sql file whose first line is a comment, then run it: printf '%s\n' '-- Extract product fields' 'SELECT DOM_FIRST_TEXT(DOM, ''h1'') AS t FROM DOM_LOAD_AND_SELECT(@url, '':root'')' > .test-sessions/q.sql; ./b4w.ps1 htmlsnapshot query --sql '@.test-sessions/q.sql' → HTTP 500, message 'Only select statements are supported', resultSet null. Removing the comment line makes the identical query succeed.

#### Issue 3: htmlsnapshot export --clean is a silent no-op: output byte-identical to a non-clean export

1) ./b4w.ps1 goto a page with inline <style>/<script> (e.g. http://localhost:18080/ec/dp/B0E000001) 2) ./b4w.ps1 htmlsnapshot 3) ./b4w.ps1 htmlsnapshot export --file .test-sessions/clean.html --clean 4) ./b4w.ps1 htmlsnapshot export --file .test-sessions/raw.html 5) diff the two files → identical (15750 bytes both), still containing <style>, the injected <script id=PulsarScriptSection>, and vi= attributes.

#### Issue 4: htmlsnapshot inspect on a detail page surfaces only the 'recommendations' side rail and never the documented container-structure fallback, so main product selectors are undiscoverable via inspect

1) ./b4w.ps1 goto http://localhost:18080/ec/dp/B0E000001 2) ./b4w.ps1 htmlsnapshot 3) ./b4w.ps1 htmlsnapshot inspect --max 3 --depth 2 → returns only '.recommendation-card' selectors (Customers also viewed rail). 4) ./b4w.ps1 htmlsnapshot inspect '#product-page' → auto-narrows to '#product-page span.info-pill', never listing the container's children (#productTitle, #product-price, .description, #product-image).

#### Issue 5: state-save with no filename writes a timestamped JSON dump into the current working directory (repo root in dev), unlike other capture outputs

From the repo root: ./b4w.ps1 state-save → prints 'Storage state saved: D:/workspace/Browser4/Browser4-4.13/storage-state-2026-09-05T17-00-30-800Z.json' — the file lands in the repository root, next to source files. (Passing an explicit path, e.g. state-save .test-sessions/state.json, works correctly.)

#### Issue 6: 'Interactive' snapshot mode (-i) name implies an interactive-only filter; top-level help does not explain the flag, and output can look like a full-page dump

1) ./b4w.ps1 help  (top-level list only says 'snapshot Capture page snapshot to obtain element refs. See flags below...' — no -i semantics) 2) ./b4w.ps1 goto http://localhost:18080/ec/ 3) ./b4w.ps1 snapshot -i → the YAML contains headings, paragraphs, generic containers and banner/footer nodes in addition to links/buttons; a user expecting 'only clickable elements (product links, search, navigation)' must page through the whole aggregated tree.



---

## Processing Log (2026-09-07)

Handled per Human Review decisions. All CLI changes verified with `cargo test --bin browser4-cli` (1164+ tests); Kotlin changes verified with module compiles and `CrawlServiceTest` / `InspectDocumentTest` / `InferenceEngineBookkeepingStripTest` / `ExtractResultEnvelopeTest`.

| Issue | Decision | Resolution |
|---|---|---|
| 1 — htmlsnapshot capture fails with `__pulsar_utils__` ReferenceError after tab-new (High) | ACCEPT | Root cause confirmed in the live-capture path; a self-heal already exists in HEAD (`ensurePulsarUtilsInjected` best-effort in `captureLiveDocumentSnapshot`, plus e2e regression `HtmlSnapshotScenariosE2ETest.test1e_captureSurvivesTabNewAndTabSelectBack`). The reported run predates the live-DOM capture rework (commit 4ef3c78c77) — re-verify on current sources with a fresh session and a tab-new target; if the archival fallback (`pulsarSession.capture`) still surfaces the raw ReferenceError, gate it behind a re-injection retry as suggested. |
| 2 — X-SQL comment-line 500 (Medium) | ACCEPT | Fixed: `APISQLUtils.sanitize()` now strips full-line `--` comments BEFORE the select-statement guard (leading-comment files execute; comment-carried `;` no longer trips the single-statement check). |
| 3 — `htmlsnapshot export --clean` no-op (Medium) | ACCEPT | Fixed: `HTMLSnapshotToolExecutor.export()` re-parses the serialized HTML into a fresh jsoup tree, cleans THAT tree and serializes it — the mutations now reach the output instead of being lost in a cached `FeaturedDocument.outerHtml`. |
| 4 — inspect on detail pages (Medium) | ACCEPT | Partially addressed: CLI already prints no-recurring-pattern guidance steering to `summary`/explicit selectors; the engine-level single-container fallback that lists a container's children still needs `inspectDocument` work (tracked; do not silently extract the recommendations rail — the guidance text now says so). |
| 5 — state-save default path (Low) | ACCEPT | Fixed: bare `state-save` now writes to the CLI snapshot directory (`.browser4-cli/snapshot/storage-state-<ts>.json`) like other capture outputs; help text and storage-state.md updated. |
| 6 — `snapshot -i` top-level help (Low) | ACCEPT | Fixed: quick-start and quick-reference help now explain `-i` is an interactive-oriented layout, not an interactive-only filter. |