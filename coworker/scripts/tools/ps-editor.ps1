#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Interactive file editor with list panel, line editing, and AI improvement.

.DESCRIPTION
    An interactive PowerShell-based file editor with three capabilities:

    1. LIST PANEL — browse files in a directory with numeric indices.
       Supports flat list view and recursive tree view.
    2. EDIT PANEL — view files (v <N>) or edit individual lines (e <N>) with
       line numbers. Edit one line at a time in edit mode.
    3. AI IMPROVE — improve the current file using Claude CLI based on a
       natural-language prompt (a <prompt>).

    ── List-mode commands ──
      ls [path]       List files (flat view). Optional path argument.
      dir [path]      Alias for ls.
      tree            Toggle between flat list and recursive tree view.
      cd <path>       Change the working directory.
      v <N>           View file at index N (print contents with line numbers).
      e <N>           Edit file at index N — enters edit mode.
      a <prompt>      AI-improve the last viewed/edited file using the prompt.
      q | quit | exit Quit the editor.

    ── Edit-mode commands ──
      :N              Edit line N (you will be prompted for the new content).
      :N,<text>       Replace line N with <text> in one step.
      :dN             Delete line N.
      :iN,<text>      Insert <text> after line N (0 = before first line).
      :s              Show current file content with line numbers.
      :w              Write (save) changes to disk.
      :q              Quit edit mode without saving, return to list.
      :a <prompt>     AI-improve the current buffer with a prompt.
      :diff           Show unsaved changes (original vs current).

.PARAMETER Path
    Starting directory path. Defaults to the current working directory.

.PARAMETER Recurse
    Start in tree view mode (recursive listing).

.EXAMPLE
    ./ps-editor.ps1
    ./ps-editor.ps1 -Path .\coworker\scripts
    ./ps-editor.ps1 -Path . -Recurse
#>

[CmdletBinding()]
param(
    [string]$Path = '.',

    [switch]$Recurse
)

$ErrorActionPreference = 'Stop'

# ── Resolve starting directory ──────────────────────────────────────────────
try {
    $resolved = Resolve-Path -Path $Path -ErrorAction Stop
    if (Test-Path -Path $resolved -PathType Container) {
        $script:WorkDir = (Get-Item -Path $resolved).FullName
    }
    else {
        $script:WorkDir = Split-Path -Parent $resolved
    }
}
catch {
    $script:WorkDir = (Get-Location).Path
}

# ── State ────────────────────────────────────────────────────────────────────
$script:Files        = @()       # Array of {Index, FullPath, RelativePath, IsDir, Size, Modified}
$script:TreeMode     = $Recurse  # $false = flat list, $true = recursive tree
$script:CurrentFile  = $null     # FullPath of last viewed/edited file
$script:EditBuffer   = $null     # String array of current edit buffer
$script:EditOriginal = $null     # Original content for diff
$script:EditPath     = $null     # Path of file being edited
$script:Running      = $true
$script:PageSize     = 30        # Items per page in list/tree view (0 = no pagination)
$script:CurrentPage  = 0         # 0-indexed current page

# ── ANSI helpers ─────────────────────────────────────────────────────────────
function Write-Color {
    param([string]$Text, [string]$Color = 'White')
    Write-Host $Text -ForegroundColor $Color -NoNewline
}

function Write-Line {
    param([string]$Text = '', [string]$Color = 'White')
    if ($Text) { Write-Host $Text -ForegroundColor $Color } else { Write-Host '' }
}

# ── File listing ─────────────────────────────────────────────────────────────
function Update-FileList {
    param([string]$Directory)

    if (-not (Test-Path -Path $Directory -PathType Container)) {
        Write-Line "Directory not found: $Directory" 'Red'
        return
    }

    $script:WorkDir = (Get-Item -Path $Directory).FullName
    $script:Files = @()
    $script:CurrentFile = $null

    if ($script:TreeMode) {
        Build-TreeView -Path $script:WorkDir -Indent ''
    }
    else {
        Build-FlatView -Path $script:WorkDir
    }
}

