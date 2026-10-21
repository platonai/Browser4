# Issues: Use Case 12: Company Due Diligence Automation

> **Source:** `20260710-140013-Use Case 12_ Company Due Diligence Automation.full.md` | **Date:** 20260710-140013 | **Mode:** dev

## Scenario Background

### Task

✅ **Task completed successfully.** The `stripe-due-diligence.md` report (286 lines) was produced with comprehensive sections including executive summary, company overview, product catalog, target customers, funding history, acquisitions, partnerships, major business events, risk assessment, maturity assessment, and investment thesis.

---

### Execution Context

**Key Commands:**

| # | Command | Purpose |
|---|---------|---------|
| 1 | `cargo run --manifest-path ... -- help` | Learn available commands |
| 2 | `goto "https://stripe.com/"` | Navigate to Stripe (redirected to zh-us) |
| 3 | `snapshot -v 0` | Capture page structure |
| 4 | `goto "https://stripe.com/en-us"` | 🔧 Override locale redirect |
| 5 | `snapshot -v 0 --stdout` | View full page elements |
| 6 | `htmlsnapshot` | Capture static HTML for extraction |
| 7 | `htmlsnapshot get text "h1"` | Extract page heading |
| 8 | `htmlsnapshot get all text "h2"` | Extract section headings |
| 9 | `eval "..." --json` | Extract product card data via JS |
| 10 | `goto "https://stripe.com/use-cases"` | ❌ 404 — page does not exist |
| 11 | `goto "https://stripe.com/"` | Return to homepage |
| 12 | `htmlsnapshot get all attr "a[href]" href` | 🔧 Discover correct use-case URLs from link map |
| 13 | `goto "https://stripe.com/use-cases/saas"` | Visit SaaS use-case page |
| 14 | `htmlsnapshot` + `get text "h1"` | Extract SaaS page content |
| 15 | `goto "https://stripe.com/use-cases/ai"` | Visit AI use-case page |
| 16 | `goto "https://www.crunchbase.com/organization/stripe"` | ❌ Cloudflare block |
| 17 | `goto "https://www.google.com/search?q=..."` | 🔧 Fallback to Google — ❌ CAPTCHA block |
| 18 | `goto "https://en.wikipedia.org/wiki/Stripe_(company)"` | 🔧 Wikipedia as funding data source |
| 19 | `htmlsnapshot` + `htmlsnapshot get all text "p"` | Extract Wikipedia paragraphs |
| 20 | `eval "..."` (x2) | Extract infobox and funding details |
| 21 | `goto "https://news.google.com/search?q=..."` | Search Google News |
| 22 | `snapshot -v 0 --stdout` | View article list |
| 23 | `eval "..." --json` (x2) | Extract article links and titles |
| 24 | `goto <article-url>` (x2) | Open articles (Forbes paywalled, MailGuard loaded) |

**Workarounds Applied During Task:**

1. **Locale override** — `stripe.com` auto-redirected to `zh-us`; had to navigate explicitly to `/en-us`.
2. **Link-map discovery** — `/use-cases` 404'd; extracted all `<a href>` attributes to find the actual use-case URLs.
3. **Wikipedia fallback** — Crunchbase and Google both blocked; used Wikipedia as the funding data source.
4. **Snapshot-based article extraction** — Google News article links had empty `.textContent` in eval; titles were extracted from snapshot AX-tree labels instead.
5. **Shell escaping** — every `eval` with JavaScript required manual quote escaping on Windows bash (`'\"'` chains).

---

---

## Issues Found (12 issues)

### Issue 1: `$cliInvocation`, `$helpCmd`, `$skillPath` are undefined template variables

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Read the evaluation task template. Same issue as the prior evaluation — these placeholder variables are not defined anywhere in the environment or repository configuration.

#### Expected Behavior

Variables should be defined in a setup script, `.env` file, or replaced with explicit literals in the evaluation template.

#### Actual Behavior

