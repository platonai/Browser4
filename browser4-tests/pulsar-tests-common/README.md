# browser4-tests-common Mock Site Utilities

This module provides reusable test/demo infrastructure for Browser4 / Pulsar examples.

## MockSiteApplication
A lightweight Spring Boot application that serves static deterministic pages under:
```
/src/main/resources/static/
```
Key demo page:
```
http://localhost:18080/generated/interactive-1.html
```
Pages emulate: search box, link list, infinite scroll, comment threads, and predictable anchors for agent action instructions.

## MockSiteLauncher (programmatic API)
Utility singleton to start/stop the mock site inside tests or example code.

### Features
- Idempotent start (safe to call multiple times)
- Optional port override (use 0 for a random free port)
- Exposes `port()` and `baseUrl()`
- Readiness probe with HTTP polling (first `/actuator/health`, fallback `/`)
- Simple restart API

### Typical usage (Kotlin)
```kotlin
val ctx = MockSiteLauncher.start(port = 8080)
val ready = MockSiteLauncher.awaitReady() // probes /actuator/health then /
println("Mock site at: ${MockSiteLauncher.baseUrl()}")
// ... run actions ...
MockSiteLauncher.stop()
```
Override health path with JVM property: `-Dmock.site.healthPath=/custom/health`.

### Auto-start in examples
`SessionInstructionsExample` (in `browser4-examples`) auto-starts the mock site if unreachable and `-Ddemo.autoStart=true` (default true). It also probes `/actuator/health` before falling back.

System properties:
- `demo.url`        : Override full demo page URL (default points to localhost:8080 demo page)
- `demo.autoStart`  : Auto-start when unreachable (true/false, default true)
- `mock.site.healthPath` : Custom health probe path for launcher (default `/actuator/health`)

## MockSiteStarter (availability waiter)
Reusable utility (`MockSiteStarter`) extracted from `SessionInstructionsExample` to wait for a mock/demo site to become available. Features:
- Tries a health endpoint first (default `/actuator/health`) then falls back to root `/`
- Configurable timeouts, intervals, connect/read timeouts
- Auto-starts `MockSiteApplication` if the site is unreachable, trying multiple ports (configured → 8182 → 8080 → 0)
- Reports success once any probe returns 2xx/3xx

```kotlin
val starter = MockSiteStarter()
starter.start("http://localhost:8182/ec/b?node=1292115012")
// ... run test actions ...
starter.stop()
```

## E-commerce Mock Endpoints (`/ec/*`)

A set of fake e-commerce pages served under `/ec/` for testing product listing and detail scraping:

| Endpoint | Description |
|----------|-------------|
| `GET /ec/` | E-commerce home page |
| `GET /ec/b?node=<id>` | Category listing page (products by category) |
| `GET /ec/dp/<productId>` | Product detail page |
| `GET /ec/static/**` | Static assets (images, etc.) |

Key classes under `ai.platon.pulsar.test.server.ec`:
- `EcommerceController` — primary `/ec/*` request handler
- `EcCategoryController` / `ListPageRenderer` — alternative category rendering
- `CatalogService` / `CatalogLoader` — product & category data loading
- `CatalogModels` — data classes for categories, products
- `HtmlRenderer` — server-side HTML generation for e-commerce pages

## MockSiteBoot (standalone main)
Moved to `browser4-rest-tests` to avoid pulling Spring Boot into `browser4-common-tests`. See `browser4-rest-tests/README.md` for details.

## TestUrls
Centralized test URL constants in `ai.platon.pulsar.test.TestUrls`:
- Real URLs: `PRODUCT_LIST_URL`, `PRODUCT_DETAIL_URL`, `NEWS_INDEX_URL`, `NEWS_DETAIL_URL`
- Mock EC server URLs: `MOCK_PRODUCT_LIST_URL`, `MOCK_PRODUCT_DETAIL_URL`
- Pre-configured `urlGroups` for baidu, jd, mogujie, vip, wikipedia

## E2E Agent Testing Framework (`src/main/resources/e2e/`)

A comprehensive testing infrastructure for AI agent (Browser/LLM) end-to-end testing, organized by concern:

