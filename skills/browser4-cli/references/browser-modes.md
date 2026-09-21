---
title: "Browser Modes — Session, Display, and Browser Source"
description: "Decision guide for the three orthogonal choices when driving a browser with browser4-cli: which session to use (default / named / SWARM), which display mode (headless / headed / SUPERVISED), and where the browser comes from (backend-launched / attach --cdp / attach --extension) — plus the secondary knobs (profile mode, interact level, browser contexts, proxy) and the failure modes of each combination."
tier: decision
---

# Browser Modes — Session, Display, and Browser Source

Three **independent** choices decide how a browser4-cli run behaves — every run picks one value on each axis:

```
session (whose state container) × display (how it renders) × source (whose browser process)
```

**Decision order:** source first (does it reuse an existing login? who owns the browser process?) → session
(concurrency and state isolation) → display (must a human participate?) → secondary knobs (§4).

> **Two hard constraints** limit the combinations:
> 1. **SWARM always launches its own browsers.** A swarm session cannot attach to an existing browser —
>    `swarm create` only accepts `--profile-mode`/`--max-open-tabs`/`--max-browser-contexts`/`--display-mode`.
> 2. **The display mode is fixed when the session is created.** Reconnecting with `open --headed` on an
>    existing session warns and ignores the flag; use `close` + `open`, or `open --fresh`.

## Quick Comparison

| Axis | Option | Choose it when | Cost / caveat |
|---|---|---|---|
| **Session** | default (unnamed) | one task, sequential script, CI one-liner | singleton — two processes without `-s` navigate each other's pages |
| | named `-s <name>` | **any parallelism**; per-task isolation; login survives reopens | one permanent profile dir per name, no automatic eviction |
| | `SWARM` | bulk, non-interactive, throughput | launches its own browsers only; needs the `browser4-swarm` plugin; first jobs wait ~30–60 s |
| **Display** | `HEADLESS` (default) | AI agents, CI, Docker, batch extraction | likelier to be fingerprinted as automation; nobody can intervene |
| | `GUI` (`--headed`) | a human must act (login, CAPTCHA, QR code); demos; visual debugging | uses the desktop; impossible in CI / no-display environments |
| | `SUPERVISED` | wrapping Chrome in an external supervisor process (in practice an Xvfb-based wrapper on Linux) | inert unless a supervisor is configured; **not** implicitly headless |
| **Source** | backend-launched (`open`) | production batches, clean environments, CI | `close` terminates the browser process |
| | `attach --cdp` | debugging live issues, cloud browsers, Electron, SSH-tunnelled remote Chrome | needs a debugging endpoint (explicit one on Linux/macOS) |
| | `attach --extension` | "just use my own browser" with zero flags/ports | not for CI; one relay connection per browser; extension required |

## 1. Axis 1 — Session

| | Default (unnamed) | Named `-s <name>` | SWARM |
|---|---|---|---|
| Slot | exactly one | one per name | fixed id `SWARM` |
| State file | `~/.browser4/cli-state.json` | `~/.browser4/sessions/<name>.json` | `sessions/SWARM.json` |
| Second session in the slot | **refused** (`An unnamed session already exists…`) | always allowed | reused if it already exists (create capabilities are ignored) |
| Browser profile | shared Browser4 profile (`DEFAULT`) | dedicated dir `…/context/groups/named/PULSAR_CHROME/cx.<sessionUuid>` | rotation (`SEQUENTIAL`) or one-shot (`TEMPORARY`) |
| Concurrency | singleton — two processes without `-s` **navigate each other's pages** | isolated per name; parallel-safe | parallel browser contexts + job queue |
| Interaction | full command set | full command set | jobs only: `create` / `submit` / `query` / `status` / `result` / `list` / `close` |
| Default footprint | 1 browser | 1 browser per session | 2 browser contexts × 8 tabs |

**Rules and pitfalls**

- Single task, sequential script, CI one-liner → default session. **Any parallelism → always pass `-s <name>`:**
  the unnamed slot is shared by every invocation that omits `-s`, and `goto` silently reconnects to it
  (last writer wins). Named sessions additionally keep their own cookies/login state across runs.
- `session-default <name>` promotes an existing named session into the unnamed slot (useful when a workflow
  should switch which session is "current").
