#!/bin/bash
# Quick script to generate OpenAPI spec and SDKs for Pulsar REST API
# Usage: ./generate-sdk.sh

set -e

echo ""
echo "========================================"
echo "Pulsar REST API - SDK Generation"
echo "========================================"
echo ""

SPEC_FILE="pulsar-rest-sdk/openapi.yaml"

# Check if server is already running
if curl -s http://localhost:8182/health > /dev/null 2>&1; then
    echo "[INFO] Server is already running on port 8182"
else
    echo "[1/3] Starting REST API server..."
    echo "      This may take 30-60 seconds..."
    ./mvnw -q -pl pulsar-rest spring-boot:run > logs/sdk-server.log 2>&1 &
    SERVER_PID=$!
    echo "      Server PID: $SERVER_PID"

    # Wait for server to start
    WAIT=0
    while ! curl -s http://localhost:8182/health > /dev/null 2>&1; do
        sleep 2
        WAIT=$((WAIT + 2))
        echo "      Waiting for server... ${WAIT}s"
        if [ $WAIT -ge 60 ]; then
            echo "[ERROR] Server did not start within 60 seconds"
            echo "        Check logs/sdk-server.log for details"
            kill $SERVER_PID 2>/dev/null || true
            exit 1
        fi
    done
fi

echo ""
echo "[2/3] Fetching OpenAPI specification..."
curl -s http://localhost:8182/v3/api-docs.yaml -o "$SPEC_FILE"
echo "      Saved to $SPEC_FILE"

echo ""
echo "[3/3] Generating SDK clients..."
./mvnw -q -pl pulsar-rest-sdk clean generate-sources

echo ""
echo "========================================"
echo "SUCCESS! SDKs generated:"
echo "========================================"
echo "  Kotlin:     pulsar-rest-sdk/target/generated-sources/kotlin/"
echo "  Java:       pulsar-rest-sdk/target/generated-sources/java/"
echo "  TypeScript: pulsar-rest-sdk/target/generated-sources/typescript/"
echo ""
echo "To publish TypeScript SDK:"
echo "  cd pulsar-rest-sdk/target/generated-sources/typescript"
echo "  npm install && npm run build && npm publish"
echo ""

# Ask if user wants to stop the server
if [ -n "$SERVER_PID" ]; then
    read -p "Stop the REST API server? (y/N): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo "Stopping server (PID: $SERVER_PID)..."
        kill $SERVER_PID 2>/dev/null || true
        echo "Server stopped"
    fi
fi

echo ""
echo "Done!"

