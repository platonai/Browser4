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

## MockSiteBoot (standalone main)
Moved to `browser4-rest-tests` to avoid pulling Spring Boot into `browser4-common-tests`. See `browser4-rest-tests/README.md` for details.

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
