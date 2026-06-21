# browser4-cli: help output missing examples for search command

**Severity:** Medium
**Category:** Documentation

## Reproduction Steps

1. Run `browser4-cli help` or `browser4-cli search --help`
2. Observe the help output for the search command
3. Note the absence of any usage examples in the help text

## Expected Behavior

The help output should include at least 2–3 common usage examples, such as:
- Basic search: `browser4-cli search "query"`
- Search with site filter: `browser4-cli search "query" --site example.com`
- Search with result limit: `browser4-cli search "query" --max-results 5`

## Actual Behavior

The help output lists flags and arguments but provides no examples showing
how to construct real commands. First-time users must experiment or read
source code to understand correct syntax.

## Suggested Improvement

Add an **Examples** section to the help output for each command, following the
convention established by widely-used CLI tools such as `curl`, `git`, and
`docker`. Each example should show a realistic command invocation and a brief
description of what it does.
