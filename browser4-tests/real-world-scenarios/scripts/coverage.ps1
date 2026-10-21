#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: must run on Linux, macOS and Windows.
# - Use Join-Path / Split-Path; never bake \ or / as a literal.
# - No Windows-only cmdlets, no Windows-only env vars.
# - Pure text processing: no browser, no backend, no Java, no network.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
Audit real-world-scenario command coverage against the browser4-cli command registry.

.DESCRIPTION
Cross-references three sources:

  1. `cli/browser4-cli/src/commands.rs`  — the command registry (`all_commands()`)
  2. `cli/browser4-cli/src/main.rs`      — the accepted spaced command forms
                                            (`preferred_spaced_command_form`)
  3. `tasks/**/*.md` + `tasks/workflow/*.ps1` — the scenario corpus

and reports, in the same three tiers the coverage plan uses:

  T1  the scenario corpus names the command explicitly
  T2  the command is not named but its behaviour is required by a scenario
      (kept by hand in `coverage-behavioural.txt`, because it is a judgement call)
  T3  nothing in the corpus touches it

Checks (default mode, exit 1 on any error):

  A  a T3 command that is neither in the baseline nor in the exclusion list
     → a NEW coverage gap
  B  an exclusion-list entry that is not a real command name
  C  (warning) an exclusion-list entry that a scenario now covers
  D  (warning) a baseline entry that a scenario now covers — stale baseline
  E  a scenario token that looks like a command but does not resolve to one
     (the `dialog status` class of bug: a spaced form the CLI does not accept)

.PARAMETER List
Print the T1/T2/T3 inventory and exit.

.PARAMETER UpdateBaseline
Rewrite `coverage-baseline.txt` from the current measurement (use after landing
scenarios that close gaps).

.PARAMETER RepoRoot
Repository root. Defaults to the parent of this script's grandparent directory.

.EXAMPLE
pwsh ./browser4-tests/real-world-scenarios/scripts/coverage.ps1 -List

.EXAMPLE
pwsh ./browser4-tests/real-world-scenarios/scripts/coverage.ps1 -UpdateBaseline

.NOTES
Run by `coverage.tests.ps1`, which `bin/test.ps1 ps` discovers automatically.
#>

[CmdletBinding()]
param(
    [switch] $List,
    [switch] $UpdateBaseline,
    [string] $RepoRoot = ''
)

$ErrorActionPreference = 'Stop'

$script:ScriptDir = $PSScriptRoot
if (-not $RepoRoot) {
    $RepoRoot = (Resolve-Path (Join-Path $script:ScriptDir '..' '..' '..')).Path
}
$script:RepoRoot    = $RepoRoot
$script:ScenRoot    = Join-Path $RepoRoot 'browser4-tests' 'real-world-scenarios'
$script:CommandsRs  = Join-Path $RepoRoot 'cli' 'browser4-cli' 'src' 'commands.rs'
$script:MainRs      = Join-Path $RepoRoot 'cli' 'browser4-cli' 'src' 'main.rs'
$script:BaselinePath = Join-Path $script:ScriptDir 'coverage-baseline.txt'
$script:ExclusionsPath = Join-Path $script:ScriptDir 'coverage-exclusions.psd1'
$script:BehaviouralPath = Join-Path $script:ScriptDir 'coverage-behavioural.txt'
$script:IgnoreTokensPath = Join-Path $script:ScriptDir 'coverage-ignore-tokens.txt'

$script:Errors = @()
$script:Warnings = @()

function Write-Head {
    param([string] $Text)
    Write-Host ''
    Write-Host "── $Text ──" -ForegroundColor Cyan
}

# ── Source 1: the command registry ────────────────────────────────────────────

function Get-Registry {
    if (-not (Test-Path $script:CommandsRs)) {
        throw "Command registry not found: $($script:CommandsRs)"
    }
    $lines = Get-Content $script:CommandsRs
    $names = New-Object System.Collections.Generic.List[string]
    foreach ($line in $lines) {
        if ($line -match '^            name: "([a-z0-9-]+)",$') {
            $names.Add($Matches[1])
        }
    }
    if ($names.Count -eq 0) { throw "No CommandDef names parsed from $($script:CommandsRs)" }
    return $names
}

# ── Source 2: accepted spaced forms ───────────────────────────────────────────

function Get-SpacedForms {
    $forms = @{}
    if (-not (Test-Path $script:MainRs)) { return $forms }
    $text = Get-Content $script:MainRs -Raw
    foreach ($m in [regex]::Matches($text, '"([a-z0-9-]+)"\s*=>\s*Some\("([a-z0-9 -]+)"\)')) {
        $forms[$m.Groups[1].Value] = $m.Groups[2].Value
    }
    return $forms
}

# ── Source 3: the scenario corpus ─────────────────────────────────────────────

