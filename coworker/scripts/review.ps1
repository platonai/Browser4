# ═══════════════════════════════════════════════════════════════════════════════
# Review — Interactive CLI for reviewing .issues.md files
# ═══════════════════════════════════════════════════════════════════════════════
# Dot-sourced by coworker.ps1.  Provides the "coworker review" subcommand.
#
# All functions use the script: scope to avoid leaking into the global session.
# ═══════════════════════════════════════════════════════════════════════════════

# ── Constants ──────────────────────────────────────────────────────────────────

$script:ReviewDecisions = @(
    'ACCEPT',
    'ACCEPT with improvements',
    'DEFER',
    'WONTFIX',
    'REJECT',
    'DUPLICATE'
)

$script:DecisionColors = @{
    'ACCEPT'                  = 'Green'
    'ACCEPT with improvements' = 'Cyan'
    'DEFER'                   = 'Yellow'
    'WONTFIX'                 = 'DarkYellow'
    'REJECT'                  = 'Red'
    'DUPLICATE'               = 'Magenta'
}

$script:SeverityColors = @{
    'Critical' = 'Red'
    'High'     = 'Yellow'
    'Medium'   = 'Cyan'
    'Low'      = 'DarkGray'
}

$script:SectionOrder = @(
    'Reproduction',
    'Expected Behavior',
    'Actual Behavior',
    'Root Cause Analysis',
    'Code Pointer',
    'AI Suggested Improvement'
)

# ── Path helpers ───────────────────────────────────────────────────────────────

function Get-IssuesDirectories {
    <#
    .SYNOPSIS
        Returns the issues directory paths used by the review workflow.
    .OUTPUTS
        PSCustomObject with Draft, Review, ReviewDone, ReviewDiscard paths.
    #>
    $tasksRoot = Get-TasksRoot
    $issuesRoot = Join-Path $tasksRoot 'issues'
    return [pscustomobject]@{
        IssuesRoot     = $issuesRoot
        Draft          = Join-Path $issuesRoot 'draft'
        Review         = Join-Path $issuesRoot 'review'
        ReviewDone     = Join-Path $issuesRoot 'review' 'done'
        ReviewDiscard  = Join-Path $issuesRoot 'review' 'done' 'discard'
    }
}

function Find-IssuesFiles {
    <#
    .SYNOPSIS
        Find all .issues.md files in draft and review directories.
    .DESCRIPTION
        Scans draft/ and review/ recursively for *.issues.md files,
        EXCLUDING review/done/ and review/done/discard/ subdirectories
        (those contain already-processed files).
    .PARAMETER IncludeDone
        Also scan review/done/ directories.
    .OUTPUTS
        String array of absolute paths, sorted newest-first.
    #>
    param(
        [switch]$IncludeDone
    )

    $dirs = Get-IssuesDirectories
    $searchDirs = @($dirs.Draft, $dirs.Review)

    if ($IncludeDone) {
        $searchDirs += $dirs.ReviewDone
    }

    # Normalize the "excluded" paths for filtering
    $doneNormalized = if (Test-Path -LiteralPath $dirs.ReviewDone) {
        (Resolve-Path -LiteralPath $dirs.ReviewDone).Path
    } else { '' }
    $discardNormalized = if (Test-Path -LiteralPath $dirs.ReviewDiscard) {
        (Resolve-Path -LiteralPath $dirs.ReviewDiscard).Path
    } else { '' }

    $results = [System.Collections.ArrayList]::new()
    $seen = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($dir in $searchDirs) {
        if (-not (Test-Path -LiteralPath $dir)) { continue }
        $found = Get-ChildItem -Path $dir -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object { -not (Test-CoworkerIgnoredFile -Item $_) } |
            Where-Object { $_.Name -like '*.issues.md' } |
            Where-Object {
                # Exclude files under review/done/ and review/done/discard/
                # when -IncludeDone is not set and we are scanning Review
                if (-not $IncludeDone) {
                    $full = $_.FullName
                    if ($doneNormalized -and $full.StartsWith($doneNormalized, [StringComparison]::OrdinalIgnoreCase)) {
                        return $false
                    }
                    if ($discardNormalized -and $full.StartsWith($discardNormalized, [StringComparison]::OrdinalIgnoreCase)) {
                        return $false
                    }
                }
                return $true
            }
        foreach ($f in $found) {
            if ($seen.Add($f.FullName)) {
                [void]$results.Add($f.FullName)
            }
        }
    }

    # Sort by filename descending (newest first — filenames start with timestamp)
    $sorted = @($results | Sort-Object { (Split-Path -Leaf $_) } -Descending)
    return $sorted
}

function Resolve-IssuesFile {
    <#
    .SYNOPSIS
        Resolve a user-provided path or name to a single .issues.md file.
    .PARAMETER Path
        Absolute or relative path to a .issues.md file.
    .PARAMETER Name
        Partial name to search for among discovered .issues.md files.
    .OUTPUTS
        Absolute path string, or $null if not found.
    #>
    param(
        [string]$Path = '',
        [string]$Name = ''
    )

    if ($Path) {
        if (Test-Path -LiteralPath $Path -PathType Leaf) {
            return (Resolve-Path -LiteralPath $Path).Path
        }
        # Try relative to issues directories
        $dirs = Get-IssuesDirectories
        foreach ($baseDir in @($dirs.Draft, $dirs.Review)) {
            $candidate = Join-Path $baseDir $Path
            if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                return (Resolve-Path -LiteralPath $candidate).Path
            }
        }
        Write-ConsoleLine -Message "File not found: $Path" -ForegroundColor Red
        return $null
    }

    if ($Name) {
        $allFiles = Find-IssuesFiles
        $searchLower = $Name.ToLowerInvariant()
        $matches = @($allFiles | Where-Object {
            (Split-Path -Leaf $_).ToLowerInvariant().Contains($searchLower)
        })

        if ($matches.Count -eq 0) {
            Write-ConsoleLine -Message "No .issues.md file matching '$Name' found." -ForegroundColor Red
            return $null
        }

        if ($matches.Count -eq 1) {
            return $matches[0]
        }

        # Multiple matches — present choices
        Write-ConsoleLine -Message "Multiple matches for '$Name':" -ForegroundColor Yellow
        for ($i = 0; $i -lt $matches.Count; $i++) {
            Write-ConsoleLine -Message "  [$i] $($matches[$i])"
        }
        Write-ConsoleLine -Message "Using first match. Specify full path to disambiguate." -ForegroundColor DarkGray
        return $matches[0]
    }

    return $null
}

# ── File parsing ───────────────────────────────────────────────────────────────

