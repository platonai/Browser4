# ── Coworker State Machine ─────────────────────────────────────────────────
# Centralized pipeline and stage definitions for the Coworker file-queue
# automation system.  Dot-sourced from config.ps1 after Paths.ps1.
#
# Three pipelines are defined:
#   main              — primary task lifecycle (0draft … 6git-pushed)
#   draft-refinement  — AI draft improvement (1ready → 2working → 3done)
#   github-issues     — GitHub issue extraction and creation
#
# Each stage carries metadata (label, color, date-stamped subdirectories,
# recursive file storage) and a list of valid next stages for transition
# validation.

# ── Pipeline definitions (single source of truth) ──────────────────────────

$script:CoworkerPipelineDefinitions = [ordered]@{
    'main' = [pscustomobject]@{
        Name        = 'main'
        DisplayName = 'Main Task Pipeline'
        BasePath    = 'main'
        Stages      = @(
            [pscustomobject]@{
                Id           = '0draft'
                Label        = 'Draft'
                Color        = 'Gray'
                Aliases      = @('Prepare', 'Draft')
                DateStamped  = $false
                Recursive    = $false
                Description  = 'Task authoring area'
                Next         = @('1ready')
            }
            [pscustomobject]@{
                Id           = '1ready'
                Label        = 'Ready'
                Color        = 'Green'
                Aliases      = @('Created', 'Ready')
                DateStamped  = $false
                Recursive    = $false
                Description  = 'Queued for execution'
                Next         = @('2working', '0draft')
            }
            [pscustomobject]@{
                Id           = '2working'
                Label        = 'Working'
                Color        = 'Yellow'
                Aliases      = @('Working')
                DateStamped  = $false
                Recursive    = $false
                Description  = 'Agent is executing'
                Next         = @('3done', '5approved', '1ready', '0draft')
            }
            [pscustomobject]@{
                Id           = '3done'
                Label        = 'Done'
                Color        = 'Cyan'
                Aliases      = @('Finished', 'Done')
                DateStamped  = $true
                Recursive    = $true
                Description  = 'Completed, awaiting review'
                Next         = @('4review', '5approved', '6git-pushed')
            }
            [pscustomobject]@{
                Id           = '4review'
                Label        = 'Review'
                Color        = 'Magenta'
                Aliases      = @('Review')
                DateStamped  = $false
                Recursive    = $false
                Description  = 'Under human review'
                Next         = @('3done', '5approved', '1ready', '0draft')
            }
            [pscustomobject]@{
                Id           = '5approved'
                Label        = 'Approved'
                Color        = 'Blue'
                Aliases      = @('Approved')
                DateStamped  = $true
                Recursive    = $true
                Description  = 'Approved, awaiting push'
                Next         = @('6git-pushed', '4review')
            }
            [pscustomobject]@{
                Id           = '6git-pushed'
                Label        = 'Pushed'
                Color        = 'DarkGray'
                Aliases      = @('Pushed')
                DateStamped  = $true
                Recursive    = $true
                Description  = 'Changes pushed to remote'
                Next         = @('0draft')
            }
        )
    }
    'draft-refinement' = [pscustomobject]@{
        Name        = 'draft-refinement'
        DisplayName = 'Draft Refinement Pipeline'
        BasePath    = 'main\0draft\refine'
        Stages      = @(
            [pscustomobject]@{
                Id           = '1ready'
                Label        = 'Ready'
                Color        = 'Green'
                Aliases      = @('Ready')
                DateStamped  = $false
                Recursive    = $false
                Description  = 'Queued for refinement'
                Next         = @('2working')
            }
            [pscustomobject]@{
                Id           = '2working'
                Label        = 'Working'
                Color        = 'Yellow'
                Aliases      = @('Working')
                DateStamped  = $false
                Recursive    = $false
                Description  = 'Agent is refining'
                Next         = @('3done', '0error', '1ready')
            }
            [pscustomobject]@{
                Id           = '3done'
                Label        = 'Done'
                Color        = 'Cyan'
                Aliases      = @('Done')
                DateStamped  = $false
                Recursive    = $false
                Description  = 'Refinement complete'
                Next         = @('1ready')
            }
            [pscustomobject]@{
                Id           = '0error'
                Label        = 'Error'
                Color        = 'Red'
                Aliases      = @('Error')
                DateStamped  = $false
                Recursive    = $false
                Description  = 'Dead-letter (max retries exceeded)'
                Next         = @('1ready')
            }
        )
    }
    'github-issues' = [pscustomobject]@{
        Name        = 'github-issues'
        DisplayName = 'GitHub Issues Pipeline'
        BasePath    = 'issues'
        Stages      = @(
            [pscustomobject]@{
                Id           = 'draft/refine/0ready'
                Label        = 'Refine Ready'
                Color        = 'Green'
                Aliases      = @('RefineReady')
                DateStamped  = $false
                Recursive    = $false
                Description  = 'Draft issues queued for extraction'
                Next         = @('draft/refine/1working')
            }
            [pscustomobject]@{
                Id           = 'draft/refine/1working'
                Label        = 'Refine Working'
                Color        = 'Yellow'
                Aliases      = @('RefineWorking')
                DateStamped  = $false
                Recursive    = $false
                Description  = 'Agent is extracting issues'
                Next         = @('draft/refine/2done', 'draft/refine/0error')
            }
            [pscustomobject]@{
                Id           = 'draft/refine/2done'
                Label        = 'Refine Done'
                Color        = 'Cyan'
                Aliases      = @('RefineDone')
                DateStamped  = $false
                Recursive    = $false
                Description  = 'Issues extracted, staged for commit'
                Next         = @('github/commit/ready')
            }
            [pscustomobject]@{
                Id           = 'draft/refine/0error'
                Label        = 'Refine Error'
                Color        = 'Red'
                Aliases      = @('RefineError')
                DateStamped  = $false
                Recursive    = $false
                Description  = 'Extraction failed'
                Next         = @('draft/refine/0ready')
            }
            [pscustomobject]@{
                Id           = 'github/commit/ready'
                Label        = 'Commit Ready'
                Color        = 'Green'
                Aliases      = @('CommitReady')
                DateStamped  = $false
                Recursive    = $false
                Description  = 'Staged issues awaiting gh issue create'
                Next         = @()
            }
        )
    }
}

