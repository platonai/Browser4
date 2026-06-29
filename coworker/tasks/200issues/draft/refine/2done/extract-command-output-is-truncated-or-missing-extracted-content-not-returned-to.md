# `extract` command output is truncated or missing — extracted content not returned to CLI

The `extract` command appears to call an AI backend to process page content, but its results are not properly surfaced in the CLI output. The extracted content is either truncated with `...` or entirely absent from the output, making the command unusable for its intended purpose.

**Steps to reproduce:**
1. Navigate to any page using `goto <url>`.
2. Run `extract "Summarize this page"`.
3. Observe the output in both default and `--json` modes.

**Expected behavior:** The CLI returns the extracted/summarized content clearly and in full. The `--json` output should include an `extracted_content` field containing the AI-extracted result.

**Actual behavior:**
- Without `--json`: Output shows `success: true message: OK data: {"title":"Hacker News","content":"This is the f...` — the content is truncated with `...`.
- With `--json`: Output contains only page metadata (`page_title`, `page_url`, `snapshot_path`). The actual extraction result is not present.
- The extracted content appears to be lost between the backend and the CLI output layer.

**Suggested improvement:** Ensure `extract` results are fully surfaced in CLI output. The `--json` output should include an `extracted_content` field. Non-JSON output should print the full extracted text without truncation.

Labels: bug, reliability

