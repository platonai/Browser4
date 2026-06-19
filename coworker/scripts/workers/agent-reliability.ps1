#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Reliable agent invocation wrapper with timeout, retry, and structured output parsing.

.DESCRIPTION
    Wraps agent.ps1's Invoke-Agent with production-grade reliability:
    - Per-invocation timeout (prevents hung processes)
    - Exponential backoff retry (default: 3 attempts, 10s/30s/90s)
    - Structured output extraction (XML-style delimiters)
    - Structured logging via Write-CoworkerLog
    - Consistent error handling

    All coworker worker scripts should use Invoke-AgentWithRetry instead of calling
    Invoke-Agent directly.
#>

param(
    [string]$Prompt,

    [string[]]$AdditionalArguments = @(),

    [string]$RepoRoot,
    [string]$WorkingDirectory,

    [switch]$CaptureOutput,

    [int]$TimeoutSeconds = 300,

    [int]$MaxRetries = 3,
    [int[]]$RetryBackoffSeconds = @(10, 30, 90),

    [string]$OutputDelimiter = 'OUTPUT',

    [switch]$UseTargetRepository,

    [string]$LogComponent = 'agent-reliability',

    [switch]$NoRetry
)

$ErrorActionPreference = 'Stop'

# ── Dot-source dependencies ──────────────────────────────────────────────────
$workerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$configPath = Join-Path (Split-Path -Parent $workerDir) 'config.ps1'
. $configPath
. (Join-Path $workerDir 'agent.ps1')

# ── Prompt wrapping ──────────────────────────────────────────────────────────
function New-AgentPrompt {
    param(
        [Parameter(Mandatory)] [string]$Prompt,
        [string]$Delimiter = 'OUTPUT'
    )

    $openTag  = "<$Delimiter>"
    $closeTag = "</$Delimiter>"

    return @"
$Prompt

INSTRUCTIONS:
1. Complete the task described above.
2. Wrap your final answer in ${openTag}...${closeTag} tags.
3. Do NOT put anything inside the tags except the final output.
4. The content between ${openTag} and ${closeTag} will be extracted automatically.

${openTag}
(your final output here)
${closeTag}
"@
}

# ── Structured output parsing ────────────────────────────────────────────────
function ConvertFrom-AgentOutput {
    param(
        [Parameter(Mandatory)] [string]$RawOutput,
        [string]$Delimiter = 'OUTPUT'
    )

    $openTag  = "<$Delimiter>"
    $closeTag = "</$Delimiter>"

    if ([string]::IsNullOrWhiteSpace($RawOutput)) {
        Write-CoworkerLog -Message 'Agent returned empty output' -Level WARN -Component $LogComponent
        return ''
    }

    # Try delimiter extraction — escape regex metacharacters so user-supplied
    # delimiters containing special characters (e.g. "[OUTPUT]") don't break matching.
    $escapedOpen  = [regex]::Escape($openTag)
    $escapedClose = [regex]::Escape($closeTag)
    if ($RawOutput -match "(?s)${escapedOpen}(.*?)${escapedClose}") {
        $extracted = $Matches[1].Trim()
        Write-CoworkerLog -Message "Extracted output via delimiters ($($extracted.Length) chars)" -Level DEBUG -Component $LogComponent
        return $extracted
    }

    # Fallback: strip common conversational prefixes
    Write-CoworkerLog -Message 'Delimiter extraction failed, using heuristic fallback' -Level WARN -Component $LogComponent

    $prefixes = @(
        'Here is the refined draft:',
        'Here is the compressed version:',
        'Here is the output:',
        'Certainly!',
        'Sure, here is',
        'Here you go:',
        'The refined content is:',
        'Below is the'
    )
    $result = $RawOutput
    foreach ($prefix in $prefixes) {
        if ($result.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
            $result = $result.Substring($prefix.Length).Trim()
        }
    }

    # Strip code fences if present
    if ($result -match "```[\w]*\r?\n") {
        $result = $result -replace '^```[\w]*\r?\n', ''
        $result = $result -replace '\r?\n```\s*$', ''
    }

    return $result.Trim()
}

# ── Structured output wrapper ────────────────────────────────────────────────
function Invoke-AgentWithStructuredOutput {
    param(
        [Parameter(Mandatory)] [string]$Prompt,
        [string]$Delimiter = 'OUTPUT',
        [hashtable]$AgentParams = @{}
    )

    $wrappedPrompt = New-AgentPrompt -Prompt $Prompt -Delimiter $Delimiter
    $rawOutput = Invoke-Agent @AgentParams -Prompt $wrappedPrompt

    if (-not $CaptureOutput) {
        return $null
    }

    return ConvertFrom-AgentOutput -RawOutput $rawOutput -Delimiter $Delimiter
}

# ══════════════════════════════════════════════════════════════════════════════
# Invoke-AgentWithRetry — the reusable function that worker scripts call
# ══════════════════════════════════════════════════════════════════════════════

