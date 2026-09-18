Ignoring 13 permissions.allow entries from .claude/settings.json: this workspace has not been trusted. Run Claude Code interactively here once and accept the trust dialog, or set projects["D:/workspace/Browser4/Browser4-4.13"].hasTrustDialogAccepted: true in C:\Users\pereg\.claude.json.
"deepseek-v4-flash" isn't described by this version's model catalog; update Claude Code, or map it with behavesAs on a modelPicker row (or modelOverrides, if it is a provider id of a model this version knows). Until then auto-compact keeps this session within 200k tokens (the context window it assumes); if the model accepts more, append [1m] to the model name for 1M, or set CLAUDE_CODE_MAX_CONTEXT_TOKENS to its real window; CLAUDE_CODE_DISABLE_UNKNOWN_MODEL_WINDOW_ENFORCEMENT=1 restores the previous wait-for-the-API behavior.
[claude-code:unrecognized_model] {"model":"deepseek-v4-flash","query_source":"generate_session_title"}
# Usability Evaluation Report — browser4-cli X-SQL E-commerce Extraction

## A. Task Result

The task was **completed successfully**. All 9 steps were executed against MockSite (`http://localhost:18080/ec/b?node=1292115012`, "Category: Electronics", 6 products):

1. **Navigation** — `./b4w.ps1 goto "http://localhost:18080/ec/b?node=1292115012"` auto-started the backend and opened the session (first-run latency absorbed by the daemon startup; no manual server setup needed).
2. **HTML snapshot** — `htmlsnapshot` capture succeeded (9 KB, 7 images, 22 interactive elements); exported to `.test-sessions/electronics-page.html`.
3. **Selector discovery** — `htmlsnapshot inspect --max 3 --depth 3` auto-discovered `.product-card` (6 matches) and suggested `div.product-title`, `a.product-link`, `img.product-img`, plus sample-structure selectors `div.product-price` / `.product-rating` and `[data-category-id="1292115012"]`.
4–6. **X-SQL with DOM + STR + ARRAY functions** — the final query (`.test-sessions/xsql-final.sql`) extracted per-product: trimmed title (`DOM_FIRST_TEXT` + `STR_TRIM`), price as number (`DOM_FIRST_FLOAT(DOM,'div.product-price',0.0)` → 899.99, 199.99…), absolute detail link (`DOM_FIRST_HREF`), image URL (`DOM_FIRST_IMG`), data attributes on the card (`DOM_FIRST_ATTR(DOM, ':root', 'data-category-id')`) and rating (`data-rating`), uppercase-normalized title (`STR_UPPER_CASE`), numbers-from-text (`STR_FIRST_FLOAT` over `"$899.99"` and `"4.6 (521)"` → 4.6), truncated display title (`STR_ABBREVIATE`), and a badge fallback chain (`ARRAY_FIRST_NOT_BLANK(MAKE_ARRAY(span.badge → .product-badges → .product-rating → 'no-badge'))` wrapped in `STR_DEFAULT_IF_BLANK`). A second run without the price filter proved the fallback fires on the 3 badge-less cards (yields rating text like `4.3 (901)`).
7. **PowerCSS `:expr()`** — used in the FROM clause: `DOM_LOAD_AND_SELECT(@url, '.product-card:expr(width >= 150 && height >= 200)')` — accepted and returned all 6 rows.
8. **WHERE / ORDER BY / LIMIT** — `WHERE CAST(DOM_FIRST_FLOAT(...) AS DOUBLE) >= 50.0` (the documented CAST workaround), `ORDER BY … ASC`, `LIMIT 3` → returned Wireless Headphones ($199.99), Smartphone ($599.00), 4K OLED TV ($899.99), correctly sorted.
9. **Data review** — all 11 columns populated correctly across runs; exit codes, table/JSON output formats and the live-DOM (URL-less) query path verified.

## B. Execution Trace

