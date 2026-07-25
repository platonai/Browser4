#!/usr/bin/env bash
# ==============================================================================
# Browser4 Environment Variable Manager
# ==============================================================================
# Usage:
#   ./bin/env-manage.sh show              Show all Browser4 env vars with current values
#   ./bin/env-manage.sh show --detailed    Show all with descriptions and defaults
#   ./bin/env-manage.sh show <PREFIX>      Show vars matching a prefix (e.g. BROWSER4_CLI)
#   ./bin/env-manage.sh get <VAR>          Print the value of a single env var
#   ./bin/env-manage.sh set <VAR> <VALUE>  Set an env var in this shell (export)
#   ./bin/env-manage.sh unset <VAR>        Unset an env var in this shell
#   ./bin/env-manage.sh export             Print export commands to source in a shell
#   ./bin/env-manage.sh export > ~/.browser4-env && source ~/.browser4-env
#   ./bin/env-manage.sh list               List all known Browser4 env var names only
#   ./bin/env-manage.sh help               Show this help
# ==============================================================================

set -euo pipefail

# ---------------------------------------------------------------------------
# Env var registry — canonical list of every BROWSER4_* env var and related keys
# Format: "VAR_NAME|Category|Default|Description"
# Categories: cli, backend, observability, llm, captcha, test, example, proxy, generic
# ---------------------------------------------------------------------------

