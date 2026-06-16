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
Removes globally installed browser4-cli executables while ignoring project-local installs.

.DESCRIPTION
This script looks for global browser4-cli installations managed by npm, pnpm, Yarn classic,
and cargo. It only targets global installations and does not remove packages installed inside
project directories such as local node_modules folders.

The script also checks for remaining browser4-cli and browser4 commands in PATH after the
cleanup attempt and can optionally fail with a non-zero exit code when any global commands
are still present.

.PARAMETER DryRun
Reports what would be removed without making any changes.

.PARAMETER FailIfRemaining
Exits with code 1 when browser4-cli or browser4 global commands are still detected at the end
of the run. This is useful for CI or verification scripts.

.EXAMPLE
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\remove-global-browser4-cli.ps1 -DryRun

Shows which global browser4-cli packages would be removed without uninstalling anything.

.EXAMPLE
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\remove-global-browser4-cli.ps1

Removes detected global browser4-cli packages from supported package managers.

.EXAMPLE
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\remove-global-browser4-cli.ps1 -FailIfRemaining

Removes detected global packages and returns exit code 1 if browser4-cli or browser4 commands
are still present afterward.

.EXAMPLE
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\remove-global-browser4-cli.ps1 -DryRun -FailIfRemaining

Performs a verification-only pass and returns exit code 1 when global browser4-cli commands
are still detected.

.NOTES
- Yarn 2+ / Yarn Berry does not support classic global installs, so the script skips Yarn
  global package removal on those versions.
- Remaining PATH entries are reported explicitly so wrappers such as .cmd or .ps1 launchers
  can be inspected after uninstall.
#>

[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [switch]$DryRun,
    [switch]$FailIfRemaining
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$packageName = 'browser4-cli'
$trackedCommands = @('browser4-cli', 'browser4')
$results = New-Object System.Collections.Generic.List[object]

function Add-Result
{
    param(
        [string]$Manager,
        [string]$Status,
        [string]$Message
    )

    $results.Add([PSCustomObject]@{
            Manager = $Manager
            Status = $Status
            Message = $Message
        }) | Out-Null
}

function Resolve-Executable
{
    param([string]$Name)

    $command = Get-Command -Name $Name -All -ErrorAction SilentlyContinue |
        Sort-Object `
            @{ Expression = {
                    if ($_.CommandType -eq 'Application')
                    {
                        0
                    }
                    elseif ($_.CommandType -eq 'ExternalScript')
                    {
                        1
                    }
                    else
                    {
                        2
                    }
                } }, `
            @{ Expression = {
                    $extension = [System.IO.Path]::GetExtension($_.Source)
                    switch ($extension.ToLowerInvariant())
                    {
                        '.exe' { 0; break }
                        '.cmd' { 1; break }
                        '.bat' { 2; break }
                        '.ps1' { 3; break }
                        default { 4; break }
                    }
                } } |
        Select-Object -First 1

    if ($null -eq $command)
    {
        return $null
    }

    return $command.Source
}

function Invoke-ExternalCommand
{
    param(
        [string]$Executable,
        [string[]]$Arguments,
        [switch]$IgnoreExitCode
    )

    $stdoutFile = [System.IO.Path]::GetTempFileName()
    $stderrFile = [System.IO.Path]::GetTempFileName()
    $invokerExecutable = $Executable
    $invokerArguments = @($Arguments)
    $executableExtension = [System.IO.Path]::GetExtension($Executable)

    switch ($executableExtension.ToLowerInvariant())
    {
        '.cmd'
        {
            $invokerExecutable = if ([string]::IsNullOrWhiteSpace($env:ComSpec)) { 'cmd.exe' } else { $env:ComSpec }
            $invokerArguments = @('/d', '/c', ('"' + $Executable + '"')) + @($Arguments)
            break
        }
        '.bat'
        {
            $invokerExecutable = if ([string]::IsNullOrWhiteSpace($env:ComSpec)) { 'cmd.exe' } else { $env:ComSpec }
            $invokerArguments = @('/d', '/c', ('"' + $Executable + '"')) + @($Arguments)
            break
        }
        '.ps1'
        {
            $invokerExecutable = Join-Path $PSHOME 'powershell.exe'
            $invokerArguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $Executable) + @($Arguments)
            break
        }
    }

    try
    {
        $process = Start-Process `
            -FilePath $invokerExecutable `
            -ArgumentList $invokerArguments `
            -NoNewWindow `
            -Wait `
            -PassThru `
            -RedirectStandardInput $(if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'NUL' } else { '/dev/null' }) `
            -RedirectStandardOutput $stdoutFile `
            -RedirectStandardError $stderrFile

        $stdout = if (Test-Path -LiteralPath $stdoutFile)
        {
            Get-Content -LiteralPath $stdoutFile -Raw
        }
        else
        {
            ''
        }

        $stderr = if (Test-Path -LiteralPath $stderrFile)
        {
            Get-Content -LiteralPath $stderrFile -Raw
        }
        else
        {
            ''
        }
    }
    finally
    {
        Remove-Item -LiteralPath $stdoutFile -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $stderrFile -Force -ErrorAction SilentlyContinue
    }

    $exitCode = if ($null -ne $process) { [int]$process.ExitCode } else { 0 }
    $output = (@($stdout, $stderr) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | ForEach-Object { $_.TrimEnd() }) -join [Environment]::NewLine
    $output = $output.Trim()

    if (-not $IgnoreExitCode -and $exitCode -ne 0)
    {
        throw "Command failed (exit code $exitCode): $Executable $($Arguments -join ' ')`n$output"
    }

    return [PSCustomObject]@{
        ExitCode = [int]$exitCode
        Output = $output
    }
}