function Get-ScenarioCorpus {
    $files = @()
    $files += Get-ChildItem (Join-Path $script:ScenRoot 'tasks') -Recurse -File -Filter '*.md' -ErrorAction SilentlyContinue
    $files += Get-ChildItem (Join-Path $script:ScenRoot 'tasks' 'workflow') -File -Filter '*.ps1' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -ne 'run-all.ps1' }
    return $files | Sort-Object FullName
}

<#
Build the CODE-context corpus: fenced blocks, inline code spans and explicit CLI
invocations only.  Prose mentions must not count as coverage — otherwise a word
like "key" or "type" in ordinary text would silently mark `key` / `type` as
covered, and a newly added command with such a name could never be reported.
Prose-unambiguous commands belong in coverage-behavioural.txt instead.
#>
function Get-CodeCorpus {
    $text = ''
    foreach ($file in (Get-ScenarioCorpus)) {
        $raw = Get-Content $file.FullName
        $inFence = $false
        foreach ($line in $raw) {
            if ($line -match '^\s*```') { $inFence = -not $inFence; continue }
            if ($inFence) { $text += $line + "`n" }
            else {
                foreach ($m in [regex]::Matches($line, '`([^`]+)`')) { $text += $m.Groups[1].Value + "`n" }
            }
            if ($line -cmatch '(browser4-cli|__CLI__|\$cliInvocation|\$cliRef|b4w\.ps1|b4w\.sh|\./b4w)\s+\S') {
                $text += $line + "`n"
            }
        }
    }
    return $text
}

function Test-InText {
    param([string] $Command, [string] $Text, [hashtable] $SpacedForms = @{})
    $forms = @($Command, ($Command -replace '-', ' '))
    if ($SpacedForms.ContainsKey($Command)) { $forms += $SpacedForms[$Command] }
    foreach ($form in $forms) {
        $pat = "(?<![a-z0-9-])" + [regex]::Escape($form) + "(?![a-z0-9-])"
        if ($Text -match $pat) { return $true }
    }
    return $false
}

# ── Configuration files ───────────────────────────────────────────────────────

function Read-LineFile {
    param([string] $Path)
    if (-not (Test-Path $Path)) { return @() }
    return @(Get-Content $Path |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and -not $_.StartsWith('#') })
}

function Read-Exclusions {
    if (-not (Test-Path $script:ExclusionsPath)) { return @{} }
    $data = Import-PowerShellDataFile -Path $script:ExclusionsPath
    $map = @{}
    foreach ($key in $data.Keys) { $map[$key] = [string]$data[$key] }
    return $map
}

function Test-Excluded {
    param([string] $Command, [hashtable] $Exclusions)
    foreach ($key in $Exclusions.Keys) {
        if ($key.EndsWith('*')) {
            if ($Command.StartsWith($key.TrimEnd('*'))) { return $key }
        } elseif ($key -eq $Command) {
            return $key
        }
    }
    return $null
}

# ── Reverse check: tokens that look like commands ─────────────────────────────

