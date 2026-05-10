# Issues Report: Amazon Whiteboard Pens Search Task

## Task Summary

1. Go to https://www.amazon.com/
2. Search for "pens to draw on whiteboards"
3. Compare the first 4 results
4. Write the result to a markdown file

---

## Issue 1: `type` + `press Enter` does not submit Amazon's search form

**Severity:** High  
**Category:** Correctness / Functionality

Typing into the Amazon searchbox (`ref=e4922`) with `type "pens to draw on whiteboards" e4922` followed by `press Enter` (both globally and with the searchbox ref `e4922`) did not submit the search form. The page remained on the Amazon homepage. Amazon's search form relies on JavaScript event handlers that are not triggered by the browser4-cli `press Enter` command.

**Workaround used:** Navigated directly to the search URL (`https://www.amazon.com/s?k=pens+to+draw+on+whiteboards`) via `open`, bypassing the interactive search flow entirely.

---

## Issue 2: Backend crash when clicking the "Go" button

**Severity:** High  
**Category:** Stability

After `type` failed to submit the search, attempting to click the "Go" search button (`ref=e5873`) resulted in an HTTP error: `error sending request for url (http://localhost:8182/mcp/call-tool)`. The Browser4 backend appeared to become unresponsive, requiring a new `open` command to recover.

---

## Issue 3: Sponsored/ad content interleaved with organic results

**Severity:** Medium  
**Category:** Correctness

Between organic search results #2 (Volcanics) and #3 (Liquid Chalk Markers), Amazon inserted a sponsored video ad for "GOTIDEAL Chalk Markers." The snapshot does not clearly distinguish between sponsored and organic results. A user asking to "compare the first 4 ones" could unknowingly include a sponsored/ad product in the comparison. There is no semantic marker (like `[Sponsored]` in a distinct attribute) that makes filtering easy.

---

## Issue 4: Product #4 appears after a "More results" section divider

**Severity:** Medium  
**Category:** Correctness / Layout

Organic result #4 (Shuttle Art, `sr=8-4`) appears after a `heading "More results"` section divider in the snapshot. This is confusing — it suggests that result #4 is not part of the main result set, even though it's the 4th organic listing. The layout of search result listitems is fragmented by intermediate UI elements.

---

## Issue 5: Snapshot file too large to read in one operation

**Severity:** Medium  
**Category:** Efficiency / Usability

The snapshot YAML file for the Amazon search results page exceeds 25,000 tokens, exceeding the read limit. This required reading the file in multiple chunks with offset/limit parameters, adding friction to the workflow. For complex e-commerce pages, snapshot sizes can become unmanageable.

---

## Issue 6: Product data scattered across deeply nested elements

**Severity:** Medium  
**Category:** Efficiency

Each product's information (name, price, rating, review count) is spread across 30-60 lines in the snapshot YAML. Price values are fragmented across nested `generic` elements (e.g., `$6.99` is split into text `$6`, nested generics with `99`). Extracting structured data requires manually parsing deeply nested accessibility tree nodes, which is slow and error-prone.

---

## Issue 7: No structured data extraction capability

**Severity:** Medium  
**Category:** Feature Gap

The snapshot provides a flat accessibility tree with no built-in mechanism to extract product data (name, price, rating, review count) in a structured format like JSON or a table. For comparison tasks like this one, the user must manually read and transcribe data from the YAML snapshot. An `extract` command exists (per `help extract`) but is not documented in SKILL.md and may not support structured product extraction.

---

## Issue 8: Session defaults to non-US delivery address

**Severity:** Low  
**Category:** Correctness

The browser session showed "Deliver to Japan" in the header, and an alert dialog stated "We're showing you items that ship to Japan." Delivery costs and availability reflected Japan shipping ($7.42-$8.42 delivery, "Ships to Japan"). If the user expects US-based results and pricing, this geographic context could produce misleading comparisons.

---

## Issue 9: Ref numbering is non-sequential and hard to follow

**Severity:** Low  
**Category:** Usability

Element refs use large, non-sequential numbers (e.g., `e4922`, `e12886`, `e5657`) that don't follow any obvious ordering. This makes it difficult to navigate the snapshot visually and understand which elements are logically grouped together. A hierarchical or scope-based numbering scheme would improve readability.

---

## Issue 10: Snapshot includes non-English UI text artifacts

**Severity:** Low  
**Category:** Cleanliness

The snapshot contains non-English UI strings (e.g., Chinese characters "提交" for Submit, "播放" for Play, "进入全屏模式" for Fullscreen) from video player controls and UI elements. These artifacts clutter the snapshot and may confuse users who expect English-only output.

---

## Issue 11: Search form interaction requires workaround knowledge

**Severity:** Low  
**Category:** Usability / Documentation

The SKILL.md documentation shows `type` + `press Enter` as the standard search interaction pattern, but this does not work on Amazon.com. Users need to know to construct search URLs manually (`/s?k=query+terms`) as a workaround. The documentation does not mention this limitation or provide guidance for sites with JavaScript-dependent form submission.

---

## Comparison Results (extracted despite issues)

| # | Product | Price | Rating | Reviews | Count | Tip |
|---|---------|-------|--------|---------|-------|-----|
| 1 | maxtek Magnetic Dry Erase Markers | $6.99 ($0.58/ct) | 4.4 | 12,282 | 12 | Fine |
| 2 | Volcanics Magnetic Dry Wipe Pens | $5.99 ($0.50/ct) | 4.5 | 10,607 | 12 | Fine |
| 3 | Liquid Chalk Markers (LED/Glass) | $5.94 ($0.42/ct) | 4.6 | 1,674 | 14 | Fine (1mm) |
| 4 | Shuttle Art Dry Erase Markers | $8.99 ($0.60/ct) | 4.4 | 14,803 | 15 | Fine |
