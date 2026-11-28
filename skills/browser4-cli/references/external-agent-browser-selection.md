---
title: "MCP Agent — Browser & Connection Selection"
description: "How an external MCP agent (Claude Desktop, GPT, any MCP client) picks which Browser4 browser to drive and how to connect it — the session.open vs session.attach decision tree, every capability key the agent can pass, channel/display/profileMode quick-reference, bot-detection escalation with session reuse, and ready-to-copy examples for the common task types."
tier: decision
---

# MCP Agent — Browser & Connection Selection

An external agent that talks to Browser4 over the MCP protocol (HTTP or STDIO) **has full control** over which browser to launch and how to connect it. The agent decides this by calling two tools from the `session` domain:

| Tool | What it does | When to call it |
|---|---|---|
| `session.open` | Launch a fresh browser, owned by Browser4 | Most tasks — clean environment, no existing login needed |
| `session.attach` | Connect to a browser that is already running | Reuse a real browser's login/cookies (banking, social, payment) |

The choice happens **before any browser tool runs**. Once the session is created, every `browser.*` call operates on that session's browser — the channel, profile mode, and display mode are fixed. To change them, close the session and open a new one (same or different `sessionId` depending on whether profile state must survive).

---

## 1. Decision Tree — Pick `open` or `attach`

```
Task starts. Does it need a browser that is ALREADY logged in somewhere?
│
├─ YES — the task says "my account", "my dashboard", "purchase as me",
│        "check my email", or any other phrase that implies a real person's state
│  │
│  ├─ Your everyday Chrome/Edge has the Browser4 Extension installed?
│  │   → session.attach(extension: true, channel: "chrome")
│  │
│  ├─ No extension, but Chrome/Edge was started with --remote-debugging-port=9222
│  │   → session.attach(cdpPort: 9222, channel: "chrome")
│  │
│  └─ Neither — the attach path is not available
│      → Fall back to session.open(capabilities={...}) and plan
│        state save / state load for login migration
│
└─ NO — the task wants a fresh, anonymous, or batch environment
   │
   ├─ Anti-detection matters (finance platforms, strict anti-bot sites,
   │   "this site blocks headless browsers")
   │   → session.open(capabilities={channel: "ego-lite", profileMode: "TEMPORARY"})
   │     ego lite owns its own fingerprint layer; Browser4 never injects
   │     stealth.js or rewrites the UA on an ego-lite session.
   │
   ├─ Batch extraction / swarm / high-throughput crawling
   │   → session.open(capabilities={profileMode: "SEQUENTIAL", ...})
   │     Browser4 provides a rotating pool of profiles; pair with proxy.rotation.url
   │     for IP isolation.
   │
   ├─ Long-running agent conversation that must remember cookies / history
   │   → session.open(sessionId: "my-agent", capabilities={profileMode: "DEFAULT"})
   │     A named session + DEFAULT profile keeps state across turns.
   │
   └─ One-shot task, no state needs to survive
       → session.open(capabilities={profileMode: "TEMPORARY"})
         A fresh random profile dir each time, cleaned up automatically.
```

> **The rule of thumb:** attach when login state matters. open everywhere else. attach reuses a real person's browser — you can't drive it without that person's context, and closing the session never closes their browser. open gives you a clean sandbox you fully control.

---

## 2. session.open — The `capabilities` Map

`session.open` accepts a `capabilities` map where every key is a **Browser4 session-level capability**. The map is parsed by `PulsarSessionManager.normalizeCapabilities()` and forwarded to `PulsarSettings.parse()` with **no filtering**: every key the agent sends is honoured. Only `profileMode` gets a smart default; `channel`, `displayMode`, and the rest pass through verbatim.

### Every capability key the agent can set