function Build-FlatView {
    param([string]$Path)

    $items = @(Get-ChildItem -Path $Path -Force -ErrorAction SilentlyContinue |
        Sort-Object { -not $_.PSIsContainer }, { $_.Name })

    $idx = 0
    foreach ($item in $items) {
        $script:Files += [PSCustomObject]@{
            Index        = $idx
            Name         = $item.Name
            FullPath     = $item.FullName
            RelativePath = $item.Name
            IsDir        = $item.PSIsContainer
            Size         = if ($item.PSIsContainer) { 0 } else { $item.Length }
            Modified     = $item.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')
            Depth        = 0
        }
        $idx++
    }
}

function Build-TreeView {
    param([string]$Path, [string]$Indent)

    $items = @(Get-ChildItem -Path $Path -Force -ErrorAction SilentlyContinue |
        Sort-Object { -not $_.PSIsContainer }, { $_.Name })

    $count = $items.Count
    for ($i = 0; $i -lt $count; $i++) {
        $item = $items[$i]
        $isLast = ($i -eq $count - 1)
        $branch  = if ($isLast) { "$([char]0x2514)$([char]0x2500)$([char]0x2500) " } else { "$([char]0x251C)$([char]0x2500)$([char]0x2500) " }
        $display = $Indent + $branch + $item.Name
        if ($item.PSIsContainer) { $display += '/' }

        $idx = $script:Files.Count
        $script:Files += [PSCustomObject]@{
            Index        = $idx
            Name         = $display
            FullPath     = $item.FullName
            RelativePath = $display
            IsDir        = $item.PSIsContainer
            Size         = if ($item.PSIsContainer) { 0 } else { $item.Length }
            Modified     = $item.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')
            Depth        = ($Indent.Length / 4)
        }

        if ($item.PSIsContainer) {
            $nextIndent = $Indent + $(if ($isLast) { '    ' } else { "$([char]0x2502)   " })
            Build-TreeView -Path $item.FullName -Indent $nextIndent
        }
    }
}

function Show-FileList {
    param([string]$Header = $script:WorkDir)

    Write-Line ''
    Write-Line ('─' * 72) 'DarkGray'
    Write-Line "  $Header" 'Cyan'
    if ($script:TreeMode) {
        Write-Line '  [tree view]  (type "tree" for flat list)' 'DarkGray'
    }
    else {
        Write-Line '  [list view]  (type "tree" for tree view)' 'DarkGray'
    }
    Write-Line ('─' * 72) 'DarkGray'

    if ($script:Files.Count -eq 0) {
        Write-Line '  (empty directory)' 'DarkGray'
        Write-Line ''
        return
    }

    # ── Pagination ──────────────────────────────────────────────────────────
    $totalItems = $script:Files.Count
    $pageSize   = $script:PageSize
    if ($pageSize -le 0) { $pageSize = $totalItems }  # 0 = show all
    $totalPages = [Math]::Max(1, [Math]::Ceiling($totalItems / $pageSize))

    # Clamp current page if item count changed (e.g. after cd)
    if ($script:CurrentPage -ge $totalPages) {
        $script:CurrentPage = $totalPages - 1
    }
    if ($script:CurrentPage -lt 0) {
        $script:CurrentPage = 0
    }

    $startIdx = $script:CurrentPage * $pageSize
    $endIdx   = [Math]::Min($startIdx + $pageSize, $totalItems) - 1

    for ($i = $startIdx; $i -le $endIdx; $i++) {
        $f       = $script:Files[$i]
        $idxStr  = '[{0,3}]' -f $f.Index
        $sizeStr = if ($f.IsDir) { '<DIR>' } else { Format-FileSize -Bytes $f.Size }
        $modStr  = $f.Modified

        Write-Color $idxStr 'Yellow'
        Write-Host '  ' -NoNewline
        if ($f.IsDir) {
            Write-Color $f.RelativePath 'Blue'
        }
        else {
            Write-Host $f.RelativePath -NoNewline
        }
        Write-Host ('  {0,8}  {1}' -f $sizeStr, $modStr) -ForegroundColor DarkGray
    }

    Write-Line ('─' * 72) 'DarkGray'

    # ── Page footer ─────────────────────────────────────────────────────────
    if ($totalPages -gt 1) {
        $pageLabel = "Page $($script:CurrentPage + 1)/$totalPages"
        $rangeLabel = "items $($startIdx + 1)-$($endIdx + 1) of $totalItems"
        Write-Color "  $pageLabel — $rangeLabel" 'DarkGray'
        Write-Line '  n=next  p=prev  pagesize <N>' 'DarkGray'
    }
    else {
        Write-Line "  $totalItems item(s)" 'DarkGray'
    }
    Write-Line ''
}

