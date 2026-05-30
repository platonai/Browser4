# Scripts

This directory contains scripts for building, running, testing, and maintaining Browser4.

## Root Scripts

### `browser4.ps1`, `browser4.sh`

Start the Browser4 server (Agentic Service).
- Copies the built artifact from `browser4-app/browser4-agents/target/` to the root `target/` directory.
- Runs the application using `java -jar`.
- **Note:** You must build the project first (e.g., using `build` or `build-run`).

### `build.ps1`, `build.sh`

Build the project using Maven.
- Defaults to `mvnw install -DskipTests`.
- Options:
    - `-clean`: Run `mvn clean` before building.
    - `-test`: Enable tests (skips tests by default).
- Accepts standard Maven arguments (e.g., `-pl`, `-am`).

### `build-run.ps1`, `build-run.sh`

Build the project and then start the Browser4 server.
- Combines `build` and `browser4` scripts.

### `open-chrome.ps1`, `open-chrome.sh`

Launch Google Chrome with a dedicated user data directory for Browser4.
- **Default**: Compiles and runs `ai.platon.pulsar.tools.OpenChromeKt` using Maven.
- **Fallback**: Launches Chrome executable directly if the Kotlin launcher fails.
- **`--native`** (Unix) / **`-Native`** (Windows): Force native Chrome launch immediately.
- Useful for debugging or maintaining a persistent browser session (`~/.browser4/browser/chrome/default/pulsar_chrome`).

### `run-examples.ps1`

Run Browser4 examples (`ai.platon.pulsar.examples.agent.Browser4AgentKt`).
- Requires the project to be built or will attempt to run using `mvnw exec:java`.

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
- `mocksiteboot`: Launch `browser4-rest-tests`' standalone mock site server via `spring-boot:run`
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
./bin/test.sh mocksiteboot -Dmock.site.port=18080
```

### `version.ps1`, `version.sh`

Print the version of Browser4.
- `version.sh`: Prints version from `VERSION` file.
- `version.sh -v`: Prints version plus git hash, branch, and date.

### `seeds.txt`

A text file containing seed URLs for testing or crawling.

## Subdirectories

### `ci/`

CI/CD related scripts for local and Docker builds.
- `ci-local.ps1/sh`: Run CI checks locally.
- `ci-docker-local.ps1/sh`: Run CI checks in Docker.
- `e2e-local.sh`: Run E2E tests locally (similar to CI workflow).
- Tag management: `ci-tag-add.ps1`, `ci-tags-rm.ps1/sh`.

### `legacy/`

Legacy scraping scripts (deprecated but preserved for reference).
- `scrape.ps1/sh`: Scrape a webpage using Browser4.
- `scrape-async.ps1/sh`: Scrape using async API.

### `python/`

Python client scripts and examples.
- `command-sse.py`: Example SSE client.
- `experimental/`: Experimental scripts.

### `quality/`

Code quality check scripts.
- `check-links.sh/ps1`: Check documentation links for validity.
- `fix-links.py`: Automatically fix broken links.
- `quality-check.sh/ps1`: Run overall quality checks.
- See `quality/README.md` for details.

### `release/`

Release management scripts.
- `bump-version.ps1/sh`: Bump project version (minor/major).
- `bump-version-patch.ps1/sh`: Bump project version (patch).
- `update-versions.ps1/sh`: Update version strings across files.

### `script-tests/`

Tests for validating the scripts in this directory.
- `test.sh`: Main test script for bin scripts.

### `tests/`

End-to-end and integration test helper scripts.
- `run-e2e-tests.sh`: Helper to run E2E tests.
- `test-cdp-tracking.sh`: Test for CDP tracking functionality.

### `tools/`

Utility scripts for development.
- `install-depends.ps1/sh`: Install dependencies (like Chrome, Maven Wrapper).
- `kill-browsers.ps1/sh`: Kill lingering browser processes.
- `cloc.sh`, `sloc.sh`: Count lines of code.
- `check-dependencies.ps1/sh`: Check for dependency updates/issues.
- `dos2unix.sh`: Convert line endings.
- `git/`: Git helper scripts (e.g., `rm-copilot-branches.ps1`).

### `git/`

Root-level git maintenance scripts.
- `clean-orphan-tags.ps1`: Clean up orphaned git tags.