function Read-IssuesFile {
    <#
    .SYNOPSIS
        Parse a .issues.md file into a structured object.
    .DESCRIPTION
        Reads the file and extracts:
          - Meta (scenario, source, date, mode)
          - Background (task summary, execution context)
          - Issues[] (number, title, severity, category, sections, reviewDecision, reviewNotes)
          - OriginalContent (raw text for later reconstruction)
    .PARAMETER FilePath
        Absolute path to the .issues.md file.
    .OUTPUTS
        PSCustomObject with Meta, Background, Issues, OriginalContent, FilePath.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath
    )

    if (-not (Test-Path -LiteralPath $FilePath -PathType Leaf)) {
        throw "File not found: $FilePath"
    }

    $rawContent = Get-Content -LiteralPath $FilePath -Raw -Encoding UTF8
    if ([string]::IsNullOrWhiteSpace($rawContent)) {
        throw "File is empty: $FilePath"
    }

    # Strip UTF-8 BOM if present (0xEF 0xBB 0xBF)
    if ($rawContent.Length -ge 3 -and $rawContent[0] -eq [char]0xFEFF) {
        $rawContent = $rawContent.Substring(1)
    }

    $normalized = $rawContent -replace "`r`n", "`n"

    # ── Parse meta line ──────────────────────────────────────────────────────
    $meta = @{
        Scenario = ''
        Source   = ''
        Date     = ''
        Mode     = 'dev'
    }
    if ($normalized -match '^# Issues:\s*(\S[^\r\n]*)') {
        $meta.Scenario = $Matches[1].Trim()
    }
    if ($normalized -match '>\s*\*\*Source:\*\*\s*`([^`]+)`\s*\|\s*\*\*Date:\*\*\s*(\S+)\s*\|\s*\*\*Mode:\*\*\s*(\S[^\r\n]*)') {
        $meta.Source = $Matches[1]
        $meta.Date   = $Matches[2]
        $meta.Mode   = $Matches[3]
    }

    # ── Parse background ─────────────────────────────────────────────────────
    $background = @{
        Task             = ''
        ExecutionContext = ''
    }

    # Find "## Scenario Background" through "## Issues Found"
    $bgStart = -1
    if ($normalized -match '## Scenario Background') {
        $bgStart = $normalized.IndexOf('## Scenario Background')
    }
    $issuesHeaderIdx = -1
    if ($normalized -match '## Issues Found') {
        $issuesHeaderIdx = $normalized.IndexOf('## Issues Found')
    }

    if ($bgStart -ge 0) {
        $bgContentStart = $normalized.IndexOf("`n", $bgStart) + 1
        $bgEnd = if ($issuesHeaderIdx -ge 0) { $issuesHeaderIdx } else { $normalized.Length }
        $bgSection = $normalized.Substring($bgContentStart, [Math]::Max(0, $bgEnd - $bgContentStart)).Trim()

        # Extract "### Task" subsection
        if ($bgSection -match '(?s)### Task\n(.+?)(?=\n###\s|\n---\s*\n|$)') {
            $background.Task = $Matches[1].Trim()
        }
        # Extract "### Execution Context" subsection
        if ($bgSection -match '(?s)### Execution Context\n(.+?)(?=\n---\s*\n|\n##\s|$)') {
            $background.ExecutionContext = $Matches[1].Trim()
        }
    }

    # ── Parse issues ─────────────────────────────────────────────────────────
    $issues = @()
    if ($issuesHeaderIdx -ge 0) {
        $issuesSectionStart = $normalized.IndexOf("`n", $issuesHeaderIdx) + 1
        # Find "## How to Reproduce" as the end boundary
        $howToIdx = $normalized.IndexOf("`n## How to Reproduce", $issuesSectionStart)
        if ($howToIdx -lt 0) { $howToIdx = $normalized.Length }
        $issuesSection = $normalized.Substring($issuesSectionStart, [Math]::Max(0, $howToIdx - $issuesSectionStart))

        # Split on "### Issue N:" headers
        $issueBlocks = @($issuesSection -split '(?=###\s+Issue\s+\d+:)') |
            Where-Object { $_ -match '###\s+Issue\s+(\d+):\s*(.+)' }

        $issueNum = 0
        foreach ($block in $issueBlocks) {
            $issueNum++
            $issue = @{
                Number         = $issueNum
                Title          = ''
                Severity       = ''
                Category       = ''
                Sections       = @()        # array of @{Label, Body}
                Decision       = $null
                Notes          = ''
            }

            # Title
            if ($block -match '###\s+Issue\s+\d+:\s*(.+?)(?:\n|$)') {
                $issue.Title = $Matches[1].Trim()
            }

            # Severity / Category (bold labels at top of issue block)
            if ($block -match '\*\*Severity:\*\*\s*(.+?)(?:\n|$)') {
                $issue.Severity = $Matches[1].Trim()
            }
            if ($block -match '\*\*Category:\*\*\s*(.+?)(?:\n|$)') {
                $issue.Category = $Matches[1].Trim()
            }

            # ── Parse sections (#### headers) ──────────────────────────────
            # Find the Human Review boundary first
            $hrIdx = $block.IndexOf("`n#### Human Review")
            if ($hrIdx -lt 0) { $hrIdx = $block.Length }
            $bodyBlock = $block.Substring(0, $hrIdx)

            # Split on #### headers (but skip the first chunk before any header)
            $sectionChunks = @($bodyBlock -split '(?=####\s+)') |
                Where-Object { $_ -match '####\s+(.+)' }

            foreach ($chunk in $sectionChunks) {
                if ($chunk -match '####\s+(.+?)(?:\n|$)((?s:.*))') {
                    $label = $Matches[1].Trim()
                    $body = $Matches[2].Trim()
                    if ($label -and $body) {
                        $issue.Sections += @{ Label = $label; Body = $body }
                    }
                }
            }

            # ── Parse Human Review section ─────────────────────────────────
            if ($hrIdx -ge 0) {
                $hrBlock = $block.Substring($hrIdx)

                # Detect which decision is checked
                foreach ($dec in $script:ReviewDecisions) {
                    $escaped = [regex]::Escape($dec)
                    if ($hrBlock -match "- \[x\] \*\*$escaped\*\*") {
                        $issue.Decision = $dec
                        break
                    }
                }

                # Extract Notes: capture until next --- separator or next issue.
                # NOTE: \s matches \n, so use [^\S\n] (whitespace except newline) to
                # avoid consuming the newline that anchors the --- separator.
                if ($hrBlock -match "\*\*Notes:\*\*[^\S\n]*\n((?s:.*?))(?=\n---|\n###\s|\Z)") {
                    $notesRaw = $Matches[1].Trim()
                    if ($notesRaw) {
                        $issue.Notes = $notesRaw
                    }
                }
            }

            $issues += $issue
        }
    }

    return [PSCustomObject]@{
        Meta            = [PSCustomObject]$meta
        Background      = [PSCustomObject]$background
        Issues          = $issues
        OriginalContent = $rawContent
        FilePath        = $FilePath
    }
}

# ── File writing ───────────────────────────────────────────────────────────────

function Write-IssuesFile {
    <#
    .SYNOPSIS
        Write review decisions and notes back to the .issues.md file.
    .DESCRIPTION
        Modifies only the #### Human Review blocks within the original content.
        All other formatting is preserved byte-for-byte.  Each issue's decision
        checkbox is set to [x] and all others to [ ].
    .PARAMETER ParsedFile
        The structured object returned by Read-IssuesFile (with updated issues).
    #>
    param(
        [Parameter(Mandatory = $true)]
        [PSObject]$ParsedFile
    )

    $content = $ParsedFile.OriginalContent

    # Normalize line endings for reliable processing
    $normalized = $content -replace "`r`n", "`n"

    foreach ($issue in $ParsedFile.Issues) {
        # Find this issue's Human Review block
        $issuePattern = "### Issue $($issue.Number):"
        $issueIdx = $normalized.IndexOf($issuePattern)
        if ($issueIdx -lt 0) { continue }

        # Find the next issue boundary or end of issues section
        $nextIssuePattern = "`n### Issue "
        $nextIdx = $normalized.IndexOf($nextIssuePattern, $issueIdx + $issuePattern.Length)
        if ($nextIdx -lt 0) {
            # Try "## How to Reproduce" as boundary
            $nextIdx = $normalized.IndexOf("`n## How to Reproduce", $issueIdx)
        }
        if ($nextIdx -lt 0) {
            $nextIdx = $normalized.Length
        }

        # Extract the issue block
        $issueBlock = $normalized.Substring($issueIdx, $nextIdx - $issueIdx)

        # Find the Human Review section within this block
        $hrMarker = "`n#### Human Review"
        $hrIdx = $issueBlock.IndexOf($hrMarker)
        if ($hrIdx -lt 0) { continue }

        $hrStart = $issueIdx + $hrIdx
        # Find where Human Review ends (--- separator or end of block)
        $hrContentStart = $hrStart + $hrMarker.Length
        $hrEnd = $normalized.IndexOf("`n---", $hrContentStart)
        if ($hrEnd -lt 0 -or $hrEnd -ge $nextIdx) {
            $hrEnd = $nextIdx
        }

        $oldHR = $normalized.Substring($hrStart, $hrEnd - $hrStart)

        # Build the new Human Review section
        $newHR = "`n#### Human Review`n`n"
        foreach ($dec in $script:ReviewDecisions) {
            $checked = if ($issue.Decision -eq $dec) { '[x]' } else { '[ ]' }
            $desc = Get-DecisionDescription -Decision $dec
            $newHR += "- $checked **$dec**"
            if ($desc) { $newHR += " — $desc" }
            $newHR += "`n"
        }
        $newHR += "- **Notes:**"
        if ($issue.Notes -and $issue.Notes.Trim()) {
            $newHR += "`n$($issue.Notes.Trim())"
        }
        $newHR += "`n"

        # Replace in the normalized content
        $normalized = $normalized.Substring(0, $hrStart) + $newHR + $normalized.Substring($hrEnd)
    }

    # Convert back to platform line endings
    $result = $normalized -replace "`n", "`r`n"

    # Write back to the original file
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText(
        [System.IO.Path]::GetFullPath($ParsedFile.FilePath),
        $result,
        $utf8NoBom
    )

    # Update the in-memory original so subsequent saves work correctly
    $ParsedFile.OriginalContent = $result
}