| Step | Command | Outcome |
|---|---|---|
| Prep | `pwd`, `mkdir -p .test-sessions` | Repo root confirmed; scratch dir ready |
| Docs | `./b4w.ps1 help` + full `SKILL.md` + references (`htmlsnapshot.md`, `x-sql*.md`, `power-dom.md`) | Learned capture→inspect→query workflow; noted quoting, CAST, `:expr`, and `DOM_FIRST_HREF` caveats |
| 1 | `./b4w.ps1 goto "http://localhost:18080/ec/b?node=1292115012"` | Session auto-created, page loaded (title "Category: Electronics") |
| 2 | `./b4w.ps1 htmlsnapshot` → `htmlsnapshot export --file .test-sessions/electronics-page.html` | Stored snapshot + local copy for structure analysis |
| 3 | `./b4w.ps1 htmlsnapshot inspect --max 3 --depth 3` | Auto-discovered `.product-card`; selectors identified |
| Probe | `.test-sessions/xsql-probe.sql` | Verified `:root` works for card's own attribute; **found `DOM_FIRST_HREF` class-only form returns empty**; `data-rating` reachable |
| 4–9 | `.test-sessions/xsql-final.sql` → `htmlsnapshot query … --sql @… --format table` | 3 rows, all fields populated, sorted, limited |
| Verify | Fallback-demo variant (no WHERE; ORDER BY title) | 6 rows; badge fallback chain visibly fires on badge-less cards |
| Negative probes | `.test-sessions/xsql-wrong-quotes.sql`, `.test-sessions/xsql-img-expr.sql` | Double-quote error → excellent 417 message with "Fix:" hint (exit 1); `DOM_FIRST_IMG`+`:expr` → **silent empty column, exit 0** |
| Code forensics | greps/reads in `main.rs`, `help.rs`, `MCPToolController.kt` | Confirmed warning heuristic, inspect coverage math, and suggestion-list omission of `.product-price`/`.product-rating` |

**Key decisions:** followed the documented file-based SQL pattern (`--sql @file`) from the start (docs warn about Windows quoting); used `CAST(... AS DOUBLE)` for numeric `WHERE` (documented trap); used tag-qualified `a.product-link` for `DOM_FIRST_HREF` (documented caveat); used the URL-explicit query form to mirror the docs' quickstart. **Workarounds:** none required for the task itself; only the two documented workarounds above were applied pre-emptively.

