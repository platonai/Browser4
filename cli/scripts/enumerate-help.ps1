#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Enumerates every browser4-cli command and subcommand, prints help
    information, and saves output to categorized files.
.DESCRIPTION
    This script discovers all browser4-cli commands (including hidden ones),
    generates detailed help for each, and organizes the output into:
      - A top-level help overview
      - One file per command (with numbered prefixes for ordering)
      - Prefix group overviews (swarm, agent, domsnapshot, crawl, snapshot)
      - An INDEX.md for easy navigation

    Output is saved to: <repo-root>/cli/help-output/
.PARAMETER Binary
    Path to the browser4-cli binary. Defaults to the release build in the
    workspace: cli/browser4-cli/target/release/browser4-cli.exe
.PARAMETER OutDir
    Directory for help output. Defaults to cli/help-output/ under the repo root.
.EXAMPLE
    .\enumerate-help.ps1
    .\enumerate-help.ps1 -Binary "C:\tools\browser4-cli.exe" -OutDir ".\docs\cli-help"
#>

param(
    [string]$Binary,
    [string]$OutDir
)

$ErrorActionPreference = "Stop"

# ---- Resolve paths ----------------------------------------------------------
$RepoRoot = Resolve-Path "$PSScriptRoot\..\.."

if (-not $Binary) {
    $Binary = Join-Path $RepoRoot "cli\browser4-cli\target\release\browser4-cli.exe"
}
if (-not $OutDir) {
    $OutDir = Join-Path $RepoRoot "cli\help-output"
}

if (-not (Test-Path $Binary)) {
    Write-Error "browser4-cli binary not found at: $Binary"
    Write-Host "Build it first: cd cli/browser4-cli && cargo build --release"
    exit 1
}

# Ensure output directory exists
$null = New-Item -ItemType Directory -Force -Path $OutDir

# Normalize binary path for reliable invocation
$Binary = Resolve-Path $Binary

Write-Host "browser4-cli binary : $Binary"
Write-Host "Output directory   : $OutDir"
Write-Host ""

# ---- Helper: invoke the CLI and capture output ------------------------------
function Invoke-Cli {
    param([string[]]$Arguments)
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $Binary
    $psi.Arguments = $Arguments -join ' '
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8

    $proc = [System.Diagnostics.Process]::new()
    $proc.StartInfo = $psi
    $null = $proc.Start()
    $stdout = $proc.StandardOutput.ReadToEnd()
    $stderr = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit(10000) | Out-Null

    # Combine: stdout first, then stderr (stderr carries errors / tips)
    $result = $stdout
    if ($stderr.Trim().Length -gt 0) {
        if ($result.Length -gt 0) { $result += "`n" }
        $result += $stderr
    }
    return $result.TrimEnd()
}

# ---- Command definitions ----------------------------------------------------

# Each entry: @{ Name = "<internal-name>"; HelpArgs = @("<arg1>", ...) }
#
# For simple commands (no spaced public form), use:
#   HelpArgs = @("--help", "<internal-name>")
#
# For commands with a spaced public form, use:
#   HelpArgs = @("<prefix>", "<sub>", "--help")
# Because `browser4-cli --help agent-run` is rejected; must use
# `browser4-cli agent run --help` instead.

