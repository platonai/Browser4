# MockSite — Test Fixture Server

Browser4 includes a lightweight **MockSite** server that serves static HTML pages for testing and demos — search boxes, forms, link lists, interactive pages, and more. When you see references to `http://localhost:18080/...` in task instructions, test scripts, or examples, they expect MockSite to be running.

MockSite is a Spring Boot application (`MockSiteApplication`) that serves static deterministic pages from `browser4-tests/pulsar-tests-common/src/main/resources/static/`. Pages emulate: search box, link list, infinite scroll, comment threads, and predictable anchors for agent action instructions.

## Starting MockSite

From the repository root, start MockSite with its default port (18080):

**Windows (PowerShell):**
```powershell
# bin/*.ps1 scripts require PowerShell 7+ (pwsh). Windows PowerShell 5.1
# refuses with a clear "#requires" error — run it via pwsh instead:
pwsh -File ./bin/test.ps1 mock-site -Dmock.site.port=18080
```

**Linux / macOS (bash):**
```bash
./bin/test.sh mock-site -Dmock.site.port=18080
```

Or run directly via Maven:

```shell
cd browser4-tests/browser4-rest-tests
./../../mvnw package -DskipTests -am spring-boot:run -D"spring-boot.run.mainClass=ai.platon.pulsar.test.server.MockSiteBoot"
```

## Key Demo Pages

| Page | URL |
|------|-----|
| Interactive fixture | `http://localhost:18080/generated/interactive-1.html` |
| Form filling fixture | `http://localhost:18080/generated/form-filling.html` |
| Other fixture | `http://localhost:18080/generated/other-1.html` |

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `MOCK_SITE_PORT` | `18080` | Port the mock server listens on |
| `MOCK_SITE_WAIT_SEC` | — | Seconds to wait for server readiness |

The launcher tries the health endpoint first (default `/actuator/health`, overridable via `mock.site.healthPath` JVM property) and falls back to `/` if the health path fails. It returns `true` on the first 2xx/3xx response.

## Alternative: Serve Fixture Files with Python

If you only need the static HTML fixtures without the full MockSite, serve the fixture directory directly:

```bash
cd browser4-tests/pulsar-tests-common/src/main/resources/static
python3 -m http.server 18080
```

The fixture HTML files (e.g., `b4/mcp-tool-controller-form-fixture.html`) will be available under `http://localhost:18080/b4/`.

## Crawling MockSite (link discovery + X-SQL)

MockSite's e-commerce pages (`/ec/...`) are built for exercising `crawl` end to
end without touching a live website. The canonical crawl reference is
[references/crawl.md](../skills/browser4-cli/references/crawl.md); this section
covers the MockSite-specific parts.

**URL discovery.** Don't guess product IDs — a sitemap exists for exactly this.
The bare path `http://localhost:18080/ec/dp/` (trailing slash) 404s: product
pages always require an ID (`/ec/dp/<product-id>`).

```bash
# Enumerate all product URLs (101 products, 20 categories)
curl -s http://localhost:18080/ec/sitemap.xml

# Build a seed file for crawl/swarm from the sitemap (first 8 products)
curl -s http://localhost:18080/ec/sitemap.xml \
  | grep -o 'http://localhost:18080/ec/dp/[A-Z0-9]*' | head -8 > seed-urls.txt
```

**Selectors.** Detail pages (`/ec/dp/…`) use ID selectors; listing pages
(`/ec/b?node=…`) use class selectors. Inspect the real page before writing a
query:

```bash
browser4-cli goto "http://localhost:18080/ec/dp/B0E000001"
browser4-cli htmlsnapshot inspect
```

| Field | Selector |
|-------|----------|
| Product title | `#productTitle` |
| Price | `#product-price` |
| Description (feature list) | `#product-features` |
| Category (breadcrumb) | `.breadcrumbs` |
| Listing card / title / price | `.product-card` / `.product-title` / `.product-price` |

**End-to-end crawl recipe.**

```bash
# 1. Start MockSite
./bin/test.ps1 mock-site

# 2. Create a seed file
echo "http://localhost:18080/ec/dp/B0E000001" > seed-urls.txt
echo "http://localhost:18080/ec/dp/B0E000002" >> seed-urls.txt
echo "http://localhost:18080/ec/dp/B0E000003" >> seed-urls.txt

# 3. Create an X-SQL extract file
cat > extract.sql << 'SQLEOF'
SELECT
  DOM_BASE_URI(dom) AS url,
  DOM_FIRST_TEXT(dom, '#productTitle') AS title,
  DOM_FIRST_TEXT(dom, '#product-price') AS price
FROM DOM_LOAD_AND_SELECT(@url, 'body')
SQLEOF

# 4. Run the crawl
browser4-cli crawl --seed-file seed-urls.txt --depth 0 --refresh \
  --sql "@extract.sql" --format table
```

> **Tip:** When selectors don't match, use `htmlsnapshot grep` with `--selector`
> to verify elements exist, or `htmlsnapshot inspect` to discover available
> selectors. MockSite uses IDs (`#productTitle`), not classes (`.title`).

**Browser DOM vs. raw HTML.** MockSite serves a JavaScript-hydrated page variant
to browsers (the crawl fetch pipeline), which can differ from the static HTML a
plain `curl` receives — e.g. category/navigation anchors arrive as `href="#"`
and the rendered product list may be a subset. When debugging link-discovery
counts, verify the *browser* DOM with `eval` or `htmlsnapshot inspect` rather
than assuming `curl` output matches what the crawler sees.

## See Also

- [Crawl Command Reference](../skills/browser4-cli/references/crawl.md) — the `crawl` command this recipe exercises
- [Test Taxonomy](TESTING.md) — test tagging, levels, costs, and execution policies
- [MockSite module README](../browser4-tests/browser4-rest-tests/README.md)
