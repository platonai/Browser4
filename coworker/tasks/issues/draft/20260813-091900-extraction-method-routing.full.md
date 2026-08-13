All seven acceptance criteria are complete. Here is my full evaluation.

---

## A. Task Result

All seven acceptance criteria in **SKILL.md §4a — Choosing an Extraction Method** were completed successfully against MockSite (`http://localhost:18080`), using only `browser4-cli` commands:

| AC | Branch | Command used | Result |
|----|--------|--------------|--------|
| AC1 | Interact first, then extract | `snapshot -i` → `fill`/`select`/`check` → `click` → `htmlsnapshot` → `htmlsnapshot get text "#result-data"` | ✅ Confirmation banner + submitted JSON (Jane / Smith / jane.smith@example.com / us / advanced) extracted and matched inputs |
| AC2 | Static, one field | `htmlsnapshot get text "#productTitle"` | ✅ `4K OLED TV 55` |
| AC3 | Static, all matches | `htmlsnapshot get all text '[class*="product-title"]'` | ✅ All 6 Electronics titles |
| AC4 | Correlated multi-field rows | `htmlsnapshot query --sql @ac4-final.sql` | ✅ 6 rows, title/price/URL aligned per card |
| AC5 | Dynamic/complex logic | `eval --json --file ac5-eval.js` | ✅ title, 2 buttons, 0 links, 0 forms, 5 headings |
| AC6 | Natural-language extraction | `extract "…" --stdout` | ✅ title / $199.99 / 4.4 / 2 feature bullets (backend has LLM key) |
| AC7 | High-volume bulk | `crawl --seed-file … --depth 0 --sql @… --format table --refresh` | ✅ 4 rows, one per seed URL |

**Important AC6 note:** The task said to record AC6 as *environment-blocked* if no LLM key was configured. My shell has no LLM key (all four env vars unset), but `extract` **succeeded** — `browser4-cli doctor` reports "✓ LLM is configured" on the **backend server** (port 18182). So the LLM key lives on the backend, not the CLI shell. This surfaced a documentation ambiguity (see Issue 10).

---

## B. Execution Trace

**Commands used (chronological):**
1. `browser4-cli help`, `browser4-cli --version` → 4.13.3; server UP at `http://localhost:18182`.
2. Read local `skills/browser4-cli/SKILL.md` (737 lines) plus references (`htmlsnapshot.md`, `x-sql.md`, `crawl.md`, `agent.md`, `docs/eval-command-output.md`, `docs/mocksite.md`). `https://browser4.io/SKILL.md` returned an SSL error (environmental network restriction; the local copy was complete).
3. Inspected MockSite source to learn exact markup/selectors: `EcControllers.kt`, `HtmlRenderer.kt`, `EcommerceController.kt`, `products.json`, `ec-product.html`, `form-filling.html`, `interactive-1.html`.
4. Verified MockSite health (200) and backend status; confirmed no LLM key in my shell.
5. **AC1:** `goto form-filling.html` (flaky first load — see Issue 1), re-`goto`, `snapshot -i --stdout`, filled via refs (`e1822`–`e1829`, `e1825`, `e1828`, `e1836`, `e1837`), `click #submit-btn`, `eval` to confirm live DOM, then `htmlsnapshot` + `htmlsnapshot get text "#result-data"` and `#result-panel`.
6. **AC2:** `goto /ec/dp/B0E000001` → `htmlsnapshot` → `get text "#productTitle"`.
7. **AC3:** `goto /ec/b?node=1292115012` → `htmlsnapshot` → `get all text '[class*="product-title"]'`.
8. **AC4:** wrote X-SQL files to `.test-sessions/`; tested scopes `[class*=…]`, `div[…]`, `article[…]`; tested `DOM_FIRST_HREF('.product-link')` vs `DOM_FIRST_HREF('a.product-link')` vs `DOM_FIRST_ATTR`. Final query used `DOM_FIRST_HREF('a.product-link')`.
9. **AC5:** `goto interactive-1.html` → `eval --json --file ac5-eval.js`.
10. **AC6:** `goto /ec/dp/B0E000002` → `extract … --stdout`; `doctor` confirmed backend LLM.
11. **AC7:** wrote `ac7-seeds.txt` (4 URLs) + `ac7-query.sql` → `crawl --seed-file … --depth 0 --sql @… --format table --refresh`.

