# ═══════════════════════════════════════════════════════════════════════════════
# Draft Panel — Interactive CLI for browsing, creating, and fixing drafts
# ═══════════════════════════════════════════════════════════════════════════════
# Dot-sourced by cowarker.ps1 when `coworker draft -i` is invoked.
#
# Provides an interactive panel that:
#   1. Lists existing drafts from 0draft/ (numbered, with titles and dates)
#   2. Creates new issue drafts interactively
#   3. Optionally calls `b4w.ps1 coworker fix` after creating a draft
#   4. Allows fixing an existing draft (assign to 1ready + run fix)
#
# Keyboard shortcuts:
#   n           New draft (interactive prompt)
#   v <N>       View draft N (print full content)
#   f <N>       Fix draft N (assign to 1ready and execute coworker fix)
#   r           Refresh the draft list
#   q / Esc     Quit
#   ?           Show help (this list)
# ═══════════════════════════════════════════════════════════════════════════════

# ── Constants ──────────────────────────────────────────────────────────────────

$script:PanelHeader = '──── Coworker Draft Panel ────'
$script:MaxTitleLength = 55
$script:MaxNameLength = 40

# ── Panel rendering ──────────────────────────────────────────────────────────

<#
.SYNOPSIS
    Scan 0draft/ and return a list of draft entries, excluding placeholders.
#>
function Get-DraftEntries {
    $dirs = Get-TaskDirectories
    $draftDir = $dirs.Draft
    if (-not (Test-Path $draftDir)) {
        return @()
    }

    $entries = @(Get-ChildItem -Path $draftDir -File -ErrorAction SilentlyContinue |
        Where-Object { -not (Test-CoworkerIgnoredFile -Item $_) } |
        Where-Object { $_.BaseName -notmatch '^[1-5]$' } |
        Where-Object { $_.Name -ne 'plan' -and $_.Name -ne 'issues' -and $_.Name -ne 'refine' } |
        Sort-Object LastWriteTime -Descending)

    $result = [System.Collections.Generic.List[object]]::new()
    foreach ($f in $entries) {
        $title = Get-TaskTitle -FilePath $f.FullName
        $result.Add([PSCustomObject]@{
            Index      = 0
            FileName   = $f.Name
            FullPath   = $f.FullName
            Title      = $title
            Modified   = $f.LastWriteTime.ToString('yyyy-MM-dd HH:mm')
            SizeBytes  = $f.Length
        })
    }
    # Number them after sorting
    for ($i = 0; $i -lt $result.Count; $i++) {
        $result[$i].Index = $i + 1
    }
    return $result.ToArray()
}

<#
.SYNOPSIS
    Render the full panel to the console.
#>
function Write-DraftPanel {
    param(
        [object[]]$Drafts,
        [string]$StatusMessage = '',
        [string]$StatusColor = 'DarkGray'
    )

    # Clear screen and redraw
    Clear-Host

    # ── Header ────────────────────────────────────────────────────────────
    Write-ConsoleLine -Message '' -ForegroundColor Cyan
    Write-ConsoleLine -Message "  $script:PanelHeader" -ForegroundColor Cyan
    Write-ConsoleLine -Message "  $(Get-WorkspaceRoot)" -ForegroundColor DarkGray
    Write-ConsoleLine -Message ''

    # ── Draft list ────────────────────────────────────────────────────────
    if ($Drafts.Count -eq 0) {
        Write-ConsoleLine -Message "  (no drafts in 0draft/)" -ForegroundColor DarkGray
    }
    else {
        Write-ConsoleLine -Message "  Existing drafts in 0draft/:" -ForegroundColor White
        Write-ConsoleLine -Message ''

        foreach ($d in $Drafts) {
            $idx = $d.Index.ToString().PadLeft(2)
            $name = $d.FileName
            if ($name.Length -gt $script:MaxNameLength) {
                $name = $name.Substring(0, $script:MaxNameLength - 3) + '...'
            }
            $name = $name.PadRight($script:MaxNameLength + 1)

            $title = $d.Title
            if ($title.Length -gt $script:MaxTitleLength) {
                $title = $title.Substring(0, $script:MaxTitleLength - 3) + '...'
            }

            Write-ConsoleLine -Message "  [$idx]  ${name}$title" -ForegroundColor White
            Write-ConsoleLine -Message "        Modified: $($d.Modified)  |  $($d.SizeBytes) bytes" -ForegroundColor DarkGray
        }
    }

    Write-ConsoleLine -Message ''

    # ── Status message ────────────────────────────────────────────────────
    if ($StatusMessage) {
        Write-ConsoleLine -Message "  $StatusMessage" -ForegroundColor $StatusColor
        Write-ConsoleLine -Message ''
    }

    # ── Shortcuts bar ─────────────────────────────────────────────────────
    Write-ConsoleLine -Message '  ' -NoNewline
    Write-Host -NoNewline -ForegroundColor DarkGray ('─' * 55)
    Write-ConsoleLine -Message ''
    Write-ConsoleLine -Message '  n  New draft    v<N> View    f<N> Fix (assign+run)' -ForegroundColor DarkGray
    Write-ConsoleLine -Message '  r  Refresh      q  Quit     ?  Help' -ForegroundColor DarkGray
    Write-ConsoleLine -Message ''
    Write-Host -NoNewline -ForegroundColor Cyan '  > '
}

