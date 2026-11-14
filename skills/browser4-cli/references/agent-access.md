---
title: "Agent Access — Presets, Refusals, Operator Approval"
description: "Reference for an agent task's access preset: what full/write/read-build/read grant, the PERMISSION_DENIED code catalogue, and how coding.requestAccess gets a refused effect approved without overriding rails."
tier: procedure
---

# Agent Access — Presets, Refusals, Operator Approval

An `agent run` task executes its coding tools under one access **preset**. The preset
decides which capabilities exist at all; when a step needs more, the agent asks for it
with `coding.requestAccess` and an operator approves it out of band. Nothing here widens
the *path* boundary — that is [agent-workspace.md](agent-workspace.md).

## Quick Start

```bash
# Read + build only: the agent may inspect and run Maven, never edit or shell out.
browser4-cli agent run "Read pom.xml and report the failing module" --coding --access read-build

# Narrow preset; a refused write becomes a pending request the operator can approve.
browser4-cli agent run "Fix the failing test" --coding --access read-build --follow
```

## When to Use

- You want a task to be read-only (audit, report, diagnosis) rather than mutating.
- A step was refused and you need to know which capability was missing and where it is allowed.
- A task keeps stalling on refusals it cannot work around, and you want to widen it only as far
  as necessary.
- You are embedding the agent and need the refusal-code contract.

## How It Works

### The presets

`--access` sets the preset for a single task; omit it to use the backend-wide
`browser4.agent.access` default (`full` when unset):

| Preset | Read workspace | Write files | Project build (`mvnBuild`/`verify`) | Shell / arbitrary code |
|---|---|---|---|---|
| `full` *(default)* | ✓ | ✓ | ✓ | ✓ |
| `write` | ✓ | ✓ | ✓ | ✗ |
| `read-build` | ✓ | ✗ | ✓ | ✗ |
| `read` | ✓ | ✗ | ✗ | ✗ |

An unknown `--access` value is rejected with an error instead of silently falling back — a
typo must not quietly grant `full`.

### Unrestricted tasks — `agent run --unrestricted`

`--unrestricted` is the escape hatch for a task that genuinely needs everything the **current
OS user** can do: a one-off repair on a dev box, a migration that must reach services outside the
workspace, a task that installs tooling.

```bash
browser4-cli agent run "Upgrade the pinned Maven plugins and run the full build" --coding --unrestricted
```

It is not another preset — it is applied **on top of** `--access` and replaces the rails with
pass-through ones for that one task:

| Rail | Normally | With `--unrestricted` |
|---|---|---|
| preset gate | `--access` / backend default | `full` — every tool domain |
| path boundary | workspace + readable roots + `--add-dir` | every path the OS user can reach |
| protected paths | refused | allowed (the OS still governs) |
| shell's own rule (command allow-list) | applied | **not** applied — programs the list never knew (`Get-Content`, `wmic`) run as written; shapes it cannot decompose are handed to the platform shell in every mode |
| OS credential stores (`~/.ssh`, `~/.aws`, `~/.gnupg`, `~/.config/gcloud`) | refused | **still refused** — see below |
| network, destructive commands | per preset | allowed |
| OS sandbox (`browser4.agent.sandbox`) | applied | **not** applied — plain child processes |
| runtime-protection vetoes (`taskkill` on the backend, `b4w build/stop`, `bin/build.ps1`, `mklink`/`ln`/`mount`) | refused | **still refused** |

The shell's remaining rule is the command allow-list, and it only looks like a rail: it does not
confine a process — the same script body already ran unexamined through `powershell -File`, and
`python -c` is on the list — so refusing `powershell -Command "Get-Content x"` only cost rounds
(2026-11-14 run). Command shapes the checker cannot decompose (`for` loops, blocks) are handed to
the platform shell in every mode, not only under this flag. Every row marked **still refused** above
keeps refusing, and the shell's `hint=` says `no approval lifts this` instead of offering a request
that cannot be granted.

What it does **not** do:

