All 18 steps complete and all deliverable files verified. Here is the full evaluation report.

---

# A. Task Result

**All 18 task steps were completed successfully** (with one significant workaround — see below).

Extracted product data from MockSite:

**Product detail (`/ec/dp/B0E000001` — 4K OLED TV 55):**
- **Title:** 4K OLED TV 55 · **Price:** $899.99 · **Image:** https://picsum.photos/seed/1250857624/200/140
- **Description:** "Premium 4K OLED TV" · **Features:** ["55 inch", "HDR10+"] · Rating 4.6 (521) · In stock (35) · Bestseller · Specs: panel OLED, resolution 3840×2160

**Electronics listing (`/ec/b?node=1292115012`) — 6 products:**
| Title | Price |
|---|---|
| 4K OLED TV 55 | $899.99 |
| Wireless Noise-Cancelling Headphones | $199.99 |
| Portable Bluetooth Speaker | $49.99 |
| Smartphone 128GB | $599.00 |
| USB-C Hub 7-in-1 | $29.95 |
| Wireless Mouse | $24.99 |

Product-link count via `eval --json`: **6**. Screenshot, X-SQL query files, schema file, state-save JSON, and the summary report were all written to `.test-sessions/` (report: `product-data-report-2026-08-14.md`).

**Workaround required:** creating a second tab (`tab-new`) permanently broke `htmlsnapshot` capture for the entire session (`ReferenceError: __pulsar_utils__ is not defined`). Reload, `goto`, `wait --load networkidle`, and closing the extra tab did not recover it; only a full session restart (`close` → `goto`) did. Steps 11–12 were completed after the restart, and the two-tab layout for steps 15–16 had to be recreated afterward.

# B. Execution Trace

**Preparation:** verified repo root; created `.test-sessions/`; ran `./b4w.ps1 help` (well-organized output: Quick Start, workflows, command categories, global options); read `skills/browser4-cli/SKILL.md` in full. Started MockSite via `pwsh ./bin/test.ps1 mock-site` in background (~2.5 min to boot; log output was sparse during startup).

**Commands used (all via `./b4w.ps1`):** `goto`, `snapshot -v 0`, `snapshot -i`, `snapshot grep` (with `-A/-B/-i`), `click e1634`, `htmlsnapshot` (capture), `htmlsnapshot inspect` (bare, `#product-page`, with `--max 3 --depth 2`), `htmlsnapshot summary`, `htmlsnapshot get text/attr`, `htmlsnapshot get all text`, `htmlsnapshot grep`, `htmlsnapshot query --sql @file --format table`, `extract --schema @file --stdout`, `doctor`, `tab-new`, `tab-list`, `tab-select`, `tab-close`, `reload`, `wait --load networkidle`, `eval --json`, `screenshot --filename`, `state-save`, `page-info`, `close`, `help <cmd>`.

**Key decisions:**
- For the "first product link" I used the first **product-card** link (`e1634` → `/ec/dp/B0E000001`) rather than category nav links.
- `inspect` auto-discovery surfaced only *recurring* patterns (recommendation cards, info-pills); I used `htmlsnapshot summary` (its own tip) to find the real selectors: `#productTitle`, `.price-row strong`, `#product-image`, `#details`.
- Discovered `htmlsnapshot grep` rejects `\$` and treats `$` as an end-of-line anchor; used `[$][0-9]+` to match literal dollar prices.
- X-SQL query written to `.test-sessions/product-extract.sql` and run with `--sql @file` — returned title/price/image in one row.
- LLM key was configured, so `extract` ran with a schema file (43 s first call, 12 ms cached retry).
- After the `tab-new` capture bug, restarted the session and recreated the two-tab layout with a second `tab-new`.

---

