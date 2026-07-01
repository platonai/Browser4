#!/usr/bin/env pwsh
<#
.SYNOPSIS
Shared helpers for the real-world scenario orchestration system.

.DESCRIPTION
Dot-source this module from run-all-scenarios.ps1, run-use-case.ps1, or
watchdog.ps1 to reuse state management, scenario discovery, token parsing,
credit-exhaustion detection, use-case parsing, and reporting helpers.

Exports:
  # State management
  Initialize-OrchestrationState
  Read-OrchestrationState
  Write-OrchestrationState
  Update-ScenarioState
  Update-OrchestratorHeartbeat

  # Scenario discovery
  Get-AllScenarios

  # Token parsing
  ConvertFrom-TokenUsage
  Format-TokenCount
  ConvertFrom-TokenSize

  # Credit detection
  Test-CreditExhaustion

  # Use-case parsing
  ConvertFrom-UseCaseFile

  # Display / reporting
  Write-OrchestratorBanner
  Write-ProgressStatus
  Write-ScenarioComplete
  Write-FinalReport
  Format-Duration

  # File locking
  Acquire-OrchestrationLock
  Release-OrchestrationLock
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ═══════════════════════════════════════════════════════════════════════════════
# Path resolution
# ═══════════════════════════════════════════════════════════════════════════════

# Repo root is 4 levels up from scripts/ in real-world-scenarios/
# (scripts -> real-world-scenarios -> browser4-tests -> repo root)
# But this module is dot-sourced from scripts in the same directory,
# so $PSScriptRoot is the scripts/ directory.
$script:RepoRoot = (Resolve-Path "$PSScriptRoot/../../..").Path

$script:UseCasesDir = Join-Path $script:RepoRoot `
    'browser4-tests\browser4-tests-common\src\main\resources\e2e\scenarios\happy_path\use-cases'

$script:TasksDir = Join-Path $script:RepoRoot `
    'browser4-tests\real-world-scenarios\tasks'

$script:ReportsRoot = Join-Path $script:RepoRoot 'target\test-reports'
$script:StateDir = Join-Path $script:ReportsRoot 'state'
$script:ReportsOutputDir = Join-Path $script:ReportsRoot 'reports'
$script:ScenariosOutputDir = Join-Path $script:ReportsRoot 'scenarios'

function Get-OrchestrationStatePath {
    <#
    .SYNOPSIS
    Returns the absolute path to the orchestration state JSON file.
    #>
    return Join-Path $script:StateDir 'orchestration-state.json'
}

function Get-OrchestrationLockPath {
    $statePath = Get-OrchestrationStatePath
    return "$statePath.lock"
}

# ═══════════════════════════════════════════════════════════════════════════════
# File locking (modeled after bin/maintenance/common/MaintenanceState.ps1)
# ═══════════════════════════════════════════════════════════════════════════════

function Acquire-OrchestrationLock {
    <#
    .SYNOPSIS
    Acquires an exclusive lock on the orchestration state file.
    Returns a disposable lock handle, or $null if lock cannot be acquired.

    .DESCRIPTION
    Uses a .lock file with retry and stale-lock detection.
    A lock older than 60 seconds is considered stale and broken.
    Retries up to 10 times with exponential backoff (100ms -> 5s max).
    #>
    param(
        [int] $MaxRetries = 10,
        [int] $BaseDelayMs = 100,
        [int] $StaleLockSeconds = 60
    )

    $lockPath = Get-OrchestrationLockPath
    $lockDir = Split-Path $lockPath -Parent

    # Ensure directory exists
    if (-not (Test-Path $lockDir)) {
        New-Item -ItemType Directory -Path $lockDir -Force | Out-Null
    }

    $delay = $BaseDelayMs

    for ($i = 0; $i -lt $MaxRetries; $i++) {
        try {
            $stream = [System.IO.File]::Open(
                $lockPath,
                [System.IO.FileMode]::CreateNew,
                [System.IO.FileAccess]::Write,
                [System.IO.FileShare]::None
            )
            $writer = New-Object System.IO.StreamWriter($stream, [System.Text.Encoding]::UTF8)
            $writer.WriteLine("pid=$pid")
            $writer.WriteLine("machine=$env:COMPUTERNAME")
            $writer.WriteLine("timestamp=$(Get-Date -Format 'yyyy-MM-ddTHH:mm:ssK')")
            $writer.Flush()
            $stream.Position = 0
            return @{
                Stream = $stream
                Path   = $lockPath
            }
        }
        catch [System.IO.IOException] {
            # Lock already exists -- check if stale
            if (Test-Path $lockPath) {
                $lockItem = Get-Item $lockPath -ErrorAction SilentlyContinue
                if ($lockItem) {
                    $lockAge = (Get-Date) - $lockItem.LastWriteTime
                    if ($lockAge.TotalSeconds -gt $StaleLockSeconds) {
                        Write-Host "  Breaking stale orchestration lock (age: $([math]::Round($lockAge.TotalSeconds))s)" -ForegroundColor DarkYellow
                        Remove-Item $lockPath -Force -ErrorAction SilentlyContinue
                        continue
                    }
                }
            }
        }

        if ($i -lt $MaxRetries - 1) {
            Start-Sleep -Milliseconds $delay
            $delay = [Math]::Min($delay * 2, 5000)
        }
    }

    Write-Host "ERROR: Could not acquire orchestration state lock after $MaxRetries retries" -ForegroundColor Red
    return $null
}