- It never widens another task: the policy is applied when the task starts and the backend
  default is restored when it ends.
- It does not escalate the OS user, and it does not turn "my permissions" into "my secrets":
  `~/.ssh`, `~/.aws`, `~/.gnupg` and `~/.config/gcloud` stay refused whatever the flag says, so
  a task cannot read a private key into its context. Nothing runs as root/Administrator that
  would not from the same shell.
- It never lets the task destroy the runtime it runs inside: the path/credential string guards are
  gone, but a `for` loop body still may not `taskkill` the backend, run `b4w build`, or rewrite
  the workspace topology with `mklink`/`ln`.
- It is inherited by `agent run --resume <id>` — a continued instruction must not lose the grant
  it was written against — and recorded on the task row (`unrestricted`) so `agent status` reports
  it and `coding.permissions` states it (`Unrestricted: YES`, with the sandbox line reading `off`).
  **A resume cannot drop the grant**: there is no `--no-unrestricted`, so going back to the rails
  means starting a new task rather than continuing that one.
- It is audited: the first time the policy is applied the backend writes a `coding.access`
  approval event with `source=flag`.

`--add-dir` has no effect next to `--unrestricted` (there is no boundary for an added folder to
join); the flag is harmless, not additive — the whole filesystem is already in scope.

Prefer the narrow tools when the task needs *one* extra capability: `--access read-build` /
`write`, `--add-dir <path>`, or an operator approval through `coding.requestAccess`.
`--unrestricted` is all-or-nothing and belongs in scripts, not in an agent's default invocation.

### Which tool domains the presets gate

The presets bound **workspace power**, so they gate the domains that can write, execute or
deploy:

| Surface | Gate |
|---|---|
| `coding.*` | per method: reads → `read`; build → `read-build`; writes → `write`; shell/exec → `full` |
| `skill.list` / `skill.info` / `skill.activate` / `skill.readDocumentation` / `skill.readReference` | `read` |
| `skill.run` / `skill.runScript` | `full` (a skill executes its own code) |
| `skill.install` / `skill.uninstall` / `skill.reload` | never exposed to `agent run`: deploying a skill is an operator action, and `b4.run` refuses the same commands |
| `tab` / `browser` / `agent` / `b4` / `system` / `memory` / `batch` / `driverEx` / `experience` | never gated — browsing has to work under every preset |
| any other domain (plugins, future tools) | `full` until it is classified — a new surface fails closed |

Only `--allow-external-access` is about paths. The import of a file named in the task
prompt (`.b4inputs/`) runs under **every** preset, `read` included: `.b4inputs/` is the
platform's input sandbox, not the user's files or project. `read` still means "no changes
to user files or the project" — the copies are task-scoped inputs the model can only read,
and a read-only task that could not receive its inputs would simply fail.

### Readable roots — Browser4's own folders

The presets bound workspace **power**. Reading is wider on purpose: Browser4's own folders are
readable even though they live outside the task workspace, because that is where the agent's
logs, task data, temp files and checkout are.

| Root | Default |
|---|---|
| Browser4 data root (`app.data.dir`, else `~/.browser4`) | readable — `logs/`, `data/`, `memory/`, the per-task `agent/…/workspace.<id>` trees and Browser4's own `tmp/` |
| Source checkout the backend runs from | readable when one is detected (a packaged runtime has none) |
| `browser4.agent.readPaths` | operator-added folders: `;`-separated, `~` expanded |

```bash
# Add folders for every task on this backend
BROWSER4_SERVER_OPTS="-Dbrowser4.agent.readPaths=D:/corpus;%TEMP%" browser4-cli open
```

These roots are **read-only**. Writes, deletes, copies and moves still happen only in the workspace
and its mirrored modules; a write into a readable root is refused and counted as `READ_ONLY`. The
access preset does not change them — `read` and `full` see the same readable roots, because reading
logs is not workspace power — and `coding.permissions` prints the list in effect while
`coding.explain(path=…)` dry-runs one path.