# ── Stage lookup index (built once at dot-source time) ────────────────────

$script:CoworkerStageIndex = @{}
$script:CoworkerStageIndexByAlias = @{}

foreach ($pipelineEntry in $script:CoworkerPipelineDefinitions.GetEnumerator()) {
    $pipelineName = $pipelineEntry.Key
    $pipeline = $pipelineEntry.Value
    foreach ($stage in $pipeline.Stages) {
        $key = "$pipelineName`:$($stage.Id)"
        $script:CoworkerStageIndex[$key] = @{
            Pipeline = $pipelineName
            Stage    = $stage
        }
        # Index by aliases too
        foreach ($alias in $stage.Aliases) {
            $aliasKey = "$pipelineName`:$alias"
            if (-not $script:CoworkerStageIndexByAlias.ContainsKey($aliasKey)) {
                $script:CoworkerStageIndexByAlias[$aliasKey] = $key
            }
        }
        # Raw Id lookup for main pipeline (most common)
        if ($pipelineName -eq 'main') {
            if (-not $script:CoworkerStageIndexByAlias.ContainsKey($stage.Id)) {
                $script:CoworkerStageIndexByAlias[$stage.Id] = $key
            }
            foreach ($alias in $stage.Aliases) {
                if (-not $script:CoworkerStageIndexByAlias.ContainsKey($alias)) {
                    $script:CoworkerStageIndexByAlias[$alias] = $key
                }
            }
        }
    }
}

# ── Public functions ───────────────────────────────────────────────────────

<#
.SYNOPSIS
    Get one or all pipeline definitions.
#>
function Get-CoworkerPipeline {
    param(
        [string]$Name = ''
    )
    if ($Name -and $script:CoworkerPipelineDefinitions.Contains($Name)) {
        return $script:CoworkerPipelineDefinitions[$Name]
    }
    if (-not $Name) {
        return $script:CoworkerPipelineDefinitions
    }
    return $null
}

<#
.SYNOPSIS
    Get a specific stage definition by pipeline and stage ID (or alias).
#>
function Get-CoworkerStage {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Pipeline,
        [Parameter(Mandatory = $true)]
        [string]$StageId
    )
    $key = "$Pipeline`:$StageId"
    if ($script:CoworkerStageIndex.ContainsKey($key)) {
        return $script:CoworkerStageIndex[$key].Stage
    }
    # Try alias lookup
    $aliasKey = "$Pipeline`:$StageId"
    if ($script:CoworkerStageIndexByAlias.ContainsKey($aliasKey)) {
        $resolvedKey = $script:CoworkerStageIndexByAlias[$aliasKey]
        return $script:CoworkerStageIndex[$resolvedKey].Stage
    }
    return $null
}

<#
.SYNOPSIS
    Resolve a stage ID (or alias) to its absolute directory path.