function Release-OrchestrationLock {
    <#
    .SYNOPSIS
    Releases a previously acquired orchestration state lock.
    #>
    param(
        [Parameter(Mandatory = $true)]
        $LockHandle
    )

    if ($null -eq $LockHandle) { return }

    try {
        if ($LockHandle.Stream) {
            $LockHandle.Stream.Dispose()
        }
    }
    catch {
        Write-Host "WARNING: Error disposing lock stream: $($_.Exception.Message)" -ForegroundColor Yellow
    }

    if ($LockHandle.Path -and (Test-Path $LockHandle.Path)) {
        Remove-Item $LockHandle.Path -Force -ErrorAction SilentlyContinue
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# State management
# ═══════════════════════════════════════════════════════════════════════════════

function Initialize-OrchestrationState {
    <#
    .SYNOPSIS
    Creates a fresh orchestration state object for a new test run.

    .PARAMETER Scenarios
    Array of scenario descriptors from Get-AllScenarios.

    .PARAMETER Mode
    'dev' or 'production'.

    .PARAMETER Force
    If set, overwrites any existing state file.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [array] $Scenarios,

        [string] $Mode = 'dev',

        [switch] $Force
    )

    $statePath = Get-OrchestrationStatePath

    if ((Test-Path $statePath) -and -not $Force) {
        Write-Host "State file already exists. Use -Resume to continue or -Force to overwrite." -ForegroundColor Yellow
        return $null
    }

    $machine = if ($env:COMPUTERNAME) { $env:COMPUTERNAME } else { [System.Environment]::MachineName }
    $now = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssK')

    $scenarioEntries = @()
    foreach ($s in $Scenarios) {
        $scenarioEntries += [PSCustomObject]@{
            id            = $s.id
            type          = $s.type
            sourceFile    = $s.sourceFile
            category      = $s.category
            level         = $s.level
            sortOrder     = $s.sortOrder
            status        = 'pending'
            startedAt     = $null
            completedAt   = $null
            durationMs    = 0
            exitCode      = $null
            attempts      = 0
            tokens        = [PSCustomObject]@{
                byModel    = [PSCustomObject]@{}
                totalInput  = 0
                totalOutput = 0
                totalCached = 0
            }
            rawOutputFile = $null
            issuesFile    = $null
            errorSummary  = $null
        }
    }

    $state = [PSCustomObject]@{
        version       = 1
        schema        = 'orchestration-state-v1'
        createdAt     = $now
        updatedAt     = $now
        orchestrator  = [PSCustomObject]@{
            pid                  = $pid
            startedAt            = $now
            heartbeat            = $now
            heartbeatIntervalMs  = 30000
            totalScenarios       = $Scenarios.Count
            completedScenarios   = 0
            passed               = 0
            failed               = 0
            skipped              = 0
            consecutiveFailures  = 0
            exitCode             = $null
            globalAbort          = $false
            abortReason          = $null
            tokenTotals          = [PSCustomObject]@{
                byModel          = [PSCustomObject]@{}
                grandTotalInput  = 0
                grandTotalOutput = 0
                grandTotalCached = 0
            }
            mode                 = $Mode
        }
        scenarios     = $scenarioEntries
    }

    Write-OrchestrationState -State $state -StateFilePath $statePath
    return $state
}

function Read-OrchestrationState {
    <#
    .SYNOPSIS
    Reads the orchestration state from disk. Returns $null if file missing or corrupt.
    #>
    param(
        [string] $StateFilePath
    )

    if (-not $StateFilePath) {
        $StateFilePath = Get-OrchestrationStatePath
    }

    if (-not (Test-Path $StateFilePath)) {
        return $null
    }

    try {
        $raw = Get-Content $StateFilePath -Raw -Encoding UTF8
        if (-not $raw.Trim()) {
            return $null
        }
        $state = $raw | ConvertFrom-Json

        # Validate version
        if ($state.version -and $state.version -gt 1) {
            Write-Host "WARNING: State file version $($state.version) is newer than supported (1)." -ForegroundColor Yellow
            return $null
        }

        return $state
    }
    catch {
        Write-Host "WARNING: Failed to parse state file: $($_.Exception.Message)" -ForegroundColor Yellow
        return $null
    }
}

function Write-OrchestrationState {
    <#
    .SYNOPSIS
    Writes orchestration state to disk atomically (temp file + rename) with file locking.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [PSCustomObject] $State,

        [Parameter(Mandatory = $true)]
        [string] $StateFilePath
    )

    $stateDir = Split-Path $StateFilePath -Parent
    if (-not (Test-Path $stateDir)) {
        New-Item -ItemType Directory -Path $stateDir -Force | Out-Null
    }

    $lock = Acquire-OrchestrationLock
    if ($null -eq $lock) {
        Write-Host "ERROR: Could not acquire lock to write orchestration state" -ForegroundColor Red
        return $false
    }

    try {
        # Update timestamp
        $State.updatedAt = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssK')

        $tmpFile = "$StateFilePath.tmp"
        $json = $State | ConvertTo-Json -Depth 10
        $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
        [System.IO.File]::WriteAllText($tmpFile, $json, $utf8NoBom)

        # Atomic rename
        if (Test-Path $StateFilePath) {
            Remove-Item $StateFilePath -Force
        }
        Rename-Item -LiteralPath $tmpFile -NewName (Split-Path $StateFilePath -Leaf) -Force

        return $true
    }
    catch {
        Write-Host "ERROR: Failed to write orchestration state: $($_.Exception.Message)" -ForegroundColor Red
        # Clean up temp file
        if (Test-Path "$StateFilePath.tmp") {
            Remove-Item "$StateFilePath.tmp" -Force -ErrorAction SilentlyContinue
        }
        return $false
    }
    finally {
        Release-OrchestrationLock -LockHandle $lock
    }
}

