# Issues: hacker-news

> **Source:** `20260708-184022-hacker-news.full.md` | **Date:** 20260708-184022 | **Mode:** dev

## Scenario Background

### Task

### Story 1: "Chatto is now Open Source!" (hmans.dev)
**Summary:** Chatto is a new open-source group/team chat application that emphasizes compactness, speed, and self-hosting ease. Features include end-to-end encrypted voice/video calls, screen sharing, and per-user encryption at rest. Installable via Homebrew (`brew install chattocorp/tap/chatto`). Chatto Cloud, a paid hosting service with European infrastructure, will soon enter public beta. Currently at v0.4, with v1.0 expected in 6–12 months. The v0.5 roadmap focuses on content moderation, safety features, and multi-server client polish.

### Story 2: "Mistral's Robostral Navigate" (mistral.ai) ⚠️ Site unreachable
**Summary:** Mistral AI launched **Robostral Navigate**, its first robotics model (8B parameters), on July 8, 2026. It provides **map-less navigation** using only a single RGB camera — no LiDAR or depth sensors required. Trained entirely in simulation (~400K trajectories across 6K scenes). Achieves 79.4% success rate on seen benchmarks and 76.6% on unseen, outperforming both single-camera (+9.7 pts) and multi-sensor approaches (+4.5 pts). Works with wheeled, legged, and flying robots via natural language instructions. Key customers: Airbus and BMW.

### Story 3: "GPT‑Live" (openai.com) ⚠️ Site unreachable
**Summary:** OpenAI launched **GPT-Live** on July 8, 2026 — a full-duplex voice model family (GPT-Live-1 and GPT-Live-1 mini) that can **listen and speak simultaneously**, like a phone call rather than a walkie-talkie. Key features: background delegation to GPT-5.5 for complex queries, natural turn-taking without interrupting, live translation, visual cards, selectable reasoning levels (Instant/Medium/High), background noise filtering, and a "Hey Chat" wake word. Early testers report excellent long-form brainstorming sessions. Rolling out on iOS, Android, and web. Some users wish for a more "Star Trek computer" utility tone vs. the personable default.

---

### Execution Context

**Key Commands:**

1. `goto "https://news.ycombinator.com/news"` — Initial navigation
2. `snapshot -v 0` — Capture accessibility tree to find story refs
3. `snapshot grep "link"` — Find story links in snapshot
4. `click e544` — Click first story ("Chatto is now Open Source")
5. `htmlsnapshot` — Capture static HTML snapshot
6. `htmlsnapshot get text "article, main, .post-content, .blog-post, .content"` — Extract article text
7. `go-back` — Return to HN
8. `snapshot grep "link"` — Re-find stories (refs invalid after navigation)
9. `click e494` — Click second story → **ERR_CONNECTION_CLOSED**
10. `goto "https://mistral.ai/news/robostral-navigate/"` — Direct navigation retry → same error
11. `goto "https://news.ycombinator.com/news"` — Return to HN
12. `eval "..." --json` — Extract story URLs via JavaScript (workaround for broken `get attr`)
13. `goto "https://openai.com/index/introducing-gpt-live/"` — Third story → **ERR_CONNECTION_CLOSED**
14. `goto "https://httpbin.org/get"` — Connectivity test (works)
15. `goto "https://news.ycombinator.com/item?id=48832212"` — Mistral HN discussion
16. `htmlsnapshot get all text ".commtext" --limit 5` — Extract discussion comments
17. `goto "https://news.ycombinator.com/item?id=48834405"` — GPT-Live HN discussion
18. `htmlsnapshot get all text ".commtext" --limit 5` — Extract discussion comments
19. WebSearch × 2 — Fallback for article content when browser blocked

**Workarounds required:**
- Used `eval` with JavaScript to extract story URLs (CSS selector via `htmlsnapshot get attr` returned wrong href)
- Used WebSearch + HN discussion comments as fallback when two article sites blocked the browser
- Had to re-snapshot and re-grep after each `go-back` navigation