function Format-FileSize {
    param([long]$Bytes)
    if ($Bytes -ge 1MB) { return '{0,5:F1} MB' -f ($Bytes / 1MB) }
    if ($Bytes -ge 1KB) { return '{0,5:F1} KB' -f ($Bytes / 1KB) }
    return '{0,5} B' -f $Bytes
}

# ── File resolution ──────────────────────────────────────────────────────────
function Resolve-FileByIndex {
    param([int]$Index)

    if ($Index -lt 0 -or $Index -ge $script:Files.Count) {
        Write-Line "Invalid index: $Index (valid range: 0-$($script:Files.Count - 1))" 'Red'
        return $null
    }

    $entry = $script:Files[$Index]
    if ($entry.IsDir) {
        Write-Line "'$($entry.Name)' is a directory. Use cd to navigate into it." 'Yellow'
        return $null
    }

    return $entry.FullPath
}

# ── View file ────────────────────────────────────────────────────────────────
function Show-FileContent {
    param([string]$FilePath)

    if (-not (Test-Path -Path $FilePath -PathType Leaf)) {
        Write-Line "File not found: $FilePath" 'Red'
        return
    }

    $script:CurrentFile = $FilePath
    $lines = @(Get-Content -Path $FilePath -Encoding UTF8 -ErrorAction Stop)
    $maxLines = [Math]::Min($lines.Count, 500)

    Write-Line ''
    Write-Line ('─' * 72) 'DarkGray'
    Write-Line "  $FilePath" 'Cyan'
    Write-Line "  $($lines.Count) line(s)" 'DarkGray'
    Write-Line ('─' * 72) 'DarkGray'

    $digits = [Math]::Max(4, ([string]$maxLines).Length)
    for ($i = 0; $i -lt $maxLines; $i++) {
        $lineNum = "{0,$digits}" -f ($i + 1)
        Write-Color "$lineNum │ " 'DarkGray'
        Write-Host $lines[$i]
    }

    if ($lines.Count -gt 500) {
        Write-Line "... ($($lines.Count - 500) more lines, showing first 500)" 'DarkGray'
    }

    Write-Line ('─' * 72) 'DarkGray'
    Write-Line ''
}

# ── Edit mode ────────────────────────────────────────────────────────────────
function Enter-EditMode {
    param([string]$FilePath)

    if (-not (Test-Path -Path $FilePath -PathType Leaf)) {
        Write-Line "File not found: $FilePath" 'Red'
        return
    }

    $script:CurrentFile = $FilePath
    $script:EditPath     = $FilePath
    $script:EditOriginal = @(Get-Content -Path $FilePath -Encoding UTF8 -ErrorAction Stop)
    $script:EditBuffer   = [System.Collections.ArrayList]::new()
    foreach ($line in $script:EditOriginal) {
        [void]$script:EditBuffer.Add($line)
    }

    Write-Line ''
    Write-Line "Editing: $FilePath" 'Cyan'
    Write-Line "  $($script:EditBuffer.Count) line(s) loaded" 'DarkGray'
    Write-Line '  Commands: :N | :N,<text> | :dN | :iN,<text> | :s | :w | :q | :diff | :a <prompt>' 'DarkGray'
    Write-Line ''

    Show-EditBuffer

    while ($script:Running) {
        $input = Read-HostPrompt 'edit'

        if ($null -eq $input) {
            # Ctrl+C or EOF
            Write-Line ''
            Write-Line 'Edit mode cancelled. Buffer discarded.' 'Yellow'
            $script:EditBuffer = $null
            $script:EditOriginal = $null
            $script:EditPath = $null
            return
        }

        $input = $input.Trim()

        if ([string]::IsNullOrWhiteSpace($input)) { continue }

        switch -Regex ($input) {
            # ── Quit edit mode ──
            '^:q(uit)?$' {
                if (Has-UnsavedChanges) {
                    Write-Line 'You have unsaved changes. Use :q! to discard, or :w to save first.' 'Yellow'
                    continue
                }
                Write-Line 'Edit mode ended (no changes).' 'DarkGray'
                break
            }
            '^:q!$' {
                Write-Line 'Edit mode ended. Changes discarded.' 'Yellow'
                break
            }

            # ── Save ──
            '^:w(rite)?$' {
                Save-EditBuffer
                Write-Line 'File saved.' 'Green'
                # Stay in edit mode after save
                continue
            }
            '^:wq$' {
                Save-EditBuffer
                Write-Line 'File saved.' 'Green'
                break
            }

            # ── Show buffer ──
            '^:s(how)?$' {
                Show-EditBuffer
                continue
            }

            # ── Diff ──
            '^:diff$' {
                Show-EditDiff
                continue
            }

            # ── Edit line: :N ──
            '^:(\d+)$' {
                $lineNum = [int]$Matches[1]
                Edit-SingleLine -LineNumber $lineNum
                Show-EditBuffer
                continue
            }

            # ── Edit line inline: :N,<text> ──
            '^:(\d+),(.+)$' {
                $lineNum = [int]$Matches[1]
                $newText = $Matches[2]
                Replace-Line -LineNumber $lineNum -NewText $newText
                Show-EditBuffer
                continue
            }

            # ── Delete line: :dN ──
            '^:d(\d+)$' {
                $lineNum = [int]$Matches[1]
                Delete-Line -LineNumber $lineNum
                Show-EditBuffer
                continue
            }

            # ── Insert line: :iN,<text> ──
            '^:i(\d+),(.+)$' {
                $lineNum = [int]$Matches[1]
                $newText = $Matches[2]
                Insert-Line -AfterLine $lineNum -Text $newText
                Show-EditBuffer
                continue
            }

            # ── AI improve ──
            '^:a\s+(.+)$' {
                $prompt = $Matches[1]
                Invoke-AiImprove -Prompt $prompt
                continue
            }

            default {
                Write-Line "Unknown edit command: $input" 'Red'
                Write-Line '  :N | :N,text | :dN | :iN,text | :s | :w | :q | :diff | :a <prompt>' 'DarkGray'
                continue
            }
        }

        # If we get here from a break, exit the edit loop
        break
    }

    $script:EditBuffer   = $null
    $script:EditOriginal = $null
    $script:EditPath     = $null
}

