---
title: "Agent Workspace — What It May Read, Where It Writes"
description: "Reference for an agent task's filesystem: the per-task workspace, the default readable root and its boundary, credential and protected paths, automatic task-input copying, and the deliverable format."
tier: procedure
---

# Agent Workspace — What It May Read, Where It Writes

An `agent run` task gets its own filesystem root and may only read inside it. This
page lists those roots, the paths that stay refused, how task inputs get in, and what
a deliverable should look like.

## Quick Start

```bash
# 1. Submit a task; it writes into its own workspace, never the repository root.
browser4-cli agent run "Fetch the top 20 products and save them as CSV"

# 2. Read where the artifacts landed and which files the agent reported.
browser4-cli agent result <task-id>          # → workspace: …/agent/…/<uuid>/workspace

# 3. Inputs outside the workspace are copied in automatically — just name them.
browser4-cli agent run "Use the fields in docs/field-spec.csv and build the sheet"
#    → the agent reads .b4inputs/field-spec.csv (mapped in its user message)
```

## When to Use

- You need to know where an agent's artifacts land, or why a file it "wrote" is not in
  the repository.
- A task names an input file (spec, fixture, CSV) and you want to know whether the agent
  can actually read it.
- An agent reported `PERMISSION_DENIED | code=PATH_DENIED` or listed a blocked input in
  `problems`, and you need the allowed route.
- You are choosing a deliverable format for a data task.

## How It Works

### The per-task workspace

Every task runs in its **own** directory. The path is
`<agent data>/<yyyy>/<MM>/<dd>/<agent-uuid>/workspace.<task-id>` — on a dev-mode
backend `<agent data>` is `~/.browser4/workspaces/<workspace-id>/app-data`.

| Command | Root |
|---|---|
| `agent run "<task>"` | `…/<agent-uuid>/workspace.<task-id>` — this task only |
| `agent run "<task>" --coding` | the same per-task directory, provisioned as a **mirror** of the checkout (source files; `.git` is absent) |
| `agent run --resume <task-id>` | the **interrupted** task's workspace, so the rows/files it already wrote are what the new run continues from |
| `agent run --shared-workspace` | the old shared `…/<agent-uuid>/workspace`, which holds earlier tasks' files |
| `--fresh-workspace` | accepted for compatibility; a per-task workspace is now the default |