```
e2e/
├── tasks/            # Goal-driven task definitions (login.task, purchase.task)
├── scenarios/        # Use-case scenarios
│   ├── happy_path/   # 14 English + 6 Chinese deterministic use cases
│   ├── adversarial/  # Prompt injection, tool hijacking, UI deception tests
│   └── chaos/        # Robustness tests (network delay, UI changes, errors)
├── constraints/      # Agent constraint rules
├── policies/         # Agent behavior policies
├── tools/            # Abstract tool definitions for testing
├── assertions/       # Multi-layer assertion configs (outcome/behavior/strategy)
├── traces/           # Agent reasoning trace storage
└── metrics/          # Agent KPIs (task success rate, hallucination rate, etc.)
```

### Design principles
- **Goal-driven, not step-driven** — agents reason and decide autonomously
- **Multi-layer assertions** — outcome, behavior, strategy, cognitive, and system-state
- **Three scenario types** — capability (can the agent do it?), robustness (what happens under stress?), adversarial (can it be tricked?)
- **Path budget control** — max steps, max tool calls, max page navigations per test

### Use case format
Each `.txt` file in `scenarios/happy_path/use-cases/` defines a task with:
- Comment metadata (level, type, description)
- Numbered natural-language steps for the agent

Example:
```
# Level: Simple
# Type: Single-site, deterministic
1. go to https://www.amazon.com/
2. search for "mechanical keyboard"
3. open the first 3 products
4. extract price, rating, and review count
5. write a comparison table to a markdown file
```

See `src/main/resources/e2e/README.md` for the full methodology, and `src/main/resources/e2e/scenarios/happy_path/use-cases/README.md` for test runner usage.

## Static Test Assets

A rich collection of browser testing fixtures under `src/main/resources/static/assets/`:

| Category | Contents |
|----------|----------|
| Accessibility | axe-core framework (v4.10+) with accessible-text and implicit-role plugins |
| Client certificates | Self-signed and trusted client/server certs (PEM, PFX) for TLS/mTLS testing |
| CSS/DOM | CSS transition/coverage pages, deep shadow DOM, form controls |
| Frames | IFrame and OOPIF test pages |
| Device APIs | Geolocation, device motion/orientation test pages |
| Navigation | BFCache, beforeunload, CSP, download-blob pages |
| Media | Example audio (MP3), digit images for image-based tests |
| JavaScript | ES6 modules, callback helpers, browser-executable fixtures |
| Drag & drop | Drag-and-drop test page |

All assets are served automatically by Spring Boot under `/assets/` when `MockSiteApplication` is running.

## Integration Notes
- Include this module as a dependency to access `MockSiteLauncher`, `MockSiteStarter`, and `TestUrls`.
- Static resources are under `src/main/resources/static` so they are served by Spring Boot out-of-the-box.
- EC mock endpoints render realistic HTML mimicking e-commerce product pages for agent scraping tests.
- See `src/main/kotlin/ai/platon/pulsar/test/server/ec/README.md` for the full e-commerce mock specification (routes, data schema, rendering rules, validation checklist).

## Troubleshooting
| Symptom | Cause | Fix |
|---------|-------|-----|
| Port already in use | Another service uses 8080 | Start with `-Dmock.site.port=0` or choose a free port |
| Auto-start fails in example | Spring context exception | Check logs; ensure dependency `browser4-tests-common` is on classpath |
| Demo page 404 | Wrong URL or port | Print `MockSiteLauncher.baseUrl()` and rebuild URL |
| Health probe fails | Actuator not enabled | Use `-Dmock.site.healthPath=/` as a fallback |
| Probe always times out | Wrong host/port | Verify URL host:port, increase timeout |

## Next Ideas
- JSON API endpoints for richer agent tasks (beyond browser UI operations)
- Synthetic latency/error toggles via query params for robustness testing
- Agent trace → automatic test case generation from recorded reasoning paths
- Self-evolving test system that adapts scenarios as the agent model changes
- World Model integration for predictive scenario validation

---
This README reflects the current state of the module; extend it as new test infrastructure is added.
