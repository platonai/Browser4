#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Ensure PowerShell is available; install it if missing
if ! command -v pwsh &> /dev/null; then
    echo "PowerShell (pwsh) not found. Installing..."
    if [ -f "${SCRIPT_DIR}/tools/install-powershell.sh" ]; then
        bash "${SCRIPT_DIR}/tools/install-powershell.sh" || {
            echo "ERROR: Failed to install PowerShell. Please install it manually."
            exit 1
        }
    else
        echo "ERROR: install-powershell.sh not found at ${SCRIPT_DIR}/tools/"
        exit 1
    fi
fi

# Forward all arguments to the PowerShell test script
exec pwsh "${SCRIPT_DIR}/test.ps1" "$@"