Why isolation is the default: files from an earlier run must never appear in this run's
`filesChanged` (a 2026-10-28 run reported a CSV written 36 minutes earlier and another
agent's report as its own deliverables), and a task that lists its workspace must not
wade through a previous task's hundreds of helper scripts.

`agent result <task-id>` names the directory (`workspace`) and lists the files **this
run** reported (`filesChanged`); a run that aborted without reporting them still lists
the files it wrote (from its mutation log), never older ones. The workspace lives on the
machine that runs the backend — when the backend is remote, copy the artifacts from
there (or have the agent return the data inline).

### What it may read by default

`coding.*` file tools resolve paths against that root and refuse everything else
(`browser4.agent.allowExternalAccess` is `false` by default). Relative paths resolve
inside the root; absolute paths, `..` traversal and paths that only *lexically* start
with it (a symlink pointing out) are refused with `PERMISSION_DENIED | code=PATH_DENIED`.

Always refused, even with external access on: `~/.ssh`, `~/.aws`, `~/.gnupg`,
`~/.config/gcloud`, `~/.browser4` (a workspace that really lives under `~/.browser4`
stays accessible for its own tree; a link from inside the workspace into one of these
locations is still refused).

Readable but never writable by the model (`code=PROTECTED_PATH`): `VERSION`, `AGENTS.md`,
`CLAUDE.md`, the root `pom.xml`, `browser4-dependencies/pom.xml`,
`.github/workflows/ci.yml`, and the `.browser4/`, `.github/workflows/` subtrees.

Binary files (`.xlsx`, `.pdf`, `.zip`, images) are not read as text by `coding.read`.

### Task inputs are copied in automatically

Before the run, a path the instruction names — absolute, or repository-relative like
`docs/config.md` / `browser4-tests\…\fixture.xlsx` — that exists **outside** the
workspace is copied into `<workspace>/.b4inputs/`, and the agent is told the
workspace-relative path in the user message:

```text
## Task inputs copied into your workspace
- `C:\work\spec.xlsx` → `.b4inputs/spec.xlsx` (721.9 KB)
```

Limits: regular files only, ≤20 files, ≤10 MB each, ≤25 MB total; credential locations
are never copied; a path already readable from the workspace (including the coding
mirror) is left alone; references that do not exist are ignored. The copies are inputs,
not deliverables — they must not be listed in `filesChanged`.

### The boundary applies to the shell and the browser too

`coding.shell` and `b4.run` used to be escape hatches around the boundary; they are
guarded now:

- `coding.shell` arguments are inspected: a literal absolute path (or a `..` escape)
  outside the workspace is refused with `PERMISSION_DENIED | code=COMMAND_PATH_DENIED`.
  Toolchain locations (`java.home`, `~/.m2`, `~/.gradle`, temp, `%SystemRoot%`), build
  launchers (`mvnw`, `python3`, any `*.exe`/`*.cmd`/`*.ps1`) and credential locations keep
  their existing behaviour. Disable with `-Dbrowser4.agent.shell.workspaceBoundary=off`.
- `b4.run` refuses `file://` URLs and `upload` paths outside the workspace with
  `code=PATH_OUTSIDE_WORKSPACE`.

Both are relaxed by `browser4.agent.allowExternalAccess=true` — that is the operator's
explicit opt-in, not something a task can grant itself.

### Deliverable format

There is **no spreadsheet writer**, and `.xlsx` output is out of scope by decision. A
task that asks to "save it as a spreadsheet" is therefore delivered as **CSV** (opens in
Excel) — and that downgrade is part of the result, not a silent substitution: name it
in `problems` at `system.taskComplete`, for example
`"requested .xlsx; delivered CSV — the runtime has no spreadsheet writer"`.

Deliver tabular data as **CSV** or **JSONL**, and Markdown for prose. An `.xlsx`
*input* is extracted with a script (`coding.shell` + python/openpyxl where available) or
converted to CSV once — `coding.read` refuses binary files. Field specs: compare the
produced columns with the spec's header row and report missing columns in `problems`
instead of quietly shipping a subset.

## Patterns

### Make a repository file readable without widening access

Name it in the task; the engine copies it into `.b4inputs/` before the first model call:

```bash
browser4-cli agent run "Read browser4-tests/pulsar-tests-common/src/test/resources/spec.csv and summarise it"
```

The agent's user message then carries `spec.csv → .b4inputs/spec.csv`, and its first
`coding.read` targets the copy. Nothing else becomes readable.

### Confirm where the artifacts landed

```bash
browser4-cli agent result <task-id>          # workspace + filesChanged
browser4-cli agent result <task-id> --json   # machine-readable, same fields
```

### Disable input copying (audit a task's exact wording)

```bash
BROWSER4_SERVER_OPTS="-Dbrowser4.agent.copyTaskInputs=false" browser4-cli open
```

Now the instruction reaches the model exactly as written and out-of-workspace paths fail
with `PATH_DENIED` — useful to prove a task really needed the input.

### Widen read access for a controlled machine

```bash
BROWSER4_SERVER_OPTS="-Dbrowser4.agent.allowExternalAccess=true" browser4-cli open
```

Credential locations stay refused. `--access` never widens paths.

## Flags

| Flag / property | Applies to | Effect |
|---|---|---|
| `--coding` | `agent run` | mirror the checkout into the per-task workspace (repo-relative paths become readable) |
| `--shared-workspace` | `agent run` | use the shared `<agent data>/workspace` (earlier tasks' files are visible) instead of this task's own directory |
| `--resume <task-id>` | `agent run` | reuse the interrupted task's workspace (its files are what the new run continues from) |
| `--fresh-workspace` | `agent run` | accepted for compatibility; per-task isolation is the default now |
| `--access <preset>` | `agent run` | which *tools* run (`full`/`write`/`read-build`/`read`); never widens paths |
| `browser4.agent.copyTaskInputs` | backend | copy instruction-referenced external files into `.b4inputs/` (default `true`) |
| `browser4.agent.allowExternalAccess` | backend | allow absolute paths outside the workspace (default `false`) |
| `browser4.agent.workspace` | backend | the checkout a `--coding` task mirrors (default: auto-detected) |

## Errors & Recovery

| Symptom | Cause | Fix |
|---|---|---|
| `PERMISSION_DENIED \| code=PATH_DENIED` | the path is outside the workspace | name the file in the task (it is copied in), or use `--coding`, or enable external access |
| `code=PROTECTED_PATH` | governance file or protected subtree | copy what you need elsewhere; these stay unwritable by design |
| `code=SENSITIVE_PATH` | credential location | never copied or read; move the data out of `~/.ssh`/`~/.aws`/`~/.browser4` |
| The agent reports a blocked input in `problems` | the file could not be resolved at all | check the path exists in the checkout, then re-run |
| `coding.read` refuses a file | binary (`.xlsx`, `.pdf`, `.zip`, image) | extract it with a script, or convert it to CSV first |
| Artifacts are "missing" from the repository | they live in the task workspace | use `agent result` to find the workspace and copy from there |
| The agent cannot see a file an earlier task wrote | each task has its own workspace | pass `--shared-workspace`, or name the file so it is copied into `.b4inputs/` |
| A failed run lists no files | the run wrote nothing **of its own** | files from earlier tasks are deliberately excluded; resume (`--resume`) continues the same workspace |

## Related

- [agent.md](agent.md) — `agent run|status|result`, `--access`, `--follow`
- [bulk-collection.md](bulk-collection.md) — collecting many items without losing data
- [shell-quoting.md](shell-quoting.md) — quoting when an agent drives the CLI on Windows
- [config.md](config.md) — `config` command family and server-side properties
