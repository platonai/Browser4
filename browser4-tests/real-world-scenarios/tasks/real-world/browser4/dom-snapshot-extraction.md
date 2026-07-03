# html-snapshot-extraction

1. Go to `http://books.toscrape.com/`.
2. Capture a fresh HTML snapshot of the page.
3. Use htmlsnapshot get to extract the text of the first book title on the page.
4. Use htmlsnapshot get to extract the HTML of the first product container.
5. Use htmlsnapshot get to extract the `href` attribute of the first book link.
6. Use htmlsnapshot get all to extract the text of every book title on the page.
7. Use htmlsnapshot get all with `--offset` and `--limit` to paginate through the results — extract titles 6 through 10.
8. Export the captured HTML snapshot to an HTML file for offline analysis.
9. Generate a summary of the page to get a compressed overview of its structure.
10. Use htmlsnapshot grep to search the page HTML for the word "price" and count the occurrences.