The evaluator must read `skills/browser4-cli/references/development.md` to discover `cargo run --manifest-path cli/browser4-cli/Cargo.toml --` as the invocation, and `skills/browser4-cli/SKILL.md` as the skill path.

#### Root Cause Analysis

Evaluation template uses unbound placeholder variables with no setup automation.

#### Code Pointer

`Evaluation task template.`

#### AI Suggested Improvement

- Add a `source setup-eval-env.sh` (or `.ps1`) script that exports these variables
- Document the exact values directly in the evaluation template as fallback inline comments
- Add a `.env.example` at the repo root with all evaluation variables pre-populated

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 2: Shell CWD resets to `C:\Users\pereg` after every command on Windows

**Severity:** High
**Category:** UX / Reliability

#### Reproduction

Run any `cargo run --manifest-path ...` command from the repo root on Windows via Git Bash. After the command completes, the shell working directory is reset from the repo root back to `C:\Users\pereg`.

#### Expected Behavior

The shell should preserve the working directory. Users should be able to run sequential commands without re-navigating.

#### Actual Behavior

Every single command must be prefixed with `cd "D:/workspace/Browser4/Browser4-4.11" && cargo run...`. For a task requiring 20+ commands, this adds ~50 characters per command and significant friction. The Shell cwd reset message appears after every invocation.

#### Root Cause Analysis

The browser4-cli daemon likely changes the shell's working directory as a side effect, or the bash integration with the Windows filesystem triggers a cwd reset. The exact mechanism is unclear — the prompt shows a different directory after each command returns.

#### Code Pointer

`Unknown — likely in the daemon process or bash shell integration layer.`

#### AI Suggested Improvement

- Investigate and fix the cwd reset behavior on Windows
- If unfixable, document the behavior prominently and provide a wrapper script that preserves the cwd
- Consider a `browser4-cli` shell alias/function that `cd`s to the repo root before each invocation

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 3: `eval` JavaScript quoting on Windows is extremely error-prone

**Severity:** High
**Category:** UX / Reliability

#### Reproduction

```bash
eval "JSON.stringify(Array.from(document.querySelectorAll('a')).filter(a => a.href.includes('/read/')))" --json
```
On Windows Git Bash, single quotes inside double quotes require escape sequences like `'\''`. For moderately complex JS expressions with nested quotes (querySelector strings, arrow functions), the escaping becomes unreadable and error-prone.

#### Expected Behavior

A straightforward way to pass JavaScript expressions. The documented `--stdin`, `--file`, and `--base64` modes exist but are high-friction for one-off queries.

#### Actual Behavior

This evaluator spent significant time debugging quote escaping for `eval` commands. The `querySelectorAll('a')` pattern required `querySelectorAll('\''a'\'')` — nearly unreadable. Several eval queries returned empty results or errors due to quoting issues before being corrected.

#### Root Cause Analysis

Windows Git Bash (MinGW) and the Rust CLI argument parser interact poorly with nested quotes. The `eval` command takes the JS expression as a positional string argument that passes through shell parsing.

#### Code Pointer

``cli/browser4-cli/src/commands.rs` — eval command argument handling.`

#### AI Suggested Improvement

- Make `eval --stdin` the default behavior when no expression argument is provided (read JS from stdin)
- Add an interactive eval mode that opens $EDITOR
- Document a clear Windows workaround pattern prominently in SKILL.md §5
- Consider a `--js-file` shorthand (currently `--file` exists but is not clearly documented for eval)
- Add a Windows-specific tip: "On Windows, write JS to a temp file and use `eval --file temp.js`"

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 4: Anti-bot protections (Cloudflare, Google CAPTCHA) block legitimate research tasks

**Severity:** High
**Category:** Reliability

#### Reproduction

```bash
goto "https://www.crunchbase.com/organization/stripe"  # → Cloudflare "Attention Required"
goto "https://www.google.com/search?q=Stripe+Crunchbase+funding"  # → Google CAPTCHA /sorry page
```

