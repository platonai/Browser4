#!/bin/bash
# 🚀 Browser4 Deployment Script
# This script handles the deployment process including local testing and production deployment
# Usage: ./deploy.sh [-v|--verbose] [-t|--test] [-p|--production]

# Enable error handling
set -euo pipefail
IFS=$'\n\t'

# Default values
VERBOSE=false
TEST_MODE=false
PRODUCTION_MODE=false
DOCKER_CONTAINER_NAME="browser4-test"
DOCKER_IMAGE_NAME="browser4"
DOCKER_TAG="latest"
SERVICE_PORT=8182
MAX_WAIT_TIME=60  # seconds
WAIT_INTERVAL=5   # seconds

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -v|--verbose)
            VERBOSE=true
            shift
            ;;
        -t|--test)
            TEST_MODE=true
            shift
            ;;
        -p|--production)
            PRODUCTION_MODE=true
            shift
            ;;
        *)
            echo "❌ Unknown option: $1"
            exit 1
            ;;
    esac
done

# 🔍 Find the first parent directory containing the VERSION file
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
APP_HOME="$SCRIPT_DIR/.."

while [[ ! -f "$APP_HOME/VERSION" ]]; do
    if [[ "$APP_HOME" == "/" ]]; then
        echo "❌ VERSION file not found in any parent directory"
        exit 1
    fi
    APP_HOME="$(dirname "$APP_HOME")"
done

if [[ "$VERBOSE" == true ]]; then
    echo "📂 Found project root at: $APP_HOME"
fi

cd "$APP_HOME"

# Check if the git repository is clean
if ! git diff --quiet; then
    echo "❌ Git repository is not clean"
    exit 1
fi

# Convert all files to Unix line endings
$APP_HOME/bin/tools/dos2unix.sh -q

# Function to log messages
log() {
    if [[ "$VERBOSE" == true ]]; then
        echo "$1"
    fi
}

# Function to wait for service to be ready
wait_for_service() {
    local url="http://localhost:$SERVICE_PORT/actuator/health"
    local start_time=$(date +%s)
    local end_time=$((start_time + MAX_WAIT_TIME))

    log "⏳ Waiting for service to be ready..."

    while [[ $(date +%s) -lt $end_time ]]; do
        if curl -s "$url" | grep -q "UP"; then
            log "✅ Service is ready!"
            return 0
        fi
        sleep $WAIT_INTERVAL
    done

    echo "❌ Service failed to start within $MAX_WAIT_TIME seconds"
    return 1
}

# Function to run integration tests
run_integration_tests() {
    log "🔍 Running integration tests..."

    # Extract curl commands using Python script
    local curl_file_dir
    if ! curl_file_dir=$(python3 "$APP_HOME/bin/tools/python/extract_curl_blocks.py" "$APP_HOME/README.md"); then
        log "❌ Failed to extract curl commands from README.md"
        return 1
    fi

    if [[ -z "$curl_file_dir" ]]; then
        log "❌ No curl examples found in README.md"
        return 1
    fi

    log "🔍 Found curl examples in README.md"
    log "$(ls -la "$curl_file_dir")"

    # Execute each curl example
    local test_count=0
    local success_count=0

    for curl_file in "$curl_file_dir/curl_block*.sh"; do
        if [[ ! -f "$curl_file" ]]; then
            continue
        fi

        test_count=$((test_count + 1))
        log "🔍 Testing ($test_count): $curl_file"

        # Execute the curl example and capture output
        if output=$("$curl_file" 2>&1); then
            success_count=$((success_count + 1))
            log "✅ Test passed"
        else
            log "❌ Test failed: $curl_file"
            log "Error output:"
            log "$output"
            return 1
        fi
    done

    log "✅ Integration tests completed: $success_count/$test_count passed"

    return 0
}

# 1. Deploy to local staging repository
log "📦 Deploying to local staging repository..."
if ! $APP_HOME/bin/release/oss-deploy-locally.sh; then
    echo "❌ Failed to deploy to local staging repository"
    exit 1
fi

# 2. Build docker image
log "🐳 Building docker image..."
if ! docker build -t "$DOCKER_IMAGE_NAME:$DOCKER_TAG" .; then
    echo "❌ Failed to build docker image"
    exit 1
fi

if [[ "$TEST_MODE" == true ]]; then
    # 3. Run docker container for testing
    log "🚀 Starting docker container for testing..."
    if ! docker run -d -p "$SERVICE_PORT:$SERVICE_PORT" --name "$DOCKER_CONTAINER_NAME" "$DOCKER_IMAGE_NAME:$DOCKER_TAG"; then
        echo "❌ Failed to start docker container"
        exit 1
    fi

    # Wait for service to be ready
    if ! wait_for_service; then
        docker logs "$DOCKER_CONTAINER_NAME"
        docker rm -f "$DOCKER_CONTAINER_NAME"
        exit 1
    fi

    # Run integration tests
    if ! run_integration_tests; then
        docker logs "$DOCKER_CONTAINER_NAME"
        docker rm -f "$DOCKER_CONTAINER_NAME"
        exit 1
    fi

    # Cleanup
    log "🧹 Cleaning up test container..."
    docker rm -f "$DOCKER_CONTAINER_NAME"
fi

if [[ "$PRODUCTION_MODE" == true ]]; then
    # 4.1 Deploy artifact to Sonatype
    log "📦 Deploying artifact to Sonatype..."
    if ! $APP_HOME/mvnw -P deploy,release nexus-deploy-staged; then
        echo "❌ Failed to deploy to Sonatype"
        exit 1
    fi

    # 4.2 Push docker image
    log "🐳 Pushing docker image..."
    if ! docker push "$DOCKER_IMAGE_NAME:$DOCKER_TAG"; then
        echo "❌ Failed to push docker image"
        exit 1
    fi
fi

echo "✅ Deployment process completed successfully!"