| Key | Values | Default | What it controls |
|---|---|---|---|
| `channel` | `"chrome"` · `"ego-lite"` | `"chrome"` | Which browser binary to launch. Managed channels only: `chrome` family (chrome, chrome-beta, chrome-dev, chrome-canary) and `ego-lite`. Edge/Brave/Opera → `session.attach`, not `session.open`. |
| `displayMode` | `"HEADLESS"` · `"GUI"` · `"SUPERVISED"` | `"HEADLESS"` | How the browser renders. `SUPERVISED` needs a configured supervisor (Xvfb on Linux). |
| `headless` | `true` / `false` | Implied by `displayMode` | Short form — `headless: false` ≈ `displayMode: "GUI"`. Don't combine with `displayMode`. |
| `profileMode` | `"DEFAULT"` · `"SEQUENTIAL"` · `"TEMPORARY"` · `"PROTOTYPE"` | Smart default | DEFAULT session → DEFAULT. Named session → SEQUENTIAL with dedicated contextDir. SWARM → SEQUENTIAL. |
| `sessionId` | any string | stable UUID | Names the session. Same name across runs reuses the same profile dir. `"DEFAULT"` resolves to the singleton default slot. |
| `maxBrowserContexts` | Int (e.g. `2`) | 2 | Number of parallel browser contexts the session may hold (each is a separate process). More contexts = more throughput, more memory. |
| `maxOpenTabs` | Int (e.g. `8`) | 8 | Max tabs per context. |
| `contextDir` | absolute path string | derived from sessionId | Force the profile directory. When `profileSession` or `profilePath` pins a swarm profile, `contextDir` is set internally and forces `maxBrowserContexts: 1`. |
| `interactLevel` | `"FASTEST"` · `"DEFAULT"` · `"BEST_DATA"` | `"DEFAULT"` | Delay preset for human-like interaction. Higher = more jitter, more pauses, slower. |
| `browser.launch.user.agent` | UA string | Chrome default (patched) | Explicit UA. A `Headless` token in it is *repaired*, not trusted. |
| `browser.launch.user.agent.stealth` | `"true"` / `"false"` | `"true"` | Disable the UA rewrite / stealth.js injection entirely. Automatic for `ego-lite` channel — no need to set it. |

### channel → Browser4 stealth behaviour matrix

| channel | Browser4 stealth.js injected? | UA rewritten? | Who owns fingerprint? |
|---|---|---|---|
| `chrome` (default) | ✅ Yes | ✅ Yes | Browser4 |
| `chrome-beta/dev/canary` | ✅ Yes | ✅ Yes | Browser4 |
| `ego-lite` | ❌ No | ❌ No | ego lite (Citro Labs) |

`ego-lite` also automatically sets `webrtc.ip_handling_policy = disable_non_proxied_udp` in the profile's Preferences to prevent WebRTC IP leaks. Nothing to configure — it just works.

---

## 3. session.attach — Transport Choices

`session.attach` takes one of **three mutually exclusive** transport flags plus the matching credentials:

| Transport | Arguments | Resolution order |
|---|---|---|
| **Browser4 Extension** | `extension: true`, optional `channel: "chrome"\|"msedge"\|"chrome-canary"` | 1. Your everyday browser — the same profile you use manually |
| **CDP by endpoint** | `cdpEndpoint: "http://localhost:9222"` | Direct URL to `/json/version`; must return 200 and list page targets |
| **CDP by port** | `cdpPort: 9222`, optional `channel: "chrome"` | Same endpoint resolution the CLI uses — `DevToolsActivePort`, process scan, port sweep |
| **CDP WebSocket** | `cdpEndpoint: "ws://host:port/devtools/browser/<uuid>"` | Chrome's built-in remote debugging — HTTP `/json*` returns 404; socket must answer `Browser.getVersion` + `Target.getTargets` |

### attach hard rules (the backend enforces them — not just suggestions)

| Rule | What happens if you break it |
|---|---|
| **One transport per call** — `extension` + `cdpEndpoint` + `cdpPort` are mutually exclusive | `require(args["cdpEndpoint"] == null && args["cdpPort"] == null)` → `IllegalArgumentException` |
| **Channel family must match** — `channel: "msedge"` must resolve to an Edge product | `requireAttachChannelMatches()` → `AttachFailureException(ENDPOINT_WRONG_BROWSER)` — no session, no driver, nothing bound |
| **Endpoint must list page targets** | `ENDPOINT_NO_PAGE_TARGETS` — attach refuses |
| **attach session never launches its own browser** | Backend adds `ATTACHED_CAPABILITY: "true"` to session config; every launch attempt is a no-op |
| **Stale attach sessions stay stale** — they are never silently replaced with a fresh Chrome | Tool calls return `SESSION_UNHEALTHY` with a clear message; re-attach explicitly |

### attach response fields (the agent should read these back)

Every attach returns a structured payload so the agent can confirm it drove the browser it meant to:

```json
{
  "sessionId": "uuid-or-name",
  "browser": "Google Chrome",
  "browserFamily": "chrome",
  "browserVersion": "138.0.0.0",
  "endpoint": "http://127.0.0.1:9222",
  "port": 9222,
  "pageTargets": 3,
  "pageTargetUrls": ["https://example.com", "about:blank"],
  "ownedBySessionId": null,
  "reboundFromPort": null
}
```

