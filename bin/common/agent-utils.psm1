# agent-utils.psm1
# AI Agent utilities — resolve and invoke AI assistants (claude, copilot, etc.)
#
# Provides:
#   - Get-AiAnalyzer         — resolve the best available AI CLI on PATH
#   - Test-AiAvailable       — check if any AI is available
#   - Test-CopilotAvailable  — backward-compatible alias for Test-AiAvailable
#   - Invoke-AiAnalysis      — invoke the best available AI with a prompt
#   - Invoke-CopilotAnalysis — backward-compatible wrapper over Invoke-AiAnalysis
#
# Usage:
#   Import-Module "$PSScriptRoot\agent-utils.psm1" -Force
#
#   # Quick check
#   if (Test-AiAvailable) { Write-Host "AI is ready" }
#
#   # Simple prompt
#   $answer = Invoke-AiAnalysis -Prompt "Explain this error: ..."
#
#   # Analyse log files
#   $answer = Invoke-AiAnalysis -Prompt "What went wrong?" -FilePaths @("./logs/a.log", "./logs/b.log")

# ============================================================================
# Module-level state (script-scoped)
# ============================================================================
$script:AiAnalyzer = $null  # resolved AI CLI: 'claude', 'copilot', or $null

# Ordered list of known AI CLIs to probe, with their display names.
$script:KnownAnalyzers = @(
    @{ Bin = 'claude';  Name = 'Claude'   },
    @{ Bin = 'copilot'; Name = 'Copilot'  }
)

# ============================================================================
# Resolve the best available AI CLI on PATH (cached per session).
# ============================================================================
<#
.SYNOPSIS
    Resolve the best available AI CLI on the current PATH.

.DESCRIPTION
    Probes known AI CLIs in priority order (claude > copilot) and caches
    the result so repeated calls never re-scan PATH.

    Returns the binary name string ('claude', 'copilot') on success,
    or $null when no AI CLI is found.

.PARAMETER ForceRefresh
    Ignore the cache and re-scan PATH.

.EXAMPLE
    $ai = Get-AiAnalyzer
    if ($ai) { & $ai -p "hello" }
#>
function Get-AiAnalyzer {
    [CmdletBinding()]
    param(
        [switch]$ForceRefresh
    )

    if (-not $ForceRefresh -and $null -ne $script:AiAnalyzer) {
        if ($script:AiAnalyzer -eq 'none') { return $null }
        return $script:AiAnalyzer
    }

    foreach ($entry in $script:KnownAnalyzers) {
        $cmd = Get-Command $entry.Bin -CommandType Application -ErrorAction SilentlyContinue
        if ($null -ne $cmd) {
            $script:AiAnalyzer = $entry.Bin
            Write-Verbose "AI analyzer resolved: $($entry.Name) ($($entry.Bin))"
            return $entry.Bin
        }
    }

    $script:AiAnalyzer = 'none'
    Write-Verbose 'No AI CLI found on PATH'
    return $null
}

# ============================================================================
# Quick availability check
# ============================================================================
<#
.SYNOPSIS
    Returns $true when at least one AI CLI is available on PATH.

.EXAMPLE
    if (Test-AiAvailable) { Write-Host "AI is ready" }
#>
function Test-AiAvailable {
    [CmdletBinding()]
    param()
    return $null -ne (Get-AiAnalyzer)
}

# Backward-compatible alias (original name in test-utils.psm1).
function Test-CopilotAvailable {
    [CmdletBinding()]
    param()
    return Test-AiAvailable
}

# ============================================================================
# Core: invoke the best available AI with a prompt
# ============================================================================
<#
.SYNOPSIS
    Send a prompt to the best available AI CLI and return the response.

.DESCRIPTION
    Resolves the best AI on PATH via Get-AiAnalyzer, invokes it with the
    given prompt, and returns the response text.

    When -FilePaths is provided the content of each file is injected into
    the prompt (prepended before the user prompt).  This is useful for
    asking the AI to analyse log files, config files, diffs, etc.

    The response is always returned as a string.  When -OutputFile is
    given the response is also saved to that path (UTF-8).

.PARAMETER Prompt
    The prompt to send to the AI.  Required.

.PARAMETER FilePaths
    Zero or more file paths whose content should be included in the prompt.
    Each file is read with its path as a header.

.PARAMETER Analyzer
    Force a specific AI CLI ('claude' or 'copilot').  When omitted the best
    available is auto-detected.

.PARAMETER OutputFile
    When provided, the AI response is saved to this file path (UTF-8).

