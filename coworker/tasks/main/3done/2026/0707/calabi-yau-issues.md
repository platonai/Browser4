# Issues: Calabi-Yau

> **Source:** `20260706-190640-Calabi-Yau.full.md` | **Date:** 20260706-190640 | **Mode:** dev

## Scenario Background

### Task

After searching Amazon.com for "Calabi-Yau" and related terms, reviewing detail pages, and filtering for age-appropriateness, here is my analysis:

### The Winner: **"Knights of the Calabi-Yau: Metaphysical Sci-Fi Fantasy Adventure"** by Brian White

| Attribute | Detail |
|-----------|--------|
| **ASIN** | B0C6VT75C6 |
| **Rating** | 5.0★ (15 reviews) |
| **Format** | Kindle eBook |
| **Price** | S$0.00 (Kindle Unlimited) |
| **Why it wins** | This is the **only product in the entire search that is genuinely age-appropriate for a 12-year-old**. It's a sci-fi/fantasy adventure novel that uses Calabi-Yau manifolds as a plot device — dimensional travel, parallel worlds, and metaphysical adventures. The description explicitly compares it to *Interstellar* and *The Dark Tower*. For a 12-year-old boy interested in Calabi-Yau, this book turns abstract math into an exciting adventure story. |

### The Shortlist (ranked by gift suitability)

| Rank | Product | Price (SGD) | Rating | Verdict |
|------|---------|-------------|--------|---------|
| 1 | **Knights of the Calabi-Yau** (Sci-Fi eBook) | S$0.00 | 5.0★ (15) | 🏆 **BEST PICK** — Age-appropriate sci-fi adventure |
| 2 | **The Shape of a Life** (Biography by Shing-Tung Yau) | S$90.56 | 4.3★ (148) | Inspiring true story of the mathematician behind Calabi-Yau |
| 3 | **Bathsheba Calabi-Yau Crystal Sculpture** | S$164.36 | 5.0★ | Beautiful 3D glass crystal of a Calabi-Yau manifold |
| 4 | **Calabi-yau Manifolds: A Bestiary For Physicists** | S$46.88 | 5.0★ | Visual "bestiary" with diagrams — aspirational reference |
| 5 | **"Of Reality - Calabi-Yau"** (Music Album) | S$27.62 | — | Ambient/instrumental background music |
| 6 | **MOXIC Geometric Pattern Carpet** | S$57.69 | — | Math-themed room decor (geometric patterns) |
| 7 | **6-Dimensional Calabi-Yau** (Book) | S$19.39 | 5.0★ | Page unavailable — title suggests accessible content |
| 8 | **Calabi-Yau Model** (Product) | — | — | Page unavailable — potentially a 3D-printed model |
| 9 | **Calabi-Yau ambient albums** (various) | S$0–S$179 | — | Multiple music albums; variable quality |
| 10 | **Surveys in Differential Geometry, Vol. 23** | — | — | Advanced reference; aspirational-only |

---

### Execution Context

**Key Commands:**

1. `cargo run -- --help` — Read CLI documentation
2. `cargo run -- goto "https://www.amazon.com"` — Navigate to Amazon
3. `cargo run -- snapshot -v 0 --compact` — Capture accessibility snapshot
4. `cargo run -- fill e351 "Calabi-Yau"` — Fill search box
5. `cargo run -- press Enter e351` — Submit search
6. `cargo run -- wait --load networkidle` — Wait for page load
7. `cargo run -- htmlsnapshot` — Capture HTML snapshot (used 7 times across different pages)
8. `cargo run -- htmlsnapshot inspect` — Auto-discover selectors
9. `cargo run -- htmlsnapshot get all text "..."` — Extract titles (used 5 times)
10. `cargo run -- htmlsnapshot get all text "span.a-price..."` — Extract prices (used 3 times)
11. `cargo run -- htmlsnapshot get all text "span.a-icon-alt"` — Extract ratings (used 3 times)
12. `cargo run -- htmlsnapshot get all attr "..." href` — Extract links (used 3 times)
13. `cargo run -- htmlsnapshot get text "#productTitle"` — Extract detail page title (used 3 times)
14. `cargo run -- click "a.s-pagination-item..." --follow` — Attempted pagination (failed)
15. `cargo run -- scroll down 3000` — Scroll page
16. `cargo run -- snapshot grep ...` — Search snapshot for pagination
17. `cargo run -- goto "..."` — Direct navigation to specific pages (used 8 times)
18. `cargo run -- eval "..."` — JavaScript evaluation for broken pages

