# Issues: advanced-mouse-interaction

> **Source:** `20260905-164437-advanced-mouse-interaction.full.md` | **Date:** 20260905-164437 | **Mode:** dev

## Scenario Background

### Task

**All 13 scenario steps completed successfully** against `http://localhost:18080/generated/interactive-5.html` ("Advanced Interaction Playground"), verified with a mix of CLI snapshot/eval output, DOM state checks, and the fixture's own interaction log. The backend (auto-started by `./b4w.ps1`) and MockSite (started via `./bin/test.ps1 mock-site`) ran from the local source tree.

| Step | Result | Verification |
|---|---|---|
| 1. Goto fixture | ✅ | Title "Advanced Interaction Playground", auto-snapshot saved |
| 2. Interactive snapshot | ✅ | `snapshot -i -v 0/1` revealed tooltip terms (e22/e25 area), 2 hover cards, sortable list (4 items), dblclick zone + reset zone, 4 dialog buttons, log |
| 3. Hover tooltips | ✅ (with caveat) | CSS eval: a11y tooltip `visibility:visible` after hover; DOM Snapshot tooltip after second hover. Snapshot-based verification proved unreliable (see Issue 2) |
| 4. Hover product card | ✅ | `.card-detail` max-height 0→100px, clientHeight 53; AX box `[box=…,748,0]` → `[box=…,748,53]` |
| 5. Drag reorder | ✅ (misreported as failure) | Order became [Medium, Low, Backlog, **High**]; log shows full DRAG START/END — but the command printed `ERROR: browser_drag failed` (Issue 1) |
| 6. Dblclick activate | ✅ | Status → "Status: ACTIVATED ✅", dbl counter 0→1, log entry |
| 7. Dblclick reset | ✅ | Counters → 0/0, Status → idle, log entry |
| 8. generate-locator | ✅ | `generate-locator e306` → `#alertBtn` |
| 9. get text w/ selector | ✅ | `get text "#alertBtn"` → "🔔 Show Alert" (untrimmed, Issue 6) |
| 10. Alert + dialog-accept | ✅ | Result area: "[alert] User dismissed the alert dialog."; click stalled 120s (Issue 3) |
| 11. Confirm + dialog-dismiss | ✅ | Result: "[confirm] User clicked Cancel / declined." (backgrounded click completed instantly) |
| 12. Prompt + accept w/ input | ✅ | Result: `[prompt] User entered: "Browser4 usability test"` |
| 13. Final screenshot | ✅ | Full-page PNG saved (1920×2112) showing final state incl. interaction log |

Final state dump (eval): list order `[🟡 Medium, 🟢 Low, ⚪ Backlog, 🔴 High]`, counters 0/0, status idle, dialog result shows the prompt outcome, and the log records drag/dblclick/reset/alert/confirm/prompt entries. Full evidence (snapshots, scripts, outputs, screenshot) is in `../../../../.test-sessions`.

### Execution Context

**Setup:** Verified pwd = repo root; created `../../../../.test-sessions`. MockSite was not running (curl 000), so I launched `pwsh ./bin/test.ps1 mock-site` (moved to background after 120s — it runs a Maven preflight install + `spring-boot:run`; port 18080 came up ~6 min later). Read `../../../../skills/browser4-cli/SKILL.md` fully and `./b4w.ps1 help` (294 lines saved to `../../../../.test-sessions/help-output.txt`), plus `help snapshot`/`help screenshot`. Then `./b4w.ps1 goto` auto-started the daemon + backend (20.6s, JVM→Spring Boot→MCP tools), which printed a **version-mismatch warning**: the local runtime bundle is built from 4.13.13-SNAPSHOT while checked-out sources are 4.13.14-SNAPSHOT — the older backend was served.

**Steps 2–4:** `snapshot -i -v 0/1 --stdout` (page = 2 viewports) gave all refs. Hovered e22 → eva...

(truncated — see full.md for complete trace)

---

## Issues Found (8 issues)

### Issue 1: drag command reports a hard failure after the drag actually succeeded (state changed, full lifecycle logged)

**Severity:** High
**Category:** Reliability

#### Reproduction

