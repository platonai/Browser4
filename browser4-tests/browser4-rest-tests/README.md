# Browser4 REST Tests

This module provides REST API integration and E2E tests for Browser4 / Pulsar, plus a standalone launcher for the mock site defined in `browser4-tests-common`.

## MockSiteBoot (standalone main)

Command-line launcher with a `main()` entry point that starts the mock site served from `browser4-tests-common`. The mock site serves static deterministic pages under `browser4-tests-common`'s `src/main/resources/static/`, including the key demo page:

```
http://localhost:18080/generated/interactive-1.html
```

Pages emulate: search box, link list, infinite scroll, comment threads, and predictable anchors for agent action instructions.

Run from the Browser4 repository root.

### Via Maven:

```shell
cd browser4-tests/browser4-rest-tests
./../../mvnw package -DskipTests -am spring-boot:run -D"spring-boot.run.mainClass=ai.platon.pulsar.test.server.MockSiteBoot"
```

### Via shared helper:

```shell
./bin/test.sh mock-site -Dmock.site.port=18080
```

### Configuration

Environment variable alternatives:
- `MOCK_SITE_PORT`
- `MOCK_SITE_WAIT_SEC`

Pass `--block` (program args) to keep the process alive if needed.

### Key points
- Tries health endpoint first (default `/actuator/health` or overridden by `mock.site.healthPath` JVM property)
- Falls back to `/` if health path fails (unless disabled)
- Configurable timeout, interval, verbosity, connect/read timeouts
- Returns `true` on first 2xx/3xx response

## REST API Test Suite

Integration and E2E tests covering Browser4's REST API surface:

- **`rest/api/controller/`** — E2E tests for command controller (sync/async, SSE), HTML snapshot scenarios, MCP tools, massive scrape tasks, and swarm controller
- **`rest/api/service/`** — Unit/integration tests for command runner, command status conversion, conversation service, extract service, and scrape service
- **`rest/api/common/`** — Shared test base classes and prompt tests
- **`rest/api/config/`** — Mock server configuration for test environments
- **`rest/api/entities/`** — Serialization tests for command status models
- **`rest/mcp/`** — WebDriver serialization and concurrency tests
- **`rest/util/server/`** — Mock web site access utilities

## Directory Structure

```
browser4-rest-tests/
├── src/main/kotlin/.../test/server/   MockSiteBoot (standalone launcher)
├── src/test/kotlin/.../rest/          REST API integration & E2E tests
├── src/test/resources/                Test configs and test data
├── logs/                              Agent trace logs (not versioned)
│   └── agent/<date>/<uuid>/
│       ├── task-standalone/           Trace logs (agent-trace.jsonl, .log)
│       ├── act/                       Action request/response pairs
│       └── summary/                   Session summaries (act.jsonl)
└── target/                            Build output (not versioned)
```

---
This README is intentionally concise; extend as the test suite grows.
