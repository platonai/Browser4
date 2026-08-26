---
title: "Tab Management"
description: "Use when working with multiple tabs or windows in a browser session: tab lifecycle, GUID-based targeting, cross-session tab operations, and extension-session quirks."
tier: procedure
---

# Tab Management

## Quick Start

```bash
browser4-cli tab-list                    # 1. LIST — tabs: index, GUID, title, URL
browser4-cli tab-new https://example.com # 2. CREATE (about:blank if URL omitted)
browser4-cli tab-select 0                # 3. SWITCH by index (or --guid <guid>)
browser4-cli snapshot -v 0               # re-snapshot: tab switch changed the page context
browser4-cli tab-close 0                 # 4. CLOSE by index (or --guid; bare = current)
browser4-cli tab-list                    # 5. VERIFY — confirm state after changes
```

## When to Use

Use tab commands for multi-tab workflows — opening several pages in one session, switching between them, or closing stale tabs. Typical use cases:

- **Multi-page research** — open each result in its own tab, then switch and snapshot each one.
- **Comparison work** — two or more pages side by side, switching back and forth.
- **Session hygiene** — closing leftover tabs before a fresh task, or after crawling.

All tab commands scope to a session: they affect the session targeted via `-s <session>` (or the DEFAULT session when `-s` is omitted). Run `browser4-cli list` first to see all sessions and their IDs before multi-session tab work.

Tab operations never leave the session — `window new` is the only command that creates a separate browser window (still within the same session's browser instance).

## How It Works

- **GUIDs:** `tab-list` shows a `GUID` column. Use `--guid` for stable targeting across tab reordering. Extension sessions show a `chrome:` prefix on numeric GUIDs; regular sessions use 32-char hex GUIDs.
- **Machine-readable output:** Use `--json` before or after the command: `browser4-cli --json tab-list` or `browser4-cli tab-list --json`. Output is a JSON envelope: `{"command":"tab-list","output":{"count":N,"tabs":[{"index":0,"guid":"...","url":"...","title":"..."}]},"status":"ok"}`. The `tabs` array and `count` are nested inside `output`.
- **Tab insert position:** New tabs are inserted by Chrome (not Browser4). Position depends on Chrome's native behavior — on Windows headless CDP, new tabs appear at index 0 (before the active tab); on macOS and some configurations, after the active tab. Always run `tab-list` after creating tabs to confirm positions before switching by index.
- **No auto-snapshot:** `tab-list` and `tab-close` do NOT trigger automatic snapshots. After `tab-select`, run `snapshot` explicitly to get fresh element refs for the new active tab — re-snapshot before interacting with elements in the new tab.

### JSON example

```bash
browser4-cli --json tab-list
# {"command":"tab-list","output":{"count":1,"tabs":[{"index":0,"guid":"...","url":"about:blank","title":"(no title)"}]},"status":"ok"}
```

## Patterns

### GUID-based targeting (survives reordering)

```bash
browser4-cli tab-close --guid 2AAA0C47D288D3943BA85D31AA8D084C
```

### Close the current tab

```bash
browser4-cli tab-close          # closes the active tab (bare form = current tab)
```

### Windows

```bash
browser4-cli window new "https://example.com"   # open a page in a new browser window
```

### Cross-session tab operations

```bash
browser4-cli -s ext-session tab-list
browser4-cli -s ext-session tab-new https://example.com
browser4-cli -s ext-session tab-select 0
```

### Extension re-attach creates a fresh tab scope

Each `attach --extension` establishes a new WebSocket connection and creates its own tab tracking scope. After re-attaching (e.g., after navigating to `chrome://version/`, which drops the connection), only tabs created through the *new* connection are visible in `tab-list`. Tabs from the previous connection are still open in Chrome but not tracked. To work with those tabs, re-open them via `tab-new` in the new session, or use `-s <name>` to preserve a named session that survives re-attach.

## Flags / Options

| Flag | Applies to | Description |
|------|-----------|-------------|
| `--guid <guid>` | `tab-select`, `tab-close` | Target a tab by stable GUID instead of index |
| `--json` | any tab command | Machine-readable JSON envelope (position before or after the command) |
| `-s <session>` | any tab command | Target a non-default session (the `list` command shows all sessions and IDs) |

## Errors & Recovery

| Symptom | Cause | Fix |
|---------|-------|-----|
| Extension session reports an error on close | Chrome's `chrome.tabs.remove` callback fires an error after the tab is already gone | The CLI verifies the tab was removed and treats the operation as successful |
| `tab-list` shows 1 tab after closing the last one | Chrome requires at least one open tab; a replacement `about:blank` is auto-created | Expected behavior — not an error |
| Tabs missing after re-attach | New connection = new tab scope | Re-open via `tab-new`, or use a named session (`-s <name>`) |
| "Stale" in `list` for extension sessions | All tabs closed; session lost its connection | Reconnect with `attach --extension` |
| Refs fail after `tab-select` | The active page context changed | Re-snapshot before interacting — see [SKILL.md §5](../SKILL.md#5-critical-warnings) |

## See Also

- [snapshot.md](snapshot.md) — re-snapshot after tab switches to get fresh refs
- [attach.md](attach.md) — extension sessions and re-attach behavior
- [SKILL.md §2 Key Concepts](../SKILL.md#2-key-concepts) — sessions and tab scoping
