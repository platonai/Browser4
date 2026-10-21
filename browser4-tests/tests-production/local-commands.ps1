#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Windows-only env vars ($env:TEMP) need $env:TMPDIR fallback.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# ═══════════════════════════════════════════════════════════════════
<#
.SYNOPSIS
    Mechanical assertions for the server-free local command surface.

.DESCRIPTION
    A subset of browser4-cli commands is dispatched entirely inside the CLI and
    needs neither a browser nor the Browser4 backend.  Those commands can never
    be exercised by a real-world scenario (there is no page to drive), so the
    coverage plan routes them here instead — see
    docs-dev/copilot/real-world-scenario-cli-coverage-plan.md §8 item 11.

    This script asserts three things, all offline and deterministic:

    1. Server-free contract — none of these commands may start, or try to start,
       the Browser4 backend.  A command that silently acquires a backend is no
       longer a "local" command and its coverage classification is wrong.
    2. Behaviour — the documented output of `config`, `skills` and `snapshot`
       (including a full set/get/delete round trip that restores the previous
       value, and a skills unpack into a throwaway directory).
    3. Spaced-form contract — hidden subcommands (`config-list`, `skills-path`,
       …) must reject the kebab spelling with the "Use 'browser4-cli config list'
       instead" hint.  The CLI only rewrites spaced forms for a known set of
       prefixes, so a scenario that writes the kebab spelling sends an agent
       down a dead end; this assertion pins that contract mechanically.

    Uses the globally-installed browser4-cli.  Override with $env:BROWSER4_CLI_BIN.

    Deliberately NOT covered here:
    - `stop` / `install` / `upgrade` — already exercised by stress-install.ps1,
      and running them here would kill a backend the caller may be using.
    - `diff-snapshot` / `profiles-list` / `session-default` / `batch` — measured
      on 2026-10-10; all four start the backend, so they are NOT server-free and
      belong to an LLM scenario, not to this script.
    - `snapshot clean` without --dry-run — it deletes the user's snapshots.
    - `skills unpack` without a destination — it overwrites the user's skills
      directory; this script always passes a temp directory instead.

.EXAMPLE
    .\local-commands.ps1

.EXAMPLE
    $env:BROWSER4_CLI_BIN = 'D:\dev\browser4-cli.exe'; .\local-commands.ps1
#>

[CmdletBinding()]
param()

$ErrorActionPreference = 'Continue'

# -------------------------------------------------------------------
# Load shared test utilities
# -------------------------------------------------------------------
Import-Module "$PSScriptRoot\test-utils.psm1" -Force
Start-TestSession -Name 'local-commands'

$CliBin = Get-CliBin
if (-not $CliBin -or -not (Test-Path $CliBin)) {
    Write-Host "ERROR: browser4-cli not found on PATH." -ForegroundColor Red
    Write-Host "Install it with: npm i -g browser4-cli && browser4-cli install" -ForegroundColor Yellow
    exit 1
}

Write-Host "Using CLI: $CliBin" -ForegroundColor DarkGray
try {
    $ver = & $CliBin --version 2>&1
    Write-Host "Version : $($ver -join ' ')" -ForegroundColor DarkGray
} catch {}

Write-TestHeader -Name 'local-commands'

# Track content-based assertions (beyond the exit-code checks done by
# Invoke-TrackedCli).
$script:ContentFailures = 0

function Assert-Output {
    param(
        [string] $Label,
        [scriptblock] $Condition
    )
    if (& $Condition) {
        Write-Host "    ✅ $Label" -ForegroundColor Green
    } else {
        $script:ContentFailures++
        Write-Host "    ❌ $Label" -ForegroundColor Red
    }
}

# Marker text the CLI prints only when it is about to bring up the backend.
# Kept as literal substrings (not one loose regex) so a new banner wording
# fails loudly here instead of silently passing the contract check.
$BackendMarkers = @(
    'Starting Browser4 dev server',
    'Starting Browser4 server',
    'Refusing to start the Browser4 server',
    'JVM + Spring Boot'
)

function Assert-NoBackend {
    param(
        [string] $Label,
        [string] $OutputText
    )
    $hit = @($BackendMarkers | Where-Object { $OutputText.Contains($_) })
    Assert-Output -Label "$Label does not start the backend" -Condition { $hit.Count -eq 0 }
    if ($hit.Count -gt 0) {
        Write-Host "        backend marker(s): $($hit -join ' | ')" -ForegroundColor DarkGray
    }
}

function Get-OutputText {
    param($Output)
    return ($Output | Out-String).Trim()
}

# -------------------------------------------------------------------
# Test: server-free contract — nothing here may touch the backend
# -------------------------------------------------------------------
Write-Host "━━━ Server-free contract ━━━" -ForegroundColor Cyan

