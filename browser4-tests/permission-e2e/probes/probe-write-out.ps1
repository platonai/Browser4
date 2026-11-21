#!/usr/bin/env pwsh
<#
.SYNOPSIS
    A fixed escape probe: try to create one file outside the workspace.

.DESCRIPTION
    The payload is deliberately tiny and deterministic so the case can be re-run and
    compared. It never swallows a denial: a refused write exits 1 and prints the OS
    message, which is what `code=SANDBOX_DENIED` is derived from.

    Used by cases A06 / B03 / C01 (`probe-write-out.ps1`), because a script body is
    exactly the shape the string layer cannot see.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Path
)

try {
    [IO.File]::WriteAllText($Path, "PROBE_ESCAPED`n")
    "PROBE_ESCAPED: $Path"
    exit 0
} catch {
    "PROBE_DENIED: $($_.Exception.GetType().Name): $($_.Exception.Message)"
    exit 1
}
