#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Skill document conformance linter for skills/browser4-cli/methodology.md (checks M1-M8).

.DESCRIPTION
    Checks every .md file under skills/ against the machine-checkable rules of
    skills/browser4-cli/methodology.md:

      M1  Frontmatter exists; title/description/tier present and valid
      M2  title equals the # heading
      M3  name in SKILL.md manifests matches the directory name
      M4  Required template sections present in order (per tier / SKILL.md variant)
      M5  Tier-content rules (flag tables, decision trees, function catalogs)
      M6  Line-count limits (SKILL.md <= 300; 100 <= decision <= 300; procedure <= 500)
      M7  All relative links resolve; no index links in SKILL.md
      M8  No emoji in callout lines; broad warnings unique outside SKILL.md section 5

    Exemptions honored from frontmatter:
      x-exempt: P4            skip a check (e.g. governance docs)
      x-role: distilled       distilled copies skip M4/M5 (bounded P2 exception)
      x-role: index           index documents skip M4 (indexes list files, no template)

.PARAMETER Path
    Directory to scan. Defaults to <repo>/skills.

.PARAMETER PassThru
    Also emit the machine-readable JSON issue list.

.EXAMPLE
    ./bin/skill-doc-lint.ps1
.EXAMPLE
    ./bin/skill-doc-lint.ps1 -Path skills/browser4-cli -PassThru
#>
param(
    [string]$Path,
    [switch]$PassThru
)

$ErrorActionPreference = 'Stop'

if (-not $Path) { $Path = Join-Path $PSScriptRoot '..\skills' }
$Path = (Resolve-Path $Path).Path

$issues = [System.Collections.Generic.List[string]]::new()
$counts = @{}

function Add-Issue([string]$file, [string]$check, [string]$msg) {
    $issue = "{0}  [{1}] {2}" -f $file, $check, $msg
    $issues.Add($issue)
    if ($counts.ContainsKey($check)) { $counts[$check]++ } else { $counts[$check] = 1 }
}

# --- helpers ---------------------------------------------------------------

function Get-Frontmatter([string]$text) {
    $m = [regex]::Match($text, '(?s)^\ufeff?---\r?\n(.*?)\r?\n---')
    if (-not $m.Success) { return $null }
    $fm = @{}
    foreach ($line in ($m.Groups[1].Value -split "`r?`n")) {
        $lm = [regex]::Match($line, '^([A-Za-z0-9_-]+):\s*(.*)$')
        if ($lm.Success) { $fm[$lm.Groups[1].Value] = $lm.Groups[2].Value.Trim().Trim('"') }
    }
    return $fm
}

# H2 headings outside code fences, in document order
function Get-Headings([string[]]$lines) {
    $inCode = $false
    $h = [System.Collections.Generic.List[string]]::new()
    foreach ($line in $lines) {
        if ($line -match '^```') { $inCode = -not $inCode; continue }
        if (-not $inCode -and $line -match '^##\s+(.+)$') { $h.Add($Matches[1].Trim()) }
    }
    return $h
}

# sections of a table (consecutive lines starting with |), outside code fences
function Get-Tables([string[]]$lines) {
    $inCode = $false
    $tables = [System.Collections.Generic.List[object]]::new()
    $cur = [System.Collections.Generic.List[string]]::new()
    foreach ($line in $lines) {
        if ($line -match '^```') { $inCode = -not $inCode; continue }
        if (-not $inCode -and $line -match '^\|') { $cur.Add($line); continue }
        if ($cur.Count -gt 0) { $tables.Add([pscustomobject]@{ Rows = $cur.Count; Sample = $cur[0] }); $cur = [System.Collections.Generic.List[string]]::new() }
    }
    if ($cur.Count -gt 0) { $tables.Add([pscustomobject]@{ Rows = $cur.Count; Sample = $cur[0] }) }
    return $tables
}

# markdown links outside code fences and inline code
function Get-Links([string[]]$lines) {
    $inCode = $false
    $links = [System.Collections.Generic.List[string]]::new()
    foreach ($line in $lines) {
        if ($line -match '^```') { $inCode = -not $inCode; continue }
        if ($inCode) { continue }
        $clean = [regex]::Replace($line, '`[^`]*`', '')
        foreach ($m in [regex]::Matches($clean, '\[[^\]]*\]\(([^)]+)\)')) {
            $links.Add($m.Groups[1].Value)
        }
    }
    return $links
}