A readable root that also sits on the sensitive list (the data root does — it is `~/.browser4`) is
readable by design: that is the relaxation. The OS credential stores stay refused whatever is
configured — adding `$HOME` as a readable root does **not** expose `~/.ssh`.

### Added folders — `agent run --add-dir`

`--add-dir <folder>` grants one task extra folders besides its workspace. Unlike the readable roots
these are **read-write scope**, and they can be a `coding.shell` working directory — but the grant
is still scope, not power:

| Question | Answer |
|---|---|
| May the task read/write there? | yes under `full`/`write`; a `read` or `read-build` preset keeps the folder **read-only** (same `READ_ONLY` refusal as the workspace) |
| May it run commands there? | only when the preset grants the shell (`full`) — the folder does not unlock execution |
| Which folders are accepted? | an **existing** directory on the backend machine, absolute; `~` is expanded, credential stores are refused; a missing path fails the submission |
| How long does it last? | this task only — reset when it ends; `--resume <id>` inherits the interrupted task's folders |
| Can it change mid-session? | yes: in `agent run -i`, `/add-dir <folder>` grants the following tasks a folder and `/dirs [clear]` lists or drops the grants |
| Where does the agent see them? | the task prompt names them and `coding.permissions` lists them |

The OS sandbox is told the same folders (`browser4.agent.sandbox=windows-acl` provisions them as
writable roots), so an allowed step is not denied by the OS afterwards.

### Refusal codes

A denied call fails like any other failed step (the task still ends whatever its own
completion check decides), and the step text names the refusal structurally:

```text
PERMISSION_DENIED | code=TOOL_DENIED | tool=coding.shell | detail=not permitted under access preset 'read-build' | hint=re-run with access=full or access=write …
```

`code` is one of `TOOL_DENIED` (the preset does not grant the method), `READ_ONLY`
(workspace is read-only), `EXEC_DISABLED` (no shell at all), `COMMAND_NOT_ALLOWED` (command
outside the shell's tier), `PATH_OUTSIDE_WORKSPACE` / `PATH_DENIED` (a path the file tools
refused), `SENSITIVE_PATH` (credentials), `COMMAND_PATH_DENIED` (a command argument naming
credentials), `PROTECTED_PATH` (governance file or protected directory, such as
`.browser4`), and `SANDBOX_DENIED` (the active OS sandbox refused the command; the step
also carries `needed=…` and the raw OS evidence stays in the log). A refusal is structural:
it must not be retried with different quoting or an interpreter. `coding.permissions` prints
the same view MCP clients get — the preset, the allowed routes and the protections.

There is **no syntax gate**. A single-line Windows `if` **is** parsed (`if [not] exist <path> <cmd>`,
`if [/i] [not] <a>==<b> <cmd>`, `if [not] defined <var> <cmd>`, `if [not] errorlevel <n> <cmd>`,
optionally `(<cmd>)` with one `else (<cmd>)`) and its payload is validated like a top-level command.
What is not parsed — `for` loops, `else if`, nested blocks, `&`/`|` inside a block, `goto`/`call`
payloads — is handed to the platform shell **verbatim**, and the shell reports its own parse error.
The vetoes that do not need the decomposition still refuse (blocked patterns, the command tier,
credentials and protected paths, and killing or rebuilding this backend), and those are ordinary
policy refusals. Use a script file run by path (`.\run.cmd`, `./run.sh`) when the shell's parse
error is in the way.

### Operator approval: `coding.requestAccess`

A refusal is not a dead end, and it never blocks the task. The agent asks for the missing
effect once:

```text
coding.requestAccess(effect="fs.write", target="src/main/kotlin/Foo.kt", reason="fix the import", ttlSeconds=600)
```

The call returns immediately with the pending request — it never waits for a human:

```json
{"requestId": "req-1", "state": "pending", "effect": "fs.write", "target": "…/Foo.kt",
 "needed": "fs.write at …/Foo.kt", "alternatives": ["continue with the tools the preset grants",
 "re-run with access=full or access=write"]}
```

Rules:

