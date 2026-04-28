#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repoRoot="$SCRIPT_DIR"
while [[ "$repoRoot" != "/" && ! -f "$repoRoot/VERSION" ]]; do
  repoRoot="$(dirname "$repoRoot")"
done
[[ -f "$repoRoot/VERSION" ]] || { echo "VERSION file not found"; exit 1; }
cd "$repoRoot"

API_BASE="${API_BASE:-http://localhost:8182}"
JAR_PATH="${JAR_PATH:-$repoRoot/browser4-app/browser4-agents/target/Browser4.jar}"
JAVA_OPTS="${JAVA_OPTS:-}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-120}"
SKIP_BUILD="${SKIP_BUILD:-false}"
KEEP_SERVER="${KEEP_SERVER:-false}"
BUILD_ARGS="${BUILD_ARGS:-}"
SERVER_LOG="$repoRoot/target/test-results/e2e-local/browser4.log"

E2E_ARGS=()

usage() {
  cat <<EOF
Usage: $(basename "$0") [options] [-- <run-e2e-tests.sh args>]

Options:
  --skip-build          Do not rebuild Browser4 (requires existing jar)
  --keep-server         Keep Browser4 running after tests
  --api-base URL        Override API base URL (default: $API_BASE)
  --jar PATH            Path to Browser4.jar (default: $JAR_PATH)
  --java-opts OPTS      Extra JVM options when starting Browser4
  --health-timeout SEC  Seconds to wait for health (default: $HEALTH_TIMEOUT)
  -h, --help            Show this help message

All remaining arguments after -- are passed to bin/tests/run-e2e-tests.sh.
Environment:
  API_BASE, JAR_PATH, JAVA_OPTS, HEALTH_TIMEOUT, SKIP_BUILD, KEEP_SERVER, BUILD_ARGS
EOF
}

log() {
  local ts
  ts=$(date '+%Y-%m-%d %H:%M:%S')
  echo "[$ts] $*"
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case $1 in
      --skip-build) SKIP_BUILD=true; shift ;;
      --keep-server) KEEP_SERVER=true; shift ;;
      --api-base) API_BASE="$2"; shift 2 ;;
      --jar) JAR_PATH="$2"; shift 2 ;;
      --java-opts) JAVA_OPTS="$2"; shift 2 ;;
      --health-timeout) HEALTH_TIMEOUT="$2"; shift 2 ;;
      -h|--help) usage; exit 0 ;;
      --) shift; E2E_ARGS+=("$@"); break ;;
      *) E2E_ARGS+=("$1"); shift ;;
    esac
  done
}

ensure_directories() {
  mkdir -p "$(dirname "$SERVER_LOG")"
}

build_browser4() {
  if [[ -f "$JAR_PATH" && "$SKIP_BUILD" == "true" ]]; then
    log "Skipping build (jar found at $JAR_PATH)"
    return
  fi
  if [[ ! -f "$JAR_PATH" && "$SKIP_BUILD" == "true" ]]; then
    log "Jar not found at $JAR_PATH, running build despite --skip-build"
  fi

  log "Building Browser4 agents..."
  local build_cmd=(./bin/build.sh -pl browser4-app/browser4-agents -am)
  if [[ -n "$BUILD_ARGS" ]]; then
    # shellcheck disable=SC2206
    build_cmd+=($BUILD_ARGS)
  fi
  "${build_cmd[@]}"
  if [[ ! -f "$JAR_PATH" ]]; then
    log "Build finished but jar not found at $JAR_PATH"
    exit 1
  fi
}

wait_for_server() {
  local start_ts
  start_ts=$(date +%s)
  log "Waiting for Browser4 to become healthy at ${API_BASE}/actuator/health (timeout: ${HEALTH_TIMEOUT}s)..."
  until curl -s --connect-timeout 5 --max-time 10 "${API_BASE}/actuator/health" | grep -qi '"status":"UP"'; do
    if (( $(date +%s) - start_ts >= HEALTH_TIMEOUT )); then
      log "Browser4 did not become healthy within ${HEALTH_TIMEOUT}s"
      return 1
    fi
    sleep 2
  done
  log "Browser4 is healthy"
}

SERVER_PID=""
cleanup() {
  if [[ -n "$SERVER_PID" && "$KEEP_SERVER" != "true" ]]; then
    log "Stopping Browser4 (pid: $SERVER_PID)..."
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

start_browser4() {
  if [[ ! -f "$JAR_PATH" ]]; then
    log "Browser4 jar not found at $JAR_PATH"
    exit 1
  fi
  log "Starting Browser4: java ${JAVA_OPTS:+$JAVA_OPTS }-jar $JAR_PATH"
  java ${JAVA_OPTS:+$JAVA_OPTS } -jar "$JAR_PATH" > "$SERVER_LOG" 2>&1 &
  SERVER_PID=$!
  log "Browser4 started with pid $SERVER_PID (logs: $SERVER_LOG)"
}

run_e2e_tests() {
  log "Running e2e tests via bin/tests/run-e2e-tests.sh ${E2E_ARGS[*]:-}"
  API_BASE="$API_BASE" bin/tests/run-e2e-tests.sh "${E2E_ARGS[@]}"
}

main() {
  parse_args "$@"
  ensure_directories
  build_browser4
  start_browser4
  if ! wait_for_server; then
    log "Browser4 failed to start. Last 50 log lines:"
    tail -n 50 "$SERVER_LOG" || true
    exit 1
  fi
  run_e2e_tests
}

main "$@"
