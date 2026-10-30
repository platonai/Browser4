---
title: "Bulk Collection — Persist, Localize, Inspect, Deliver"
description: "Collecting many pages/items without losing data: persist rows as you go, pin locale and currency, inspect large outputs without flooding the context, choose a deliverable format (CSV, not xlsx), and quote commands correctly when an agent drives the CLI on Windows."
tier: procedure
---

# Bulk Collection — Persist, Localize, Inspect, Deliver

Use this when a task means "collect N items" (pages, products, listings) rather than
"read this one page". The failure mode is always the same: the data is gathered into the
conversation, the tool round is cut off, the digest keeps only a few results, and the
next round re-fetches what it already had.

Observed (2026-10-28 supervision run): a 20-product task crawled the same 20 pages
**four times** and, after 29 minutes, still had no deliverable — because nothing had
been written to disk between rounds.

## Quick Start

```bash
# 1. Persist from the first round: fetch + extract + write in one command.
browser4-cli crawl --seed-file .b4scratch/urls.txt --depth 0 \
  --sql @.b4scratch/x-asin.sql --format csv -o .b4scratch/rows.csv

# 2. Pin the locale by OPERATING THE PAGE (pick language/currency in the site's own UI)
browser4-cli -s shop open --headless "https://www.amazon.com/"   # land on the storefront
browser4-cli -s shop snapshot -v 0 --stdout                      # find the language/currency control
browser4-cli -s shop click <ref>                                 # open the picker, choose English / USD
browser4-cli -s shop snapshot -v 0                               # confirm the page is now in en-US / USD

# 3. Verify the row count before handing the CSV over (coding.shell / any shell).
python -c "import csv;print(sum(1 for _ in csv.reader(open('.b4scratch/rows.csv',encoding='utf-8'))))"
```

## When to Use

- The task asks for N pages/products/rows rather than one page or one field.
- A previous run "collected everything" and then reported nothing, or re-fetched the same
  URLs across rounds.
- Titles or prices come back in an unexpected language or currency.
- You need to inspect a large CSV/page dump without flooding the model's context.
- The deliverable is supposed to be a spreadsheet and you are deciding what to write.

## How It Works

### Rounds are capped — persist or lose it

A browsing run's inner tool loop is capped (40 tool calls per round). When the round is
cut off, only a short digest of the newest results survives into the next round: the
conversation-level plan and any data that was never written to disk are gone. So a bulk
task must write each row as soon as it has it, and keep a progress list of collected ids.

### Locale and currency: drive the site's own picker

Marketplaces localize by host/IP, profile and account, so `amazon.com` can answer a
US-oriented task with Chinese titles, an HKD price, and a
`/customer-preferences/…` "change language & currency" interstitial instead of the page
you asked for. **The fix is an interaction, not a request header**: open the storefront,
read the page, and choose the language/currency/delivery location in the site's own UI —
that writes the preference into the browser profile, which is what every later page (and
every later batch on the same session) then inherits.

`?language=en_US`, `set headers Accept-Language` and a hand-written `i18n-prefs` cookie
are at best site-specific hints. Measured 2026-10-29 on `amazon.com`: the URL parameter,
the header override **and** `cookie-set lc-main/i18n-prefs` all left the tab on the
Chinese preferences interstitial in a fresh profile; only driving the page (or fetching
with the cookies the page itself had set) produced English content.

Post-processing localized values (currency conversion, title translation) is not
extraction and produces a silently wrong dataset.

> **The durable fix is the browser profile, and only the UI can write it.** `crawl` reads
> with the calling session's browser (DEFAULT unless `-s <name>`), `crawl --session <name>`
> / `--profile <dir>` pins one explicitly, and `swarm create --session <name>` reuses a
> warmed session's profile directory. So warm the storefront once in that session
> (`-s shop open https://www.amazon.com` → pick language + currency + delivery location),
> verify one product, then run the batch: every page — and every later batch — inherits
> the choice.

### Tool results are bounded

Every tool result is bounded before it reaches the model (`coding.read` ≈1.6 KB, most
tools ≈1.2 KB), though it now keeps its line structure. That is enough to read a few rows,
never a whole batch: write the batch to a file and inspect slices of it (`coding.stat`,
`coding.grep`, `python -c` printing selected columns).

## Patterns

### Batch: crawl straight to CSV

```bash
browser4-cli crawl --seed-file .b4scratch/urls.txt --depth 0 \
  --sql @.b4scratch/x-asin.sql --format csv -o .b4scratch/rows.csv
```

`crawl` fetches and extracts in parallel and writes the file itself, so nothing depends on
the model's context. Prefer this whenever the item URLs are known up front.

### Item-by-item: append a row and record progress

When each item needs a bespoke interaction (`goto` + `eval`), append immediately:

```text
coding.append(path=".b4scratch/rows.csv", content="B0DY95T5HB,Chefman 0.7 cu ft,99.89\n")
coding.append(path=".b4scratch/progress.txt", content="B0DY95T5HB\n")
```

Write the header row once at the start; never rewrite the whole file per item.
`coding.listDir` reminds you when `.b4scratch/` has no `progress.txt` — its absence is
how a run loses the ids it already collected and re-crawls the same pages.

### Waiting for a page in a batch

There is no `goto --load` option. `goto` is a navigation command; the explicit wait is
`wait --load domcontentloaded|load|networkidle` afterwards. For a batch, prefer the path
that does the waiting itself:

```bash
# One command per page: navigate, wait, evaluate, write — no round-trip between steps.
browser4-cli crawl --seed-file .b4scratch/urls.txt --depth 0 --sql @.b4scratch/x-asin.sql --format csv -o .b4scratch/rows.csv

# Per-item eval: resolve the promise (--await), bound the wait to the result element.
browser4-cli eval --await --wait-selector "#productTitle" --file .b4scratch/item.js
```

