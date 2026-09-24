---
title: "Tab Management"
description: "Use when working with multiple tabs or windows in a browser session: the quick start, recipes (GUID targeting, cross-session work, windows), flags and error recovery for tab workflows. The behaviour behind the commands — GUID forms, JSON envelope, insert position, last-tab rule, extension quirks — is documented once in tabs.md."
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

Each tab command acts on the browser of the session it is given (`-s <session>`, otherwise the DEFAULT session), on the tab named by index or by `--guid`. Selecting a tab changes the active page context, so re-snapshot before touching elements in it. The behaviours behind that — GUID forms and prefixes, where Chrome inserts a new tab, the `--json` envelope, what happens to the last tab, and what an extension re-attach resets — are documented once in [tabs.md](tabs.md).

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

### Extension re-attach starts a new tab scope

Tabs opened through the previous connection stay open in Chrome but leave `tab-list` — what that means for your session, and how a named session survives it, is in [tabs.md](tabs.md).

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
| `tab-list` shows 1 tab after closing the last one | Chrome keeps at least one tab open, so a replacement appears (see [tabs.md](tabs.md)) | Expected behavior — not an error |
| Tabs missing after re-attach | New connection = new tab scope | Re-open via `tab-new`, or use a named session (`-s <name>`) |
| "Stale" in `list` for extension sessions | All tabs closed; session lost its connection | Reconnect with `attach --extension` |
| Refs fail after `tab-select` | The active page context changed | Re-snapshot before interacting — see [SKILL.md §5](../SKILL.md#5-critical-warnings) |

## See Also

- [tabs.md](tabs.md) — the behaviour reference behind these commands: GUID forms and prefixes, the `--json` envelope, tab insert position, the last-tab rule, extension-session quirks
- [snapshot.md](snapshot.md) — re-snapshot after tab switches to get fresh refs
- [attach.md](attach.md) — extension sessions and re-attach behavior
- [SKILL.md §2 Key Concepts](../SKILL.md#2-key-concepts) — sessions and tab scoping