- Bulk, non-interactive, throughput-oriented → **SWARM** (`swarm create` → `swarm query --sql @q.sql
  --seed-file urls.txt --refresh`). Sequential multi-page crawling with link discovery is `crawl`; periodic
  repeats are `loop` — do not use swarm for either.
- `attach` and `open` share the session namespace: `attach --extension` fails with `An unnamed session
  already exists` unless you pass `-s <name>` or `close` first.
- A swarm session is isolated from default/named sessions and needs the `browser4-swarm` runtime plugin —
  without it the API answers `503 {"error":"Swarm not installed"}`.
- On a fresh swarm session the first jobs stay `queued` for ~30–60 s while the browser contexts boot. That
  is normal, not a stall.
- One `close` = one session. `close-all` closes all sessions and clears local state but leaves the backend
  running. Named sessions create one permanent profile directory each; there is no automatic
  retention/eviction.

## 2. Axis 2 — Display Mode

| Mode | Flag | When it is the right choice | Cost / risk |
|---|---|---|---|
| **HEADLESS** (default) | `--headless` (implicit) | AI agents, CI, Docker, batch extraction | more likely to be fingerprinted as automation; nobody can intervene |
| **GUI** | `--headed` | a human must act (login, CAPTCHA, scan a QR code); demonstrations; visual debugging; keeping a browser open for inspection | uses the desktop; impossible in CI/no-display environments |
| **SUPERVISED** | `swarm create --display-mode SUPERVISED` / `displayMode` capability | wrapping Chrome in an external supervisor process — in practice an Xvfb-based wrapper on Linux | inert unless the supervisor is configured (see below) |

**Resolution order:** (1) `browser.display.mode` in the server config (shipped default `HEADLESS`); (2) the
session's own display preference wins over the server default — an explicit `displayMode` capability beats
the `headed` boolean, and both beat the server default; (3) `open` always sends an explicit preference
(`--headed` → GUI, otherwise HEADLESS), so the server default in practice only applies to sessions created
by other clients.

**Environment overrides**

- In an environment without GUI support (headless CI, Docker) the browser is launched headless regardless of
  `--headed` — the flag degrades instead of failing.
- The standalone MCP server (`java -jar Browser4.jar --app mcp`) has no Spring config, so `--headless` is the
  only thing that keeps it from opening a visible window; the last of `--headless`/`--headed` wins.

**`SUPERVISED` — read this before using it.** It does not mean "headless with a virtual display" by itself:
it makes Browser4 launch `supervisorProcess supervisorArgs chromeBinary chromeArgs` instead of Chrome
directly. Therefore the mode only has an effect when a supervisor process is configured
(`browser.launch.supervisor.process`, args in `browser.launch.supervisor.process.args`) — typically `xvfb-run`
on Linux. If the configured supervisor binary cannot be located it is dropped with a warning and Chrome starts
normally. `SUPERVISED` does **not** imply headless: on a desktop OS without a supervisor configured it behaves
like GUI, and in Docker/headless environments the launch is forced headless anyway.

**Headed-mode reliability and anti-bot notes**

- After `open --headed`, the CLI verifies that a visible window actually exists and warns when the session was
  launched headless anyway, or when the process is headed but no window is detected. If you see that warning,
  `close` and retry `open --headed` once.
- Browser4 passes plain `--headless` (never `--headless=new`), forces
  `--disable-blink-features=AutomationControlled`, and leaves user-agent rotation off by default because
  rotation itself is detectable.
- Sites with strong bot protection may still block automated sessions. When the goal is "act as the logged-in
  user", prefer the attach paths (axis 3) over launching another browser, and consider raising
  `--interact-level` (§4).
- GUI mode is also the diagnosis mode: on shutdown the accompanied pool closer can keep the browser open and point at `chrome://version/` and `chrome://history/` so a human can inspect what happened.
- **No evidence:** there is currently no mode-specific implementation for video/screencast, clipboard, or download behaviour, so do not promise differences between headless and headed for those.

## 3. Axis 3 — Browser Source