function Show-EditBuffer {
    Write-Line ''
    Write-Line ('─' * 72) 'DarkGray'
    $digits = [Math]::Max(4, ([string]$script:EditBuffer.Count).Length)
    for ($i = 0; $i -lt $script:EditBuffer.Count; $i++) {
        $lineNum = "{0,$digits}" -f ($i + 1)
        $marker  = if ($script:EditOriginal -and $i -lt $script:EditOriginal.Count -and
                       $script:EditBuffer[$i] -ne $script:EditOriginal[$i]) { ' *' } else { '  ' }
        Write-Color "$lineNum │ " 'DarkGray'
        Write-Color $marker 'Yellow'
        Write-Host $script:EditBuffer[$i]
    }
    Write-Line ('─' * 72) 'DarkGray'
    Write-Line "  $($script:EditBuffer.Count) line(s)" 'DarkGray'

    if ($script:EditOriginal -and $script:EditBuffer.Count -ne $script:EditOriginal.Count) {
        $delta = $script:EditBuffer.Count - $script:EditOriginal.Count
        $sign  = if ($delta -gt 0) { '+' } else { '' }
        Write-Line "  $sign$delta line(s) vs original ($($script:EditOriginal.Count))" 'Yellow'
    }
    Write-Line ''
}

function Show-EditDiff {
    if (-not $script:EditOriginal) {
        Write-Line 'No original to diff against.' 'Yellow'
        return
    }

    Write-Line ''
    Write-Line ('─' * 72) 'DarkGray'
    Write-Line '  Changes ( * = modified, + = added, - = removed)' 'Yellow'
    Write-Line ('─' * 72) 'DarkGray'

    $origCount = $script:EditOriginal.Count
    $bufCount  = $script:EditBuffer.Count
    $maxLines  = [Math]::Max($origCount, $bufCount)
    $digits    = [Math]::Max(4, ([string]$maxLines).Length)

    for ($i = 0; $i -lt $maxLines; $i++) {
        $origLine = if ($i -lt $origCount) { $script:EditOriginal[$i] } else { $null }
        $bufLine  = if ($i -lt $bufCount) { $script:EditBuffer[$i] } else { $null }

        if ($null -eq $bufLine -and $null -ne $origLine) {
            # Removed
            $ln = "{0,$digits}" -f ($i + 1)
            Write-Color "$ln - " 'Red'
            Write-Host $origLine -ForegroundColor Red
        }
        elseif ($null -ne $bufLine -and $null -eq $origLine) {
            # Added
            $ln = "{0,$digits}" -f ($i + 1)
            Write-Color "$ln + " 'Green'
            Write-Host $bufLine -ForegroundColor Green
        }
        elseif ($bufLine -ne $origLine) {
            # Modified
            $ln = "{0,$digits}" -f ($i + 1), $digits
            Write-Color "$ln * " 'Yellow'
            Write-Host $bufLine -ForegroundColor Yellow
        }
    }
    Write-Line ('─' * 72) 'DarkGray'
    Write-Line ''
}

