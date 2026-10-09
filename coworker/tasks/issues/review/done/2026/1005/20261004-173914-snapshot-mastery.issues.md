# Issues: snapshot-mastery

> **Source:** `20261004-173914-snapshot-mastery.full.md` | **Date:** 20261004-173914 | **Mode:** dev

## Scenario Background

### Task

All ten requested steps were executed against a live, locally-built Browser4 backend driving headless Chrome at `https://en.wikipedia.org/wiki/Christopher_Alexander`.

**What worked:** navigation, viewport snapshots, interactive-mode snapshots, depth-limited snapshots, URL-annotated snapshots, link clicking/navigation, auto-diff, the complete `snapshot grep` option matrix (`-i`, `-C`, `-v`, `-c`, `-F`, `-w`, `-e`), and `--stdout` output with pagination.

**What did not work as documented:**
- **Step 4 (scoped snapshot via CSS selector) failed functionally.** `snapshot --selector "<css>"` is silently ignored — I proved this end-to-end: `--selector "#mw-content-text"`, `--selector "#firstHeading"`, `--selector "#zzz-not-here-12345"` and no selector all returned **byte-identical** trees (exit 0, no warning). A direct MCP probe confirmed the *server* receives `selector` and drops it. Same for `snapshot grep --selector` / `--selector-all`. I completed the step's intent (main-content-only content) with the documented alternative `htmlsnapshot get text "#mw-content-text .mw-parser-output p"`, which honors CSS selectors correctly.
- **Step 6 (`-u` URLs) is a no-op** — `/url:` entries are always emitted; `-v 0` and `-v 0 -u` outputs are byte-identical.
- **Step 8 nuance:** `-v 0` is the *current screen only* (the article is 10 viewports; 9,571px hidden), not the full page — the full tree needs `-v all` or no `-v`. And `snapshot --auto-diff` after a click diffs against the click's own automatic post-command snapshot, not the pre-click page, so it shows same-page noise (41 added / 292 removed / 15 modified) rather than the navigation. The working pattern is `click <ref> --no-snapshot` → `snapshot -v 0 --auto-diff` (161/64/49, i.e. the actual page transition).
- **Step 9 nuance:** auto-diff markers are never persisted to any snapshot file, and `snapshot grep` searches a *live* full-page AX tree — so "grep the auto-diff output for changed elements" is not possible; grep always searches the current tree.

Two additional silent-failure bugs were found by reading the source: the promised `--depth` truncation warning is dead code (depth is stored as a JSON integer, read back as a string), and `--stdout` still writes snapshot files despite the docs saying it does not — which silently shifts the next `--auto-diff` baseline.

### Execution Context

**Environment:** repo root `D:/workspace/Browser4/Browser4-4.13`, wrapper `./b4w.ps1`, named session `-s wiki-eval` (deliberate: the unnamed DEFAULT session is a shared singleton). All evidence saved under `.test-sessions/20261004T1433038523034Z/`.

**Setup / discovery:** `help`, `help snapshot`, `help snapshot grep`, `help click`, plus `skills/browser4-cli/SKILL.md` and `references/snapshot.md`.

**Snapshot matrix (article page):**

| Command | Result |
|---|---|
| `snapshot -v 0 --stdout --all` | 511 lines; 10 viewports total, 9,571px hidden |
| `snapshot -i --stdout --all` | 478 lines; 0 heading/paragraph/listitem roles — h1 had ref `e205` in default mode but is an unaddressable `text:` line in `-i` |
| `snapshot --selector "#mw-content-text" --stdout --all` | 757 lines, **byte-identica...

(truncated — see full.md for complete trace)

---

## Issues Found (9 issues)

### Issue 1: snapshot --selector is silently ignored (scoping feature entirely non-functional)

**Severity:** High
**Category:** Product

#### Reproduction

