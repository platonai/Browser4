#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Shared prompt construction utilities for coworker worker scripts.

.DESCRIPTION
    Provides composable prompt-building functions so all worker scripts produce
    consistent, backend-agnostic agent instructions. Eliminates the duplicated
    here-string prompt templates across the codebase.
#>

# ── Backend detection ────────────────────────────────────────────────────────
$workerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$configPath = Join-Path (Split-Path -Parent $workerDir) 'config.ps1'
if (Test-Path $configPath) { . $configPath }

function Get-AgentBackend {
    if ($CLAUDE) { return 'claude' }
    return 'copilot'
}

# ── Prompt fragments ─────────────────────────────────────────────────────────

function New-AgentSystemPrompt {
    param(
        [Parameter(Mandatory)] [string]$Role,
        [string]$TaskDescription
    )

    $lines = @(
        "You are an AI assistant $Role.",
        $TaskDescription
        '',
        'ABSOLUTE PATH INSTRUCTIONS:',
        '- All file paths in this prompt are ABSOLUTE paths.',
        '- Use them directly. Do NOT prepend any directory or modify them.',
        '- Verify the file path before writing.',
        ''
    )
    return $lines -join "`n"
}

function Add-ConstraintsBlock {
    param(
        [string[]]$AdditionalConstraints = @()
    )
    $defaults = @(
        'CONSTRAINTS:',
        '- Use English only.',
        '- Be concise but thorough.',
        '- Synthesize information; do NOT just list raw data.',
        '- Write directly to the specified absolute path.',
        '- Do NOT include conversational framing, explanations, or code fences in output files.'
    )
    $all = $defaults + $AdditionalConstraints
    return ($all -join "`n")
}

function Add-FileWriteInstruction {
    param(
        [Parameter(Mandatory)] [string]$FilePath,
        [ValidateSet('Create', 'Overwrite', 'Append', 'Merge')]
        [string]$Mode = 'Create'
    )

    $operation = switch ($Mode) {
        'Create'    { "Write the content to the file at ABSOLUTE path: $FilePath. Create the file if it does not exist." }
        'Overwrite' { "Write the content to the file at ABSOLUTE path: $FilePath. Overwrite it if it exists (I have already backed it up)." }
        'Append'    { "Read the existing content of the file at ABSOLUTE path: $FilePath. Append new content to it. Do NOT overwrite existing content." }
        'Merge'     { "Read the existing content of the file at ABSOLUTE path: $FilePath. Merge the new information with the existing content: append new items, consolidate similar points, and update sections that have new insights. Do NOT simply overwrite." }
    }
    return $operation
}

# ── Memory specification schemas ─────────────────────────────────────────────

$script:MemorySpecs = @{
    Daily = @'
### Tasks Executed
- ...

### Execution Quality Review
- What worked well
- What was inefficient

### Issues Encountered
- ...

### Root Cause Analysis
- ...

### Process Improvement Insight
- At least one concrete improvement for future execution
'@

    Monthly = @'
### Work Themes
- Major areas of focus this month

### Recurring Issues
- Problems that happened multiple times

### Structural Bottlenecks
- Process or technical limitations slowing progress

### Efficiency Trend
- Qualitative assessment of speed/quality over the month

### System Adjustments Proposed
- Changes to tools/workflow based on this month's experience
'@

    Yearly = @'
### Project State Evolution
- High-level changes in project scope/maturity

### Major Achievements
- Key milestones reached

### Major Failures
- Significant setbacks and lessons

### Structural Problems (Solved / Unsolved)
- Persistent issues

### Capability Upgrades
- New skills/tools acquired

### Strategic Risks
- Potential future threats

### Project Trajectory Forecast
- Where the project is heading

### Three Immediate Strategic Actions
- High-level next steps for next year
'@

    Global = @'
### Global Project Evolution
- How the project has changed over time

### Cumulative Achievements
- Major milestones across all years

### Persistent Structural Issues
- Problems that span multiple years

### Capability Trajectory
- Skills and tools acquired over time

### Strategic Outlook
- Long-term risks and opportunities

### Key Lessons Learned
- The most important insights from the entire project history
'@
}

function Get-MemorySpecification {
    param(
        [Parameter(Mandatory)]
        [ValidateSet('Daily', 'Monthly', 'Yearly', 'Global')]
        [string]$Level
    )
    return $script:MemorySpecs[$Level]
}

function New-MemoryGenerationPrompt {
    param(
        [Parameter(Mandatory)]
        [ValidateSet('Daily', 'Monthly', 'Yearly', 'Global')]
        [string]$Level,

        [Parameter(Mandatory)]
        [string]$TargetFile,

        [Parameter(Mandatory)]
        [string]$SourceContent,

        [string]$DateLabel = '',

        [ValidateSet('Create', 'Merge')]
        [string]$Mode = 'Create',

        [string[]]$ExtraConstraints = @()
    )

    $spec = Get-MemorySpecification -Level $Level
    $fileInstruction = Add-FileWriteInstruction -FilePath $TargetFile -Mode $(if ($Mode -eq 'Merge') { 'Merge' } else { 'Overwrite' })
    $systemPrompt = New-AgentSystemPrompt `
        -Role "helping to generate a $($Level.ToUpper()) memory summary for a developer coworker" `
        -TaskDescription "Based on the provided source content, generate the content for the $($Level.ToLower()) memory file."

    $header = $null
    switch ($Level) {
        'Daily'   { $header = "# MEMORY - $DateLabel`n## Daily Memory - $DateLabel" }
        'Monthly' { $header = "# MEMORY - $DateLabel`n## Monthly Memory - $DateLabel" }
        'Yearly'  { $header = "# MEMORY - $DateLabel`n## Annual Strategic Review - $DateLabel" }
        'Global'  { $header = "# MEMORY`n## Global Project Memory" }
    }

    $constraints = Add-ConstraintsBlock -AdditionalConstraints (@(
        $fileInstruction,
        "Use the following structure for the output:`n$spec"
    ) + $ExtraConstraints)

    return @"
$systemPrompt

SPECIFICATION:
$header

$spec

$constraints

SOURCE CONTENT:
$SourceContent
"@
}

# ── Refinement prompt ────────────────────────────────────────────────────────

function New-RefinementPrompt {
    param(
        [Parameter(Mandatory)]
        [string]$FilePath,

        [Parameter(Mandatory)]
        [string]$Content,

        [string]$Audience = 'Technical team members',

        [string]$DomainContext = ''
    )

    $contextBlock = if ($DomainContext) {
        "DOMAIN CONTEXT: $DomainContext`n`n"
    } else { '' }

    $badExamples = @(
        'BAD (includes commentary): "Here is the refined draft: ..."',
        'BAD (includes code fences): ```markdown ... ```',
        'BAD (includes explanation): "I made the following changes: ..."',
        "GOOD (content only): Starts directly with the document's title or first heading"
    )

    return @"
Refine the following draft for clarity, coherence, and relevance.

Target audience: $Audience
${contextBlock}
IMPORTANT — OUTPUT FORMAT:
- Return ONLY the complete refined document.
- Do NOT include: explanations, commentary, code fences, "Here is", "Certainly", "I've refined", etc.
- The output must start directly with the document content.
- Examples of what NOT to do:

$($badExamples -join "`n")

Source file: $FilePath

--- BEGIN DRAFT ---
$Content
--- END DRAFT ---
"@
}
