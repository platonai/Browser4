# Browser4 WebDriver-Compatible API (Reading `openapi.yaml` and Mapping to Implementation)

> Goal: make `openapi/openapi.md` a **maintainable index between spec and code**: easy to read, easy to locate things, and easy to keep updated.
>
> Sources of truth:
> - **Spec**: `openapi/openapi.yaml`
> - **Implementation**: `pulsar-rest/src/main/kotlin/ai/platon/pulsar/rest/openapi/controller/*`
>
> Scope: this document focuses on the WebDriver-Compatible API (rooted at `/session...`). Other REST surfaces in this repo (such as `/api/*`) are not covered in detail here (see Appendix).

---

## 1. OpenAPI Overview (Extracted from `openapi.yaml`)

- OpenAPI: `3.1.0`
- Title: **Browser4 WebDriver-Compatible API**
- Server (default dev address): `http://localhost:8182`
- Style: many responses use a WebDriver-compatible wrapper:
    - Success: `{"value": ...}` or `{"value": null}`
    - Failure: `ErrorResponse.value.error` / `ErrorResponse.value.message`

### 1.1 Tags (Capability Groups)

`openapi.yaml` groups endpoints into 9 capability tags:

- `session`: session lifecycle (create/get/delete)
- `navigation`: navigation and URL information (url/documentUri/baseUri)
- `selectors`: extension: selector-first interactions (exists/waitFor/click/fill/press/outerHtml/screenshot/element(s))
- `element`: standard WebDriver element-by-id (find element(s)/click/sendKeys/attribute/text)
- `script`: execute JavaScript (sync/async)
- `control`: delay/pause/stop
- `events`: event config, subscription, query
- `agent`: AI agent (run/observe/act/extract/summarize/clearHistory)
- `pulsar`: PulsarSession capabilities (normalize/open/load/submit)

---

## 2. Endpoint Overview (By Tag)

> Tip: this is a “skeleton index”. For detailed request/response schemas and status codes, refer to `openapi.yaml`.

### 2.1 session

| Method | Path | operationId |
|---|---|---|
| POST | `/session` | `createSession` |
| GET | `/session/{sessionId}` | `getSession` |
| DELETE | `/session/{sessionId}` | `deleteSession` |

### 2.2 navigation

| Method | Path | operationId |
|---|---|---|
| POST | `/session/{sessionId}/url` | `navigateTo` |
| GET | `/session/{sessionId}/url` | `getCurrentUrl` |
| GET | `/session/{sessionId}/documentUri` | `getDocumentUri` |
| GET | `/session/{sessionId}/baseUri` | `getBaseUri` |

### 2.3 selectors (selector-first extension)

| Method | Path | operationId |
|---|---|---|
| POST | `/session/{sessionId}/selectors/exists` | `selectorExists` |
| POST | `/session/{sessionId}/selectors/waitFor` | `waitForSelector` |
| POST | `/session/{sessionId}/selectors/element` | `findElementBySelector` |
| POST | `/session/{sessionId}/selectors/elements` | `findElementsBySelector` |
| POST | `/session/{sessionId}/selectors/click` | `clickBySelector` |
| POST | `/session/{sessionId}/selectors/fill` | `fillBySelector` |
| POST | `/session/{sessionId}/selectors/press` | `pressBySelector` |
| POST | `/session/{sessionId}/selectors/outerHtml` | `getOuterHtmlBySelector` |
| POST | `/session/{sessionId}/selectors/screenshot` | `screenshotBySelector` |

### 2.4 element (standard WebDriver element)

| Method | Path | operationId |
|---|---|---|
| POST | `/session/{sessionId}/element` | `findElement` |
| POST | `/session/{sessionId}/elements` | `findElements` |
| POST | `/session/{sessionId}/element/{elementId}/click` | `clickElement` |
| POST | `/session/{sessionId}/element/{elementId}/value` | `sendKeysToElement` |
| GET | `/session/{sessionId}/element/{elementId}/attribute/{name}` | `getElementAttribute` |
| GET | `/session/{sessionId}/element/{elementId}/text` | `getElementText` |

### 2.5 script

