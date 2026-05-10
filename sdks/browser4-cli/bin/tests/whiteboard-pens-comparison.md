# Whiteboard Pens Comparison — Top 4 Amazon Search Results

**Search query:** "pens to draw on whiteboards"
**Search URL:** https://www.amazon.com/s?k=pens+to+draw+on+whiteboards
**Total results:** 364

---

## Product 1: maxtek Magnetic Dry Erase Markers
- **Price:** $6.99 ($0.58/count)
- **Pack:** 12 count, colorful, fine tip
- **Rating:** 4.4 / 5 (12,273 ratings)
- **Popularity:** 10K+ bought in past month
- **Badge:** Amazon's Choice / Overall Pick
- **Features:** Magnetic, low odor, includes eraser, recycled materials
- **Delivery:** FREE to Hong Kong by Mon, May 18 (on $35+ orders)
- **URL:** https://www.amazon.com/dp/B0B9CDZ9BL/

## Product 2: Liquid Chalk Markers — 14 Pack
- **Price:** $5.94 ($0.42/count) — Typical: $6.99
- **Pack:** 14 count, 12 vibrant colors, 1mm fine points
- **Rating:** 4.6 / 5 (1,671 ratings)
- **Popularity:** 7K+ bought in past month
- **Features:** Works on LED boards, acrylic, glass, dry erase whiteboards, windows, mirrors
- **Delivery:** FREE to Hong Kong by Mon, May 18 (on $35+ orders)
- **URL:** https://www.amazon.com/dp/B0C6LPTQDB/
- **Note:** These are liquid CHALK markers, not traditional dry erase pens.

## Product 3: Volcanics Magnetic Dry Wipe Pens
- **Price:** $5.99 ($0.50/count) — List: $7.49
- **Pack:** 12 count, 10 colors, fine tip
- **Rating:** 4.5 / 5 (10,606 ratings) — "Top Reviewed for Color"
- **Popularity:** 10K+ bought in past month
- **Features:** Magnetic, low odor, eraser cap, recycled materials
- **Delivery:** FREE to Hong Kong by Mon, May 18 (on $35+ orders)
- **URL:** https://www.amazon.com/dp/B08NPQDYR1/

## Product 4: EXPO Dry Erase Markers
- **Price:** $9.69 ($1.21/count)
- **Pack:** 8 count, assorted colors, ultra fine tip
- **Rating:** 4.6 / 5 (101,783 ratings)
- **Popularity:** 10K+ bought in past month
- **Features:** Low odor ink, for whiteboards and calendars
- **Delivery:** FREE to Hong Kong by May 18-21 (on $35+ orders)
- **URL:** https://www.amazon.com/dp/B00I8OBAOU/

---

## Comparison Table

| # | Product | Price | $/Count | Count | Rating | # Reviews |
|---|---------|-------|---------|-------|--------|-----------|
| 1 | maxtek Dry Erase | $6.99 | $0.58 | 12 | 4.4 | 12,273 |
| 2 | Liquid Chalk Markers | $5.94 | $0.42 | 14 | 4.6 | 1,671 |
| 3 | Volcanics Dry Wipe | $5.99 | $0.50 | 12 | 4.5 | 10,606 |
| 4 | EXPO Dry Erase | $9.69 | $1.21 | 8 | 4.6 | 101,783 |

---

## Verdict

- **Best value per pen:** Product 2 (Liquid Chalk Markers) at $0.42/count, but these are chalk markers, not dry erase.
- **Best value dry erase:** Product 3 (Volcanics) at $0.50/count, 4.5 stars, 10.6K reviews.
- **Most trusted:** Product 4 (EXPO) with 101K+ reviews and 4.6 stars, but 2-3x more expensive per pen.
- **Amazon's Pick:** Product 1 (maxtek) — solid overall but lowest rating of the four.

---

## Issues Found with browser4-cli During This Task

### Issue 1: Server becomes unresponsive after initial session
**Severity:** High — blocks all progress

After `open https://www.amazon.com/` succeeded and the snapshot was read, the next command (`click e402` to dismiss a dialog) failed with:
```
Error: HTTP request failed: error sending request for url (http://localhost:8182/mcp/call-tool)
```
The server was running (the `open` command had just started it), but subsequent calls to `/mcp/call-tool` failed. Required `kill-all` and a complete restart. This happened mid-task, killing the active session and losing all page state.

