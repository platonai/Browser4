#requires -Version 7
<#
    Measure the FIXED per-request overhead of the coding agent:

      1. the system prompt a coding task gets (CliAgentPrompt.role + hygiene + policy block);
      2. the native tool-spec payload of its initial tool set (what the model calls
         "tool disclosure"), plus the text-mode rendering for comparison.

    Why a script: the overhead is spread over hand-written Kotlin string literals and
    `ToolSpec` declarations, so nothing in the build fails when a description grows by
    500 characters — yet every round of every run pays for it. This script turns the
    literals into two numbers that can be compared across commits.

    It reads SOURCE (it never starts the backend), so it works in CI and offline. The
    numbers are character counts plus a token estimate (see -CharsPerToken); for the
    authoritative token count, read the real usage the provider returns, or the prompt
    dump written by AgentConfig.logInferenceToFile (onBeforeGenerate).

    Baseline + methodology: docs-dev/copilot/20261115-prompt-tool-spec-token-baseline.md

    Usage:
      pwsh bin/measure-prompt-tokens.ps1                 # human table
      pwsh bin/measure-prompt-tokens.ps1 -Json           # machine-readable object
      pwsh bin/measure-prompt-tokens.ps1 -Check          # exit 1 when a number drifted
      pwsh bin/measure-prompt-tokens.ps1 -Check -Tolerance 0.05

    Exit codes: 0 = measured (and, with -Check, within tolerance); 1 = drift / parse failure.
#>
[CmdletBinding()]
param(
    # Emit one JSON object instead of the human table.
    [switch]$Json,

    # Compare the measurements with the recorded baseline; exit 1 on drift.
    [switch]$Check,

    # Allowed relative drift under -Check (0.10 = 10%).
    [double]$Tolerance = 0.10,

    # Repository root; defaults to this script's parent directory.
    [string]$RepoRoot,

    # Token estimate divisor. English prose tokenizes at ~4.0 chars/token; JSON schema
    # with punctuation and long identifiers at ~3.5. Both are reported.
    [double[]]$CharsPerToken = @(4.0, 3.5)
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not $RepoRoot) { $RepoRoot = Split-Path -Parent $PSScriptRoot }
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path

$Paths = @{
    CodingExecutor = 'browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/CodingToolExecutor.kt'
    B4Executor     = 'browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/B4CliToolExecutor.kt'
    SystemExecutor = 'browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/SystemToolExecutor.kt'
    MemoryExecutor = 'browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/memory/MemoryToolExecutor.kt'
    SkillExecutor  = 'browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/skills/tools/SkillToolExecutor.kt'
    Disclosure     = 'browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/inference/chat/ToolDisclosure.kt'
    Prompt         = 'browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/agents/CliAgentPrompt.kt'
    Agent          = 'browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/agents/RobustBrowserAgent.kt'
    Quickstart     = 'skills/browser4-cli/references/quickstart.md'
}

# The initial tool sets, mirrored from RobustBrowserAgent.kt. CLI_CORE_DOMAINS
# (`b4`, `system`, `memory`) is taken wholesale — every method of those three domains is
# measured — so only the two coding allow-lists have to be listed here. Keep them in sync
# when RobustBrowserAgent changes: the counts are asserted below, so a silent divergence
# shows up as a failing -Check rather than a smaller number nobody notices.
$CodingTaskCodingMethods = @(
    'shell', 'shellOutput', 'shellStatus',
    'read', 'readLines', 'readFiles', 'write', 'append', 'replace', 'insertAfter', 'editLines',
    'delete', 'mkdir', 'listDir', 'glob', 'grep', 'stat', 'diff', 'changeSummary',
    'validate', 'verify', 'mvnBuild', 'diagnostics', 'workspaceRoot', 'devTask'
)

$BrowsingTaskCodingMethods = @(
    'shell',
    'read', 'readLines', 'write', 'append', 'replace',
    'listDir', 'glob', 'grep',
    'stat', 'mkdir', 'delete',
    'diff', 'changeSummary', 'workspaceRoot'
)

