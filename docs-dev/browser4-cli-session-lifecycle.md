# browser4-cli Session Lifecycle: `open` vs `goto`

## Overview

`browser4-cli` manages browser sessions that persist across CLI invocations. Two
commands can navigate to a URL — `open` and `goto` — but they differ in how they
handle the underlying session.

---

## Quick reference

| | `open` | `goto` |
|---|---|---|
| **Session** | Always creates a **new** session | **Reuses** existing; auto-creates if none |
| **URL** | Optional (defaults to `about:blank`) | Required |
| **Profile options** | `--headed`, `--persistent`, `--profile`, `--profile-mode` | None |
| **Snapshot** | Only when URL is non-empty | Always |
| **Prints session ID** | Always | Only when auto-creating |

---

## `open` — Create a fresh browser session

`open` unconditionally creates a new browser session. If a session was already
active, it is replaced.

### Without a URL

```bash
browser4-cli open
# → Session opened: <new-id>
# No navigation, no snapshot. Browser sits at about:blank.
```

### With a URL

```bash
browser4-cli open https://example.com/
# → Session opened: <new-id>
# → Navigates to the URL, prints page snapshot.
```

### With profile options

```bash
browser4-cli open --headed --profile-mode=TEMPORARY https://example.com/
# → Opens a visible browser with a temporary profile, navigates to example.com.
```

### When to use `open`

- You want a **clean slate** — discard the previous session and start fresh.
- You need to configure the browser profile (`--headed`, `--profile-mode`, etc.).
- You want to open a browser without navigating anywhere (just `open` with no URL).

---

## `goto` — Navigate (create session only if needed)

`goto` navigates to a URL. It reuses the current session if one is active;
otherwise it auto-creates one.

### With an existing session

```bash
browser4-cli open https://example.com/
browser4-cli goto https://httpbin.org/
# → No "Session opened" message. Navigates the existing session to httpbin.org.
```

### Without an existing session (standalone)

```bash
browser4-cli goto https://example.com/
# → Session opened: <auto-created-id>
# → Navigates to example.com, prints page snapshot.
```

Unlike `open`, `goto` **always** navigates and **always** produces a snapshot.

### When to use `goto`

- You want to navigate somewhere and don't care whether a session already exists.
- You're scripting a multi-step flow across CLI invocations and want to reuse the
  same browser session.
- You want the simplest possible command: `goto <url>` just works.

---

## Session persistence

Sessions survive across CLI invocations. State is stored in the CLI state
directory (`~/.browser4/cli/state.json` on Linux/macOS, or
`%LOCALAPPDATA%/browser4/cli/state.json` on Windows).

```bash
browser4-cli open https://example.com/
# ... hours later, in a different terminal ...
browser4-cli goto https://httpbin.org/     # reuses the same session
browser4-cli snapshot                       # captures current page state
browser4-cli close                          # explicitly end the session
```

## Session lifecycle commands

| Command | Effect |
|---------|--------|
| `open [url]` | Create new session, optionally navigate |
| `goto <url>` | Navigate (auto-create session if needed) |
| `close` | Close current session |
| `close-all` | Close all sessions across servers |
| `kill-all` | Stop Browser4 server and kill all browser processes |
| `list` | Show active sessions and their status |

## Implementation notes

`goto` was changed in the 4.8.x branch to auto-create sessions. Previously it
required a prior `open` call and would fail with:

```
Error: No active session. Run "browser4-cli open" first.
```

The implementation (`handle_goto` in `src/main.rs`) reads the current session
state:

- **Session exists:** clones the existing session ID and calls `browser_navigate`.
- **No session:** calls `create_session` (same path as `open`), prints the new
  session ID, then navigates.

Both `open` and `goto` delegate navigation to the same server-side
`browser_navigate` MCP tool. The difference is purely in how the CLI manages the
session lifecycle before making that call.

`goto` takes its own post-navigation snapshot internally (matching `open`'s
pattern), so it is listed in `no_snapshot_commands()` to prevent the generic
`run()` dispatcher from taking a redundant second snapshot.
