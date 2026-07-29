---
title: "HTML Snapshot Scenarios — Audit, Compliance & Monitoring"
description: "Recipes for SEO health audits, competitive price tracking, compliance verification, CI/E2E regression snapshots, and incident response debugging using grep, query, export, and load options."
tier: procedure
---

# HTML Snapshot Scenarios — Audit, Compliance & Monitoring

Practical recipes for auditing web pages, tracking competitive pricing, verifying compliance requirements, running CI regression checks, and debugging incidents — using `htmlsnapshot grep`, `query`, `export`, and load options.

> **Note:** CSS selectors are tied to live websites and may break over time. See [SKILL.md §5](../SKILL.md#5-critical-warnings). Treat these examples as patterns, not copy-paste recipes.

> **Parent document:** [htmlsnapshot-scenarios.md](htmlsnapshot-scenarios.md) — full scenario index, patterns & tips, and command reference.

## Scenarios

| # | Scenario | Primary Commands | Domain |
|---|----------|------------------|--------|
| 3 | SEO Health Audit | `query` (X-SQL), `grep` | Marketing |
| 4 | Competitive Price Tracker | `query` (X-SQL + load options) | Business |
| 6 | Compliance Verification | `get`, `export`, `grep` | Legal / Governance |
| 9 | CI/E2E Visual Regression Snapshot | `htmlsnapshot`, `export`, `grep` | Engineering |
| 12 | Incident Response & Debugging | `grep` | Engineering / SRE |

---

## 3. SEO Health Audit

**Problem:** An SEO specialist needs to verify that every page on a site has exactly one `<h1>`, all images have `alt` attributes, and no links are broken — across dozens of URLs.

**Why HTML Snapshot:** X-SQL can answer structural questions declaratively. No need to write a custom scraper.

### 3a. Check heading hierarchy

```bash
browser4-cli goto "https://example.com/blog/some-post"

browser4-cli htmlsnapshot query --sql "
  SELECT
    'H1 count' AS check_name,
    CAST(COUNT(*) AS VARCHAR) AS value
  FROM DOM_LOAD_AND_SELECT(@url, 'h1')
  UNION ALL
  SELECT
    'H2 count',
    CAST(COUNT(*) AS VARCHAR)
  FROM DOM_LOAD_AND_SELECT(@url, 'h2')
"
```

### 3b. Find images missing alt text

```bash
browser4-cli htmlsnapshot query --sql "
  SELECT
    DOM_FIRST_ATTR(dom, 'img', 'src') AS image_src,
    DOM_FIRST_ATTR(dom, 'img', 'alt') AS alt_text
  FROM DOM_LOAD_AND_SELECT(@url, 'img:not([alt])')
"
```

### 3c. Extract all meta tags

```bash
browser4-cli htmlsnapshot query --sql "
  SELECT
    DOM_FIRST_ATTR(dom, 'meta[name=description]', 'content') AS meta_description,
    DOM_FIRST_ATTR(dom, 'meta[name=keywords]', 'content') AS meta_keywords,
    DOM_FIRST_ATTR(dom, 'link[rel=canonical]', 'href') AS canonical_url,
    DOM_FIRST_TEXT(dom, 'title') AS title_tag
  FROM DOM_LOAD_AND_SELECT(@url, ':root')
"
```

### 3d. List all outbound links

```bash
browser4-cli htmlsnapshot query --sql "
  SELECT
    DOM_FIRST_TEXT(dom, 'a') AS link_text,
    DOM_FIRST_ATTR(dom, 'a', 'href') AS href,
    DOM_FIRST_ATTR(dom, 'a', 'rel') AS rel
  FROM DOM_LOAD_AND_SELECT(@url, 'a[href^=http]')
"
```

### 3e. Quick grep-based checks

When you don't need structured output, `grep` gives instant answers without writing SQL:

```bash
browser4-cli goto "https://example.com/blog/some-post"
browser4-cli htmlsnapshot

# Count how many <h1> tags exist (SEO: should be exactly 1)
browser4-cli htmlsnapshot grep -c '<h1[>\s]'
# → 1

# Find images missing alt text
browser4-cli htmlsnapshot grep -c '<img[^>]*alt=""'
# → 3  (3 images have empty alt — fix them)

# Check for meta description (pass/fail for CI)
browser4-cli htmlsnapshot grep -l -F '<meta name="description"' | grep -q htmlsnapshot && echo PASS || echo FAIL
# exit code 0 = found

# Count total links on the page
browser4-cli htmlsnapshot grep -c '<a[>\s]'
# → 142

# Scope to <head> only — find all meta tags in one shot
browser4-cli htmlsnapshot grep --selector head '<meta'
# → 5:<meta charset="utf-8">
# → 6:<meta name="viewport" content="width=device-width">
# → 7:<meta name="description" content="...">
```

**Why `grep` wins here:** For presence/absence checks and counting, `grep` is faster to write and runs client-side without touching the X-SQL backend. Use `query` when you need structured extraction (field names, tabular output) and `grep` for quick checks, counting, and debugging.

---

## 4. Competitive Price Tracker

**Problem:** Track pricing changes across 5 competitor product pages. Re-run every 6 hours. Cache the page for 1 hour to avoid hammering the server. Disable JS rendering for speed (prices are in the server-rendered HTML).

**Why HTML Snapshot:** Load options (`-i 1h -njr 3`) control caching and rendering behavior. X-SQL lets you extract exactly the fields you need.

### 4a. Single product query with load options

```bash
# The -i 1h caches the page for 1 hour; -njr 3 skips JS rendering (3 retries)
browser4-cli htmlsnapshot query "https://competitor.com/product/123 -i 1h -njr 3" --sql "
  SELECT
    DOM_BASE_URI(dom) AS url,
    DOM_FIRST_TEXT(dom, '.product-price') AS price,
    DOM_FIRST_TEXT(dom, '.stock-status') AS stock,
    DOM_FIRST_TEXT(dom, 'h1') AS title
  FROM DOM_LOAD_AND_SELECT(@url, 'body')
"
```

### 4b. Batch query across multiple URLs

Save the query to a file:

**pricing.sql:**
```sql
SELECT
  DOM_BASE_URI(dom) AS url,
  DOM_FIRST_TEXT(dom, 'h1') AS product,
  DOM_FIRST_TEXT(dom, '.price') AS price
FROM DOM_LOAD_AND_SELECT(@url, 'body')
```

Then run it against each URL:

```bash
for url in \
  "https://competitor.com/p/123 -i 1h" \
  "https://competitor.com/p/456 -i 1h" \
  "https://competitor.com/p/789 -i 1h"
do
  browser4-cli htmlsnapshot query "$url" --sql @pricing.sql
done
```

### 4c. Cron-driven monitoring

```bash
# In crontab: run every 6 hours at 3 minutes past the hour
3 */6 * * * cd /path/to/project && ./scripts/track-prices.sh >> prices.log
```

---

## 6. Compliance Verification

**Problem:** A compliance officer must verify that every page in a financial-services site displays the required legal disclaimer, cookie consent banner, and accessibility statement link — before every release.

**Why HTML Snapshot:** `get` with CSS selectors returns deterministic pass/fail answers. `export` creates an auditable artifact.

### 6a. Verify required elements exist

```bash
browser4-cli goto "https://bank.example.com/products/savings"
browser4-cli htmlsnapshot

# Query the cached snapshot for required elements
# Check for legal disclaimer
browser4-cli htmlsnapshot get text ".legal-disclaimer"
# Returns the disclaimer text — exit code 0 means it was found

# Check for cookie banner
browser4-cli htmlsnapshot get text "#cookie-consent-banner"
# Returns empty string if missing — check with: [ -n "$result" ]

# Check accessibility statement link
browser4-cli htmlsnapshot get attr "a[href*='accessibility']" href
```

### 6b. Batch verification script

```bash
#!/bin/bash
# verify-compliance.sh — run against a list of URLs
PAGES=(
  "/products/savings"
  "/products/checking"
  "/products/loans"
  "/about/terms"
)

REQUIRED=(
  ".legal-disclaimer"
  "#cookie-consent-banner"
  "a[href*='accessibility']"
  "a[href*='privacy']"
)

for page in "${PAGES[@]}"; do
  browser4-cli goto "https://bank.example.com$page"
  browser4-cli htmlsnapshot

  for selector in "${REQUIRED[@]}"; do
    result=$(browser4-cli htmlsnapshot get text "$selector" 2>/dev/null)
    if [ -z "$result" ]; then
      echo "FAIL: $page — missing $selector"
      exit 1
    fi
  done
  echo "PASS: $page"
done
```

### 6c. Quick compliance check with grep

For fast ad-hoc checks, `grep` answers presence/absence questions without writing scripts:

```bash
browser4-cli goto "https://bank.example.com/products/savings"
browser4-cli htmlsnapshot

# Verify required legal text exists anywhere on the page
browser4-cli htmlsnapshot grep -l -F "FDIC Insured" | grep -q htmlsnapshot && echo "PASS" || echo "FAIL"
browser4-cli htmlsnapshot grep -l -F "Terms and Conditions" | grep -q htmlsnapshot && echo "PASS" || echo "FAIL"

# Check for forbidden content (tracking scripts, data leaks)
browser4-cli htmlsnapshot grep -i 'gtag|fbq|_gaq' && echo "WARNING: trackers found"

# Scope to footer for legal links
browser4-cli htmlsnapshot grep --selector footer -i 'privacy|accessibility|terms'
# → <a href="/privacy">Privacy Policy</a>
# → <a href="/accessibility">Accessibility Statement</a>
# → <a href="/terms">Terms of Service</a>

# Count how many cookie consent elements exist (should be 1)
browser4-cli htmlsnapshot grep -c -F 'cookie-consent'
# → 1
```

**Why `grep` here:** For compliance, you often need to answer "is this text present?" instantly. The `-l` flag (files-with-matches) prints "htmlsnapshot" when matches exist — pipe to `grep -q htmlsnapshot` for a pass/fail exit code. Use `--selector` to scope to specific page regions (footer, nav, main).

### 6d. Archive for audit trail

```bash
browser4-cli goto "https://bank.example.com/products/savings"
browser4-cli htmlsnapshot
browser4-cli htmlsnapshot export --file "compliance-$(date +%Y%m%d)-savings.html"
# Store in versioned S3 bucket for regulatory audit
```

---

## 9. CI / E2E Visual Regression Snapshot

**Problem:** After every frontend deploy, the QA pipeline must capture the DOM state of 10 critical pages and compare them against known-good baselines. The accessibility-tree `snapshot` won't work here — we need the actual DOM structure.

**Why HTML Snapshot:** `htmlsnapshot` captures raw HTML — the source of truth for DOM structure. `export` with `--file` writes it to disk for `diff`. Runs in CI without a display.

### 9a. Capture and diff in CI

```bash
#!/bin/bash
# ci-dom-regression.sh — runs in GitHub Actions / Jenkins

BASELINE_DIR="./snapshots/baseline"
CURRENT_DIR="./snapshots/current"

PAGES=(
  "https://staging.example.com/"
  "https://staging.example.com/products"
  "https://staging.example.com/about"
  "https://staging.example.com/contact"
)

for url in "${PAGES[@]}"; do
  slug=$(echo "$url" | sed 's/[^a-zA-Z0-9]/_/g')
  browser4-cli goto "$url"
  browser4-cli htmlsnapshot
  browser4-cli htmlsnapshot export --file "$CURRENT_DIR/${slug}.html"
done

# Diff current against baseline
diff -r "$BASELINE_DIR" "$CURRENT_DIR" > dom-diff.txt

if [ -s dom-diff.txt ]; then
  echo "DOM regression detected!"
  cat dom-diff.txt
  exit 1
fi

echo "No DOM regression — all pages match baseline."
```

### 9b. Promote current to baseline after approval

```bash
rm -rf ./snapshots/baseline
cp -r ./snapshots/current ./snapshots/baseline
git add ./snapshots/baseline
git commit -m "chore: update HTML snapshot baselines"
```

### 9c. Check specific elements after deploy

```bash
browser4-cli goto "https://staging.example.com/checkout"
browser4-cli htmlsnapshot

# Verify critical elements rendered (queries the cached snapshot)
browser4-cli htmlsnapshot get text "#cart-total"        # Must exist
browser4-cli htmlsnapshot get attr "#checkout-btn" href  # Must be /checkout
browser4-cli htmlsnapshot get text ".item-count"         # Must be > 0
```

### 9d. Fast smoke test with grep

For a lightweight CI smoke test that just checks critical strings are present:

```bash
browser4-cli goto "https://staging.example.com/checkout"
browser4-cli htmlsnapshot

# Verify checkout page has all required sections (exits non-zero if any missing)
browser4-cli htmlsnapshot grep -l -F "Cart Total" | grep -q htmlsnapshot || exit 1
browser4-cli htmlsnapshot grep -l -F "Shipping Address" | grep -q htmlsnapshot || exit 1
browser4-cli htmlsnapshot grep -l -F "Place Order" | grep -q htmlsnapshot || exit 1

# Ensure no error messages leaked to the page
browser4-cli htmlsnapshot grep -i 'error|exception|stack trace' && exit 1

# Scope to <main> to check only the content area
browser4-cli htmlsnapshot grep --selector main -l -F "Order Summary" | grep -q htmlsnapshot || exit 1

echo "Smoke test passed"
```

**Why `grep` for CI:** The `-l` flag prints "htmlsnapshot" when matches exist — pipe to `grep -q htmlsnapshot` for a 0/1 exit code based on match presence. Use `-F` for literal strings (no regex escaping needed) and `--selector` to scope to specific regions.

---

## 12. Incident Response & Debugging with Grep

**Problem:** During an incident, an SRE or developer needs to quickly search a rendered page for error messages, broken elements, leaked secrets, or unexpected content — fast, without writing SQL or loading tools.

**Why HTML Snapshot:** `grep` searches the full HTML snapshot HTML client-side with familiar grep semantics. No backend round-trip for the search itself. All standard grep flags work: `-i`, `-v`, `-c`, `-A`/`-B`/`-C`, `-F`, `-w`.

### 12a. Find error messages on a broken page

```bash
browser4-cli goto "https://app.example.com/dashboard"
browser4-cli htmlsnapshot

# Search for common error patterns (case-insensitive)
browser4-cli htmlsnapshot grep -i 'error|exception|failed|timeout|500|503'

# Show 3 lines of context around each error for debugging
browser4-cli htmlsnapshot grep -i -C 3 'error|exception'

# Scope to <main> to ignore nav/footer noise
browser4-cli htmlsnapshot grep --selector main -i -C 2 'stack trace'
```

### 12b. Detect leaked secrets or sensitive data

```bash
browser4-cli goto "https://app.example.com/settings"
browser4-cli htmlsnapshot

# Check for common secret patterns (access keys, tokens, private keys)
browser4-cli htmlsnapshot grep -i 'AKIA[0-9A-Z]{16}' && echo "WARNING: AWS key pattern found"
browser4-cli htmlsnapshot grep -i 'sk-[a-zA-Z0-9]{32,}' && echo "WARNING: API key pattern found"
browser4-cli htmlsnapshot grep -i 'BEGIN.*PRIVATE KEY' && echo "WARNING: Private key in page"

# Look for internal hostnames or IPs leaked to the frontend
browser4-cli htmlsnapshot grep -i '\.internal|\.local|10\.\d+\.\d+\.\d+'
```

### 12c. Verify post-deploy content

After a deploy, confirm specific content appeared/disappeared:

```bash
browser4-cli goto "https://staging.example.com"
browser4-cli htmlsnapshot

# Confirm the new feature flag is on (fail if not found)
browser4-cli htmlsnapshot grep -l -F 'feature.new-checkout.enabled' | grep -q htmlsnapshot || exit 1

# Confirm debug info is NOT in the rendered HTML (fail if found)
browser4-cli htmlsnapshot grep -l -F 'debugMode' | grep -vq htmlsnapshot && exit 1

# Show the 2 lines around the version tag to verify deploy
browser4-cli htmlsnapshot grep -C 2 -F 'v2.14.1'
# → 45:  <meta name="version" content="v2.14.1">
# → 46:  <meta name="build-time" content="2026-06-27T14:30:00Z">
```

### 12d. Inverted and targeted search — find what's NOT there

```bash
# Show only non-empty lines (strip blank lines for readability)
browser4-cli htmlsnapshot grep -v '^\s*$'

# Find all <img> tags WITHOUT alt attributes (grep then filter with standard grep)
browser4-cli htmlsnapshot grep '<img[^>]*>' | while read line; do
  if ! echo "$line" | grep -q 'alt='; then
    echo "Missing alt: $line"
  fi
done

# Find links missing rel="nofollow" (grep then exclude matches)
browser4-cli htmlsnapshot grep --selector main '<a[^>]*href="http[^"]*"[^>]*>' | grep -v 'rel='
```

**Why `grep` for incident response:** It's the fastest path from "is X on the page?" to an answer. No SQL, no selectors, no backend load — just regex against the cached snapshot. The grep-style flags (`-A`/`-B`/`-C` for context, `-v` for inverse, `-c` for counting, `-F` for literal strings) match what every developer already knows.

---

## See Also

- [htmlsnapshot-scenarios.md](htmlsnapshot-scenarios.md) — full scenario index, patterns & tips
- [htmlsnapshot-scenarios-extraction.md](htmlsnapshot-scenarios-extraction.md) — e-commerce, news, jobs, academic, real estate extraction
- [htmlsnapshot-scenarios-amazon.md](htmlsnapshot-scenarios-amazon.md) — Amazon discovery-to-extraction workflows
- [htmlsnapshot-scenarios-advanced.md](htmlsnapshot-scenarios-advanced.md) — summary, inspect, and agent form discovery
- [htmlsnapshot.md](htmlsnapshot.md) — full command reference (grep flags, load options, pagination)
- [SKILL.md](../SKILL.md) — Browser4 CLI automation skill overview
