# Issues: Use Case 12: Company Due Diligence Automation

> **Source:** `20260710-140013-Use Case 12_ Company Due Diligence Automation.full.md` | **Date:** 20260710-140013 | **Mode:** dev

## Scenario Background

### Task

✅ **Task completed successfully.** The `stripe-due-diligence.md` report (286 lines) was produced with comprehensive sections including executive summary, company overview, product catalog, target customers, funding history, acquisitions, partnerships, major business events, risk assessment, maturity assessment, and investment thesis.

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

## Issues Found (12 issues)
> **Review complete:** 0 approved, 12 deferred/rejected

### Issue 1: `$cliInvocation`, `$helpCmd`, `$skillPath` are undefined template variables

**Severity:** Medium
**Category:** Documentation

#### Review Result

**Decision:** DUPLICATE

**Summary:** - Add a `source setup-eval-env.sh` (or `.ps1`) script that exports these variables

---

### Issue 2: Shell CWD resets to `C:\Users\pereg` after every command on Windows

**Severity:** High
**Category:** UX / Reliability

#### Review Result

**Decision:** DUPLICATE

**Summary:** - Investigate and fix the cwd reset behavior on Windows

---

### Issue 3: `eval` JavaScript quoting on Windows is extremely error-prone

**Severity:** High
**Category:** UX / Reliability

#### Review Result

**Decision:** DUPLICATE

**Summary:** - Make `eval --stdin` the default behavior when no expression argument is provided (read JS from stdin)

---

### Issue 4: Anti-bot protections (Cloudflare, Google CAPTCHA) block legitimate research tasks

**Severity:** High
**Category:** Reliability

#### Review Result

**Decision:** DUPLICATE

**Summary:** - Document known anti-bot limitations prominently in SKILL.md with a "Sites that may block automated access" section

---

### Issue 5: `goto` locale auto-redirect changes page content without warning

**Severity:** Medium
**Category:** UX / Reliability

#### Review Result

**Decision:** DUPLICATE

**Summary:** - Display a warning when the final URL's host or path prefix differs significantly from the requested URL

---

### Issue 6: `/use-cases` URL in task description is incorrect — no index page exists

**Severity:** Low
**Category:** Discoverability (task design, not product)

#### Review Result

**Decision:** WONTFIX

**Summary:** - Update the task to reference specific use-case pages or to instruct the evaluator to discover use-case URLs from the homepage navigation

---

### Issue 7: Google News article titles missing from `eval` textContent extraction

**Severity:** Medium
**Category:** Product / Reliability

#### Review Result

**Decision:** WONTFIX

**Summary:** - Document that `eval` queries operate on the light DOM and may miss text in Shadow DOM/web components

---

### Issue 8: No built-in "extract all links" command for site structure discovery

**Severity:** Low
**Category:** Discoverability / UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add a `links` or `sitemap` command: `browser4-cli links --group --internal-only` that extracts, deduplicates, and groups all links by path prefix

---

### Issue 9: `cargo run` compilation overhead and noise on every command

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Document a two-step pattern prominently: `cargo build --manifest-path ...` once, then `./cli/browser4-cli/target/debug/browser4-cli.exe` for all subsequent commands

---

### Issue 10: Paywalled/blocked content has no standard error or fallback behavior

**Severity:** Medium
**Category:** Reliability / UX

#### Review Result

**Decision:** DEFER

**Summary:** - Add a `--readability` or `--reader-mode` flag to `htmlsnapshot` that extracts main content using a readability algorithm (bypassing some paywall-lite implementations)

---

### Issue 11: `htmlsnapshot get text "h1"` returned logo alt text instead of page heading

**Severity:** Low
**Category:** Product / UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Document that `h1` may not contain the page title on SPA sites — recommend `document.title` via eval as a fallback

---

### Issue 12: `htmlsnapshot` capture is large and slow for content-heavy pages

**Severity:** Low
**Category:** UX / Performance

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add a `--text-only` or `--lightweight` flag that captures only text content and semantic structure, skipping box coordinates

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

#auto-approve