$Commands = @(
    # -- Browser sessions --
    @{ Name = "open";           HelpArgs = @("--help", "open") },
    @{ Name = "attach";         HelpArgs = @("--help", "attach") },
    @{ Name = "close";          HelpArgs = @("--help", "close") },
    @{ Name = "list";           HelpArgs = @("--help", "list") },
    @{ Name = "close-all";      HelpArgs = @("--help", "close-all") },
    @{ Name = "kill-all";       HelpArgs = @("--help", "kill-all") },
    @{ Name = "stop";           HelpArgs = @("--help", "stop") },
    @{ Name = "status";         HelpArgs = @("--help", "status") },
    @{ Name = "doctor";         HelpArgs = @("--help", "doctor") },

    # -- Navigation --
    @{ Name = "goto";           HelpArgs = @("--help", "goto") },
    @{ Name = "go-back";        HelpArgs = @("--help", "go-back") },
    @{ Name = "go-forward";     HelpArgs = @("--help", "go-forward") },
    @{ Name = "reload";         HelpArgs = @("--help", "reload") },

    # -- Keyboard --
    @{ Name = "press";          HelpArgs = @("--help", "press") },
    @{ Name = "type";           HelpArgs = @("--help", "type") },
    @{ Name = "keydown";        HelpArgs = @("--help", "keydown") },
    @{ Name = "keyup";          HelpArgs = @("--help", "keyup") },
    @{ Name = "fill";           HelpArgs = @("--help", "fill") },

    # -- Mouse --
    @{ Name = "click";          HelpArgs = @("--help", "click") },
    @{ Name = "dblclick";       HelpArgs = @("--help", "dblclick") },
    @{ Name = "hover";          HelpArgs = @("--help", "hover") },
    @{ Name = "drag";           HelpArgs = @("--help", "drag") },
    @{ Name = "mousemove";      HelpArgs = @("--help", "mousemove") },
    @{ Name = "mousedown";      HelpArgs = @("--help", "mousedown") },
    @{ Name = "mouseup";        HelpArgs = @("--help", "mouseup") },
    @{ Name = "mousewheel";     HelpArgs = @("--help", "mousewheel") },
    @{ Name = "scroll";         HelpArgs = @("--help", "scroll") },

    # -- Core --
    @{ Name = "snapshot";       HelpArgs = @("--help", "snapshot") },
    @{ Name = "get";            HelpArgs = @("--help", "get") },
    @{ Name = "eval";           HelpArgs = @("--help", "eval") },
    @{ Name = "wait";           HelpArgs = @("--help", "wait") },
    @{ Name = "select";         HelpArgs = @("--help", "select") },
    @{ Name = "check";          HelpArgs = @("--help", "check") },
    @{ Name = "uncheck";        HelpArgs = @("--help", "uncheck") },
    @{ Name = "dialog-accept";  HelpArgs = @("--help", "dialog-accept") },
    @{ Name = "dialog-dismiss"; HelpArgs = @("--help", "dialog-dismiss") },
    @{ Name = "resize";         HelpArgs = @("--help", "resize") },
    @{ Name = "delete-data";    HelpArgs = @("--help", "delete-data") },
    @{ Name = "batch";          HelpArgs = @("--help", "batch") },
    @{ Name = "generate-locator"; HelpArgs = @("--help", "generate-locator") },

    # -- Export --
    @{ Name = "screenshot";     HelpArgs = @("--help", "screenshot") },
    @{ Name = "pdf";            HelpArgs = @("--help", "pdf") },

    # -- Tabs --
    @{ Name = "tab-list";       HelpArgs = @("--help", "tab-list") },
    @{ Name = "tab-new";        HelpArgs = @("--help", "tab-new") },
    @{ Name = "tab-close";      HelpArgs = @("--help", "tab-close") },
    @{ Name = "tab-select";     HelpArgs = @("--help", "tab-select") },

    # -- Storage --
    @{ Name = "state-save";     HelpArgs = @("--help", "state-save") },
    @{ Name = "state-load";     HelpArgs = @("--help", "state-load") },
    @{ Name = "cookie-list";    HelpArgs = @("--help", "cookie-list") },
    @{ Name = "cookie-get";     HelpArgs = @("--help", "cookie-get") },
    @{ Name = "cookie-set";     HelpArgs = @("--help", "cookie-set") },
    @{ Name = "cookie-delete";  HelpArgs = @("--help", "cookie-delete") },
    @{ Name = "cookie-clear";   HelpArgs = @("--help", "cookie-clear") },
    @{ Name = "localstorage-list";    HelpArgs = @("--help", "localstorage-list") },
    @{ Name = "localstorage-get";     HelpArgs = @("--help", "localstorage-get") },
    @{ Name = "localstorage-set";     HelpArgs = @("--help", "localstorage-set") },
    @{ Name = "localstorage-delete";  HelpArgs = @("--help", "localstorage-delete") },
    @{ Name = "localstorage-clear";   HelpArgs = @("--help", "localstorage-clear") },
    @{ Name = "sessionstorage-list";  HelpArgs = @("--help", "sessionstorage-list") },
    @{ Name = "sessionstorage-get";   HelpArgs = @("--help", "sessionstorage-get") },
    @{ Name = "sessionstorage-set";   HelpArgs = @("--help", "sessionstorage-set") },
    @{ Name = "sessionstorage-delete"; HelpArgs = @("--help", "sessionstorage-delete") },
    @{ Name = "sessionstorage-clear"; HelpArgs = @("--help", "sessionstorage-clear") },

    # -- Agent (hidden: agent-run, agent-status, agent-result) --
    @{ Name = "extract";        HelpArgs = @("--help", "extract") },
    # agent-list is a standalone command, not hidden
    @{ Name = "agent-list";     HelpArgs = @("--help", "agent-list") },

    # -- Snapshot subcommands (spaced-form: domsnapshot *, snapshot grep) --
    @{ Name = "snapshot-grep";  HelpArgs = @("--help", "snapshot-grep") },

    # -- DOM Snapshot (base command + spaced subcommands) --
    @{ Name = "domsnapshot";    HelpArgs = @("--help", "domsnapshot") },

    # -- Crawl --
    @{ Name = "crawl";          HelpArgs = @("--help", "crawl") },

    # -- Install / Admin --
    @{ Name = "install";        HelpArgs = @("--help", "install") },
    @{ Name = "upgrade";        HelpArgs = @("--help", "upgrade") },
    @{ Name = "uninstall";      HelpArgs = @("--help", "uninstall") },
    @{ Name = "loop";           HelpArgs = @("--help", "loop") }
)

