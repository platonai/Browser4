#!/bin/bash
set -e

bin=$(dirname "$0")

. "$bin/tools/install-powershell.sh"

echo "It is strongly recommended to launch `pwsh` and run the .ps1commands directly within the `pwsh` terminal."

# Forward all arguments to the PowerShell test script
exec pwsh "$bin/test.ps1" "$@"
