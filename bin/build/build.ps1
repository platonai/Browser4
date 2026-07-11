#!/usr/bin/env pwsh
# ═══════════════════════════════════════════════════════════════════════
# Browser4 Build Script
# ═══════════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Guard Get-CimInstance / chcp behind platform checks.
# ═══════════════════════════════════════════════════════════════════════

$ErrorActionPreference = "Stop"

$repoRoot = (git rev-parse --show-toplevel 2>$null)
if (-not $repoRoot) {
  Write-Host "Error: not in a git repository" -ForegroundColor Red
  exit 1
}
Set-Location $repoRoot

# Resolve the Maven wrapper for this platform
$MvnwScript = if ($IsWindows) { Join-Path $repoRoot 'mvnw.cmd' }
              else              { Join-Path $repoRoot 'mvnw'     }

# ═══════════════════════════════════════════════════════
# Build-state tracking files (used by --resume and AI diagnosis)
# ═══════════════════════════════════════════════════════
$BuildStateDir = Join-Path $repoRoot ".build-state"
$BuildLogFile = Join-Path $BuildStateDir "build.log"
$LastFailedModuleFile = Join-Path $BuildStateDir "last-failed-module.txt"
$BuildEnvFile = Join-Path $BuildStateDir "build-env.txt"
$BuildErrorFile = Join-Path $BuildStateDir "last-build-error.txt"
$BuildStatusFile = Join-Path $BuildStateDir "build-status.txt"

function Write-TrackedFile {
  param([string]$Path, [string]$Content)
  if (-not (Test-Path $BuildStateDir)) {
    New-Item -ItemType Directory -Path $BuildStateDir -Force | Out-Null
  }
  Set-Content -Path $Path -Value $Content -Encoding UTF8
}

function Read-TrackedFile {
  param([string]$Path)
  if (Test-Path $Path) {
    return (Get-Content -Path $Path -Raw -Encoding UTF8).Trim()
  }
  return $null
}

