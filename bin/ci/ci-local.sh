#!/bin/bash

# Exit on error
set -euo pipefail

# Script configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT_NAME="$(basename "$0")"

# Default values
declare -i INTERVAL_SECONDS=60
declare -i FETCH_REMOTE=1
declare -i FORCE_BUILD=0
BUILD_MODULES=":pulsar-tests"
declare -i LOG_TO_FILE=0
LOG_FILE=""
declare -i VERBOSE=0
declare -i CLEAN_BUILD=0

# Colors for output (only when stdout is a terminal)
if [[ -t 1 ]]; then
    declare -r RED='\033[0;31m'
    declare -r GREEN='\033[0;32m'
    declare -r YELLOW='\033[1;33m'
    declare -r BLUE='\033[0;34m'
    declare -r NC='\033[0m'
else
    declare -r RED=''
    declare -r GREEN=''
    declare -r YELLOW=''
    declare -r BLUE=''
    declare -r NC=''
fi

# Logging function
log() {
    local -r level="$1"
    shift
    local -r msg="$*"
    local timestamp
    timestamp=$(date +'%Y-%m-%d %H:%M:%S')

    # Output to terminal
    case "$level" in
        INFO) echo -e "${GREEN}[INFO ${timestamp}]${NC} ${msg}" ;;
        WARN) echo -e "${YELLOW}[WARN ${timestamp}]${NC} ${msg}" ;;
        ERROR) echo -e "${RED}[ERROR ${timestamp}]${NC} ${msg}" >&2 ;;
        DEBUG)
            if [[ $VERBOSE -eq 1 ]]; then
                echo -e "${BLUE}[DEBUG ${timestamp}]${NC} ${msg}"
            fi
            ;;
    esac

    # Output to log file if enabled
    if [[ $LOG_TO_FILE -eq 1 && -n "$LOG_FILE" ]]; then
        echo "[${level} ${timestamp}] ${msg}" >> "$LOG_FILE"
    fi
}

# Cleanup function
cleanup() {
    log INFO "Received interrupt signal. Shutting down gracefully..."
    exit 0
}

# Trap signals for graceful shutdown
trap cleanup SIGINT SIGTERM

# Help message
show_help() {
    cat << EOF
Usage: $SCRIPT_NAME [OPTIONS] [INTERVAL_SECONDS]

Local CI/CD script that monitors git repository changes and triggers builds.

OPTIONS:
    -h, --help              Show this help message and exit
    -v, --verbose           Enable verbose (debug) logging
    -f, --force             Force build and test on every iteration (ignore git changes)
    -n, --no-fetch          Skip fetching remote changes (local changes only)
    -c, --clean             Perform clean build (-clean flag)
    -l, --log-to-file       Log output to file (ci-local-<timestamp>.log)
    -m, --modules MODULES   Comma-separated list of modules to build (default: :pulsar-tests)
                            Use 'all' to build all modules

ARGUMENTS:
    INTERVAL_SECONDS        Interval between checks in seconds (default: 60)

EXAMPLES:
    # Run with default settings (check every 60s)
    $SCRIPT_NAME

    # Run with 30s interval and verbose logging
    $SCRIPT_NAME -v 30

    # Force build every time, log to file, and check every 120s
    $SCRIPT_NAME -f -l 120

    # Build specific modules every 90s
    $SCRIPT_NAME -m ":browser4-core,pulsar-client" 90

    # Build all modules with clean flag
    $SCRIPT_NAME -c -m all

EOF
}

# Parse command line arguments
parse_args() {
    local -i position_args=0
    local modules_arg=""

    while [[ $# -gt 0 ]]; do
        case $1 in
            -h|--help)
                show_help
                exit 0
                ;;
            -f|--force)
                FORCE_BUILD=1
                log INFO "Force mode enabled - will build and test on every iteration"
                shift
                ;;
            -n|--no-fetch)
                FETCH_REMOTE=0
                log INFO "No-fetch mode enabled - will only check local changes"
                shift
                ;;
            -v|--verbose)
                VERBOSE=1
                log DEBUG "Verbose logging enabled"
                shift
                ;;
            -c|--clean)
                CLEAN_BUILD=1
                log INFO "Clean build enabled"
                shift
                ;;
            -l|--log-to-file)
                LOG_TO_FILE=1
                LOG_FILE="${SCRIPT_DIR}/ci-local-$(date +%Y%m%d-%H%M%S).log"
                log INFO "Logging to file: $LOG_FILE"
                shift
                ;;
            -m|--modules)
                if [[ -z "${2:-}" ]]; then
                    log ERROR "--modules requires an argument"
                    exit 1
                fi
                modules_arg="$2"
                shift 2
                ;;
            -*)
                log ERROR "Unknown option: $1"
                show_help >&2
                exit 1
                ;;
            *)
                # If it's a number, treat it as intervalSeconds
                if [[ $1 =~ ^[0-9]+$ ]]; then
                    INTERVAL_SECONDS=$1
                    if [[ $INTERVAL_SECONDS -lt 1 ]]; then
                        log ERROR "Interval must be at least 1 second"
                        exit 1
                    fi
                    shift
                else
                    log ERROR "Invalid argument: $1"
                    show_help >&2
                    exit 1
                fi
                ;;
        esac
    done

    # Process modules argument
    if [[ -n "$modules_arg" ]]; then
        if [[ "$modules_arg" == "all" ]]; then
            BUILD_MODULES=""
            log INFO "Building all modules"
        else
            # Replace commas with spaces for build.sh compatibility
            BUILD_MODULES="${modules_arg//,/ }"
            log INFO "Building modules: $BUILD_MODULES"
        fi
    fi
}