function Get-FirstNonEmptyLine
{
    param([string]$Text)

    if ([string]::IsNullOrWhiteSpace($Text))
    {
        return $null
    }

    return $Text -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1
}

function Get-MessageOrDefault
{
    param(
        [string]$Message,
        [string]$DefaultMessage
    )

    if ([string]::IsNullOrWhiteSpace($Message))
    {
        return $DefaultMessage
    }

    return $Message
}

function Get-VersionMajor
{
    param([string]$VersionText)

    if ([string]::IsNullOrWhiteSpace($VersionText))
    {
        return $null
    }

    $match = [regex]::Match($VersionText, '^(\d+)')
    if (-not $match.Success)
    {
        return $null
    }

    return [int]$match.Groups[1].Value
}

function Write-Utf8NoBomFile
{
    param(
        [string]$Path,
        [string]$Content
    )

    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
}

function Get-CargoHomePath
{
    if (-not [string]::IsNullOrWhiteSpace($env:CARGO_HOME))
    {
        return $env:CARGO_HOME
    }

    return Join-Path $HOME '.cargo'
}

function Remove-CargoMetadataEntries
{
    param([string]$CrateName)

    $cargoHome = Get-CargoHomePath
    $cratesTomlPath = Join-Path $cargoHome '.crates.toml'
    $cratesJsonPath = Join-Path $cargoHome '.crates2.json'
    $removedTomlEntries = @()
    $removedJsonEntries = @()

    if (Test-Path -LiteralPath $cratesTomlPath)
    {
        $tomlLines = Get-Content -LiteralPath $cratesTomlPath
        $filteredTomlLines = foreach ($line in $tomlLines)
        {
            if ($line -match ('^"' + [regex]::Escape($CrateName) + '\s+.+"\s*=\s*\[.*\]$'))
            {
                $removedTomlEntries += $line.Trim()
                continue
            }

            $line
        }

        if ($removedTomlEntries.Count -gt 0)
        {
            Write-Utf8NoBomFile -Path $cratesTomlPath -Content (($filteredTomlLines -join [Environment]::NewLine) + [Environment]::NewLine)
        }
    }

    if (Test-Path -LiteralPath $cratesJsonPath)
    {
        $jsonText = Get-Content -LiteralPath $cratesJsonPath -Raw
        if (-not [string]::IsNullOrWhiteSpace($jsonText))
        {
            $json = $jsonText | ConvertFrom-Json
            if ($null -ne $json -and $null -ne $json.installs)
            {
                $propertyNames = @($json.installs.PSObject.Properties.Name)
                foreach ($propertyName in $propertyNames)
                {
                    if ($propertyName -like "$CrateName *")
                    {
                        $removedJsonEntries += $propertyName
                        [void]$json.installs.PSObject.Properties.Remove($propertyName)
                    }
                }

                if ($removedJsonEntries.Count -gt 0)
                {
                    Write-Utf8NoBomFile -Path $cratesJsonPath -Content (($json | ConvertTo-Json -Depth 100) + [Environment]::NewLine)
                }
            }
        }
    }

    return [PSCustomObject]@{
        RemovedTomlEntries = @($removedTomlEntries)
        RemovedJsonEntries = @($removedJsonEntries)
        TotalRemoved = @($removedTomlEntries).Count + @($removedJsonEntries).Count
    }
}