# ═══════════════════════════════════════════════════════
# System / environment info
# ═══════════════════════════════════════════════════════
function Write-SystemInfo {
  $lines = [System.Collections.ArrayList]::new()

  [void]$lines.Add("")
  [void]$lines.Add("==============================================")
  [void]$lines.Add("  SYSTEM & BUILD ENVIRONMENT")
  [void]$lines.Add("==============================================")
  [void]$lines.Add("")

  # --- OS ---
  if ($IsWindows) {
    $osName = "Windows"
    $osVer = [Environment]::OSVersion.VersionString
  }
  elseif ($IsLinux) {
    $osName = "Linux"
    $osVer = (uname -r 2>$null)
  }
  elseif ($IsMacOS) {
    $osName = "macOS"
    $osVer = (sw_vers -productVersion 2>$null)
  }
  else {
    $osName = "Unknown"
    $osVer = ""
  }
  [void]$lines.Add("[OS]        $osName  $osVer")

  # --- CPU ---
  try {
    if ($IsWindows) {
      $cpuName = ((Get-CimInstance Win32_Processor -ErrorAction Stop).Name -split '\s+')[0..6] -join ' '
      $cpuCores = (Get-CimInstance Win32_ComputerSystem -ErrorAction Stop).NumberOfLogicalProcessors
    }
    elseif ($IsLinux) {
      $cpuName = ((Get-Content /proc/cpuinfo 2>$null | Select-String "model name" | Select-Object -First 1) -replace '.*:\s+', '').Trim()
      $cpuCores = (nproc 2>$null)
    }
    elseif ($IsMacOS) {
      $cpuName = (sysctl -n machdep.cpu.brand_string 2>$null)
      $cpuCores = (sysctl -n hw.logicalcpu 2>$null)
    }
    if (-not $cpuCores) { $cpuCores = [Environment]::ProcessorCount }
    [void]$lines.Add("[CPU]       $cpuName  ($cpuCores logical cores)")
  }
  catch {
    [void]$lines.Add("[CPU]       (unable to query)")
  }

  # --- Memory ---
  try {
    if ($IsWindows) {
      $totalMemGB = [math]::Round((Get-CimInstance Win32_ComputerSystem -ErrorAction Stop).TotalPhysicalMemory / 1GB, 1)
    }
    elseif ($IsLinux) {
      $totalMemKB = [long]((Get-Content /proc/meminfo | Select-String MemTotal).Line -replace '\D')
      $totalMemGB = [math]::Round($totalMemKB / 1MB, 1)
    }
    elseif ($IsMacOS) {
      $totalMemGB = [math]::Round((sysctl -n hw.memsize) / 1GB, 1)
    }
    [void]$lines.Add("[Memory]    ${totalMemGB} GB")
  }
  catch {
    [void]$lines.Add("[Memory]    (unable to query)")
  }

  # --- Disk ---
  try {
    $currentDrive = (Get-Location).Drive.Name
    $diskInfo = Get-PSDrive -Name $currentDrive -ErrorAction Stop
    if ($diskInfo) {
      $freeGB = [math]::Round($diskInfo.Free / 1GB, 1)
      $totalGB = [math]::Round(($diskInfo.Free + $diskInfo.Used) / 1GB, 1)
      [void]$lines.Add("[Disk]      ${totalGB} GB total, ${freeGB} GB free  (drive ${currentDrive}:)")
    }
  }
  catch {
    [void]$lines.Add("[Disk]      (unable to query)")
  }

  # --- Git ---
  $gitVer = (git --version 2>$null) -replace 'git version ', ''
  $gitBranch = (git rev-parse --abbrev-ref HEAD 2>$null)
  $gitCommit = (git rev-parse --short HEAD 2>$null)
  [void]$lines.Add("[Git]       $gitVer  |  branch: $gitBranch  |  commit: $gitCommit")

  # --- Java ---
  try {
    $javaVerOut = java -version 2>&1 | Select-Object -First 1
    [void]$lines.Add("[Java]      $javaVerOut")
  }
  catch {
    [void]$lines.Add("[Java]      NOT FOUND or not on PATH")
  }
  $javaHome = $env:JAVA_HOME
  if ($javaHome) {
    [void]$lines.Add("[JAVA_HOME] $javaHome")
  }

  # --- Maven ---
  try {
    $mvnVer = & $MvnwScript --version 2>&1 | Select-Object -First 2 | ForEach-Object { $_ }
    [void]$lines.Add("[Maven]     $($mvnVer -join '; ')" )
  }
  catch {
    [void]$lines.Add("[Maven]     (unable to query)")
  }

  # --- PowerShell ---
  [void]$lines.Add("[PowerShell] $($PSVersionTable.PSVersion)  Edition: $($PSVersionTable.PSEdition)")

  [void]$lines.Add("")

  Write-Host ($lines -join "`n")
  return $lines -join "`n"
}

function Write-RustInfo {
  $lines = [System.Collections.ArrayList]::new()

  [void]$lines.Add("")
  [void]$lines.Add("==============================================")
  [void]$lines.Add("  RUST / CARGO TOOLCHAIN")
  [void]$lines.Add("==============================================")
  [void]$lines.Add("")

  $cargoCmd = Get-Command cargo -ErrorAction SilentlyContinue
  if ($cargoCmd) {
    [void]$lines.Add("[Cargo]     $(cargo --version 2>$null)")
    [void]$lines.Add("[rustc]     $(rustc --version 2>$null)")
    $toolchain = (rustup show active-toolchain 2>$null)
    if ($toolchain) {
      [void]$lines.Add("[rustup]    active toolchain: $toolchain")
    }
    [void]$lines.Add("[cargo path] $($cargoCmd.Source)")
  }
  else {
    [void]$lines.Add("[Cargo]     NOT INSTALLED — --cli builds will be skipped")
  }
  [void]$lines.Add("")

  Write-Host ($lines -join "`n")
  return $lines -join "`n"
}

