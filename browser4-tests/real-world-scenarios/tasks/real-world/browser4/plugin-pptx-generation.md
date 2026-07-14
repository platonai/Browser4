# plugin-pptx-generation

Test the **browser4-pptx** plugin by generating PowerPoint presentations from real, well-structured web pages.

1. Navigate to `https://en.wikipedia.org/wiki/Solar_System`. This is a featured Wikipedia article with rich structure: headings at multiple levels (H1–H3), paragraphs, images, tables, lists, and blockquotes — perfect for PPTX generation testing.

2. Use the `pptx.generate` tool to extract structured content from the page and generate a PowerPoint file. Verify the result:
   - `filePath` points to an existing `.pptx` file
   - `slideCount` > 5 (a rich page should produce multiple slides)
   - `blockCount` > 20 (many content blocks extracted)
   - `imageCount` > 0 (images were embedded)
   - `durationMs` is reported

3. Verify the PPTX file:
   - The file exists at the reported path
   - The file size is > 10 KB (meaningful content, not an empty template)
   - The file is valid (not corrupt — try to inspect its structure)

4. Navigate to `https://en.wikipedia.org/wiki/Moon`. This is a shorter but still well-structured article. Use `pptx.generate` to create a second presentation. Compare results:
   - How many slides were generated vs the Solar System article
   - How does slide count correlate with page content complexity
   - Confirm a different output file was produced (not overwriting the first)

5. Navigate to `https://httpbin.org/html`. This is a very simple page with minimal structure (just a few paragraphs and headings). Use `pptx.generate` and report:
   - How many slides a minimal page produces
   - Whether the output is still a valid PPTX file

6. Summarize: how well does `pptx.generate` handle pages of varying complexity? What content types map best to slides?
