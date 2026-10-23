#!/bin/bash
# Thin wrapper: run the code devtask evaluation on Linux/macOS/Git Bash.
# The evaluation itself is PowerShell (bin/eval-devtask.ps1) because it drives
# the b4w.ps1 development wrapper, which requires pwsh on every platform.

bin=$(dirname "$0")

. "$bin/tools/install-powershell.sh"

echo "It is strongly recommended to launch \`pwsh\` and run the .ps1 commands directly within the \`pwsh\` terminal."

exec pwsh "$bin/eval-devtask.ps1" "$@"
