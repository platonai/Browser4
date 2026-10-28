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

# 2. Pin the locale on the page before trusting titles/prices.
browser4-cli goto "https://www.amazon.com/dp/B0DY95T5HB?language=en_US"

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

### Locale and currency are page-level

Marketplaces localize by host/IP and `Accept-Language`. The backend sends no
`Accept-Language` of its own, so `amazon.com` can answer with Chinese titles and HKD
prices for a US-oriented task. The fix belongs on the page — a header override, a cookie,
or the `?language=` URL parameter — and must be verified on one known product before the
batch runs. Post-processing localized values (currency conversion, title translation) is
not extraction and produces a silently wrong dataset.

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

### Pin the locale before extracting

```bash
browser4-cli set headers "Accept-Language=en-US,en;q=0.9"
browser4-cli goto "https://www.amazon.com/dp/B0DY95T5HB?language=en_US"
```

For cookie-driven locales, set `lc-main` / `i18n-prefs` before navigating. `crawl` has no
per-request header flag, so for bulk runs put `?language=en_US` on every seed URL.

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
| `set headers` | session-wide `Accept-Language` (locale/currency) |
| `goto …?language=en_US` | per-request locale for a single page |
| `coding.append(path, content)` | persist a row/id as soon as you have it |
| `coding.stat` / `coding.grep` | size/grep a large output without dumping it |
| `<workspace>/.b4inputs/` | inputs the engine copied in for this task (read-only deliverables) |

## Errors & Recovery

| Symptom | Cause | Fix |
|---|---|---|
| The same pages are fetched in several rounds | rows/progress kept only in the conversation | append each row + id to `.b4scratch/` as you collect |
| Titles/prices in the wrong language or currency | host locale, not the site | set `Accept-Language` / cookies / `?language=en_US` and re-read; verify one product |
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
