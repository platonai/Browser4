#!/bin/bash
# Wrapper that invokes run-tests.ps1 with pwsh.
# All arguments are forwarded to the PowerShell script.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec pwsh -NoProfile -File "$SCRIPT_DIR/run-tests.ps1" "$@"
