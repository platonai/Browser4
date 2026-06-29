# `eval` command requires JavaScript knowledge and manual output size management for basic content reading

Using `eval` to read page content is the only available workaround for text extraction, but it requires users to write JavaScript expressions and manually manage output size — an error-prone approach that creates unnecessary friction.

**Steps to reproduce:**
1. Navigate to a content-rich page.
2. Run `eval "document.body.innerText"` without truncation.
3. Observe potentially enormous output, or run `eval "document.body.innerText.substring(0, 4000)"` with a guessed-at character limit.

**Expected behavior:** A content-extraction command that returns a reasonable amount of text by default, with options for more.

**Actual behavior:** Users must write JavaScript expressions for every extraction, manually `substring()` to avoid flooding the terminal, and guess at reasonable character limits. This is error-prone and creates friction in a core workflow (reading page content).

**Suggested improvement:** Add a `read` or `content` command that extracts readable page text with sensible defaults (e.g., first 2000 characters, with `--full` flag for everything). Document this as the primary way to read page content. Even if a dedicated `get text` command is added later, `eval` should offer better defaults for text extraction.

Labels: enhancement, ux