#### Expected Behavior

Either (a) the browser automation presents as a normal browser and passes common bot checks, or (b) documentation clearly states which types of sites are likely to block automated access and suggests workarounds.

#### Actual Behavior

Both Crunchbase and Google blocked the automated browser with anti-bot pages. This forced a fallback to Wikipedia for funding data and Google News (which worked) for news articles. The Google search block is particularly impactful since web search is a fundamental research tool.

#### Root Cause Analysis

browser4-cli uses Chrome via CDP, which may expose automation signals (navigator.webdriver, missing human-like behavior patterns) that trigger anti-bot detection. Cloudflare and Google's reCAPTCHA are specifically designed to detect and block headless/automated browsers.

#### Code Pointer

`Backend WebDriver implementation; CDP connection parameters; browser fingerprint/stealth configuration.`

#### AI Suggested Improvement

- Document known anti-bot limitations prominently in SKILL.md with a "Sites that may block automated access" section
- Add a `--stealth` mode that applies common evasion techniques (webdriver flag removal, realistic fingerprints)
- Provide a "Search Google" convenience command that uses a search API instead of browser-based search
- Add a `search <query>` command that can use multiple search backends (Google, Bing, DuckDuckGo) with automatic fallback
- Consider integrating with a SERP API for reliable search when direct browser access is blocked

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 5: `goto` locale auto-redirect changes page content without warning

**Severity:** Medium
**Category:** UX / Reliability

#### Reproduction

```bash
goto "https://stripe.com/"  # → Page URL: https://stripe.com/zh-us (Chinese)
```

#### Expected Behavior

Either (a) the command respects the requested URL without geo-IP redirection, (b) a warning is shown when the final URL differs substantially from the requested URL, or (c) a locale override option is documented.

#### Actual Behavior

Navigating to `stripe.com` silently redirected to `stripe.com/zh-us`, likely based on the user's IP geolocation. The page content changed to Chinese without any indication that this happened. The evaluator noticed the URL change in the "Page URL" output line, but the page title was also in Chinese, making it obvious.

#### Root Cause Analysis

Stripe.com uses IP-based geolocation to redirect users to locale-specific versions. The browser follows the HTTP redirect transparently. The CLI reports the final URL but does not flag that it differs from the requested URL.

#### Code Pointer

``goto` command implementation and page metadata reporting.`

#### AI Suggested Improvement

- Display a warning when the final URL's host or path prefix differs significantly from the requested URL
- Add a `--no-redirect` or `--follow-redirects=false` flag to prevent locale redirects
- Add `--locale <code>` or `--accept-language <code>` option to set the Accept-Language header
- Document the redirect behavior in the `goto` command help text

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 6: `/use-cases` URL in task description is incorrect — no index page exists

**Severity:** Low
**Category:** Discoverability (task design, not product)

#### Reproduction

Navigate to `https://stripe.com/use-cases` — returns 404 "Page not found."

#### Expected Behavior

The task description should reference actual URLs that exist on the target site.

#### Actual Behavior

Stripe does not have a `/use-cases` index page. Individual use-case pages exist at `/use-cases/saas`, `/use-cases/ai`, `/use-cases/ecommerce`, etc. The evaluator had to extract all links from the homepage to discover the correct URLs.

#### Root Cause Analysis

Stripe restructured their site at some point, removing the `/use-cases` landing page. The task description references an outdated URL.

#### Code Pointer

`N/A — task template issue, not product code.`

#### AI Suggested Improvement

- Update the task to reference specific use-case pages or to instruct the evaluator to discover use-case URLs from the homepage navigation
- Add a note in the evaluation template that URLs may have changed and evaluators should adapt

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 7: Google News article titles missing from `eval` textContent extraction

**Severity:** Medium
**Category:** Product / Reliability

#### Reproduction

