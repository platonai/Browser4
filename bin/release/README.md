# Release Browser4

Scripts for managing the Browser4 release lifecycle — bumping versions,
checking publish status, triggering CI workflows, and downloading release assets.

All scripts are cross-platform PowerShell (`pwsh`) unless noted otherwise.

## Prerequisites

- **PowerShell 7+** (`pwsh`) — required for all `.ps1` scripts.
- **GitHub CLI** (`gh`) — required for `check-publish-status.ps1`, `trigger-release-action.ps1`, `trigger-cli-release-action.ps1`, and `download-release-assets.ps1`.
- **Git** — all scripts operate from the repository root.
- **Bash** — for `update-versions.sh` (Linux/macOS; also works in Git Bash on Windows).

## Scripts

### `bump-version.ps1`

Bumps the project version by the specified part (major, minor, or patch).

- Reads the current version from the `VERSION` file at the repo root.
- Increments the specified part.
- Updates the version number in `pom.xml`, `README.md`, `README.zh.md`, and the `VERSION` file itself.
- Runs `check-publish-status.ps1` as a precheck (verifies the current version is
  the latest GitHub release and that the `pulsar-bom` artifact is on Maven Central).
- Commits the changes to Git.

```
.\bump-version.ps1 -Part minor
.\bump-version.ps1 -Part major
```

### `bump-version-patch.ps1`

Convenience wrapper that calls `bump-version.ps1 -Part patch`.

```
.\bump-version-patch.ps1
```

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

### `update-versions.sh`

Bash script that replaces `-SNAPSHOT` versions with the release version across
the repository. Used during the release process to strip the SNAPSHOT qualifier
from `pom.xml`, `README.md`, `README.zh.md`, and `llm-config.md` files.

Also syncs the CLI version metadata via `cli/scripts/sync-version.js` if available.

```bash
./bin/release/update-versions.sh
```

## Typical Release Workflow

1. Ensure all tests pass.
2. Run `check-publish-status.ps1` to verify the current version is published.
3. Run `bump-version.ps1 -Part <major|minor|patch>` to bump the version and commit.
4. Run `trigger-release-action.ps1` to push the tag and start the CI release build.
5. Wait for CI to build and publish to GitHub Releases.
6. Run `bump-version-patch.ps1` to bump the version for the next bug-fix cycle.