function Update-ScenarioState {
    <#
    .SYNOPSIS
    Updates a single scenario entry in the state object and persists to disk.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [PSCustomObject] $State,

        [Parameter(Mandatory = $true)]
        [string] $ScenarioId,

        [Parameter(Mandatory = $true)]
        [ValidateSet('pending', 'running', 'passed', 'failed', 'skipped')]
        [string] $Status,

        [hashtable] $Fields = @{}
    )

    $found = $false
    for ($i = 0; $i -lt $State.scenarios.Count; $i++) {
        if ($State.scenarios[$i].id -eq $ScenarioId) {
            $State.scenarios[$i].status = $Status
            foreach ($key in $Fields.Keys) {
                $State.scenarios[$i].$key = $Fields[$key]
            }
            $found = $true
            break
        }
    }

    if (-not $found) {
        Write-Host "WARNING: Scenario '$ScenarioId' not found in state." -ForegroundColor Yellow
        return $false
    }

    # Update orchestrator-level counters
    $completed = 0; $passed = 0; $failed = 0; $skipped = 0
    foreach ($s in $State.scenarios) {
        if ($s.status -in @('passed', 'failed', 'skipped')) {
            $completed++
        }
        if ($s.status -eq 'passed') { $passed++ }
        if ($s.status -eq 'failed') { $failed++ }
        if ($s.status -eq 'skipped') { $skipped++ }
    }
    $State.orchestrator.completedScenarios = $completed
    $State.orchestrator.passed = $passed
    $State.orchestrator.failed = $failed
    $State.orchestrator.skipped = $skipped

    $statePath = Get-OrchestrationStatePath
    return Write-OrchestrationState -State $State -StateFilePath $statePath
}

function Update-OrchestratorHeartbeat {
    <#
    .SYNOPSIS
    Writes the current timestamp to the orchestrator heartbeat field.
    Does NOT acquire the full write lock -- uses a lightweight approach.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [PSCustomObject] $State
    )

    $State.orchestrator.heartbeat = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssK')
    $statePath = Get-OrchestrationStatePath
    return Write-OrchestrationState -State $State -StateFilePath $statePath
}

# ═══════════════════════════════════════════════════════════════════════════════
# Scenario discovery
# ═══════════════════════════════════════════════════════════════════════════════

function Get-UseCaseLevel {
    <#
    .SYNOPSIS
    Extracts the difficulty level from use-case file comment lines.
    #>
    param(
        [string[]] $Lines = @()
    )

    if ($null -eq $Lines -or $Lines.Count -eq 0) { return 'Unknown' }

    foreach ($line in $Lines) {
        if ($line -match '#\s*Level:\s*(.+)') {
            $level = $Matches[1].Trim()
            switch -Wildcard ($level) {
                '*Simple*'    { return 'Simple' }
                '*Complex*'   { return 'Complex' }
                '*Enterprise*'{ return 'Enterprise' }
                default       { return $level }
            }
        }
    }
    return 'Unknown'
}

function Get-UseCaseNumber {
    <#
    .SYNOPSIS
    Extracts the numeric prefix from a use-case filename.
    "01-ecommerce-product-comparison.txt" -> "01"
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string] $FileName
    )

    if ($FileName -match '^(\d+)-') {
        return $Matches[1]
    }
    return '99'
}

