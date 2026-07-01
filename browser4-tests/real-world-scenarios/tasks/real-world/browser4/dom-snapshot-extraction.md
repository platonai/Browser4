# dom-snapshot-extraction

1. Go to `http://books.toscrape.com/`.
2. Capture a fresh DOM snapshot of the page.
3. Use domsnapshot get to extract the text of the first book title on the page.
4. Use domsnapshot get to extract the HTML of the first product container.
5. Use domsnapshot get to extract the `href` attribute of the first book link.
6. Use domsnapshot get all to extract the text of every book title on the page.
7. Use domsnapshot get all with `--offset` and `--limit` to paginate through the results — extract titles 6 through 10.
8. Export the captured DOM snapshot to an HTML file for offline analysis.
9. Generate a summary of the page to get a compressed overview of its structure.
10. Use domsnapshot grep to search the page HTML for the word "price" and count the occurrences.