# ── Action handlers ─────────────────────────────────────────────────────────

<#
.SYNOPSIS
    Read a single key from the user. Returns the key character and any
    trailing text (e.g. "v3" → 'v', '3').
#>
function Read-PanelInput {
    Write-Host -NoNewline -ForegroundColor Cyan '  > '
    $raw = Read-Host
    return $raw.Trim()
}

<#
.SYNOPSIS
    Show the help overlay.
#>
function Show-PanelHelp {
    Clear-Host
    Write-ConsoleLine -Message ''
    Write-ConsoleLine -Message "  ──── Draft Panel Help ────" -ForegroundColor Cyan
    Write-ConsoleLine -Message ''
    Write-ConsoleLine -Message "  n              Create a new draft interactively." -ForegroundColor White
    Write-ConsoleLine -Message "                   Prompts for title and content. After saving," -ForegroundColor DarkGray
    Write-ConsoleLine -Message "                   offers to assign to 1ready and fix immediately." -ForegroundColor DarkGray
    Write-ConsoleLine -Message ''
    Write-ConsoleLine -Message "  v <N>          View draft N. Prints full content of the draft." -ForegroundColor White
    Write-ConsoleLine -Message "                   Example: v 1" -ForegroundColor DarkGray
    Write-ConsoleLine -Message ''
    Write-ConsoleLine -Message "  f <N>          Fix draft N. Assigns it to 1ready/ and runs" -ForegroundColor White
    Write-ConsoleLine -Message "                   b4w coworker fix to execute it immediately." -ForegroundColor DarkGray
    Write-ConsoleLine -Message "                   Example: f 3" -ForegroundColor DarkGray
    Write-ConsoleLine -Message ''
    Write-ConsoleLine -Message "  r              Refresh the draft list (rescan 0draft/)." -ForegroundColor White
    Write-ConsoleLine -Message "  q / Esc        Quit the panel." -ForegroundColor White
    Write-ConsoleLine -Message "  ?              Show this help." -ForegroundColor White
    Write-ConsoleLine -Message ''
    Write-ConsoleLine -Message "  ────────────────────────────────────────────────────" -ForegroundColor DarkGray
    Write-ConsoleLine -Message '  Press Enter to return.' -ForegroundColor DarkGray
    Write-ConsoleLine -Message ''
    Read-Host | Out-Null
}

<#
.SYNOPSIS
    View a single draft by index.
