# htmlsnapshot-inspect-discovery

1. Go to `http://books.toscrape.com/`.
2. Optionally capture an HTML snapshot (metadata/archive only — every read below serves the live DOM, so the capture is not a prerequisite).
3. Run htmlsnapshot inspect without a selector to answer "what repeats on this page?" (it auto-discovers repeating blocks; for a header/nav/main/aside/footer landmark outline, use htmlsnapshot summary).
4. Run htmlsnapshot inspect with a CSS selector targeting the product listing area, using `--max 5` to limit to 5 examples and `--depth 3` to deepen the descendant walk behind the "Suggested selectors" list (note: the printed sample tree always shows direct children only, regardless of `--depth`).
5. Generate a page summary to get a compressed overview (including page landmarks) alongside the inspection results.
6. Use the selectors discovered by inspect to run an htmlsnapshot get all query — extract all book titles using one of the suggested selectors. The trailing count line ("N elements matched.") / `match_count` JSON field is the authoritative element count.
7. Use htmlsnapshot grep with `--selector` only to search raw HTML text (Rust regex, not a CSS selector); its `-c` output counts matching LINES, not elements — do not use it to validate a selector's match count.
8. Based on the discovered structure, write and run an htmlsnapshot query using X-SQL to extract both book titles and prices.
9. Optionally, explore another section of the page (e.g., the sidebar category list) by running inspect with a different container selector.