# callout lines (> **Warning:** / **Note:** / **Tip:**)
function Get-Callouts([string[]]$lines) {
    $c = [System.Collections.Generic.List[object]]::new()
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^>\s*\*\*(Warning|Note|Tip):\*\*(.*)$') {
            $c.Add([pscustomobject]@{ Kind = $Matches[1]; Text = $Matches[2].Trim(); LineNo = $i + 1 })
        }
    }
    return $c
}

$EmojiRe = '[\uD83C-\uDBFF][\uDC00-\uDFFF]|[\u2600-\u27BF\u2B00-\u2BFF\uFE0F\u00A9\u00AE\u2122]'

# warning text fingerprints (normalized 25-char windows) for M8 uniqueness
$warningWindows = [System.Collections.Generic.HashSet[string]]::new()

# --- templates -------------------------------------------------------------

$TemplateSkillDecision = @('Core Loop', 'Key Concepts', 'Command Map', 'Decision Trees', 'Critical Warnings', 'Quick Patterns', 'Reference Map')
$TemplateDecision      = @('Quick Comparison', 'Decision Tree', 'When to Use Each', 'Quick Patterns', 'Reference Map')
$TemplateProcedure     = @('Quick Start', 'When to Use', 'How It Works', 'Patterns', 'Flags', 'Errors & Recovery')
$TemplateCatalog       = @('Overview', 'Quick Index')   # reference section heading is free (methodology v2.2)

# per-section heading aliases (methodology v2.1/v2.2): map template section -> accepted equivalents
$AliasesBySection = @{
    'Quick Index'        = @('Quick Reference', 'Table of Contents', 'Commands', 'Function Index')
    'Errors & Recovery'  = @('Error handling')
}

function Test-Template([string[]]$headings, [string[]]$template, [hashtable]$aliasesBySection = @{}) {
    if ($null -eq $aliasesBySection) { $aliasesBySection = @{} }
    # all sections must exist somewhere
    foreach ($needle in $template) {
        $candidates = @($needle) + @($aliasesBySection[$needle])
        $found = $false
        foreach ($h in $headings) {
            foreach ($c in $candidates) {
                if ($null -ne $c -and $h.ToLowerInvariant().Contains($c.ToLowerInvariant())) { $found = $true; break }
            }
            if ($found) { break }
        }
        if (-not $found) { return "missing section: $needle" }
    }
    # and must appear in template order
    $pos = -1
    foreach ($needle in $template) {
        $candidates = @($needle) + @($aliasesBySection[$needle])
        $found = -1
        for ($i = 0; $i -lt $headings.Count; $i++) {
            foreach ($c in $candidates) {
                if ($null -ne $c -and $headings[$i].ToLowerInvariant().Contains($c.ToLowerInvariant()) -and $i -gt $pos) { $found = $i; break }
            }
            if ($found -ge 0) { break }
        }
        if ($found -lt 0) { return "section out of order: $needle" }
        $pos = $found
    }
    return $null
}

# --- scan ------------------------------------------------------------------

$files = Get-ChildItem -Path $Path -Recurse -Filter *.md | Sort-Object FullName
$skipM8 = $false