```json
{
  "issues": [
    {
      "title": "tab-new permanently breaks htmlsnapshot capture for the whole session (__pulsar_utils__ not defined)",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "1) Open a session and verify `htmlsnapshot` capture works (e.g. on /ec/dp/B0E000001). 2) Run `tab-new http://localhost:18080/ec/b?node=1292115012`. 3) Run `htmlsnapshot` on the new tab — fails with: ERROR: html_snapshot_capture failed: ReferenceError: __pulsar_utils__ is not defined at <anonymous>:1:1. 4) Switch back to the original tab — capture fails there too. 5) reload / goto another URL / wait --load networkidle / tab-close the extra tab — all still fail. 6) Only `close` + a fresh `goto` (new session) restores capture.",
      "expected": "htmlsnapshot capture should work on every tab of the session; at worst, a reload or returning to the original tab should recover.",
      "actual": "After tab-new, htmlsnapshot capture fails on ALL tabs of the session with ReferenceError: __pulsar_utils__ is not defined. No in-band action (reload, navigation, wait, closing tabs) recovers it. Only a full session restart recovers.",
      "rootCause": "The backend evaluates htmlsnapshot capture JS against a page context where the __pulsar_utils__ helper (injected at session/first-page setup, likely via Page.addScriptToEvaluateOnNewDocument on the session's original target) was never registered. Creating a new tab appears to switch or invalidate the tracked execution context/target used by capture, and the injection is not re-applied to new documents or targets afterward. Investigation needed: check how/where __pulsar_utils__ is injected in browser4-rest (html_snapshot_capture tool) and how tab creation updates the session's page target binding.",
      "codePointer": "browser4-rest (html_snapshot_capture tool impl) + PulsarWebDriver tab/target tracking; exact function TBD by follow-up analysis",
      "suggestion": "- Re-inject the __pulsar_utils__ helper on every capture attempt (or via Page.addScriptToEvaluateOnNewDocument for every target created in the session), so capture is self-healing\n- Ensure tab-new/tab-select re-register the session's evaluation target for capture; add a regression e2e test: capture → tab-new → capture → tab-select back → capture\n- Improve the error message: when __pulsar_utils__ is missing, suggest `close` + `goto` to recover the session instead of dumping a raw JS stack"
    },
    {
      "title": "extract returns a Java object dump with task metadata merged into the extracted JSON string",
      "severity": "High",
      "category": "Product",
      "reproduction": "browser4-cli extract \"Extract the product title, price, description, and feature list from this product detail page\" --schema @schema.json --stdout\nOutput: {\"type\":\"ai.platon.pulsar.agentic.ExtractResult\",\"description\":\"{\\\"title\\\":\\\"4K OLED TV 55\\\",...,\\\"metadata\\\":{\\\"progress\\\":\\\"\\\",\\\"completed\\\":false},\\\"inputToken\\\":2241,\\\"outputToken\\\":2092,\\\"totalToken\\\":4333,\\\"inferenceTimeMillis\\\":43376}\"}",
      "expected": "Clean JSON of the constrained fields, e.g. {\"title\":\"4K OLED TV 55\",\"price\":\"$899.99\",\"description\":\"...\",\"features\":[...]} — the documented purpose of extract + --schema is structured machine-readable output.",
      "actual": "The extracted data is a JSON STRING nested inside `description` of a serialized Java object (ExtractResult toString). Task bookkeeping fields (progress, completed, inputToken, outputToken, totalToken, inferenceTimeMillis) are merged INSIDE the extracted JSON string. `--json` does not clean this up and additionally emits the raw dump on line 1 plus the JSON envelope on line 2 (violating the global '--json emits JSON only' contract). The same polluted content is written to the saved extract file.",
      "rootCause": "Backend serializes the ExtractResult Java object (its toString) as the tool output instead of the extraction payload; task-status fields (progress/completed/token counts/inference time) get merged into the LLM's result object before stringification. Likely the agentic task pipeline stores the JSON result in a `description` field alongside task metadata and returns the wrapper object.",
      "codePointer": "browser4-agentic ExtractResult handling / MCPToolController extract tool mapping (exact function TBD by follow-up analysis)",
      "suggestion": "- Return the extracted JSON payload as the tool result; move token/timing metadata to a separate envelope field or strip it entirely\n- Make --json emit a single JSON document (currently two lines are printed: raw dump + envelope)\n- Add an integration test asserting extract --schema output parses as clean JSON matching the schema"
    },
    {
      "title": "snapshot -i does not filter to interactive elements as documented",
      "severity": "Medium",
      "category": "Documentation",
      "reproduction": "On http://localhost:18080/ec/, run `snapshot -v 0` then `snapshot -i` and compare. The -i snapshot file contains 164 ref lines vs 122 for -v 0 and includes 86 non-interactive nodes: paragraphs, headings (h1/h2), articles, listitems, generic divs.",
      "expected": "Per SKILL.md: 'Interactive elements only: buttons, links, inputs, selects, textareas. Strips generic <div>, <span>, and other non-interactive containers' — a smaller, cleaner tree.",
      "actual": "-i output still contains paragraphs, headings, articles, listitems and generic containers; it has MORE lines than -v 0 (text gets flattened into ancestor names, e.g. banner name contains all inner text).",
      "rootCause": "The -i implementation appears to alter name aggregation (merging descendant text into node names) rather than filtering the AX tree to interactive roles. Either the feature doesn't do what the docs say or the filtering logic is inverted/absent in the snapshot rendering path.",
      "codePointer": "cli/browser4-cli snapshot -i handling (likely snapshot.rs or the backend AX-tree serializer)",
      "suggestion": "- Implement or fix -i to actually strip non-interactive roles (keep link/button/textbox/checkbox/select/etc.)\n- Or, if current behavior is intended, rename the flag (-t 'text-flattened'?) and correct SKILL.md and help text\n- Add a unit test: -i output must contain zero paragraph/heading/article/listitem roles"
    },
    {
      "title": "htmlsnapshot inspect cannot discover selectors for a single (non-repeating) product block",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "On the product detail page (/ec/dp/B0E000001), run `htmlsnapshot inspect --max 3 --depth 2` (as the task instructs) to find selectors for title/price/description/image.",
      "expected": "Selector discovery for the main product block (title, price, description, image).",
      "actual": "Auto-discovery only analyzes RECURRING sibling patterns: it surfaced `.recommendation-card` (Customers also viewed rail) and, with an explicit `#product-page` selector, `.info-pill` badges. The single product title/price/description/image selectors were not shown. `htmlsnapshot summary` (visual clustering) did surface them (#productTitle, .price-row strong, #product-image, #details) — but only after the tool's tip pointed there.",
      "rootCause": "inspect's auto-discovery is designed for list pages (repeating cards); it has no mode for a single non-repeating block. The gap is discoverability: a first-time user on a detail page follows the documented recipe and gets unrelated selectors, with no in-command guidance.",
      "suggestion": "- Add a 'single block' mode (e.g. inspect with a selector that matches one element could list its key child selectors with sample values instead of searching for repeats)\n- When no repeating pattern above a confidence threshold is found, print an explicit note: 'No recurring pattern found — try htmlsnapshot summary for single-page discovery'\n- Document in SKILL.md that inspect is for list pages; summary/get are for detail pages"
    },
    {
      "title": "htmlsnapshot grep regex traps: $ is an end-of-line anchor, \\$ is rejected, and -n is unsupported",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "1) `htmlsnapshot grep '$'` returns 279 matches (every line) — looks like success but is the end-of-line anchor. 2) `htmlsnapshot grep '\\$[0-9]+\\.[0-9]{2}'` → 'Invalid regex pattern: regex parse error: incomplete escape sequence'. 3) `htmlsnapshot grep -n 'pattern'` → 'Error: unexpected positional arguments (this command accepts 1)'. A literal dollar must be written as [$].",
      "expected": "A documented regex dialect; a way to match a literal $; consistent flags with `snapshot grep` (which supports -A/-B and shows line numbers).",
      "actual": "Undocumented line-anchored regex dialect (Rust regex): `$`/`^` are anchors, `\\$` is an invalid escape, literal `$` needs [$]. Users searching for prices with `$` get either 'everything matches' (silent wrong result) or a parse error. -n works on `snapshot grep` but not `htmlsnapshot grep`.",
      "rootCause": "Line-based regex matching makes anchors active per line; the regex engine rejects \\$ as an invalid escape; no documentation states the dialect or the [$] idiom; grep flag sets diverged between the two grep implementations.",
      "suggestion": "- Document the regex dialect and the '[$] for literal dollar' idiom in help + SKILL.md\n- Consider a --fixed-string mode for literal searches\n- Align flags between snapshot grep and htmlsnapshot grep (support -n in both), or at least reject it with a clear message listing supported flags"
    },
    {
      "title": "Misleading warning when htmlsnapshot get all returns exactly 1 result",
      "severity": "Low",
      "category": "UX",
      "reproduction": "`htmlsnapshot get all text \".buybox\"` on the detail page → correct result array, followed by: 'Only 1 result(s) found for \".buybox\". The page structure may have changed since the snapshot was captured. Try htmlsnapshot inspect \".buybox\" to discover current selectors.'",
      "expected": "No warning, or a neutral informational note — a single match is the correct expected result for unique page elements (one buybox, one #productTitle).",
      "actual": "Warning text implies the snapshot is stale or the selector is wrong, causing doubt about a perfectly correct result.",
      "rootCause": "The warning is emitted for any low match count (<=1) without distinguishing 'selector legitimately matches one unique element' from 'selector used to match more and now doesn't'.",
      "suggestion": "- Only warn when the result count is 0, or when a previous capture of the same selector returned more\n- Change wording for count==1 to a neutral note, e.g. '1 match'"
    },
    {
      "title": "doctor in dev mode urges installing a stale runtime that would replace the local build",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "Run `doctor` in dev mode from the source tree (local backend JAR auto-built). Output: 'Installed runtime: v4.12.3 … run browser4-cli install to update' and 'Backend version (4.13.4-SNAPSHOT) doesn't match installed runtime (v4.12.3) — run browser4-cli install to repair.'",
      "expected": "Dev mode should recognize the local backend build and not advise installing a packaged runtime that would replace the code under test.",
      "actual": "A first-time user following the advice runs `install`, switching to the v4.12.3 release bundle and un-testing the local 4.13.x changes.",
      "rootCause": "doctor compares CLI version against the installed runtime dir without accounting for dev-mode sessions where the backend runs from the local source tree by design.",
      "suggestion": "- In dev mode (backend reported as *-SNAPSHOT), suppress or reword the install advice\n- Add a dev-mode line: 'Running against local source build — install advice suppressed'"
    }
  ],
  "assessment": {
    "completionStatus": "Successful — all 18 task steps completed, all deliverables produced (report, screenshot, X-SQL files, schema, state-save JSON). One significant workaround: the tab-new → htmlsnapshot capture bug forced a session restart and recreation of the two-tab layout.",
    "successRate": "85% — 18/18 steps completed; the multi-tab steps (11, 15-16) required a session-restart workaround and re-created tab layout",
    "issuesFound": 7,
    "majorBlockers": "tab-new permanently breaks htmlsnapshot capture for the whole session (__pulsar_utils__ ReferenceError); unrecoverable in-session, no hint of the cause or the restart workaround in the error.",
    "mostConfusingAspects": "1) htmlsnapshot inspect on a detail page returning recommendation-card selectors instead of the product block; 2) grep `$` matching every line / `\\$` erroring (undocumented regex dialect); 3) snapshot -i not filtering to interactive elements despite the docs; 4) extract's polluted Java-object output despite --schema and --json.",
    "mostValuableImprovements": "1) Fix the tab-new/capture target bug (breaks a core extraction feature in multi-tab workflows); 2) return clean schema-constrained JSON from extract; 3) give inspect a single-block discovery mode; 4) document the grep regex dialect with a fixed-string option.",
    "usabilityRating": 7
  }
}
```
