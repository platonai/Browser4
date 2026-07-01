# javascript-evaluation

Before running this scenario, ensure MockSite is running on localhost:18080 (`./bin/test.ps1 mock-site`).

1. Go to `http://localhost:18080/generated/interactive-1.html`.
2. Take an interactive snapshot to discover an element with a reference label.
3. Run a simple JavaScript expression to get the page title: `document.title`.
4. Run a JavaScript expression that returns a JSON object with page metadata: use `--json` to get `document.URL`, `document.title`, and the number of links on the page as structured JSON.
5. Write a small JavaScript snippet to a temporary file (e.g., `page_info.js`) that computes and logs the number of images, links, and forms on the page. Run it with `eval --file`.
6. Pipe a JavaScript expression via stdin using `eval --stdin` to get the text content of all heading elements.
7. Use `eval` with `--ref` (if supported) to execute JavaScript in the context of a specific element identified by its snapshot reference label.
8. Verify the results of each evaluation method produce correct and consistent output.