cd D:/workspace/Browser4/Browser4-4.13
./b4w.ps1 -s wiki-eval goto "https://en.wikipedia.org/wiki/Christopher_Alexander"
./b4w.ps1 -s wiki-eval snapshot --selector "#mw-content-text" --stdout --all > scoped.txt
./b4w.ps1 -s wiki-eval snapshot --stdout --all > unscoped.txt
diff scoped.txt unscoped.txt   # identical
./b4w.ps1 -s wiki-eval snapshot --selector "#zzz-not-here-12345" -v 0 --stdout --all   # still exits 0 with the full tree
./b4w.ps1 -s wiki-eval snapshot grep --selector "#firstHeading" -c "ref=e"   # 488, same as without selector; --selector-all also 488
Direct server probe (isolates CLI from backend): POST http://localhost:18182/mcp/call-tool {"tool":"browser_snapshot","arguments":{"sessionId":"<id>","viewports":"0","selector":"#firstHeading"}} returns a byte-identical 26398-byte body to the call without "selector".

#### Expected Behavior

The AX tree is scoped to the matched subtree (keeping root-to-leaf ancestors for context, as documented), so --selector "#firstHeading" yields a handful of lines instead of the whole page; a non-existent selector either returns an empty/error result or prints a warning; exit code reflects the failure.

#### Actual Behavior

The full page tree is returned regardless of the selector; a selector matching nothing still exits 0 with the complete tree and no warning on stderr or stdout. snapshot grep --selector / --selector-all are equally inert (identical match counts for three different selectors).

#### Root Cause Analysis

The CLI maps the flag correctly (commands.rs tool_params_fn sets p["selector"]), and the server-side executor accepts it (BrowserTabToolExecutor.kt "ariaSnapshot" branch builds AriaSnapshotOptions(selector = ...) and calls driver.ariaSnapshot(options)), but the downstream AX-snapshot renderer ignores AriaSnapshotOptions.selector. That renderer lives in the ai.platon.pulsar.chrome.dom package of the external pulsar-browser artifact, which is not present in this checkout (only referenced via the browser4-browser dependency), so the ignore happens inside the dependency, not in this tree.

#### Code Pointer

`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/BrowserTabToolExecutor.kt (ariaSnapshot branch, ~line 1247-1274) — options are built but ignored downstream by the pulsar-browser snapshot renderer (ai.platon.pulsar.chrome.dom)`

#### AI Suggested Improvement

- Honor AriaSnapshotOptions.selector in the AX snapshot renderer: resolve the CSS selector, capture only the matched subtree, and include root-to-leaf ancestors for tree-path context (matching the documented contract).
- Add an explicit failure path: when a selector matches nothing, return an error or a non-zero result instead of silently returning the whole page; this must propagate to snapshot and snapshot grep.
- Wire the same selector handling into snapshot grep's scoped path so --selector/--selector-all actually narrow results.
- Add a regression test asserting the scoped tree is strictly smaller and contains the matched node (e.g. in browser4-tests/pulsar-e2e-tests .../SnapshotServiceE2ETest.kt).
- Update the CLI help if the feature is intentionally unsupported, and fail loudly rather than accepting a no-op flag.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: snapshot --stdout writes snapshot files to disk despite documenting the opposite, silently shifting the next --auto-diff baseline

**Severity:** Medium
**Category:** Documentation

#### Reproduction

