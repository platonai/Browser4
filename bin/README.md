# Scripts

This directory contains scripts for building, running, testing, and maintaining Browser4.

## Root Scripts

### `build.ps1`, `build.sh`

Build the project using Maven and Cargo.
- Runs `./mvnw install` (Maven) + `cargo build --release` (CLI).
- Defaults to `-DskipTests`.
- Options:
    - `-clean`: Run `mvn clean` before building.
    - `-test`: Enable tests (skipped by default).
- Accepts standard Maven arguments (e.g., `-pl`, `-am`).

### `test.ps1`, `test.sh`

Comprehensive test runner for the current Maven reactors plus the Browser4 CLI package.

**Usage:**
```bash
./bin/test.sh [test-types...] [additional-args...]
```

**Test Types:**
- `fast`: Run fast unit tests (default)
- `it`: Run integration tests
- `e2e`: Run end-to-end tests
- `mock-site`: Launch `browser4-rest-tests`' standalone mock site server via `spring-boot:run` (`mocksite` and `mocksiteboot` are accepted as legacy aliases)
- `rest`: Run REST module tests
- `skills`: Run skills module tests
- `mcp`: Run MCP module tests
- `browser4`: Run all Browser4 main tests (`fast`, `rest`, `it`, `e2e`)
- `cli`: Run Rust Browser4 CLI tests from `cli/browser4-cli`

**Examples:**
```bash
./bin/test.sh fast                       # Run unit tests
./bin/test.sh it                         # Run integration tests
./bin/test.sh browser4                   # Run all main tests
./bin/test.sh cli                        # Run Browser4 CLI tests
./bin/test.sh cli -- --nocapture         # Pass extra cargo test args
./bin/test.sh mock-site -Dmock.site.port=18080
```

### `version.ps1`, `version.sh`

Print the version of Browser4.
- `version.sh`: Prints version from `VERSION` file.
- `version.sh -v`: Prints version plus git hash, branch, and date.

### `seeds.txt`

A text file containing seed URLs for testing or crawling.

---

## Subdirectories

### `build/`

Build scripts with extended functionality.

- **`build.ps1` / `build.sh`**: Full build pipeline — Maven + Spring Boot fat JAR + Cargo. Builds the entire project including the CLI, then copies `Browser4.jar` to `target/`.
- **`spring-boot.ps1`**: Build then launch Browser4 via `mvnw spring-boot:run`. Convenient for development hot-reload workflows.

### `ci/`

CI/CD helper scripts.

- **`ci-tag-add.ps1`**: Add a CI release tag.
- **`ci-tags-rm.ps1`**: Remove CI release tags.

### `common/`

Shared PowerShell utility modules imported by other scripts.

- **`Util.ps1`**: Common utilities including `Fix-Encoding-UTF8` — sets the console code page and output encoding to UTF-8 to prevent mojibake in Windows PowerShell.

### `git/`

Git maintenance and housekeeping scripts.

- **`clean-orphan-tags.ps1`**: Clean up orphaned git tags that no longer exist on the remote.
- **`delete-copilot-branches.ps1`**: Delete local and remote branches matching the GitHub Copilot naming pattern (`copilot/*`).
- **`git-config.ps1`**: Quick-set git HTTP/HTTPS proxy configuration.
- **`remove-tags-before.ps1`**: Remove stable-version git tags older than a specified threshold (default: before `v4.0.0`). Supports remote deletion.

### `quality/`

Code quality check scripts.

- **`fix-links.py`**: Automatically fix broken documentation links.

### `release/`

Release management scripts. See also [release/README.md](release/README.md) for the release workflow.

- **`bump-version.ps1`**: Bump project version (minor/major).
- **`bump-version-patch.ps1`**: Bump project version (patch).
- **`release-tag-add.ps1`**: Interactive script to create and push a release tag. Validates the version in `VERSION`, shows changelog since the previous tag, and pushes to the specified remote.
- **`update-versions.sh`**: Replace `-SNAPSHOT` version strings across `pom.xml`, `llm-config.md`, `README.md`, etc. Used by the CI release pipeline.

### `tools/`

Utility scripts for development and system maintenance.

**Process Management:**
- **`browser4-process-common.ps1`**: Shared module for detecting Browser4 Java and Chrome processes. Used by other process-management scripts.
- **`kill-browsers.ps1` / `kill-browsers.sh`**: Kill browser processes (Chrome, Chromium, Edge) that were launched by Browser4.
- **`kill-browsers-short.ps1`**: Minimal version — kills Chrome processes with `PULSAR_CHROME` in the command line.
- **`kill-browser4-processes.ps1`**: Kill Browser4 Java server processes. Supports `-ListOnly` for inspection without termination.
- **`list-browser4-processes.ps1`**: List all running Browser4 Java processes.
- **`list-browsers.ps1`**: List all browser processes launched by Browser4.

**Dependencies & Setup:**
- **`install-depends.ps1` / `install-depends.sh`**: Install system dependencies (Chrome, Maven Wrapper, etc.).
- **`check-dependencies.ps1` / `check-dependencies.sh`**: Check for dependency updates or issues.
- **`fix-kotlin-daemon.ps1`**: Diagnose and fix stale Kotlin daemon processes. Kills orphaned daemon JVMs, cleans lock files in `%LOCALAPPDATA%\kotlin\daemon`, and optionally runs a Maven install check to verify the fix.

**Housekeeping:**
- **`clear-temp-dir.ps1`**: Targeted cleanup of temporary build artifacts (tomcat, chrome, test, `.jar`, `koltin`, playwright, VS installer residue). Supports `-MinAgeHours` (default 24h) and `-WhatIf` for preview.
- **`remove-global-browser4-cli.ps1`**: Uninstall globally installed `browser4-cli` packages from npm, pnpm, Yarn, and cargo. Supports `-DryRun` and `-FailIfRemaining` for CI validation.
- **`dos2unix.sh`**: Convert Windows line endings to Unix.

**Proxy:**
- **`proxy.sh`**: Set/unset/status HTTP(S) and SOCKS5 proxy environment variables, git config, and VS Code settings. Must be `source`d for environment variables to persist:
  ```bash
  source bin/tools/proxy.sh on
  source bin/tools/proxy.sh off
  source bin/tools/proxy.sh status
  ```

**Code Metrics:**
- **`cloc.ps1`**: Count lines of code for a given git ref (defaults to HEAD).

**Maven Configuration:**
- **`maven/cn/settings.xml`**: Pre-configured Maven settings with Huawei Cloud mirror for faster builds in China.
- **`maven/maven-settings.md`**: Instructions for using the Chinese Maven mirror.

**Cron:**
- **`cron/update_and_build.sh`**: Cron-compatible script for periodic git-pull + rebuild. Intended to be scheduled via crontab.