# The recorded baseline (characters). -Check compares against these.
# Measured 2026-11-20 on 4.16.x; see the doc for the methodology and for what to do
# when a change here is intentional.
$Baseline = @{
    CodingPromptChars           = 5133   # role + hygiene + workspace-boundary block
    CodingPromptRestrictedChars = 6063   # role + hygiene + permissions block (write preset)
    CodingTaskToolChars         = 22547  # 39 tools + the two disclosure meta tools
    BrowsingTaskToolChars       = 16528  # 29 tools + the two disclosure meta tools
    CodingTaskToolCount         = 39
    BrowsingTaskToolCount       = 29
}

# ---------------------------------------------------------------- helpers

function Get-SourceText([string]$RelPath) {
    $full = Join-Path $RepoRoot $RelPath
    if (-not (Test-Path -LiteralPath $full)) { throw "missing source file: $RelPath" }
    return (Get-Content -LiteralPath $full -Raw)
}

function Get-Lines([string]$RelPath) {
    $full = Join-Path $RepoRoot $RelPath
    if (-not (Test-Path -LiteralPath $full)) { throw "missing source file: $RelPath" }
    return (Get-Content -LiteralPath $full)
}

# Sum of the *content* of every double-quoted literal inside $Text.
function Get-QuotedChars([string]$Text) {
    $sum = 0
    foreach ($m in [regex]::Matches($Text, '"((?:\\.|[^"\\])*)"')) { $sum += $m.Groups[1].Value.Length }
    return $sum
}

# Content of the triple-quoted block that encloses the Nth occurrence of $Anchor.
#
# Anchors are prose fragments, so the same sentence can also appear in a KDoc comment
# (it does: the browsing role is quoted in the CliAgentPrompt class comment). An
# occurrence that is not inside a raw string is skipped instead of failing the run.
function Get-TripleQuotedBlock([string]$Text, [string]$Anchor, [int]$Occurrence = -1) {
    $searchFrom = 0
    $index = 0
    while ($true) {
        $idx = $Text.IndexOf($Anchor, $searchFrom, [StringComparison]::Ordinal)
        if ($idx -lt 0) { throw "anchor not found: '$Anchor' (occurrence $Occurrence)" }
        $searchFrom = $idx + 1

        $candidate = if ($Occurrence -lt 0) { $true } else { $index -eq $Occurrence }
        $index++

        $start = $Text.LastIndexOf('"""', $idx, [StringComparison]::Ordinal)
        $end = $Text.IndexOf('"""', $idx, [StringComparison]::Ordinal)
        $resolvable = ($start -ge 0 -and $end -ge 0)

        if ($resolvable) {
            # Inside a block comment when the last /* is later than the last */.
            $lastOpen = $Text.LastIndexOf('/*', $idx, [StringComparison]::Ordinal)
            $lastClose = $Text.LastIndexOf('*/', $idx, [StringComparison]::Ordinal)
            $resolvable = ($lastOpen -le $lastClose)
        }

        if (-not $resolvable) {
            if ($Occurrence -ge 0) { throw "occurrence $Occurrence of '$Anchor' is not inside a raw string" }
            continue
        }
        if (-not $candidate) { continue }

        return $Text.Substring($start + 3, $end - $start - 3)
    }
}

# Trim-intent a Kotlin raw string the way `trimIndent()` would (good enough for counts).
function Format-RawString([string]$Raw) {
    $lines = $Raw -split "`r?`n"
    $indent = [int]::MaxValue
    foreach ($line in $lines) {
        if ($line.Trim().Length -eq 0) { continue }
        $lead = $line.Length - $line.TrimStart().Length
        if ($lead -lt $indent) { $indent = $lead }
    }
    if ($indent -eq [int]::MaxValue) { $indent = 0 }
    $out = foreach ($line in $lines) {
        if ($line.Length -ge $indent) { $line.Substring($indent) } else { $line.TrimStart() }
    }
    return (($out -join "`n").Trim())
}

# The literal text of the Kotlin statement that starts at $Anchor and ends at $EndAnchor.
function Get-StatementLiterals([string]$Text, [string]$Anchor, [string]$EndAnchor) {
    $start = $Text.IndexOf($Anchor, [StringComparison]::Ordinal)
    if ($start -lt 0) { throw "anchor not found: '$Anchor'" }
    $end = $Text.IndexOf($EndAnchor, $start, [StringComparison]::Ordinal)
    if ($end -lt 0) { throw "end anchor not found: '$EndAnchor'" }
    return $Text.Substring($start, $end - $start + $EndAnchor.Length)
}

