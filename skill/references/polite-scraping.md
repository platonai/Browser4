# Polite Scraping

When navigating multiple pages rapidly, be respectful of the target website to avoid triggering rate limiting, CAPTCHAs, or IP blocks.

## Basic Pattern

```bash
browser4-cli goto "https://www.amazon.com/dp/B0CXJ1NT4B"
browser4-cli wait 2000      # 2-second polite delay
browser4-cli eval "document.title"
```

## Batch Operations

```bash
for asin in B0CXJ1NT4B B0BGH5L5FX B0GF6N1NWM; do
  browser4-cli goto "https://www.amazon.com/dp/$asin"
  browser4-cli wait 1500    # 1.5s between product pages
  browser4-cli eval --json "JSON.stringify({title: document.title.split(':')[0]?.trim(), price: document.querySelector('.a-price .a-offscreen')?.textContent})"
done
```

## Guidelines

- Add `wait 1000-3000` (1–3 seconds) between rapid navigations on the same site
- Amazon and similar sites may show CAPTCHAs under aggressive automated access — longer delays reduce risk
- Use `eval` or `domsnapshot get all` to batch-extract data from a single page load when possible, rather than navigating to each detail page individually
- Prefer `crawl` with conservative `--depth` and `--page-load-timeout` for automated multi-page traversal — it includes built-in rate limiting