foreach ($f in $files) {
    $rel = $f.FullName.Substring($Path.Length + 1)
    $lines = Get-Content $f.FullName
    $text = $lines -join "`n"
    $base = [System.IO.Path]::GetFileName($f.FullName)
    $dirName = [System.IO.Path]::GetFileName([System.IO.Path]::GetDirectoryName($f.FullName))

    $fm = Get-Frontmatter $text
    $isManifest = $base -eq 'SKILL.md'
    $exempt = @()
    if ($fm -and $fm['x-exempt']) { $exempt = $fm['x-exempt'] -split ',' | ForEach-Object { $_.Trim() } }
    $role = if ($fm) { $fm['x-role'] } else { $null }

    # M1: frontmatter ------------------------------------------------------
    if (-not $fm) {
        Add-Issue $rel 'M1' 'no YAML frontmatter'
    } else {
        foreach ($req in @('title', 'description', 'tier')) {
            if (-not $fm.ContainsKey($req) -or -not $fm[$req]) { Add-Issue $rel 'M1' "missing frontmatter field: $req" }
        }
        if ($fm['tier'] -and $fm['tier'] -notin @('decision', 'procedure', 'catalog')) {
            Add-Issue $rel 'M1' "invalid tier: '$($fm['tier'])' (must be decision|procedure|catalog)"
        }
        if ($role -eq 'distilled') {
            if (-not $fm['source']) { Add-Issue $rel 'M1' 'distilled document missing source field' }
            elseif (-not (Test-Path (Join-Path $f.DirectoryName $fm['source']))) { Add-Issue $rel 'M1' "distilled source does not exist: $($fm['source'])" }
        }
    }

    # M2: title == H1 -------------------------------------------------------
    if ($fm -and $fm['title']) {
        $h1 = $null
        foreach ($line in $lines) { if ($line -match '^#\s+(.+)$') { $h1 = $Matches[1].Trim(); break } }
        if ($h1 -and $fm['title'] -ne $h1) {
            Add-Issue $rel 'M2' "title '$($fm['title'])' != H1 '$h1'"
        }
    }

    # M3: manifest name == directory ---------------------------------------
    if ($isManifest -and $fm -and $fm['name'] -and $fm['name'] -ne $dirName) {
        Add-Issue $rel 'M3' "name '$($fm['name'])' != directory '$dirName'"
    }

    $headings = @(Get-Headings $lines)

    # M4: template sections -------------------------------------------------
    if ($fm -and $fm['tier'] -and $role -notin @('distilled', 'index') -and 'P4' -notin $exempt) {
        $tpl = $null
        if ($isManifest) {
            $tpl = if ($fm['tier'] -eq 'procedure') { $TemplateProcedure } else { $TemplateSkillDecision }
        } else {
            switch ($fm['tier']) {
                'decision'  { $tpl = $TemplateDecision }
                'procedure' { $tpl = $TemplateProcedure }
                'catalog'   { $tpl = $TemplateCatalog }
            }
        }
        if ($tpl) {
            $err = Test-Template $headings $tpl $AliasesBySection
            if ($err) { Add-Issue $rel 'M4' "tier=$($fm['tier']): $err" }
        }
    }

    # M5: tier-content rules ------------------------------------------------
    if ($fm -and $fm['tier'] -and $role -ne 'distilled') {
        if ($fm['tier'] -eq 'decision' -and -not $isManifest) {
            foreach ($t in @(Get-Tables $lines)) {
                if ($t.Rows -gt 20) { Add-Issue $rel 'M5' "decision doc with $($t.Rows)-row table (complete flag listing?): $($t.Sample)" }
            }
        }
        if ($fm['tier'] -eq 'catalog') {
            if ($headings -contains 'Decision Tree') { Add-Issue $rel 'M5' 'catalog contains a ## Decision Tree section' }
        }
        if ($fm['tier'] -eq 'procedure') {
            if ($headings -contains 'Quick Index' -or $headings -contains 'Reference') { Add-Issue $rel 'M5' 'procedure contains a catalog-style section (Quick Index/Reference)' }
        }
    }

    # M6: line counts -------------------------------------------------------
    if ($fm -and $fm['tier'] -and $role -ne 'distilled') {
        $n = $lines.Count
        if ($isManifest -and $n -gt 300) { Add-Issue $rel 'M6' "SKILL.md is $n lines (cap 300)" }
        elseif ($fm['tier'] -eq 'decision' -and ($n -lt 100 -or $n -gt 300)) { Add-Issue $rel 'M6' "decision doc is $n lines (target 100-300)" }
        elseif ($fm['tier'] -eq 'procedure' -and $n -gt 500) { Add-Issue $rel 'M6' "procedure doc is $n lines (cap 500)" }
        elseif ($fm['tier'] -eq 'procedure' -and $n -lt 100) { Add-Issue $rel 'M6' "procedure doc is $n lines (target >= 100)" }
    }

    # M7: links -------------------------------------------------------------
    if ($fm -and $role -ne 'distilled') {
        foreach ($link in @(Get-Links $lines)) {
            if ($link -match '^(https?://|mailto:|#|data:)') { continue }
            $target = ($link -split '#')[0]
            if (-not $target) { continue }
            $resolved = [System.IO.Path]::GetFullPath((Join-Path $f.DirectoryName $target))
            if (-not (Test-Path $resolved)) { Add-Issue $rel 'M7' "broken link: $link" }
        }
        if ($isManifest) {
            foreach ($link in @(Get-Links $lines)) {
                if ($link -match '^(https?://|#|mailto:|data:)') { continue }
                $target = ($link -split '#')[0]
                if (-not $target) { continue }
                $t = Get-Content (Join-Path $f.DirectoryName $target) -TotalCount 30 -ErrorAction SilentlyContinue
                $tfm = if ($t) { Get-Frontmatter ($t -join "`n") } else { $null }
                if ($tfm -and $tfm['x-role'] -eq 'index') { Add-Issue $rel 'M7' 'SKILL.md links to an index document (P6: index must not sit on the critical path)' }
            }
        }
    }

    # M8: callout emoji + warning uniqueness --------------------------------
    if ($fm -and $role -ne 'distilled') {
        foreach ($c in @(Get-Callouts $lines)) {
            if ($c.Text -match $EmojiRe) { Add-Issue $rel 'M8' "emoji in $($c.Kind) callout (line $($c.LineNo))" }
        }
    }
    if ($isManifest) {
        $inSec5 = $false
        foreach ($line in $lines) {
            if ($line -match '^##\s') { $inSec5 = $line -match 'Critical Warnings' }
            if ($inSec5 -and $line -match '^>\s*\*\*Warning:\*\*\s*(.+)$') {
                $norm = ($Matches[1] -replace '[^a-zA-Z0-9 ]', '').ToLowerInvariant()
                if ($norm.Length -ge 25) {
                    for ($i = 0; $i -le $norm.Length - 25; $i++) { [void]$warningWindows.Add($norm.Substring($i, 25)) }
                }
            }
        }
    }
}