Key things to check:
- `browserFamily` matches your requested `channel` — if you asked for `"msedge"` and got `"chrome"`, something went wrong.
- `ownedBySessionId` non-null means **another live session already drives this browser**. That's a supported workflow, but the agent should know it's sharing.
- `reboundFromPort` non-null means this session was re-attached to a different endpoint than its first bind — another session was displaced.

---

## 4. Bot-Detection Escalation — Runtime Decision Loop

External agents have an advantage here that Browser4's built-in agent doesn't: **they can close and re-open sessions with different capabilities at runtime**. The canonical escalation:

```
Round 1: HEADLESS blocked (challenge page, CAPTCHA, empty body, 403/429)
│
├─ Same sessionId, close + open with displayMode: "GUI"
│   session.close(sessionId)
│   session.open(
│     sessionId: "task-xyz",           ← same id = same profile dir = cookies preserved
│     capabilities: {
│       displayMode: "GUI",            ← new mode
│       profileMode: "DEFAULT"          ← profile survives the close
│     }
│   )
│   → retry the failing operation ONCE with the headed browser
│
├─ GUI also blocked (site checks IP / fingerprint)
│   → close + open on ego-lite
│   session.close(sessionId)
│   session.open(
│     capabilities: {
│       channel: "ego-lite",
│       profileMode: "TEMPORARY",       ← fresh clean profile
│       displayMode: "HEADLESS"         ← ego-lite's fingerprint owns anti-detect
│     }
│   )
│
└─ Still blocked / requires real login
    → session.attach to the user's real browser
    session.attach(extension: true, channel: "chrome")
```

### Why same sessionId matters

A session named `"task-xyz"` resolves to a stable UUID, which maps to a dedicated Chrome user-data directory (`…/context/groups/named/PULSAR_CHROME/cx.<uuid>/`). Close + open with the **same sessionId** gives you the **same cookies, localStorage, and history** — only the display mode changes.

### Why profileMode must survive the escalation

If Round 1 used `profileMode: "DEFAULT"` and Round 2 drops profileMode entirely (defaulting to SEQUENTIAL for a named session), the new browser has a different profile dir — all cookies are gone, and the retry is pointless. Always carry `profileMode` across escalations:

```json
// ❌ WRONG — profile mode changes implicitly
session.close("task-xyz")
session.open(sessionId: "task-xyz", capabilities: {displayMode: "GUI"})

// ✅ CORRECT — profile mode survives
session.close("task-xyz")
session.open(sessionId: "task-xyz", capabilities: {
  displayMode: "GUI",
  profileMode: "DEFAULT"
})
```

---

## 5. Ready-to-Copy Examples by Task Type

### Example A — Agent research / one-off data collection

```json
{
  "tool": "session.open",
  "arguments": {
    "url": "https://example.com/research",
    "capabilities": {
      "displayMode": "HEADLESS",
      "profileMode": "TEMPORARY"
    }
  }
}
```

### Example B — Batch crawling / swarm / high throughput

```json
{
  "tool": "session.open",
  "arguments": {
    "capabilities": {
      "displayMode": "HEADLESS",
      "profileMode": "SEQUENTIAL",
      "maxBrowserContexts": 2,
      "maxOpenTabs": 8,
      "interactLevel": "FASTEST"
    }
  }
}
```

Remember to pair SEQUENTIAL with proxy rotation if IP isolation matters — set `proxy.rotation.url` in the backend's `application.properties` (or pass it as `-Dbrowser.proxy.rotation.url=...` when starting the server).

### Example C — Long-running agent conversation (multi-turn, must remember state)

```json
{
  "tool": "session.open",
  "arguments": {
    "sessionId": "finance-research-agent",
    "url": "https://example.com",
    "capabilities": {
      "displayMode": "HEADLESS",
      "profileMode": "DEFAULT"
    }
  }
}
```

### Example D — Anti-detection (fingerprint browser)

```json
{
  "tool": "session.open",
  "arguments": {
    "capabilities": {
      "channel": "ego-lite",
      "displayMode": "HEADLESS",
      "profileMode": "TEMPORARY"
    }
  }
}
```

Browser4 stealth layer is **automatically skipped** for `ego-lite` — no `stealth.js` injection, no UA rewrite. Nothing else to configure.

### Example E — Headless was blocked, retry with GUI (profile state preserved)

```json
// Step 1: close the headless session
{"tool": "session.close", "arguments": {"sessionId": "finance-research-agent"}}

// Step 2: re-open with GUI, same sessionId = same profile
{
  "tool": "session.open",
  "arguments": {
    "sessionId": "finance-research-agent",
    "capabilities": {
      "displayMode": "GUI",
      "profileMode": "DEFAULT"
    }
  }
}

// Step 3: retry the failing operation ONCE (then escalate further if still blocked)
```

