# element-assertions-and-focus

Before running this scenario, ensure MockSite is running on localhost:18080 (`./bin/test.ps1 mock-site`).

This scenario covers element state assertions and viewport/history helpers: `is visible`, `is enabled`,
`is checked`, `check`, `uncheck`, `focus`, `scrollintoview`, `highlight`, `pushstate`.

Fixture: `http://localhost:18080/b4/mcp-tool-controller-interactive-fixture.html` — it contains
`#click-target` (visible, enabled button), `#hidden-target` (`display: none`), `#disabled-target`
(disabled input), `#check-target` (checkbox), `#fill-target` (text input), `#select-target` (dropdown)
and `#scroll-target` (roughly 1600 px below the fold). Page state is mirrored into
`window.__browser4State` and `#state-log`.

## Steps

### 1. State assertions

1. Open a fresh session and navigate to the interactive fixture.
2. Assert visibility for a visible element, a `display: none` element, and a selector that matches nothing.
3. Assert enabled/disabled state for an enabled and a disabled element.
4. Assert the checked state of `#check-target` before and after toggling it.

### 2. Toggling

5. Check the checkbox, then uncheck it, reading both the assertion result and the page's own state after each step.
6. Toggle a second control (e.g. `#select-target` via `select`) and confirm the two state sets do not interfere.

### 3. Focus, viewport and history

7. Focus the text input and confirm which element holds focus.
8. Scroll the below-the-fold target into view and confirm the page actually scrolled.
9. Highlight an element and confirm the visual marker is applied to that element only.
10. Push a new history entry and confirm the URL changed without a page reload.

## Acceptance Criteria

1. **AC1 — is visible:** `is visible "#click-target"` prints `true`; `is visible "#hidden-target"` prints `false`; a selector that matches nothing also reports `false` rather than an error. Record the exit code for the `false` cases and whether the help documents it (a false assertion must not be silently readable as success in a script).
2. **AC2 — is enabled:** `is enabled "#click-target"` prints `true`; `is enabled "#disabled-target"` prints `false`.
3. **AC3 — check/uncheck round trip:** `check "#check-target"` makes `is checked "#check-target"` print `true` and `window.__browser4State.checkbox` `true`; `uncheck "#check-target"` reverses both.
4. **AC4 — focus:** `focus "#fill-target"` makes `document.activeElement.id` equal `fill-target` (verify with `eval`).
5. **AC5 — scrollintoview:** `scrollintoview "#scroll-target"` increases `window.scrollY`, and the element's box then intersects the viewport (verify with `get box`).
6. **AC6 — highlight:** `highlight "#click-target"` sets an inline outline on that element (verify `element.style.outline` contains the outline colour/width) and does not touch any sibling element.
7. **AC7 — pushstate:** `pushstate "/b4/frame-other.html"` changes `location.pathname` while the page keeps its DOM (the previously asserted elements still resolve and `document.readyState` stays `complete`), and `page-info` reports the new URL.
8. **AC8 — failures are explicit:** every command rejects a bad selector or missing argument with a non-zero exit and a message that names the selector — no silent pass.

## What to evaluate while doing this

- Can a first-time user tell the difference between `is visible` (a query that prints true/false) and `check`/`focus` (actions), and what each returns?
- Is the exit-code contract of the assertion commands documented where a script author would look?
- Does `pushstate` explain that it only changes the client-side URL, and does anything warn when the pushed URL does not match the server?