function Get-AllScenarios {
    <#
    .SYNOPSIS
    Discovers all scenarios from both .txt use-case files and .md task files.

    .DESCRIPTION
    Scans the use-cases/ directory for .txt files and the tasks/ directory
    for .md files. Assigns each scenario a sort order:
      1. Use-case Simple   (01-04)
      2. Use-case Complex  (06-11)
      3. Use-case Enterprise (12-14)
      4. Use-case Chinese  (20-24)
      5. .md generic tasks (alphabetical)
      6. .md browser4 tasks (alphabetical)
      7. .md mock-site tasks (alphabetical)

    .OUTPUTS
    Array of PSCustomObject with: id, type, sourceFile, category, level, sortOrder
    #>
    param(
        [string] $UseCasesDir,
        [string] $TasksDir
    )

    if (-not $UseCasesDir) { $UseCasesDir = $script:UseCasesDir }
    if (-not $TasksDir)    { $TasksDir    = $script:TasksDir }

    $scenarios = [System.Collections.ArrayList]::new()
    $sortCounter = 0

    # ── Use-case .txt files ──────────────────────────────────────────────────
    if (Test-Path $UseCasesDir) {
        $useCaseFiles = Get-ChildItem -Path $UseCasesDir -Filter '*.txt' -File -ErrorAction SilentlyContinue |
            Sort-Object Name

        # Group by level for ordering
        $levelOrder = @{
            'Simple'     = 0
            'Complex'    = 1
            'Enterprise' = 2
        }

        $grouped = $useCaseFiles | ForEach-Object {
            $lines = @(Get-Content $_.FullName -TotalCount 5 -ErrorAction SilentlyContinue)
            if ($null -eq $lines -or $lines.Count -eq 0) { $lines = @('') }
            [PSCustomObject]@{
                File  = $_
                Level = Get-UseCaseLevel -Lines $lines
                Num   = Get-UseCaseNumber -FileName $_.Name
            }
        } | Sort-Object {
            $lo = $levelOrder[$_.Level]
            if ($null -eq $lo) { $lo = 99 }
            "$lo-$($_.Num)"
        }

        foreach ($item in $grouped) {
            $sortCounter++
            $id = [System.IO.Path]::GetFileNameWithoutExtension($item.File.Name)

            # Make source path relative to repo root
            $relPath = $item.File.FullName.Replace($script:RepoRoot, '').TrimStart('\', '/')

            [void]$scenarios.Add([PSCustomObject]@{
                id         = $id
                type       = 'use-case'
                sourceFile = $relPath
                category   = $null
                level      = $item.Level
                sortOrder  = $sortCounter
            })
        }
    }

    # ── Task .md files ───────────────────────────────────────────────────────
    if (Test-Path $TasksDir) {
        $taskFiles = Get-ChildItem -Path $TasksDir -Filter '*.md' -Recurse -File -ErrorAction SilentlyContinue

        # Category ordering: generic < browser4 < mock-site
        $categoryOrder = @{
            'generic'   = 0
            'browser4'  = 1
            'mock-site' = 2
        }

        $taskItems = $taskFiles | ForEach-Object {
            # Use relative path from TasksDir to avoid false matches on repo name
            $relPath = $_.FullName.Replace($TasksDir, '')
            $cat = ''
            if ($relPath -match '\\mock-site\\' -or $relPath -match '/mock-site/') {
                $cat = 'mock-site'
            }
            elseif ($relPath -match '\\browser4\\' -or $relPath -match '/browser4/') {
                $cat = 'browser4'
            }
            elseif ($relPath -match '\\generic\\' -or $relPath -match '/generic/') {
                $cat = 'generic'
            }
            elseif ($relPath -match '\\real-world\\' -or $relPath -match '/real-world/') {
                $cat = 'real-world'
            }

            [PSCustomObject]@{
                File     = $_
                Category = $cat
            }
        } | Sort-Object {
            $co = $categoryOrder[$_.Category]
            if ($null -eq $co) { $co = 99 }
            "$co-$($_.File.Name)"
        }

        foreach ($item in $taskItems) {
            $sortCounter++
            $id = [System.IO.Path]::GetFileNameWithoutExtension($item.File.Name)
            $relPath = $item.File.FullName.Replace($script:RepoRoot, '').TrimStart('\', '/')

            [void]$scenarios.Add([PSCustomObject]@{
                id         = $id
                type       = 'md-task'
                sourceFile = $relPath
                category   = $item.Category
                level      = $null
                sortOrder  = $sortCounter
            })
        }
    }

    return $scenarios.ToArray()
}

# ═══════════════════════════════════════════════════════════════════════════════
# Token usage parsing
# ═══════════════════════════════════════════════════════════════════════════════

function ConvertFrom-TokenSize {
    <#
    .SYNOPSIS
    Parses a human-readable token size string to a long integer.
    "917.2k" -> 917200, "1.2M" -> 1200000, "500" -> 500
    #>
    param(
        [string] $SizeStr = ''
    )

    if ([string]::IsNullOrWhiteSpace($SizeStr)) {
        return 0
    }

    $str = $SizeStr.Trim().ToLower()
    if ($str -match '^([\d.]+)\s*([kmb])?$') {
        $value = [double]::Parse($Matches[1],
            [System.Globalization.CultureInfo]::InvariantCulture)
        $unit = $Matches[2]
        switch ($unit) {
            'k' { return [long]($value * 1000) }
            'm' { return [long]($value * 1000000) }
            'b' { return [long]($value * 1000000000) }
            default { return [long]$value }
        }
    }
    return 0
}

function Format-TokenCount {
    <#
    .SYNOPSIS
    Formats a token count integer to human-readable form.
    917200 -> "917.2k", 1200000 -> "1.2M"
    #>
    param(
        [Parameter(Mandatory = $true)]
        [long] $Count
    )

    if ($Count -ge 1000000000) {
        return '{0:F1}B' -f ($Count / 1000000000)
    }
    if ($Count -ge 1000000) {
        return '{0:F1}M' -f ($Count / 1000000)
    }
    if ($Count -ge 1000) {
        return '{0:F1}k' -f ($Count / 1000)
    }
    return "$Count"
}

function ConvertFrom-TokenUsage {
    <#
    .SYNOPSIS
    Parses the "Breakdown by AI model:" section from Claude Code Session Summary output.

    .DESCRIPTION
    Uses the same regex pattern from coworker/scripts/workers/count-total-token-usage.py:
      ^(\S+)\s+([\d.]+[kmb]?) in,\s+([\d.]+[kmb]?) out,\s+([\d.]+[kmb]?) cached

    .PARAMETER Output
    Full captured output from the claude CLI session.

    .OUTPUTS
    Hashtable with keys: byModel (hashtable of model->{input,output,cached}), rawText
    #>
    param(
        [string] $Output = ''
    )

    $result = @{
        byModel = @{}
        rawText = ''
    }

    if ([string]::IsNullOrWhiteSpace($Output)) {
        return $result
    }

    # Normalize line endings
    $normalized = $Output -replace '\r\n', "`n"

    # Find the "Breakdown by AI model:" section
    if ($normalized -notmatch '(?s)Breakdown by AI model:(.+?)(?:\n\n|\n\s*\n|\Z)') {
        return $result
    }

    $breakdownBlock = $Matches[1]
    $result.rawText = $breakdownBlock.Trim()

    # Parse each model line: model-name   count in, count out, count cached
    $modelLinePattern = '^\s*(\S+)\s+([\d.]+[kmb]?)\s+in,\s+([\d.]+[kmb]?)\s+out,\s+([\d.]+[kmb]?)\s+cached'
    $lines = $breakdownBlock -split "`n"

    foreach ($line in $lines) {
        if ($line -match $modelLinePattern) {
            $modelName = $Matches[1].Trim()
            $inputTokens  = ConvertFrom-TokenSize -SizeStr $Matches[2]
            $outputTokens = ConvertFrom-TokenSize -SizeStr $Matches[3]
            $cachedTokens = ConvertFrom-TokenSize -SizeStr $Matches[4]

            $result.byModel[$modelName] = @{
                input  = $inputTokens
                output = $outputTokens
                cached = $cachedTokens
            }
        }
    }

    return $result
}

function Merge-TokenUsage {
    <#
    .SYNOPSIS
    Merges parsed token usage into state accumulator.

    .PARAMETER State
    The orchestration state object (updated in-place).

    .PARAMETER TokenUsage
    Result from ConvertFrom-TokenUsage.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [PSCustomObject] $State,

        [Parameter(Mandatory = $true)]
        [hashtable] $TokenUsage
    )

    $totals = $State.orchestrator.tokenTotals

    foreach ($model in $TokenUsage.byModel.Keys) {
        $usage = $TokenUsage.byModel[$model]

        # Check if model already exists as a property
        $existingNames = @($totals.byModel.PSObject.Properties | ForEach-Object { $_.Name })
        if ($existingNames -contains $model) {
            $totals.byModel.$model.input  += $usage.input
            $totals.byModel.$model.output += $usage.output
            $totals.byModel.$model.cached += $usage.cached
        }
        else {
            # Need to rebuild the byModel PSCustomObject
            $newByModel = @{}
            foreach ($prop in $totals.byModel.PSObject.Properties) {
                $newByModel[$prop.Name] = $prop.Value
            }
            $newByModel[$model] = [PSCustomObject]@{
                input  = $usage.input
                output = $usage.output
                cached = $usage.cached
            }
            $totals.byModel = [PSCustomObject]$newByModel
        }
    }

    $totals.grandTotalInput  += ($TokenUsage.byModel.Values | Measure-Object -Property input  -Sum).Sum
    $totals.grandTotalOutput += ($TokenUsage.byModel.Values | Measure-Object -Property output -Sum).Sum
    $totals.grandTotalCached += ($TokenUsage.byModel.Values | Measure-Object -Property cached -Sum).Sum
}

# ═══════════════════════════════════════════════════════════════════════════════
# Credit exhaustion detection
# ═══════════════════════════════════════════════════════════════════════════════

$script:CreditExhaustionPatterns = @(
    @{ Pattern = 'insufficient.*(?:credits?|balance|quota)';          Flags = 'IgnoreCase' }
    @{ Pattern = '(?:credits?|balance|quota).*insufficient';          Flags = 'IgnoreCase' }
    @{ Pattern = '\b402\b';                                           Flags = 'IgnoreCase' }
    @{ Pattern = 'Payment Required';                                  Flags = 'IgnoreCase' }
    @{ Pattern = 'account.*(?:credits?|balance)';                     Flags = 'IgnoreCase' }
    @{ Pattern = '(?:credits?|balance).*account';                     Flags = 'IgnoreCase' }
    @{ Pattern = 'run out of.*(?:credits?|money|tokens)';             Flags = 'IgnoreCase' }
    @{ Pattern = '(?:credits?|money|tokens).*run out';                Flags = 'IgnoreCase' }
    @{ Pattern = 'billing.*(?:error|issue|problem|failed)';           Flags = 'IgnoreCase' }
    @{ Pattern = '(?:error|issue|problem|failed).*billing';           Flags = 'IgnoreCase' }
    @{ Pattern = 'API key.*(?:invalid|expired|revoked|disabled|suspended)'; Flags = 'IgnoreCase' }
    @{ Pattern = 'authentication.*(?:failed|expired|required)';       Flags = 'IgnoreCase' }
    @{ Pattern = 'rate limit.*(?:exceeded|reached)';                  Flags = 'IgnoreCase' }
    @{ Pattern = 'Error:.*(?:auth|credential|permission|denied)';     Flags = 'IgnoreCase' }
    @{ Pattern = 'not enough.*(?:credits?|tokens?|balance)';          Flags = 'IgnoreCase' }
    @{ Pattern = '(?:credits?|tokens?|balance).*not enough';          Flags = 'IgnoreCase' }
    @{ Pattern = 'credit.*(?:exhausted|depleted|empty|zero)';         Flags = 'IgnoreCase' }
    @{ Pattern = '(?:exhausted|depleted|empty|zero).*credit';         Flags = 'IgnoreCase' }
)

function Test-CreditExhaustion {
    <#
    .SYNOPSIS
    Checks captured output and exit code for credit/billing exhaustion signals.

    .DESCRIPTION
    Scans the output against a list of known credit-exhaustion patterns.
    Returns a hashtable with detection result and details.

    .PARAMETER Output
    Captured output from the claude CLI session (stdout + stderr combined).

    .PARAMETER ExitCode
    Process exit code.

    .PARAMETER AdditionalPatterns
    Extra regex patterns to check beyond the built-in list.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string] $Output,

        [int] $ExitCode = 0,

        [array] $AdditionalPatterns = @()
    )

    $allPatterns = $script:CreditExhaustionPatterns + $AdditionalPatterns

    foreach ($entry in $allPatterns) {
        $regexOpts = [System.Text.RegularExpressions.RegexOptions]::None
        if ($entry.Flags -eq 'IgnoreCase') {
            $regexOpts = [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
        }
        $regex = [regex]::new($entry.Pattern, $regexOpts)
        if ($regex.IsMatch($Output)) {
            return @{
                detected       = $true
                reason         = "Credit exhaustion detected: matched pattern '$($entry.Pattern)'"
                matchedPattern = $entry.Pattern
            }
        }
    }

    return @{ detected = $false; reason = ''; matchedPattern = '' }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Use-case file parsing
# ═══════════════════════════════════════════════════════════════════════════════

function ConvertFrom-UseCaseFile {
    <#
    .SYNOPSIS
    Parses a .txt use-case file into metadata + numbered task instructions.

    .DESCRIPTION
    Lines starting with '#' are metadata comments. The remaining numbered
    lines (e.g. "1. do something") form the task instructions.

    .OUTPUTS
    Hashtable with:
      ScenarioName  - Extracted from first # heading or filename
      Level         - "Simple", "Complex", "Enterprise", or "Unknown"
      Type          - Use case type from metadata
      Description   - Description from metadata
      Instructions  - Single string of numbered task steps
      MetadataLines - Array of raw comment lines
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string] $FilePath
    )

    if (-not (Test-Path -LiteralPath $FilePath -PathType Leaf)) {
        throw "Use-case file not found: $FilePath"
    }

    $rawContent = Get-Content -Path $FilePath -Raw -Encoding UTF8
    if ([string]::IsNullOrWhiteSpace($rawContent)) {
        throw "Use-case file is empty: $FilePath"
    }

    $normalized = $rawContent -replace '\r\n', "`n"
    $lines = $normalized -split "`n"

    $metadataLines = [System.Collections.ArrayList]::new()
    $instructionLines = [System.Collections.ArrayList]::new()
    $scenarioName = ''
    $level = 'Unknown'
    $type = ''
    $description = ''

    foreach ($line in $lines) {
        $trimmed = $line.Trim()
        if (-not $trimmed) { continue }

        if ($trimmed.StartsWith('#')) {
            [void]$metadataLines.Add($trimmed)

            # Extract structured metadata
            if (-not $scenarioName -and $trimmed -match '#\s*Use Case\s*\d*:?\s*(.+)') {
                $scenarioName = $Matches[1].Trim()
            }
            if ($trimmed -match '#\s*Level:\s*(.+)') {
                $level = $Matches[1].Trim()
            }
            if ($trimmed -match '#\s*Type:\s*(.+)') {
                $type = $Matches[1].Trim()
            }
            if ($trimmed -match '#\s*Description:\s*(.+)') {
                $description = $Matches[1].Trim()
            }
        }
        else {
            # Collect instruction lines (strip numbering for cleaner prompt building)
            [void]$instructionLines.Add($trimmed)
        }
    }

    # Fallback: use filename as scenario name
    if (-not $scenarioName) {
        $scenarioName = [System.IO.Path]::GetFileNameWithoutExtension($FilePath)
    }

    # Normalize level
    switch -Wildcard ($level) {
        '*Simple*'     { $level = 'Simple' }
        '*Complex*'    { $level = 'Complex' }
        '*Enterprise*' { $level = 'Enterprise' }
    }

    $instructions = ($instructionLines -join "`n").Trim()
    if ([string]::IsNullOrWhiteSpace($instructions)) {
        throw "No task instructions found in use-case file: $FilePath"
    }

    return @{
        ScenarioName  = $scenarioName
        Level         = $level
        Type          = $type
        Description   = $description
        Instructions  = $instructions
        MetadataLines = $metadataLines.ToArray()
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Display / reporting helpers
# ═══════════════════════════════════════════════════════════════════════════════

function Format-Duration {
    <#
    .SYNOPSIS
    Formats a TimeSpan into a human-readable string.
    #>
    param(
        [TimeSpan] $Duration
    )
    if ($Duration.TotalSeconds -lt 1) { return '<1s' }
    if ($Duration.TotalMinutes -lt 1) {
        return '{0:F1}s' -f $Duration.TotalSeconds
    }
    if ($Duration.TotalHours -lt 1) {
        return '{0}m {1}s' -f $Duration.Minutes, $Duration.Seconds
    }
    return '{0}h {1}m {2}s' -f $Duration.Hours, $Duration.Minutes, $Duration.Seconds
}

function Write-OrchestratorBanner {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Text
    )
    $width = 80
    try {
        $width = [Math]::Min(80, [Console]::WindowWidth - 4)
    }
    catch {
        # Non-interactive console (e.g., piped/redirected) — use default 80
    }
    $line = '=' * [Math]::Min($width, $Text.Length + 8)
    Write-Host ''
    Write-Host $line -ForegroundColor Cyan
    Write-Host "  $Text" -ForegroundColor Cyan
    Write-Host $line -ForegroundColor Cyan
}