```json
{
  "issues": [
    {
      "title": "DOM_FIRST_IMG with a PowerCSS :expr() selector silently drops the image column while exiting 0",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "Write a query using DOM_FIRST_IMG(DOM, 'img:expr(width > 200)') against any page with matching wide images (see .test-sessions/xsql-img-expr.sql) and run: ./b4w.ps1 htmlsnapshot query \"http://localhost:18080/ec/b?node=1292115012\" --sql @.test-sessions/xsql-img-expr.sql --format table; echo $?",
      "expected": "Return the image src for every match, or fail with an explicit error and non-zero exit code.",
      "actual": "img_filtered is empty for every row, output says '2 rows returned.', and the exit code is 0. The only mitigation is a warning banner printed after the result table (text-scan heuristic). Scripted/--quiet/--json consumers cannot detect the data loss; the empty column is indistinguishable from a legitimate no-match.",
      "rootCause": "The image-scanning path behind the DOM_*_IMG helpers does not evaluate :expr(...) and matches nothing (the doc comment in SKILL.md and x-sql.md:349 names this). The behavior is documented in several reference files, but the engine neither errors nor returns a non-200 envelope, so 'success' semantics are preserved. The CLI-level warning at main.rs:7258 is the only guard and it is a string heuristic (see next issue).",
      "codePointer": "cli/browser4-cli/src/main.rs:6975 (sql_uses_dom_first_img_expr) and :7258 (warning emission); the failing selector evaluation lives in the external pulsar-ql DomSelectFunctions image helpers (ai.platon.pulsar.ql) which ignore :expr.",
      "suggestion": "- Make the backend DOM_*_IMG selector path evaluate :expr, or throw a descriptive X-SQL error (417) when :expr appears inside a DOM_*_IMG selector argument instead of silently returning nothing.\n- If the engine limitation must stay, at minimum return a per-cell placeholder/error marker and a non-zero exit code so pipelines notice.\n- Keep the CLI warning but make it precise (see next issue) so healthy queries are not flagged."
    },
    {
      "title": "False-positive 'DOM_FIRST_IMG ignores :expr' warning fires on fully successful queries that use :expr only in the FROM clause",
      "severity": "Low",
      "category": "Product",
      "reproduction": "Run .test-sessions/xsql-final.sql (DOM_FIRST_IMG(DOM, 'img.product-img') — plain selector — plus :expr(width >= 150 && height >= 200) in DOM_LOAD_AND_SELECT's FROM selector): ./b4w.ps1 htmlsnapshot query \"http://localhost:18080/ec/b?node=1292115012\" --sql @.test-sessions/xsql-final.sql --format table",
      "expected": "No warning: the :expr filter is only in the FROM clause where it IS evaluated (x-sql.md:349), and image_url is correctly populated for all rows.",
      "actual": "Every row has a correct image_url, yet the CLI appends '⚠️ DOM_FIRST_IMG does not evaluate PowerCSS :expr(...) filters — a filtered selector silently matches nothing'. The warning is factually wrong for this query and erodes trust in the tool's diagnostics.",
      "rootCause": "sql_uses_dom_first_img_expr (main.rs:6975-6981) tests string co-occurrence: upper.contains(\":EXPR(\") && (contains DOM_FIRST_IMG/DOM_NTH_IMG/DOM_ALL_IMGS) across the whole SQL text. It never checks whether :expr is actually an argument of the IMG call. Unit tests cover pure positive and pure negative cases but not the mixed case (DOM_FIRST_IMG with a plain selector + :expr elsewhere), which is exactly the composition SKILL.md's own quickstart template produces.",
      "codePointer": "cli/browser4-cli/src/main.rs:6975-6981 (fn sql_uses_dom_first_img_expr)",
      "suggestion": "- Parse the argument span of each DOM_FIRST_IMG/DOM_NTH_IMG/DOM_ALL_IMGS call (balanced parentheses) and flag only when :expr appears inside that span.\n- Add a unit test for the mixed case: DOM_FIRST_IMG(DOM, 'img.product-img') with :expr in DOM_LOAD_AND_SELECT must not warn."
    },
    {
      "title": "htmlsnapshot inspect never suggests recurring class selectors for elements that carry ids — .product-price and .product-rating are missing from 'Suggested selectors' on an e-commerce page",
      "severity": "Medium",
      "category": "Product",
      "reproduction": "1) ./b4w.ps1 goto \"http://localhost:18080/ec/b?node=1292115012\"  2) ./b4w.ps1 htmlsnapshot  3) ./b4w.ps1 htmlsnapshot inspect \".product-card\"  → sample structures show div#product-price-B0E00000X.product-price / div#product-rating-*.product-rating, but 'Suggested selectors' contains only div.product-title, a.product-link, img.product-img and (3/6) div.product-badges/span.badge — no .product-price, no .product-rating, although they occur in 6/6 cards. 4) ./b4w.ps1 htmlsnapshot inspect \".product-price\" (6 matches) → suggestion list is empty except bare-tag 'div'.",
      "expected": "Class selectors recurring in 100% of analyzed matches (div.product-price, div.product-rating) should be suggested for extraction, like div.product-title is. The price selector is the primary field an e-commerce extraction query needs (SKILL.md's own quickstart extracts '.price').",
      "actual": "Id-bearing leaf data elements are absent from the ranked suggestions; they surface only as per-card unique-id singletons (#product-B0E000001 div#product-price-B0E000001.product-price …) in the raw JSON's singletonSuggestions — useless for row-wise extraction — and the CLI does not render singletonSuggestions at all. The omission is silent: no error, output looks complete. Verified in both human and raw JSON output (--json).",
      "rootCause": "In inspectDocument (MCPToolController.kt:1723-1832) the candidate walk records plain 'tag.class' candidates plus compound 'tag.class#id' candidates for elements with ids; the inline comment (lines 1744-1749) says unique template ids should fall out at the threshold while the recurring plain class survives. Empirically the plain class candidate for id-bearing elements never reaches the ranked list (count-6 div.product-price loses to count-3 div.product-badges, which is impossible by the stated score formula), so a filtering/dedup step in the walk or ranking must be dropping it. Pinpointing the exact step needs a debug trace (candidate dedupe via the SelectorCandidate data class or the 'seen' per-match set is the prime suspect).",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt:1723-1832 (inspectDocument candidate walk) — drop suspected around lines 1740-1755 or 1812-1826",
      "suggestion": "- Trace candidateStats for 'div.product-price' inside inspectDocument with a unit test that asserts an id-bearing class element yields a surviving plain class suggestion (fixture: the mock e-commerce card).\n- Fix the walk so plain 'tag.class' candidates of id-bearing elements are counted independently of their compound unique-id form.\n- Consider rendering singletonSuggestions in the CLI output, since backend already computes price/id elements that the CLI currently hides."
    },
    {
      "title": "CLI subcommand help for `htmlsnapshot query` contradicts the reference docs about what data the query runs against",
      "severity": "Medium",
      "category": "Documentation",
      "reproduction": "Run: ./b4w.ps1 htmlsnapshot --help  → 'htmlsnapshot query [url]   Run X-SQL against the HTML snapshot stored in Browser4's page storage via the scrape API.' Then compare with skills/browser4-cli/references/htmlsnapshot.md ('query never reads the stored htmlsnapshot cache…') and the main help text, and with observed behavior (a URL-less query returns the current live page state).",
      "expected": "All help surfaces should agree on the data source semantics that SKILL.md stresses as fundamental: no-URL/current-URL → live DOM; other URL → independent fetch.",
      "actual": "The subcommand help describes query as reading the stored snapshot, which the reference docs explicitly deny; main help (b4w.ps1 help) already describes the correct live-DOM semantics, so users get contradictory guidance depending on which help surface they read. A user deciding between 'capture then get' vs 'query' on this basis can draw the wrong conclusion about freshness/login state.",
      "rootCause": "Stale description string in the CLI help catalog that predates the live-DOM seeding behavior; help.rs was not updated when the query path changed.",
      "codePointer": "cli/browser4-cli/src/help.rs:1649 (htmlsnapshot query description string)",
      "suggestion": "- Rewrite the string to: 'Run X-SQL. Without a URL (or for the current page URL) the query is seeded from the session's LIVE page first; an explicit different URL is fetched independently. Does not read the stored htmlsnapshot capture cache.'\n- Grep the help catalog for other stale 'page storage' claims about query."
    },
    {
      "title": "htmlsnapshot inspect coverage fractions (N/N) are computed over the analyzed subset, which reads as full-set coverage when --max truncates",
      "severity": "Low",
      "category": "UX",
      "reproduction": "After capture run: ./b4w.ps1 htmlsnapshot inspect --max 3 --depth 3  → header '(6 matches, 3 analyzed)'; rows then print '3/3 (100%) div.product-title', '2/3 (67%) div.product-badges' — but the page actually has badges on 3 of 6 cards, so 67% of the analyzed subset is not the true 50% coverage of the set a user will extract from.",
      "expected": "Row percentages/denominators should be unambiguous: either computed over the total match count, or explicitly labeled as '2 of 3 analyzed' so users don't misjudge selector robustness across the full result set.",
      "actual": "Rows show 'N/N (%)' where the denominator is the (possibly capped) analyzed count, while the heading and narrative emphasize total matches (6). The disclosure exists ('It analyzed 3 of the 6 occurrences') but the per-row fractions still read as full coverage; with --max used per the task guidance, every row shows a perfect 100% ceiling that later proves wrong on unseen matches.",
      "rootCause": "Backend computes coverage as count/matches.size where matches is the analyzed (post-cap) list (MCPToolController.kt:1901), and the CLI renders analyzed as the denominator (main.rs:8111).",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt:1901 (coverage = stats.count * 100.0 / matches.size); cli/browser4-cli/src/main.rs:8111",
      "suggestion": "- When --max < total matches, compute counts for candidate selectors across ALL matches (cheap class/id string counting) and report coverage over the full matchCount, capping only the expensive depth-walk samples.\n- Otherwise label the rows explicitly, e.g. '2/3 analyzed (67% of 3)' or add a footnote row: 'coverage measured over the 3 analyzed of 6 matches'."
    },
    {
      "title": "DOM_FIRST_HREF silently returns an empty string for class-only selectors while the tag-qualified form works",
      "severity": "Low",
      "category": "Reliability",
      "reproduction": "Probe query (xsql-probe.sql): SELECT DOM_FIRST_HREF(DOM, 'a.product-link') AS href_tagged, DOM_FIRST_HREF(DOM, '.product-link') AS href_class FROM DOM_LOAD_AND_SELECT(@url, '.product-card') → href_tagged = 'http://localhost:18080/ec/dp/B0E000001', href_class = '' (exit 0).",
      "expected": "Both forms address the same anchor and should return the same href; at minimum a class-only selector should not fail silently.",
      "actual": "Class-only selector yields empty strings with a successful envelope and exit 0 — indistinguishable from 'no link in card'. A new user who discovers '.product-link' via inspect and writes DOM_FIRST_HREF(DOM, '.product-link') (class-only, mirroring DOM_FIRST_TEXT usage) loses the whole column without any error.",
      "rootCause": "The href-scanning scalar function apparently requires an anchor tag-name context to resolve (external pulsar-ql implementation, referenced in the x-sql.md note: 'DOM_FIRST_HREF(DOM, sel) can return an empty string for a class-only selector while the tag-qualified form a.product-link works'). The x-sql.md:332 note documents the symptom, but the failure mode stays silent and the note is buried in a catalog index rather than the quick-reference/common-mistakes tables.",
      "codePointer": "External ai.platon.pulsar.ql DomFunctions/DomSelectFunctions href helper; doc note at skills/browser4-cli/references/x-sql.md:332",
      "suggestion": "- Fix the engine to accept class-only descendant selectors for href extraction, or raise a descriptive error instead of returning ''.\n- Promote the caveat into SKILL.md's 'Common mistakes and solutions' table (symptom: DOM_FIRST_HREF returns empty for .class selectors) and standardize the extraction templates on DOM_FIRST_ATTR(DOM, sel, 'href') / DOM_FIRST_HREF(DOM, 'a.<class>')."
    },
    {
      "title": "MAKE_ARRAY is load-bearing in every ARRAY-function example but is never documented as a function",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "Read skills/browser4-cli/references/x-sql-array-functions.md: every ARRAY_FIRST_NOT_BLANK / ARRAY_FIRST_NOT_EMPTY example calls MAKE_ARRAY(...), yet the page's Quick Index lists only '3 functions' (ARRAY_JOIN_TO_STRING, ARRAY_FIRST_NOT_BLANK, ARRAY_FIRST_NOT_EMPTY) and x-sql.md's ARRAY namespace table likewise omits it.",
      "expected": "MAKE_ARRAY should be listed with its signature and semantics (including NULL/blank handling) wherever ARRAY functions are documented, so users know it exists and how to build the fallback chains the docs promote.",
      "actual": "MAKE_ARRAY appears only inside examples with no definition, source, or entry in any function index. It works (verified: the fallback chains ran), so the gap is purely discoverability — a new user cannot tell if it is an H2 built-in, a custom alias, or a typo, and has no documented way to construct arrays other than copying examples.",
      "rootCause": "Documentation gap: array-function reference and master index were never updated with the MAKE_ARRAY helper that the examples depend on.",
      "codePointer": "skills/browser4-cli/references/x-sql-array-functions.md (add a MAKE_ARRAY section); skills/browser4-cli/references/x-sql.md ARRAY namespace table",
      "suggestion": "- Add a documented MAKE_ARRAY(values...) section with signature, NULL handling, and a one-line note on the equivalent H2 literal syntax if any.\n- Add it to the x-sql.md ARRAY namespace table so the master function index is complete."
    }
  ],
  "assessment": {
    "completionStatus": "Successful — all 9 task steps completed: navigation, capture, selector discovery (inspect --max 3 --depth 3), a full X-SQL query using every requested DOM/STR/ARRAY function, PowerCSS :expr() in the FROM clause, WHERE/ORDER BY/LIMIT, and review of the extracted data (two output variants plus negative probes for error quality).",
    "successRate": "95 — one probe column (DOM_FIRST_HREF class-only form) returned empty and one final-query warning was spurious, but neither affected the required deliverables; every documented function executed as specified on the first run.",
    "issuesFound": 7,
    "majorBlockers": "None. The daemon auto-started cleanly, MockSite was reachable, and no command needed a retry. The documented CAST workaround for numeric WHERE and the tag-qualified DOM_FIRST_HREF form were applied pre-emptively after reading the references.",
    "mostConfusingAspects": "1) Silent empty results with exit code 0 for DOM_FIRST_IMG + :expr and DOM_FIRST_HREF with a class-only selector — data loss looks like success. 2) htmlsnapshot inspect's 'Suggested selectors' omitting .product-price/.product-rating (id-bearing elements) even though they recur in every card — the exact selectors the task needed. 3) The ⚠️ warning banner printed after fully successful queries when :expr appears anywhere in the SQL. 4) Contradictory descriptions of htmlsnapshot query's data source between subcommand help and reference docs.",
    "mostValuableImprovements": "1) Fix inspectDocument so recurring class selectors of id-bearing elements (.product-price) reach 'Suggested selectors' — the flagship discovery path for e-commerce extraction. 2) Make the DOM_*_IMG/:expr and class-only DOM_FIRST_HREF silent failures loud (error or non-zero exit) or precisely warned, so no column can vanish silently. 3) Scope the :expr warning heuristic to the DOM_FIRST_IMG argument span to eliminate false positives. 4) Align the htmlsnapshot query help string with the documented live-DOM semantics.",
    "usabilityRating": 7
  }
}
```

