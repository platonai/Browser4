---
title: "Tabs — Lifecycle, GUIDs, and Extension Sessions"
description: "Reference for tab management: tab-list / tab-new / tab-select / tab-close, stable GUID targeting, session scoping, last-tab behavior, insert-position caveats, and the extension-attached session quirks (delayed close confirmation, re-attach tab scope)."
tier: catalog
---

# Tabs — Lifecycle, GUIDs, and Extension Sessions

Tab commands scope to a session — all operations affect the session targeted via
`-s <session>` (or the DEFAULT session when `-s` is omitted).

## Overview

Canonical reference for the tab command family and the behaviour behind it: stable GUID
targeting, session scoping, last-tab handling, insert-position caveats, and the quirks of
extension-attached sessions. Read it when a tab you expected is missing from `tab-list`, or
before you rely on an index. The step-by-step workflow, the recipes and the error recovery
live in [tab-management.md](tab-management.md).

## Quick Index

| Command | Returns | One-line description |
|---------|---------|----------------------|
| `tab-list` | tab table, or a JSON envelope with `output.tabs[]` and `output.count` | List the session's tabs with index, GUID, title, URL and active flag |
| `tab-new [url]` | the new tab's GUID (the index is Chrome's choice) | Open a tab; `about:blank` when the URL is omitted |
| `tab-select <index>` / `--guid <guid>` | — | Make a tab the active page context — re-`snapshot` afterwards |
| `tab-close [index]` / `--guid <guid>` | — | Close a tab; closes the current tab when no target is given |

All four accept `-s <session>` (DEFAULT session otherwise) and `--json`.

## Key notes

- **GUIDs:** `tab-list` shows a `GUID` column. Use `--guid` for stable targeting across tab
  reordering. Extension sessions show a `chrome:` prefix on numeric GUIDs; regular sessions use
  32-char hex GUIDs.
- **Machine-readable output:** Use `--json` either before or after the command:
  `browser4-cli --json tab-list` or `browser4-cli tab-list --json`. Output is a JSON envelope —
  `{"status":"ok","command":"tab-list","output":{"tabs":[{"index":0,"guid":"…","url":"…","title":"…","active":true}],"count":N}}`.
  The `tabs` array and `count` are nested inside `output`, and each tab also carries `active`.
- **Session scoping:** Prefix tab commands with `-s <session-id>` to target a non-default session.
  The `list` command shows all tracked sessions and their IDs.
- **Last-tab behavior:** Chrome requires at least one open tab. Closing the last tab makes Chrome
  create a replacement — the CLI prints a note on stderr, and `tab-list` still shows 1 tab
  afterward. Whether that replacement is blank depends on Chrome's behavior, so do not rely on it
  being `about:blank`.
- **Tab insert position:** New tabs are inserted by Chrome (not Browser4), and the resulting index
  varies by platform, Chrome version and mode. The CLI re-identifies the new tab by GUID rather
  than assuming a position. Always run `tab-list` after creating a tab, and prefer `--guid` when
  order matters.
- **No auto-snapshot:** `tab-list`, `tab-new`, `tab-close` and `tab-select` do NOT trigger
  automatic snapshots. After `tab-select`, run `snapshot` explicitly to get fresh element refs for
  the new active tab — the switch changes the active page context.
- **Extension sessions:** When closing tabs on extension-attached sessions, the backend may report
  an error even though the tab was successfully closed (Chrome's `chrome.tabs.remove` callback can
  fire an error after the tab is already gone). The CLI verifies that the tab was actually removed
  and treats the operation as successful in this case. Extension sessions may also show "Stale" in
  `list` output when the backend no longer has a live connection for them (for example after Chrome
  drops the WebSocket) — reconnect with `attach --extension`.
- **Extension re-attach creates a fresh tab scope:** Each `attach --extension` establishes a new
  WebSocket connection and creates its own tab tracking scope. After re-attaching (e.g. after
  navigating to `chrome://version/` which drops the connection), only tabs created through the
  *new* connection are visible in `tab-list`. Tabs from the previous connection are still open in
  Chrome but are not tracked by the new session. To work with those tabs, either re-open them via
  `tab-new` in the new session, or use `-s <name>` to preserve a named session that survives
  re-attach.

## Examples

```bash
# List all tabs in the default session
browser4-cli tab-list

# Machine-readable tab data (both forms work)
browser4-cli --json tab-list
browser4-cli tab-list --json
# Output: {"status":"ok","command":"tab-list","output":{"tabs":[{"index":0,"guid":"…","url":"about:blank","title":"(no title)","active":true}],"count":1}}

# Open a tab and switch to it
browser4-cli tab-new https://httpbin.org/get
# Output:
#   Created tab with GUID: 2AAA0C47... (https://httpbin.org/get)
#   Switched to tab 0 (https://httpbin.org/get)
# Note: the index is chosen by Chrome and varies by platform and mode.
# Run `tab-list` to confirm it, or target the tab with --guid.

# Close by GUID (survives reordering)
browser4-cli tab-close --guid 2AAA0C47D288D3943BA85D31AA8D084C
```

## Related

- [tab-management.md](tab-management.md) — the tab workflow procedures: quick start, recipes, flags, error recovery
- [browser-modes.md](browser-modes.md) — session and browser-source choices (managed / `attach --cdp` / `attach --extension`)
- [snapshot.md](snapshot.md) — re-capturing refs after a tab switch
