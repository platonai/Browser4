#!/usr/bin/env pwsh
# ═══════════════════════════════════════════════════════════════════════════════
# review.tests.ps1 — Tests for the .issues.md format shared between
#   - coworker/gui/frontend/issue-model.js  (canonical JS schema)
#   - coworker/scripts/review.ps1            (PowerShell implementation)
#
# Run:  Invoke-Pester -Path .\coworker\scripts\tests\review.tests.ps1
#
# Tests verify that the PowerShell parser/writer produces output compatible
# with issue-model.js.  The same golden fixture is used in both test suites.
# ═══════════════════════════════════════════════════════════════════════════════

$ErrorActionPreference = 'Continue'

# ═══════════════════════════════════════════════════════════════════════════════
# All functions use global: scope so they're visible inside Pester 5 Describe
# blocks (which run with isolated scopes).
# ═══════════════════════════════════════════════════════════════════════════════

$global:ReviewDecisions = @(
    'ACCEPT',
    'ACCEPT with improvements',
    'DEFER',
    'WONTFIX',
    'REJECT',
    'DUPLICATE'
)

function global:Get-DecisionDescription {
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

function global:Read-IssuesFile {
    param([string]$FilePath)

    if (-not (Test-Path -LiteralPath $FilePath -PathType Leaf)) {
        throw "File not found: $FilePath"
    }
    $rawContent = Get-Content -LiteralPath $FilePath -Raw -Encoding UTF8
    if ([string]::IsNullOrWhiteSpace($rawContent)) { throw "File is empty: $FilePath" }
    if ($rawContent.Length -ge 3 -and $rawContent[0] -eq [char]0xFEFF) {
        $rawContent = $rawContent.Substring(1)
    }
    $normalized = $rawContent -replace "`r`n", "`n"

    # Meta
    $meta = @{ Scenario = ''; Source = ''; Date = ''; Mode = 'dev' }
    if ($normalized -match '^# Issues:\s*(\S[^\r\n]*)') { $meta.Scenario = $Matches[1].Trim() }
    if ($normalized -match '>\s*\*\*Source:\*\*\s*`([^`]+)`\s*\|\s*\*\*Date:\*\*\s*(\S+)\s*\|\s*\*\*Mode:\*\*\s*(\S[^\r\n]*)') {
        $meta.Source = $Matches[1]; $meta.Date = $Matches[2]; $meta.Mode = $Matches[3]
    }

    # Background
    $background = @{ Task = ''; ExecutionContext = '' }
    if ($normalized -match '(?s)### Task\n(.+?)(?=\n###\s|\n---\s*\n|$)') { $background.Task = $Matches[1].Trim() }
    if ($normalized -match '(?s)### Execution Context\n(.+?)(?=\n---\s*\n|\n##\s|$)') { $background.ExecutionContext = $Matches[1].Trim() }

    # Issues
    $issuesHeaderIdx = -1
    if ($normalized -match '## Issues Found') { $issuesHeaderIdx = $normalized.IndexOf('## Issues Found') }
    $issues = @()
    if ($issuesHeaderIdx -ge 0) {
        $issuesSectionStart = $normalized.IndexOf("`n", $issuesHeaderIdx) + 1
        $howToIdx = $normalized.IndexOf("`n## How to Reproduce", $issuesSectionStart)
        if ($howToIdx -lt 0) { $howToIdx = $normalized.Length }
        $issuesSection = $normalized.Substring($issuesSectionStart, [Math]::Max(0, $howToIdx - $issuesSectionStart))
        $issueBlocks = @($issuesSection -split '(?=###\s+Issue\s+\d+:)') |
            Where-Object { $_ -match '###\s+Issue\s+(\d+):\s*(.+)' }
        $issueNum = 0
        foreach ($block in $issueBlocks) {
            $issueNum++
            $issue = @{ Number = $issueNum; Title = ''; Severity = ''; Category = ''; Sections = @(); Decision = $null; Notes = '' }
            if ($block -match '###\s+Issue\s+\d+:\s*(.+?)(?:\n|$)') { $issue.Title = $Matches[1].Trim() }
            if ($block -match '\*\*Severity:\*\*\s*(.+?)(?:\n|$)') { $issue.Severity = $Matches[1].Trim() }
            if ($block -match '\*\*Category:\*\*\s*(.+?)(?:\n|$)') { $issue.Category = $Matches[1].Trim() }
            $hrIdx = $block.IndexOf("`n#### Human Review")
            if ($hrIdx -lt 0) { $hrIdx = $block.Length }
            $bodyBlock = $block.Substring(0, $hrIdx)
            $sectionChunks = @($bodyBlock -split '(?=####\s+)') | Where-Object { $_ -match '####\s+(.+)' }
            foreach ($chunk in $sectionChunks) {
                if ($chunk -match '####\s+(.+?)(?:\n|$)((?s:.*))') {
                    $label = $Matches[1].Trim(); $body = $Matches[2].Trim()
                    if ($label -and $body) { $issue.Sections += @{ Label = $label; Body = $body } }
                }
            }
            if ($hrIdx -ge 0) {
                $hrBlock = $block.Substring($hrIdx)
                foreach ($dec in $global:ReviewDecisions) {
                    $escaped = [regex]::Escape($dec)
                    if ($hrBlock -match "- \[x\] \*\*$escaped\*\*") { $issue.Decision = $dec; break }
                }
                if ($hrBlock -match "\*\*Notes:\*\*[^\S\n]*\n((?s:.*?))(?=\n---|\n###\s|\Z)") {
                    $notesRaw = $Matches[1].Trim()
                    if ($notesRaw) { $issue.Notes = $notesRaw }
                }
            }
            $issues += $issue
        }
    }
    return [PSCustomObject]@{ Meta = [PSCustomObject]$meta; Background = [PSCustomObject]$background; Issues = $issues; OriginalContent = $rawContent; FilePath = $FilePath }
}

function global:Write-IssuesFile {
    param([Parameter(Mandatory = $true)][PSObject]$ParsedFile)

    $content = $ParsedFile.OriginalContent
    $normalized = $content -replace "`r`n", "`n"
    foreach ($issue in $ParsedFile.Issues) {
        $issuePattern = "### Issue $($issue.Number):"
        $issueIdx = $normalized.IndexOf($issuePattern)
        if ($issueIdx -lt 0) { continue }
        $nextIdx = $normalized.IndexOf("`n### Issue ", $issueIdx + $issuePattern.Length)
        if ($nextIdx -lt 0) { $nextIdx = $normalized.IndexOf("`n## How to Reproduce", $issueIdx) }
        if ($nextIdx -lt 0) { $nextIdx = $normalized.Length }
        $issueBlock = $normalized.Substring($issueIdx, $nextIdx - $issueIdx)
        $hrMarker = "`n#### Human Review"
        $hrIdx = $issueBlock.IndexOf($hrMarker)
        if ($hrIdx -lt 0) { continue }
        $hrStart = $issueIdx + $hrIdx
        $hrContentStart = $hrStart + $hrMarker.Length
        $hrEnd = $normalized.IndexOf("`n---", $hrContentStart)
        if ($hrEnd -lt 0 -or $hrEnd -ge $nextIdx) { $hrEnd = $nextIdx }

        $newHR = "`n#### Human Review`n`n"
        foreach ($dec in $global:ReviewDecisions) {
            $checked = if ($issue.Decision -eq $dec) { '[x]' } else { '[ ]' }
            $desc = Get-DecisionDescription -Decision $dec
            $newHR += "- $checked **$dec**"
            if ($desc) { $newHR += " — $desc" }
            $newHR += "`n"
        }
        $newHR += "- **Notes:**"
        if ($issue.Notes -and $issue.Notes.Trim()) { $newHR += "`n$($issue.Notes.Trim())" }
        $newHR += "`n"
        $normalized = $normalized.Substring(0, $hrStart) + $newHR + $normalized.Substring($hrEnd)
    }
    $result = $normalized -replace "`n", "`r`n"
    $utf8 = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText([System.IO.Path]::GetFullPath($ParsedFile.FilePath), $result, $utf8)
    $ParsedFile.OriginalContent = $result
}

# ═══════════════════════════════════════════════════════════════════════════════
# Shared golden fixture — MUST match issue-model.test.js GOLDEN_FIXTURE exactly.
# ═══════════════════════════════════════════════════════════════════════════════

$global:GoldenFixture = @'
# Issues: golden-scenario

> **Source:** `20260725-120000-golden-scenario.full.md` | **Date:** 20260725-120000 | **Mode:** dev

## Scenario Background

### Task

The agent was asked to fill out a form on example.com.

### Execution Context

| Step | Command | Result |
|------|---------|--------|
| 1 | `goto https://example.com/form` | OK |
| 2 | `snapshot -i` | OK |
| 3 | `fill e3 "test@example.com"` | OK |

---

## Issues Found (3 issues)

### Issue 1: Snapshot preview too short

**Severity:** Medium
**Category:** UX

#### Reproduction

Run `snapshot -i` on a page with many form fields.

#### Expected Behavior

The preview shows all interactive elements.

#### Actual Behavior

Only 10 lines shown. Form fields below the fold are invisible.

#### Root Cause Analysis

The preview truncation limit is hard-coded to 10 lines.

#### Code Pointer

`cli/browser4-cli/src/snapshot.rs:render_preview()`

#### AI Suggested Improvement

- Increase preview limit to 30 lines
- Bias toward showing interactive elements first

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**


---

### Issue 2: fill command silently fails with special characters

**Severity:** High
**Category:** Reliability

#### Reproduction

```
fill e3 "user's <test> & check"
```

#### Expected Behavior

Characters are properly escaped or a clear error is shown.

#### Actual Behavior

The command succeeds but the field contains garbled text.

#### Root Cause Analysis

Shell escaping is not handled before sending characters to CDP.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:fill_command()`

#### AI Suggested Improvement

- Escape special characters before dispatch
- Add a `--raw` flag for literal input

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**


---

### Issue 3: No --help example for goto command

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Run `browser4-cli goto --help`.

#### Expected Behavior

Help output includes at least one usage example.

#### Actual Behavior

Only flag descriptions, no examples.

#### Root Cause Analysis

The CLI help generator does not include examples.

#### Code Pointer


#### AI Suggested Improvement

- Add usage examples to all --help outputs

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**


---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source.
3. The backend server starts automatically in dev mode.

### Per-Issue Reproduction Steps

#### Issue 1: Snapshot preview too short

Run `snapshot -i` on a page with many form fields.

#### Issue 2: fill command silently fails with special characters

```
fill e3 "user's <test> & check"
```

#### Issue 3: No --help example for goto command

Run `browser4-cli goto --help`.
'@

# ═══════════════════════════════════════════════════════════════════════════════
# Fixture helpers
# ═══════════════════════════════════════════════════════════════════════════════

$global:ReviewTestRoot = $null

function global:Initialize-Fixture {
    $global:ReviewTestRoot = Join-Path ([System.IO.Path]::GetTempPath()) "ReviewTests_$(Get-Random -Minimum 1000 -Maximum 9999)"
    New-Item -ItemType Directory -Path $global:ReviewTestRoot -Force | Out-Null
}

function global:Remove-Fixture {
    if ($global:ReviewTestRoot -and (Test-Path $global:ReviewTestRoot)) {
        Remove-Item -Path $global:ReviewTestRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function global:Write-FixtureFile {
    param([string]$FileName, [string]$Content)
    $path = Join-Path $global:ReviewTestRoot $FileName
    $utf8 = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($path, $Content, $utf8)
    return $path
}

# ═══════════════════════════════════════════════════════════════════════════════
# Read-IssuesFile tests
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Read-IssuesFile' {

    BeforeEach { Initialize-Fixture }
    AfterEach  { Remove-Fixture }

    It 'parses meta fields correctly' {
        $filePath = Write-FixtureFile -FileName 'golden.issues.md' -Content $global:GoldenFixture
        $f = Read-IssuesFile -FilePath $filePath

        $f.Meta.Scenario | Should -BeExactly 'golden-scenario'
        $f.Meta.Source   | Should -BeExactly '20260725-120000-golden-scenario.full.md'
        $f.Meta.Date     | Should -BeExactly '20260725-120000'
        $f.Meta.Mode     | Should -BeExactly 'dev'
    }

    It 'parses background sections' {
        $filePath = Write-FixtureFile -FileName 'golden.issues.md' -Content $global:GoldenFixture
        $f = Read-IssuesFile -FilePath $filePath

        $f.Background.Task | Should -Match 'fill out a form'
        $f.Background.ExecutionContext | Should -Match 'goto'
        $f.Background.ExecutionContext | Should -Match 'snapshot'
    }

    It 'parses all three issues' {
        $filePath = Write-FixtureFile -FileName 'golden.issues.md' -Content $global:GoldenFixture
        $f = Read-IssuesFile -FilePath $filePath

        $f.Issues.Count | Should -Be 3
    }

    It 'parses issue numbers and titles' {
        $filePath = Write-FixtureFile -FileName 'golden.issues.md' -Content $global:GoldenFixture
        $f = Read-IssuesFile -FilePath $filePath

        $f.Issues[0].Number | Should -Be 1
        $f.Issues[0].Title  | Should -BeExactly 'Snapshot preview too short'
        $f.Issues[1].Number | Should -Be 2
        $f.Issues[1].Title  | Should -BeExactly 'fill command silently fails with special characters'
        $f.Issues[2].Number | Should -Be 3
        $f.Issues[2].Title  | Should -BeExactly 'No --help example for goto command'
    }

    It 'parses severity and category' {
        $filePath = Write-FixtureFile -FileName 'golden.issues.md' -Content $global:GoldenFixture
        $f = Read-IssuesFile -FilePath $filePath

        $f.Issues[0].Severity | Should -BeExactly 'Medium'
        $f.Issues[0].Category | Should -BeExactly 'UX'
        $f.Issues[1].Severity | Should -BeExactly 'High'
        $f.Issues[1].Category | Should -BeExactly 'Reliability'
        $f.Issues[2].Severity | Should -BeExactly 'Low'
        $f.Issues[2].Category | Should -BeExactly 'Discoverability'
    }

    It 'parses section labels in canonical order' {
        $filePath = Write-FixtureFile -FileName 'golden.issues.md' -Content $global:GoldenFixture
        $f = Read-IssuesFile -FilePath $filePath

        $f.Issues[0].Sections.Count | Should -BeGreaterThan 4
        $labels = $f.Issues[0].Sections | ForEach-Object { $_.Label }
        $labels[0] | Should -BeExactly 'Reproduction'
        $labels[1] | Should -BeExactly 'Expected Behavior'
        $labels[2] | Should -BeExactly 'Actual Behavior'
        $labels[3] | Should -BeExactly 'Root Cause Analysis'
        $labels[4] | Should -BeExactly 'Code Pointer'
        $labels[5] | Should -BeExactly 'AI Suggested Improvement'
    }

    It 'skips empty-body sections' {
        $filePath = Write-FixtureFile -FileName 'golden.issues.md' -Content $global:GoldenFixture
        $f = Read-IssuesFile -FilePath $filePath

        $cp = $f.Issues[2].Sections | Where-Object { $_.Label -eq 'Code Pointer' } | Select-Object -First 1
        $cp | Should -BeNullOrEmpty
    }

    It 'has null decisions for unreviewed issues' {
        $filePath = Write-FixtureFile -FileName 'golden.issues.md' -Content $global:GoldenFixture
        $f = Read-IssuesFile -FilePath $filePath

        foreach ($issue in $f.Issues) {
            $issue.Decision | Should -BeNullOrEmpty
            $issue.Notes    | Should -BeExactly ''
        }
    }

    It 'parses previously-reviewed decisions from [x] checkboxes' {
        $reviewed = $global:GoldenFixture
        # Use string .Replace() for reliability (avoid regex escaping issues)
        $reviewed = $reviewed.Replace(
            '- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct',
            '- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct')
        # Add a note after the first "- **Notes:**" marker
        $notesMarker = '- **Notes:**'
        $notesPos = $reviewed.IndexOf($notesMarker)
        if ($notesPos -ge 0) {
            $insertPos = $notesPos + $notesMarker.Length
            $nlPos = $reviewed.IndexOf("`n", $insertPos)
            $reviewed = $reviewed.Substring(0, $nlPos + 1) +
                        'Looks good.' + "`n" +
                        $reviewed.Substring($nlPos + 1)
        }

        $filePath = Write-FixtureFile -FileName 'reviewed.issues.md' -Content $reviewed
        $f = Read-IssuesFile -FilePath $filePath

        $f.Issues[0].Decision | Should -BeExactly 'ACCEPT'
        $f.Issues[0].Notes    | Should -BeExactly 'Looks good.'
    }

    It 'handles files with zero issues gracefully' {
        $empty = "# Issues: empty`n`n> **Source:** ``x`` | **Date:** 20260725 | **Mode:** dev`n`n## Issues Found (0)`n`nNo issues.`n"
        $filePath = Write-FixtureFile -FileName 'empty.issues.md' -Content $empty
        $f = Read-IssuesFile -FilePath $filePath

        $f.Issues.Count | Should -Be 0
    }

    It 'throws on missing file' {
        { Read-IssuesFile -FilePath 'C:\nonexistent\path\file.md' } | Should -Throw
    }

    It 'throws on empty file' {
        $filePath = Write-FixtureFile -FileName 'empty-file.issues.md' -Content ''
        { Read-IssuesFile -FilePath $filePath } | Should -Throw
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Write-IssuesFile tests
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Write-IssuesFile' {

    BeforeEach { Initialize-Fixture }
    AfterEach  { Remove-Fixture }

    It 'sets ACCEPT decision and preserves all other content' {
        $filePath = Write-FixtureFile -FileName 'golden.issues.md' -Content $global:GoldenFixture
        $f = Read-IssuesFile -FilePath $filePath

        $f.Issues[0].Decision = 'ACCEPT'
        $f.Issues[0].Notes = 'This is valid.'
        Write-IssuesFile -ParsedFile $f

        $f2 = Read-IssuesFile -FilePath $filePath
        $f2.Issues[0].Decision | Should -BeExactly 'ACCEPT'
        $f2.Issues[0].Notes    | Should -BeExactly 'This is valid.'
        $f2.Issues[1].Decision | Should -BeNullOrEmpty
        $f2.Issues[2].Decision | Should -BeNullOrEmpty
        $f2.Meta.Scenario      | Should -BeExactly 'golden-scenario'
    }

    It 'sets REJECT decision correctly' {
        $filePath = Write-FixtureFile -FileName 'golden.issues.md' -Content $global:GoldenFixture
        $f = Read-IssuesFile -FilePath $filePath

        $f.Issues[1].Decision = 'REJECT'
        $f.Issues[1].Notes = 'Not a real bug.'
        Write-IssuesFile -ParsedFile $f

        $f2 = Read-IssuesFile -FilePath $filePath
        $f2.Issues[1].Decision | Should -BeExactly 'REJECT'
        $f2.Issues[1].Notes    | Should -BeExactly 'Not a real bug.'
    }

    It 'toggles decision from ACCEPT to DEFER across saves' {
        $filePath = Write-FixtureFile -FileName 'golden.issues.md' -Content $global:GoldenFixture

        $f = Read-IssuesFile -FilePath $filePath
        $f.Issues[0].Decision = 'ACCEPT'
        Write-IssuesFile -ParsedFile $f

        $f2 = Read-IssuesFile -FilePath $filePath
        $f2.Issues[0].Decision | Should -BeExactly 'ACCEPT'
        $f2.Issues[0].Decision = 'DEFER'
        Write-IssuesFile -ParsedFile $f2

        $f3 = Read-IssuesFile -FilePath $filePath
        $f3.Issues[0].Decision | Should -BeExactly 'DEFER'
    }

    It 'clears a decision (set to null)' {
        $filePath = Write-FixtureFile -FileName 'golden.issues.md' -Content $global:GoldenFixture

        $f = Read-IssuesFile -FilePath $filePath
        $f.Issues[0].Decision = 'ACCEPT'
        Write-IssuesFile -ParsedFile $f

        $f2 = Read-IssuesFile -FilePath $filePath
        $f2.Issues[0].Decision = $null
        $f2.Issues[0].Notes = ''
        Write-IssuesFile -ParsedFile $f2

        $f3 = Read-IssuesFile -FilePath $filePath
        $f3.Issues[0].Decision | Should -BeNullOrEmpty
    }

    It 'handles multiline notes' {
        $filePath = Write-FixtureFile -FileName 'golden.issues.md' -Content $global:GoldenFixture
        $f = Read-IssuesFile -FilePath $filePath

        $f.Issues[0].Decision = 'ACCEPT with improvements'
        $f.Issues[0].Notes = "Line 1``nLine 2``n- bullet``n``code``"
        Write-IssuesFile -ParsedFile $f

        $f2 = Read-IssuesFile -FilePath $filePath
        $f2.Issues[0].Decision | Should -BeExactly 'ACCEPT with improvements'
        $f2.Issues[0].Notes    | Should -Match 'Line 1'
        $f2.Issues[0].Notes    | Should -Match 'Line 2'
    }

    It 'is idempotent — multiple saves do not corrupt the file' {
        $filePath = Write-FixtureFile -FileName 'golden.issues.md' -Content $global:GoldenFixture

        1..5 | ForEach-Object {
            $f = Read-IssuesFile -FilePath $filePath
            $f.Issues[0].Decision = 'ACCEPT'
            $f.Issues[1].Decision = 'REJECT'
            $f.Issues[2].Decision = 'DUPLICATE'
            Write-IssuesFile -ParsedFile $f

            $f2 = Read-IssuesFile -FilePath $filePath
            $f2.Issues[0].Decision | Should -BeExactly 'ACCEPT'
            $f2.Issues[1].Decision | Should -BeExactly 'REJECT'
            $f2.Issues[2].Decision | Should -BeExactly 'DUPLICATE'
            $f2.Issues.Count       | Should -Be 3
            $f2.Meta.Scenario      | Should -BeExactly 'golden-scenario'
        }
    }

    It 'preserves How to Reproduce section' {
        $filePath = Write-FixtureFile -FileName 'golden.issues.md' -Content $global:GoldenFixture
        $f = Read-IssuesFile -FilePath $filePath
        $f.Issues[0].Decision = 'ACCEPT'
        Write-IssuesFile -ParsedFile $f

        $content = Get-Content -Raw -Path $filePath -Encoding UTF8
        $content | Should -Match '## How to Reproduce'
        $content | Should -Match '### Common Setup'
    }

    It 'preserves code blocks and backtick content' {
        $filePath = Write-FixtureFile -FileName 'golden.issues.md' -Content $global:GoldenFixture
        $f = Read-IssuesFile -FilePath $filePath
        $f.Issues[1].Decision = 'ACCEPT'
        Write-IssuesFile -ParsedFile $f

        $content = Get-Content -Raw -Path $filePath -Encoding UTF8
        $content | Should -Match '```'
        $content | Should -Match "user's <test> & check"
    }

    It 'preserves Scenario Background after write' {
        $filePath = Write-FixtureFile -FileName 'golden.issues.md' -Content $global:GoldenFixture
        $f = Read-IssuesFile -FilePath $filePath
        $f.Issues[0].Decision = 'WONTFIX'
        Write-IssuesFile -ParsedFile $f

        $content = Get-Content -Raw -Path $filePath -Encoding UTF8
        $content | Should -Match '## Scenario Background'
    }

    It 'writes [x] for selected decision and [ ] for others' {
        $filePath = Write-FixtureFile -FileName 'golden.issues.md' -Content $global:GoldenFixture
        $f = Read-IssuesFile -FilePath $filePath
        $f.Issues[0].Decision = 'DEFER'
        Write-IssuesFile -ParsedFile $f

        $content = Get-Content -Raw -Path $filePath -Encoding UTF8
        $issue1Start = $content.IndexOf('### Issue 1:')
        $issue2Start = $content.IndexOf('### Issue 2:', $issue1Start + 5)
        $issue1Block = $content.Substring($issue1Start, $issue2Start - $issue1Start)

        $issue1Block | Should -Match '- \[x\] \*\*DEFER\*\*'
        $issue1Block | Should -Match '- \[ \] \*\*ACCEPT\*\*'
        $issue1Block | Should -Not -Match '- \[x\] \*\*ACCEPT\*\*'
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Cross-format consistency — PS output must match JS canonical format
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Cross-format consistency' {

    BeforeEach { Initialize-Fixture }
    AfterEach  { Remove-Fixture }

    It 'produces Human Review section matching canonical format' {
        $filePath = Write-FixtureFile -FileName 'golden.issues.md' -Content $global:GoldenFixture
        $f = Read-IssuesFile -FilePath $filePath
        $f.Issues[0].Decision = 'ACCEPT'
        $f.Issues[0].Notes = 'Test note.'
        Write-IssuesFile -ParsedFile $f

        $content = Get-Content -Raw -Path $filePath -Encoding UTF8
        $content | Should -Match '#### Human Review\r?\n\r?\n'
        $content | Should -Match '- \[x\] \*\*ACCEPT\*\*'
        $content | Should -Match '- \*\*Notes:\*\*'
        $content | Should -Match 'Test note\.'
    }

    It 'round-trips all six decision types' {
        $decisions = @('ACCEPT', 'ACCEPT with improvements', 'DEFER',
                        'WONTFIX', 'REJECT', 'DUPLICATE')
        $filePath = Write-FixtureFile -FileName 'all-decisions.issues.md' -Content $global:GoldenFixture
        $f = Read-IssuesFile -FilePath $filePath

        for ($i = 0; $i -lt 3; $i++) {
            $f.Issues[$i].Decision = $decisions[$i]
            $f.Issues[$i].Notes = "Note for $($decisions[$i])"
        }
        Write-IssuesFile -ParsedFile $f

        $f2 = Read-IssuesFile -FilePath $filePath
        $f2.Issues[0].Decision | Should -BeExactly 'ACCEPT'
        $f2.Issues[1].Decision | Should -BeExactly 'ACCEPT with improvements'
        $f2.Issues[2].Decision | Should -BeExactly 'DEFER'

        for ($i = 0; $i -lt 3; $i++) {
            $f2.Issues[$i].Decision = $decisions[$i + 3]
            $f2.Issues[$i].Notes = "Note for $($decisions[$i + 3])"
        }
        Write-IssuesFile -ParsedFile $f2

        $f3 = Read-IssuesFile -FilePath $filePath
        $f3.Issues[0].Decision | Should -BeExactly 'WONTFIX'
        $f3.Issues[1].Decision | Should -BeExactly 'REJECT'
        $f3.Issues[2].Decision | Should -BeExactly 'DUPLICATE'
    }

    It 'preserves Source metadata across multiple saves' {
        $filePath = Write-FixtureFile -FileName 'golden.issues.md' -Content $global:GoldenFixture

        1..3 | ForEach-Object {
            $f = Read-IssuesFile -FilePath $filePath
            $f.Issues[0].Decision = 'ACCEPT'
            Write-IssuesFile -ParsedFile $f
        }

        $f = Read-IssuesFile -FilePath $filePath
        $f.Meta.Source | Should -BeExactly '20260725-120000-golden-scenario.full.md'
        $f.Meta.Date   | Should -BeExactly '20260725-120000'
        $f.Meta.Mode   | Should -BeExactly 'dev'
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Decision list — must match issue-model.js DECISIONS array exactly
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Review decisions match issue-model.js' {

    It 'has exactly six decisions in canonical order' {
        $global:ReviewDecisions.Count | Should -Be 6
        $global:ReviewDecisions[0] | Should -BeExactly 'ACCEPT'
        $global:ReviewDecisions[1] | Should -BeExactly 'ACCEPT with improvements'
        $global:ReviewDecisions[2] | Should -BeExactly 'DEFER'
        $global:ReviewDecisions[3] | Should -BeExactly 'WONTFIX'
        $global:ReviewDecisions[4] | Should -BeExactly 'REJECT'
        $global:ReviewDecisions[5] | Should -BeExactly 'DUPLICATE'
    }

    It 'has descriptions for all six decisions' {
        foreach ($dec in $global:ReviewDecisions) {
            $desc = Get-DecisionDescription -Decision $dec
            $desc | Should -Not -BeNullOrEmpty
        }
    }

    It 'descriptions match issue-model.js wording' {
        (Get-DecisionDescription -Decision 'ACCEPT')                  | Should -Match 'confirmed valid'
        (Get-DecisionDescription -Decision 'ACCEPT with improvements') | Should -Match 'needs refinement'
        (Get-DecisionDescription -Decision 'DEFER')                   | Should -Match 'intentionally deferred'
        (Get-DecisionDescription -Decision 'WONTFIX')                 | Should -Match 'will not be fixed'
        (Get-DecisionDescription -Decision 'REJECT')                  | Should -Match 'not a problem'
        (Get-DecisionDescription -Decision 'DUPLICATE')               | Should -Match 'duplicates another'
    }
}