# ---------------------------------------------------------------- tool specs

<#
    Parse every `toolSpec["name"] = ToolSpec(...)` block of one executor.

    Only what the native schema actually carries is kept: the method name, the
    description (the converter appends `help` to it), the return type, and the
    declared arguments. Argument descriptions are deliberately NOT counted: the
    converter computes them and then drops them (see ToolSpecificationConverter),
    so they never reach the model.
#>
function Get-ToolSpecs([string]$RelPath) {
    $lines = Get-Lines $RelPath
    $starts = [System.Collections.Generic.List[object]]::new()
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match 'toolSpec\["([A-Za-z0-9_]+)"\]\s*=\s*ToolSpec\(') {
            $starts.Add([pscustomobject]@{ Method = $Matches[1]; Start = $i })
        }
    }
    if ($starts.Count -eq 0) { throw "no toolSpec blocks found in $RelPath" }

    $specs = [System.Collections.Generic.List[object]]::new()
    for ($k = 0; $k -lt $starts.Count; $k++) {
        $begin = $starts[$k].Start
        $limit = if ($k + 1 -lt $starts.Count) { $starts[$k + 1].Start - 1 } else { $lines.Count - 1 }
        $end = $limit
        for ($i = $begin; $i -le $limit; $i++) {
            if ($lines[$i] -match '^\s{8}\)\s*$') { $end = $i; break }
        }
        $block = ($lines[$begin..$end] -join "`n")

        $args = [System.Collections.Generic.List[object]]::new()
        foreach ($m in [regex]::Matches($block, 'ToolSpec\.Arg\(\s*"([^"]+)"\s*,\s*"([^"]*)"(\s*,\s*"([^"]*)")?')) {
            $args.Add([pscustomobject]@{
                    Name       = $m.Groups[1].Value
                    HasDefault = $m.Groups[3].Success
                })
        }

        # Everything the converter appends to the description lives after `description =`.
        $descChars = 0
        $descIdx = $block.IndexOf('description =', [StringComparison]::Ordinal)
        if ($descIdx -ge 0) {
            $tail = $block.Substring($descIdx) -replace '(?s)"""(.*?)"""', '$1'
            $descChars = Get-QuotedChars $tail
        }

        $ret = ''
        if ($block -match 'returnType\s*=\s*"([^"]*)"') { $ret = $Matches[1] }

        $specs.Add([pscustomobject]@{
                Method  = $starts[$k].Method
                Desc    = $descChars
                Return  = $ret
                Args    = $args.ToArray()
            })
    }
    return $specs.ToArray()
}

<#
    The two always-on disclosure meta tools (`system.listTools`, `system.exposeTools`).

    They are declared in ToolDisclosure.metaSpecs(), not in an executor, so they are
    parsed separately — otherwise the initial payload would be under-counted by the
    pair the loop adds to every single request.