function Get-DecisionDescription {
    param([string]$Decision)
    switch ($Decision) {
        'ACCEPT'                  { return 'issue confirmed valid; suggested improvement is correct' }
        'ACCEPT with improvements' { return 'issue valid but fix needs refinement (add details in Notes)' }
        'DEFER'                   { return 'issue acknowledged but intentionally deferred (add rationale in Notes)' }
        'WONTFIX'                 { return 'issue acknowledged but will not be fixed (add rationale in Notes)' }
        'REJECT'                  { return 'issue invalid, not a problem, or already addressed' }
        'DUPLICATE'               { return 'issue duplicates another existing issue (reference in Notes)' }
        default                   { return '' }
    }
}

# ── File operations ────────────────────────────────────────────────────────────

function Move-IssuesFile {
    <#
    .SYNOPSIS
        Move a .issues.md file to review/done/ or review/done/discard/.
    .PARAMETER FilePath
        Source file to move.
    .PARAMETER Discard
        If set, moves to review/done/discard/ instead of review/done/.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,
        [switch]$Discard
    )

    $dirs = Get-IssuesDirectories
    $targetDir = if ($Discard) { $dirs.ReviewDiscard } else { $dirs.ReviewDone }

    # Preserve date-based subdirectory structure
    $relativePath = ''
    $fileParent = Split-Path -Parent $FilePath

    # Try to extract the date-based portion of the path relative to review/ or draft/
    foreach ($baseDir in @($dirs.Review, $dirs.Draft)) {
        $baseDirNormalized = (Resolve-Path -LiteralPath $baseDir -ErrorAction SilentlyContinue).Path
        $fileParentNormalized = (Resolve-Path -LiteralPath $fileParent -ErrorAction SilentlyContinue).Path
        if ($fileParentNormalized.StartsWith($baseDirNormalized, [StringComparison]::OrdinalIgnoreCase)) {
            $relativePath = $fileParentNormalized.Substring($baseDirNormalized.Length).TrimStart('\/')
            break
        }
    }

    if ($relativePath) {
        $targetDir = Join-Path $targetDir $relativePath
    }

    if (-not (Test-Path -LiteralPath $targetDir)) {
        New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
    }

    $fileName = Split-Path -Leaf $FilePath
    $destPath = Join-Path $targetDir $fileName

    # Handle collisions
    if (Test-Path -LiteralPath $destPath) {
        $baseName = [System.IO.Path]::GetFileNameWithoutExtension($fileName)
        $ext = [System.IO.Path]::GetExtension($fileName)
        $counter = 2
        while (Test-Path -LiteralPath $destPath) {
            $destPath = Join-Path $targetDir "$baseName.$counter$ext"
            $counter++
        }
    }

    Move-Item -Path $FilePath -Destination $destPath -Force
    return $destPath
}

# ── Display rendering ──────────────────────────────────────────────────────────

function Write-ProgressBar {
    param(
        [int]$Completed,
        [int]$Total,
        [int]$Width = 30
    )
    if ($Total -eq 0) {
        Write-Host ('░' * $Width) -NoNewline -ForegroundColor DarkGray
        return
    }
    $pct = [Math]::Min(100, [Math]::Round($Completed / $Total * 100))
    $filled = [Math]::Round($Width * $Completed / $Total)
    $empty = $Width - $filled
    Write-Host ('█' * $filled) -NoNewline -ForegroundColor Cyan
    Write-Host ('░' * $empty) -NoNewline -ForegroundColor DarkGray
    Write-Host "  $Completed/$Total reviewed ($pct%)" -NoNewline
}

function Write-SeverityBadge {
    param([string]$Severity)
    $color = if ($script:SeverityColors.ContainsKey($Severity)) { $script:SeverityColors[$Severity] } else { 'White' }
    Write-Host " $Severity " -NoNewline -BackgroundColor $color -ForegroundColor Black
}

function Write-DecisionBadge {
    param([string]$Decision)
    if (-not $Decision) {
        Write-Host ' [not set] ' -NoNewline -ForegroundColor DarkGray
        return
    }
    $color = if ($script:DecisionColors.ContainsKey($Decision)) { $script:DecisionColors[$Decision] } else { 'White' }
    Write-Host " $Decision " -NoNewline -ForegroundColor $color
}

function Clear-ScreenSafe {
    try { Clear-Host } catch { }
}

function Get-TerminalWidth {
    <#
    .SYNOPSIS
        Return the usable content width for the review display.
    #>
    $raw = 120
    try { $raw = $host.UI.RawUI.WindowSize.Width } catch { }
    if ($raw -le 0) { $raw = 120 }
    return [Math]::Max(80, $raw - 2)
}

function Show-IssueHeader {
    <#
    .SYNOPSIS
        Display the review file header (scenario name, meta, progress bar).
    #>
    param([PSObject]$ParsedFile)

    $meta = $ParsedFile.Meta
    $total = $ParsedFile.Issues.Count
    $reviewed = @($ParsedFile.Issues | Where-Object { $_.Decision }).Count
    $W = Get-TerminalWidth
    $inner = $W - 2  # content area inside borders

    Clear-ScreenSafe
    Write-Host ''
    Write-Host ('╔' + ('═' * $inner) + '╗') -ForegroundColor DarkGray

    # First line: scenario name + mode
    $line1 = "  Issues: $($meta.Scenario)"
    $modeTag = "  $($meta.Mode)  "
    $line1Max = $inner - $modeTag.Length - 2
    if ($line1.Length -gt $line1Max) { $line1 = $line1.Substring(0, $line1Max - 2) + '…' }
    Write-Host '║ ' -NoNewline -ForegroundColor DarkGray
    Write-Host $line1.PadRight($inner - $modeTag.Length - 2) -NoNewline -ForegroundColor Cyan
    Write-Host $modeTag -NoNewline -ForegroundColor DarkGray
    Write-Host ' ║' -ForegroundColor DarkGray

    # Source line
    if ($meta.Source) {
        $srcLine = "  Source: $($meta.Source)"
        if ($srcLine.Length -gt $inner - 2) { $srcLine = $srcLine.Substring(0, $inner - 4) + '…' }
        Write-Host ('║ ' + $srcLine.PadRight($inner - 2) + ' ║') -ForegroundColor DarkGray
    }

    # Progress bar
    Write-Host '║ ' -NoNewline -ForegroundColor DarkGray
    Write-ProgressBar -Completed $reviewed -Total $total -Width ($inner - 4)
    Write-Host ' ║' -ForegroundColor DarkGray
    Write-Host ('╚' + ('═' * $inner) + '╝') -ForegroundColor DarkGray
    Write-Host ''
}