#>
function Show-DraftView {
    param(
        [object[]]$Drafts,
        [int]$Index
    )

    if ($Index -lt 1 -or $Index -gt $Drafts.Count) {
        Write-ConsoleLine -Message "  Invalid draft number: $Index (valid: 1-$($Drafts.Count))" -ForegroundColor Red
        Start-Sleep -Milliseconds 1500
        return
    }

    $draft = $Drafts[$Index - 1]
    Clear-Host
    Write-ConsoleLine -Message ''
    Write-ConsoleLine -Message "  ──── Draft $Index ────" -ForegroundColor Cyan
    Write-ConsoleLine -Message "  File    : $($draft.FileName)" -ForegroundColor DarkGray
    Write-ConsoleLine -Message "  Title   : $($draft.Title)" -ForegroundColor DarkGray
    Write-ConsoleLine -Message "  Modified: $($draft.Modified)" -ForegroundColor DarkGray
    Write-ConsoleLine -Message "  Path    : $($draft.FullPath)" -ForegroundColor DarkGray
    Write-ConsoleLine -Message ''
    Write-ConsoleLine -Message '  ────────────────────────────────────────────────────' -ForegroundColor DarkGray

    # Print full content
    try {
        $content = Get-Content -Path $draft.FullPath -Raw -Encoding UTF8 -ErrorAction Stop
        $lines = $content -split "`n"
        foreach ($line in $lines) {
            Write-Host "  $line"
        }
    }
    catch {
        Write-ConsoleLine -Message "  Error reading file: $_" -ForegroundColor Red
    }

    Write-ConsoleLine -Message ''
    Write-ConsoleLine -Message '  ────────────────────────────────────────────────────' -ForegroundColor DarkGray
    Write-ConsoleLine -Message '  Press Enter to return to the draft list.' -ForegroundColor DarkGray
    Write-ConsoleLine -Message ''
    Read-Host | Out-Null
}

<#
.SYNOPSIS
    Assign a draft to 1ready/ and call coworker fix on it.