**Major steps:**
1. Learned CLI from SKILL.md and `--help`
2. Navigated Amazon → Searched "Calabi-Yau" → Extracted 32 products across 2 pages
3. Reviewed 5 detail pages (3 successful, 2 broken)
4. Tried alternative searches ("poster", "3D model")
5. Compiled shortlist, identified #1 pick

**Workarounds required:**
- Pagination click failed (off-screen) → Used direct URL navigation instead
- Amazon redirected to .sg domain → Re-navigated to .com
- Two product pages (dp/B0FVVXCRF9, dp/B0BYT51YHL) returned blank — had to skip
- Amazon showed Chinese UI (zh_CN) — worked around it; no English toggle found via CLI

---

---

---

## Issues Found (9 issues)
> **Review complete:** 1 approved, 8 deferred/rejected

### Issue 1: Click on off-screen elements fails silently with misleading message

**Severity:** High
**Category:** UX

#### Reproduction

`cargo run -- goto "https://www.amazon.com/s?k=Calabi-Yau"` then `cargo run -- click "a.s-pagination-item.s-pagination-next" --follow`

#### Expected Behavior

Element should be scrolled into view and clicked, or a clear error should say "element is off-screen, scroll first."

#### Actual Behavior

Click reports success with `✓ Clicked` but then warns `⚠️ Click ... did not result in navigation — page URL is unchanged. The element may be off-screen`. The suggestion mentions `--follow` but `--follow` was already used. The real cause (off-screen element) is buried as a secondary guess.

#### Root Cause Analysis

The CLI doesn't auto-scroll elements into view before clicking. The error message conflates two possible causes (no-navigation vs off-screen) and suggests `--follow` as a fix when `--follow` is already in use.

#### Code Pointer

``cli/browser4-cli/src/main.rs` — click handling and error reporting`

#### AI Suggested Improvement

- Auto-scroll elements into view before clicking, or at minimum return a clear error: "Element is off-screen at [coordinates]. Use `scroll` to bring it into view first."
- Don't suggest `--follow` when the user already passed it
- Consider adding a `--scroll-into-view` flag to click/hover/dblclick

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

---

### Issue 2: `htmlsnapshot inspect` auto-discover fails on e-commerce product grids

**Severity:** High
**Category:** Reliability

#### Reproduction

`cargo run -- goto "https://www.amazon.com/s?k=Calabi-Yau"` then `cargo run -- htmlsnapshot` then `cargo run -- htmlsnapshot inspect`

#### Expected Behavior

Auto-discover should find product card selectors for e-commerce grids.

#### Actual Behavior

Auto-discover samples from the top of the DOM tree and misses the deeply-nested product card containers.

#### Root Cause Analysis

The sampling logic picks elements too high in the DOM tree; product cards are typically nested deep inside `<main>` or content areas.

#### AI Suggested Improvement

Sample from deeper in the DOM tree, prioritizing elements inside `<main>` or with higher y-coordinate bounding boxes.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:** Improving auto-discover heuristics is valuable but requires careful design to avoid breaking existing behavior. The current workaround (manually specifying selectors) works.

---

### Issue 3: Amazon geo-redirects silently change domain without warning

**Severity:** Medium
**Category:** Reliability

#### Reproduction

`cargo run -- goto "https://www.amazon.com/s?k=Calabi-Yau&page=2"`

#### Expected Behavior

User should be notified when the browser is redirected to a different domain.

#### Actual Behavior

Amazon silently redirects from `.com` to `.sg` (or other regional domains) with no indication to the user.

#### Root Cause Analysis

