---
title: "Configuration"
description: "Reference for the config command family: persistent CLI defaults (server, timeout, proxy, session) and server-side runtime config keys (agent.llm.maxRequestTokens, agent.token.budget.total)."
tier: catalog
---

# Configuration

## Overview

The `config` command family manages two kinds of settings: persistent CLI defaults in `~/.browser4/config.json` (honouring `BROWSER4_CLI_STATE_DIR`) and server-side runtime overrides routed to the running backend. Read this when you need to change a default server, timeout, proxy, session name, or an agent token limit.

## Quick Index

| Key | Type | Description |
|-----|------|-------------|
| `server` | CLI default | Default Browser4 server URL (overridden by `--server` / `BROWSER4_CLI_SERVER`) |
| `timeout` | CLI default | Default HTTP timeout in seconds (overridden by `--timeout`) |
| `proxy` | CLI default | Default download proxy URL (overridden by `--proxy`) |
| `session` | CLI default | Default session name (overridden by `-s` / `--session`) |
| `agent.llm.maxRequestTokens` | server-side | Per-request LLM token limit (default 500,000) |
| `agent.token.budget.total` | server-side | Cumulative token budget per agent run (default 5,000,000) |

## CLI Defaults (config.json)

The `config` command manages persistent CLI defaults stored in
`~/.browser4/config.json` (honours `BROWSER4_CLI_STATE_DIR`). These are global
fallbacks — an explicit flag or environment variable always wins per invocation.

| Key | Purpose | Overridden by |
|-----|---------|---------------|
| `server` | Default Browser4 server URL | `--server` / `BROWSER4_CLI_SERVER` |
| `timeout` | Default HTTP timeout (seconds) | `--timeout` |
| `proxy` | Default download proxy URL | `--proxy` |
| `session` | Default session name | `-s` / `--session` / `BROWSER4_CLI_SESSION` |

```bash
browser4-cli config                              # List all values + config file path
browser4-cli config list                         # Same as above
browser4-cli config get server                   # Print one value ("(not set)" if unset)
browser4-cli config set server http://localhost:8182
browser4-cli config set timeout 45               # Positive integer seconds
browser4-cli config delete session               # Reset a key to its default
```

Notes:
- `config get` / `set` / `delete` use the spaced form (`config get server`), not `config-get server`.
- `timeout` must be a positive integer; `0` and unknown keys are rejected with a non-zero exit.
- `config set server` sets the persistent default; a later `--server` flag or `BROWSER4_CLI_SERVER` still overrides it for that invocation.

## Server-side Config Keys (runtime overrides)

`agent.llm.maxRequestTokens` (per-request LLM token limit, default 500,000) and
`agent.token.budget.total` (cumulative token budget per agent run, default
5,000,000) are **server-side** config keys handled by the same `config` command
family. When an agent task exceeds either limit it halts with a status report;
raising the limit this way allows the task to continue — a runtime override,
effective immediately, no server restart.

```bash
browser4-cli config get agent.llm.maxRequestTokens   # Show default / configured / override / effective
browser4-cli config set agent.llm.maxRequestTokens 800000  # Raise the limit
browser4-cli config set agent.llm.maxRequestTokens unlimited # Disable enforcement
browser4-cli config delete agent.llm.maxRequestTokens      # Clear override, back to config values
browser4-cli config set agent.token.budget.total 10000000  # Raise the per-run budget
```

Notes:
- Unlike local keys, these are not stored in `config.json` — they route to the running server's unified REST config interface (`GET/PUT/DELETE /api/config/{key}`, `GET /api/config` to list all), so the server must be up.
- The override is runtime-only — lost on server restart, which falls back to the key's value in the server configuration.
- Accepted values: a non-negative integer, `0`, or `unlimited` (= 0, disables enforcement). Unknown keys and invalid values are rejected server-side with 404/400.
