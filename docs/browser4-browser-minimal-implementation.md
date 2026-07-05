# Browser4-Browser: Minimal Implementation Scope

Analysis of which [SKILL.md](../skills/browser4-cli/SKILL.md) commands can be minimally implemented in
`browser4-core/browser4-browser` alone, without dependencies on `browser4-rest`, `browser4-agentic`,
`browser4-parse`, or the `cli/` Rust layer.

## Architecture Context

```
cli/ (Rust)  ──MCP/HTTP──▶  browser4-rest (server)  ──▶  browser4-agentic (AI)
                                                        │
                                                        ▼
                                              browser4-browser (CDP)  ──▶  Chrome
                                                        │
                                                        ▼
                                              browser4-parse (SQL engine)
```

- **`browser4-browser`** is a Kotlin/JVM library wrapping Chrome DevTools Protocol (CDP).
  Exposes: `BrowserProtocol` (300+ CDP methods), `SnapshotService` (DOM/AX/snapshot merge),
  `ScreenshotHandler`, `DOMHandler`, `ChromeLauncher`, `PulsarBrowser`, `HighlightManager`.

- **`browser4-rest`** adds the HTTP server, session management, and multi-browser orchestration.

- **`browser4-agentic`** adds AI/LLM-powered extraction, summarization, and agent loops.

- **`browser4-parse`** adds the X-SQL query engine (~200 DOM/STR/ARRAY functions).

- **`cli/`** (Rust) is the user-facing CLI that talks to the server over HTTP.

### What browser4-browser Provides

| Capability | Key API |
|---|---|
| Chrome launching | `ChromeLauncher.launch()` |
| CDP transport | `RemoteChromeProtocol` (Ktor WebSocket) |
| Navigation | `BrowserProtocol.navigate()`, `reload()`, `getNavigationHistory()` |
| JS evaluation | `BrowserProtocol.evaluate()`, `callFunctionOn()` |
| DOM/AX/Snapshot trees | `CDPSnapshotService.getDOMState()` / `getBrowserUseState()` |
| Element querying | `DOMHandler.queryLocator()` (CSS, XPath, backend node ID) |
| Mouse input | `BrowserProtocol.dispatchMouse*()` |
| Keyboard input | `BrowserProtocol.dispatchKeyEvent()`, `insertText()` |
| Screenshots | `ScreenshotHandler.screenshot()` |
| PDF export | `BrowserProtocol.printToPDF()` |
| Cookies | `BrowserProtocol.getCookies()`, `deleteCookies()`, `clearBrowserCookies()`, `setCookies()` |
| Tabs | `PulsarBrowser.listTabs()`, `createTab()`, `closeTab()` |
| Viewport resize | `BrowserProtocol.setDeviceMetricsOverride()` |
| File upload | `BrowserProtocol.setFileInputFiles()` |
| Dialogs | `BrowserProtocol.handleJavaScriptDialog()` |
| Console messages | `BrowserProtocol.onConsoleMessageAdded` |
| Network interception | `BrowserProtocol` Fetch domain methods |
| Highlights | `HighlightManager.addHighlights()` / `removeHighlights()` |
| Drag & drop | `BrowserProtocol.dispatchDragEvent()` |

### What browser4-browser Does NOT Provide

- Server/daemon lifecycle (port binding, health checks, multi-client)
- Session persistence across process restarts
- AI/LLM integration (no HTTP calls to OpenAI/Anthropic)
- SQL query parser and execution engine
- Multi-browser orchestration (swarm)
- Recursive crawling with queue management
- Task persistence, scheduling, or resume (loop)
- CLI argument parsing, install/upgrade toolchain

---

## Commands Implementable in browser4-browser Alone

### Navigation & Session

| CLI Command | browser4-browser API | Notes |
|---|---|---|
| `goto <url>` | `BrowserProtocol.navigate(url)` | Core single-page navigation |
| `open [--headed] [url]` | `ChromeLauncher.launch()` + `BrowserProtocol.navigate()` | Launch Chrome process + navigate |
| `go-back` | `NavigationHistory` → `navigateToHistoryEntry(currentIndex - 1)` | |
| `go-forward` | `NavigationHistory` → `navigateToHistoryEntry(currentIndex + 1)` | |
| `reload` | `BrowserProtocol.reload()` | |

### Keyboard