# ═══════════════════════════════════════════════════════
# Help
# ═══════════════════════════════════════════════════════
function Write-Help {
  @"

Build script for Browser4 — Maven + optional Rust CLI frontend

Usage:
  build.ps1 [flags]

Flags:
  -main, --all-main-modules     Build with profile `all-main-modules`
  --all-test-modules            Build with profile `all-test-modules`
  --all-modules                 Build with profile `all-modules`
                                (includes -main, --all-test-modules, and examples)
  -test, --test                 Run tests (-DskipTests=false)
  -pl, --projects <arg>         Comma-delimited list of reactor projects
                                to build.  Always builds dependent modules
                                (automatically adds -am).
  --resume                      Resume from the last failed module (-rf)
  -X, --debug                   Maven execution debug output
  --cli                         Build Rust CLI frontend only (skip Maven)
  -ac, --also-cli               Also build the Rust CLI frontend
                                (run after Maven, if Maven is not skipped)
  -clean, --clean               Run `mvn clean` before building
                                (equivalent to mvn clean install ...)
  -h, --help                    Print this help message

Defaults:  skipTests=true   no profiles   no CLI   no debug   no clean

Examples:
  .\bin\build\build.ps1
  .\bin\build\build.ps1 -main -test
  .\bin\build\build.ps1 --all-modules -ac
  .\bin\build\build.ps1 --cli
  .\bin\build\build.ps1 -pl browser4-core,browser4-rest -X
  .\bin\build\build.ps1 --resume
"@
  Write-Host $_
}

# ═══════════════════════════════════════════════════════
# Build-step helpers
# ═══════════════════════════════════════════════════════
function Invoke-MavenBuild {
  param([string]$Directory, [Object[]]$BuildArgs)

  Push-Location $Directory
  try {
    $mvnwName = Split-Path $MvnwScript -Leaf
    $cmdLine = "$mvnwName $($BuildArgs -join ' ')"
    $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"

    Write-Host ""
    Write-Host "[BUILD] $cmdLine" -ForegroundColor Cyan

    # Persist to build log
    Add-Content -Path $BuildLogFile -Value "[$ts] $cmdLine"

    # Capture output to detect currently-building module for --resume tracking
    $currentModule = ""
    $mvnOutput = & $MvnwScript @BuildArgs 2>&1
    $exitCode = $LASTEXITCODE

    # Stream output to console, while tracking "Building ..." lines
    foreach ($line in $mvnOutput) {
      Write-Host $line
      if ($line -match '^\s*\[INFO\]\s+Building\s+(\S+)\s+') {
        $currentModule = $Matches[1]
        Write-TrackedFile -Path $LastFailedModuleFile -Content $currentModule
      }
    }

    if ($exitCode -ne 0) {
      $errMsg = "Maven command failed in $Directory (exit code $exitCode)"
      Add-Content -Path $BuildLogFile -Value "[$ts] FAILED: $errMsg"
      if ($currentModule) {
        Write-TrackedFile -Path $LastFailedModuleFile -Content $currentModule
        Write-Host "[TRACK] Last module before failure: $currentModule" -ForegroundColor Yellow
      }
      throw $errMsg
    }

    Add-Content -Path $BuildLogFile -Value "[$ts] SUCCESS"
    Write-TrackedFile -Path $LastFailedModuleFile -Content ""
  }
  finally {
    Pop-Location
  }
}

function Invoke-CargoBuild {
  param(
    [string]$Directory,
    [bool]$RunTests
  )

  $cargoCmd = Get-Command cargo -ErrorAction SilentlyContinue
  if (-not $cargoCmd) {
    throw "cargo is not installed or not in PATH — cannot build CLI"
  }

  Push-Location $Directory
  try {
    if ($RunTests) {
      Write-Host "[BUILD] cargo test --locked --bin browser4-cli" -ForegroundColor Cyan
      & cargo test --locked --bin browser4-cli
      if ($LASTEXITCODE -ne 0) {
        throw "Cargo test failed in $Directory (exit code $LASTEXITCODE)"
      }
    }

    Write-Host "[BUILD] cargo build --release --locked" -ForegroundColor Cyan
    & cargo build --release --locked
    if ($LASTEXITCODE -ne 0) {
      throw "Cargo build failed in $Directory (exit code $LASTEXITCODE)"
    }
  }
  finally {
    Pop-Location
  }
}