#>
function Invoke-FixDraft {
    param(
        [object[]]$Drafts,
        [int]$Index
    )

    if ($Index -lt 1 -or $Index -gt $Drafts.Count) {
        Write-ConsoleLine -Message "  Invalid draft number: $Index (valid: 1-$($Drafts.Count))" -ForegroundColor Red
        Start-Sleep -Milliseconds 1500
        return
    }

    $draft = $Drafts[$Index - 1]
    $draftPath = $draft.FullPath

    # Confirm
    Write-ConsoleLine -Message "  Fix draft $Index : $($draft.Title)" -ForegroundColor Cyan
    Write-ConsoleLine -Message "  This will assign the draft to 1ready/ and execute it." -ForegroundColor DarkGray
    Write-Host -NoNewline -ForegroundColor Yellow '  Proceed? [y/N] '
    $confirm = Read-Host
    if ($confirm -notmatch '^[yY]') {
        Write-ConsoleLine -Message "  Cancelled." -ForegroundColor DarkGray
        Start-Sleep -Milliseconds 1000
        return
    }

    Write-ConsoleLine -Message "  Assigning to 1ready/..." -ForegroundColor Cyan

    # Move the file to 1ready/
    $dirs = Get-TaskDirectories
    $readyDir = $dirs.Ready
    if (-not (Test-Path $readyDir)) {
        New-Item -ItemType Directory -Path $readyDir -Force | Out-Null
    }

    $destInfo = Resolve-UniquePath -Directory $readyDir -BaseName ([System.IO.Path]::GetFileNameWithoutExtension($draftPath)) -Extension '.md'
    Move-Item -Path $draftPath -Destination $destInfo.Path -Force
    Write-ConsoleLine -Message "  Moved to 1ready: $($destInfo.Path)" -ForegroundColor Green

    # Restore placeholders
    Ensure-DraftPlaceholders -DraftDirectory $dirs.Draft

    Write-ConsoleLine -Message "  Running coworker fix..." -ForegroundColor Cyan

    # Run coworker fix via b4w.ps1
    $b4wScript = Join-Path (Get-WorkspaceRoot) 'b4w.ps1'
    if (Test-Path $b4wScript) {
        # Run fix on the newly assigned file
        $fixArgs = @('-File', $b4wScript, 'coworker', 'fix', '-Path', $destInfo.Path)
        $process = Start-Process -FilePath 'pwsh.exe' `
            -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $b4wScript, 'coworker', 'fix', '-Path', $destInfo.Path) `
            -PassThru -NoNewWindow -Wait

        if ($process.ExitCode -ne 0) {
            Write-ConsoleLine -Message "  Fix completed with exit code $($process.ExitCode)." -ForegroundColor Yellow
        }
        else {
            Write-ConsoleLine -Message "  Fix completed successfully." -ForegroundColor Green
        }
    }
    else {
        Write-ConsoleLine -Message "  Warning: b4w.ps1 not found. File is in 1ready/ — run manually:" -ForegroundColor Yellow
        Write-ConsoleLine -Message "    b4w coworker fix -Path `"$($destInfo.Path)`"" -ForegroundColor Yellow
    }

    Write-ConsoleLine -Message ''
    Write-ConsoleLine -Message '  Press Enter to continue.' -ForegroundColor DarkGray
    Read-Host | Out-Null
}

<#
.SYNOPSIS
    Create a new draft interactively, then optionally fix it.
#>
function Invoke-NewDraftInteractive {
    Clear-Host
    Write-ConsoleLine -Message ''
    Write-ConsoleLine -Message "  ──── New Draft ────" -ForegroundColor Cyan
    Write-ConsoleLine -Message ''

    # Title
    Write-Host -NoNewline -ForegroundColor White '  Title (optional): '
    $title = (Read-Host).Trim()

    # Content (multiline)
    Write-ConsoleLine -Message ''
    Write-ConsoleLine -Message "  Enter draft content below." -ForegroundColor White
    Write-ConsoleLine -Message "  Finish with a line containing only '.' (dot)." -ForegroundColor DarkGray
    Write-ConsoleLine -Message ''

    $lines = @()
    while ($true) {
        $line = Read-Host
        if ($line -eq '.') { break }
        $lines += $line
    }
    $content = ($lines -join [Environment]::NewLine).Trim()

    if (-not $title -and -not $content) {
        Write-ConsoleLine -Message ''
        Write-ConsoleLine -Message "  Draft not saved: title and content are both empty." -ForegroundColor Yellow
        Start-Sleep -Milliseconds 1500
        return
    }

    # Save draft
    $dirs = Get-TaskDirectories
    $draftDir = $dirs.Draft
    if (-not (Test-Path $draftDir)) {
        New-Item -ItemType Directory -Path $draftDir -Force | Out-Null
    }

    # Determine filename
    if ($title) {
        $safeName = $title -replace '[\\/*?:"<>|]', '_' -replace '\s+', '-'
        $safeName = $safeName -replace '[^A-Za-z0-9._-]', '-' -replace '-+', '-'
        $safeName = $safeName.Trim(' ', '.', '-', '_')
        if ($safeName.Length -gt 60) { $safeName = $safeName.Substring(0, 60).Trim(' ', '.', '-', '_') }
        if (-not $safeName) { $safeName = 'draft' }
        $fileName = "$safeName.md"
    }
    else {
        $ts = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
        $fileName = "draft-$ts.md"
    }

    $fileInfo = Resolve-UniquePath -Directory $draftDir -BaseName ([System.IO.Path]::GetFileNameWithoutExtension($fileName)) -Extension '.md'
    $filePath = $fileInfo.Path

    $desc = if ($content) { "Task drafted via coworker CLI." } else { "" }
    $body = if ($content) { $content } else { "Describe the task here." }
    $fileContent = @"
Title: $title
Description: $desc
Prompt: $body
"@
    Set-Content -Path $filePath -Value $fileContent -Encoding UTF8

    Write-ConsoleLine -Message ''
    Write-ConsoleLine -Message "  Created draft: $filePath" -ForegroundColor Green

    # Ask if user wants to fix it now
    Write-ConsoleLine -Message ''
    Write-Host -NoNewline -ForegroundColor Cyan '  Fix this issue now? (assign to 1ready + run cowarker fix) [y/N] '
    $fixNow = Read-Host
    if ($fixNow -match '^[yY]') {
        Write-ConsoleLine -Message "  Assigning to 1ready/..." -ForegroundColor Cyan

        $readyDir = $dirs.Ready
        if (-not (Test-Path $readyDir)) {
            New-Item -ItemType Directory -Path $readyDir -Force | Out-Null
        }

        $destInfo = Resolve-UniquePath -Directory $readyDir -BaseName ([System.IO.Path]::GetFileNameWithoutExtension($filePath)) -Extension '.md'
        Move-Item -Path $filePath -Destination $destInfo.Path -Force
        Write-ConsoleLine -Message "  Moved to 1ready: $($destInfo.Path)" -ForegroundColor Green
        Ensure-DraftPlaceholders -DraftDirectory $dirs.Draft

        Write-ConsoleLine -Message "  Running coworker fix..." -ForegroundColor Cyan

        $b4wScript = Join-Path (Get-WorkspaceRoot) 'b4w.ps1'
        if (Test-Path $b4wScript) {
            $process = Start-Process -FilePath 'pwsh.exe' `
                -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $b4wScript, 'coworker', 'fix', '-Path', $destInfo.Path) `
                -PassThru -NoNewWindow -Wait

            if ($process.ExitCode -ne 0) {
                Write-ConsoleLine -Message "  Fix completed with exit code $($process.ExitCode)." -ForegroundColor Yellow
            }
            else {
                Write-ConsoleLine -Message "  Fix completed successfully." -ForegroundColor Green
            }
        }
        else {
            Write-ConsoleLine -Message "  Warning: b4w.ps1 not found. File is in 1ready/ — run manually:" -ForegroundColor Yellow
            Write-ConsoleLine -Message "    b4w coworker fix -Path `"$($destInfo.Path)`"" -ForegroundColor Yellow
        }
    }
    else {
        Write-ConsoleLine -Message "  Draft saved. Use 'f $((Get-DraftEntries).Count)' from the panel to fix it later." -ForegroundColor DarkGray
    }

    Write-ConsoleLine -Message ''
    Write-ConsoleLine -Message '  Press Enter to return to the draft list.' -ForegroundColor DarkGray
    Write-Host -NoNewline
    Read-Host | Out-Null
}

