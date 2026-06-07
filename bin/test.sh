#!/bin/bash

. "$(dirname "$0")/tools/install-powershell.sh"

# Call bin/test.ps1 with all passed arguments
pwsh bin/test.ps1 "$@"
