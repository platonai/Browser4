# Node Mock Site (pulsar-mock-site-node)

A Node.js (Express) implementation mirroring the Kotlin `MockSiteApplication` under `pulsar-tests-common`. It exposes the same test endpoints for integration / scraping tests without needing to spin up the Spring Boot context.

## Endpoints

| Path | Type | Description |
|------|------|-------------|
| `/` | text | Welcome banner |
| `/hello` | text | Hello world |
| `/text` | text/plain | Plain text sample |
| `/csv` | text/csv | Simple CSV data |
| `/json` | application/json | JSON response |
| `/robots.txt` | text/plain | Robots directives |
| `/amazon/home.htm` | text/html | Loads `home.htm` from resource search paths |
| `/amazon/product.htm` | text/html | Loads `B08PP5MSVB.original.htm` or fallback HTML if missing |

## Resource Resolution
The server attempts to load Amazon HTML resources from these candidate base paths (first match wins):
1. `pulsar-core/pulsar-resources/src/test/resources/pages/amazon`
2. `pulsar-tests-common/src/main/resources/pages/amazon`
3. `src/main/resources/pages/amazon`

If `B08PP5MSVB.original.htm` is absent, a deterministic fallback HTML page is returned so tests remain stable.

## Run
```bash
# From repo root
cd pulsar-tests-common/node-mock-site
npm install
npm start
# Server listens on http://localhost:39090 by default
```
Set a custom port:
```bash
PORT=40000 npm start
```

## Test
```bash
npm test
```

## Design Notes
- Pure ESM (`type: module`) for modern Node (>=18).
- Lightweight dependency footprint: Express + Morgan.
- Jest + Supertest for endpoint verification.
- No build step required.

## Future Enhancements (Optional)
- Add TypeScript and type definitions.
- Add eslint/prettier for linting.
- Provide a Maven wrapper script to invoke Node tests as part of CI.
- Support streaming / SSE test endpoints similar to existing Kotlin SSE examples.

## License
Follows the repository's overall license (see top-level LICENSE file).