KNOWN_VARS=(
  # ---- CLI: Runtime & Paths ----
  "BROWSER4_EXTENSION_TOKEN|cli||Chrome extension auth token for auto-approval of attach requests"
  "BROWSER4_CLI_SESSION|cli||Default session ID for the CLI"
  "BROWSER4_CLI_STATE_DIR|cli|~/.browser4|Override CLI session state directory"
  "BROWSER4_RUNTIME_DIR|cli|platform-dependent|Override runtime bundle & download cache directory"
  "BROWSER4_SKILLS_DIR|cli|versioned-install-dir|Override skills directory path"
  "BROWSER4_BROWSER_PATH|cli|(auto-detect)|Custom Chromium/Chrome browser executable path"
  "BROWSER4_SERVER_OPTS|cli||JVM options injected into backend server launch (space-separated)"
  'BROWSER4_SERVER_LOG_DIR|cli|(RUNTIME_DIR)/logs|Directory for server startup logs'

  # ---- CLI: Download & Mirror ----
  "BROWSER4_RELEASES_BASE_URL|cli|https://github.com/platonAI/Browser4/releases|Override releases download base URL"
  "BROWSER4_MIRRORS_CONFIG|cli|runtime/mirrors.json|Override mirror configuration file path"
  "BROWSER4_CLI_MIRROR_CHECK_TIMEOUT_SECS|cli|5|Mirror reachability check timeout (seconds)"
  "BROWSER4_CLI_MIRROR_SPEED_TEST_TIMEOUT_SECS|cli|30|Per-mirror speed test download timeout (seconds)"
  "BROWSER4_CLI_MIRROR_PREFERENCE_TTL_SECS|cli|86400|Cached mirror preference TTL (seconds, 86400=24h)"
  "BROWSER4_CLI_DOWNLOAD_TIMEOUT_SECS|cli|1800|Download timeout (seconds, 1800=30min)"
  "BROWSER4_CLI_DISABLE_MIRROR_SPEED_TEST|cli||Set to 1 to disable speed testing (TCP-only fallback)"
  "BROWSER4_CLI_INVOKE_DIR|cli|pwd|Root search start directory for relative paths"

  # ---- CLI: Build & Bundle ----
  "BROWSER4_CLI_FORCE_REMOTE_BUNDLE|cli||Set to 1/true/yes/on to force download from remote releases"
  "BROWSER4_CLI_FORCE_REBUILD_BUNDLE|cli||Set to 1/true/yes/on to force rebuild from source"

  # ---- CLI: Timeouts ----
  "BROWSER4_CLI_HTTP_TIMEOUT_SECS|cli|30|Default HTTP request timeout (seconds)"
  "BROWSER4_CLI_NAVIGATION_TIMEOUT_SECS|cli|120|Navigation request timeout (seconds)"
  "BROWSER4_CLI_INPUT_TIMEOUT_SECS|cli|90|Text input request timeout (seconds)"
  "BROWSER4_CLI_SNAPSHOT_TIMEOUT_SECS|cli|60|Snapshot request timeout (seconds)"
  "BROWSER4_CLI_AGENT_TIMEOUT_SECS|cli|180|Agent tool request timeout (seconds)"
  "BROWSER4_CLI_ACT_TIMEOUT_SECS|cli|60|Act tool request timeout (seconds)"
  "BROWSER4_CLI_CRAWL_TIMEOUT_SECS|cli|600|Crawl request timeout (seconds)"

  # ---- CLI: Proxy ----
  "BROWSER4_CLI_PROXY|cli||Explicit CLI download proxy URL (set by --proxy <url>)"
  "BROWSER4_CLI_TEST_TEMPORARY_PROFILE|cli||Internal test flag for temporary profile"

  # ---- LLM / AI Provider Keys ----
  "LLM_API_KEY|llm||Generic LLM API key (fallback for all providers)"
  "OPENAI_API_KEY|llm||OpenAI API key"
  "OPENROUTER_API_KEY|llm||OpenRouter API key"
  "DEEPSEEK_API_KEY|llm||DeepSeek API key"
  "VOLCENGINE_API_KEY|llm||Volcengine (ByteDance) API key"

  # ---- CAPTCHA Solving ----
  "CAPSOLVER_KEY|captcha||Capsolver API key for CAPTCHA solving"
  "TWOCAPTCHA_KEY|captcha||2Captcha API key for CAPTCHA solving"
  "ANTICAPTCHA_KEY|captcha||Anti-Captcha API key for CAPTCHA solving"

  # ---- Observability (OpenTelemetry + Metrics) ----
  "OTEL_TRACES_ENABLED|observability|true|Enable OpenTelemetry tracing (true/false)"
  "OTEL_EXPORTER_OTLP_ENDPOINT|observability|http://localhost:4317|OTLP gRPC collector endpoint"
  "OTEL_SERVICE_NAME|observability|browser4-agentic|OpenTelemetry service name"
  "OTEL_SERVICE_VERSION|observability|4.8.1-SNAPSHOT|OpenTelemetry service version"
  "METRICS_ENABLED|observability|true|Enable metrics collection (true/false)"
  "METRICS_PREFIX|observability|pulsar_agentic|Metrics name prefix"
  "METRICS_COMMON_TAGS|observability||Comma-separated key=value pairs for metrics common tags"

  # ---- Backend (Kotlin/Spring) ----
  "BROWSER4_TAB_READ_ACTIONS_WHITELIST|backend||Comma-separated list of read actions (e.g. read_snapshot,read_state)"
  "PLAYWRIGHT_BROWSERS_PATH|backend||Custom path to Playwright browser binaries"

  # ---- E2E Testing ----
  "BROWSER4_E2E_SERVICE_URL|test||External Browser4 service URL for E2E tests"
  "BROWSER4_E2E_SERVER_URL|test||Alias for BROWSER4_E2E_SERVICE_URL"
  "BROWSER4_E2E_FIXTURE_HOST|test|127.0.0.1|Host the Browser4 container uses to reach fixture server"
  "BROWSER4_E2E_CLI_TIMEOUT_SECS|test||Override per-command timeout in E2E tests (seconds)"
  "BROWSER4_E2E_USE_MAVEN_STARTUP|test||Set to 1/true/yes/on for Maven-based startup in E2E"
  "BROWSER4_E2E_FORCE_REMOTE_BUNDLE|test||Force remote bundle in E2E tests"

  # ---- Mock Site Testing ----
  "MOCK_SITE_PORT|test|8182|Port for mock site test server"
  "MOCK_SITE_WAIT_SEC|test||Wait seconds for mock site startup"

  # ---- Example Crawlers (credentials) ----
  "PULSAR_TAOBAO_USERNAME|example||Taobao login username (example crawlers)"
  "PULSAR_TAOBAO_PASSWORD|example||Taobao login password (example crawlers)"
  "PULSAR_SIMUWANG_USERNAME|example||Simuwang login username (example crawlers)"
  "PULSAR_SIMUWANG_PASSWORD|example||Simuwang login password (example crawlers)"

  # ---- System / Generic (read by Browser4) ----
  "http_proxy|proxy||HTTP proxy (lowercase)"
  "HTTP_PROXY|proxy||HTTP proxy (uppercase)"
  "https_proxy|proxy||HTTPS proxy (lowercase)"
  "HTTPS_PROXY|proxy||HTTPS proxy (uppercase)"
  "all_proxy|proxy||All-protocols proxy (lowercase)"
  "ALL_PROXY|proxy||All-protocols proxy (uppercase)"
  "TZ|generic||System timezone"
  "LC_ALL|generic||Locale override (all categories)"
  "LANG|generic||Locale (language)"
  "LC_CTYPE|generic||Locale (character classification)"
  "LC_MESSAGES|generic||Locale (messages)"
)

# ---- helpers ----

usage() {
  sed -n '2,15p' "$0"
}

