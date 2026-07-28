# Browser4 — Logging Reference

Complete catalog of every log source across the project: Kotlin backend, Rust CLI,
and PowerShell scripts. Use this as the single source of truth when building log
management tooling, debugging, or configuring observability.

> **Live monitoring:** Run `.\bin\tools\watch-logs.ps1` for a real-time TUI
> dashboard with color-coded tailing across all nine log sources.

---

## 1. Kotlin / Java Backend (Logback)

**Framework:** SLF4J + Logback. `log4j-core` is **excluded** in all POMs —
`log4j-to-slf4j` bridges log4j API calls to SLF4J. No log4j XML configs exist.

### 1.1 Configuration Files

| File | Role |
|---|---|
| `browser4-core/browser4-resources/src/main/resources/logback.xml` | Default (dev/test), root INFO, all appenders |
| `browser4-core/browser4-resources/src/main/resources/logback-dev.xml` | Dev mode — same as default plus embedded-MongoDB suppressions |
| `browser4-core/browser4-resources/src/main/resources/logback-prod.xml` | Production — root WARN, console suppressed, tighter levels |
| `browser4-core/browser4-resources/src/test/resources/logback-test.xml` | Test baseline (DRFA + METRICS + SQL + console) |
| `browser4-rest/src/test/resources/logback-test.xml` | REST tests — adds DC appender, DEBUG on `pulsar.rest` |
| `browser4-core/browser4-browser/src/test/resources/logback-test.xml` | Browser tests — TRACE on `browser4.driver.chrome` |
| `browser4-agentic/src/test/resources/logback-test.xml` | Agentic tests — console only, log.level overridable |
| `browser4-tests/browser4-rest-tests/src/test/resources/logback-test.xml` | REST integration tests |
| `browser4-tests/browser4-e2e-tests/src/test/resources/logback-test.xml` | E2E tests — DEBUG on `skeleton.ai.agent` |
| `browser4-tests/pulsar-e2e-tests/src/test/resources/logback-test.xml` | Pulsar E2E — same structure |
| `browser4-tests/pulsar-it-tests/src/test/resources/logback-test.xml` | Pulsar IT — `skeleton.ai.agent` DEBUG |
| `browser4-tests/pulsar-tests-common/src/test/resources/logback-test.xml` | Test common — minimal console |
| `examples/browser4-examples/src/main/resources/logback.xml` | Examples — writes to `logs/pulsar.exam.log` |

### 1.2 Log File Catalog

All files live under `${logging.dir}` (default: `logs/`) with
`${logging.prefix}` (default: `pulsar`).  The table uses the default prefix.

| Log File | Appender | Rotation | Content |
|---|---|---|---|
| `pulsar.log` | DRFA (rolling) | daily | Root — everything not explicitly routed elsewhere |
| `pulsar.s.log` | SERVER (rolling) | daily | Server / core framework (`ai.platon.pulsar.*`, `ai.platon.scent.*`) |
| `pulsar.api.log` | API (rolling) | daily | Scrape API tasks (`ScrapeServiceV1.Task`, `ScrapeServiceV2.Task`) |
| `pulsar.pg.log` | PAGES (rolling) | daily | Page processing (`AbstractSinkAwareSQLExtractor`, `StreamingCrawler`, `BrowserEmulator`) |
| `pulsar.hv.log` | HARVEST (rolling) | daily | Harvest / ML (`AnalysablePageCorpus`, `NodeClusterRunner`) |
| `pulsar.bs.log` | BROWSER (file) | none | Browser / CDP operations (`ai.platon.pulsar.browser`, `browser4.driver.chrome`) |
| `pulsar.m.log` | METRICS (file) | none | Metrics (`MetricsSystem`, `AppMetrics`) |
| `pulsar.c.log` | COUNTERS (file) | none | Counters (`CounterReporter`) |
| `pulsar.sql.log` | SQL (file) | none | JDBC / SQL queries (`Jdbc` utility) |
| `pulsar.dc.log` | COLLECT (file) | none | Data collector (`ai.platon.pulsar.common.collect`) |
| `pulsar.exam.log` | Examples RFA | daily | Examples app only |

**Log patterns:**