function Get-SuspiciousTokens {
    param([string[]] $Registry, [hashtable] $SpacedForms, [string[]] $IgnoreTokens, [hashtable] $Exclusions)

    # Every form the CLI accepts.  Ordinal comparer: command names are lowercase
    # and `localStorage` (a JS API in a code span) is NOT the `localstorage-*`
    # family.
    #
    # `rewrite_prefixed_command()` only rewrites spaced forms for a known set of
    # prefixes, so `tab-list` / `dialog-status` / `delete-data` do NOT accept
    # `tab list` / `dialog status` / `delete data` — those must be written
    # kebab-case.  Modelling that is the whole point of check E.
    $accepted = [System.Collections.Hashtable]::new([System.StringComparer]::Ordinal)
    foreach ($c in $Registry) { $accepted[$c] = $true }
    $rewritablePrefixes = @('swarm', 'agent', 'experience', 'htmlsnapshot', 'snapshot', 'skills', 'plugin', 'config', 'code')
    foreach ($c in $Registry) {
        $i = $c.IndexOf('-')
        if ($i -gt 0 -and $rewritablePrefixes -contains $c.Substring(0, $i)) {
            $accepted[$c.Replace('-', ' ')] = $true
        }
        if ($SpacedForms.ContainsKey($c)) { $accepted[$SpacedForms[$c]] = $true }
    }
    # Prefixes that introduce a command family (`dialog`, `network`, `tab`, ...).
    $prefixes = [System.Collections.Hashtable]::new([System.StringComparer]::Ordinal)
    foreach ($c in $Registry) {
        $i = $c.IndexOf('-')
        if ($i -gt 0) { $prefixes[$c.Substring(0, $i)] = $true }
    }

    $findings = @()
    foreach ($file in (Get-ScenarioCorpus)) {
        $rel = $file.FullName.Substring($script:ScenRoot.Length + 1) -replace '\\', '/'
        $raw = Get-Content $file.FullName -Raw

        $candidates = New-Object System.Collections.Generic.List[object]
        foreach ($m in [regex]::Matches($raw, '`([^`\r\n]+)`')) {
            $candidates.Add([pscustomobject]@{ Token = $m.Groups[1].Value.Trim(); Kind = 'code-span' })
        }
        foreach ($m in [regex]::Matches($raw, '(?:__CLI__|browser4-cli|\$cliInvocation|\$cliRef|b4w)\s+([a-z][a-z0-9-]*(?: [a-z][a-z0-9-]*){0,2})')) {
            $candidates.Add([pscustomobject]@{ Token = $m.Groups[1].Value.Trim(); Kind = 'invocation' })
        }

        foreach ($cand in $candidates) {
            $tok = $cand.Token
            # Case-sensitive on purpose: `localStorage` is a JS API, not a command.
            if ($tok -cnotmatch '^[a-z][a-z0-9-]*$' -and
                $tok -cnotmatch '^[a-z][a-z0-9-]* [a-z][a-z0-9-]*( [a-z][a-z0-9-]*){0,2}$') {
                continue
            }
            # A bare family name (`agent`, `tab`, `experience`) is normal prose in
            # a usage template; only multi-word spans can be a spaced command.
            if ($tok -cnotmatch ' ') { continue }
            if ($accepted.ContainsKey($tok)) { continue }
            # NOTE: deliberately no "kebab-ise the token and accept it" fallback —
            # `dialog status` kebab-ises to a real command but the CLI rejects that
            # spelling, which is exactly what this check exists to catch.
            if ($IgnoreTokens -contains $tok) { continue }

            $first = ($tok -split ' ')[0]
            # A complete command followed by positional arguments (`set geo`,
            # `get box`, `frame main`) is legitimate usage, not a bad name.
            if ($Registry -ccontains $first) { continue }
            # Only flag tokens whose first word introduces a real command family.
            if (-not $prefixes.ContainsKey($first)) { continue }
            if (Test-Excluded $tok $Exclusions) { continue }

            $findings += [pscustomobject]@{ File = $rel; Token = $tok; Kind = $cand.Kind }
        }
    }
    return $findings
}

# ── Measure ───────────────────────────────────────────────────────────────────

$registry = Get-Registry
$spacedForms = Get-SpacedForms
$exclusions = Read-Exclusions
$baseline = Read-LineFile $script:BaselinePath
$behavioural = Read-LineFile $script:BehaviouralPath
$ignoreTokens = Read-LineFile $script:IgnoreTokensPath

$corpus = Get-CodeCorpus

$t1 = New-Object System.Collections.Generic.List[string]
$t2 = New-Object System.Collections.Generic.List[string]
$t3 = New-Object System.Collections.Generic.List[string]
foreach ($cmd in $registry) {
    if (Test-InText $cmd $corpus $spacedForms) { $t1.Add($cmd) }
    elseif ($behavioural -contains $cmd) { $t2.Add($cmd) }
    else { $t3.Add($cmd) }
}

if ($List) {
    Write-Host ''
    Write-Host "browser4-cli command coverage (real-world-scenarios)" -ForegroundColor Cyan
    Write-Host "  registry:  $($registry.Count) commands"
    Write-Host "  T1 named:  $($t1.Count)"
    Write-Host "  T2 behavioural (curated): $($t2.Count)"
    Write-Host "  T3 uncovered: $($t3.Count)"
    Write-Host ''
    Write-Host 'T3 (uncovered):' -ForegroundColor Yellow
    foreach ($cmd in $t3) {
        $reason = Test-Excluded $cmd $exclusions
        $suffix = if ($reason) { "  [excluded: $reason]" } else { '' }
        Write-Host "  $cmd$suffix"
    }
    Write-Host ''
    exit 0
}

if ($UpdateBaseline) {
    $gapList = $t3 | Where-Object { -not (Test-Excluded $_ $exclusions) } | Sort-Object
    $header = @(
        '# Coverage baseline for real-world-scenarios.',
        '# Every command listed here is a KNOWN gap: it is not covered by any scenario',
        '# and not excused in coverage-exclusions.psd1 yet.  coverage.ps1 fails only when a',
        '# NEW gap appears, so landing scenarios should shrink this file:',
        '#   pwsh ./browser4-tests/real-world-scenarios/scripts/coverage.ps1 -UpdateBaseline',
        "# Generated: $((Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'))"
    )
    Set-Content -Path $script:BaselinePath -Value ($header + $gapList) -Encoding UTF8
    Write-Host "Baseline updated: $($gapList.Count) gap(s) recorded in $($script:BaselinePath)"
    exit 0
}

# ── Check A: new coverage gaps ────────────────────────────────────────────────