---

---

## Issues Found (6 issues)

### Issue 1: Major sites (mistral.ai, openai.com) refuse CDP browser connections

**Severity:** High
**Category:** Reliability

#### Reproduction

```
goto "https://mistral.ai/news/robostral-navigate/"
goto "https://openai.com/index/introducing-gpt-live/"
```

#### Expected Behavior

Pages load normally like other websites (e.g., hmans.dev, httpbin.org).

#### Actual Behavior

Both sites return `chrome-error://chromewebdata/` with `ERR_CONNECTION_CLOSED`. The error message on the page reads: "This site can't be reached — [domain] unexpectedly closed the connection."

#### Root Cause Analysis

These domains appear to be blocking connections from headless/CDP-controlled Chrome instances. The browser's TLS fingerprint or headless characteristics may trigger bot-detection or anti-scraping defenses. Since hmans.dev and httpbin.org work fine, this is domain-specific filtering rather than a general connectivity issue.

#### Code Pointer

`(investigation needed — may require CDP-level fingerprint modification or proxy support)`

#### AI Suggested Improvement

- Add a `--stealth` or `--anti-detect` mode that modifies CDP-level browser fingerprints (e.g., navigator.webdriver, Chrome headless UA string) to reduce detection
- Document known-blocked domains and provide guidance on when to use fallback strategies (e.g., `attach` to a real Chrome, or use proxy rotation)
- Add a specific error message when `ERR_CONNECTION_CLOSED` is detected, suggesting the domain may be blocking headless browsers and pointing to relevant documentation

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] Real limitation — major sites block headless/CDP-controlled Chrome, and the scenario required WebSearch fallback as a result. However, adding a --stealth/--anti-detect mode is a large architectural feature requiring ongoing cat-and-mouse maintenance against evolving bot-detection techniques. Postpone until stealth/resilience gets dedicated design attention. In the interim, documenting known-blocked domains and improving the ERR_CONNECTION_CLOSED error message to suggest fallback strategies is actionable and low-risk.

---

### Issue 2: `htmlsnapshot get attr` returns wrong href for HN story links

**Severity:** Medium
**Category:** UX / Documentation

#### Reproduction

```
goto "https://news.ycombinator.com/news"
htmlsnapshot
htmlsnapshot get attr "#48832212 a" href
```

#### Expected Behavior

Returns the story URL (e.g., `https://mistral.ai/news/robostral-navigate/`).

#### Actual Behavior

Returns `vote?id=48832212&how=up&goto=news` — the upvote link, not the story link.

#### Root Cause Analysis

HN's HTML structure has multiple `<a>` elements within each story row. The `#48832212 a` selector matches the first `<a>` descendant, which is the upvote arrow, not the title link. The story title link uses a different DOM path. This is a CSS selector specificity issue, but the behavior is surprising to a new user who expects `#id a` to return the most prominent link.

#### Code Pointer

`(N/A — this is about documentation/UX, not a code bug)`

#### AI Suggested Improvement

- Add a troubleshooting section to SKILL.md or htmlsnapshot.md covering common CSS selector gotchas (e.g., "first link matched may not be the one you want — use `htmlsnapshot inspect` to discover the correct selector path")
- Improve the `htmlsnapshot` tips output to suggest `htmlsnapshot inspect` when the user extracts unexpected values
- Consider adding a `--verbose` flag to `htmlsnapshot get` that shows which DOM element matched the selector, helping users debug incorrect selections

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Valid issue: the CSS selector #id a matches the first <a> descendant (upvote link on HN), which surprises users expecting the title link. However, this is correct CSS behavior — not a code bug. The documentation improvements (troubleshooting section, suggesting htmlsnapshot inspect) are the right fix. The --verbose flag to show matched elements is a good addition. But the unified command suggestion belongs to Issue 4's domain.

---

### Issue 3: No automatic re-snapshot after navigation — silent ref staleness risk

**Severity:** Medium
**Category:** UX / Reliability

#### Reproduction

