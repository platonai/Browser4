# `click` command times out on cross-domain navigation

## Summary

The `click` command consistently times out (30s) when clicking a link that navigates to a different domain. This occurs with 100% reproduction rate on Hacker News, where all story links point to external article domains.

## Steps to Reproduce

1. Run `browser4-cli goto https://news.ycombinator.com/news`
2. Take a snapshot with `browser4-cli snapshot`
3. Read the snapshot YAML to identify a story link element reference (ref)
4. Run `browser4-cli click <ref>` on a story link pointing to an external domain

## Expected Behavior

The `click` command should navigate to the target page and return a success response within a reasonable time.

## Actual Behavior

The command consistently times out after 30 seconds with an HTTP request timeout error. The navigation does succeed silently — the page does change to the external domain — but the CLI reports failure.

## Impact

- Breaks the core workflow for sites like Hacker News, where the primary interaction is clicking external links
- 100% reproduction rate makes the command effectively unusable for cross-domain navigation
- Errors logged even though the underlying operation succeeds

## Additional Context

- This issue was identified during a Hacker News evaluation where opening the top 3 stories required clicking external article links
- Commands that do not involve cross-domain navigation (`goto`, `go-back`, `summarize`) work correctly
- The timeout is 30 seconds, matching the default HTTP request timeout for backend tool calls

Labels: bug, reliability, high-severity
