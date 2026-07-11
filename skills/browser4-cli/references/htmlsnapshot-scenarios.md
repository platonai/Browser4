---
title: "HTML Snapshot — Real-World Scenarios"
description: "Index of practical end-to-end recipes for htmlsnapshot: extraction, Amazon workflows, audit & compliance, and advanced discovery. Includes patterns & tips, command selection guide, and tested/verified results."
tier: decision
---

# HTML Snapshot — Real-World Scenarios

Practical, end-to-end recipes using the `htmlsnapshot` family of commands. Each scenario is self-contained: you can adapt the CSS selectors and X-SQL queries to your own target pages.

> **Note:** CSS selectors are tied to live websites and may break over time. See [SKILL.md §5](../SKILL.md#5-critical-warnings). Always discover current selectors with `htmlsnapshot inspect` or `htmlsnapshot summary` before extraction.

## Scenario Index

### Data Extraction
Scenarios for extracting structured data from listing and detail pages using `get`, `query`, and `export`.

| # | Scenario | Primary Commands | Domain |
|---|----------|------------------|--------|
| 1 | E-Commerce Product Monitoring | `get`, `query` | Retail |
| 2 | News Headline Aggregator | `htmlsnapshot`, `get`, `export` | Media |
| 5 | Job Board Scraper | `get`, `query`, `grep` | HR / Recruiting |
| 7 | Academic Literature Metadata Extraction | `query` (X-SQL) | Research |
| 8 | Real Estate Listing Monitor | `get`, `query` | Property |

📄 **[View extraction scenarios →](htmlsnapshot-scenarios-extraction.md)**

### Amazon Discovery & Extraction
Complete Amazon workflows emphasizing discovery-first patterns: use `summary` and `inspect` to find selectors before committing to extraction queries.

| # | Scenario | Primary Commands | Key Pattern |
|---|----------|------------------|-------------|
| 14 | Amazon Home Page Discovery | `summary`, `inspect` | Structure overview before interaction |
| 15 | Amazon Search Results Extraction | `summary`, `inspect`, `get all`, `query` | Discovery → validate → extract |
| 16 | Amazon Product Detail Extraction | `summary`, `inspect`, `get`, `grep`, `export` | Full product page data collection |

📄 **[View Amazon scenarios →](htmlsnapshot-scenarios-amazon.md)**

### Audit, Compliance & Monitoring
Scenarios for auditing pages, tracking pricing changes, verifying compliance, and debugging incidents — grep-heavy workflows with CI integration.

| # | Scenario | Primary Commands | Domain |
|---|----------|------------------|--------|
| 3 | SEO Health Audit | `query` (X-SQL), `grep` | Marketing |
| 4 | Competitive Price Tracker | `query` (X-SQL + load options) | Business |
| 6 | Compliance Verification | `get`, `export`, `grep` | Legal / Governance |
| 9 | CI/E2E Visual Regression Snapshot | `htmlsnapshot`, `export`, `grep` | Engineering |
| 12 | Incident Response & Debugging | `grep` | Engineering / SRE |

📄 **[View audit scenarios →](htmlsnapshot-scenarios-audit.md)**

### Advanced Discovery & Automation
Scenarios for discovering page structure, finding selectors on unknown pages, and integrating HTML snapshots with agent workflows.

| # | Scenario | Primary Commands | Domain |
|---|----------|------------------|--------|
| 10 | Agent-Assisted Form Discovery | `get` + Agent CLI | AI / Automation |
| 11 | Page Structure Analysis | `summary` | Research / Auditing |
| 13 | Selector Discovery for Unknown Pages | `inspect` | Research / Scraping |

📄 **[View advanced scenarios →](htmlsnapshot-scenarios-advanced.md)**

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

**X-SQL `query` note:** The X-SQL query path (`htmlsnapshot query`) previously had a Jackson serialization issue with `java.time.Instant` fields in `ScrapeResponse`. This was fixed by using `pulsarObjectMapper()` (which includes `JavaTimeModule`) instead of `jacksonObjectMapper()` in `MCPToolController.kt`. Verified 2026-07-11 — no further action needed.

---

## See Also

- [HTML Snapshot Reference](htmlsnapshot.md) — full command reference for `get`, `query`, `grep`, `summary`, `export`, `inspect`
- [CSS Selector Bridge](css-selector-bridge.md) — bridging interactive snapshot refs to HTML snapshot CSS selectors
- [X-SQL Reference](x-sql.md) — DOM and string function reference for `htmlsnapshot query`
- [PowerCSS :expr()](power-dom.md) — visual feature selectors for resilient element targeting
- [SKILL.md](../SKILL.md) — Browser4 CLI automation skill overview