function Write-ProgressStatus {
    <#
    .SYNOPSIS
    Writes a live-updating status line showing progress, pass/fail, ETA, and token usage.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [PSCustomObject] $State,

        [datetime] $StartTime,

        $CurrentScenario = $null,

        [string] $CurrentAction = ''
    )

    $o = $State.orchestrator
    $total = $o.totalScenarios
    $completed = $o.completedScenarios
    $passed = $o.passed
    $failed = $o.failed
    $pct = if ($total -gt 0) { [math]::Round(($completed / $total) * 100, 1) } else { 0 }

    # Progress bar
    $barWidth = 30
    $filled = [math]::Floor(($completed / [Math]::Max(1, $total)) * $barWidth)
    $empty = $barWidth - $filled
    $bar = '[' + ('#' * $filled) + ('-' * $empty) + ']'

    # ETA
    $eta = '--:--'
    if ($completed -gt 0 -and $StartTime) {
        $elapsed = (Get-Date) - $StartTime
        $avgPerScenario = $elapsed.TotalSeconds / $completed
        $remaining = ($total - $completed) * $avgPerScenario
        if ($remaining -gt 0) {
            $etaTime = (Get-Date).AddSeconds($remaining)
            $eta = $etaTime.ToString('HH:mm')
        }
    }

    # Token totals
    $tokenStr = ''
    $gt = $o.tokenTotals
    if ($gt.grandTotalInput -gt 0) {
        $tokenStr = " | Tokens: $(Format-TokenCount $gt.grandTotalInput) in"
    }

    # Status line
    $statusLine = "$bar $completed/$total ($pct%) | Pass: $passed | Fail: $failed | Consec: $($o.consecutiveFailures) | ETA: $eta$tokenStr"

    if ($CurrentScenario) {
        $statusLine += " | Now: $($CurrentScenario.id)"
        if ($CurrentAction) {
            $statusLine += " [$CurrentAction]"
        }
    }

    Write-Host $statusLine -ForegroundColor Cyan
}