# Commands with spaced public forms — the CLI rejects `--help <kebab-name>`
# for these, so we use `<prefix> <sub> --help` instead.
$SpacedCommands = @(
    # Agent subcommands (hidden)
    @{ Name = "agent-run";      HelpArgs = @("agent", "run", "--help") },
    @{ Name = "agent-status";   HelpArgs = @("agent", "status", "--help") },
    @{ Name = "agent-result";   HelpArgs = @("agent", "result", "--help") },

    # Swarm subcommands
    @{ Name = "swarm-create";   HelpArgs = @("swarm", "create", "--help") },
    @{ Name = "swarm-submit";   HelpArgs = @("swarm", "submit", "--help") },
    @{ Name = "swarm-query";    HelpArgs = @("swarm", "query", "--help") },
    @{ Name = "swarm-status";   HelpArgs = @("swarm", "status", "--help") },
    @{ Name = "swarm-result";   HelpArgs = @("swarm", "result", "--help") },
    @{ Name = "swarm-list";     HelpArgs = @("swarm", "list", "--help") },

    # DOM Snapshot subcommands
    @{ Name = "domsnapshot-get";       HelpArgs = @("domsnapshot", "get", "--help") },
    @{ Name = "domsnapshot-get-all";   HelpArgs = @("domsnapshot", "get", "all", "--help") },
    @{ Name = "domsnapshot-query";     HelpArgs = @("domsnapshot", "query", "--help") },
    @{ Name = "domsnapshot-export";    HelpArgs = @("domsnapshot", "export", "--help") },
    @{ Name = "domsnapshot-summary";   HelpArgs = @("domsnapshot", "summary", "--help") },
    @{ Name = "domsnapshot-grep";      HelpArgs = @("domsnapshot", "grep", "--help") },
    @{ Name = "domsnapshot-inspect";   HelpArgs = @("domsnapshot", "inspect", "--help") },

    # Crawl subcommand
    @{ Name = "crawl-list";     HelpArgs = @("crawl", "list", "--help") }
)

# Hidden commands (not shown in top-level help, but included for completeness)
$HiddenCommands = @(
    @{ Name = "upload";         HelpArgs = @("--help", "upload") },
    @{ Name = "console";        HelpArgs = @("--help", "console") },
    @{ Name = "summarize";      HelpArgs = @("--help", "summarize") }
)
# Note: agent-run, agent-status, agent-result are also hidden but already in $SpacedCommands

# Prefix groups to generate overview pages for
$PrefixGroups = @(
    @{ Name = "swarm";       HelpArgs = @("--help", "swarm") },
    @{ Name = "agent";        HelpArgs = @("--help", "agent") },
    @{ Name = "domsnapshot";  HelpArgs = @("--help", "domsnapshot") },
    @{ Name = "crawl";        HelpArgs = @("--help", "crawl") },
    @{ Name = "snapshot";     HelpArgs = @("--help", "snapshot") }
)

# ---- Generate help files ----------------------------------------------------

$allFiles = @()   # Track generated files for the index

# 1. Top-level help
Write-Host "[1/5] Generating top-level help..."
$topHelp = Invoke-Cli -Arguments @("--help")
$topFile = "00-top-level-help.txt"
$topHelp | Out-File -FilePath (Join-Path $OutDir $topFile) -Encoding utf8
$allFiles += @{ Path = $topFile; Label = "Top-level help (browser4-cli --help)" }
Write-Host "       -> $topFile"

# 2. Per-command help (simple commands)
Write-Host "[2/5] Generating per-command help ($($Commands.Count) simple commands)..."
$idx = 0
foreach ($cmd in $Commands) {
    $idx++
    $padIdx = $idx.ToString("00")
    $safeName = $cmd.Name -replace '[<>:"/\\|?*]', '_'
    $fileName = "$padIdx-$safeName.txt"

    Write-Host "       [$idx/$($Commands.Count)] $($cmd.Name)"
    $help = Invoke-Cli -Arguments $cmd.HelpArgs
    $help | Out-File -FilePath (Join-Path $OutDir $fileName) -Encoding utf8
    $allFiles += @{ Path = $fileName; Label = $cmd.Name }
}