function Has-UnsavedChanges {
    if (-not $script:EditOriginal) { return $false }
    if ($script:EditOriginal.Count -ne $script:EditBuffer.Count) { return $true }
    for ($i = 0; $i -lt $script:EditOriginal.Count; $i++) {
        if ($script:EditOriginal[$i] -ne $script:EditBuffer[$i]) { return $true }
    }
    return $false
}

function Save-EditBuffer {
    $content = $script:EditBuffer -join [Environment]::NewLine
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($script:EditPath, $content, $utf8NoBom)
    $script:EditOriginal = @($script:EditBuffer | ForEach-Object { $_ })
}

function Edit-SingleLine {
    param([int]$LineNumber)

    if ($LineNumber -lt 1 -or $LineNumber -gt $script:EditBuffer.Count) {
        Write-Line "Line $LineNumber out of range (1-$($script:EditBuffer.Count))." 'Red'
        return
    }

    $currentContent = $script:EditBuffer[$LineNumber - 1]
    $digits = [Math]::Max(4, ([string]$script:EditBuffer.Count).Length)
    $lineLabel = "{0,$digits}" -f $LineNumber

    Write-Line ''
    Write-Color "$lineLabel » " 'DarkGray'
    Write-Line $currentContent 'DarkGray'
    Write-Line 'Enter new content (or leave empty to cancel):' 'DarkGray'

    $newContent = Read-HostPrompt 'new'
    if ($null -eq $newContent) {
        Write-Line 'Cancelled.' 'DarkGray'
        return
    }

    Replace-Line -LineNumber $LineNumber -NewText $newContent
}

function Replace-Line {
    param([int]$LineNumber, [string]$NewText)

    if ($LineNumber -lt 1 -or $LineNumber -gt $script:EditBuffer.Count) {
        Write-Line "Line $LineNumber out of range (1-$($script:EditBuffer.Count))." 'Red'
        return
    }

    $oldContent = $script:EditBuffer[$LineNumber - 1]
    $script:EditBuffer[$LineNumber - 1] = $NewText

    $digits = [Math]::Max(4, ([string]$script:EditBuffer.Count).Length)
    $lineLabel = "{0,$digits}" -f $LineNumber

    Write-Color "$lineLabel - " 'Red'
    Write-Line $oldContent 'Red'
    Write-Color "$lineLabel + " 'Green'
    Write-Line $NewText 'Green'
}

function Delete-Line {
    param([int]$LineNumber)

    if ($LineNumber -lt 1 -or $LineNumber -gt $script:EditBuffer.Count) {
        Write-Line "Line $LineNumber out of range (1-$($script:EditBuffer.Count))." 'Red'
        return
    }

    $deleted = $script:EditBuffer[$LineNumber - 1]
    $script:EditBuffer.RemoveAt($LineNumber - 1)

    $digits = [Math]::Max(4, ([string]($script:EditBuffer.Count + 1)).Length)
    $lineLabel = "{0,$digits}" -f $LineNumber
    Write-Color "$lineLabel - " 'Red'
    Write-Line $deleted 'Red'
    Write-Line "  Line $LineNumber deleted. $($script:EditBuffer.Count) line(s) remaining." 'DarkGray'
}

function Insert-Line {
    param([int]$AfterLine, [string]$Text)

    if ($AfterLine -lt 0 -or $AfterLine -gt $script:EditBuffer.Count) {
        Write-Line "Insert position $AfterLine out of range (0-$($script:EditBuffer.Count))." 'Red'
        return
    }

    $script:EditBuffer.Insert($AfterLine, $Text)

    $digits = [Math]::Max(4, ([string]$script:EditBuffer.Count).Length)
    $lineLabel = "{0,$digits}" -f ($AfterLine + 1)
    Write-Color "$lineLabel + " 'Green'
    Write-Line $Text 'Green'
    Write-Line "  Inserted after line $AfterLine. $($script:EditBuffer.Count) line(s) total." 'DarkGray'
}

