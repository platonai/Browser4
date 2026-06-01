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

## MockSiteBoot (standalone main)
Command line launcher with a main() entry point.

Run from Browser4 root (`submodules/Browser4`).

Run via Maven:

```shell
cd browser4-tests/browser4-rest-tests
./../../mvnw package -DskipTests -am spring-boot:run -D"spring-boot.run.mainClass=ai.platon.pulsar.test.server.MockSiteBoot"
```

Or from the repo root via the shared helper:

```shell
./bin/test.sh mock-site -Dmock.site.port=18080
```

Environment variable alternatives:
- `MOCK_SITE_PORT`
- `MOCK_SITE_WAIT_SEC`

Pass `--block` (program args) to keep the process alive if needed.

### Key points
- Tries health endpoint first (default `/actuator/health` or overridden by `mock.site.healthPath` JVM property)
- Falls back to `/` if health path fails (unless disabled)
- Configurable timeout, interval, verbosity, connect/read timeouts
- Returns `true` on first 2xx/3xx response

---
This README is intentionally concise; extend as the mock site grows.
