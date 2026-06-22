# `scroll down <px>` returns help output instead of scrolling

## Summary

The `browser4-cli scroll down <px>` command is broken: instead of scrolling the page, it outputs the full help text, as if the command was not recognized. This is a high-severity reliability issue that makes a basic, frequently-needed operation unusable.

## Steps to Reproduce

1. Open any page with `browser4-cli open <url>`
2. Run `browser4-cli scroll down 500`

## Expected Behavior

The page scrolls down 500px with a success message or updated snapshot.

## Actual Behavior

The command outputs the full help text (Usage listing all commands), as if the command syntax was wrong. This happened consistently across multiple attempts.

## Additional Context

The syntax `scroll down <px>` is documented in SKILL.md and matches the help output format, but it is silently falling through to the help handler. Workarounds exist (`mousewheel` or `eval "window.scrollBy(...)"`) but are not intuitive for new users.

## Suggested Improvement

Investigate the scroll command parser. The command should be properly recognized and executed. If there is a parsing issue, it should produce a specific error message rather than falling through to help output.

Labels: bug, high, reliability
