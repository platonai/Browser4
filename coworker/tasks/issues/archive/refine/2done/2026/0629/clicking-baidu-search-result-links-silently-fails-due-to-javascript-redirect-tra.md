# Clicking Baidu Search Result Links Silently Fails Due to JavaScript Redirect Tracking

Clicking search result links on Baidu (and likely other sites that use JavaScript-based redirect tracking) silently fails — the page URL and content remain on the search results page with no error or indication that navigation did not occur.

**Steps to Reproduce:**
1. Navigate to `https://www.baidu.com` and perform a search
2. Take a snapshot and identify a result link ref (e.g., `e11234` for a Baidu Baike entry)
3. Run `browser4-cli click e11234`
4. Check the current page URL

**Expected:** Clicking a search result link navigates the browser to the target page.

**Actual:** The snapshot reports the same search results page URL. Baidu uses `baidu.com/link?url=...` redirect URLs with JavaScript tracking (`window.location` or `window.open`). The accessibility-tree click appears to trigger the click event, but the JS-based navigation never completes — the browser stays on the search results page. No error is reported.

**Workaround:** Manually construct the target URL and navigate using `goto` instead of clicking.

**Suggested Fix:** Investigate whether the click implementation properly handles pages that use JavaScript `window.location` or `window.open` for navigation rather than standard `<a href>` navigation. Consider whether the click needs to wait for or follow JS-initiated navigations.

Labels: bug, reliability