```bash
eval "JSON.stringify(Array.from(document.querySelectorAll('a')).filter(a => a.href.includes('/read/')).slice(0,10).map(a => ({title: a.textContent?.trim(), href: a.href})))" --json
```
Half of the article titles returned as empty strings because Google News splits article title text across nested `<span>` and `<a>` elements.

#### Expected Behavior

`textContent` should return the full visible text of the link element. Either it works correctly, or documentation explains the limitation.

#### Actual Behavior

Some `textContent` values were empty strings despite the link element having visible text in nested elements. The titles were successfully extracted from the accessibility-tree snapshot instead, where they appeared as AX-tree label text. This suggests a Shadow DOM or slotted content issue on Google News.

#### Root Cause Analysis

Google News uses complex nested DOM structures, potentially with Shadow DOM or custom elements, where standard `textContent` on the `<a>` element returns empty. The accessibility tree snapshot correctly surfaces the text because it traverses the composed/accessible tree.

#### Code Pointer

`Google News DOM structure; browser4-cli eval JS execution context.`

#### AI Suggested Improvement

- Document that `eval` queries operate on the light DOM and may miss text in Shadow DOM/web components
- Add a `get text <ref>` command that uses the accessibility tree directly (the snapshot already has the correct text)
- Consider adding a `get innerText <css-selector>` variant of `htmlsnapshot get` that uses `innerText` instead of `textContent`
- Add a note in SKILL.md: "For sites using web components (Google News, Reddit, YouTube), prefer snapshot-based extraction over eval"

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 8: No built-in "extract all links" command for site structure discovery

**Severity:** Low
**Category:** Discoverability / UX

#### Reproduction

To discover available pages after a 404 on `/use-cases`, the evaluator had to:
1. Navigate back to the homepage
2. Capture `htmlsnapshot`
3. Run `htmlsnapshot get all attr "a[href]" href --limit 200`
4. Manually scan the output for use-case related URLs

#### Expected Behavior

A quick way to discover a site's structure — e.g., `sitemap` or `links` command that extracts and groups all links by path prefix.

#### Actual Behavior

The `htmlsnapshot get all attr` approach works but returns a flat, unsorted list of 200 URLs that requires manual scanning. The `htmlsnapshot inspect` command exists for CSS selector discovery but doesn't help with URL structure discovery.

#### Root Cause Analysis

No built-in command for site structure exploration. The user must compose `htmlsnapshot get all attr` with manual filtering.

#### Code Pointer

`N/A — feature request.`

#### AI Suggested Improvement

- Add a `links` or `sitemap` command: `browser4-cli links --group --internal-only` that extracts, deduplicates, and groups all links by path prefix
- Add a "Site Discovery" recipe to SKILL.md §6 showing the `htmlsnapshot get all attr "a[href]" href` pattern
- Consider a `--paths-only` flag for `htmlsnapshot get all attr` that strips query strings and fragments for cleaner output

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 9: `cargo run` compilation overhead and noise on every command

**Severity:** Low
**Category:** UX

#### Reproduction

Every invocation of `cargo run --manifest-path ...` prints:
```
    Finished `dev` profile [unoptimized + debuginfo] target(s) in 0.27s
     Running `cli\browser4-cli\target\debug\browser4-cli.exe ...`
```
For 20+ commands, this adds ~10 lines of noise and ~5 seconds of cumulative overhead.

#### Expected Behavior

Either cargo output is suppressed by default in dev mode, or a faster pattern is documented prominently (build once, invoke binary directly).

#### Actual Behavior

The `--quiet` flag to cargo suppresses these lines but must be added manually. The development.md mentions it but the SKILL.md quick-start does not. The evaluator discovered this pattern mid-task but it added another flag to every command.

#### Root Cause Analysis

`cargo run` always checks compilation status. The development.md documents `--quiet` for output redirection but does not recommend it as the default invocation pattern for interactive use.

#### Code Pointer

``skills/browser4-cli/references/development.md` line 39.`

#### AI Suggested Improvement

