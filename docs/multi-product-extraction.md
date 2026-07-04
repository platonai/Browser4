# Multi-Product Extraction: Getting Structured Fields Across Detail Pages

A common Browser4 pattern: discover product URLs from a search/listing page, then
extract structured fields (title, price, description, features, rating, images)
from each product's **detail page**.  The listing page gives you the URLs; the
detail pages hold the data you actually need.

This guide covers every approach, from the simplest one-off to production-scale
parallel extraction.

## The task (Laser-Engraved Crystal)

The motivating example — evaluating 10 crystal night-light products for a
12-year-old boy's gift.  The workflow:

1.  Search Amazon for "Laser-Engraved Crystal"
2.  Extract ~48 product titles, prices, ratings, and links from the search
    results page
3.  Filter to 10 candidates that are pre-engraved and boy-appropriate
4.  Navigate to each product's detail page and extract: full features,
    description, rating breakdown, images, specs, gift-box info

The key challenge is step 4: you have a list of URLs, and each one needs
consistent structured extraction.  The solutions below all solve this, with
different trade-offs.

---

## Solution 1: swarm query with seed file ★ Recommended

Parallel extraction across multiple browser contexts.  Submit all URLs at once,
get structured JSON results back.

```bash
# Step 1 — extract the product URLs from search results (one-time)
browser4-cli eval --json "
JSON.stringify([...document.querySelectorAll(
  '[data-component-type=\"s-search-result\"] a.a-link-normal[href*=\"/dp/\"]'
)].map(a => a.href).slice(0, 10))
" > urls.txt

# Step 2 — write a reusable X-SQL extraction query
cat > extract.sql << 'SQL'
SELECT
    dom_base_uri(dom) AS url,
    dom_first_text(dom, '#productTitle') AS title,
    dom_first_text(dom, '.a-price .a-offscreen') AS price,
    dom_first_text(dom, '#acrCustomerReviewText') AS rating,
    dom_first_text(dom, '#feature-bullets') AS features,
    dom_first_text(dom, '#productDescription') AS description,
    dom_first_attr(dom, '#landingImage', 'src') AS image_url
FROM load_and_select(@url, 'body')
SQL

# Step 3 — parallel extraction
browser4-cli swarm create --display-mode HEADLESS --max-browser-contexts 3
browser4-cli swarm query --sql @extract.sql --seed-file urls.txt --refresh
browser4-cli swarm status scrape-task-1    # poll until isDone: true
browser4-cli swarm result scrape-task-1    # structured JSON per product
browser4-cli close
```

| Pros | Cons |
|---|---|
| Parallel execution (3 browser contexts) | Requires X-SQL learning |
| Structured JSON output, one row per product | Detail page selectors must be consistent |
| `--refresh` ensures fresh data | Seed file must be populated first |
| Scales to thousands of URLs | Swarm session setup overhead |
| `-expires 1h` avoids re-fetching when iterating on query | |

**Best for:** 10–10 000 URLs where page structure is consistent.  This is the
pattern the CLI tip system nudges users toward for correlated multi-field
extraction.

---

## Solution 2: batch with goto + htmlsnapshot get

The simplest automation — just sequence the same commands you'd type manually.

```bash
browser4-cli batch --bail \
  "goto 'https://www.amazon.com/dp/B0C17W3Q9B'" \
  "htmlsnapshot" \
  "htmlsnapshot get text '#productTitle'" \
  "htmlsnapshot get text '.a-price .a-offscreen'" \
  "htmlsnapshot get text '#feature-bullets'" \
  "goto 'https://www.amazon.com/dp/B0CXYZ1234'" \
  "htmlsnapshot" \
  "htmlsnapshot get text '#productTitle'" \
  "htmlsnapshot get text '.a-price .a-offscreen'" \
  "htmlsnapshot get text '#feature-bullets'" \
  ...
```

| Pros | Cons |
|---|---|
| Zero new concepts — same commands, automated | Sequential only (10 pages × 4 cmds = slow) |
| `--bail` stops on first failure | Output is interleaved text, needs parsing |
| Full control per page (different selectors) | Verbose: 10 URLs × 5 fields = 50+ arguments |
| No swarm setup | No built-in structured output |

**Best for:** 3–10 URLs, quick one-off where you just need the data on screen.

---

## Solution 3: swarm submit (fetch) → swarm query (extract)

Decouple fetching from extraction.  Fetch all pages first, then run extraction
queries against the cached content.  Pays off on rate-limited sites where
re-fetching is expensive.

```bash
# Phase 1 — fetch and parse all detail pages
browser4-cli swarm create --display-mode HEADLESS
browser4-cli swarm submit --seed-file urls.txt --parse --store-content --refresh

# Phase 2 — run X-SQL extraction against the cached pages
# (can iterate on this without re-fetching)
browser4-cli swarm query --sql @extract.sql --seed-file urls.txt
browser4-cli swarm result scrape-task-2
```