**Key decisions:** used single-quoted PowerShell strings for selectors containing `"`; quoted `@file` arguments to avoid PowerShell splatting; used `DOM_FIRST_HREF('a.product-link')` (tag-qualified) after `.product-link` returned empty; used `div[class*="product-card"]` after confirming the browser4-cli-served markup uses `<div>` (not `<article>`).

**Workarounds required:** re-`goto` after the flaky first load; single-quote selectors; quote `@file`; tag-qualify the `DOM_FIRST_HREF` selector (or use `DOM_FIRST_ATTR`).

**Temporary files** were created under `.test-sessions/` (`ac4-*.sql`, `ac5-eval.js`, `ac7-seeds.txt`, `ac7-query.sql`). Note: `.test-sessions/` already contained extensive leftovers from prior evaluation runs.

---

```json
{
  "issues": [
    {
      "title": "First goto reports \"Page loaded\" but the DOM is empty and stuck in readyState \"loading\"",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "browser4-cli goto \"http://localhost:18080/generated/form-filling.html\"\nThen immediately: browser4-cli eval \"document.readyState\"  → returns \"loading\"; eval \"document.querySelectorAll('input').length\" → 0. The goto output printed \"Page loaded\" and even \"wait --load networkidle\" reported \"✓ Wait complete\".",
      "expected": "After goto reports success, the page should be fully loaded (readyState \"complete\", populated DOM).",
      "actual": "The first navigation (reconnecting an existing DEFAULT session that was on /ec/dp/B0E000003) left the target URL set but the document empty: document.title was \"\", document.documentElement.outerHTML.length was null, and there were 0 inputs/selects/buttons. A second goto (which printed \"Already at … page unchanged\") actually completed the load (readyState \"complete\", 13 inputs).",
      "rootCause": "Likely a race in the reconnect-and-navigate path: when goto reuses a pre-existing session and navigates, the 'Page loaded' signal fires before the document finishes parsing/committing. The auto-captured snapshot and page-info read an empty document. Needs investigation into the goto navigation-completion detection (vs. the load-event/readyState it actually waits on).",
      "codePointer": "cli/browser4-cli/src/main.rs (goto/navigation completion handling) — follow-up analysis needed",
      "suggestion": "- Make goto's 'Page loaded' wait on document.readyState === 'complete' (or DOM content readiness), not just the HTTP navigation commit, before printing success and capturing the snapshot\n- If the navigation is to the same URL the session already had, force a reload instead of printing 'page unchanged' with a stale empty DOM\n- Surface a clearer diagnostic when the post-goto snapshot is empty (e.g. 'page appears empty; try wait --load networkidle or reload')"
    },
    {
      "title": "CLI pollutes the repo root with 128 files across two inconsistently-named state directories",
      "severity": "Medium",
      "category": "Product",
      "reproduction": "Run several browser4-cli commands from the repo root with an unwritable ~/.browser4. Observe D:\\workspace\\Browser4\\Browser4-4.13\\.browser4-cli\\snapshot\\ (128 files: snapshot-*.yml, extract-*.txt) and .\\.browser4-cli-state\\ (sessions\\, loops\\).",
      "expected": "State/snapshots land in a single, clearly-named, configurable directory (ideally outside the repo, or at least consistently named and documented).",
      "actual": "Two sibling dirs are created at the repo root: .browser4-cli (snapshots/extract results, 128 files) and .browser4-cli-state (session/loop state). The warning text names .browser4-cli-state, but snapshots go to .browser4-cli, so a user cannot tell which env var controls which directory.",
      "rootCause": "The CLI state fallback path and the snapshot-output path are computed independently and use different names ('.browser4-cli' vs '.browser4-cli-state'). BROWSER4_CLI_STATE_DIR relocates state but snapshots appear to be written to a separately-derived '.browser4-cli' path.",
      "codePointer": "cli/browser4-cli/src/ (state-dir fallback and snapshot-dir resolution)",
      "suggestion": "- Unify snapshot and session state under one root (e.g. honor BROWSER4_CLI_STATE_DIR for both, defaulting snapshots under <state>/snapshot)\n- Document the exact directory each artifact type is written to and the env var that controls it\n- Prefer ~/.browser4 (outside the repo) when writable, and when falling back, print the full resolved path once rather than per-command"
    },
    {
      "title": "Permission-denied CLI-state warning printed on every single command",
      "severity": "Low",
      "category": "UX",
      "reproduction": "browser4-cli fill e1822 \"Jane\" (or any command) in a sandbox where C:\\Users\\<user>\\.browser4 is not writable.",
      "expected": "The fallback warning should appear once (or be suppressible), not on every command.",
      "actual": "Every command prints two lines: 'cannot write CLI state to C:\\Users\\pereg\\.browser4 (permission denied)' and 'using …\\.browser4-cli-state instead — set BROWSER4_CLI_STATE_DIR to a writable location to silence this warning'. This adds noise to every interaction and clutters output that is otherwise meant to be parseable.",
      "rootCause": "The fallback/warning logic runs on each invocation with no deduplication or sticky suppression.",
      "codePointer": "cli/browser4-cli/src/ (state-dir initialization/fallback warning)",
      "suggestion": "- Emit the warning only once per process lifetime (or cache a 'warned' marker)\n- Add a --quiet-aware suppression or a dedicated config flag to silence it"
    },
    {
      "title": "PowerShell treats unquoted @file as a splat; documented `--sql @query.sql` fails on Windows",
      "severity": "Medium",
      "category": "Discoverability",
      "reproduction": "browser4-cli htmlsnapshot query \"http://localhost:18080/ec/b?node=1292115012\" --sql @.test-sessions/ac4-query.sql\n→ ParserError: Unrecognized token in source text (the @ is the PowerShell splat operator).",
      "expected": "The @file syntax shown throughout SKILL.md/help should work in PowerShell (the primary Windows shell) or be documented with a Windows-safe form.",
      "actual": "Unquoted @file is a PowerShell parse error. Quoting it (--sql \"@.test-sessions/...\") works, but the docs and help examples use the unquoted form copied from bash.",
      "rootCause": "@ is PowerShell's splat operator, so an unquoted @path token never reaches the CLI. The docs were written bash-first.",
      "codePointer": "skills/browser4-cli/SKILL.md (X-SQL quickstart examples) and references/htmlsnapshot.md / x-sql.md",
      "suggestion": "- Add explicit PowerShell examples showing --sql \"@query.sql\" (quoted) alongside the bash form\n- Add a note in the SKILL.md §4e and §5 shell-quoting warning that on PowerShell @file must be quoted"
    },
    {
      "title": "PowerShell backslash-quote escaping breaks CSS selectors; must use single quotes",
      "severity": "Medium",
      "category": "Discoverability",
      "reproduction": "browser4-cli htmlsnapshot get all text \"[class*=\\\"product-title\\\"]\"\n→ 'No elements matched \"[class*=\\\".'",
      "expected": "A clear, working way to pass a CSS attribute selector like [class*=\"x\"] from PowerShell.",
      "actual": "Using \\\" inside a double-quoted PowerShell string passes a mangled selector (the CLI sees '[class*=\"' truncated). Single quotes — '[class*=\"product-title\"]' — work, but this is easy to get wrong and the error message is confusing (it echoes the truncated selector and suggests a nonsense command).",
      "rootCause": "PowerShell uses the backtick for escaping, not backslash; \\\" is a literal backslash+quote. The docs' Windows guidance exists in shell-quoting.md but the primary SKILL.md examples use bash quoting.",
      "codePointer": "skills/browser4-cli/references/shell-quoting.md and SKILL.md §5 Critical Warnings",
      "suggestion": "- Prominently document the single-quote pattern for PowerShell selectors with double-quoted attribute values\n- Improve the empty-match error message to not echo a half-parsed selector and to drop the misleading 'query the live accessibility tree' suggestion"
    },
    {
      "title": "DOM_FIRST_HREF(DOM, '.product-link') returns empty; requires tag-qualified selector or DOM_FIRST_ATTR",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "SELECT DOM_FIRST_HREF(DOM, '.product-link') AS link FROM DOM_LOAD_AND_SELECT(@url, '[class*=\"product-card\"]')\n→ link is \"\" for all rows. DOM_FIRST_HREF(DOM, 'a.product-link') → correct absolute URL; DOM_FIRST_ATTR(DOM, '.product-link', 'href') → correct relative URL.",
      "expected": "DOM_FIRST_HREF with any selector that matches an <a> element should return its href consistently.",
      "actual": "A class-only selector (.product-link) yields empty; the same element selected with a tag+class selector (a.product-link) works. The task's own AC4 instruction to use DOM_FIRST_HREF is a trap for a user who naturally writes the class-only selector.",
      "rootCause": "DOM_FIRST_HREF resolves relative→absolute and, per docs/ql-functions-guide.md:902-906, can return empty in scoped DOM. Empirically it is selector-sensitive (class-only fails, tag-qualified succeeds), which points to the internal element-selection vs. href-resolution step, not merely a base-URL absence. The function lives in an external Pulsar library (not this repo's .kt files).",
      "codePointer": "",
      "suggestion": "- Investigate why firstHref(dom, '.product-link') and firstHref(dom, 'a.product-link') diverge for the same element, and normalize them\n- Add a regression test for class-only vs tag-qualified selectors with relative hrefs in scoped DOM\n- Update SKILL.md §4e/x-sql.md to call out this gotcha and recommend DOM_FIRST_ATTR for href extraction"
    },
    {
      "title": "SKILL.md §4a/§5 falsely claim htmlsnapshot is stale after JS interactions — it actually reflects the live DOM",
      "severity": "High",
      "category": "Documentation",
      "reproduction": "Fill+submit the client-side form-filling page, then: browser4-cli htmlsnapshot; browser4-cli htmlsnapshot get text \"#result-data\". It returns the just-submitted JSON payload, not the initial 'No submission yet.'",
      "expected": "Behavior should match the documentation. §5 states 'htmlsnapshot captures the initial page HTML, not the live DOM… the snapshot will be stale' and §4a says JS-modified content 'will not be reflected'.",
      "actual": "htmlsnapshot capture reflects the current live DOM: after a JS-driven form submission, get text \"#result-data\" returns the submitted values. The documented 'use eval instead' advice is therefore wrong for this version and would push users to a needlessly indirect path (and the reverse concern — trusting a genuinely-stale cached snapshot — is real and unaddressed).",
      "rootCause": "The docs describe an older implementation ('initial server-rendered HTML') while the current htmlsnapshot capture re-serializes the live DOM. The documentation was not updated when the capture semantics changed.",
      "codePointer": "skills/browser4-cli/SKILL.md lines 299 and 470-471 (and references/htmlsnapshot.md 'initial page HTML' wording)",
      "suggestion": "- Correct §4a/§5 to state that htmlsnapshot capture serializes the current live DOM and will reflect JS updates as long as you re-capture after interacting\n- Clarify the real staleness rule: the *cached* snapshot is stale only if you do not re-capture after a navigation/interaction; document that the auto-captured snapshot after goto is a distinct earlier capture\n- Add an explicit note in AC1-style scenarios: after submit, run htmlsnapshot (capture) before get, then eval is not required"
    },
    {
      "title": "crawl.md MockSite selector table is inaccurate (#feature-bullets and .breadcrumb don't exist)",
      "severity": "Medium",
      "category": "Documentation",
      "reproduction": "Open http://localhost:18080/ec/dp/B0E000002 and inspect. crawl.md lists MockSite selectors: Description=#feature-bullets, Category=.breadcrumb. Neither selector matches (the actual IDs/classes are #product-features and .breadcrumbs).",
      "expected": "The documented MockSite selectors should match the served pages.",
      "actual": "#feature-bullets matches 0 elements; the feature list is <ul id=\"product-features\">. The breadcrumb is .breadcrumbs (plural). Title (#productTitle) and price (#product-price) are correct.",
      "rootCause": "The crawl.md selector table was written against a different/older template or the Amazon fixture, and was not reconciled with the current HtmlRenderer product template (ec-product.html) which uses #product-features and .breadcrumbs.",
      "codePointer": "skills/browser4-cli/references/crawl.md lines 272-280",
      "suggestion": "- Correct the table to #productTitle, #product-price, #product-features, .breadcrumbs (and note the listing-page selectors .product-card/.product-title/.product-price)\n- Add a note that listing pages use .product-title/.product-price classes while detail pages use #productTitle/#product-price IDs"
    },
    {
      "title": "extract output is a Java-serialization wrapper with double-encoded JSON and leaked token metadata",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "browser4-cli extract \"Return the product title, displayed price, rating, and the top three feature bullets as JSON.\" --stdout\n→ {\"type\":\"ai.platon.pulsar.agentic.ExtractResult\",\"description\":\"{\\\"product_title\\\":…,\\\"metadata\\\":{…},\\\"inputToken\\\":1932,\\\"outputToken\\\":4872,\\\"totalToken\\\":6804,\\\"inferenceTimeMillis\\\":5}\"}",
      "expected": "The user asked for '…as JSON'; they should get a clean JSON object (e.g. {\"product_title\":…,\"displayed_price\":…,\"rating\":…,\"feature_bullets\":[…]}) — not an implementation wrapper.",
      "actual": "The requested data is correct but is (1) wrapped in a {\"type\",\"description\"} envelope, (2) double-encoded as an escaped string inside 'description', (3) polluted with inputToken/outputToken/totalToken/inferenceTimeMillis and a metadata.completed:false field, and (4) saved to a file by default instead of stdout.",
      "rootCause": "MCPToolController.kt:952-959 has an 'is ExtractResult' branch that would emit clean {success,message,data} JSON, but the extract result apparently falls through to the generic else branch (lines 963-968) that wraps any non-serializable object as {type, description}. The token-usage stats are also merged into the result data node before serialization.",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt:952-968 and browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/PerceptiveAgent.kt:111-119",
      "suggestion": "- Ensure the extract tool result is typed as ExtractResult at the serialization point so it hits the clean {success,message,data} branch\n- Emit the LLM's structured data as the top-level payload and move token usage/inference timing into a separate, clearly-labeled metadata field (or strip it from user output)\n- Default extract to --stdout for a single-page sync extraction, or clearly print the extracted JSON inline alongside the saved file path"
    },
    {
      "title": "extract/agent help says the LLM key is 'configured in the environment' but the key lives on the backend server",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "Run browser4-cli extract … with no OPENROUTER_API_KEY/DEEPSEEK_API_KEY in the CLI shell. It succeeds; browser4-cli doctor shows '✓ LLM is configured'.",
      "expected": "The docs should specify where the LLM key must be set (the process/environment that runs the backend server, not the CLI shell).",
      "actual": "extract worked because the separately-managed backend (port 18182) has the key. A first-time user who starts the backend themselves via the CLI would reasonably assume 'the environment' means their own shell; the reference (agent.md §Prerequisites) likewise shows export DEEPSEEK_API_KEY=… without stating it must reach the backend process.",
      "rootCause": "Ambiguous phrasing — 'environment' can mean the CLI shell or the backend server environment. The architecture (CLI → REST backend → LLM) means the key must be visible to the backend JVM.",
      "codePointer": "skills/browser4-cli/references/agent.md (Prerequisites section) and cli/browser4-cli/src/help.rs (extract/chat help text)",
      "suggestion": "- Reword to: 'the key must be set in the environment of the Browser4 backend server process (wherever it runs); when the CLI auto-starts the backend, set it in the CLI's own environment before the first launch'\n- Add a doctor-based tip ('LLM is configured/not configured') pointing users at the right place"
    },
    {
      "title": "MockSite serves two different HTML structures for the same URL depending on Accept header",
      "severity": "Low",
      "category": "Product",
      "reproduction": "GET http://localhost:18080/ec/b?node=1292115012 with Accept */* (e.g. PowerShell Invoke-WebRequest) → article.product-card with h2.product-title. Same URL in browser4-cli (browser goto and scrape API) → div.product-card with div.product-title.",
      "expected": "One stable markup for a given URL regardless of client Accept header, so selector discovery is unambiguous.",
      "actual": "Two Spring controllers map /ec/b (EcommerceController vs EcCategoryController); the more specific produces=text/html match wins for the browser, so browser4-cli consistently sees div.product-card, but a curl/Invoke-WebRequest with */* sees article.product-card. The fixture README's 'Suggested ID / Class Conventions' still documents article.product-card.",
      "rootCause": "MockSite fixture design: duplicated /ec/b mappings with different renderers and no unambiguous single winner across Accept values. Not a browser4-cli defect, but it undermines the scenario's selector discoverability when users cross-check with a different HTTP client.",
      "codePointer": "browser4-tests/pulsar-tests-common/src/main/kotlin/ai/platon/pulsar/test/server/ec/EcControllers.kt and EcommerceController.kt (duplicate /ec/b mappings)",
      "suggestion": "- Consolidate to a single /ec/b handler (or make both renderers emit the same product-card element tag/classes)\n- Update the ec/README.md conventions to match the markup actually served to text/html clients (div.product-card / div.product-title)"
    },
    {
      "title": "Misleading empty-match hint suggests htmlsnapshot get text queries 'the live accessibility tree'",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "browser4-cli htmlsnapshot get all text \"[class*=\\\"product-title\\\"]\" (with a mangled/empty selector) → 'No elements matched … Alternatively, use get text \"…\" to query the live accessibility tree instead of the stored snapshot.'",
      "expected": "Accurate guidance: get/get all query the stored HTML snapshot via CSS selectors, not the accessibility tree.",
      "actual": "The hint claims get text queries the live accessibility tree, which is incorrect (that is snapshot/eval territory). Combined with echoing the half-parsed selector, the recovery message actively misleads.",
      "rootCause": "Copy/pasted or outdated hint text in the empty-result path; the selector truncation is a downstream effect of the PowerShell escaping issue but the 'live accessibility tree' wording is independently wrong.",
      "codePointer": "cli/browser4-cli/src/ (htmlsnapshot get empty-result error/hint text)",
      "suggestion": "- Replace the 'live accessibility tree' wording with accurate guidance: verify the selector with htmlsnapshot grep, or re-capture with htmlsnapshot\n- Don't echo a truncated selector in the suggestion line; show the exact selector the CLI received"
    }
  ],
  "assessment": {
    "completionStatus": "Successful — all 7 acceptance criteria (AC1–AC7) covering every branch of SKILL.md §4a were completed and verified against MockSite. AC6 (extract) succeeded because the backend server has an LLM key configured (confirmed via doctor), not because the CLI shell had one.",
    "successRate": "95% — all criteria passed; the remaining 5% reflects friction and workarounds required along the way (flaky first load, PowerShell quoting, DOM_FIRST_HREF empty, messy extract output) rather than any failed criterion.",
    "issuesFound": 12,
    "majorBlockers": "None. No criterion was blocked; every issue had a workaround that allowed the task to proceed.",
    "mostConfusingAspects": "PowerShell-specific quoting (unquoted @file splat and \\\" escaping) that breaks otherwise-correct documented commands; DOM_FIRST_HREF returning empty for a class-only selector; SKILL.md asserting htmlsnapshot is stale when it is not; the extract command's Java-wrapper output format; and the CLI silently writing 128 files into the repo root under two inconsistently-named directories.",
    "mostValuableImprovements": "1) Fix the PowerShell quoting story for @file and attribute selectors and surface it in the primary SKILL.md (not just shell-quoting.md). 2) Make DOM_FIRST_HREF consistent (or document DOM_FIRST_ATTR as the canonical href extractor). 3) Correct the §4a/§5 'htmlsnapshot is stale after JS interactions' claim to match actual live-DOM behavior. 4) Return clean structured JSON from extract instead of the {type,description} wrapper with leaked token stats. 5) Make goto's 'Page loaded' detection actually wait for document readiness, and unify/relocate CLI state+snapshot directories.",
    "usabilityRating": 6
  }
}
```