# ── AI improve ───────────────────────────────────────────────────────────────
function Invoke-AiImprove {
    param([string]$Prompt)

    $targetFile = $script:EditPath
    if (-not $targetFile) {
        $targetFile = $script:CurrentFile
    }

    if (-not $targetFile) {
        Write-Line 'No file selected. View or edit a file first with v <N> or e <N>.' 'Red'
        return
    }

    if (-not (Test-Path -Path $targetFile -PathType Leaf)) {
        Write-Line "File not found: $targetFile" 'Red'
        return
    }

    Write-Line ''
    Write-Line "AI improve: $targetFile" 'Cyan'
    Write-Line "Prompt: $Prompt" 'Yellow'
    Write-Line ''

    # Check if claude CLI is available
    $claudeCmd = $null
    if (Get-Command 'claude' -ErrorAction SilentlyContinue) {
        $claudeCmd = 'claude'
    }
    elseif (Get-Command 'claude.exe' -ErrorAction SilentlyContinue) {
        $claudeCmd = 'claude.exe'
    }

    if (-not $claudeCmd) {
        Write-Line 'Claude CLI not found. To use AI improve:' 'Red'
        Write-Line '  1. Install Claude Code: npm install -g @anthropic-ai/claude-code' 'Red'
        Write-Line '  2. Or manually run: claude -p "improve <file>: $Prompt"' 'Red'
        Write-Line ''
        Write-Line "  Target file: $targetFile" 'DarkGray'
        Write-Line "  Your prompt: $Prompt" 'DarkGray'
        return
    }

    try {
        Write-Line 'Invoking Claude CLI...' 'DarkGray'

        $fullPrompt = "Improve the file according to this instruction: $Prompt. Write the complete improved file content. Only output the final file content, no explanations."
        $tempInFile = [System.IO.Path]::GetTempFileName()
        try {
            # Write the current file content to a temp file as reference
            Copy-Item -Path $targetFile -Destination $tempInFile -Force

            $result = & $claudeCmd -p $fullPrompt --print 2>&1
            $exitCode = $LASTEXITCODE

            if ($exitCode -ne 0 -or [string]::IsNullOrWhiteSpace($result)) {
                Write-Line "Claude CLI returned exit code $exitCode or empty output." 'Red'
                Write-Line "Result: $result" 'Red'
                return
            }

            # If we're in edit mode, replace the buffer
            if ($script:EditBuffer -and $script:EditPath -eq $targetFile) {
                Write-Line ''
                Write-Line 'AI suggestion received. Replace edit buffer? (y/n)' 'Yellow'
                $confirm = Read-HostPrompt 'apply'
                if ($confirm -eq 'y' -or $confirm -eq 'yes') {
                    $newLines = @($result -split '\r?\n')
                    $script:EditBuffer.Clear()
                    foreach ($line in $newLines) {
                        [void]$script:EditBuffer.Add($line)
                    }
                    Write-Line 'Buffer replaced with AI-improved content. Use :diff to review, :w to save.' 'Green'
                    Show-EditBuffer
                }
                else {
                    Write-Line 'AI suggestion discarded.' 'DarkGray'
                }
            }
            else {
                # Not in edit mode — show the suggestion
                Write-Line ''
                Write-Line ('─' * 72) 'DarkGray'
                Write-Line '  AI suggestion:' 'Cyan'
                Write-Line ('─' * 72) 'DarkGray'
                Write-Host $result
                Write-Line ('─' * 72) 'DarkGray'
                Write-Line ''
                Write-Line 'To apply, enter edit mode with e <N> and use :a <prompt> again.' 'DarkGray'
            }
        }
        finally {
            if (Test-Path $tempInFile) { Remove-Item $tempInFile -Force -ErrorAction SilentlyContinue }
        }
    }
    catch {
        Write-Line "Error invoking Claude CLI: $_" 'Red'
    }
}

# ── Shell helpers ────────────────────────────────────────────────────────────
function Read-HostPrompt {
    param([string]$Mode = 'cmd')

    Write-Host "$Mode> " -ForegroundColor Cyan -NoNewline
    $input = Read-Host
    return $input
}