# Find the application home directory
find_app_home() {
    local current_dir="$SCRIPT_DIR"

    while [[ -n "$current_dir" && "$current_dir" != "/" ]]; do
        if [[ -f "$current_dir/VERSION" ]]; then
            echo "$current_dir"
            return 0
        fi
        current_dir=$(dirname "$current_dir")
    done

    log ERROR "Could not find VERSION file in any parent directory"
    return 1
}

# Validate prerequisites
validate_prerequisites() {
    local -i failed=0

    if ! command -v git &> /dev/null; then
        log ERROR "git is not installed or not in PATH"
        ((failed++))
    fi

    if ! command -v realpath &> /dev/null; then
        log ERROR "realpath is not installed or not in PATH"
        ((failed++))
    fi

    if [[ "$OSTYPE" == "linux-gnu"* ]]; then
        if ! command -v inotifywait &> /dev/null; then
            log WARN "inotifywait not found - file watching features won't be available"
        fi
    fi

    if [[ $failed -gt 0 ]]; then
        log ERROR "Prerequisites validation failed"
        exit 1
    fi
}

# Get current git branch name
get_current_branch() {
    git symbolic-ref --short HEAD 2>/dev/null || echo "HEAD (detached)"
}

# Get current HEAD hash
get_head_hash() {
    git rev-parse HEAD 2>/dev/null || echo "unknown"
}

# Check if repository is clean
is_repo_clean() {
    git diff-index --quiet HEAD -- 2>/dev/null
}

# Fetch remote changes
fetch_remote() {
    if [[ $FETCH_REMOTE -eq 0 ]]; then
        log DEBUG "Skipping remote fetch as requested"
        return 0
    fi

    local branch
    branch=$(get_current_branch)
    log INFO "Fetching latest changes for branch: $branch"

    if ! git fetch --quiet origin 2>/dev/null; then
        log WARN "Failed to fetch from remote repository"
        return 1
    fi

    local local_hash remote_hash
    local_hash=$(git rev-parse HEAD)
    remote_hash=$(git rev-parse "@{u}" 2>/dev/null || echo "")

    if [[ -n "$remote_hash" && "$local_hash" != "$remote_hash" ]]; then
        log INFO "Remote is ahead of local by $(git rev-list --count HEAD.."@{u}") commits"
        return 0
    fi

    return 1
}

# Print repository status
print_repo_status() {
    local branch hash status
    branch=$(get_current_branch)
    hash=$(get_head_hash | cut -c1-8)

    if is_repo_clean; then
        status="clean"
    else
        status="${YELLOW}modified${NC}"
    fi

    log INFO "Repository status: branch=${BLUE}${branch}${NC}, hash=${BLUE}${hash}${NC}, status=${status}"
}

# Run the build script
run_build_script() {
    log INFO "Starting build process..."

    if [[ ! -f "$buildScript" ]]; then
        log ERROR "Build script not found: $buildScript"
        return 1
    fi

    if [[ ! -x "$buildScript" ]]; then
        log ERROR "Build script is not executable: $buildScript"
        return 1
    fi

    # Construct build command
    local build_cmd=("$buildScript")

    # also build projects required
    build_cmd+=("-am")
    build_cmd+=("-Ppulsar-tests")

    if [[ $CLEAN_BUILD -eq 1 ]]; then
        build_cmd+=("-clean")
        log DEBUG "Clean build flag added"
    fi

    build_cmd+=("-test")

    if [[ -n "$BUILD_MODULES" ]]; then
        # Check if BUILD_MODULES is a single module with commas (convert spaces back)
        if [[ "$BUILD_MODULES" == *" "* ]]; then
            build_cmd+=("-pl" "$BUILD_MODULES")
        else
            # Single module without spaces
            build_cmd+=("-pl" "$BUILD_MODULES")
        fi
        log DEBUG "Building modules: $BUILD_MODULES"
    fi

    log INFO "Executing: ${build_cmd[*]}"

    # Execute build and capture exit code
    local build_start build_end build_duration build_exit
    build_start=$(date +%s)

    if ! "${build_cmd[@]}"; then
        build_exit=$?
        build_end=$(date +%s)
        build_duration=$((build_end - build_start))

        log ERROR "Build failed with exit code $build_exit (duration: ${build_duration}s)"
        return $build_exit
    fi

    build_end=$(date +%s)
    build_duration=$((build_end - build_start))

    log INFO "Build completed successfully in ${build_duration}s"
    return 0
}

