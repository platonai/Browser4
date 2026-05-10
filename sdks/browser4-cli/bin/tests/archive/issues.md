# Issues Found: Amazon Whiteboard Pens Task

Task: Go to amazon.com, search for "pens to draw on whiteboards", compare the first 4 results, write results to markdown.

## Issue 1: Amazon bot detection blocks form-based search (CAPTCHA / 503)

**Severity:** High
**Category:** Correctness

Using `fill` on the searchbox (e134) followed by `press Enter` resulted in a 503 error page: "Sorry! Something went wrong!" Amazon detected the automated interaction and blocked the search. The workaround was to navigate directly to the search URL (`/s?k=pens+to+draw+on+whiteboards`), which bypassed the bot detection.

**Impact:** The natural workflow (type into search box + press Enter) failed. Users must guess or construct search URLs manually, which is not the expected user experience for a browser automation tool.

**Suggested fix:** The tool could detect CAPTCHA/503 pages and provide guidance, or the underlying browser engine could use more human-like interaction patterns (e.g., randomized delays between keystrokes).

## Issue 2: Snapshot YAML files exceed read limits for complex pages

**Severity:** Medium
**Category:** Usability / Tooling

The Amazon search results page snapshot was over 28,000 tokens, exceeding the 25,000 token read limit. The full page could not be read in a single operation, requiring manual chunking with offset/limit parameters. This adds friction to every step that needs to parse page content.

**Impact:** Users must read snapshots in multiple chunks, which is error-prone and slow. Could miss elements located in unread portions of the file.

## Issue 3: No structured data extraction from snapshots

**Severity:** Medium
**Category:** Feature gap

Product details (name, price, rating, review count) had to be manually read and transcribed from the YAML snapshot by interpreting ARIA roles and text content. There is no built-in `extract` or query mechanism to pull structured data like "all product names and prices from search results."

**Impact:** Error-prone manual transcription. Comparison tasks that are simple for a human (look at 4 products) require dozens of lines of snapshot parsing for the CLI tool.

## Issue 4: Delivery location defaults to unexpected country (Japan)

**Severity:** Low-Medium
**Category:** Correctness

The browser session defaulted to showing delivery to Japan, not the United States. An alert dialog "International Shopping Transition Alert" appeared explaining this, but was never dismissed (modal=false, stayed on page). All shipping costs and delivery dates reflected shipping to Japan (e.g., $7.42-$8.42, arriving May 21).

**Impact:** Price and availability data is incorrect for US-based users. The alert dialog potentially obscures page content.

## Issue 5: Search results include loosely related products (relevance)

**Severity:** Low
**Category:** Correctness

Result #3 ("Liquid Chalk Markers") and other results on the page (e.g., "Celepen Window Chalk Markers for Cars Glass") are not strictly "pens to draw on whiteboards" — they are chalk markers for different surfaces. The search results are broad, but the tool provides no way to filter or refine by product type.

**Impact:** Comparison data may include irrelevant products, requiring manual judgment to determine relevance.

## Issue 6: Per-command `cargo run` overhead

**Severity:** Low
**Category:** Efficiency

Each individual command required a separate `cargo run -- <command>` invocation. Even though incremental compilation is fast (0.12s), the process startup overhead accumulates across multiple commands. For a non-batch workflow, each step requires a separate shell invocation.

**Impact:** Slower task completion. The CLI is designed for interactive use but testing in this environment adds per-command latency.

## Issue 7: No single-command product page detail extraction

**Severity:** Medium
**Category:** Completeness

To do a thorough comparison (tip size, ink quality, actual suitability for drawing on whiteboards as opposed to just writing), you would need to click into each of the 4 product pages individually, take snapshots, and extract details. This was not done because it would require 4+ additional command invocations per product. The comparison was limited to what appeared on the search results page.

**Impact:** The comparison may lack key differentiators visible only on product detail pages (e.g., smudge resistance, ghosting, tip material).

## Issue 8: No auto-dismissal of non-modal page dialogs

**Severity:** Low
**Category:** Usability

The "International Shopping Transition Alert" dialog remained on the page throughout the session. While it was marked `modal: false`, it still occupied screen space and could interfere with element interaction.

**Impact:** Minor — did not block the task in this case, but could obscure or overlap interactive elements on other pages.

## Summary

| Issue | Severity | Category |
|-------|----------|----------|
| Bot detection blocks form search | High | Correctness |
| Snapshot files exceed read limits | Medium | Usability |
| No structured data extraction | Medium | Feature gap |
| Delivery defaults to wrong country | Low-Medium | Correctness |
| Loose search result relevance | Low | Correctness |
| Per-command cargo run overhead | Low | Efficiency |
| No single-command product detail extraction | Medium | Completeness |
| Non-modal dialogs not auto-dismissed | Low | Usability |