#>
function Get-CoworkerStageDirectory {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PipelineName,
        [Parameter(Mandatory = $true)]
        [string]$StageId
    )
    $stage = Get-CoworkerStage -Pipeline $PipelineName -StageId $StageId
    if (-not $stage) {
        throw "Unknown stage: pipeline='$PipelineName', stage='$StageId'"
    }
    $pipelineObj = Get-CoworkerPipeline -Name $PipelineName
    $tasksRoot = Get-TasksRoot
    return Join-Path $tasksRoot "$($pipelineObj.BasePath)\$($stage.Id)"
}

<#
.SYNOPSIS
    Get the base directory for a pipeline.
#>
function Get-CoworkerPipelineDirectory {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Pipeline
    )
    $pipelineDef = Get-CoworkerPipeline -Name $Pipeline
    if (-not $pipelineDef) {
        throw "Unknown pipeline: '$Pipeline'"
    }
    return Join-Path (Get-TasksRoot) $pipelineDef.BasePath
}

<#
.SYNOPSIS
    Ensure all stage directories exist for a pipeline.
#>
function Ensure-CoworkerPipelineDirectories {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Pipeline
    )
    $pipelineDef = Get-CoworkerPipeline -Name $Pipeline
    if (-not $pipelineDef) {
        throw "Unknown pipeline: '$Pipeline'"
    }
    $tasksRoot = Get-TasksRoot
    foreach ($stage in $pipelineDef.Stages) {
        $dir = Join-Path $tasksRoot "$($pipelineDef.BasePath)\$($stage.Id)"
        if (-not (Test-Path $dir)) {
            New-Item -ItemType Directory -Path $dir -Force | Out-Null
        }
    }
}

<#
.SYNOPSIS
    Get the human-readable label for a stage directory name.
    Searches all pipelines by default.
#>
function Get-CoworkerStageLabel {
    param(
        [Parameter(Mandatory = $true)]
        [string]$StateDirName,
        [string]$Pipeline = 'main'
    )
    $stage = Get-CoworkerStage -Pipeline $Pipeline -StageId $StateDirName
    if ($stage) { return $stage.Label }

    foreach ($pipelineName in $script:CoworkerPipelineDefinitions.Keys) {
        $stage = Get-CoworkerStage -Pipeline $pipelineName -StageId $StateDirName
        if ($stage) { return $stage.Label }
    }
    return $StateDirName
}

<#
.SYNOPSIS
    Get the ANSI color for a stage directory name.
    Searches all pipelines by default.
#>
function Get-CoworkerStageColor {
    param(
        [Parameter(Mandatory = $true)]
        [string]$StateDirName,
        [string]$Pipeline = 'main'
    )
    $stage = Get-CoworkerStage -Pipeline $Pipeline -StageId $StateDirName
    if ($stage) { return $stage.Color }

    foreach ($pipelineName in $script:CoworkerPipelineDefinitions.Keys) {
        $stage = Get-CoworkerStage -Pipeline $pipelineName -StageId $StateDirName
        if ($stage) { return $stage.Color }
    }
    return 'White'
}

<#
.SYNOPSIS
    Look up a stage directory name from a label string.
#>
function Get-CoworkerStageFromLabel {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Label,
        [string]$Pipeline = 'main'
    )
    $pipelineDef = Get-CoworkerPipeline -Name $Pipeline
    if ($pipelineDef) {
        foreach ($stage in $pipelineDef.Stages) {
            if ($stage.Label -eq $Label -or $stage.Aliases -contains $Label) {
                return $stage.Id
            }
        }
    }

    foreach ($pipelineName in $script:CoworkerPipelineDefinitions.Keys) {
        $pipelineDef = $script:CoworkerPipelineDefinitions[$pipelineName]
        foreach ($stage in $pipelineDef.Stages) {
            if ($stage.Label -eq $Label -or $stage.Aliases -contains $Label) {
                return $stage.Id
            }
        }
    }
    return $null
}

<#
.SYNOPSIS
    Validate whether a state transition is allowed.
#>
function Test-CoworkerStateTransition {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Pipeline,
        [Parameter(Mandatory = $true)]
        [string]$FromStage,
        [Parameter(Mandatory = $true)]
        [string]$ToStage
    )
    $stage = Get-CoworkerStage -Pipeline $Pipeline -StageId $FromStage
    if (-not $stage) { return $false }
    return $ToStage -in $stage.Next
}

<#
.SYNOPSIS
    Get valid next stages from a given stage.
#>
function Get-CoworkerNextStages {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Pipeline,
        [Parameter(Mandatory = $true)]
        [string]$StageId
    )
    $stage = Get-CoworkerStage -Pipeline $Pipeline -StageId $StageId
    if (-not $stage) { return @() }
    return @($stage.Next)
}

