# `extract` Command Unusable for Complex Pages — Times Out on Content-Rich Sites

The `browser4-cli extract` command, which is the primary mechanism for AI-powered structured data extraction, consistently times out on real-world pages like Amazon search results. This makes the core value proposition of the CLI — extracting structured data from web pages — non-functional for any content-rich site.

**Steps to Reproduce:**
1. Navigate to `https://www.amazon.com/s?k=pens+to+draw+on+whiteboards`
2. Run `browser4-cli extract "Extract the first 4 search results"`

**Expected Behavior:** The command returns structured data within a reasonable time (under 60 seconds).

**Actual Behavior:** The command hits an HTTP timeout after 30 seconds (npm-installed CLI) or 180 seconds (cargo-built CLI). Server-side processing never completes for Amazon-scale pages. The user is forced to fall back to `browser4-cli eval` with hand-crafted JavaScript selectors, which required 5 iterations to get right — and is entirely inaccessible to users without JavaScript knowledge.

**Additional Context:**
- An environment variable `BROWSER4_CLI_AGENT_TIMEOUT_SECS` appears to exist but is not documented.
- For a first-time user attempting a data extraction task, the estimated success rate is ~40% due to this issue alone.

**Suggested Improvements:**
- Implement chunked or streaming extraction for large pages so partial results are returned progressively.
- Allow configurable timeouts per-command (document the existing `BROWSER4_CLI_AGENT_TIMEOUT_SECS` env var).
- Provide a lighter-weight extraction mode optimized for search result pages.
- Consider client-side evaluation as an automatic fallback when server-side extraction exceeds a threshold.

**Acceptance Criteria:**
- `extract` completes successfully on an Amazon search results page (1200+ line snapshot) within 60 seconds.
- If extraction cannot complete within the configured timeout, a partial result or clear diagnostic is returned rather than a silent timeout.

Labels: bug, reliability