Write-Head 'Check A — new coverage gaps'
$newGaps = @()
$knownGaps = @()
foreach ($cmd in $t3) {
    if (Test-Excluded $cmd $exclusions) { continue }
    if ($baseline -contains $cmd) { $knownGaps += $cmd; continue }
    $newGaps += $cmd
}
if ($newGaps.Count -eq 0) {
    Write-Host "  PASS: no new gaps ($($knownGaps.Count) known gap(s) remain, $($t3.Count - $knownGaps.Count) excused)" -ForegroundColor Green
} else {
    Write-Host "  FAIL: $($newGaps.Count) new command(s) have no scenario:" -ForegroundColor Red
    foreach ($cmd in $newGaps) { Write-Host "    $cmd" -ForegroundColor Red }
    $script:Errors += "new coverage gap(s): $($newGaps -join ', ')"
}

# ── Check B/C: exclusion list sanity ──────────────────────────────────────────

Write-Head 'Check B/C — exclusion list sanity'
$badExclusions = @()
$staleExclusions = @()
foreach ($key in $exclusions.Keys) {
    if ($key.EndsWith('*')) {
        $prefix = $key.TrimEnd('*')
        $matches = @($registry | Where-Object { $_.StartsWith($prefix) })
        if ($matches.Count -eq 0) { $badExclusions += $key }
        continue
    }
    if ($registry -notcontains $key) { $badExclusions += $key; continue }
    if ($t1 -contains $key) { $staleExclusions += $key }
}
if ($badExclusions.Count -eq 0) {
    Write-Host "  PASS: every exclusion names a real command ($($exclusions.Count) entry/entries)" -ForegroundColor Green
} else {
    Write-Host "  FAIL: exclusion entries that match no command: $($badExclusions -join ', ')" -ForegroundColor Red
    $script:Errors += "invalid exclusion entry: $($badExclusions -join ', ')"
}
if ($staleExclusions.Count -gt 0) {
    Write-Host "  WARN: excluded but now covered by a scenario — remove from coverage-exclusions.psd1: $($staleExclusions -join ', ')" -ForegroundColor Yellow
    $script:Warnings += "stale exclusion(s): $($staleExclusions -join ', ')"
}

# ── Check D: stale baseline ───────────────────────────────────────────────────

Write-Head 'Check D — baseline freshness'
$staleBaseline = @($baseline | Where-Object { $t1 -contains $_ })
if ($staleBaseline.Count -eq 0) {
    Write-Host "  PASS: baseline has no already-covered commands" -ForegroundColor Green
} else {
    Write-Host "  WARN: now covered — re-run with -UpdateBaseline: $($staleBaseline -join ', ')" -ForegroundColor Yellow
    $script:Warnings += "stale baseline entries: $($staleBaseline -join ', ')"
}

# ── Check E: scenario tokens that are not valid commands ─────────────────────

Write-Head 'Check E — scenario command tokens'
$suspicious = Get-SuspiciousTokens -Registry $registry -SpacedForms $spacedForms -IgnoreTokens $ignoreTokens -Exclusions $exclusions
if ($suspicious.Count -eq 0) {
    Write-Host '  PASS: every command-looking token in the scenario corpus resolves to a real command' -ForegroundColor Green
} else {
    Write-Host "  FAIL: $($suspicious.Count) token(s) look like commands but do not resolve:" -ForegroundColor Red
    foreach ($s in $suspicious) {
        Write-Host "    $($s.Token)  ($($s.File), $($s.Kind))" -ForegroundColor Red
    }
    Write-Host '  Fix the scenario token, or add it to coverage-ignore-tokens.txt with a reason.' -ForegroundColor DarkGray
    $script:Errors += "unresolved command token(s): $(($suspicious | ForEach-Object { $_.Token } | Select-Object -Unique) -join ', ')"
}

# ── Summary ───────────────────────────────────────────────────────────────────

Write-Host ''
Write-Host '══════════════════════════════════════════════════' -ForegroundColor Cyan
Write-Host "Coverage: T1 $($t1.Count) named / T2 $($t2.Count) behavioural / T3 $($t3.Count) uncovered of $($registry.Count)"
if ($script:Errors.Count -gt 0) {
    Write-Host "FAILED: $($script:Errors.Count) error(s)" -ForegroundColor Red
    foreach ($e in $script:Errors) { Write-Host "  - $e" -ForegroundColor Red }
    Write-Host '══════════════════════════════════════════════════' -ForegroundColor Red
    exit 1
}
Write-Host "PASSED ($($script:Warnings.Count) warning(s))" -ForegroundColor Green
foreach ($w in $script:Warnings) { Write-Host "  ! $w" -ForegroundColor Yellow }
Write-Host '══════════════════════════════════════════════════' -ForegroundColor Cyan
exit 0