| Appender | Pattern |
|---|---|
| CONSOLE | `%clr(%d{yyyy-MM-dd HH:mm:ss.SSS}){faint} %clr(%5p) %clr([%.10t]){faint} %clr(%c{2}){cyan} %clr(-){faint} %m%n%wEx` |
| DRFA / PAGES / API | `%d{HH:mm:ss.SSS} [%.10thread] %-5level %logger{26} - %msg%n` |
| SERVER / HARVEST | `%d{HH:mm:ss.SSS} [%.10thread] %-5level %logger{36} - %msg%n` |
| BROWSER / METRICS / COUNTERS / SQL / COLLECT | `%d{ISO8601} --- %m%n` |

### 1.3 Configurable Properties

| Property | Default | Settable via |
|---|---|---|
| `logging.dir` | `logs` | `-Dlogging.dir=...` or env |
| `logging.prefix` | `pulsar` | `-Dlogging.prefix=...` or env |
| `logging.file` | `${logging.prefix}.log` | derived |
| `log.level` | `INFO` (test configs) | `-Dlog.level=DEBUG` |

### 1.4 Runtime Log Control

- **Spring Boot Actuator** exposed on `*` (`management.endpoints.web.exposure.include=*`)
  - `GET /actuator/loggers` — view / modify logger levels at runtime
  - `GET /actuator/logfiles` — access log file content (if `logging.file.name` is set)
- **DoctorController REST API:**
  - `GET /api/doctor/log-files` — list all `.log` files with size and last-modified
  - `GET /api/doctor/logs?file=X&lines=N&filter=pattern` — tail / search log content
- **CI overrides** via `BROWSER4_SERVER_OPTS`:
  ```yaml
  BROWSER4_SERVER_OPTS=-Dapp.name=browser4-test
    -Dlogging.level.ai.platon.browser4.chrome=DEBUG
    -Dlogging.level.ai.platon.pulsar.browser=DEBUG
  ```

### 1.5 Programmatic Logger Configuration

- `PulsarWebDriverCDPTests.kt:22-25` — manipulates Logback logger levels directly
  via `(LoggerFactory.getLogger(name) as Logger).level = level`
- `CodahaleSlf4jReporter.kt` — custom SLF4J reporter for Codahale Metrics;
  maps to TRACE/DEBUG/INFO/WARN/ERROR levels via `LoggerFactory.getLogger("metrics")`
- `Browser4NativeHints.kt` — registers `logback*.xml` resources for GraalVM
  native-image builds

---

## 2. Rust CLI (`browser4-cli`)

The Rust CLI has **no application-level logging framework**. There is no `log`,
`tracing`, `env_logger`, or `RUST_LOG` support. All diagnostic output is direct
`println!` / `eprintln!`.

### 2.1 Output Mechanisms

| Mechanism | Destination | Controlled by |
|---|---|---|
| `cli_println!` macro | stdout | `--quiet` or `--json` suppress |
| `println!` / `eprintln!` | stdout / stderr | raw debug output |
| Tip system | stderr | `--show-tip` flag |
| `--json` flag | stdout | machine-parseable JSON envelope |

### 2.2 Server Startup Log

When the CLI launches the Browser4 backend process, it creates a startup log that
captures **both stdout and stderr** of the child process.

- **Path:** `<temp>/browser4/browser4-cli/browser4-server-jar-port<N>-<iso-ts>.log`
  - Example: `browser4-server-jar-port9292-20260728T120000Z.log`
- **Env var override:** `BROWSER4_SERVER_LOG_DIR`
- **Fallback resolution order:**
  1. Explicit `log_dir` parameter (tests only)
  2. `BROWSER4_SERVER_LOG_DIR` environment variable
  3. OS temp directory → `browser4/browser4-cli/`
- **No rotation** — each server launch creates a new timestamped file
- **Source:** `cli/browser4-cli/src/daemon.rs:4600-4749`

### 2.3 `doctor log` Command (Remote Log Viewer)

Defined in `cli/browser4-cli/src/main.rs:13468-13588`.  Fetches backend logs via
the DoctorController HTTP API — the CLI never reads backend log files directly.

```
doctor log                        list available log files on the backend
doctor log <name>                 view log file content
doctor log <name> --tail          show last 200 lines
doctor log <name> grep <pattern>  search with regex
doctor --verbose                  show backend logs inline in command output
```

### 2.4 Global Flags

| Flag | Effect |
|---|---|
| `--json` | machine-parseable JSON to stdout |
| `-q` / `--quiet` | suppress normal output, errors only |
| `--pretty` | pretty-print JSON |
| `--show-tip` / `-tip` | show tips on stderr |

