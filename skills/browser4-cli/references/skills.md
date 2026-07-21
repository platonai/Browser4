---
title: "Skills Command Reference"
description: "Reference for skills and skill-* commands. Manage bundled AI agent skill files and installable backend skills."
tier: procedure
---

# Skills Command Reference

Browser4 has two independent skill systems — know which one you need:

| System | Commands | What it manages | Where skills live |
|--------|----------|-----------------|-------------------|
| **Bundled skills** | `skills`, `skills get`, `skills path`, `skills unpack` | AI agent instruction files embedded in the CLI binary | Compiled into `browser4-cli` at build time |
| **Backend skills** | `skill-list`, `skill-info`, `skill-install`, `skill-uninstall`, `skill-reload` | User-installed skills on the Browser4 server | Backend skill registry (server-side) |

> **Rule of thumb:** Use bundled-skill commands (`skills ...`) to refresh the
> AI agent's local instruction files.  Use backend-skill commands
> (`skill-...`) to manage installable server-side skills.

---

## Part 1: Bundled Skills (`skills`)

Bundled skills are AI agent instruction files (SKILL.md + reference documents)
that are compiled into the `browser4-cli` binary.  They always match the
installed CLI version — no network fetch, no version drift.

### Quick start

```bash
browser4-cli skills                    # List all bundled skill names
browser4-cli skills get browser4-cli   # Print the main SKILL.md to stdout
browser4-cli skills get browser4-cli --full  # Include all reference files
browser4-cli skills path               # Print the skills directory path
browser4-cli skills unpack             # Write all bundled files to disk
```

### When to Use

Use `skills get` when your AI agent needs the current, version-matched
instructions for using browser4-cli — this avoids stale cached copies.  Use
`skills unpack` after `browser4-cli install` or to refresh skill files on
disk.  Use `skills path` to locate the skills directory for tool
configuration.

### How It Works

At build time, `build.rs` scans the `skills/` directory and embeds every file
into the binary as static strings.  The `skills` command reads from these
embedded strings — it never touches the network.

- **`skills` (and `skills-list`)** — prints the names of all bundled skills.
- **`skills get <name>`** — prints the SKILL.md content for a skill.  With
  `--full`, concatenates all bundled files for that skill (references, extra
  docs).
- **`skills get --all`** — prints every bundled skill in full.
- **`skills path [name]`** — prints the skills directory (or a specific
  skill's directory within it).
- **`skills unpack [dest]`** — writes all embedded files to disk, recreating
  the `skills/` directory structure.

### Skills directory

The default skills directory is `<runtime-data-dir>/skills/`.  Set
`BROWSER4_SKILLS_DIR` to override:

```bash
export BROWSER4_SKILLS_DIR=/path/to/custom/skills
browser4-cli skills path
# → /path/to/custom/skills
```

The directory is created automatically by `skills unpack`.  `browser4-cli
install` also unpacks skills into the versioned installation directory.

### Options

| Command | Flag | Description |
|---------|------|-------------|
| `skills get` | `--full` | Include all reference files (not just SKILL.md) |
| `skills get` | `--all` | Output every bundled skill consecutively |
| `skills unpack` | `dest` (arg) | Target directory (default: skills directory) |

### Common patterns

#### Refresh AI agent instructions

```bash
# Agent wants the latest browser4-cli instructions (version-matched to binary)
browser4-cli skills get browser4-cli

# Agent needs the full reference set
browser4-cli skills get browser4-cli --full
```

#### Unpack skills for external tooling

```bash
# Unpack to default location
browser4-cli skills unpack

# Unpack to a custom directory
browser4-cli skills unpack /opt/ai-agent/skills
```

#### List available bundled skills

```bash
browser4-cli skills
# browser4-cli
# browser4-experience
# browser4-plugin
```

---

## Part 2: Backend Skill Management (`skill-*`)

Backend skills are user-installed skills managed by the Browser4 server.
Unlike bundled skills, these are installed from directories containing a
`SKILL.md` file and are registered in the server's skill registry.

### Quick start

```bash
browser4-cli skill-list                          # List installed skills
browser4-cli skill-info my-skill                 # Show details for one skill
browser4-cli skill-install /path/to/skill-dir    # Install a skill
browser4-cli skill-uninstall my-skill            # Remove a skill
browser4-cli skill-reload my-skill               # Reload from source directory
```

### Commands

#### `skill-list`

List all skills installed on the server.  No arguments.

```
browser4-cli skill-list
```

#### `skill-info`

Show metadata for a specific skill.

| Argument | Required | Description |
|----------|----------|-------------|
| `id` | Yes | Skill identifier |

```
browser4-cli skill-info web-scraping
```

#### `skill-install`

Install a skill from a local directory.  The directory must contain a
`SKILL.md` file with valid YAML frontmatter (`name:` field).

| Argument | Required | Description |
|----------|----------|-------------|
| `path` | Yes | Path to skill directory containing SKILL.md |

| Option | Description |
|--------|-------------|
| `--overwrite` | Overwrite an existing skill with the same ID |

```
browser4-cli skill-install /home/user/my-custom-skill
browser4-cli skill-install /home/user/my-custom-skill --overwrite
```

#### `skill-uninstall`

Remove a skill from the server by ID.

| Argument | Required | Description |
|----------|----------|-------------|
| `id` | Yes | Skill identifier |

```
browser4-cli skill-uninstall web-scraping
```

#### `skill-reload`

Reload a skill from its original source directory.  Useful during skill
development when you've edited the skill files and want the server to pick up
changes without reinstalling.

| Argument | Required | Description |
|----------|----------|-------------|
| `id` | Yes | Skill identifier |

```
browser4-cli skill-reload my-skill
```

### Common patterns

#### Install and verify a custom skill

```bash
browser4-cli skill-install ./my-extraction-skill
browser4-cli skill-info my-extraction-skill
```

#### Development loop: edit → reload → test

```bash
# Edit SKILL.md in your skill directory, then:
browser4-cli skill-reload my-skill
browser4-cli skill-info my-skill   # verify changes took effect
```

#### Clean up unused skills

```bash
browser4-cli skill-list
browser4-cli skill-uninstall old-experiment
```

---

## Error handling

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Skill not found: <name>` (skills get) | Name doesn't match a bundled skill | Run `browser4-cli skills` to see available names |
| `BROWSER4_SKILLS_DIR starts with '-'` | Env var set to a CLI flag value | Unset or correct the variable |
| `Failed to create directory` | No write permission to skills dir | Check permissions or use `BROWSER4_SKILLS_DIR` to point elsewhere |
| `Skill directory must contain SKILL.md` | `skill-install` target missing SKILL.md | Ensure the directory has a valid SKILL.md |
| `Skill already exists` | Installing a skill with a duplicate ID | Use `--overwrite` or uninstall first |

## See also

- [browser4-cli SKILL.md](../SKILL.md) — the main bundled skill (use `skills get browser4-cli`)
- [SKILL Document Methodology](../../methodology.md) — principles for writing skill documents
- [Plugin Development](../../docs-dev/plugin-development.md) — plugin system (related to installable skills)
