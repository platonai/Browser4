# Bot stealth check

## Phase 1 — Discover bot detection services

1. Go to `https://www.google.com` and search for: `online bot detection test browser automation stealth check`.
2. Read through the search results to identify at least 5 distinct online services that test whether a browser is a bot or automated.
3. From the results, pick 5 reputable services. Good candidates include:
   - `https://bot.sannysoft.com/`
   - `https://browserscan.net/bot-detection`
   - `https://bot.incolumitas.com/`
   - `https://deviceandbrowserinfo.com/are_you_a_bot`
   - `https://pixelscan.net/`
   - `https://abrahamjuliot.github.io/creepjs/`
   - `https://bot-detector.rebrowser.net/`

   If any of these are unavailable, substitute others found in the search results.

## Phase 2 — Run stealth checks on each service

For each of the 5 selected services, do the following:

4. **Navigate** to the service URL.
5. **Wait** for the page to fully load and for any auto-detection to complete.
6. **Take a full-page snapshot** to capture all visible results, scores, and pass/fail indicators.
7. **Record the key findings:**
   - Overall verdict (pass / fail / suspicious / score out of 100)
   - Which specific checks passed or failed (e.g., WebDriver flag, headless detection, User-Agent consistency, canvas fingerprint, WebGL renderer, plugins check, `window.chrome` integrity)
   - Any recommendations or explanations the service provides

## Phase 3 — Report

8. Compile the results from all 5 services into a markdown report saved to `./target/bot-stealth-report.md`.
9. The report should include:
   - A summary table listing each service, its URL, and the overall verdict/score
   - Per-service details: which checks passed, which failed, and any notable warnings
   - A conclusion: does Browser4 pass as a human browser, and if not, what specific signals gave it away
