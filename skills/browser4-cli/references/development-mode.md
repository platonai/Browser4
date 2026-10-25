---
title: "Development Mode"
description: "One backend per checkout: development ports from 8282 upward, per-checkout CLI state and backend app data roots, the shared config/prototype links, workspace-scoped stop, and the escape hatches."
tier: catalog
---

# Development Mode

## Overview

The CLI enables **development mode** when it runs from inside a Browser4
checkout — a directory holding both `ROOT.md` and `pom.xml`. Every checkout
then owns its backend port, its CLI state namespace and its backend app data
root, so `Browser4-4.13`, `Browser4-4.14` and git worktrees run side by side
instead of adopting each other's backend, overwriting each other's sessions, or
contending for one Chrome profile. Installed (production) builds are untouched:
they keep port `18182` and the flat `~/.browser4` layout.

| | Installed / production | Development (source checkout) |
|---|---|---|
| Backend port | `18182` | first free port from **`8282`** upward |
| CLI state, sessions, config | `~/.browser4/` | `~/.browser4/workspaces/<checkout>-<hash>/` |
| Backend app data (`-Dapp.data.dir`) | `~/.browser4` | `<state dir>/app-data/` — H2/WebDB data, agent memory, logs, the session-name registry |
| Browser context profiles (`-Dapp.tmp.base.dir`) | `%TEMP%/browser4-<user>/context` (`$TMPDIR` on Unix) | `<state dir>/app-data/tmp/` — the `context/groups/{default,named}/PULSAR_CHROME/cx.*` browser profiles |
| Browser prototype | `~/.browser4/browser/chrome/prototype` | linked (junction/symlink) to the global prototype |
| LLM config (`config/conf-enabled`) | `~/.browser4/config` | linked (junction/symlink) into the workspace app data |
| AOT cache | shared | per checkout — no cross-checkout invalidation |
| `stop` | every managed backend + its browsers | **only this checkout's** backends, plus the browsers **they** launched |

## Quick Index

| Topic | Where |
|---|---|
| Which port a checkout uses | [Port Allocation](#port-allocation) |
| Where state and app data live | [State and App Data](#state-and-app-data) |
| What is shared between workspaces | [Shared Paths](#shared-paths) |
| Stopping one workspace's backend | [Stopping Backends](#stopping-backends) |
| Disabling development mode | [Escape Hatches](#escape-hatches) |
| Checking which roots are active | [Verifying](#verifying) |

## Port Allocation

A fresh checkout takes the first free port at or above `8282` (32-port scan,
each candidate probed on loopback for 250 ms). The chosen URL is remembered in
that checkout's state file, so later commands reuse the same port — while it is
free, or still held by this checkout's own backend. If another workspace takes
the port over in the meantime, the next command transparently re-allocates
(from `8282` upward) instead of talking to the neighbour's backend.

With 4.13 already serving on `8282`, the first command in 4.14 uses `8283`.
Ports outside the development range, and URLs that resolve to a real host, are
honoured untouched — an explicit `--server http://localhost:18182` keeps talking
to the installed backend.

## State and App Data

The CLI state directory (server URL, sessions, managed-process registry, AOT
cache) is namespaced per checkout as
`~/.browser4/workspaces/<checkout>-<fnv1a32 of its absolute path>/`. The hash
keeps two checkouts that share a folder name apart, and the slug is stable
across Rust releases. `config set server …` therefore cannot leak into a
neighbouring workspace.

The backend is launched with `-Dapp.data.dir=<state dir>/app-data`, which is
what makes H2/WebDB data, agent memory, logs and the session-name registry
(`app-data/session-registry.json`) workspace-private.

The browser context tree is scoped the same way, through
`-Dapp.tmp.base.dir=<state dir>/app-data/tmp`: the SDK derives
`AppPaths.CONTEXT_BASE_DIR` from that temp root, so
`context/groups/{default,named}/PULSAR_CHROME/cx.*` lands under this checkout.
Without it every checkout shared `%TEMP%/browser4-<user>/context`, and a second
backend discovered the first one's Chrome on that user-data directory, **reused
it**, added tabs to it and resolved `DEFAULT` to the same session UUID. Sharing
browser profiles and logins across checkouts is a hazard, not a feature: two
workspaces can now run **headed** browsers at the same time (the default
`browser.profile.mode=DEFAULT` profile is per workspace here).

A running backend holds those H2 databases, browser profiles and agent memory
open inside that `app-data` directory, so deleting it is a **hard reset** of the
workspace — stop the backend first (`browser4-cli stop`), or expect sessions,
task state and browser profiles to be gone underneath it.

## Shared Paths

Two things stay deliberately global, linked into the workspace app data root:

- `config` — the user's configuration (`conf-enabled`), which holds the LLM API
  keys, so keys keep working without being duplicated. Where links are
  unavailable the tree is copied and re-synced from a content fingerprint.
- `browser/chrome/prototype` — the tree every `SEQUENTIAL`/`TEMPORARY` context
  is copied from. A workspace that cannot link it simply keeps its own.

Session browser profiles are never shared — keeping those private is the point
of the isolation, and `-Dapp.tmp.base.dir` is what enforces it (see
[State and App Data](#state-and-app-data)).

Backend logs are written by the **launcher**, not by the app-data tree:
`BROWSER4_SERVER_LOG_DIR` when set, otherwise
`%TEMP%/browser4/browser4-cli/tmp/cli/` (`browser4-server-jar-port<port>-*.log`).
That file exists even when the JVM never reached its web server, so it is the
only record of a failed start — `browser4-cli doctor log` lists it when the
backend is unreachable.

## Stopping Backends

`browser4-cli stop` is workspace-scoped in development mode: it stops the
backends this checkout registered (plus the resolved loopback port), and leaves
other workspaces running. It also closes the browsers **those** backends
launched — a force-stopped backend cannot do it, and the `PULSAR_CHROME` root
keeps pointing at a parent pid that no longer exists, so nothing later could
attribute it to this checkout. Browsers belonging to another workspace are
never touched. `browser4-cli kill-all` remains the global hammer that clears
every managed backend and browser, across workspaces.

## Escape Hatches

| Goal | How |
|---|---|
| Target a specific backend | `--server <url>` / `config set server <url>` / `BROWSER4_CLI_SERVER` |
| Disable development mode entirely | `BROWSER4_CLI_FORCE_REMOTE_BUNDLE=1` (production ports and flat `~/.browser4` state) |
| Share one state directory across checkouts | `BROWSER4_CLI_STATE_DIR=<dir>` |

Server-URL precedence is unchanged:
`--server` / `BROWSER4_CLI_SERVER` > `config set server` > this checkout's
development port.

## Verifying

```bash
browser4-cli status            # prints "Workspace app data: <path>" in development mode
browser4-cli stop              # stops only this workspace's backends
```

The backend keeps using the shared `~/.browser4` when the workspace app data
root cannot be prepared — the CLI warns once and says so.