| | Backend-launched (`open`) | `attach --cdp` | `attach --extension` |
|---|---|---|---|
| Browser | Chrome launched by Browser4 | any already-running CDP endpoint: Chrome/Edge/Electron/cloud | already-running Chrome/Edge **with the Browser4 extension installed** |
| Setup | none | remote debugging enabled in the target (`chrome://inspect/#remote-debugging`), or start it with `--remote-debugging-port=N` | install the extension; optionally set `BROWSER4_EXTENSION_TOKEN` to skip the approval dialog |
| Login state | whatever the Browser4 profile holds (or `state-save`/`state-load`) | the real profile you are using | the real profile you are using |
| Connection check | — | endpoint probed (`/json/version` + at least one page target) before binding; loud errors otherwise | session stays pending until the extension connects; pending connections expire after ~2 min |
| `close` behaviour | **terminates the browser process** | disconnects; **the browser keeps running**, but the tab Browser4 was driving is closed | disconnects the relay; the browser keeps running, but the tabs Browser4 drove are removed (`chrome.tabs.remove`) |
| If the connection drops | a new session can be created | **never silently replaced** — the command errors and asks you to re-attach | same, and a stale extension session is auto-reconnected once |
| Concurrency | one browser per session | several sessions may attach to the same browser | **one relay connection per browser** — a new attach tears down the previous one |
| Works in CI | yes | only with an explicitly started browser/endpoint | no (needs an interactive Chrome with the extension) |
| Best for | production batches, clean environments, CI | debugging live issues, cloud browsers, Electron, SSH-tunnelled remote Chrome | "just use my own browser" with zero flags/ports |

**`attach --cdp` endpoint resolution.** `--cdp` accepts a channel name (`chrome`, `chrome-canary`, `msedge`,
`msedge-dev`, …), an HTTP endpoint (`http://localhost:9222`), a WebSocket URL, a bare port, or `host:port`.
Channel-name resolution has three tiers: scan running processes for `--remote-debugging-port=N`, then the
channel's default port, then a scan of 9222–9333. **When the browser was started with
`--remote-debugging-port=0` (which is what Browser4-launched browsers use), the real port is discovered by
listing the process's listening ports — this tier is Windows-only.** On Linux/macOS, pass an explicit endpoint
(or start the target browser with a fixed `--remote-debugging-port`) instead of relying on the channel name.