function Invoke-AgentWithRetry {
    [CmdletBinding()]
    param(
        [string]$Prompt,

        [string[]]$AdditionalArguments = @(),

        [string]$RepoRoot,
        [string]$WorkingDirectory,

        [switch]$CaptureOutput,

        [int]$TimeoutSeconds = 300,

        [int]$MaxRetries = 3,
        [int[]]$RetryBackoffSeconds = @(10, 30, 90),

        [string]$OutputDelimiter = 'OUTPUT',

        [switch]$UseTargetRepository,

        [string]$LogComponent = 'agent-reliability',

        [switch]$NoRetry
    )

    $attempt = 0
    $lastError = $null

    while ($attempt -lt $MaxRetries) {
        $attempt++
        $backoffIndex = [Math]::Min($attempt - 1, $RetryBackoffSeconds.Count - 1)
        $backoff = if ($RetryBackoffSeconds.Count -gt 0) { $RetryBackoffSeconds[$backoffIndex] } else { 0 }

        try {
            if ($attempt -gt 1) {
                Write-CoworkerLog -Message "Retry attempt $attempt of $MaxRetries (${backoff}s backoff)" -Level WARN -Component $LogComponent
                Start-Sleep -Seconds $backoff
            }

            # Build agent params for the NoRetry code path (delegates to Invoke-Agent
            # which now supports -TimeoutSeconds, so timeout is enforced in both paths).
            $agentParams = @{
                Prompt               = $Prompt
                AdditionalArguments  = $AdditionalArguments
                CaptureOutput        = $CaptureOutput
                UseTargetRepository  = $UseTargetRepository
                TimeoutSeconds       = $TimeoutSeconds
            }
            if ($PSBoundParameters.ContainsKey('RepoRoot'))        { $agentParams.RepoRoot = $RepoRoot }
            if ($PSBoundParameters.ContainsKey('WorkingDirectory')) { $agentParams.WorkingDirectory = $WorkingDirectory }

            if ($NoRetry) {
                return Invoke-AgentWithStructuredOutput -Prompt $Prompt -Delimiter $OutputDelimiter -AgentParams $agentParams
            }

            # Retry path: manage the process directly so we can distinguish timeout
            # failures from other errors and retry accordingly.
            $commandArgs = @{
                UseTargetRepository = $UseTargetRepository
            }
            if ($PSBoundParameters.ContainsKey('RepoRoot'))        { $commandArgs.RepoRoot = $RepoRoot }
            if ($PSBoundParameters.ContainsKey('WorkingDirectory')) { $commandArgs.WorkingDirectory = $WorkingDirectory }

            $command = Get-AgentCommand @commandArgs
            $wrappedPrompt = New-AgentPrompt -Prompt $Prompt -Delimiter $OutputDelimiter

            if ($CaptureOutput) {
                $stdOutPath = [System.IO.Path]::GetTempFileName()
                $stdErrPath = [System.IO.Path]::GetTempFileName()

                try {
                    $process = Start-AgentProcess -Executable $command.Executable -BaseArgs $command.BaseArgs `
                        -Prompt $wrappedPrompt -AdditionalArguments $AdditionalArguments `
                        -WorkingDirectory $command.WorkingDirectory `
                        -StdOutPath $stdOutPath -StdErrPath $stdErrPath -NoNewWindow -Backend $command.Backend

                    $completed = $process.WaitForExit($TimeoutSeconds * 1000)
                    if (-not $completed) {
                        $process.Kill($true)
                        throw "Agent process timed out after ${TimeoutSeconds}s (attempt $attempt)"
                    }

                    $global:LASTEXITCODE = $process.ExitCode

                    if (Test-Path $stdErrPath) {
                        $errorOutput = Get-Content -Path $stdErrPath -Raw -Encoding UTF8
                        if (-not [string]::IsNullOrWhiteSpace($errorOutput)) {
                            [Console]::Error.Write($errorOutput)
                        }
                    }

                    if ($process.ExitCode -ne 0) {
                        throw "Agent exited with code $($process.ExitCode) (attempt $attempt)"
                    }

                    if (Test-Path $stdOutPath) {
                        $rawOutput = Get-Content -Path $stdOutPath -Raw -Encoding UTF8
                        # Parse the already-captured output directly — do NOT call
                        # Invoke-AgentWithStructuredOutput again (it would re-invoke
                        # the agent, doubling cost and losing timeout protection).
                        return ConvertFrom-AgentOutput -RawOutput $rawOutput -Delimiter $OutputDelimiter
                    }

                    return ''
                }
                finally {
                    Remove-Item $stdOutPath -ErrorAction SilentlyContinue
                    Remove-Item $stdErrPath -ErrorAction SilentlyContinue
                }
            }
            else {
                $process = Start-AgentProcess -Executable $command.Executable -BaseArgs $command.BaseArgs `
                    -Prompt $wrappedPrompt -AdditionalArguments $AdditionalArguments `
                    -WorkingDirectory $command.WorkingDirectory -NoNewWindow -Backend $command.Backend

                $completed = $process.WaitForExit($TimeoutSeconds * 1000)
                if (-not $completed) {
                    $process.Kill($true)
                    throw "Agent process timed out after ${TimeoutSeconds}s (attempt $attempt)"
                }

                $global:LASTEXITCODE = $process.ExitCode

                if ($process.ExitCode -ne 0) {
                    throw "Agent exited with code $($process.ExitCode) (attempt $attempt)"
                }

                return $null
            }
        }
        catch {
            $lastError = $_
            Write-CoworkerLog -Message "Attempt $attempt failed: $_" -Level ERROR -Component $LogComponent

            if ($attempt -ge $MaxRetries) {
                Write-CoworkerLog -Message "All $MaxRetries attempts failed. Last error: $lastError" -Level ERROR -Component $LogComponent
                throw "Invoke-AgentWithRetry: all $MaxRetries attempts failed. Final error: $lastError"
            }
        }
    }

    throw 'Invoke-AgentWithRetry: unexpected end of retry loop'
}

# ══════════════════════════════════════════════════════════════════════════════
# Direct execution entry point — only when invoked directly (not dot-sourced)
# ══════════════════════════════════════════════════════════════════════════════

if ($MyInvocation.InvocationName -ne '.') {
    if ([string]::IsNullOrWhiteSpace($Prompt)) {
        throw 'Prompt is required when executing agent-reliability.ps1 directly.'
    }

    Invoke-AgentWithRetry @PSBoundParameters
}