function Show-IssueDisplay {
    <#
    .SYNOPSIS
        Render a single issue with all sections and review controls.
    #>
    param(
        [PSObject]$ParsedFile,
        [int]$Index
    )

    $issue = $ParsedFile.Issues[$Index]
    if (-not $issue) { return }

    $total = $ParsedFile.Issues.Count
    Show-IssueHeader -ParsedFile $ParsedFile
    $W = Get-TerminalWidth
    $inner = $W - 2  # content area inside borders
    $textW = $inner - 4  # width for text content (inside padding)

    # ── Issue card top ─────────────────────────────────────────────────────
    $cardTitle = " Issue $($issue.Number) of $total "
    $dashLen = $inner - $cardTitle.Length
    if ($dashLen -lt 0) { $dashLen = 0; $cardTitle = $cardTitle.Substring(0, $inner - 2) + '…' }
    Write-Host ('┌' + $cardTitle) -NoNewline -ForegroundColor Cyan
    Write-Host ('─' * $dashLen + '┐') -ForegroundColor DarkGray

    # Severity + Category + Decision — single line, decision right-flush
    $decLabel = if ($issue.Decision) { $issue.Decision } else { '[not set]' }
    $sevCat = "  Severity: $($issue.Severity)    Category: $($issue.Category)  "
    $decPart = "Decision: $decLabel  "
    $metaPad = $inner - $sevCat.Length - $decPart.Length
    if ($metaPad -lt 2) { $metaPad = 2 }
    Write-Host '│' -NoNewline -ForegroundColor DarkGray
    Write-Host $sevCat -NoNewline -ForegroundColor White
    Write-Host (' ' * $metaPad) -NoNewline
    Write-Host $decPart -NoNewline -ForegroundColor DarkGray
    Write-Host '│' -ForegroundColor DarkGray

    Write-Host ('├' + ('─' * $inner) + '┤') -ForegroundColor DarkGray

    # Issue title
    $title = $issue.Title
    if ($title.Length -gt $textW) { $title = $title.Substring(0, $textW - 3) + '...' }
    Write-Host ('│  ' + $title.PadRight($textW) + '  │') -ForegroundColor Yellow
    Write-Host ('│' + (' ' * $inner) + '│') -ForegroundColor DarkGray

    # ── Sections ────────────────────────────────────────────────────────────
    foreach ($sec in $issue.Sections) {
        $secHeader = "  ── $($sec.Label) "
        $secDash = $inner - $secHeader.Length
        if ($secDash -lt 2) { $secDash = 2 }
        Write-Host ('│' + $secHeader + ('─' * $secDash)) -NoNewline -ForegroundColor DarkGray
        Write-Host '│' -ForegroundColor DarkGray

        $lines = $sec.Body -split "`n"
        foreach ($line in $lines) {
            if ($line.Length -eq 0) {
                Write-Host ('│' + (' ' * $inner) + '│') -ForegroundColor DarkGray
                continue
            }
            $remaining = $line
            while ($remaining.Length -gt 0) {
                if ($remaining.Length -gt $textW) {
                    $chunk = $remaining.Substring(0, $textW)
                    $remaining = $remaining.Substring($textW)
                } else {
                    $chunk = $remaining
                    $remaining = ''
                }
                Write-Host ('│  ' + $chunk.PadRight($textW) + '  │') -ForegroundColor White
            }
        }
        Write-Host ('│' + (' ' * $inner) + '│') -ForegroundColor DarkGray
    }

    # ── Notes ───────────────────────────────────────────────────────────────
    $notesHeader = "  ── Review Notes "
    $notesDash = $inner - $notesHeader.Length
    if ($notesDash -lt 2) { $notesDash = 2 }
    Write-Host ('│' + $notesHeader + ('─' * $notesDash)) -NoNewline -ForegroundColor DarkGray
    Write-Host '│' -ForegroundColor DarkGray

    if ($issue.Notes) {
        $notesLines = $issue.Notes -split "`n"
        foreach ($line in $notesLines) {
            $remaining = $line
            while ($remaining.Length -gt 0) {
                if ($remaining.Length -gt $textW) {
                    $chunk = $remaining.Substring(0, $textW)
                    $remaining = $remaining.Substring($textW)
                } else {
                    $chunk = $remaining
                    $remaining = ''
                }
                Write-Host ('│  ' + $chunk.PadRight($textW) + '  │') -ForegroundColor Green
            }
        }
    } else {
        Write-Host ('│  (empty)' + (' ' * ($inner - 10)) + '│') -ForegroundColor DarkGray
    }

    # Issue card bottom
    Write-Host ('└' + ('─' * $inner) + '┘') -ForegroundColor DarkGray
    Write-Host ''

    # ── Decision bar ──────────────────────────────────────────────────────────
    # 2 rows × 3 columns.  Total visual width = $inner + 2.
    # Layout: ║ + space + [3 cols] + space + ║
    # Each col: indicator(1) + label(N) + pad → fills colW.
    $margin = 2  # one space each side of content inside borders
    $colW = [Math]::Floor(($inner - $margin) / 3)
    $colRem = ($inner - $margin) - (3 * $colW)

    Write-Host ('╔' + ('═' * $inner) + '╗') -ForegroundColor DarkGray

    for ($row = 0; $row -lt 2; $row++) {
        Write-Host '║ ' -NoNewline -ForegroundColor DarkGray
        for ($col = 0; $col -lt 3; $col++) {
            $d = $row * 3 + $col
            $dec = $script:ReviewDecisions[$d]
            $num = $d + 1
            $isSelected = ($issue.Decision -eq $dec)
            $color = if ($isSelected) { $script:DecisionColors[$dec] } else { 'White' }
            $decLabel = if ($dec -eq 'ACCEPT with improvements') { 'ACCEPT+improve' } else { $dec }

            $entry = "[$num] $decLabel"
            $thisColW = $colW
            if ($col -eq 2) { $thisColW += $colRem }
            if ($entry.Length -gt $thisColW - 1) {
                $maxLen = $thisColW - 3
                if ($maxLen -lt 1) { $maxLen = 1 }
                $entry = $entry.Substring(0, $maxLen) + '…'
            }

            if ($isSelected) {
                Write-Host '▶' -NoNewline -ForegroundColor $color
            } else {
                Write-Host ' ' -NoNewline
            }
            Write-Host $entry -NoNewline -ForegroundColor $color
            $pad = $thisColW - 1 - $entry.Length
            if ($pad -gt 0) { Write-Host (' ' * $pad) -NoNewline }
        }
        Write-Host ' ║' -ForegroundColor DarkGray
    }

    Write-Host ('║' + (' ' * $inner) + '║') -ForegroundColor DarkGray

    # ── Navigation bar ──────────────────────────────────────────────────────
    # Two rows, each filling exactly $inner chars of content between the
    # border + 1-space margins.

    # Row 1
    $r1 = '[n]/[p] issue  [N]/[P] file  [a] AI  [v] view-all'
    $r1Pad = $inner - $margin - $r1.Length
    if ($r1Pad -lt 1) { $r1Pad = 1 }
    Write-Host '║ ' -NoNewline -ForegroundColor DarkGray
    Write-Host '[n]' -NoNewline -ForegroundColor Cyan
    Write-Host '/' -NoNewline -ForegroundColor DarkGray
    Write-Host '[p]' -NoNewline -ForegroundColor Cyan
    Write-Host ' issue  ' -NoNewline -ForegroundColor White
    Write-Host '[N]' -NoNewline -ForegroundColor Cyan
    Write-Host '/' -NoNewline -ForegroundColor DarkGray
    Write-Host '[P]' -NoNewline -ForegroundColor Cyan
    Write-Host ' file  ' -NoNewline -ForegroundColor White
    Write-Host '[a]' -NoNewline -ForegroundColor Cyan
    Write-Host ' AI  ' -NoNewline -ForegroundColor White
    Write-Host '[v]' -NoNewline -ForegroundColor Cyan
    Write-Host ' view-all' -NoNewline -ForegroundColor White
    Write-Host (' ' * $r1Pad) -NoNewline
    Write-Host ' ║' -ForegroundColor DarkGray

    # Row 2
    $r2 = '[e] notes  [m] mark-done  [d] discard  [q] quit  [?] help'
    $r2Pad = $inner - $margin - $r2.Length
    if ($r2Pad -lt 1) { $r2Pad = 1 }
    Write-Host '║ ' -NoNewline -ForegroundColor DarkGray
    Write-Host '[e]' -NoNewline -ForegroundColor Cyan
    Write-Host ' notes  ' -NoNewline -ForegroundColor White
    Write-Host '[m]' -NoNewline -ForegroundColor Cyan
    Write-Host ' mark-done  ' -NoNewline -ForegroundColor White
    Write-Host '[d]' -NoNewline -ForegroundColor Cyan
    Write-Host ' discard  ' -NoNewline -ForegroundColor White
    Write-Host '[q]' -NoNewline -ForegroundColor Cyan
    Write-Host ' quit  ' -NoNewline -ForegroundColor White
    Write-Host '[?]' -NoNewline -ForegroundColor Cyan
    Write-Host ' help' -NoNewline -ForegroundColor White
    Write-Host (' ' * $r2Pad) -NoNewline
    Write-Host ' ║' -ForegroundColor DarkGray

    Write-Host ('╚' + ('═' * $inner) + '╝') -ForegroundColor DarkGray
}