| Method | Path | operationId |
|---|---|---|
| POST | `/session/{sessionId}/execute/sync` | `executeSync` |
| POST | `/session/{sessionId}/execute/async` | `executeAsync` |

### 2.6 control

| Method | Path | operationId |
|---|---|---|
| POST | `/session/{sessionId}/control/delay` | `delay` |
| POST | `/session/{sessionId}/control/pause` | `pause` |
| POST | `/session/{sessionId}/control/stop` | `stop` |

### 2.7 events

| Method | Path | operationId |
|---|---|---|
| POST | `/session/{sessionId}/event-configs` | `createEventConfig` |
| GET | `/session/{sessionId}/event-configs` | `getEventConfigs` |
| GET | `/session/{sessionId}/events` | `getEvents` |
| POST | `/session/{sessionId}/events/subscribe` | `subscribeToEvents` |

### 2.8 agent

| Method | Path | operationId |
|---|---|---|
| POST | `/session/{sessionId}/agent/run` | `run` |
| POST | `/session/{sessionId}/agent/observe` | `observe` |
| POST | `/session/{sessionId}/agent/act` | `act` |
| POST | `/session/{sessionId}/agent/extract` | `extract` |
| POST | `/session/{sessionId}/agent/summarize` | `summarize` |
| POST | `/session/{sessionId}/agent/clearHistory` | `clearHistory` |

### 2.9 pulsar

| Method | Path | operationId |
|---|---|---|
| POST | `/session/{sessionId}/normalize` | `normalize` |
| POST | `/session/{sessionId}/open` | `open` |
| POST | `/session/{sessionId}/load` | `load` |
| POST | `/session/{sessionId}/submit` | `submit` |

---

## 3. Spec → Controller Mapping (Implementation Map)

The WebDriver-Compatible API implementation is mostly located under:

- `pulsar-rest/src/main/kotlin/ai/platon/pulsar/rest/openapi/controller/`

Controller files by tag:

| Tag | Controller |
|---|---|
| session | `SessionController.kt` |
| navigation | `NavigationController.kt` |
| selectors | `SelectorController.kt` |
| element | `ElementController.kt` |
| script | `ScriptController.kt` |
| control | `ControlController.kt` |
| events | `EventsController.kt` |
| agent | `AgentController.kt` |
| pulsar | `PulsarSessionController.kt` |

Key dependencies (used to differentiate real vs mock):

- entry for real sessions and real capabilities: `pulsar-rest/.../openapi/service/SessionManager.kt` (when present/injected, controllers enable the real branch)
- mock/demo storage: `pulsar-rest/.../openapi/store/InMemoryStore.kt`

---

## 4. Implementation Coverage Matrix (real / mock)

> Definitions:
> - **real**: the controller obtains a session through `SessionManager` and invokes real actions via `session.pulsarSession.*` or `session.pulsarSession.getOrCreateBoundDriver()` / `session.agent.*`.
> - **mock**: the controller only operates on sessions/elements/events stored in `InMemoryStore`, returning demo data.
>
> Note: the current implementation allows real and mock to coexist (the same endpoint can take different branches under different runtime configurations).

| Tag | Endpoint (representative) | Real | Mock | Notes |
|---|---|---:|---:|---|
| session | `/session` `/session/{id}` | ✅ | ✅ | `SessionController`: switches real/mock depending on whether `SessionManager` is injected |
| navigation | `/session/{id}/url` | ✅ | ✅ | real mode calls `pulsarSession.load(url)`, but `GET url/documentUri/baseUri` currently mostly returns the “stored url” |
| selectors | `/selectors/exists` `/waitFor` `/click` `/fill` `/press` `/outerHtml` `/screenshot` | ✅ | ✅ | real mode executes via bound driver; mock mode is mostly demo (e.g., exists always true, screenshot returns mock base64) |
| selectors | `/selectors/element(s)` | ❌ | ✅ | currently element(s) lookup is still based on generating elementId from the store (real mode is not aligned to “find via driver”) |
| element | `/element/{elementId}/*` | ✅ (partial) | ✅ | real mode maps elementId → selector (via store) and then operates via driver; elementId still originates from the store |
| script | `/execute/sync` `/execute/async` | ✅ | ✅ | real uses driver.evaluate; mock returns null |
| control | `/control/*` | ✅ (partial) | ✅ | `/control/delay` sleeps for requested duration (capped at 30s); `/control/pause` and `/control/stop` update session status in `SessionManager`. No direct driver-level pause/stop yet. |
| events | `/event-configs` `/events` `/events/subscribe` | ❌ | ✅ | mock only (in-memory event system), not a real browser event stream |
| agent | `/agent/*` | ✅ | ✅ | real calls `session.agent.*`; mock returns demo responses |
| pulsar | `/normalize` `/open` `/load` `/submit` | ✅ | ✅ | real calls `pulsarSession.*`; mock returns a demo WebPageResult |

