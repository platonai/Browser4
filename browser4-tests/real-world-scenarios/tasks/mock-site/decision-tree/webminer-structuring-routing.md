# webminer-structuring-routing

Before running this scenario, ensure MockSite is running on localhost:18080 (`./bin/test.ps1 mock-site`). To execute the WebMiner steps, install the tool with `browser4-cli webminer install` (first-class CLI command, no PowerShell), or use the launcher described in `skills/browser4-web-miner/SKILL.md` (`.\webminer.ps1 install`), or provide a local `scent-miner.jar`.

This scenario covers every branch in **SKILL.md §4d — Structuring Extracted Pages (WebMiner)**.

## Acceptance Criteria

1. **AC1 — Small/medium corpus (< 1,000 pages):** Build a small local HTML corpus from MockSite pages and run the free WebMiner pipeline against it.
2. **AC2 — Production-scale corpus (> 1,000 pages):** Show the decision point where the Spark-based commercial tier is the correct choice, even if you do not execute that proprietary pipeline locally.
3. **AC3 — Acquire pages from single-page browsing:** Export individual MockSite pages with `goto` + `htmlsnapshot export`.
4. **AC4 — Acquire pages from bulk known URLs:** Use `crawl --seed-file ... --depth 0` as the bulk acquisition path.
5. **AC5 — Acquire pages with high throughput:** Use `swarm create` plus a seed-file submission path as the high-throughput acquisition option.

## Steps

### 1. Acquire a small local corpus from single pages (AC3)

1. Create a local directory for exported HTML files.
2. Visit at least 3 MockSite product detail pages under `http://localhost:18080/ec/dp/`.
3. On each page, capture an HTML snapshot and export it to a distinct file with `htmlsnapshot export --file ...`.
4. Verify the directory now contains a small corpus of product-page HTML files.

### 2. Run the free WebMiner pipeline on the small corpus (AC1)

1. Use the directory created in step 1 as the input corpus.
2. Run either:

```
browser4-cli webminer all <html-dir>
```

or

```
.\webminer.ps1 all <html-dir>
```

or

```
java -jar scent-miner.jar all <html-dir>
```

3. Verify the run produces the expected local outputs: encoded data, clustered results, and generated views/report artifacts.
4. Confirm this is the right branch when the dataset is comfortably below 1,000 pages.

### 3. Bulk acquisition from known URLs (AC4)

1. Create a seed file with at least 6 MockSite product URLs.
2. Run:

```
crawl --seed-file <path-to-seed-file> --depth 0 --refresh
```

3. Verify this is the correct acquisition path when you already know the URLs and want to fetch many pages without link discovery.
4. If your workflow stores fetched HTML outside the Browser4 cache, stage those files into a WebMiner input directory.

### 4. High-throughput acquisition with swarm (AC5)

1. Create a swarm session with headless mode enabled.
2. Reuse or expand the seed file from step 3.
3. Submit the same product set through the swarm path:
   - `swarm create ...`
   - `swarm query --seed-file <path-to-seed-file> --sql @<query-file> --refresh`
4. Verify the job completes successfully and record that this is the preferred path when acquisition throughput matters more than simple sequential crawling.

### 5. Production-scale decision point (AC2)

1. Estimate or simulate a larger corpus target, for example a 1,200-page daily product export assembled from many MockSite-style category and detail pages.
2. Document that this scale exceeds the free-tier guidance in SKILL.md and therefore maps to the Apache Spark commercial pipeline rather than the single-machine SMILE workflow.
3. Keep the same acquisition patterns from steps 3-4, but route the resulting HTML corpus to the commercial WebMiner deployment instead of trying to force the free local pipeline past its intended scale.