On the fixture page: ./b4w.ps1 goto http://localhost:18080/generated/interactive-5.html, then snapshot -i, then ./b4w.ps1 drag e288 e291 (drag 'High Priority' list item onto the 'Backlog' item). Command prints: ERROR: browser_drag failed: Failed to drag 'backend:288' to 'backend:291': Target element is occluded or moved: the resolved point is covered by another element. Yet the list has reordered to [Medium, Low, Backlog, High] and the page interaction log contains a complete 'DRAG START ... DRAG END: High Priority → new position' pair.

#### Expected Behavior

A successful drag should exit 0 and report success; a genuinely failed drag should leave the page unchanged. The user should never be told a drag failed when it visibly succeeded (a retry would double-drag).

#### Actual Behavior

The tool call reported failure with a nonzero/error result while the DOM and the fixture log prove the full drag lifecycle (dragstart→dragenter→dragover→drop→dragend) completed and the item moved to the list bottom. Backend log (pulsar.log) shows one 'Calling tool: browser_drag' with the completed lifecycle, then ~0.6s later 'Error executing expression: tab.drag(...) - Failed to drag ... occluded or moved'.

#### Root Cause Analysis

The driver's occlusion pre-check runs inside the injected script before any event dispatch, so a failing run cannot reorder the page — therefore a first attempt must have completed the drag (full lifecycle logged) and a retry (driver repeat(3) catches ChromeDriverException, or an executor-level retry) re-ran the sequence afterwards. By then the source element occupied the old target point (it had been appended to the bottom of the list), so the second run's elementFromPoint hit-test correctly found 'another element' (the source itself) and surfaced the error while the first attempt's side effects stayed. The retry-after-success path is not idempotent in practice despite the code comment claiming it is ('all failures are reported before any event is dispatched, so a retry re-runs the sequence idempotently'). Which layer triggered the retry and why attempt 1 raised a transient CDP error after dispatching needs a log-level trace.

#### Code Pointer

`browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/Browser4WebDriver.kt:drag (and buildDragSequenceScript occlusion pre-check); retry wrapper: browser4-agentic AbstractToolExecutor (see 'Error executing expression' WARN in pulsar.log)`

#### AI Suggested Improvement

- Treat an occlusion failure on a re-run within the same drag() invocation as success when the previous attempt already completed the event lifecycle (or verify by measuring whether the source node moved before throwing).
- Make retries safe: if attempt N-1 dispatched the lifecycle, skip re-dispatch and report success (optionally with a warning on stderr).
- Post-drag verification: compare source/target geometry before and after; report the drag outcome based on actual DOM change rather than only the script's ok flag.
- When an occlusion error is genuine, print the offending element (tag/id) that covers the target point instead of a generic message.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Highest priority of the set — reporting failure on a succeeded drag actively induces a double-drag on retry, which corrupts state the user can't see. The proposed fix (verify actual DOM change / whether the lifecycle already dispatched before throwing or re-dispatching) is the right shape; treat "occlusion failure after a completed lifecycle on an earlier attempt" as success.

---

### Issue 2: Tooltip (CSS :hover) visibility cannot be reliably verified via snapshots: hidden tooltip text appears in snapshot grep output, while the visible tooltip never appears as a node in viewport snapshots

**Severity:** Medium
**Category:** Reliability

#### Reproduction

After a fresh ./b4w.ps1 reload, with the mouse nowhere near the terms and both tooltip spans CSS-hidden (visibility:hidden), run ./b4w.ps1 snapshot grep 'hierarchical representation|static capture' — it matches and prints both tooltip texts merged into the paragraph/term names (e.g. generic 'Accessibility Tree A hierarchical representation of the page ...'). Then hover a tooltip term and run snapshot -i -v 0 --stdout: the now-visible tooltip content appears nowhere (no node, no ref, no text). getComputedStyle via eval confirms the tooltip really is visible at that moment.

#### Expected Behavior

CSS-hidden content should not appear in any snapshot output; a hovered-visible tooltip should be discoverable in a viewport snapshot (own node/ref, or at least a box/geometry change) so 'verify the tooltip appeared' works with snapshot commands as SKILL.md's mouse-interaction pattern suggests.

#### Actual Behavior

Full-page/aggregated renderings (snapshot grep and the auto-saved snapshot YAML files) include visibility:hidden descendant text regardless of hover state, so the grep matches both pre-hover (false positive) and post-hover. Viewport renderings (snapshot -v 0, -i -v 0) omit the tooltip node entirely even when it is visible (the only observable difference between the two snapshot modes was the card-detail box geometry, which is a different CSS mechanism). A user/agent following the documented 'hover <ref> then snapshot grep <text>' pattern gets a match that proves nothing about visibility.

