# Session Instructions Demo Test Pages

This folder contains a self‑contained HTML test environment that mirrors all natural language actions used in `SessionInstructionsExample`.

## Main Entry Page
- `session-instructions-demo.html` — Core page with:
  - `#title` element (for initial extraction: `mapOf("title" to "#title")`).
  - Navigation bar with 4 links (A, B, C, D). The 3rd link (`Link C`) is the deterministic target for the instruction "click the 3rd link" (goes to `pageC.html`).
  - Search form (`<form id="searchForm">`, input `#searchBox`) to satisfy: "find the search box, type 'web scraping' and submit the form" and also "search for 'browser'".
  - Dynamic search results container (`#searchResults`) which injects `<li class="athing"><span class="title"><a ...>...</a></span></li>` items so extraction with the selector `.athing .title a` works both before and after search.
  - Initial article list (`#articleList`) with `.athing .title a` links, including:
    - "Show HN: Demo Project" (`page2.html`)
    - "Ask HN: Need help with scraping" (`page3.html`)
    - Additional generic entries.
  - Comment thread toggles via small "comments" links producing expandable sections (supports: "open the first comment thread").
  - Infinite scroll simulation: scrolling to `#infiniteMarker` auto‑loads more `.athing` entries (supports: "scroll to the bottom of the page and wait for new content to load").
  - Tall spacer (`.screen-tall`) to ensure scrolling and screenshot viability ("take a full-page screenshot").
  - Action log area for optional debugging (not required by the example but useful).

## Supporting Pages (Navigation Targets)
Each has an `#title` element to keep extraction logic consistent:
- `pageA.html`, `pageB.html`, `pageC.html` — Nav bar targets (C = 3rd link).
- `page2.html` — "Show HN" style page.
- `page3.html` — "Ask HN" style page.
- `page4.html`, `page5.html` — Additional article targets.
- `pageResult1.html`, `pageResult2.html`, `pageResult3.html` — Generic search result stub pages.

## Mapping of SessionInstructionsExample Steps
| Example Step | Provided Mechanism |
|--------------|--------------------|
| Open URL | Use `session-instructions-demo.html` instead of external site. |
| Parse opened page | `#title` + static DOM present. |
| Extract initial fields (title) | `#title` exists. |
| Action: search for 'browser' | Enter text in `#searchBox`, submit form; results appear in `#searchResults`. |
| Click the 3rd link | 3rd nav link points to `pageC.html` with its own `#title`. |
| Find search box, type 'web scraping' and submit | Same search form. |
| Re-attach current URL & parse | All pages have stable DOM + `#title`. |
| Click first link that contains 'Show HN' or 'Ask HN' | Article list contains both; first match is deterministic (Show HN). |
| Scroll to bottom & wait for new content | Infinite scroll loads batches of `.athing` items near bottom sentinel. |
| Open the first comment thread | First "comments" link toggles `#thread-c1`. |
| Navigate back / forward | Browser history created via normal `<a>` navigation. |
| Take a full-page screenshot | Tall page + dynamic areas. |
| Extract article titles and hrefs | `.athing .title a` present initially, after scroll, and in search results. |

## Suggested Local Usage
1. Ensure the project serves static resources from `src/main/resources/static` (Spring Boot & many frameworks do this automatically).
2. Start the application (Windows CMD):
   ```cmd
   mvnw.cmd spring-boot:run
   ```
   or build & run the module that exposes static content.
3. Visit (typical Spring Boot default):
   `http://localhost:8080/generated/tta/instructions/session-instructions-demo.html`
4. Update `SessionInstructionsExample` (if desired) to use the local URL instead of the external ProductHunt URL, e.g.:
   ```kotlin
   val url = "http://localhost:8080/generated/tta/instructions/session-instructions-demo.html"
   ```
5. Run the example class — the existing instruction set should now operate entirely offline.

## Notes
- Infinite scroll only loads two additional batches to keep DOM size bounded.
- Comment thread expansion toggles visibility of `#thread-c1` / `#thread-c2`; only the first is required by the example.
- All navigation targets return quickly with a `#title` so re-parsing and extraction stay consistent.
- Search results reuse `.athing .title a` structure to exercise extraction post‑interaction.

## Possible Extensions
- Add a screenshot placeholder element to verify capture success programmatically.
- Add data attributes (`data-testid`) for even more deterministic selection if needed.
- Add a form submission page if future instructions require posting data.

Enjoy offline deterministic testing for `SessionInstructionsExample`.