- Asking is a read: every preset exposes `coding.requestAccess`, including `read`.
- The same `effect + target` is deduplicated — a retrying model gets the existing
  `requestId` instead of flooding the queue. Once a grant expires, a fresh request is
  allowed.
- The agent keeps working with the tools the preset grants while the request is pending.
- An approval is **effect-level**: `fs.write` unlocks mutating methods (and the project
  build, which needs the narrower `read-build` grant); `fs.exec` / `proc.spawn` unlock the
  shell and arbitrary code. The target is recorded in the audit and the grant's scope, not
  enforced per path — the layers that know the path still enforce the rails below.
- `ttlSeconds` bounds the grant; omitted, it lasts until the task ends. Expiry is lazy:
  the next decision (or `coding.permissions`) drops the grant and records
  `ACCESS_EXPIRED`, so no timer runs in the background.

Three operator channels issue the same approval:

```bash
# REST, for dashboards and scripts
curl http://localhost:18182/api/agent/<taskId>/access                     # pending + grants
curl -X POST http://localhost:18182/api/agent/<taskId>/access/<requestId>/approve
curl -X POST "http://localhost:18182/api/agent/<taskId>/access/<requestId>/deny?reason=..."
```

```text
# Interactive CLI (`agent run -i`) — handled locally, never sent to the model
/access [task-id]                 show pending requests and active grants
/approve <request-id> [task-id]   approve one pending request
/deny <request-id> [task-id]      deny one pending request
```

Without a TTY the CLI cannot ask, so it only informs: when the session ends it prints the
requests still pending (nothing is printed when there are none), and the run is never
blocked waiting for an answer.

```json
// Strategy file <workspace>/.browser4/access-policy.json — pre-approvals for an
// unattended job, loaded once when the task starts
{"grants": [{"effect": "fs.write", "target": "src/generated", "ttlSeconds": 3600, "reason": "codegen"}]}
```

`target` is a command line for `fs.exec`/`proc.spawn`, a host for `net.connect`, otherwise a
workspace-relative path. `.browser4` is a protected directory, so the job cannot rewrite its
own permissions.

An approval that would cross a rail is converted to a denial (`409`), and the request's
`state` says so — the operator cannot force it through either. The same rails apply to every
channel, including strategy-file entries.

Rails that an approval can never cross:

- `denyTools` (embedder-level) — an explicit denial is a rail, not a capability. A refusal
  names it: `detail=excluded by denyTools under access preset 'full'`.
- Protected paths (governance files, `.browser4`, `.github/workflows`) and sensitive paths
  (credentials) are never approved. The Browser4 data root is already readable through the
  readable roots (below) — an approval cannot widen anything else on the sensitive list.
- The workspace boundary while `allowExternalAccess=false`: a target outside the workspace
  is refused at approval time.
- The shell's command whitelist (safe commands plus `DEV_COMMANDS`): an approval can pick
  among them, never add an arbitrary program.
- The OS sandbox: it keeps enforcing the process boundary whatever was approved.

Every step of the flow is audited with its source (`rest`, `file`, `cli` or the unattended
`default`): `ACCESS_REQUESTED`, `ACCESS_APPROVED`, `ACCESS_DENIED`, `ACCESS_EXPIRED`.

### The unattended default

A run nobody watches cannot answer an approval. When no TTY is attached (a script, a CI
job, a submitted task), the task derives the **unattended default** instead of the
server-wide preset: `full` with outbound network allowed — the alternative is every
unattended write failing on its first call. The rails above are untouched, execution still
passes the command whitelist, and the derived default is audited once as
`ACCESS_APPROVED(source=default)`.

| `browser4.agent.access.unattended` | Derived preset |
|---|---|
| unset | `full`, network on |
| `full` / `write` / `read-build` / `read` | that preset, network on |
| `deny` | no workspace power: every `coding.*` / `skill.*` method refused, network off, browsing still available |

A watched `agent run -i` session on a TTY keeps `browser4.agent.access`; an explicit
`--access` always wins over the unattended default.


### Two limits worth knowing

