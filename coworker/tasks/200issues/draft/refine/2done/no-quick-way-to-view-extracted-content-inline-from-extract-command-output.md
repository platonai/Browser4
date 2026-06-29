# No quick way to view extracted content inline from `extract` command output

Running `browser4-cli extract "..."` writes the extracted data to a timestamped JSON file and prints only the file path. There is no built-in way to preview the extracted content without manually opening or reading that file in a separate step.

**Steps to reproduce:**
1. Run `browser4-cli extract "extract product titles and prices from the search results"`
2. Observe that the CLI prints only a file path.
3. The extracted data itself requires a separate `cat` or file-open step to view.

**Expected behavior:** The CLI should offer a way to see extracted data directly — either by default (a brief summary), or via a flag such as `--print` that sends the JSON content to stdout.

**Actual behavior:** The user must manually note the file path and read the file separately. During rapid iteration (tweaking an extraction prompt and re-running), this back-and-forth adds significant friction.

**Suggested resolution:** Add a `--print` flag to `extract` that writes the full extracted content to stdout. Alternatively, always print a brief inline preview (e.g., keys present, item count, first few fields) alongside the file path so the user can quickly confirm the extraction worked without leaving the terminal.

Labels: enhancement, ux

