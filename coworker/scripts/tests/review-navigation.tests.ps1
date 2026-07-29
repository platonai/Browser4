#!/usr/bin/env pwsh

$ErrorActionPreference = 'Stop'
$global:ReviewNavigationScript = Join-Path $PSScriptRoot '..\review.ps1'

function global:Invoke-ReviewNavigationTarget {
    param(
        [string[]]$Files,
        [string]$CurrentFilePath,
        [int]$Direction
    )

    & {
        param($ScriptPath, $TestFiles, $TestCurrentFilePath, $TestDirection)
        . $ScriptPath
        Get-ReviewFileNavigationTarget -Files $TestFiles -CurrentFilePath $TestCurrentFilePath -Direction $TestDirection
    } $global:ReviewNavigationScript $Files $CurrentFilePath $Direction
}

function global:Invoke-ReviewNavigationAction {
    param([ConsoleKeyInfo]$KeyInfo)

    & {
        param($ScriptPath, $TestKeyInfo)
        . $ScriptPath
        Get-ReviewNavigationAction -KeyInfo $TestKeyInfo
    } $global:ReviewNavigationScript $KeyInfo
}

Describe 'Get-ReviewFileNavigationTarget' {
    BeforeAll {
        $script:reviewNavigationRoot = Join-Path ([System.IO.Path]::GetTempPath()) "ReviewNavigation_$(Get-Random)"
        New-Item -ItemType Directory -Path $script:reviewNavigationRoot -Force | Out-Null
        $script:reviewFiles = @(
            (Join-Path $script:reviewNavigationRoot 'first.issues.md'),
            (Join-Path $script:reviewNavigationRoot 'second.issues.md'),
            (Join-Path $script:reviewNavigationRoot 'third.issues.md')
        )
        foreach ($file in $script:reviewFiles) {
            [System.IO.File]::WriteAllText($file, '# test')
        }
    }

    Describe 'Get-ReviewNavigationAction' {
        It 'maps Shift+N and Shift+P to file navigation' {
            $nextFile = [ConsoleKeyInfo]::new('N', [ConsoleKey]::N, $true, $false, $false)
            $previousFile = [ConsoleKeyInfo]::new('P', [ConsoleKey]::P, $true, $false, $false)

            Invoke-ReviewNavigationAction -KeyInfo $nextFile | Should -Be 'next-file'
            Invoke-ReviewNavigationAction -KeyInfo $previousFile | Should -Be 'prev-file'
        }

        It 'maps B, L, and Escape back to the file list' {
            $back = [ConsoleKeyInfo]::new('b', [ConsoleKey]::B, $false, $false, $false)
            $list = [ConsoleKeyInfo]::new('l', [ConsoleKey]::L, $false, $false, $false)
            $escape = [ConsoleKeyInfo]::new([char]27, [ConsoleKey]::Escape, $false, $false, $false)

            Invoke-ReviewNavigationAction -KeyInfo $back | Should -Be 'back-to-list'
            Invoke-ReviewNavigationAction -KeyInfo $list | Should -Be 'back-to-list'
            Invoke-ReviewNavigationAction -KeyInfo $escape | Should -Be 'back-to-list'
        }
    }

    AfterAll {
        Remove-Item -LiteralPath $script:reviewNavigationRoot -Recurse -Force
    }

    It 'returns the next file for uppercase-N navigation' {
        Invoke-ReviewNavigationTarget -Files $script:reviewFiles -CurrentFilePath $script:reviewFiles[0] -Direction 1 |
            Should -Be $script:reviewFiles[1]
    }

    It 'returns the previous file for all-issues navigation' {
        Invoke-ReviewNavigationTarget -Files $script:reviewFiles -CurrentFilePath $script:reviewFiles[1] -Direction -1 |
            Should -Be $script:reviewFiles[0]
    }

    It 'returns nothing at the first and last file boundaries' {
        Invoke-ReviewNavigationTarget -Files $script:reviewFiles -CurrentFilePath $script:reviewFiles[0] -Direction -1 |
            Should -BeNullOrEmpty
        Invoke-ReviewNavigationTarget -Files $script:reviewFiles -CurrentFilePath $script:reviewFiles[2] -Direction 1 |
            Should -BeNullOrEmpty
    }
}