```
goto "https://news.ycombinator.com/news"
snapshot -v 0       # refs captured: e544, e494, e593
click e544          # navigates to article
go-back             # returns to HN
click e494          # uses stale ref from old snapshot
```

#### Expected Behavior

Either the click works (refs are still valid), or a clear error warns that refs are stale.

#### Actual Behavior

The click may appear to work (no error) but target the wrong element because the DOM has been recreated. In this specific case, going back happened to preserve the same refs, but this is not guaranteed and is warned about in the docs. A new user who misses the "Refs are single-use" warning could silently interact with wrong elements.

#### Root Cause Analysis

After navigation events (`goto`, `go-back`, `reload`, tab switches), the DOM is rebuilt and CDP backend node IDs change. The documentation clearly warns about this in SKILL.md §2 and §5, but there is no runtime protection — the CLI accepts stale refs without warning if they happen to resolve to valid (but different) nodes.

#### Code Pointer

``cli/browser4-cli/src/commands.rs` (command dispatch layer — could track last navigation event and warn)`

#### AI Suggested Improvement

- Track whether a navigation command was issued since the last snapshot, and emit a warning on stderr when a stale ref is used: "⚠️ Navigation occurred since last snapshot. Refs may be stale. Run `snapshot -v 0` first."
- Add an `--auto-snapshot` flag to `click`, `fill`, and other interaction commands that automatically re-snapshots before interacting
- Consider embedding a snapshot-sequence counter in refs and rejecting refs from an older sequence

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Silent ref staleness after navigation is a real reliability risk for AI agents — clicking a stale ref may target the wrong element with no error. Tracking navigation events and emitting a stderr warning when stale refs are used is a well-scoped, high-value improvement. Skip the sequence-counter and --auto-snapshot suggestions for now — the warning alone addresses the core risk without over-engineering.

---

### Issue 4: `snapshot` vs `htmlsnapshot` — confusing two-system design for new users

**Severity:** Medium
**Category:** Discoverability / Documentation

#### Reproduction

A new user reads the SKILL.md core loop and sees `snapshot -v 0` as the primary command. Later they discover `htmlsnapshot` in the command map as a separate system. The relationship and when to use each requires reading multiple reference files.

#### Expected Behavior

Clear, immediate guidance on which snapshot command to use for which purpose, without needing to navigate reference documentation.

#### Actual Behavior

The distinction exists but requires mental model building. `snapshot` captures the accessibility tree (for interaction refs). `htmlsnapshot` captures static HTML (for CSS selector extraction). The core loop only shows `snapshot`, while most extraction patterns use `htmlsnapshot`. A new user might try `htmlsnapshot get text "..."` without first running `htmlsnapshot`, or try to get element refs from `htmlsnapshot`.

#### Root Cause Analysis

The two systems serve different purposes (interaction targeting vs. content extraction) but share similar naming. The SKILL.md core loop covers interaction but extraction patterns appear in separate reference files.

#### Code Pointer

``skills/browser4-cli/SKILL.md` (the core loop section and command map)`

#### AI Suggested Improvement

- Add a one-sentence comparison at the top of the SKILL.md command map: "`snapshot` = for finding interactive element refs to click/fill. `htmlsnapshot` = for extracting text/data via CSS selectors."
- Add a cross-reference from `htmlsnapshot get` error messages suggesting the user run `htmlsnapshot` first if no snapshot is stored
- Consider adding a `snapshot extract` subcommand that captures both snapshots in one step, reducing the two-step dance

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Valid discoverability concern — the two-snapshot system (accessibility tree for interaction vs. HTML for extraction) is confusing without upfront guidance. The one-sentence comparison in the command map and cross-reference in htmlsnapshot get error messages are easy wins. The unified snapshot extract subcommand suggestion is a larger feature that should be deferred separately — it conflates two systems with different underlying data sources and performance characteristics.

---

### Issue 5: Cargo rebuild overhead on every command (~0.5s each)

**Severity:** Low
**Category:** UX

