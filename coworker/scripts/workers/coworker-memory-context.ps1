#!/usr/bin/env pwsh

<#
.SYNOPSIS
    Initialize memory context for coworker task execution.

.DESCRIPTION
    Wraps coworker-memory-generator.ps1 to safely produce memory context
    (context + instructions) as JSON on stdout. Diagnostic messages go to
    stderr so they never corrupt the JSON payload.

    Can run standalone or be sourced from coworker.ps1.

.PARAMETER Type
    Memory type to generate. Valid: init, daily, monthly, yearly, global.
    Default: init.

.PARAMETER Date
    Date in yyyy-MM-dd format. Default: today (UTC).

.EXAMPLE
    # Standalone use
    .\coworker-memory-context.ps1 -Type init -Date "2026-06-20"

.EXAMPLE
    # Called from coworker.ps1
    $result = & $memoryContextScript -Type init -Date $date | ConvertFrom-Json
    $result.context      # memory context string
    $result.instructions # memory instructions string
#>

param(
    [Parameter(Position = 0)]
    [ValidateSet('init', 'daily', 'monthly', 'yearly', 'global')]
    [string]$Type = 'init',

    [Parameter(Position = 1)]
    [string]$Date = ((Get-Date).ToUniversalTime().ToString('yyyy-MM-dd'))
)

$ErrorActionPreference = 'Stop'

# ── Locate and load config ───────────────────────────────────────────────
$scriptsDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$configPath = Join-Path (Split-Path -Parent $scriptsDir) 'config.ps1'
if (-not (Test-Path -LiteralPath $configPath)) {
    $result = @{ context = ''; instructions = ''; error = "config.ps1 not found: $configPath" }
    Write-Output ($result | ConvertTo-Json -Compress)
    exit 1
}
. $configPath

$generatorScript = Join-Path $scriptsDir 'coworker-memory-generator.ps1'

if (-not (Test-Path -LiteralPath $generatorScript)) {
    $result = @{ context = ''; instructions = ''; error = "Memory generator not found: $generatorScript" }
    Write-Output ($result | ConvertTo-Json -Compress)
    exit 1
}

# ── Helpers ──────────────────────────────────────────────────────────────

function Write-MemoryDiagnostic {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Message,
        [ValidateSet('INFO', 'WARN', 'ERROR')]
        [string]$Level = 'INFO'
    )
    $timestamp = Get-CoworkerTimestamp
    [Console]::Error.WriteLine("[${timestamp}] [${Level}] [memory-context] $Message")
}

function Remove-AnsiSequences {
    param([string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return $Text }
    $esc = [string][char]27
    return $Text -replace ($esc + '\[[0-9;?]*[ -/]*[@-~]'), ''
}

# ── Main ─────────────────────────────────────────────────────────────────

try {
    Write-MemoryDiagnostic "Invoking memory generator: Type=$Type Date=$Date"

    # Capture stdout as an array of lines; redirect error stream to /dev/null
    # so generator warnings never leak into the JSON on stdout.
    $stdoutLines = & $generatorScript -Type $Type -Date $Date 2>$null

    if ($null -eq $stdoutLines -or $stdoutLines.Count -eq 0) {
        Write-MemoryDiagnostic 'Memory generator produced no output' 'WARN'
        $result = @{ context = ''; instructions = '' }
        Write-Output ($result | ConvertTo-Json -Compress)
        exit 0
    }

    # Join lines and strip ANSI escape sequences
    $joined = if ($stdoutLines -is [array]) { $stdoutLines -join "`n" } else { [string]$stdoutLines }
    $cleaned = Remove-AnsiSequences -Text $joined

    # Extract the first JSON object from the output.  We use regex extraction
    # rather than assuming the entire stdout is valid JSON, because the
    # generator may emit status text alongside the payload.
    $jsonMatch = [regex]::Match($cleaned, '\{[\s\S]*\}')
    if (-not $jsonMatch.Success) {
        Write-MemoryDiagnostic 'No JSON object found in generator output' 'WARN'
        $result = @{ context = ''; instructions = '' }
        Write-Output ($result | ConvertTo-Json -Compress)
        exit 0
    }

    $memoryResult = $jsonMatch.Value | ConvertFrom-Json

    $props = $memoryResult.PSObject.Properties
    $context = if ($props.Name -contains 'context') { [string]$memoryResult.context } else { '' }
    $instructions = if ($props.Name -contains 'instructions') { [string]$memoryResult.instructions } else { '' }

    Write-MemoryDiagnostic "Memory context ready (context: $($context.Length) chars, instructions: $($instructions.Length) chars)"

    $result = @{
        context      = $context
        instructions = $instructions
    }
    Write-Output ($result | ConvertTo-Json -Compress)
    exit 0
}
catch {
    Write-MemoryDiagnostic "Memory context initialization failed: $_" 'ERROR'
    $result = @{ context = ''; instructions = ''; error = "Failed: $_" }
    Write-Output ($result | ConvertTo-Json -Compress)
    exit 1
}