# second pass: warning text must not recur outside the SKILL.md section 5 that owns it
if ($warningWindows.Count -gt 0) {
    foreach ($f in $files) {
        $rel = $f.FullName.Substring($Path.Length + 1)
        if ([System.IO.Path]::GetFileName($f.FullName) -eq 'SKILL.md') { continue }
        $lines = Get-Content $f.FullName
        $inCode = $false
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match '^```') { $inCode = -not $inCode; continue }
            if ($inCode) { continue }
            if ($lines[$i] -match '^>\s*\*\*(Warning|Note|Tip):\*\*\s*(.+)$') {
                # one-line pointers back to a SKILL.md section 5 are the sanctioned form (P5)
                if ($lines[$i] -match '\[[^\]]*\]\([^)]*SKILL\.md[^)]*\)') { continue }
                $norm = ($Matches[2] -replace '[^a-zA-Z0-9 ]', '').ToLowerInvariant()
                if ($norm.Length -ge 25) {
                    for ($j = 0; $j -le $norm.Length - 25; $j++) {
                        if ($warningWindows.Contains($norm.Substring($j, 25))) {
                            Add-Issue $rel 'M8' "warning text duplicates SKILL.md section 5 (line $($i + 1)) — link back instead"
                            break
                        }
                    }
                }
            }
        }
    }
}

# --- report ----------------------------------------------------------------

$failed = $issues.Count -gt 0
$total = $files.Count
$summary = "checked $total files, $($issues.Count) issue(s)"
if ($counts.Count -gt 0) {
    $byCheck = ($counts.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ', '
    $summary += " [$byCheck]"
}
Write-Host $summary
$issues | Sort-Object -Unique | ForEach-Object { Write-Host "  $_" }

if ($PassThru) {
    [pscustomobject]@{
        Files    = $total
        Issues   = $issues.Count
        ByCheck  = $counts
        Details  = @($issues | Sort-Object -Unique)
    } | ConvertTo-Json -Depth 4
}

exit $(if ($failed) { 1 } else { 0 })