# ── Main loop ──────────────────────────────────────────────────────────────

<#
.SYNOPSIS
    Entry point for the interactive draft panel.
    Called when `coworker draft -i` or `coworker draft --interactive` is invoked.
#>
function Invoke-DraftPanel {
    # Verify we have a TTY
    if (-not (Test-CanPrompt)) {
        Write-ConsoleLine -Message 'Error: Interactive draft panel requires a TTY (console input).' -ForegroundColor Red
        Write-ConsoleLine -Message 'Use -NoInteractive for non-interactive draft creation.' -ForegroundColor Yellow
        exit 1
    }

    $statusMessage = ''
    $statusColor = 'DarkGray'

    while ($true) {
        $drafts = @(Get-DraftEntries)
        Write-DraftPanel -Drafts $drafts -StatusMessage $statusMessage -StatusColor $statusColor
        $statusMessage = ''
        $statusColor = 'DarkGray'

        $input = Read-PanelInput

        if (-not $input) {
            continue
        }

        # Match patterns
        switch -Regex ($input) {
            '^[qQ]$' {
                # Quit
                Clear-Host
                Write-ConsoleLine -Message "  Coworker Draft Panel closed." -ForegroundColor DarkGray
                break
            }
            '^\?$' {
                Show-PanelHelp
                continue
            }
            '^[rR]$' {
                # Refresh — naturally happens each loop iteration
                continue
            }
            '^[nN]$' {
                Invoke-NewDraftInteractive
                continue
            }
            '^[vV]\s+(\d+)$' {
                $idx = [int]$Matches[1]
                Show-DraftView -Drafts $drafts -Index $idx
                continue
            }
            '^[vV]$' {
                # Bare 'v' — ask for which one
                Write-ConsoleLine -Message "  Usage: v <number>  (e.g. v 1)" -ForegroundColor Yellow
                Start-Sleep -Milliseconds 1500
                continue
            }
            '^[fF]\s+(\d+)$' {
                $idx = [int]$Matches[1]
                Invoke-FixDraft -Drafts $drafts -Index $idx
                continue
            }
            '^[fF]$' {
                # Bare 'f' — ask for which one
                Write-ConsoleLine -Message "  Usage: f <number>  (e.g. f 1)" -ForegroundColor Yellow
                Start-Sleep -Milliseconds 1500
                continue
            }
            default {
                $statusMessage = "Unknown command: '$input'. Type ? for help."
                $statusColor = 'Red'
                continue
            }
        }
        break
    }
}