Attaching binds the session to a page tab of the target browser — an existing page when one is available,
otherwise a newly created `about:blank` tab. Subsequent commands act on that bound tab, and `close` closes it
(see [attach.md](attach.md#close-vs-disconnect)); tabs you opened yourself and never drove through the session
are left alone.

**`attach --extension` constraints**

- Non-debuggable pages (`chrome://`, `edge://`, `devtools://`, extension pages) are filtered out; navigating to
  a `chrome://` page can drop the WebSocket.
- Every attach creates a **new session and a new tab scope**: tabs from the previous connection are still open
  in Chrome but are no longer tracked. Use `-s <name>` to preserve a named session across re-attach.
- `BROWSER4_EXTENSION_TOKEN` only auto-approves the connect page; it is not a WebSocket credential — do not
  treat it as a security boundary.
- `--endpoint <server-url>` selects the Browser4 server to run against; it is not a CDP endpoint and cannot be
  combined with `--extension`.

**`SYSTEM_DEFAULT` profile mode is deprecated.** Pointing Browser4 at your everyday browser profile no longer
works with Chrome ≥143. To act as the logged-in user, use one of the attach paths; to move authentication into
a managed session, use `state-save` / `state-load`.

## 4. Secondary Knobs

| Knob | Values / default | Why it matters |
|---|---|---|
| `--profile-mode` | `DEFAULT` (shared) · `SEQUENTIAL` (rotating pool) · `TEMPORARY` (one-shot) · `SYSTEM_DEFAULT` (deprecated) · `PROTOTYPE` (advanced) | Isolation vs state retention vs proxy rotation. `DEFAULT`/`SYSTEM_DEFAULT`/`PROTOTYPE` use 1 context and no tab limit; `SEQUENTIAL`/`TEMPORARY` rotate a pool. |
| `--interact-level` | `FASTEST` … `DEFAULT` … `BEST_DATA` | Selects an interaction profile and delay preset: higher = more human-like, more complete data, slower. The main "behave less like a bot" knob. |
| `--max-browser-contexts` / `--max-open-tabs` | swarm: 2 contexts × 8 tabs | Each context is a browser instance — this is the throughput/memory dial. |
| Browser contexts (server) | `browser.context.number=2`, `browser.max.active.tabs=8` | Global defaults for managed browsers. |
| Proxy rotation | `proxy.rotation.url`, only for `SEQUENTIAL`/`TEMPORARY` | IP rotation requires giving up the shared `DEFAULT` profile. |
| Browser channel | managed = Chrome; attach = chrome*/msedge* | Need Edge/Canary/Electron/cloud → attach only. |
| Platform | GUI-less envs force headless; sandboxed shells need writable `BROWSER4_RUNTIME_DIR` / `BROWSER4_CLI_STATE_DIR` | Determines whether headed is even possible and whether the backend can start. |
| Lifecycle | `close` (one session) · `close-all` · `swarm close` | `swarm close` also aborts pending tasks; forgetting it holds contexts and the worker pool. |
| Observability | `list` (Connection column shows the **actual** browser, flagging channel mismatches) · `status` · `screenshot` | Always confirm the real browser after an attach — driving the wrong profile looks like "lost login state". |
| Cold start | first `open` starts the runtime; first swarm jobs wait 30–60 s | Do not diagnose a cold start as a hang. |
| Per-session concurrency | commands on one session are serialized | Parallelism comes from multiple sessions/contexts, not from issuing commands concurrently to one session. |

## Decision Tree

```
Need to drive a browser
├─ Need the login/state of the browser you already use, a live page, a cloud
│  browser, or Electron?
│  ├─ No debugging-port setup wanted → attach --extension [channel]
│  │    (avoid chrome:// pages; one session per browser; not for CI)
│  └─ Want a controlled/remote endpoint → attach --cdp <url|host:port|channel>
│       (on Linux/macOS pass an explicit endpoint; `close` leaves the browser running)
├─ Bulk, non-interactive, throughput?
│  └─ swarm create [--profile-mode TEMPORARY] [--max-browser-contexts N]
│     → swarm query --sql @q.sql --seed-file urls.txt --refresh
│     (browser4-swarm plugin required; always `swarm close` at the end)
├─ Parallel work, several tasks, or isolated state?
│  └─ one `-s <name>` per task  (dedicated profile; login state survives reopens)
└─ Otherwise → default session, `open --headless <url>`
   ├─ Human must act (login/CAPTCHA/demo)? → `open --headed`
   │    (display mode is fixed at creation: `close` or `--fresh` to change it)
   ├─ Want a throwaway environment? → `--profile-mode TEMPORARY`
   └─ Page withholds data from "robots"? → raise `--interact-level`
        (FAST → GOOD_DATA/BEST_DATA), consider headed, or switch to attach
```

## When to Use Each

| Scenario | Session | Display | Source |
|---|---|---|---|
| Routine AI-agent automation | default, or one `-s` per task when parallel | headless | managed |
| Authenticated site with bot protection | `-s <name>` (keep the profile) | headed for the first login, else headless | `attach --extension` |
| Human-in-the-loop (CAPTCHA, 2FA, demo) | default/named | **headed** | managed or attach |
| Bulk extraction (hundreds–thousands of URLs) | SWARM | `HEADLESS` | managed (swarm's own contexts) |
| Debugging a live issue / user's session | `-s debug` | n/a | `attach --cdp` |
| Cloud browser / headless server / Electron | `-s cloud` | n/a | `attach --cdp <ws|url>` |
| CI / Docker | default | headless (forced) | managed |
| One-off clean scrape | default | headless | managed + `--profile-mode TEMPORARY` |

## Quick Patterns

```bash
# Headless one-off in the default (singleton) session
browser4-cli open --headless https://example.com
# Parallel work: one named session per task, isolated profile and login state
browser4-cli -s task-a open --headless https://example.com
# A human must act (login/CAPTCHA/QR): headed, then reuse the profile afterwards
browser4-cli -s task-a open --headed https://example.com
# Reuse your own logged-in browser: no ports, no flags
browser4-cli attach --extension
# Controlled or remote endpoint; `close` leaves that browser running
browser4-cli attach --cdp http://localhost:9222
# Bulk throughput: swarm jobs, then always close the swarm
browser4-cli swarm create --profile-mode TEMPORARY --max-browser-contexts 4
browser4-cli swarm query --sql @q.sql --seed-file urls.txt --refresh
browser4-cli swarm close
```

Display mode is fixed at session creation — `close` + `open`, or `open --fresh`, to change it. When a page withholds data from "robots", raise `--interact-level`.

## Limits Worth Verifying Before You Rely On Them

Both behaviours below are documented but not covered by an assertion. Each has a runnable measurement script
under `browser4-tests/tests-production/` (they print a verdict and never assert, so any measured outcome
exits 0).

**A. Named-session profile binding across a backend restart.** Named sessions get the dedicated profile
directory `cx.<sessionUuid>`, and the CLI reuses a stored session id only while the backend still reports that
session as active. The backend's name→UUID mapping is in memory, so after a backend/daemon restart a re-open
by name may resolve to a *new* UUID and therefore a new (empty) profile directory.

```powershell
# automated (the restart phase is destructive → opt-in)
pwsh browser4-tests/tests-production/verify-named-session-profile.ps1 -RestartBackend
```

Manual equivalent: `browser4-cli -s repro open --headless https://example.com` → `-s repro cookie-set
b4_repro_marker keepme --expires 7d` (persistent marker) → `-s repro cookie-list` (marker present) →
`browser4-cli stop` (stops the backend) → `-s repro open --headless https://example.com` (auto-starts a fresh
backend) → `-s repro cookie-list` (marker still present?). Also compare the directories under
`~/.browser4/context/groups/named/PULSAR_CHROME/`: a **new** `cx.<uuid>` directory means a new, empty profile.

- **STABLE** — same session id and the marker cookie came back: reopening by name really restores the same profile.
- **REBOUND** — the session identity was not preserved (new session id) and/or the marker cookie was lost:
  login state is *not* guaranteed across a restart. Persist auth with `state-save` / `state-load` instead of
  relying on the profile. A lost cookie alone does not prove a new profile directory — a non-persistent
  (temporary/incognito-like) context drops it too — so the script reports the session id, the cookie, and the
  profile directories as separate signals. The on-disk profile layout has differed between versions, so the
  directory signal is best effort: both `context/groups/named/**` and `browser/chrome/**` are searched for
  `cx.*` directories.

> **Careful with the restart step:** `browser4-cli stop` also sweeps orphaned browser processes, so every
> browser Browser4 launched is closed with it.

**B. What `close` does to the tabs of an attached session.**

```powershell
# 1. start a target browser with remote debugging enabled
chrome --remote-debugging-port=9222 https://example.com
# 2. measure (observes /json/version and /json/list directly, not through the CLI)
pwsh browser4-tests/tests-production/verify-attach-close-tabs.ps1 -Cdp 9222
```

It reports `PROCESS SURVIVED` / `PROCESS KILLED` plus `TABS CLOSED` (listing the tabs that disappeared) or
`TABS UNTOUCHED`. Extension-attached sessions can be checked by hand: `attach --extension`, note the tabs with
`tab-list`, `close`, then compare — the relay removes the tabs it drove via `chrome.tabs.remove`.

**Measured results (reference machine, 2026-09-11)** — measured with CLI `4.13.17` and the local runtime
bundle `4.13.14-SNAPSHOT`; the CLI warned that the served backend was **older than the checked-out sources**
(`4.13.18-SNAPSHOT`), so re-run both scripts after a bundle rebuild before treating these as current
behaviour.

| Measurement | Observed |
|---|---|
| Named-session profile across a backend restart | **REBOUND** — the session id changed (`b8c986c7…` → `8d99d197…`) after `stop` + re-open by name, and the persistent marker cookie (`--expires 7d`) was gone (`cookie-list` → `[]`, `document.cookie` empty). No `named`/`cx.*` profile directories existed on that build, so the directory signal could not be attributed. |
| `close` on a CDP-attached session | **PROCESS SURVIVED + TABS CLOSED** — the browser kept answering `/json/version`; the tab the session had bound (a newly created `about:blank`) disappeared, while the pre-existing `https://example.com/` tab stayed open. Confirms the close semantics in [attach.md](attach.md#close-vs-disconnect). |

**Other open points.** `PROTOTYPE` profile mode is documented as the base for `SEQUENTIAL`/`TEMPORARY`, while
the in-repo generator for it currently creates a default profile — treat it as advanced/unverified.
Mode-specific media behaviour (video, clipboard, downloads) is not implemented per mode, so no differences can
be promised.

## Reference Map

- [attach.md](attach.md) — full `attach` reference (CDP and extension)
- [swarm.md](swarm.md) — swarm session, jobs, and lifecycle
- [decision-trees.md](decision-trees.md) — which extraction method to use
- [storage-state.md](storage-state.md) — moving auth state into managed sessions
- [load-options-guide.md](load-options-guide.md) — `-interactLevel` and friends
