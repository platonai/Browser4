---
title: "Background Crawl Tasks"
description: "Reference for crawl tasks after submission: crawl status/result/cancel/clear/list, the stdout/stderr contract, the global --json envelope, and crawl resume with its checkpoint semantics."
tier: catalog
---

# Background Crawl Tasks

## Overview

A crawl submitted with `--background` returns a task ID immediately; these
subcommands manage and monitor it afterwards. They also work for a crawl the CLI
stopped waiting for and for any id the local task store still tracks. Every
answer comes from the backend record — the CLI never invents progress.

## Quick Index

| Subcommand | What it does |
|---|---|
| `crawl status <task-id>` | Raw record on **stdout**; the one-line summary (`Created` / `Processing` / `OK`, pages found, errors) and the interrupted / resume notes on **stderr** |
| `crawl result <task-id>` | Same split: raw record on **stdout**, summary and the "still processing" / resume notes on **stderr**.  A task still `Processing` returns its partial record with `status: Processing`, so `result` and `status` both work as a poll.  `--verbose` adds the per-page provenance rows on stderr |
| `crawl cancel <task-id>` | Cancel a running or queued task; it transitions to TIMEOUT and stays visible in `crawl list` until cleared or expired by TTL.  `{"cancelled": false}` means no running worker was found — the record is still queryable.  A cancellation does **not** throw the work away: the checkpoint is kept, the record reports what is left, and `crawl resume <task-id>` continues it |
| `crawl resume <task-id>` | Continue an interrupted task from its checkpoint — see [`crawl resume`](#crawl-resume) below |
| `crawl clear` | Remove completed, cancelled and failed tasks from the store; running tasks are not affected.  A checkpoint that still has work left is **kept**, so a cleared timed-out task can still be resumed by id, and interrupted tasks are not touched at all (they are not finished tasks); `crawl clear --all` is the explicit way to discard resumable state — after it, an interrupted task can only be submitted again |
| `crawl list` | List all tracked crawl tasks across all sessions.  This is **housekeeping state, not an audit log**: listing prunes entries the backend no longer knows and entries older than 24 h, and prints what it removed.  Backend records and crawl checkpoints are never affected; `crawl result <task-id>` still works for a pruned id |

```bash
browser4-cli crawl status <task-id>
browser4-cli crawl result <task-id>
browser4-cli crawl cancel <task-id>
browser4-cli crawl clear --all
browser4-cli crawl list --status interrupted
```

## Task records: the stdout/stderr contract

`crawl status` and `crawl result` follow one rule: **stdout carries only the backend's raw JSON
record**; the human summary and the interrupted / resume / still-processing notes go to **stderr**
(`-q`/`--quiet` and JSON mode suppress that summary — with `--quiet` the record goes too, so the
command prints nothing).  A script reads **stdout only**.

`--json` is **global**: write it **before** the command (`browser4-cli --json crawl status <id>`).
stdout is then a single-line envelope (`{"status":"ok","command":"crawl-status","output":{…}}`)
instead of the raw record, carrying `output.task_id`, `resumable`, `remaining`,
`skipped_already_fetched`, `resume_count`, `resumed_from` and `raw` for `status` (`task_id` and
`raw` for `result`).  Do not write `crawl status --json <id>`: the flag then swallows the id.

```bash
# Poll a background crawl: loop while the record still says Created/Processing.
id=550e8400-e29b-41d4-a716-446655440000
while :; do
  rec=$(browser4-cli crawl status "$id" 2>/dev/null)   # drop the stderr summary
  grep -qE '"status" *: *"(Created|Processing)"' <<<"$rec" || break
  sleep 10
done
printf '%s\n' "$rec"    # terminal record: OK, Request Timeout, Interrupted, …
```

The wire values are `ResourceStatus` display text — `Created`, `Processing`, `OK`,
`Request Timeout`, `Internal Server Error`, `Not Found`, plus `Interrupted` (the worker died with
the backend — see [`crawl resume`](#crawl-resume)); one vocabulary, defined by `CrawlStatus` on the
backend.  Lifecycle labels: `queued`, `processing`, `completed`, `failed (timeout)`,
`failed (error)`, `failed (not found)`, `interrupted`.

A record also carries the resume bookkeeping, whether or not the task was ever interrupted:
`resumable` (a checkpoint with work left), `remaining` (URLs still to fetch),
`skippedAlreadyFetched` (URLs restored instead of re-requested), `resumeCount`, and `resumedFrom`
(the interruption a resumed run continued from).  `crawl status` prints them and emits them as
`--json` fields; `Interrupted` is **terminal** — `crawl status` never waits on it.

> **While a task runs, the progress counts only grow.**  `pagesFound` counts every page collected
> so far across *all* seeds and `pagesExpected` / `failedPages` never fall back once reported (a
> poller can treat a decrease as a bug); `pagesExpected` covers the seeds already finished, so on a
> multi-seed crawl it climbs as seeds settle.

## `crawl list` flags

| Flag | Type | Description |
|---|---|---|
| `--limit` | int | Show at most N tasks (latest first) |
| `--offset` | int | Skip the first N tasks |
| `--status` | string | Filter by status: `completed`, `running`, `failed`, `queued`, `interrupted`, `not found`.  `failed` also matches a run whose label is `partial failure (<ok> of <expected> pages ok)` — a crawl that finished with pages missing keeps `status = OK` on the backend (so a poller stops) while the CLI records the partial failure |
| `--since` | time | Show only tasks submitted since a relative time (e.g. `1h`, `30m`, `1d`) |
| `--clear` | bool | Remove all tracked tasks from the list |

## crawl resume

Continue an interrupted crawl from its checkpoint — after a backend restart, a crash, a `SIGKILL`,
a `crawl cancel`, or a task the server-side `--timeout` budget cut off.

```bash
browser4-cli crawl resume <task-id>
browser4-cli crawl resume <task-id> --bg
browser4-cli crawl resume <task-id> --retry-failed
browser4-cli crawl resume <task-id> --force
```

| Flag | Type | Description |
|---|---|---|
| `--force` / `-f` | bool | Resume even when the task's record says it completed successfully (a no-op when nothing is left; combine with `--retry-failed` to fetch the URLs it lost) |
| `--retry-failed` | bool | Re-submit the URLs that failed terminally before, instead of keeping them failed |
| `--bg` / `--background` | bool | Start the resume and return immediately; poll with `crawl status` / `crawl result` |
| `--verbose` | bool | Mark each row as fetched-in-this-run or restored-from-checkpoint |

The task keeps its id — `crawl resume` continues *that* task.  Already-fetched URLs are not
requested again (counted in `skippedAlreadyFetched` and restored into the result), in-flight URLs
are re-submitted with a fresh delivery-attempt budget, terminally failed URLs stay failed unless
`--retry-failed`, and the discovered frontier is followed so a `--depth >= 1` crawl continues where
it stopped instead of restarting at the seeds.  The result is the union of the runs —
`pagesFound + failedPages.size == pagesExpected` still holds and each row carries `run` /
`fetchedAt` / `restoredFromCheckpoint` — and a rejected resume (already completed, nothing left, no
checkpoint on disk) prints the reason and exits non-zero; a live worker is refused.

## Checkpoints

The **checkpoint** on disk holds the input contract, the URLs that succeeded, those that failed
terminally, those still in flight, and the links discovered but never queued.

Automatic resume at backend startup is **off by default** (`crawl.autoResume=false`): a restart is
not consent to keep hitting sites.  Checkpoints live under `~/.browser4/data/crawl/checkpoints/`,
survive the task store's LRU and TTL, and are discarded by `crawl clear --all`.  The full contract
— file layout, write cadence, crash safety, the end-to-end SIGKILL test — is in
[docs/crawl-checkpoint-resume.md](../../../docs/crawl-checkpoint-resume.md).

## See also

- [crawl.md](crawl.md) — the crawl command itself: flags, output formats, error handling
- [Crawl checkpoint & resume](../../../docs/crawl-checkpoint-resume.md) — checkpoint layout,
  write cadence, resume semantics, `crawl.autoResume`