.PARAMETER TimeoutSecs
    Maximum time to wait for the AI to respond (default: 300).

.PARAMETER Quiet
    Suppress the informational banner line that announces which AI is running.

.OUTPUTS
    String.  The AI response text, or $null on failure / no AI available.

.EXAMPLE
    # Simple question
    $answer = Invoke-AiAnalysis -Prompt "What is browser automation?"

.EXAMPLE
    # Analyse log files
    $answer = Invoke-AiAnalysis -Prompt "Why did these tests fail?" `
                                -FilePaths @("./logs/a.log", "./logs/b.log")

.EXAMPLE
    # Save response to file
    Invoke-AiAnalysis -Prompt "summarise this" -FilePaths @("./big.log") `
                      -OutputFile "./summary.txt"
#>
function Invoke-AiAnalysis {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true, Position = 0)]
        [string]$Prompt,

        [string[]]$FilePaths,

        [ValidateSet('claude', 'copilot')]
        [string]$Analyzer = '',

        [string]$OutputFile = '',

        [int]$TimeoutSecs = 300,

        [switch]$Quiet
    )

    # Resolve analyzer
    $analyzer = if ($Analyzer) { $Analyzer } else { Get-AiAnalyzer }
    if (-not $analyzer) {
        Write-Warning 'Invoke-AiAnalysis: No AI CLI found on PATH (checked: claude, copilot)'
        return $null
    }

    # Build prompt
    $fullPrompt = ''
    if ($FilePaths -and $FilePaths.Count -gt 0) {
        foreach ($fp in $FilePaths) {
            if (Test-Path -LiteralPath $fp -PathType Leaf) {
                try {
                    $content = Get-Content -LiteralPath $fp -Raw -ErrorAction Stop
                    $fullPrompt += "─── FILE: $fp ───`n$content`n`n"
                } catch {
                    Write-Warning "Invoke-AiAnalysis: Could not read file: $fp"
                }
            } else {
                Write-Warning "Invoke-AiAnalysis: File not found: $fp"
            }
        }
    }
    $fullPrompt += $Prompt

    # Announce
    if (-not $Quiet) {
        $displayName = if ($analyzer -eq 'claude') { 'Claude' } else { 'Copilot' }
        Write-Host "`n🤖 Running AI analysis with $displayName ..." -ForegroundColor Magenta
        Write-Verbose "   Prompt length: $($fullPrompt.Length) chars"
    }

    # Invoke
    try {
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $result = & $analyzer -p $fullPrompt 2>&1
        $sw.Stop()
        $text = ($result | Out-String).Trim()

        Write-Verbose "   $analyzer responded in $('{0:F1}' -f $sw.Elapsed.TotalSeconds)s ($($text.Length) chars)"

        # Save to file if requested
        if ($OutputFile) {
            try {
                $text | Set-Content -LiteralPath $OutputFile -Encoding UTF8
                Write-Verbose "   Response saved to: $OutputFile"
            } catch {
                Write-Warning "Invoke-AiAnalysis: Could not write output file: $OutputFile"
            }
        }

        return $text
    } catch {
        Write-Warning "Invoke-AiAnalysis: $analyzer invocation failed: $($_.Exception.Message)"
        return $null
    }
}

# ============================================================================
# Backward-compatible wrapper (original name from test-utils.psm1).
# ============================================================================
<#
.SYNOPSIS
    Backward-compatible wrapper over Invoke-AiAnalysis.

.DESCRIPTION
    Mirrors the original test-utils.psm1 Invoke-CopilotAnalysis signature
    (LogPaths + ExtraPrompt) but delegates to Invoke-AiAnalysis internally.

.PARAMETER LogPaths
    File paths to include in the prompt (mapped to -FilePaths).

.PARAMETER ExtraPrompt
    Additional prompt text prepended before the log list.
#>
function Invoke-CopilotAnalysis {
    [CmdletBinding()]
    param(
        [string[]]$LogPaths,
        [string]$ExtraPrompt = ''
    )

    $prompt = ''
    if ($ExtraPrompt) {
        $prompt = "$ExtraPrompt`n"
    }
    $prompt += 'Analyse the failures in the provided log files.  Suggest root causes and fixes.'

    return Invoke-AiAnalysis -Prompt $prompt -FilePaths $LogPaths
}

# ============================================================================
# Export public functions
# ============================================================================
Export-ModuleMember -Function @(
    'Get-AiAnalyzer',
    'Test-AiAvailable',
    'Test-CopilotAvailable',
    'Invoke-AiAnalysis',
    'Invoke-CopilotAnalysis'
)
