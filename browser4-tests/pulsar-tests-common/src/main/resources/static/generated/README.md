# Generated Test Assets

The assets in this directory are created by the Browser4 team for test purposes. All files are AI-generated and serve as static test fixtures for browser automation, interactive testing, and visual regression scenarios.

## Directory Layout

### Interactive Test Pages
A collection of single-page HTML applications with progressively complex interactions, form controls, and dynamic behaviors — used for DOM event testing, RPA scripting, and visual regression validation.

| File | Purpose |
|------|---------|
| `interactive-1.html` | Basic input echo, background color select, calculator, show/hide toggle |
| `interactive-2.html` | Info collection, summary button, range slider font sizing, CSS hover cards |
| `interactive-3.html` | IntersectionObserver animations, volume range, toggle box with fade transitions |
| `interactive-4.html` | Dark mode toggle, HTML5 drag-and-drop sortable list |
| `interactive-screens.html` | Multi-section page with input echo, calculator, toggle, email validation, contact form |
| `interactive-dynamic.html` | Dynamically generated interactive content |

See [`INTERACTIVE-README.md`](INTERACTIVE-README.md) for detailed per-page event maps, DOM interaction points, and test assertion guidance.

### Supporting Files
| File | Purpose |
|------|---------|
| `interactive-elements-index.json` | Machine-readable index of interactive elements across all pages |
| `interactive-page.md` | Markdown documentation for interactive page structure |
| `form-filling.html` | Standalone form-filling test page |
| `injected-js.test.html` | JavaScript injection test harness |
| `document.json` | Sample JSON document fixture |
| `saas-home.html` / `saas-home.md` | Mock SaaS landing page for visual and content extraction tests |

### `mock-ai-command/`
Pages that simulate an AI command REST API client for testing polling, SSE streaming, and model selection workflows.

| File | Purpose |
|------|---------|
| `mock-polling.html` | Input → POST → UUID → poll loop demo |
| `sse.html` | Server-Sent Events streaming client mock |
| `model-selector.html` | AI model selection UI mock |

### `mock-amazon/`
Simulated e-commerce pages (product listing, detail, category) with lazy-loading and autocomplete behaviors, built for scroll-triggered content loading tests and navigation flow testing.

| Path | Purpose |
|------|---------|
| `ec-home.html` | E-commerce home page mock |
| `ec-category.html` | Category browsing page mock |
| `ec-product.html` | Product detail page with delayed price reveal |
| `list/` | Multi-file category listing app (HTML/CSS/JS) with autocomplete search |
| `product/` | Multi-file product detail app (HTML/CSS/JS) with lazy content loading |
| `data/products.json` | Sample product data fixture |
| `prompt.md` | The original prompt used to generate these mock pages |

### `tta/` (Test Training Area)
Self-contained HTML test environment that mirrors natural-language actions used in `SessionInstructionsExample`.

| Path | Purpose |
|------|---------|
| `act/act-demo.html` | Session instructions demo — navigation, search, infinite scroll, comment threads |
| `act/pageA.html`–`pageE.html` | Navigation target pages for link-clicking workflows |
| `act/page2.html`–`page5.html` | Article-style target pages |
| `act/pageResult1.html`–`pageResult3.html` | Search result stub pages |
| `act/README.md` | Full mapping of session instruction steps to page mechanisms |

## Usage

All pages are static HTML/CSS/JS with no external dependencies — they work over `file://` or served via any HTTP server. When the project is running (e.g., Spring Boot on port 8080), assets are available under:

```
http://localhost:8080/generated/<path>
```

For IntersectionObserver-dependent pages, prefer HTTP serving to avoid browser security restrictions.

## Version

Current for Browser4 4.12.x.