# ── Command dispatch (list mode) ─────────────────────────────────────────────
function Invoke-ListCommand {
    param([string]$RawInput)

    $raw = $RawInput.Trim()

    if ([string]::IsNullOrWhiteSpace($raw)) { return }

    # ── Quit ──
    if ($raw -match '^(q|quit|exit)$') {
        Write-Line 'Goodbye.' 'Cyan'
        $script:Running = $false
        return
    }

    # ── Help ──
    if ($raw -match '^(h|help|\?)$') {
        Show-Help
        return
    }

    # ── Toggle tree view ──
    if ($raw -eq 'tree') {
        $script:TreeMode = -not $script:TreeMode
        $script:CurrentPage = 0
        $modeStr = if ($script:TreeMode) { 'tree' } else { 'list' }
        Write-Line "Switched to $modeStr view." 'Green'
        Update-FileList -Directory $script:WorkDir
        Show-FileList
        return
    }

    # ── List files ──
    if ($raw -match '^(ls|dir)(\s+(.+))?$') {
        $script:CurrentPage = 0
        $targetDir = if ($Matches[3]) { $Matches[3] } else { $script:WorkDir }
        try {
            $targetDir = Resolve-Path -Path $targetDir -ErrorAction Stop
            Update-FileList -Directory $targetDir
            Show-FileList
        }
        catch {
            Write-Line "Cannot resolve path: $($Matches[3])" 'Red'
        }
        return
    }

    # ── Change directory ──
    if ($raw -match '^cd\s+(.+)$') {
        $script:CurrentPage = 0
        $target = $Matches[1].Trim()
        try {
            $resolvedPath = Resolve-Path -Path $target -ErrorAction Stop
            $dirPath = if (Test-Path -Path $resolvedPath -PathType Container) {
                $resolvedPath
            }
            else {
                Split-Path -Parent $resolvedPath
            }
            Update-FileList -Directory $dirPath
            Show-FileList
        }
        catch {
            Write-Line "Cannot find path: $target" 'Red'
        }
        return
    }

    # ── View file ──
    if ($raw -match '^v\s+(\d+)$') {
        $idx = [int]$Matches[1]
        $filePath = Resolve-FileByIndex -Index $idx
        if ($filePath) {
            Show-FileContent -FilePath $filePath
        }
        return
    }

    # ── Edit file ──
    if ($raw -match '^e\s+(\d+)$') {
        $idx = [int]$Matches[1]
        $filePath = Resolve-FileByIndex -Index $idx
        if ($filePath) {
            Enter-EditMode -FilePath $filePath
            # Refresh list after edit completes
            Update-FileList -Directory $script:WorkDir
            Show-FileList
        }
        return
    }

    # ── AI improve: a <prompt> ──
    if ($raw -match '^a\s+(.+)$') {
        $prompt = $Matches[1].Trim()
        if (-not $script:CurrentFile) {
            Write-Line 'No file selected. View or edit a file first with v <N> or e <N>.' 'Red'
            return
        }
        Invoke-AiImprove -Prompt $prompt
        return
    }

    # ── Page navigation ──
    if ($raw -match '^(n|next)$') {
        $pageSize = $script:PageSize
        if ($pageSize -le 0) { $pageSize = $script:Files.Count }
        $totalPages = [Math]::Max(1, [Math]::Ceiling($script:Files.Count / $pageSize))
        if ($script:CurrentPage -lt $totalPages - 1) {
            $script:CurrentPage++
            Show-FileList
        }
        else {
            Write-Line "Already on the last page ($totalPages)." 'DarkGray'
        }
        return
    }
    if ($raw -match '^(p|prev)$') {
        if ($script:CurrentPage -gt 0) {
            $script:CurrentPage--
            Show-FileList
        }
        else {
            Write-Line 'Already on the first page.' 'DarkGray'
        }
        return
    }

    # ── Set page size ──
    if ($raw -match '^pagesize\s+(\d+)$') {
        $newSize = [int]$Matches[1]
        if ($newSize -lt 1) {
            Write-Line 'Page size must be >= 1 (use 0 for no pagination).' 'Red'
            return
        }
        $script:PageSize = $newSize
        $script:CurrentPage = 0
        Write-Line "Page size set to $newSize." 'Green'
        Show-FileList
        return
    }
    if ($raw -match '^pagesize$') {
        if ($script:PageSize -le 0) {
            Write-Line 'Pagination disabled (showing all items). Use pagesize <N> to enable.' 'DarkGray'
        }
        else {
            Write-Line "Page size: $($script:PageSize) items. Use pagesize <N> to change, 0 to disable." 'DarkGray'
        }
        return
    }

    # ── Unknown ──
    Write-Line "Unknown command: $raw" 'Red'
    Write-Line '  ls | tree | cd <path> | v <N> | e <N> | a <prompt> | n/p | pagesize | q' 'DarkGray'
}