| Pros | Cons |
|---|---|
| Decoupled fetch/extract — iterate on query without re-fetching | Two steps instead of one |
| `--store-content` persists pages for re-querying | Overkill for <20 URLs |
| Avoids rate-limiting during query development | |

**Best for:** 100+ URLs on rate-limited sites, or when iterating on selectors.

---

## Solution 4: eval with JS fetch-all

A single `eval` call that fetches all pages via `fetch()` and parses them with
`DOMParser`.

```bash
browser4-cli eval --json "
(async () => {
  const urls = [
    'https://www.amazon.com/dp/B0C17W3Q9B',
    'https://www.amazon.com/dp/B0CXYZ1234',
    ...
  ];
  const results = [];
  for (const url of urls) {
    const resp = await fetch(url);
    const html = await resp.text();
    const doc = new DOMParser().parseFromString(html, 'text/html');
    results.push({
      url,
      title: doc.querySelector('#productTitle')?.textContent?.trim(),
      price: doc.querySelector('.a-price .a-offscreen')?.textContent,
      rating: doc.querySelector('#acrCustomerReviewText')?.textContent,
      features: doc.querySelector('#feature-bullets')?.textContent?.trim(),
    });
  }
  return results;
})()"
```

| Pros | Cons |
|---|---|
| Single command, single JSON array output | **CORS will likely block** cross-origin `fetch()` |
| All extraction in one shot | No caching between fetches |
| Familiar JS (no X-SQL) | Fragile — one JS error loses everything |
| | Amazon detects rapid same-origin fetches as bot activity |

**Best for:** 2–5 URLs on sites that don't enforce CORS on `fetch()`.  Not
recommended for Amazon — CORS and bot detection make this unreliable.

---

## Solution 5: X-SQL UNION across URLs

A single `htmlsnapshot query` that UNIONs results from multiple
`DOM_LOAD_AND_SELECT` calls.

```bash
browser4-cli htmlsnapshot query --sql "
  SELECT dom_base_uri(dom) AS url,
         dom_first_text(dom, '#productTitle') AS title,
         dom_first_text(dom, '.a-price .a-offscreen') AS price,
         dom_first_text(dom, '#feature-bullets') AS features
  FROM load_and_select('https://www.amazon.com/dp/B0C17W3Q9B -expires 1h', 'body')
  UNION ALL
  SELECT dom_base_uri(dom),
         dom_first_text(dom, '#productTitle'),
         dom_first_text(dom, '.a-price .a-offscreen'),
         dom_first_text(dom, '#feature-bullets')
  FROM load_and_select('https://www.amazon.com/dp/B0CXYZ1234 -expires 1h', 'body')
  UNION ALL
  ...
"
```

| Pros | Cons |
|---|---|
| Single command, structured table output | Verbose SQL for 10+ URLs |
| `-expires 1h` caches individual fetches | Serial execution (one page at a time) |
| No swarm setup needed | Query string becomes unwieldy |
| Works with any `htmlsnapshot`-capable page | All URLs must be known at write time |

**Best for:** 3–8 URLs, simple fields, when you want a single-command answer
without swarm overhead.

---

## Solution 6: extract (LLM-powered)

Zero selectors — describe what you want in natural language and the LLM reads
the page.

```bash
browser4-cli goto "https://www.amazon.com/dp/B0C17W3Q9B"
browser4-cli extract "product title, price, rating count, feature bullets, \
  product description, main image URL, whether it comes in a gift box" \
  --schema '{
    "type": "object",
    "properties": {
      "title": {"type": "string"},
      "price": {"type": "string"},
      "rating": {"type": "string"},
      "feature_bullets": {"type": "string"},
      "description": {"type": "string"},
      "image_url": {"type": "string"},
      "gift_box": {"type": "boolean"}
    }
  }'
```

| Pros | Cons |
|---|---|
| No CSS selectors, no JS, no X-SQL | LLM cost per page |
| Handles inconsistent page layouts gracefully | Slower (model inference) |
| `--schema` enforces structured output | Still requires sequential navigation per page |
| Natural language instructions | Less predictable without `--schema` |