# ═══════════════════════════════════════════════════════
# AI bug-report generation (called on build failure)
# ═══════════════════════════════════════════════════════
function Invoke-BugReportAgent {
  param(
    [Parameter(Mandatory = $true)]
    [string]$BuildSystem,
    [Parameter(Mandatory = $true)]
    [string]$ErrorMessage
  )

  Write-Host ""
  Write-Host "[BUG-REPORT] Generating AI bug report for $BuildSystem failure..." -ForegroundColor Yellow

  $bugReportDir = Join-Path $repoRoot "coworker\tasks\issues\draft"
  if (-not (Test-Path $bugReportDir)) {
    New-Item -ItemType Directory -Path $bugReportDir -Force | Out-Null
  }

  # ── Gather diagnostic context ──────────────────────────────────────────
  $buildLogTail = ""
  if (Test-Path $BuildLogFile) {
    $buildLogTail = (Get-Content -Path $BuildLogFile -Tail 80 -Encoding UTF8 -ErrorAction SilentlyContinue) -join "`n"
  }

  $envSnapshot = ""
  if (Test-Path $BuildEnvFile) {
    $envSnapshot = Get-Content -Path $BuildEnvFile -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
  }

  $lastFailedModule = Read-TrackedFile -Path $LastFailedModuleFile
  $buildStatus = Read-TrackedFile -Path $BuildStatusFile

  # ── AI prompt ──────────────────────────────────────────────────────────
  $prompt = @"
You are a senior QA engineer for the Browser4 project — a web agent / browser automation platform built with Kotlin/JVM (Maven) and Rust (Cargo).

A **$BuildSystem build has FAILED** in CI/local. Analyze the failure and write a concise, actionable bug report in markdown.

## Failure Data

**Build System:** $BuildSystem
**Error Message:** $ErrorMessage
**Last Module Building:** $lastFailedModule
**Build Status:** $buildStatus

### Build Log (tail)
````
$buildLogTail
````

### Environment Snapshot
$envSnapshot

## Instructions

Produce a bug report with these sections (use level-2 ``##`` headings):

1. **Title** — Start with a ``# `` level-1 heading: a short, specific title summarizing the failure.
2. **Severity** — ``Critical`` | ``High`` | ``Medium`` | ``Low`` — with a one-sentence justification.
3. **Summary** — 2-4 sentences: what broke, which module, and the impact.
4. **Error Analysis** — Parse the error message and log. Identify the likely root cause (compilation error, dependency resolution, test failure, OOM, timeout, missing tool, etc.).
5. **Reproduction** — Steps another developer can follow to reproduce.
6. **Suggested Fix** — Concrete next step: revert a commit, fix a dependency version, add missing config, run a diagnostic command, etc.
7. **Environment** — Distill the relevant parts of the environment snapshot (OS, Java version, Cargo version, git branch/commit).

**Output ONLY the markdown bug report — no conversational text, no tool-call logs, no prefixes.**
"@

  $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
  $safeName = $BuildSystem.ToLower() -replace '[^a-z0-9]', '-'
  $outputFile = Join-Path $bugReportDir "${timestamp}-build-failure-${safeName}.md"

  # ── Try AI agent via coworker infrastructure ───────────────────────────
  $agentUsed = $false
  try {
    $agentScript = Join-Path $repoRoot "coworker\scripts\workers\agent.ps1"
    $configScript = Join-Path $repoRoot "coworker\scripts\config.ps1"

    if ((Test-Path $agentScript) -and (Test-Path $configScript)) {
      . $configScript
      . $agentScript

      $result = Invoke-Agent -Prompt $prompt -CaptureOutput -WorkingDirectory $repoRoot -TimeoutSeconds 300

      if ($result) {
        # Strip tool-call log lines and conversational noise
        $cleaned = ($result -replace '(?m)^\s*[●│└✗○◉◈▸▪▹]\s*.*$', '') -replace '(?m)^\s*\d+\s*│\s*.*$', ''
        $cleaned = ($cleaned -split "`n" | Where-Object { $_.Trim() -ne "" }) -join "`n"
        $cleaned = $cleaned.Trim()

        if ($cleaned) {
          $cleaned | Out-File -FilePath $outputFile -Encoding UTF8
          $agentUsed = $true
          Write-Host "[BUG-REPORT] AI-generated report: $outputFile" -ForegroundColor Green
        }
      }
    }
  }
  catch {
    Write-Host "[BUG-REPORT] AI agent unavailable: $_" -ForegroundColor DarkYellow
  }

  # ── Fallback: template-based report when AI is not available ───────────
  if (-not $agentUsed) {
    $fallback = @"
# Build Failure: $BuildSystem — $lastFailedModule

**Time:** $(Get-Date -Format "yyyy-MM-dd HH:mm:ss")
**Severity:** High *(auto-generated — AI analysis unavailable)*
**Build System:** $BuildSystem

## Summary
The $BuildSystem build failed on module ``$lastFailedModule``. The error requires manual investigation.

## Error Message
````
$ErrorMessage
````

## Build Log (tail)
````
$buildLogTail
````

## Environment
$envSnapshot

## Reproduction
Run ``.\bin\build\build.ps1`` with the same flags that triggered this failure.

## Suggested Fix
1. Review the error message and build log above.
2. Compare with the last successful build for differences.
3. Run the failing module in isolation to narrow the cause.

---
*(Fallback template — AI agent was not available to analyze the failure.)*
"@
    $fallback | Out-File -FilePath $outputFile -Encoding UTF8
    Write-Host "[BUG-REPORT] Fallback report: $outputFile" -ForegroundColor Yellow
  }

  return $outputFile
}

# ═══════════════════════════════════════════════════════
# Argument parsing
# ═══════════════════════════════════════════════════════
$ShowHelp = $false
$ProfileAllMain = $false
$ProfileAllTest = $false
$ProfileAllModules = $false
$SkipTests = $true
$ResumeFromFailure = $false
$DebugMode = $false
$CliOnly = $false
$AlsoCli = $false
$RunClean = $false
$Projects = $null
$AdditionalMvnArgs = @()

$i = 0
while ($i -lt $args.Count) {
  $arg = $args[$i]
  switch -Wildcard ($arg) {
    '-main'                 { $ProfileAllMain = $true }
    '--all-main-modules'    { $ProfileAllMain = $true }
    '--all-test-modules'    { $ProfileAllTest = $true }
    '--all-modules'         { $ProfileAllModules = $true }
    '-test'                 { $SkipTests = $false }
    '--test'                { $SkipTests = $false }
    '-pl'                   {
      $i++
      if ($i -lt $args.Count) { $Projects = $args[$i] }
      else { Write-Host "Error: --projects requires a value" -ForegroundColor Red; Write-Help; exit 1 }
    }
    '--projects'            {
      $i++
      if ($i -lt $args.Count) { $Projects = $args[$i] }
      else { Write-Host "Error: --projects requires a value" -ForegroundColor Red; Write-Help; exit 1 }
    }
    '--resume'              { $ResumeFromFailure = $true }
    '-X'                    { $DebugMode = $true }
    '--debug'               { $DebugMode = $true }
    '--cli'                 { $CliOnly = $true }
    '-ac'                   { $AlsoCli = $true }
    '--also-cli'            { $AlsoCli = $true }
    '-clean'                { $RunClean = $true }
    '--clean'               { $RunClean = $true }
    '-h'                    { $ShowHelp = $true }
    '--help'                { $ShowHelp = $true }
    default {
      if ($arg -match '^-') {
        Write-Host "Unknown flag: $arg" -ForegroundColor Red
        Write-Help
        exit 1
      }
      $AdditionalMvnArgs += $arg
    }
  }
  $i++
}

# --- Help / no-args → print info + help ---
if ($ShowHelp) {
  [void](Write-SystemInfo)
  [void](Write-RustInfo)
  Write-Help
  exit 0
}

if ($args.Count -eq 0) {
  [void](Write-SystemInfo)
  [void](Write-RustInfo)
  Write-Help
  exit 0
}

# ═══════════════════════════════════════════════════════
# Print environment info
# ═══════════════════════════════════════════════════════
$sysInfo = Write-SystemInfo
$rustInfo = ""
if ($CliOnly -or $AlsoCli) {
  $rustInfo = Write-RustInfo
}

# --- Init build-state directory ---
if (-not (Test-Path $BuildStateDir)) {
  New-Item -ItemType Directory -Path $BuildStateDir -Force | Out-Null
}

# --- Start build log ---
$ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
$sep = "=" * 60
@"
$sep
Build started: $ts
$sep
"@ | Add-Content -Path $BuildLogFile

# --- Write environment snapshot for AI diagnosis ---
$envSnapshot = $sysInfo
if ($rustInfo) { $envSnapshot += "`n$rustInfo" }
Write-TrackedFile -Path $BuildEnvFile -Content $envSnapshot

# --- Write initial status ---
Write-TrackedFile -Path $BuildStatusFile -Content "IN_PROGRESS started=$ts"

# ═══════════════════════════════════════════════════════
# Assemble Maven options
# ═══════════════════════════════════════════════════════
$MvnOptions = [System.Collections.ArrayList]::new()

# --- Profiles ---
$Profiles = [System.Collections.ArrayList]::new()
if ($ProfileAllModules) {
  [void]$Profiles.Add('all-modules')
}
else {
  if ($ProfileAllMain) { [void]$Profiles.Add('all-main-modules') }
  if ($ProfileAllTest) { [void]$Profiles.Add('all-test-modules') }
}
if ($Profiles.Count -gt 0) {
  [void]$MvnOptions.Add('-P' + ($Profiles -join ','))
}

# --- Tests ---
if ($SkipTests) {
  [void]$MvnOptions.Add('-DskipTests')
}

# --- Debug ---
if ($DebugMode) {
  [void]$MvnOptions.Add('-X')
}

# --- Specific projects ---
if ($Projects) {
  [void]$MvnOptions.Add('-pl')
  [void]$MvnOptions.Add($Projects)
  [void]$MvnOptions.Add('-am')
}

# --- Resume ---
if ($ResumeFromFailure) {
  $lastFailed = Read-TrackedFile -Path $LastFailedModuleFile
  if ($lastFailed) {
    Write-Host "[RESUME] Resuming from module: $lastFailed" -ForegroundColor Yellow
    [void]$MvnOptions.Add('-rf')
    [void]$MvnOptions.Add($lastFailed)
  }
  else {
    Write-Host "[RESUME] No previous failure recorded — building from the start" -ForegroundColor Yellow
  }
}

# --- Goal ---
if ($RunClean) {
  [void]$MvnOptions.Add('clean')
}
[void]$MvnOptions.Add('install')

# --- User-passed extra args ---
if ($AdditionalMvnArgs.Count -gt 0) {
  [void]$MvnOptions.AddRange($AdditionalMvnArgs)
}

# Always clean browser4-bundle and browser4-standalone target dirs —
# they aggregate other modules and their output becomes stale on every build.
$StaleTargets = @(
  (Join-Path $repoRoot 'browser4-apps\browser4-bundle\target'),
  (Join-Path $repoRoot 'browser4-apps\browser4-standalone\target')
)
foreach ($TargetDir in $StaleTargets) {
  if (Test-Path $TargetDir) {
    Write-Host "Cleaning stale target: $TargetDir"
    Remove-Item -Recurse -Force $TargetDir
  }
}

# ═══════════════════════════════════════════════════════
# Execute build
# ═══════════════════════════════════════════════════════
try {
  if (-not $CliOnly) {
    $CurrentBuildSystem = "Maven"
    Invoke-MavenBuild -Directory $repoRoot -BuildArgs $MvnOptions.ToArray()
  }

  if ($CliOnly -or $AlsoCli) {
    $CurrentBuildSystem = "Cargo"
    Invoke-CargoBuild -Directory (Join-Path $repoRoot 'cli\browser4-cli') -RunTests (-not $SkipTests)
  }

  # --- Success ---
  $doneTs = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
  Write-TrackedFile -Path $BuildStatusFile -Content "SUCCESS completed=$doneTs"
  Write-TrackedFile -Path $LastFailedModuleFile -Content ""
  Remove-Item -Path $BuildErrorFile -ErrorAction SilentlyContinue

  Write-Host ""
  Write-Host "Build completed successfully at $doneTs" -ForegroundColor Green
}
catch {
  $failTs = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
  Write-TrackedFile -Path $BuildStatusFile -Content "FAILED time=$failTs"
  Write-TrackedFile -Path $BuildErrorFile -Content $_.Exception.Message

  Write-Host ""
  Write-Host "Build FAILED at $failTs" -ForegroundColor Red
  Write-Host "  Error : $_" -ForegroundColor Red
  Write-Host "  Logs  : $BuildLogFile" -ForegroundColor DarkGray
  Write-Host "  State : $BuildStateDir\" -ForegroundColor DarkGray
  Write-Host "  Re-run with --resume to continue from the failing module" -ForegroundColor DarkGray

  # ── Trigger AI bug-report generation ───────────────────────────────────
  $buildSystem = if ($CurrentBuildSystem) { $CurrentBuildSystem } else { "Unknown" }
  $bugReportPath = Invoke-BugReportAgent -BuildSystem $buildSystem -ErrorMessage $_.Exception.Message
  if ($bugReportPath) {
    Write-Host "  Bug Report : $bugReportPath" -ForegroundColor DarkGray
  }

  exit 1
}