### Issue 2: `goto` requires prior `open` — documentation unclear
**Severity:** Medium — confusing UX

The SKILL.md shows `goto` used standalone:
```bash
browser4-cli goto https://browser4.io/
```
But running `goto` without first calling `open` produces:
```
Error: No active session. Run "browser4-cli open" first.
```
The documentation implies `goto` can navigate independently, but it requires an existing session.

### Issue 3: No inline snapshot display — requires separate file read
**Severity:** Medium — doubles interaction steps

After every command (`open`, `fill`, `press Enter`), the tool prints only a file path:
```
[Snapshot](D:\workspace\...\snapshot-2026-05-09T17-55-34Z.yml)
```
To get element refs for the next command, the user must separately `Read` the file. This means every interaction step requires at least 2 operations (run command + read snapshot), doubling the workflow overhead compared to tools that print the snapshot inline.

### Issue 4: Search requires two separate commands
**Severity:** Low — usability friction

There is no dedicated `search` command. Searching Amazon requires:
1. `fill e36 "pens to draw on whiteboards"` — type into the searchbox
2. `press Enter` — submit the search

A combined `search <ref> <query>` command, or having `fill` auto-submit on searchboxes, would reduce friction. Some tools offer `type` + auto-Enter behavior for form fields.

### Issue 5: Snapshot files can exceed token limits
**Severity:** Medium — blocks reading results

The Amazon search results snapshot was over 30,000 tokens (exceeding the 25,000 token read limit). This forced chunked reading with `offset`/`limit` parameters. The user must manually guess where product results begin in the file. A paginated snapshot format or a `--limit` flag on the snapshot command would help.

### Issue 6: Session-scoped element refs force re-reading snapshots
**Severity:** Low — inherent to architecture, but worth noting

Element refs (e.g., `e36` for the searchbox) are assigned per session per page load. After navigation, all refs change. While this is inherent to how browser automation works, combined with Issue 3 (no inline display), it means every single interaction requires: run command → read snapshot file → find element ref → run next command.

### Issue 7: International shipping alert dialog may interfere
**Severity:** Low — situational

On the first `open` of Amazon, an alert dialog appeared ("International Shopping Transition Alert — We're showing you items that ship to Hong Kong"). This dialog had dismiss/change-address buttons and could potentially interfere with clicking page elements behind it. In the retry session, this dialog did not appear, suggesting non-deterministic behavior (possibly cookie-based).

### Issue 8: Delivery location defaults to Hong Kong
**Severity:** Low — affects relevance of results

The browser session defaulted to showing delivery to Hong Kong. All prices, delivery estimates, and available inventory reflect Hong Kong shipping. For users in other regions, this skews the comparison data.

### Issue 9: Search results include off-category products
**Severity:** Low — Amazon's issue, not the CLI's

Product 2 ("Liquid Chalk Markers") is a different product category from "pens to draw on whiteboards." The search term "pens" on Amazon returns predominantly markers. This is an Amazon search relevance issue, not a CLI bug, but users should be aware that search results may need manual filtering.

### Issue 10: No programmatic filtering of search results
**Severity:** Low — feature gap

To narrow results by brand, price range, or tip type, the user would need to manually click filter elements on the search results page. There is no CLI mechanism to apply search filters programmatically (e.g., `browser4-cli filter price-range 0-10`).

### Issue 11: `kill-all` destroys all sessions irreversibly
**Severity:** Medium — data loss risk

When the server became unresponsive (Issue 1), the only recovery path was `kill-all`, which destroys all sessions. There is no `restart-server` command that preserves sessions, and no session persistence across server restarts.

### Issue 12: Snapshot file path is absolute and long on Windows
**Severity:** Low — readability

The snapshot file path output uses Windows absolute paths with backslashes:
```
[Snapshot](D:\workspace\Browser4Team\submodules\Browser4\sdks\browser4-cli\.browser4-cli\snapshot\snapshot-2026-05-09T17-55-34Z.yml)
```
This is hard to read and type. A shorter relative path or a copyable one-liner would improve UX.