function Show-AllIssuesDisplay {
    <#
    .SYNOPSIS
        Render a compact table of all issues with their decision status.
    #>
    param([PSObject]$ParsedFile)

    $meta = $ParsedFile.Meta
    $total = $ParsedFile.Issues.Count
    $reviewed = @($ParsedFile.Issues | Where-Object { $_.Decision }).Count
    $W = Get-TerminalWidth
    $inner = $W - 2

    Clear-ScreenSafe
    Write-Host ''
    $allTitle = " All Issues: $($meta.Scenario) ($reviewed/$total reviewed) "
    $allDash = [Math]::Max(2, $inner - $allTitle.Length)
    Write-Host ('═' * [Math]::Floor($allDash / 2)) -NoNewline -ForegroundColor DarkGray
    Write-Host $allTitle -NoNewline -ForegroundColor Cyan
    Write-Host ('═' * [Math]::Ceiling($allDash / 2)) -ForegroundColor DarkGray
    Write-Host ''

    # Table column widths — proportional to terminal width minus padding
    $gap = 2  # spaces between columns
    $numW = 5
    $sevW = [Math]::Max(9,  [Math]::Floor($inner * 0.10))
    $catW = [Math]::Max(13, [Math]::Floor($inner * 0.15))
    $decW = [Math]::Max(22, [Math]::Floor($inner * 0.20))
    $titleW = $inner - $numW - $sevW - $catW - $decW - ($gap * 4)
    if ($titleW -lt 20) { $titleW = 20 }

    # Build a pad-right helper that writes with -NoNewline and -ForegroundColor
    function Write-Col {
        param([string]$Text, [int]$Width, [string]$Color = 'White')
        if ($Text.Length -gt $Width) {
            $Text = $Text.Substring(0, $Width - 2) + '…'
        }
        Write-Host ($Text.PadRight($Width)) -NoNewline -ForegroundColor $Color
        Write-Host (' ' * $gap) -NoNewline
    }

    # Table header (2-space left indent matching data rows)
    Write-Host '  ' -NoNewline
    Write-Col -Text '# ' -Width $numW -Color DarkGray
    Write-Col -Text 'Title' -Width $titleW -Color DarkGray
    Write-Col -Text 'Severity' -Width $sevW -Color DarkGray
    Write-Col -Text 'Category' -Width $catW -Color DarkGray
    Write-Host 'Decision' -ForegroundColor DarkGray
    Write-Host ('  ' + ('─' * ($inner - 2))) -ForegroundColor DarkGray

    foreach ($issue in $ParsedFile.Issues) {
        Write-Host '  ' -NoNewline
        # Number — left-aligned with right-padding
        $numStr = "$($issue.Number). "
        Write-Col -Text $numStr -Width $numW -Color DarkGray

        # Title — left-aligned, truncated if needed
        $title = $issue.Title
        Write-Col -Text $title -Width $titleW -Color White

        # Severity — colored by level
        $sevColor = if ($script:SeverityColors.ContainsKey($issue.Severity)) { $script:SeverityColors[$issue.Severity] } else { 'White' }
        Write-Col -Text $issue.Severity -Width $sevW -Color $sevColor

        # Category
        Write-Col -Text $issue.Category -Width $catW -Color DarkGray

        # Decision — colored + padded consistently
        if ($issue.Decision) {
            $decColor = if ($script:DecisionColors.ContainsKey($issue.Decision)) { $script:DecisionColors[$issue.Decision] } else { 'White' }
            Write-Host $issue.Decision -ForegroundColor $decColor
        } else {
            Write-Host '—' -ForegroundColor DarkGray
        }
    }

    Write-Host ''
    Write-Host ('═' * $inner) -ForegroundColor DarkGray
    Write-Host "  Press a number (1-$total) to jump, [v] for single mode, [q] to quit" -ForegroundColor DarkGray
}

function Show-Help {
    Clear-ScreenSafe
    Write-Host ''
    Write-Host '══════════ Review Help ══════════' -ForegroundColor Cyan
    Write-Host ''
    Write-Host '  REVIEW DECISIONS' -ForegroundColor Yellow
    Write-Host '  [1] ACCEPT                  — issue confirmed valid; suggested fix is correct'
    Write-Host '  [2] ACCEPT with improvements — issue valid but fix needs refinement'
    Write-Host '  [3] DEFER                   — acknowledged but deferred (add rationale)'
    Write-Host '  [4] WONTFIX                 — acknowledged but will not fix (add rationale)'
    Write-Host '  [5] REJECT                  — issue invalid or already addressed'
    Write-Host '  [6] DUPLICATE               — duplicates another issue (reference in Notes)'
    Write-Host ''
    Write-Host '  NAVIGATION' -ForegroundColor Yellow
    Write-Host '  [n] / [p]       Next / previous issue'
    Write-Host '  [N] / [P]       Next / previous file'
    Write-Host ''
    Write-Host '  ACTIONS' -ForegroundColor Yellow
    Write-Host '  [e]             Edit review notes for current issue'
    Write-Host '  [a]             AI review — get AI suggestion for current issue'
    Write-Host '  [A]             AI review ALL issues in this file'
    Write-Host '  [v]             Toggle view: single-issue / all-issues table'
    Write-Host '  [m]             Mark file as DONE → moves to 1ready/ for execution'
    Write-Host '  [d]             Discard file → moves to review/done/discard/'
    Write-Host '  [q]             Quit'
    Write-Host '  [?]             Show this help'
    Write-Host ''
    Write-Host '  State is saved automatically after every change.' -ForegroundColor DarkGray
    Write-Host ''
    Write-Host '  Press any key to return...' -ForegroundColor DarkGray
    [Console]::ReadKey($true) | Out-Null
}

# ── Interactive session ────────────────────────────────────────────────────────

