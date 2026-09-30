---
name: browser4-seo
title: "Browser4 SEO"
description: "Extract and audit SEO metadata from the current browser page. Use when the user wants to check a page's SEO health, review meta tags, Open Graph, Twitter Cards, canonical links, structured data (JSON-LD), or diagnose why a page ranks poorly."
tier: procedure
---

# Browser4 SEO

## Quick Start

```bash
browser4-cli goto "https://example.com"     # load the page to audit
browser4-cli plugin-list                    # confirm browser4-seo is installed AND enabled
# then call seo.extractMeta() — full SEO metadata extraction (see Tools below)
```

Extract and audit SEO metadata from the current page using the `browser4-seo` plugin's two tools.

## When to Use

Use this skill when the user wants to:

- **Check a page's SEO health** — "audit the SEO of this page", "is this page SEO-friendly?"
- **Review meta tags** — "what's the title/description/canonical of this page?"
- **Check Open Graph / Twitter Card** — "does this page have proper social share tags?"
- **Review structured data** — "does this page have JSON-LD schema markup?"
- **Diagnose ranking problems** — "why isn't this page ranking?" (SEO issues are a common cause)

Do NOT use this skill for:

- General content extraction — use the `browser4-cli` HTML snapshot / X-SQL instead.
- Performance auditing (Core Web Vitals) — this plugin only covers SEO metadata, not performance metrics.

## How It Works

The `browser4-seo` plugin exposes two tools that operate on the page currently loaded in the browser session: `seo.extractMeta()` returns every SEO metadata field (title, description, canonical, Open Graph, Twitter Cards, JSON-LD), and `seo.checkIssues()` compares the page against SEO best practices and returns a health report.

## Patterns

### 1. Quick SEO health check

```text
seo.checkIssues()   # returns a prioritized issue list
```

### 2. Full metadata extraction

```text
seo.extractMeta()   # all fields: title, description, canonical, OG, Twitter, JSON-LD
```

### 3. Diagnose poor ranking

```text
seo.checkIssues()   # start with the issue report, then fix the top items
```

## Flags

Neither `seo.extractMeta()` nor `seo.checkIssues()` takes arguments — both operate on the page currently loaded in the browser session.

## Errors & Recovery

| Symptom | Cause | Fix |
|---------|-------|-----|
| Tool call fails with "plugin not found" | `browser4-seo` not installed | `browser4-cli plugin-list`; install the plugin |
| Plugin installed but `seo.*` tools unavailable | Plugin is opt-in (default-disabled) | Enable it: `browser4.plugins.enable=browser4-seo` (or `browser4.plugins.enable-all=true`) and restart |
| `extractMeta` returns empty fields | No page loaded in the session | `browser4-cli goto <url>` first |
| `checkIssues` reports everything missing | Page is a JS shell | The plugin reads the loaded DOM — ensure the page finished rendering |

## Prerequisites

The `browser4-seo` plugin must be installed AND enabled on the Browser4 server. The plugin is opt-in (`defaultEnabled: false`): installing the JAR alone does not activate it. Enable it with `browser4.plugins.enable=browser4-seo` (or `browser4.plugins.enable-all=true`) and restart. Check with:

```bash
browser4-cli plugin-list
# Look for "browser4-seo" in the output
```

If not installed, see the plugin's README for build and deploy instructions.

## Tools

### `seo.extractMeta()`

Extracts all SEO metadata from the current page. No arguments — operates on the page currently loaded in the browser session.

Returns a JSON object with fields:

| Field | Type | Description |
|-------|------|-------------|
| `url` | String | Final page URL (after redirects) |
| `title` | String? | `<title>` content |
| `description` | String? | `<meta name="description">` |
| `canonical` | String? | `<link rel="canonical">` href |
| `robots` | String? | `<meta name="robots">` content |
| `og` | Object | All Open Graph tags (`og:title`, `og:image`, etc.) |
| `twitter` | Object | All Twitter Card tags |
| `headings` | Object | Count of h1/h2/h3/h4 |
| `images` | Int | Total `<img>` count |
| `imagesWithoutAlt` | Int | Images missing `alt` attribute |
| `wordCount` | Int | Approximate body word count |
| `jsonLd` | Array | Parsed JSON-LD structured data blocks |

### `seo.checkIssues()`

Audits the page and returns a categorized list of SEO issues.

Returns a JSON object with:

| Field | Type | Description |
|-------|------|-------------|
| `issueCount` | Int | Total issues found |
| `errorCount` | Int | Critical issues (e.g., noindex, no title) |
| `warningCount` | Int | Important issues (e.g., no canonical, thin content) |
| `infoCount` | Int | Minor issues (e.g., no Twitter Card) |
| `issues` | Array | Each issue: `{ severity, field, message, value }` |

Severity levels: `error` (must fix) > `warning` (should fix) > `info` (nice to have).

## Workflow

### Example 1: Quick SEO audit

```
1. Navigate to the target page (browser4-cli navigate <url>)
2. seo.checkIssues()                      → get the issue list with severity
3. Summarize errors first, then warnings
4. If user wants detail on a specific field → seo.extractMeta()
```

### Example 2: Full metadata review

```
1. Navigate to the target page
2. seo.extractMeta()                      → full metadata object
3. Present: title, description, canonical, OG, Twitter, JSON-LD
4. Flag any missing or suspicious values
```

### Example 3: Compare before/after a fix

```
1. seo.checkIssues()                      → baseline (note error/warning counts)
2. (user or agent fixes the page source)
3. Refresh the page
4. seo.checkIssues()                      → compare counts to verify the fix
```

This last workflow is the **browser-first feedback loop**: change code → refresh → re-audit → confirm.

## Tips

- Always run `seo.checkIssues()` first — it gives a quick summary. Use `seo.extractMeta()` only when you need the raw values.
- The `noindex` robots directive is an `error`-level issue — flag it immediately, as it blocks the page from search engines entirely.
- `imagesWithoutAlt` > 0 is common but easy to fix — suggest adding alt text.
- JSON-LD count of 0 is only `info` severity — not all pages need structured data, but e-commerce and article pages benefit from it.
