# `extract` output goes to file, not stdout

## Summary
The `extract` command writes its AI-generated output to a timestamped `.txt` file in `.browser4-cli/snapshot/` and only prints the file path. For non-batch interactive use, users expect the extracted data to appear directly in the terminal after waiting for AI processing. Instead, they must open a separate file.

## Steps to Reproduce
1. Run `browser4-cli extract "get product titles"`
2. Wait for AI processing to complete
3. Observe that only a file path is printed, not the extracted data

## Expected Behavior
For single-shot (non-batch) usage, extracted data should be viewable directly in the terminal. A `--stdout` flag would allow inline viewing while still writing to file as the default.

## Actual Behavior
Output goes exclusively to a file that must be opened in a separate step. The CLI only prints the path to the output file.

## Suggested Fix
Add a `--stdout` flag to `extract` that prints results to stdout. Consider printing a brief preview or summary inline even when writing the full result to file.

Labels: enhancement, UX, low
