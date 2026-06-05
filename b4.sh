#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXE_PATH="$SCRIPT_DIR/cli/browser4-cli/target/release/browser4-cli"
CARGO_DIR="$SCRIPT_DIR/cli/browser4-cli"

if [[ ! -x "$EXE_PATH" ]]; then
    echo "[b4.sh] browser4-cli not built -- building now..."
    (cd "$CARGO_DIR" && cargo build --release)
    if [[ ! -x "$EXE_PATH" ]]; then
        echo "[b4.sh] ERROR: build failed -- executable still not found: \"$EXE_PATH\""
        exit 1
    fi
fi

exec "$EXE_PATH" "$@"
