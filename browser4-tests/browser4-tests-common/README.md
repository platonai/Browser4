# browser4-tests-common Mock Site Utilities

This module provides reusable test/demo infrastructure for Browser4 / Pulsar examples.

## MockSiteApplication
A lightweight Spring Boot application that serves static deterministic pages under:
```
/generated/tta/instructions/
```
Key demo page:
```
http://localhost:8080/generated/tta/instructions/instructions-demo.html
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

## MockSiteBoot (standalone main)
Command line launcher with a main() entry point.

Run from Browser4 root (`submodules/Browser4`).

Run via Maven (single-line):
```
mvnw.cmd -pl browser4-tests/browser4-tests-common -am spring-boot:run -Dspring-boot.run.mainClass=ai.platon.pulsar.test.server.MockSiteBoot
```
Custom port & wait seconds (`cmd.exe`):
```
mvnw.cmd -pl browser4-tests/browser4-tests-common -am spring-boot:run ^
  -Dspring-boot.run.mainClass=ai.platon.pulsar.test.server.MockSiteBoot ^
  -Dmock.site.port=9090 ^
  -Dmock.site.waitSec=10
```
Custom port & wait seconds (PowerShell):
```powershell
.\mvnw.cmd -pl browser4-tests/browser4-tests-common -am spring-boot:run `
  -D"spring-boot.run.mainClass=ai.platon.pulsar.test.server.MockSiteBoot" `
  -D"mock.site.port=9090" `
  -D"mock.site.waitSec=10"
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

## Integration Notes
- Include this module as a dependency to access `MockSiteLauncher`.
- Static resources are under `src/main/resources/static` so they are served by Spring Boot out-of-the-box.

## Troubleshooting
| Symptom | Cause | Fix |
|---------|-------|-----|
| Port already in use | Another service uses 8080 | Start with `-Dmock.site.port=0` or choose a free port |
| Auto-start fails in example | Spring context exception | Check logs; ensure dependency `browser4-tests-common` is on classpath |
| Demo page 404 | Wrong URL or port | Print `MockSiteLauncher.baseUrl()` and rebuild URL |
| Health probe fails | Actuator not enabled | Use `-Dmock.site.healthPath=/` as a fallback |
| Probe always times out | Wrong host/port | Verify URL host:port, increase timeout |

## Next Ideas
- Add JSON API endpoints for richer agent tasks
- Provide recorded scenario scripts
- Additional synthetic latency/error toggles via query params

---
This README is intentionally concise; extend as the mock site grows.
