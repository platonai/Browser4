# `scroll` command fails — returns help text instead of scrolling

**Severity:** High  
**Category:** Bug / Reliability

## Summary

The `browser4-cli scroll` command does not work as documented. When invoked with a direction and pixel value (e.g., `browser4-cli scroll down 800`), it displays the help text instead of performing the scroll action. This suggests a parsing issue with its positional arguments.

## Steps to Reproduce

1. Navigate to any scrollable page (e.g., Amazon search results).
2. Run: `browser4-cli scroll down 800`

## Expected Behavior

The page scrolls down by 800 pixels.

## Actual Behavior

Help text is displayed, indicating the command was not recognized. The scroll does not execute.

## Workaround

`browser4-cli eval "window.scrollBy(0, 800)"` can be used as a substitute.

## Suggested Fix

Verify the argument parser accepts `direction` and `pixels` as separate positional arguments. Ensure the subcommand routing does not fall through to the help handler when valid arguments are provided.

