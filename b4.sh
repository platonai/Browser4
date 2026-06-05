#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXE_PATH="$SCRIPT_DIR/cli/browser4-cli/target/release/browser4-cli"

if [[ ! -x "$EXE_PATH" ]]; then
    echo "[b4.sh] ERROR: executable not found: \"$EXE_PATH\""
    echo "[b4.sh] Run: cargo build --release  (in cli/browser4-cli)"
    exit 1
fi

exec "$EXE_PATH" "$@"