This is Amazon's server-side geo-detection redirecting based on IP location. The CLI trusts the browser's navigation without comparing the final URL against the requested URL.

#### AI Suggested Improvement

Detect when the final URL domain differs from the requested URL domain and warn the user.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:** This is standard web behavior (server-side redirects), not a browser4 bug. The `goto` command already reports the final URL. Adding domain-change warnings would add noise for most sites that legitimately redirect (www → apex, etc.).

---

### Issue 4: Development invocation requires cd into subdirectory each time

**Severity:** Medium
**Category:** UX

#### Reproduction

Every `cargo run -- <command>` requires `cd cli/browser4-cli &&` prefix from the repo root.

#### Expected Behavior

A convenience script at repo root allows running CLI commands without manual `cd`.

#### Actual Behavior

Users must remember to `cd cli/browser4-cli` before every `cargo run` invocation, adding friction during development and scenario evaluation.

#### Root Cause Analysis

Cargo projects require running from the crate directory. No repo-root wrapper script exists.

#### AI Suggested Improvement

Add a `dev-cli.sh` / `dev-cli.ps1` script at the repo root that wraps `cd cli/browser4-cli && cargo run -- "$@"`.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:** Low-priority convenience improvement. Users can create a shell alias as a workaround. Worth including if other root-level developer tooling scripts are added.

---

### Issue 5: Product detail pages sometimes load with empty title and no content

**Severity:** Medium
**Category:** Reliability

#### Reproduction

`cargo run -- goto "https://www.amazon.com/dp/B0FVVXCRF9"` (6-Dimensional Calabi-Yau) and `cargo run -- goto "https://www.amazon.com/dp/B0BYT51YHL"` (Calabi-Yau Model)

#### Expected Behavior

Page should load with visible content and a populated document title.

#### Actual Behavior

Some Amazon product pages load with an empty `<title>` and no visible content, despite `wait --load networkidle` reporting success.

#### Root Cause Analysis

Amazon serves bot-detection/CAPTCHA pages or region-blocked content for certain ASINs. The page is "loaded" from a network perspective but contains no real content.

#### AI Suggested Improvement

After `goto` + `wait --load networkidle`, check if `document.title` is empty and warn the user that the page may be blocked or region-restricted.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:** This is Amazon's anti-bot/anti-scraping behavior, not a browser4 bug. Adding content-empty detection would be a general-purpose heuristic that over-fires on legitimate blank pages (SPA loading states, about:blank, etc.).

---

### Issue 6: Prices shown in SGD (Singapore Dollars) without clear indication of currency locale

**Severity:** Low
**Category:** UX

#### Reproduction

All price extractions returned `SGD` or `S$` prefixed values even when navigating to `amazon.com`.

#### Expected Behavior

User should be aware that prices are shown in a local currency, not USD.

#### Actual Behavior

Amazon serves SGD prices based on IP geolocation, but the CLI gives no indication of the active marketplace locale.

#### Root Cause Analysis

Amazon's server-side geolocation selects the marketplace and currency. The CLI extracts whatever the page renders without surfacing locale metadata.

#### AI Suggested Improvement

After `goto`, detect and report the detected Amazon marketplace locale (e.g., "Amazon.sg — prices in SGD").

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:** This is Amazon-specific behavior, not a browser4 concern. The CLI correctly reports what the page contains. Adding site-specific locale detection would create a maintenance burden.

---

### Issue 7: Help output too verbose — 150+ lines for `--help`

**Severity:** Low
**Category:** Discoverability

#### Reproduction

`cargo run -- --help`

#### Expected Behavior

Concise help output showing command categories with a few examples.

#### Actual Behavior

150+ lines of help output listing every subcommand and flag, making it hard to scan for the right command.

#### Root Cause Analysis

clap (the Rust CLI framework) auto-generates help from all registered subcommands and their arguments. With many subcommands, the output grows proportionally.

#### AI Suggested Improvement

