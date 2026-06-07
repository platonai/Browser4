#!/bin/bash

# Check if pwsh is installed
if command -v pwsh &> /dev/null
then
    # echo "PowerShell (pwsh) has been already installed."
    exit
fi

echo "PowerShell (pwsh) is not installed. Installing PowerShell..."

curl -fsSL https://aka.ms/install-powershell.sh | bash