<#
.SYNOPSIS
    Determine which pipeline and stage a task file path belongs to.
    Returns a hashtable with Pipeline and StageId, or $null if not recognized.
#>
function Resolve-CoworkerTaskStage {
    param(
        [Parameter(Mandatory = $true)]
        [string]$TaskPath
    )
    $resolvedPath = [System.IO.Path]::GetFullPath($TaskPath)
    $tasksRoot = (Get-TasksRoot).TrimEnd('\', '/')

    if (-not $resolvedPath.StartsWith($tasksRoot, [StringComparison]::OrdinalIgnoreCase)) {
        return $null
    }

    $relativePath = $resolvedPath.Substring($tasksRoot.Length).TrimStart('\', '/')

    foreach ($pipelineEntry in $script:CoworkerPipelineDefinitions.GetEnumerator()) {
        $pipelineName = $pipelineEntry.Key
        $pipeline = $pipelineEntry.Value
        $basePath = $pipeline.BasePath.Replace('\', '/')

        if (-not $relativePath.StartsWith($basePath, [StringComparison]::OrdinalIgnoreCase)) {
            continue
        }

        foreach ($stage in $pipeline.Stages) {
            $stageRelPath = "$basePath/$($stage.Id)".Replace('\', '/')
            if ($relativePath.StartsWith($stageRelPath, [StringComparison]::OrdinalIgnoreCase)) {
                return @{
                    Pipeline = $pipelineName
                    StageId  = $stage.Id
                    Stage    = $stage
                }
            }
        }
    }

    return $null
}

<#
.SYNOPSIS
    List task files in a given stage directory.
#>
function Get-CoworkerStageTasks {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Pipeline,
        [Parameter(Mandatory = $true)]
        [string]$StageId
    )
    $dir = Get-CoworkerStageDirectory -PipelineName $Pipeline -StageId $StageId
    if (-not (Test-Path $dir)) { return @() }

    $stage = Get-CoworkerStage -Pipeline $Pipeline -StageId $StageId
    $recurse = $stage.Recursive

    $files = if ($recurse) {
        Get-ChildItem -Path $dir -Recurse -File -ErrorAction SilentlyContinue
    }
    else {
        Get-ChildItem -Path $dir -File -ErrorAction SilentlyContinue
    }

    return @($files | Where-Object { -not (Test-CoworkerIgnoredFile -Item $_) })
}

# ═══════════════════════════════════════════════════════════════════════════════
# Backward-compatibility shims
# ═══════════════════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
    [COMPAT SHIM] Return task directories for the main pipeline.
    Matches the original Get-TaskDirectories in coworker.ps1 exactly.
#>
function Get-TaskDirectories {
    $tasksRoot = Get-TasksRoot
    $main = Join-Path $tasksRoot 'main'
    return [pscustomobject]@{
        Draft    = Join-Path $main '0draft'
        Ready    = Join-Path $main '1ready'
        Working  = Join-Path $main '2working'
        Done     = Join-Path $main '3done'
        Review   = Join-Path $main '4review'
        Approved = Join-Path $main '5approved'
        Pushed   = Join-Path $main '6git-pushed'
    }
}

<#
.SYNOPSIS
    [COMPAT SHIM] Get the label for a state directory.
    Delegates to Get-CoworkerStageLabel.
#>
function Get-StateLabel {
    param([string]$DirPath)
    $name = Split-Path -Leaf $DirPath
    return Get-CoworkerStageLabel -StateDirName $name
}

<#
.SYNOPSIS
    [COMPAT SHIM] Get the color for a state directory.
    Delegates to Get-CoworkerStageColor.
#>
function Get-StateColor {
    param([string]$DirPath)
    $name = Split-Path -Leaf $DirPath
    return Get-CoworkerStageColor -StateDirName $name
}

<#
.SYNOPSIS
    [COMPAT SHIM] Look up a state directory name from a label.
    Matches the original Get-StateFromLabel in coworker.ps1 exactly,
    then falls back to the centralized lookup.
#>
function Get-StateFromLabel {
    param([string]$Label)
    switch ($Label) {
        'draft'    { return '0draft' }
        'ready'    { return '1ready' }
        'working'  { return '2working' }
        'done'     { return '3done' }
        'review'   { return '4review' }
        'approved' { return '5approved' }
        'pushed'   { return '6git-pushed' }
        'all'      { return 'all' }
        default    { return (Get-CoworkerStageFromLabel -Label $Label -Pipeline 'main') }
    }
}
