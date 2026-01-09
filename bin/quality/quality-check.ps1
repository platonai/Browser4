Param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $Root

$Exempt = @('pulsar-benchmarks','examples/browser4-examples','pulsar-bom','browser4')

$modules = Select-String -Path pom.xml -Pattern '<module>([^<]+)</module>' -AllMatches | ForEach-Object { $_.Matches } | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique

$rows = @()
$fail = 0
foreach ($m in $modules) {
    if (-not (Test-Path $m)) { continue }
    $testRoot = Join-Path $m 'src/test'
    $tests = 0; $its = 0; $e2e = 0
    if (Test-Path $testRoot) {
        $tests = @(Get-ChildItem -Recurse -Include *Test.kt,*Test.java -Path $testRoot -ErrorAction SilentlyContinue) .Count
        $its   = @(Get-ChildItem -Recurse -Include *IT.kt,*IT.java -Path $testRoot -ErrorAction SilentlyContinue) .Count
        $e2e   = @(Select-String -Path (Join-Path $testRoot '*.*') -Pattern '@Tag\("E2ETest"\)' -ErrorAction SilentlyContinue) .Count
    }
    $status = 'OK'; $note = ''
    if ($tests -eq 0 -and $its -eq 0 -and $e2e -eq 0) {
        if ($Exempt -contains $m) { $status='SKIP'; $note='exempt' } else { $status='MISSING'; $note='no tests'; $fail=1 }
    }
    $rows += [PSCustomObject]@{ Module=$m; Tests=$tests; ITs=$its; E2E=$e2e; Status=$status; Notes=$note }
}

$rows | Format-Table -AutoSize
if ($fail -ne 0) { Write-Error 'One or more non-exempt modules lack tests.'; exit 2 } else { Write-Host 'No blocking issues detected.' }