function Write-ScenarioComplete {
    <#
    .SYNOPSIS
    Writes a completion line for a single scenario.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [PSCustomObject] $Scenario,

        [int] $ExitCode = 0,

        [long] $DurationMs = 0,

        [hashtable] $TokenUsage = $null
    )

    $icon  = if ($ExitCode -eq 0) { '[PASS]' } else { '[FAIL]' }
    $color = if ($ExitCode -eq 0) { 'Green' } else { 'Red' }
    $duration = Format-Duration -Duration ([TimeSpan]::FromMilliseconds($DurationMs))

    $tokenInfo = ''
    if ($TokenUsage -and $TokenUsage.byModel.Count -gt 0) {
        $totalIn = ($TokenUsage.byModel.Values | Measure-Object -Property input -Sum).Sum
        $totalOut = ($TokenUsage.byModel.Values | Measure-Object -Property output -Sum).Sum
        $tokenInfo = " | $(Format-TokenCount $totalIn) in, $(Format-TokenCount $totalOut) out"
    }

    Write-Host "  $icon $($Scenario.id) ($duration$tokenInfo)" -ForegroundColor $color

    if ($ExitCode -ne 0 -and $Scenario.errorSummary) {
        Write-Host "    Error: $($Scenario.errorSummary)" -ForegroundColor DarkGray
    }
}

