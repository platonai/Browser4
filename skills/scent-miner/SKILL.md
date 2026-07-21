---
name: scent-miner
title: "Scent-Miner — Mine structured data from HTML dumps via WebMiner"
description: "Exports pages from Browser4's webdb, then runs scent-miner (from platonai/web-miner) to extract structured data and generate HTML/Excel output views. Use when asked to mine, scrape, or extract structured data from web pages stored in the web database."
allowed-tools: Bash(./skills/scent-miner/scripts/*), Bash(browser4-cli:*)
tier: decision
---

# Scent-Miner

Scent-miner (WebMiner) from [platonai/web-miner](https://github.com/platonai/web-miner)
analyzes a directory of HTML files, identifies recurring content patterns and
data tables, and exports the results as HTML reports and Excel spreadsheets.
Everything runs locally — no network calls, no data leaves your machine.

This skill orchestrates the full pipeline: export pages from Browser4's
web database via `webdb export`, then run scent-miner over the exported
HTML dump to extract structured data and generate output views.

## When to Use

- "mine data from these URLs"
- "extract structured data from webdb"
- "run scent-miner on these pages"
- "scrape and mine this site"
- "export pages and mine them"
- "generate views from webdb dump"

## Prerequisites

1. **browser4-cli** — installed and available on PATH (`browser4-cli --version`)
2. **Java 17+** — the driver scripts auto-resolve Java (see Java resolution below)
3. **scent-miner.jar** — downloaded automatically from GitHub releases on first
   run, or placed manually at `skills/scent-miner/scripts/scent-miner.jar`
4. **Browser4 backend** — running with a session that has the target pages
   loaded in its web database

### Java resolution order

The driver scripts (`skills/scent-miner/scripts/run-scent-miner.ps1` / `.sh`) resolve
Java automatically:

1. `$JAVA_HOME/bin/java` — if `JAVA_HOME` is set
2. `java` on system `PATH` — if found
3. Browser4 runtime bundle JRE — at `browser4-apps/browser4-bundle/target/runtime-bundle/_work/.../runtime/bin/java`

No manual Java configuration is needed if any of these is available.

## Workflow

### Step 1 — Collect URLs from the user

Ask the user for the set of URLs to mine. Accept:

- A comma-separated list: `"http://a.com,http://b.com"`
- A wildcard for all pages: `"*"`
- A file path containing one URL per line

If the user hasn't provided URLs yet, ask them now. Do not proceed without URLs.

### Step 2 — Export pages from webdb

Run `webdb export` to dump each page's HTML content to a local directory.
URLs are automatically normalized before the database lookup, so the raw
URL the user provides does not need to match the stored key exactly.

```bash
browser4-cli webdb export "<url1>,<url2>,..." <output-dir>
```

Example:

```bash
browser4-cli webdb export "https://example.com,https://example.com/page2" /tmp/webdb-dump
```

Use `"*"` to export all pages in the session's web database:

```bash
browser4-cli webdb export "*" /tmp/webdb-dump
```

The command creates one `.htm` file per exported page, named after the
normalized URL (e.g., `example.com.htm`, `example.com_page2.htm`).

### Step 3 — Run scent-miner over the exported dump

Use the driver script to run scent-miner. It handles Java resolution and
jar download automatically.

**PowerShell:**

```powershell
.\skills\scent-miner\scripts\run-scent-miner.ps1 -InputDir <output-dir>
```

**Bash:**

```bash
./skills/scent-miner/scripts/run-scent-miner.sh --input <output-dir>
```

**With options:**

```powershell
# Limit to 50 pages for a quick test
.\skills\scent-miner\scripts\run-scent-miner.ps1 -InputDir /tmp/webdb-dump -Limit 50

# Focus on a specific content area with a CSS selector
.\skills\scent-miner\scripts\run-scent-miner.ps1 -InputDir /tmp/webdb-dump -ComponentSelector "#mainContent"

# Stricter extraction (validate samples instead of trusting them)
.\skills\scent-miner\scripts\run-scent-miner.ps1 -InputDir /tmp/webdb-dump -NoTrustSamples

# Higher size threshold to skip small stub pages
.\skills\scent-miner\scripts\run-scent-miner.ps1 -InputDir /tmp/webdb-dump -RequireSize 1000000
```

Bash equivalents use `--input`, `--limit`, `--component-selector`,
`--no-trust-samples`, `--require-size`.

### Step 4 — Report results

After scent-miner completes, report to the user:

- How many HTML files were processed (shown in script output)
- Where the output views were written: `<input-dir>-views/views/`
- The HTML report: `<input-dir>-views/views/index.html`
- Excel exports: `<input-dir>-views/views/*.xlsx`

List the output files:

```bash
ls -la <output-dir>-views/views/
```

## Scent-Miner Options Reference

| Flag | Default | Purpose |
|------|---------|---------|
| `--input, -i <path>` | *required* | Directory containing `*.html` / `*.htm` files |
| `--component-selector, -c <css>` | *(none)* | CSS selector for the main content area on each page |
| `--require-size <bytes>` | `500000` | Minimum page size in bytes (smaller pages are skipped) |
| `--limit, -l <N>` | `0` (no limit) | Load at most N pages from the input directory |
| `--no-trust-samples` | off | Validate and clean samples instead of trusting them |

## Output Structure

```
<input-dir>-views/
  └── views/
      ├── index.html          # HTML report of extracted tables
      ├── *.xlsx              # Excel export of tabulated data
      └── ...
```

Open `index.html` in a browser to browse the extracted data, or load the
`.xlsx` files in Excel for further analysis.

## Tips

- **Page size matters** — scent-miner skips files smaller than `--require-size`
  (default 500 KB) to avoid processing error pages, redirects, or stubs.
  Adjust this threshold if your pages are unusually small or large.
- **Component selector** — use `--component-selector` to narrow the mining
  scope to the main content block. For example, `#ppd` works for Amazon
  product pages. Point it at the DOM element that wraps the repeating
  content you want to extract.
- **Trust vs. validate** — by default scent-miner trusts that samples are
  well-formed and uses them directly. Pass `--no-trust-samples` to validate
  and clean every sample first, which produces higher-quality output at the
  cost of slower processing.
- **Quick tests** — use `--limit 10` to mine just a few pages and verify
  the results before running the full dataset.

## Jar Download

The driver scripts automatically download `scent-miner.jar` from
[GitHub Releases](https://github.com/platonai/web-miner/releases) on first run.
The jar is cached at `skills/scent-miner/scripts/scent-miner.jar`.

To use a specific version, pass `--version <ver>` (bash) or
`-ScentMinerVersion <ver>` (PowerShell).

If GitHub is unreachable, download the jar manually and place it at
`skills/scent-miner/scripts/scent-miner.jar`, or pass an explicit path:

```powershell
.\skills\scent-miner\scripts\run-scent-miner.ps1 -InputDir /tmp/dump -ScentMinerJar C:\tools\scent-miner.jar
```

## Troubleshooting

### "Page not found in webdb"

The URL may not have been loaded into the session. Verify:

```bash
browser4-cli webdb normalize "<url>"
```

The export command normalizes URLs before lookup. If the page was loaded
under a different URL, use the normalized form in the export command.

### "No Java runtime found"

Install Java 17+ and set `JAVA_HOME`, or ensure `java` is on `PATH`.
Alternatively, build the Browser4 runtime bundle which includes a bundled JRE:

```bash
.\bin\build.ps1 runtimeBundle
```

### "Failed to download scent-miner.jar"

GitHub may be unreachable. Download manually:
1. Visit https://github.com/platonai/web-miner/releases
2. Download `scent-miner.jar` from the latest release
3. Place it at `skills/scent-miner/scripts/scent-miner.jar`