- Document a two-step pattern prominently: `cargo build --manifest-path ...` once, then `./cli/browser4-cli/target/debug/browser4-cli.exe` for all subsequent commands
- Add a `dev` or `b4` shell alias/script that builds once and provides a shortcut
- Update the SKILL.md "Copy-Paste Template" section to use the built binary, not `cargo run`
- Recommend `cargo run --quiet --manifest-path ...` as the default for interactive evaluation sessions

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 10: Paywalled/blocked content has no standard error or fallback behavior

**Severity:** Medium
**Category:** Reliability / UX

#### Reproduction

```bash
goto "https://www.forbes.com/sites/..."  # Forbes loads with paywall
eval "document.querySelector('article')?.innerText"  # Returns "undefined"
```

#### Expected Behavior

After `goto`, a warning or indicator that the page content may be behind a paywall or not fully accessible. Alternatively, a `--reader-mode` flag that attempts to extract content via readability algorithms.

#### Actual Behavior

The `goto` command reports success and shows the page title. The user discovers the paywall only when attempting content extraction, which returns empty/null results. The failure mode is silent — there is no indication that content is paywalled until extraction fails with an unhelpful `null` or `undefined`.

#### Root Cause Analysis

`goto` reports success based on HTTP response (200) and page load completion, not on content accessibility. Paywall detection requires content analysis that the CLI does not perform.

#### Code Pointer

``goto` command implementation; page metadata reporting.`

#### AI Suggested Improvement

- Add a `--readability` or `--reader-mode` flag to `htmlsnapshot` that extracts main content using a readability algorithm (bypassing some paywall-lite implementations)
- Add paywall detection heuristics: check for common paywall CSS selectors/DOM patterns and warn
- Document common failure modes in SKILL.md: "Paywalled sites (Forbes, WSJ, Bloomberg) will load but eval/htmlsnapshot may return empty content"
- Add a `--extract-main-content` flag that uses the accessibility tree to find and extract the primary content region

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 11: `htmlsnapshot get text "h1"` returned logo alt text instead of page heading

**Severity:** Low
**Category:** Product / UX

#### Reproduction

```bash
goto "https://stripe.com/use-cases/saas"
htmlsnapshot get text "h1"  # → "Stripe logo"
```

#### Expected Behavior

The `<h1>` selector returns the page's primary heading ("Stripe for SaaS | Billing Platform for SaaS Businesses").

#### Actual Behavior

The page uses `<h1>` for the logo image alt text, not for the visible page title. The real page heading is in a different element. The `htmlsnapshot get text` command works correctly — it's the page markup that's non-standard — but the result is confusing to a user who expects `h1` to contain the page title.

#### Root Cause Analysis

Modern single-page applications (SPAs) often misuse semantic HTML elements. The Stripe site puts the logo in an `<h1>`. This is a web authoring issue, but it affects the usability of `htmlsnapshot get text "h1"` as a "get the main heading" shortcut.

#### Code Pointer

`N/A — website markup issue, not product code. But documentation could address this.`

#### AI Suggested Improvement

- Document that `h1` may not contain the page title on SPA sites — recommend `document.title` via eval as a fallback
- Add a convenience command: `title` or `page-info` that returns the `<title>` element text reliably
- Consider `htmlsnapshot summary` as a higher-level alternative that identifies the main content region

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 12: `htmlsnapshot` capture is large and slow for content-heavy pages

**Severity:** Low
**Category:** UX / Performance

#### Reproduction

```bash
goto "https://stripe.com/"
htmlsnapshot  # → 711 KB, 179 links, 100 interactive elements, 46 images
```

#### Expected Behavior

A lightweight capture that focuses on text content and structural elements, suitable for quick extraction.

#### Actual Behavior

The Stripe homepage capture was 711 KB with 100 interactive elements catalogued. The output includes detailed box coordinates for every element. For a task that only needs text content, this is excessive.

#### Root Cause Analysis

