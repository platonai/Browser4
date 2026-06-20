# ── Coworker task-specific logging utilities ──────────────────────────────
# Dot-source after config.ps1.

function Write-ConsoleLine {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Message,
        [System.ConsoleColor]$ForegroundColor,
        [switch]$ErrorStream
    )

    $canUseHost = $false
    try {
        $canUseHost = [Environment]::UserInteractive -and $null -ne $Host -and $null -ne $Host.UI -and $null -ne $Host.UI.RawUI
    }
    catch {
        $canUseHost = $false
    }

    if ($canUseHost) {
        if ($PSBoundParameters.ContainsKey('ForegroundColor')) {
            Write-Host $Message -ForegroundColor $ForegroundColor
        } else {
            Write-Host $Message
        }
        return
    }

    $isRedirected = if ($ErrorStream) { [Console]::IsErrorRedirected } else { [Console]::IsOutputRedirected }
    if ($isRedirected) {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Message + [Environment]::NewLine)
        $stream = if ($ErrorStream) { [Console]::OpenStandardError() } else { [Console]::OpenStandardOutput() }
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush()
        return
    }

    if ($PSBoundParameters.ContainsKey('ForegroundColor')) {
        Write-Host $Message -ForegroundColor $ForegroundColor
    } else {
        Write-Host $Message
    }
}

function Write-LogMessage {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Message,
        [ValidateSet('INFO', 'WARN', 'ERROR')]
        [string]$Level = 'INFO'
    )

    $timestamp = (Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss')
    $logEntry = "[$timestamp] [$Level] $Message"

    switch ($Level) {
        'INFO' { Write-ConsoleLine -Message $logEntry }
        'WARN' { Write-ConsoleLine -Message $logEntry -ForegroundColor Yellow }
        'ERROR' { Write-ConsoleLine -Message $logEntry -ForegroundColor Red }
    }

    # Append to script log file (set by caller)
    if ($script:__ScriptLogPath) {
        $logEntry | Out-File -FilePath $script:__ScriptLogPath -Append -Encoding UTF8
    }
}

function Write-LogVerbose {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Message
    )

    $timestamp = (Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss')
    $logEntry = "[$timestamp] [DEBUG] $Message"

    if ($script:__ScriptLogPath) {
        $logEntry | Out-File -FilePath $script:__ScriptLogPath -Append -Encoding UTF8
    }
}