### Example F — Attach to user's everyday Chrome (reuse their login state)

```json
// The user must have the Browser4 Extension installed and enabled in their Chrome.
{"tool": "session.attach", "arguments": {"extension": true, "channel": "chrome"}}

// Alternative — Chrome started with a debug port:
{"tool": "session.attach", "arguments": {"cdpPort": 9222, "channel": "chrome"}}

// Alternative — explicit CDP endpoint URL:
{"tool": "session.attach", "arguments": {"cdpEndpoint": "http://localhost:9222"}}

// Alternative — Chrome's built-in remote debugging (chrome://inspect) over browser-level WebSocket:
{"tool": "session.attach", "arguments": {"cdpEndpoint": "ws://127.0.0.1:9222/devtools/browser/<uuid>"}}
```

### Example G — Attach to a cloud browser / Electron app

```json
// Cloud browser service (e.g. Browserless, BrowserUse cloud) exposes a CDP endpoint.
{"tool": "session.attach", "arguments": {"cdpEndpoint": "ws://cloud-provider.com:9222/devtools/browser/abc"}}

// Electron app started with --remote-debugging-port:
{"tool": "session.attach", "arguments": {"cdpPort": 9222}}
```

---

## 6. Performance & Observability

| Signal | What to read | When to check |
|---|---|---|
| `session.attach` response `browserFamily` | Confirms the attach bound the browser family you asked for | Immediately after attach |
| `session.attach` response `ownedBySessionId` | Non-null = a sibling session shares this browser | Debug why two tasks see each other's tabs |
| `browser_window_state` tool | `VISIBLE` / `HIDDEN` / `UNKNOWN` with a `verified` flag | After `displayMode: "GUI"` open — confirm the window actually appeared |
| `list_sessions` response | Per-session `kind` (BROWSER4_LAUNCHED / CDP_ATTACHED / EXTENSION_ATTACHED), `display`, `healthy`, `ownsBrowser` | Any time before a tool call — confirm the session is still alive |
| Bot-detection signals inside the page | Challenge page title, `captcha` / `robot` keywords, empty body, 4xx/5xx status | After `goto` + `snapshot` — trigger escalation |
| Session close `browserTerminated` | `true` / `false` / absent | After `session.close` on a managed session — confirm the process is gone |

---

## 7. Fail Fast — The Backend Rejects Bad Combinations

The backend surfaces every failure with a stable error code, so the agent can branch on the cause instead of parsing prose:

| Code | Scenario | What the agent should do |
|---|---|---|
| `ENDPOINT_WRONG_BROWSER` | `attach --cdp msedge` resolved to a Chrome endpoint | Read the `browserFamily` from the error hint; either attach the correct channel or pass an explicit `cdpEndpoint` URL |
| `ENDPOINT_NO_PAGE_TARGETS` | Browser is reachable but has zero page tabs | Tell the user to open a tab in that browser, then retry |
| `ENDPOINT_UNREACHABLE` | Nothing answers at the endpoint | Start the browser with `--remote-debugging-port=<port>`, or fix the host/port |
| `SESSION_UNHEALTHY` | attach session went stale — WebSocket dropped or browser closed | **Don't `open`** (that would silently start a fresh Chrome without login state); `session.attach` again |
| `requires either 'cdpEndpoint' or 'cdpPort' or 'extension'` | `attach` with none of them | Call `session.open` instead, or add the missing transport flag |
| `capabilities cannot be combined` | `extension: true` + `cdpPort: 9222` | Pick one transport — extension relay or direct CDP |

---

## 8. Reference Map

| Document | What it covers |
|---|---|
| [browser-modes.md](browser-modes.md) | The same three axes (session × display × source) but from the **CLI user** perspective — `open --headed`, the bot-detection escalation docs, managed channel details, ego-lite stealth behaviour |
| [attach.md](attach.md) | Full `attach` command reference — the client-side resolution chain, `DevToolsActivePort` resolution, channel verification logic, close/disconnect semantics, every error case |
| [decision-trees.md](decision-trees.md) | Data extraction decision trees — `snapshot` vs `htmlsnapshot`, `get` vs `query`, `crawl` vs `swarm` |
| [config.md](config.md) | Backend configuration reference — `browser.launch.user.agent`, proxy settings, display mode defaults |
| [attach.md §Session close semantics](attach.md#close-vs-disconnect) | Exactly what happens to the browser process and the session tab on close for each session kind (BROWSER4_LAUNCHED vs CDP_ATTACHED vs EXTENSION_ATTACHED) |