Default `--help` should show only command categories with 1-2 examples each; detailed subcommand help available via `--help <subcommand>`.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:** Verbose help is standard clap behavior and expected for a CLI with many subcommands. Users can pipe through `less`/`grep`. Customizing the top-level help would fight the framework and add maintenance overhead. `--help <subcommand>` already provides focused help.

---

### Issue 8: SKILL.md core loop uses `snapshot -v 0` but e-commerce pages need `htmlsnapshot`

**Severity:** Low
**Category:** Documentation

#### Reproduction

Following the SKILL.md "Core Loop" template: `goto → snapshot -v 0 → click/fill → snapshot -v 0 --auto-diff → htmlsnapshot get ...`

#### Expected Behavior

SKILL.md should document when to use accessibility snapshots vs. HTML snapshots for different page types.

#### Actual Behavior

The core loop template always starts with `snapshot -v 0`, but on e-commerce pages the accessibility tree is too sparse — `htmlsnapshot` is needed to extract product data.

#### Root Cause Analysis

SKILL.md was written primarily for interactive/form-heavy pages where accessibility snapshots excel. E-commerce/data-extraction workflows need HTML-level access that only `htmlsnapshot` provides.

#### AI Suggested Improvement

Add a section to SKILL.md explaining the tradeoffs: use `snapshot` for interaction-heavy pages (forms, dialogs), use `htmlsnapshot` for data-extraction pages (search results, product listings).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:** Documentation improvement that should be bundled with a broader SKILL.md refresh. The guidance is already implicitly discoverable through `--help` examples.

---

### Issue 9: `snapshot grep` produces noisy output with full YAML paths

**Severity:** Low
**Category:** UX

#### Reproduction

`cargo run -- snapshot grep -i "下一页\|next\|pagination"`

#### Expected Behavior

Grep output should show concise matches with truncated context.

#### Actual Behavior

Output includes full YAML node paths and long inline text content (200+ chars), making it hard to scan for relevant matches.

#### Root Cause Analysis

The grep output renders the full YAML path and inline text without truncation. For deeply nested elements with long text content, the output becomes very wide.

#### AI Suggested Improvement

Truncate long inline content in grep output (e.g., `...` for text > 200 chars).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:** Minor UX polish. Truncation is straightforward but the right threshold needs consideration. Bundle with other grep output improvements.

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Click on off-screen elements fails silently with misleading message

`cargo run -- goto "https://www.amazon.com/s?k=Calabi-Yau"` then `cargo run -- click "a.s-pagination-item.s-pagination-next" --follow`

#### Issue 2: `htmlsnapshot inspect` auto-discover fails on e-commerce product grids

`cargo run -- goto "https://www.amazon.com/s?k=Calabi-Yau"` then `cargo run -- htmlsnapshot` then `cargo run -- htmlsnapshot inspect`

#### Issue 3: Amazon geo-redirects silently change domain without warning

`cargo run -- goto "https://www.amazon.com/s?k=Calabi-Yau&page=2"`

#### Issue 4: Development invocation requires cd into subdirectory each time

Every `cargo run -- <command>` requires `cd cli/browser4-cli &&` prefix from the repo root.

#### Issue 5: Product detail pages sometimes load with empty title and no content

`cargo run -- goto "https://www.amazon.com/dp/B0FVVXCRF9"` (6-Dimensional Calabi-Yau) and `cargo run -- goto "https://www.amazon.com/dp/B0BYT51YHL"` (Calabi-Yau Model)

#### Issue 6: Prices shown in SGD (Singapore Dollars) without clear indication of currency locale

All price extractions returned `SGD` or `S$` prefixed values.

#### Issue 7: Help output too verbose — 150+ lines for `--help`

`cargo run -- --help`

#### Issue 8: SKILL.md core loop uses `snapshot -v 0` but e-commerce pages need `htmlsnapshot`

Following the SKILL.md "Core Loop" template: `goto → snapshot -v 0 → click/fill → snapshot -v 0 --auto-diff → htmlsnapshot get ...`

#### Issue 9: `snapshot grep` produces noisy output with full YAML paths

`cargo run -- snapshot grep -i "下一页\|next\|pagination"`