---

## 3. PowerShell Scripts

### 3.1 Coworker System

**Log directory:** `~/.browser4-coworker/tasks/300logs/` (configurable in
`coworker/scripts/config.psd1` → `Paths.LogDirectory` and
`coworker/scripts/coworker-scheduler.config.psd1` → `Scheduler.LogDirectory`)

**Directory structure:** `YYYY/MM/DD/HHmmss-<taskname>.<type>.log`

| File Pattern | Created By | Content |
|---|---|---|
| `HHmmss-coworker.log` | `engineer.ps1` | Script-level log (all `Write-LogMessage` calls) |
| `HHmmss-<name>.task.log` | `engineer.ps1` | Task metadata + prompt + execution summary |
| `HHmmss-<name>.agent.log` | `engineer.ps1` | Agent stdout + stderr (merged) |
| `HHmmss-<name>.agent.log.stdout` | `engineer.ps1` (temp) | Agent stdout (merged then deleted) |
| `HHmmss-<name>.agent.log.stderr` | `engineer.ps1` (temp) | Agent stderr (merged then deleted) |
| `HHmmss-memory-context.err` | `engineer.ps1` (temp) | Memory context script stderr |
| `HHmmss-<name>.stdout.log` | `coworker-scheduler.ps1` | Task stdout + PowerShell transcript |
| `HHmmss-<name>.stderr.log` | `coworker-scheduler.ps1` | Task stderr |
| `scheduled-tasks.status.json` | `coworker-scheduler.ps1` | Scheduler status snapshot |

**Logging functions and their modules:**

| Function | Module | Behavior |
|---|---|---|
| `Write-CoworkerLog` | `coworker/scripts/common/Logging.ps1` | Colorized `Write-Host` with timestamp + level + component |
| `Normalize-CoworkerLogFile` | `coworker/scripts/common/Logging.ps1` | Strip ANSI, normalize encoding to UTF-8 |
| `Write-LogMessage` | `coworker/scripts/workers/task-logger.ps1` | Timestamped console + append to `$script:__ScriptLogPath` |
| `Write-LogVerbose` | `coworker/scripts/workers/task-logger.ps1` | DEBUG-level, file-only (not console) |
| `Write-ConsoleLine` | `coworker/scripts/workers/task-logger.ps1` | Console output with interactive-terminal detection |

**Key scripts:**

| Script | Role |
|---|---|
| `coworker/scripts/coworker-scheduler.ps1` | Polls for pending tasks, spawns them with `Start-Transcript` |
| `coworker/scripts/engineer.ps1` | Processes a batch of task files, runs agent, auto-commits |
| `coworker/scripts/config.ps1` | Loads config, dot-sources all utility modules |
| `coworker/scripts/common/Paths.ps1` | `Get-LogDirectory` — resolves log path from config |
| `coworker/coworker.ps1` | Main dispatch script (`coworker commit`, `coworker run`, etc.) |

### 3.2 Build Scripts

| File | Script | Content |
|---|---|---|
| `.build/spring-boot.log` | `bin/build/spring-boot.ps1` | Spring Boot stdout+stderr (truncated each build) |

The log is written via `RedirectStandardOutput` + `RedirectStandardError` using
a helper that merges both streams through `cmd.exe` redirect.

### 3.3 Test Infrastructure

| Location | Script | Content |
|---|---|---|
| `browser4-tests/tests-production/logs/<name>_<ts>/cmd_XXXX_<name>.log` | `test-utils.psm1` | Full stdout/stderr per CLI command invocation |
| `.test-sessions/<session-id>/test-session.json` | `test-session.psm1` | Cross-run test results, log paths, pass/fail counts |

**`test-utils.psm1` key exports:**
- `Initialize-TestLogging -Name <name>` — creates per-script log directory
- `Invoke-TrackedCli` — wraps CLI calls with full stdout/stderr capture
- `Register-CliResult` — records exit code, elapsed time, log paths

**`test-session.psm1`** — maintains one JSON file per test invocation under
`.test-sessions/<timestamp>/test-session.json` with rolling history (max 5
entries per test type).

### 3.4 Maintenance System

| Location | Created By | Content |
|---|---|---|
| `bin/maintenance/logs/<CheckId>-<ts>.json` | `report-json.ps1` | Per-check structured result |
| `bin/maintenance/logs/summary-<ts>.json` | `report-json.ps1` | Aggregate summary across all checks |