#>
function Get-MetaSpecs {
    $text = Get-SourceText $Paths.Disclosure
    $specs = @()
    foreach ($method in @('listTools', 'exposeTools')) {
        $marker = "method = `"$method`""
        $idx = $text.IndexOf($marker, [StringComparison]::Ordinal)
        if ($idx -lt 0) { throw "meta tool '$method' not found in ToolDisclosure.kt" }
        # The ToolSpec(...) block closes on its own `),` line; match that line, not the
        # first `)` (the arguments list closes on the same line as its argument).
        $close = [regex]::Match($text.Substring($idx), '(?m)^\s{12}\),\s*$')
        if (-not $close.Success) { throw "could not find the end of the '$method' ToolSpec block" }
        $block = $text.Substring($idx, $close.Index)
        $args = @()
        foreach ($m in [regex]::Matches($block, 'ToolSpec\.Arg\(\s*"([^"]+)"\s*,\s*"([^"]*)"(\s*,\s*"([^"]*)")?')) {
            $args += [pscustomobject]@{ Name = $m.Groups[1].Value; HasDefault = $m.Groups[3].Success }
        }
        $descChars = 0
        $descIdx = $block.IndexOf('description =', [StringComparison]::Ordinal)
        if ($descIdx -ge 0) { $descChars = Get-QuotedChars $block.Substring($descIdx) }
        $specs += [pscustomobject]@{ Method = $method; Desc = $descChars; Return = 'String'; Args = $args }
    }
    return $specs
}

<#
    Characters one tool costs on the wire, OpenAI function-calling shape:

      {"type":"function","function":{"name":..,"description":..,
       "parameters":{"type":"object","properties":{..},"required":[..]}}}

    The constants below are the fixed JSON scaffolding measured on that shape; the
    per-argument cost is the property name plus its `{"type":"..."}` wrapper.
#>
function Measure-SchemaChars([object]$Spec) {
    $chars = 105 + $Spec.Method.Length + $Spec.Desc
    if ($Spec.Return -and $Spec.Return -ne 'Unit') { $chars += 9 + $Spec.Return.Length }   # "Returns: X"
    $required = @()
    foreach ($arg in $Spec.Args) {
        $chars += $arg.Name.Length + 24
        if (-not $arg.HasDefault) { $required += $arg.Name }
    }
    if ($required.Count -gt 0) {
        $chars += 16
        foreach ($name in $required) { $chars += $name.Length + 3 }
    }
    return $chars
}

function Measure-Set([object[]]$Specs, [string[]]$Methods) {
    $selected = $Specs | Where-Object { $Methods -contains $_.Method }
    $chars = 0
    foreach ($spec in $selected) { $chars += Measure-SchemaChars $spec }
    return [pscustomobject]@{ Count = @($selected).Count; Chars = $chars }
}

# ---------------------------------------------------------------- prompt blocks

function Get-PromptMeasurements {
    $text = Get-SourceText $Paths.Prompt

    # --- coding role: the template plus the variant this host actually emits -----
    $roleTemplate = Format-RawString (Get-TripleQuotedBlock $text 'You are a coding agent working on a Browser4 checkout')
    $treeMirror = Format-RawString (Get-TripleQuotedBlock $text 'It mirrors the repository for this run.')
    $treeWorktree = Format-RawString (Get-TripleQuotedBlock $text 'It is an isolated git worktree of the checkout')
    $shellWindows = Format-RawString (Get-StatementLiterals $text 'runs through cmd.exe' 'prefix."')
    $shellPosix = Format-RawString (Get-StatementLiterals $text 'coding.shell runs through sh -c' 'prefix."')

    # `$tree` / `$shell` are placeholders in the template: swap them for the variant.
    $roleTemplateStripped = $roleTemplate.Replace('$tree', '').Replace('$shell', '')

    $codingRoleWindows = $roleTemplateStripped.Length + $treeMirror.Length + $shellWindows.Length
    $codingRolePosix = $roleTemplateStripped.Length + $treeMirror.Length + $shellPosix.Length
    $codingRoleWorktree = $roleTemplateStripped.Length + $treeWorktree.Length + $shellWindows.Length

    # --- coding hygiene: the first `### Context hygiene` block -------------------
    $hygieneCoding = Format-RawString (Get-TripleQuotedBlock $text '### Context hygiene' 0)
    $hygieneBrowsing = Format-RawString (Get-TripleQuotedBlock $text '### Context hygiene' 1)

    # --- policy blocks (both are built with appendLine, so count their literals) --
    # The anchors carry the `appendLine(` call so they match the STATEMENT, not the
    # identical heading quoted inside the function's KDoc comment.
    $wsBoundaryBlock = Get-StatementLiterals $text 'appendLine("### Workspace boundary")' '.trimEnd()'
    $wsBoundaryChars = Get-QuotedChars $wsBoundaryBlock
    $permsBlock = Get-StatementLiterals $text 'appendLine("### Permissions")' '.trimEnd()'
    $permsChars = Get-QuotedChars $permsBlock

    # --- browsing-only pieces (for the contrast) --------------------------------
    $browseRole = Format-RawString (Get-TripleQuotedBlock $text 'You are a browser automation agent')
    $agentText = Get-SourceText $Paths.Agent
    $moreDetail = Format-RawString (Get-TripleQuotedBlock $agentText 'Need more detail? Call system.skillDoc')
    $quickstartChars = (Get-SourceText $Paths.Quickstart).Length

    return [pscustomobject]@{
        CodingRoleChars         = $codingRoleWindows
        CodingRoleCharsPosix    = $codingRolePosix
        CodingRoleCharsWorktree = $codingRoleWorktree
        TreeMirrorChars         = $treeMirror.Length
        TreeWorktreeChars       = $treeWorktree.Length
        ShellWindowsChars       = $shellWindows.Length
        ShellPosixChars         = $shellPosix.Length
        HygieneCodingChars      = $hygieneCoding.Length
        WorkspaceBoundaryChars  = $wsBoundaryChars
        PermissionsBlockChars   = $permsChars
        BrowseRoleChars         = $browseRole.Length
        BrowseHygieneChars      = $hygieneBrowsing.Length
        MoreDetailChars         = $moreDetail.Length
        QuickstartChars         = $quickstartChars
    }
}