#### Reproduction

Run any `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- <cmd>` invocation.

#### Expected Behavior

Near-instant command execution after the first build.

#### Actual Behavior

Each invocation shows `Finished 'dev' profile [unoptimized + debuginfo] target(s) in 0.47s`. While 0.5s is fast, it adds up over many commands and creates a perceptible delay between issuing a command and seeing the result. This is inherent to `cargo run` but affects the interactive feel.

#### Root Cause Analysis

`cargo run` checks the binary and dependencies for changes on every invocation, even when nothing has changed. This is standard Cargo behavior, not a browser4-cli bug.

#### Code Pointer

`(N/A — Cargo toolchain behavior)`

#### AI Suggested Improvement

- Document the `cargo build` then `./target/debug/browser4-cli` shortcut in the development reference for faster iteration
- Consider adding a `justfile` or `Makefile` with a `dev` target that builds once and runs commands against the built binary
- Add a shell alias example to the development docs: `alias b4='/path/to/target/debug/browser4-cli'`

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: WONTFIX] This is standard Cargo behavior (checking binary and dependencies for changes on every invocation), not a browser4-cli bug. Per review guidelines: development-mode friction (cargo run overhead, cd into subdirs) → WONTFIX. The suggested documentation workarounds (build once then use the binary directly, shell aliases) are fine to add to dev docs but don't change the decision.

---

### Issue 6: Tips suggest `--stdout` but it's easy to overlook

**Severity:** Low
**Category:** Discoverability

#### Reproduction

After each `snapshot` command, the tip says "Use `--stdout` to print element refs inline instead of opening the snapshot file" but the user must already be reading tips to discover this.

#### Expected Behavior

The most efficient workflow (`snapshot -v 0 --stdout`) should be more prominently featured.

#### Actual Behavior

The default `snapshot` output saves to a file and shows a 10-line preview. The user must discover `--stdout` from the tip or read documentation.

#### Root Cause Analysis

The default behavior (save to file) is the safer, more general-purpose default. The tip system works but relies on the user reading stderr output.

#### Code Pointer

`(N/A — this is a design preference)`

#### AI Suggested Improvement

- Add `--stdout` to the SKILL.md core loop commands as the recommended pattern for interactive use
- Consider a config option or environment variable (e.g., `BROWSER4_SNAPSHOT_MODE=inline`) to change the default behavior

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Simple documentation improvement: adding --stdout to the SKILL.md core loop examples as the recommended interactive-use pattern is a low-effort, high-impact change. The tips already mention --stdout but featuring it in the primary workflow examples reduces discoverability friction for new users and AI agents alike.

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Major sites (mistral.ai, openai.com) refuse CDP browser connections

```
goto "https://mistral.ai/news/robostral-navigate/"
goto "https://openai.com/index/introducing-gpt-live/"
```

#### Issue 2: `htmlsnapshot get attr` returns wrong href for HN story links

```
goto "https://news.ycombinator.com/news"
htmlsnapshot
htmlsnapshot get attr "#48832212 a" href
```

#### Issue 3: No automatic re-snapshot after navigation — silent ref staleness risk

```
goto "https://news.ycombinator.com/news"
snapshot -v 0       # refs captured: e544, e494, e593
click e544          # navigates to article
go-back             # returns to HN
click e494          # uses stale ref from old snapshot
```

#### Issue 4: `snapshot` vs `htmlsnapshot` — confusing two-system design for new users

A new user reads the SKILL.md core loop and sees `snapshot -v 0` as the primary command. Later they discover `htmlsnapshot` in the command map as a separate system. The relationship and when to use each requires reading multiple reference files.

#### Issue 5: Cargo rebuild overhead on every command (~0.5s each)

Run any `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- <cmd>` invocation.

#### Issue 6: Tips suggest `--stdout` but it's easy to overlook

After each `snapshot` command, the tip says "Use `--stdout` to print element refs inline instead of opening the snapshot file" but the user must already be reading tips to discover this.