function Start-ReviewSession {
    <#
    .SYNOPSIS
        Run the interactive review loop for a single .issues.md file.
    .DESCRIPTION
        Displays issues one at a time and responds to single-key commands.
        Tracks dirty state and prompts to save on quit/navigate when changes exist.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [PSObject]$ParsedFile,

        [string[]]$AllFiles = @()
    )

    $totalIssues = $ParsedFile.Issues.Count
    if ($totalIssues -eq 0) {
        Write-ConsoleLine -Message "This file has no parsed issues." -ForegroundColor Yellow
        Write-ConsoleLine -Message "Use 'coworker review' to select a different file, or discard this one." -ForegroundColor DarkGray
        return
    }

    $currentIdx = 0
    $mode = 'single'  # 'single' or 'all'
    $running = $true

    # Helper: persist current state to disk silently
    function Save-Now {
        try {
            Write-IssuesFile -ParsedFile $ParsedFile
            return $true
        } catch {
            # Show error briefly but don't block
            return $false
        }
    }

    # Find current file position in the file list for prev/next file nav
    $currentFileIdx = -1
    $normalizedPath = (Resolve-Path -LiteralPath $ParsedFile.FilePath).Path
    for ($i = 0; $i -lt $AllFiles.Count; $i++) {
        if ((Resolve-Path -LiteralPath $AllFiles[$i]).Path -eq $normalizedPath) {
            $currentFileIdx = $i
            break
        }
    }

    while ($running) {
        if ($mode -eq 'single') {
            Show-IssueDisplay -ParsedFile $ParsedFile -Index $currentIdx
        } else {
            Show-AllIssuesDisplay -ParsedFile $ParsedFile
        }

        # Read key
        $key = $null
        try {
            if ($host.UI.RawUI.KeyAvailable -or $true) {
                $key = [Console]::ReadKey($true)
            }
        } catch {
            # Non-interactive fallback
            Write-ConsoleLine -Message "Non-interactive mode — dumping all issues." -ForegroundColor Yellow
            Show-AllIssuesDisplay -ParsedFile $ParsedFile
            return
        }

        if (-not $key) { continue }

        $char = $key.KeyChar

        # ── Decision keys (1-6) ───────────────────────────────────────────
        if ($char -ge '1' -and $char -le '6') {
            $decIdx = [int][string]$char - 1
            $decision = $script:ReviewDecisions[$decIdx]
            $issue = $ParsedFile.Issues[$currentIdx]
            # Toggle: if already selected, deselect
            if ($issue.Decision -eq $decision) {
                $issue.Decision = $null
            } else {
                $issue.Decision = $decision
            }
            # Auto-save immediately
            $null = Save-Now
            continue
        }

        # ── Navigation ────────────────────────────────────────────────────
        if ($char -eq 'n') {
            if ($currentIdx -lt $totalIssues - 1) { $currentIdx++ }
            continue
        }
        if ($char -eq 'p') {
            if ($currentIdx -gt 0) { $currentIdx-- }
            continue
        }

        # Next/previous file (uppercase N/P = Shift+n, Shift+p)
        if ($char -eq 'N') {
            $null = Save-Now
            if ($currentFileIdx -ge 0 -and $currentFileIdx -lt $AllFiles.Count - 1) {
                return 'next-file'
            }
            Write-ConsoleLine -Message "Already at the last file." -ForegroundColor DarkGray
            Start-Sleep -Milliseconds 500
            continue
        }
        if ($char -eq 'P') {
            $null = Save-Now
            if ($currentFileIdx -gt 0) {
                return 'prev-file'
            }
            Write-ConsoleLine -Message "Already at the first file." -ForegroundColor DarkGray
            Start-Sleep -Milliseconds 500
            continue
        }

        # ── Edit notes ────────────────────────────────────────────────────
        if ($char -eq 'e') {
            $issue = $ParsedFile.Issues[$currentIdx]
            Write-Host ''
            Write-Host 'Review notes (Enter to submit, empty to clear):' -ForegroundColor Cyan
            if ($issue.Notes) {
                Write-Host "Current: $($issue.Notes)" -ForegroundColor DarkGray
            }
            $newNotes = Read-Host 'Notes'
            if ($newNotes) {
                $issue.Notes = $newNotes
            } elseif ($newNotes -eq '') {
                $issue.Notes = ''
            }
            $null = Save-Now
            continue
        }

        # ── Save (no-op — state saves automatically) ──────────────────────
        if ($char -eq 's') {
            $null = Save-Now
            continue
        }

        # ── AI Review ─────────────────────────────────────────────────────
        if ($char -eq 'a') {
            $issue = $ParsedFile.Issues[$currentIdx]
            $aiResult = Invoke-AiReview -Issue $issue -ParsedFile $ParsedFile -Batch:$false
            if ($aiResult) { $null = Save-Now }
            continue
        }
        if ($char -eq 'A') {
            $aiResult = Invoke-AiReview -ParsedFile $ParsedFile -Batch:$true
            if ($aiResult) { $null = Save-Now }
            continue
        }

        # ── Toggle view mode ──────────────────────────────────────────────
        if ($char -eq 'v') {
            $mode = if ($mode -eq 'single') { 'all' } else { 'single' }
            continue
        }

        # ── Mark done ─────────────────────────────────────────────────────
        if ($char -eq 'm') {
            $null = Save-Now

            $unreviewed = @($ParsedFile.Issues | Where-Object { -not $_.Decision }).Count
            Write-Host ''
            Write-Host 'Finalize this review?' -ForegroundColor Yellow
            if ($unreviewed -gt 0) {
                Write-Host "  $unreviewed issue(s) still unreviewed — will be treated as WONTFIX." -ForegroundColor Yellow
            }
            Write-Host '  • Approved issues → keep full detail'
            Write-Host '  • Other issues → condensed abstract'
            Write-Host '  • Original file → moved to 1ready/ for execution'
            $confirm = Read-Host "Proceed? [y/N]"
            if ($confirm -match '^[yY]') {
                try {
                    $tasksRoot = Get-TasksRoot
                    $readyDir = Join-Path $tasksRoot 'main' '1ready'
                    if (-not (Test-Path -LiteralPath $readyDir)) {
                        New-Item -ItemType Directory -Path $readyDir -Force | Out-Null
                    }
                    $fileName = Split-Path -Leaf $ParsedFile.FilePath
                    $destBaseName = [System.IO.Path]::GetFileNameWithoutExtension($fileName)
                    $destPath = Join-Path $readyDir $fileName

                    # Handle collisions
                    if (Test-Path -LiteralPath $destPath) {
                        $counter = 2
                        $ext = [System.IO.Path]::GetExtension($fileName)
                        while (Test-Path -LiteralPath $destPath) {
                            $destPath = Join-Path $readyDir "$destBaseName.$counter$ext"
                            $counter++
                        }
                    }

                    Move-Item -Path $ParsedFile.FilePath -Destination $destPath -Force
                    Write-ConsoleLine -Message "Marked done → $destPath" -ForegroundColor Green
                    return 'done'
                } catch {
                    Write-ConsoleLine -Message "Mark done failed: $_" -ForegroundColor Red
                    Start-Sleep -Milliseconds 500
                }
            }
            continue
        }

        # ── Discard ───────────────────────────────────────────────────────
        if ($char -eq 'd') {
            Write-Host ''
            Write-Host 'Discard this file? It will be moved to review/done/discard/.' -ForegroundColor Yellow
            Write-Host 'Use this for files with no valuable issues.' -ForegroundColor DarkGray
            $confirm = Read-Host "Proceed? [y/N]"
            if ($confirm -match '^[yY]') {
                try {
                    $destPath = Move-IssuesFile -FilePath $ParsedFile.FilePath -Discard
                    Write-ConsoleLine -Message "Discarded → $destPath" -ForegroundColor Green
                    return 'discard'
                } catch {
                    Write-ConsoleLine -Message "Discard failed: $_" -ForegroundColor Red
                    Start-Sleep -Milliseconds 500
                }
            }
            continue
        }

        # ── Help ──────────────────────────────────────────────────────────
        if ($char -eq '?') {
            Show-Help
            continue
        }

        # ── Quit ──────────────────────────────────────────────────────────
        if ($char -eq 'q') {
            $null = Save-Now
            return 'quit'
        }

        # ── Jump to issue (in all mode) ───────────────────────────────────
        if ($mode -eq 'all') {
            $num = 0
            if ([int]::TryParse([string]$char, [ref]$num)) {
                if ($num -ge 1 -and $num -le $totalIssues) {
                    $currentIdx = $num - 1
                    $mode = 'single'
                }
            }
        }
    }
}

# ── AI Review ──────────────────────────────────────────────────────────────────

function Invoke-AiReview {
    <#
    .SYNOPSIS
        Invoke the configured AI agent to review one or all issues.
    .DESCRIPTION
        Sends issue details to the AI agent and parses the response for a
        decision.  When -Batch is set, all issues are reviewed together.
    .PARAMETER ParsedFile
        The structured review file.
    .PARAMETER Issue
        Single issue to review (ignored when -Batch is set).
    .PARAMETER Batch
        Review all issues in the file at once.
    .OUTPUTS
        Boolean — $true if any changes were applied.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [PSObject]$ParsedFile,
        [PSObject]$Issue = $null,
        [switch]$Batch
    )

    # Check if an agent CLI is available
    $agent = 'claude'
    if (-not (Get-Command claude -ErrorAction SilentlyContinue)) {
        if (Get-Command kimi -ErrorAction SilentlyContinue) {
            $agent = 'kimi'
        } elseif (Get-Command opencode -ErrorAction SilentlyContinue) {
            $agent = 'opencode'
        } else {
            Write-ConsoleLine -Message "AI review requires 'claude', 'kimi', or 'opencode' on PATH." -ForegroundColor Red
            Start-Sleep -Milliseconds 500
            return $false
        }
    }

    if ($Batch) {
        return Invoke-AiReviewBatch -ParsedFile $ParsedFile -Agent $agent
    } else {
        return Invoke-AiReviewSingle -ParsedFile $ParsedFile -Issue $Issue -Agent $agent
    }
}

function Invoke-AiReviewSingle {
    param([PSObject]$ParsedFile, [PSObject]$Issue, [string]$Agent)

    Write-ConsoleLine -Message "AI reviewing issue $($Issue.Number)..." -ForegroundColor Cyan

    # Build the review prompt
    $sectionsText = ''
    foreach ($sec in $Issue.Sections) {
        $sectionsText += "`n### $($sec.Label)`n`n$($sec.Body)`n"
    }

    # Include sibling context for duplicate detection
    $siblingText = ''
    $siblings = $ParsedFile.Issues | Where-Object { $_.Number -ne $Issue.Number }
    if ($siblings) {
        $siblingText = "`n### Sibling Issues (in same file)`n`n"
        foreach ($sib in $siblings) {
            $sibDec = if ($sib.Decision) { " → $($sib.Decision)" } else { '' }
            $siblingText += "- Issue $($sib.Number): $($sib.Title)$sibDec`n"
        }
    }

    $prompt = @"
You are reviewing an issue found during browser4-cli usability evaluation.

## Issue to Review

**Title:** $($Issue.Title)
**Severity:** $($Issue.Severity)
**Category:** $($Issue.Category)

$sectionsText

$siblingText

## Instructions

Analyze this issue and choose ONE review decision:
1. ACCEPT — issue is valid and the suggested fix is correct
2. ACCEPT with improvements — issue is valid but the fix needs refinement
3. DEFER — issue is acknowledged but intentionally deferred
4. WONTFIX — issue is acknowledged but will not be fixed
5. REJECT — issue is invalid, not a problem, or already addressed
6. DUPLICATE — duplicates another issue (reference which one)

Respond with EXACTLY this format (no code fences, no extra text):

DECISION: <exact decision name>
NOTES: <brief rationale — 1-3 sentences explaining why you chose this decision>
"@

    $result = Invoke-AgentPrompt -Prompt $prompt -Agent $Agent -Label "AI Review Issue $($Issue.Number)"
    return Apply-AiResult -ParsedFile $ParsedFile -IssueNumber $Issue.Number -ResultText $result
}