color_value() {
  local val="$1"
  if [[ -n "$val" ]]; then
    # truncate long values (like tokens)
    if [[ ${#val} -gt 50 ]]; then
      printf '\033[32m%s...\033[0m' "${val:0:50}"
    else
      printf '\033[32m%s\033[0m' "$val"
    fi
  else
    printf '\033[90m<not set>\033[0m'
  fi
}

color_category() {
  case "$1" in
    cli)           printf '\033[36m%-12s\033[0m' "$1" ;;  # cyan
    llm)           printf '\033[35m%-12s\033[0m' "$1" ;;  # magenta
    captcha)       printf '\033[31m%-12s\033[0m' "$1" ;;  # red
    observability) printf '\033[33m%-12s\033[0m' "$1" ;;  # yellow
    backend)       printf '\033[34m%-12s\033[0m' "$1" ;;  # blue
    test)          printf '\033[95m%-12s\033[0m' "$1" ;;  # bright magenta
    example)       printf '\033[37m%-12s\033[0m' "$1" ;;  # white
    proxy)         printf '\033[33m%-12s\033[0m' "$1" ;;  # yellow
    generic)       printf '\033[90m%-12s\033[0m' "$1" ;;  # gray
  esac
}

show_var() {
  local name="$1" category="$2" default="$3" desc="$4"
  local val="${!name:-}"
  printf "  %-45s  %s  %-12s  %s\n" "$name" "$(color_value "$val")" "$default" "$desc"
}

show_var_detailed() {
  local name="$1" category="$2" default="$3" desc="$4"
  local val="${!name:-}"
  echo "──────────────────────────────────────────────────────────────────"
  printf "  Variable:   %s\n" "$name"
  printf "  Category:   " && color_category "$category" && echo
  printf "  Value:      %s\n" "$(color_value "$val")"
  printf "  Default:    %s\n" "${default:-<none>}"
  printf "  Description: %s\n" "$desc"
  echo
}

show_all() {
  local detailed="${1:-}"
  local filter="${2:-}"

  echo "Browser4 Environment Variables"
  echo "=============================="
  echo ""

  local count=0 set_count=0
  for entry in "${KNOWN_VARS[@]}"; do
    IFS='|' read -r name category default desc <<< "$entry"

    [[ -n "$filter" && "$name" != "$filter"* ]] && continue

    if [[ -n "${!name:-}" ]]; then
      ((++set_count))
    fi
    ((++count))

    if [[ "$detailed" == "detailed" ]]; then
      show_var_detailed "$name" "$category" "$default" "$desc"
    else
      show_var "$name" "$category" "$default" "$desc"
    fi
  done

  echo ""
  echo "──────────────────────────────────────────────────────────────────"
  printf "  %d/%d variables configured\n" "$set_count" "$count"
  if [[ -n "$filter" ]]; then
    printf "  Filter: names starting with \"%s\"\n" "$filter"
  fi
}

cmd_show() {
  local detailed="simple"
  local filter=""

  for arg in "$@"; do
    case "$arg" in
      --detailed|-d) detailed="detailed" ;;
      --help|-h) usage; return 0 ;;
      *) filter="$arg" ;;
    esac
  done

  show_all "$detailed" "$filter"
}

cmd_get() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "<not set>"
    return 1
  fi
  echo "${!name}"
}

cmd_set() {
  local name="$1" value="$2"
  export "$name"="$value"
  printf "Set %s = " "$name"
  color_value "$value"
  echo
}

cmd_unset() {
  local name="$1"
  unset "$name"
  printf "Unset %s\n" "$name"
}

cmd_export() {
  echo "# Browser4 environment variables — source this file:"
  echo "#   source <(./bin/env-manage.sh export)"
  echo ""
  for entry in "${KNOWN_VARS[@]}"; do
    IFS='|' read -r name category default desc <<< "$entry"
    if [[ -n "${!name:-}" ]]; then
      printf "export %s=%q\n" "$name" "${!name}"
    fi
  done
}

cmd_list() {
  local filter="${1:-}"
  for entry in "${KNOWN_VARS[@]}"; do
    IFS='|' read -r name category default desc <<< "$entry"
    [[ -n "$filter" && "$name" != "$filter"* ]] && continue
    printf "%-45s  [%s]  %s\n" "$name" "$category" "$desc"
  done
}

# ---- main ----

case "${1:-help}" in
  show)
    shift
    cmd_show "$@"
    ;;
  get)
    shift
    cmd_get "$@"
    ;;
  set)
    shift
    cmd_set "$@"
    ;;
  unset)
    shift
    cmd_unset "$@"
    ;;
  export)
    cmd_export
    ;;
  list)
    shift
    cmd_list "${1:-}"
    ;;
  help|--help|-h)
    usage
    ;;
  *)
    echo "Unknown command: ${1:-}"
    usage
    exit 1
    ;;
esac
