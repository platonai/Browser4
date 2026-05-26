#!/usr/bin/env bash

if ! command -v pwsh &> /dev/null; then
    echo "PowerShell is not installed. Please install PowerShell to use this script. Supported platforms include Linux, macOS, and Windows."
    echo "Visit https://learn.microsoft.com/en-us/powershell/scripting/install/install-powershell-on-linux for installation instructions."
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
pwsh "$SCRIPT_DIR/coworker-scheduler.ps1"
