---
title: "Agent Access — Presets, Refusals, Auto-Approval"
description: "Reference for an agent task's access preset: what full/write/read-build/read grant, the PERMISSION_DENIED code catalogue, and how --auto-approve raises a bounded ceiling without overriding rails."
tier: procedure
---

# Agent Access — Presets, Refusals, Auto-Approval

An `agent run` task executes its coding tools under one access **preset**. The preset
decides which capabilities exist at all; `--auto-approve` can raise a bounded ceiling on
top of it. Nothing here widens the *path* boundary — that is
[agent-workspace.md](agent-workspace.md).

## Quick Start

```bash
# Read + build only: the agent may inspect and run Maven, never edit or shell out.
browser4-cli agent run "Read pom.xml and report the failing module" --coding --access read-build

# Narrow preset, but allow file writes when the agent decides it must.
browser4-cli agent run "Fix the failing test" --coding --access read-build --auto-approve write
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
`.browser4`). A refusal is structural: it must not be retried with different quoting or an
interpreter. `coding.permissions` prints the same view MCP clients get — the preset, the
allowed routes and the protections.

### Auto-approval raises a ceiling

`--auto-approve <preset>` grants automatically a call the preset refuses, as long as the
preset that *would* grant it is no wider than the ceiling. This keeps a task on a narrow
preset without the agent stalling on a denial it cannot work around.

| Ceiling | Grants automatically on top of the preset |
|---|---|
| `read-build` | the project build |
| `write` | file mutation (`write`/`edit`/`delete`/`copy`/`move`) **and** the project build (the build requires the narrower `read-build` grant, so it is approved too) |
| `full` | file mutation, the project build **and** the shell / arbitrary code |

Rules that hold regardless of the ceiling:

- `denyTools` (embedder-level) is never overridden — an explicit denial is a rail, not a
  capability. A refusal names it: `detail=excluded by denyTools under access preset 'full'`.
- Protected paths (governance files, `.browser4`, `.github/workflows`) and sensitive paths
  (credentials) are never approved.
- Every automatically approved call is audited once as
  `coding access auto-approved: tool=… required=… ceiling=…` and counted under
  `AUTO_APPROVED`, so the widening is visible after the fact.

Omitted, automatic approval is **off** and every refusal stands. The backend-wide default
lives in `browser4.agent.autoApprove`; like `--access`, the flag is scoped to the task and
reset when it ends.

### Two limits worth knowing

- The presets bound what the model can *spell* — mutating paths, commands and code. They
  are not an OS-level sandbox: every preset that allows the project build (`read-build`,
  `write`, `full`) executes repository code (Maven plugins, npm scripts). Use a container
  or VM when the repository itself is untrusted.
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

### Narrow preset with a single widening

```bash
browser4-cli agent run "Diagnose the failing build and fix only the import" \
  --coding --access read-build --auto-approve write
```

The shell stays unavailable; writes are approved per call and audited.

### Inspect the effective policy

```text
coding.permissions()      # preset, granted tools, protections, approval ceiling
```

Use this when the agent keeps hitting refusals: it is the same view a client gets, so the
answer to "why was this refused" is one call away.

## Flags

| Flag / property | Applies to | Effect |
|---|---|---|
| `--access <preset>` | `agent run` | `full` \| `write` \| `read-build` \| `read` for this task; reset at task end |
| `--auto-approve <preset>` | `agent run` | bounded ceiling that grants refused calls up to that width |
| `browser4.agent.access` | backend | server-wide default preset (unset = `full`) |
| `browser4.agent.autoApprove` | backend | server-wide approval ceiling (unset = off) |
| `browser4.agent.allowDestructive` | backend | deprecated: `false` refuses delete/copy/move, not a read-only switch |
| `browser4.agent.shell.pathGuard` | backend | deprecated debugging switch for the shell's argument path guard; leave on |
| `browser4.agent.allowExternalAccess` | backend | path boundary, documented in [agent-workspace.md](agent-workspace.md) |

## Errors & Recovery

| Symptom | Meaning | Fix |
|---|---|---|
| `code=TOOL_DENIED` | the preset does not grant the method | use a wider preset, or `--auto-approve` with the narrowest preset that grants it |
| `code=EXEC_DISABLED` | shell/code execution is off under the preset | run the step another way, or widen to `full` (audited) |
| `code=COMMAND_NOT_ALLOWED` | the command is outside the shell's tier | use the project build (`mvnBuild`), a script file, or a different command |
| `code=PATH_DENIED` | the path is outside the workspace | see [agent-workspace.md](agent-workspace.md) — name the input, use `--coding`, or widen paths |
| `code=PROTECTED_PATH` / `code=SENSITIVE_PATH` | rails, not capabilities | never approvable; copy the data somewhere allowed |
| Auto-approval "did not apply" | the call needs a wider preset than the ceiling, or an explicit `denyTools` rail matched | raise the ceiling, or remove the denial in the embedding configuration |

## Related

- [agent.md](agent.md) — `agent run|status|result`, `--coding`, `--follow`
- [agent-workspace.md](agent-workspace.md) — the path boundary, sensitive and protected locations
- [config.md](config.md) — `config` command family and server-side overrides