ls -t .browser4-cli/snapshot/*.yml | head -1
./b4w.ps1 -s wiki-eval snapshot -v 0 --stdout --page-size 15 > /dev/null
ls -t .browser4-cli/snapshot/*.yml | head -1   # a NEW timestamped file appears
Provenance check: the file created by a --stdout run is what a later `snapshot -v 0 --auto-diff` reports as its baseline (e.g. "# Snapshot Diff: snapshot-2026-10-04T17-36-42-903Z.yml -> ...").

#### Expected Behavior

Per help snapshot ('Print snapshot content to stdout instead of saving to file'), snapshot.md and SKILL.md, --stdout should not create a file; only non-stdout captures (and interaction auto-snapshots) should be persisted.

#### Actual Behavior

Every --stdout capture also writes .browser4-cli/snapshot/snapshot-<ts>.yml. Because find_previous_snapshot() picks the most recent file, a read-only --stdout inspection silently becomes the baseline for the next --auto-diff, changing what 'what changed' means.

#### Root Cause Analysis

handle_snapshot() calls resolve_output_path(...) and save_snapshot(&out_path, ...) unconditionally at main.rs:5766/5796; the --stdout flag is only consulted later (~line 5811) to decide whether to also print the content. The write is not gated on the flag.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_snapshot() (resolve_output_path/save_snapshot at ~5766-5796)`

#### AI Suggested Improvement

- Skip save_snapshot() when the stdout/raw flag is set (still print to stdout), or clearly document that --stdout only *adds* stdout output on top of the file write.
- If persisting is intentional, exclude --stdout captures from find_previous_snapshot() so inspection runs cannot perturb the auto-diff baseline.
- Add a unit test that asserts no new file is created for a --stdout invocation (file-count before/after).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: snapshot -u/--urls is a no-op — link URLs are always included and cannot be toggled

**Severity:** Medium
**Category:** Documentation

#### Reproduction

./b4w.ps1 -s wiki-eval snapshot -v 0 --stdout --all > a.txt
./b4w.ps1 -s wiki-eval snapshot -v 0 -u --stdout --all > b.txt
diff a.txt b.txt   # identical (82 '/url:' lines each)
Direct server probe: browser_snapshot with urls:false vs urls:true vs no urls key -> byte-identical 26398-byte responses.

#### Expected Behavior

Without -u, links omit /url lines (or -u controls their inclusion in some documented way); with -u, hrefs are present. The help says 'Include href URLs for link elements'.

#### Actual Behavior

URL lines are present in every capture regardless of the flag, so the option has no observable effect and wastes user/agent effort (the task explicitly asked to capture a snapshot honoring URLs via this flag).

#### Root Cause Analysis

Same root cause as the selector issue: the executor passes AriaSnapshotOptions(urls = urls) to driver.ariaSnapshot(options), but the renderer ignores the urls field and always emits /url entries. CLI mapping is correct (commands.rs sets p["urls"] = true).

#### Code Pointer

`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/BrowserTabToolExecutor.kt (ariaSnapshot branch) and the pulsar-browser AX renderer consuming AriaSnapshotOptions`

#### AI Suggested Improvement

- Implement the urls toggle in the renderer (omit /url lines when false) or remove the flag and the option from the API/help to stop advertising a no-op.
- If URLs become opt-in, keep the default behavior documented (e.g. default true) so existing outputs do not silently change.
- Add a test comparing /url-line counts with urls on and off.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: Interactive-mode docs contradict observed behavior: -i strips refs from headings/paragraphs/list items (they do not 'all carry refs')

**Severity:** Medium
**Category:** Documentation

#### Reproduction

./b4w.ps1 -s wiki-eval snapshot --stdout --all > default.txt
./b4w.ps1 -s wiki-eval snapshot -i --stdout --all > interactive.txt
In default.txt: `- heading "Christopher Alexander" [level=1] [ref=e205]`
In interactive.txt: 0 lines matching 'heading ', 0 'paragraph ', 0 'listitem '; the h1 appears only as unaddressable `- text: ...` lines (0 of 101 text: lines carry refs).

#### Expected Behavior

Per snapshot.md: '-i is not a strict filter… any addressable element (headings, paragraphs, list items, generic <div> containers — they all carry refs) stays in the output.' A user should be able to rely on refs for those roles in -i mode.

#### Actual Behavior

Structural roles (heading/paragraph/listitem: 4/5/30 lines in default viewport 0) vanish as ref-bearing nodes in -i mode and become plain text lines without refs; the tree also shrinks materially (757 -> 478 lines full page). The mode is therefore partly interactive-only — the opposite of what the docs stress.

#### Root Cause Analysis

The -i renderer merges inner text into enclosing element names and, as a side effect, drops the role/ref lines of non-interactive structural nodes (rendering them as 'text:' content instead). The documentation was evidently written against a different renderer behavior than the one shipped in this build.

#### Code Pointer

`AX renderer in the pulsar-browser artifact (AriaSnapshotOptions.interactive), exercised via browser4-agentic/.../BrowserTabToolExecutor.kt; docs: skills/browser4-cli/references/snapshot.md (Interactive Mode)`

#### AI Suggested Improvement

- Decide the contract: either keep refs on structural elements in -i mode (preferred — refs are the reason to run snapshot), or update SKILL.md/snapshot.md to state that -i removes refs from headings, paragraphs and list items while adding them for links/buttons.
- Document that -i changes the role taxonomy (heading/paragraph/listitem -> text).
- Add a golden-file test capturing the -i role/ref contract so docs and output cannot drift again.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: Documented --depth truncation warning never fires (dead code: depth stored as integer, read as string)

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 -s wiki-eval snapshot -d 3 --stdout 2>err.txt | wc -l   # 28 lines vs 689-line full tree
cat err.txt   # only the ref-lifecycle tip, no depth warning

#### Expected Behavior

help snapshot states: 'A warning is printed on stderr when content has been truncated by the depth limit.' A user passing -d 3 should be told that elements deeper than 3 levels are omitted.

#### Actual Behavior

Nothing is printed about depth truncation, so the heavily-truncated output (28 of 689 lines; the whole article body is cut off) looks like the complete tree. The user cannot tell content was silently dropped.

#### Root Cause Analysis

commands.rs writes the parsed depth as a JSON number (p["depth"] = json!(n), n: i32), but main.rs computes depth_used with tool_params.get("depth").and_then(|v| v.as_str()).map_or(false, ...), which is always None for a number. depth_used is therefore permanently false, making the warning at line 5886 dead code (the same as_str() misuse also weakens the has_filter check at ~line 5828).

#### Code Pointer

`cli/browser4-cli/src/main.rs:5830 (depth_used) vs cli/browser4-cli/src/commands.rs:1566-1568 (p["depth"] = json!(n))`

#### AI Suggested Improvement

- Read depth tolerantly, e.g. tool_params.get("depth").and_then(|v| v.as_i64().or_else(|| v.as_str().and_then(|s| s.parse().ok()))), or store it as a string like viewports.
- Add a unit test that renders snapshot tool params with -d and asserts depth_used == true.
- Consider also warning when a depth-limited capture drops a large fraction of nodes, and mention that combining depth with a (working) selector is the way to condense a subtree.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: Auto-diff after a click compares against the click's own auto-snapshot, hiding the page change and reporting transient noise

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s wiki-eval click <article-link-ref>          # navigation; auto-snapshot written
./b4w.ps1 -s wiki-eval snapshot -v 0 --auto-diff --stdout --all
# Diff header references the click's auto-snapshot as the baseline:
# Snapshot Diff: snapshot-...17-33-44-579Z.yml -> snapshot-...17-33-48-172Z.yml
# 41 added, 292 removed, 15 modified   <- same page both sides, page idle between captures
./b4w.ps1 -s wiki-eval snapshot -v 0 --auto-diff --stdout --all   # repeat: 0 added, 1 removed
Workaround that works: ./b4w.ps1 -s wiki-eval click <ref> --no-snapshot; then snapshot -v 0 --auto-diff -> 161 added / 64 removed / 49 modified (the real transition).

#### Expected Behavior

The documented observe->interact->verify loop should let the user see what the interaction changed relative to the pre-interaction page (or at minimum a clean, low-noise delta).

#### Actual Behavior

The interaction's automatic post-command snapshot becomes the diff baseline, so the diff compares new page vs new page — a user following the docs sees 292 spurious 'removed' elements instead of the page change, and the help note that 'after page navigation all elements appear as changed' does not match (few elements actually changed). The auto-snapshot can also capture a pre-settled transient DOM (691 lines vs the settled 380 for the same viewport 4s later), adding noise.

#### Root Cause Analysis

Every interaction writes a snapshot file, and snapshot_diff.rs:find_previous_snapshot() selects the most recent file for the same session/viewport as the baseline without distinguishing interaction auto-snapshots from deliberate captures. The CLI documents --no-snapshot only as a performance shortcut, never as the way to preserve a diff baseline.

#### Code Pointer

`cli/browser4-cli/src/snapshot_diff.rs:find_previous_snapshot() (~line 84); auto-snapshot write path in cli/browser4-cli/src/main.rs (auto-snapshot after command, ~line 1239)`

#### AI Suggested Improvement

- Make the baseline semantics explicit in help/docs: a diff compares against the most recent capture *including* the automatic post-command snapshots, and demonstrate `click <ref> --no-snapshot` in the auto-diff examples when the pre-interaction state matters.
- Alternatively, skip auto-snapshots in find_previous_snapshot() (or tag them) so deliberate captures form the baseline; add --baseline <file> for explicit control.
- Delay/verify the post-command auto-snapshot until the DOM is settled (e.g. after load/idle) so it cannot record a transient tree.
- Consider a diff mode that lists only the diff without the full tree when --stdout is used (help currently says 'show only what changed' but --stdout prints the whole tree plus the diff).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: Full-page captures are stamped with a contradictory 'You are currently viewing viewport 0' footer

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s wiki-eval snapshot --stdout > full.txt   # 689 lines, box y-values up to ~10082
head -7 full.txt   # processingViewport: 0 / hiddenBottomHeight: 1177px
tail -8 full.txt   # 'This page has 3 viewports ... You are currently viewing viewport 0 (absolute).'
./b4w.ps1 -s wiki-eval snapshot -v all --stdout | tail -3   # no such footer; output is the whole tree

#### Expected Behavior

Either the capture is viewport 0 (and the tree should stop at y<=1080), or the output is the full tree and the header/footer should say so. snapshot.md correctly documents that omitting -v captures the full page.

#### Actual Behavior

Without -v the entire page is emitted, but the emitted Viewport State block (processingViewport 0, hiddenBottomHeight 9571px) and footer assert the user is viewing viewport 0 — factually wrong for that output and contradictory to the -v all mode, which carries no such framing.

#### Root Cause Analysis

The CLI prepends a viewport-state header string unconditionally on the snapshot body and appends the viewport-paging footer whenever no viewports value is present; the header values come from the server's current-scroll state, not from the actual capture scope.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_snapshot() header/footer construction (~5772-5800 and ~5907-5932)`

#### AI Suggested Improvement

- Omit the Viewport State block and the paging footer for unfiltered (full-tree) captures, or relabel them as 'current scroll position' rather than capture scope.
- Print an explicit 'capture scope: full page (all viewports)' line so users know nothing is hidden.
- Align this with -v all so both full-tree paths render identically.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: snapshot grep exits 0 and prints only '0 matches found' when nothing matches

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s wiki-eval snapshot grep "zzz_no_such_token_42"; echo $?
# stdout: '0 matches found'; exit code 0

#### Expected Behavior

As a grep-style tool, a no-match result should be distinguishable by exit status (grep convention: 1), or the behavior should be documented; scripts should not need to parse stdout text.

#### Actual Behavior

Exit code is 0 for both matches and no matches. Automated callers cannot tell 'found nothing' from 'found something' without text parsing, which is exactly what a grep-compatible interface should avoid.

#### Root Cause Analysis

The snapshot grep handler always returns Ok(()) and renders the count as human text; it never maps zero matches to a distinct process exit code.

#### Code Pointer

`cli/browser4-cli/src/main.rs:snapshot grep handler (~line 6035-6100, where the scoped search and count rendering are implemented)`

#### AI Suggested Improvement

- Return exit code 1 when there are zero matches (GNU grep semantics), or add an opt-in flag (--exit-code) so existing callers are unaffected.
- Document the exit-code contract in `help snapshot grep` and in the SKILL.md grep row.
- Keep the human-readable '0 matches found' line for interactive use.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: snapshot.md misdescribes the grep target: grep searches a live full-page tree, and auto-diff markers are not greppable

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 -s wiki-eval snapshot -v 0   # saves a 380-line file
./b4w.ps1 -s wiki-eval snapshot grep -c -v wikipedia   # 561 non-matching lines, impossible for the 380-line file
ls -t .browser4-cli/snapshot/*.yml | head -3   # no new file written by grep
./b4w.ps1 -s wiki-eval snapshot -v 0 --auto-diff --stdout --all > d.txt && ./b4w.ps1 -s wiki-eval snapshot grep -c "^[+-] "   # diff lines from d.txt are not found by grep

#### Expected Behavior

Per snapshot.md ('Grep operates on the most recent snapshot. If no snapshot exists yet, run snapshot first') and the task workflow, grep should search the most recent (or diffed) snapshot content.

#### Actual Behavior

grep searches a live, full-page, in-memory AX tree regardless of the last capture's viewport, writes no snapshot, and never sees the ### Diff / +/- markers — those exist only in the auto-diff command response and are not persisted. SKILL.md's table ('live, no prior capture') is the accurate description; snapshot.md contradicts it.

#### Root Cause Analysis

snapshot grep triggers its own fresh AX capture server-side and greps that; the diff section is rendered only in the CLI response text by snapshot_diff.rs:diff_snapshots() and is never written into the snapshot file that grep could consume.

#### Code Pointer

`skills/browser4-cli/references/snapshot.md (Snapshot Grep section) and cli/browser4-cli/src/main.rs snapshot grep handler; diff rendering: cli/browser4-cli/src/snapshot_diff.rs:format_diff_output()`

#### AI Suggested Improvement

- Align snapshot.md with SKILL.md: state that grep captures the live full AX tree and ignores point-in-time viewport files.
- If searchable diffs are desired, persist the diff section (or a sidecar .diff.yml) so snapshot grep can search changed elements; otherwise document that diff output is response-only.
- Add a doc example showing the correct way to inspect changes (read the ### Diff section, or grep the live tree for the new page's marker text).

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

**Completion Status:** Partially Successful — all 10 steps were executed and 9 produced their intended outcome; step 4 (scoped snapshot) is functionally impossible as documented because --selector is silently ignored, and was completed only via the separate htmlsnapshot command.

**Success Rate:** 85% — navigation, all snapshot variants except scoping, clicking, auto-diff, the whole grep matrix and --stdout output worked; --selector, --urls and the depth warning failed silently, and the auto-diff-after-click workflow needed an undocumented workaround.

**Issues Found:** 9

**Major Blockers:** None that stopped the task, but one documented core feature is broken: snapshot --selector (and grep --selector) is a silent no-op confirmed both through the CLI and by direct MCP probes (byte-identical responses). A secondary blocker was the auto-diff baseline being consumed by the interaction's auto-snapshot, requiring `click --no-snapshot` to see the effect of the navigation.

**Most Confusing Aspects:** Silently ignored flags: --selector returns the full page (even for a non-existent selector) with exit 0 and no warning, and -u/--urls changes nothing because URLs are always present. The task's own premise that -v 0 is a 'full-page' capture is wrong — -v 0 is the current screen (10 viewports on this article) and the full tree needs -v all or no -v, yet a full-tree capture is still stamped 'You are currently viewing viewport 0'. snapshot --auto-diff after a click compares against the click's own auto-snapshot rather than the pre-click page, producing 292 spurious 'removals' on an unchanged page. -i is named 'interactive' but is neither an interactive-only filter nor a safe source of refs (headings/paragraphs lose their refs). --stdout is documented as not saving files but saves anyway, which then shifts the next diff baseline.

**Most Valuable Improvements:** 1) Make --selector actually scope the AX tree (or fail loudly when it cannot) for both snapshot and snapshot grep — this is the single highest-value fix. 2) Honor or remove --urls, and fix the dead depth-truncation warning (type mismatch between commands.rs and main.rs) so truncated output is never silent. 3) Make --stdout truly stdout-only, or document its file side effect and exclude such captures from the auto-diff baseline. 4) Document and support the auto-diff baseline explicitly (e.g. recommend click --no-snapshot, or exclude interaction auto-snapshots from find_previous_snapshot), and stop stamping full-tree captures with viewport-0 state text. 5) Correct the -i and grep-target documentation so the docs describe what the shipped renderer actually does.

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

#### Issue 1: snapshot --selector is silently ignored (scoping feature entirely non-functional)

cd D:/workspace/Browser4/Browser4-4.13
./b4w.ps1 -s wiki-eval goto "https://en.wikipedia.org/wiki/Christopher_Alexander"
./b4w.ps1 -s wiki-eval snapshot --selector "#mw-content-text" --stdout --all > scoped.txt
./b4w.ps1 -s wiki-eval snapshot --stdout --all > unscoped.txt
diff scoped.txt unscoped.txt   # identical
./b4w.ps1 -s wiki-eval snapshot --selector "#zzz-not-here-12345" -v 0 --stdout --all   # still exits 0 with the full tree
./b4w.ps1 -s wiki-eval snapshot grep --selector "#firstHeading" -c "ref=e"   # 488, same as without selector; --selector-all also 488
Direct server probe (isolates CLI from backend): POST http://localhost:18182/mcp/call-tool {"tool":"browser_snapshot","arguments":{"sessionId":"<id>","viewports":"0","selector":"#firstHeading"}} returns a byte-identical 26398-byte body to the call without "selector".

#### Issue 2: snapshot --stdout writes snapshot files to disk despite documenting the opposite, silently shifting the next --auto-diff baseline

ls -t .browser4-cli/snapshot/*.yml | head -1
./b4w.ps1 -s wiki-eval snapshot -v 0 --stdout --page-size 15 > /dev/null
ls -t .browser4-cli/snapshot/*.yml | head -1   # a NEW timestamped file appears
Provenance check: the file created by a --stdout run is what a later `snapshot -v 0 --auto-diff` reports as its baseline (e.g. "# Snapshot Diff: snapshot-2026-10-04T17-36-42-903Z.yml -> ...").

#### Issue 3: snapshot -u/--urls is a no-op — link URLs are always included and cannot be toggled

./b4w.ps1 -s wiki-eval snapshot -v 0 --stdout --all > a.txt
./b4w.ps1 -s wiki-eval snapshot -v 0 -u --stdout --all > b.txt
diff a.txt b.txt   # identical (82 '/url:' lines each)
Direct server probe: browser_snapshot with urls:false vs urls:true vs no urls key -> byte-identical 26398-byte responses.

#### Issue 4: Interactive-mode docs contradict observed behavior: -i strips refs from headings/paragraphs/list items (they do not 'all carry refs')

./b4w.ps1 -s wiki-eval snapshot --stdout --all > default.txt
./b4w.ps1 -s wiki-eval snapshot -i --stdout --all > interactive.txt
In default.txt: `- heading "Christopher Alexander" [level=1] [ref=e205]`
In interactive.txt: 0 lines matching 'heading ', 0 'paragraph ', 0 'listitem '; the h1 appears only as unaddressable `- text: ...` lines (0 of 101 text: lines carry refs).

#### Issue 5: Documented --depth truncation warning never fires (dead code: depth stored as integer, read as string)

./b4w.ps1 -s wiki-eval snapshot -d 3 --stdout 2>err.txt | wc -l   # 28 lines vs 689-line full tree
cat err.txt   # only the ref-lifecycle tip, no depth warning

#### Issue 6: Auto-diff after a click compares against the click's own auto-snapshot, hiding the page change and reporting transient noise

./b4w.ps1 -s wiki-eval click <article-link-ref>          # navigation; auto-snapshot written
./b4w.ps1 -s wiki-eval snapshot -v 0 --auto-diff --stdout --all
# Diff header references the click's auto-snapshot as the baseline:
# Snapshot Diff: snapshot-...17-33-44-579Z.yml -> snapshot-...17-33-48-172Z.yml
# 41 added, 292 removed, 15 modified   <- same page both sides, page idle between captures
./b4w.ps1 -s wiki-eval snapshot -v 0 --auto-diff --stdout --all   # repeat: 0 added, 1 removed
Workaround that works: ./b4w.ps1 -s wiki-eval click <ref> --no-snapshot; then snapshot -v 0 --auto-diff -> 161 added / 64 removed / 49 modified (the real transition).

#### Issue 7: Full-page captures are stamped with a contradictory 'You are currently viewing viewport 0' footer

./b4w.ps1 -s wiki-eval snapshot --stdout > full.txt   # 689 lines, box y-values up to ~10082
head -7 full.txt   # processingViewport: 0 / hiddenBottomHeight: 1177px
tail -8 full.txt   # 'This page has 3 viewports ... You are currently viewing viewport 0 (absolute).'
./b4w.ps1 -s wiki-eval snapshot -v all --stdout | tail -3   # no such footer; output is the whole tree

#### Issue 8: snapshot grep exits 0 and prints only '0 matches found' when nothing matches

./b4w.ps1 -s wiki-eval snapshot grep "zzz_no_such_token_42"; echo $?
# stdout: '0 matches found'; exit code 0

#### Issue 9: snapshot.md misdescribes the grep target: grep searches a live full-page tree, and auto-diff markers are not greppable

./b4w.ps1 -s wiki-eval snapshot -v 0   # saves a 380-line file
./b4w.ps1 -s wiki-eval snapshot grep -c -v wikipedia   # 561 non-matching lines, impossible for the 380-line file
ls -t .browser4-cli/snapshot/*.yml | head -3   # no new file written by grep
./b4w.ps1 -s wiki-eval snapshot -v 0 --auto-diff --stdout --all > d.txt && ./b4w.ps1 -s wiki-eval snapshot grep -c "^[+-] "   # diff lines from d.txt are not found by grep

