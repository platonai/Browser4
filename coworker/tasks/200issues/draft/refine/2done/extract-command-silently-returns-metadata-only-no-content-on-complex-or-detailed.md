# `extract` command silently returns metadata-only (no content) on complex or detailed prompts

The `extract` command can silently return a successful response that contains only `{metadata: {...}}` with no `content` field, even though the prompt requested detailed structured extraction. This is a silent partial failure — the CLI reports success but no data was actually extracted.

**Steps to reproduce:**
1. Run a simple extraction:
   `browser4-cli extract "get the first 4 search results with title, price..."`
   → Response includes a rich `content` field with extracted data and `links`.
2. Run a more detailed extraction:
   `browser4-cli extract "For each of the first 4 search results, extract: title, price, star rating..."`
   → Response file contains `success: true` but **no `content` field** — only `{metadata: {...}}`.

**Expected behavior:** The second, more detailed prompt also returns extracted content in the `content` field. If the agent cannot fulfill the extraction, it should surface an error or at minimum a warning.

**Actual behavior:** The response is `success: true` with no content. There is no indication to the user that the extraction produced no data — the user only discovers this by manually reading the output file.

**Suggested fixes:**
- Add validation that the `content` field is present and non-empty in `extract` responses.
- Surface a warning to the user (via stderr or inline output) if the agent returned no extraction data despite `success: true`.
- Investigate why the second prompt pattern produced no extraction — possibly a prompt-length limit, a model truncation issue, or a parsing failure in the agent pipeline.

Labels: bug, reliability

