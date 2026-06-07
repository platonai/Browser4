#!/bin/bash

. "$(dirname "$0")/tools/install-powershell.sh"

# Call bin/build/build.ps1 with all passed arguments
pwsh bin/build/build.ps1 "$@"