- The presets bound what the model can *spell* — mutating paths, commands and code. They
  are not an OS-level sandbox: every preset that allows the project build (`read-build`,
  `write`, `full`) executes repository code (Maven plugins, npm scripts). The execution
  sandbox (`browser4.agent.sandbox`, see [config.md](../../../docs/config.md)) enforces
  where a process may write when your platform has a backend; otherwise use a container or
  VM when the repository itself is untrusted.
- `--access` is scoped to the task and reset when it ends, so the next task on the same
  backend starts from the server default again.

## Patterns

### Read-only audit of a change

```bash
browser4-cli agent run "Review the diff of browser4-agentic and list the risks" \
  --coding --access read
```

The agent can read and grep but neither edit nor build; anything it cannot do lands in
`problems` instead of a silent retry loop.

### Narrow preset with a single permitted widening

```bash
browser4-cli agent run "Diagnose the failing build and fix only the import" \
  --coding --access read-build --follow
```

The shell stays unavailable; when the agent needs the write it asks once, and the operator
approves exactly that effect from the REST endpoint. Writes stay off until then.

### Inspect the effective policy

```text
coding.permissions()      # preset, granted tools, protections, pending requests, operator grants
```

Use this when the agent keeps hitting refusals: it is the same view a client gets, so the
answer to "why was this refused" is one call away.

## Flags

| Flag / property | Applies to | Effect |
|---|---|---|
| `--access <preset>` | `agent run` | `full` \| `write` \| `read-build` \| `read` for this task; reset at task end |
| `browser4.agent.access` | backend | server-wide default preset (unset = `full`); a watched TTY run and an explicit `--access` keep it |
| `browser4.agent.access.unattended` | backend | default for a run without a TTY: `deny` \| `read` \| `read-build` \| `write` \| `full` (unset = `full`, network on) |
| `browser4.agent.allowDestructive` | backend | deprecated: `false` refuses delete/copy/move, not a read-only switch |
| `browser4.agent.shell.pathGuard` | backend | deprecated debugging switch for the shell's argument path guard; advisory only (the OS sandbox enforces), leave on |
| `browser4.agent.allowExternalAccess` | backend | deprecated; advisory path widening, documented in [agent-workspace.md](agent-workspace.md) |

## Errors & Recovery

| Symptom | Meaning | Fix |
|---|---|---|
| `code=TOOL_DENIED` | the preset does not grant the method | use a wider preset, or call `coding.requestAccess` and have an operator approve that effect |
| `code=EXEC_DISABLED` | shell/code execution is off under the preset | run the step another way, request `fs.exec` approval, or widen to `full` |
| `code=COMMAND_NOT_ALLOWED` | the command is outside the shell's tier | use the project build (`mvnBuild`), an allowed equivalent (`type`/`cat`/`dir`/`grep`) or a `coding.*` file tool, a script file, or a different command — under `--unrestricted` the tier does not apply at all; a `no approval lifts this` hint means the command is a permanent veto (runtime/credential/deny) |
| `code=PATH_DENIED` | the path is outside the workspace | see [agent-workspace.md](agent-workspace.md) — name the input, use `--coding`, or widen paths |
| `code=PROTECTED_PATH` / `code=SENSITIVE_PATH` | rails, not capabilities | never approvable; copy the data somewhere allowed |
| `code=SANDBOX_DENIED` | the OS sandbox refused the operation (the step's `needed` names the granted roots) | keep the command inside the workspace and granted roots; change the sandbox configuration, not the command |
| An approved grant "did not apply" | the effect does not cover the method, the grant expired, or a rail matched (denied tool, protected path, workspace boundary, command whitelist) | ask for the right effect (`fs.write` vs `fs.exec`), re-request after expiry, or remove the rail in the embedding configuration — a rail is never approvable |

## Related

- [agent.md](agent.md) — `agent run|status|result`, `--coding`, `--follow`
- [agent-workspace.md](agent-workspace.md) — the path boundary, sensitive and protected locations
- [config.md](config.md) — `config` command family and server-side overrides