function Remove-GlobalNodePackage
{
    param(
        [string]$Manager,
        [string]$ExecutableName,
        [string[]]$LocationArgs,
        [scriptblock]$PackagePathBuilder,
        [string[]]$RemoveArgs,
        [string[]]$FallbackRemoveArgs = @()
    )

    $executable = Resolve-Executable -Name $ExecutableName
    if ([string]::IsNullOrWhiteSpace($executable))
    {
        Add-Result -Manager $Manager -Status 'Skipped' -Message 'Package manager not detected.'
        return
    }

    $locationResult = Invoke-ExternalCommand -Executable $executable -Arguments $LocationArgs -IgnoreExitCode
    if ($locationResult.ExitCode -ne 0)
    {
        Add-Result -Manager $Manager -Status 'Skipped' -Message "Unable to read the global installation directory. $($locationResult.Output)"
        return
    }

    $location = Get-FirstNonEmptyLine -Text $locationResult.Output
    if ([string]::IsNullOrWhiteSpace($location))
    {
        Add-Result -Manager $Manager -Status 'Skipped' -Message 'Global installation directory is empty.'
        return
    }

    $packagePath = & $PackagePathBuilder $location
    if (-not (Test-Path -LiteralPath $packagePath))
    {
        Add-Result -Manager $Manager -Status 'Skipped' -Message 'No globally installed browser4-cli package was found.'
        return
    }

    if ($DryRun -or -not $PSCmdlet.ShouldProcess("$Manager global package $packageName", 'Uninstall'))
    {
        Add-Result -Manager $Manager -Status 'Planned' -Message "Would uninstall from $packagePath."
        return
    }

    $removeResult = Invoke-ExternalCommand -Executable $executable -Arguments $RemoveArgs -IgnoreExitCode
    $packageStillExists = Test-Path -LiteralPath $packagePath

    if ($packageStillExists -and $FallbackRemoveArgs.Count -gt 0)
    {
        $fallbackResult = Invoke-ExternalCommand -Executable $executable -Arguments $FallbackRemoveArgs -IgnoreExitCode
        $packageStillExists = Test-Path -LiteralPath $packagePath

        if (-not $packageStillExists)
        {
            Add-Result -Manager $Manager -Status 'Removed' -Message "Uninstalled using the fallback command. $($fallbackResult.Output)"
            return
        }

        Add-Result -Manager $Manager -Status 'Failed' -Message "Uninstall failed. remove: $($removeResult.Output) | fallback: $($fallbackResult.Output)"
        return
    }

    if ($packageStillExists)
    {
        Add-Result -Manager $Manager -Status 'Failed' -Message "The package is still present after uninstall at $packagePath. $($removeResult.Output)"
        return
    }

    Add-Result -Manager $Manager -Status 'Removed' -Message (Get-MessageOrDefault -Message $removeResult.Output -DefaultMessage 'Uninstalled.')
}

function Remove-CargoPackage
{
    $executable = Resolve-Executable -Name 'cargo'
    if ([string]::IsNullOrWhiteSpace($executable))
    {
        Add-Result -Manager 'cargo' -Status 'Skipped' -Message 'cargo was not detected.'
        return
    }

    $listResult = Invoke-ExternalCommand -Executable $executable -Arguments @('install', '--list') -IgnoreExitCode
    if ($listResult.ExitCode -ne 0)
    {
        Add-Result -Manager 'cargo' -Status 'Skipped' -Message "Unable to read the cargo global install list. $($listResult.Output)"
        return
    }

    if ($listResult.Output -notmatch '(^|\r?\n)browser4-cli\s+v[^\r\n]*:')
    {
        Add-Result -Manager 'cargo' -Status 'Skipped' -Message 'No globally installed browser4-cli crate was found via cargo.'
        return
    }

    if ($DryRun -or -not $PSCmdlet.ShouldProcess('cargo global crate browser4-cli', 'Uninstall'))
    {
        Add-Result -Manager 'cargo' -Status 'Planned' -Message 'Would run cargo uninstall browser4-cli.'
        return
    }

    $removeResult = Invoke-ExternalCommand -Executable $executable -Arguments @('uninstall', 'browser4-cli') -IgnoreExitCode

    if ($removeResult.ExitCode -ne 0 -and $removeResult.Output -match 'corrupt metadata')
    {
        $cleanupResult = Remove-CargoMetadataEntries -CrateName 'browser4-cli'
        $verifyAfterCleanup = Invoke-ExternalCommand -Executable $executable -Arguments @('install', '--list') -IgnoreExitCode

        if ($cleanupResult.TotalRemoved -gt 0 -and $verifyAfterCleanup.Output -notmatch '(^|\r?\n)browser4-cli\s+v[^\r\n]*:')
        {
            $cleanupSummary = "Removed stale Cargo metadata entries after uninstall failed: TOML=$(@($cleanupResult.RemovedTomlEntries).Count), JSON=$(@($cleanupResult.RemovedJsonEntries).Count)."
            Add-Result -Manager 'cargo' -Status 'Removed' -Message "$cleanupSummary Original cargo output: $($removeResult.Output)"
            return
        }
    }

    $verifyResult = Invoke-ExternalCommand -Executable $executable -Arguments @('install', '--list') -IgnoreExitCode

    if ($verifyResult.Output -match '(^|\r?\n)browser4-cli\s+v[^\r\n]*:')
    {
        Add-Result -Manager 'cargo' -Status 'Failed' -Message "browser4-cli is still detected after uninstall. $($removeResult.Output)"
        return
    }

    Add-Result -Manager 'cargo' -Status 'Removed' -Message (Get-MessageOrDefault -Message $removeResult.Output -DefaultMessage 'Uninstalled.')
}

