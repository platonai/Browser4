# MockSite — Test Fixture Server

Browser4 includes a lightweight **MockSite** server that serves static HTML pages for testing and demos — search boxes, forms, link lists, interactive pages, and more. When you see references to `http://localhost:18080/...` in task instructions, test scripts, or examples, they expect MockSite to be running.

MockSite is a Spring Boot application (`MockSiteApplication`) that serves static deterministic pages from `browser4-tests/pulsar-tests-common/src/main/resources/static/`. Pages emulate: search box, link list, infinite scroll, comment threads, and predictable anchors for agent action instructions.

## Starting MockSite

From the repository root, start MockSite with its default port (18080):

**Windows (PowerShell):**
```powershell
./bin/test.ps1 mock-site -Dmock.site.port=18080
```

**Linux / macOS (bash):**
```bash
./bin/test.sh mock-site -Dmock.site.port=18080
```

Or run directly via Maven:

```shell
cd browser4-tests/browser4-rest-tests
./../../mvnw package -DskipTests -am spring-boot:run -D"spring-boot.run.mainClass=ai.platon.pulsar.test.server.MockSiteBoot"
```

## Key Demo Pages

| Page | URL |
|------|-----|
| Interactive fixture | `http://localhost:18080/generated/interactive-1.html` |
| Form filling fixture | `http://localhost:18080/generated/form-filling.html` |
| Other fixture | `http://localhost:18080/generated/other-1.html` |

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `MOCK_SITE_PORT` | `18080` | Port the mock server listens on |
| `MOCK_SITE_WAIT_SEC` | — | Seconds to wait for server readiness |

The launcher tries the health endpoint first (default `/actuator/health`, overridable via `mock.site.healthPath` JVM property) and falls back to `/` if the health path fails. It returns `true` on the first 2xx/3xx response.

## Alternative: Serve Fixture Files with Python

If you only need the static HTML fixtures without the full MockSite, serve the fixture directory directly:

```bash
cd browser4-tests/pulsar-tests-common/src/main/resources/static
python3 -m http.server 18080
```

The fixture HTML files (e.g., `b4/mcp-tool-controller-form-fixture.html`) will be available under `http://localhost:18080/b4/`.

## See Also

- [Test Taxonomy](TESTING.md) — test tagging, levels, costs, and execution policies
- [MockSite module README](../browser4-tests/browser4-rest-tests/README.md)