#### Root Cause Analysis

The full-page aggregation path builds element names from descendant text that ignores CSS visibility (DOM-text based), while the viewport path serializes per-node AX names and prunes hidden/ignored nodes — the two paths disagree about hidden content, and neither exposes the rendered visibility state of :hover-revealed nodes. Exact location of the divergence (browser4-rest snapshot executor vs driver/upstream AX serializer) requires code investigation: compare the snapshot capture path used for viewport-filtered vs full-page requests.

#### AI Suggested Improvement

- Make the text aggregation visibility-aware (innerText-like semantics) so visibility:hidden content never leaks into names/grep matches.
- Include hover/visibility state or geometry in serialized nodes so a tooltip that becomes visible is detectable in viewport snapshots.
- Update SKILL.md's hover-verification pattern: verify with eval (getComputedStyle visibility/opacity) or geometry deltas instead of snapshot grep.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Real divergence between the aggregation path (DOM-text based, leaks `visibility:hidden` content) and the viewport path (AX-pruned, drops hover-revealed nodes), and it silently breaks the documented hover-verification pattern. Prefer visibility-aware pruning during the AX walk over blanket innerText semantics (costly, and still won't surface a *visible* tooltip node) — the geometry/visibility-state signal in serialized nodes is what actually enables snapshot-based verification. Complement Issue 5's fix when re-testing.
> **Tracking (2026-09-07):** Filed upstream as [platonai/Browser4base#4](https://github.com/platonai/Browser4base/issues/4) (snapshot serialization is not CSS-visibility-aware; `Labels: bug`). Root cause was traced to the pulsar-side AX serializer, which is unreachable from this repo — the code fix belongs in Browser4base; this repo's interim action (SKILL.md hover-verification via eval/geometry) is documented in the linked issue.

---

### Issue 3: Documented two-step dialog flow (click → dialog-accept in a separate invocation) stalls the CLI for the full 120s HTTP timeout per dialog

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 click e306 (Show Alert button) per SKILL.md §Dialog Handling, then in a second invocation ./b4w.ps1 dialog-accept. The click command blocks for ~120s and finally prints 'HTTP request timed out [tool=browser_click, endpoint=.../mcp/call-tool, timeout=120s ...]'. When dialog-accept was issued ~1s before the deadline, the click still reported a timeout although the server-side action completed (result area updated to '[alert] User dismissed the alert dialog.').

#### Expected Behavior

The two-step flow should be practical: the triggering click should either return promptly with a 'dialog is open — run dialog-accept' state, or the docs should warn that the click blocks until the dialog is handled and point to a non-blocking alternative.

#### Actual Behavior

A synchronous user or agent following the documented pattern cannot run dialog-accept until the click invocation ends, so each alert/confirm/prompt costs a full 120s stall (or Ctrl+C). The click command even races the timeout: it reported failure although the dialog had just been dismissed and the page updated. The alternative --auto-dismiss-dialogs exists but only auto-accepts — it cannot be used to test the dismiss path of confirm/prompt. (Workaround used here: run the click as a background shell job, then dialog-dismiss/accept ~5s later; the click then completes successfully within seconds.)

#### Root Cause Analysis

The backend click RPC blocks until the JS dialog is dismissed (Chrome blocks the page main thread), and the CLI's default HTTP timeout (120s) is the only bound; nothing detects Page.javascriptDialogOpening to return early. SKILL.md documents the two-step flow but never states that the first step blocks until the second step is performed.

#### Code Pointer

`cli/browser4-cli (dialog command implementation and SKILL.md dialog section); backend dialog state handling in browser4-rest MCPToolController / driver dialog listener`

#### AI Suggested Improvement

- Detect the dialog-open event and make the click return promptly with an explicit 'dialog pending, run dialog-accept/dialog-dismiss' message instead of blocking to the HTTP timeout.
- In SKILL.md, present --auto-dismiss-dialogs as the primary one-step option and add a warning: 'click blocks until the dialog is handled; run the click and dialog-accept in separate terminals/background, or the click will time out after N seconds'.
- Add a --dialog-timeout option (default well below 120s) so failed dialog-trigger clicks fail fast.
- If the click times out but the page later updates, print the existing 'may have succeeded' note (it does) and suggest running dialog-accept before retrying.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] A 120s stall per dialog makes the documented two-step flow effectively unusable for synchronous agents, and the timeout race produces a false failure even when the flow completed. Early return on `Page.javascriptDialogOpening` with an explicit "dialog pending — run dialog-accept/dismiss" message is the correct fix; in the interim the docs must state that the click blocks.

