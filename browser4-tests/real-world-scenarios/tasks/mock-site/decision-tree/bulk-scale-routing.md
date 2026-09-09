# bulk-scale-routing

Before running this scenario, ensure MockSite is running on localhost:18080 (`./bin/test.ps1 mock-site`).

This scenario covers every branch in **SKILL.md §4b — Choosing Bulk/Scale Approach**.

## Acceptance Criteria

1. **AC1 — Single list page:** Use `htmlsnapshot query` with `DOM_LOAD_AND_SELECT` against one MockSite listing page.
2. **AC2 — Multiple known URLs:** Use `crawl --seed-file ... --depth 0 --sql @query.sql` against several specific product URLs.
3. **AC3 — Crawl from a start URL:** Use `crawl <url> --out-link-selector ... --depth N` on the generated crawl fixture.
4. **AC4 — Parallel execution:** Use `swarm create` and `swarm query --seed-file ...` for the same style of structured extraction at higher throughput.
5. **AC5 — Repeated monitoring:** Use `loop` in subcommand mode (`-i`/`-n` flags **before** `--`) against a named session that is already on a MockSite page.
6. **AC6 — Just a few URLs in a shell script:** Run the few-URL pattern with a simple shell loop (PowerShell loop on Windows is acceptable) and add a short wait between iterations.

## Steps

### 1. Single list page (AC1)

1. Go to `http://localhost:18080/ec/b?node=1292115012`.
2. Capture an HTML snapshot.
3. Write and run an X-SQL query that extracts one row per product card from the listing page.
4. Verify the result set contains correlated fields such as title + price for each product on that one page.

### 2. Multiple known URLs via seed file (AC2)

1. Create a seed file with at least 3 known MockSite product detail URLs, for example:
   - `http://localhost:18080/ec/dp/B0E000001`
   - `http://localhost:18080/ec/dp/B0E000002`
   - `http://localhost:18080/ec/dp/B0E000003`
2. Write an X-SQL query file that extracts `DOM_BASE_URI(DOM)`, `#productTitle`, and `#product-price`.
3. Run:

```
crawl --seed-file <path-to-seed-file> --depth 0 --sql @<query-file> --format table --refresh
```

4. Verify one structured result row is returned for each seed URL.

### 3. Crawl from a start URL with link discovery (AC3)

1. Go to the crawl fixture hub:

```
http://localhost:18080/generated/crawl/index.html
```

2. Run a crawl with link discovery, for example:

```
crawl http://localhost:18080/generated/crawl/index.html -d 2 -ol "a.product" -olp "/product/"
```

3. Verify product pages are discovered from the start URL and that unrelated category links are excluded by the selector/pattern filters.

### 4. Parallel execution with swarm (AC4)

1. Create a swarm session in headless mode with small but real parallelism, for example `--max-browser-contexts 2` and `--max-open-tabs 4`.
2. Reuse the seed file and query file from AC2.
3. Run `swarm query --sql @<query-file> --seed-file <path-to-seed-file> --refresh`.
4. Poll `swarm status`, then fetch the completed payload with `swarm result`.
5. Verify the extraction succeeds across multiple URLs and close the swarm session afterward.

### 5. Repeated monitoring with loop (AC5)

1. Open a named browser session on a stable MockSite page, for example:

```
-s price-watch goto http://localhost:18080/ec/dp/B0E000001
```

2. Start a short named loop in subcommand mode:

```
loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()"
```

3. Check `loop --list` or `loop --status --name mock-price-watch` while it runs.
4. Verify the loop executes repeated page checks without needing a multi-page crawl or swarm job.

### 6. Just a few URLs in a shell script (AC6)

1. Prepare a short list of 2-3 MockSite product URLs.
2. Exercise the lightweight few-URL pattern:
   - On bash: `for url in ...; do browser4-cli goto "$url"; ...; sleep 2; done`
   - On PowerShell: iterate the same list with `ForEach-Object`, calling `goto`, `htmlsnapshot`, and `htmlsnapshot get text "#productTitle"` with `Start-Sleep` between pages
3. Verify this ad-hoc loop is sufficient for a very small URL set and does not require crawl, swarm, or loop.