| CLI Command | browser4-browser API | Notes |
|---|---|---|
| `press <key> [ref]` | `BrowserProtocol.focus()` + `dispatchKeyEvent()` | Focus element first if ref given |
| `type <text> [ref]` | `BrowserProtocol.insertText()` or per-char `dispatchKeyEvent()` | |
| `fill <ref> <value>` | Click + select-all + `insertText()` | Clear then type |
| `keydown <key>` | `BrowserProtocol.dispatchKeyEvent(type=keyDown, ...)` | |
| `keyup <key>` | `BrowserProtocol.dispatchKeyEvent(type=keyUp, ...)` | |

### Mouse

| CLI Command | browser4-browser API | Notes |
|---|---|---|
| `click <ref>` | `scrollIntoViewIfNeeded()` + `dispatchMousePressed()` + `dispatchMouseReleased()` | Compute clickable point from box model |
| `dblclick <ref>` | Same as click with `clickCount=2` | |
| `hover <ref>` | `dispatchMouseMoved()` to element center | |
| `drag <from> <to>` | `dispatchDragEvent()` sequence | |
| `mousemove <x> <y>` | `BrowserProtocol.dispatchMouseMoved(x, y)` | Absolute coordinates |
| `mousedown [right]` | `BrowserProtocol.dispatchMousePressed()` | |
| `mouseup [right]` | `BrowserProtocol.dispatchMouseReleased()` | |
| `mousewheel <dx> <dy>` | `BrowserProtocol.dispatchMouseWheel()` | |
| `scroll <dir> <px>` | `BrowserProtocol.dispatchMouseWheel()` or JS `window.scrollBy()` | |

### Core Interaction

| CLI Command | browser4-browser API | Notes |
|---|---|---|
| `select <ref> <val>` | JS `evaluate()` to set `element.value` + dispatch `change` event | |
| `check <ref>` | JS `evaluate()` to set `element.checked = true` | |
| `uncheck <ref>` | JS `evaluate()` to set `element.checked = false` | |
| `wait <ms>` | Coroutine `delay(ms)` | |
| `wait <ref>` | Poll `DOMHandler.queryLocator()` until non-null | |
| `wait --text "..."` | Poll `BrowserProtocol.evaluate("document.body.innerText.includes(...)")` | |
| `wait --url "<glob>"` | Poll `BrowserProtocol.evaluate("window.location.href")` + glob match | |
| `wait --load <strategy>` | Poll JS `document.readyState` / network-idle expression | |
| `wait --fn "<js>"` | Poll `BrowserProtocol.evaluate(js)` until truthy | |
| `dialog-accept [prompt]` | `BrowserProtocol.handleJavaScriptDialog(accept=true, promptText)` | |
| `dialog-dismiss` | `BrowserProtocol.handleJavaScriptDialog(accept=false)` | |
| `resize <w> <h>` | `BrowserProtocol.setDeviceMetricsOverride()` | |
| `upload <ref> <file>` | `DOMHandler.queryLocator()` + `BrowserProtocol.setFileInputFiles()` | |

### Snapshots

| CLI Command | browser4-browser API | Notes |
|---|---|---|
| `snapshot` | `CDPSnapshotService.getBrowserUseState()` | Full merged DOM+AX+Snapshot tree |
| `snapshot -v 0` | Split `DOMState` tree by viewport boundaries | Post-processing on merged tree |
| `snapshot -i` | Filter `DOMState` to interactive-only nodes | `isInteractable` field |
| `snapshot -c/-no-compact` | Control `CompactOptions` in `DOMStateBuilder.build()` | |
| `snapshot -d <n>` | Pass `maxDepth` to `SnapshotOptions` | |
| `snapshot -s "<css>"` | Scope tree to CSS selector subtree | Pre-filter before merge |
| `snapshot -u` | Include `/url` properties from AX tree | Already in merged tree |
| `snapshot --boxes/--no-boxes` | Include/exclude `[box=...]` annotations | `absolutePosition` on nodes |
| `snapshot --auto-diff` | Diff two `DOMState` snapshots in memory | Requires storing previous snapshot |
| `snapshot --stdout/--raw` | Serialize `DOMState` to YAML string | `DOMSerializer` already exists |
| `snapshot grep <pattern>` | Regex search over serialized snapshot YAML | Trivial post-processing |

### Element Data Extraction

