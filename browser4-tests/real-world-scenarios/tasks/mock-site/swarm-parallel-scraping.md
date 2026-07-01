# swarm-parallel-scraping

Before running this scenario, ensure MockSite is running on localhost:18080 (`./bin/test.ps1 mock-site`).

1. Create a swarm session with these options:
   - `--display-mode HEADLESS` to run without a visible browser window
   - `--max-browser-contexts 2` to limit parallel browser instances
   - `--max-open-tabs 4` to limit tabs per context
2. Create a seed file with several MockSite product URLs. Use real product IDs from the Electronics category:
   - `http://localhost:18080/ec/dp/B0E000001` through `http://localhost:18080/ec/dp/B0E000006` (6 Electronics products)
   - Add 4 more from the Home category: `http://localhost:18080/ec/dp/B0H000001` through `http://localhost:18080/ec/dp/B0H000004`
3. Write an X-SQL extraction query to a file that extracts the product title, price, and image URL from each product page.
4. Submit the extraction jobs to the swarm using `swarm query` with `--sql @file`, `--seed-file`, and `--refresh`.
5. Also use `swarm submit` to enqueue a plain scrape job (without X-SQL) for a single URL to compare the two submission methods.
6. Poll the swarm status for each job until they complete.
7. Retrieve and review the results from each completed job.
8. List all swarm tasks to see the task history.
9. Close the swarm session to release resources.
