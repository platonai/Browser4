# Help output does not mention `open` can take a URL

## Summary

The `browser4-cli help` output for the `open` command says `open [url]` but does not clearly document or demonstrate that it accepts an optional URL. Many new users may run `open` without a URL and then use `goto` separately, missing the convenience of opening directly to a target page.

## Steps to reproduce

1. Run `browser4-cli help`.
2. Read the section describing the `open` command.

## Expected behavior

The help output should clearly document that `open [url]` accepts an optional URL and provide a usage example, such as `open https://example.com`.

## Actual behavior

The help text mentions `open [url]` but does not prominently illustrate the URL usage. The SKILL.md file has a clear example (`open https://browser4.io`), but this information is not surfaced in the CLI help output.

## Suggested resolution

- Add an explicit example line to the help text for the `open` command, e.g., `open https://example.com` or `open <url>`.
- Consider adding a short description of what happens when `open` is used with versus without a URL.

Labels: documentation, UX