---

### Issue 4: drag offers no drop-position control: placement on live-reorder lists is probabilistic (±2px jitter around the target element's center decides before/after insertion)

**Severity:** Medium
**Category:** Product

#### Reproduction

On the fixture's sortable list: ./b4w.ps1 drag e288 e291 (drag 'High Priority' onto 'Backlog'). The task intent 'put High at the bottom' only succeeds when the single synthesized dragover lands below Backlog's vertical center; the driver jitters the target point by ±2px, so repeated attempts flip between 'High last' (appendChild) and 'High before Backlog' (3rd of 4). Dragging 'Backlog' onto 'High' similarly flips between 'Backlog first' and 'Backlog second'. Order must be re-checked and the drag retried until it lands as intended.

#### Expected Behavior

Either deterministic insertion semantics (documented: 'dropping on element X inserts at X's position') or a way to target an explicit drop position (top/bottom of a container, or a point offset) so 'move item to the bottom of the list' is expressible and repeatable.

#### Actual Behavior

The driver dispatches exactly one dragover event at (targetCenterX±2, targetCenterY±2) (Browser4WebDriver.buildDragSequenceScript + randomOffset(2.0)); the page's reorder logic (getDragAfterElement) converts that pointer Y into an insert-before/append decision, so an exact-center hit is a coin flip. There is no drag-to-container-edge, drop-point, or position argument in the CLI. The step-5 goal was achieved only because the misreported retry append landed 'High' last by chance, then was verified.

#### Root Cause Analysis

Single-dragover-at-center drag simulation with randomized ±2px jitter is a poor match for live-reorder list handlers that decide placement from pointer Y relative to item centers; and the drag API models only 'element onto element', not 'element to list position'. SortableJS/react-dnd-style libraries are additionally unsupported (isTrusted=false), noted in the driver comments but not in user-facing docs.

#### Code Pointer

`browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/Browser4WebDriver.kt:buildDragSequenceScript / randomOffset; CLI drag command in cli/browser4-cli`

#### AI Suggested Improvement

- Dispatch a short chain of dragover events sweeping from the source position to the target (like a real mouse drag) so live-reorder handlers converge deterministically instead of deciding on one center-point event.
- Support position-aware drag targets (e.g. drag <src> <container> --at bottom|top or a client-point offset), and document exactly where the drop lands.
- After any drag, evaluate the source's DOM position and include the resulting order/position in the command output.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Same code region as Issue 1 but an independent defect: ±2px center jitter making insert-before/append a coin flip means "move to bottom" is not expressible or repeatable — the deeper gap is the API modeling only element-onto-element. The dragover sweep helps, but position-aware targets (`--at top|bottom`/point offset) plus reporting the resulting DOM position are what make the intent deterministic.

---

### Issue 5: CSS :hover state set by hover persists across later interactions — clicks elsewhere do not clear it, so hover-only content stays 'open' indefinitely

**Severity:** Medium
**Category:** Reliability

#### Reproduction

On the fixture: hover the product card (hover e276) — card detail expands. Then perform several unrelated clicks/dblclicks/dialog actions at other coordinates (e.g. click e306/e307/e308 in the dialog section ~1100px below). A final eval of getComputedStyle/#productCard .card-detail.clientHeight still returns > 0 and the full-page screenshot shows the detail text expanded, i.e. the card is still :hovered although the pointer logically moved away long ago.

#### Expected Behavior

After the mouse is used elsewhere (click/dblclick coordinates outside the card), CSS :hover on the card should clear and hover-revealed content should collapse, matching real mouse behavior.

#### Actual Behavior

The hover effect persists for the rest of the session: click/dblclick/drag actions dispatch press/release at new coordinates without a preceding mouseMoved, so Chrome's hover state never updates; there is no documented 'clear hover / move mouse to neutral point' primitive (mousemove <x> <y> exists but is not mentioned in the mouse-interaction docs). Visual assertions/screenshots taken later can show stale hover content.

#### Root Cause Analysis

hover dispatches a mouse move to the element, but later interactions don't move the mouse first; the browser retains the last hover target. Driver-side hover-state management is missing (no implicit mouse reset between interactions).

#### Code Pointer

`browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/Browser4WebDriver.kt (hover/click/dblclick mouse event dispatch)`

#### AI Suggested Improvement

- Dispatch a mouseMoved to the target coordinates before click/dblclick press events so :hover reflects the actual interaction point.
- Add an explicit documented command/pattern to clear hover (e.g. mousemove to a neutral area like 0,0) and mention it in SKILL.md's mouse-interaction section.
- Optionally reset hover state after each interaction command that doesn't end in a hover.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Stale CSS :hover corrupting later screenshots/assertions stems from click/dblclick dispatching press/release without a preceding mouseMoved, so the browser never updates the hover target. Dispatching mouseMoved to the interaction point before press is a small, safe change; add and document the neutral-point reset. Note this fixes the *driver* side while Issue 2 is the *serializer* side of the same :hover problem — do both before re-validating tooltip flows.

---

### Issue 6: Dev mode serves a stale cached backend bundle when the checkout version no longer matches, requiring a manual rebuild

**Severity:** Low
**Category:** Product

#### Reproduction

With checked-out sources at 4.13.14-SNAPSHOT and a previously built local runtime bundle from 4.13.13-SNAPSHOT, run ./b4w.ps1 goto <url>. Output: '⚠ the existing local Browser4 runtime bundle was built from 4.13.13-SNAPSHOT sources, but the checked-out sources are 4.13.14-SNAPSHOT. ⚠ Serving the OLD backend build — behaviour may not match the checked-out code. To rebuild from source, run: powershell ... build-runtime-bundle.ps1' — then it starts the old bundle anyway.

#### Expected Behavior

In dev mode (source tree), the backend under test should match the checked-out sources, or the launcher should rebuild/refresh automatically (or offer to) when the bundle version is older than the checkout.

#### Actual Behavior

The evaluation backend silently (after a warning) runs 4.13.13-SNAPSHOT code while the tree is 4.13.14-SNAPSHOT; users must notice the warning and manually invoke a PowerShell build script (or set BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1) to test the code they actually have checked out — easy to miss for a new user who expects 'auto-starts the locally-built backend' to mean current sources.

#### Root Cause Analysis

The dev launcher intentionally reuses the cached runtime bundle for speed and only warns on version skew; nothing in the auto-start path rebuilds when the checkout is newer.

#### Code Pointer

`b4w.ps1 / cli daemon bundle-start logic (warning text printed by the CLI on first launch)`

#### AI Suggested Improvement

- On version skew, auto-rebuild the bundle once (or prompt the user) instead of proceeding with the stale backend in dev mode.
- Surface the skew in `browser4-cli status` output for every command, not only the first launch banner.
- Document in SKILL.md/CLAUDE.md that after a version bump in the source tree the runtime bundle must be rebuilt before dev-mode testing reflects current code.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] The stale-bundle reuse is a deliberate launcher speed tradeoff, already surfaces a warning, and has a documented override (`BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1`); dev-only with no wrong-result corruption. If picked up later, the cheap win is surfacing the skew in `status` output per invocation, not auto-rebuilding (a surprise multi-minute rebuild on every command is its own UX regression).

