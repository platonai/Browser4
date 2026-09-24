---
title: "Crawl Checkpoint & Resume (断点续传)"
description: "How an interrupted crawl is reported, persisted and continued — the checkpoint file, the resume semantics, and what is deliberately not re-fetched."
tier: reference
---

# Crawl Checkpoint & Resume (断点续传)

A crawl that is interrupted — a backend restart, a crash, a `SIGKILL`, or a task the
server-side `--timeout` budget cut off — can be **continued** instead of re-submitted.
The task keeps its id, the URLs that already succeeded are **not requested again**,
the URLs that were in flight and the links the crawl had discovered but never queued
are fetched, and the result is the union of both runs with per-row provenance.

Before this existed, an interrupted crawl could not be resumed at all: only the task
*record* survived, and a task that was running when the process died was restored in
its `Processing` state with no worker behind it — a phantom that `crawl status`
reported as running forever.

## 1. Status tells the truth

| Status | Meaning |
|---|---|
| `Created` / `Processing` | A worker is running (or about to run) this task. |
| `OK` | The crawl finished. |
| `Request Timeout` | The server-side task limit (or a caller's `crawl cancel`) stopped it. |
| `Interrupted` | The worker died with the process. **Terminal** — nothing will move it on its own — and resumable. |
| `Internal Server Error` / `Not Found` | The run failed / the task is unknown. |

`Interrupted` is terminal on purpose: a status that is neither terminal nor pollable
would make every `crawl status` wait hang. It is also the one terminal status that is
*resumable*, which is what `resumable` on the record tells you:

```bash
browser4-cli crawl status <task-id>
# Interrupted  | 42 page(s) fetched, 3 left to fetch  | resumable: yes
# interrupted by a server restart; resume with 'browser4-cli crawl resume <task-id>'
```

An interrupted task is exempt from the task store's 100-entry LRU and from the 1-day
task TTL: the record lives outside the bounded store, and the work state lives in its
own file. `crawl clear` (which removes *finished* tasks) leaves it alone;
`crawl clear --all` is the explicit way to throw it away.

## 2. What is persisted, and where

Two files per task, outside the task store and outside its lifecycle:

```
${browser4.data.dir:-$HOME}/.browser4/data/crawl/checkpoints/<taskId>.json        (work state)
${browser4.data.dir:-$HOME}/.browser4/data/crawl/checkpoints/<taskId>.rows.jsonl  (fetched rows)
${browser4.data.dir:-$HOME}/.browser4/data/crawl/checkpoints/<taskId>.json.bak    (previous good state)
```

The state file carries three things:

1. **The input contract** — the seed URLs (a `--seed-file` is resolved by the CLI
   before submission, so the file's *contents* are captured), `depth`, the
   LoadOptions `args` (out-link selector/pattern, top-links, ignore-url-query,
   no-norm, readonly, expires, priority), the X-SQL query, the parallelism budget and
   the task budget. A resume never needs the caller to re-type anything.
2. **The work state** — per seed: the URLs that failed terminally (with their
   reasons), the URLs that were submitted and never settled, the discovered frontier,
   and the counts (`pagesExpected`) the merged report is built from.
3. **The rows already fetched** — also in the row log, which is what makes the
   "already fetched" set durable (see below).

The row log is an **append per settled URL**: one line, written once. A periodic
whole-state rewrite cannot contain the rows that settled since the previous write, and
a resume that does not know about such a row would request that URL a second time —
the one thing a resume promises never to do. So the two files split the promise in a
way that is both cheap and honest:

| Fact | Durability |
|---|---|
| "this URL was already fetched, do not request it again" | **immediate** (one append per row) |
| "these URLs are in flight" | as soon as the round submits them (incremental) |
| "this URL failed terminally", the frontier, the counts | on the write cadence below |

### Crash safety and the write policy

The state file is written by **atomic replace**: the new state goes to a `.tmp` file,
is flushed, and is then moved over the live file, with the previous good copy kept as
`.bak`. A crash therefore leaves either the old checkpoint or the new one — never a
half-written one. A file that cannot be parsed at all (a truncation on an exotic
filesystem) falls back to the `.bak` copy, and if that is unreadable too the task is
reported as **not resumable** rather than crashing the server or silently re-fetching.
The row log needs no such machinery: a partially written last line is skipped, which
costs at most one re-fetch.

State writes are periodic, not only on status changes:

| Trigger | When |
|---|---|
| Change | every 5 settled URLs |
| Staleness | every 2 s of wall clock (a 1 s tick drains whatever changed, so the bound is real even when the crawl goes quiet) |
| Submission | incrementally, as each round queues new URLs |
| Transition | always — a round ending, a status change, shutdown |

The write rate is additionally bounded by the checkpoint's own size (~64 KB/s per
task), because the state file is a whole-state rewrite: a small crawl writes as soon
as enough changed, a very large one writes proportionally less often. A `SIGKILL` can
therefore cost the *in-flight* bookkeeping of the last moment — those URLs are
re-submitted, which is correct — but it cannot cost the fact that a URL was already
fetched.


## 3. Resuming

```bash
# CLI
browser4-cli crawl resume <task-id>              # continue, then poll to completion
browser4-cli crawl resume <task-id> --bg         # continue, return immediately
browser4-cli crawl resume <task-id> --retry-failed
browser4-cli crawl resume <task-id> --force

# REST
curl -X POST "http://localhost:18182/api/crawl/<task-id>/resume?retryFailed=true"
```

The REST call answers with what it did (a rejection is an answer, not an error — only
a task with a *live worker* is a `409 Conflict`, because two workers on one checkpoint
would fetch everything twice):

```json
{
  "taskId": "…", "resumed": true, "status": "Created",
  "message": "resumed run 2: 42 already-fetched URL(s) restored from the checkpoint, 3 URL(s) left to fetch",
  "remaining": 3, "skippedAlreadyFetched": 42, "resumeCount": 1
}
```

### Semantics

- **Already-succeeded URLs are not re-fetched.** They are counted in
  `skippedAlreadyFetched` and restored into the result; the target site sees no
  repeat request for a URL that had already succeeded.
- **Terminally failed URLs stay failed** unless `--retry-failed` is passed — a third
  load of a twice-failed URL buys nothing but time (the same reason the delivery
  retry budget is one).
- **URLs that were in flight are re-submitted**, with a fresh delivery-attempt budget.
- **The frontier is followed**, so a `--depth >= 1` crawl continues its breadth-first
  walk instead of restarting at the seeds.
- **The task id is kept**, `resumeCount` increments, and every row carries `run`
  (`1` = the original run, `2` = the first resume, …), `fetchedAt`, and
  `restoredFromCheckpoint` so a consumer can tell which rows came from which run.
  `--verbose` prints that provenance per URL.
- **The accounting still holds**: `pagesFound + failedPages.size == pagesExpected`,
  across the merge. A row from the first run and a row from the resume are both
  "expected pages" exactly once.
- **Concurrency is enforced**: resuming a task whose worker is alive is refused;
  resuming a task that already completed successfully is a no-op unless `--force`
  (and `--force --retry-failed` re-fetches the URLs it lost).
- **A resumed run gets a fresh task budget**: the URLs it re-submits are new work, and
  measuring them against a clock that already ran out would refuse every one of them.
- **A task with no checkpoint cannot be resumed** — there is no input contract to
  continue from — and says so instead of quietly re-crawling from the seeds. Submit it
  again in that case.

## 4. Automatic resume is **off** by default

```properties
# application.properties
crawl.autoResume=false   # default: an interrupted task waits for 'crawl resume'
```

A restart is not consent to keep hitting third-party sites, so nothing is resumed
until someone asks. With `crawl.autoResume=true`, every task that was running when the
process died is continued at startup, from its checkpoint. Either way the tasks are
reported as interrupted first, so nothing is silent.

## 5. Verifying it end to end

```bash
# 1. Start a crawl that takes a while (depth >= 1, several seeds, parallel > 1).
browser4-cli crawl --seed-file urls.txt --depth 2 --parallel 4 -ol "a[href]" --bg
# 2. Kill the backend process outright while it runs.
# 3. Restart it.
browser4-cli crawl status <task-id>     # → Interrupted, with remaining / skipped counts
browser4-cli crawl resume <task-id>     # → continues; the site sees no repeat request
                                        #   for any URL that had already succeeded
browser4-cli crawl result <task-id>     # → union of both runs, invariant intact
```

The target site's request log is the check that matters: it must show no request for a
URL whose row already existed before the interruption.

**Measured on a real backend** (a fixture site with a 37-page link tree, `--depth 2
--parallel 4`, backend killed with `TerminateProcess` mid-crawl, then restarted and
resumed with `crawl resume`):

| Check | Result |
|---|---|
| Status after the restart | `Interrupted`, `resumable: true`, `remaining: 18`, `skippedAlreadyFetched: 7` |
| Requests for the 7 URLs that had already succeeded | **exactly 1 each** — none re-requested |
| Requests for the 6 URLs in flight at the kill | 2 each (re-submitted, as designed) |
| Pages in the final result | **37 of 37**, `status: OK`, `pagesFound + failedPages == pagesExpected` |
| Row provenance | 7 rows `run: 1, restoredFromCheckpoint: true`; 30 rows `run: 2` |

Two gaps that this test *found* and that are now fixed by the row log and the
incremental submission record: a URL that settled in the last moments before the kill
was re-fetched, and links queued in that window were lost silently (the crawl finished
with 35 and then 29 of 37 pages, reporting `OK`). Both are what the two-file layout
above exists to prevent.

## Non-goals

- Single-backend checkpoint/resume only; no distributed or cross-node resume.
- The page store's `--expires` cache semantics are a different mechanism and are not
  involved.
- Links dropped by `--top-links` are *not* frontier work: the budget is per page per
  run, and a resume does not silently spend more of it.
- WebMiner's own `--resume` is a separate tool with its own resume.