function Write-FinalReport {
    <#
    .SYNOPSIS
    Generates both JSON and Markdown final report files.

    .PARAMETER State
    The final orchestration state object.

    .PARAMETER JsonPath
    Full path for the JSON report output.

    .PARAMETER MarkdownPath
    Full path for the Markdown report output.

    .PARAMETER StartTime
    When the run started (for total duration).
    #>
    param(
        [Parameter(Mandatory = $true)]
        [PSCustomObject] $State,

        [Parameter(Mandatory = $true)]
        [string] $JsonPath,

        [Parameter(Mandatory = $true)]
        [string] $MarkdownPath,

        [datetime] $StartTime
    )

    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)

    # ── Ensure output directories exist ──
    foreach ($dir in @((Split-Path $JsonPath -Parent), (Split-Path $MarkdownPath -Parent))) {
        if ($dir -and -not (Test-Path $dir)) {
            New-Item -ItemType Directory -Path $dir -Force | Out-Null
        }
    }

    # ── JSON report ─────────────────────────────────────────────────────────
    $jsonReport = $State | ConvertTo-Json -Depth 10
    [System.IO.File]::WriteAllText($JsonPath, $jsonReport, $utf8NoBom)

    # ── Markdown report ─────────────────────────────────────────────────────
    $totalDuration = (Get-Date) - $StartTime
    $o = $State.orchestrator

    $md = @()
    $md += "# Real-World Scenario Test Report"
    $md += ""
    $md += "**Generated:** $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
    $md += "**Mode:** $($o.mode)"
    $md += "**Total Duration:** $(Format-Duration $totalDuration)"
    $md += ""
    $md += "---"
    $md += ""
    $md += "## Summary"
    $md += ""
    $md += "| Metric | Value |"
    $md += "|--------|-------|"
    $md += "| Total Scenarios | $($o.totalScenarios) |"
    $md += "| Passed | $($o.passed) |"
    $md += "| Failed | $($o.failed) |"
    $md += "| Skipped | $($o.skipped) |"
    $md += "| Pass Rate | $([math]::Round(($o.passed / [Math]::Max(1, $o.totalScenarios)) * 100, 1))% |"
    $md += "| Aborted | $($o.globalAbort) |"
    if ($o.abortReason) {
        $md += "| Abort Reason | $($o.abortReason) |"
    }
    $md += ""

    # Token totals
    if ($o.tokenTotals.grandTotalInput -gt 0) {
        $md += "## Token Usage"
        $md += ""
        $md += "| Metric | Count |"
        $md += "|--------|-------|"
        $md += "| Total Input | $(Format-TokenCount $o.tokenTotals.grandTotalInput) |"
        $md += "| Total Output | $(Format-TokenCount $o.tokenTotals.grandTotalOutput) |"
        $md += "| Total Cached | $(Format-TokenCount $o.tokenTotals.grandTotalCached) |"
        $md += ""

        $modelNames = @($o.tokenTotals.byModel.PSObject.Properties | ForEach-Object { $_.Name })
        if ($modelNames.Count -gt 0) {
            $md += "### By Model"
            $md += ""
            $md += "| Model | Input | Output | Cached |"
            $md += "|-------|-------|--------|--------|"
            foreach ($m in $modelNames) {
                $d = $o.tokenTotals.byModel.$m
                $md += "| $m | $(Format-TokenCount $d.input) | $(Format-TokenCount $d.output) | $(Format-TokenCount $d.cached) |"
            }
            $md += ""
        }
    }

    # Scenario results
    $md += "## Scenario Results"
    $md += ""

    # Group by type/level for readability
    $md += "### Use Cases"
    $md += ""
    $md += "| # | Scenario | Level | Status | Duration | Tokens In | Tokens Out |"
    $md += "|---|----------|-------|--------|----------|-----------|------------|"

    $index = 0
    foreach ($s in $State.scenarios) {
        if ($s.type -ne 'use-case') { continue }
        $index++
        $icon = if ($s.status -eq 'passed') { '✅' }
                elseif ($s.status -eq 'failed') { '❌' }
                elseif ($s.status -eq 'skipped') { '⏭️' }
                else { '⬜' }
        $dur = if ($s.durationMs -gt 0) { Format-Duration ([TimeSpan]::FromMilliseconds($s.durationMs)) } else { '-' }
        $tin = if ($s.tokens.totalInput -gt 0) { Format-TokenCount $s.tokens.totalInput } else { '-' }
        $tout = if ($s.tokens.totalOutput -gt 0) { Format-TokenCount $s.tokens.totalOutput } else { '-' }
        $md += "| $index | $($s.id) | $($s.level) | $icon $($s.status) | $dur | $tin | $tout |"
    }

    $md += ""
    $md += "### MD Tasks"
    $md += ""
    $md += "| # | Scenario | Category | Status | Duration | Tokens In | Tokens Out |"
    $md += "|---|----------|----------|--------|----------|-----------|------------|"

    $index = 0
    foreach ($s in $State.scenarios) {
        if ($s.type -ne 'md-task') { continue }
        $index++
        $icon = if ($s.status -eq 'passed') { '✅' }
                elseif ($s.status -eq 'failed') { '❌' }
                elseif ($s.status -eq 'skipped') { '⏭️' }
                else { '⬜' }
        $dur = if ($s.durationMs -gt 0) { Format-Duration ([TimeSpan]::FromMilliseconds($s.durationMs)) } else { '-' }
        $tin = if ($s.tokens.totalInput -gt 0) { Format-TokenCount $s.tokens.totalInput } else { '-' }
        $tout = if ($s.tokens.totalOutput -gt 0) { Format-TokenCount $s.tokens.totalOutput } else { '-' }
        $md += "| $index | $($s.id) | $($s.category) | $icon $($s.status) | $dur | $tin | $tout |"
    }

    # Failed scenarios detail
    $failedScenarios = @($State.scenarios | Where-Object { $_.status -eq 'failed' })
    if ($failedScenarios.Count -gt 0) {
        $md += ""
        $md += "## Failed Scenarios"
        $md += ""
        foreach ($fs in $failedScenarios) {
            $md += "### $($fs.id)"
            $md += ""
            $md += "- **Type:** $($fs.type)"
            if ($fs.level) { $md += "- **Level:** $($fs.level)" }
            if ($fs.category) { $md += "- **Category:** $($fs.category)" }
            $md += "- **Duration:** $(Format-Duration ([TimeSpan]::FromMilliseconds($fs.durationMs)))"
            $md += "- **Exit Code:** $($fs.exitCode)"
            $md += "- **Attempts:** $($fs.attempts)"
            if ($fs.errorSummary) {
                $md += "- **Error:** $($fs.errorSummary)"
            }
            if ($fs.issuesFile) {
                $md += "- **Issues:** ``$($fs.issuesFile)``"
            }
            if ($fs.rawOutputFile) {
                $md += "- **Raw Output:** ``$($fs.rawOutputFile)``"
            }
            $md += ""
        }
    }

    if ($o.globalAbort) {
        $md += "## Abort"
        $md += ""
        $md += "**Reason:** $($o.abortReason)"
        $md += ""
    }

    [System.IO.File]::WriteAllText($MarkdownPath, ($md -join "`n"), $utf8NoBom)
}

# ═══════════════════════════════════════════════════════════════════════════════
# Module loaded
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host "[orchestration-common] Module loaded." -ForegroundColor DarkGray