function Invoke-AiReviewBatch {
    param([PSObject]$ParsedFile, [string]$Agent)

    Write-ConsoleLine -Message "AI reviewing all $($ParsedFile.Issues.Count) issues..." -ForegroundColor Cyan

    $issuesText = ''
    foreach ($issue in $ParsedFile.Issues) {
        $issuesText += "`n---`n"
        $issuesText += "`n**Issue $($issue.Number):** $($issue.Title)`n"
        $issuesText += "**Severity:** $($issue.Severity) | **Category:** $($issue.Category)`n"
        foreach ($sec in $issue.Sections) {
            # Truncate long sections for batch review
            $body = $sec.Body
            if ($body.Length -gt 500) { $body = $body.Substring(0, 500) + '...' }
            $issuesText += "`n### $($sec.Label)`n$body`n"
        }
    }

    $prompt = @"
You are reviewing issues found during browser4-cli usability evaluation.
Review ALL of the following issues together. Consider cross-issue patterns:
identify duplicates, prioritize severity, and maintain consistency.

$issuesText

## Instructions

For each issue, choose ONE review decision from:
ACCEPT | ACCEPT with improvements | DEFER | WONTFIX | REJECT | DUPLICATE

Respond with EXACTLY this format for each issue (no code fences):

ISSUE 1:
DECISION: <decision>
NOTES: <rationale — 1-2 sentences>

ISSUE 2:
DECISION: <decision>
NOTES: <rationale — 1-2 sentences>
"@

    $result = Invoke-AgentPrompt -Prompt $prompt -Agent $Agent -Label 'AI Review All'
    return Apply-AiBatchResult -ParsedFile $ParsedFile -ResultText $result
}

function Invoke-AgentPrompt {
    param([string]$Prompt, [string]$Agent, [string]$Label)

    try {
        $tempFile = [System.IO.Path]::GetTempFileName()
        $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
        [System.IO.File]::WriteAllText($tempFile, $Prompt, $utf8NoBom)

        $stdOutPath = [System.IO.Path]::GetTempFileName()
        $stdErrPath = [System.IO.Path]::GetTempFileName()

        try {
            $procInfo = New-Object System.Diagnostics.ProcessStartInfo
            $procInfo.FileName = $Agent
            $procInfo.RedirectStandardInput = $true
            $procInfo.RedirectStandardOutput = $true
            $procInfo.RedirectStandardError = $true
            $procInfo.UseShellExecute = $false
            $procInfo.CreateNoWindow = $true
            $procInfo.StandardOutputEncoding = $utf8NoBom
            $procInfo.StandardErrorEncoding = $utf8NoBom

            switch ($Agent) {
                'claude' {
                    $procInfo.ArgumentList.Add('--dangerously-skip-permissions')
                    $procInfo.ArgumentList.Add('-p')
                    # Prompt is piped via stdin below to avoid command-line limits
                }
                'kimi' {
                    $procInfo.ArgumentList.Add('-p')
                    # Prompt via stdin
                }
                'opencode' {
                    $procInfo.ArgumentList.Add('run')
                    # Prompt via stdin
                }
            }

            $proc = New-Object System.Diagnostics.Process
            $proc.StartInfo = $procInfo
            $proc.Start() | Out-Null

            # Feed prompt via stdin
            $proc.StandardInput.Write($Prompt)
            $proc.StandardInput.Close()

            $timeoutMs = 120000
            if (-not $proc.WaitForExit($timeoutMs)) {
                $proc.Kill()
                Write-ConsoleLine -Message "AI review timed out." -ForegroundColor Yellow
                Start-Sleep -Milliseconds 500
                return ''
            }

            $stdout = $proc.StandardOutput.ReadToEnd()
            return $stdout
        } finally {
            Remove-Item $tempFile -ErrorAction SilentlyContinue
            Remove-Item $stdOutPath -ErrorAction SilentlyContinue
            Remove-Item $stdErrPath -ErrorAction SilentlyContinue
        }
    } catch {
        Write-ConsoleLine -Message "AI review failed: $_" -ForegroundColor Red
        Start-Sleep -Milliseconds 500
        return ''
    }
}

function Apply-AiResult {
    param([PSObject]$ParsedFile, [int]$IssueNumber, [string]$ResultText)

    if (-not $ResultText) { return $false }

    $decision = ''
    $notes = ''

    if ($ResultText -match 'DECISION:\s*(.+?)(?:\r?\n|$)') {
        $rawDec = $Matches[1].Trim()
        $decision = Normalize-Decision -Raw $rawDec
    }
    if ($ResultText -match 'NOTES:\s*((?s:.+?))(?:\r?\n\r?\n|\r?\nISSUE|\Z)') {
        $notes = $Matches[1].Trim()
    }

    if (-not $decision) {
        Write-ConsoleLine -Message "AI did not return a valid decision." -ForegroundColor Yellow
        Start-Sleep -Milliseconds 500
        return $false
    }

    $issue = $ParsedFile.Issues | Where-Object { $_.Number -eq $IssueNumber } | Select-Object -First 1
    if (-not $issue) { return $false }

    $issue.Decision = $decision
    if ($notes) {
        $aiTag = "[AI suggested: $decision]"
        if ($issue.Notes -and $issue.Notes.IndexOf('[AI suggested:') -ge 0) {
            $issue.Notes = $issue.Notes -replace '\[AI suggested:.*?\]\s*', "$aiTag "
        } elseif ($issue.Notes) {
            $issue.Notes = "$aiTag $($issue.Notes)"
        } else {
            $issue.Notes = "$aiTag $notes"
        }
    }

    Write-ConsoleLine -Message "AI → Issue $IssueNumber : $decision" -ForegroundColor Cyan
    Start-Sleep -Milliseconds 500
    return $true
}

function Apply-AiBatchResult {
    param([PSObject]$ParsedFile, [string]$ResultText)

    if (-not $ResultText) { return $false }

    # Parse ISSUE N: blocks
    $changed = $false
    $issueMatches = [regex]::Matches($ResultText, '(?s)ISSUE\s+(\d+):.*?DECISION:\s*(.+?)(?:\r?\n|$).*?NOTES:\s*((?s:.+?))(?=\r?\nISSUE|\r?\n\r?\n|\Z)')

    foreach ($match in $issueMatches) {
        $num = [int]$match.Groups[1].Value
        $rawDec = $match.Groups[2].Value.Trim()
        $notes = $match.Groups[3].Value.Trim()
        $decision = Normalize-Decision -Raw $rawDec

        if (-not $decision) { continue }

        $issue = $ParsedFile.Issues | Where-Object { $_.Number -eq $num } | Select-Object -First 1
        if (-not $issue) { continue }

        $issue.Decision = $decision
        if ($notes) {
            $aiTag = "[AI suggested: $decision]"
            if ($issue.Notes -and $issue.Notes.IndexOf('[AI suggested:') -ge 0) {
                $issue.Notes = $issue.Notes -replace '\[AI suggested:.*?\]\s*', "$aiTag "
            } elseif ($issue.Notes) {
                $issue.Notes = "$aiTag $($issue.Notes)"
            } else {
                $issue.Notes = "$aiTag $notes"
            }
        }
        $changed = $true
    }

    if (-not $changed) {
        # Try alternate format: per-line DECISION/NOTES
        $lines = $ResultText -split "`n"
        $currentIssueNum = 0
        foreach ($line in $lines) {
            if ($line -match 'ISSUE\s+(\d+)') {
                $currentIssueNum = [int]$Matches[1]
                continue
            }
            if ($currentIssueNum -gt 0 -and $line -match 'DECISION:\s*(.+)') {
                $decision = Normalize-Decision -Raw $Matches[1].Trim()
                if ($decision) {
                    $issue = $ParsedFile.Issues | Where-Object { $_.Number -eq $currentIssueNum } | Select-Object -First 1
                    if ($issue) {
                        $issue.Decision = $decision
                        $changed = $true
                    }
                }
            }
        }
    }

    if ($changed) {
        $count = @($ParsedFile.Issues | Where-Object { $_.Decision }).Count
        Write-ConsoleLine -Message "AI reviewed: $count/$($ParsedFile.Issues.Count) issues now have decisions." -ForegroundColor Cyan
        Start-Sleep -Milliseconds 500
    } else {
        Write-ConsoleLine -Message "AI did not return parseable decisions." -ForegroundColor Yellow
        Start-Sleep -Milliseconds 500
    }
    return $changed
}

