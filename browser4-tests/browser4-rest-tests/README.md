# Browser4 REST Tests

E2E and integration test suites for the Browser4 REST API, plus a standalone mock-site
launcher for test/demo infrastructure.

## MockSiteBoot (standalone main)

Command-line launcher with a `main()` entrypoint that starts the mock site defined in
`browser4-tests-common` (see `MockSiteApplication` / `MockSiteLauncher` / `MockSiteStarter`).
It serves deterministic HTML pages for browser-agent testing.

Demo pages served from the mock site at `http://localhost:18080/`:

| Path | Content |
|------|---------|
| `/` | Welcome page (JSON) |
| `/hello` | Hello, World! |
| `/text` | Plain text response |
| `/csv` | CSV table |
| `/json` | JSON message |
| `/robots.txt` | Sample robots.txt |
| `/amazon/home.htm` | Amazon-style home page |
| `/amazon/product.htm` | Amazon-style product detail |
| `/assets/test-pages/form-page.html` | Form with inputs, buttons, links |
| `/assets/test-pages/error-page.html` | Empty/hidden/delayed-content divs |
| `/assets/test-pages/keyboard-test.html` | Keyboard and focus event harness |

### Running

From the repo root:

```shell
./mvnw -pl browser4-tests/browser4-rest-tests -am -DskipTests spring-boot:run
```

Or via the shared test helper:

```shell
./bin/test.sh mock-site -Dmock.site.port=18080
```

### Configuration

System properties / environment variables:

| Property | Env | Default | Description |
|----------|-----|---------|-------------|
| `mock.site.port` | `MOCK_SITE_PORT` | `18080` | Desired port (`0` = random) |
| `mock.site.waitSec` | `MOCK_SITE_WAIT_SEC` | `6` | Readiness wait seconds |
| `mock.site.healthPath` | — | `/actuator/health` | Health-check path |

Pass `--block` (program args) to keep the JVM alive after startup.

### Readiness

- Tries the health endpoint first (default `/actuator/health`, overridable via
  `mock.site.healthPath`).
- Falls back to `/` if the health path fails (unless disabled).
- Configurable timeout, interval, and connect/read timeouts.
- Returns `true` on the first 2xx/3xx response.

## Test suites (`src/test/kotlin/`)

E2E tests against the REST API controllers (`ai.platon.pulsar.rest.api.controller`):

| Class | Scope |
|-------|-------|
| `CommandControllerE2ETest` | Agent command execution via REST |
| `CommandControllerSSETest` | Server-Sent Events streaming |
| `DomSnapshotScenariosE2ETest` | DOM snapshot across 10 scenarios |
| `MCPToolControllerE2ETest` | MCP tool endpoints |
| `SwarmControllerE2ETest` | Swarm (multi-agent) endpoints |
| `MassiveScrapeTaskTest` | Large-scale scraping tasks |

Service-level integration tests (`ai.platon.pulsar.rest.api.service`):

| Class | Scope |
|-------|-------|
| `ScrapeServiceTests` | Scrape service logic |
| `ExtractServiceTest` | Extract service logic |
| `ConversationServiceTest` | Conversation management |
| `CommandRunnerTest` | Command runner lifecycle |
| `CommandStatusConversionTest` | Status serialization |

Base classes shared by E2E tests:

- `IntegrationTestBase` — Spring Boot test harness with `RestTestClient`, port injection,
  `Browser4AutoConfiguration`.
- `RestAPITestBase` — extends `IntegrationTestBase` with mock EC server config and SQL
  template fixtures for product-list / product-detail scrape tests.

## Dependencies

- **browser4-rest** — the REST API module under test
- **browser4-tests-common** — `MockSiteApplication`, `MockSiteLauncher`, `MockSiteStarter`,
  and demo page controllers
- **Spring Boot Test** — `@SpringBootTest`, `RestTestClient`

## Logs (`logs/`)

Test and mock-site output is written to the `logs/` directory (logback config in
`src/test/resources/logback-test.xml`):

| File / dir | Contents |
|------------|----------|
| `pulsar.log` | Main application log (time-based rotation: `pulsar.log..YYYY-MM-DD`) |
| `pulsar.m.log` | Metrics output |
| `pulsar.dc.log` | Data-collector output |
| `pulsar.sql.log` | SQL query log |
| `agent/` | Per-session agent logs, organised as `YYYYMMDD.HHmmss/<session-uuid>/` |

---
This README is intentionally concise; extend as the module grows.
