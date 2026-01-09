#!/bin/bash

# Find the first parent directory containing the VERSION file
AppHome="$(dirname "$(readlink -f "$0")")"
while [[ "$AppHome" != "/" && ! -f "$AppHome/VERSION" ]]; do
    AppHome="$(dirname "$AppHome")"
done
cd "$AppHome" || exit 1

# Configuration parameters
DOCKERFILE="${DOCKERFILE:-docker/browser4-prod/Dockerfile}"
IMAGE_NAME="${IMAGE_NAME:-browser4-test}"
CONTAINER_NAME="${CONTAINER_NAME:-browser4-test}"
PORT="${PORT:-8182}"
HEALTH_CHECK_TIMEOUT="${HEALTH_CHECK_TIMEOUT:-60}"

# Build & Run the Docker image locally, and then run the integration tests
set -e

# Cleanup function to ensure container is stopped
cleanup() {
    echo "Cleaning up..."
    docker stop "$CONTAINER_NAME" 2>/dev/null || true
    docker rm "$CONTAINER_NAME" 2>/dev/null || true
}
trap cleanup EXIT

# Check if port is already in use
if command -v lsof > /dev/null && lsof -Pi ":$PORT" -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo "Error: Port $PORT is already in use"
    exit 1
fi

# Build the Docker image
echo "Building Docker image: $IMAGE_NAME"
docker build -t "$IMAGE_NAME" -f $DOCKERFILE . || {
    echo "Error: Docker build failed"
    exit 1
}

# Run the Docker container
echo "Starting container: $CONTAINER_NAME"
CONTAINER_ID=$(docker run --rm -d --name "$CONTAINER_NAME" -p "$PORT:$PORT" "$IMAGE_NAME")
echo "Container started with ID: $CONTAINER_ID"

# Enhanced health check function
check_health() {
    local url=$1
    local response=$(curl -s "$url" 2>/dev/null)
    if [[ $? -eq 0 ]] && echo "$response" | grep -q '"status":"UP"'; then
        return 0
    fi
    return 1
}

# Wait for the container to start with timeout
echo "Waiting for the Pulsar RPA service to start..."
RETRY_COUNT=0
MAX_RETRIES=$((HEALTH_CHECK_TIMEOUT/2))

# Wait until the actuator health endpoint is ready
until check_health "http://localhost:$PORT/actuator/health" || [ $RETRY_COUNT -eq $MAX_RETRIES ]; do
    echo "Waiting for Pulsar RPA service to be ready (actuator)... ($((RETRY_COUNT+1))/$MAX_RETRIES)"
    sleep 2
    RETRY_COUNT=$((RETRY_COUNT+1))
done

if [ $RETRY_COUNT -eq $MAX_RETRIES ]; then
    echo "Error: Actuator health check failed to start within ${HEALTH_CHECK_TIMEOUT} seconds"
    echo "Container logs:"
    docker logs "$CONTAINER_NAME"
    exit 1
fi

# Reset retry count for second health check
RETRY_COUNT=0

# Wait until the custom health endpoint is ready
until check_health "http://localhost:$PORT/api/system/health" || [ $RETRY_COUNT -eq $MAX_RETRIES ]; do
    echo "Waiting for Pulsar RPA service to be ready (system)... ($((RETRY_COUNT+1))/$MAX_RETRIES)"
    sleep 2
    RETRY_COUNT=$((RETRY_COUNT+1))
done

if [ $RETRY_COUNT -eq $MAX_RETRIES ]; then
    echo "Error: System health check failed to start within ${HEALTH_CHECK_TIMEOUT} seconds"
    echo "Container logs:"
    docker logs "$CONTAINER_NAME"
    exit 1
fi

echo "Pulsar RPA service is ready."

# Run the integration tests
echo "Running integration tests..."
if ! bin/run-integration-test.sh; then
    echo "Error: Integration tests failed"
    echo "Container logs:"
    docker logs "$CONTAINER_NAME"
    exit 1
fi

echo "Integration tests completed successfully!"
