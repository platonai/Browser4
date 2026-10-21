# iframes-and-popups

Before running this scenario, ensure MockSite is running on localhost:18080 (`./bin/test.ps1 mock-site`).

This scenario covers frame scoping and multi-window handling: `frames`, `frame`, and `window new`.

Fixture: `http://localhost:18080/b4/frame-switch.html` — a main document with three same-origin iframes:
`#pay-frame` (name `payframe`, contains `#card-number`, `#card-name`, `#pay-submit`, `#pay-state`),
`#other-frame` (name `otherframe`), and `#nested-frame` (name `nestedframe`, which itself embeds
`#inner-frame`, name `innerframe`, containing `#inner-heading`). The main document has `#main-input`,
`#main-button` and `#main-state` (`main-initial` → `main-clicked`).

## Steps

### 1. Discover the frame tree

1. Open a fresh session and navigate to the frame fixture.
2. Wait until `#pay-frame`'s document is loaded (e.g. until its `#pay-submit` element resolves).
3. List the frames and note how the active frame is marked.

### 2. Operate inside a frame

4. Switch into the payment frame and read its heading.
5. Fill the card number **inside** the frame and submit the payment form by clicking its button.
6. Read the frame's state from the main document (via the iframe's `contentDocument`) to confirm the frame really handled the interaction.
7. While still scoped to the frame, check whether a main-document element is reported as visible.
8. Return to the main document and confirm main-document elements resolve again (click `#main-button`, then read `#main-state`).

### 3. Nested frames

9. Switch into `#nested-frame`, then switch again into `#inner-frame` and read the inner heading.
10. Return to the main document.
11. Attempt to switch to a frame that does not exist.

### 4. Windows and tabs

12. Create a new window targeted at `http://localhost:18080/b4/frame-other.html`.
13. List the open targets and confirm the new one is present with the requested URL.
14. Select it and confirm the current page identity.

## Acceptance Criteria

1. **AC1 — frame tree:** `frames` lists `payframe`, `otherframe`, `nestedframe` and the iframe URLs (`frame-pay.html`, …), and marks the currently active frame (`"active":true`).
2. **AC2 — switch by selector:** `frame "#pay-frame"` succeeds and reports the payment frame.
3. **AC3 — scoped interaction:** after switching, `fill "#card-number" 4111-1111-1111-1111` and `click "#pay-submit"` reach the iframe; `#pay-state` becomes `submitted:4111-1111-1111-1111:` (card holder left empty on purpose — record what the CLI reports for the empty field).
4. **AC4 — scoping is real:** while the payment frame is selected, `is visible "#main-button"` reports `false` (the main document is out of scope); after `frame main` it reports `true`.
5. **AC5 — nested frames:** `frame "#nested-frame"` followed by `frame "#inner-frame"` reaches the innermost document and its `#inner-heading` read succeeds.
6. **AC6 — failure is loud:** `frame "#no-such-frame"` exits non-zero with a message naming the missing frame (`Frame not found` or equivalent) instead of silently staying on the previous frame.
7. **AC7 — new window/target:** `window new http://localhost:18080/b4/frame-other.html` creates a target that `tab-list` shows with that URL, and `tab-select` + `page-info` confirm it. Record whether the CLI describes it as a *window* or a *tab* — the help states they are equivalent, so any mismatch between help text and observed behavior is a usability finding.
8. **AC8 — scope resets on navigation:** after navigating the main document, an element command that was previously frame-scoped resolves against the main document again (no silent stale scope).

## What to evaluate while doing this

- Is it discoverable that element commands are frame-scoped at all, and what the accepted `frame` target forms are (ref / CSS selector / name / id / URL fragment)?
- Does the CLI warn when a selector exists in another frame instead of just reporting "not found"?
- Is the difference between `window new`, `tab-new` and a page-opened popup explained anywhere a first-time user would look?
