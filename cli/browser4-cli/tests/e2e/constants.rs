use std::time::Duration;

pub const BROWSER_PROFILE_MODE: &str = "SEQUENTIAL";

pub const OPEN_PROFILE_MODE_ARG: &str = "--profile-mode=SEQUENTIAL";

pub const OPEN_INTERACT_LEVEL_ARG: &str = "--interact-level=FASTEST";

pub const USE_MAVEN_STARTUP_FLAG: &str = "--use-maven-startup";

pub const INTERACTIVE_PATH: &str = "/interactive";

pub const OTHER_PATH: &str = "/other";

pub const FORM_PATH: &str = "/form";

pub const INTERACTIVE_TITLE: &str = "Browser4 CLI Interactive Fixture";

pub const OTHER_TITLE: &str = "Browser4 CLI Other Fixture";

pub const FORM_TITLE: &str = "Browser4 CLI Form Fixture";

pub const ROOT_SEARCH_START_DIR_ENV: &str = "BROWSER4_CLI_INVOKE_DIR";

pub const USE_MAVEN_STARTUP_ENV: &str = "BROWSER4_E2E_USE_MAVEN_STARTUP";

pub const FORCE_REMOTE_BUNDLE_ENV: &str = "BROWSER4_E2E_FORCE_REMOTE_BUNDLE";

pub const FORCE_REMOTE_BUNDLE_CLI_ENV: &str = "BROWSER4_CLI_FORCE_REMOTE_BUNDLE";

pub const LAST_FAILED_SCENARIOS_FILE: &str = "last-failed-scenarios.json";

pub const INTERACTIVE_FIXTURE_FILE: &str = "mcp-tool-controller-interactive-fixture.html";

pub const OTHER_FIXTURE_FILE: &str = "mcp-tool-controller-other-fixture.html";

pub const FORM_FIXTURE_FILE: &str = "mcp-tool-controller-form-fixture.html";

pub const MAX_EMPTY_READ_ATTEMPTS: u32 = 200; // 2 s with 10 ms sleep per attempt

pub const OUTPUT_COLLECTOR_DRAIN_TIMEOUT: Duration = Duration::from_secs(2);

pub const MAX_ALLOWED_FAILED_SCENARIOS: usize = 5;

pub const COVERAGE_TEST_NAME: &str = "test_e2e_command_coverage";

pub const HELP_TEXT: &str = r#"browser4-cli e2e test runner

Usage: cargo test --test e2e -- [OPTIONS]

Scenario selection:
  --scenario=<name|pattern>, -s     Run only scenarios matching this name or
                                    glob pattern (* and ? wildcards)
  --scenario-from=<name>, -f        Run scenarios starting from the named one
  --scenario-limit=<count>, -n      Run at most <count> selected scenarios
  --failed                          Rerun scenarios that failed in the previous run
  --group=<name>, -g                Run only scenarios in the specified group
                                    (repeatable; e.g. --group=open --group=eval)
  --level=<BASIC|EXTENDED|all>, -L  Max scenario level (default: BASIC)

Scenario inclusion (disabled by default):
  --enable-batch-scenario, -b       Include batch-command scenarios
  --enable-install-scenario, -i     Include install/upgrade scenarios
  --batch-only                      Run only batch-command scenarios

Output control:
  --list, -l                        List all scenario names (dry run)
  --list-groups, -G                 List available groups with scenario counts
  --quiet, -q                       Only show pass/fail summary (no per-step timings)
  --verbose, -v                     Print extra diagnostic info for debugging

Execution:
  --fail-fast, -F                   Stop after the first failure
  --force-remote-bundle, -R         Download runtime bundle from remote release
                                    (sets BROWSER4_CLI_FORCE_REMOTE_BUNDLE=1)

  --help, -h                        Print this help message

Environment variables:
  BROWSER4_E2E_SERVICE_URL     Connect to an already-running Browser4 service
  BROWSER4_E2E_SERVER_URL      Alias for BROWSER4_E2E_SERVICE_URL
  BROWSER4_E2E_FIXTURE_HOST    Host the Browser4 container uses to reach the
                               fixture HTTP server (default: 127.0.0.1)
  BROWSER4_E2E_CLI_TIMEOUT_SECS Override per-command timeout in seconds
  BROWSER4_E2E_USE_MAVEN_STARTUP Set to 1/true/yes/on for Maven startup
  BROWSER4_E2E_FORCE_REMOTE_BUNDLE Set to 1/true/yes/on to download runtime
                               bundle from GitHub (equiv. --force-remote-bundle)
  BROWSER4_CLI_FORCE_REMOTE_BUNDLE Set to 1/true/yes/on in the CLI process
                               to skip local Maven/jlink build
"#;
