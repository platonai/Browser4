# Release Browser4

Scripts for managing the Browser4 release lifecycle — version maintenance,
checking publish status, triggering CI workflows, and downloading release assets.

PowerShell scripts require `pwsh` unless noted otherwise.

## Prerequisites

- **Node.js 18+** — required for `version.mjs` (the unified version tool).
- **PowerShell 7+** (`pwsh`) — required for all `.ps1` scripts.
- **GitHub CLI** (`gh`) — required for `check-publish-status.ps1`, `trigger-release.ps1`, `monitor-release.ps1`, and `download-release-assets.ps1`.
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
node bin/version.mjs auto              # Auto-bump backend + CLI if changed
node bin/version.mjs auto --dry-run    # Preview bump plan with release info
node bin/version.mjs auto --commit     # Apply bump and commit+push

# CLI version (VERSION → package.json, Cargo.toml)
node bin/version.mjs cli show          # Print CLI version
node bin/version.mjs cli sync          # Sync to dependent files
node bin/version.mjs cli sync --check  # Check-only mode (CI lint)
node bin/version.mjs cli auto          # Auto-bump CLI if cli/ changed
node bin/version.mjs cli auto --dry-run  # Preview CLI bump

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
- Also reports `browser4-cli` versions from `VERSION`, latest `-cli` tag on GitHub, and npm (`browser4-cli` package).
- Provides detailed release info: publish date, author, release URL, asset list.
- Exits 0 if published or naturally next; non-zero otherwise.

```
.\check-publish-status.ps1
```

### `trigger-release.ps1`

Triggers the main Browser4 release workflow (`release.yml`) on GitHub Actions.

**Dry-run by default** — running it without `-Apply` only performs the read-only
checks and previews the tag and release notes; it never creates or pushes anything.

- **Prerelease checks**: Delegates to `version.mjs prerelease-check` to verify
  version consistency across all files (VERSION, pom.xml, Cargo.toml, package.json)
  and confirm the current version is the next patch after the last GitHub release.
  Warns (and, in `-Apply` mode, asks for confirmation) if issues are found.
- Creates and pushes a `v{version}` tag (e.g. `v4.13.0`), which triggers
  the release workflow via the `on.push.tags` trigger.
- Shows changes since the previous release tag for release notes.
- **AI release notes (opt-in)**: by default the script does NOT call an AI
  agent — the raw commit list is used as the release notes. Pass `-Agent auto`
  to opt in with the backend auto-resolved (claude/codex/kimi/dsh/gh copilot),
  or `-Agent <name>` to pin a specific backend (claude, kimi, codex, dsh,
  copilot). The commit list is sent to the agent to generate structured release
  notes, used as the annotated tag message (unless `-message` is given). Falls
  back to the raw commit list if no agent is available.
- Supports `-remote`, `-message`, `-Apply`, `-DryRun`, and `-Agent`.

```
.\bin\release\trigger-release.ps1                             # dry run (preview)
.\bin\release\trigger-release.ps1 -Apply                      # actually create + push
.\bin\release\trigger-release.ps1 -Apply -message "Hotfix for login crash"
.\bin\release\trigger-release.ps1 -Agent auto                 # dry run, AI notes (auto backend)
.\bin\release\trigger-release.ps1 -Agent dsh                  # dry run, AI notes via dsh
```

### `monitor-release.ps1`

Triggers a release and monitors the workflow until completion.

**Dry-run by default** — running it without `-Apply` calls `trigger-release.ps1`
in dry-run mode (preview only) and exits without monitoring. Pass `-Apply` to
actually trigger and monitor.

- Calls `trigger-release.ps1` to create and push the release tag (in `-Apply` mode
  you will be prompted for confirmations, just as with `trigger-release.ps1 -Apply` directly).
- Captures the tag name and locates the triggered Release workflow run.
- Streams the workflow logs in real time.
- Reports the final conclusion (success/failure) and exits with the same code.
- Supports `-NoWatch` for non-interactive terminals (polls via `gh run list`/`gh run view`).
- Supports `-Apply`, `-DryRun`, and `-Agent` (forwarded to `trigger-release.ps1`).
- On workflow failure, auto-extracts diagnostic information (failing tests,
  error blocks) for quick triage.

```
.\bin\release\monitor-release.ps1                             # dry run (preview)
.\bin\release\monitor-release.ps1 -Apply                      # actually trigger + monitor
.\bin\release\monitor-release.ps1 -Apply -message "Hotfix for login crash"
.\bin\release\monitor-release.ps1 -Apply -NoWatch -PollIntervalSeconds 10
```

### `download-release-assets.ps1`

Downloads all assets from a GitHub release for `platonai/Browser4`.

- Defaults to the latest release (including prereleases).
- Supports a specific `-Tag`, custom `-OutputDir`, and `GITHUB_TOKEN` for authenticated requests.

```
.\download-release-assets.ps1
.\download-release-assets.ps1 -Tag v4.13.0 -OutputDir ./downloads
```

### `update-versions.sh` → `version.mjs release`

**Deprecated.** Use `node bin/version.mjs release` instead.

Strips the `-SNAPSHOT` qualifier from the version for release deployment.
Replaces `X.Y.Z-SNAPSHOT` with `X.Y.Z` in `pom.xml`, `README.md`,
`README.zh.md`, and `llm-config.md` files, then syncs CLI version metadata.

```bash
node bin/version.mjs release
```

## Tests

The `tests/` subdirectory contains PowerShell test scripts runnable with Pester:

- `monitor-release.tests.ps1` — Unit tests for `monitor-release.ps1` helper functions
  (`ConvertTo-LogLines`, `Parse-GitHubLogLine`, `Extract-MinimalErrors`).

Run with:

```powershell
Invoke-Pester .\bin\release\tests\monitor-release.tests.ps1
```

## Typical Release Workflow

1. Ensure all tests pass.
2. Run `check-publish-status.ps1` to verify the current version is published.
3. Run `node bin/version.mjs bump <major|minor|patch>` to bump the version and commit.
4. Run `.\bin\release\trigger-release.ps1 -Apply` to push the tag and start the CI release build.
5. Wait for CI to build and publish to GitHub Releases.
6. Run `node bin/version.mjs bump patch` to bump the version for the next bug-fix cycle.