**Key functions:**
- `Write-MaintenanceLog` — timestamped, colorized console logging (INFO/WARN/ERROR/DEBUG)
- `Get-MaintenanceLogDir` — returns `bin/maintenance/logs/` (creates if missing)
- `Invoke-MaintenanceStep` — captures `$LASTEXITCODE`, stdout, stderr with timing

### 3.5 Other Scripts That Produce Log Output

| Script | Output |
|---|---|
| `bin/tools/cron/update_and_build.sh` | `>> cron.log 2>&1` (gitignored) |
| `cli/scripts/smoke-test-runtime-bundle.sh` | Dumps `$SERVER_LOG_DIR/*.log` on failure |
| `browser4-tests/tests-production/run-tests.sh` | Collects recent `.log` files for AI analysis on failure |
| `browser4-tests/real-world-scenarios/scripts/watchdog.ps1` | `Write-WatchdogLog` with INFO/WARN/ERROR levels |
| `bin/maintenance/checks/check-log-sizes.ps1` | Monitors `logs/`, `300logs/`, `maintenance/logs/` against thresholds (500MB total, 50MB/file) |
| `bin/tools/clear-temp-dir.ps1` | Targets `.log` files for cleanup |

---

## 4. CI / CD (GitHub Actions)

### 4.1 Log Artifacts Uploaded on Failure

| Workflow | Artifact Name | Content |
|---|---|---|
| `.github/workflows/release.yml` | `smoke-test-logs-${{ matrix.artifact_name }}` | `${{ runner.temp }}/browser4-server-logs/` |
| `.github/workflows/cross-platform-smoke.yml` | `smoke-test-logs-*` | Same pattern |
| `.github/workflows/weekly-production-test.yml` | `production-test-logs-${{ matrix.name }}` | `.browser4-acceptance/` + `b4cli-*.txt` |
| `.github/actions/run-tests/action.yml` | test reports | Maven Surefire XML reports |

### 4.2 Maintenance Workflow

`.github/workflows/maintenance.yml` reads `bin/maintenance/logs/summary-*.json`
and renders results as a GitHub Step Summary.

---

## 5. Environment Variables

| Variable | Layer | Purpose |
|---|---|---|
| `logging.dir` | Kotlin | Logback log directory (default: `logs`) |
| `logging.prefix` | Kotlin | Logback filename prefix (default: `pulsar`) |
| `log.level` | Kotlin | Root logger level override (default: `INFO`) |
| `BROWSER4_SERVER_LOG_DIR` | Rust / Kotlin | Override server startup and runtime log directories |
| `BROWSER4_SERVER_OPTS` | Rust | Inject JVM options (e.g. `-Dlogging.dir=...`, `-Dlogging.level.*=...`) |
| `BROWSER4_CLI_STATE_DIR` | Rust | CLI state directory (contains history, not logs) |
| `BROWSER4_RUNTIME_DIR` | Rust | Runtime data / cache directory |

---

## 6. Directory Quick Reference

```
{repo}/logs/                                    ← Kotlin backend (Logback, 10 files)
{repo}/.build/spring-boot.log                    ← Build: Spring Boot output
{repo}/bin/maintenance/logs/                     ← Maintenance check JSON
{repo}/browser4-tests/tests-production/logs/     ← Test: per-CLI transcripts
{repo}/.test-sessions/                           ← Test: cross-run session state
{repo}/cron.log                                  ← Cron job output (gitignored)

~/.browser4-coworker/tasks/300logs/              ← Coworker scheduler + task runner
%TEMP%/browser4/browser4-cli/                   ← Rust CLI server startup logs
```

---

## 7. Log Health Checks

`bin/maintenance/checks/check-log-sizes.ps1` monitors three directories:

| Directory | Default Threshold |
|---|---|
| `logs/` | 500 MB total, 50 MB per file |
| `coworker/tasks/300logs/` | 500 MB total, 50 MB per file |
| `bin/maintenance/logs/` | 500 MB total, 50 MB per file |

Thresholds are configurable in `bin/maintenance/thresholds/thresholds.psd1`
under the `LogHealth` key with `MaxTotalMB` and `MaxFileMB`.

---

## 8. .gitignore Log Exclusions

```
logs/          # all logs/ directories
derby.log      # Apache Derby database log
/cron.log      # cron job output
*.log          # JVM crash logs (hs_err_pid*.log)
```
