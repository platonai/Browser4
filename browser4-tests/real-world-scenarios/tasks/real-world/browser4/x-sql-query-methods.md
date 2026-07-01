# x-sql-query-methods

1. Go to `http://books.toscrape.com/`.
2. Use domsnapshot inspect to analyze the DOM structure and discover CSS selectors for book items, titles, and prices.
3. Write an X-SQL query that extracts book titles and prices from the page, and run it inline with `--sql`.
4. Write the same X-SQL query to a file (name it `extract_books.sql`), then run it using `--sql @extract_books.sql`.
5. Pass the same query via stdin using `--sql-stdin` (pipe the file contents or echo the query into the command).
6. Base64-encode the query and pass it via `--sql-base64` to verify all four input methods work.
7. Run a query with `--result-only` to get just the data without metadata.
8. Clean up by deleting the temporary `extract_books.sql` file.
