#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Scan coworker/tasks/200issues/github/open for issue files and create them
    on GitHub via the gh CLI.

.DESCRIPTION
    Each file in the open directory represents a GitHub issue to be created.
    After a successful creation the file moves to the "done" directory; on
    failure it moves to "failed" so the operator can inspect and retry.

    File format (markdown):
        # <Title>
        <Body>

        Labels: bug, enhancement          (optional)
        Assignees: username               (optional)
        Repo: owner/repo                  (optional — defaults to gh default)
#>

$ErrorActionPreference = 'Stop'

$workerDir = $PSScriptRoot
$configPath = Join-Path (Split-Path -Parent $workerDir) 'config.ps1'
. $configPath

$repoRoot = Get-WorkspaceRoot

$issuesRoot = Resolve-TasksPath '200issues\github'
$openDir = Join-Path $issuesRoot 'open'
$doneDir = Join-Path $issuesRoot 'done'
$failedDir = Join-Path $issuesRoot 'failed'

foreach ($directory in @($openDir, $doneDir, $failedDir)) {
    Ensure-CoworkerDirectory -Path $directory
}

$files = @(Get-ChildItem -Path $openDir -File |
    Where-Object { -not (Test-CoworkerIgnoredFile -Item $_) } |
    Sort-Object Name)

if ($files.Count -eq 0) {
    Write-CoworkerLog -Message "No GitHub issue files found in $openDir" -Level 'INFO' -Component 'commit-github-issues'
    exit 0
}

Write-CoworkerLog -Message "Found $($files.Count) issue file(s) to commit" -Level 'INFO' -Component 'commit-github-issues'

$failureCount = 0

foreach ($file in $files) {
    Write-CoworkerLog -Message "Processing: $($file.Name)" -Level 'INFO' -Component 'commit-github-issues'

    try {
        $content = Get-Content -Path $file.FullName -Raw -Encoding UTF8

        if ([string]::IsNullOrWhiteSpace($content)) {
            throw 'File is empty.'
        }

        # ── Parse title ────────────────────────────────────────────────────
        $title = ''
        $bodyLines = @()

        if ($content -match "(?m)^#\s+(?<title>.+?)(\r?\n|$)") {
            $title = $Matches['title'].Trim()
            $titleIndex = $content.IndexOf($Matches[0]) + $Matches[0].Length
            $remaining = $content.Substring($titleIndex).TrimStart("`r", "`n")
        }
        else {
            $lines = @($content -split '\r?\n' | Where-Object { $_.Trim() -ne '' })
            if ($lines.Count -gt 0) {
                $title = $lines[0].Trim()
                $remaining = ($lines[1..($lines.Count - 1)] -join "`n").Trim()
            }
        }

        if ([string]::IsNullOrWhiteSpace($title)) {
            throw 'Could not parse issue title.'
        }

        # ── Parse metadata fields ──────────────────────────────────────────
        $labels = @()
        $assignees = @()
        $repo = ''
        $metadataPatterns = @(
            @{ Pattern = "(?m)^Labels:\s*(?<value>.+?)(\r?\n|$)"; Target = 'labels' },
            @{ Pattern = "(?m)^Assignees:\s*(?<value>.+?)(\r?\n|$)"; Target = 'assignees' },
            @{ Pattern = "(?m)^Repo:\s*(?<value>.+?)(\r?\n|$)"; Target = 'repo' }
        )

        foreach ($meta in $metadataPatterns) {
            if ($content -match $meta.Pattern) {
                $value = $Matches['value'].Trim()
                if ($value) {
                    switch ($meta.Target) {
                        'labels' { $labels = @($value -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }) }
                        'assignees' { $assignees = @($value -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }) }
                        'repo' { $repo = $value }
                    }
                }
            }
        }

        # ── Build body (everything after metadata lines) ────────────────────
        if ($PSBoundParameters.ContainsKey('remaining') -and $remaining) {
            $bodyLines = @($remaining -split '\r?\n')
        }

        $bodyContent = ($bodyLines | Where-Object {
            $_ -notmatch '^Labels:\s*' -and
            $_ -notmatch '^Assignees:\s*' -and
            $_ -notmatch '^Repo:\s*'
        } | ForEach-Object { $_.TrimEnd() }) -join "`n"

        $bodyContent = $bodyContent.Trim()

        # ── Build gh arguments ─────────────────────────────────────────────
        $ghArgs = [System.Collections.ArrayList]::new()
        [void]$ghArgs.Add('issue')
        [void]$ghArgs.Add('create')
        [void]$ghArgs.Add('--title')
        [void]$ghArgs.Add($title)

        if ($bodyContent) {
            [void]$ghArgs.Add('--body')
            [void]$ghArgs.Add($bodyContent)
        }

        foreach ($label in $labels) {
            [void]$ghArgs.Add('--label')
            [void]$ghArgs.Add($label)
        }

        foreach ($assignee in $assignees) {
            [void]$ghArgs.Add('--assignee')
            [void]$ghArgs.Add($assignee)
        }

        if ($repo) {
            [void]$ghArgs.Add('--repo')
            [void]$ghArgs.Add($repo)
        }

        # ── Create the issue ───────────────────────────────────────────────
        Write-CoworkerLog -Message "Creating issue: $title" -Level 'INFO' -Component 'commit-github-issues'

        $ghOutput = & gh $ghArgs 2>&1
        $exitCode = $LASTEXITCODE

        if ($exitCode -ne 0) {
            throw "gh exited with code $exitCode: $ghOutput"
        }

        Write-CoworkerLog -Message "Issue created: $ghOutput" -Level 'INFO' -Component 'commit-github-issues'

        # ── Move to done ───────────────────────────────────────────────────
        $donePath = Join-Path $doneDir $file.Name
        Move-Item -Path $file.FullName -Destination $donePath -Force
        Write-CoworkerLog -Message "Moved to done: $donePath" -Level 'INFO' -Component 'commit-github-issues'
    }
    catch {
        $failureCount++
        Write-CoworkerLog -Message "Failed to create issue from $($file.Name): $_" -Level 'ERROR' -Component 'commit-github-issues'

        $failedPath = Join-Path $failedDir $file.Name
        Move-Item -Path $file.FullName -Destination $failedPath -Force -ErrorAction SilentlyContinue
        Write-CoworkerLog -Message "Moved to failed: $failedPath" -Level 'WARN' -Component 'commit-github-issues'
    }
}

if ($failureCount -gt 0) {
    Write-CoworkerLog -Message "$failureCount issue(s) failed to create. Check the failed directory: $failedDir" -Level 'WARN' -Component 'commit-github-issues'
    exit 1
}

Write-CoworkerLog -Message 'All issues committed successfully.' -Level 'INFO' -Component 'commit-github-issues'
exit 0