function Remove-YarnPackage
{
    $executable = Resolve-Executable -Name 'yarn'
    if ([string]::IsNullOrWhiteSpace($executable))
    {
        Add-Result -Manager 'yarn' -Status 'Skipped' -Message 'Package manager not detected.'
        return
    }

    $versionResult = Invoke-ExternalCommand -Executable $executable -Arguments @('--version') -IgnoreExitCode
    if ($versionResult.ExitCode -ne 0)
    {
        Add-Result -Manager 'yarn' -Status 'Skipped' -Message "Unable to read the Yarn version. $($versionResult.Output)"
        return
    }

    $version = Get-FirstNonEmptyLine -Text $versionResult.Output
    $majorVersion = Get-VersionMajor -VersionText $version
    if ($null -eq $majorVersion)
    {
        Add-Result -Manager 'yarn' -Status 'Skipped' -Message "Unable to determine the Yarn major version from '$version'."
        return
    }

    if ($majorVersion -ge 2)
    {
        Add-Result -Manager 'yarn' -Status 'Skipped' -Message "Detected Yarn $version, which does not support classic global installs. Skipping Yarn global package removal."
        return
    }

    Remove-GlobalNodePackage `
        -Manager 'yarn' `
        -ExecutableName 'yarn' `
        -LocationArgs @('global', 'dir') `
        -PackagePathBuilder { param($location) Join-Path -Path $location -ChildPath (Join-Path -Path 'node_modules' -ChildPath $packageName) } `
        -RemoveArgs @('global', 'remove', $packageName)
}

function Get-RemainingGlobalCommands
{
    foreach ($commandName in $trackedCommands)
    {
        Get-Command -Name $commandName -All -ErrorAction SilentlyContinue |
            Where-Object {
                $_.CommandType -in @('Application', 'ExternalScript') -and
                -not [string]::IsNullOrWhiteSpace($_.Source) -and
                $_.Source -notmatch '[\\/]node_modules[\\/]'
            } |
            Select-Object @{ Name = 'Command'; Expression = { $commandName } }, Source
    }
}

Remove-GlobalNodePackage `
    -Manager 'npm' `
    -ExecutableName 'npm' `
    -LocationArgs @('root', '-g') `
    -PackagePathBuilder { param($location) Join-Path -Path $location -ChildPath $packageName } `
    -RemoveArgs @('uninstall', '-g', $packageName)

Remove-GlobalNodePackage `
    -Manager 'pnpm' `
    -ExecutableName 'pnpm' `
    -LocationArgs @('root', '-g') `
    -PackagePathBuilder { param($location) Join-Path -Path $location -ChildPath $packageName } `
    -RemoveArgs @('remove', '--global', $packageName) `
    -FallbackRemoveArgs @('unlink', '--global', $packageName)

Remove-YarnPackage

Remove-CargoPackage

$remainingCommands = @(Get-RemainingGlobalCommands)

if ($results.Count -eq 0)
{
    Write-Host 'No checks were executed.'
}
else
{
    $results | Format-Table -AutoSize | Out-String | Write-Host
}

if ($remainingCommands.Count -gt 0)
{
    Write-Host 'WARNING: The following global commands are still present. Please inspect their origin:'
    $remainingCommands | Sort-Object Command, Source | Format-Table -AutoSize | Out-String | Write-Host

    if ($FailIfRemaining)
    {
        Write-Host 'Self-check failed because global browser4-cli/browser4 commands are still present.'
        exit 1
    }
}
else
{
    Write-Host 'No remaining global browser4-cli/browser4 commands were detected.'
}
