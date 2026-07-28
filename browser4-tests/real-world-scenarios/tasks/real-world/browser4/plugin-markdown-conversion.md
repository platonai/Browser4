# plugin-markdown-conversion

Test the **browser4-markdown** plugin by converting real web pages to Markdown, discovering links, and crawling a small site.

1. Navigate to `https://en.wikipedia.org/wiki/Web_scraping`. This is a well-structured Wikipedia article with headings, paragraphs, tables, lists, links, and images — ideal for Markdown conversion testing.

2. Use the `markdown.convert` tool to convert the current page to a Markdown file. Verify the result:
   - `filePath` points to an existing `.md` file
   - `charCount` > 1000 (meaningful content was extracted)
   - `linkCount` and `imageCount` are non-zero
   - The file contains the page title as an H1 heading

3. Read the first 50 lines of the generated Markdown file and confirm it includes:
   - A title heading
   - Well-structured paragraphs
   - Links preserved as `[text](url)` syntax
   - At least one list or table

4. Use the `markdown.discoverLinks` tool to scan the current page for all links. Report:
   - Total links on the page
   - Number of internal (Wikipedia) vs external links
   - At least 3 examples of each type

5. Navigate to `https://en.wikipedia.org/wiki/Data_scraping` (a shorter related article). Use `markdown.convert` to convert this page too. Verify it produces a different, valid Markdown file.

6. Use the `markdown.fetch` tool to fetch `https://httpbin.org/html` via direct HTTP (no browser) and convert to Markdown. This page has simple HTML — verify the conversion succeeds and captures the content without JavaScript.

7. Use the `markdown.crawl` tool to crawl starting from `https://httpbin.org/` with `maxDepth: 1` and `maxPages: 3`. Verify the crawl summary shows at least 1 page crawled successfully.