| CLI Command | browser4-browser API | Notes |
|---|---|---|
| `get text <ref>` | `DOMHandler.queryLocator()` → `BrowserProtocol.evaluate("el.textContent", ref)` | |
| `get html <ref>` | `BrowserProtocol.getOuterHTML(nodeId)` | |
| `get box <ref>` | `BrowserProtocol.getBoxModel(nodeId)` | |
| `get styles <ref>` | `BrowserProtocol.getComputedStyleForNode(nodeId)` | |
| `get property <ref> <name>` | `BrowserProtocol.evaluate("el[name]", ref)` via `callFunctionOn` | |
| `get attr <ref> <name>` | `BrowserProtocol.getAttributes(nodeId)` → filter by name | |
| `generate-locator <ref>` | `SnapshotService.findElement()` → `.cssSelector()` on merged node | |

### JavaScript Evaluation

| CLI Command | browser4-browser API | Notes |
|---|---|---|
| `eval "<js>" [ref]` | `BrowserProtocol.evaluate(expression)` or `callFunctionOn()` with element | |
| `eval --json "<js>"` | Same + JSON-serialize the result | |
| `eval --file script.js` | Read file contents → `evaluate()` | File I/O, not CDP |

### Screenshots & Export

| CLI Command | browser4-browser API | Notes |
|---|---|---|
| `screenshot [ref]` | `ScreenshotHandler.screenshot(selector)` or `screenshot(fullPage)` | |
| `screenshot --full-page` | `ScreenshotHandler.screenshot(fullPage=true)` | Uses `getLayoutMetrics` + `setDeviceMetricsOverride` |
| `pdf` | `BrowserProtocol.printToPDF()` | |

### Tabs

| CLI Command | browser4-browser API | Notes |
|---|---|---|
| `tab-list` | `PulsarBrowser.listTabs()` | |
| `tab-new [url]` | `PulsarBrowser.createTab(url)` | |
| `tab-close [index]` | `PulsarBrowser.listTabs()` → `closeTab(tabs[index])` | |
| `tab-select <index>` | `PulsarBrowser.listTabs()` → `bringToFront()` | |

### Storage

| CLI Command | browser4-browser API | Notes |
|---|---|---|
| `state-save [file]` | `BrowserProtocol.getCookies()` + JS `localStorage` → serialize to JSON | |
| `state-load <file>` | Parse JSON → `BrowserProtocol.setCookies()` + JS `localStorage.setItem()` | |
| `cookie-list` | `BrowserProtocol.getCookies()` | Filter by domain/path optional |
| `cookie-get <name>` | `BrowserProtocol.getCookies()` → find by name | |
| `cookie-set <n> <v>` | `BrowserProtocol.setCookies([cookie])` | |
| `cookie-delete <name>` | `BrowserProtocol.deleteCookies(name)` | |
| `cookie-clear` | `BrowserProtocol.clearBrowserCookies()` | |
| `localstorage-list` | `BrowserProtocol.evaluate("Object.keys(localStorage)")` | |
| `localstorage-get <k>` | `BrowserProtocol.evaluate("localStorage.getItem(k)")` | |
| `localstorage-set <k> <v>` | `BrowserProtocol.evaluate("localStorage.setItem(k, v)")` | |
| `localstorage-delete <k>` | `BrowserProtocol.evaluate("localStorage.removeItem(k)")` | |
| `localstorage-clear` | `BrowserProtocol.evaluate("localStorage.clear()")` | |
| `sessionstorage-*` | Same pattern with `sessionStorage` | |

### HTML Snapshot (Static)

| CLI Command | browser4-browser API | Notes |
|---|---|---|
| `htmlsnapshot` (capture) | `CDPSnapshotService.getDOMState()` + `DOMSerializer` | |
| `htmlsnapshot get <field> [sel]` | `DOMHandler.queryLocator(sel)` → extract field | Text, html, attr via CSS |
| `htmlsnapshot get all <field> [sel]` | `DOMHandler.queryLocatorAll(sel)` → extract from each | |
| `htmlsnapshot summary` | Summarize `DOMState` — element counts, roles, heading structure | |
| `htmlsnapshot export` | `BrowserProtocol.getOuterHTML(documentNodeId)` | |
| `htmlsnapshot grep <pattern>` | Regex search over exported HTML | Post-processing |
| `htmlsnapshot inspect [sel]` | `CDPSnapshotService` merged tree analysis | Structure introspection |

### Other

