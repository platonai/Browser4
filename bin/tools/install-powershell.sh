#!/bin/bash

# Check if pwsh is installed
if command -v pwsh &> /dev/null
then
    echo "PowerShell (pwsh) has been already installed."
    return 0
fi

curl -fsSL https://aka.ms/install-powershell.sh | bash
