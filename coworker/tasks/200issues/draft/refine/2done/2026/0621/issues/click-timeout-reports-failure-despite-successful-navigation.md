# `click` timeout reports failure despite successful navigation

## Summary

When the `click` command is used on a cross-domain link and times out, the underlying navigation still succeeds silently. This creates a misleading state where the CLI reports a failure error but the browser has actually navigated to the target page. The user has no way to distinguish between a genuine failure and a silent success.

## Steps to Reproduce

1. Run `browser4-cli goto https://news.ycombinator.com/news`
2. Take a snapshot and identify a story link ref
3. Run `browser4-cli click <ref>` (the command will time out)
4. After the timeout error, run `browser4-cli snapshot` to see the current page

## Expected Behavior

If the navigation succeeds, the CLI should report success. If it fails, the CLI should report failure and the browser should remain on the original page.

## Actual Behavior

The CLI reports a timeout error, suggesting the command failed. However, the browser has actually navigated to the target page (confirmed by running `snapshot` after the timeout). This creates uncertainty about the browser state and whether subsequent commands will operate on the new or old page.

## Impact

- User cannot trust error messages from `click` on cross-domain links
- Subsequent commands may operate on an unexpected page
- No recovery guidance is provided (e.g., "navigation may have succeeded, run `snapshot` to verify")
- Particularly problematic for workflows like opening Hacker News stories, where every story link is cross-domain

Labels: bug, reliability, UX
