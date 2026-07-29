# ── Coworker Task Registry ─────────────────────────────────────────────────
# Lightweight task metadata index backed by the State.ps1 persistent store
# (~/.browser4-coworker/state.json).  All operations are best-effort —
# failures never block task execution.  The directory state machine remains
# the authoritative source of truth; this registry is a cache/index.
#
# Dot-sourced from config.ps1 after StateMachine.ps1.

# ── Constants ──────────────────────────────────────────────────────────────

$script:TaskRegistryMaxBatchSize = 20
$script:TaskRegistryStateKey = 'taskRegistry'

# ── Internal helpers ───────────────────────────────────────────────────────

function _Get-TaskRegistryState {
    try {
        $state = Read-CoworkerState
        if (-not $state.ContainsKey($script:TaskRegistryStateKey)) {
            $state[$script:TaskRegistryStateKey] = @{
                version = 1
                tasks   = @{}
            }
        }
        $registry = $state[$script:TaskRegistryStateKey]
        # Normalize: ensure tasks is a hashtable
        if ($registry -is [hashtable] -and $registry.ContainsKey('tasks')) {
            if ($registry['tasks'] -isnot [hashtable]) {
                $registry['tasks'] = @{}
            }
        }
        else {
            $registry = @{ version = 1; tasks = @{} }
            $state[$script:TaskRegistryStateKey] = $registry
        }
        return @{ State = $state; Registry = $registry }
    }
    catch {
        return $null
    }
}

function _Save-TaskRegistryState {
    param(
        [Parameter(Mandatory = $true)]
        [hashtable]$State
    )
    try {
        Write-CoworkerState -State $State
    }
    catch {
        # Best-effort; silently ignore write failures
    }
}

function _Get-TaskId {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath
    )
    $baseName = [System.IO.Path]::GetFileNameWithoutExtension($FilePath)
    # Normalize to kebab-case for consistent lookups
    return $baseName -replace '[^A-Za-z0-9._-]', '-' -replace '-+', '-'
}

# ── Public functions ───────────────────────────────────────────────────────

<#
.SYNOPSIS
    Register a task file in the index, or update its state.
    Called after any file move that changes task state.

.PARAMETER FilePath
    Absolute or relative path to the task file.

.PARAMETER Pipeline
    Pipeline name (main, draft-refinement, github-issues).

.PARAMETER FromState
    Previous state directory name, or $null for initial creation.

.PARAMETER ToState
    New state directory name.

.PARAMETER Reason
    Human-readable reason for the transition (created, assigned, etc.).
#>
function Register-CoworkerTaskMove {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,
        [Parameter(Mandatory = $true)]
        [string]$Pipeline,
        [string]$FromState,
        [Parameter(Mandatory = $true)]
        [string]$ToState,
        [string]$Reason = ''
    )

    try {
        $taskId = _Get-TaskId -FilePath $FilePath
        if ([string]::IsNullOrWhiteSpace($taskId)) { return }

        $result = _Get-TaskRegistryState
        if (-not $result) { return }
        $state = $result.State
        $registry = $result.Registry
        $tasks = $registry['tasks']

        $now = Get-CoworkerTimestamp

        if (-not $tasks.ContainsKey($taskId)) {
            # New task registration
            $tasks[$taskId] = @{
                taskId         = $taskId
                pipeline       = $Pipeline
                currentState   = $ToState
                filename       = Split-Path -Leaf $FilePath
                createdAt      = $now
                lastModifiedAt = $now
                history        = @(
                    @{
                        from      = if ($FromState) { $FromState } else { $null }
                        to        = $ToState
                        timestamp = $now
                        reason    = if ($Reason) { $Reason } else { 'created' }
                    }
                )
            }
        }
        else {
            # Update existing task
            $task = $tasks[$taskId]
            if ($task -is [hashtable]) {
                $task['currentState'] = $ToState
                $task['pipeline'] = $Pipeline
                $task['lastModifiedAt'] = $now
                if (-not $task.ContainsKey('history') -or $task['history'] -isnot [array]) {
                    $task['history'] = @()
                }
                $task['history'] += @{
                    from      = if ($FromState) { $FromState } else { $null }
                    to        = $ToState
                    timestamp = $now
                    reason    = if ($Reason) { $Reason } else { 'moved' }
                }
                # Keep audit trail bounded (last 50 entries)
                if ($task['history'].Count -gt 50) {
                    $task['history'] = @($task['history'] | Select-Object -Last 50)
                }
            }
        }

        _Save-TaskRegistryState -State $state
    }
    catch {
        # Best-effort; silently ignore failures
    }
}

<#
.SYNOPSIS
    Look up task metadata by task ID (kebab-case filename stem) or filename.

.PARAMETER TaskId
    The task identifier — either a kebab-case name or a full filename.
