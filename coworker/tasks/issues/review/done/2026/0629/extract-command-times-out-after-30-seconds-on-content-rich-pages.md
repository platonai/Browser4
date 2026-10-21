# `extract` Command Times Out After 30 Seconds on Content-Rich Pages

The `extract` command hits a hard 30-second HTTP timeout when processing content-heavy pages, making it unreliable for real-world data extraction tasks.

**Steps to Reproduce:**
1. Navigate to a content-rich page (e.g., a Baidu Baike article)
2. Run `browser4-cli extract "Extract all text content about..."` with a descriptive prompt
3. Wait for the result

**Expected:** Structured extraction of page content based on the instruction, completing within a reasonable time (or with a configurable timeout).

**Actual:** `Error: HTTP request timed out [tool=agent_extract, endpoint=http://localhost:8182/mcp/call-tool, timeout=30s]`

**Workaround:** Use `browser4-cli eval` with raw JavaScript (e.g., `document.body.innerText`) to extract text content, bypassing the AI-powered extraction entirely.

**Suggested Fix:** Increase the default timeout for the `extract` command, or add a `--timeout` parameter so users can adjust it based on page complexity. The 30-second limit is too short for AI-powered extraction on content-rich pages.

Labels: bug, enhancement

