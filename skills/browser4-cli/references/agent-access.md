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
  (credentials) are never approved.
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
| `code=COMMAND_NOT_ALLOWED` | the command is outside the shell's tier | use the project build (`mvnBuild`), a script file, or a different command |
| `code=PATH_DENIED` | the path is outside the workspace | see [agent-workspace.md](agent-workspace.md) — name the input, use `--coding`, or widen paths |
| `code=PROTECTED_PATH` / `code=SENSITIVE_PATH` | rails, not capabilities | never approvable; copy the data somewhere allowed |
| `code=SANDBOX_DENIED` | the OS sandbox refused the operation (the step's `needed` names the granted roots) | keep the command inside the workspace and granted roots; change the sandbox configuration, not the command |
| An approved grant "did not apply" | the effect does not cover the method, the grant expired, or a rail matched (denied tool, protected path, workspace boundary, command whitelist) | ask for the right effect (`fs.write` vs `fs.exec`), re-request after expiry, or remove the rail in the embedding configuration — a rail is never approvable |

## Related

- [agent.md](agent.md) — `agent run|status|result`, `--coding`, `--follow`
- [agent-workspace.md](agent-workspace.md) — the path boundary, sensitive and protected locations
- [config.md](config.md) — `config` command family and server-side overrides