function Normalize-Decision {
    param([string]$Raw)
    $trimmed = $Raw.Trim()
    foreach ($dec in $script:ReviewDecisions) {
        if ($trimmed -eq $dec) { return $dec }
    }
    # Fuzzy match
    $lower = $trimmed.ToLowerInvariant()
    if ($lower.Contains('accept with') -or $lower.Contains('improve')) { return 'ACCEPT with improvements' }
    if ($lower.Contains('accept')) { return 'ACCEPT' }
    if ($lower.Contains('defer')) { return 'DEFER' }
    if ($lower.Contains('wontfix') -or $lower.Contains("won't fix")) { return 'WONTFIX' }
    if ($lower.Contains('reject')) { return 'REJECT' }
    if ($lower.Contains('duplicate')) { return 'DUPLICATE' }
    return ''
}

# ── File picker ────────────────────────────────────────────────────────────────

function Show-FilePicker {
    <#
    .SYNOPSIS
        Display an interactive file list and let the user choose one.
    .OUTPUTS
        Selected file path, or $null.
    #>
    param([string[]]$Files)

    if ($Files.Count -eq 0) {
        Write-ConsoleLine -Message "No .issues.md files found in draft/ or review/." -ForegroundColor Yellow
        Write-ConsoleLine -Message "Run a real-world scenario test to generate issues first." -ForegroundColor DarkGray
        return $null
    }

    Clear-ScreenSafe
    Write-Host ''
    Write-Host '══════ Review Queue ══════' -ForegroundColor Cyan
    Write-Host ''

    # Group files by directory type (draft vs review)
    $dirs = Get-IssuesDirectories
    $draftNormalized = (Resolve-Path -LiteralPath $dirs.Draft -ErrorAction SilentlyContinue).Path
    $reviewNormalized = (Resolve-Path -LiteralPath $dirs.Review -ErrorAction SilentlyContinue).Path

    for ($i = 0; $i -lt $Files.Count; $i++) {
        $f = $Files[$i]
        $dirLabel = ''
        $dirColor = 'DarkGray'
        $fileNormalized = (Resolve-Path -LiteralPath $f).Path
        if ($fileNormalized.StartsWith($draftNormalized, [StringComparison]::OrdinalIgnoreCase)) {
            $dirLabel = '[draft]'
            $dirColor = 'Gray'
        } elseif ($fileNormalized.StartsWith($reviewNormalized, [StringComparison]::OrdinalIgnoreCase)) {
            $dirLabel = '[review]'
            $dirColor = 'Yellow'
        }
        $name = Split-Path -Leaf $f
        # Quick parse for issue count
        $total = 0
        $reviewed = 0
        try {
            $quick = Get-Content -LiteralPath $f -TotalCount 30 -Encoding UTF8 -ErrorAction SilentlyContinue | Out-String
            if ($quick -match '## Issues Found \((\d+) issue') {
                $total = [int]$Matches[1]
            }
            # Count [x] checkboxes
            $reviewedMatches = [regex]::Matches($quick, '- \[x\] \*\*')
            $reviewed = $reviewedMatches.Count
        } catch { }

        $numStr = "$i".PadLeft(2)
        Write-Host "  [$numStr] " -NoNewline -ForegroundColor Cyan
        Write-Host $dirLabel -NoNewline -ForegroundColor $dirColor
        Write-Host " $name" -NoNewline -ForegroundColor White
        if ($total -gt 0) {
            Write-Host "  ($reviewed/$total reviewed)" -NoNewline -ForegroundColor DarkGray
        }
        Write-Host ''
    }

    Write-Host ''
    Write-Host '══════════════════════════' -ForegroundColor DarkGray
    Write-Host '  Enter number (0-$($Files.Count - 1)), or [q] to quit' -ForegroundColor DarkGray

    $choice = Read-Host 'File'
    if ($choice -eq 'q') { return $null }

    $idx = 0
    if ([int]::TryParse($choice, [ref]$idx) -and $idx -ge 0 -and $idx -lt $Files.Count) {
        return $Files[$idx]
    }

    Write-ConsoleLine -Message "Invalid selection." -ForegroundColor Red
    return $null
}

# ── Entry point ────────────────────────────────────────────────────────────────

function Invoke-Review {
    <#
    .SYNOPSIS
        Interactive review of .issues.md files from the terminal.
    .DESCRIPTION
        Lists .issues.md files and lets the user select one, or opens a
        specific file when -Path or -Name is provided.  Enters an interactive
        session for setting review decisions, adding notes, and finalizing.
    .PARAMETER Path
        Specific .issues.md file to review.
    .PARAMETER Name
        Partial name to search for among .issues.md files.
    .PARAMETER List
        Show available files and exit.
    .PARAMETER All
        Include review/done/ files in the listing.
    #>
    param(
        [string]$Path = '',
        [string]$Name = '',
        [switch]$List,
        [switch]$All
    )

    $allFiles = Find-IssuesFiles -IncludeDone:$All

    # ── List mode ────────────────────────────────────────────────────────────
    if ($List) {
        if ($allFiles.Count -eq 0) {
            Write-ConsoleLine -Message "No .issues.md files found." -ForegroundColor Yellow
            return
        }
        Write-ConsoleLine -Message "Found $($allFiles.Count) .issues.md file(s):" -ForegroundColor Cyan
        $dirs = Get-IssuesDirectories
        $draftNormalized = (Resolve-Path -LiteralPath $dirs.Draft -ErrorAction SilentlyContinue).Path
        $reviewNormalized = (Resolve-Path -LiteralPath $dirs.Review -ErrorAction SilentlyContinue).Path
        foreach ($f in $allFiles) {
            $fileNormalized = (Resolve-Path -LiteralPath $f).Path
            $dirLabel = if ($fileNormalized.StartsWith($draftNormalized, [StringComparison]::OrdinalIgnoreCase)) {
                '[draft] '
            } elseif ($fileNormalized.StartsWith($reviewNormalized, [StringComparison]::OrdinalIgnoreCase)) {
                '[review]'
            } else {
                '        '
            }
            Write-ConsoleLine -Message "  $dirLabel $f" -ForegroundColor DarkGray
        }
        return
    }

    # ── Resolve file ─────────────────────────────────────────────────────────
    $filePath = ''
    if ($Path) {
        $filePath = Resolve-IssuesFile -Path $Path
    } elseif ($Name) {
        $filePath = Resolve-IssuesFile -Name $Name
    }

    if (-not $filePath) {
        if ($allFiles.Count -gt 0) {
            $filePath = Show-FilePicker -Files $allFiles
        } else {
            Write-ConsoleLine -Message "No .issues.md files found in draft/ or review/." -ForegroundColor Yellow
            return
        }
    }

    if (-not $filePath) {
        return
    }

    # ── Parse and start review ───────────────────────────────────────────────
    try {
        $parsed = Read-IssuesFile -FilePath $filePath
    } catch {
        Write-ConsoleLine -Message "Error reading file: $_" -ForegroundColor Red
        return
    }

    if ($parsed.Issues.Count -eq 0) {
        Write-ConsoleLine -Message "This file has no parsed issues." -ForegroundColor Yellow
        Write-ConsoleLine -Message "Use [d] in the review session to discard it." -ForegroundColor DarkGray
    }

    # ── Interactive loop (supports file-to-file navigation) ──────────────────
    $currentParsed = $parsed
    while ($true) {
        $result = Start-ReviewSession -ParsedFile $currentParsed -AllFiles $allFiles

        if ($result -eq 'quit' -or $result -eq 'done' -or $result -eq 'discard') {
            break
        }

        # Find next/prev file
        $normalizedPath = (Resolve-Path -LiteralPath $currentParsed.FilePath).Path
        $currentIdx = -1
        for ($i = 0; $i -lt $allFiles.Count; $i++) {
            if ((Resolve-Path -LiteralPath $allFiles[$i]).Path -eq $normalizedPath) {
                $currentIdx = $i
                break
            }
        }

        $nextIdx = -1
        if ($result -eq 'next-file' -and $currentIdx -lt $allFiles.Count - 1) {
            $nextIdx = $currentIdx + 1
        } elseif ($result -eq 'prev-file' -and $currentIdx -gt 0) {
            $nextIdx = $currentIdx - 1
        }

        if ($nextIdx -lt 0) { break }

        try {
            $currentParsed = Read-IssuesFile -FilePath $allFiles[$nextIdx]
        } catch {
            Write-ConsoleLine -Message "Error reading next file: $_" -ForegroundColor Red
            break
        }
    }

    Write-Host ''
    Write-ConsoleLine -Message "Review session ended." -ForegroundColor Cyan
}
