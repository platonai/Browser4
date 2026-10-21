# console-errors-and-cdp

Before running this scenario, ensure MockSite is running on localhost:18080 (`./bin/test.ps1 mock-site`).

This scenario covers page-side diagnostics and the low-level escape hatches: `console`, `errors`,
`dialog-status`, `cdp`, `tool list`, and `tool call`.

Fixtures:
- `http://localhost:18080/assets/consolelog.html` — logs `here:<url>` to the console on load.
- `http://localhost:18080/assets/error.html` — calls `console.error('Not a JS error')` and then throws an uncaught `Error('Fancy error!')` from `myscript.js`.
- `http://localhost:18080/b4/console-probe-fixture.html` — a serialization probe; nothing is logged until `b4Probe.run()` is called from the page.
- `http://localhost:18080/b4/mcp-tool-controller-interactive-fixture.html` — has `#prompt-target` (prompt) and `#confirm-target` (confirm) buttons and mirrors dialog outcomes into `window.__browser4State` / `#state-log`.

## Steps

### 1. Console capture

1. Navigate to the console-log fixture and read the captured console messages at the default level.
2. Raise the minimum level to `warn` and read again.
3. Navigate to the error fixture and read the console messages and the page errors separately.
4. Read the console-probe fixture, then trigger `b4Probe.run()` from the page and read the console again — note whether the probe itself becomes observable.

### 2. Clearing

5. Clear the console list and read it again.
6. Clear the error list and read it again.

### 3. Raw DevTools Protocol

7. Use a raw CDP call to evaluate `document.title` in the page and compare the answer with the CLI's own `eval` command.
8. Use the same raw channel to read something structural (e.g. the document's frame tree or node count) and note whether the raw shape is usable without documentation.

### 4. Generic tool passthrough

9. List the available agent tools with `tool list` (one name per line) and narrow it with `tool list --filter <substring>`. Compare that list with `plugin list` and note which one actually names callable tools; pick one concrete tool name.
10. Invoke that tool through `tool call <name> --json '<args>'` and compare the result with the equivalent typed CLI command (if one exists).

### 5. Dialog state

11. On the interactive fixture, click `#confirm-target` **without** handling the dialog, then query the dialog state.
12. Accept the dialog and check the page state; repeat the click and dismiss it instead.
13. Repeat once with `#prompt-target`, accepting with a text value.

## Acceptance Criteria

1. **AC1 — console levels:** `console` returns the fixture's `console.log` entry; `console warn` (minimum level `warn`) excludes plain `log` entries and keeps warnings/errors.
2. **AC2 — page errors:** `errors` reports the uncaught `Fancy error!` with a source/line reference; `console` alone does not surface it as a page error.
3. **AC3 — clearing:** after `console --clear` a subsequent `console` read is empty; after `errors --clear` a subsequent `errors` read is empty.
4. **AC4 — raw CDP:** `cdp Runtime.evaluate --json '{"expression":"document.title"}'` returns the same title as `eval document.title`.
5. **AC5 — tool discovery and passthrough:** `tool list` prints the callable MCP tool names, one per line, and works without a page session; `tool list --filter <substring>` keeps only names containing it (case-insensitive), and a filter that matches nothing prints nothing rather than falling back to the full list. `tool call <name> --json '<args>'` then prints a JSON result for a name taken from that listing; record the exact name and whether its arguments were discoverable without reading backend source.
6. **AC6 — dialog state is queryable:** after clicking `#confirm-target` without handling it, `dialog-status` reports a pending dialog and identifies its type/message — it must not report "no dialog" while the page is blocked.
7. **AC7 — dialog handling:** `dialog-accept` resolves the confirm dialog and `#state-log` then shows `confirmResult: "accepted"`; a click followed by `dialog-dismiss` produces `"dismissed"`; the prompt case records the typed value (or `__dismissed__` when dismissed).
8. **AC8 — no silent failure:** every command that cannot act (unknown tool name, invalid JSON params, no browser session) fails with a non-zero exit and an actionable message; record the exact text otherwise.

## What to evaluate while doing this

- Are the console/error commands discoverable by a first-time user who only knows "I want to see what the page logged"?
- Does `dialog-status` explain that it is session/tab scoped, and does the CLI clearly distinguish "no dialog" from "cannot tell"?
- Is `tool list` discoverable as the way to find tool names (as opposed to `plugin list`, which lists installed plugin JARs), and is `tool call` documented well enough to use without reading the backend, including how to discover a tool's argument shapes?
