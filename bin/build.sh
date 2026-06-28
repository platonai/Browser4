#!/bin/bash

. "$(dirname "$0")/tools/install-powershell.sh"

echo "It is strongly recommended to launch `pwsh` and run the .ps1commands directly within the `pwsh` terminal."

# Call bin/build/build.ps1 with all passed arguments
pwsh bin/build/build.ps1 "$@"
