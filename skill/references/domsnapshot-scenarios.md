---
title: "DOM Snapshot — Real-World Scenarios"
description: "Index of practical end-to-end recipes for domsnapshot: extraction, Amazon workflows, audit & compliance, and advanced discovery. Includes patterns & tips, command selection guide, and tested/verified results."
---

# DOM Snapshot — Real-World Scenarios

Practical, end-to-end recipes using the `domsnapshot` family of commands. Each scenario is self-contained: you can adapt the CSS selectors and X-SQL queries to your own target pages.

> **⚠️ CSS selectors are tied to live websites — they WILL break over time.** The selectors shown in these examples (e.g. `#productTitle`, `.s-result-item`, `.a-price .a-offscreen`) worked at the time of writing but are not guaranteed to work today. Websites change their HTML structure, class names, and element IDs without notice. **Treat these examples as patterns, not copy-paste recipes.** Before running a scenario, use [`domsnapshot inspect`](domsnapshot-scenarios-advanced.md#13-selector-discovery-for-unknown-pages) or [`domsnapshot summary`](domsnapshot-scenarios-advanced.md#11-page-structure-analysis-with-summary-wpsi) to discover the current selectors on your target page.

## Scenario Index

### Data Extraction
Scenarios for extracting structured data from listing and detail pages using `get`, `query`, and `export`.

| # | Scenario | Primary Commands | Domain |
|---|----------|------------------|--------|
| 1 | E-Commerce Product Monitoring | `get`, `query` | Retail |
| 2 | News Headline Aggregator | `domsnapshot`, `get`, `export` | Media |
| 5 | Job Board Scraper | `get`, `query`, `grep` | HR / Recruiting |
| 7 | Academic Literature Metadata Extraction | `query` (X-SQL) | Research |
| 8 | Real Estate Listing Monitor | `get`, `query` | Property |

📄 **[View extraction scenarios →](domsnapshot-scenarios-extraction.md)**

### Amazon Discovery & Extraction
Complete Amazon workflows emphasizing discovery-first patterns: use `summary` and `inspect` to find selectors before committing to extraction queries.

| # | Scenario | Primary Commands | Key Pattern |
|---|----------|------------------|-------------|
| 14 | Amazon Home Page Discovery | `summary`, `inspect` | Structure overview before interaction |
| 15 | Amazon Search Results Extraction | `summary`, `inspect`, `get all`, `query` | Discovery → validate → extract |
| 16 | Amazon Product Detail Extraction | `summary`, `inspect`, `get`, `grep`, `export` | Full product page data collection |

📄 **[View Amazon scenarios →](domsnapshot-scenarios-amazon.md)**

### Audit, Compliance & Monitoring
Scenarios for auditing pages, tracking pricing changes, verifying compliance, and debugging incidents — grep-heavy workflows with CI integration.

| # | Scenario | Primary Commands | Domain |
|---|----------|------------------|--------|
| 3 | SEO Health Audit | `query` (X-SQL), `grep` | Marketing |
| 4 | Competitive Price Tracker | `query` (X-SQL + load options) | Business |
| 6 | Compliance Verification | `get`, `export`, `grep` | Legal / Governance |
| 9 | CI/E2E Visual Regression Snapshot | `domsnapshot`, `export`, `grep` | Engineering |
| 12 | Incident Response & Debugging | `grep` | Engineering / SRE |

📄 **[View audit scenarios →](domsnapshot-scenarios-audit.md)**

### Advanced Discovery & Automation
Scenarios for discovering page structure, finding selectors on unknown pages, and integrating DOM snapshots with agent workflows.

| # | Scenario | Primary Commands | Domain |
|---|----------|------------------|--------|
| 10 | Agent-Assisted Form Discovery | `get` + Agent CLI | AI / Automation |
| 11 | Page Structure Analysis | `summary` | Research / Auditing |
| 13 | Selector Discovery for Unknown Pages | `inspect` | Research / Scraping |

📄 **[View advanced scenarios →](domsnapshot-scenarios-advanced.md)**

---

## Patterns & Tips

### Combining commands

Most real workflows chain `goto` → `domsnapshot` → `get`/`query`/`export`/`grep`/`summary`. The standalone `domsnapshot` command captures and caches the DOM snapshot; subsequent commands reuse the cached snapshot:

```bash
browser4-cli goto "$URL"
browser4-cli domsnapshot                         # capture + cache
browser4-cli domsnapshot get text "$SELECTOR"    # reads from cache
browser4-cli domsnapshot get html "$SELECTOR"    # reads from cache
browser4-cli domsnapshot grep -i "pattern"       # reads from cache (client-side search)
browser4-cli domsnapshot summary                 # reads from cache (generates WPSI)
browser4-cli domsnapshot inspect ".card"         # reads from cache (suggests selectors)
```

The cache is invalidated by the next `domsnapshot` capture or a page navigation (`goto`, `reload`, etc.).

### Error handling

```bash
# In scripts: check exit codes
if ! browser4-cli domsnapshot get text ".critical-element" > /dev/null 2>&1; then
  echo "ERROR: .critical-element not found on $URL"
  exit 1
fi
```

### Load options reference

Append these to the URL string in `domsnapshot query`:

| Option | Meaning |
|--------|---------|
| `-i 1h` | Cache page for 1 hour |
| `-i 1d` | Cache page for 1 day |
| `-njr 3` | No JavaScript rendering, retry up to 3 times |
| `-njr 0` | Force JS rendering every time |

### Choosing the right command

| Command | Best for |
|---------|----------|
| `get` | One value from one element; simple scripts; raw text/HTML for piping. `get html` paginated at 2K lines by default; `get text` defaults to `--all` (no pagination). |
| `get all` | Multiple values from repeating elements; JSON array output; validating selectors before X-SQL queries |
| `query` | Multiple fields from repeating elements; filtering (`WHERE`/`expr()`); structured tabular output |
| `export` | Saving full HTML for archival, diffing, external tooling, offline analysis |
| `grep` | Presence/absence checks; counting; quick searches with context; CI smoke tests; incident response. Output paginated by default — use `--all` to disable. |
| `summary` | Page discovery before writing selectors; structural audits; LLM-friendly page overviews |
| `inspect` | Discovering unknown CSS selectors on complex pages; finding recurring patterns; selector validation before extraction |

> **Important:** `domsnapshot get` returns **only the first match** (querySelector semantics). For extracting data from multiple elements (e.g., all products on a listing page), use `domsnapshot get all` (returns a JSON array) or `domsnapshot query` with X-SQL's `load_and_select`.

### Command form notes

- The CLI uses the **spaced form**: `browser4-cli domsnapshot get text "h1"`, not the hyphenated `domsnapshot-get`.
- `browser4-cli domsnapshot` (with no subcommand) captures a fresh DOM snapshot and caches it in the backend. Subsequent `get`, `query`, `export`, `grep`, `summary`, and `inspect` calls reuse this cached snapshot — they do **not** re-capture the page. The cache is invalidated by the next `domsnapshot` capture or a page navigation.
- The capture command returns enriched metadata including `imageCount`, `linkCount`, and `interactiveElements` (tag, class, id, aria attributes, bounding-box).
- `grep` performs matching **client-side** by fetching the snapshot HTML then running regex locally — no backend round-trip for the search itself.
- `summary` generates a WPSI YAML file from the cached snapshot — useful as a discovery step before writing selectors.
- `inspect` analyzes DOM structure and suggests CSS selectors for recurring patterns. It is fully deterministic (no AI) and based on structural recurrence across matching elements.
- **Output pagination:** `get html`, `get all html`, and `grep` paginate output by default at 2000 lines per page. `get text` and `get all text` default to `--all` (no pagination). Use `--page N` for subsequent pages, `--page-size N` to change page size, or `--all` to disable pagination entirely. Pagination is automatically skipped in `--json` and `--quiet` modes.
- **X-SQL `load_and_select`** makes its own HTTP request when called via `domsnapshot query` with a URL argument. The initial `goto` + `domsnapshot` capture step is for validating the page and testing selectors with `get`/`inspect` before running the X-SQL query — it does not eliminate the server-side load inside `load_and_select(@url, ...)`.

---

## Tested & Verified

All scenarios using `get`, `export`, `grep`, and `summary` commands have been tested against live websites:

| Scenario | Test Site | Result |
|----------|-----------|--------|
| 1a. Product extraction | books.toscrape.com | ✅ Title, price, availability, image URL |
| 3. SEO metadata | en.wikipedia.org | ✅ Title, H1, meta description, canonical URL |
| 3e. Grep-based SEO checks | en.wikipedia.org | ✅ `-c`, `--selector`, `-l` all functional |
| 5. Listing extraction | books.toscrape.com | ✅ Product title and price (single-element) |
| 6. Compliance verification | en.wikipedia.org | ✅ Footer link extraction, element presence check |
| 6c. Grep compliance checks | en.wikipedia.org | ✅ `-l`, `-F`, `--selector footer` functional |
| 9. Export & archival | Multiple sites | ✅ Pretty-formatted HTML with metadata |
| 9d. Grep smoke tests | Multiple sites | ✅ `-l` pass/fail, `-C` context, `--selector` |
| 10. Form discovery | httpbin.org/forms/post | ✅ Full form HTML with all input fields |
| 11. Summary (WPSI) | en.wikipedia.org | ✅ YAML output with headings, stats, keyContent |
| 12. Grep incident response | Multiple sites | ✅ `-i`, `-v`, `-C`, `-F`, `--selector` all functional |

**X-SQL `query` note:** The X-SQL query path (`domsnapshot query`) has a known Jackson serialization issue with `java.time.Instant` fields in `ScrapeResponse`. A fix has been applied in `MCPToolController.kt` (using the Spring-configured `ObjectMapper` with `JavaTimeModule` instead of `jacksonObjectMapper()`). This requires a server rebuild to take effect.

---

## See Also

- [DOM Snapshot Reference](domsnapshot.md) — full command reference for `get`, `query`, `grep`, `summary`, `export`, `inspect`
- [CSS Selector Bridge](css-selector-bridge.md) — bridging interactive snapshot refs to DOM snapshot CSS selectors
- [X-SQL Reference](x-sql.md) — DOM and string function reference for `domsnapshot query`
- [PowerCSS :expr()](power-dom.md) — visual feature selectors for resilient element targeting
- [Polite Scraping](polite-scraping.md) — rate limiting and CAPTCHA avoidance
- [Error Handling](error-handling.md) — common failure modes and recovery patterns
- [SKILL.md](../SKILL.md) — Browser4 CLI automation skill overview
