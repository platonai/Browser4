# Add a built-in command for page body / article text extraction

Navigating to a page and then reading its text content is the single most common browser automation workflow. Currently, there is no command like `get text body` or `extract-content` that directly returns the page's main text. Users must write raw JavaScript via `eval` (e.g., `document.body.innerText.substring(0, 4000)`) to get page text, which requires JavaScript knowledge and manual truncation to avoid enormous output. The `extract` command exists but calls an AI backend and its output is not surfaced in the CLI (see related issue about `extract` output).

**Steps to reproduce:**
1. Navigate to any article page using `goto <url>`.
2. Try to extract the article's readable text content.
3. Observe that no command like `get text body` or `extract-content` exists.

**Expected behavior:** A command or straightforward workflow to retrieve the main textual content of a page (e.g., `get text page`, or `extract --mode=article`). Content should be returned with sensible default truncation (e.g., first 2000 characters) and a `--full` flag for complete text.

**Actual behavior:** The user must write raw JavaScript via `eval` (e.g., `document.body.innerText.substring(0, 4000)`) to get page text. This requires JavaScript knowledge and manual truncation to avoid flooding the terminal.

**Suggested improvement:** Add a `get text` target that works on the full page body or auto-detects the main content area. Alternatively, enhance `extract` to reliably return LLM-extracted summaries as CLI output. Document this as the primary workflow for article summarization.

Labels: enhancement, ux