---

### Issue 7: get text returns raw textContent with HTML-formatting whitespace, so element labels come back indented/padded

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 generate-locator e306 → '#alertBtn'; ./b4w.ps1 get text '#alertBtn' → output '            🔔 Show Alert' (12 leading spaces + trailing newline), because the button's textContent includes the pretty-printed HTML whitespace/newlines around the label.

#### Expected Behavior

The button's readable label '🔔 Show Alert' — matching what snapshot shows in the AX name — trimmed of formatting whitespace.

#### Actual Behavior

The command returns textContent verbatim ('\n            🔔 Show Alert\n        '), forcing the user/agent to trim when comparing against snapshot labels or expected strings.

#### Root Cause Analysis

get text (live-DOM text extraction) returns the raw textContent property; unlike the AX name computation (which normalizes whitespace), no trimming/normalization is applied.

#### Code Pointer

`CLI/backend get-text executor for live DOM reads (cli/browser4-cli get command → browser4-rest/browser driver text extraction)`

#### AI Suggested Improvement

- Trim/normalize whitespace in text extraction output, or offer --raw for the verbatim textContent.
- Document that get text returns raw textContent while snapshot names are whitespace-normalized, so users aren't surprised by label mismatches.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Small, well-scoped fix: live-DOM text extraction should normalize whitespace to match AX-name semantics (trimmed label), with `--raw` preserving verbatim textContent for consumers that need it. Check downstream users of the raw value (locator matching, comparisons) before changing the default.