# ---------------------------------------------------------------- measure

function Measure-All {
    $coding = Get-ToolSpecs $Paths.CodingExecutor
    $b4 = Get-ToolSpecs $Paths.B4Executor
    $system = Get-ToolSpecs $Paths.SystemExecutor
    $memory = Get-ToolSpecs $Paths.MemoryExecutor
    $skill = Get-ToolSpecs $Paths.SkillExecutor
    $meta = Get-MetaSpecs

    $metaChars = 0
    foreach ($spec in $meta) { $metaChars += Measure-SchemaChars $spec }

    $codingTask = Measure-Set $coding $CodingTaskCodingMethods
    $browsingTask = Measure-Set $coding $BrowsingTaskCodingMethods
    $b4Set = Measure-Set $b4 $b4.Method
    $systemSet = Measure-Set $system $system.Method
    $memorySet = Measure-Set $memory $memory.Method
    $skillSet = Measure-Set $skill $skill.Method

    if ($codingTask.Count -ne $CodingTaskCodingMethods.Count) {
        throw "coding allow-list names $($CodingTaskCodingMethods.Count) methods but only $($codingTask.Count) exist in CodingToolExecutor"
    }

    $prompt = Get-PromptMeasurements

    # System prompt variants: (role + hygiene) + the policy block that applies.
    $codingPromptDefault = $prompt.CodingRoleChars + 2 + $prompt.HygieneCodingChars + $prompt.WorkspaceBoundaryChars
    $codingPromptRestricted = $prompt.CodingRoleChars + 2 + $prompt.HygieneCodingChars + $prompt.PermissionsBlockChars
    $codingPromptExternal = $prompt.CodingRoleChars + 2 + $prompt.HygieneCodingChars
    $browsingPrompt = $prompt.BrowseRoleChars + 2 + $prompt.MoreDetailChars + $prompt.QuickstartChars +
        2 + $prompt.BrowseHygieneChars + $prompt.WorkspaceBoundaryChars

    $codingTaskToolChars = $codingTask.Chars + $b4Set.Chars + $systemSet.Chars + $memorySet.Chars + $metaChars
    $browsingTaskToolChars = $browsingTask.Chars + $b4Set.Chars + $systemSet.Chars + $memorySet.Chars + $metaChars
    $fullCliRegistryChars = $codingSetChars = 0
    foreach ($spec in $coding) { $codingSetChars += Measure-SchemaChars $spec }
    $fullCliRegistryChars = $codingSetChars + $b4Set.Chars + $systemSet.Chars + $memorySet.Chars + $skillSet.Chars

    return [pscustomobject]@{
        GeneratedAt                  = (Get-Date).ToString('yyyy-MM-dd')
        RepoRoot                     = $RepoRoot
        CodingTaskToolCount          = $codingTask.Count + $b4Set.Count + $systemSet.Count + $memorySet.Count
        CodingTaskToolChars          = $codingTaskToolChars
        BrowsingTaskToolCount        = $browsingTask.Count + $b4Set.Count + $systemSet.Count + $memorySet.Count
        BrowsingTaskToolChars        = $browsingTaskToolChars
        MetaToolChars                = $metaChars
        CodingTaskSchemaBreakdown    = [pscustomobject]@{
            coding = $codingTask.Chars; b4 = $b4Set.Chars
            system = $systemSet.Chars; memory = $memorySet.Chars; meta = $metaChars
        }
        FullCliRegistryChars         = $fullCliRegistryChars
        FullCliRegistryToolCount     = $coding.Count + $b4Set.Count + $systemSet.Count + $memorySet.Count + $skillSet.Count
        AllCodingSpecChars           = $codingSetChars
        CodingSpecCount              = $coding.Count
        CodingPromptChars            = $codingPromptDefault
        CodingPromptRestrictedChars  = $codingPromptRestricted
        CodingPromptExternalChars    = $codingPromptExternal
        BrowsingPromptChars          = $browsingPrompt
        PromptBreakdown              = $prompt
    }
}

