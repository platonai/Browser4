# `extract` command writes output only to a file; no inline display of extracted data

When the `extract` command completes successfully, it prints a timestamped file path to stdout but does not display the extracted data inline. This adds unnecessary friction — the user must note the path, then separately open or `cat` the file to see the results.

**Steps to reproduce:**
1. Run `browser4-cli extract "extract the first 4 search results with title, price, and rating"`
2. Observe the CLI output

**Expected behavior:** An option to see extracted data inline on stdout, or at minimum a brief JSON summary of what was extracted (e.g., number of items, keys present).

**Actual behavior:** The CLI prints only a file path:
```
Response written to: D:\path\to\response-20260628-141732-abc123.json
```
The user must then manually read that file to see the extracted content.

**Suggested resolution:** Add a `--inline` or `--print` flag that pipes the extracted content (or a structured preview) directly to stdout, eliminating the need for a separate file-read step during interactive use.

Labels: enhancement, ux

