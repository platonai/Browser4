#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXE_PATH="$SCRIPT_DIR/target/release/browser4-cli"

if [[ ! -x "$EXE_PATH" ]]; then
    echo "[browser4-cli.sh] ERROR: executable not found: \"$EXE_PATH\""
    echo "[browser4-cli.sh] Run: cargo build --release  (in sdks/browser4-cli)"
    exit 1
fi

exec "$EXE_PATH" "$@"