function ConvertTo-Tokens([int]$Chars, [double]$PerToken) { return [int][math]::Round($Chars / $PerToken) }

# ---------------------------------------------------------------- report

try {
    $result = Measure-All
} catch {
    Write-Error "measurement failed: $($_.Exception.Message)"
    exit 1
}

$tokens = @{}
foreach ($ratio in $CharsPerToken) {
    $key = "at$($ratio.ToString([System.Globalization.CultureInfo]::InvariantCulture))"
    $tokens[$key] = [pscustomobject]@{
        codingPromptDefault    = ConvertTo-Tokens $result.CodingPromptChars $ratio
        codingPromptRestricted = ConvertTo-Tokens $result.CodingPromptRestrictedChars $ratio
        browsingPrompt         = ConvertTo-Tokens $result.BrowsingPromptChars $ratio
        codingTaskTools        = ConvertTo-Tokens $result.CodingTaskToolChars $ratio
        browsingTaskTools      = ConvertTo-Tokens $result.BrowsingTaskToolChars $ratio
        fullCliRegistry        = ConvertTo-Tokens $result.FullCliRegistryChars $ratio
        fixedOverhead          = ConvertTo-Tokens ($result.CodingPromptChars + $result.CodingTaskToolChars) $ratio
    }
}

$drift = @()
if ($Check) {
    # NOTE: the loop variable must not be named $check — PowerShell names are
    # case-insensitive, so it would collide with the [switch]$Check parameter and
    # fail to hold a hashtable.
    $probes = @(
        @{ Name = 'CodingPromptChars'; Actual = $result.CodingPromptChars },
        @{ Name = 'CodingPromptRestrictedChars'; Actual = $result.CodingPromptRestrictedChars },
        @{ Name = 'CodingTaskToolChars'; Actual = $result.CodingTaskToolChars },
        @{ Name = 'BrowsingTaskToolChars'; Actual = $result.BrowsingTaskToolChars },
        @{ Name = 'CodingTaskToolCount'; Actual = $result.CodingTaskToolCount },
        @{ Name = 'BrowsingTaskToolCount'; Actual = $result.BrowsingTaskToolCount }
    )
    foreach ($probe in $probes) {
        $expected = [double]$Baseline[$probe.Name]
        $actual = [double]$probe.Actual
        $delta = if ($expected -eq 0) { 0 } else { ($actual - $expected) / $expected }
        if ([math]::Abs($delta) -gt $Tolerance) {
            $drift += [pscustomobject]@{
                Metric = $probe.Name; Baseline = [int]$expected; Actual = [int]$actual
                DeltaPercent = [math]::Round($delta * 100, 1)
            }
        }
    }
}

if ($Json) {
    $payload = $result | ConvertTo-Json -Depth 6
    if ($Check) {
        # `-Json -Check` stays machine-readable: drift travels inside the object.
        $obj = $result | ConvertTo-Json -Depth 6 | ConvertFrom-Json
        $obj | Add-Member -NotePropertyName Check -NotePropertyValue ([pscustomobject]@{
                Passed     = ($drift.Count -eq 0)
                Tolerance  = $Tolerance
                Drift      = @($drift)
                Baseline   = $Baseline
            }) -Force
        $payload = $obj | ConvertTo-Json -Depth 8
    }
    $payload
    if ($drift.Count -gt 0) { exit 1 } else { exit 0 }
}

Write-Host ''
Write-Host 'Browser4 agent — fixed per-request overhead (source-measured)' -ForegroundColor Cyan
Write-Host ("repo: {0}" -f $result.RepoRoot) -ForegroundColor DarkGray
Write-Host ''

