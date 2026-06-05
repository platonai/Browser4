# process-task-source.ps1 — Monitor GitHub issues and a specified URL, dispatch tasks to coworker workflow.
#
# Usage:
#   .\bin\process-task-source.ps1 [options]
#
# Options:
#   -Repo      GitHub repository in "owner/repo" format  (default: platonai/Browser4)
#   -Assignee  GitHub username to filter issues by       (default: galaxyeye)
#   -Url       URL to poll every minute                  (default: "")
#   -Keyword   Keyword to search for in URL response     (default: @galaxyeye)
#   -Interval  Polling interval in seconds               (default: 60)
#   -Once      Run once and exit (no loop)
#
# Examples:
#   .\bin\process-task-source.ps1 -Repo platonai/Browser4 -Url https://example.com/tasks
#   .\bin\process-task-source.ps1 -Once

param(
    [string]$Repo     = "platonai/Browser4",
    [string]$Assignee = "galaxyeye",
    [string]$Url      = "",
    [string]$Keyword  = "@galaxyeye",
    [int]   $Interval = 60,
    [switch]$Once
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$configScriptPath = Join-Path $PSScriptRoot 'config.ps1'
. $configScriptPath

$RepoRoot = Get-WorkspaceRoot

$TasksDir = Resolve-TasksPath '1created'
New-Item -ItemType Directory -Force -Path $TasksDir | Out-Null

# ── helpers ────────────────────────────────────────────────────────────────────
function Get-Timestamp {
    # Millisecond-precision unix timestamp (pure digits)
    [long]([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
}

function Dispatch-Task {
    param([string]$Content)
    $ts  = Get-Timestamp
    $dst = Join-Path $TasksDir "$ts"
    # Write to a temp file first, then move atomically
    $tmp = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($tmp, $Content, [System.Text.Encoding]::UTF8)
    Move-Item -Path $tmp -Destination $dst -Force
    Write-Host "[$([DateTime]::UtcNow.ToString('o'))] Dispatched task file: $dst"
}

# ── GitHub issues monitor ──────────────────────────────────────────────────────
function Fetch-GitHubIssues {
    Write-Host "[$([DateTime]::UtcNow.ToString('o'))] Fetching latest issues from $Repo assigned to $Assignee ..."

    $ghAvailable = $null -ne (Get-Command gh -ErrorAction SilentlyContinue)

    if ($ghAvailable) {
        # Use gh CLI
        $jsonText = gh issue list `
            --repo $Repo `
            --search "assignee:$Assignee is:open -label:processed created:>2026-02-28" `
            --limit 20 `
            --json number,title,body,url,createdAt,state 2>$null
    }elseif ($env:GITHUB_TOKEN) {
        # Fall back to REST API via Invoke-RestMethod
        $headers  = @{
            Authorization = "Bearer $($env:GITHUB_TOKEN)"
            Accept        = "application/vnd.github+json"
        }
        $apiUrl   = "https://api.github.com/repos/$Repo/issues?assignee=$Assignee&per_page=20&state=open"
        $jsonText = (Invoke-RestMethod -Uri $apiUrl -Headers $headers -Method Get) | ConvertTo-Json -Depth 10
    } else {
        Write-Warning "Neither 'gh' CLI nor GITHUB_TOKEN is available; skipping GitHub monitor."
        return
    }

    if ([string]::IsNullOrWhiteSpace($jsonText)) { return }

    $issues = $jsonText | ConvertFrom-Json
    if ($issues -isnot [System.Array]) { $issues = @($issues) }

    foreach ($issue in $issues) {
        # Check if created after 2026-02-28
        $createdAt = $issue.created_at
        if ($createdAt -le "2026-02-28") { continue }

        $content = $issue | ConvertTo-Json -Depth 10 -Compress
        Dispatch-Task -Content $content

        # Mark issue as processed
        if ($ghAvailable) {
            $number = $issue.number
            Write-Host "[$([DateTime]::UtcNow.ToString('o'))] Marking issue #$number as processed..."
            gh issue edit $number --repo $Repo --add-label "processed" 2>$null
        }
    }
}

# ── URL monitor ────────────────────────────────────────────────────────────────
function Fetch-Url {
    if ([string]::IsNullOrWhiteSpace($Url)) { return }
    Write-Host "[$([DateTime]::UtcNow.ToString('o'))] Polling URL: $Url ..."

    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 30 -ErrorAction Stop
        $body     = $response.Content
    } catch {
        Write-Warning "Failed to fetch ${Url}: $_"
        return
    }

    if ($body -like "*$Keyword*") {
        # Calculate MD5 hash of the content
        $md5 = [System.BitConverter]::ToString((New-Object System.Security.Cryptography.MD5CryptoServiceProvider).ComputeHash([System.Text.Encoding]::UTF8.GetBytes($body))).Replace("-", "").ToLower()
        $marker = "<!-- TASK_SOURCE_MONITOR_HASH: $md5 -->"

        # Check if any existing task file contains this hash
        $existing = $false
        if (Test-Path $TasksDir) {
             # Use efficient grep-like search
             $files = Get-ChildItem -Path $TasksDir -Recurse -File
             if ($files) {
                 foreach ($file in $files) {
                     if (Select-String -Path $file.FullName -Pattern $md5 -SimpleMatch -Quiet) {
                         $existing = $true
                         break
                     }
                 }
             }
        }

        if ($existing) {
            Write-Host "[$([DateTime]::UtcNow.ToString('o'))] Duplicate task content detected (Hash: $md5). Skipping."
        } else {
            Write-Host "[$([DateTime]::UtcNow.ToString('o'))] Keyword '$Keyword' found in URL response."
            # Append hash to content so it can be detected next time
            Dispatch-Task -Content "$body`n$marker"
        }
    } else {
        Write-Host "[$([DateTime]::UtcNow.ToString('o'))] Keyword '$Keyword' not found; nothing to dispatch."
    }
}

# ── main loop ──────────────────────────────────────────────────────────────────
Write-Host "[$([DateTime]::UtcNow.ToString('o'))] Monitor started. repo=$Repo assignee=$Assignee url=$(if ($Url) { $Url } else { '<none>' }) keyword=$Keyword interval=${Interval}s"

do {
    Fetch-GitHubIssues
    Fetch-Url
    if (-not $Once) {
        Write-Host "[$([DateTime]::UtcNow.ToString('o'))] Sleeping ${Interval}s ..."
        Start-Sleep -Seconds $Interval
    }
} while (-not $Once)