# 3. Spaced-form command help
Write-Host "[3/5] Generating spaced-form command help ($($SpacedCommands.Count) commands)..."
$baseIdx = $idx
foreach ($cmd in $SpacedCommands) {
    $idx++
    $padIdx = $idx.ToString("00")
    $safeName = $cmd.Name -replace '[<>:"/\\|?*]', '_'
    $fileName = "$padIdx-$safeName.txt"

    Write-Host "       [$($idx - $baseIdx)/$($SpacedCommands.Count)] $($cmd.Name)"
    $help = Invoke-Cli -Arguments $cmd.HelpArgs
    $help | Out-File -FilePath (Join-Path $OutDir $fileName) -Encoding utf8
    $allFiles += @{ Path = $fileName; Label = $cmd.Name }
}

# 4. Hidden commands
Write-Host "[4/5] Generating hidden-command help ($($HiddenCommands.Count) commands)..."
$baseIdx = $idx
foreach ($cmd in $HiddenCommands) {
    $idx++
    $padIdx = $idx.ToString("00")
    $safeName = $cmd.Name -replace '[<>:"/\\|?*]', '_'
    $fileName = "$padIdx-$safeName.txt"

    Write-Host "       [$($idx - $baseIdx)/$($HiddenCommands.Count)] $($cmd.Name) [hidden]"
    $help = Invoke-Cli -Arguments $cmd.HelpArgs
    $help | Out-File -FilePath (Join-Path $OutDir $fileName) -Encoding utf8
    $allFiles += @{ Path = $fileName; Label = "$($cmd.Name) (hidden)" }
}

# 5. Prefix group overviews
Write-Host "[5/5] Generating prefix group overviews..."
foreach ($group in $PrefixGroups) {
    $safeName = $group.Name
    $fileName = "prefix-$safeName.txt"

    Write-Host "       prefix: $($group.Name)"
    # For prefix groups, `--help <prefix>` prints subcommands
    $help = Invoke-Cli -Arguments $group.HelpArgs
    $help | Out-File -FilePath (Join-Path $OutDir $fileName) -Encoding utf8
    $allFiles += @{ Path = $fileName; Label = "Prefix group: $($group.Name)" }
}

# ---- Generate INDEX.md ------------------------------------------------------
Write-Host ""
Write-Host "Generating INDEX.md..."

$indexLines = @(
    "# browser4-cli Help Reference",
    "",
    "Generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')",
    "Binary: $Binary",
    "",
    "## Overview",
    "",
    "This directory contains the complete help output for every browser4-cli",
    "command and subcommand.",
    "",
    "## Files",
    ""
)

foreach ($entry in $allFiles) {
    $indexLines += "- [$($entry.Label)]($($entry.Path))"
}

$totalCmdCount = $Commands.Count + $SpacedCommands.Count + $HiddenCommands.Count
$visibleCount = $totalCmdCount - 5  # 5 hidden: upload, console, summarize, agent-run, agent-result

$indexLines += (
    "",
    "## Quick Reference",
    "",
    "### Global help",
    '```',
    "browser4-cli --help",
    '```',
    "",
    "### Per-command help (simple commands)",
    '```',
    "browser4-cli --help <command>",
    '```',
    "",
    "### Per-command help (spaced subcommands)",
    '```',
    "browser4-cli <prefix> <sub> --help      # e.g. browser4-cli swarm create --help",
    "browser4-cli help <prefix> <sub>        # equivalent",
    '```',
    "",
    "### Prefix group overview",
    '```',
    "browser4-cli --help <prefix>            # e.g. browser4-cli --help swarm",
    '```',
    "",
    "## Command Count",
    "",
    "- **Total commands**: $totalCmdCount",
    "- **Visible commands**: $visibleCount (5 hidden)",
    "- **Hidden commands**: upload, console, summarize, agent-run, agent-status, agent-result",
    ""
)

$indexContent = $indexLines -join "`n"
$indexPath = Join-Path $OutDir "INDEX.md"
$indexContent | Out-File -FilePath $indexPath -Encoding utf8

Write-Host "Done! All help files saved to: $OutDir"
Write-Host "             Total files: $($allFiles.Count + 1) (including INDEX.md)"
Write-Host ""
Write-Host "Start here: $(Join-Path $OutDir 'INDEX.md')"