# ── Help ─────────────────────────────────────────────────────────────────────
function Show-Help {
    Write-Line ''
    Write-Line ('─' * 72) 'DarkGray'
    Write-Line '  PS Editor — Commands' 'Cyan'
    Write-Line ('─' * 72) 'DarkGray'
    Write-Line ''
    Write-Line '  LIST MODE:' 'Yellow'
    Write-Line '    ls [path]     List files (flat view)' 'White'
    Write-Line '    dir [path]    Alias for ls' 'White'
    Write-Line '    tree          Toggle flat list / tree view' 'White'
    Write-Line '    cd <path>     Change working directory' 'White'
    Write-Line '    v <N>         View file at index N (with line numbers)' 'White'
    Write-Line '    e <N>         Edit file at index N (line-by-line)' 'White'
    Write-Line '    a <prompt>    AI-improve last viewed/edited file' 'White'
    Write-Line '    n | next      Next page (when paginated)' 'White'
    Write-Line '    p | prev      Previous page (when paginated)' 'White'
    Write-Line '    pagesize [N]  Show/set items per page (0 = no limit)' 'White'
    Write-Line '    q | quit      Exit' 'White'
    Write-Line '    h | help      Show this help' 'White'
    Write-Line ''
    Write-Line '  EDIT MODE (after e <N>):' 'Yellow'
    Write-Line '    :N            Edit line N (prompts for new content)' 'White'
    Write-Line '    :N,<text>     Replace line N with <text>' 'White'
    Write-Line '    :dN           Delete line N' 'White'
    Write-Line '    :iN,<text>    Insert <text> after line N (0 = before first)' 'White'
    Write-Line '    :s            Show buffer with line numbers' 'White'
    Write-Line '    :diff         Show unsaved changes' 'White'
    Write-Line '    :w            Save to disk (stays in edit mode)' 'White'
    Write-Line '    :wq           Save and quit edit mode' 'White'
    Write-Line '    :q            Quit edit mode (warns if unsaved)' 'White'
    Write-Line '    :q!           Force quit, discard changes' 'White'
    Write-Line '    :a <prompt>   AI-improve current buffer' 'White'
    Write-Line ''
    Write-Line '  EXAMPLES:' 'Yellow'
    Write-Line '    v 0                View the first file' 'DarkGray'
    Write-Line '    e 3                Edit file at index 3' 'DarkGray'
    Write-Line '    :12,fixed text     Replace line 12 with "fixed text"' 'DarkGray'
    Write-Line '    :d5                Delete line 5' 'DarkGray'
    Write-Line '    :i0,new header     Insert "new header" before line 1' 'DarkGray'
    Write-Line '    a fix typos        AI-improve: fix typos in current file' 'DarkGray'
    Write-Line ''
}

# ══════════════════════════════════════════════════════════════════════════════
# Main Loop
# ══════════════════════════════════════════════════════════════════════════════

Write-Line ''
Write-Line '╔══════════════════════════════════════════════════════════════════╗' 'Cyan'
Write-Line '║  PS Editor — interactive file browser & editor                  ║' 'Cyan'
Write-Line '║  Type "h" for help, "q" to quit                                ║' 'Cyan'
Write-Line '╚══════════════════════════════════════════════════════════════════╝' 'Cyan'

Update-FileList -Directory $script:WorkDir
Show-FileList

while ($script:Running) {
    try {
        $input = Read-HostPrompt 'cmd'

        if ($null -eq $input) {
            # Ctrl+C or EOF
            Write-Line ''
            Write-Line 'Goodbye.' 'Cyan'
            break
        }

        Invoke-ListCommand -RawInput $input
    }
    catch {
        Write-Line "Error: $_" 'Red'
        # Continue the loop
    }
}

exit 0