$rows = @(
    [pscustomobject]@{ Item = 'system prompt, coding (full + no external access)'; Chars = $result.CodingPromptChars }
    [pscustomobject]@{ Item = 'system prompt, coding (restricted preset, e.g. write)'; Chars = $result.CodingPromptRestrictedChars }
    [pscustomobject]@{ Item = 'system prompt, browsing (resident quickstart.md)'; Chars = $result.BrowsingPromptChars }
    [pscustomobject]@{ Item = ("tool disclosure, coding task ({0} tools + 2 meta)" -f $result.CodingTaskToolCount); Chars = $result.CodingTaskToolChars }
    [pscustomobject]@{ Item = ("tool disclosure, browsing task ({0} tools + 2 meta)" -f $result.BrowsingTaskToolCount); Chars = $result.BrowsingTaskToolChars }
    [pscustomobject]@{ Item = ("full CLI registry, no disclosure ({0} tools)" -f $result.FullCliRegistryToolCount); Chars = $result.FullCliRegistryChars }
    [pscustomobject]@{ Item = 'FIXED OVERHEAD, coding (prompt + tools)'; Chars = ($result.CodingPromptChars + $result.CodingTaskToolChars) }
)

$ratio = $CharsPerToken[0]
$rows | Select-Object Item,
@{ n = 'Chars'; e = { '{0:N0}' -f $_.Chars } },
@{ n = "tokens @$ratio"; e = { '{0:N0}' -f (ConvertTo-Tokens $_.Chars $ratio) } } |
Format-Table -AutoSize | Out-String -Width 120 | Write-Host

Write-Host 'Breakdown' -ForegroundColor Cyan
@(
    [pscustomobject]@{ Piece = 'role (coding)'; Chars = $result.PromptBreakdown.CodingRoleChars }
    [pscustomobject]@{ Piece = 'hygiene (coding)'; Chars = $result.PromptBreakdown.HygieneCodingChars }
    [pscustomobject]@{ Piece = 'workspace-boundary block'; Chars = $result.PromptBreakdown.WorkspaceBoundaryChars }
    [pscustomobject]@{ Piece = 'permissions block (restricted preset)'; Chars = $result.PromptBreakdown.PermissionsBlockChars }
    [pscustomobject]@{ Piece = 'quickstart.md (browsing only)'; Chars = $result.PromptBreakdown.QuickstartChars }
) | Format-Table -AutoSize | Out-String -Width 100 | Write-Host

@(
    [pscustomobject]@{ Domain = 'coding.* (25 whitelisted)'; Chars = $result.CodingTaskSchemaBreakdown.coding }
    [pscustomobject]@{ Domain = 'b4.*'; Chars = $result.CodingTaskSchemaBreakdown.b4 }
    [pscustomobject]@{ Domain = 'system.*'; Chars = $result.CodingTaskSchemaBreakdown.system }
    [pscustomobject]@{ Domain = 'memory.*'; Chars = $result.CodingTaskSchemaBreakdown.memory }
    [pscustomobject]@{ Domain = 'listTools/exposeTools'; Chars = $result.CodingTaskSchemaBreakdown.meta }
) | Format-Table -AutoSize | Out-String -Width 100 | Write-Host

Write-Host ("all {0} coding specs (full domain): {1:N0} chars" -f $result.CodingSpecCount, $result.AllCodingSpecChars) -ForegroundColor DarkGray
Write-Host ("prompt, coding, full + external access: {0:N0} chars" -f $result.CodingPromptExternalChars) -ForegroundColor DarkGray
if ($CharsPerToken.Count -gt 1) {
    $second = $CharsPerToken[1]
    Write-Host ("tokens above use {0} chars/token; JSON-dense schema tokenizes nearer {1} (fixed overhead -> ~{2:N0})." -f `
            $CharsPerToken[0], $second, (ConvertTo-Tokens ($result.CodingPromptChars + $result.CodingTaskToolChars) $second)) -ForegroundColor DarkGray
}
Write-Host ''

if ($Check) {
    if ($drift.Count -eq 0) {
        Write-Host ("OK: every metric is within {0:P0} of the recorded baseline." -f $Tolerance) -ForegroundColor Green
        exit 0
    }
    Write-Host ("DRIFT: {0} metric(s) moved more than {1:P0}:" -f $drift.Count, $Tolerance) -ForegroundColor Yellow
    $drift | Format-Table -AutoSize | Out-String -Width 100 | Write-Host
    Write-Host 'If the change is intended, update $Baseline here and the table in' -ForegroundColor Yellow
    Write-Host 'docs-dev/copilot/20261115-prompt-tool-spec-token-baseline.md' -ForegroundColor Yellow
    exit 1
}

Write-Host 'Tip: -Check compares against the baseline; -Json emits a machine-readable object.' -ForegroundColor DarkGray
exit 0