After `goto`, poll the element you need (`wait "<selector>"`, or
`eval --wait-selector <css>`) instead of assuming the DOM settled: a result page
computed by page JS can still be empty when the network goes quiet.

### Resume a half-finished run

At the top of a round, read `.b4scratch/progress.txt`, skip the ids already listed, and
continue from the first missing one. On completion, compare the row count with the
requested N before calling `system.taskComplete`.

### Pin the locale by operating the page (the fix that works)

A localized marketplace answers with a *preferences page* instead of the page you asked
for. Recognise it (`/customer-preferences/`, "change language & currency", a language
dropdown) and then **use it like a shopper would** — one interaction, then verify:

```bash
browser4-cli -s shop open --headless "https://www.amazon.com/"
browser4-cli -s shop snapshot -v 0 --stdout                          # locate the picker/link, note its ref
browser4-cli -s shop click <ref>                                     # or select <ref> "en_US"
browser4-cli -s shop htmlsnapshot get text "#nav-logo"               # the page is now the storefront
browser4-cli -s shop goto "https://www.amazon.com/dp/B0DY95T5HB"     # verify one known product
```

Notes that save a round:

- The picker is often a `<select>` inside a form: `snapshot -i` (or `snapshot grep -i
  "language|currency|English"`) finds it, `select <ref> "en_US"` + `click <submit-ref>`
  commits it. A link that already carries `language=en_US` only works if the site honours
  it — click it rather than trusting the URL.
- After the click, **re-snapshot**: refs are single-use and the interstitial replaces the
  document.
- The choice is stored in the profile, so the session keeps it: reuse `-s shop` for the
  whole batch (`crawl --session shop`) and every page after this point is the right locale.
- `set headers "Accept-Language=en-US,en;q=0.9"` and a `?language=` query are *hints*, not
  the fix: keep them as a cheap first try, but if the page still comes back localized, go
  back to the picker. Never post-process the values.

For an un-attended batch (`crawl` has no click step), warm the locale first in the session
as above, then run `crawl --session shop` — the crawl inherits the profile.

### Inspect a large CSV without flooding the context

```bash
browser4-cli htmlsnapshot get all text "table tr"        # a page slice
python -c "import csv;r=list(csv.reader(open('.b4scratch/rows.csv')));print(r[0]);print(r[1])"
```

Use the file for the data and the tool output for the decision (counts, first rows,
missing columns).

### Quote `@file` correctly when an agent drives the CLI

`b4.run(args=…)` is executed as `pwsh -Command "& browser4-cli …"`, so PowerShell parses
the line: quote `@file` or it is treated as splatting. `coding.shell` is `cmd.exe`
instead, and inline interpreters (`powershell -Command …`, `bash -c …`) are refused by the
command guard.

```text
✗ b4.run(args="crawl --sql @.b4scratch/q.sql …")     → PowerShell ParserError
✓ b4.run(args="crawl --sql '@.b4scratch/q.sql' …")   → the file is read
```

## Flags

| Flag / tool | Use |
|---|---|
| `crawl --seed-file <file>` | one URL list for the whole batch |
| `crawl --depth 0` | detail/list pages only, no link following |
| `crawl --sql @file.sql` | X-SQL extraction (quote the `@file` on Windows) |
| `crawl --format csv\|json\|table` | output format (`csv` for spreadsheets) |
| `crawl -o <path>` | write the extracted rows to a file |
| `snapshot -v 0` / `click` / `select` | drive the locale picker on the page (the durable fix) |
| `set headers` | session-wide `Accept-Language` — a hint, not the locale fix |
| `crawl --session <name>` | read the site in the profile whose locale you prepared |
| `coding.append(path, content)` | persist a row/id as soon as you have it |
| `coding.stat` / `coding.grep` | size/grep a large output without dumping it |
| `<workspace>/.b4inputs/` | inputs the engine copied in for this task (read-only deliverables) |

## Errors & Recovery

| Symptom | Cause | Fix |
|---|---|---|
| The same pages are fetched in several rounds | rows/progress kept only in the conversation | append each row + id to `.b4scratch/` as you collect |
| Titles/prices in the wrong language or currency, or you land on a "change language & currency" page | the site localized for the profile/IP | open the picker on the page and choose language + currency (`snapshot` → `select`/`click`), then verify one product; `set headers`/`?language=` are only hints |
| `ParserError` from a `b4.run` command | PowerShell parsed an unquoted `@file` | quote it: `'--sql @q.sql'` (see [shell-quoting.md](shell-quoting.md)) |
| `PERMISSION_DENIED \| code=COMMAND_NOT_ALLOWED` | inline interpreter or a blocked command | keep each shell's syntax in its own tool; use `b4.run` or a script file |
| Extracted CSV has the wrong columns | spec not compared with the output | diff the header row against the field spec; report missing columns in `problems` |
| `coding.read` refuses the file | binary (`.xlsx`, `.pdf`, `.zip`, image) | extract with a script, or convert to CSV once |
| Deliverable "missing" after a green run | written under the task workspace, not the repo | `agent result` → `workspace`, then copy the CSV out |

## Related

- [crawl.md](crawl.md) — seed files, X-SQL extraction, `--format`, failure semantics
- [scrape-formats.md](scrape-formats.md) — one capture, many formats; the quoting trap
- [emulation.md](emulation.md) — `set headers`, `set geo`, other overrides
- [agent-workspace.md](agent-workspace.md) — where an agent reads/writes and what it may deliver
- [shell-quoting.md](shell-quoting.md) — the full Windows quoting guide