# Print initial banner
print_banner() {
    local -r line="$(printf '%*s' 80 | tr ' ' '-')"
    echo "$line"
    echo "Local CI/CD Monitor"
    echo "Repository: $repoPath"
    echo "Build script: $buildScript"
    echo "Check interval: ${INTERVAL_SECONDS}s"
    echo "Clean build: $([[ $CLEAN_BUILD -eq 1 ]] && echo 'enabled' || echo 'disabled')"
    echo "Fetch remote: $([[ $FETCH_REMOTE -eq 1 ]] && echo 'enabled' || echo 'disabled')"
    echo "Force build: $([[ $FORCE_BUILD -eq 1 ]] && echo 'enabled' || echo 'disabled')"
    if [[ -n "$BUILD_MODULES" ]]; then
        echo "Build modules: $BUILD_MODULES"
    else
        echo "Build modules: all"
    fi
    if [[ $LOG_TO_FILE -eq 1 ]]; then
        echo "Log file: $LOG_FILE"
    fi
    echo "$line"
    echo
}

# Main function
main() {
    log DEBUG "Starting with arguments:" "$@"

    # Parse arguments
    parse_args "$@"

    # Validate prerequisites
    validate_prerequisites

    # Find app home
    local app_home
    if ! app_home=$(find_app_home); then
        exit 1
    fi

    # Get repository path and build script
    repoPath="$repoRoot"
    buildScript="$repoRoot/bin/build.sh"

    # Print banner
    print_banner

    # Initial repository status
    print_repo_status

    # Verify repository is a git repository
    if ! git -C "$repoPath" rev-parse --git-dir &> /dev/null; then
        log ERROR "Not a git repository: $repoPath"
        exit 1
    fi

    # Change to repository directory
    cd "$repoPath" || exit 1
    log DEBUG "Changed to repository directory: $(pwd)"

    # Check remote tracking branch
    if [[ $FETCH_REMOTE -eq 1 ]]; then
        if ! git rev-parse --abbrev-ref --symbolic-full-name "@{u}" &> /dev/null; then
            log WARN "No upstream branch configured. Remote fetch disabled."
            FETCH_REMOTE=0
        fi
    fi

    # Update repository on startup if fetch is enabled
    if [[ $FETCH_REMOTE -eq 1 ]]; then
        log INFO "Updating repository before initial build..."
        if fetch_remote; then
            log INFO "Pulling latest changes on startup..."
            if ! git pull --no-rebase --quiet; then
                log WARN "Git pull encountered issues, but continuing with existing code..."
            fi
        fi
    fi

    # Initial build
    log INFO "Intial build starting..."
    run_build_script

    # Initialize tracking variables
    local -i build_count=0
    local -i success_count=0
    local -i failure_count=0
    local last_hash
    last_hash=$(get_head_hash)
    log INFO "Initial HEAD hash: ${last_hash:0:8}"

    # Main loop
    log INFO "Beginning monitor loop (interval: ${INTERVAL_SECONDS}s)"

    while true; do
        # Wait for next interval
        sleep "$INTERVAL_SECONDS"

        log INFO "Starting CI check cycle..."
        print_repo_status

        local current_hash="$last_hash"
        local -i should_build=0
        local build_reason=""

        # Check for remote changes - always pull if fetch_remote indicates changes
        fetch_remote
        local -i fetch_exit=$?
        if [[ $fetch_exit -eq 0 ]]; then
            # Pull the changes (this will succeed if fetch_remote returned 0)
            log INFO "Pulling latest changes..."
            # Use || true to prevent exit on error due to set -e but show the error
            if ! git pull --no-rebase --quiet; then
                log WARN "Git pull encountered issues, but continuing..."
            fi
            # Always continue after pull attempt - don't let errors stop the build check
            should_build=1
            build_reason="remote changes detected"
        fi

        # Check for local changes (only when fetch is disabled)
        if [[ $FETCH_REMOTE -eq 0 ]] && ! is_repo_clean; then
            should_build=1
            build_reason="local uncommitted changes"
        fi

        # Force build if enabled and no other reason to build
        if [[ $FORCE_BUILD -eq 1 ]] && [[ $should_build -eq 0 ]]; then
            should_build=1
            build_reason="force build mode enabled"
        fi

        # Get current hash for comparison
        current_hash=$(get_head_hash)
        if [[ "$current_hash" != "$last_hash" ]]; then
            should_build=1
            build_reason="commit change (${last_hash:0:8} -> ${current_hash:0:8})"
        fi

        # Build if needed
        if [[ $should_build -eq 1 ]]; then
            ((build_count++))
            log INFO "Build trigger #$build_count: $build_reason"

            if run_build_script; then
                ((success_count++))
                last_hash="$current_hash"
            else
                ((failure_count++))
            fi

            log INFO "Build stats: total=$build_count, success=$success_count, failure=$failure_count"
        else
            log INFO "No changes detected, monitoring..."
        fi
    done
}

# Run main function with all arguments
main "$@"
