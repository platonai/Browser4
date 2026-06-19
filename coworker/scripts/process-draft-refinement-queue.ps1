#!/usr/bin/env pwsh

param(
    [string]$Path,
    [int]$IntervalSeconds = 15,
    [switch]$Once
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$configScriptPath = Join-Path $PSScriptRoot 'config.ps1'
. $configScriptPath

function Get-PendingDraftFiles {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ScanPath
    )

    if (-not (Test-Path -LiteralPath $ScanPath)) {
        return @()
    }

    $scanItem = Get-Item -LiteralPath $ScanPath
    if ($scanItem.PSIsContainer) {
        return @(
            Get-ChildItem -Path $scanItem.FullName -File |
                Where-Object { Test-CoworkerActionableDraftRefinementFile -Item $_ } |
                Sort-Object Name
        )
    }

    if (Test-CoworkerActionableDraftRefinementFile -Item $scanItem) {
        return @($scanItem)
    }

    return @()
}

function Wait-ForDraftRefinementSignal {
    param(
        [Parameter(Mandatory = $true)]
        [int]$TimeoutSeconds
    )

    $eventRecord = Wait-Event -Timeout $TimeoutSeconds
    if ($null -ne $eventRecord) {
        Remove-Event -EventIdentifier $eventRecord.EventIdentifier -ErrorAction SilentlyContinue
    }

    while ($true) {
        $queuedEvent = Wait-Event -Timeout 0
        if ($null -eq $queuedEvent) {
            break
        }

        Remove-Event -EventIdentifier $queuedEvent.EventIdentifier -ErrorAction SilentlyContinue
    }
}

$refineScript = Join-Path $PSScriptRoot 'workers\refine-drafts.ps1'
$defaultReadyDir = Resolve-TasksPath '0draft\refine\1ready'
$scanPath = if ([string]::IsNullOrWhiteSpace($Path)) { $defaultReadyDir } else { [System.IO.Path]::GetFullPath($Path) }
$watchRegistrations = @()

Write-CoworkerLog -Component 'process-draft-refinement-queue' -Message ("Monitoring draft refinement path: {0}" -f $scanPath)
Write-CoworkerLog -Component 'process-draft-refinement-queue' -Level 'DEBUG' -Message ("Refine script: {0}" -f $refineScript)

try {
    if (-not $Once) {
        $watchRegistrations += New-CoworkerFileWatcher -Path $scanPath -SourcePrefix 'process-draft-refinement-queue'
    }

    while ($true) {
        $pendingFiles = @(Get-PendingDraftFiles -ScanPath $scanPath)
        if ($pendingFiles.Count -eq 0) {
            if ($Once) {
                exit 0
            }

            Write-CoworkerLog -Component 'process-draft-refinement-queue' -Level 'DEBUG' -Message 'No actionable draft files found for refinement.'
            Wait-ForDraftRefinementSignal -TimeoutSeconds $IntervalSeconds
            continue
        }

        Write-CoworkerLog -Component 'process-draft-refinement-queue' -Message ("Refining {0} draft file(s)." -f $pendingFiles.Count)
        & $refineScript -Path $scanPath
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0) {
            Write-CoworkerLog -Component 'process-draft-refinement-queue' -Level 'WARN' -Message ("Draft refinement finished with exit code {0}." -f $exitCode)
        }

        if ($Once) {
            exit $exitCode
        }
    }
}
catch {
    Write-CoworkerLog -Component 'process-draft-refinement-queue' -Level 'ERROR' -Message $_.Exception.Message
    exit 1
}
finally {
    foreach ($registration in @($watchRegistrations)) {
        Remove-CoworkerFileWatcher -Registration $registration
    }
}
