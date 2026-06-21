# `scroll` command documented in SKILL.md but not implemented in CLI

The `scroll` command is prominently documented in SKILL.md as a supported command, but the actual CLI does not recognize it. Running `browser4-cli scroll down 500` prints the full help text instead of scrolling the page, indicating the command is not wired up. Users must fall back to `browser4-cli eval "window.scrollBy(0, N)"` as a workaround, which is unintuitive and poorly documented.

## Steps to reproduce

1. Open any page longer than one viewport: `browser4-cli open https://www.baidu.com`
2. Attempt to scroll: `browser4-cli scroll down 500`

## Expected behavior

The page scrolls down by 500 pixels.

## Actual behavior

The full help text is printed; the command is not recognized.

## Additional context

- The `eval "window.scrollBy(0, N)"` workaround works but is undiscoverable for new users.
- This is a critical blocker for real-world browsing, as most pages are longer than one viewport.
- Either implement the `scroll` command as documented, or remove it from SKILL.md to avoid misleading users.