---

### Issue 8: Main help text for snapshot says 'See flags below for filtering, scoping, and output options' but no flags are shown anywhere in the main help output

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 help (or capture to file) and inspect the snapshot entry: '★ snapshot  Capture page snapshot to obtain element refs. See flags below for filtering, scoping, and output options.' — grep the whole help output for --viewport/--interactive/--auto-diff: zero matches. The flags only appear in ./b4w.ps1 help snapshot.

#### Expected Behavior

Either include the key snapshot flags (-v, -i, --auto-diff, --stdout) inline in the main help entry, or reword the pointer to 'See: help snapshot'.

#### Actual Behavior

A first-time user reading the main help is pointed 'below' for flags that are not there; they must know to run the subcommand help to find -i/-v/--auto-diff (the exact flags the docs' quick patterns rely on).

#### Root Cause Analysis

The main help generator appends the generic 'See flags below' sentence to the snapshot description without a following flags block (flags are only in the per-command help view).

#### Code Pointer

`cli/browser4-cli help rendering (main help text for snapshot)`

#### AI Suggested Improvement

- Add the flags inline to the main-help snapshot entry (or a compact flag summary line), since snapshot is a starred high-frequency command.
- Otherwise change the wording to 'Run help snapshot for filtering, scoping, and output options'.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Verified — the description at commands.rs:1494 promises flags "below" that only exist in the subcommand help, and snapshot is a starred command whose flags (-i/-v/--auto-diff) the docs' quick patterns depend on. Cheapest correct fix is rewording to "Run: help snapshot"; inline flags are nicer but must stay in sync with the subcommand help.

---

## Overall Assessment

**Completion Status:** Successful — all 13 scenario steps were completed and verified on the interactive-5.html fixture (tooltips triggered, hover card expanded, priority list reordered with High Priority at the bottom, dblclick activate/reset verified, generate-locator produced '#alertBtn', alert/confirm/prompt handled with verified result-area updates, final full-page screenshot captured). Several steps required extra verification tooling or workarounds due to the issues above.

**Success Rate:** 90% — all task steps succeeded, but 3 steps required non-obvious workarounds (CSS eval for tooltip verification, backgrounded clicks to avoid 120s dialog stalls, verify-and-retry for drag placement) and one step (drag) succeeded while the tool falsely reported failure, which a first-time user would likely treat as a blocker.

**Issues Found:** 8

**Major Blockers:** None that prevented completion. Closest to a blocker: the drag command falsely reporting failure while mutating the page (Issue 1), and the 120s per-dialog stall when following the documented two-step dialog pattern (Issue 3).

**Most Confusing Aspects:** For a first-time user: (1) being told a drag failed when the page clearly changed — and being unsure whether to retry; (2) snapshot grep matching tooltip text that is actually hidden, making 'is the tooltip showing?' unanswerable from snapshots; (3) the click command hanging for 2 minutes on dialog-trigger buttons while the docs present click + dialog-accept as a routine flow; (4) 'See flags below' in main help pointing at flags that aren't there.

**Most Valuable Improvements:** Fix drag's false-failure reporting and make retries idempotent (Issue 1); make snapshot rendering visibility-aware so hidden CSS content can't masquerade as visible (Issue 2); detect open dialogs and return early instead of blocking the click for 120s (Issue 3); document and support deterministic drag placement (Issue 4).

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

#### Issue 1: drag command reports a hard failure after the drag actually succeeded (state changed, full lifecycle logged)

On the fixture page: ./b4w.ps1 goto http://localhost:18080/generated/interactive-5.html, then snapshot -i, then ./b4w.ps1 drag e288 e291 (drag 'High Priority' list item onto the 'Backlog' item). Command prints: ERROR: browser_drag failed: Failed to drag 'backend:288' to 'backend:291': Target element is occluded or moved: the resolved point is covered by another element. Yet the list has reordered to [Medium, Low, Backlog, High] and the page interaction log contains a complete 'DRAG START ... DRAG END: High Priority → new position' pair.

#### Issue 2: Tooltip (CSS :hover) visibility cannot be reliably verified via snapshots: hidden tooltip text appears in snapshot grep output, while the visible tooltip never appears as a node in viewport snapshots

After a fresh ./b4w.ps1 reload, with the mouse nowhere near the terms and both tooltip spans CSS-hidden (visibility:hidden), run ./b4w.ps1 snapshot grep 'hierarchical representation|static capture' — it matches and prints both tooltip texts merged into the paragraph/term names (e.g. generic 'Accessibility Tree A hierarchical representation of the page ...'). Then hover a tooltip term and run snapshot -i -v 0 --stdout: the now-visible tooltip content appears nowhere (no node, no ref, no text). getComputedStyle via eval confirms the tooltip really is visible at that moment.

#### Issue 3: Documented two-step dialog flow (click → dialog-accept in a separate invocation) stalls the CLI for the full 120s HTTP timeout per dialog

./b4w.ps1 click e306 (Show Alert button) per SKILL.md §Dialog Handling, then in a second invocation ./b4w.ps1 dialog-accept. The click command blocks for ~120s and finally prints 'HTTP request timed out [tool=browser_click, endpoint=.../mcp/call-tool, timeout=120s ...]'. When dialog-accept was issued ~1s before the deadline, the click still reported a timeout although the server-side action completed (result area updated to '[alert] User dismissed the alert dialog.').

#### Issue 4: drag offers no drop-position control: placement on live-reorder lists is probabilistic (±2px jitter around the target element's center decides before/after insertion)

On the fixture's sortable list: ./b4w.ps1 drag e288 e291 (drag 'High Priority' onto 'Backlog'). The task intent 'put High at the bottom' only succeeds when the single synthesized dragover lands below Backlog's vertical center; the driver jitters the target point by ±2px, so repeated attempts flip between 'High last' (appendChild) and 'High before Backlog' (3rd of 4). Dragging 'Backlog' onto 'High' similarly flips between 'Backlog first' and 'Backlog second'. Order must be re-checked and the drag retried until it lands as intended.

#### Issue 5: CSS :hover state set by hover persists across later interactions — clicks elsewhere do not clear it, so hover-only content stays 'open' indefinitely

On the fixture: hover the product card (hover e276) — card detail expands. Then perform several unrelated clicks/dblclicks/dialog actions at other coordinates (e.g. click e306/e307/e308 in the dialog section ~1100px below). A final eval of getComputedStyle/#productCard .card-detail.clientHeight still returns > 0 and the full-page screenshot shows the detail text expanded, i.e. the card is still :hovered although the pointer logically moved away long ago.

#### Issue 6: Dev mode serves a stale cached backend bundle when the checkout version no longer matches, requiring a manual rebuild

With checked-out sources at 4.13.14-SNAPSHOT and a previously built local runtime bundle from 4.13.13-SNAPSHOT, run ./b4w.ps1 goto <url>. Output: '⚠ the existing local Browser4 runtime bundle was built from 4.13.13-SNAPSHOT sources, but the checked-out sources are 4.13.14-SNAPSHOT. ⚠ Serving the OLD backend build — behaviour may not match the checked-out code. To rebuild from source, run: powershell ... build-runtime-bundle.ps1' — then it starts the old bundle anyway.

#### Issue 7: get text returns raw textContent with HTML-formatting whitespace, so element labels come back indented/padded

./b4w.ps1 generate-locator e306 → '#alertBtn'; ./b4w.ps1 get text '#alertBtn' → output '            🔔 Show Alert' (12 leading spaces + trailing newline), because the button's textContent includes the pretty-printed HTML whitespace/newlines around the label.

#### Issue 8: Main help text for snapshot says 'See flags below for filtering, scoping, and output options' but no flags are shown anywhere in the main help output

./b4w.ps1 help (or capture to file) and inspect the snapshot entry: '★ snapshot  Capture page snapshot to obtain element refs. See flags below for filtering, scoping, and output options.' — grep the whole help output for --viewport/--interactive/--auto-diff: zero matches. The flags only appear in ./b4w.ps1 help snapshot.

