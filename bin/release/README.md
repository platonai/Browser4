# Release Browser4

Scripts for managing the Browser4 release lifecycle — version maintenance,
checking publish status, triggering CI workflows, and downloading release assets.

PowerShell scripts require `pwsh` unless noted otherwise.

## Prerequisites

- **Node.js 18+** — required for `version.mjs` (the unified version tool).
- **PowerShell 7+** (`pwsh`) — required for all `.ps1` scripts.
- **GitHub CLI** (`gh`) — required for `check-publish-status.ps1`, `trigger-release-action.ps1`, `trigger-cli-release-action.ps1`, and `download-release-assets.ps1`.
- **Git** — all scripts operate from the repository root.

## Scripts

### `version.mjs`

**Unified version maintenance tool** (located at `bin/version.mjs`). Single entry
point for all version operations — replaces the previous mix of bash, PowerShell,
and Node.js scripts.

Browser4 has two independent version tracks:

```
# Backend version (VERSION file → pom.xml, READMEs)
node bin/version.mjs show              # Print backend version
node bin/version.mjs show -v           # Print version + git hash, branch, date
node bin/version.mjs release           # Strip -SNAPSHOT for release deployment
node bin/version.mjs bump <part>       # Bump major/minor/patch
node bin/version.mjs bump <part> --dry-run    # Show what would change
node bin/version.mjs bump <part> --skip-precheck  # Skip publish-status check

# CLI version (cli/VERSION-CLI → package.json, Cargo.toml)
node bin/version.mjs cli show          # Print CLI version
node bin/version.mjs cli sync          # Sync to dependent files
node bin/version.mjs cli sync --check  # Check-only mode (CI lint)

# Cross-cutting
node bin/version.mjs check             # Full version consistency check
```

### `bump-version.ps1` → `version.mjs bump`

**Deprecated.** Use `node bin/version.mjs bump <part>` instead.

Bumps the project version by the specified part (major, minor, or patch).

- Reads the current version from the `VERSION` file at the repo root.
- Increments the specified part.
- Runs `check-publish-status.ps1` as a precheck (verifies the current version is
  the latest GitHub release and that the `pulsar-bom` artifact is on Maven Central).
- Updates `pom.xml`, `VERSION`, and commits the changes to Git.

```
node bin/version.mjs bump patch         # patch bump
node bin/version.mjs bump minor         # minor bump
node bin/version.mjs bump major         # major bump
node bin/version.mjs bump patch --dry-run   # dry-run
```

### `bump-version-patch.ps1` → `version.mjs bump patch`

**Deprecated.** Use `node bin/version.mjs bump patch` instead.

### `check-publish-status.ps1`

Checks whether the current project version and `browser4-cli` version have been
fully published.

- Compares the local version against the latest GitHub release.
- Shows status: `[OK]` already published, `[GO]` ready to publish (next in sequence), `[XX]` behind latest release.
- Also reports `browser4-cli` versions from `cli/VERSION-CLI`, latest `-cli` tag on GitHub, and npm (`browser4-cli` package).
- Provides detailed release info: publish date, author, release URL, asset list.
- Exits 0 if published or naturally next; non-zero otherwise.

```
.\check-publish-status.ps1
```

### `trigger-release-action.ps1`

Triggers the main Browser4 release workflow (`release.yml`) on GitHub Actions.

- Creates and pushes a `v{version}` tag (e.g. `v4.11.0`), which triggers
  the release workflow via the `on.push.tags` trigger.
- Supports `-DryRun` for testing (creates a `v{version}_dry_run.N` tag).

```
.\trigger-release-action.ps1
.\trigger-release-action.ps1 -DryRun
```

### `trigger-cli-release-action.ps1`

Triggers the `browser4-cli` release workflow (`release-cli.yml`) on GitHub Actions.

Two modes:
- **Tag mode** (default): creates and pushes a `v{version}-cli` tag.
- **Dispatch mode** (`-Dispatch`): uses `gh workflow run` to trigger the workflow directly.

Supports `-DryRun`, `-SkipBinaryBuild`, and custom version overrides.

```
.\trigger-cli-release-action.ps1
.\trigger-cli-release-action.ps1 -Dispatch
.\trigger-cli-release-action.ps1 -DryRun
```

### `download-release-assets.ps1`

Downloads all assets from a GitHub release for `platonai/Browser4`.

- Defaults to the latest release (including prereleases).
- Supports a specific `-Tag`, custom `-OutputDir`, and `GITHUB_TOKEN` for authenticated requests.

```
.\download-release-assets.ps1
.\download-release-assets.ps1 -Tag v4.11.0 -OutputDir ./downloads
```

### `update-versions.sh` → `version.mjs release`

**Deprecated.** Use `node bin/version.mjs release` instead.

Strips the `-SNAPSHOT` qualifier from the version for release deployment.
Replaces `X.Y.Z-SNAPSHOT` with `X.Y.Z` in `pom.xml`, `README.md`,
`README.zh.md`, and `llm-config.md` files, then syncs CLI version metadata.

```bash
node bin/version.mjs release
```

## Typical Release Workflow

1. Ensure all tests pass.
2. Run `check-publish-status.ps1` to verify the current version is published.
3. Run `node bin/version.mjs bump <major|minor|patch>` to bump the version and commit.
4. Run `trigger-release-action.ps1` to push the tag and start the CI release build.
5. Wait for CI to build and publish to GitHub Releases.
6. Run `node bin/version.mjs bump patch` to bump the version for the next bug-fix cycle.