**Best for:** Pages with irregular structure where CSS selectors break, or when
you need semantic understanding (e.g. "is this appropriate for a 12-year-old
boy?").

---

## Solution 7: loop over URLs

Resumable, paced sequential processing.  Each iteration processes one URL.

```bash
# Write URLs to a file
cat > urls.txt << 'EOF'
https://www.amazon.com/dp/B0C17W3Q9B
https://www.amazon.com/dp/B0CXYZ1234
...
EOF

# Process one URL per iteration
browser4-cli loop --name crystal-extract --interval 10s --count 10 -- \
  "goto \"\$(sed -n '\${B4_ITERATION}p' urls.txt)\"" \
  "htmlsnapshot" \
  "htmlsnapshot get text '#productTitle'" \
  "htmlsnapshot get text '.a-price .a-offscreen'" \
  "htmlsnapshot get text '#feature-bullets'"
```

| Pros | Cons |
|---|---|
| Resumable after interruption | Complex setup |
| Progress persisted to `~/.browser4/loops/` | Sequential only |
| `--interval` avoids rate-limiting | Output scattered across iterations |
| `--pause` / `--resume` for manual pacing | |

**Best for:** Recurring monitoring (daily price checks) or when you need
interruptible long-running extraction with manual oversight.

---

## Solution 8: hybrid — list extraction → swarm detail extraction

The general pattern: extract URLs from the list page with any method, then feed
them to swarm for parallel detail extraction.

```bash
# Phase 1 — extract product URLs from search results (eval, X-SQL, or get)
browser4-cli goto "https://www.amazon.com/s?k=Laser-Engraved+Crystal"
browser4-cli htmlsnapshot
browser4-cli htmlsnapshot query --sql "
  SELECT dom_first_href(dom, 'a.a-link-normal[href*=\"/dp/\"]') AS url,
         dom_first_text(dom, 'h2') AS title,
         dom_first_text(dom, '.a-price .a-offscreen') AS price
  FROM load_and_select(@url, '[data-component-type=\"s-search-result\"]')
  WHERE dom_first_text(dom, 'h2') IS NOT NULL
" > search-results.json

# Phase 2 — extract URLs from results, write to seed file
# (one-time scripting step — jq, python, or manual)
cat search-results.json | jq -r '.[].url' > urls.txt

# Phase 3 — parallel detail-page extraction
browser4-cli swarm create --display-mode HEADLESS
browser4-cli swarm query --sql @extract.sql --seed-file urls.txt --refresh
browser4-cli swarm result scrape-task-1
browser4-cli close
```

| Pros | Cons |
|---|---|
| Best of both worlds — fast list scan + parallel detail extraction | Two-phase workflow |
| X-SQL on list page gives correlated fields (no array-alignment issues) | Requires a data-shuffling step between phases |
| Swarm parallelizes the expensive part (detail pages) | |
| Scalable to any number of detail pages | |

**Best for:** The general case.  This is the recommended pattern whenever URLs
come from a search/listing page and the real data is on detail pages.

---

## Decision matrix

| Solution | Parallel? | Structured output? | Selector-free? | Setup cost | Best for |
|---|---|---|---|---|---|
| **1. Swarm query + seed** | ✅ | ✅ JSON | ❌ | Medium | 10–10 000 URLs |
| **2. Batch + get** | ❌ | ❌ text | ❌ | Low | 3–10 URLs, quick one-off |
| **3. Swarm 2-phase** | ✅ | ✅ JSON | ❌ | High | 100+ URLs, rate-limited sites |
| **4. Eval fetch-all** | ❌ | ✅ JSON | ❌ | Low | 2–5 URLs, no CORS |
| **5. X-SQL UNION** | ❌ | ✅ table | ❌ | Low | 3–8 URLs, simple fields |
| **6. Extract (LLM)** | ❌ | ✅ schema | ✅ | Low | Irregular pages, 5–20 URLs |
| **7. Loop** | ❌ | ❌ text | ❌ | High | Recurring monitoring |
| **8. Hybrid list→swarm** | ✅ | ✅ JSON | ❌ | Medium | General pattern |

---

## Quick pick by scenario

- **"I have 5 product pages and 10 minutes"** → Solution 2 (`batch` + `get`)
- **"I have 50–500 product pages, same site"** → Solution 1 (`swarm query` + seed file)
- **"The pages have wildly different layouts"** → Solution 6 (`extract --schema`)
- **"I need this to run every morning"** → Solution 7 (`loop`)
- **"I'm iterating on selectors and can't afford re-fetches"** → Solution 3 (swarm 2-phase)
- **"I want a single command, no swarm setup"** → Solution 5 (X-SQL UNION)

## See also

- [X-SQL: DOM_LOAD_AND_SELECT](skill/references/x-sql-dom-load-select.md) — the
  table-source function for loading pages in X-SQL queries
- [Swarm reference](skill/references/swarm.md) — parallel scraping and X-SQL
  extraction across multiple browser contexts
- [HTML Snapshot extraction scenarios](skill/references/htmlsnapshot-scenarios-extraction.md) —
  end-to-end e-commerce extraction recipes
- [X-SQL reference](skill/references/x-sql.md) — ~200 functions across DOM_*,
  STR_*, and ARRAY_* namespaces
