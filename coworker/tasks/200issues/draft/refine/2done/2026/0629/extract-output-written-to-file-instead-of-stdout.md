# `extract` output written to file instead of stdout

**Severity:** Low  
**Category:** UX

## Summary
The `extract` command writes its AI-generated results to a `.txt` file in `.browser4-cli/snapshot/` instead of printing them to stdout. After waiting for AI processing (which can take seconds), the user sees only a file path and must open the file separately to view results.

## Steps to Reproduce
1. Navigate to a page: `browser4-cli goto "https://www.amazon.com/s?k=pens"`
2. Run: `browser4-cli extract "get the product titles and prices"`
3. Observe: CLI prints a `.txt` file path but not the extracted content

## Expected Behavior
For interactive/non-batch use, extracted data should appear directly in the terminal after AI processing completes. The file path could be shown as a secondary output.

## Actual Behavior
Only the file path is printed. The user must `cat` or open the file to see results, adding friction to every extraction workflow.

## Context
Discovered during an Amazon product search evaluation. This is part of a broader pattern: `snapshot`, `extract`, and other data-output commands all write to files without a stdout option. The constant file-reading loop is the most frequently mentioned UX friction in usability evaluations.

## Suggested Improvement
Add a `--stdout` flag to `extract` that prints results to stdout (while still saving to file). Consider printing a preview/summary inline by default and full content to file.

---