## D. Overall Assessment

- **Completion status:** Successful — task executed end-to-end with no blockers and no retries needed.
- **Estimated success rate:** ~95% (the task itself 100%; the evaluation probes deliberately exercised failure paths, two of which misbehaved as described above).
- **Issues found:** 7 (1 High, 2 Medium, 4 Low — plus the Medium discovery-gap issue ranked as Product).
- **Major blockers:** none.
- **Most confusing aspects for a first-time user:** the tool is *extremely* well documented (SKILL.md + 30 reference files) but has a consistent failure personality: **silent empty results with exit 0** for several DOM scalar-function edge cases (`DOM_FIRST_IMG`+`:expr`, class-only `DOM_FIRST_HREF`), a **selector-discovery gap on the flagship path** (inspect omits `.product-price`), and one **spurious warning** on healthy queries. The H2 dialect traps (double quotes, `CAST` for numeric `WHERE`, `@url` unquoted) are heavily documented, but only reachable by reading the deep references — and the docs' own warning banners proved imperfect.
- **Most valuable improvements:** the inspect price-selector fix, precise/error-level handling of the silent `:expr` and href failures, and help-text consistency.
- **Overall usability rating: 7/10** — strong docs, smooth first-run experience, excellent error-message quality for the documented mistakes (the 417 "Fix:" hint for double-quoted selectors is a model to copy), but the silent data-loss modes and the selector-discovery gap cost it points. For an agent following SKILL.md, the task is very doable; for a human in a terminal, the raw-JSON default of `query` and the deep multi-file reference structure add friction that the tip system partially compensates for.