| CLI Command | browser4-browser API | Notes |
|---|---|---|
| `console [min-level]` | Accumulate `onConsoleMessageAdded` events, filter by level | Event listener pattern |
| `console --clear` | Clear accumulated buffer | |

---

## Commands NOT Implementable in browser4-browser Alone

### CLI Toolchain (cli/ only)

| CLI Command | Missing Capability |
|---|---|
| `install` | Downloads runtime bundle (JRE + jars + scripts) from CDN |
| `uninstall` | Removes npm/cargo packages + runtime data directories |
| `upgrade` | Version-check + download latest/ tagged release |
| `batch` | Parses multiple sub-commands, executes sequentially |
| `loop` | Task scheduler with persistence (`~/.browser4/loop-state.json`), resume, pause |

### Server/Session Layer (needs browser4-rest)

| CLI Command | Missing Capability |
|---|---|
| `close` | Session lifecycle managed by server; browser4-browser just closes CDP |
| `attach --cdp <...>` | Server-mediated CDP endpoint discovery + routing to existing Chrome |
| `list` | Multi-session state tracking across workspaces |
| `close-all` | Graceful shutdown of all sessions while keeping server alive |
| `kill-all` | Force-kill all browser processes + stop backend |
| `status` | Server version, port, health endpoint |
| `doctor` | Build info, log tailing, system metrics aggregation |

### AI/LLM Integration (needs browser4-agentic)

| CLI Command | Missing Capability |
|---|---|
| `extract "<prompt>"` | Sends page content to LLM, parses structured response |
| `summarize "<prompt>"` | Sends page content to LLM, returns summary |
| `agent-run <task>` | Autonomous agent loop: navigate → snapshot → act → repeat |
| `agent-status <id>` | Task tracking in agentic orchestration layer |
| `agent-result <id>` | Retrieve completed agent output |
| `agent-list` | List all tracked agent tasks |

### Multi-Browser Orchestration (needs browser4-rest + queue)

| CLI Command | Missing Capability |
|---|---|
| `swarm-create` | Pool of isolated browser contexts, configurable concurrency |
| `swarm-submit <url>` | Job queue with deadlines, cache expiry, seed files |
| `swarm-query <url> --sql` | Distributes X-SQL queries across swarm contexts |
| `swarm-status <id>` | Job status tracking |
| `swarm-result <id>` | Retrieve completed job results |
| `swarm-list` | List all tracked jobs |
| `crawl <url>` | Recursive link extraction, depth-limited traversal, queue management |

### SQL Engine (needs browser4-parse)

| CLI Command | Missing Capability |
|---|---|
| `htmlsnapshot query --sql <query>` | X-SQL parser + ~200 functions (`DOM_*`, `STR_*`, `ARRAY_*`) |
| `swarm-query --sql <query>` | Same SQL engine, distributed across swarm |

> **Note:** A minimal subset of X-SQL (basic `SELECT DOM_FIRST_TEXT(...) FROM DOM_LOAD_AND_SELECT(...)`)
> _could_ be implemented with `BrowserProtocol.evaluate()` + CSS selector iteration, but the full
> ~200-function SQL surface is firmly in `browser4-parse`.

---

## Summary

| Category | Implementable | Not Implementable |
|---|---|---|
| Navigation | 5/5 | — |
| Keyboard | 5/5 | — |
| Mouse | 9/9 | — |
| Core Interaction | 12/12 | — |
| Snapshots | 11/11 | — |
| Element Extraction | 8/8 | — |
| JS Evaluation | 3/3 | — |
| Screenshots & Export | 3/3 | — |
| Tabs | 4/4 | — |
| Storage | 14/14 | — |
| HTML Snapshot | 7/8 | X-SQL queries |
| Console | 2/2 | — |
| CLI Toolchain | — | install, uninstall, upgrade, batch, loop (5) |
| Server/Session | — | close, attach, list, close-all, kill-all, status, doctor (7) |
| AI/LLM | — | extract, summarize, agent-* (6) |
| Orchestration | — | swarm-*, crawl (8) |
| **Total** | **~83** | **~26** |

**Bottom line:** The vast majority of browser-interaction commands (~76%) map directly to
`BrowserProtocol`, `SnapshotService`, `ScreenshotHandler`, and `DOMHandler` APIs already in
`browser4-browser`. A minimal standalone implementation would need to add only: a thin CLI
argument parser, YAML serialization of `DOMState`, and an event loop for the `wait` command.
Everything else already exists in the module.