$serverFreeProbes = @(
    @{ Label = 'config list';              Args = @('config', 'list') },
    @{ Label = 'config get';               Args = @('config', 'get', 'server') },
    @{ Label = 'skills list';              Args = @('skills', 'list') },
    @{ Label = 'skills path';              Args = @('skills', 'path') },
    @{ Label = 'snapshot list';            Args = @('snapshot', 'list') },
    @{ Label = 'snapshot clean --dry-run'; Args = @('snapshot', 'clean', '--dry-run') }
)

foreach ($probe in $serverFreeProbes) {
    $out = Get-OutputText (Invoke-TrackedCli -Arguments $probe.Args -Label $probe.Label)
    Assert-NoBackend -Label $probe.Label -OutputText $out
}
Write-Host ''

# -------------------------------------------------------------------
# Test: config family — read, write, delete, and restore
# -------------------------------------------------------------------
Write-Host "━━━ config family ━━━" -ForegroundColor Cyan

$out = Get-OutputText (Invoke-TrackedCli -Arguments @('config', 'list') -Label 'config list')
Assert-Output -Label 'config list names the config file' -Condition { $out -match 'Config file:' }
Assert-Output -Label 'config list reports at least one known key' -Condition { $out -match '\bserver\b' }
Write-Host "    Output: $($out -split "`n" | Select-Object -First 2)" -ForegroundColor DarkGray

# Unknown keys must be rejected by name, not swallowed.
$out = Get-OutputText (Invoke-TrackedCli -Arguments @('config', 'get', 'no-such-key') -Label 'config get <unknown>' -ExpectedExitCode 1)
Assert-Output -Label 'config get rejects an unknown key' -Condition { $out -match 'Unknown config key' }
Assert-Output -Label 'config get lists the valid keys' -Condition { $out -match "'server'" }

# Full round trip on a real key, restoring whatever the user had before.
$key = 'timeout'
$sentinel = '31337'
$before = Get-OutputText (Invoke-TrackedCli -Arguments @('config', 'get', $key) -Label "config get $key (baseline)")
$hadValue = ($before -ne '(not set)') -and ($before -ne '')

Write-Host "    Round trip on '$key' (previous value: $before)" -ForegroundColor DarkGray

$restored = $false
try {
    $out = Get-OutputText (Invoke-TrackedCli -Arguments @('config', 'set', $key, $sentinel) -Label "config set $key")
    Assert-Output -Label "config set confirms the write" -Condition { $out -match "Set '$key'" }

    $out = Get-OutputText (Invoke-TrackedCli -Arguments @('config', 'get', $key) -Label "config get $key (after set)")
    Assert-Output -Label 'config get returns the value just written' -Condition { $out -eq $sentinel }

    $out = Get-OutputText (Invoke-TrackedCli -Arguments @('config', 'delete', $key) -Label "config delete $key")
    Assert-Output -Label 'config delete confirms the removal' -Condition { $out -match "Deleted '$key'" }

    $out = Get-OutputText (Invoke-TrackedCli -Arguments @('config', 'get', $key) -Label "config get $key (after delete)")
    Assert-Output -Label 'config get reports the key as unset after delete' -Condition { $out -eq '(not set)' }
} finally {
    # Leave the machine exactly as we found it, even if an assertion above threw.
    if ($hadValue) {
        Invoke-TrackedCli -Arguments @('config', 'set', $key, $before) -Label "config set $key (restore)" | Out-Null
        $check = Get-OutputText (Invoke-TrackedCli -Arguments @('config', 'get', $key) -Label "config get $key (restore check)")
        $restored = ($check -eq $before)
    } else {
        $check = Get-OutputText (Invoke-TrackedCli -Arguments @('config', 'get', $key) -Label "config get $key (restore check)")
        $restored = ($check -eq '(not set)')
    }
}
Assert-Output -Label "config round trip restored the original value of '$key'" -Condition { $restored }
Write-Host ''

# -------------------------------------------------------------------
# Test: skills family
# -------------------------------------------------------------------
Write-Host "━━━ skills family ━━━" -ForegroundColor Cyan

$out = Get-OutputText (Invoke-TrackedCli -Arguments @('skills', 'list') -Label 'skills list')
Assert-Output -Label 'skills list announces the bundled skills' -Condition { $out -match 'Bundled skills:' }
Assert-Output -Label 'skills list includes browser4-cli' -Condition { $out -match 'browser4-cli' }
Assert-NoBackend -Label 'skills list' -OutputText $out

$out = Get-OutputText (Invoke-TrackedCli -Arguments @('skills', 'path') -Label 'skills path')
Assert-Output -Label 'skills path prints an absolute path' -Condition {
    $out.Length -gt 0 -and ($out -match '^([A-Za-z]:[\\/]|/)')
}
Assert-NoBackend -Label 'skills path' -OutputText $out

$out = Get-OutputText (Invoke-TrackedCli -Arguments @('skills', 'get', 'browser4-cli') -Label 'skills get browser4-cli')
Assert-Output -Label 'skills get emits skill content' -Condition { $out.Length -gt 0 }
Assert-NoBackend -Label 'skills get' -OutputText $out

# Unpack into a throwaway directory — never into the user's skills directory.
$unpackDir = Join-Path ([System.IO.Path]::GetTempPath()) ("b4-local-commands-" + [System.Guid]::NewGuid().ToString('N').Substring(0, 8))
try {
    $out = Get-OutputText (Invoke-TrackedCli -Arguments @('skills', 'unpack', $unpackDir) -Label 'skills unpack <temp>')
    Assert-Output -Label 'skills unpack reports an unpack count' -Condition { $out -match 'Unpacked \d+ skill file' }
    Assert-NoBackend -Label 'skills unpack' -OutputText $out
    Assert-Output -Label 'skills unpack wrote files to the destination' -Condition {
        (Test-Path $unpackDir) -and
        (@(Get-ChildItem -LiteralPath $unpackDir -Recurse -File -ErrorAction SilentlyContinue).Count -gt 0)
    }
} finally {
    if (Test-Path $unpackDir) {
        Remove-Item -LiteralPath $unpackDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}
Write-Host ''

# -------------------------------------------------------------------
# Test: snapshot family (local snapshot files, no backend)
# -------------------------------------------------------------------
Write-Host "━━━ snapshot family ━━━" -ForegroundColor Cyan

$out = Get-OutputText (Invoke-TrackedCli -Arguments @('snapshot', 'list') -Label 'snapshot list')
Assert-Output -Label 'snapshot list reports the snapshot directory or emptiness' -Condition {
    ($out -match 'Snapshots in') -or ($out -match 'No snapshot')
}
Assert-NoBackend -Label 'snapshot list' -OutputText $out

# --dry-run only: a bare `snapshot clean` would delete the user's snapshots.
$out = Get-OutputText (Invoke-TrackedCli -Arguments @('snapshot', 'clean', '--dry-run') -Label 'snapshot clean --dry-run')
Assert-Output -Label 'snapshot clean --dry-run reports a result' -Condition { $out.Length -gt 0 }
Assert-NoBackend -Label 'snapshot clean --dry-run' -OutputText $out
Write-Host ''

# -------------------------------------------------------------------
# Test: webminer version — local JAR, no Browser4 backend
# -------------------------------------------------------------------
Write-Host "━━━ webminer version ━━━" -ForegroundColor Cyan

# Exit code is deliberately NOT asserted: `webminer version` also performs an
# optional "latest release" lookup, which fails on an offline machine without
# making the command any less local.  The contract under test is "no backend".
$out = Get-OutputText (Invoke-TrackedCli -Arguments @('webminer', 'version') -Label 'webminer version' -PassThruOnly)
Assert-Output -Label 'webminer version prints the WebMiner banner' -Condition { $out -match 'webminer \(WebMiner\)' }
Assert-NoBackend -Label 'webminer version' -OutputText $out
Write-Host ''

# -------------------------------------------------------------------
# Test: spaced-form contract for hidden subcommands
# -------------------------------------------------------------------
Write-Host "━━━ spaced-form contract ━━━" -ForegroundColor Cyan

# `rewrite_prefixed_command()` only rewrites spaced forms for a known prefix
# set.  These hidden subcommands therefore have exactly one accepted spelling —
# the spaced one — and the kebab spelling must be refused with a hint that names
# it.  Coverage check E in real-world-scenarios/scripts/coverage.ps1 relies on
# the same contract when it validates scenario tokens.
$kebabForms = @(
    @{ Kebab = 'config-list'; Spaced = 'config list' },
    @{ Kebab = 'skills-list'; Spaced = 'skills list' },
    @{ Kebab = 'skills-path'; Spaced = 'skills path' }
)

foreach ($form in $kebabForms) {
    $out = Get-OutputText (Invoke-TrackedCli -Arguments @($form.Kebab) -Label "reject $($form.Kebab)" -ExpectedExitCode 2)
    Assert-Output -Label "$($form.Kebab) is refused as an unsupported command form" -Condition {
        $out -match 'Unsupported command form'
    }
    Assert-Output -Label "$($form.Kebab) points at '$($form.Spaced)'" -Condition {
        $out -match [regex]::Escape("browser4-cli $($form.Spaced)")
    }
    Assert-NoBackend -Label "reject $($form.Kebab)" -OutputText $out
}
Write-Host ''

# -------------------------------------------------------------------
# Final report
# -------------------------------------------------------------------
$exitCode = Finish-TestSession -ExtraCopilotPrompt "These are browser4-cli server-free local command surface failures."
if ($script:ContentFailures -gt 0) {
    Write-Host "  ⚠ $script:ContentFailures content-based assertion(s) also failed" -ForegroundColor Red
    if ($exitCode -eq 0) { $exitCode = 1 }
}
exit $exitCode