`htmlsnapshot` captures the full DOM with detailed metadata (bounding boxes, CSS classes, ARIA attributes) by default. There is no "text-only" or "lightweight" mode.

#### Code Pointer

``htmlsnapshot` command implementation; snapshot storage layer.`

#### AI Suggested Improvement

- Add a `--text-only` or `--lightweight` flag that captures only text content and semantic structure, skipping box coordinates
- Add a `--no-images` flag to skip image metadata
- Consider making `htmlsnapshot summary` capture and summarize in one step (currently requires a separate `htmlsnapshot` capture first)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: `$cliInvocation`, `$helpCmd`, `$skillPath` are undefined template variables

Read the evaluation task template. Same issue as the prior evaluation — these placeholder variables are not defined anywhere in the environment or repository configuration.

#### Issue 2: Shell CWD resets to `C:\Users\pereg` after every command on Windows

Run any `cargo run --manifest-path ...` command from the repo root on Windows via Git Bash. After the command completes, the shell working directory is reset from the repo root back to `C:\Users\pereg`.

#### Issue 3: `eval` JavaScript quoting on Windows is extremely error-prone

```bash
eval "JSON.stringify(Array.from(document.querySelectorAll('a')).filter(a => a.href.includes('/read/')))" --json
```
On Windows Git Bash, single quotes inside double quotes require escape sequences like `'\''`. For moderately complex JS expressions with nested quotes (querySelector strings, arrow functions), the escaping becomes unreadable and error-prone.

#### Issue 4: Anti-bot protections (Cloudflare, Google CAPTCHA) block legitimate research tasks

```bash
goto "https://www.crunchbase.com/organization/stripe"  # → Cloudflare "Attention Required"
goto "https://www.google.com/search?q=Stripe+Crunchbase+funding"  # → Google CAPTCHA /sorry page
```

#### Issue 5: `goto` locale auto-redirect changes page content without warning

```bash
goto "https://stripe.com/"  # → Page URL: https://stripe.com/zh-us (Chinese)
```

#### Issue 6: `/use-cases` URL in task description is incorrect — no index page exists

Navigate to `https://stripe.com/use-cases` — returns 404 "Page not found."

#### Issue 7: Google News article titles missing from `eval` textContent extraction

```bash
eval "JSON.stringify(Array.from(document.querySelectorAll('a')).filter(a => a.href.includes('/read/')).slice(0,10).map(a => ({title: a.textContent?.trim(), href: a.href})))" --json
```
Half of the article titles returned as empty strings because Google News splits article title text across nested `<span>` and `<a>` elements.

#### Issue 8: No built-in "extract all links" command for site structure discovery

To discover available pages after a 404 on `/use-cases`, the evaluator had to:
1. Navigate back to the homepage
2. Capture `htmlsnapshot`
3. Run `htmlsnapshot get all attr "a[href]" href --limit 200`
4. Manually scan the output for use-case related URLs

#### Issue 9: `cargo run` compilation overhead and noise on every command

Every invocation of `cargo run --manifest-path ...` prints:
```
    Finished `dev` profile [unoptimized + debuginfo] target(s) in 0.27s
     Running `cli\browser4-cli\target\debug\browser4-cli.exe ...`
```
For 20+ commands, this adds ~10 lines of noise and ~5 seconds of cumulative overhead.

#### Issue 10: Paywalled/blocked content has no standard error or fallback behavior

```bash
goto "https://www.forbes.com/sites/..."  # Forbes loads with paywall
eval "document.querySelector('article')?.innerText"  # Returns "undefined"
```

#### Issue 11: `htmlsnapshot get text "h1"` returned logo alt text instead of page heading

```bash
goto "https://stripe.com/use-cases/saas"
htmlsnapshot get text "h1"  # → "Stripe logo"
```

#### Issue 12: `htmlsnapshot` capture is large and slow for content-heavy pages

```bash
goto "https://stripe.com/"
htmlsnapshot  # → 711 KB, 179 links, 100 interactive elements, 46 images
```