---

## 5. Known Semantic Differences and Notes (spec vs implementation)

### 5.1 The meaning of “current URL” in navigation

- `POST /url` (real) triggers a load: `pulsarSession.load(request.url)`, and writes the url into `SessionManager`.
- `GET /url` / `GET /documentUri` / `GET /baseUri` (real) currently mostly uses the **url stored in SessionManager / the session object**.
    - This is not fully equivalent to the WebDriver semantics of “read the current address/document URI from the real browser”.

### 5.2 elementId semantics in selectors / element

- `elementId` currently behaves more like a “server-side session store handle”.
- In real mode, element click/fill/text/attribute operations map elementId back to a selector, then execute via the driver.
    - That means elementId lifetime/validity is controlled by the store, not a native browser reference.

### 5.3 control / events status

- `control` endpoints now have partial real implementation:
    - `/control/delay`: sleeps for the requested duration (capped at 30 seconds to prevent resource exhaustion).
    - `/control/pause` and `/control/stop`: update session status in `SessionManager`, but do not yet pause/stop driver-level operations.
- `events` currently have no real branch: they mainly serve as demos/placeholders (in-memory event system).
- To fully align with WebDriver / real browser event streams, driver-side capabilities and a clearer state machine/subscription model would be needed.

---

## 6. Maintenance Suggestions (Keeping spec and implementation in sync)

1. **Treat `openapi.yaml` as the single source of spec truth**: when adding/modifying endpoints, update the yaml first, then add the controller logic and update this mapping/matrix.
2. **Make demo-only endpoints explicit**: consider consistent markings in controllers or docs (e.g., `@Deprecated("demo-only")` or README labels) to avoid accidental misuse.
3. **Prioritize real implementation gaps (by value)**:
    - P0: align real find for `selectors/element(s)` (find via driver and return a stable elementId strategy)
    - P1: design real semantics for `events` (browser event stream integration, if publicly promised)
    - P1: further align navigation “current URL/documentUri” retrieval
    - P2: extend `control/pause` and `control/stop` to pause/stop driver-level operations
4. **Add minimal contract tests (MockMvc/WebTestClient)** covering at least:
    - `POST /session` → returns sessionId
    - 404 error body matches `ErrorResponse`
    - basic success path for `POST /session/{id}/load` or `POST /session/{id}/url`

---

## 7. Quick Verification (Windows / PowerShell / Maven Wrapper)

> Note: this repo is a multi-module Maven project. Use `mvnw.cmd` at the project root.

```powershell
# 1) Quick build (skip tests)
.\mvnw.cmd -q -DskipTests package

# 2) Run tests for the REST module only (will build dependent modules)
.\mvnw.cmd -pl pulsar-rest -am test -D"surefire.failIfNoSpecifiedTests=false"
```

---

## Appendix A: Other REST surfaces

There are also REST controllers outside `/session...` in this repo (for example, `/api/*` commands, chat, extraction, etc.). Whether those endpoints are included in OpenAPI (and whether they belong to the same contract surface as this doc) should be described in a separate document to avoid mixing them with the WebDriver-Compatible API.

---

## Additional Resources

- **[OpenAPI Specification](./openapi.yaml)** - Complete API specification
- **[CHANGELOG](./CHANGELOG.md)** - Version history and release notes
- **[Version Evolution Plan](../docs/api-version-evolution.md)** - Versioning strategy and policies
- **[REST API Examples](../docs/rest-api-examples.md)** - Practical usage examples

---

_Last updated: 2025-01-20 | Document version: 1.0.0_

