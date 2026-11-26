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
`<agent data>/agent/<yyyy>/<MM>/<dd>/<agent-uuid>/workspace.<task-id>` — on a dev-mode
backend `<agent data>` is `~/.browser4/workspaces/<workspace-id>/app-data`.

| Command | Root |
|---|---|
| `agent run "<task>"` | `…/<agent-uuid>/workspace.<task-id>` — this task only |
| `agent run "<task>" --coding` | an isolated **git worktree** of the checkout: `<repo>/.browser4/worktrees/<task-id>` (it has `.git`; the change is merged back as a patch) |
| `agent run --resume <task-id>` | the **interrupted** task's workspace, so the rows/files it already wrote are what the new run continues from |
| `agent run --shared-workspace` | the old shared `…/<agent-uuid>/workspace`, which holds earlier tasks' files |
| `agent run "<task>" --workspace <dir>` | **`<dir>` itself**, used in place — the caller named the tree |
| `--fresh-workspace` | accepted for compatibility; a per-task workspace is now the default |

Why isolation is the default: files from an earlier run must never appear in this run's
`filesChanged` (a 2026-10-28 run reported a CSV written 36 minutes earlier and another
agent's report as its own deliverables), and a task that lists its workspace must not
wade through a previous task's hundreds of helper scripts. For `--coding` the same
requirement is met with a git worktree instead of a copy: every write is disposable,
`git status`/`git diff` work, and a change that does not pass `coding.verify` is never
merged into the checkout.

**A workspace that is not a git work tree cannot run isolated at all.** The coding task
fails and says so: install git, or set `-Dbrowser4.agent.workspace.isolation=off`. It
does not fall back to the mirror, because mirroring links the checkout's modules into the
scratch workspace — every write would land in your tree with no merge step and no
`merge`/`isolatedWorkspace` in the result.

**Where a coding task's change lands.** `agent result` reports `workspace` = the
**main repository** (the checkout the patch was applied to), `isolatedWorkspace` = the
worktree, and `merge` = `applied` | `refused` | `pending`. `refused` means nothing was
applied — the run's files exist only in `isolatedWorkspace` — so a client must not read
`workspace` as containing the work. A refused merge also fails the task, with the reason
in `problems`/the failure message. The verdict must still describe the tree: a file
written **after** `coding.verify` refuses the merge ("re-run verify for the final change
set"), and a change spanning two ecosystems needs one passing verdict per module.

**Unmerged work is never swept away.** The backend keeps the newest
`browser4.agent.isolation.keep` worktrees and anything younger than
`browser4.agent.isolation.maxAgeDays`, and reclaims the rest — but only those whose
manifest proves the task merged. A tree whose change never reached the checkout (refused,
failed, cancelled, crashed, still running) is retained, and so is one with no manifest to
prove otherwise: a refused run has no patch until a merge is attempted, so that tree is
the only copy of the work. `b4w recover --status` lists what is retained;
`b4w recover --prune --force` deletes it deliberately.

`agent result <task-id>` names the directory (`workspace`) and lists the files **this
run** reported (`filesChanged`); a run that aborted without reporting them still lists
the files it wrote (from its mutation log), never older ones. The workspace lives on the
machine that runs the backend — when the backend is remote, copy the artifacts from
there (or have the agent return the data inline).

### The directory you name (`--workspace`)

```bash
browser4-cli agent run "Summarize every CSV in this folder" --workspace D:/corpus
browser4-cli agent run "Port the helpers into this project" --coding --workspace D:/work/port-target
```

`--workspace <dir>` replaces the per-task directory with a folder **you** name, and the
task runs **in place** there:

- the agent reads and writes `<dir>` directly — no clone, no mirror, nothing merged out of
  it, and Browser4 never creates, archives or sweeps it (retention only touches its own
  per-task workspaces), so whatever is already there is the starting state;
- `<dir>` must be an **existing directory** on the backend machine. A typo is not turned
  into an empty folder the run writes into; the submission fails and names the path. A
  relative path resolves against the shell directory you typed the command in, and a
  leading `~` is your home directory;
- `--coding` keeps its meaning (no browser page, coding tool set) but the run is still in
  place: **no git worktree and no merge**. That is deliberate — a data corpus is a
  legitimate coding target — and it is why the backend's own coding checkout is
  **refused** for a coding task: running in place there would bypass the isolated
  worktree and its verify-gated merge. Drop `--workspace` for that (the default gives the
  task a worktree), or have the operator set
  `-Dbrowser4.agent.workspace.isolation=off` and drop it;
- it cannot be combined with `--resume` (the resume carries its own workspace) or
  `--shared-workspace` (both name the workspace). The CLI refuses the pair before
  submitting, and the backend refuses it too;
- `.browser4/workspace.json` is written **inside** `<dir>` (the task's manifest: what ran,
  what it wrote). That file is how `agent result` and the finish gate know what the run
  changed; the deliverable archive is *not* created next to your folder;
- task inputs still arrive the usual way: a file the instruction names outside `<dir>` is
  copied into `<dir>/.b4inputs/` before the first model call;
- in `agent run -i` the directory applies to **every** task of the session and the banner
  names it;
- writing there is still bounded by `--access`. `filesChanged` means "written by this
  run" — a file you left in the folder beforehand is not claimed.

Do not confuse it with `-Dbrowser4.agent.workspace=<checkout>`: that is a backend-wide
setting naming the checkout coding tasks mirror/worktree from; `--workspace` is this one
task's directory on the CLI.

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
`.github/workflows/ci.yml`, and the `.browser4/`, `.github/workflows/` subtrees. The shell applies
the same split — it refuses the **write** shapes there (a redirection target, a mutating command's
path argument, `cd` into the subtree) and allows reads, so `type .browser4/…` and `coding.read` both
reach the previous task's ledger.

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
mirror) is left alone; references that do not exist are ignored; a file named twice (in
two spellings) is copied once. The copies are inputs,
not deliverables — they must not be listed in `filesChanged`.

**What counts as a reference.** The instruction is prose, not a command line, so the
engine looks for path-shaped text instead of applying shell splitting rules. Tolerated:

| Form | Example |
|---|---|
| Windows drive path (either separator) | `D:\Backup\Data\fields.xlsx`, `D:/Backup/fields.xlsx` |
| Windows UNC share | `\\fileserver\specs\fields.xlsx` (resolves on Windows only) |
| POSIX absolute path | `/home/me/fields.xlsx` |
| `~` home shorthand | `~/fields.xlsx` (`~user/…` is **not** expanded) |
| Relative to the repo / backend cwd | `docs/config.md`, `browser4-tests\…\fixture.xlsx` |
| Local `file:` URL | `file:///D:/Backup/fields.xlsx`, `file:///home/me/fields.xlsx` |
| Quoted — required for spaces | `'D:\My Docs\fields.xlsx'`, `"…"`, `“…”`, `‘…’` |
| CJK punctuation around it | `字段：D:\Backup\fields.xlsx`, `字段（…）`, `读取 docs/config.md，然后总结` |

A relative reference is resolved against the workspace mirror, then the repository root,
then the backend's working directory — **not** the CLI's current directory.

Not accepted, on purpose: a **bare file name** with no directory (`fields.xlsx` — give it
a directory or an absolute path), a **directory** (only regular files are copied),
environment variables (`%USERPROFILE%\…`, `$HOME/…`), a **remote** `file://host/…` URL,
percent-encoded URLs (`%20` is not decoded), globs (`*.xlsx`), `@file` / `-flag`
arguments, and anything under a credential location.

Paths with spaces need the quotes *inside* the task text — the shell strips its own
quoting before the CLI sees the argument:

```bash
browser4-cli agent run "字段表在 'D:\My Docs\fields.xlsx'，读取并总结"
```

When nothing was copied the backend logs one `No task input materialized: N candidate
reference(s)` line, and a `Task input skipped/refused` line for each reference that hit a
limit or a credential location (per-reference misses stay at `debug`). That line is the
first thing to check when the model reports `PERMISSION_DENIED | code=PATH_DENIED` for a
file the task did name.

### The boundary applies to the shell and the browser too

`coding.shell` and `b4.run` used to be escape hatches around the boundary; they are
guarded now:

- `coding.shell` arguments are inspected: a literal absolute path (or a `..` escape)
  outside the workspace is refused with `PERMISSION_DENIED | code=COMMAND_PATH_DENIED`.
  Toolchain locations (`java.home`, `~/.m2`, `~/.gradle`, temp, `%SystemRoot%`), build
  launchers (`mvnw`, `python3`, any `*.exe`/`*.cmd`/`*.ps1`) and credential locations keep
  their existing behaviour. This inspection is advisory (fast-fail); the OS sandbox
  (`browser4.agent.sandbox`) is the enforcement boundary. Disable the check with
  `-Dbrowser4.agent.shell.workspaceBoundary=off`.
- `b4.run` refuses `file://` URLs, `upload` paths and CLI **write destinations** outside
  the workspace — `--output-file`, crawl `--out`, `har stop --path` and the `-o` alias all
  make the CLI write straight to disk — with `code=PATH_OUTSIDE_WORKSPACE`.
- `b4.run` also refuses `code`, `tool call` and `plugin` while the task runs narrowed
  (`read`, `read-build`, `write`): those commands hand the request to the CLI subprocess,
  which carries no preset, so the backend would answer with its own server-wide policy.
  Under `full` they stay available; the refusal is `code=COMMAND_NOT_ALLOWED` naming the
  preset.

The path rules above are relaxed by `browser4.agent.allowExternalAccess=true` — that is
the operator's explicit opt-in, not something a task can grant itself, and it is
**deprecated** and advisory: it relaxes the string checks above, not the OS sandbox
(`browser4.agent.sandbox`). The `code` / `tool call` / `plugin` rule is driven by the
preset instead: only `access=full` allows them.

### Other tasks' workspaces

Writing into **another task's workspace** is allowed by default, because handing a deliverable to the
next task (or finishing a previous one's work) is the normal collaboration shape:

```bash
browser4-cli agent run "Finish the CSV in workspace.task-8f3a: add the currency column, keep its schema"
browser4-cli agent run "Move today's report.json from workspace.task-8f3a into this task's deliverables"
```

What is in scope is derived from the layout, never from the name alone:

- `<agent data>/agent/<yyyy>/<MM>/<dd>/<agent-uuid>/workspace` — the legacy shared workspace;
- `<agent data>/agent/<yyyy>/<MM>/<dd>/<agent-uuid>/workspace.<task-id>` — a per-task workspace;
- the newest `browser4.agent.taskWorkspaceWriteDays` day directories (default `7`), at most 64 roots
  (`TaskWorkspaces.MAX_ROOTS`), so a run start never walks months of history.

What stays out of scope, deliberately:

- **`workspace.archive`** — the deliverables of aborted tasks live next to the workspace and are not
  a workspace a run may rewrite;
- **`<agent data>/agent/skills`** — deploying an executable skill is a privilege escalation, refused
  exactly as it is for `b4.run`;
- **isolated coding worktrees** (`<repo>/.browser4/worktrees/<task-id>`) — an unmerged worktree is
  the only copy of a change that has not passed `coding.verify`. Hand one over explicitly with
  `--add-dir <worktree>`.

The access preset still decides whether anything may be written, so `--access read` keeps other
tasks' workspaces read-only, and the credential rails (`~/.ssh`, `~/.aws`, …) are untouched. The OS
sandbox is given the **day directory** of each granted workspace, so a confined child needs one
label/ACE per day rather than one per task; the string layer keeps the exact list.

Costs to know about: a run can now overwrite or delete another task's deliverables, so `filesChanged`
no longer proves the files it names were *produced* by that run — it proves they were **written by**
it. The set is resolved when the policy is applied, so a task that starts *later* is not in it
(`--add-dir` covers that case). `coding.permissions` prints the roots this task actually got.

Restore the workspace-only write boundary with:

```bash
BROWSER4_SERVER_OPTS="-Dbrowser4.agent.allowTaskWorkspaceWrites=false" browser4-cli open
```

### Extra folders granted with `--add-dir`

A task's boundary is its own workspace plus the other task workspaces above (and its mirrored module roots, the checkout those mirrors come from — writable, so a self-development task edits the code it runs from — and the readable roots). To let a run work on a folder that lives somewhere else, grant it explicitly:

```bash
browser4-cli agent run "Port the helpers in ../shared-libs into this repo" --coding --add-dir ../shared-libs
browser4-cli agent run "Summarize every file in the corpus" --coding --add-dir D:/corpus --add-dir D:/archive
```

- **Repeatable** — one `--add-dir` per folder. A relative path resolves against the directory you typed the command in, and a leading `~` is your home directory.
- **It must already exist on the backend machine.** The grant is never created implicitly, and a missing path (or a file) fails the submission with the reason instead of starting a task whose grant means nothing.
- **Scope, not power.** The agent may read, write and use it as a `coding.shell` working directory exactly like its workspace, but `--access` still decides what is allowed — `--access read` keeps an added folder read-only — and the OS credential stores (`~/.ssh`, `~/.aws`, `~/.gnupg`, `~/.config/gcloud`) stay refused.
- **Task-scoped.** The grant is reset when the task ends and `--resume <id>` inherits it. `coding.permissions` lists the folders and the task prompt names them (absolute paths, as given), so the model does not have to guess.
- **Available mid-session too.** In `agent run -i`, `/add-dir <dir>` grants the following tasks a folder without restarting the session (`/dirs` lists the grants, `/dirs clear` drops them). The session banner shows the grants it started with.
- **The sandbox agrees.** With `-Dbrowser4.agent.sandbox=windows-acl` the sandbox account is granted the same folders, so an OS denial cannot contradict a step the boundary allowed.

### `--unrestricted` — no boundary at all

A task started with `--unrestricted` runs with the current OS user's own permissions:

```bash
browser4-cli agent run "Move the cache into D:/corpus and fix the symlinks" --coding --unrestricted
```

The boundary described above is replaced with a pass-through one (no workspace scope, no
readable-root list, no need for `--add-dir`), the protected rails are dropped, and
the OS sandbox (`browser4.agent.sandbox`) is **not** applied — child processes inherit the
backend's own token, so a command reaches exactly what you could reach from the same terminal.
The shell's command allow-list goes too, so programs it never knew — a PowerShell cmdlet included —
run as written (the list is not a boundary: the same body already ran unexamined through
`powershell -File`). An inline-script body (`powershell -Command …`, `bash -c …`) is not judged by
that list in **any** mode, and a shape the checker cannot decompose, `for` loops included, is handed
to the platform shell in every mode.
The OS credential stores (`~/.ssh`, `~/.aws`, `~/.gnupg`, `~/.config/gcloud`) are the exception:
they stay refused, because "my permissions" must not become "my secrets" in a model's context.
The OS is then the only limit: on Windows with `windows-acl` configured, that confinement is what
you are switching off, so prefer an added folder when the task only needs one more directory.
The flag is scoped to the task (the backend default is restored afterwards) and inherited by
`agent run --resume <id>`; [agent-access.md](agent-access.md#unrestricted-tasks--agent-run---unrestricted)
covers the capability side.

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
`coding.read` targets the copy. Nothing else becomes readable. The import runs under
every `--access` preset, `read` included — `.b4inputs/` is the task's input sandbox, not
the user's files.

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
| `--coding` | `agent run` | run in an isolated **git worktree** of the checkout (has `.git`); the change is merged into the main repository as a patch only when `coding.verify` passes |
| `--shared-workspace` | `agent run` | use the shared `<agent data>/workspace` (earlier tasks' files are visible) instead of this task's own directory |
| `--workspace <dir>` | `agent run` | run this task **in place** in an existing directory you name: no per-task copy, no worktree, no merge, nothing created/archived/swept; the backend's own coding checkout is refused for `--coding`; incompatible with `--resume` and `--shared-workspace` |
| `--resume <task-id>` | `agent run` | reuse the interrupted task's workspace (its files are what the new run continues from) |
| `--fresh-workspace` | `agent run` | accepted for compatibility; per-task isolation is the default now |
| `--access <preset>` | `agent run` | which *tools* run (`full`/`write`/`read-build`/`read`); never widens paths |
| `--add-dir <dir>` | `agent run` | grant an **existing** folder outside the workspace: readable, writable and usable as a shell working directory for this task (repeatable; scope only — the preset still bounds what may be done there, and `--resume` inherits it) |
| `browser4.agent.allowTaskWorkspaceWrites` | backend | whether a run may write other tasks' workspaces (default `true`; `false` restores the workspace-only boundary) |
| `browser4.agent.taskWorkspaceWriteDays` | backend | how many of the newest day directories the scan looks at (default `7`, clamped to `1..400`) |
| `browser4.agent.copyTaskInputs` | backend | copy instruction-referenced external files into `.b4inputs/` (default `true`) |
| `browser4.agent.allowExternalAccess` | backend | deprecated; advisory string-layer widening for absolute paths outside the workspace (default `false`) |
| `browser4.agent.workspace` | backend | the checkout a `--coding` task mirrors (default: auto-detected) |

## Errors & Recovery

| Symptom | Cause | Fix |
|---|---|---|
| `PERMISSION_DENIED \| code=PATH_DENIED` | the path is outside the workspace | name the file in the task (it is copied in), use `--add-dir <folder>` for a folder the task must work in, use `--coding`, or enable external access |
| `--workspace '<…>' is not an existing directory` | the folder is missing on the backend machine (or the path is a file) | create it first — a workspace is never created implicitly — or fix the path |
| `--workspace cannot be combined with --resume` / `--shared-workspace` | two flags named the workspace | keep exactly one: `--workspace <dir>`, `--resume <id>`, or `--shared-workspace` |
| `--workspace '<…>' is the backend's coding checkout` | a `--coding` task asked to run in place in the checkout | drop `--workspace` (isolated worktree), or opt out backend-wide with `-Dbrowser4.agent.workspace.isolation=off` |
| `code=PROTECTED_PATH` | governance file or protected subtree | copy what you need elsewhere; these stay unwritable by design |
| `code=SENSITIVE_PATH` | credential location | never copied or read; move the data out of `~/.ssh`/`~/.aws`/`~/.browser4` |
| The agent reports a blocked input in `problems` | the file could not be resolved at all | check the path exists in the checkout, then re-run |
| `coding.read` refuses a file | binary (`.xlsx`, `.pdf`, `.zip`, image) | extract it with a script, or convert it to CSV first |
| Artifacts are "missing" from the repository | they live in the task workspace | use `agent result` to find the workspace and copy from there |
| A write into another task's workspace is refused | it is outside the window (older than `taskWorkspaceWriteDays`, past the 64-root cap), it is `workspace.archive`/`agent/skills`, or the run is a coding worktree | `--add-dir <path>` grants that one folder; `coding.permissions` shows what the task got |
| The agent cannot see a file an earlier task wrote | reads of other workspaces are allowed, but only where the task looks | name the absolute path (`coding.permissions` lists the writable ones), or pass `--shared-workspace` |
| A failed run lists no files | the run wrote nothing **of its own** | files from earlier tasks are deliberately excluded; resume (`--resume`) continues the same workspace |

## Related

- [agent.md](agent.md) — `agent run|status|result`, `--access`, `--follow`
- [bulk-collection.md](bulk-collection.md) — collecting many items without losing data
- [shell-quoting.md](shell-quoting.md) — quoting when an agent drives the CLI on Windows
- [config.md](config.md) — `config` command family and server-side properties
