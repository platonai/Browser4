# Browser4 Test Resources

Static test fixtures served by Spring Boot's default static resource management for
Browser4 test suites.

- **`assets/`** — Copied from Playwright for test purposes (HTML pages, media files,
  HAR traces, service workers, and other fixtures). Key subdirectories include:
  `axe-core/` (accessibility testing), `client-certificates/` (TLS/SSL cert fixtures),
  `extension-with-logging/` (browser extension testing), `frames/` (iframe/nested
  frame fixtures), `input/` (form input fixtures), `modernizr/` (feature detection),
  `serviceworkers/` (service worker test pages), `wpt/` (Web Platform Test fixtures),
  and `webfont/` (web font loading tests).
- **`assets-p/`** — Created by the Browser4 team for test purposes (custom test pages).
- **`b4/`** — Browser4-specific test fixtures including MCP tool controller HTML pages
  for form and interactive testing (`mcp-tool-controller-form-fixture.html`,
  `mcp-tool-controller-interactive-fixture.html`, `mcp-tool-controller-other-fixture.html`).
- **`ec/`** — Electron/Chromium static assets (placeholder images and other embedded
  browser resources).
- **`generated/`** — Created by the Browser4 team, primarily AI-generated: interactive
  test pages (`interactive-1.html` through `interactive-dynamic.html`), form-filling
  fixtures (`form-filling.html`), SaaS mockups (`saas-home.html`), mock AI command
  pages (`mock-ai-command/`), mock e-commerce pages (`mock-amazon/`), and session
  instruction training areas (`tta/`). See [`generated/README.md`](generated/README.md)
  for details.

Version: 4.12.x