#>
function Get-CoworkerTaskInfo {
    param(
        [Parameter(Mandatory = $true)]
        [string]$TaskId
    )

    try {
        $taskId = _Get-TaskId -FilePath $TaskId
        if ([string]::IsNullOrWhiteSpace($taskId)) { return $null }

        $result = _Get-TaskRegistryState
        if (-not $result) { return $null }

        $tasks = $result.Registry['tasks']
        if ($tasks.ContainsKey($taskId)) {
            return $tasks[$taskId]
        }
        return $null
    }
    catch {
        return $null
    }
}

<#
.SYNOPSIS
    Find tasks by pipeline and optional state.

.PARAMETER Pipeline
    Pipeline name, or empty string for all pipelines.

.PARAMETER State
    Optional state directory name to filter by.
#>
function Find-CoworkerTasksByState {
    param(
        [string]$Pipeline = '',
        [string]$State = ''
    )

    try {
        $result = _Get-TaskRegistryState
        if (-not $result) { return @() }

        $tasks = $result.Registry['tasks']
        $matches = @()

        foreach ($entry in $tasks.GetEnumerator()) {
            $task = $entry.Value
            if ($task -isnot [hashtable]) { continue }

            $pipelineMatch = (-not $Pipeline) -or ($task['pipeline'] -eq $Pipeline)
            $stateMatch = (-not $State) -or ($task['currentState'] -eq $State)

            if ($pipelineMatch -and $stateMatch) {
                $matches += $task
            }
        }

        return @($matches | Sort-Object lastModifiedAt -Descending)
    }
    catch {
        return @()
    }
}

<#
.SYNOPSIS
    Get the complete audit trail (history) for a task.
#>
function Get-CoworkerTaskHistory {
    param(
        [Parameter(Mandatory = $true)]
        [string]$TaskId
    )

    $taskInfo = Get-CoworkerTaskInfo -TaskId $TaskId
    if (-not $taskInfo -or -not $taskInfo.ContainsKey('history')) {
        return @()
    }
    return @($taskInfo['history'])
}

<#
.SYNOPSIS
    Rebuild the task registry index from scratch by scanning all pipeline
    directories.  Useful for recovery or after manual file moves.
#>
function Rebuild-CoworkerTaskRegistry {
    [CmdletBinding()]
    param()

    try {
        $state = Read-CoworkerState
        $newTasks = @{}
        $now = Get-CoworkerTimestamp

        foreach ($pipelineEntry in $script:CoworkerPipelineDefinitions.GetEnumerator()) {
            $pipelineName = $pipelineEntry.Key
            $pipeline = $pipelineEntry.Value

            foreach ($stage in $pipeline.Stages) {
                try {
                    $dir = Get-CoworkerStageDirectory -PipelineName $pipelineName -StageId $stage.Id
                    if (-not (Test-Path $dir)) { continue }

                    $searchDepth = if ($stage.Recursive) { 3 } else { 1 }
                    $files = Get-ChildItem -Path $dir -File -Depth $searchDepth -ErrorAction SilentlyContinue |
                        Where-Object { -not (Test-CoworkerIgnoredFile -Item $_) }

                    foreach ($file in $files) {
                        $taskId = _Get-TaskId -FilePath $file.Name
                        if ([string]::IsNullOrWhiteSpace($taskId)) { continue }

                        if (-not $newTasks.ContainsKey($taskId)) {
                            $newTasks[$taskId] = @{
                                taskId         = $taskId
                                pipeline       = $pipelineName
                                currentState   = $stage.Id
                                filename       = $file.Name
                                createdAt      = $now
                                lastModifiedAt = $now
                                history        = @(
                                    @{
                                        from      = $null
                                        to        = $stage.Id
                                        timestamp = $now
                                        reason    = 'rebuilt from filesystem scan'
                                    }
                                )
                            }
                        }
                    }
                }
                catch {
                    # Skip stages with inaccessible directories
                }
            }
        }

        $state[$script:TaskRegistryStateKey] = @{
            version = 1
            tasks   = $newTasks
        }

        Write-CoworkerState -State $state
        Write-CoworkerLog -Message "Task registry rebuilt: $($newTasks.Count) task(s) indexed." -Component 'task-registry'
    }
    catch {
        Write-CoworkerLog -Message "Failed to rebuild task registry: $_" -Level WARN -Component 'task-registry'
    }
}

<#
.SYNOPSIS
    Remove a task from the registry (e.g. when a task file is deleted).
#>
function Remove-CoworkerTask {
    param(
        [Parameter(Mandatory = $true)]
        [string]$TaskId
    )

    try {
        $taskId = _Get-TaskId -FilePath $TaskId
        if ([string]::IsNullOrWhiteSpace($taskId)) { return }

        $result = _Get-TaskRegistryState
        if (-not $result) { return }

        $tasks = $result.Registry['tasks']
        if ($tasks.ContainsKey($taskId)) {
            $tasks.Remove($taskId)
            _Save-TaskRegistryState -State $result.State
        }
    }
    catch {
        # Best-effort
    }
}
